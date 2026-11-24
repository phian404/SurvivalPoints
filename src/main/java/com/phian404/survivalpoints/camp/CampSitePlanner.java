package com.phian404.survivalpoints.camp;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructurePiece;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * План лагеря и предпроверка участка.
 *
 * <p>Порядок создания лагеря: сначала рассчитать план и проверить участок, и
 * только при успехе писать {@link CampRecord} в {@link CampSavedData} и ставить
 * блоки. Обратный порядок (создать запись, потом пытаться строить) оставляет в
 * мире лагерь-призрак: запись есть, блоков нет, а слоты указывают в воздух.
 * Поэтому все причины отказа собраны здесь, до записи.
 *
 * <p>Проверки участка:
 * <ul>
 *     <li>перепад высот по площадке не больше {@link CampTuning#CAMP_MAX_HEIGHT_DELTA};</li>
 *     <li>нет воды и лавы на площадке — периметр на воде утонул бы, а пашня
 *     фермы в затопленной низине превратилась бы в болото;</li>
 *     <li>площадка не пересекается с уже существующими лагерями — иначе два
 *     периметра встанут друг в друге;</li>
 *     <li>в зоне подготовки нет {@code BlockEntity} — выравнивание снесло бы
 *     их вместе с содержимым.</li>
 * </ul>
 *
 * <p>Высота клетки — первый воздушный блок над настоящим грунтом
 * ({@code surfaceAirY = groundBlockY + 1}). Стволы, листва и заменяемая
 * растительность при съёме высот пропускаются, поэтому перепад считается по
 * рельефу, а не по деревьям. Пол двора — медиана этих высот: выравнивание и
 * подсыпает низины, и срезает бугры, поэтому ярус выбирается по середине.
 *
 * <p>План — данные, а не блоки: {@link CampGenerator} использует его как
 * единственный источник решений «где ворота», «куда смотрят слоты»,
 * «на каком ярусе пол». Это исключает расхождение между проверкой участка и
 * фактической застройкой.
 */
public final class CampSitePlanner {

    /**
     * Сторона шаблона фермы, с которой у неё вход.
     *
     * <p>В {@code farm_lvl1}/{@code farm_lvl2} дорожка {@code dirt_path} лежит
     * в ряду {@code z = 3}, а забор замкнут по {@code z = 7} и бокам: открыт
     * именно север ({@code -Z}). Ориентация модуля считается от этой стороны,
     * чтобы вход фермы смотрел во двор, а не в забор.
     */
    private static final Direction FARM_ENTRANCE = Direction.NORTH;

    /**
     * Сторона шаблона вышки, с которой у неё вход.
     *
     * <p>В обновлённом {@code watchtower.nbt} (5×8×5) проёмы открыты на
     * <b>севере</b> ({@code z = 0} — воздух между угловыми опорами) и на
     * <b>востоке</b> ({@code x = 4}). Южная и западная стены обшиты листом.
     * Входом считается северная сторона: она первой принимает игрока,
     * поднимающегося от ворот.
     */
    private static final Direction WATCHTOWER_ENTRANCE = Direction.NORTH;

    /**
     * Ширина габарита вышки по X и Z (шаблон {@code 5×8×5}).
     *
     * <p>Вышка занимает угловой квадрат периметра: её стены продолжают стены
     * лагеря, поэтому периметр пропускает эти клетки и примыкает к угловым
     * опорам модуля. Число задано здесь, чтобы планировщик и генератор
     * пользовались одним и тем же значением.
     */
    public static final int WATCHTOWER_FOOTPRINT = 5;

    /** Маркер {@link #findGroundY}: в колонке встречена вода или лава. */
    public static final int FLUID_FOUND = Integer.MAX_VALUE;

    /** Маркер {@link #findGroundY}: грунта в колонке нет. */
    public static final int NO_GROUND = Integer.MIN_VALUE;

    /**
     * Ширина коридора подхода к воротам в блоках.
     *
     * <p>3 блока: клетка проёма и по одной соседней слева и справа. Одно
     * значение для оценки ориентации и для правки рельефа — иначе оценка и
     * генерация описывали бы разные коридоры.
     */
    public static final int APPROACH_WIDTH = 3;

    /**
     * Перепад, который игрок переступает без правки рельефа (в блоках).
     *
     * <p>Один блок — обычная ступенька. Клетка с большим перепадом требует
     * среза или подсыпки, поэтому считается неудобной для подхода.
     */
    public static final int APPROACH_STEP_LIMIT = 1;

    /** Отступ центра фермы от локального края по X (в клетках). */
    private static final int FARM_INSET_X = 5;

    /** Отступ центра фермы от локального края по Z (в клетках). */
    private static final int FARM_INSET_Z = WATCHTOWER_FOOTPRINT;

    /** Сдвиг радио-якоря от центра к локальному югу (в клетках). */
    private static final int RADIO_OFFSET_Z = 2;

    /**
     * Порядок перебора ориентаций лагеря.
     *
     * <p>Первым идёт юг: на ровной площадке все стороны равноценны, и
     * прежнее поведение (ворота на юге) сохраняется. Далее — по часовой
     * стрелке, чтобы порядок был предсказуемым и не зависел от реализации.
     */
    private static final Direction[] ORIENTATIONS = {
            Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST};

    /**
     * Максимальное смещение центра кандидата от запроса (в блоках).
     *
     * <p>Ограничение по горизонтали: поиск не уходит в неизвестные далёкие
     * чанки и остаётся предсказуемым для игрока, поставившего радио.
     */
    public static final int SITE_SEARCH_RADIUS = 6;

    /** Шаг сетки кандидатов (в блоках). */
    public static final int SITE_SEARCH_STEP = 2;

    /**
     * Предел числа проверенных кандидатов.
     *
     * <p>Бюджет фиксирован и покрывает всю сетку радиуса 6 с шагом 2:
     * {@code (2 * 6 / 2 + 1)^2 = 49} точек. Внешнее кольцо также участвует
     * в выборе, а незагруженные точки не расходуют бюджет.
     */
    public static final int SITE_SEARCH_MAX_CANDIDATES = 49;

    private CampSitePlanner() {
    }

    /**
     * План застройки лагеря.
     *
     * <p>{@link #getRequestPos()} — исходная точка запроса, а центр площадки,
     * ярус пола и позиция радио-якоря — независимые результаты планирования.
     * Кандидат один: центр прежний, а пол выбран по медиане высот площадки.
     * Генератор не должен выводить геометрию или высоту из точки запроса.
     *
     * <p>Все позиции хранятся в мировых координатах, но рассчитаны в
     * <b>локальной сетке</b> периметра: клетки {@code 0..2*radius} по X и Z,
     * где {@code 0} — северо-западный угол, {@code 2*radius} — юго-восточный.
     * Планировка описана один раз для локального юга и переносится в мир
     * поворотом {@link #getLayoutRotation()}, поэтому геометрия не зависит от
     * того, куда в мире смотрят ворота.
     *
     * <p>Повороты модулей отдельные: у шаблонов разные стороны входа
     * (см. константы {@code *_ENTRANCE}), и общий угол разворота всех
     * модулей сразу поставил бы вход одной из построек к забору.
     */
    public static final class Plan {

        private final BlockPos requestPos;
        private final BlockPos center;
        private final int radius;
        private final int floorY;
        private final int medianY;
        private final Rotation layoutRotation;
        private final Rotation watchtowerRotation;
        private final Rotation farmRotation;
        private final BlockPos gatePos;
        private final BlockPos watchtowerPos;
        private final BlockPos farmPos;
        private final BlockPos radioPos;
        private final int[] surfaceAirY;
        private final BlockState[] surfaceStates;

        private Plan(BlockPos requestPos, BlockPos center, int radius, int floorY, int medianY,
                     Rotation layoutRotation, Rotation watchtowerRotation, Rotation farmRotation,
                     BlockPos gatePos, BlockPos watchtowerPos, BlockPos farmPos, BlockPos radioPos,
                     int[] surfaceAirY, BlockState[] surfaceStates) {
            this.requestPos = requestPos;
            this.center = center;
            this.radius = radius;
            this.floorY = floorY;
            this.medianY = medianY;
            this.layoutRotation = layoutRotation;
            this.watchtowerRotation = watchtowerRotation;
            this.farmRotation = farmRotation;
            this.gatePos = gatePos;
            this.watchtowerPos = watchtowerPos;
            this.farmPos = farmPos;
            this.radioPos = radioPos;
            this.surfaceAirY = surfaceAirY;
            this.surfaceStates = surfaceStates;
        }

        /** Исходная точка запроса; не является центром площадки или якорем. */
        public BlockPos getRequestPos() {
            return this.requestPos;
        }

        /**
         * Центр лагеря — геометрический центр двора.
         *
         * <p>Не путать с {@link #getRadioPos()}: радио-якорь стоит ближе к
         * дорожке, чтобы не занимать середину двора, где проходит развязка
         * дорожек. Центр нужен для поиска лагеря по точке и для расчёта
         * локальной сетки.
         */
        public BlockPos getCenter() {
            return this.center;
        }

        public int getRadius() {
            return this.radius;
        }

        /** Сторона периметра в блоках ({@code 2*radius+1}). */
        public int getSide() {
            return this.radius * 2 + 1;
        }

        /**
         * Перевести локальную клетку сетки в мировую точку.
         *
         * <p>Смещение от центра поворачивается на {@link #getLayoutRotation()},
         * поэтому один и тот же расчёт планировки даёт ворота на любой из
         * четырёх сторон.
         *
         * @param localX клетка по X ({@code 0..2*radius}, {@code 0} — локальный запад)
         * @param localZ клетка по Z ({@code 0..2*radius}, {@code 0} — локальный север)
         * @param y      ярус в мире
         * @return мировая точка
         */
        public BlockPos localToWorld(int localX, int localZ, int y) {
            return worldFromLocal(this.center, this.radius, this.layoutRotation, localX, localZ, y);
        }

        /**
         * Ярус пола двора: первый воздушный блок над поверхностью.
         * На нём стоят посты, секции и модули.
         */
        public int getFloorY() {
            return this.floorY;
        }

        /**
         * Медиана высот клеток — выбранный ярус пола лагеря.
         *
         * <p>Значения карты — первые воздушные блоки над грунтом, поэтому
         * медиана уже использует конвенцию {@code floorY} и не требует
         * дополнительного смещения. Она равна {@link #getFloorY()}; оба поля
         * сохранены для статистики и последующих этапов оценки.
         */
        public int getMedianY() {
            return this.medianY;
        }

        /**
         * Поворот локальной сетки планировки.
         *
         * <p>Локальный юг (сторона ворот) после поворота смотрит в
         * {@link #getGateFacing()}. Один и тот же угол применяется к сетке,
         * к поворотам шаблонов модулей и к осям панелей периметра.
         */
        public Rotation getLayoutRotation() {
            return this.layoutRotation;
        }

        /** Мировое направление, в которое ворота смотрят наружу. */
        public Direction getGateFacing() {
            return this.layoutRotation.rotate(Direction.SOUTH);
        }

        /** Ось стены с воротами в мировых координатах. */
        public Direction.Axis getGateWallAxis() {
            return this.getGateFacing().getAxis() == Direction.Axis.Z
                    ? Direction.Axis.X : Direction.Axis.Z;
        }

        /**
         * Ось локальной сетки в мировых координатах.
         *
         * <p>Ось выводится из фактического поворота опорного направления:
         * поворот на 90° меняет оси местами, на 180° оставляет их прежними.
         * Нужен панелям и проволоке — их ось должна совпасть со стеной.
         *
         * @param localAxis ось в локальной сетке
         * @return та же ось после поворота планировки
         */
        public Direction.Axis worldAxis(Direction.Axis localAxis) {
            Direction anchor = localAxis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
            return this.layoutRotation.rotate(anchor).getAxis();
        }

        /** Локальная клетка створки ворот по X. */
        public int getGateLocalX() {
            return this.radius;
        }

        /** Локальная клетка радио-якоря по X. */
        public int getRadioLocalX() {
            return this.radius;
        }

        /** Локальная клетка радио-якоря по Z. */
        public int getRadioLocalZ() {
            return this.radius + RADIO_OFFSET_Z;
        }

        /** Локальная клетка центра фермы по X. */
        public int getFarmLocalX() {
            return this.radius * 2 - FARM_INSET_X;
        }

        /** Локальная клетка центра фермы по Z. */
        public int getFarmLocalZ() {
            return FARM_INSET_Z;
        }

        /**
         * Поворот вышки: вход шаблона смотрит во двор.
         *
         * <p>Вышка стоит в локальном юго-западном углу периметра, поэтому при
         * любом повороте планировки её северная и восточная открытые стороны
         * остаются обращёнными внутрь двора.
         */
        public Rotation getWatchtowerRotation() {
            return this.watchtowerRotation;
        }

        /** Поворот фермы: вход шаблона развёрнут во двор (на юг). */
        public Rotation getFarmRotation() {
            return this.farmRotation;
        }

        /** Позиция створки ворот (нижняя половина двери). */
        public BlockPos getGatePos() {
            return this.gatePos;
        }

        /** Точка размещения вышки (центр её габарита). */
        public BlockPos getWatchtowerPos() {
            return this.watchtowerPos;
        }

        /** Точка размещения фермы (центр её габарита). */
        public BlockPos getFarmPos() {
            return this.farmPos;
        }

        /** Точка размещения радио-якоря. */
        public BlockPos getRadioPos() {
            return this.radioPos;
        }

        /**
         * Фактическая высота свободного блока над грунтом в клетке площадки.
         *
         * <p>Генератор выравнивает каждую клетку относительно
         * {@link #getFloorY()}: разница между этой высотой и полом двора —
         * столько блоков нужно подсыпать или срезать.
         *
         * @param localX клетка по X ({@code 0..2*radius})
         * @param localZ клетка по Z ({@code 0..2*radius})
         * @return первый воздушный ярус над грунтом этой клетки
         */
        public int cellSurfaceY(int localX, int localZ) {
            return this.surfaceAirY[sampleIndex(localX, localZ)];
        }

        /**
         * Блок, лежавший на поверхности клетки до выравнивания.
         *
         * <p>Сохраняется, чтобы вернуть на {@code floorY - 1} именно
         * {@code grass_block} или {@code sand}, а не голый {@code dirt}:
         * иначе выровненный двор выглядел бы искусственной насыпью.
         *
         * @param localX клетка по X ({@code 0..2*radius})
         * @param localZ клетка по Z ({@code 0..2*radius})
         * @return состояние поверхностного блока клетки
         */
        public BlockState cellSurfaceState(int localX, int localZ) {
            return this.surfaceStates[sampleIndex(localX, localZ)];
        }

        /**
         * Индекс карты высот для локальной клетки планировки.
         *
         * <p>Карта снята в мировых осях ещё до выбора ориентации, поэтому
         * локальное смещение клетки переводится в мировое тем же поворотом,
         * что и {@link #localToWorld}. Без этого после поворота планировки
         * высоты читались бы из чужих колонок.
         *
         * @param localX локальная клетка по X
         * @param localZ локальная клетка по Z
         * @return индекс в {@link #surfaceAirY} и {@link #surfaceStates}
         */
        private int sampleIndex(int localX, int localZ) {
            BlockPos world = localToWorld(localX, localZ, 0);
            int cells = getSide();
            return (world.getX() - this.center.getX() + this.radius) * cells
                    + (world.getZ() - this.center.getZ() + this.radius);
        }
    }

    /**
     * Результат проверки: план либо причина отказа.
     */
    public static final class Outcome {

        private final Plan plan;
        private final String refusal;

        private Outcome(Plan plan, String refusal) {
            this.plan = plan;
            this.refusal = refusal;
        }

        public boolean isSuccess() {
            return this.plan != null;
        }

        @Nullable
        public Plan getPlan() {
            return this.plan;
        }

        @Nullable
        public String getRefusal() {
            return this.refusal;
        }
    }

    /**
     * Проверить участок и построить план.
     *
     * <p>Перебирается ограниченная сетка центров вокруг {@code requestPos}.
     * Для каждого центра проверяются рельеф, жидкости, пересечения, зона
     * подготовки и четыре ориентации. Все чтения идут по уже загруженным
     * чанкам: поиск не вызывает генерацию и не меняет мир. После выбора
     * {@link CampManager} выполняет подготовку и перенос радио.
     *
     * @param level      серверный уровень
     * @param requestPos исходная точка запроса (где игрок поставил радио)
     * @param radius     радиус лагеря
     * @return план или причина отказа
     */
    public static Outcome plan(ServerLevel level, BlockPos requestPos, int radius) {
        SiteScore best = null;
        String lastRefusal = null;
        int checked = 0;

        for (BlockPos center : candidateCenters(requestPos)) {
            if (checked >= SITE_SEARCH_MAX_CANDIDATES) {
                break;
            }
            if (!isAreaLoaded(level, center, radius)) {
                continue;
            }
            checked++;

            SiteCandidate candidate = evaluateSite(level, requestPos, center, radius);
            if (candidate.plan != null) {
                SiteScore score = new SiteScore(candidate.plan, candidate.orientation);
                if (best == null || score.isBetterThan(best)) {
                    best = score;
                }
            } else if (lastRefusal == null) {
                lastRefusal = candidate.refusal;
            }
        }

        if (best != null) {
            return new Outcome(best.plan, null);
        }
        return new Outcome(null, lastRefusal != null
                ? lastRefusal : "Подходящая площадка не найдена");
    }

    /**
     * Детерминированная сетка центров вокруг запроса.
     *
     * <p>Первым всегда идёт сам {@code requestPos}: на ровной площадке он
     * выигрывает по нулевому смещению. Затем кандидаты сортируются по
     * расстоянию до запроса, а при равном — по координатам. Это убирает
     * зависимость от порядка обхода чанков и от случайных чисел.
     *
     * @param requestPos исходная точка
     * @return список центров в порядке приоритета
     */
    private static List<BlockPos> candidateCenters(BlockPos requestPos) {
        List<BlockPos> centers = new ArrayList<>();
        for (int dx = -SITE_SEARCH_RADIUS; dx <= SITE_SEARCH_RADIUS; dx += SITE_SEARCH_STEP) {
            for (int dz = -SITE_SEARCH_RADIUS; dz <= SITE_SEARCH_RADIUS; dz += SITE_SEARCH_STEP) {
                centers.add(new BlockPos(requestPos.getX() + dx, requestPos.getY(),
                        requestPos.getZ() + dz));
            }
        }
        centers.sort((a, b) -> {
            int da = squareDistance(a, requestPos);
            int db = squareDistance(b, requestPos);
            if (da != db) {
                return Integer.compare(da, db);
            }
            if (a.getX() != b.getX()) {
                return Integer.compare(a.getX(), b.getX());
            }
            return Integer.compare(a.getZ(), b.getZ());
        });
        return centers;
    }

    /** Квадрат горизонтального расстояния между точками. */
    private static int squareDistance(BlockPos a, BlockPos b) {
        int dx = a.getX() - b.getX();
        int dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Проверить, что все чтения создания лагеря покрыты загруженными чанками.
     *
     * <p>Площадка и зона подготовки занимают {@code radius + CAMP_CLEAR_MARGIN}.
     * Дальше {@link CampGenerator#probeOuterTerrainY} читает рельеф за полосой
     * перехода: база — {@code radius + margin + 1}, затем ещё
     * {@code EDGE_PROBE_STEPS - 1} шагов. Поэтому общий радиус равен
     * {@code radius + margin + EDGE_PROBE_STEPS}; для текущих значений это
     * {@code 10 + 3 + 3 = 16}.
     *
     * <p>Используется только {@code hasChunk}: метод не запрашивает чанк и не
     * запускает генерацию. Незагруженный кандидат пропускается без ошибки.
     *
     * @param level  серверный уровень
     * @param center центр кандидата
     * @param radius радиус лагеря
     * @return {@code true}, если все нужные чанки уже есть
     */
    private static boolean isAreaLoaded(ServerLevel level, BlockPos center, int radius) {
        int extent = radius + CampTuning.CAMP_CLEAR_MARGIN + CampGenerator.EDGE_PROBE_STEPS;
        int minX = (center.getX() - extent) >> 4;
        int maxX = (center.getX() + extent) >> 4;
        int minZ = (center.getZ() - extent) >> 4;
        int maxZ = (center.getZ() + extent) >> 4;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (!level.getChunkSource().hasChunk(cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Полностью оценить один центр: рельеф, высоту, ориентацию и зону подготовки.
     *
     * @param level      серверный уровень
     * @param requestPos исходная точка запроса
     * @param center     центр кандидата
     * @param radius     радиус лагеря
     * @return план либо причина отказа
     */
    private static SiteCandidate evaluateSite(ServerLevel level, BlockPos requestPos,
                                              BlockPos center, int radius) {
        String conflict = findCampConflict(level, center, radius);
        if (conflict != null) {
            return SiteCandidate.refused(conflict);
        }

        int half = radius;
        int minX = center.getX() - half;
        int minZ = center.getZ() - half;
        int cells = radius * 2 + 1;
        int[] surfaceAirY = new int[cells * cells];
        BlockState[] surfaceStates = new BlockState[cells * cells];
        int[] stats = new int[cells * cells];
        int statsCount = 0;

        for (int lx = 0; lx < cells; lx++) {
            for (int lz = 0; lz < cells; lz++) {
                int x = minX + lx;
                int z = minZ + lz;
                // Исходное радио исключается по его фактической колонке: блок
                // стоит в requestPos, а не в центре выбранной площадки.
                boolean isRequestColumn = x == requestPos.getX() && z == requestPos.getZ();
                int groundY = isRequestColumn
                        ? findGroundY(level, x, z, requestPos.getY())
                        : findGroundY(level, x, z);
                if (groundY == FLUID_FOUND) {
                    return SiteCandidate.refused("Вода или лава на площадке у " + x + "," + z);
                }
                if (groundY == NO_GROUND) {
                    return SiteCandidate.refused("Нет грунта на площадке у " + x + "," + z);
                }
                int cell = lx * cells + lz;
                surfaceAirY[cell] = groundY + 1;
                surfaceStates[cell] = level.getBlockState(new BlockPos(x, groundY, z));
                stats[statsCount++] = groundY + 1;
            }
        }

        int medianY = median(stats, statsCount);
        int floorY = medianY;
        int maxDeviation = 0;
        for (int i = 0; i < statsCount; i++) {
            maxDeviation = Math.max(maxDeviation, Math.abs(stats[i] - floorY));
        }
        if (maxDeviation > CampTuning.CAMP_MAX_HEIGHT_DELTA) {
            return SiteCandidate.refused("Слишком неровная площадка: отклонение от пола "
                    + maxDeviation + " блоков, допустимо " + CampTuning.CAMP_MAX_HEIGHT_DELTA
                    + " (медианный уровень участка " + medianY + ")");
        }

        String structureConflict = findStructureConflict(level, center, radius, floorY);
        if (structureConflict != null) {
            return SiteCandidate.refused(structureConflict);
        }

        OrientationScore orientation = chooseOrientation(level, center, radius, floorY);
        Plan plan = buildPlan(requestPos, center, radius, floorY, medianY,
                orientation.rotation, surfaceAirY, surfaceStates);

        String blocked = checkPreparationZone(level, plan);
        if (blocked != null) {
            return SiteCandidate.refused(blocked);
        }
        return SiteCandidate.accepted(plan, orientation);
    }

    /**
     * Собрать план для уже выбранных центра, высоты и ориентации.
     *
     * @param requestPos    исходная точка запроса
     * @param center        выбранный центр
     * @param radius        радиус лагеря
     * @param floorY        выбранный ярус пола
     * @param medianY       медиана высот площадки
     * @param layoutRotation поворот локальной сетки
     * @param surfaceAirY   карта высот
     * @param surfaceStates карта поверхностных блоков
     * @return готовый план
     */
    private static Plan buildPlan(BlockPos requestPos, BlockPos center, int radius, int floorY,
                                  int medianY, Rotation layoutRotation, int[] surfaceAirY,
                                  BlockState[] surfaceStates) {
        int side = radius * 2;
        BlockPos gatePos = worldFromLocal(center, radius, layoutRotation, radius, side, floorY);
        int watchtowerCenterLocalX = WATCHTOWER_FOOTPRINT / 2;
        int watchtowerCenterLocalZ = side - WATCHTOWER_FOOTPRINT / 2;
        BlockPos watchtowerPos = worldFromLocal(center, radius, layoutRotation,
                watchtowerCenterLocalX, watchtowerCenterLocalZ, floorY);
        BlockPos farmPos = worldFromLocal(center, radius, layoutRotation,
                side - FARM_INSET_X, FARM_INSET_Z, floorY - 1);
        BlockPos radioPos = worldFromLocal(center, radius, layoutRotation,
                radius, radius + RADIO_OFFSET_Z, floorY);

        Rotation watchtowerRotation = rotationForFacing(
                layoutRotation.rotate(Direction.NORTH), WATCHTOWER_ENTRANCE);
        Rotation farmRotation = rotationForFacing(
                layoutRotation.rotate(Direction.SOUTH), FARM_ENTRANCE);

        return new Plan(requestPos, center, radius, floorY, medianY, layoutRotation,
                watchtowerRotation, farmRotation, gatePos, watchtowerPos, farmPos, radioPos,
                surfaceAirY, surfaceStates);
    }

    /** Результат оценки одного центра. */
    private static final class SiteCandidate {

        private final Plan plan;
        private final String refusal;
        private final OrientationScore orientation;

        private SiteCandidate(Plan plan, String refusal, OrientationScore orientation) {
            this.plan = plan;
            this.refusal = refusal;
            this.orientation = orientation;
        }

        private static SiteCandidate accepted(Plan plan, OrientationScore orientation) {
            return new SiteCandidate(plan, null, orientation);
        }

        private static SiteCandidate refused(String refusal) {
            return new SiteCandidate(null, refusal, null);
        }
    }

    /**
     * Итоговая оценка допустимого центра.
     *
     * <p>Сравнение лексикографическое, без весов: сначала качество подхода к
     * воротам, затем расстояние центра от запроса, затем координаты. Поэтому
     * исходная точка выигрывает при равном рельефе, а выбор остаётся
     * воспроизводимым.
     */
    private static final class SiteScore {

        private final Plan plan;
        private final OrientationScore orientation;
        private final int distance;

        private SiteScore(Plan plan, OrientationScore orientation) {
            this.plan = plan;
            this.orientation = orientation;
            this.distance = squareDistance(plan.getCenter(), plan.getRequestPos());
        }

        private boolean isBetterThan(SiteScore other) {
            if (this.orientation.isBetterThan(other.orientation)) {
                return true;
            }
            if (other.orientation.isBetterThan(this.orientation)) {
                return false;
            }
            if (this.distance != other.distance) {
                return this.distance < other.distance;
            }
            BlockPos a = this.plan.getCenter();
            BlockPos b = other.plan.getCenter();
            if (a.getX() != b.getX()) {
                return a.getX() < b.getX();
            }
            return a.getZ() < b.getZ();
        }
    }

    /**
     * Перевести локальную клетку планировки в мировую точку.
     *
     * <p>Локальное смещение от центра поворачивается на {@code rotation}.
     * Направления берутся у {@link Rotation#rotate(Direction)}, поэтому
     * планировщик использует ровно тот же поворот, что и вставка шаблонов.
     *
     * @param center   центр площадки
     * @param radius   радиус лагеря
     * @param rotation поворот локальной сетки
     * @param localX   локальная клетка по X
     * @param localZ   локальная клетка по Z
     * @param y        ярус в мире
     * @return мировая точка
     */
    private static BlockPos worldFromLocal(BlockPos center, int radius, Rotation rotation,
                                           int localX, int localZ, int y) {
        int worldX = center.getX();
        int worldZ = center.getZ();
        int dx = localX - radius;
        if (dx != 0) {
            Direction step = rotation.rotate(dx > 0 ? Direction.EAST : Direction.WEST);
            worldX += step.getStepX() * Math.abs(dx);
            worldZ += step.getStepZ() * Math.abs(dx);
        }
        int dz = localZ - radius;
        if (dz != 0) {
            Direction step = rotation.rotate(dz > 0 ? Direction.SOUTH : Direction.NORTH);
            worldX += step.getStepX() * Math.abs(dz);
            worldZ += step.getStepZ() * Math.abs(dz);
        }
        return new BlockPos(worldX, y, worldZ);
    }

    /**
     * Выбрать сторону ворот по удобству подхода.
     *
     * @param level  серверный уровень
     * @param center центр площадки
     * @param radius радиус лагеря
     * @param floorY выбранный ярус пола
     * @return оценка лучшей стороны (вместе с её поворотом)
     */
    private static OrientationScore chooseOrientation(ServerLevel level, BlockPos center,
                                                      int radius, int floorY) {
        OrientationScore best = null;
        for (Direction facing : ORIENTATIONS) {
            OrientationScore candidate =
                    evaluateOrientation(level, center, radius, floorY, facing);
            if (best == null || candidate.isBetterThan(best)) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Оценить подход к воротам для одной стороны.
     *
     * <p>Оценка повторяет правила генератора, но ничего не меняет в мире:
     * коридор шириной {@link #APPROACH_WIDTH} идёт от стены наружу на
     * {@link CampTuning#CAMP_CLEAR_MARGIN} клеток, поверхность ведётся от
     * уровня двора с шагом не более {@link #APPROACH_STEP_LIMIT}, верхняя
     * граница — уровень поверхности двора.
     *
     * <p>Считаются три величины: клетки без подхода (жидкость или пустота),
     * суммарный объём правок рельефа и суммарное отклонение натурального
     * грунта от уровня двора. Все клетки коридора равноценны: отдельные веса
     * не вводятся, а клетка с жидкостью исключает сторону из первого
     * приоритета.
     *
     * @param level  серверный уровень
     * @param center центр площадки
     * @param radius радиус лагеря
     * @param floorY выбранный ярус пола
     * @param facing сторона ворот (наружу)
     * @return оценка стороны
     */
    private static OrientationScore evaluateOrientation(ServerLevel level, BlockPos center,
                                                        int radius, int floorY, Direction facing) {
        int halfWidth = APPROACH_WIDTH / 2;
        int margin = CampTuning.CAMP_CLEAR_MARGIN;
        int courtyardY = floorY - 1;
        Direction lateral = facing.getClockWise();
        int[] previous = new int[halfWidth * 2 + 1];
        Arrays.fill(previous, courtyardY);

        int blocked = 0;
        int rework = 0;
        int deviation = 0;

        for (int step = 1; step <= margin; step++) {
            for (int offset = -halfWidth; offset <= halfWidth; offset++) {
                int distance = radius + step;
                int x = center.getX() + facing.getStepX() * distance
                        + lateral.getStepX() * offset;
                int z = center.getZ() + facing.getStepZ() * distance
                        + lateral.getStepZ() * offset;
                int groundY = findGroundY(level, x, z);
                if (groundY == FLUID_FOUND || groundY == NO_GROUND) {
                    // Подхода здесь нет: подсыпка жидкость не заменяет, а
                    // опорой для следующей строки остаётся прежний уровень.
                    blocked++;
                    continue;
                }
                int index = offset + halfWidth;
                deviation += Math.abs(groundY - courtyardY);
                int maxY = Math.min(previous[index] + APPROACH_STEP_LIMIT, courtyardY);
                int minY = previous[index] - APPROACH_STEP_LIMIT;
                int result;
                if (groundY > maxY) {
                    result = maxY;
                } else if (groundY < minY) {
                    result = minY;
                } else {
                    result = groundY;
                }
                rework += Math.abs(groundY - result);
                previous[index] = result;
            }
        }

        return new OrientationScore(rotationForFacing(facing, Direction.SOUTH),
                blocked, rework, deviation);
    }

    /**
     * Оценка одной ориентации лагеря.
     *
     * <p>Сравнение лексикографическое, без весов: сначала отсутствие
     * непроходимых клеток, затем объём правок рельефа, затем отклонение
     * натурального грунта. Полное совпадение решает порядок
     * {@link #ORIENTATIONS} — юг остаётся предпочтительным, как и раньше.
     */
    private static final class OrientationScore {

        private final Rotation rotation;
        private final int blockedCells;
        private final int reworkBlocks;
        private final int terrainDeviation;

        private OrientationScore(Rotation rotation, int blockedCells, int reworkBlocks,
                                 int terrainDeviation) {
            this.rotation = rotation;
            this.blockedCells = blockedCells;
            this.reworkBlocks = reworkBlocks;
            this.terrainDeviation = terrainDeviation;
        }

        private boolean isBetterThan(OrientationScore other) {
            if ((this.blockedCells == 0) != (other.blockedCells == 0)) {
                return this.blockedCells == 0;
            }
            if (this.blockedCells != other.blockedCells) {
                return this.blockedCells < other.blockedCells;
            }
            if (this.reworkBlocks != other.reworkBlocks) {
                return this.reworkBlocks < other.reworkBlocks;
            }
            return this.terrainDeviation < other.terrainDeviation;
        }
    }

    /**
     * Медиана высот клеток площадки.
     *
     * <p>При чётном количестве клеток медиана — среднее двух центральных
     * значений, округлённое до целого яруса: пол двора обязан быть целым
     * блоком, а дробный результат сдвинул бы выравнивание на полблока.
     *
     * @param values высоты клеток (включая грунт под исходным радио)
     * @param count  сколько значений заполнено
     * @return медианный первый воздушный ярус над поверхностью
     */
    private static int median(int[] values, int count) {
        int[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        if (sorted.length % 2 == 1) {
            return sorted[mid];
        }
        return (int) Math.round((sorted[mid - 1] + sorted[mid]) / 2.0D);
    }

    /**
     * Найти настоящий грунт колонки.
     *
     * <p>Скан идёт сверху вниз от {@link Heightmap.Types#WORLD_SURFACE}: этот
     * тип включает любые блоки на поверхности, поэтому высокие цветы и
     * растения попадают в обзор, а не теряются, как при
     * {@code MOTION_BLOCKING_NO_LEAVES}.
     *
     * <p>Порядок проверок важен: жидкость проверяется первой, потому что вода
     * и лава сами являются {@code canBeReplaced()} и иначе были бы пропущены
     * как обычный воздух над грунтом. Затем пропускаются брёвна, листва,
     * заменяемое и всё неблокирующее — цветы, трава, саженцы.
     *
     * @param level серверный уровень
     * @param x     координата
     * @param z     координата
     * @return Y грунта, либо {@link #FLUID_FOUND}, либо {@link #NO_GROUND}
     */
    public static int findGroundY(ServerLevel level, int x, int z) {
        return findGroundY(level, x, z, Integer.MIN_VALUE);
    }

    /**
     * Найти грунт колонки, пропустив блок на заданном ярусе.
     *
     * <p>Нужно для колонки якоря: блок {@code camp_radio} — полноценный
     * твёрдый блок и по всем признакам выглядит как поверхность, но грунтом
     * не является. Пропуск по ярусу (а не по типу блока) не тянет в
     * планировщик зависимость от реестра блоков и работает для любого
     * якорного блока.
     *
     * @param level серверный уровень
     * @param x     координата
     * @param z     координата
     * @param skipY ярус, который нужно пропустить ({@code Integer.MIN_VALUE} — не пропускать)
     * @return Y грунта, либо {@link #FLUID_FOUND}, либо {@link #NO_GROUND}
     */
    public static int findGroundY(ServerLevel level, int x, int z, int skipY) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        for (int y = top; y >= level.getMinBuildHeight(); y--) {
            if (y == skipY) {
                continue;
            }
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (!state.getFluidState().isEmpty()) {
                return FLUID_FOUND;
            }
            if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                    || state.canBeReplaced() || !state.blocksMotion()) {
                continue;
            }
            return y;
        }
        return NO_GROUND;
    }

    /**
     * Проверить, что шаблоны модулей существуют.
     *
     * <p>Делается до записи лагеря: отсутствующий шаблон — обычная ситуация
     * (не собран датапак), и узнавать о ней после создания записи нельзя.
     *
     * @param level серверный уровень
     * @return {@code null}, если всё на месте, иначе причина отказа
     */
    @Nullable
    public static String checkModules(ServerLevel level) {
        String[] modules = {CampGenerator.WATCHTOWER_MODULE,
                CampGenerator.FARM_MODULE_LVL1, CampGenerator.FARM_MODULE_LVL2};
        for (String moduleId : modules) {
            String missing = checkTemplate(level, moduleId);
            if (missing != null) {
                return missing;
            }
        }
        return null;
    }

    @Nullable
    private static String checkTemplate(ServerLevel level, String moduleId) {
        ResourceLocation id = ResourceLocation.parse(moduleId);
        if (level.getStructureManager().get(id).isEmpty()) {
            return "Шаблон модуля " + moduleId + " не найден";
        }
        return null;
    }

    /**
     * Поворот, при котором сторона входа модуля направлена в {@code desired}.
     *
     * <p>{@link Rotation#rotate(Direction)} переводит горизонтальный вектор в
     * повёрнутый, поэтому искомый поворот — тот, для которого
     * {@code rotation.rotate(entrance) == desired}. Сравнение идёт по факту,
     * а не через разность углов: у {@code Direction} нет порядкового номера
     * по кругу, и арифметика над {@code get2DDataValue()} дала бы неверный
     * результат для пар «север/юг».
     *
     * @param desired  направление, куда должен смотреть вход модуля
     * @param entrance сторона шаблона, на которой у него вход
     * @return поворот для {@code StructurePlaceSettings}
     */
    private static Rotation rotationForFacing(Direction desired, Direction entrance) {
        if (Rotation.CLOCKWISE_90.rotate(entrance) == desired) {
            return Rotation.CLOCKWISE_90;
        }
        if (Rotation.CLOCKWISE_180.rotate(entrance) == desired) {
            return Rotation.CLOCKWISE_180;
        }
        if (Rotation.COUNTERCLOCKWISE_90.rotate(entrance) == desired) {
            return Rotation.COUNTERCLOCKWISE_90;
        }
        return Rotation.NONE;
    }

    /**
     * Пересечение площадки с уже сгенерированными структурами мира.
     *
     * <p>Источник — {@link LevelChunk#getAllStarts()} и
     * {@link LevelChunk#getAllReferences()} уже загруженных чанков. Старт
     * структуры хранится в её стартовом чанке, который может лежать за пределами
     * площадки, поэтому дополнительно проверяются ссылки из чанков площадки.
     * Карты содержат все ванильные и модифицированные {@link StructureStart};
     * поиск и генерация структур не вызываются, чанки принудительно не
     * загружаются.
     *
     * <p>Сравниваются не общие габариты старта, а границы отдельных
     * {@link StructurePiece}: у крупных структур общий bounding box может
     * накрывать пустые чанки, и площадку не следует отбрасывать из-за них.
     * Зона лагеря — периметр плюс {@link CampTuning#CAMP_CLEAR_MARGIN}, по
     * высоте — диапазон реально изменяемых блоков (грунт под полом и верх
     * расчистки деревьев), тот же, что в {@link #checkPreparationZone}.
     *
     * @param level  серверный уровень
     * @param center центр кандидата
     * @param radius радиус лагеря
     * @param floorY выбранный ярус пола
     * @return причина отказа или {@code null}
     */
    @Nullable
    private static String findStructureConflict(ServerLevel level, BlockPos center,
                                                int radius, int floorY) {
        int margin = CampTuning.CAMP_CLEAR_MARGIN;
        int minX = center.getX() - radius - margin;
        int maxX = center.getX() + radius + margin;
        int minZ = center.getZ() - radius - margin;
        int maxZ = center.getZ() + radius + margin;
        int minY = floorY - 1 - CampTuning.CAMP_MAX_HEIGHT_DELTA;
        int maxY = floorY + CampTuning.CAMP_CLEAR_HEADROOM;

        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        int minChunkZ = minZ >> 4;
        int maxChunkZ = maxZ >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }

                for (Map.Entry<Structure, StructureStart> entry : chunk.getAllStarts().entrySet()) {
                    String conflict = checkStructureStart(level, entry.getKey(), entry.getValue(),
                            minX, maxX, minY, maxY, minZ, maxZ);
                    if (conflict != null) {
                        return conflict;
                    }
                }

                for (Map.Entry<Structure, LongSet> entry : chunk.getAllReferences().entrySet()) {
                    Structure structure = entry.getKey();
                    for (long reference : entry.getValue()) {
                        int startX = ChunkPos.getX(reference);
                        int startZ = ChunkPos.getZ(reference);
                        LevelChunk startChunk = level.getChunkSource().getChunkNow(startX, startZ);
                        if (startChunk == null) {
                            ResourceLocation id = level.registryAccess()
                                    .registryOrThrow(Registries.STRUCTURE)
                                    .getKey(structure);
                            return "Структура " + (id != null ? id : "?")
                                    + " не проверена: стартовый чанк " + startX + "," + startZ
                                    + " не загружен";
                        }
                        String conflict = checkStructureStart(level, structure,
                                startChunk.getStartForStructure(structure),
                                minX, maxX, minY, maxY, minZ, maxZ);
                        if (conflict != null) {
                            return conflict;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Проверить отдельные части одного старта на пересечение с зоной лагеря.
     */
    @Nullable
    private static String checkStructureStart(ServerLevel level, Structure structure,
                                              @Nullable StructureStart start,
                                              int minX, int maxX, int minY, int maxY,
                                              int minZ, int maxZ) {
        if (start == null || !start.isValid()) {
            return null;
        }
        for (StructurePiece piece : start.getPieces()) {
            BoundingBox box = piece.getBoundingBox();
            if (box.maxX() < minX || box.minX() > maxX
                    || box.maxZ() < minZ || box.minZ() > maxZ
                    || box.maxY() < minY || box.minY() > maxY) {
                continue;
            }
            ResourceLocation id = level.registryAccess()
                    .registryOrThrow(Registries.STRUCTURE)
                    .getKey(structure);
            return "Структура " + (id != null ? id : "?") + " у "
                    + box.minX() + "," + box.minY() + "," + box.minZ();
        }
        return null;
    }

    /**
     * Проверить, что в зоне подготовки площадки нет чужих объектов.
     *
     * <p>Зона — периметр плюс {@link CampTuning#CAMP_CLEAR_MARGIN} блоков
     * наружу: там подсыпается край и подход к воротам, поэтому объекты в этой
     * полосе тоже попадут под изменения. По вертикали проверка идёт от
     * {@code minY - 1} (грунт под самой низкой клеткой) до
     * {@code floorY + CAMP_CLEAR_HEADROOM} (верх расчистки деревьев).
     *
     * <p>Достаточно любого {@code BlockEntity} — сундук, печь, спавнер:
     * выравнивание снесло бы их вместе с содержимым. Полноценная защита
     * построек игрока здесь не делается — это отдельная задача ручного
     * создания лагерей.
     *
     * <p>Колонка исходного запроса пропускается: при установке радио-вышки в
     * игре её собственный {@code CampRadioBlockEntity} уже стоит в мире и иначе
     * блокировал бы создание лагеря.
     *
     * @param level серверный уровень
     * @param plan  рассчитанный план
     * @return причина отказа или {@code null}
     */
    @Nullable
    public static String checkPreparationZone(ServerLevel level, Plan plan) {
        BlockPos requestPos = plan.getRequestPos();
        int margin = CampTuning.CAMP_CLEAR_MARGIN;
        int minX = plan.getCenter().getX() - plan.getRadius() - margin;
        int maxX = plan.getCenter().getX() + plan.getRadius() + margin;
        int minZ = plan.getCenter().getZ() - plan.getRadius() - margin;
        int maxZ = plan.getCenter().getZ() + plan.getRadius() + margin;
        int minY = plan.getFloorY() - 1 - CampTuning.CAMP_MAX_HEIGHT_DELTA;
        int maxY = plan.getFloorY() + CampTuning.CAMP_CLEAR_HEADROOM;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (x == requestPos.getX() && z == requestPos.getZ()) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockEntity(pos) != null) {
                        return "В зоне подготовки найден объект у " + x + "," + y + "," + z;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Пересечение с существующим лагерем.
     *
     * <p>Площадка лагеря — квадрат {@code center ± radius} по X и Z, поэтому
     * проверяются две проекции раздельно. Конфликт есть, если по обеим осям
     * расстояние между центрами меньше суммы радиусов плюс
     * {@link CampTuning#CAMP_MIN_GAP_BETWEEN}. Это покрывает и диагональное
     * пересечение углами, и лагеря разных размеров.
     *
     * @param level  серверный уровень
     * @param center центр рассчитываемой площадки
     * @param radius радиус лагеря
     * @return причина отказа или {@code null}
     */
    @Nullable
    private static String findCampConflict(ServerLevel level, BlockPos center, int radius) {
        CampSavedData data = CampManager.data(level);
        for (CampRecord other : data.getCamps()) {
            int minDistance = radius + other.getRadius() + CampTuning.CAMP_MIN_GAP_BETWEEN;
            int dx = Math.abs(other.getCenter().getX() - center.getX());
            int dz = Math.abs(other.getCenter().getZ() - center.getZ());
            if (dx < minDistance && dz < minDistance) {
                return "Слишком близко к лагерю «" + other.getName() + "»";
            }
        }
        return null;
    }

}