package com.phian404.survivalpoints.camp;

import com.phian404.survivalpoints.camp.block.fence.SectionBlock;
import com.phian404.survivalpoints.camp.blockentity.CampRadioBlockEntity;
import com.phian404.survivalpoints.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import javax.annotation.Nullable;

/**
 * Генерация материального лагеря по плану {@link CampSitePlanner}.
 *
 * <p>Состав лагеря:
 * <ol>
 *     <li>радио-вышка-якорь в центре;</li>
 *     <li>периметр из опор и секций по спеке v1.1.7-final, с воротами на юге;</li>
 *     <li>слот вышки ({@code watchtower}) в северо-западном углу;</li>
 *     <li>слот фермы ({@code farm}) на востоке двора, с резервом под {@code farm_lvl2}.</li>
 * </ol>
 *
 * <p>Вызывается строго после успешной проверки участка
 * ({@link CampSitePlanner#plan}): здесь уже нет причин отказа — только застройка.
 */
public class CampGenerator {

    /** Модуль вышки. */
    public static final String WATCHTOWER_MODULE = "survivalpoints:watchtower";

    /** Модуль фермы первого уровня. */
    public static final String FARM_MODULE_LVL1 = "survivalpoints:farm_lvl1";

    /** Модуль фермы второго уровня (апгрейд). */
    public static final String FARM_MODULE_LVL2 = "survivalpoints:farm_lvl2";

    /** Id слота вышки. */
    public static final String WATCHTOWER_SLOT = "watchtower";

    /** Id слота фермы. */
    public static final String FARM_SLOT = "farm";

    /**
     * Шаг опор периметра в локальных клетках.
     *
     * <p>4 клетки: опора, 3 блока панели, опора. Пролёт шириной 3 совпадает
     * с проёмом ворот ({@code 9..11}) и с шириной трезубца вышки.
     */
    private static final int POST_STEP = 4;

    /**
     * Высота периметра в блоках над полом двора.
     *
     * <p>4 = два яруса панелей (0, 1), ярус проволоки (2) и проволока над
     * воротами (3). Значение нужно только для области пересчёта форм: он
     * должен покрыть самый верхний блок застройки.
     */
    private static final int PERIMETER_HEIGHT = 4;

    /** Вышка 5×8×5 — модуль в углу периметра. */
    private static final ResourceLocation WATCHTOWER_ID = ResourceLocation.parse(WATCHTOWER_MODULE);

    /** Ферма lvl1: 9×5×9. */
    private static final ResourceLocation FARM_LVL1_ID = ResourceLocation.parse(FARM_MODULE_LVL1);

    /** Ферма lvl2: 9×5×9 — тот же габарит, резерв под неё совпадает с lvl1. */
    private static final ResourceLocation FARM_LVL2_ID = ResourceLocation.parse(FARM_MODULE_LVL2);

    private CampGenerator() {
    }

