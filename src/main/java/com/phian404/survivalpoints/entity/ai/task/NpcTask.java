package com.phian404.survivalpoints.entity.ai.task;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Задача верхнего уровня для NPC.
 *
 * <p>Хранит только данные, необходимые для выбора, выполнения и освобождения.
 * Временные задачи не сохраняются в NBT. Резервацией владеет тот компонент,
 * который создал задачу (например, {@code CampFarmManager}); контроллер NPC
 * только хранит ссылку и вызывает освобождение через него.
 */
public final class NpcTask {

    private final UUID id;
    private final NpcTaskPriority priority;
    private final String type;
    @Nullable
    private final BlockPos target;
    @Nullable
    private final UUID sourceId;

    public NpcTask(NpcTaskPriority priority, String type, @Nullable BlockPos target,
                   @Nullable UUID sourceId) {
        this(UUID.randomUUID(), priority, type, target, sourceId);
    }

    private NpcTask(UUID id, NpcTaskPriority priority, String type, @Nullable BlockPos target,
                    @Nullable UUID sourceId) {
        this.id = id;
        this.priority = priority;
        this.type = type;
        this.target = target;
        this.sourceId = sourceId;
    }

    public UUID getId() {
        return this.id;
    }

    public NpcTaskPriority getPriority() {
        return this.priority;
    }

    public String getType() {
        return this.type;
    }

    @Nullable
    public BlockPos getTarget() {
        return this.target;
    }

    /** Идентификатор источника задачи (например, id лагеря). */
    @Nullable
    public UUID getSourceId() {
        return this.sourceId;
    }
}
