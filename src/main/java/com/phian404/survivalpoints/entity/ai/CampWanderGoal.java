package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.UUID;

/**
 * Прогулка жителя по круговой территории лагеря.
 *
 * <p>Граница берётся из {@link CampRecord#getCenter()} и
 * {@link CampRecord#getRadius()} — без учёта радиуса тревоги, который добавляет
 * {@link CampRecord#contains()}. Точка и все узлы построенного маршрута должны
 * лежать внутри круга; при невыполнении условия житель остаётся на месте.
 *
 * <p>Жители без лагерной привязки и лагеря, запись которого не найдена, goal не
 * использует — их поведение не меняется.
 */
public class CampWanderGoal extends Goal {

    /** Сколько попыток выбрать точку внутри круга. */
    private static final int TARGET_ATTEMPTS = 10;

    /** Минимальная дистанция до цели, чтобы прогулка имела смысл. */
    private static final double MIN_TARGET_DISTANCE_SQR = 1.0D;

    /** Пауза между прогулками: от и до тиков. */
    private static final int PAUSE_MIN = 40;
    private static final int PAUSE_MAX = 140;

    /** Скорость передвижения по лагерю. */
    private static final double SPEED = 1.0D;

    private final BaseSurvivorEntity npc;

    /** Оставшиеся тики паузы. */
    private int pauseTicks = 0;

    public CampWanderGoal(BaseSurvivorEntity npc) {
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
        CampRecord camp = camp(level);
        if (camp == null) {
            return false;
        }

        BlockPos target = pickTarget(camp);
        if (target == null) {
            return false;
        }
        if (!this.npc.getNavigation().moveTo(target.getX() + 0.5D, target.getY(),
                target.getZ() + 0.5D, SPEED)) {
            return false;
        }

        Path path = this.npc.getNavigation().getPath();
        if (path == null || !pathInside(camp, path)) {
            this.npc.getNavigation().stop();
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.pauseTicks > 0 || this.npc.level().isClientSide
                || !(this.npc.level() instanceof ServerLevel level)) {
            return false;
        }
        CampRecord camp = camp(level);
        if (camp == null) {
            return false;
        }
        Path path = this.npc.getNavigation().getPath();
        return path != null && !path.isDone() && pathInside(camp, path);
    }

    @Override
    public void stop() {
        this.npc.getNavigation().stop();
        this.pauseTicks = PAUSE_MIN + this.npc.getRandom().nextInt(PAUSE_MAX - PAUSE_MIN + 1);
    }

    /** Лагерь NPC по campId; {@code null} при отсутствии привязки или записи. */
    @Nullable
    private CampRecord camp(ServerLevel level) {
        UUID campId = this.npc.getCampId();
        return campId == null ? null : CampManager.getCamp(level, campId);
    }

    /**
     * Случайная точка внутри круга лагеря.
     *
     * <p>Радиус берётся с запасом на полтора блока внутрь, чтобы цель не лежала
     * вплотную к границе. Точка проверяется по горизонтальной дистанции до
     * центра и по опоре под ногами.
     */
    @Nullable
    private BlockPos pickTarget(CampRecord camp) {
        BlockPos center = camp.getCenter();
        int radius = Math.max(1, camp.getRadius() - 2);
        int floorY = this.npc.blockPosition().getY();

        for (int attempt = 0; attempt < TARGET_ATTEMPTS; attempt++) {
            float angle = this.npc.getRandom().nextFloat() * Mth.TWO_PI;
            float distance = Mth.sqrt(this.npc.getRandom().nextFloat()) * radius;
            int x = center.getX() + Mth.floor(Mth.cos(angle) * distance);
            int z = center.getZ() + Mth.floor(Mth.sin(angle) * distance);
            BlockPos pos = new BlockPos(x, floorY, z);

            if (!insideCircle(center, camp.getRadius(), pos)) {
                continue;
            }
            if (pos.distSqr(this.npc.blockPosition()) < MIN_TARGET_DISTANCE_SQR) {
                continue;
            }
            if (!this.npc.level().getBlockState(pos).isAir()
                    || !this.npc.level().getBlockState(pos.above()).isAir()
                    || !this.npc.level().getBlockState(pos.below()).blocksMotion()) {
                continue;
            }
            return pos;
        }
        return null;
    }

    /** Все узлы маршрута внутри круга лагеря. */
    private static boolean pathInside(CampRecord camp, Path path) {
        BlockPos center = camp.getCenter();
        int radius = camp.getRadius();
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            if (!insideCircle(center, radius, path.getNodePos(i))) {
                return false;
            }
        }
        return true;
    }

    /** Горизонтальная проверка точки по кругу, без радиуса тревоги. */
    private static boolean insideCircle(BlockPos center, int radius, BlockPos pos) {
        double dx = pos.getX() - center.getX();
        double dz = pos.getZ() - center.getZ();
        return dx * dx + dz * dz <= (double) radius * radius;
    }
}