    /**
     * Подготовить площадку под застройку: выровнять рельеф по плану.
     *
     * <p>Вызывается ровно один раз — из {@code CampManager.createCamp} до
     * записи лагеря. Здесь уже нет причин отказа: план рассчитан, зона
     * подготовки проверена на {@code BlockEntity}.
     *
     * <p>Порядок по клетке: расчистка → подсыпка → поверхность → срез.
     * Расчистка идёт первой, иначе ствол оказался бы внутри подсыпаемого
     * столбца и остался бы торчать из нового пола.
     *
     * <p>Колонка якоря не трогается: блок {@code camp_radio} и его координаты
     * в {@link CampRecord} должны остаться неизменными, иначе распад лагеря
     * при сносе якоря перестанет работать.
     *
     * @param level  серверный уровень
     * @param plan   план застройки
     * @param anchor точка якоря (её колонка исключается)
     */
    public static void prepareTerrain(ServerLevel level, CampSitePlanner.Plan plan, BlockPos anchor) {
        int floorY = plan.getFloorY();
        int side = plan.getSide();
        int clearTop = floorY + CampTuning.CAMP_CLEAR_HEADROOM;

        for (int lx = 0; lx < side; lx++) {
            for (int lz = 0; lz < side; lz++) {
                BlockPos column = plan.localToWorld(lx, lz, floorY);
                if (column.getX() == anchor.getX() && column.getZ() == anchor.getZ()) {
                    continue;
                }
                int surfaceY = plan.cellSurfaceY(lx, lz);
                int groundY = surfaceY - 1;

                // 1. Расчистка: деревья, цветы и прочая мягкая растительность.
                //    Цветы в 1.20.1 возвращают canBeReplaced() == false, поэтому
                //    без проверки blocksMotion() они пережили бы выравнивание.
                for (int y = groundY + 1; y <= clearTop; y++) {
                    BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                            || state.canBeReplaced() || !state.blocksMotion()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }

                // 2. Подсыпка: низкие клетки поднимаются до уровня двора.
                for (int y = groundY + 1; y <= floorY - 2; y++) {
                    level.setBlock(new BlockPos(column.getX(), y, column.getZ()),
                            Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
                }

                // 3. Срез: возвышенности снимаются до уровня двора.
                for (int y = floorY; y <= groundY; y++) {
                    level.setBlock(new BlockPos(column.getX(), y, column.getZ()),
                            Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }

                // 4. Поверхность: возвращаем исходный блок (трава, песок) на
                //    floorY - 1, чтобы двор не выглядел голой насыпью.
                BlockState surface = plan.cellSurfaceState(lx, lz);
                if (surface != null && !surface.isAir()) {
                    level.setBlock(new BlockPos(column.getX(), floorY - 1, column.getZ()),
                            surface, Block.UPDATE_CLIENTS);
                }
            }
        }

        prepareEdges(level, plan, anchor);
    }

    /**
     * Подсыпать край площадки и подход к воротам.
     *
     * <p>Если двор оказался выше соседнего грунта на 1–2 блока, по краю
     * остался бы обрыв. В полосе {@link CampTuning#CAMP_CLEAR_MARGIN} снаружи
     * периметра грунт поднимается до {@code floorY - 1} — ровно до уровня
     * поверхности двора. Срез наружу не выходит: снаружи рельеф только
     * поднимается.
     *
     * <p>Подход к воротам — плавный спуск от двора к окружающему уровню:
     * каждая следующая клетка южнее ворот опускается не более чем на блок,
     * поэтому обрыв не образуется.
     *
     * @param level  серверный уровень
     * @param plan   план
     * @param anchor точка якоря (её колонка исключается)
     */
    private static void prepareEdges(ServerLevel level, CampSitePlanner.Plan plan, BlockPos anchor) {
        int floorY = plan.getFloorY();
        int margin = CampTuning.CAMP_CLEAR_MARGIN;
        int innerMinX = plan.getCenter().getX() - plan.getRadius();
        int innerMaxX = plan.getCenter().getX() + plan.getRadius();
        int innerMinZ = plan.getCenter().getZ() - plan.getRadius();
        int innerMaxZ = plan.getCenter().getZ() + plan.getRadius();
        int minX = innerMinX - margin;
        int maxX = innerMaxX + margin;
        int minZ = innerMinZ - margin;
        int maxZ = innerMaxZ + margin;

        // Только внешнее кольцо: внутри периметра рельеф уже выровнен, и
        // повторная подсыпка затёрла бы восстановленный поверхностный блок.
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean inside = x >= innerMinX && x <= innerMaxX
                        && z >= innerMinZ && z <= innerMaxZ;
                if (inside) {
                    continue;
                }
                if (x == anchor.getX() && z == anchor.getZ()) {
                    continue;
                }
                fillEdgeColumn(level, x, z, floorY - 1);
            }
        }

        // Подход к воротам: спуск на блок за клетку от уровня двора.
        BlockPos gate = plan.getGatePos();
        for (int step = 1; step <= margin; step++) {
            int targetY = floorY - 1 - step;
            int z = gate.getZ() + step;
            for (int dx = -1; dx <= 1; dx++) {
                int x = gate.getX() + dx;
                if (x == anchor.getX() && z == anchor.getZ()) {
                    continue;
                }
                fillEdgeColumn(level, x, z, targetY);
            }
        }
    }

