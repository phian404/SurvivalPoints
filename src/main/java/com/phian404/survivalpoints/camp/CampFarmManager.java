package com.phian404.survivalpoints.camp;

import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import com.phian404.survivalpoints.entity.ai.crop.CropAdapter;
import com.phian404.survivalpoints.entity.ai.task.NpcTask;
import com.phian404.survivalpoints.entity.ai.task.NpcTaskPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Диспетчер фермерских работ одного лагеря.
 *
 * <p>Периодически сканирует только уже загруженные чанки и формирует временный
 * пул задач: сначала сбор зрелых культур, затем посадка на пустые грядки. Пул и
 * резервации существуют только в runtime и не сохраняются в NBT. Диспетчер не
 * перемещает NPC и не выполняет работу — он только отдаёт подготовленные задачи.
 *
 * <p>Владелец резервации — этот менеджер. {@code NpcTaskController} хранит ссылку
 * на задачу и вызывает {@code releaseCallback}, который снимает резервацию здесь.
 */
public final class CampFarmManager {

    /** Время жизни резервации в тиках. */
    private static final int RESERVATION_TTL = 200;

    /** Задержка повторного предложения недоступной задачи. */
    private static final int UNAVAILABLE_COOLDOWN = 60;
    /** Потолок нарастающего cooldown отказов по одной грядке. */
    private static final int MAX_FAIL_COOLDOWN = 600;
    /** Тип задачи сбора. */
    public static final String TYPE_HARVEST = "farm_harvest";
    /** Тип задачи посадки. */
    public static final String TYPE_PLANT = "farm_plant";

    private final Map<BlockPos, FarmTask> tasks = new LinkedHashMap<>();
    private long lastScanTick = Long.MIN_VALUE;

    /** Последний результат запроса задачи — только диагностические данные. */
    private String lastTakeResult = "NOT_REQUESTED";
    private long lastTakeTick = Long.MIN_VALUE;

    /**
     * Сводка runtime-пула для Debug Wand. Метод только читает состояние.
     */
    public String diagnosticSummary(long gameTime) {
        int harvest = 0;
        int plant = 0;
        int reserved = 0;
        int cooling = 0;
        for (FarmTask task : this.tasks.values()) {
            if (TYPE_HARVEST.equals(task.task.getType())) {
                harvest++;
            } else if (TYPE_PLANT.equals(task.task.getType())) {
                plant++;
            }
            if (task.reservedBy != null) {
                reserved++;
            }
            if (gameTime < task.availableAt) {
                cooling++;
            }
        }
        String scan = this.lastScanTick == Long.MIN_VALUE
                ? "never" : Long.toString(Math.max(0L, gameTime - this.lastScanTick)) + "t ago";
        String request = this.lastTakeTick == Long.MIN_VALUE
                ? "never" : this.lastTakeResult + " (" + Math.max(0L, gameTime - this.lastTakeTick) + "t ago)";
        return "H=" + harvest + " P=" + plant + " reserved=" + reserved
                + " cooldown=" + cooling + " scan=" + scan + " take=" + request;
    }

    /** Обновить пул задач, если прошёл интервал сканирования. */
    public void refresh(ServerLevel level, CampRecord record, long gameTime) {
        // Long.MIN_VALUE — признак «скана ещё не было». Вычитание из него
        // переполняет long и даёт отрицательную разницу, из-за чего первый
        // скан никогда не наступал. Проверяем признак до арифметики.
        if (this.lastScanTick != Long.MIN_VALUE
                && gameTime - this.lastScanTick < CampTuning.FARM_SCAN_INTERVAL) {
            return;
        }
        this.lastScanTick = gameTime;
        scan(level, record, gameTime);
    }

    /** Доступная задача для NPC; {@code null}, если работы нет. */
    @Nullable
    public NpcTask take(ServerLevel level, CampRecord record, UUID npcId, long gameTime) {
        return take(level, record, null, npcId, gameTime);
    }

    /**
     * Доступная и выполнимая задача для конкретного NPC.
     *
     * <p>Проверка инвентаря для {@code PLANT} и вместимости для {@code HARVEST}
     * выполняется здесь, чтобы диспетчер не выдавал задачу, которую исполнитель
     * не сможет выполнить.
     */
    @Nullable
    public NpcTask take(ServerLevel level, CampRecord record, @Nullable BaseSurvivorEntity npc,
                        UUID npcId, long gameTime) {
        return takeForType(level, record, npc, npcId, gameTime, null, null);
    }

