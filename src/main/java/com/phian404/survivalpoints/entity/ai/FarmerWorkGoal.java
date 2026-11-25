package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampPoi;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampStructurePlacer;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import com.phian404.survivalpoints.entity.ai.crop.CropAdapter;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Работа фермера на участках своего фермерского слота.
 *
 * <p>Участок — POI {@code farm_plot}, указывающий на блок пашни; культура растёт
 * на блок выше. Фермер работает только с POI того же {@code slotId}, что и его
 * {@code workPoiPos}. Граница — круг {@link CampRecord#getCenter()} с отступом
 * 2 блока от {@link CampRecord#getRadius()}; {@link CampRecord#contains()} не
 * используется.
 */
public class FarmerWorkGoal extends Goal {

    /** Отступ от границы лагеря. */
    private static final int BORDER_INSET = 2;

    /** Дистанция, с которой фермер может работать с участком. */
    private static final double WORK_DISTANCE_SQR = 2.5D * 2.5D;

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
    private BlockPos target;

    /** Последняя проблемная цель — чтобы не выбирать её повторно подряд. */
    @Nullable
    private BlockPos failedTarget;

    public FarmerWorkGoal(BaseSurvivorEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
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

        this.target = pickWorkTarget(level, camp);
        if (this.target == null) {
            return false;
        }
        BlockPos approach = approachPos(level, camp, this.target);
        if (approach == null) {
            this.target = null;
            return false;
        }
        if (this.npc.blockPosition().distSqr(approach) <= WORK_DISTANCE_SQR) {
            return true;
        }
        if (!this.npc.getNavigation().moveTo(approach.getX() + 0.5D, approach.getY(),
                approach.getZ() + 0.5D, SPEED)) {
            this.target = null;
            return false;
        }
        Path path = this.npc.getNavigation().getPath();
        if (path == null || !pathInside(camp, path)) {
            this.npc.getNavigation().stop();
            this.target = null;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.pauseTicks > 0 || this.target == null || this.npc.level().isClientSide
                || !(this.npc.level() instanceof ServerLevel level)) {
            return false;
        }
        CampRecord camp = camp(level);
        if (camp == null) {
            return false;
        }
        BlockPos approach = approachPos(level, camp, this.target);
        if (approach == null) {
            return false;
        }
        if (this.npc.blockPosition().distSqr(approach) > WORK_DISTANCE_SQR) {
            Path path = this.npc.getNavigation().getPath();
            return path != null && !path.isDone() && pathInside(camp, path);
        }
        // Позиция взаимодействия достигнута — проверяем доступность самой грядки.
        return this.npc.blockPosition().distSqr(this.target.above()) <= WORK_DISTANCE_SQR;
    }

    @Override
    public void tick() {
        if (this.target == null || !(this.npc.level() instanceof ServerLevel level)) {
            return;
        }
        CampRecord camp = camp(level);
        BlockPos approach = camp == null ? null : approachPos(level, camp, this.target);
        if (approach == null) {
            this.target = null;
            return;
        }
        if (this.npc.blockPosition().distSqr(approach) > WORK_DISTANCE_SQR) {
            this.npc.getNavigation().moveTo(approach.getX() + 0.5D, approach.getY(),
                    approach.getZ() + 0.5D, SPEED);
            return;
        }

        this.npc.getNavigation().stop();
        BlockPos plot = this.target;
        this.npc.getLookControl().setLookAt(plot.getX() + 0.5D, plot.getY() + 0.5D,
                plot.getZ() + 0.5D);
        BlockPos cropPos = plot.above();
        BlockState state = level.getBlockState(cropPos);
        CropAdapter adapter = CropAdapter.find(state);
        boolean done;
        if (adapter != null && adapter.isMature(state)) {
            done = harvest(level, cropPos, state, adapter);
        } else if (state.isAir()) {
            done = plant(level, cropPos);
        } else {
            done = false;
        }

        this.target = null;
        if (done) {
            this.failedTarget = null;
            this.pauseTicks = PAUSE_MIN + this.npc.getRandom().nextInt(PAUSE_MAX - PAUSE_MIN + 1);
        } else {
            this.failedTarget = plot;
            this.pauseTicks = RETRY_PAUSE;
        }
    }

    @Override
    public void stop() {
        this.target = null;
        this.npc.getNavigation().stop();
    }

    /** Сбор только если весь дроп помещается в инвентарь. */
    private boolean harvest(ServerLevel level, BlockPos pos, BlockState state, CropAdapter adapter) {
        List<ItemStack> drops = adapter.getDrops(level, pos, state);
        if (drops.isEmpty() || !fitsInventory(drops)) {
            return false;
        }
        for (ItemStack drop : drops) {
            this.npc.addItem(drop.copy());
        }
        level.destroyBlock(pos, false);
        return true;
    }

    /**
     * Посадка: адаптер и культура определяются по семени из инвентаря.
     *
     * <p>Семя списывается только после успешной установки блока; при неудаче
     * извлечённый предмет возвращается обратно в инвентарь.
     */
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

    /** Первое семя из инвентаря, для которого есть адаптер и культура. */
    @Nullable
    private ItemStack findPlantableSeed() {
        for (int slot = 0; slot < this.npc.getInventory().getSlots(); slot++) {
            ItemStack stack = this.npc.getInventory().getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            CropAdapter adapter = CropAdapter.find(stack);
            if (adapter != null && adapter.getCropForSeed(stack) != null) {
                return stack;
            }
        }
        return null;
    }

    /** Вмещаются ли все стекы в инвентарь целиком. */
    private boolean fitsInventory(List<ItemStack> drops) {
        ItemStackHandlerView view = new ItemStackHandlerView(this.npc.getInventory().getSlots());
        for (int slot = 0; slot < this.npc.getInventory().getSlots(); slot++) {
            view.stacks[slot] = this.npc.getInventory().getStackInSlot(slot).copy();
        }
        for (ItemStack drop : drops) {
            ItemStack remaining = drop.copy();
            for (int slot = 0; slot < view.stacks.length && !remaining.isEmpty(); slot++) {
                remaining = view.insert(slot, remaining);
            }
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Лагерь NPC по campId. */
    @Nullable
    private CampRecord camp(ServerLevel level) {
        UUID campId = this.npc.getCampId();
        return campId == null ? null : CampManager.getCamp(level, campId);
    }

    /**
     * Выбор цели: сначала зрелые культуры, затем посадка.
     *
     * <p>Сбор всегда имеет приоритет — пустая грядка не может перебить зрелую
     * культуру независимо от расстояния. Участок фермы сам по себе может лежать
     * вне круга; проверяется достижимость позиции взаимодействия внутри круга.
     */
    @Nullable
    private BlockPos pickWorkTarget(ServerLevel level, CampRecord camp) {
        BlockPos anchor = this.npc.getWorkPoiPos();
        String slotId = null;
        for (CampPoi poi : camp.getPois()) {
            if (CampStructurePlacer.FARM_PLOT_TYPE.equals(poi.getType())
                    && anchor.equals(poi.getPos())) {
                slotId = poi.getSlotId();
                break;
            }
        }
        if (slotId == null) {
            return null;
        }

        List<BlockPos> plots = new ArrayList<>();
        for (CampPoi poi : camp.getPois()) {
            if (CampStructurePlacer.FARM_PLOT_TYPE.equals(poi.getType())
                    && slotId.equals(poi.getSlotId())) {
                plots.add(poi.getPos());
            }
        }

        BlockPos harvest = nearest(level, camp, plots, true);
        if (harvest != null) {
            return harvest;
        }
        return nearest(level, camp, plots, false);
    }

    /**
     * Ближайший доступный участок нужного типа.
     *
     * @param harvest {@code true} — зрелая культура, {@code false} — посадка
     */
    @Nullable
    private BlockPos nearest(ServerLevel level, CampRecord camp, List<BlockPos> plots,
                             boolean harvest) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos plot : plots) {
            if (plot.equals(this.failedTarget)) {
                continue;
            }
            boolean ready = harvest ? isHarvestReady(level, plot) : isPlantReady(level, plot);
            if (!ready) {
                continue;
            }
            BlockPos approach = approachPos(level, camp, plot);
            if (approach == null) {
                continue;
            }
            double distance = this.npc.blockPosition().distSqr(approach);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = plot;
            }
        }
        return best;
    }

    /** Зрелая культура на участке, дроп которой помещается в инвентарь. */
    private boolean isHarvestReady(ServerLevel level, BlockPos plot) {
        BlockState state = level.getBlockState(plot.above());
        CropAdapter adapter = CropAdapter.find(state);
        return adapter != null && adapter.isMature(state)
                && fitsInventory(adapter.getDrops(level, plot.above(), state));
    }

    /** Пустая грядка с подходящим семенем в инвентаре. */
    private boolean isPlantReady(ServerLevel level, BlockPos plot) {
        return level.getBlockState(plot.above()).isAir() && findPlantableSeed() != null;
    }

    /**
     * Боковая позиция взаимодействия с грядкой.
     *
     * <p>NPC не встаёт на саму пашню. Кандидаты — четыре соседние клетки на
     * уровень выше грядки; каждая проверяется по опоре, свободному пространству
     * и кругу лагеря. Предпочитается клетка без пашни под ногами, затем
     * ближайшая к NPC. Для грядок вне круга дополнительно проверяется, что
     * расстояние от позиции до грядки не превышает {@link #WORK_DISTANCE_SQR}.
     */
    @Nullable
    private BlockPos approachPos(ServerLevel level, CampRecord camp, BlockPos plot) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            // Ноги NPC на уровень выше пашни: сама грядка не используется как
            // позиция стояния, а опорой служит любой твёрдый блок под ногами.
            BlockPos pos = plot.relative(dir).above();
            if (!insideBorder(camp, pos)) {
                continue;
            }
            if (!level.getBlockState(pos.below()).blocksMotion()
                    || !level.getBlockState(pos).isAir()
                    || !level.getBlockState(pos.above()).isAir()) {
                continue;
            }
            if (pos.distSqr(plot.above()) > WORK_DISTANCE_SQR) {
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

    /** Лёгкая копия инвентаря для проверки вместимости без его изменения. */
    private static final class ItemStackHandlerView {
        private final ItemStack[] stacks;

        private ItemStackHandlerView(int size) {
            this.stacks = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                this.stacks[i] = ItemStack.EMPTY;
            }
        }

        private ItemStack insert(int slot, ItemStack stack) {
            ItemStack existing = this.stacks[slot];
            int limit = Math.min(64, stack.getMaxStackSize());
            if (!existing.isEmpty()) {
                if (!ItemStack.isSameItemSameTags(existing, stack)) {
                    return stack;
                }
                limit -= existing.getCount();
            }
            if (limit <= 0) {
                return stack;
            }
            int moved = Math.min(limit, stack.getCount());
            if (existing.isEmpty()) {
                this.stacks[slot] = stack.copy();
                this.stacks[slot].setCount(moved);
            } else {
                existing.grow(moved);
            }
            ItemStack rest = stack.copy();
            rest.shrink(moved);
            return rest;
        }
    }
}
