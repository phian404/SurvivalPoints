package com.phian404.survivalpoints.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Слот модуля лагеря: место в плане, куда встаёт структура.
 *
 * <p>Слот — это не блок в мире, а запись о размещении: какой модуль, в каком
 * ярусе, от какой точки и с каким поворотом. Границы ({@link #getBounds()})
 * нужны для ответа на вопрос «слот уже занят?», поэтому хранятся вместе со
 * слотом, а не пересчитываются из шаблона: шаблон мог измениться, а слот —
 * место, которое уже занято в мире.
 *
 * <p>{@link #isModified()} помечает слот, содержимое которого игрок изменил
 * после размещения (сломал стену, снял дверь). Пока флага нет, слот можно
 * перегенерировать молча; с флагом — нет.
 */
public class CampModuleSlot {

    /** Устойчивый идентификатор слота внутри лагеря (например, {@code farm_nw}). */
    private final String slotId;

    /** Идентификатор модуля: что ставится в слот ({@code survivalpoints:test_farm}). */
    private final String moduleId;

    /** Ярус лагеря: 0 — земля, 1 — следующая высота (вышка, второй этаж). */
    private final int level;

    /** Угол слота (точка, от которой размещается шаблон). */
    private final BlockPos origin;

    /** Поворот шаблона в градусах: 0, 90, 180 или 270. */
    private final int rotation;

    /** Габарит занятой области в мировых координатах. */
    private final SlotBounds bounds;

    /** Изменён ли слот после размещения (игрок или другая система). */
    private boolean modified;

    public CampModuleSlot(String slotId, String moduleId, int level, BlockPos origin,
                          int rotation, SlotBounds bounds) {
        this(slotId, moduleId, level, origin, rotation, bounds, false);
    }

    public CampModuleSlot(String slotId, String moduleId, int level, BlockPos origin,
                          int rotation, SlotBounds bounds, boolean modified) {
        this.slotId = slotId;
        this.moduleId = moduleId;
        this.level = level;
        this.origin = origin;
        this.rotation = rotation;
        this.bounds = bounds;
        this.modified = modified;
    }

    public String getSlotId() {
        return this.slotId;
    }

    public String getModuleId() {
        return this.moduleId;
    }

    public int getLevel() {
        return this.level;
    }

    public BlockPos getOrigin() {
        return this.origin;
    }

    /** Поворот шаблона в градусах (0, 90, 180, 270). */
    public int getRotation() {
        return this.rotation;
    }

    public SlotBounds getBounds() {
        return this.bounds;
    }

    public boolean isModified() {
        return this.modified;
    }

    public void setModified(boolean modified) {
        this.modified = modified;
    }

    /** Слот в NBT. Поворот хранится числом — он нужен при повторном размещении. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Slot", this.slotId);
        tag.putString("Module", this.moduleId);
        tag.putInt("Level", this.level);
        tag.putLong("Origin", this.origin.asLong());
        tag.putInt("Rotation", this.rotation);
        tag.putIntArray("Bounds", this.bounds.toArray());
        tag.putBoolean("Modified", this.modified);
        return tag;
    }

    /**
     * Слот из NBT.
     *
     * @param tag тег слота
     * @return слот или {@code null}, если тег неполный (битое сохранение)
     */
    @Nullable
    public static CampModuleSlot load(CompoundTag tag) {
        String slotId = tag.getString("Slot");
        String moduleId = tag.getString("Module");
        if (slotId.isEmpty() || moduleId.isEmpty() || !tag.contains("Origin")) {
            return null;
        }
        return new CampModuleSlot(slotId, moduleId, tag.getInt("Level"),
                BlockPos.of(tag.getLong("Origin")), tag.getInt("Rotation"),
                SlotBounds.fromArray(tag.getIntArray("Bounds")), tag.getBoolean("Modified"));
    }

    @Override
    public String toString() {
        return this.slotId + "[" + this.moduleId + " lvl" + this.level + " rot" + this.rotation
                + " @" + this.origin.toShortString() + "]";
    }

    /**
     * Прямоугольная область в мировых координатах (включительно).
     *
     * <p>Собственный тип вместо {@code BoundingBox}: границы слота — данные
     * лагеря, они живут дольше одного размещения и не должны зависеть от
     * того, каким классом ванилла считает объём шаблона.
     */
    public static final class SlotBounds {

        private final int minX;
        private final int minY;
        private final int minZ;
        private final int maxX;
        private final int maxY;
        private final int maxZ;

        public SlotBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.minX = Math.min(minX, maxX);
            this.minY = Math.min(minY, maxY);
            this.minZ = Math.min(minZ, maxZ);
            this.maxX = Math.max(minX, maxX);
            this.maxY = Math.max(minY, maxY);
            this.maxZ = Math.max(minZ, maxZ);
        }

        public int getMinX() {
            return this.minX;
        }

        public int getMinY() {
            return this.minY;
        }

        public int getMinZ() {
            return this.minZ;
        }

        public int getMaxX() {
            return this.maxX;
        }

        public int getMaxY() {
            return this.maxY;
        }

        public int getMaxZ() {
            return this.maxZ;
        }

        /** Содержит ли точка область. */
        public boolean contains(BlockPos pos) {
            return pos.getX() >= this.minX && pos.getX() <= this.maxX
                    && pos.getY() >= this.minY && pos.getY() <= this.maxY
                    && pos.getZ() >= this.minZ && pos.getZ() <= this.maxZ;
        }

        /** Пересекаются ли две области (занятость соседнего слота). */
        public boolean intersects(SlotBounds other) {
            return this.minX <= other.maxX && this.maxX >= other.minX
                    && this.minY <= other.maxY && this.maxY >= other.minY
                    && this.minZ <= other.maxZ && this.maxZ >= other.minZ;
        }

        public int[] toArray() {
            return new int[]{this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ};
        }

        /** Область из массива; пустой или короткий массив даёт точку в нуле. */
        public static SlotBounds fromArray(int[] data) {
            if (data == null || data.length < 6) {
                return new SlotBounds(0, 0, 0, 0, 0, 0);
            }
            return new SlotBounds(data[0], data[1], data[2], data[3], data[4], data[5]);
        }

        /** Область нулевого размера в точке. */
        public static SlotBounds at(BlockPos pos) {
            return new SlotBounds(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX(), pos.getY(), pos.getZ());
        }

        @Override
        public String toString() {
            return this.minX + "," + this.minY + "," + this.minZ + ".."
                    + this.maxX + "," + this.maxY + "," + this.maxZ;
        }
    }
}