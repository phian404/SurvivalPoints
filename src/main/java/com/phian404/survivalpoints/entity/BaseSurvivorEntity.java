package com.phian404.survivalpoints.entity;

import com.phian404.survivalpoints.entity.role.SurvivorRole;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Общая основа всех выживших NPC мода.
 *
 * <p>Здесь живут только данные, общие для любой роли: постоянный identity UUID,
 * роль, выбранный скин и необязательная привязка к лагерю/посту. Боевая логика
 * стража остаётся в {@link BaseGuardianEntity}, пассивное поведение — в
 * {@code BaseSurvivorNpcEntity}.
 *
 * <p><b>Почему identity UUID отдельно от {@code getUUID()}.</b> UUID сущности
 * Minecraft идентифицирует конкретный объект и меняется при замене типа
 * (в 1.20.1 {@code Mob.convertTo(...)} создаёт новую сущность). Лагерные записи
 * должны переживать замену, поэтому они ссылаются на стабильный identity UUID.
 */
public abstract class BaseSurvivorEntity extends PathfinderMob {

    private static final EntityDataAccessor<Integer> DATA_SKIN =
            SynchedEntityData.defineId(BaseSurvivorEntity.class, EntityDataSerializers.INT);

    /**
     * Сколько вариантов скина доступно. Индекс синхронизируется как 0..COUNT-1.
     *
     * <p>Значение должно совпадать с длиной таблицы текстур в
     * {@code ModEntityRenderers.SurvivorSkin}.
     */
    public static final int SKIN_COUNT = 9;

    /** Постоянный UUID выжившего, не зависящий от UUID текущей сущности. */
    private UUID identityId;

    /** Текущая роль. У базового NPC это CIVILIAN, у стража — GUARDIAN. */
    private SurvivorRole role = SurvivorRole.CIVILIAN;

    /** Необязательная привязка к лагерю. Независимый NPC её не имеет. */
    @Nullable
    private UUID campId;

    /** Необязательная привязка к посту лагеря. */
    @Nullable
    private UUID campPostId;

    protected BaseSurvivorEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_SKIN, 0);
    }

    // ==================== Идентичность ====================

    /**
     * Постоянный identity UUID.
     *
     * <p>При первом обращении создаётся из UUID сущности, чтобы уже существующие
     * стражи (у которых поля в NBT ещё нет) автоматически получили identity,
     * совпадающий с их текущим Entity UUID. Благодаря этому старые лагерные
     * записи {@code guardianPosts}, где ключом был UUID сущности, остаются
     * валидными без отдельной миграции.
     */
    public UUID getIdentityId() {
        if (this.identityId == null) {
            this.identityId = this.getUUID();
        }
        return this.identityId;
    }

    public void setIdentityId(UUID identityId) {
        this.identityId = identityId;
    }

    // ==================== Роль ====================

    public SurvivorRole getRole() {
        return this.role;
    }

    protected void setRoleInternal(SurvivorRole role) {
        this.role = role;
    }

    // ==================== Скин ====================

    public int getSkinIndex() {
        int index = this.entityData.get(DATA_SKIN);
        return index < 0 || index >= SKIN_COUNT ? 0 : index;
    }

    public void setSkinIndex(int index) {
        int clamped = index < 0 ? 0 : Math.min(index, SKIN_COUNT - 1);
        this.entityData.set(DATA_SKIN, clamped);
    }

    /** Случайный скин. Вызывается только на сервере при первичном создании. */
    public void rollRandomSkin() {
        this.setSkinIndex(this.getRandom().nextInt(SKIN_COUNT));
    }

    // ==================== Лагерь ====================

    @Nullable
    public UUID getCampId() {
        return this.campId;
    }

    public void setCampId(@Nullable UUID campId) {
        this.campId = campId;
    }

    @Nullable
    public UUID getCampPostId() {
        return this.campPostId;
    }

    public void setCampPostId(@Nullable UUID campPostId) {
        this.campPostId = campPostId;
    }

    public boolean hasCampBinding() {
        return this.campId != null;
    }

    // ==================== Сохранение ====================

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putUUID("IdentityId", this.getIdentityId());
        tag.putString("Role", this.role.name());
        tag.putInt("Skin", this.getSkinIndex());
        if (this.campId != null) {
            tag.putUUID("CampId", this.campId);
        }
        if (this.campPostId != null) {
            tag.putUUID("CampPostId", this.campPostId);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("IdentityId")) {
            this.identityId = tag.getUUID("IdentityId");
        }
        this.role = SurvivorRole.byName(tag.getString("Role"));
        this.setSkinIndex(tag.getInt("Skin"));
        this.campId = tag.hasUUID("CampId") ? tag.getUUID("CampId") : null;
        this.campPostId = tag.hasUUID("CampPostId") ? tag.getUUID("CampPostId") : null;
    }
}
