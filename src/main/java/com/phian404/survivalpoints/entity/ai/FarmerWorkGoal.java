package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.camp.CampFarmManager;
import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import com.phian404.survivalpoints.entity.ai.crop.CropAdapter;
import com.phian404.survivalpoints.entity.ai.task.NpcTask;
import com.phian404.survivalpoints.entity.ai.task.NpcTaskController;
import com.phian404.survivalpoints.entity.role.SurvivorProfession;
import com.phian404.survivalpoints.entity.role.SurvivorRole;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

    /** Отступ от границы лагеря. */
    private static final int BORDER_INSET = 2;

    /** Предельная дистанция от позиции взаимодействия до грядки. */
    private static final double REACH_DISTANCE_SQR = 1.5D * 1.5D;

    /** Пауза после успешного действия. */
    private static final int PAUSE_MIN = 30;
    private static final int PAUSE_MAX = 80;

    /** Короткая задержка после неудачного действия. */
    private static final int RETRY_PAUSE = 10;

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

    public FarmerWorkGoal(BaseSurvivorEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    /** Текущее состояние исполнения. */
    public State getState() {
        return this.state;
    }

    @Override
    public boolean canUse() {
        if (this.pauseTicks > 0) {
            this.pauseTicks--;
            return false;
        }
        if (this.npc.level().isClientSide || !(this.npc.level() instanceof ServerLevel level)) {
            return false;
        }
        if (this.npc.getRole() != SurvivorRole.CIVILIAN
                || this.npc.getProfession() != SurvivorProfession.FARMER) {
            return false;
        }
        CampRecord camp = camp(level);
        if (camp == null || this.npc.getWorkPoiPos() == null) {
            return false;
        }

        NpcTaskController controller = CampManager.taskController(this.npc.getIdentityId());
        if (controller.current() != null) {
            return false;
        }

        CampFarmManager manager = CampManager.farmManager(camp);
        NpcTask task = manager.take(level, camp, this.npc, this.npc.getIdentityId(), level.getGameTime());
        if (task == null || task.getTarget() == null) {
            return false;
        }
        BlockPos approach = approachPos(level, camp, task.getTarget());
        if (approach == null) {
            manager.markUnavailable(task.getId(), level.getGameTime());
            return false;
        }

        UUID taskId = task.getId();
        if (!controller.tryStart(task, () -> manager.release(taskId))) {
            manager.release(taskId);
            return false;
        }

        this.approach = approach;
        this.state = State.MOVING;
        if (this.npc.blockPosition().distSqr(approach) <= REACH_DISTANCE_SQR) {
            return true;
        }
        if (!this.npc.getNavigation().moveTo(approach.getX() + 0.5D, approach.getY(),
                approach.getZ() + 0.5D, SPEED)) {
            cancel(level, manager, taskId, false);
            return false;
        }
        Path path = this.npc.getNavigation().getPath();
        if (path == null || !pathInside(camp, path)) {
            this.npc.getNavigation().stop();
            cancel(level, manager, taskId, false);
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.pauseTicks > 0 || this.approach == null || this.npc.level().isClientSide
                || !(this.npc.level() instanceof ServerLevel level)) {
            return false;
        }
        CampRecord camp = camp(level);
        NpcTaskController controller = CampManager.taskController(this.npc.getIdentityId());
        NpcTask task = controller.current();
        if (camp == null || task == null || task.getTarget() == null) {
            return false;
        }
        if (this.npc.blockPosition().distSqr(this.approach) > REACH_DISTANCE_SQR) {
            Path path = this.npc.getNavigation().getPath();
            return path != null && !path.isDone() && pathInside(camp, path);
        }
        return true;
    }

    @Override
    public void tick() {
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

        if (this.npc.blockPosition().distSqr(this.approach) > REACH_DISTANCE_SQR) {
            this.npc.getNavigation().moveTo(this.approach.getX() + 0.5D, this.approach.getY(),
                    this.approach.getZ() + 0.5D, SPEED);
            this.state = State.MOVING;
            return;
        }

        this.npc.getNavigation().stop();
        BlockPos plot = task.getTarget();
        this.npc.getLookControl().setLookAt(plot.getX() + 0.5D, plot.getY() + 0.5D,
                plot.getZ() + 0.5D);
        this.state = State.WORKING;

        boolean done = perform(level, task, plot);
        if (done) {
            controller.complete();
            this.pauseTicks = PAUSE_MIN + this.npc.getRandom().nextInt(PAUSE_MAX - PAUSE_MIN + 1);
        } else {
            manager.markUnavailable(task.getId(), level.getGameTime());
            controller.cancel();
            this.pauseTicks = RETRY_PAUSE;
        }
        this.approach = null;
        this.state = State.IDLE;
    }

    @Override
    public void stop() {
        if (this.npc.level() instanceof ServerLevel level) {
            CampRecord camp = camp(level);
            NpcTaskController controller = CampManager.taskController(this.npc.getIdentityId());
            NpcTask task = controller.current();
            if (camp != null && task != null) {
                CampManager.farmManager(camp).release(task.getId());
            }
            controller.cancel();
        }
        this.npc.getNavigation().stop();
        this.approach = null;
        this.state = State.IDLE;
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
        level.destroyBlock(pos, false);
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
        manager.release(taskId);
        CampManager.taskController(this.npc.getIdentityId()).cancel();
        this.approach = null;
        this.state = State.IDLE;
        this.pauseTicks = done ? PAUSE_MIN : RETRY_PAUSE;
    }

    @Nullable
    private CampRecord camp(ServerLevel level) {
        UUID campId = this.npc.getCampId();
        return campId == null ? null : CampManager.getCamp(level, campId);
    }

    /** Боковая позиция на уровень выше пашни. */
    @Nullable
    private BlockPos approachPos(ServerLevel level, CampRecord camp, BlockPos plot) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos pos = plot.relative(dir).above();
            if (!insideBorder(camp, pos)) {
                continue;
            }
            if (!level.getBlockState(pos.below()).blocksMotion()
                    || !level.getBlockState(pos).isAir()
                    || !level.getBlockState(pos.above()).isAir()) {
                continue;
            }
            if (pos.distSqr(plot.above()) > REACH_DISTANCE_SQR) {
                continue;
            }
            boolean onFarmland = level.getBlockState(pos.below()).is(Blocks.FARMLAND);
            double distance = this.npc.blockPosition().distSqr(pos) + (onFarmland ? 1000.0D : 0.0D);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best;
    }

    private static boolean insideBorder(CampRecord camp, BlockPos pos) {
        BlockPos center = camp.getCenter();
        int radius = Math.max(0, camp.getRadius() - BORDER_INSET);
        double dx = pos.getX() - center.getX();
        double dz = pos.getZ() - center.getZ();
        return dx * dx + dz * dz <= (double) radius * radius;
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
