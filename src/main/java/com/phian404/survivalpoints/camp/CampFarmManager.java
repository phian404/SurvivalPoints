package com.phian404.survivalpoints.camp;

import com.phian404.survivalpoints.entity.ai.crop.CropAdapter;
import com.phian404.survivalpoints.entity.ai.task.NpcTask;
import com.phian404.survivalpoints.entity.ai.task.NpcTaskPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;
import java.util.ArrayList;
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

    /** Тип задачи сбора. */
    public static final String TYPE_HARVEST = "farm_harvest";
    /** Тип задачи посадки. */
    public static final String TYPE_PLANT = "farm_plant";

    private final Map<BlockPos, FarmTask> tasks = new LinkedHashMap<>();
    private long lastScanTick = Long.MIN_VALUE;

    /** Обновить пул задач, если прошёл интервал сканирования. */
    public void refresh(ServerLevel level, CampRecord record, long gameTime) {
        if (gameTime - this.lastScanTick < CampTuning.FARM_SCAN_INTERVAL) {
            return;
        }
        this.lastScanTick = gameTime;
        scan(level, record, gameTime);
    }

    /** Доступная задача для NPC; {@code null}, если работы нет. */
    @Nullable
    public NpcTask take(ServerLevel level, CampRecord record, UUID npcId, long gameTime) {
        dropExpired(gameTime);
        for (FarmTask task : this.tasks.values()) {
            if (task.reservedBy != null || !isStillValid(level, record, task)) {
                continue;
            }
            task.reservedBy = npcId;
            task.reservedAt = gameTime;
            return task.task;
        }
        return null;
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
            if (!isChunkReady(level, plot)) {
                continue;
            }
            BlockState cropState = level.getBlockState(plot.above());
            CropAdapter adapter = CropAdapter.find(cropState);
            if (adapter != null && adapter.isMature(cropState)) {
                add(fresh, plot, poi.getSlotId(), TYPE_HARVEST, record.getId());
            } else if (cropState.isAir()) {
                add(fresh, plot, poi.getSlotId(), TYPE_PLANT, record.getId());
            }
        }

        // Переносятся только ещё не истёкшие резервации по живым задачам.
        for (Map.Entry<BlockPos, FarmTask> entry : fresh.entrySet()) {
            FarmTask old = this.tasks.get(entry.getKey());
            if (old != null && old.reservedBy != null
                    && gameTime - old.reservedAt < RESERVATION_TTL
                    && old.task.getType().equals(entry.getValue().task.getType())) {
                entry.setValue(old);
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

        private FarmTask(NpcTask task, String slotId) {
            this.task = task;
            this.slotId = slotId;
        }
    }
}
