package com.phian404.survivalpoints.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Пост охраны лагеря: точка, тип и направление обзора.
 *
 * <p>Пост — это «закреплённое место» стража. Пока лагеря нет как объекта мира,
 * пост — просто точка в координатах; позже (волна 2–3) посты встают на забор
 * и вышки, а страж возвращается к своему посту вместо отхода «от врага».
 */
public class CampPost {

    /** Тип поста. */
    public enum Type {
        /** У забора: смотрит в узкий сектор наружу. */
        WALL,
        /** На вышке: обзор 360°, повышенный бонус к стрельбе. */
        TOWER,
        /** У ворот: основная точка конфликта. */
        GATE,
        /** Мобильный: страж обходит периметр. */
        PATROL
    }

    private final UUID id;
    private Type type;
    private BlockPos pos;
    private float facingYaw;
    @Nullable
    private UUID assignedGuardian;

    public CampPost(Type type, BlockPos pos, float facingYaw) {
        this(UUID.randomUUID(), type, pos, facingYaw, null);
    }

    private CampPost(UUID id, Type type, BlockPos pos, float facingYaw, @Nullable UUID assignedGuardian) {
        this.id = id;
        this.type = type;
        this.pos = pos;
        this.facingYaw = facingYaw;
        this.assignedGuardian = assignedGuardian;
    }

    public UUID getId() {
        return this.id;
    }

    public Type getType() {
        return this.type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public BlockPos getPos() {
        return this.pos;
    }

    public void setPos(BlockPos pos) {
        this.pos = pos;
    }

    /** Направление обзора в градусах (курс, как у сущности). */
    public float getFacingYaw() {
        return this.facingYaw;
    }

    public void setFacingYaw(float facingYaw) {
        this.facingYaw = facingYaw;
    }

    @Nullable
    public UUID getAssignedGuardian() {
        return this.assignedGuardian;
    }

    /** Занять пост стражем (или освободить, если {@code null}). */
    public void assign(@Nullable UUID guardian) {
        this.assignedGuardian = guardian;
    }

    /** Свободен ли пост. */
    public boolean isFree() {
        return this.assignedGuardian == null;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", this.id);
        tag.putString("Type", this.type.name());
        tag.putLong("Pos", this.pos.asLong());
        tag.putFloat("Yaw", this.facingYaw);
        if (this.assignedGuardian != null) {
            tag.putUUID("Guardian", this.assignedGuardian);
        }
        return tag;
    }

    public static CampPost load(CompoundTag tag) {
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        Type type;
        try {
            type = Type.valueOf(tag.getString("Type"));
        } catch (IllegalArgumentException e) {
            type = Type.WALL;
        }
        BlockPos pos = BlockPos.of(tag.getLong("Pos"));
        float yaw = tag.getFloat("Yaw");
        UUID guardian = tag.hasUUID("Guardian") ? tag.getUUID("Guardian") : null;
        return new CampPost(id, type, pos, yaw, guardian);
    }
}