    /**
     * Подсыпать одну клетку наружной полосы до заданного уровня.
     *
     * <p>Поднимается только воздух и заменяемое: уже стоящий грунт не
     * трогается, поэтому подсыпка не срезает естественный рельеф и не
     * заваливает растительность. Верхний блок ставится исходным
     * поверхностным блоком клетки, если он известен.
     *
     * @param level   серверный уровень
     * @param x       координата
     * @param z       координата
     * @param targetY уровень поверхности, до которого поднимается клетка
     */
    private static void fillEdgeColumn(ServerLevel level, int x, int z, int targetY) {
        // Грунт ищется той же функцией, что и при съёме высот: цветы и трава
        // не считаются поверхностью, иначе подсыпка начиналась бы выше неё.
        int groundY = CampSitePlanner.findGroundY(level, x, z);
        if (groundY == CampSitePlanner.FLUID_FOUND || groundY == CampSitePlanner.NO_GROUND) {
            return;
        }
        if (groundY >= targetY) {
            return;
        }
        // Верхний блок кладётся реальным поверхностным блоком колонки, а не
        // всегда травой: в песчаном биоме двор остался бы с зелёной нашлёпкой.
        BlockState surface = level.getBlockState(new BlockPos(x, groundY, z));
        for (int y = groundY + 1; y < targetY; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!isReplaceableSoft(level.getBlockState(pos))) {
                continue;
            }
            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        BlockPos top = new BlockPos(x, targetY, z);
        if (isReplaceableSoft(level.getBlockState(top))) {
            level.setBlock(top, surface, Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Клетка, которую подсыпка вправе занять.
     *
     * <p>Только воздух, заменяемое и неблокирующая растительность с пустой
     * жидкостью. Существующий твёрдый блок (стена, постройка) не трогается:
     * подсыпка края не должна затирать чужие объекты.
     *
     * @param state состояние проверяемой клетки
     * @return {@code true}, если клетку можно заполнить
     */
    private static boolean isReplaceableSoft(BlockState state) {
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        return state.isAir() || state.canBeReplaced() || !state.blocksMotion();
    }

    /**
     * Построить материальную часть лагеря по плану.
     *
     * <p>Порядок: пол двора (дорожки и подсыпка), периметр, модули, якорь.
     * Модули ставятся последними: у вышки собственный пол на {@code y=0}, и
     * подсыпка двора не должна лечь поверх него.
     *
     * <p>Выравнивание рельефа здесь не выполняется: площадка уже подготовлена
     * в {@link #prepareTerrain} до записи лагеря.
     *
     * @param level  серверный уровень
     * @param plan   план застройки (проверка участка уже прошла)
     * @param record лагерь, уже добавленный в {@link CampSavedData}
     */
    public static void generate(ServerLevel level, CampSitePlanner.Plan plan, CampRecord record) {
        record.setGatePos(plan.getGatePos());

        generatePerimeter(level, plan);
        placeDefaultModules(level, plan, record);

        // Пересчёт форм по всей площадке — после того, как стоят и периметр,
        // и модули. Секции держат языки и вертикальное сшивание в состоянии,
        // и без этого прохода стены выглядят разомкнутыми: панели не
        // примыкают к опорам, на торцах остаются рейки. Порядок важен —
        // панели лагеря должны увидеть стены вышки, с которыми соприкасаются
        // в углу площадки, поэтому проход идёт после размещения модулей.
        refreshAllShapes(level, plan);

        generatePaths(level, plan, record);
        placeRadio(level, plan, record);
    }

    /**
     * Пересчитать формы всех блоков лагеря.
     *
     * <p>Область берётся с запасом в один блок по горизонтали и на всю высоту
     * застройки: {@code updateShape} секции смотрит на соседей вдоль своей оси,
     * а значит панель на краю должна увидеть опору, стоящую внутри площадки.
     * Запас снизу не нужен — под полом двора лежит грунт, но верхний блок
     * грунта тоже участвует в пересчёте, поэтому {@code minY} на блок ниже пола.
     *
     * @param level серверный уровень
     * @param plan  план
     */
    private static void refreshAllShapes(ServerLevel level, CampSitePlanner.Plan plan) {
        BlockPos min = plan.localToWorld(0, 0, plan.getFloorY() - 1);
        BlockPos max = plan.localToWorld(plan.getSide() - 1, plan.getSide() - 1,
                plan.getFloorY() + PERIMETER_HEIGHT);
        CampStructurePlacer.refreshShapes(level,
                min.getX() - 1, min.getY(), min.getZ() - 1,
                max.getX() + 1, max.getY(), max.getZ() + 1);
    }

    /**
     * Радио-якорь лагеря.
     *
     * <p>Ставится после периметра и модулей: генератор вертикали вышки идёт
     * вплотную к дорожке, и якорь не должен оказаться внутри постройки.
     *
     * @param level  серверный уровень
     * @param plan   план
     * @param record лагерь
     */
    private static void placeRadio(ServerLevel level, CampSitePlanner.Plan plan, CampRecord record) {
        BlockPos radio = plan.getRadioPos();
        level.setBlock(radio, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(radio, ModBlocks.CAMP_RADIO.get().defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(radio) instanceof CampRadioBlockEntity radioBe) {
            radioBe.setCampId(record.getId());
        }
    }

    /**
     * Дорожки двора: от ворот к центру и от центра к входу фермы.
     *
     * <p>Ширина 2 блока — как у тропинок в шаблонах модулей. Дорожка ведёт от
     * ворот до радио и дальше на восток к северному входу фермы, чтобы маршрут
     * «ворота → ферма» читался по земле, а не угадывался.
     *
     * @param level  серверный уровень
     * @param plan   план
     * @param record лагерь (габариты слотов защищаются от дорожек)
     */
    private static void generatePaths(ServerLevel level, CampSitePlanner.Plan plan,
                                      CampRecord record) {
        int floorY = plan.getFloorY();
        BlockPos gate = plan.getGatePos();
        BlockPos radio = plan.getRadioPos();
        BlockPos farm = plan.getFarmPos();

        // 1. От ворот на север. Ширина 2 блока — как у тропинок в шаблонах
        //    модулей: колонка створки и клетка слева от неё.
        for (int z = gate.getZ() - 1; z >= radio.getZ(); z--) {
            setPath(level, record, gate.getX(), floorY, z);
            setPath(level, record, gate.getX() - 1, floorY, z);
        }

        // 2. Поворот на восток, к оси фермы: две строки на уровне радио.
        //    Ширина 2 блока — как у тропинок в шаблонах модулей.
        for (int x = radio.getX(); x <= farm.getX(); x++) {
            setPath(level, record, x, floorY, radio.getZ());
            setPath(level, record, x, floorY, radio.getZ() + 1);
        }

        // 3. Поворот на север к входу фермы. Габарит 9×9 с центром в
        //    {@code farm}: южная стена — {@code farm.getZ() + 4}, вход после
        //    поворота на 180° смотрит на юг, поэтому дорожка заканчивается
        //    клеткой {@code farm.getZ() + 5} — снаружи постройки. Строки
        //    {@code radio.getZ()} и {@code radio.getZ() + 1} уже проложены
        //    шагом 2, здесь идёт только оставшийся отрезок на север.
        int farmEntranceZ = farm.getZ() + 5;
        for (int z = radio.getZ() - 1; z >= farmEntranceZ; z--) {
            setPath(level, record, farm.getX() - 1, floorY, z);
            setPath(level, record, farm.getX(), floorY, z);
        }
    }

    /**
     * Заменить грунт тропинкой, не сломав уже стоящее строение.
     *
     * <p>{@code floorY} — первый воздушный ярус: игрок стоит ногами на нём,
     * а твёрдый грунт лежит на {@code floorY - 1}. Тропинка меняет именно
     * грунт, поэтому {@code dirt_path} ставится на {@code floorY - 1}:
     * блок на {@code floorY} поднял бы пол двора ступенькой в один блок.
     *
     * <p>Клетка пропускается, если над грунтом уже что-то стоит (стена,
     * модуль, дорожка другого шага): тропинки не должны проходить сквозь
     * постройки.
     *
     * <p>Клетки внутри габаритов слотов модулей пропускаются целиком: габарит
     * слота шире фактического модуля на запас, и дорожка не должна проходить
     * по территории постройки даже по её краю.
     *
     * <p>Отдельно запрещена замена {@code farmland}. У фермы нижний слой —
     * пашня с посевами: {@code wheat} стоит на {@code floorY} и блокирует
     * дорожку, но это защита «по факту культуры». Если культура в клетке
     * отсутствует (снята игроком, сломана пересчётом форм), проверка
     * {@code isAir} пропускает дорожку, и {@code dirt_path} встаёт вместо
     * пашни. Пашня под посевом — единственная опора культуры, поэтому такая
     * замена и давала выпадающие семена. Проверка по блоку грунта закрывает
     * это независимо от того, что стоит сверху.
     *
     * @param level  серверный уровень
     * @param record лагерь (для габаритов уже стоящих слотов)
     * @param x      координата
     * @param floorY ярус пола двора (первый воздушный)
     * @param z      координата
     */
    private static void setPath(ServerLevel level, CampRecord record, int x, int floorY, int z) {
        BlockPos above = new BlockPos(x, floorY, z);
        if (!level.getBlockState(above).isAir()) {
            return;
        }
        if (insideModuleSlot(record, above)) {
            return;
        }
        BlockPos ground = above.below();
        BlockState groundState = level.getBlockState(ground);
        if (groundState.is(Blocks.FARMLAND)) {
            return;
        }
        level.setBlock(ground, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    /**
     * Точка внутри габарита уже поставленного слота модуля.
     *
     * @param record лагерь
     * @param pos    точка в мире
     * @return {@code true}, если точка в чьём-то слоте
     */
    private static boolean insideModuleSlot(CampRecord record, BlockPos pos) {
        for (CampModuleSlot slot : record.getModuleSlots().values()) {
            if (slot.getBounds().contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Периметр компактного аванпоста 21×21.
     *
     * <p>Геометрия описана в локальных клетках {@code 0..side}, где
     * {@code side == 2*radius}:
     * <ul>
     *     <li>опоры периметра через каждые 4 клетки: {@code 0, 4, 8, 12, 16, 20};</li>
     *     <li>пролёт между опорами — 3 блока панели на ярусах 0 и 1;</li>
     *     <li>юго-западный угол {@code 0..4 × 16..20} занимает вышка — периметр
     *     там пропускается, стены вышки продолжают стены лагеря;</li>
     *     <li>южный центр {@code 9..11} — ворота: опоры {@code 8} и {@code 12}
     *     высотой 4 (на блок выше стены), клетки {@code 9} и {@code 11} —
     *     сплошные панели, клетка {@code 10} — единственная створка двери;</li>
     *     <li>ярус 2 — проволока по всему периметру, включая клетки над опорами.</li>
     * </ul>
     *
     * <p>Порядок обхода: сначала опоры, потом панели, потом проволока.
     * {@code SectionBlock.canSurvive} требует соседа вдоль оси или несущий блок
     * снизу, поэтому колонны опор должны стоять первыми — иначе панели и
     * проволока не найдут опоры и будут уничтожены.
     *
     * @param level серверный уровень
     * @param plan  план
     */
    private static void generatePerimeter(ServerLevel level, CampSitePlanner.Plan plan) {
        int side = plan.getSide() - 1;
        int floorY = plan.getFloorY();

        // === 1. Опоры периметра (два яруса) ===
        // Шаг 4 клетки. Опоры стоят на y = 0 и y = 1: верхний ярус выносит
        // проволоку над пролётами, и она крепится к настоящей опоре, а не
        // висит в воздухе. Углы ставятся один раз — обход северной и южной
        // сторон идёт целиком, западной и восточной — только внутренние клетки.
        //
        // Опоры внутри квадрата вышки пропускаются: там стоят её собственные
        // угловые столбы, и вторая колонна в той же клетке заменила бы их.
        for (int i = 0; i <= side; i += POST_STEP) {
            if (!isWatchtowerCell(i, 0, side)) {
                placePostColumn(level, plan, i, 0, floorY, 2);
            }
            if (!isWatchtowerCell(i, side, side)) {
                placePostColumn(level, plan, i, side, floorY, 2);
            }
        }
        for (int i = POST_STEP; i < side; i += POST_STEP) {
            if (!isWatchtowerCell(0, i, side)) {
                placePostColumn(level, plan, 0, i, floorY, 2);
            }
            if (!isWatchtowerCell(side, i, side)) {
                placePostColumn(level, plan, side, i, floorY, 2);
            }
        }

        // Опоры по краям проёма (клетки 8 и 12) поднимаются на четвёртый ярус:
        // они выше стены на блок (y = 0, 1, 2, 3) и несут проволоку, идущую
        // над воротами поверх панелей. Нижние два яруса уже поставлены общим
        // циклом опор.
        int gateLeft = side / 2 - 2;
        int gateRight = side / 2 + 2;
        for (int x = gateLeft; x <= gateRight; x += POST_STEP) {
            if (!isWatchtowerCell(x, side, side)) {
                placePost(level, plan.localToWorld(x, side, floorY + 2));
                placePost(level, plan.localToWorld(x, side, floorY + 3));
            }
        }

        // Клетки 9 и 11 проёма — глухие панели на ярусах 0..2. Это рама
        // единственной створки в клетке 10: она должна быть обшита с обеих
        // сторон, иначе проём между опорами 8 и 12 читается как дыра, а не
        // как ворота. Ставятся здесь, а не в общем цикле панелей, потому что
        // проём целиком исключён из него (см. isGateSpan).
        int blindLeft = side / 2 - 1;
        int blindRight = side / 2 + 1;
        for (int y = 0; y <= 2; y++) {
            placeSheet(level, plan.localToWorld(blindLeft, side, floorY + y),
                    Direction.Axis.X, y + blindLeft);
            placeSheet(level, plan.localToWorld(blindRight, side, floorY + y),
                    Direction.Axis.X, y + blindRight);
        }

        // === 2. Панели (ярусы 0 и 1) ===
        for (int y = 0; y < 2; y++) {
            int worldY = floorY + y;
            for (int i = 0; i <= side; i++) {
                if (isPostCell(i)) {
                    continue;
                }
                // Северная стена — целиком.
                if (!isWatchtowerCell(i, 0, side)) {
                    fillSpan(level, plan, i, 0, worldY, Direction.Axis.X, y);
                }
                // Южная стена — с проёмом ворот и вышкой в углу.
                if (!isGateSpan(i, side) && !isWatchtowerCell(i, side, side)) {
                    fillSpan(level, plan, i, side, worldY, Direction.Axis.X, y);
                }
                // Западная и восточная стены.
                if (!isWatchtowerCell(0, i, side)) {
                    fillSpan(level, plan, 0, i, worldY, Direction.Axis.Z, y);
                }
                if (!isWatchtowerCell(side, i, side)) {
                    fillSpan(level, plan, side, i, worldY, Direction.Axis.Z, y);
                }
            }
        }

        // === 3. Проволока (ярус 2) ===
        // Клетки у ворот пропускаются: опоры 8 и 12 подняты до яруса 3, и
        // проволока заменила бы их верхний блок, а над клетками 9..11 лежит
        // проволока проёма на ярусе 3 — здесь она была бы внутри панелей.
        int wireY = floorY + 2;
        for (int i = 0; i <= side; i++) {
            if (!isWatchtowerCell(i, 0, side)) {
                placeWire(level, plan.localToWorld(i, 0, wireY), Direction.Axis.X);
            }
            if (!isGateSpan(i, side) && !isGatePostCell(i, side)
                    && !isWatchtowerCell(i, side, side)) {
                placeWire(level, plan.localToWorld(i, side, wireY), Direction.Axis.X);
            }
            if (!isWatchtowerCell(0, i, side)) {
                placeWire(level, plan.localToWorld(0, i, wireY), Direction.Axis.Z);
            }
            if (!isWatchtowerCell(side, i, side)) {
                placeWire(level, plan.localToWorld(side, i, wireY), Direction.Axis.Z);
            }
        }

        // === 4. Створка ворот ===
        // Одна створка ровно в центре пролёта (клетка 10). Соседние клетки
        // 9 и 11 обшиты панелями до яруса 2, поэтому створка стоит в раме.
        placeGateLeaf(level, plan.getGatePos(), DoorHingeSide.LEFT);

        // Перемычка над створкой — строго на ярусе 2.
        //
        // <p>Створка {@code DoorBlock} занимает ДВА блока: нижнюю половину на
        // {@code floorY} и верхнюю на {@code floorY + 1}. Поэтому {@code above()}
        // (один ярус вверх) попадает ровно в верхнюю половину двери и заменяет
        // её панелью — дверь остаётся без верха, ломается и рассыпается.
        // Перемычка ставится через {@code above(2)}, на первый свободный ярус.
        placeSheet(level, plan.getGatePos().above(2), Direction.Axis.X, 1);

        // Проволока над проёмом на ярусе 3: идёт над перемычкой и глухими
        // панелями и замыкается на опоры проёма, поднятые до яруса 3 — их
        // верхние блоки стоят на том же ярусе, поэтому линия замкнута.
        // Сами клетки опор (8 и 12) проволокой не закрываются.
        for (int x = blindLeft; x <= blindRight; x++) {
            placeWire(level, plan.localToWorld(x, side, floorY + 3), Direction.Axis.X);
        }
    }

    /**
     * Колонна опор в одной клетке периметра.
     *
     * <p>Опоры ставятся снизу вверх: {@code PostBlock.canSurvive} допускает
     * опору на опоре, но не на секции, поэтому нижняя должна уже стоять.
     *
     * @param level  серверный уровень
     * @param plan   план
     * @param lx     локальная клетка по X
     * @param lz     локальная клетка по Z
     * @param floorY ярус пола двора
     * @param height сколько блоков опоры
     */
    private static void placePostColumn(ServerLevel level, CampSitePlanner.Plan plan,
                                        int lx, int lz, int floorY, int height) {
        for (int i = 0; i < height; i++) {
            placePost(level, plan.localToWorld(lx, lz, floorY + i));
        }
    }

    /** Клетка периметра, занятая опорой. */
    private static boolean isPostCell(int index) {
        return index % POST_STEP == 0;
    }

    /**
     * Клетка опоры у края проёма ворот ({@code 8} или {@code 12}).
     *
     * <p>Эти опоры выше остальной стены на блок и на ярусе 2 несут место под
     * верхний столб, поэтому проволока яруса 2 сюда не ставится.
     */
    private static boolean isGatePostCell(int index, int side) {
        int center = side / 2;
        return index == center - 2 || index == center + 2;
    }

    /**
     * Клетки южной стены внутри проёма ворот ({@code 9..11}).
     *
     * <p>Готовые панели и проволока ярусов периметра здесь не ставятся: клетки
     * {@code 9} и {@code 11} получают сплошные панели на всех трёх ярусах,
     * клетку {@code 10} занимает створка двери. Опоры {@code 8} и {@code 12}
     * в проём не входят.
     */
    private static boolean isGateSpan(int index, int side) {
        int center = side / 2;
        return index >= center - 1 && index <= center + 1;
    }

    /**
     * Клетка углового квадрата, занятого вышкой.
     *
     * <p>Вышка стоит в юго-западном углу и занимает квадрат
     * {@code 0..WATCHTOWER_FOOTPRINT-1} по X и
     * {@code side-WATCHTOWER_FOOTPRINT+1..side} по Z. Её собственные стены
     * продолжают стены лагеря, поэтому периметр эти клетки пропускает —
     * иначе панели лагеря встали бы внутрь построек вышки.
     *
     * @param lx   локальная клетка по X
     * @param lz   локальная клетка по Z
     * @param side сторона периметра в клетках
     * @return {@code true}, если клетку занимает вышка
     */
    private static boolean isWatchtowerCell(int lx, int lz, int side) {
        int max = CampSitePlanner.WATCHTOWER_FOOTPRINT - 1;
        return lx <= max && lz >= side - max;
    }

    /**
     * Заполнить пролёт между опорами панелью.
     *
     * <p>Материал детерминированно чередуется: примерно каждая четвёртая
     * панель — ржавая. Смещение берётся из координаты и яруса, поэтому
     * перегенерация того же лагеря даёт тот же рисунок.
     *
     * @param level серверный уровень
     * @param plan  план
     * @param lx    локальная клетка по X
     * @param lz    локальная клетка по Z
     * @param y     ярус в мире
     * @param axis  ось пролёта
     * @param tier  ярус панели (0 или 1)
     */
    private static void fillSpan(ServerLevel level, CampSitePlanner.Plan plan, int lx, int lz,
                                 int y, Direction.Axis axis, int tier) {
        placeSheet(level, plan.localToWorld(lx, lz, y), axis, tier + lx * 3 + lz * 7);
    }

    /** Поставить панель листа с детерминированным выбором ржавого варианта. */
    private static void placeSheet(ServerLevel level, BlockPos pos, Direction.Axis axis, int seed) {
        int variant = Math.floorMod(seed, 5);
        Block block;
        if (variant == 1) {
            block = ModBlocks.RUSTED_SHEET_0.get();
        } else if (variant == 3) {
            block = ModBlocks.RUSTED_SHEET_1.get();
        } else {
            block = ModBlocks.SHEET_SECTION.get();
        }
        BlockState state = block.defaultBlockState().setValue(SectionBlock.AXIS, axis);
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    /** Поставить панель проволоки с детерминированным выбором ржавого варианта. */
    private static void placeWire(ServerLevel level, BlockPos pos, Direction.Axis axis) {
        int variant = Math.floorMod(pos.getX() * 3 + pos.getZ() * 5, 5);
        Block block;
        if (variant == 1) {
            block = ModBlocks.RUSTED_WIRE_0.get();
        } else if (variant == 3) {
            block = ModBlocks.RUSTED_WIRE_1.get();
        } else {
            block = ModBlocks.WIRE_SECTION.get();
        }
        BlockState state = block.defaultBlockState().setValue(SectionBlock.AXIS, axis);
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    /**
     * Поставить створку ворот.
     *
     * <p>Дверь смотрит на юг ({@code FACING = SOUTH}): она закрывает проём,
     * а игрок снаружи видит её лицевую сторону. Петля слева — створка
     * распахивается наружу, не уходя в толщу стены.
     *
     * @param level серверный уровень
     * @param pos   нижняя половина створки
     * @param hinge сторона петли
     */
    private static void placeGateLeaf(ServerLevel level, BlockPos pos, DoorHingeSide hinge) {
        BlockState lower = ModBlocks.CAMP_GATE.get().defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.SOUTH)
                .setValue(DoorBlock.HINGE, hinge)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        level.setBlock(pos, lower, Block.UPDATE_CLIENTS);
        level.setBlock(pos.above(),
                lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS);
    }

    /**
     * Слоты вышки и фермы по плану.
     *
     * <p>Высота вставки различается по строению нижнего слоя модуля. У фермы
     * {@code y=0} — заменитель грунта (трава, пашня, тропинка): она встаёт на
     * {@code floorY - 1}, и её земля ложится вровень с двором. У вышки
     * {@code y=0} — уже пол постройки (oak_planks): она встаёт прямо на
     * {@code floorY}, иначе пол вышки утопал на блок ниже земли двора.
     *
     * @param level  серверный уровень
     * @param plan   план
     * @param record лагерь
     */
    private static void placeDefaultModules(ServerLevel level, CampSitePlanner.Plan plan,
                                            CampRecord record) {
        CampSavedData data = CampManager.data(level);

        CampStructurePlacer.placeModule(level, data, record, WATCHTOWER_SLOT, WATCHTOWER_ID,
                0, plan.getWatchtowerPos(), plan.getWatchtowerRotation());
        placeFarm(level, data, record, plan);
    }

    /**
     * Разместить ферму в слоте {@code farm} и выровнять грунт под ней.
     *
     * <p>Слот резервируется с максимальными габаритами: у {@code farm_lvl1} и
     * {@code farm_lvl2} размер 9×5×9 совпадает, но расчёт идёт от фактического
     * шаблона через {@link CampStructurePlacer} — если lvl2 когда-нибудь
     * вырастет, слот займёт его реальный габарит после той же вставки.
     *
     * <p>Подсыпка: модуль ставится на {@code floorY - 1}; если под его габаритом
     * земля ниже уровня двора, выравнивание выполняется dirt-блоками — иначе
     * нижний слой модуля висел бы в воздухе, а пашня не имела бы опоры.
     *
     * @param level  серверный уровень
     * @param data   хранилище
     * @param record лагерь
     * @param plan   план
     */
    private static void placeFarm(ServerLevel level, CampSavedData data, CampRecord record,
                                  CampSitePlanner.Plan plan) {
        // План уже отдаёт точку на ярус ниже пола двора ({@code floorY - 1}):
        // у фермы {@code y=0} — слой грунта, и он должен лечь вровень с двором.
        BlockPos base = plan.getFarmPos();
        fillFarmBase(level, base, FARM_LVL1_ID, plan.getFarmRotation());

        CampStructurePlacer.placeModule(level, data, record, FARM_SLOT, FARM_LVL1_ID,
                0, base, plan.getFarmRotation());
    }

    /**
     * Подсыпать землю в просадках под габаритом фермы.
     *
     * <p>Нижний слой модуля встаёт на {@code base.getY()} — уровень земли
     * двора. В просадочном столбце (земля на блок ниже, проверка участка это
     * допускает) на этом уровне воздух, и пашня модуля висела бы. Заполняется
     * ровно этот уровень: блоки ниже не трогаются — они уже грунт, а выше
     * начался бы сам модуль.
     *
     * <p>Габарит берётся у фактически вставляемого шаблона с его поворотом:
     * у повёрнутого шаблона длины по X и Z меняются местами. Так подсыпка
     * остаётся корректной при любом повороте и смене шаблона.
     *
     * @param level    серверный уровень
     * @param base     точка размещения фермы (центр её габарита)
     * @param moduleId шаблон фермы
     * @param rotation поворот
     */
    private static void fillFarmBase(ServerLevel level, BlockPos base,
                                     ResourceLocation moduleId, Rotation rotation) {
        var templateOpt = level.getStructureManager().get(moduleId);
        if (templateOpt.isEmpty()) {
            return;
        }
        net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings settings =
                new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings()
                        .setRotation(rotation);
        var probe = templateOpt.get().getBoundingBox(settings, BlockPos.ZERO);
        int minX = base.getX() - (probe.maxX() - probe.minX()) / 2;
        int maxX = base.getX() + (probe.maxX() - probe.minX() + 1) / 2;
        int minZ = base.getZ() - (probe.maxZ() - probe.minZ()) / 2;
        int maxZ = base.getZ() + (probe.maxZ() - probe.minZ() + 1) / 2;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos ground = new BlockPos(x, base.getY(), z);
                BlockState state = level.getBlockState(ground);
                if (!state.isAir() && !state.canBeReplaced()) {
                    continue;
                }
                level.setBlock(ground, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /** Поставить опору периметра. */
    private static void placePost(ServerLevel level, BlockPos pos) {
        BlockState state = ModBlocks.POST.get().defaultBlockState();
        // Опоры стоят на земле двора; для canSurvive достаточно sturdy-основания.
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    // ==================== Апгрейд слота ====================

    /**
     * Апгрейд слота фермы: {@code farm_lvl1} → {@code farm_lvl2}.
     *
     * <p>Порядок: проверить защиту изменений игрока → снять блоки старого
     * модуля → вставить новый на тех же origin и rotation → пересканировать
     * POI слота. POI собираются заново: маркеры обоих шаблонов ({@code
     * guard_post} и т.п.) и пашня — по факту блоков в мире.
     *
     * <p>Старые POI слота снимаются целиком ({@link CampRecord#replacePois}):
     * точки относятся к прежней планировке и после замены шаблона могли бы
     * указывать на воздух.
     *
     * @param level  серверный уровень
     * @param data   хранилище
     * @param record лагерь
     * @param slotId id слота
     * @param force  игнорировать флаг {@code modified} (отладочный обход)
     * @return причина отказа или {@code null} при успехе
     */
    @Nullable
    public static String upgradeFarm(ServerLevel level, CampSavedData data, CampRecord record,
                                     String slotId, boolean force) {
        CampModuleSlot slot = record.getModuleSlot(slotId);
        if (slot == null) {
            return "Слот " + slotId + " не найден";
        }
        if (!FARM_SLOT.equals(slotId)) {
            return "Апгрейд поддерживается только для слота " + FARM_SLOT;
        }
        if (!FARM_MODULE_LVL1.equals(slot.getModuleId())) {
            return "В слоте " + slotId + " модуль " + slot.getModuleId()
                    + " — апгрейд возможен только с " + FARM_MODULE_LVL1;
        }
        if (slot.isModified() && !force) {
            return "Слот " + slotId + " изменён игроком. Апгрейд отменён "
                    + "(принудительно — с force)";
        }

        Rotation rotation = CampStructurePlacer.rotationFromDegrees(slot.getRotation());
        BlockPos origin = slot.getOrigin();

        // Снять старую застройку слота. Нижний слой не трогается: у ферм он
        // заменяет грунт (пашня, тропинка), и снос его в воздух оставил бы яму.
        CampStructurePlacer.clearSlot(level, slot);

        // Новые POI — после вставки: и маркеры нового шаблона, и пашня.
        CampStructurePlacer.PlacementResult result = CampStructurePlacer.placeModule(
                level, data, record, slotId, FARM_LVL2_ID, slot.getLevel(), origin, rotation);
        if (!result.isSuccess()) {
            // Слот с блоками уже снят, данные слота ещё старые. Оставляем
            // слот в данных: его границы известны, повторный вызов с force
            // или восстановление слота выполнятся поверх.
            return result.getMessage();
        }

        // POI слота пересканированы внутри placeModule: маркеры нового шаблона
        // и пашня по фактическим блокам. replacePois снял набор прежней
        // планировки целиком — висячих точек от lvl1 не остаётся.
        data.setDirty();
        return null;
    }

    // ==================== Посты ====================

    /**
     * Создать посты из POI лагеря.
     *
     * <p>Маркеры {@code poi/guard_post} в шаблонах — реальные места охраны:
     * они разворачиваются в {@link CampPost} и попадают в штат лагеря.
     * Виртуальная расстановка по кругу (бывший {@code generateWallPosts})
     * удалена: её точки не имели связи с застройкой и при генерации модулей
     * могли оказаться внутри стен или в воздухе.
     *
     * <p>Тип поста по слоту: маркер внутри вышки — {@code TOWER} (обзор
     * 360°), в прочих модулях — {@code WALL}. Точку у ворот создаёт сам
     * генератор: ворота — единственный вход, и пост у входа нужен даже без
     * соответствующего маркера в шаблоне.
     *
     * @param record лагерь
     * @param plan   план (для позиции ворот и пола двора)
     */
    public static void createPostsFromPois(CampRecord record, CampSitePlanner.Plan plan) {
        // Пост у ворот: на шаг южнее проёма, снаружи. Клетки проёма заняты
        // створками и глухой панелью, поэтому точка стража — перед воротами.
        BlockPos gatePost = plan.getGatePos().south();
        record.addPost(new CampPost(CampPost.Type.GATE, gatePost, 0.0F));

        for (CampPoi poi : record.getPois()) {
            if (!poi.getType().startsWith(CampStructurePlacer.POI_PREFIX)) {
                continue;
            }
            if (CampStructurePlacer.FARM_PLOT_TYPE.equals(poi.getType())) {
                // Клетки пашни — рабочие точки фермеров, не посты охраны.
                continue;
            }
            CampPost.Type type = WATCHTOWER_SLOT.equals(poi.getSlotId())
                    ? CampPost.Type.TOWER : CampPost.Type.WALL;
            record.addPost(new CampPost(type, poi.getPos(), 0.0F));
        }
    }
}