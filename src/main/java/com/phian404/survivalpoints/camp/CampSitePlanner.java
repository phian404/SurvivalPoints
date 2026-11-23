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

    private CampSitePlanner() {
    }

    /**
     * План застройки лагеря.
     *
     * <p>Все позиции хранятся в мировых координатах, но рассчитаны в
     * <b>локальной сетке</b> периметра: клетки {@code 0..2*radius} по X и Z,
     * где {@code 0} — северо-западный угол, {@code 2*radius} — юго-восточный.
     * Такая сетка позволяет описать планировку «опоры через 4 клетки» и
     * «ворота в центре южной стены» независимо от того, где стоит якорь.
     *
     * <p>Повороты модулей отдельные: у шаблонов разные стороны входа
     * (см. константы {@code *_ENTRANCE}), и общий угол разворота всех
     * модулей сразу поставил бы вход одной из построек к забору.
     */
    public static final class Plan {

        private final BlockPos center;
        private final int radius;
        private final int floorY;
        private final Rotation watchtowerRotation;
        private final Rotation farmRotation;
        private final BlockPos gatePos;
        private final BlockPos watchtowerPos;
        private final BlockPos farmPos;
        private final BlockPos radioPos;
        private final int[] surfaceAirY;
        private final BlockState[] surfaceStates;

        private Plan(BlockPos center, int radius, int floorY, Rotation watchtowerRotation,
                     Rotation farmRotation, BlockPos gatePos,
                     BlockPos watchtowerPos, BlockPos farmPos, BlockPos radioPos,
                     int[] surfaceAirY, BlockState[] surfaceStates) {
            this.center = center;
            this.radius = radius;
            this.floorY = floorY;
            this.watchtowerRotation = watchtowerRotation;
            this.farmRotation = farmRotation;
            this.gatePos = gatePos;
            this.watchtowerPos = watchtowerPos;
            this.farmPos = farmPos;
            this.radioPos = radioPos;
            this.surfaceAirY = surfaceAirY;
            this.surfaceStates = surfaceStates;
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
         * @param localX клетка по X ({@code 0..2*radius}, {@code 0} — запад)
         * @param localZ клетка по Z ({@code 0..2*radius}, {@code 0} — север)
         * @param y      ярус в мире
         * @return мировая точка
         */
        public BlockPos localToWorld(int localX, int localZ, int y) {
            return new BlockPos(this.center.getX() - this.radius + localX, y,
                    this.center.getZ() - this.radius + localZ);
        }

        /** Ярус пола двора: на нём стоят посты, секции и модули. */
        public int getFloorY() {
            return this.floorY;
        }

        /**
         * Поворот вышки: вход шаблона смотрит на север, во двор.
         *
         * <p>Вышка стоит в юго-западном углу периметра, поэтому её северная
         * и восточная открытые стороны смотрят внутрь двора без поворота.
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
            return this.surfaceAirY[indexOf(localX, localZ)];
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
            return this.surfaceStates[indexOf(localX, localZ)];
        }

        private int indexOf(int localX, int localZ) {
            int cells = getSide();
            return localX * cells + localZ;
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
     * @param level  серверный уровень
     * @param anchor точка, где игрок поставил якорь (радио-вышку)
     * @param radius радиус лагеря
     * @return план или причина отказа
     */
    public static Outcome plan(ServerLevel level, BlockPos anchor, int radius) {
        // Периметр занимает клетки 0..2*radius: сторона 2*radius+1 блок.
        int half = radius;
        int minX = anchor.getX() - half;
        int maxX = anchor.getX() + half;
        int minZ = anchor.getZ() - half;
        int maxZ = anchor.getZ() + half;

        String conflict = findCampConflict(level, anchor, radius);
        if (conflict != null) {
            return new Outcome(null, conflict);
        }

        // === Съём высот площадки ===
        // Клетки сетки 0..side по X и Z. Высота каждой клетки — первый
        // воздушный блок над настоящим грунтом, поэтому стволы, листва и
        // заменяемая растительность при определении поверхности пропускаются:
        // иначе клетка с деревом дала бы высоту кроны, и перепад по площадке
        // считался бы по деревьям, а не по рельефу.
        int cells = radius * 2 + 1;
        int[] surfaceAirY = new int[cells * cells];
        BlockState[] surfaceStates = new BlockState[cells * cells];
        int minSurface = Integer.MAX_VALUE;
        int maxSurface = Integer.MIN_VALUE;

        for (int lx = 0; lx < cells; lx++) {
            for (int lz = 0; lz < cells; lz++) {
                int x = minX + lx;
                int z = minZ + lz;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int groundY = Integer.MIN_VALUE;
                BlockState ground = null;
                for (int y = top; y >= level.getMinBuildHeight(); y--) {
                    BlockState state = level.getBlockState(new BlockPos(x, y, z));
                    // Жидкость проверяется раньше canBeReplaced(): вода и лава
                    // сами являются заменяемыми, и без этой проверки затопленная
                    // низина считалась бы обычным воздухом над грунтом.
                    if (!state.getFluidState().isEmpty()) {
                        return new Outcome(null, "Вода или лава на площадке у " + x + "," + z);
                    }
                    if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                            || state.canBeReplaced()) {
                        continue;
                    }
                    groundY = y;
                    ground = state;
                    break;
                }
                if (ground == null) {
                    return new Outcome(null, "Нет грунта на площадке у " + x + "," + z);
                }
                int cell = lx * cells + lz;
                surfaceAirY[cell] = groundY + 1;
                surfaceStates[cell] = ground;
                minSurface = Math.min(minSurface, groundY + 1);
                maxSurface = Math.max(maxSurface, groundY + 1);
            }
        }

        int delta = maxSurface - minSurface;
        if (delta > CampTuning.CAMP_MAX_HEIGHT_DELTA) {
            return new Outcome(null, "Слишком неровная площадка: перепад "
                    + delta + " блоков, допустимо " + CampTuning.CAMP_MAX_HEIGHT_DELTA);
        }

        // Пол двора — медиана высот, а не максимум: при перепаде до двух
        // блоков выравнивание и подсыпает низины, и срезает бугры, поэтому
        // ярус выбирается по середине рельефа. Значение передаётся в генератор
        // как есть: floorY — первый воздушный ярус над грунтом.
        int floorY = median(surfaceAirY);

        // === Локальная сетка ===
        // Планировка описана в клетках 0..2*radius: (0,0) — северо-запад,
        // (2*radius, 2*radius) — юго-восток. Ворота и вход — на южной стене
        // (localZ == 2*radius), поэтому направление «на юг» в мировой системе
        // совпадает с положительным Z при любом положении якоря.
        int side = radius * 2;

        // Ворота: одна створка ровно в центре южной стены (клетка 10).
        // Соседние клетки пролёта (9 и 11) — глухие панели: они дают двери
        // раму и симметричную обшивку с обеих сторон, поэтому проём не
        // расползается на весь пролёт между опорами 8 и 12.
        BlockPos gatePos = new BlockPos(anchor.getX(), floorY, anchor.getZ() + half);

        // Вышка 5×5 стоит в юго-западном углу периметра: её габарит точно
        // совпадает с угловым квадратом сетки (local 0..4 × 16..20), поэтому
        // стены вышки продолжают стены лагеря, а северная и восточная
        // открытые стороны смотрят внутрь двора.
        int watchtowerCenterLocalX = WATCHTOWER_FOOTPRINT / 2;
        int watchtowerCenterLocalZ = side - WATCHTOWER_FOOTPRINT / 2;
        BlockPos watchtowerPos = new BlockPos(
                anchor.getX() - radius + watchtowerCenterLocalX, floorY,
                anchor.getZ() - radius + watchtowerCenterLocalZ);

        // Ферма 9×9 — в дальнем от ворот углу двора (северо-восток), с
        // отступом в 1 блок от стен: local X 11..19, Z 1..9. Вход фермы
        // (север шаблона) при повороте на 180° смотрит на юг — на центральную
        // дорожку, ведущую от ворот, и не упирается в забор.
        int farmCenterLocalX = side - 5;
        int farmCenterLocalZ = WATCHTOWER_FOOTPRINT;
        BlockPos farmPos = new BlockPos(
                anchor.getX() - radius + farmCenterLocalX, floorY - 1,
                anchor.getZ() - radius + farmCenterLocalZ);

        // Радио-якорь — рядом с центром, но со сдвигом на 2 клетки к югу:
        // в самом центре сходятся дорожки, и якорь перекрыл бы развязку.
        BlockPos radioPos = new BlockPos(anchor.getX(), floorY, anchor.getZ() + 2);

        // Повороты: вышка стоит без поворота (её север/восток уже смотрят во
        // двор), ферму разворачиваем так, чтобы её северный вход смотрел на юг.
        Rotation watchtowerRotation = rotationForFacing(Direction.NORTH, WATCHTOWER_ENTRANCE);
        Rotation farmRotation = rotationForFacing(Direction.SOUTH, FARM_ENTRANCE);

        return new Outcome(new Plan(anchor, radius, floorY, watchtowerRotation, farmRotation,
                gatePos, watchtowerPos, farmPos, radioPos, surfaceAirY, surfaceStates), null);
    }

    /**
     * Медиана высот клеток площадки.
     *
     * <p>При чётном количестве клеток медиана — среднее двух центральных
     * значений, округлённое до целого яруса: пол двора обязан быть целым
     * блоком, а дробный результат сдвинул бы выравнивание на полблока.
     *
     * @param values высоты клеток ({@code surfaceAirY})
     * @return целый ярус пола двора
     */
    private static int median(int[] values) {
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        if (sorted.length % 2 == 1) {
            return sorted[mid];
        }
        return (int) Math.round((sorted[mid - 1] + sorted[mid]) / 2.0D);
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
     * <p>Колонка якоря пропускается: при установке радио-вышки в игре её
     * собственный {@code CampRadioBlockEntity} уже стоит в мире и иначе
     * блокировал бы создание лагеря.
     *
     * @param level  серверный уровень
     * @param plan   рассчитанный план
     * @param anchor точка якоря (её колонка исключается)
     * @return причина отказа или {@code null}
     */
    @Nullable
    public static String checkPreparationZone(ServerLevel level, Plan plan, BlockPos anchor) {
        int margin = CampTuning.CAMP_CLEAR_MARGIN;
        int minX = plan.getCenter().getX() - plan.getRadius() - margin;
        int maxX = plan.getCenter().getX() + plan.getRadius() + margin;
        int minZ = plan.getCenter().getZ() - plan.getRadius() - margin;
        int maxZ = plan.getCenter().getZ() + plan.getRadius() + margin;
        int minY = plan.getFloorY() - 1 - CampTuning.CAMP_MAX_HEIGHT_DELTA;
        int maxY = plan.getFloorY() + CampTuning.CAMP_CLEAR_HEADROOM;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (x == anchor.getX() && z == anchor.getZ()) {
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
     */
    @Nullable
    private static String findCampConflict(ServerLevel level, BlockPos anchor, int radius) {
        CampSavedData data = CampManager.data(level);
        for (CampRecord other : data.getCamps()) {
            double dx = other.getCenter().getX() - anchor.getX();
            double dz = other.getCenter().getZ() - anchor.getZ();
            double minDistance = radius + other.getRadius() + CampTuning.CAMP_MIN_GAP_BETWEEN;
            if (dx * dx + dz * dz < minDistance * minDistance) {
                return "Слишком близко к лагерю «" + other.getName() + "»";
            }
        }
        return null;
    }

}