package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.camp.CampFarmManager;
import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampSchedule;
import com.phian404.survivalpoints.camp.FarmWorkStation;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import com.phian404.survivalpoints.entity.ai.crop.CropAdapter;
import com.phian404.survivalpoints.entity.ai.task.NpcTask;
import com.phian404.survivalpoints.entity.ai.task.NpcTaskController;
import com.phian404.survivalpoints.entity.role.SurvivorProfession;
import com.phian404.survivalpoints.entity.role.SurvivorRole;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Работа фермера «со станции».
 *
 * <p>Вместо перехода к каждой грядке фермер один раз выбирает рабочую позицию
 * ({@link FarmWorkStation}), подходит к ней и обслуживает все грядки в радиусе,
 * поворачивая голову. Сессия — это один тип работы: сначала сбор всего
 * созревшего, затем отдельная сессия посадки. Если в радиусе станции грядки
 * кончились, но работа того же типа ещё есть в лагере, станция пересчитывается
 * (не более {@link #MAX_RELOCATIONS} переносов за сессию).
 *
 * <p>Порядок обхода задаёт этот Goal; диспетчер {@link CampFarmManager} только
 * подтверждает эксклюзивность конкретной грядки ({@code takeAt}) и ведёт
 * cooldown отказавших грядок.
 */
public class FarmerStandWorkGoal extends Goal {

    public enum State {
        IDLE, MOVING, WORKING
    }

    private static final int PAUSE_MIN = 3;
    private static final int PAUSE_MAX = 6;
    private static final int RETRY_PAUSE = 20;
    /**
     * Сколько тиков ждать недоступную по cooldown/резервации работу, прежде
     * чем свернуть сессию.
     */
    private static final int SESSION_IDLE_TIMEOUT = 80;
    /** Сколько тиков идти к станции, прежде чем признать маршрут неудачным. */
    private static final int MOVE_TIMEOUT = 200;
    /**
     * Сколько тиков подряд разрешено не находить путь к станции. Отказ на один
     * тик — обычное дело (NPC ещё падает на опору после установки), и рвать
     * из-за него сессию значит открывать её заново каждые пару тиков.
     */
    private static final int PATH_FAILURES_MAX = 10;
    /** Сколько тиков NPC подруливает в клетку станции, прежде чем бросить грядку. */
    private static final int POSITION_FIX_MAX = 60;
    private static final int MAX_RELOCATIONS = 2;
    private static final int MAX_FACING_TICKS = 60;
    private static final int END_LOOK_TICKS = 10;
    private static final float LOOK_TOLERANCE = 12.0F;
    private static final double SPEED = 1.0D;
    private static final double ARRIVE_SQR = 2.25D;
    private static final double ARRIVE_DY = 1.5D;

    private final BaseSurvivorEntity npc;

    private State state = State.IDLE;
    private int pauseTicks;
    @Nullable
    private BlockPos pauseLookTarget;
    @Nullable
    private BlockPos lastWorkedPlot;

    private boolean sessionActive;
    @Nullable
    private String sessionType;
    private int sessionIdleTicks;
    private int sessionStarts;
    /** Семя взято из инвентаря: в конце сессии его нужно вернуть обратно. */
    private boolean seedFromInventory;

    @Nullable
    private BlockPos stand;
    private final List<BlockPos> planned = new ArrayList<>();
    @Nullable
    private NpcTask currentTask;
    @Nullable
    private BlockPos currentPlot;
    private int moveTicks;
    private int pathFailures;
    private int relocations;
    private int facingTicks;
    private int positionFixTicks;

    public FarmerStandWorkGoal(BaseSurvivorEntity npc) {
        this.npc = npc;
        setFlags(java.util.EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    public State getState() {
        return this.state;
    }

    public boolean isSessionActive() {
        return this.sessionActive;
    }

    public int getSessionStarts() {
        return this.sessionStarts;
    }

    /** Станция текущей сессии; для отладки и тестов. */
    @Nullable
    public BlockPos getStand() {
        return this.stand;
    }

    /** Размер плана текущей сессии; для отладки и тестов. */
    public int getPlannedCount() {
        return this.planned.size();
    }

    @Nullable
    public String getSessionType() {
        return this.sessionType;
    }

    // ==================== жизненный цикл ====================

    @Override
    public boolean canUse() {
        if (this.pauseTicks > 0) {
            this.pauseTicks--;
            return false;
        }
        ServerLevel level = serverLevel();
        if (level == null || !isFarmer()) {
            return false;
        }
        // Работа — только днём: вечером и ночью житель отдыхает.
        if (!CampSchedule.of(level).isWorkTime()) {
            return false;
        }
        CampRecord camp = camp(level);
        if (camp == null) {
            return false;
        }
        NpcTaskController controller = CampManager.taskController(this.npc.getIdentityId());
        if (controller.current() != null) {
            return false;
        }
        Plan plan = plan(level, camp, CampManager.farmManager(camp), null);
        if (plan == null) {
            long now = level.getGameTime();
            if (now % 40L == 0L) {
                CampManager.farmManager(camp).refresh(level, camp, now);
                plan = plan(level, camp, CampManager.farmManager(camp), null);
            }
            if (plan == null) {
                return false;
            }
        }
        begin(level, camp, plan);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (serverLevel() == null || !isFarmer()) {
            return false;
        }
        // Вечер и ночь заканчивают сессию штатно: stop() вернёт инструмент в
        // инвентарь, ничего не теряя.
        if (!CampSchedule.of(serverLevel()).isWorkTime()) {
            return false;
        }
        if (this.pauseLookTarget != null) {
            return true;
        }
        return this.sessionActive;
    }

    @Override
    public void start() {
        this.facingTicks = 0;
    }

    @Override
    public void tick() {
        if (this.pauseTicks > 0) {
            this.tickPause();
            return;
        }
        ServerLevel level = serverLevel();
        if (level == null) {
            endSession(false);
            return;
        }
        CampRecord camp = camp(level);
        if (camp == null) {
            endSession(false);
            return;
        }
        switch (this.state) {
            case MOVING -> tickMove(level, camp);
            case WORKING -> tickWork(level, camp);
            default -> {
            }
        }
    }

    @Override
    public void stop() {
        ServerLevel level = serverLevel();
        if (level != null) {
            releaseActive(level);
        }
        this.npc.getNavigation().stop();
        returnSeed();
        this.state = State.IDLE;
        this.sessionActive = false;
        this.sessionType = null;
        this.sessionIdleTicks = 0;
        this.stand = null;
        this.planned.clear();
        this.currentTask = null;
        this.currentPlot = null;
        this.moveTicks = 0;
        this.relocations = 0;
        this.facingTicks = 0;
        this.pauseLookTarget = null;
        this.pauseTicks = 0;
        NpcLookHelper.releaseGaze(this.npc);
    }

    /**
     * Взгляд на центр блока. Плавность обеспечивает сама сущность
     * ({@code BaseSurvivorNpcEntity#aimGaze}): она ведёт голову каждый тик, так
     * что здесь достаточно назвать цель.
     */
    private void lookAt(BlockPos target) {
        NpcLookHelper.lookAt(this.npc,
                target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
    }

    /** Взгляд на грядку (культуру сверху); без грядки взгляд остаётся прежним. */
    private void lookAtPlot(@Nullable BlockPos plot) {
        if (plot != null) {
            lookAt(plot.above());
        }
    }

    // ==================== фазы ====================

    private void tickPause() {
        this.pauseTicks--;
        this.npc.getNavigation().stop();
        // Цель взгляда нужна каждый тик: пустой pauseLookTarget (например,
        // после неудачной задачи) иначе оставляет голову без цели и она
        // уходит к телу. Своей позиции в качестве цели нет — на неё смотреть
        // значит задрать голову вниз.
        BlockPos target = this.pauseLookTarget != null ? this.pauseLookTarget : this.lastWorkedPlot;
        lookAtPlot(target);
        if (this.pauseTicks == 0) {
            this.pauseLookTarget = null;
        }
    }

    /**
     * Взгляд на доступную грядку станции, иначе — на последнюю обработанную.
     * Точка «под ногами» не годится: при подходе к станции азимут на неё
     * скачет, и голова крутится туда-сюда вместе с телом.
     */
    private void lookAtStationWork(ServerLevel level) {
        BlockPos station = this.stand;
        if (station != null) {
            for (BlockPos plot : this.planned) {
                if (FarmWorkStation.canWorkFrom(level, station, plot)) {
                    lookAt(plot.above());
                    return;
                }
            }
        }
        lookAtPlot(this.lastWorkedPlot);
    }

    private void tickMove(ServerLevel level, CampRecord camp) {
        this.moveTicks++;
        BlockPos station = this.stand;
        if (station == null) {
            endSession(false);
            return;
        }
        lookAtStationWork(level);

        if (arrived()) {
            this.npc.getNavigation().stop();
            this.state = State.WORKING;
            this.moveTicks = 0;
            this.facingTicks = 0;
            this.sessionIdleTicks = 0;
            return;
        }
        if (this.npc.getNavigation().isDone()) {
            if (goTo(station)) {
                this.pathFailures = 0;
            } else if (++this.pathFailures >= PATH_FAILURES_MAX) {
                endSession(true);
                return;
            }
        }
        if (this.moveTicks > MOVE_TIMEOUT) {
            endSession(true);
        }
    }

    private void tickWork(ServerLevel level, CampRecord camp) {
        if (this.currentTask == null) {
            if (!pickNext(level, camp)) {
                lookAtStationWork(level);
                if (!continueOrRelocate(level, camp)) {
                    endSession(true);
                }
            }
            return;
        }
        BlockPos plot = this.currentPlot;
        if (plot == null) {
            dropCurrent(level, camp);
            return;
        }
        // Покрытие и видимость посчитаны из клетки станции. Если NPC стоит рядом,
        // но не в ней, сначала пробуем встать в клетку: pathfinder доводит лишь
        // до соседней, и работа из неё не прошла бы проверку.
        if (!FarmWorkStation.canWorkFrom(level, this.npc.blockPosition(), plot)) {
            if (++this.positionFixTicks > POSITION_FIX_MAX) {
                // Грядку нужно убрать из плана и наказать cooldown'ом, иначе
                // pickNext возьмёт её снова по станции и цикл повторится.
                this.positionFixTicks = 0;
                failCurrent(level, camp);
                return;
            }
            BlockPos station = this.stand;
            if (station != null) {
                this.npc.getNavigation().stop();
                this.npc.getMoveControl().setWantedPosition(
                        station.getX() + 0.5D, station.getY(), station.getZ() + 0.5D, SPEED);
            }
            return;
        }
        this.positionFixTicks = 0;
        if (!isActionable(level, this.currentTask, plot)) {
            dropCurrent(level, camp);
            return;
        }
        BlockPos crop = plot.above();
        lookAt(crop);
        if (!NpcLookHelper.isFacingBlock(this.npc, crop, LOOK_TOLERANCE)) {
            this.facingTicks++;
            if (this.facingTicks > MAX_FACING_TICKS) {
                failCurrent(level, camp);
            }
            return;
        }
        if (perform(level, this.currentTask, plot)) {
            succeed(level, camp, plot);
        } else {
            failCurrent(level, camp);
        }
    }

    // ==================== планирование ====================

    /** Сессия: тип работы, грядки и станция. */
    private record Plan(String type, List<BlockPos> targets, BlockPos stand) {
    }

    @Nullable
    private Plan plan(ServerLevel level, CampRecord camp, CampFarmManager manager,
                      @Nullable String onlyType) {
        long gameTime = level.getGameTime();
        if (onlyType != null) {
            List<BlockPos> targets = manager.availableTargets(level, camp, this.npc, gameTime, onlyType);
            return targets.isEmpty() ? null : planOf(level, camp, onlyType, targets);
        }
        List<BlockPos> harvest = manager.availableTargets(level, camp, this.npc, gameTime,
                CampFarmManager.TYPE_HARVEST);
        if (!harvest.isEmpty()) {
            return planOf(level, camp, CampFarmManager.TYPE_HARVEST, harvest);
        }
        List<BlockPos> plant = manager.availableTargets(level, camp, this.npc, gameTime,
                CampFarmManager.TYPE_PLANT);
        if (plant.isEmpty()) {
            return null;
        }
        // Зрелая грядка ждёт cooldown после неудачи: сначала досбор, потом посадка.
        if (manager.hasMaturePlot(level, camp)) {
            return null;
        }
        return planOf(level, camp, CampFarmManager.TYPE_PLANT, plant);
    }

    @Nullable
    private Plan planOf(ServerLevel level, CampRecord camp, String type, List<BlockPos> targets) {
        BlockPos station = FarmWorkStation.best(level, camp, targets, this.npc.blockPosition(),
                this.npc.getBbWidth(), this.npc.getBbHeight());
        if (station == null) {
            return null;
        }
        List<BlockPos> workable = new java.util.ArrayList<>();
        for (BlockPos plot : targets) {
            if (FarmWorkStation.canWorkFrom(level, station, plot)) {
                workable.add(plot);
            }
        }
        if (workable.isEmpty()) {
            return null;
        }
        return new Plan(type, List.copyOf(workable), station);
    }

    private void begin(ServerLevel level, CampRecord camp, Plan plan) {
        this.state = State.MOVING;
        this.sessionActive = true;
        this.sessionType = plan.type();
        if (CampFarmManager.TYPE_PLANT.equals(plan.type())) {
            // Семя в руке с начала сессии: NPC идёт к грядкам уже с ним.
            ensureSeedInHand();
        }
        this.sessionStarts++;
        this.sessionIdleTicks = 0;
        this.relocations = 0;
        this.moveTicks = 0;
        this.pathFailures = 0;
        this.facingTicks = 0;
        this.currentTask = null;
        this.currentPlot = null;
        this.stand = plan.stand();
        this.planned.clear();
        this.planned.addAll(FarmWorkStation.orderedFrom(plan.stand(), plan.targets(),
                this.npc.getYRot()));
        goTo(plan.stand());
    }

    // ==================== выбор грядки ====================

    private boolean pickNext(ServerLevel level, CampRecord camp) {
        BlockPos station = this.stand;
        if (station == null) {
            return false;
        }
        CampFarmManager manager = CampManager.farmManager(camp);
        for (BlockPos plot : List.copyOf(this.planned)) {
            if (!FarmWorkStation.canWorkFrom(level, station, plot)) {
                continue;
            }
            NpcTask task = manager.takeAt(level, camp, this.npc, this.npc.getIdentityId(),
                    level.getGameTime(), this.sessionType, plot);
            if (task == null) {
                continue;
            }
            // Задача живёт в контроллере NPC: при прерывании или остановке
            // резервация снимется даже без участия этого goal.
            if (!CampManager.taskController(this.npc.getIdentityId())
                    .tryStart(task, () -> manager.release(task.getId()))) {
                manager.release(task.getId());
                continue;
            }
            this.currentTask = task;
            this.currentPlot = plot;
            this.sessionIdleTicks = 0;
            this.facingTicks = 0;
            return true;
        }
        return false;
    }

    /**
     * Работа со станции закончилась.
     *
     * @return true, если сессия продолжается (перенос станции, ожидание cooldown
     *         или переход к посадке после сбора)
     */
    private boolean continueOrRelocate(ServerLevel level, CampRecord camp) {
        this.sessionIdleTicks++;
        CampFarmManager manager = CampManager.farmManager(camp);
        List<BlockPos> remaining = manager.availableTargets(level, camp, this.npc,
                level.getGameTime(), this.sessionType);
        if (!remaining.isEmpty()) {
            if (this.relocations < MAX_RELOCATIONS) {
                BlockPos next = FarmWorkStation.best(level, camp, remaining,
                        this.npc.blockPosition(), this.npc.getBbWidth(), this.npc.getBbHeight());
                if (next != null && !next.equals(this.stand)) {
                    this.relocations++;
                    this.stand = next;
                    this.planned.clear();
                    this.planned.addAll(FarmWorkStation.orderedFrom(next, remaining,
                            this.npc.getYRot()));
                    this.state = State.MOVING;
                    this.moveTicks = 0;
                    goTo(next);
                    return true;
                }
                // Лучшая станция — текущая, но часть грядок с неё недостижима:
                // переносимся к станции, которая кроет именно их.
                List<BlockPos> uncovered = new ArrayList<>();
                for (BlockPos plot : remaining) {
                    if (!FarmWorkStation.canWorkFrom(level, this.stand, plot)) {
                        uncovered.add(plot);
                    }
                }
                if (!uncovered.isEmpty()) {
                    BlockPos detour = FarmWorkStation.best(level, camp, uncovered,
                            this.npc.blockPosition(), this.npc.getBbWidth(),
                            this.npc.getBbHeight());
                    if (detour != null && !detour.equals(this.stand)) {
                        this.relocations++;
                        this.stand = detour;
                        this.planned.clear();
                        this.planned.addAll(FarmWorkStation.orderedFrom(detour, uncovered,
                                this.npc.getYRot()));
                        this.state = State.MOVING;
                        this.moveTicks = 0;
                        goTo(detour);
                        return true;
                    }
                }
            }
            // Всё, что осталось, ждёт cooldown или чужой резервации.
            return this.sessionIdleTicks < SESSION_IDLE_TIMEOUT;
        }
        // Сбор закончен — сразу начинаем отдельную сессию посадки.
        if (CampFarmManager.TYPE_HARVEST.equals(this.sessionType)) {
            // Пока в лагере есть зрелая культура (пусть даже её сбор ждёт
            // cooldown после неудачи или она выпала из плана), посадка поверх
            // неё начинаться не должна.
            if (hasMaturePlanned(level) || manager.hasMaturePlot(level, camp)) {
                return this.sessionIdleTicks < SESSION_IDLE_TIMEOUT;
            }
            Plan nextPlan = plan(level, camp, manager, CampFarmManager.TYPE_PLANT);
            if (nextPlan != null) {
                begin(level, camp, nextPlan);
                return true;
            }
        }
        return false;
    }

    /** В плане осталась зрелая культура, снять которую пока нельзя (cooldown, резерв). */
    private boolean hasMaturePlanned(ServerLevel level) {
        for (BlockPos plot : this.planned) {
            BlockState crop = level.getBlockState(plot.above());
            if (crop.getBlock() instanceof CropBlock cropBlock && cropBlock.isMaxAge(crop)) {
                return true;
            }
        }
        return false;
    }

    // ==================== действия ====================

    private void succeed(ServerLevel level, CampRecord camp, BlockPos plot) {
        NpcTask task = this.currentTask;
        if (task != null) {
            CampFarmManager manager = CampManager.farmManager(camp);
            manager.markSucceeded(task.getId());
            manager.refreshPlot(level, camp, plot, level.getGameTime());
        }
        CampManager.taskController(this.npc.getIdentityId()).complete();
        this.planned.remove(plot);
        this.currentTask = null;
        this.currentPlot = null;
        this.facingTicks = 0;
        this.sessionIdleTicks = 0;
        this.lastWorkedPlot = plot;
        this.pauseLookTarget = plot;
        this.pauseTicks = PAUSE_MIN + this.npc.getRandom().nextInt(PAUSE_MAX - PAUSE_MIN + 1);
    }

    private void dropCurrent(ServerLevel level, CampRecord camp) {
        releaseCurrent(level, camp);
        this.currentTask = null;
        this.currentPlot = null;
        this.facingTicks = 0;
    }

    private void failCurrent(ServerLevel level, CampRecord camp) {
        NpcTask task = this.currentTask;
        if (task != null) {
            CampFarmManager manager = CampManager.farmManager(camp);
            manager.markFailed(task.getId(), level.getGameTime());
        }
        if (this.currentPlot != null) {
            this.planned.remove(this.currentPlot);
        }
        CampManager.taskController(this.npc.getIdentityId()).cancel();
        this.currentTask = null;
        this.currentPlot = null;
        this.facingTicks = 0;
        this.pauseTicks = RETRY_PAUSE;
        this.pauseLookTarget = null;
    }

    private void releaseCurrent(ServerLevel level, CampRecord camp) {
        CampManager.taskController(this.npc.getIdentityId()).cancel();
        NpcTask task = this.currentTask;
        if (task != null) {
            CampManager.farmManager(camp).release(task.getId());
        }
    }

    private void releaseActive(ServerLevel level) {
        CampRecord camp = camp(level);
        NpcTaskController controller = CampManager.taskController(this.npc.getIdentityId());
        NpcTask active = controller.current();
        if (camp != null && active != null) {
            CampManager.farmManager(camp).release(active.getId());
        }
        controller.cancel();
    }

    private void endSession(boolean lookAtLast) {
        ServerLevel level = serverLevel();
        if (level != null) {
            releaseActive(level);
        }
        this.npc.getNavigation().stop();
        returnSeed();
        this.sessionActive = false;
        this.sessionType = null;
        this.sessionIdleTicks = 0;
        this.state = State.IDLE;
        this.stand = null;
        this.planned.clear();
        this.currentTask = null;
        this.currentPlot = null;
        this.moveTicks = 0;
        this.relocations = 0;
        this.facingTicks = 0;
        if (lookAtLast && this.lastWorkedPlot != null) {
            this.pauseLookTarget = this.lastWorkedPlot;
            this.pauseTicks = END_LOOK_TICKS + this.npc.getRandom().nextInt(6);
        } else {
            this.pauseLookTarget = null;
            this.pauseTicks = RETRY_PAUSE;
        }
    }

    private boolean perform(ServerLevel level, NpcTask task, BlockPos plot) {
        if (!level.isLoaded(plot)) {
            return false;
        }
        BlockPos cropPos = plot.above();
        BlockState state = level.getBlockState(cropPos);
        CropAdapter adapter = CropAdapter.find(state);
        if (CampFarmManager.TYPE_HARVEST.equals(task.getType())) {
            if (adapter == null || !adapter.isMature(state)) {
                return false;
            }
            return harvest(level, cropPos, state, adapter);
        }
        if (CampFarmManager.TYPE_PLANT.equals(task.getType())) {
            if (!state.isAir() || !level.getBlockState(plot).is(Blocks.FARMLAND)) {
                return false;
            }
            return ensureSeedInHand() && plant(level, cropPos);
        }
        return false;
    }

    private boolean isActionable(ServerLevel level, NpcTask task, BlockPos plot) {
        if (!level.isLoaded(plot)) {
            return false;
        }
        BlockState state = level.getBlockState(plot.above());
        if (CampFarmManager.TYPE_HARVEST.equals(task.getType())) {
            CropAdapter adapter = CropAdapter.find(state);
            return adapter != null && adapter.isMature(state);
        }
        if (CampFarmManager.TYPE_PLANT.equals(task.getType())) {
            return state.isAir() && level.getBlockState(plot).is(Blocks.FARMLAND);
        }
        return false;
    }

    private boolean harvest(ServerLevel level, BlockPos pos, BlockState state, CropAdapter adapter) {
        List<ItemStack> drops = adapter.getDrops(level, pos, state);
        if (drops.isEmpty() || !fitsInventory(drops)) {
            return false;
        }
        if (!level.destroyBlock(pos, false)) {
            return false;
        }
        for (ItemStack drop : drops) {
            this.npc.addItem(drop.copy());
        }
        this.npc.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    private boolean plant(ServerLevel level, BlockPos pos) {
        ItemStack seed = this.npc.getMainHandItem();
        if (seed.isEmpty()) {
            return false;
        }
        CropAdapter adapter = CropAdapter.find(seed);
        BlockState crop = adapter == null ? null : adapter.getCropForSeed(seed);
        if (adapter == null || crop == null || !adapter.plant(level, pos, crop)) {
            return false;
        }
        // Жест — с семенем в руке: списываем его уже после замаха, иначе
        // анимация играет пустой рукой.
        NpcItemUse.act(this.npc, SoundEvents.CROP_PLANTED, 0.6F);
        ItemStack remaining = seed.copy();
        remaining.shrink(1);
        NpcItemUse.hold(this.npc, remaining);
        return true;
    }

    /**
     * Семя в руке: существующую экипировку не перезаписываем, из инвентаря
     * берём ровно один предмет — он расходуется посадкой либо возвращается в
     * конце сессии.
     */
    private boolean ensureSeedInHand() {
        ItemStack held = this.npc.getMainHandItem();
        if (!held.isEmpty()) {
            return CropAdapter.find(held) != null;
        }
        if (!NpcItemUse.takeFromInventory(this.npc, stack -> CropAdapter.find(stack) != null)) {
            return false;
        }
        this.seedFromInventory = true;
        return true;
    }

    /**
     * Вернуть в инвентарь семя, взятое из него же: иначе оно «зависает» в руке
     * после сессии. Что не поместилось, остаётся в руке — предмет не теряется.
     */
    private void returnSeed() {
        if (!this.seedFromInventory) {
            return;
        }
        this.seedFromInventory = false;
        ItemStack held = this.npc.getMainHandItem();
        if (!held.isEmpty() && CropAdapter.find(held) != null) {
            NpcItemUse.hold(this.npc, this.npc.addItem(held));
        }
    }

    private boolean fitsInventory(List<ItemStack> drops) {
        int free = 0;
        for (int slot = 0; slot < this.npc.getInventory().getSlots(); slot++) {
            ItemStack stack = this.npc.getInventory().getStackInSlot(slot);
            free += stack.isEmpty() ? 64 : Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        int needed = 0;
        for (ItemStack drop : drops) {
            needed += drop.getCount();
        }
        return needed <= free;
    }

    // ==================== навигация и окружение ====================

    private boolean goTo(BlockPos station) {
        return this.npc.getNavigation().moveTo(
                station.getX() + 0.5D, station.getY(), station.getZ() + 0.5D, SPEED);
    }

    private boolean arrived() {
        BlockPos station = this.stand;
        // Допуск нужен pathfinder'у: в тесных сценах он останавливается на
        // соседнюю клетку. Работу из такой позиции отсекает повторная проверка
        // FarmWorkStation.canWorkFrom по фактической клетке NPC в tickWork.
        if (station == null) {
            return false;
        }
        BlockPos pos = this.npc.blockPosition();
        double dx = station.getX() - pos.getX();
        double dz = station.getZ() - pos.getZ();
        return dx * dx + dz * dz <= ARRIVE_SQR
                && Math.abs(station.getY() - pos.getY()) <= ARRIVE_DY;
    }

    @Nullable
    private ServerLevel serverLevel() {
        return this.npc.level() instanceof ServerLevel level ? level : null;
    }

    private boolean isFarmer() {
        return this.npc.getRole() == SurvivorRole.CIVILIAN
                && this.npc.getProfession() == SurvivorProfession.FARMER;
    }

    @Nullable
    private CampRecord camp(ServerLevel level) {
        UUID campId = this.npc.getCampId();
        return campId == null ? null : CampManager.getCamp(level, campId);
    }
}
