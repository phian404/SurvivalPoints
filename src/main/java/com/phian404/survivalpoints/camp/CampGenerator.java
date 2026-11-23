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
     * Шаг опор периметра в локальных клетках.
     *
     * <p>4 клетки: опора, 3 блока панели, опора. Пролёт шириной 3 совпадает
     * с проёмом ворот ({@code 9..11}) и с шириной трезубца вышки.
     */
    private static final int POST_STEP = 4;

    /**
     * Ширина коридора подхода к воротам в клетках.
     *
     * <p>3 блока: центральная клетка проёма и по одной соседней слева и
     * справа. Совпадает с проёмом ворот, поэтому коридор не упирается в
     * створки и не шире самого входа.
     */
    private static final int GATE_ACCESS_WIDTH = 3;

    /**
     * Требуемая свободная высота коридора в блоках.
     *
     * <p>2 блока — минимум, при котором игрок ростом 1.8 проходит пешком,
     * не пригибаясь и не задевая головой блок над поверхностью.
     */
    private static final int GATE_ACCESS_HEIGHT = 2;

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
    private static final int EDGE_PROBE_STEPS = 3;

    /**
     * Минимальный перепад, при котором вообще строится переход.
     *
     * <p>Один блок игрок переступает без всякой подготовки. Если строить
     * интерполяцию при перепаде в 1 блок, округление поднимает первое кольцо
     * на уровень двора и добавляет уступ там, где местность была ровной.
     */
    private static final int EDGE_MIN_TRANSITION_DROP = 1;

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

        // Деревья — до любого выравнивания: см. javadoc метода.
        removeObstructingTrees(level, plan, anchor);

        for (int lx = 0; lx < side; lx++) {
            for (int lz = 0; lz < side; lz++) {
                BlockPos column = plan.localToWorld(lx, lz, floorY);
                int surfaceY = plan.cellSurfaceY(lx, lz);
                int groundY = surfaceY - 1;

                if (column.getX() == anchor.getX() && column.getZ() == anchor.getZ()) {
                    // Колонка якоря: сам блок camp_radio на floorY и его
                    // BlockEntity остаются нетронутыми, расчистка и срез здесь
                    // не выполняются. Выравнивается только грунт под якорем —
                    // иначе на месте закреплённого якоря остался бы провал.
                    fillUnderAnchor(level, column.getX(), column.getZ(), groundY, floorY);
                    continue;
                }

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
     * Удалить деревья, чей ствол пересекает зону подготовки площадки.
     *
     * <p>Признак принадлежности дерева к зоне — ствол, а не листва. Крона
     * широкого дерева может накрыть площадку, стоя при этом в стороне: такое
     * дерево не мешает застройке, и его нельзя сносить. Поэтому сначала
     * ищутся брёвна внутри зоны, затем от каждого бревна обходом по связности
     * восстанавливается весь ствол, и только если ствол действительно входит
     * в зону, дерево удаляется целиком — вместе с кроной за границами зоны.
     *
     * @param level  серверный уровень
     * @param plan   план застройки
     * @param anchor точка якоря (её колонка не трогается)
     */
    private static void removeObstructingTrees(ServerLevel level, CampSitePlanner.Plan plan,
                                               BlockPos anchor) {
        Set<BlockPos> trunks = new HashSet<>();
        collectZoneLogs(level, plan, trunks);
        if (trunks.isEmpty()) {
            return;
        }
        // Сюда попадают только стволы, у которых хотя бы одно бревно лежало
        // внутри зоны. Дерево, накрывшее площадку одной кроной, в этом
        // множестве отсутствует — его не трогаем.
        Set<BlockPos> wholeTrees = new HashSet<>();
        for (BlockPos log : trunks) {
            collectTrunk(level, log, wholeTrees);
        }
        Set<BlockPos> canopy = new HashSet<>();
        for (BlockPos log : wholeTrees) {
            collectCanopy(level, log, canopy);
        }
        for (BlockPos pos : wholeTrees) {
            clearTreeBlock(level, pos, anchor);
        }
        for (BlockPos pos : canopy) {
            clearTreeBlock(level, pos, anchor);
        }
    }

    /**
     * Найти все брёвна внутри зоны подготовки.
     *
     * <p>Зона — квадрат площадки плюс полоса {@link CampTuning#CAMP_CLEAR_MARGIN}
     * наружу и по высоте от грунта до {@link CampTuning#CAMP_CLEAR_HEADROOM}
     * над двором. Колонка якоря пропускается.
     *
     * @param level  серверный уровень
     * @param plan   план застройки
     * @param out    множество, куда складываются найденные брёвна
     */
    private static void collectZoneLogs(ServerLevel level, CampSitePlanner.Plan plan,
                                        Set<BlockPos> out) {
        int margin = CampTuning.CAMP_CLEAR_MARGIN;
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
     * дерева, стоящих рядом. Ограничение {@link #TRUNK_BLOCK_LIMIT} не даёт
     * обходу уйти в бесконечность на повреждённых данных.
     *
     * @param level серверный уровень
     * @param start бревно, с которого начинается обход
     * @param out   множество, куда складывается весь ствол
     */
    private static void collectTrunk(ServerLevel level, BlockPos start, Set<BlockPos> out) {
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty() && out.size() < TRUNK_BLOCK_LIMIT) {
            BlockPos current = queue.poll();
            if (!out.add(current)) {
                continue;
            }
            for (int dx = -TRUNK_SCAN_RADIUS; dx <= TRUNK_SCAN_RADIUS; dx++) {
                for (int dz = -TRUNK_SCAN_RADIUS; dz <= TRUNK_SCAN_RADIUS; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos next = current.offset(dx, dy, dz);
                        if (out.contains(next)) {
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
     * <p>Крона ищется в кубе {@link #CANOPY_SCAN_RADIUS} вокруг каждого блока
     * ствола и тоже обходится по связности: так в неё попадают только блоки
     * этого дерева, а листва соседнего дерева остаётся. Ограничение
     * {@link #CANOPY_BLOCK_LIMIT} страхует от бесконечного обхода.
     *
     * @param level серверный уровень
     * @param trunk блок ствола, вокруг которого ищется крона
     * @param out   множество, куда складываются блоки кроны
     */
    private static void collectCanopy(ServerLevel level, BlockPos trunk, Set<BlockPos> out) {
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(trunk);
        Set<BlockPos> visited = new HashSet<>();
        int collected = 0;
        while (!queue.isEmpty() && collected < CANOPY_BLOCK_LIMIT) {
            BlockPos current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = current.offset(dx, dy, dz);
                        if (visited.contains(next) || out.contains(next)) {
                            continue;
                        }
                        if (!withinCanopyRadius(next, trunk)) {
                            continue;
                        }
                        BlockState state = level.getBlockState(next);
                        if (state.is(BlockTags.LEAVES)) {
                            out.add(next);
                            collected++;
                            queue.add(next);
                        }
                    }
                }
            }
        }
    }

    /**
     * Проверить, попадает ли блок кроны в допустимый радиус от ствола.
     *
     * <p>Сравнение по кубу, а не по сфере: крона в Minecraft строится
     * кубическими слоями, и сферическая отсечка срезала бы её углы.
     *
     * @param pos   проверяемый блок
     * @param trunk блок ствола
     * @return {@code true}, если блок не дальше {@link #CANOPY_SCAN_RADIUS}
     */
    private static boolean withinCanopyRadius(BlockPos pos, BlockPos trunk) {
        return Math.abs(pos.getX() - trunk.getX()) <= CANOPY_SCAN_RADIUS
                && Math.abs(pos.getY() - trunk.getY()) <= CANOPY_SCAN_RADIUS
                && Math.abs(pos.getZ() - trunk.getZ()) <= CANOPY_SCAN_RADIUS;
    }

    /**
     * Убрать один блок дерева, не задевая якорь и чужие объекты.
     *
     * <p>Колонка якоря неприкосновенна: там стоит {@code camp_radio} со своим
     * {@code BlockEntity}. Твёрдые рукотворные блоки тоже не трогаются —
     * снос дерева не должен разрушать постройки, случайно оказавшиеся в
     * кроне.
     *
     * @param level  серверный уровень
     * @param pos    координата блока
     * @param anchor точка якоря
     */
    private static void clearTreeBlock(ServerLevel level, BlockPos pos, BlockPos anchor) {
        if (pos.getX() == anchor.getX() && pos.getZ() == anchor.getZ()) {
            return;
        }
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
     * Выровнять грунт под блоком якоря до уровня двора.
     *
     * <p>Якорь закреплён на {@code floorY}, поэтому трогать сам блок и всё
     * выше нельзя: {@code camp_radio} стоит на верхней грани грунта, и срез
     * оставил бы его висеть в воздухе. Подсыпается только пустота ниже
     * {@code floorY - 1}: при нормальной установке грунт под якорем уже на
     * этом ярусе, и метод ничего не делает.
     *
     * @param level  серверный уровень
     * @param x      координата
     * @param z      координата
     * @param groundY реальный ярус грунта под якорем
     * @param floorY ярус пола двора (он же ярус блока якоря)
     */
    private static void fillUnderAnchor(ServerLevel level, int x, int z, int groundY, int floorY) {
        for (int y = groundY + 1; y <= floorY - 2; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!isReplaceableSoft(level.getBlockState(pos))) {
                continue;
            }
            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        BlockPos surface = new BlockPos(x, floorY - 1, z);
        if (isReplaceableSoft(level.getBlockState(surface))) {
            level.setBlock(surface, Blocks.GRASS_BLOCK.defaultBlockState(),
                    Block.UPDATE_CLIENTS);
        }
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
     * <p>Подход к воротам — коридор шириной {@link #GATE_ACCESS_WIDTH} блока
     * южнее ворот. Его рельеф не перестраивается: срезается только грунт,
     * физически перекрывающий вход, и над поверхностью освобождается
     * {@link #GATE_ACCESS_HEIGHT} блока под рост игрока.
     *
     * @param level  серверный уровень
     * @param plan   план
     * @param anchor точка якоря (её колонка исключается)
     */
    private static void prepareEdges(ServerLevel level, CampSitePlanner.Plan plan, BlockPos anchor) {
        int floorY = plan.getFloorY();
        int margin = CampTuning.CAMP_CLEAR_MARGIN;
        int half = plan.getRadius();
        int minX = plan.getCenter().getX() - half - margin;
        int maxX = plan.getCenter().getX() + half + margin;
        int minZ = plan.getCenter().getZ() - half - margin;
        int maxZ = plan.getCenter().getZ() + half + margin;

        BlockPos gate = plan.getGatePos();
        int halfWidth = GATE_ACCESS_WIDTH / 2;

        // Переход строится по шагам от периметра наружу: первое кольцо
        // почти повторяет двор, последнее совпадает с рельефом местности.
        // Клетки коридора у ворот пропускаются: у них своя обработка ниже,
        // иначе проход начал бы проседать вместе с пологим склоном.
        for (int step = 1; step <= margin; step++) {
            double t = (double) step / margin;
            int ringMinX = plan.getCenter().getX() - half - step;
            int ringMaxX = plan.getCenter().getX() + half + step;
            int ringMinZ = plan.getCenter().getZ() - half - step;
            int ringMaxZ = plan.getCenter().getZ() + half + step;

            blendRing(level, plan, floorY, t, margin, ringMinX, ringMaxX,
                    ringMinZ, ringMaxZ, gate, halfWidth, anchor);
        }

        // Подход к воротам: проход держится на уровне порога, чтобы игрок
        // входил с площадки, а не спрыгивал в яму. Срез снимает только то,
        // что физически перекрывает вход.
        for (int step = 1; step <= margin; step++) {
            int z = gate.getZ() + step;
            for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                int x = gate.getX() + dx;
                if (x == anchor.getX() && z == anchor.getZ()) {
                    continue;
                }
                fillEdgeColumn(level, x, z, floorY - 1);
                carveApproachColumn(level, x, z, floorY);
            }
        }
    }

    /**
     * Клетка, принадлежащая коридору подхода к воротам.
     *
     * <p>Коридор начинается сразу за плоскостью ворот и уходит на юг на
     * ширину полосы перехода, по ширине равнясь проёму ворот. Такие клетки
     * нельзя вести по общему склону: перед входом нужен ровный участок на
     * уровне порога.
     *
     * @param gate      центральная клетка ворот
     * @param halfWidth половина ширины коридора
     * @param margin    глубина коридора в клетках
     * @param x         проверяемая координата
     * @param z         проверяемая координата
     * @return {@code true}, если клетка входит в коридор
     */
    private static boolean isGateAccessCell(BlockPos gate, int halfWidth, int margin,
                                            int x, int z) {
        if (x < gate.getX() - halfWidth || x > gate.getX() + halfWidth) {
            return false;
        }
        int offset = z - gate.getZ();
        return offset >= 1 && offset <= margin;
    }

    /**
     * Обрабоать одно кольцо перехода вокруг площадки.
     *
     * <p>Кольцо — это рамка на расстоянии {@code step} от периметра. Переход
     * нужен только там, где местность НИЖЕ двора: иначе у лагеря появился бы
     * обрыв. Если рельеф снаружи не ниже двора, кольцо не трогается вообще —
     * именно подсыпка «на всякий случай» и создавала насыпи на ровном месте.
     *
     * <p>Высота считается в уровнях блоков: и двор, и местность задаются
     * ярусом грунта, а не ярусом воздуха над ним. Смешение этих систем давало
     * постоянный лишний блок на внешнем кольце.
     *
     * @param level  серверный уровень
     * @param plan   план застройки
     * @param floorY уровень пола двора
     * @param t      доля удаления от периметра (0..1)
     * @param margin ширина полосы перехода в клетках
     * @param minX   минимальная координата рамки
     * @param maxX   максимальная координата рамки
     * @param minZ   минимальная координата рамки
     * @param maxZ   максимальная координата рамки
     * @param gate   центральная клетка ворот
     * @param halfWidth половина ширины коридора подхода
     * @param anchor точка якоря (её колонка исключается)
     */
    private static void blendRing(ServerLevel level, CampSitePlanner.Plan plan, int floorY,
                                  double t, int margin, int minX, int maxX,
                                  int minZ, int maxZ, BlockPos gate, int halfWidth,
                                  BlockPos anchor) {
        // Уровень грунта двора. Блок поверхности лежит на floorY - 1, и все
        // дальнейшие сравнения ведутся именно в этих координатах.
        int courtyardY = floorY - 1;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // Внутри рамки рельеф уже выровнен в prepareTerrain, повторно
                // его трогать нельзя: затёрся бы восстановленный дёрн.
                boolean onRing = x == minX || x == maxX || z == minZ || z == maxZ;
                if (!onRing) {
                    continue;
                }
                if (x == anchor.getX() && z == anchor.getZ()) {
                    continue;
                }
                if (isGateAccessCell(gate, halfWidth, margin, x, z)) {
                    continue;
                }

                int distX = x - plan.getCenter().getX();
                int distZ = z - plan.getCenter().getZ();
                int naturalY = probeOuterTerrainY(level, plan.getCenter(), distX, distZ,
                        plan.getRadius(), margin);
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
     * Снять естественный выступ, перекрывающий проход.
     *
     * <p>Естественная высота сохраняется: клетка не получает расчётной
     * ступени. Срез нужен только там, где грунт поднялся строго выше уровня
     * пола двора, — такую стенку игрок не переступит в воротах. Грунт на
     * уровне пола и ниже остаётся как есть.
     *
     * <p>После среза над поверхностью освобождается
     * {@link #GATE_ACCESS_HEIGHT} блока под рост игрока.
     *
     * @param level  серверный уровень
     * @param x      координата
     * @param z      координата
     * @param floorY уровень пола двора (верхняя граница допустимого грунта)
     */
    private static void carveApproachColumn(ServerLevel level, int x, int z, int floorY) {
        int groundY = CampSitePlanner.findGroundY(level, x, z);
        if (groundY == CampSitePlanner.FLUID_FOUND || groundY == CampSitePlanner.NO_GROUND) {
            return;
        }
        int surfaceY = groundY;
        if (groundY > floorY) {
            surfaceY = cutDownTo(level, x, z, groundY, floorY);
        }
        // Высота прохода считается от поверхности, но не ниже пола двора:
        // в низине над головой всё равно должны оставаться свободными те же
        // два блока, что и на пороге ворот.
        clearApproachHeadroom(level, x, z, Math.max(surfaceY, floorY - 1));
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
     * расчищаются стволы, листва и мягкая растительность. Твёрдый
     * рукотворный блок остаётся: коридор не должен сносить постройки.
     *
     * @param level    серверный уровень
     * @param x        координата
     * @param z        координата
     * @param surfaceY уровень поверхности, от которого считается высота
     */
    private static void clearApproachHeadroom(ServerLevel level, int x, int z, int surfaceY) {
        int top = surfaceY + GATE_ACCESS_HEIGHT;
        for (int y = surfaceY + 1; y <= top; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                    || isReplaceableSoft(state)) {
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