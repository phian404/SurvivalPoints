package com.phian404.survivalpoints.camp.site;

import com.phian404.survivalpoints.camp.CampSitePlanner;
import com.phian404.survivalpoints.camp.CampTuning;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Детерминированные кандидаты автоматических лагерей.
 *
 * <p>Координаты кандидатов — центры фиксированной сетки
 * {@link CampTuning#SITE_CELL_SIZE} и не зависят от сида. Сид влияет только на
 * порядок рассмотрения ячеек ({@link #candidateOrder}). Это осознанный выбор:
 * площадки предсказуемы, а обход детерминирован и воспроизводим.
 */
public final class CampSiteCandidates {

    private static final ResourceKey<Level> OVERWORLD = Level.OVERWORLD;

    private CampSiteCandidates() {
    }

    /**
     * Обратимо упаковать координаты ячейки в ключ журнала.
     *
     * <p>Смешивание с сидом здесь не используется: ключ должен однозначно
     * восстанавливать X/Z, в том числе отрицательные.
     */
    public static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    /** Восстановить координаты ячейки из ключа: {@code {cellX, cellZ}}. */
    public static int[] cellCoords(long cellKey) {
        return new int[]{(int) (cellKey >> 32), (int) (cellKey & 0xFFFFFFFFL)};
    }

    /**
     * Устойчивое смешивание сида, координат ячейки и измерения.
     *
     * <p>Используется только для сортировки кандидатов в проходе. Не простое
     * XOR: каждый компонент входит через умножение на константу и поворот,
     * затем результат пропускается через финальный mix64. Отрицательные
     * координаты корректно преобразуются через {@link Math#floorDiv}.
     */
    public static long candidateOrder(long worldSeed, int cellX, int cellZ,
                                      ResourceKey<Level> dimension) {
        long h = worldSeed;
        h ^= (long) cellX * 0x9E3779B97F4A7C15L;
        h = Long.rotateLeft(h, 31);
        h ^= (long) cellZ * 0xBF58476D1CE4E5B9L;
        h = Long.rotateLeft(h, 27);
        h ^= (long) dimension.location().hashCode() * 0x94D049BB133111EBL;
        return mix64(h);
    }

    private static long mix64(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** Номер ячейки по координате блока (корректно для отрицательных). */
    public static int cellOf(int blockCoord) {
        return Math.floorDiv(blockCoord, CampTuning.SITE_CELL_SIZE);
    }

    /**
     * Центр ячейки в горизонтали, Y = 0. Чистая арифметика, без чтения мира.
     */
    public static BlockPos cellCenterXZ(long cellKey) {
        int[] coords = cellCoords(cellKey);
        return new BlockPos(coords[0] * CampTuning.SITE_CELL_SIZE
                        + CampTuning.SITE_CELL_SIZE / 2,
                0,
                coords[1] * CampTuning.SITE_CELL_SIZE
                        + CampTuning.SITE_CELL_SIZE / 2);
    }

    /**
     * Точка кандидата на поверхности в центре ячейки.
     *
     * <p>Вызывать только после проверки готовности области: метод читает
     * высоту из мира.
     */
    public static BlockPos candidateAnchor(ServerLevel level, long cellKey) {
        BlockPos center = cellCenterXZ(cellKey);
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                center.getX(), center.getZ());
        return new BlockPos(center.getX(), y, center.getZ());
    }

    /** Поверхностный биом — через общий метод планировщика. */
    public static Holder<Biome> surfaceBiome(ServerLevel level, BlockPos pos) {
        return CampSitePlanner.surfaceBiome(level, pos);
    }

    /** Проходит ли биом по разрешённому тегу. */
    public static boolean isAllowedBiome(ServerLevel level, BlockPos pos, TagKey<Biome> tag) {
        return surfaceBiome(level, pos).is(tag);
    }

    /** Разрешено ли измерение для автоматических лагерей. */
    public static boolean isAllowedDimension(ResourceKey<Level> dimension) {
        return OVERWORLD.equals(dimension);
    }

    /** Тег разрешённых биомов по id из {@link CampTuning}. */
    public static TagKey<Biome> allowedBiomeTag() {
        return TagKey.create(net.minecraft.core.registries.Registries.BIOME,
                ResourceLocation.parse(CampTuning.SITE_BIOME_ALLOW_TAG));
    }
}
