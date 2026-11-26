package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.camp.CampFarmManager;
import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.FarmApproach;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import com.phian404.survivalpoints.entity.ai.crop.CropAdapter;
import com.phian404.survivalpoints.entity.ai.task.NpcTask;
import com.phian404.survivalpoints.entity.ai.task.NpcTaskController;
import com.phian404.survivalpoints.entity.role.SurvivorProfession;
import com.phian404.survivalpoints.entity.role.SurvivorRole;
import com.phian404.survivalpoints.init.GuardianTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.UUID;

/**
 * Исполнитель фермерских задач из {@link CampFarmManager}.
 *
 * <p>Goal больше не сканирует POI сам: он запрашивает задачу у диспетчера,
 * резервирует её через {@link NpcTaskController}, строит путь к боковой позиции
 * взаимодействия и выполняет действие только после физического прибытия.
 * Диспетчер остаётся владельцем резервации; Goal только инициирует освобождение
 * через callback контроллера.
 */
public class FarmerWorkGoal extends Goal {

    /** Предельная дистанция от позиции взаимодействия до грядки. */
    private static final double REACH_DISTANCE_SQR = 1.5D * 1.5D;

    /** Пауза после успешного действия (уменьшена вдвое для отзывчивости). */
    private static final int PAUSE_MIN = 15;
    private static final int PAUSE_MAX = 40;

    /** Короткая задержка после неудачного действия. */
    private static final int RETRY_PAUSE = 10;

    /** Сколько тиков без прогресса считается застреванием. */
    private static final int STUCK_TIMEOUT = 100;

    /** Интервал принудительного перестроения пути. */
    private static final int REPATH_INTERVAL = 20;

    /** Скорость движения к участку. */
    private static final double SPEED = 1.0D;

    private final BaseSurvivorEntity npc;
    private int pauseTicks = 0;
    @Nullable
    private BlockPos approach;

    /** Состояние исполнения для отладки. */
    public enum State {
        IDLE, MOVING, WORKING
    }

    private State state = State.IDLE;

    /** ВРЕМЕННО: частота сводки и последняя записанная стадия. */
    private static final int TRACE_INTERVAL = 100;
    private String lastTraceStage = "";
    private long lastTraceTick = Long.MIN_VALUE;
    private boolean wasPaused = false;

    /** Позиция NPC на предыдущем тике и счётчик отсутствия прогресса. */
    @Nullable
    private BlockPos lastNpcPos;
    private int stuckTicks = 0;
    private int repathTicks = 0;
    /** Корректирующий навигационный шаг уже выпущен по текущему завершённому пути. */
    private boolean correctiveStep = false;
    /**
     * Клетка, к которой ведёт принятый путь. Навигатор выбирает ближайшую
     * достижимую из пригодных клеток, поэтому она может не совпадать с первой
     * по порядку сторон; прибытие сверяется именно по ней.
     */
    @Nullable
    private BlockPos pathTarget;

    /** В руку положено семя только для отображения (копия из инвентаря). */
    private boolean heldDisplaySeed = false;

    /**
     * Грядка, на которую смотрим во время паузы после успешной работы: без
     * этого цель гаснет (LookControl доворачивает голову лишь пару тиков),
     * цель останавливается и взгляд перехватывают RandomLookAroundGoal /
     * LookAtPlayerGoal.
     */
    @Nullable
    private BlockPos pauseLookTarget;

