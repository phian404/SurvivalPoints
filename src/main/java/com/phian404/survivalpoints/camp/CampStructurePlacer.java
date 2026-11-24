package com.phian404.survivalpoints.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Размещение модуля лагеря из {@link StructureTemplate}.
 *
 * <p>Порядок работы:
 * <ol>
 *     <li>загрузить шаблон по {@link ResourceLocation} через менеджер структур уровня;</li>
 *     <li><b>до размещения</b> найти маркеры — блоки {@code structure_block} с
 *     metadata, применив тот же {@link StructurePlaceSettings}, что и для
 *     размещения: иначе координаты маркеров не совпадут с поворотом;</li>
 *     <li>разместить структуру, игнорируя сами блоки {@code structure_block};</li>
 *     <li>в постобработке убрать маркеры из мира и записать POI в лагерь.</li>
 * </ol>
 *
 * <p><b>Про режим маркера.</b> По документации маркеры — это блоки
 * {@code structure_block} в режиме {@code DATA}. В реальных шаблонах
 * ({@code data/survivalpoints/structures/test_farm.nbt}) они лежат в режиме
 * {@code LOAD}: так их сохраняет редактор структур. Поэтому отбор идёт по
 * факту наличия {@code metadata} с нашим префиксом, а не по
 * {@code StructureMode.DATA} — иначе совместимый шаблон дал бы ноль маркеров.
 */
public final class CampStructurePlacer {

    /** Префикс metadata, который считается точкой интереса лагеря. */
    public static final String POI_PREFIX = "survivalpoints:poi/";

    /** Тип POI клетки пашни (найден сканированием, а не маркером). */
    public static final String FARM_PLOT_TYPE = POI_PREFIX + "farm_plot";

    private CampStructurePlacer() {
    }

    /**
     * Итог размещения модуля.
     *
     * <p>Возвращается вместо исключений: команда и генератор должны показать
     * причину отказа в чате, а не падать. Отсутствующий шаблон — обычная
     * ситуация (не собран датапак), а не аварийная.
     */
    public static final class PlacementResult {

        private final boolean success;
        private final String message;
        private final List<CampPoi> pois;
        private final CampModuleSlot.SlotBounds bounds;

        private PlacementResult(boolean success, String message, List<CampPoi> pois) {
            this(success, message, pois, null);
        }

        private PlacementResult(boolean success, String message, List<CampPoi> pois,
                                CampModuleSlot.SlotBounds bounds) {
            this.success = success;
            this.message = message;
            this.pois = pois;
            this.bounds = bounds;
        }

        private static PlacementResult failure(String message) {
            return new PlacementResult(false, message, List.of(), null);
        }

        public boolean isSuccess() {
            return this.success;
        }

        public String getMessage() {
            return this.message;
        }

        /** Найденные и записанные точки интереса. */
        public List<CampPoi> getPois() {
            return this.pois;
        }

        /** Габарит размещённого слота в мировых координатах (только при успехе). */
        public CampModuleSlot.SlotBounds getBounds() {
            return this.bounds;
        }
    }