    /**
     * Взять задачу заданного типа. Используется рабочей сессией фермера,
     * чтобы не переключаться со сбора на посадку посреди одной серии.
     */
    @Nullable
    public NpcTask takeForType(ServerLevel level, CampRecord record, @Nullable BaseSurvivorEntity npc,
                               UUID npcId, long gameTime, @Nullable String requiredType,
                               @Nullable BlockPos preferredApproach) {
        dropExpired(gameTime);
        this.lastTakeTick = gameTime;
        this.lastTakeResult = this.tasks.isEmpty() ? "POOL_EMPTY" : "NO_ELIGIBLE_TASK";
        // Сначала разбираем зрелые культуры, затем пустые грядки. Внутри
        // каждого типа выбираем ближайшую к NPC задачу; при равной дистанции
        // сохраняется исходный порядок POI.
        List<FarmTask> candidates = new ArrayList<>(this.tasks.values());
        candidates.sort(Comparator
                .comparingInt((FarmTask task) ->
                        TYPE_HARVEST.equals(task.task.getType()) ? 0 : 1)
                .thenComparingInt(task -> npc != null && preferredApproach != null
                        && FarmApproach.validate(level, record, task.task.getTarget(), preferredApproach,
                                npc.getBbWidth(), npc.getBbHeight()) == null ? 0 : 1)
                .thenComparingDouble(task -> npc == null ? 0.0D
                        : npc.distanceToSqr(task.task.getTarget().getX() + 0.5D,
                                task.task.getTarget().getY() + 0.5D,
                                task.task.getTarget().getZ() + 0.5D)));
        for (FarmTask task : candidates) {
            if (requiredType != null && !requiredType.equals(task.task.getType())) {
                continue;
            }
            if (task.reservedBy != null) {
                this.lastTakeResult = "ALL_OR_NEXT_RESERVED";
                continue;
            }
            if (gameTime < task.availableAt) {
                this.lastTakeResult = "COOLDOWN";
                continue;
            }
            if (!isStillValid(level, record, task)) {
                this.lastTakeResult = "TASK_INVALID";
                continue;
            }
            if (npc != null && !isExecutable(level, task, npc)) {
                task.availableAt = gameTime + UNAVAILABLE_COOLDOWN;
                this.lastTakeResult = "NOT_EXECUTABLE";
                continue;
            }
            // Общая с исполнителем проверка подхода ДО резервации: диспетчер не
            // выдаёт задачу, у которой ни одна соседняя клетка не пригодна.
            // Наличие клетки не гарантирует достижимость пути — это остаётся
            // за исполнителем. POI не снимается с учёта; повтор через
            // UNAVAILABLE_COOLDOWN, на случай изменения мира.
            if (npc != null && FarmApproach.findApproach(level, record, task.task.getTarget(),
                    npc.getBbWidth(), npc.getBbHeight()) == null) {
                task.availableAt = gameTime + UNAVAILABLE_COOLDOWN;
                this.lastTakeResult = "NO_APPROACH";
                continue;
            }
            task.reservedBy = npcId;
            this.lastTakeResult = "ASSIGNED:" + task.task.getType() + "@" + task.task.getTarget().toShortString();
            task.reservedAt = gameTime;
            return task.task;
        }
        return null;
    }

    /** Продлить резервацию активной задачи. */
    public void keepAlive(UUID taskId, long gameTime) {
        for (FarmTask task : this.tasks.values()) {
            if (task.task.getId().equals(taskId)) {
                task.reservedAt = gameTime;
                return;
            }
        }
    }

    /**
     * Грядки, на которые сейчас есть работа и которые не заняты другим
     * исполнителем. Только чтение: резервации не выдаются, счётчики отказов и
     * cooldown не меняются. Нужна планировщику рабочей станции, который сам
     * решает порядок обхода, а диспетчер остаётся стражем эксклюзивности.
     */
    public List<BlockPos> availableTargets(ServerLevel level, CampRecord record,
                                           @Nullable BaseSurvivorEntity npc,
                                           long gameTime, String type) {
        List<BlockPos> targets = new ArrayList<>();
        for (FarmTask task : this.tasks.values()) {
            if (!type.equals(task.task.getType())) {
                continue;
            }
            if (task.reservedBy != null || gameTime < task.availableAt) {
                continue;
            }
            if (!isStillValid(level, record, task)) {
                continue;
            }
            if (npc != null && !isExecutable(level, task, npc)) {
                continue;
            }
            targets.add(task.task.getTarget());
        }
        return targets;
    }

