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
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

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
     * Смещение конца дорожки от центра фермы (в локальных клетках).
     *
     * <p>Габарит фермы 9×9: от центра до стены — 4 клетки, плюс одна клетка
     * снаружи. Дорожка должна упереться в землю перед входом, а не в пол
     * постройки.
     */
    private static final int FARM_ENTRANCE_OFFSET = 5;

    /**
     * Шаг опор периметра в локальных клетках.
     *
     * <p>4 клетки: опора, 3 блока панели, опора. Пролёт шириной 3 совпадает
     * с проёмом ворот ({@code 9..11}) и с шириной трезубца вышки.
     */
    private static final int POST_STEP = 4;

    /**
     * Требуемая свободная высота коридора в блоках.
     *
     * <p>2 блока — минимум, при котором игрок ростом 1.8 проходит пешком,
     * не пригибаясь и не задевая головой блок над поверхностью.
     */
    private static final int GATE_ACCESS_HEIGHT = 2;

    /**
     * Дополнительный запас области поиска стволов в блоках.
     *
     * <p>Запас равен нулю: стволы ищутся ровно в зоне подготовки площадки
     * ({@link CampTuning#CAMP_CLEAR_MARGIN}). Раньше запас расширял поиск на
     * три блока наружу, и деревья сносились за пределами обрабатываемой
     * полосы — лес вырубался шире, чем лагерь меняет рельеф.
     *
     * <p>Дерево, у которого в зоне оказалась только крона, по-прежнему
     * остаётся: признак принадлежности к зоне — ствол.
     */
    private static final int TREE_DETECT_EXTRA = 0;

    /**
     * Горизонтальный радиус поиска ствола вокруг брёвен, найденных в зоне.
     *
     * <p>2 блока покрывают двуствольные породы (дуб 2×2) и стволы, которые
     * отклоняются от вертикали. Больший радиус рискую склеить соседние
     * деревья в одно.
     */
    private static final int TRUNK_SCAN_RADIUS = 2;

    /**
     * Предел блоков ствола при обходе одного дерева.
     *
     * <p>Страховка от патологий: обход не должен уходить в бесконечность на
     * повреждённом чанке или нестандартном моде.
     */
    private static final int TRUNK_BLOCK_LIMIT = 256;

    /**
     * Радиус поиска кроны вокруг ствола.
     *
     * <p>8 блоков — с запасом на крупные породы: крона обычного дуба уходит
     * от ствола не дальше 4 блоков, у большой ели — до 6.
     */
    private static final int CANOPY_SCAN_RADIUS = 8;

    /**
     * Предел блоков кроны при обходе одного дерева.
     *
     * <p>Страховка от бесконечного обхода: крупное дерево редко превышает
     * несколько сотен блоков листвы.
     */
    private static final int CANOPY_BLOCK_LIMIT = 2048;

    /**
     * Глубина пробы окружающего рельефа в клетках.
     *
     * <p>Высота перехода у внешней границы полосы берётся не из одной
     * клетки, а из медианы нескольких проб вдоль направления «от лагеря
     * наружу». Одиночная проба попадала бы на ствол, яму или выступ и
     * давала бы рваный край.
     */
    public static final int EDGE_PROBE_STEPS = 3;

    /**
     * Минимальный перепад, при котором вообще строится переход.
     *
     * <p>Один блок игрок переступает без всякой подготовки. Если строить
     * интерполяцию при перепаде в 1 блок, округление поднимает первое кольцо
     * на уровень двора и добавляет уступ там, где местность была ровной.
     */
    private static final int EDGE_MIN_TRANSITION_DROP = 1;

    /**
     * Минимальная видимая высота стены над внешней поверхностью у периметра.
     *
     * <p>Считается от верхнего сплошного блока периметра ({@code floorY + 1}).
     * Два блока — порог, ниже которого игрок с внешней земли перепрыгивает
     * периметр: запрыгнуть можно на блок выше уровня опоры, поэтому внешняя
     * поверхность у стены не должна подниматься выше яруса двора.
     */
    private static final int WALL_OUTER_MIN_EXPOSURE = 2;

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
     * Подготовить площадку под застройку: выровнять рельеф о плану.
     *
     * <p>Вызывается ровно один раз — из {@code CampManager.createCamp} до
     * записи лагеря. Здесь уже нет причин отказа: план рассчитан, зона
     * подготовки проверена на {@code BlockEntity}.
     *
     * <p>Порядок: удаление деревьев → по клеткам расчистка → подсыпка →
     * поверхность → срез.
     *
     * <p>Деревья удаляются до выравнивания, иначе крона осталась бы висеть
     * после среза холма, а ствол оказался бы внутри подсыпаемого столбца и
     * торчал из нового пола. Признак «дерево в зоне» — ствол, а не листва:
     * крона соседнего дерева может накрыть площадку, не касаясь её стволом,
     * и такое дерево остаётся.
     *
     * <p>Все клетки двора выравниваются одинаково. Радио-якорь игрока стоит в
     * {@code requestPos} и всё равно переносится в {@link #radioPos} при
     * генерации ({@link #placeRadio}), поэтому на время выравнивания он
     * снимается: запись лагеря создаётся позже и удаление блока ничего не
     * распускает. Колонка нового якоря тоже пуста — блок там ещё не стоит.
     *
     * @param level серверный уровень
     * @param plan  план застройки
     */
    public static void prepareTerrain(ServerLevel level, CampSitePlanner.Plan plan) {
        int floorY = plan.getFloorY();
        int side = plan.getSide();
        int clearTop = floorY + CampTuning.CAMP_CLEAR_HEADROOM;

        // Исходный якорь снимается здесь, а не в placeRadio: иначе на новом
        // ярусе он оказался бы внутри подсыпки или попал под срез. Снимается
        // только настоящий camp_radio — прочие блоки в requestPos не трогаются.
        BlockPos requestPos = plan.getRequestPos();
        if (level.getBlockState(requestPos).is(ModBlocks.CAMP_RADIO.get())) {
            level.setBlock(requestPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }

        // Деревья — до любого выравнивания: см. javadoc метода. Решение
        // используется всей остальной подготовкой: снесённые блоки уже
        // удалены, а сохранённые деревья нельзя задевать очисткой.
        TreeDecision trees = removeObstructingTrees(level, plan);

        for (int lx = 0; lx < side; lx++) {
            for (int lz = 0; lz < side; lz++) {
                BlockPos column = plan.localToWorld(lx, lz, floorY);
                int surfaceY = plan.cellSurfaceY(lx, lz);
                int groundY = surfaceY - 1;

                // 1. Расчистка: цветы и прочая мягкая растительность.
                //    Цветы в 1.20.1 возвращают canBeReplaced() == false, поэтому
                //    без проверки blocksMotion() они пережили бы выравнивание.
                //    Брёвна и листва здесь не трогаются: судьбу деревьев решает
                //    removeObstructingTrees, и сохранённое дерево не должно
                //    терять часть кроны от границы двора.
                for (int y = groundY + 1; y <= clearTop; y++) {
                    BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
                        continue;
                    }
                    if (state.canBeReplaced() || !state.blocksMotion()) {
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

        prepareEdges(level, plan, trees);
    }

    /**
     * Разобрать деревья зоны подготовки и снести те, чей ствол её пересекает.
     *
     * <p>Признак принадлежности дерева к зоне — ствол, а не листва. Крона
     * широкого дерева может накрыть площадку, стоя при этом в стороне: такое
     * дерево не мешает застройке, и его нельзя сносить. Поэтому сначала
     * ищутся брёвна внутри зоны, затем от каждого бревна обходом по связности
     * восстанавливается весь ствол вместе с кроной, и только если обход
     * удался целиком, дерево удаляется — иначе оно остаётся нетронутым.
     *
     * <p>Правило «всё или ничего» принципиально: при упоре в лимит обхода
     * частичный снос оставлял в воздухе обрубок ствола или одинокую листву.
     *
     * <p>Возвращённое решение — единственный источник правды о деревьях для
     * остальной подготовки: очистка клеток и коридор ворот удаляют только
     * блоки из набора сноса, а блоки сохранённых деревьев не трогают даже
     * внутри двора.
     *
     * @param level серверный уровень
     * @param plan  план застройки
     * @return решение по деревьям: что снесено и что сохраняется
     */
    private static TreeDecision removeObstructingTrees(ServerLevel level,
                                                       CampSitePlanner.Plan plan) {
        TreeDecision decision = new TreeDecision();
        Set<BlockPos> zoneLogs = new HashSet<>();
        collectZoneLogs(level, plan, zoneLogs);
        if (zoneLogs.isEmpty()) {
            return decision;
        }
        // Сюда попадают только стволы, у которых хотя бы одно бревно лежало
        // внутри зоны. Дерево, накрывшее площадку одной кроной, в этом
        // множестве отсутствует — его не трогаем.
        Set<BlockPos> handled = new HashSet<>();
        for (BlockPos log : zoneLogs) {
            if (handled.contains(log)) {
                continue;
            }
            TreeBlocks tree = collectTree(level, log);
            // Бревна помечаются обработанными независимо от полноты: второе
            // бревно того же дерева уже не нужно обходить заново.
            handled.addAll(tree.trunk);
            // Спорная листва копится независимо от полноты дерева: после
            // сноса стволов именно она может остаться висеть в воздухе.
            decision.collectSpare(tree);
            if (!tree.complete) {
                // Сохранение — это отсутствие дерева в наборе сноса: оно
                // остаётся целиком, включая нависающую над лагерем крону.
                continue;
            }
            decision.remove(tree);
            for (BlockPos pos : tree.trunk) {
                clearTreeBlock(level, pos);
            }
            for (BlockPos pos : tree.canopy) {
                clearTreeBlock(level, pos);
            }
        }
        // Стволы уже снесены, поэтому листва без единого бревна рядом не
        // принадлежит ни одному оставшемуся дереву и висела бы в воздухе.
        for (BlockPos pos : decision.spare) {
            if (!level.getBlockState(pos).is(BlockTags.LEAVES)) {
                continue;
            }
            if (hasLogNear(level, pos)) {
                continue;
            }
            clearTreeBlock(level, pos);
        }
        return decision;
    }

    /**
     * Проверить, есть ли рядом бревно оставшегося дерева.
     *
     * <p>Область проверки — куб {@link #CANOPY_SCAN_RADIUS}: листва, у
     * которой в этом радиусе нет ни одного бревна, не принадлежит ни одному
     * стволу и должна быть удалена.
     *
     * @param level серверный уровень
     * @param pos   координата блока листвы
     * @return {@code true}, если поблизости осталось бревно
     */
    private static boolean hasLogNear(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -CANOPY_SCAN_RADIUS; dx <= CANOPY_SCAN_RADIUS; dx++) {
            for (int dy = -CANOPY_SCAN_RADIUS; dy <= CANOPY_SCAN_RADIUS; dy++) {
                for (int dz = -CANOPY_SCAN_RADIUS; dz <= CANOPY_SCAN_RADIUS; dz++) {
                    cursor.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (level.getBlockState(cursor).is(BlockTags.LOGS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Собрать одно дерево: ствол и крону.
     *
     * <p>Крона обходится только у полностью собранного ствола: частичный
     * ствол означает, что принадлежность листвы дереву не установлена.
     *
     * @param level серверный уровень
     * @param start бревно, с которого начинается обход
     * @return блоки дерева и признак полноты обхода
     */
    private static TreeBlocks collectTree(ServerLevel level, BlockPos start) {
        TreeBlocks tree = new TreeBlocks();
        collectTrunk(level, start, tree);
        if (tree.complete) {
            collectCanopy(level, tree);
        }
        return tree;
    }

    /**
     * Найти все брёвна внутри зоны подготовки.
     *
     * <p>Зона подготовки — квадрат площадки плюс полоса
     * {@link CampTuning#CAMP_CLEAR_MARGIN} наружу. Дополнительный запас
     * {@link #TREE_DETECT_EXTRA} равен нулю: обнаружение стволов не выходит
     * за границу полосы, грунт и деревья обрабатываются по одной площади.
     *
     * <p>По высоте поиск идёт от минимальной высоты мира до
     * {@link CampTuning#CAMP_CLEAR_HEADROOM} над двором: ствол не может
     * начинаться ниже бедрока и не тянеся выше этой границы в зоне лагеря.
     * Колонка якоря пропускается при удалении, а не при поиске: если в
     * колонке якоря окажется бревно, его принадлежность дереву всё равно
     * нужно восстановить.
     *
     * @param level  серверный уровень
     * @param plan   план застройки
     * @param out    множество, куда складываются найденные брёвна
     */
    private static void collectZoneLogs(ServerLevel level, CampSitePlanner.Plan plan,
                                        Set<BlockPos> out) {
        int margin = CampTuning.CAMP_CLEAR_MARGIN + TREE_DETECT_EXTRA;
        int floorY = plan.getFloorY();
        int minY = level.getMinBuildHeight();
        int maxY = floorY + CampTuning.CAMP_CLEAR_HEADROOM;
        int minX = plan.getCenter().getX() - plan.getRadius() - margin;
        int maxX = plan.getCenter().getX() + plan.getRadius() + margin;
        int minZ = plan.getCenter().getZ() - plan.getRadius() - margin;
        int maxZ = plan.getCenter().getZ() + plan.getRadius() + margin;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    if (level.getBlockState(cursor).is(BlockTags.LOGS)) {
                        out.add(cursor.immutable());
                    }
                }
            }
        }
    }

    /**
     * Восстановить ствол по связности от одного бревна.
     *
     * <p>Принадлежность блоку ствола определяется не расстоянием, а
     * соседством: обход идёт по брёвнам, у которых смещение по X и Z не
     * превышает {@link #TRUNK_SCAN_RADIUS} блоков. Это покрывает наклонные
     * стволы и двуствольные породы (дуб 2×2), но не склеивает два отдельных
     * дерева, стоящих рядом.
     *
     * <p>Набор ствола у каждого дерева свой, поэтому чужой ствол больше не
     * обрывает обход: раньше проверка по общей коллекции прекращала
     * расширение на блоке, уже собранном соседним деревом, и часть ствола
     * оставалась стоять. Если обход упёрся в {@link #TRUNK_BLOCK_LIMIT},
     * дерево помечается неполным и не удаляется вовсе.
     *
     * @param level серверный уровень
     * @param start бревно, с которого начинается обход
     * @param tree  собираемое дерево (ствол накапливается здесь)
     */
    private static void collectTrunk(ServerLevel level, BlockPos start, TreeBlocks tree) {
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            if (seen.size() >= TRUNK_BLOCK_LIMIT) {
                // Обход не завершён: ствол обрезан лимитом. Такой обрубок
                // нельзя оставлять висеть, поэтому дерево не трогаем.
                tree.complete = false;
                return;
            }
            BlockPos current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            tree.trunk.add(current);
            for (int dx = -TRUNK_SCAN_RADIUS; dx <= TRUNK_SCAN_RADIUS; dx++) {
                for (int dz = -TRUNK_SCAN_RADIUS; dz <= TRUNK_SCAN_RADIUS; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos next = current.offset(dx, dy, dz);
                        if (seen.contains(next)) {
                            continue;
                        }
                        if (level.getBlockState(next).is(BlockTags.LOGS)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
    }

    /**
     * Собрать крону вокруг ствола.
     *
     * <p>Обход начинается сразу со всех блоков ствола и идёт по связности
     * листвы. Одной связности недостаточно: у соприкасающихся деревьев кроны
     * образуют общий объём, и обход перетекал на листву соседа — её сносили,
     * а ствол соседа оставался голым. Поэтому каждый найденный блок листвы
     * дополнительно проверяется на принадлежность по расстоянию до стволов
     * ({@link #ownership}).
     *
     * <p>Допустимая область — расстояние до ближайшего бревна ствола не
     * больше {@link #CANOPY_SCAN_RADIUS}. Метрика кубическая, потому что
     * крона в Minecraft строится кубическими слоями. Куб вокруг габарита
     * ствола здесь не подходит: у высокого дерева он отсекал верхние слои
     * кроны, и после сноса ствола они оставались висеть.
     *
     * <p>Блок с неоднозначной принадлежностью просто не удаляется и не
     * продолжает обход: спорный участок остаётся у соседа. Дерево при этом
     * считается собранным — иначе в сомкнутом лесу, где чужие стволы стоят
     * на равном расстоянии почти от каждой листвы, не сносилось бы ни одно
     * дерево.
     *
     * <p>Если обход упёрся в {@link #CANOPY_BLOCK_LIMIT}, дерево помечается
     * неполным и не удаляется вовсе: частично снесённая крона оставила бы
     * висеть обрубок.
     *
     * @param level серверный уровень
     * @param tree  дерево с уже собранным стволом (крона дописывается сюда)
     */
    private static void collectCanopy(ServerLevel level, TreeBlocks tree) {
        if (tree.trunk.isEmpty()) {
            tree.complete = false;
            return;
        }
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>(tree.trunk);
        queue.addAll(tree.trunk);

        while (!queue.isEmpty()) {
            if (visited.size() >= CANOPY_BLOCK_LIMIT) {
                tree.complete = false;
                return;
            }
            BlockPos current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = current.offset(dx, dy, dz);
                        if (visited.contains(next)) {
                            continue;
                        }
                        int ownDistance = trunkDistance(next, tree.trunk);
                        if (ownDistance > CANOPY_SCAN_RADIUS) {
                            continue;
                        }
                        if (!level.getBlockState(next).is(BlockTags.LEAVES)) {
                            continue;
                        }
                        Ownership owner = ownership(level, next, tree.trunk, ownDistance);
                        if (owner != Ownership.OWN) {
                            // Крона соседнего дерева либо спорный блок: его не
                            // удаляем вместе с этим деревом, но обход через
                            // него продолжается. Иначе листва за спорным
                            // блоком оставалась непосещённой и висела в
                            // воздухе после сноса стволов.
                            tree.spare.add(next);
                            visited.add(next);
                            queue.add(next);
                            continue;
                        }
                        visited.add(next);
                        tree.canopy.add(next);
                        queue.add(next);
                    }
                }
            }
        }
    }

    /**
     * Решение по деревьям зоны подготовки.
     *
     * <p>Единственный источник правды: остальная подготовка удаляет только
     * блоки из набора сноса и не трогает блоки сохранённых деревьев. Так
     * сохранённое дерево не теряет часть кроны от границы двора, а снесённое
     * удаляется целиком — включая листву за пределами зоны.
     */
    private static final class TreeDecision {

        /** Блоки деревьев, признанных подлежащими сносу. */
        private final Set<BlockPos> removeBlocks = new HashSet<>();

        /**
         * Листва, отброшенная при обходе как чужая или спорная.
         *
         * <p>Проверяется после сноса стволов: если рядом не осталось ни
         * одного бревна, листва никому не принадлежит и удаляется.
         */
        private final Set<BlockPos> spare = new HashSet<>();

        /**
         * Запомнить дерево как подлежащее сносу.
         *
         * @param tree дерево с полным обходом
         */
        private void remove(TreeBlocks tree) {
            this.removeBlocks.addAll(tree.trunk);
            this.removeBlocks.addAll(tree.canopy);
        }

        /**
         * Накопить листву, не попавшую в крону дерева.
         *
         * @param tree разобранное дерево
         */
        private void collectSpare(TreeBlocks tree) {
            this.spare.addAll(tree.spare);
        }

        /**
         * Проверить, можно ли удалять блок дерева.
         *
         * @param pos координата блока
         * @return {@code true}, если блок принадлежит снесённому дереву
         */
        private boolean canClear(BlockPos pos) {
            return this.removeBlocks.contains(pos);
        }
    }

    /**
     * Расстояние от блока до ближайшего бревна ствола.
     *
     * <p>Метрика кубическая (максимум из трёх разностей), как и отсечка
     * кроны: крона строится кубическими слоями, и евклидово расстояние
     * срезало бы её углы.
     *
     * @param pos   проверяемый блок
     * @param trunk бревна ствола
     * @return расстояние до ближайшего бревна, либо {@link Integer#MAX_VALUE},
     *         если ствол пуст
     */
    private static int trunkDistance(BlockPos pos, Set<BlockPos> trunk) {
        int best = Integer.MAX_VALUE;
        for (BlockPos log : trunk) {
            int distance = Math.max(Math.abs(pos.getX() - log.getX()),
                    Math.max(Math.abs(pos.getY() - log.getY()),
                            Math.abs(pos.getZ() - log.getZ())));
            if (distance < best) {
                best = distance;
            }
        }
        return best;
    }

    /**
     * Определить, принадлежит ли блок листвы обрабатываемому дереву.
     *
     * <p>Одна связность листвы ненадёжна: у соприкасающихся деревьев кроны
     * образуют общий объём, и обход перетекал на листву соседа. Поэтому блок
     * сравнивается по расстоянию до своего ствола и до ближайшего чужого
     * бревна. Поиск чужого ограничен кубом радиуса {@code ownDistance}:
     * бревно дальше этого расстояния не может оказаться ближе своего.
     *
     * <p>Равное расстояние означает, что листва одинаково удалена от двух
     * стволов, и принадлежность не установлена — такой блок остаётся на
     * месте, но снос дерева не отменяет.
     *
     * @param level       серверный уровень
     * @param pos         проверяемый блок листвы
     * @param trunk       бревна обрабатываемого ствола
     * @param ownDistance расстояние от блока до своего ствола
     * @return принадлежность блока
     */
    private static Ownership ownership(ServerLevel level, BlockPos pos, Set<BlockPos> trunk,
                                       int ownDistance) {
        int radius = Math.min(ownDistance, CANOPY_SCAN_RADIUS);
        int bestForeign = Integer.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = pos.offset(dx, dy, dz);
                    if (trunk.contains(candidate)) {
                        continue;
                    }
                    if (!level.getBlockState(candidate).is(BlockTags.LOGS)) {
                        continue;
                    }
                    int distance = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
                    if (distance < bestForeign) {
                        bestForeign = distance;
                    }
                }
            }
        }
        if (bestForeign > ownDistance) {
            return Ownership.OWN;
        }
        if (bestForeign == ownDistance) {
            return Ownership.AMBIGUOUS;
        }
        return Ownership.FOREIGN;
    }

    /**
     * Принадлежность блока листвы обрабатываемому дереву.
     */
    private enum Ownership {

        /** Блок ближе к своему стволу — его можно удалять. */
        OWN,

        /** Блок ближе к чужому стволу — это крона соседа, её не трогаем. */
        FOREIGN,

        /** Расстояния равны — блок спорный, его не трогаем. */
        AMBIGUOUS
    }

    /**
     * Блоки одного дерева и признак полноты обхода.
     *
     * <p>Коллекции принадлежат ровно одному дереву: так обход соседей не
     * обрывается на уже найденных блоках. {@code complete == false} означает,
     * что обход упёрся в лимит или встретил спорную принадлежность, и дерево
     * нельзя удалять — иначе в мире остался бы обрубок.
     */
    private static final class TreeBlocks {

        private final Set<BlockPos> trunk = new HashSet<>();
        private final Set<BlockPos> canopy = new HashSet<>();

        /**
         * Листва рядом со стволом, отброшенная как чужая или спорная.
         *
         * <p>Хранится отдельно от {@link #canopy}: она не принадлежит этому
         * дереву и не удаляется вместе с ним, но её нужно проверить после
         * сноса всех стволов.
         */
        private final Set<BlockPos> spare = new HashSet<>();

        private boolean complete = true;
    }

    /**
     * Убрать один блок дерева, не задевая чужие объекты.
     *
     * <p>Твёрдые рукотворные блоки и блоки с {@code BlockEntity} не трогаются:
     * снос дерева не должен разрушать постройки, случайно оказавшиеся в кроне.
     *
     * @param level серверный уровень
     * @param pos   координата блока
     */
    private static void clearTreeBlock(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) != null) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES)) {
            return;
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    /**
     * Подготовить край площадки и подход к воротам.
     *
     * <p>Задача перехода — не дать двору обрываться ступенькой в поле.
     * Плоский вал по всему контуру не подходит: он превращает окрестность в
     * насыпную платформу с резкой квадратной границей. Вместо этого высота
     * каждой внешней клетки считается отдельно: чем дальше клетка от
     * периметра, тем ближе её поверхность к настоящему рельефу этой
     * местности. На самом периметре поверхность равна двору, у внешней
     * границы полосы — медиане проб окружающего рельефа, между ними —
     * линейная интерполяция и потолок в один блок на клетку.
     *
     * <p>Ширина полосы тоже не постоянна: клетка, чей собственный рельеф уже
     * лежит на уровне двора, не требует перехода и не обрабатывается. Для
     * клеток внутри периметра ничего не меняется — там рельеф выровнен
     * заранее в {@link #prepareTerrain}.
     *
     * <p>Подход к воротам — коридор шириной
     * {@link CampSitePlanner#APPROACH_WIDTH} блока наружу от ворот. Его рельеф
     * не перестраивается: срезается только грунт,
     * физически перекрывающий вход, и над поверхностью освобождается
     * {@link #GATE_ACCESS_HEIGHT} блока под рост игрока.
     *
     * @param level серверный уровень
     * @param plan  план
     * @param trees решение по деревьям
     */
    private static void prepareEdges(ServerLevel level, CampSitePlanner.Plan plan,
                                     TreeDecision trees) {
        int floorY = plan.getFloorY();
        int margin = CampTuning.CAMP_CLEAR_MARGIN;
        int half = plan.getRadius();

        BlockPos gate = plan.getGatePos();
        Direction facing = plan.getGateFacing();
        int halfWidth = CampSitePlanner.APPROACH_WIDTH / 2;

        // Переход строится по шагам от периметра наружу: первое кольцо
        // повторяет уровень двора, последнее совпадает с рельефом местности.
        // Клетки коридора у ворот пропускаются: у них своя обработка ниже,
        // иначе проход начал бы проседать вместе с пологим склоном.
        for (int step = 1; step <= margin; step++) {
            int ringMinX = plan.getCenter().getX() - half - step;
            int ringMaxX = plan.getCenter().getX() + half + step;
            int ringMinZ = plan.getCenter().getZ() - half - step;
            int ringMaxZ = plan.getCenter().getZ() + half + step;

            blendRing(level, plan, floorY, step, margin, ringMinX, ringMaxX,
                    ringMinZ, ringMaxZ, gate, facing, halfWidth);
        }

        // Подход к воротам: поверхность ведётся от порога двора наружу с
        // перепадом не более блока на клетку. Высокие выступы срезаются,
        // провалы подсыпаются ровно настолько, чтобы шаг остался проходимым.
        prepareApproachCorridor(level, gate, facing, floorY, halfWidth, margin, trees);
    }

    /**
     * Выровнять коридор подхода к воротам по допустимому перепаду.
     *
     * <p>Обработка идёт строками от ворот наружу, поэтому высота каждой
     * клетки ограничена высотой предыдущей строки в той же колонке X: вверх
     * и вниз не более одного блока. Готовой лестницы не строится — уровень
     * каждой клетки выводится из её собственного рельефа, а исправляется
     * только то, что нарушает проходимость.
     *
     * <p>Верхняя граница — уровень поверхности двора
     * ({@code floorY - 1}): подход не поднимается выше порога, иначе у входа
     * появилась бы ступенька. Низины подсыпаются мягким материалом, твёрдые
     * блоки и объекты с {@code BlockEntity} обрывают подсыпку и срез.
     *
     * @param level     серверный уровень
     * @param gate      центральная клетка ворот
     * @param facing    направление ворот наружу
     * @param floorY    уровень пола двора
     * @param halfWidth половина ширины коридора
     * @param margin    глубина коридора в клетках
     * @param trees     решение по деревьям
     */
    private static void prepareApproachCorridor(ServerLevel level, BlockPos gate, Direction facing,
                                                int floorY, int halfWidth, int margin,
                                                TreeDecision trees) {
        int courtyardY = floorY - 1;
        Direction lateral = facing.getClockWise();
        int[] previous = new int[halfWidth * 2 + 1];
        Arrays.fill(previous, courtyardY);
        for (int step = 1; step <= margin; step++) {
            for (int offset = -halfWidth; offset <= halfWidth; offset++) {
                int x = gate.getX() + facing.getStepX() * step
                        + lateral.getStepX() * offset;
                int z = gate.getZ() + facing.getStepZ() * step
                        + lateral.getStepZ() * offset;
                int index = offset + halfWidth;
                int surfaceY = approachSurfaceY(level, x, z, previous[index], courtyardY);
                previous[index] = surfaceY;
                clearApproachHeadroom(level, x, z, surfaceY, trees);
            }
        }
    }

    /**
     * Привести одну клетку коридора к допустимому перепаду.
     *
     * <p>Клетка сохраняет свой рельеф, если он укладывается в интервал
     * {@code [previous - 1, previous + 1]} и не поднимается выше порога
     * двора. Иначе снимается только естественный грунт выше верхней границы
     * или подсыпается мягкий материал до нижней.
     *
     * @param level     серверный уровень
     * @param x         координата
     * @param z         координата
     * @param previousY поверхность предыдущей строки коридора
     * @param courtyardY уровень поверхности двора (верхняя граница)
     * @return фактический уровень поверхности после правки
     */
    private static int approachSurfaceY(ServerLevel level, int x, int z, int previousY,
                                        int courtyardY) {
        int groundY = CampSitePlanner.findGroundY(level, x, z);
        if (groundY == CampSitePlanner.FLUID_FOUND || groundY == CampSitePlanner.NO_GROUND) {
            // Жидкость или пустота: рельеф не трогается, опорой для следующей
            // клетки остаётся предыдущий уровень.
            return previousY;
        }
        // Тот же предел шага, что и в оценке ориентации: иначе выбранная
        // сторона оценивалась бы по одним правилам, а строилась по другим.
        int maxY = Math.min(previousY + CampSitePlanner.APPROACH_STEP_LIMIT, courtyardY);
        int minY = previousY - CampSitePlanner.APPROACH_STEP_LIMIT;
        if (groundY > maxY) {
            return cutDownTo(level, x, z, groundY, maxY);
        }
        if (groundY < minY) {
            return raiseApproachColumn(level, x, z, groundY, minY);
        }
        return groundY;
    }

    /**
     * Подсыпать низину коридора до нижней допустимой границы.
     *
     * <p>Подсыпка минимальна: она возвращает только недостающую разницу и не
     * заваливает коридор до уровня двора. Твёрдый блок или объект с
     * {@code BlockEntity} останавливает подсыпку — чужие постройки не
     * разрушаются и не засыпаются.
     *
     * @param level   серверный уровень
     * @param x       координата
     * @param z       координата
     * @param groundY текущий уровень поверхности
     * @param targetY уровень, до которого нужно поднять клетку
     * @return фактический уровень поверхности после подсыпки
     */
    private static int raiseApproachColumn(ServerLevel level, int x, int z, int groundY, int targetY) {
        BlockState surface = level.getBlockState(new BlockPos(x, groundY, z));
        int reached = groundY;
        for (int y = groundY + 1; y <= targetY; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (level.getBlockEntity(pos) != null || !isReplaceableSoft(level.getBlockState(pos))) {
                break;
            }
            level.setBlock(pos, y == targetY ? surface : Blocks.DIRT.defaultBlockState(),
                    Block.UPDATE_CLIENTS);
            reached = y;
        }
        return reached;
    }

    /**
     * Клетка, принадлежащая коридору подхода к воротам.
     *
     * <p>Коридор начинается сразу за плоскостью ворот и уходит наружу на
     * ширину полосы перехода, по ширине равнясь проёму ворот. Клетки
     * проецируются на направление ворот и на перпендикулярную ось, поэтому
     * проверка не зависит от того, куда смотрят ворота.
     *
     * @param gate      центральная клетка ворот
     * @param facing    направление ворот наружу
     * @param halfWidth половина ширины коридора
     * @param margin    глубина коридора в клетках
     * @param x         проверяемая координата
     * @param z         проверяемая координата
     * @return {@code true}, если клетка входит в коридор
     */
    private static boolean isGateAccessCell(BlockPos gate, Direction facing, int halfWidth,
                                            int margin, int x, int z) {
        Direction lateral = facing.getClockWise();
        int forward = (x - gate.getX()) * facing.getStepX()
                + (z - gate.getZ()) * facing.getStepZ();
        if (forward < 1 || forward > margin) {
            return false;
        }
        int sideways = Math.abs((x - gate.getX()) * lateral.getStepX()
                + (z - gate.getZ()) * lateral.getStepZ());
        return sideways <= halfWidth;
    }

    /**
     * Обработать одно кольцо перехода вокруг площадки.
     *
     * <p>Кольцо — это рамка на расстоянии {@code step} от периметра. Ниже
     * двора переход подсыпает низину, выше — срезает естественный грунт до
     * безопасной высоты. Без среза земля подходила бы к стене вплотную на
     * уровне двора, и периметр перепрыгивался бы снаружи.
     *
     * <p>Срез выполняется только в полосе перехода: за её внешней границей
     * рельеф остаётся исходным. Высота, до которой срезается кольцо, растёт
     * на блок за кольцо, поэтому у стены остаётся ровный откос, а не обрыв.
     *
     * <p>Высота считается в уровнях блоков: и двор, и местность задаются
     * ярусом грунта, а не ярусом воздуха над ним. Смешение этих систем давало
     * постоянный лишний блок на внешнем кольце.
     *
     * @param level  серверный уровень
     * @param plan   план застройки
     * @param floorY уровень пола двора
     * @param step   номер кольца от периметра (1..margin)
     * @param margin ширина полосы перехода в клетках
     * @param minX   минимальная координата рамки
     * @param maxX   максимальная координата рамки
     * @param minZ   минимальная координата рамки
     * @param maxZ   максимальная координата рамки
     * @param gate   центральная клетка ворот
     * @param facing направление ворот наружу
     * @param halfWidth половина ширины коридора подхода
     */
    private static void blendRing(ServerLevel level, CampSitePlanner.Plan plan, int floorY,
                                  int step, int margin, int minX, int maxX,
                                  int minZ, int maxZ, BlockPos gate, Direction facing,
                                  int halfWidth) {
        // Уровень грунта двора. Блок поверхности лежит на floorY - 1, и все
        // дальнейшие сравнения ведутся именно в этих координатах.
        int courtyardY = floorY - 1;
        // Предел, до которого внешняя поверхность вправе подниматься у стены:
        // в этом случае видимый выступ стены над землёй равен
        // WALL_OUTER_MIN_EXPOSURE, и периметр нельзя перепрыгнуть снаружи.
        int maxOuterY = floorY + 1 - WALL_OUTER_MIN_EXPOSURE;
        double t = (double) step / margin;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // Внутри рамки рельеф уже выровнен в prepareTerrain, повторно
                // его трогать нельзя: затёрся бы восстановленный дёрн.
                boolean onRing = x == minX || x == maxX || z == minZ || z == maxZ;
                if (!onRing) {
                    continue;
                }
                if (isGateAccessCell(gate, facing, halfWidth, margin, x, z)) {
                    continue;
                }

                int distX = x - plan.getCenter().getX();
                int distZ = z - plan.getCenter().getZ();
                int naturalY = probeOuterTerrainY(level, plan.getCenter(), distX, distZ,
                        plan.getRadius(), margin);
                // Фактическая высота именно этой колонки. Проба снаружи
                // описывает местность на удалении и локальный выступ из дёрна
                // не видит: при низких пробах колонка считалась «нормальной»
                // и не обрабатывалась вовсе.
                int localGroundY = CampSitePlanner.findGroundY(level, x, z);
                boolean localKnown = localGroundY != CampSitePlanner.FLUID_FOUND
                        && localGroundY != CampSitePlanner.NO_GROUND;
                // Для решения о срезе берётся более высокая из двух оценок:
                // локальный выступ срезается независимо от того, что проба
                // показала низкую местность.
                int outerY = localKnown ? Math.max(naturalY, localGroundY) : naturalY;
                if (outerY > maxOuterY) {
                    // Рельеф не ниже стены: без среза земля подходила бы
                    // к периметру на высоту, с которой игрок запрыгивает
                    // внутрь. Снимается только естественный грунт и только
                    // в полосе перехода — за ней рельеф не меняется.
                    int targetY = blendedSurfaceY(courtyardY, outerY, t);
                    // Не более блока на кольцо: срез не образует стенку у
                    // самого периметра, а уходит вверх ступенями по полосе.
                    targetY = Math.min(targetY, courtyardY + step - 1);
                    cutEdgeColumn(level, x, z, targetY);
                    continue;
                }
                // Перепад в один блок игрок переступает сам: переход только
                // добавил бы уступ там, где местность была ровной.
                if (courtyardY - naturalY <= EDGE_MIN_TRANSITION_DROP) {
                    continue;
                }
                int targetY = blendedSurfaceY(courtyardY, naturalY, t);

                // Потолок в один блок на кольцо: даже при крутом рельефе
                // переход остаётся пологим, а не превращается в стену.
                double previousT = Math.max(0.0D, t - 1.0D / margin);
                int previousY = blendedSurfaceY(courtyardY, naturalY, previousT);
                targetY = Math.min(targetY, previousY + 1);
                fillEdgeColumn(level, x, z, targetY);
            }
        }
    }

    /**
     * Срезать естественный грунт кольца до безопасной высоты.
     *
     * <p>Снимается только природный материал ({@link #isNaturalTerrain}):
     * рукотворный блок обрывает срез, поэтому постройка у лагеря остаётся
     * нетронутой. Ярус {@code targetY} сохраняется как новая поверхность.
     *
     * @param level   серверный уровень
     * @param x       координата
     * @param z       координата
     * @param targetY уровень, который остаётся поверхностью
     * @return фактический уровень поверхности после среза
     */
    private static int cutEdgeColumn(ServerLevel level, int x, int z, int targetY) {
        int groundY = CampSitePlanner.findGroundY(level, x, z);
        if (groundY == CampSitePlanner.FLUID_FOUND || groundY == CampSitePlanner.NO_GROUND) {
            return groundY;
        }
        if (groundY <= targetY) {
            return groundY;
        }
        return cutDownTo(level, x, z, groundY, targetY);
    }

    /**
     * Срезать грунт до допустимого уровня.
     *
     * <p>Сверху вниз снимается только природный материал
     * ({@link #isNaturalTerrain}): рукотворный блок обрывает срез, чтобы
     * подготовка подхода не разрушила чужую постройку.
     *
     * <p>Уровень {@code limitY} остаётся занятым: цикл снимает блоки строго
     * выше него. Именно этот оставшийся блок служит поверхностью прохода,
     * поэтому срез не оставляет провала на лишний блок.
     *
     * @param level   серверный уровень
     * @param x       координата
     * @param z       координата
     * @param groundY текущий ярус поверхности
     * @param limitY  уровень, который сохраняется как поверхность
     * @return фактический уровень поверхности после среза
     */
    private static int cutDownTo(ServerLevel level, int x, int z, int groundY, int limitY) {
        int surfaceY = groundY;
        for (int y = groundY; y > limitY; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!isNaturalTerrain(level.getBlockState(pos))) {
                break;
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            surfaceY = y - 1;
        }
        return surfaceY;
    }

    /**
     * Освободить место над поверхностью прохода.
     *
     * <p>Игрок занимает почти два блока, поэтому над поверхностью
     * расчищается мягкая растительность. Твёрдый рукотворный блок остаётся:
     * коридор не должен сносить постройки.
     *
     * <p>Брёвна и листва удаляются только по решению
     * {@link #removeObstructingTrees}: сохранённое дерево у ворот не должно
     * терять часть ствола от расчистки коридора.
     *
     * @param level    серверный уровень
     * @param x        координата
     * @param z        координата
     * @param surfaceY уровень поверхности, от которого считается высота
     * @param trees    решение по деревьям
     */
    private static void clearApproachHeadroom(ServerLevel level, int x, int z, int surfaceY,
                                              TreeDecision trees) {
        int top = surfaceY + GATE_ACCESS_HEIGHT;
        for (int y = surfaceY + 1; y <= top; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)) {
                if (trees.canClear(pos)) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
                continue;
            }
            if (isReplaceableSoft(state)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * Замерить настоящий рельеф снаружи площадки по направлению от центра.
     *
     * <p>Проба берётся за внешней границей полосы перехода, а не в самой
     * клетке кольца: полосу мы меняем сами, и её текущее состояние уже не
     * описывает местность. Точка пробы зависит только от направления и
     * ширины полосы, но не от номера кольца — иначе одна и та же местность
     * давала бы разную высоту на разных кольцах и переход шёл бы уступами.
     *
     * <p>Из нескольких проб берётся нижняя медиана: она устойчива к стволу,
     * кусту и одиночному выступу, но не завышает результат.
     *
     * @param level  серверный уровень
     * @param center центр площадки
     * @param distX  смещение клетки от центра по X
     * @param distZ  смещение клетки от центра по Z
     * @param radius радиус площадки
     * @param margin ширина полосы перехода в клетках
     * @return медиана ярусов грунта снаружи или {@code center.getY() - 1},
     *         если рельеф замерить не удалось
     */
    private static int probeOuterTerrainY(ServerLevel level, BlockPos center, int distX, int distZ,
                                          int radius, int margin) {
        int dirX = Integer.signum(distX);
        int dirZ = Integer.signum(distZ);
        if (dirX == 0 && dirZ == 0) {
            return center.getY() - 1;
        }
        // Проба строго за полосой перехода, в неизменённой местности.
        int baseOffset = radius + margin + 1;
        int[] samples = new int[EDGE_PROBE_STEPS];
        int count = 0;
        for (int probe = 0; probe < EDGE_PROBE_STEPS; probe++) {
            int offset = baseOffset + probe;
            int x = center.getX() + dirX * offset;
            int z = center.getZ() + dirZ * offset;
            int groundY = CampSitePlanner.findGroundY(level, x, z);
            if (groundY == CampSitePlanner.FLUID_FOUND
                    || groundY == CampSitePlanner.NO_GROUND) {
                continue;
            }
            // Ярус грунта, а не ярус воздуха над ним: двор тоже считается
            // ярусом блока поверхности (floorY - 1).
            samples[count++] = groundY;
        }
        if (count == 0) {
            return center.getY() - 1;
        }
        int[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        return sorted[count / 2];
    }

    /**
     * Высота перехода между двором и рельефом местности.
     *
     * <p>{@code t = 0} даёт уровень двора, {@code t = 1} — уровень местности.
     * Между ними высота меняется плавно, поэтому резкой квадратной границы
     * не возникает.
     *
     * @param courtyardY уровень поверхности двора
     * @param naturalY   уровень поверхности местности
     * @param t          доля удаления от периметра (0..1)
     * @return расчётный уровень поверхности перехода
     */
    private static int blendedSurfaceY(int courtyardY, int naturalY, double t) {
        return (int) Math.round(courtyardY + (naturalY - courtyardY) * t);
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
     * Блок, который считается естественным рельефом.
     *
     * <p>Срез подхода вправе убирать только природный материал: почвы, песок,
     * гравий, глину, мох и базовые породы. Рукотворные блоки (булыжник,
     * кирпичи, доски) в эти теги не входят, поэтому постройка перед воротами
     * останется нетронутой.
     *
     * @param state состояние проверяемой клетки
     * @return {@code true}, если блок можно срезать как рельеф
     */
    private static boolean isNaturalTerrain(BlockState state) {
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        return state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.MOSS_BLOCK);
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
        record.setAnchor(plan.getRadioPos());
        record.setCenter(plan.getCenter());
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
        // Локальные углы после поворота планировки больше не совпадают с
        // мировыми минимумом и максимумом: при развороте на 90° «северо-запад»
        // уходит на другой конец света. Поэтому область собирается по всем
        // четырём углам — так она остаётся корректной при любом повороте.
        int last = plan.getSide() - 1;
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            int localX = (corner & 1) == 0 ? 0 : last;
            int localZ = (corner & 2) == 0 ? 0 : last;
            BlockPos world = plan.localToWorld(localX, localZ, plan.getFloorY());
            minX = Math.min(minX, world.getX());
            maxX = Math.max(maxX, world.getX());
            minZ = Math.min(minZ, world.getZ());
            maxZ = Math.max(maxZ, world.getZ());
        }

        CampStructurePlacer.refreshShapes(level,
                minX - 1, plan.getFloorY() - 1, minZ - 1,
                maxX + 1, plan.getFloorY() + PERIMETER_HEIGHT, maxZ + 1);
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
        // Исходный якорь уже снят в prepareTerrain: там он мешал выравниванию
        // двора и мог оказаться внутри подсыпки. Запись лагеря создаётся
        // позже, поэтому удаление блока лагерь не распускает.
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
        int side = plan.getRadius() * 2;
        int gateLocalX = plan.getGateLocalX();
        int radioLocalX = plan.getRadioLocalX();
        int radioLocalZ = plan.getRadioLocalZ();
        int farmLocalX = plan.getFarmLocalX();
        int farmLocalZ = plan.getFarmLocalZ();

        // Дорожки описаны в локальной сетке и переносятся в мир тем же
        // поворотом, что и вся геометрия. Поэтому рисунок двора при любой
        // ориентации остаётся тем же относительно ворот.

        // 1. От ворот внутрь двора. Ширина 2 блока — как у тропинок в
        //    шаблонах модулей: колонка створки и клетка рядом с ней.
        for (int lz = side - 1; lz >= radioLocalZ; lz--) {
            setPath(level, record, plan, gateLocalX, lz, floorY);
            setPath(level, record, plan, gateLocalX - 1, lz, floorY);
        }

        // 2. Поворот к оси фермы: две строки на уровне радио.
        for (int lx = radioLocalX; lx <= farmLocalX; lx++) {
            setPath(level, record, plan, lx, radioLocalZ, floorY);
            setPath(level, record, plan, lx, radioLocalZ + 1, floorY);
        }

        // 3. Поворот к входу фермы. Габарит 9×9 с центром в
        //    {@code farm}: вход после разворота на 180° смотрит наружу от
        //    постройки, поэтому дорожка заканчивается на пять клеток дальше
        //    центра. Строки радио уже проложены шагом 2, здесь идёт только
        //    оставшийся отрезок.
        int farmEntranceLocalZ = farmLocalZ + FARM_ENTRANCE_OFFSET;
        for (int lz = radioLocalZ - 1; lz >= farmEntranceLocalZ; lz--) {
            setPath(level, record, plan, farmLocalX - 1, lz, floorY);
            setPath(level, record, plan, farmLocalX, lz, floorY);
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
     * @param plan   план (для перевода локальной клетки в мир)
     * @param localX локальная клетка по X
     * @param localZ локальная клетка по Z
     * @param floorY ярус пола двора (первый воздушный)
     */
    private static void setPath(ServerLevel level, CampRecord record, CampSitePlanner.Plan plan,
                                int localX, int localZ, int floorY) {
        BlockPos above = plan.localToWorld(localX, localZ, floorY);
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
                    plan.worldAxis(Direction.Axis.X), y + blindLeft);
            placeSheet(level, plan.localToWorld(blindRight, side, floorY + y),
                    plan.worldAxis(Direction.Axis.X), y + blindRight);
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
        Direction.Axis localX = Direction.Axis.X;
        Direction.Axis localZ = Direction.Axis.Z;
        for (int i = 0; i <= side; i++) {
            if (!isWatchtowerCell(i, 0, side)) {
                placeWire(level, plan.localToWorld(i, 0, wireY),
                        plan.worldAxis(localX));
            }
            if (!isGateSpan(i, side) && !isGatePostCell(i, side)
                    && !isWatchtowerCell(i, side, side)) {
                placeWire(level, plan.localToWorld(i, side, wireY),
                        plan.worldAxis(localX));
            }
            if (!isWatchtowerCell(0, i, side)) {
                placeWire(level, plan.localToWorld(0, i, wireY),
                        plan.worldAxis(localZ));
            }
            if (!isWatchtowerCell(side, i, side)) {
                placeWire(level, plan.localToWorld(side, i, wireY),
                        plan.worldAxis(localZ));
            }
        }

        // === 4. Створка ворот ===
        // Одна створка ровно в центре пролёта (клетка 10). Соседние клетки
        // 9 и 11 обшиты панелями до яруса 2, поэтому створка стоит в раме.
        placeGateLeaf(level, plan.getGatePos(), plan.getGateFacing(), DoorHingeSide.LEFT);

        // Перемычка над створкой — строго на ярусе 2.
        //
        // <p>Створка {@code DoorBlock} занимает ДВА блока: нижнюю половину на
        // {@code floorY} и верхнюю на {@code floorY + 1}. Поэтому {@code above()}
        // (один ярус вверх) попадает ровно в верхнюю половину двери и заменяет
        // её панелью — дверь остаётся без верха, ломается и рассыпается.
        // Перемычка ставится через {@code above(2)}, на первый свободный ярус.
        placeSheet(level, plan.getGatePos().above(2), plan.getGateWallAxis(), 1);

        // Проволока над проёмом на ярусе 3: идёт над перемычкой и глухими
        // панелями и замыкается на опоры проёма, поднятые до яруса 3 — их
        // верхние блоки стоят на том же ярусе, поэтому линия замкнута.
        // Сами клетки опор (8 и 12) проволокой не закрываются.
        for (int x = blindLeft; x <= blindRight; x++) {
            placeWire(level, plan.localToWorld(x, side, floorY + 3),
                    plan.worldAxis(Direction.Axis.X));
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
     * @param localAxis ось пролёта в локальной сетке
     * @param tier  ярус панели (0 или 1)
     */
    private static void fillSpan(ServerLevel level, CampSitePlanner.Plan plan, int lx, int lz,
                                 int y, Direction.Axis localAxis, int tier) {
        placeSheet(level, plan.localToWorld(lx, lz, y), plan.worldAxis(localAxis),
                tier + lx * 3 + lz * 7);
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
    private static void placeGateLeaf(ServerLevel level, BlockPos pos, Direction facing,
                                      DoorHingeSide hinge) {
        BlockState lower = ModBlocks.CAMP_GATE.get().defaultBlockState()
                .setValue(DoorBlock.FACING, facing)
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
        StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation);
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
        // Пост у ворот: на шаг наружу от проёма. Клетки проёма заняты
        // створками и глухой панелью, поэтому точка стража — перед воротами,
        // с той стороны, куда смотрит вход.
        BlockPos gatePost = plan.getGatePos().relative(plan.getGateFacing());
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