    /**
     * Разместить модуль в мире и записать его в лагерь.
     *
     * <p>Слот перезаписывается целиком: старое размещение того же {@code slotId}
     * считается заменённым. POI слота заменяются по той же причине — см.
     * {@link CampRecord#replacePois(String, List)}.
     *
     * @param level    серверный уровень
     * @param data     хранилище лагерей (помечается dirty при успехе)
     * @param record   лагерь
     * @param slotId   id слота внутри лагеря
     * @param moduleId шаблон модуля
     * @param levelY   ярус слота (0 — земля)
     * @param origin   точка размещения (угол шаблона)
     * @param rotation поворот шаблона
     * @return результат размещения
     */
    public static PlacementResult placeModule(ServerLevel level, CampSavedData data, CampRecord record,
                                              String slotId, ResourceLocation moduleId, int levelY,
                                              BlockPos origin, Rotation rotation) {
        Optional<StructureTemplate> loaded = level.getStructureManager().get(moduleId);
        if (loaded.isEmpty()) {
            return PlacementResult.failure("Шаблон " + moduleId + " не найден");
        }
        StructureTemplate template = loaded.get();
        if (template.getSize().getX() <= 0 || template.getSize().getY() <= 0
                || template.getSize().getZ() <= 0) {
            return PlacementResult.failure("Шаблон " + moduleId + " пуст");
        }

        // Настройки одинаковы для поиска маркеров и для размещения: координаты
        // маркеров, найденные с другими настройками, после поворота уехали бы.
        StructurePlaceSettings settings = newSettings(rotation);

        // Размещение идёт от УГЛА габарита, а вызывающий думает «поставить в
        // точку». Без поправки шаблон 5×5 от точки (x,z) занимает x..x+4 и
        // уезжает вбок-вперёд на половину своего размера — это и читается как
        // «появилось не перед игроком, а справа чуть впереди». Сдвигаем так,
        // чтобы центр габарита лёг в переданную точку.
        BlockPos placedAt = centerAt(template, settings, origin);

        // Маркеры ищутся от ТОЙ ЖЕ точки, что и размещение. filterBlocks сам
        // применяет поворот и смещение; при другой базе (например, от
        // «центральной» точки вызывающего) координаты маркеров разъехались бы
        // с реальными блоками на половину габарита.
        List<StructureTemplate.StructureBlockInfo> markers =
                template.filterBlocks(placedAt, settings, Blocks.STRUCTURE_BLOCK);

        // knownShape=true — это ключ к тому, чтобы структура не разваливалась.
        // В конце placeInWorld для КАЖДОГО размещённого блока вызывается
        // blockUpdated, который дёргает neighborChanged у всех шести соседей —
        // независимо от флага setBlock. Секция при этом видит недостроенную
        // стену (сосед вдоль оси ещё не поставлен), canSurvive() даёт false,
        // и блок уничтожается вместе с дропом. К концу размещения от стен
        // остаются огрызки — это и выглядит как «три секции ломаются при
        // генерации». Флаг UPDATE_CLIENTS вместо UPDATE_ALL НЕ спасает: он
        // отключает только updateNeighbourShapes внутри setBlock, но не
        // финальный blockUpdated. setKnownShape(true) выключает целиком
        // финальную постобработку placeInWorld (updateShapeAtEdge,
        // updateFromNeighbourShapes и blockUpdated).
        //
        // Цена: формы и зависимые свойства не пересчитываются вообще. Это
        // исправляет следующий проход updateShapes — уже после того, как все
        // блоки стоят на местах.

        StructurePlaceSettings placement = settings.copy()
                .setKnownShape(true)
                .addProcessor(BlockIgnoreProcessor.STRUCTURE_BLOCK);
        // Флаг UPDATE_KNOWN_SHAPE (16) нужен ВМЕСТЕ с setKnownShape(true).
        // setKnownShape отключает только финальную постобработку placeInWorld,
        // а каждый setBlock внутри цикла вставки при отсутствии бита 16 сам
        // выполняет updateNeighbourShapes (см. Level.setBlock: ветка под
        // `(flags & 16) == 0`). Сосед-культура получает updateShape раньше,
        // чем секция увидит актуальный sky light: публикация значений идёт
        // только в LightEngine.runLightUpdates через swapSectionMap, то есть
        // асинхронно, а getLightValue до этого читает visibleSectionData с
        // нулями. CropBlock.canSurvive при getRawBrightness < 8 и
        // !canSeeSky даёт false, BushBlock.updateShape возвращает AIR — посев
        // исчезает прямо внутри placeInWorld.
        boolean placed = template.placeInWorld(level, placedAt, placedAt, placement,
                level.getRandom(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        if (!placed) {
            return PlacementResult.failure("Не удалось разместить " + moduleId);
        }

        BoundingBox box = template.getBoundingBox(settings, placedAt);

        updateShapes(level, box);

        clearMarkers(level, markers);
        CampModuleSlot.SlotBounds bounds = new CampModuleSlot.SlotBounds(
                box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ());

        // Точки двух сортов: явные маркеры шаблона и найденная сканированием
        // пашня. Маркеры идут первыми, чтобы ручная разметка автора модуля
        // имела приоритет над автоматикой.
        List<CampPoi> pois = collectPois(slotId, markers);
        pois.addAll(scanFarmland(level, slotId, bounds, pois));

        record.setModuleSlot(new CampModuleSlot(slotId, moduleId.toString(), levelY,
                origin, rotationDegrees(rotation), bounds));
        record.replacePois(slotId, pois);
        data.setDirty();

        return new PlacementResult(true, "Модуль " + moduleId.getPath() + " размещён",
                List.copyOf(pois), bounds);
    }

    /**
     * Настройки размещения, общие для поиска маркеров и для самой вставки.
     *
     * <p>{@code Mirror.NONE}: модули лагеря не зеркалятся — у них есть
     * направленные элементы (ворота двора, лестницы), и отражение сделало бы
     * вход не с той стороны. Ориентация задаётся только поворотом.
     *
     * @param rotation поворот шаблона
     * @return настройки размещения
     */
    private static StructurePlaceSettings newSettings(Rotation rotation) {
        return new StructurePlaceSettings()
                .setRotation(rotation)
                .setMirror(Mirror.NONE)
                .setIgnoreEntities(true);
    }

    /**
     * Убрать блоки прежнего размещения слота перед повторной вставкой.
     *
     * <p>Нижний слой ({@code minY}) не трогается: в модулях фермы он заменяет
     * собою грунт двора (трава, тропинка, пашня), и снос его в воздух оставил
     * бы в земле яму. Новый модуль всё равно перезапишет этот слой своими
     * блоками.
     *
     * <p>Флаг {@link Block#UPDATE_CLIENTS}: соседей не оповещаем. Массовый
     * {@code neighborChanged} по полуснесённой конструкции — это ровно та
     * ситуация, в которой секции уничтожают себя через {@code canSurvive()}.
     *
     * @param level серверный уровень
     * @param slot  слот, чьи блоки снимаются
     */
    public static void clearSlot(ServerLevel level, CampModuleSlot slot) {
        CampModuleSlot.SlotBounds bounds = slot.getBounds();
        for (int y = bounds.getMinY() + 1; y <= bounds.getMaxY(); y++) {
            for (int x = bounds.getMinX(); x <= bounds.getMaxX(); x++) {
                for (int z = bounds.getMinZ(); z <= bounds.getMaxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    /**
     * Снять створку ворот лагеря и обе её половины.
     *
     * <p>Створка не принадлежит слотам модулей, поэтому {@link #clearSlot} её
     * не касается: без отдельного метода снос лагеря оставлял бы в воздухе
     * висящую дверь. Верхняя половина снимается первой — {@code DoorBlock}
     * при разрушении нижней уничтожает верхнюю сам, но обратный порядок дал бы
     * дроп и лишние обновления соседей.
     *
     * @param level серверный уровень
     * @param gate  нижняя половина створки ({@code null} — ворот нет)
     */
    public static void clearGateLeaf(ServerLevel level, @Nullable BlockPos gate) {
        if (gate == null) {
            return;
        }
        if (level.getBlockState(gate.above()).getBlock() instanceof DoorBlock) {
            level.setBlock(gate.above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        if (level.getBlockState(gate).getBlock() instanceof DoorBlock) {
            level.setBlock(gate, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Угол размещения, при котором центр габарита попадает в {@code target}.
     *
     * <p>{@code placeInWorld} трактует {@code origin} как угол, а не центр:
     * шаблон 5×5 от точки {@code (x,z)} занимает {@code x..x+4}, то есть
     * уезжает вбок и вперёд на половину размера. Эта поправка убирает такой
     * сдвиг, чтобы вызывающий думал точкой, а не углом.
     *
     * <p><b>Смещение берётся как центр габарита, а не половина его ширины.</b>
     * Это принципиально: у повёрнутого шаблона габарит может уйти в
     * отрицательные координаты. Для {@code farm_lvl1} (9×9) при повороте на
     * 180° {@code getBoundingBox} даёт {@code min = -8, max = 0}, поэтому
     * «половина ширины» равна {@code (0 - (-8)) / 2 = +4}, и модуль уезжал бы
     * на 4 блока вбок и вперёд — то есть за периметр. Центр габарита в этом
     * случае {@code (-8 + 0) / 2 = -4}, и поправка остаётся верной при любом
     * повороте.
     *
     * @param template шаблон
     * @param settings настройки размещения (с поворотом)
     * @param target   точка, куда должен попасть центр
     * @return угол размещения
     */
    private static BlockPos centerAt(StructureTemplate template, StructurePlaceSettings settings,
                                     BlockPos target) {
        BoundingBox probe = template.getBoundingBox(settings, BlockPos.ZERO);
        int centerX = (probe.minX() + probe.maxX()) / 2;
        int centerZ = (probe.minZ() + probe.maxZ()) / 2;
        return target.offset(-centerX, 0, -centerZ);
    }

    /**
     * Пересчитать формы и зависимые свойства блоков в прямоугольной области.
     *
     * <p>Нужен для застройки, которую генератор ставит напрямую, минуя
     * {@link StructureTemplate}: периметр лагеря. {@code SectionBlock} держит
     * в состоянии языки соединения, {@code StackedSectionBlock} — вертикальное
     * сшивание, {@code WireSectionBlock} — концы линии и стойку. Все они
     * считаются только в {@code updateShape}/{@code getStateForPlacement},
     * поэтому без этого прохода стены выглядят разомкнутыми: секции не
     * примыкают к опорам, на торцах остаются рейки, а проволока рисуется
     * с обрезанными концами.
     *
     * <p>Вызывать после того, как расставлены и периметр, и модули: стены
     * вышки должны сшиться с панелями лагеря в тех клетках, где они
     * соприкасаются.
     *
     * @param level серверный уровень
     * @param minX  западная граница (включительно)
     * @param minY  нижняя граница (включительно)
     * @param minZ  северная граница (включительно)
     * @param maxX  восточная граница (включительно)
     * @param maxY  верхняя граница (включительно)
     * @param maxZ  южная граница (включительно)
     */
    public static void refreshShapes(ServerLevel level, int minX, int minY, int minZ,
                                     int maxX, int maxY, int maxZ) {
        updateShapes(level, new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ));
    }

    /**
     * Пересчитать формы и зависимые свойства всех блоков области.
     *
     * <p>Нужно потому, что размещение идёт с {@code setKnownShape(true)}:
     * ванильная постобработка {@code placeInWorld} отключена целиком
     * (см. комментарий у {@code placeInWorld}), поэтому ни {@code updateShape},
     * ни {@code blockUpdated} при размещении не вызываются. Без этого прохода
     * секции остались бы с нулевыми свойствами языков и без вертикального
     * сшивания — визуально «недорисованными».
     *
     * <p>Порядок обхода — снизу вверх: {@code updateShape} у
     * {@code StackedSectionBlock} смотрит на блоки сверху и снизу, поэтому
     * нижние ярусы должны быть пересчитаны раньше верхних.
     *
     * @param level серверный уровень
     * @param box   габарит области, где нужно пересчитать формы
     */
    private static void updateShapes(ServerLevel level, BoundingBox box) {
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int x = box.minX(); x <= box.maxX(); x++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    // Культуры пропускаются: единственное их свойство — AGE,
                    // «сшивать» нечего, а canSurvive зависит от освещения.
                    // Во время генерации sky light секции ещё не опубликован
                    // (visibleSectionData обновляется только в
                    // LightEngine.runLightUpdates), поэтому updateShape снёс бы
                    // и жизнеспособный посев. Выживание проверит ванильный
                    // neighborChanged/randomTick, когда свет станет актуальным.
                    if (state.getBlock() instanceof CropBlock) {
                        continue;
                    }
                    // Соседей НЕ оповещаем: каждый блок области всё равно
                    // попадёт в этот же цикл и пересчитает себя сам, а лишний
                    // neighborChanged у секции — это ещё один шанс сработать
                    // canSurvive() и уничтожить блок.
                    BlockState updated = state;
                    for (Direction dir : Direction.values()) {
                        updated = updated.updateShape(dir, level.getBlockState(pos.relative(dir)),
                                level, pos, pos.relative(dir));
                    }
                    if (updated != state) {
                        level.setBlock(pos, updated, Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
    }

    /**
     * Собрать POI из маркеров. Позиции в {@code info.pos()} уже мировые:
     * {@link StructureTemplate#filterBlocks(BlockPos, StructurePlaceSettings, Block)}
     * сам применил поворот и смещение.
     */
    private static List<CampPoi> collectPois(String slotId,
                                             List<StructureTemplate.StructureBlockInfo> markers) {
        List<CampPoi> pois = new ArrayList<>();
        for (StructureTemplate.StructureBlockInfo info : markers) {
            String metadata = metadataOf(info);
            if (metadata == null || !metadata.startsWith(POI_PREFIX)) {
                continue;
            }
            pois.add(new CampPoi(slotId, metadata, info.pos()));
        }
        return pois;
    }

    /**
     * Найти пашню внутри габарита слота и превратить её в POI {@code farm_plot}.
     *
     * <p>Автор модуля не обязан вручную расставлять маркер на каждую грядку:
     * пашня в шаблоне уже размечена блоками {@link Blocks#FARMLAND}. Скан идёт
     * по <b>всему габариту</b>, а не только по нижнему слою: в {@code farm_lvl2}
     * пашня лежит в том же ярусе, но проверка по одному слою сломалась бы на
     * многоярусных модулях. Горизонтальные границы ({@code minX..maxX},
     * {@code minZ..maxZ}) берутся из габарита, поэтому лишнего не сканируется.
     *
     * <p>Сверху не проверяется: грядка — это сам блок пашни, а не то, что на
     * ней посажено. Пустая пашня и пашня с пшеницей равнозначны.
     *
     * @param level  серверный уровень
     * @param slotId id слота
     * @param bounds габарит слота
     * @param known  уже собранные точки: дубли по координате не добавляются
     * @return новые точки пашни
     */
    private static List<CampPoi> scanFarmland(ServerLevel level, String slotId,
                                              CampModuleSlot.SlotBounds bounds,
                                              List<CampPoi> known) {
        List<CampPoi> found = new ArrayList<>();
        for (int y = bounds.getMinY(); y <= bounds.getMaxY(); y++) {
            for (int x = bounds.getMinX(); x <= bounds.getMaxX(); x++) {
                for (int z = bounds.getMinZ(); z <= bounds.getMaxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).is(Blocks.FARMLAND)) {
                        continue;
                    }
                    if (containsAt(known, pos) || containsAt(found, pos)) {
                        continue;
                    }
                    found.add(new CampPoi(slotId, FARM_PLOT_TYPE, pos));
                }
            }
        }
        return found;
    }

    /** Есть ли в наборе точка с такой координатой. */
    private static boolean containsAt(List<CampPoi> pois, BlockPos pos) {
        for (CampPoi poi : pois) {
            if (poi.getPos().equals(pos)) {
                return true;
            }
        }
        return false;
    }

    /** metadata из NBT блока-маркера; {@code null}, если пусто. */
    @Nullable
    private static String metadataOf(StructureTemplate.StructureBlockInfo info) {
        CompoundTag nbt = info.nbt();
        if (nbt == null) {
            return null;
        }
        String metadata = nbt.getString("metadata");
        return metadata.isEmpty() ? null : metadata;
    }

    /**
     * Убрать маркеры из мира.
     *
     * <p>Обычно чистить нечего: {@link BlockIgnoreProcessor#STRUCTURE_BLOCK}
     * не даёт им встать в мир. Проверка оставлена как страховка — если
     * процессор когда-нибудь уберут из настроек, служебные блоки не останутся
     * висеть в лагере.
     */
    private static void clearMarkers(ServerLevel level,
                                     List<StructureTemplate.StructureBlockInfo> markers) {
        for (StructureTemplate.StructureBlockInfo info : markers) {
            BlockPos pos = info.pos();
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.STRUCTURE_BLOCK) || state.is(Blocks.STRUCTURE_VOID)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    /** Градусы поворота в ванильный {@link Rotation}. */
    public static Rotation rotationFromDegrees(int degrees) {
        return switch (degrees) {
            case 90 -> Rotation.CLOCKWISE_90;
            case 180 -> Rotation.CLOCKWISE_180;
            case 270, -90 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** Ванильный {@link Rotation} в градусы (0/90/180/270) — для NBT слота. */
    public static int rotationDegrees(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> 90;
            case CLOCKWISE_180 -> 180;
            case COUNTERCLOCKWISE_90 -> 270;
            default -> 0;
        };
    }

}