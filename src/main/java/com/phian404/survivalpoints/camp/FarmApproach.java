package com.phian404.survivalpoints.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Общая проверка боковой позиции взаимодействия с грядкой.
 *
 * <p>Единственный источник критериев отбора клетки подхода: граница лагеря,
 * прозрачность, опора, дистанция до грядки и коллизия гипотетического AABB
 * тела NPC. Проверка строит тело по переданным размерам NPC в позиции
 * кандидата, а не по текущему AABB сущности.
 *
 * <p>Наличие пригодной клетки подхода не гарантирует достижимость пути:
 * поиск пути остаётся ответственностью исполнителя. Проверка используется
 * и диспетчером (до резервации задачи), и исполнителем (при выборе стороны
 * взаимодействия), чтобы оба не расходились в оценке одной и той же грядки.
 */
public final class FarmApproach {

    /**
     * Отступ от границы лагеря. Ноль: ферма стоит у периметра (9×9 при радиусе
     * 5), и при отступе 2 крайние грядки (расстояние² до центра до 20 при
     * пороге 9) выпадали из рабочего круга радиуса 3. Ноль даёт круг радиуса
     * лагеря — все грядки внутри, клетки подхода крайних грядок проходят
     * по границе (максимум расстояние² = 25).
     */
    public static final int BORDER_INSET = 0;

    /**
     * Предельная дистанция от центра NPC до центра целевого блока.
     *
     * <p>Соседняя клетка по горизонтали даёт ровно 1 блок между центрами —
     * этого достаточно, чтобы взаимодействовать с грядкой, не вставая на неё.
     */
    public static final double TARGET_REACH_SQR = 1.0D * 1.0D;

    private FarmApproach() {
    }

    /** Клетка подхода: первая пригодная соседняя клетка грядки, иначе null. */
    @Nullable
    public static BlockPos findApproach(ServerLevel level, CampRecord camp, BlockPos plot,
                                        double bodyWidth, double bodyHeight) {
        List<BlockPos> cells = findApproaches(level, camp, plot, bodyWidth, bodyHeight);
        return cells.isEmpty() ? null : cells.get(0);
    }

    /**
     * Все пригодные клетки подхода (от 0 до 4). Исполнителю нужен весь набор:
     * навигация умеет строить путь сразу к нескольким целям и выбирает
     * ближайшую достижимую, а не первую по порядку сторон.
     */
    public static List<BlockPos> findApproaches(ServerLevel level, CampRecord camp, BlockPos plot,
                                                double bodyWidth, double bodyHeight) {
        // Основной вариант — стоять рядом с грядкой на её уровне. Если так
        // нельзя (грядки вкопаны в землю, как в шаблоне farm_lvl1), годится
        // клетка на уровень выше: это дорожка над соседней землёй.
        List<BlockPos> sameLevel = approachesAt(level, camp, plot, 0, bodyWidth, bodyHeight);
        if (!sameLevel.isEmpty()) {
            return sameLevel;
        }
        return approachesAt(level, camp, plot, 1, bodyWidth, bodyHeight);
    }

    private static List<BlockPos> approachesAt(ServerLevel level, CampRecord camp, BlockPos plot,
                                               int dy, double bodyWidth, double bodyHeight) {
        List<BlockPos> cells = new ArrayList<>(4);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = plot.relative(dir).offset(0, dy, 0);
            if (validate(level, camp, plot, candidate, bodyWidth, bodyHeight) == null) {
                cells.add(candidate);
            }
        }
        return cells;
    }

    /**
     * Причина отказа для кандидата (null — кандидат пригоден). Критерии и их
     * порядок — единственные для всех вызывающих сторон, ничего не ослаблено.
     */
    @Nullable
    public static String validate(ServerLevel level, CampRecord camp, BlockPos plot,
                                  BlockPos pos, double bodyWidth, double bodyHeight) {
        if (horizontalDistSqr(pos, plot) > TARGET_REACH_SQR) {
            return "TOO_FAR distSqr=" + horizontalDistSqr(pos, plot);
        }
        return standCheck(level, camp, pos, bodyWidth, bodyHeight);
    }

    /**
     * Причина, по которой NPC не может стоять в клетке (null — может).
     *
     * <p>Без привязки к конкретной грядке: этим же критерием пользуется
     * рабочая станция, обслуживающая несколько грядок с одной клетки.
     */
    @Nullable
    public static String standCheck(ServerLevel level, CampRecord camp, BlockPos pos,
                                    double bodyWidth, double bodyHeight) {
        BlockPos center = camp.getCenter();
        double dx = pos.getX() - center.getX();
        double dz = pos.getZ() - center.getZ();
        double distToCenter = Math.sqrt(dx * dx + dz * dz);
        if (!insideBorder(camp, pos)) {
            return "INSIDE_BORDER=false distToCenter=" + String.format("%.2f", distToCenter);
        }
        var state = level.getBlockState(pos);
        if (state.isSolidRender(level, pos)) {
            return "SOLID_RENDER block=" + state.getBlock()
                    + " distToCenter=" + String.format("%.2f", distToCenter);
        }
        // Сама клетка может быть водой (мостки из воды): стоять в источнике
        // можно, течения нет, тело проверяется обычным порядком ниже.
        var below = level.getBlockState(pos.below());
        // Вода — допустимая опора: ферма стоит на воде, и крайние клетки
        // подхода опираются на водяные секции. Требуем источник (не поток),
        // иначе фермера унесёт течением.
        if (!below.blocksMotion() && !below.getFluidState().isSource()) {
            return "NO_SUPPORT block=" + below.getBlock() + "@" + pos.below()
                    + " blocksMotion=false";
        }
        double half = bodyWidth / 2.0D;
        double bottom = pos.getY() + 1.0D;
        var supportShape = state.getCollisionShape(level, pos);
        if (!supportShape.isEmpty()) {
            bottom = pos.getY() + supportShape.max(Direction.Axis.Y);
        }
        AABB body = new AABB(
                pos.getX() + 0.5D - half, bottom, pos.getZ() + 0.5D - half,
                pos.getX() + 0.5D + half, bottom + bodyHeight,
                pos.getZ() + 0.5D + half);
        if (!level.noCollision(body)) {
            return "BODY_COLLISION body=" + body + " hits=" + bodyCollisionHits(level, body);
        }
        return null;
    }

    /** Горизонтальное расстояние между центрами клеток, без компоненты Y. */
    public static double horizontalDistSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /** Клетка внутри рабочего круга лагеря (радиус минус отступ). */
    public static boolean insideBorder(CampRecord camp, BlockPos pos) {
        BlockPos center = camp.getCenter();
        int radius = Math.max(0, camp.getRadius() - BORDER_INSET);
        double dx = pos.getX() - center.getX();
        double dz = pos.getZ() - center.getZ();
        return dx * dx + dz * dz <= (double) radius * radius;
    }

    /** Список блоков, пересекающих AABB тела NPC (не более 4 записей). */
    private static List<String> bodyCollisionHits(ServerLevel level, AABB body) {
        List<String> hits = new ArrayList<>();
        for (var shape : level.getBlockCollisions(null, body)) {
            for (AABB box : shape.toAabbs()) {
                var state = level.getBlockState(BlockPos.containing(box.getCenter()));
                if (hits.size() < 4) {
                    hits.add(state.getBlock() + "@" + BlockPos.containing(box.getCenter())
                            + " box=" + box);
                }
            }
        }
        return hits;
    }
}
