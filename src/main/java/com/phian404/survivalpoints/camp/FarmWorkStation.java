package com.phian404.survivalpoints.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Рабочая станция фермера: одна клетка, с которой он обслуживает несколько
 * грядок, поворачивая голову, а не переходя между ними.
 *
 * <p>Модель вычисляется из мира, а не берётся из структуры: кандидаты — клетки,
 * соседние с грядками, пригодные для стоянки по тем же критериям, что и клетка
 * подхода ({@link FarmApproach#standCheck}). Победитель — максимум видимых
 * грядок в радиусе {@link #WORK_REACH}; при равенстве предпочитается клетка
 * ближе к центроиду грядок (у фермы-кольца это и есть люк с водой) и лишь
 * затем — ближе к самому NPC. Так «центральный люк» и «автоматический расчёт»
 * работают вместе: структура задаёт геометрию, выбор делает покрытие.
 */
public final class FarmWorkStation {

    /**
     * Горизонтальная дальность работы от центра клетки станции.
     *
     * <p>Игрок достаёт блоки с 4.5 блоков; 4.0 оставляет запас и покрывает
     * кольцо из восьми грядок вокруг люка с любой внешней клетки.
     */
    public static final double WORK_REACH = 4.0D;

    /** Допустимый перепад высоты между станцией и грядкой. */
    public static final int MAX_VERTICAL_OFFSET = 1;

    private static final double WORK_REACH_SQR = WORK_REACH * WORK_REACH;
    private static final double EYE_HEIGHT = 1.6D;

    private FarmWorkStation() {
    }

    /** Может ли фермер обработать грядку, оставаясь в этой клетке. */
    public static boolean canWorkFrom(ServerLevel level, BlockPos stand, BlockPos plot) {
        if (Math.abs(plot.getY() - stand.getY()) > MAX_VERTICAL_OFFSET) {
            return false;
        }
        if (FarmApproach.horizontalDistSqr(stand, plot) > WORK_REACH_SQR) {
            return false;
        }
        return sees(level, stand, plot);
    }

    /** Сколько из переданных грядок обслуживается со станции. */
    public static int coverage(ServerLevel level, BlockPos stand, Collection<BlockPos> plots) {
        int count = 0;
        for (BlockPos plot : plots) {
            if (canWorkFrom(level, stand, plot)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Лучшая станция для набора грядок или {@code null}, если ни одна клетка
     * рядом с ними не пригодна для стоянки.
     *
     * @param from текущая позиция NPC — последний критерий при равном покрытии
     */
    @Nullable
    public static BlockPos best(ServerLevel level, CampRecord camp, Collection<BlockPos> plots,
                                BlockPos from, double bodyWidth, double bodyHeight) {
        List<BlockPos> cells = candidates(level, camp, plots, bodyWidth, bodyHeight);
        if (cells.isEmpty()) {
            return null;
        }
        BlockPos anchor = centroid(plots);

        // Первый проход — только геометрия, без лучей: он отсекает клетки,
        // которые заведомо хуже лучшей по числу грядок в радиусе.
        Map<BlockPos, Integer> geometric = new HashMap<>(cells.size());
        int bestGeometric = 0;
        for (BlockPos cell : cells) {
            int count = 0;
            for (BlockPos plot : plots) {
                if (inReach(cell, plot)) {
                    count++;
                }
            }
            geometric.put(cell, count);
            bestGeometric = Math.max(bestGeometric, count);
        }

        BlockPos best = null;
        int bestCoverage = 0;
        double bestAnchorDist = Double.MAX_VALUE;
        double bestNpcDist = Double.MAX_VALUE;
        for (BlockPos cell : cells) {
            if (geometric.get(cell) < bestGeometric - 1) {
                continue;
            }
            int count = coverage(level, cell, plots);
            double anchorDist = FarmApproach.horizontalDistSqr(cell, anchor);
            double npcDist = FarmApproach.horizontalDistSqr(cell, from);
            if (best == null || count > bestCoverage
                    || (count == bestCoverage && anchorDist < bestAnchorDist)
                    || (count == bestCoverage && anchorDist == bestAnchorDist
                            && npcDist < bestNpcDist)) {
                best = cell;
                bestCoverage = count;
                bestAnchorDist = anchorDist;
                bestNpcDist = npcDist;
            }
        }
        return best;
    }

    /** Грядки плана в порядке обхода от станции: ближние первыми. */
    public static List<BlockPos> orderedFrom(BlockPos stand, Collection<BlockPos> plots) {
        List<BlockPos> ordered = new ArrayList<>(plots);
        ordered.sort((a, b) -> Double.compare(
                FarmApproach.horizontalDistSqr(stand, a),
                FarmApproach.horizontalDistSqr(stand, b)));
        return ordered;
    }

    /**
     * Клетки-кандидаты: соседи грядок, пригодные для стоянки.
     *
     * <p>Стоять в воде можно только если сухих вариантов нет вовсе: фермы на
     * воде держатся на мостках из источников, но постоянная станция в воде
     * выглядит неестественно, когда рядом есть сухая клетка.
     */
    public static List<BlockPos> candidates(ServerLevel level, CampRecord camp,
                                            Collection<BlockPos> plots,
                                            double bodyWidth, double bodyHeight) {
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> dry = new ArrayList<>();
        List<BlockPos> wet = new ArrayList<>();
        for (BlockPos plot : plots) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos cell = plot.relative(dir).immutable();
                if (!seen.add(cell)) {
                    continue;
                }
                if (FarmApproach.standCheck(level, camp, cell, bodyWidth, bodyHeight) != null) {
                    continue;
                }
                if (level.getBlockState(cell).getFluidState().isSource()) {
                    wet.add(cell);
                } else {
                    dry.add(cell);
                }
            }
        }
        return dry.isEmpty() ? wet : dry;
    }

    /** Центроид грядок по горизонтали: «центр фермы» для предпочтения. */
    public static BlockPos centroid(Collection<BlockPos> plots) {
        double x = 0.0D;
        double z = 0.0D;
        int y = 0;
        for (BlockPos plot : plots) {
            x += plot.getX();
            z += plot.getZ();
            y += plot.getY();
        }
        int n = Math.max(1, plots.size());
        return BlockPos.containing(x / n, (double) y / n, z / n);
    }

    private static boolean inReach(BlockPos stand, BlockPos plot) {
        return Math.abs(plot.getY() - stand.getY()) <= MAX_VERTICAL_OFFSET
                && FarmApproach.horizontalDistSqr(stand, plot) <= WORK_REACH_SQR;
    }

    /**
     * Прямая видимость культуры из точки глаз станции.
     *
     * <p>Луч идёт в центр блока культуры; собственная форма пшеницы не
     * коллизионная, поэтому попадание в грядку или промах считаются успехом,
     * а попадание в любой другой блок — препятствием.
     */
    private static boolean sees(ServerLevel level, BlockPos stand, BlockPos plot) {
        Vec3 eye = new Vec3(stand.getX() + 0.5D, stand.getY() + EYE_HEIGHT, stand.getZ() + 0.5D);
        Vec3 target = Vec3.atCenterOf(plot.above());
        BlockHitResult hit = level.clip(new ClipContext(eye, target,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        return hit.getBlockPos().equals(plot.above()) || hit.getBlockPos().equals(plot);
    }
}
