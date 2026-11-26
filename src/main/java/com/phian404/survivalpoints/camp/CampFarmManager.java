package com.phian404.survivalpoints.camp;

import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import com.phian404.survivalpoints.entity.ai.crop.CropAdapter;
import com.phian404.survivalpoints.entity.ai.task.NpcTask;
import com.phian404.survivalpoints.entity.ai.task.NpcTaskPriority;
import com.phian404.survivalpoints.init.GuardianTrace;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;
import java.util.ArrayList;
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
    /** Цель и причина последней неотданной задачи — для диагностики NO_TASK. */
    @Nullable
    private BlockPos blockedTarget;
    @Nullable
    private String blockedReason;

    /** Тип задачи сбора. */
    public static final String TYPE_HARVEST = "farm_harvest";
    /** Тип задачи посадки. */
    public static final String TYPE_PLANT = "farm_plant";

    private final Map<BlockPos, FarmTask> tasks = new LinkedHashMap<>();
    private long lastScanTick = Long.MIN_VALUE;
    private long lastTakeTraceTick = Long.MIN_VALUE;
    private String lastTakeTraceKey = "";

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
        trace("REFRESH", "camp=" + record.getId() + " interval=" + CampTuning.FARM_SCAN_INTERVAL
                + " harvest=" + taskCount(TYPE_HARVEST) + " plant=" + taskCount(TYPE_PLANT)
                + " total=" + this.tasks.size(), level);
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
        dropExpired(gameTime);
        int reserved = 0;
        int cooldown = 0;
        int stale = 0;
        int notExecutable = 0;
        @Nullable BlockPos blockedTarget = null;
        @Nullable String blockedReason = null;
        // Сначала разбираем зрелые культуры, затем пустые грядки. Внутри
        // каждого типа выбираем ближайшую к NPC задачу; при равной дистанции
        // сохраняется исходный порядок POI.
        List<FarmTask> candidates = new ArrayList<>(this.tasks.values());
        candidates.sort(Comparator
                .comparingInt((FarmTask task) ->
                        TYPE_HARVEST.equals(task.task.getType()) ? 0 : 1)
                .thenComparingDouble(task -> npc == null ? 0.0D
                        : npc.distanceToSqr(task.task.getTarget().getX() + 0.5D,
                                task.task.getTarget().getY() + 0.5D,
                                task.task.getTarget().getZ() + 0.5D)));
        for (FarmTask task : candidates) {
            if (task.reservedBy != null) {
                reserved++;
                if (blockedTarget == null) {
                    blockedTarget = task.task.getTarget();
                    blockedReason = "reserved";
                }
                continue;
            }
            if (gameTime < task.availableAt) {
                cooldown++;
                if (blockedTarget == null) {
                    blockedTarget = task.task.getTarget();
                    blockedReason = "cooldown=" + (task.availableAt - gameTime)
                            + " failures=" + task.failures;
                }
                continue;
            }
            if (!isStillValid(level, record, task)) {
                stale++;
                if (blockedTarget == null) {
                    blockedTarget = task.task.getTarget();
                    blockedReason = "stale";
                }
                continue;
            }
            if (npc != null && !isExecutable(level, task, npc)) {
                notExecutable++;
                task.availableAt = gameTime + UNAVAILABLE_COOLDOWN;
                if (blockedTarget == null) {
                    blockedTarget = task.task.getTarget();
                    blockedReason = "not_executable";
                }
                continue;
            }
            // Общая с исполнителем проверка подхода ДО резервации: диспетчер не
            // выдаёт задачу, у которой ни одна соседняя клетка не пригодна.
            // Наличие клетки не гарантирует достижимость пути — это остаётся
            // за исполнителем. POI не снимается с учёта; повтор через
            // UNAVAILABLE_COOLDOWN, на случай изменения мира.
            if (npc != null && FarmApproach.findApproach(level, record, task.task.getTarget(),
                    npc.getBbWidth(), npc.getBbHeight()) == null) {
                notExecutable++;
                task.availableAt = gameTime + UNAVAILABLE_COOLDOWN;
                if (blockedTarget == null) {
                    blockedTarget = task.task.getTarget();
                    blockedReason = "no_approach";
                }
                continue;
            }
            task.reservedBy = npcId;
            task.reservedAt = gameTime;
            traceTake(level, "camp=" + record.getId() + " npc=" + npcId
                    + " result=" + task.task.getType() + " target=" + task.task.getTarget());
            return task.task;
        }
        traceTake(level, "camp=" + record.getId() + " npc=" + npcId + " result=NO_TASK"
                + " total=" + this.tasks.size() + " reserved=" + reserved
                + " cooldown=" + cooldown + " stale=" + stale
                + " notExecutable=" + notExecutable
                + (blockedTarget != null
                        ? " blocked=" + blockedTarget + " reason=" + blockedReason
                        : ""));
        this.blockedTarget = blockedTarget;
        this.blockedReason = blockedReason;
        return null;
    }

    /**
     * Причина последнего отказа take(): какая грядка и почему не выдана.
     * Пустая строка, если задач не было вовсе.
     */
    public String takeSummary() {
        if (this.blockedTarget == null) {
            return "";
        }
        return " blocked=" + this.blockedTarget + " reason=" + this.blockedReason;
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

    /** Проверка выполнимости задачи конкретным NPC. */
    private boolean isExecutable(ServerLevel level, FarmTask task, BaseSurvivorEntity npc) {
        BlockPos plot = task.task.getTarget();
        BlockState state = level.getBlockState(plot.above());
        if (TYPE_HARVEST.equals(task.task.getType())) {
            CropAdapter adapter = CropAdapter.find(state);
            return adapter != null && adapter.isMature(state)
                    && fitsInventory(npc, adapter.getDrops(level, plot.above(), state));
        }
        if (!state.isAir()) {
            return false;
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

    /** ВРЕМЕННО: диагностика take() не чаще раза в 100 тиков для одного NPC. */
    private void traceTake(ServerLevel level, String detail) {
        long now = level.getGameTime();
        if (detail.equals(this.lastTakeTraceKey) && now - this.lastTakeTraceTick < 100) {
            return;
        }
        this.lastTakeTraceKey = detail;
        this.lastTakeTraceTick = now;
        trace("TAKE", detail, level);
    }

    /** ВРЕМЕННО: вывод диагностики (лог-файл и чат) через GuardianTrace. */
    private static void trace(String stage, String detail, ServerLevel level) {
        GuardianTrace.always(level, "CampFarmManager: " + stage + " " + detail);
    }

    /** ВРЕМЕННО: имя блока для диагностики. */
    private static String blockName(BlockState state) {
        return state.getBlock().builtInRegistryHolder().key().location().toString();
    }

    /** ВРЕМЕННО: количество задач по типам для диагностики. */
    public int taskCount(String type) {
        int count = 0;
        for (FarmTask task : this.tasks.values()) {
            if (type.equals(task.task.getType())) {
                count++;
            }
        }
        return count;
    }

    /** Полный сброс runtime-пула (например, при выгрузке лагеря). */
    public void clear() {
        this.tasks.clear();
        this.lastScanTick = Long.MIN_VALUE;
    }

    /** Сканирование POI лагеря без принудительной загрузки чанков. */
    private void scan(ServerLevel level, CampRecord record, long gameTime) {
        Map<BlockPos, FarmTask> fresh = new LinkedHashMap<>();
        int total = 0;
        int loaded = 0;
        int recognized = 0;
        int mature = 0;
        int empty = 0;
        int harvestCreated = 0;
        int plantCreated = 0;
        int skippedType = 0;
        int skippedChunk = 0;
        int skippedOther = 0;

        for (CampPoi poi : record.getPois()) {
            if (!CampStructurePlacer.FARM_PLOT_TYPE.equals(poi.getType())) {
                skippedType++;
                continue;
            }
            total++;
            BlockPos plot = poi.getPos();
            boolean isLoaded = level.isLoaded(plot);
            ChunkPos cp = new ChunkPos(plot);
            LevelChunk chunk = isLoaded ? level.getChunkSource().getChunkNow(cp.x, cp.z) : null;
            boolean chunkReady = chunk != null && chunk.getStatus().isOrAfter(ChunkStatus.FULL);
            if (!isLoaded || !chunkReady) {
                skippedChunk++;
                trace("POI", "pos=" + plot + " result=SKIPPED reason=CHUNK_NOT_LOADED"
                        + " isLoaded=" + isLoaded + " getChunkNow=" + (chunk != null), level);
                continue;
            }
            loaded++;
            // Грядки вне рабочего круга лагеря (радиус минус BORDER_INSET) не
            // получают задач: фермер к ним всё равно не подойдёт — клетка
            // подхода отбраковывается border-критерием FarmApproach. POI
            // остаётся зарегистрированным: при расширении лагеря грядка
            // оживёт на следующем скане.
            if (!FarmApproach.insideBorder(record, plot)) {
                skippedOther++;
                trace("POI", "pos=" + plot + " result=SKIPPED reason=OUTSIDE_WORK_BORDER", level);
                continue;
            }
            BlockState soil = level.getBlockState(plot);
            BlockState cropState = level.getBlockState(plot.above());
            CropAdapter adapter = CropAdapter.find(cropState);
            String base = "POI=" + plot + " block=" + blockName(soil)
                    + " above=" + blockName(cropState);
            if (adapter != null) {
                recognized++;
            }
            if (adapter != null && adapter.isMature(cropState)) {
                mature++;
                add(fresh, plot, poi.getSlotId(), TYPE_HARVEST, record.getId());
                harvestCreated++;
                trace("POI", base + " adapter=" + adapter.getClass().getSimpleName()
                        + " result=HARVEST_CREATED", level);
            } else if (cropState.isAir()) {
                empty++;
                add(fresh, plot, poi.getSlotId(), TYPE_PLANT, record.getId());
                plantCreated++;
                trace("POI", base + " result=PLANT_CREATED", level);
            } else {
                skippedOther++;
                trace("POI", base + " adapter=" + (adapter == null ? "NONE"
                        : adapter.getClass().getSimpleName()) + " result=SKIPPED reason=NO_WORK", level);
            }
        }

        trace("SCAN_SUMMARY", "camp=" + record.getId() + " totalPOI=" + total
                + " loaded=" + loaded + " recognized=" + recognized + " mature=" + mature
                + " empty=" + empty + " harvest=" + harvestCreated + " plant=" + plantCreated
                + " skippedType=" + skippedType + " skippedChunk=" + skippedChunk
                + " skippedOther=" + skippedOther, level);

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
        return state.isAir();
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
