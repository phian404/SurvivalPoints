package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.camp.CampGenerator;
import com.phian404.survivalpoints.camp.CampFarmManager;
import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampModuleSlot;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
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
    private static final int TARGET_ATTEMPTS = 16;

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

    /** Выход из фермы должен завершиться, даже если обычная пауза ещё активна. */
    private boolean exitInProgress = false;

    /** Последняя причина запуска/отказа — только для Debug Wand. */
    private String lastDecision = "NOT_CHECKED";
    @Nullable
    private BlockPos lastTarget;
    private int lastPathNodes = -1;
    private boolean lastPathDone = true;
    private int lastExitCandidates;
    private int lastExitReachable;
    private int lastExitAccepted;
    private boolean lastExitUsedFallback;
    private int lastRejectBounds;
    private int lastRejectZone;
    private int lastRejectDistance;
    private int lastRejectClearance;
    private int lastRejectSupport;
    private long lastAttemptTick = -1L;

    /** Краткая диагностика без изменения поведения Goal. */
    public String diagnosticSummary(long gameTime) {
        long age = this.lastAttemptTick < 0L ? -1L : Math.max(0L, gameTime - this.lastAttemptTick);
        CampRecord currentCamp = this.npc.level() instanceof ServerLevel level ? camp(level) : null;
        return "insideFarm=" + (currentCamp != null
                && CampWorkZoneAccess.isInsideFarmZone(this.npc, currentCamp))
                + " decision=" + this.lastDecision
                + " target=" + (this.lastTarget == null ? "—" : this.lastTarget.toShortString())
                + " path=" + (this.lastPathNodes < 0 ? "—"
                : this.lastPathNodes + " nodes/done=" + this.lastPathDone)
                + " exitCandidates=" + this.lastExitCandidates
                + " reachable=" + this.lastExitReachable
                + " accepted=" + this.lastExitAccepted
                + " fallback=" + this.lastExitUsedFallback
                + " reject[bounds=" + this.lastRejectBounds
                + ",zone=" + this.lastRejectZone
                + ",near=" + this.lastRejectDistance
                + ",space=" + this.lastRejectClearance
                + ",support=" + this.lastRejectSupport + "]"
                + " age=" + age + "t";
    }

    public CampWanderGoal(BaseSurvivorEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (this.npc.level().isClientSide || !(this.npc.level() instanceof ServerLevel level)) {
            return false;
        }
        this.lastAttemptTick = level.getGameTime();
        this.lastTarget = null;
        this.lastPathNodes = -1;
        this.lastExitCandidates = 0;
        this.lastExitReachable = 0;
        this.lastExitAccepted = 0;
        this.lastExitUsedFallback = false;
        this.lastRejectBounds = 0;
        this.lastRejectZone = 0;
        this.lastRejectDistance = 0;
        this.lastRejectClearance = 0;
        this.lastRejectSupport = 0;

        CampRecord camp = camp(level);
        if (camp == null) {
            this.lastDecision = "NO_CAMP";
            return false;
        }

        // После окончания работы фермер должен покинуть ферму сразу,
        // а не ждать окончания обычной паузы между прогулками. Но если
        // следующая грядка уже готова — остаёмся на ферме, иначе прогулка
        // уводит NPC наружу и тут же прерывается новой задачей (ping-pong).
        boolean leavingFarm = CampWorkZoneAccess.isInsideFarmZone(this.npc, camp);
        if (leavingFarm) {
            CampFarmManager farmManager = CampManager.farmManager(camp);
            if (farmManager.hasAvailableWork(level, camp, this.npc, level.getGameTime())) {
                this.lastDecision = "WORK_AVAILABLE";
                return false;
            }
        }
        if (this.pauseTicks > 0 && !leavingFarm) {
            this.pauseTicks--;
            this.lastDecision = "PAUSED";
            return false;
        }

        BlockPos target = leavingFarm ? pickFarmExitTarget(camp) : pickTarget(camp);
        if (target == null) {
            this.lastDecision = leavingFarm ? "NO_EXIT_TARGET" : "NO_WANDER_TARGET";
            return false;
        }
        this.lastTarget = target;
        if (!this.npc.getNavigation().moveTo(target.getX() + 0.5D, target.getY(),
                target.getZ() + 0.5D, SPEED)) {
            this.lastDecision = "MOVE_TO_REJECTED";
            return false;
        }

        Path path = this.npc.getNavigation().getPath();
        if (path == null) {
            this.lastDecision = "PATH_NULL";
            this.npc.getNavigation().stop();
            return false;
        }
        this.lastPathNodes = path.getNodeCount();
        this.lastPathDone = path.isDone();
        if (!pathInside(camp, path)) {
            this.lastDecision = "PATH_REJECTED";
            this.npc.getNavigation().stop();
            return false;
        }
        this.exitInProgress = leavingFarm;
        this.lastDecision = leavingFarm
                ? (this.lastExitUsedFallback ? "EXIT_STARTED_FALLBACK" : "EXIT_STARTED")
                : "WANDER_STARTED";
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if ((this.pauseTicks > 0 && !this.exitInProgress) || this.npc.level().isClientSide
                || !(this.npc.level() instanceof ServerLevel level)) {
            return false;
        }
        CampRecord camp = camp(level);
        if (camp == null) {
            return false;
        }
        // Работа появилась посреди выхода с фермы: не доводим прогулку до
        // края, чтобы фермер не ходил «наружу-обратно».
        if (this.exitInProgress
                && CampManager.farmManager(camp).hasAvailableWork(level, camp, this.npc,
                        level.getGameTime())) {
            this.npc.getNavigation().stop();
            this.lastDecision = "WORK_AVAILABLE";
            return false;
        }
        Path path = this.npc.getNavigation().getPath();
        return path != null && !path.isDone() && pathInside(camp, path);
    }

    @Override
    public void stop() {
        this.npc.getNavigation().stop();
        this.exitInProgress = false;
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

        for (int attempt = 0; attempt < TARGET_ATTEMPTS; attempt++) {
            float angle = this.npc.getRandom().nextFloat() * Mth.TWO_PI;
            float distance = Mth.sqrt(this.npc.getRandom().nextFloat()) * radius;
            int x = center.getX() + Mth.floor(Mth.cos(angle) * distance);
            int z = center.getZ() + Mth.floor(Mth.sin(angle) * distance);

            // Высота определяется отдельно для каждой X/Z-точки.
            // Это позволяет выбирать цели на склонах и ступенчатом рельефе,
            // не привязывая прогулку к Y текущего положения NPC.
            int surfaceY = this.npc.level().getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, surfaceY, z);

            if (!insideCircle(center, camp.getRadius(), pos)) {
                this.lastRejectBounds++;
                continue;
            }
            if (!CampWorkZoneAccess.isPositionAllowed(this.npc, camp, pos)) {
                this.lastRejectZone++;
                continue;
            }
            if (pos.distSqr(this.npc.blockPosition()) < MIN_TARGET_DISTANCE_SQR) {
                this.lastRejectDistance++;
                continue;
            }
            if (!hasClearance(pos)) {
                this.lastRejectClearance++;
                continue;
            }
            if (!hasSupport(pos)) {
                this.lastRejectSupport++;
                continue;
            }
            return pos;
        }
        return null;
    }

    /**
     * Выбрать ближайшую достижимую точку за границей фермы. Проверяем
     * периметр bounds, чтобы выход не зависел от случайного выбора цели.
     */
    @Nullable
    private BlockPos pickFarmExitTarget(CampRecord camp) {
        CampModuleSlot farm = camp.getModuleSlot(CampGenerator.FARM_SLOT);
        if (farm == null) {
            return pickTarget(camp);
        }
        CampModuleSlot.SlotBounds bounds = farm.getBounds();
        java.util.List<BlockPos> candidates = new java.util.ArrayList<>();
        for (int x = bounds.getMinX() - 1; x <= bounds.getMaxX() + 1; x++) {
            for (int z = bounds.getMinZ() - 1; z <= bounds.getMaxZ() + 1; z++) {
                if (x != bounds.getMinX() - 1 && x != bounds.getMaxX() + 1
                        && z != bounds.getMinZ() - 1 && z != bounds.getMaxZ() + 1) {
                    continue;
                }
                // Периметр может проходить по склону или ступени. Для каждой
                // X/Z-клетки ищем реальную поверхность, а не используем Y NPC:
                // иначе все точки на другом уровне ошибочно отбрасываются.
                int surfaceY = this.npc.level().getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos pos = new BlockPos(x, surfaceY, z);
                if (!insideCircle(camp.getCenter(), camp.getRadius(), pos)) {
                    this.lastRejectBounds++;
                    continue;
                }
                if (!CampWorkZoneAccess.isPositionAllowed(this.npc, camp, pos)) {
                    this.lastRejectZone++;
                    continue;
                }
                if (!hasClearance(pos)) {
                    this.lastRejectClearance++;
                    continue;
                }
                if (!hasSupport(pos)) {
                    this.lastRejectSupport++;
                    continue;
                }
                candidates.add(pos);
            }
        }
        candidates.sort(java.util.Comparator.comparingDouble(
                pos -> pos.distSqr(this.npc.blockPosition())));
        this.lastExitCandidates = candidates.size();
        for (BlockPos candidate : candidates) {
            Path path = this.npc.getNavigation().createPath(candidate, 0);
            if (path != null && path.canReach()) {
                this.lastExitReachable++;
                if (pathInside(camp, path)) {
                    this.lastExitAccepted++;
                    return candidate;
                }
            }
        }
        this.lastExitUsedFallback = true;
        // Если ближайшие точки периметра перекрыты стеной или декорацией,
        // пробуем обычную цель прогулки: маршрут всё равно обязан выйти
        // из фермы и не может войти обратно.
        return pickTarget(camp);
    }

    /** Достаточно места для тела NPC и головы в целевой клетке. */
    private boolean hasClearance(BlockPos pos) {
        return this.npc.level().getBlockState(pos).isAir()
                && this.npc.level().getBlockState(pos.above()).isAir();
    }

    /** Под целевой клеткой должна быть твёрдая опора. */
    private boolean hasSupport(BlockPos pos) {
        return this.npc.level().getBlockState(pos.below()).blocksMotion();
    }

    /** Все узлы маршрута внутри круга лагеря. */
    private boolean pathInside(CampRecord camp, Path path) {
        BlockPos center = camp.getCenter();
        int radius = camp.getRadius();
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            if (!insideCircle(center, radius, path.getNodePos(i))) {
                return false;
            }
        }
        return CampWorkZoneAccess.isWanderPathAllowed(this.npc, camp, path);
    }

    /** Горизонтальная проверка точки по кругу, без радиуса тревоги. */
    private static boolean insideCircle(BlockPos center, int radius, BlockPos pos) {
        double dx = pos.getX() - center.getX();
        double dz = pos.getZ() - center.getZ();
        return dx * dx + dz * dz <= (double) radius * radius;
    }
}
