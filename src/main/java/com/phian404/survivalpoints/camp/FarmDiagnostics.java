package com.phian404.survivalpoints.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ВРЕМЕННАЯ диагностика потери пшеницы при генерации фермы.
 *
 * <p>Класс не участвует в генерации: он только читает мир и пишет строки в
 * лог. После установления причины его нужно удалить вместе со всеми
 * вызовами {@link #scan} и {@link #describeRange}.
 */
public final class FarmDiagnostics {

    private static final Logger LOGGER = LogUtils.getLogger();

    private FarmDiagnostics() {
    }

    /**
     * Снять снимок посевов в прямоугольной области.
     *
     * <p>Для каждой культуры пишется координата, возраст и блок
     * непосредственно под ней — именно он определяет, переживёт ли посев
     * ближайший {@code updateShape}.
     *
     * @param level  серверный уровень
     * @param stage  метка этапа (видна в логе)
     * @param minX   западная граница включительно
     * @param minY   нижняя граница включительно
     * @param minZ   северная граница включительно
     * @param maxX   восточная граница включительно
     * @param maxY   верхняя граница включительно
     * @param maxZ   южная граница включительно
     */
    public static void scan(ServerLevel level, String stage, int minX, int minY, int minZ,
                            int maxX, int maxY, int maxZ) {
        List<String> crops = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (!state.is(BlockTags.CROPS)) {
                        continue;
                    }
                    BlockPos pos = cursor.immutable();
                    BlockState below = level.getBlockState(pos.below());
                    String age = ageOf(state);
                    crops.add(pos.toShortString() + " a" + age + " under=" + name(below));
                    if (!below.is(net.minecraft.world.level.block.Blocks.FARMLAND)) {
                        broken.add(pos.toShortString() + " under=" + name(below));
                    }
                }
            }
        }
        LOGGER.info("[SP][FARM] {} crops={} broken={} at {}..{}",
                stage, crops.size(), broken.size(),
                new BlockPos(minX, minY, minZ).toShortString(),
                new BlockPos(maxX, maxY, maxZ).toShortString());
        if (!crops.isEmpty()) {
            LOGGER.info("[SP][FARM] {} list: {}", stage, String.join(" | ", crops));
        }
        if (!broken.isEmpty()) {
            LOGGER.info("[SP][FARM] {} BROKEN: {}", stage, String.join(" | ", broken));
        }
    }

    /**
     * Записать границы, по которым работает другой код.
     *
     * @param stage метка
     * @param label имя диапазона
     * @param a     первая граница
     * @param b     вторая граница
     */
    public static void describeRange(String stage, String label, BlockPos a, BlockPos b) {
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX());
        int maxY = Math.max(a.getY(), b.getY());
        int maxZ = Math.max(a.getZ(), b.getZ());
        LOGGER.info("[SP][FARM] {} {} = {}..{} (size {}x{}x{})",
                stage, label,
                new BlockPos(minX, minY, minZ).toShortString(),
                new BlockPos(maxX, maxY, maxZ).toShortString(),
                maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1);
    }

    /**
     * Снять координаты культур до расчистки площадки.
     *
     * <p>Область совпадает с зоной, которую обрабатывает
     * {@code CampGenerator.prepareTerrain}: квадрат площадки по горизонтали и
     * полоса высот от нижней границы перепада до верха расчистки. Снимок
     * нужен, чтобы после подготовки точно знать, какие существующие посевы
     * исчезли и по какому условию.
     *
     * @param level серверный уровень
     * @param plan  план застройки
     * @return карта «координата → состояние» на момент до расчистки
     */
    public static Map<BlockPos, BlockState> snapshotCrops(ServerLevel level,
                                                          CampSitePlanner.Plan plan) {
        Map<BlockPos, BlockState> crops = new LinkedHashMap<>();
        int floorY = plan.getFloorY();
        int side = plan.getSide();
        int minY = floorY - CampTuning.CAMP_MAX_HEIGHT_DELTA;
        int maxY = floorY + CampTuning.CAMP_CLEAR_HEADROOM;
        List<String> list = new ArrayList<>();
        for (int lx = 0; lx < side; lx++) {
            for (int lz = 0; lz < side; lz++) {
                BlockPos column = plan.localToWorld(lx, lz, floorY);
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
                    BlockState state = level.getBlockState(pos);
                    if (!state.is(BlockTags.CROPS)) {
                        continue;
                    }
                    crops.put(pos.immutable(), state);
                    list.add(pos.toShortString() + " a" + ageOf(state));
                }
            }
        }
        LOGGER.info("[SP][TERRAIN] before prepareTerrain crops={}", crops.size());
        if (!list.isEmpty()) {
            LOGGER.info("[SP][TERRAIN] before prepareTerrain list: {}", String.join(" | ", list));
        }
        return crops;
    }

    /**
     * Сравнить культуры до и после расчистки площадки.
     *
     * <p>Для каждой исчезнувшей культуры печатается условие расчистки, под
     * которое она попадала. Культуры, стоящие на земле, не имеют коллизии,
     * поэтому единственный подходящий признак — {@code !blocksMotion()}.
     *
     * @param level  серверный уровень
     * @param plan   план застройки
     * @param before снимок, сделанный до {@code prepareTerrain}
     */
    public static void reportTerrainClear(ServerLevel level, CampSitePlanner.Plan plan,
                                          Map<BlockPos, BlockState> before) {
        List<String> kept = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : before.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState old = entry.getValue();
            BlockState now = level.getBlockState(pos);
            if (now.is(BlockTags.CROPS)) {
                kept.add(pos.toShortString());
                continue;
            }
            removed.add(pos.toShortString() + " " + name(old)
                    + " reason=" + clearReason(old));
        }
        LOGGER.info("[SP][TERRAIN] after prepareTerrain crops={} removed={}",
                kept.size(), removed.size());
        if (!kept.isEmpty()) {
            LOGGER.info("[SP][TERRAIN] after prepareTerrain kept: {}", String.join(" | ", kept));
        }
        if (!removed.isEmpty()) {
            LOGGER.info("[SP][TERRAIN] after prepareTerrain REMOVED: {}",
                    String.join(" | ", removed));
        }
    }

    /**
     * Условие расчистки, под которое попадал блок до подготовки площадки.
     *
     * @param state состояние блока на момент до расчистки
     * @return текстовая метка условия
     */
    private static String clearReason(BlockState state) {
        if (state.is(BlockTags.LOGS)) {
            return "LOGS";
        }
        if (state.is(BlockTags.LEAVES)) {
            return "LEAVES";
        }
        if (state.canBeReplaced()) {
            return "canBeReplaced";
        }
        if (!state.blocksMotion()) {
            return "!blocksMotion";
        }
        return "none";
    }

    /**
     * ВРЕМЕННО: записать результат среза локального возвышения.
     *
     * <p>Строка показывает, что высокая колонка внутри полосы подготовки была
     * обнаружена по собственной высоте, а не по внешней пробе, и до какого
     * яруса её удалось срезать.
     *
     * @param label      метка случая
     * @param pos        координата колонки
     * @param courtyardY уровень поверхности двора
     * @param probeY     высота внешней пробы
     * @param localY     фактическая высота колонки
     * @param targetY    расчётный уровень среза
     * @param resultY    фактический уровень поверхности после среза
     */
    public static void logEdgeCut(String label, BlockPos pos, int courtyardY, int probeY,
                                  int localY, int targetY, int resultY) {
        LOGGER.info("[SP][EDGE] {} at {} courtyard={} probe={} local={} target={} result={}",
                label, pos.toShortString(), courtyardY, probeY, localY, targetY, resultY);
    }

    private static String ageOf(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            return String.valueOf(crop.getAge(state));
        }
        return "?";
    }

    private static String name(BlockState state) {
        String id = state.getBlock().toString();
        if (state.isAir()) {
            return "air";
        }
        return id;
    }
}