    /**
     * Зарезервировать конкретную грядку, выбранную исполнителем.
     *
     * <p>Проверки те же, что и в {@link #takeForType}, но без выбора «первой
     * подходящей»: порядок обхода задаёт рабочая станция, диспетчер лишь
     * подтверждает, что грядка ещё свободна и выполнима.
     */
    @Nullable
    public NpcTask takeAt(ServerLevel level, CampRecord record, @Nullable BaseSurvivorEntity npc,
                          UUID npcId, long gameTime, String type, BlockPos plot) {
        dropExpired(gameTime);
        this.lastTakeTick = gameTime;
        FarmTask task = this.tasks.get(plot.immutable());
        if (task == null) {
            this.lastTakeResult = "NO_TASK_AT_PLOT";
            return null;
        }
        if (!type.equals(task.task.getType())) {
            this.lastTakeResult = "TYPE_MISMATCH";
            return null;
        }
        if (task.reservedBy != null && !task.reservedBy.equals(npcId)) {
            this.lastTakeResult = "RESERVED_ELSEWHERE";
            return null;
        }
        if (gameTime < task.availableAt) {
            this.lastTakeResult = "COOLDOWN";
            return null;
        }
        if (!isStillValid(level, record, task)) {
            this.tasks.remove(plot.immutable());
            this.lastTakeResult = "TASK_INVALID";
            return null;
        }
        if (npc != null && !isExecutable(level, task, npc)) {
            task.availableAt = gameTime + UNAVAILABLE_COOLDOWN;
            this.lastTakeResult = "NOT_EXECUTABLE";
            return null;
        }
        task.reservedBy = npcId;
        task.reservedAt = gameTime;
        this.lastTakeResult = "ASSIGNED_BY_PLAN:" + type + "@" + task.task.getTarget().toShortString();
        return task.task;
    }

    /** Причина, по которой {@link #takeForType} не выдал задачу. */
    public enum TakeBlock {
        /** Действительных задач нужного типа нет: серия закончена. */
        NONE,
        /** Работа есть, но ждёт истечения cooldown или освобождения резервации. */
        TEMPORARY,
        /** Работа есть, но выполнить её сейчас нельзя: инвентарь, подход. */
        BLOCKED
    }

    /**
     * Read-only классификация отказа {@link #takeForType}.
     *
     * <p>Ничего не пишет: ни cooldown, ни резерваций, ни счётчиков. Проверка
     * выполнимости идёт до cooldown намеренно: заполненный инвентарь должен
     * давать {@link TakeBlock#BLOCKED}, а не «временный» cooldown, который
     * {@code takeForType} выставил бы на этой же задаче. Отсутствие клетки
     * подхода временно, пока действует cooldown: мир может измениться, и
     * задача снова станет выполнимой; после истечения cooldown повторная
     * неудача считается блокировкой.
     */
    public TakeBlock classifyForType(ServerLevel level, CampRecord record,
                                     @Nullable BaseSurvivorEntity npc, long gameTime,
                                     @Nullable String requiredType) {
        boolean temporary = false;
        boolean blocked = false;
        for (FarmTask task : this.tasks.values()) {
            if (requiredType != null && !requiredType.equals(task.task.getType())) {
                continue;
            }
            if (!isStillValid(level, record, task)) {
                continue;
            }
            if (task.reservedBy != null) {
                temporary = true;
                continue;
            }
            if (npc != null && !isExecutable(level, task, npc)) {
                blocked = true;
                continue;
            }
            if (gameTime < task.availableAt) {
                temporary = true;
                continue;
            }
            if (npc != null && FarmApproach.findApproach(level, record, task.task.getTarget(),
                    npc.getBbWidth(), npc.getBbHeight()) == null) {
                blocked = true;
                continue;
            }
            // Задача доступна прямо сейчас: takeForType её бы взял, поэтому
            // ожидание бессмысленно — рабочий Goal повторит взятие сразу.
            temporary = true;
        }
        if (temporary) {
            return TakeBlock.TEMPORARY;
        }
        return blocked ? TakeBlock.BLOCKED : TakeBlock.NONE;
    }