    public FarmerWorkGoal(BaseSurvivorEntity npc) {
        this.npc = npc;
        // Flag.LOOK: без него во время движения и работы параллельно выполняются
        // RandomLookAroundGoal/LookAtPlayerGoal, и они вместе с setLookAt(plot)
        // каждый тик перетягивают LookControl — голова NPC кивает вверх-вниз.
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    /** Текущее состояние исполнения. */
    public State getState() {
        return this.state;
    }

    /**
     * ВРЕМЕННО: записать только изменение диагностического состояния.
     *
     * <p>Повтор той же стадии в пределах 100 тиков не пишется. Полностью
     * отключается существующим флагом {@code /sp_trace chat off}.
     */
    private void trace(ServerLevel level, String stage, String detail) {
        long now = level.getGameTime();
        String key = stage + "|" + detail;
        if (key.equals(this.lastTraceStage) && now - this.lastTraceTick < TRACE_INTERVAL) {
            return;
        }
        this.lastTraceStage = key;
        this.lastTraceTick = now;
        GuardianTrace.always(level, "FarmerWorkGoal: identity=" + this.npc.getIdentityId()
                + " stage=" + stage + " " + detail);
    }

    @Override
    public boolean canUse() {
        if (this.pauseTicks > 0) {
            this.pauseTicks--;
            if (!this.wasPaused && this.npc.level() instanceof ServerLevel level) {
                trace(level, "PAUSE", "result=ENTER ticks=" + this.pauseTicks);
            }
            this.wasPaused = true;
            return false;
        }
        if (this.npc.level().isClientSide || !(this.npc.level() instanceof ServerLevel level)) {
            return false;
        }
        if (this.wasPaused) {
            trace(level, "PAUSE", "result=EXIT");
            this.wasPaused = false;
        }
        if (this.npc.getRole() != SurvivorRole.CIVILIAN) {
            return false;
        }
        if (this.npc.getProfession() != SurvivorProfession.FARMER) {
            return false;
        }
        CampRecord camp = camp(level);
        if (camp == null) {
            trace(level, "CAMP", "result=NO_RECORD campId=" + this.npc.getCampId());
            return false;
        }
        if (this.npc.getWorkPoiPos() == null) {
            trace(level, "WORK_POI", "result=NULL");
            return false;
        }

        NpcTaskController controller = CampManager.taskController(this.npc.getIdentityId());
        if (controller.current() != null) {
            trace(level, "CONTROLLER", "result=BUSY task=" + controller.current().getType());
            return false;
        }

        CampFarmManager manager = CampManager.farmManager(camp);
        int harvest = manager.taskCount(CampFarmManager.TYPE_HARVEST);
        int plant = manager.taskCount(CampFarmManager.TYPE_PLANT);
        NpcTask task = manager.take(level, camp, this.npc, this.npc.getIdentityId(), level.getGameTime());
        if (task == null || task.getTarget() == null) {
            trace(level, "TAKE_RESULT", "harvest=" + harvest + " plant=" + plant
                    + " result=NO_TASK" + manager.takeSummary());
            return false;
        }
        trace(level, "TAKE_RESULT", "harvest=" + harvest + " plant=" + plant
                + " result=" + task.getType() + " target=" + task.getTarget());
        BlockPos approach = approachPos(level, camp, task.getTarget());
        if (approach == null) {
            trace(level, "APPROACH", "result=NO_POSITION target=" + task.getTarget()
                    + " reason=NO_SAFE_SIDE_INSIDE_BORDER");
            manager.markFailed(task.getId(), level.getGameTime());
            return false;
        }
        trace(level, "APPROACH", "result=OK target=" + task.getTarget()
                + " approach=" + approach + " task=" + task.getType());

        UUID taskId = task.getId();
        if (!controller.tryStart(task, () -> manager.release(taskId))) {
            trace(level, "TRY_START", "result=REJECTED");
            manager.release(taskId);
            return false;
        }
        trace(level, "TRY_START", "result=OK");

        this.approach = approach;
        showHeldSeed(task);
        this.state = State.MOVING;
        if (atApproach(approach)) {
            trace(level, "PATH_RESULT", "result=ALREADY_THERE");
            return true;
        }
        // Путь строим сразу к набору всех пригодных клеток подхода: ванильная
        // навигация выбирает ближайшую достижимую, а не первую по порядку
        // сторон. Точность 0 — цель должна совпасть с клеткой, а не оказаться
        // в манхэттенском радиусе 1 (иначе получается one-node partial path).
        // PathFinder не сигнализирует недостижимость null-ом: он отдаёт
        // частичный путь с canReach() == false — это проверяет approachPath.
        Path path = approachPath(level, camp, task);
        if (path == null || !this.npc.getNavigation().moveTo(path, SPEED)) {
            trace(level, "PATH_RESULT", "result=NO_PATH approach=" + approach);
            abandon(level, camp, task, RETRY_PAUSE);
            return false;
        }
        if (!pathInside(camp, path)) {
            trace(level, "PATH_RESULT", "result=OUTSIDE_BORDER nodes=" + path.getNodeCount());
            abandon(level, camp, task, RETRY_PAUSE);
            return false;
        }
        // Прибытие сверяем по клетке, в которую действительно ведёт принятый
        // путь: путь мог быть построен не к первой пригодной клетке, и тогда
        // atApproach ждал бы клетку, до которой фермер не идёт.
        if (this.pathTarget != null) {
            this.approach = this.pathTarget;
        }
        trace(level, "PATH_RESULT", "result=OK nodes=" + path.getNodeCount()
                + " approach=" + this.approach);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        // Пауза после успешной работы — держим цель живой, чтобы NPC стоял и
        // смотрел на обработанную грядку (тик паузы обслуживает tick()).
        if (this.pauseLookTarget != null) {
            return true;
        }
        if (this.pauseTicks > 0 || this.approach == null || this.npc.level().isClientSide
                || !(this.npc.level() instanceof ServerLevel level)) {
            return false;
        }
        NpcTaskController controller = CampManager.taskController(this.npc.getIdentityId());
        NpcTask task = controller.current();
        return task != null && task.getTarget() != null;
    }

    @Override
    public void tick() {
        if (this.pauseLookTarget != null) {
            this.pauseTicks--;
            this.npc.getNavigation().stop();
            this.npc.getLookControl().setLookAt(this.pauseLookTarget.getX() + 0.5D,
                    this.pauseLookTarget.getY() + 1.5D, this.pauseLookTarget.getZ() + 0.5D);
            if (this.pauseTicks <= 0) {
                this.pauseLookTarget = null;
            }
            return;
        }
        if (this.approach == null || !(this.npc.level() instanceof ServerLevel level)) {
            return;
        }
        CampRecord camp = camp(level);
        NpcTaskController controller = CampManager.taskController(this.npc.getIdentityId());
        NpcTask task = controller.current();
        if (camp == null || task == null || task.getTarget() == null) {
            stop();
            return;
        }
        CampFarmManager manager = CampManager.farmManager(camp);
        manager.keepAlive(task.getId(), level.getGameTime());

        if (!atApproach(this.approach)) {
            BlockPos current = this.npc.blockPosition();
            if (current.equals(this.lastNpcPos)) {
                this.stuckTicks++;
            } else {
                this.stuckTicks = 0;
                this.lastNpcPos = current;
            }
            if (this.stuckTicks >= STUCK_TIMEOUT) {
                trace(level, "STUCK", "result=GIVE_UP approach=" + this.approach
                        + " npc=" + current + " ticks=" + this.stuckTicks);
                abandon(level, camp, task, RETRY_PAUSE);
                return;
            }

            Path path = this.npc.getNavigation().getPath();
            if (path == null || path.isDone()) {
                // Страховка: навигатор считает маршрут завершённым, но
                // atApproach() ещё не true (например, остановился в зоне
                // advance узла). Первичный путь теперь строится с точностью 0,
                // так что в штатном сценарии эта ветка не срабатывает.
                // Корректирующий шаг делаем той же системой навигации:
                // createPath с accuracy 0 строит настоящий путь, который
                // навигатор реально ведёт (коллизии, срезание углов и таймаут
                // остались в навигаторе, MoveControl в обход не используется).
                // Повтор — только после того, как новый путь начался и снова
                // завершился. STUCK остаётся финальной страховкой.
                if (!this.correctiveStep) {
                    this.correctiveStep = true;
                    Path correctivePath = this.approachPath(level, camp, task);
                    boolean started = correctivePath != null
                            && this.npc.getNavigation().moveTo(correctivePath, SPEED);
                    if (started && this.pathTarget != null) {
                        this.approach = this.pathTarget;
                    }
                    trace(level, "CORRECTIVE_STEP",
                            "result=" + (started ? "STARTED" : "NO_PATH")
                                    + " nodes=" + (correctivePath == null ? 0
                                            : correctivePath.getNodeCount())
                                    + " npc=" + this.npc.blockPosition()
                                    + " pos=" + this.npc.position()
                                    + " approach=" + this.approach);
                    if (!started) {
                        abandon(level, camp, task, RETRY_PAUSE);
                    }
                }
            } else {
                this.correctiveStep = false;
                this.repathTicks--;
                if (this.repathTicks <= 0) {
                    Path repath = this.approachPath(level, camp, task);
                    boolean started = repath != null
                            && this.npc.getNavigation().moveTo(repath, SPEED);
                    if (started && this.pathTarget != null) {
                        this.approach = this.pathTarget;
                    }
                    this.repathTicks = REPATH_INTERVAL;
                    if (started) {
                        trace(level, "REPATH", "npc=" + current + " pos=" + this.npc.position()
                                + " approach=" + this.approach
                                + " nodes=" + repath.getNodeCount()
                                + " idx=" + repath.getNextNodeIndex());
                    } else {
                        trace(level, "REPATH", "result=NO_PATH npc=" + current
                                + " approach=" + this.approach);
                    }
                }
            }
            this.state = State.MOVING;
            return;
        }
        this.stuckTicks = 0;
        this.lastNpcPos = null;
        this.repathTicks = 0;
        trace(level, "ARRIVED", "npc=" + this.npc.blockPosition()
                + " pos=" + this.npc.position() + " approach=" + this.approach);

        this.npc.getNavigation().stop();
        BlockPos plot = task.getTarget();
        // Смотрим на саму культуру (блок над грядкой), а не на пашню под ней:
        // иначе при сборе и посадке голова опущена в землю.
        this.npc.getLookControl().setLookAt(plot.getX() + 0.5D, plot.getY() + 1.5D,
                plot.getZ() + 0.5D);

        // Повторная проверка непосредственно перед изменением блока: NPC обязан
        // физически находиться рядом с целевой грядкой, а не только около
        // абстрактной позиции подхода.
        double targetDistance = FarmApproach.horizontalDistSqr(this.npc.blockPosition(), plot);
        if (targetDistance > FarmApproach.TARGET_REACH_SQR) {
            trace(level, "TARGET_DISTANCE", "result=TOO_FAR target=" + plot
                    + " distSqr=" + String.format("%.2f", targetDistance));
            abandon(level, camp, task, RETRY_PAUSE);
            return;
        }

        this.state = State.WORKING;
        boolean done = perform(level, task, plot);
        trace(level, "INTERACT", "target=" + plot + " task=" + task.getType()
                + " result=" + (done ? "DONE" : "FAILED"));
        if (done) {
            CampManager.farmManager(camp).markSucceeded(task.getId());
            controller.complete();
            clearHeldItem();
            this.pauseTicks = PAUSE_MIN + this.npc.getRandom().nextInt(PAUSE_MAX - PAUSE_MIN + 1);
            this.pauseLookTarget = plot;
            this.approach = null;
            this.correctiveStep = false;
            this.state = State.IDLE;
        } else {
            abandon(level, camp, task, RETRY_PAUSE);
        }
    }

    @Override
    public void stop() {
        if (this.npc.level() instanceof ServerLevel level) {
            CampRecord camp = camp(level);
            NpcTaskController controller = CampManager.taskController(this.npc.getIdentityId());
            NpcTask task = controller.current();
            Path path = this.npc.getNavigation().getPath();
            String reason = this.pauseTicks > 0 ? "PAUSE"
                    : this.approach == null ? "NO_APPROACH"
                    : task == null ? "NO_TASK"
                    : atApproach(this.approach) ? "ARRIVED" : "GOAL_INTERRUPTED";
            trace(level, "STOP_DIAG", "reason=" + reason
                    + " npcBlock=" + this.npc.blockPosition()
                    + " npcPos=" + this.npc.position()
                    + " approach=" + this.approach
                    + " atApproach=" + (this.approach != null && atApproach(this.approach))
                    + " path=" + (path != null)
                    + " done=" + (path != null && path.isDone())
                    + " nodes=" + (path == null ? 0 : path.getNodeCount())
                    + " idx=" + (path == null ? 0 : path.getNextNodeIndex()));
            if (camp != null && task != null) {
                trace(level, "RELEASE", "result=STOP task=" + task.getType()
                        + " target=" + task.getTarget() + " reason=" + reason);
                CampManager.farmManager(camp).release(task.getId());
            }
            controller.cancel();
        }
        this.npc.getNavigation().stop();
        clearHeldItem();
        this.approach = null;
        this.pauseLookTarget = null;
        this.state = State.IDLE;
        this.stuckTicks = 0;
        this.lastNpcPos = null;
        this.repathTicks = 0;
        this.correctiveStep = false;
    }

    /**
     * Путь к грядке: цель — любая пригодная клетка подхода. Навигация сама
     * выбирает ближайшую достижимую; точность 0, чтобы цель совпала с клеткой,
     * а не попала в манхэттенский радиус. Возвращает null, если пути к
     * достижимой клетке нет (частичный путь отбрасывается по canReach).
     */
    @Nullable
    private Path approachPath(ServerLevel level, CampRecord camp, NpcTask task) {
        BlockPos target = task.getTarget();
        java.util.List<BlockPos> targets = new java.util.ArrayList<>(
                FarmApproach.findApproaches(level, camp, target,
                        this.npc.getBbWidth(), this.npc.getBbHeight()));
        if (targets.isEmpty() && this.approach != null
                && FarmApproach.validate(level, camp, target, this.approach,
                        this.npc.getBbWidth(), this.npc.getBbHeight()) == null) {
            targets.add(this.approach);
        }
        // Ближайшие клетки первыми: у PathFinder ограниченный радиус поиска, и
        // дальняя цель может оказаться вне него. Перебираем через
        // createPath(BlockPos, 0): у этой перегрузки радиус шире, чем у варианта
        // с набором клеток, и она строит настоящий маршрут, а не манхэттенский
        // допуск 1.
        targets.sort(java.util.Comparator.comparingDouble(
                cell -> cell.distSqr(this.npc.blockPosition())));
        this.pathTarget = null;
        for (BlockPos cell : targets) {
            Path path = this.npc.getNavigation().createPath(cell, 0);
            // PathFinder не сигнализирует недостижимость null-ом: он отдаёт
            // частичный путь с canReach() == false — такой не принимаем.
            if (path != null && path.canReach()) {
                // Путь ведёт именно в эту клетку — прибытие сверяется по ней,
                // а не по первой пригодной по порядку сторон.
                this.pathTarget = cell;
                return path;
            }
        }
        return null;
    }

    /**
     * Единая точка отказа от начатой задачи.
     *
     * <p>Снимает резервацию у диспетчера, отменяет задачу в контроллере,
     * останавливает навигацию и приводит Goal в состояние покоя. Раньше эта
     * логика была размазана по веткам STUCK, TOO_FAR, неудачного действия и
     * трёх провалов пути с немного разными наборами вызовов — из-за чего
     * задачу можно было забыть освободить.
     *
     * <p>Каждый отказ увеличивает счётчик отказов грядки: повторное
     * предложение задачи сдвигается на нарастающий cooldown, чтобы фермер не
     * зацикливался на одной упорно недоступной грядке. Успешное выполнение
     * счётчик обнуляет ({@link CampFarmManager#markSucceeded}).
     *
     * @param pause пауза в тиках перед следующим поиском задачи
     */
    private void abandon(ServerLevel level, CampRecord camp, NpcTask task, int pause) {
        CampFarmManager manager = CampManager.farmManager(camp);
        manager.markFailed(task.getId(), level.getGameTime());
        trace(level, "RELEASE", "result=CANCEL taskId=" + task.getId());
        CampManager.taskController(this.npc.getIdentityId()).cancel();
        this.npc.getNavigation().stop();
        clearHeldItem();
        this.approach = null;
        this.pauseLookTarget = null;
        this.state = State.IDLE;
        this.stuckTicks = 0;
        this.lastNpcPos = null;
        this.repathTicks = 0;
        this.correctiveStep = false;
        this.pauseTicks = pause;
    }

    /** Повторная проверка и выполнение действия. */
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
            return plant(level, cropPos);
        }
        return false;
    }

    private boolean harvest(ServerLevel level, BlockPos pos, BlockState state, CropAdapter adapter) {
        java.util.List<ItemStack> drops = adapter.getDrops(level, pos, state);
        if (drops.isEmpty() || !fitsInventory(drops)) {
            return false;
        }
        for (ItemStack drop : drops) {
            this.npc.addItem(drop.copy());
        }
        if (level.destroyBlock(pos, false)) {
            this.npc.swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    private boolean plant(ServerLevel level, BlockPos pos) {
        ItemStack seed = findPlantableSeed();
        if (seed == null) {
            return false;
        }
        CropAdapter adapter = CropAdapter.find(seed);
        BlockState crop = adapter == null ? null : adapter.getCropForSeed(seed);
        if (adapter == null || crop == null) {
            return false;
        }
        ItemStack extracted = this.npc.extractItem(seed, 1);
        if (extracted.isEmpty()) {
            return false;
        }
        if (!adapter.plant(level, pos, crop)) {
            this.npc.addItem(extracted);
            return false;
        }
        this.npc.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    @Nullable
    private ItemStack findPlantableSeed() {
        for (int slot = 0; slot < this.npc.getInventory().getSlots(); slot++) {
            ItemStack stack = this.npc.getInventory().getStackInSlot(slot);
            if (!stack.isEmpty() && CropAdapter.find(stack) != null) {
                return stack;
            }
        }
        return null;
    }

    /**
     * Показывает семя в главной руке на время задачи посадки.
     *
     * <p>Это только отображение: в руку кладётся копия предмета из инвентаря,
     * запас не тратится. Если NPC уже что-то держит (например, предмет,
     * подаренный игроком), его рука не трогается.
     */
    private void showHeldSeed(NpcTask task) {
        if (!CampFarmManager.TYPE_PLANT.equals(task.getType())) {
            clearHeldItem();
            return;
        }
        if (!this.heldDisplaySeed && !this.npc.getMainHandItem().isEmpty()) {
            return;
        }
        ItemStack seed = findPlantableSeed();
        if (seed == null) {
            return;
        }
        this.npc.setItemSlot(EquipmentSlot.MAINHAND, seed.copyWithCount(1));
        this.heldDisplaySeed = true;
    }

    /** Убирает из руки только то семя, которое гол положил сам. */
    private void clearHeldItem() {
        if (this.heldDisplaySeed) {
            this.npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            this.heldDisplaySeed = false;
        }
    }

    private boolean fitsInventory(java.util.List<ItemStack> drops) {
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

    private void cancel(ServerLevel level, CampFarmManager manager, UUID taskId, boolean done) {
        trace(level, "RELEASE", "result=CANCEL taskId=" + taskId);
        manager.release(taskId);
        CampManager.taskController(this.npc.getIdentityId()).cancel();
        this.approach = null;
        this.pauseLookTarget = null;
        this.state = State.IDLE;
        this.stuckTicks = 0;
        this.lastNpcPos = null;
        this.repathTicks = 0;
        this.correctiveStep = false;
        this.pauseTicks = done ? PAUSE_MIN : RETRY_PAUSE;
    }
    @Nullable
    private CampRecord camp(ServerLevel level) {
        UUID campId = this.npc.getCampId();
        return campId == null ? null : CampManager.getCamp(level, campId);
    }

    /**
     * Боковая позиция взаимодействия.
     *
     * <p>Ноги NPC находятся на том же уровне, что и блок пашни: культура стоит
     * на блок выше, поэтому позиция «над пашней» была на блок выше фактического
     * уровня стояния и давала лишнюю вертикальную компоненту в дистанции.
     * Кандидат — соседняя клетка на уровне пашни с твёрдой опорой под ногами и
     * двумя свободными клетками для тела и головы. Опорой может быть любой
     * твёрдый блок, не обязательно пашня.
     */
    @Nullable
    private BlockPos approachPos(ServerLevel level, CampRecord camp, BlockPos plot) {
        // Только четыре стороны: диагональная клетка имеет distSqr=2 до грядки
        // и отсекается порогом TARGET_REACH_SQR=1.0, поэтому её перебор
        // бесполезен. Порог взаимодействия не расширяем.
        java.util.List<String> diag = new java.util.ArrayList<>();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = plot.relative(dir);
            String reason = FarmApproach.validate(level, camp, plot, candidate,
                    this.npc.getBbWidth(), this.npc.getBbHeight());
            if (reason == null) {
                return candidate;
            }
            diag.add(candidate + " -> " + reason);
        }
        // Одна сводка на одну попытку участка: подход вызывается один раз
        // на взятую задачу, поэтому постоянного потока логов нет.
        trace(level, "APPROACH_DIAG", "plot=" + plot + " candidates=" + diag);
        return null;
    }

    /**
    /**
     * NPC физически находится в выбранной клетке подхода.
     *
     * <p>Допуск — полблока от центра клетки (остановка навигатора), вертикаль
     * до 1.5 блоков. Клетка подхода всегда соседняя с грядкой, поэтому
     * прибытие согласовано с порогом TARGET_REACH_SQR до грядки.
     */
    private boolean atApproach(BlockPos approach) {
        // Прибытие — NPC фактически внутри клетки подхода. Допуск только
        // полблока от её центра (остановка навигатора): допуск в целую клетку
        // засчитывал прибытие из соседней клетки, и TASK начинался с
        // TARGET_DISTANCE=TOO_FAR (distSqr=4) — цикл ALREADY_THERE -> TOO_FAR.
        // Клетка подхода по построению соседняя с грядкой, поэтому прибытие
        // здесь гарантирует horizontalDistSqr <= TARGET_REACH_SQR: критерии
        // согласованы, радиус взаимодействия не расширяется.
        if (this.npc.blockPosition().equals(approach)) {
            return true;
        }
        double dx = this.npc.getX() - (approach.getX() + 0.5D);
        double dz = this.npc.getZ() - (approach.getZ() + 0.5D);
        double dy = this.npc.getY() - approach.getY();
        return dx * dx + dz * dz <= 0.25D && Math.abs(dy) <= 1.5D;
    }

    /** Горизонтальное расстояние между центрами клеток — общее с FarmApproach. */
    private static double horizontalDistSqr(BlockPos a, BlockPos b) {
        return FarmApproach.horizontalDistSqr(a, b);
    }

    private static boolean insideBorder(CampRecord camp, BlockPos pos) {
        return FarmApproach.insideBorder(camp, pos);
    }

    private static boolean pathInside(CampRecord camp, Path path) {
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            if (!insideBorder(camp, path.getNodePos(i))) {
                return false;
            }
        }
        return true;
    }
}
