package com.phian404.survivalpoints.entity;

import com.phian404.survivalpoints.entity.role.SurvivorGender;
import com.phian404.survivalpoints.entity.role.SurvivorNames;
import com.phian404.survivalpoints.entity.role.SurvivorRole;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
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
     * Синхронизированный пол. Хранится как ordinal {@link SurvivorGender}.
     *
     * <p>Пол — самостоятельное постоянное свойство, а не производная от
     * индекса скина: игровой логике он нужен без обращения к клиентским
     * ресурсам, а сохранённое значение не должно переопределяться таблицей
     * скинов.
     */
    private static final EntityDataAccessor<Byte> DATA_GENDER =
            SynchedEntityData.defineId(BaseSurvivorEntity.class, EntityDataSerializers.BYTE);

    /**
     * Синхронизированный индивидуальный масштаб модели.
     *
     * <p>Масштаб назначается один раз при первичном создании и сохраняется,
     * потому что он визуально отличает NPC друг от друга. Перегенерация при
     * загрузке мира или смене роли сделала бы рост «прыгающим» и сломала бы
     * узнаваемость конкретного выжившего.
     */
    private static final EntityDataAccessor<Float> DATA_SCALE =
            SynchedEntityData.defineId(BaseSurvivorEntity.class, EntityDataSerializers.FLOAT);

    /** Диапазон роста для женского NPC. */
    public static final float SCALE_FEMALE_MIN = 0.90F;
    public static final float SCALE_FEMALE_MAX = 0.97F;

    /** Диапазон роста для мужского NPC. */
    public static final float SCALE_MALE_MIN = 0.95F;
    public static final float SCALE_MALE_MAX = 1.02F;

    /**
     * Показывать ли имя NPC над головой.
     *
     * <p>Вынесено в константу, чтобы позже сделать настройкой без правки
     * логики назначения имени. Значение {@code true}: имя читается издалека,
     * а не только при наведении прицела.
     */
    public static final boolean CUSTOM_NAME_VISIBLE = true;

    /**
     * Валидационный коридор роста — объединение диапазонов обоих полов.
     * Используется при чтении NBT: значение вне коридора считается мусором.
     */
    public static final float SCALE_MIN = SCALE_FEMALE_MIN;
    public static final float SCALE_MAX = SCALE_MALE_MAX;

    /**
     * Сколько вариантов скина доступно. Индекс синхронизируется как 0..COUNT-1.
     *
     * <p>Значение должно совпадать с длиной таблицы вариантов
     * {@link SurvivorSkin}.
     */
    public static final int SKIN_COUNT = 14;

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
        this.entityData.define(DATA_GENDER, (byte) SurvivorGender.MALE.ordinal());
        this.entityData.define(DATA_SCALE, 1.0F);
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

    /**
     * Случайный скин. Вызывается только на сервере при первичном создании.
     *
     * <p>Пол назначается здесь же, из таблицы скинов: при первичном создании
     * пол и скин всегда согласованы. При смене роли и загрузке мира этот метод
     * не вызывается, поэтому сохранённый пол не перезаписывается.
     */
    public void rollRandomSkin() {
        int index = this.getRandom().nextInt(SKIN_COUNT);
        this.setSkinIndex(index);
        this.setGender(SurvivorSkin.genderFor(index));
    }

    // ==================== Имя ====================

    /**
     * Назначает имя, если его ещё нет.
     *
     * <p>Метод идемпотентен: повторный вызов ничего не меняет. Это позволяет
     * вызывать его из всех точек появления NPC без риска перегенерировать имя
     * при смене роли или загрузке мира.
     *
     * <p>Имя хранится в ванильном {@code CustomName}: отдельного поля в NBT нет,
     * поэтому сохранение и синхронизация клиенту выполняются движком.
     *
     * <p>Пол должен быть уже установлен — от него зависит набор имён. Поэтому
     * вызовы из NBT идут после восстановления {@link #getGender()}.
     */
    public void ensureName() {
        if (this.hasCustomName()) {
            return;
        }
        this.setCustomName(Component.literal(SurvivorNames.random(this.getGender(), this.getRandom())));
        this.setCustomNameVisible(CUSTOM_NAME_VISIBLE);
    }

    // ==================== Пол ====================

    public SurvivorGender getGender() {
        return SurvivorGender.byOrdinal(this.entityData.get(DATA_GENDER));
    }

    public void setGender(SurvivorGender gender) {
        this.entityData.set(DATA_GENDER, (byte) gender.ordinal());
    }

    // ==================== Индивидуальный рост ====================

    public float getScale() {
        return this.entityData.get(DATA_SCALE);
    }

    public void setScale(float scale) {
        this.entityData.set(DATA_SCALE, scale);
    }

    /**
     * Случайный рост по полу. Вызывается только при первичном создании и при
     * миграции сохранения, где тега {@code Scale} нет или он невалиден.
     *
     * <p>Пол должен быть уже установлен: диапазон зависит от него. Поэтому
     * метод нельзя вызывать раньше {@link #setGender(SurvivorGender)}.
     */
    public void rollRandomScale() {
        float min = this.getGender() == SurvivorGender.FEMALE ? SCALE_FEMALE_MIN : SCALE_MALE_MIN;
        float max = this.getGender() == SurvivorGender.FEMALE ? SCALE_FEMALE_MAX : SCALE_MALE_MAX;
        this.setScale(Mth.nextFloat(this.getRandom(), min, max));
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
        tag.putString("Gender", this.getGender().name());
        tag.putFloat("Scale", this.getScale());
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

        // Порядок важен: скин читается первым, потому что от него зависит
        // миграция пола у старых сохранений. getInt при отсутствии ключа
        // возвращает 0, поэтому читать пол раньше скина нельзя.
        this.setSkinIndex(tag.getInt("Skin"));

        if (tag.contains("Gender")) {
            this.setGender(SurvivorGender.byName(tag.getString("Gender")));
        } else {
            // Старое сохранение: тега пола ещё нет — восстанавливаем по скину.
            this.setGender(SurvivorSkin.genderFor(this.getSkinIndex()));
        }

        // Рост читается строго после пола: диапазон зависит от пола. При
        // отсутствии или мусоре в теге значение назначается один раз и
        // сохранится при следующей записи NBT.
        float savedScale = tag.getFloat("Scale");
        if (tag.contains("Scale")
                && !Float.isNaN(savedScale) && !Float.isInfinite(savedScale)
                && savedScale >= SCALE_MIN && savedScale <= SCALE_MAX) {
            this.setScale(savedScale);
        } else {
            this.rollRandomScale();
        }

        this.campId = tag.hasUUID("CampId") ? tag.getUUID("CampId") : null;
        this.campPostId = tag.hasUUID("CampPostId") ? tag.getUUID("CampPostId") : null;

        // Имя читается движком из ванильного CustomName до этого метода.
        // ensureName() покрывает только старые сохранения, где имени не было:
        // там оно назначается один раз и сохранится при следующей записи NBT.
        this.ensureName();
    }
}