    /**
     * Есть ли для NPC работа прямо сейчас, без её резервации.
     *
     * <p>Read-only аналог фильтра {@link #take}: те же проверки
     * (резервация, cooldown, валидность, выполнимость, подход), но без
     * записи {@code availableAt}, резервации и счётчиков. Нужен прогулке,
     * чтобы не уводить фермера с фермы, когда следующая грядка уже готова.
     */
    public boolean hasAvailableWork(ServerLevel level, CampRecord record,
                                    BaseSurvivorEntity npc, long gameTime) {
        return hasWork(level, record, npc, gameTime, false);
    }

    /**
     * Есть ли на ферме действительные задачи, включая временно отложенные
     * cooldown. Прогулка использует это, чтобы не начинать выход из фермы
     * между повторными попытками рабочего Goal.
     */
    public boolean hasPendingWork(ServerLevel level, CampRecord record,
                                  BaseSurvivorEntity npc, long gameTime) {
        return hasWork(level, record, npc, gameTime, true);
    }

    private boolean hasWork(ServerLevel level, CampRecord record, BaseSurvivorEntity npc,
                            long gameTime, boolean includeCooldown) {
        for (FarmTask task : this.tasks.values()) {
            if (task.reservedBy != null || (!includeCooldown && gameTime < task.availableAt)) {
                continue;
            }
            if (!isStillValid(level, record, task)) {
                continue;
            }
            if (!isExecutable(level, task, npc)) {
                continue;
            }
            if (FarmApproach.findApproach(level, record, task.task.getTarget(),
                    npc.getBbWidth(), npc.getBbHeight()) == null) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Отказ исполнителя от задачи: снимает резервацию и откладывает повторное
     * предложение на нарастающий cooldown (UNAVAILABLE_COOLDOWN * failures,
     * до MAX_FAIL_COOLDOWN), чтобы фермер не зацикливался на одной
     * недоступной грядке. Идемпотентно.
     */
    public void markFailed(UUID taskId, long gameTime) {
        for (FarmTask task : this.tasks.values()) {
            if (task.task.getId().equals(taskId)) {
                task.failures++;
                task.reservedBy = null;
                task.reservedAt = Long.MIN_VALUE;
                task.availableAt = gameTime
                        + Math.min(UNAVAILABLE_COOLDOWN * task.failures, MAX_FAIL_COOLDOWN);
                return;
            }
        }
    }

    /** Успешное выполнение: счётчик отказов грядки обнуляется. */
    public void markSucceeded(UUID taskId) {
        for (FarmTask task : this.tasks.values()) {
            if (task.task.getId().equals(taskId)) {
                task.failures = 0;
                return;
            }
        }
    }

    /** Немедленно пересчитать одну грядку после известного изменения мира. */
    public void refreshPlot(ServerLevel level, CampRecord record, BlockPos plot, long gameTime) {
        BlockPos key = plot.immutable();
        FarmTask previous = this.tasks.get(key);
        FarmTask current = createTaskForPlot(level, record, key);
        if (current == null) {
            this.tasks.remove(key);
            return;
        }
        if (previous != null && previous.task.getType().equals(current.task.getType())) {
            if (previous.reservedBy != null && gameTime - previous.reservedAt < RESERVATION_TTL) {
                this.tasks.put(key, previous);
                return;
            }
            if (gameTime < previous.availableAt) {
                current.failures = previous.failures;
                current.availableAt = previous.availableAt;
            }
        }
        this.tasks.put(key, current);
    }

    /** Удалить задачу грядки до завершения внешнего разрушения блока. */
    public void invalidatePlotAt(BlockPos changedPos) {
        this.tasks.remove(changedPos);
        this.tasks.remove(changedPos.below());
    }

    /** Проверка выполнимости задачи конкретным NPC. */
    private boolean isExecutable(ServerLevel level, FarmTask task, BaseSurvivorEntity npc) {
        BlockPos plot = task.task.getTarget();
        BlockState state = level.getBlockState(plot.above());
        if (TYPE_HARVEST.equals(task.task.getType())) {
            CropAdapter adapter = CropAdapter.find(state);
            return adapter != null && adapter.isMature(state)
                    && fitsInventory(npc, adapter.getDrops(level, plot.above(), state));
        }
        if (!state.isAir() || !level.getBlockState(plot).is(Blocks.FARMLAND)) {
            return false;
        }
        // Предмет в основной руке имеет приоритет: не подменяем экипировку
        // семенами из внутреннего инвентаря, если рука занята.
        ItemStack held = npc.getMainHandItem();
        if (!held.isEmpty()) {
            return CropAdapter.find(held) != null;
        }
        for (int slot = 0; slot < npc.getInventory().getSlots(); slot++) {
            ItemStack stack = npc.getInventory().getStackInSlot(slot);
            if (!stack.isEmpty() && CropAdapter.find(stack) != null) {
                return true;
            }
        }
        return false;
    }

    /** Вмещаются ли все стекы в инвентарь NPC. */
    private static boolean fitsInventory(BaseSurvivorEntity npc, List<ItemStack> drops) {
        int free = 0;
        for (int slot = 0; slot < npc.getInventory().getSlots(); slot++) {
            ItemStack stack = npc.getInventory().getStackInSlot(slot);
            free += stack.isEmpty() ? 64 : Math.max(0, stack.getMaxStackSize() - stack.getCount());
        }
        int needed = 0;
        for (ItemStack drop : drops) {
            needed += drop.getCount();
        }
        return needed <= free;
    }

    /** Освободить задачу по идентификатору. Идемпотентно. */
    public void release(UUID taskId) {
        for (Iterator<FarmTask> it = this.tasks.values().iterator(); it.hasNext(); ) {
            FarmTask task = it.next();
            if (task.task.getId().equals(taskId)) {
                task.reservedBy = null;
                task.reservedAt = Long.MIN_VALUE;
                return;
            }
        }
    }

    /** Полный сброс runtime-пула (например, при выгрузке лагеря). */
    public void clear() {
        this.tasks.clear();
        this.lastScanTick = Long.MIN_VALUE;
    }

    /** Сканирование POI лагеря без принудительной загрузки чанков. */
    private void scan(ServerLevel level, CampRecord record, long gameTime) {
        Map<BlockPos, FarmTask> fresh = new LinkedHashMap<>();
        for (CampPoi poi : record.getPois()) {
            if (!CampStructurePlacer.FARM_PLOT_TYPE.equals(poi.getType())) {
                continue;
            }
            BlockPos plot = poi.getPos();
            boolean isLoaded = level.isLoaded(plot);
            ChunkPos cp = new ChunkPos(plot);
            LevelChunk chunk = isLoaded ? level.getChunkSource().getChunkNow(cp.x, cp.z) : null;
            boolean chunkReady = chunk != null && chunk.getStatus().isOrAfter(ChunkStatus.FULL);
            if (!isLoaded || !chunkReady) {
                continue;
            }
            // Грядки вне рабочего круга лагеря (радиус минус BORDER_INSET) не
            // получают задач: фермер к ним всё равно не подойдёт — клетка
            // подхода отбраковывается border-критерием FarmApproach. POI
            // остаётся зарегистрированным: при расширении лагеря грядка
            // оживёт на следующем скане.
            if (!FarmApproach.insideBorder(record, plot)) {
                continue;
            }
            BlockState cropState = level.getBlockState(plot.above());
            CropAdapter adapter = CropAdapter.find(cropState);
            if (adapter != null && adapter.isMature(cropState)) {
                add(fresh, plot, poi.getSlotId(), TYPE_HARVEST, record.getId());
            } else if (cropState.isAir() && level.getBlockState(plot).is(Blocks.FARMLAND)) {
                add(fresh, plot, poi.getSlotId(), TYPE_PLANT, record.getId());
            }
        }

        // Переносятся только ещё не истёкшие резервации по живым задачам.
        // Счётчик отказов живёт, пока не истёк его backoff: если время вышло,
        // считаем, что ситуация в мире могла измениться, и начинаем отсчёт
        // заново.
        for (Map.Entry<BlockPos, FarmTask> entry : fresh.entrySet()) {
            FarmTask old = this.tasks.get(entry.getKey());
            if (old != null) {
                if (old.reservedBy != null
                        && gameTime - old.reservedAt < RESERVATION_TTL
                        && old.task.getType().equals(entry.getValue().task.getType())) {
                    entry.setValue(old);
                } else if (gameTime < old.availableAt) {
                    entry.getValue().failures = old.failures;
                    entry.getValue().availableAt = old.availableAt;
                }
            }
        }
        this.tasks.clear();
        this.tasks.putAll(fresh);
    }

    private static void add(Map<BlockPos, FarmTask> target, BlockPos plot, String slotId,
                            String type, UUID campId) {
        NpcTask task = new NpcTask(NpcTaskPriority.WORK, type, plot, campId);
        target.put(plot, new FarmTask(task, slotId));
    }

    /** Создать актуальную задачу для одной зарегистрированной и загруженной грядки. */
    @Nullable
    private static FarmTask createTaskForPlot(ServerLevel level, CampRecord record, BlockPos plot) {
        if (!FarmApproach.insideBorder(record, plot) || !isChunkReady(level, plot)) {
            return null;
        }
        CampPoi farmPoi = null;
        for (CampPoi poi : record.getPois()) {
            if (CampStructurePlacer.FARM_PLOT_TYPE.equals(poi.getType()) && plot.equals(poi.getPos())) {
                farmPoi = poi;
                break;
            }
        }
        if (farmPoi == null) {
            return null;
        }
        BlockState cropState = level.getBlockState(plot.above());
        CropAdapter adapter = CropAdapter.find(cropState);
        if (adapter != null && adapter.isMature(cropState)) {
            return new FarmTask(new NpcTask(NpcTaskPriority.WORK, TYPE_HARVEST, plot, record.getId()), farmPoi.getSlotId());
        }
        if (cropState.isAir() && level.getBlockState(plot).is(Blocks.FARMLAND)) {
            return new FarmTask(new NpcTask(NpcTaskPriority.WORK, TYPE_PLANT, plot, record.getId()), farmPoi.getSlotId());
        }
        return null;
    }

    /** Задача всё ещё соответствует состоянию мира. */
    private boolean isStillValid(ServerLevel level, CampRecord record, FarmTask task) {
        if (!isChunkReady(level, task.task.getTarget())) {
            return false;
        }
        BlockPos plot = task.task.getTarget();
        boolean found = false;
        for (CampPoi poi : record.getPois()) {
            if (CampStructurePlacer.FARM_PLOT_TYPE.equals(poi.getType())
                    && plot.equals(poi.getPos()) && task.slotId.equals(poi.getSlotId())) {
                found = true;
                break;
            }
        }
        if (!found) {
            return false;
        }
        BlockState state = level.getBlockState(plot.above());
        if (TYPE_HARVEST.equals(task.task.getType())) {
            CropAdapter adapter = CropAdapter.find(state);
            return adapter != null && adapter.isMature(state);
        }
        return state.isAir() && level.getBlockState(plot).is(Blocks.FARMLAND);
    }

    private void dropExpired(long gameTime) {
        for (Iterator<FarmTask> it = this.tasks.values().iterator(); it.hasNext(); ) {
            FarmTask task = it.next();
            if (task.reservedBy != null && gameTime - task.reservedAt >= RESERVATION_TTL) {
                task.reservedBy = null;
                task.reservedAt = Long.MIN_VALUE;
            }
        }
    }

    /** Чанк готов к безопасному чтению; принудительно не загружается. */
    private static boolean isChunkReady(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        ChunkPos chunkPos = new ChunkPos(pos);
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
        return chunk != null && chunk.getStatus().isOrAfter(ChunkStatus.FULL);
    }

    /** Временная задача фермы с состоянием резервации. */
    private static final class FarmTask {
        private final NpcTask task;
        private final String slotId;
        @Nullable
        private UUID reservedBy;
        private long reservedAt = Long.MIN_VALUE;
        private long availableAt = Long.MIN_VALUE;
        /** Сколько раз подряд задача по этой грядке провалилась у исполнителя. */
        private int failures;

        private FarmTask(NpcTask task, String slotId) {
            this.task = task;
            this.slotId = slotId;
        }
    }
}
