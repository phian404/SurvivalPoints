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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
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

    /** Пауза после действия. */
    private static final int PAUSE_MIN = 30;
    private static final int PAUSE_MAX = 80;

    /** Скорость движения к участку. */
    private static final double SPEED = 1.0D;

    private final BaseSurvivorEntity npc;
    private int pauseTicks = 0;
    @Nullable
    private BlockPos target;

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
        if (this.npc.blockPosition().distSqr(this.target) <= WORK_DISTANCE_SQR) {
            return true;
        }
        if (!this.npc.getNavigation().moveTo(this.target.getX() + 0.5D, this.target.getY(),
                this.target.getZ() + 0.5D, SPEED)) {
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
        if (camp == null || !insideBorder(camp, this.target)) {
            return false;
        }
        if (this.npc.blockPosition().distSqr(this.target) <= WORK_DISTANCE_SQR) {
            return true;
        }
        Path path = this.npc.getNavigation().getPath();
        return path != null && !path.isDone() && pathInside(camp, path);
    }

    @Override
    public void tick() {
        if (this.target == null || !(this.npc.level() instanceof ServerLevel level)) {
            return;
        }
        if (this.npc.blockPosition().distSqr(this.target) > WORK_DISTANCE_SQR) {
            this.npc.getNavigation().moveTo(this.target.getX() + 0.5D, this.target.getY(),
                    this.target.getZ() + 0.5D, SPEED);
            return;
        }

        this.npc.getNavigation().stop();
        BlockPos cropPos = this.target.above();
        BlockState state = level.getBlockState(cropPos);
        CropAdapter adapter = CropAdapter.find(state);
        if (adapter != null && adapter.isMature(state)) {
            harvest(level, cropPos, state, adapter);
        } else if (state.isAir()) {
            plant(level, cropPos, adapterForPlanting(level, cropPos));
        }
        this.target = null;
        this.pauseTicks = PAUSE_MIN + this.npc.getRandom().nextInt(PAUSE_MAX - PAUSE_MIN + 1);
    }

    @Override
    public void stop() {
        this.target = null;
        this.npc.getNavigation().stop();
    }

    /** Сбор только если весь дроп помещается в инвентарь. */
    private void harvest(ServerLevel level, BlockPos pos, BlockState state, CropAdapter adapter) {
        List<ItemStack> drops = adapter.getDrops(level, pos, state);
        if (drops.isEmpty()) {
            return;
        }
        if (!fitsInventory(drops)) {
            return;
        }
        for (ItemStack drop : drops) {
            this.npc.addItem(drop.copy());
        }
        level.destroyBlock(pos, false);
    }

    /** Посадка: семена списываются только после успешной установки блока. */
    private void plant(ServerLevel level, BlockPos pos, @Nullable CropAdapter adapter) {
        if (adapter == null) {
            return;
        }
        ItemStack seed = adapter.getSeed(level, pos, level.getBlockState(pos.below()));
        if (seed == null || seed.isEmpty()) {
            return;
        }
        ItemStack extracted = this.npc.extractItem(seed, 1);
        if (extracted.isEmpty()) {
            return;
        }
        if (!adapter.plant(level, pos)) {
            this.npc.addItem(extracted);
        }
    }

    /**
     * Адаптер для пустого участка: берём его из блока пашни под ним.
     *
     * <p>Для культур на базе CropBlock семена и посадка определяются одним
     * адаптером; конкретный вид культуры появится после посадки.
     */
    @Nullable
    private CropAdapter adapterForPlanting(ServerLevel level, BlockPos cropPos) {
        BlockState soil = level.getBlockState(cropPos.below());
        for (CropAdapter adapter : CropAdapter.REGISTRY) {
            if (adapter.getSeed(level, cropPos, soil) != null) {
                return adapter;
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
     * Ближайший участок с полезным действием среди POI того же слота.
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
                    && slotId.equals(poi.getSlotId()) && insideBorder(camp, poi.getPos())) {
                plots.add(poi.getPos());
            }
        }

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos plot : plots) {
            if (!hasWork(level, plot)) {
                continue;
            }
            double distance = this.npc.blockPosition().distSqr(plot);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = plot;
            }
        }
        return best;
    }

    /** Есть ли на участке работа: спелая культура или посадка при наличии семян. */
    private boolean hasWork(ServerLevel level, BlockPos plot) {
        BlockPos cropPos = plot.above();
        BlockState state = level.getBlockState(cropPos);
        CropAdapter adapter = CropAdapter.find(state);
        if (adapter != null && adapter.isMature(state)) {
            return fitsInventory(adapter.getDrops(level, cropPos, state));
        }
        if (!state.isAir()) {
            return false;
        }
        return adapterForPlanting(level, cropPos) != null
                && this.npc.hasItem(adapterForPlanting(level, cropPos)
                        .getSeed(level, cropPos, level.getBlockState(plot)));
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
