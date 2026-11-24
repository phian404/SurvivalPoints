package com.phian404.survivalpoints.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.Arrays;

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
     * <p>Кандидат один: центр площадки прежний, ярус пола выбирается по
     * медиане высот, а из четырёх сторон ворот берётся та, где подход удобнее
     * по фактическому рельефу. Исходная точка запроса задаёт только центр и
     * колонку установленного радио, которое нужно исключить из съёма рельефа.
     *
     * @param level      серверный уровень
     * @param requestPos исходная точка запроса (где игрок поставил радио)
     * @param radius     радиус лагеря
     * @return план или причина отказа
     */
    public static Outcome plan(ServerLevel level, BlockPos requestPos, int radius) {
        // Единственный кандидат: прежняя площадка вокруг запроса. Её Y не
        // задаёт пол — тот выбирается после съёма высот по медиане.
        BlockPos center = requestPos;

        // Периметр занимает клетки 0..2*radius: сторона 2*radius+1 блок.
        int half = radius;
        int minX = center.getX() - half;
        int maxX = center.getX() + half;
        int minZ = center.getZ() - half;
        int maxZ = center.getZ() + half;

        String conflict = findCampConflict(level, center, radius);
        if (conflict != null) {
            return new Outcome(null, conflict);
        }

        // === Съём высот площадки ===
        // Клетки сетки 0..side по X и Z. Высота каждой клетки — первый
        // воздушный блок над настоящим грунтом. Стволы, листва, заменяемая
        // растительность и всё неблокирующее (цветы, трава) при определении
        // поверхности пропускаются: цветок в 1.20.1 возвращает
        // canBeReplaced() == false, и без проверки blocksMotion() он был бы
        // принят за грунт — отсюда ложные высоты и оставшиеся цветы во дворе.
        int cells = radius * 2 + 1;
        int[] surfaceAirY = new int[cells * cells];
        BlockState[] surfaceStates = new BlockState[cells * cells];
        int[] stats = new int[cells * cells];
        int statsCount = 0;

        for (int lx = 0; lx < cells; lx++) {
            for (int lz = 0; lz < cells; lz++) {
                int x = minX + lx;
                int z = minZ + lz;
                // В колонке исходного радио блок camp_radio не является
                // грунтом: его нужно пропустить, иначе поверхностью считался
                // бы сам якорь, а настоящий грунт под ним остался бы неучтённым.
                // Пропускается именно запрос, а не рассчитываемая позиция якоря.
                boolean isRequestColumn = x == requestPos.getX() && z == requestPos.getZ();
                int groundY = isRequestColumn
                        ? findGroundY(level, x, z, requestPos.getY())
                        : findGroundY(level, x, z);
                if (groundY == FLUID_FOUND) {
                    return new Outcome(null, "Вода или лава на площадке у " + x + "," + z);
                }
                if (groundY == NO_GROUND) {
                    return new Outcome(null, "Нет грунта на площадке у " + x + "," + z);
                }
                int cell = lx * cells + lz;
                surfaceAirY[cell] = groundY + 1;
                surfaceStates[cell] = level.getBlockState(new BlockPos(x, groundY, z));
                stats[statsCount++] = groundY + 1;
            }
        }

        // Карта хранит первый воздушный блок над грунтом. Её медиана — уже
        // ярус floorY, поэтому дополнительное +/-1 здесь не применяется.
        int medianY = median(stats, statsCount);
        int floorY = medianY;

        // Перепад проверяется как максимальное отклонение клетки от пола:
        // выравнивание подсыпает низины и срезает бугры относительно floorY,
        // поэтому допустимо ровно CAMP_MAX_HEIGHT_DELTA в обе стороны.
        int maxDeviation = 0;
        for (int i = 0; i < statsCount; i++) {
            maxDeviation = Math.max(maxDeviation, Math.abs(stats[i] - floorY));
        }
        if (maxDeviation > CampTuning.CAMP_MAX_HEIGHT_DELTA) {
            return new Outcome(null, "Слишком неровная площадка: отклонение от пола "
                    + maxDeviation + " блоков, допустимо " + CampTuning.CAMP_MAX_HEIGHT_DELTA
                    + " (медианный уровень участка " + medianY + ")");
        }

        // === Ориентация ===
        // Центр, радиус и floorY уже выбраны, поэтому четыре варианта
        // отличаются только стороной ворот. Оценка читает натуральный рельеф
        // перед воротами и ничего не меняет в мире.
        Rotation layoutRotation = chooseOrientation(level, center, radius, floorY);

        // === Локальная сетка ===
        // Планировка описана в клетках 0..2*radius: (0,0) — северо-запад,
        // (2*radius, 2*radius) — юго-восток. Ворота и вход — на локальной
        // южной стене, а в мир сетка переносится поворотом layoutRotation.
        int side = radius * 2;

        // Ворота: одна створка ровно в центре стены ворот. Соседние клетки
        // пролёта — глухие панели: они дают двери раму и симметричную обшивку.
        BlockPos gatePos = worldFromLocal(center, radius, layoutRotation,
                radius, side, floorY);

        // Вышка 5×5 стоит в локальном юго-западном углу периметра: её габарит
        // совпадает с угловым квадратом сетки, её стены продолжают стены
        // лагеря, а открытые стороны смотрят внутрь двора.
        int watchtowerCenterLocalX = WATCHTOWER_FOOTPRINT / 2;
        int watchtowerCenterLocalZ = side - WATCHTOWER_FOOTPRINT / 2;
        BlockPos watchtowerPos = worldFromLocal(center, radius, layoutRotation,
                watchtowerCenterLocalX, watchtowerCenterLocalZ, floorY);

        // Ферма 9×9 — в дальнем от ворот углу двора, с отступом от стен.
        // Локальный поворот на 180° разворачивает её вход во двор.
        BlockPos farmPos = worldFromLocal(center, radius, layoutRotation,
                side - FARM_INSET_X, FARM_INSET_Z, floorY - 1);

        // Радио-якорь — рядом с центром, но со сдвигом к локальному югу:
        // в самом центре сходятся дорожки, и якорь перекрыл бы развязку.
        BlockPos radioPos = worldFromLocal(center, radius, layoutRotation,
                radius, radius + RADIO_OFFSET_Z, floorY);

        // Повороты шаблонов: сторона входа модуля должна смотреть туда же, куда
        // смотрела её локальная цель после поворота сетки. Так поворот шаблона
        // вычисляется той же функцией, что и раньше, но уже в мировых осях.
        Rotation watchtowerRotation = rotationForFacing(
                layoutRotation.rotate(Direction.NORTH), WATCHTOWER_ENTRANCE);
        Rotation farmRotation = rotationForFacing(
                layoutRotation.rotate(Direction.SOUTH), FARM_ENTRANCE);

        return new Outcome(new Plan(requestPos, center, radius, floorY, medianY,
                layoutRotation, watchtowerRotation, farmRotation, gatePos, watchtowerPos,
                farmPos, radioPos, surfaceAirY, surfaceStates), null);
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
     * @return поворот локальной сетки для лучшей стороны
     */
    private static Rotation chooseOrientation(ServerLevel level, BlockPos center,
                                              int radius, int floorY) {
        OrientationScore best = null;
        for (Direction facing : ORIENTATIONS) {
            OrientationScore candidate =
                    evaluateOrientation(level, center, radius, floorY, facing);
            if (best == null || candidate.isBetterThan(best)) {
                best = candidate;
            }
        }
        return best.rotation;
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
     * <p>Сравнение по горизонтали с запасом в один радиус: два периметра
     * вплотную тоже недопустимы — между ними не останется прохода.
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
            double dx = other.getCenter().getX() - center.getX();
            double dz = other.getCenter().getZ() - center.getZ();
            double minDistance = radius + other.getRadius() + CampTuning.CAMP_MIN_GAP_BETWEEN;
            if (dx * dx + dz * dz < minDistance * minDistance) {
                return "Слишком близко к лагерю «" + other.getName() + "»";
            }
        }
        return null;
    }

}