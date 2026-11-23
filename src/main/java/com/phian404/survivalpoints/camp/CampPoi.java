package com.phian404.survivalpoints.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;

/**
 * Точка интереса лагеря: место, которое важно для симуляции, но не является
 * блоком (грядка, пост охраны, точка сбора).
 *
 * <p>POI всегда привязан к слоту ({@link #getSlotId()}): когда слот
 * перегенерируется, его старые точки снимаются целиком, а не выборочно —
 * иначе после смены шаблона остались бы POI от прежней планировки.
 *
 * <p>{@link #getType()} — строка вида {@code survivalpoints:poi/farm_plot}.
 * Хранится строкой, а не enum: набор типов расширяется данными (шаблонами),
 * и новый тип не должен требовать правки Java.
 */
public class CampPoi {

    private final String slotId;
    private final String type;
    private final BlockPos pos;

    public CampPoi(String slotId, String type, BlockPos pos) {
        this.slotId = slotId;
        this.type = type;
        this.pos = pos;
    }

    /** Слот, из которого пришла точка. */
    public String getSlotId() {
        return this.slotId;
    }

    /** Тип точки: {@code survivalpoints:poi/farm_plot}. */
    public String getType() {
        return this.type;
    }

    public BlockPos getPos() {
        return this.pos;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Slot", this.slotId);
        tag.putString("Type", this.type);
        tag.putLong("Pos", this.pos.asLong());
        return tag;
    }

    /**
     * Точка из NBT.
     *
     * @param tag тег точки
     * @return точка или {@code null}, если тег неполный
     */
    @Nullable
    public static CampPoi load(CompoundTag tag) {
        String slotId = tag.getString("Slot");
        String type = tag.getString("Type");
        if (slotId.isEmpty() || type.isEmpty() || !tag.contains("Pos")) {
            return null;
        }
        return new CampPoi(slotId, type, BlockPos.of(tag.getLong("Pos")));
    }

    @Override
    public String toString() {
        return this.type + "@" + this.pos.toShortString();
    }
}