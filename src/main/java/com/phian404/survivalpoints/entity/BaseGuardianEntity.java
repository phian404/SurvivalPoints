package com.phian404.survivalpoints.entity;

import com.phian404.survivalpoints.entity.ai.GuardianEatBreadGoal;
import com.phian404.survivalpoints.entity.ai.GuardianLookAtPlayerGoal;
import com.phian404.survivalpoints.entity.ai.GuardianMeleeAttackGoal;
import com.phian404.survivalpoints.entity.ai.GuardianRangedAttackGoal;
import com.phian404.survivalpoints.entity.ai.GuardianRetreatGoal;
import com.phian404.survivalpoints.entity.ai.GuardianTargetGoal;
import com.phian404.survivalpoints.init.GuardianTuning;
import com.phian404.survivalpoints.init.ModEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Страж — гуманоидный защитник.
 *
 * <p>Архитектура: вся боевая логика вынесена в {@link GuardianState} — единый автомат
 * состояний. Голды отвечают ТОЛЬКО за перемещение, и каждый из них работает
 * исключительно в своём состоянии. Это исключает ситуацию, когда три гола одновременно
 * пишут в {@code setDeltaMovement} и дёргают моба в разные стороны (старый баг:
 * страж «зависал» в стене и умирал в углу).
 *
 * <p>Перемещение в бою: <b>маршрут строит навигация</b> (она обходит стены, ищет
 * выход из ямы и умеет подъёмы на блок), а <b>скорость считает сам страж</b> — в
 * мировых координатах, от следующего узла маршрута. Благодаря этому тело можно
 * держать развёрнутым к врагу, даже когда маршрут уводит в сторону.
 */
public class BaseGuardianEntity extends PathfinderMob {

    public static EntityType<BaseGuardianEntity> TYPE;

    private static final EntityDataAccessor<Boolean> DATA_CHARGING =
            SynchedEntityData.defineId(BaseGuardianEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<Byte> DATA_AI_STATE =
            SynchedEntityData.defineId(BaseGuardianEntity.class, EntityDataSerializers.BYTE);

    /**
     * Синхронизированный наклон головы (в градусах).
     *
     * <p>Курс тела передавать не нужно: он идёт ванильным пакетом поворота
     * ({@code getYRot()}), и клиент его и так получает. А вот наклон
     * ({@code xRot}) клиент обнуляет каждый тик в {@code LookControl}, поэтому
     * его приходится дублировать здесь.
     */
    private static final EntityDataAccessor<Float> DATA_HEAD_PITCH =
            SynchedEntityData.defineId(BaseGuardianEntity.class, EntityDataSerializers.FLOAT);

    /**
     * Синхронизированный курс головы (в градусах).
     *
     * <p>Курс головы передаётся ещё и ванильным пакетом
     * {@code ClientboundRotateHeadPacket}, но клиентский {@code LookControl}
     * каждый тик уводит {@code yHeadRot} к корпусу и зажимает его углами
     * (75°). Поэтому дублируем его здесь, а рендерер подставляет это значение
     * при отрисовке — тогда голова на клиенте точно такая же, как на сервере.
     */
    private static final EntityDataAccessor<Float> DATA_HEAD_YAW =
            SynchedEntityData.defineId(BaseGuardianEntity.class, EntityDataSerializers.FLOAT);

    /** Сколько тиков осталось анимации взмаха мечом (0 — взмаха нет). */
    private static final EntityDataAccessor<Byte> DATA_SWING_TIME =
            SynchedEntityData.defineId(BaseGuardianEntity.class, EntityDataSerializers.BYTE);

    /**
     * Сглаженный курс тела.
     *
     * <p>Собственное поле, а не {@code yBodyRot}: ванильный {@code yBodyRot}
     * каждый тик уводится по направлению движения, и доворот отсчитывался бы
     * каждый раз заново.
     */
    private float bodyYaw = 0.0F;

    /**
     * Курс головы — ведётся отдельно от корпуса.
     *
     * <p>Голова доворачивается к цели быстрее корпуса, но не может отойти от
     * него дальше, чем на {@link GuardianTuning#MAX_HEAD_YAW_OFFSET} — ровно
     * как в ванильном {@code LookControl.clampHeadRotationToBody()}.
     */
    private float headYaw = 0.0F;

    /** Сглаженный наклон головы. */
    private float headPitch = 0.0F;

    // ==================== Плавность движения ====================

    /**
     * Текущий сглаженный курс движения (градусы) и признак, что он задан.
     *
     * <p>Направление движения берётся из узла навигационного маршрута, а узел
     * меняется при каждом пересчёте пути. Если писать скорость мгновенно,
     * страж дёргался, и анимация шага (walkAnimation) дёргалась вместе с ним.
     * Теперь курс доворачивается к нужному с пределом градусов за тик — так же,
     * как это делает ванильный {@code MoveControl.rotlerp}.
     */
    private float moveYaw = 0.0F;
    private boolean moveYawValid = false;

    /** Горизонтальная скорость: разгоняется и тормозится плавно. */
    private double moveSpeed = 0.0D;

    /** Просил ли кто-нибудь двигаться в прошлом тике. */
    private boolean moveRequested = false;


    /**
     * Единственный источник правды о том, что страж делает прямо сейчас.
     * Значение синхронизируется с клиентом и используется рендером.
     */
    public enum GuardianState {
        /** Цели нет: ходит, ест хлеб, осматривается. */
        IDLE,
        /** Цель есть, держит дистанцию и стреляет из арбалета. */
        RANGED,
        /** Цель вплотную: достаёт меч. */
        MELEE,
        /** Мало ХП: отступает, стреляет и зовёт союзников. Всегда с арбалетом. */
        PANIC
    }

    /** Состояние для клиентского рендера (обратная совместимость). */
    public enum CombatState { RANGED, MELEE }

    // ==================== Заряд и кулдауны ====================

    private int crossbowChargeTime = 0;

    /**
     * «Взвод»: сколько тиков страж держит заряженный арбалет на цели,
     * прежде чем выпустить стрелу.
     *
     * <p>Без этой паузы выстрел вылетает ровно в тот тик, когда арбалет
     * дозарядился, — выглядит так, будто страж схватил арбалет и тут же
     * выстрелил. Полсекунды наводки на цель выглядит естественнее.
     */
    private int shotDelay = 0;

    /** Сколько тиков осталось анимации взмаха мечом. */
    private int meleeSwingTime = 0;
    private int helpScreamCooldown = 0;
    private int swingSoundCooldown = 0;
    private int breadCooldown = 0;
    private boolean eating = false;

    // ==================== Статистика стрельбы (диагностика) ====================

    /** Сколько стрел выстрелено. */
    private int shotsFired = 0;

    /** Сколько попаданий зарегистрировал сервер. */
    private int arrowsHit = 0;

    /** Сколько урона суммарно нанесено. */
    private float damageDealt = 0.0F;

    // ==================== Помощь союзникам ====================

    /** Куда союзник должен подойти (точка, где звал страж). */
    @Nullable
    private BlockPos rallyPoint = null;

    /** Кого союзник должен атаковать после прибытия. */
    @Nullable
    private LivingEntity rallyTarget = null;

    /** Сколько тиков ещё актуальна точка сбора. */
    private int rallyTimer = 0;

    // ==================== Рабочий рубеж ====================

    /**
     * Занят ли рабочий рубеж.
     *
     * <p>Смысл тот же, что и у одноимённого флага в голе, но состояние переживает
     * перезапуск гола (смена состояний {@code MELEE ↔ RANGED}) — из-за чего раньше
     * страж терял достигнутый рубеж и снова сближался с врагом.
     */
    private boolean standoffReached = false;

    /**
     * Мировая позиция занятого рубежа.
     *
     * <p>Отход считается от этой точки, а не от позиции врага: якорь стоит на месте,
     * поэтому маршрут не пересчитывается каждый тик и страж не «танцует» на рубеже.
     * Позже этот якорь заменит настоящий пост лагеря.
     */
    @Nullable
    private Vec3 standoffAnchor = null;

    /**
     * Высота глаз стража.
     *
     * <p>По умолчанию Minecraft считает её как {@code высота_хитбокса * 0.85}.
     * У игрока высота глаз ровно 1.62, а у стража получается 1.53 (при росте 1.8)
     * или 1.66 (при росте 1.95). Из-за разницы {@code LookControl} наклоняет голову
     * стража вниз, когда он смотрит на игрока. Ставим глаза на уровень игрока —
     * тогда наклон равен нулю по определению.
     *
     * <p>Переопределять {@code getEyeHeight()} нельзя — он final, но он вызывает
     * {@code getStandingEyeHeight()}, который переопределить можно.
     */
    @Override
    protected float getStandingEyeHeight(Pose pose, EntityDimensions dimensions) {
        return GuardianTuning.EYE_HEIGHT;
    }

    public BaseGuardianEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setCanPickUpLoot(true);

        // Ванильный MoveControl каждый тик доворачивает курс моба к направлению
        // маршрута (rotlerp, до 90°/тик). Это воюет с нашим разворотом корпуса
        // к цели: при отступлении маршрут и цель указывают в противоположные
        // стороны, и курс скачет между ними. Оставляем от MoveControl всё, кроме
        // доворачивания курса — в первую очередь прыжки через препятствия.
        this.moveControl = new MoveControl(this) {
            @Override
            public void tick() {
                float savedYaw = this.mob.getYRot();
                super.tick();
                this.mob.setYRot(savedYaw);
            }
        };
    }

    // ==================== Регистрация ====================

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_CHARGING, false);
        this.entityData.define(DATA_AI_STATE, (byte) GuardianState.IDLE.ordinal());
        this.entityData.define(DATA_HEAD_PITCH, 0.0F);
        this.entityData.define(DATA_HEAD_YAW, 0.0F);
        this.entityData.define(DATA_SWING_TIME, (byte) 0);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();

        // Каждый боевой гол работает ТОЛЬКО в своём состоянии (проверка в canUse).
        // Поэтому одновременно активен максимум один гол перемещения.
        this.goalSelector.addGoal(0, new GuardianRetreatGoal(this));      // PANIC
        this.goalSelector.addGoal(1, new GuardianMeleeAttackGoal(this));  // MELEE
        this.goalSelector.addGoal(2, new GuardianRangedAttackGoal(this));  // RANGED
        this.goalSelector.addGoal(3, new GuardianEatBreadGoal(this));     // IDLE
        this.goalSelector.addGoal(4, new RandomStrollGoal(this, 1.0D));    // IDLE

        // Взгляд на игрока — только в покое. Ванильный LookAtPlayerGoal при
        // появлении цели продолжает работать: его canUse() требует отсутствия
        // цели, а canContinueToUse() проверяет только наличие игрока рядом.
        // Из-за этого LookControl каждый тик уводил голову стража к игроку,
        // и голова доворачивалась отдельно от тела.
        this.goalSelector.addGoal(5, new GuardianLookAtPlayerGoal(this));

        this.targetSelector.addGoal(1, new GuardianTargetGoal(this));
    }

    // ==================== Рабочий рубеж ====================

    /**
     * Занят ли рабочий рубеж.
     *
     * <p>Флаг живёт в сущности, а не в голе: переключение состояний
     * {@code MELEE ↔ RANGED} перезапускает боевой гол, и если бы флаг остался в голе,
     * страж каждый раз «забывал» достигнутый рубеж и снова сближался — отсюда
     * качели «приблизился — отошёл — приблизился».
     */
    public boolean isStandoffReached() {
        return this.standoffReached;
    }

    /**
     * Пометить рубеж занятым и запомнить его позицию.
     *
     * @param anchor мировая позиция рубежа
     */
    public void setStandoffReached(Vec3 anchor) {
        this.standoffReached = true;
        this.standoffAnchor = anchor;
    }

    /**
     * Мировая позиция занятого рубежа.
     *
     * @return точка рубежа или {@code null}, если рубеж не занят
     */
    @Nullable
    public Vec3 getStandoffAnchor() {
        return this.standoffAnchor;
    }

    /** Забыть рубеж — следующий бой начнётся со сближения. */
    public void resetStandoff() {
        this.standoffReached = false;
        this.standoffAnchor = null;
    }

    // ==================== Состояние ====================

    public GuardianState getAIState() {
        byte id = this.entityData.get(DATA_AI_STATE);
        GuardianState[] values = GuardianState.values();
        if (id < 0 || id >= values.length) {
            return GuardianState.IDLE;
        }
        return values[id];
    }

    public void setAIState(GuardianState state) {
        this.entityData.set(DATA_AI_STATE, (byte) state.ordinal());
    }

    /** Состояние для рендера: PANIC и RANGED — это арбалет, MELEE — меч. */
    public CombatState getCombatState() {
        return this.getAIState() == GuardianState.MELEE ? CombatState.MELEE : CombatState.RANGED;
    }

    public boolean isPanicking() {
        return this.getAIState() == GuardianState.PANIC;
    }

    /**
     * Автомат состояний. Вызывается каждый тик на сервере.
     *
     * <p>Раньше здесь стоял ранний выход при ХП < 40% — из-за этого экипировка
     * «замораживалась» и страж, попав в панику с мечом в руке, уже не доставал арбалет.
     * Теперь паника — это отдельное состояние, в котором оружие всегда правильное.
     */
    public void updateAIState() {
        if (this.isEating()) {
            this.setAIState(GuardianState.IDLE);
            return;
        }

        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) {
            if (this.getAIState() != GuardianState.IDLE) {
                this.setAIState(GuardianState.IDLE);
                this.getNavigation().stop();
            }
            // Цели нет — рубеж больше не актуален.
            this.resetStandoff();
            return;
        }

        double distance = this.distanceTo(target);
        GuardianState current = this.getAIState();
        GuardianState next;

        if (this.getHealth() < this.getMaxHealth() * GuardianTuning.PANIC_HP_RATIO) {
            // Паника имеет приоритет над всем: отступаем и стреляем из арбалета.
            next = GuardianState.PANIC;
        } else if (current == GuardianState.MELEE && distance <= GuardianTuning.MELEE_EXIT_DISTANCE) {
            // Гистерезис: из меча возвращаемся к арбалету только на расстоянии.
            next = GuardianState.MELEE;
        } else if (distance < GuardianTuning.MELEE_ENTER_DISTANCE) {
            next = GuardianState.MELEE;
        } else {
            next = GuardianState.RANGED;
        }

        if (next != current) {
            this.setAIState(next);
            // Старый путь навигации может тянуть моба в другую сторону — гасим его.
            this.getNavigation().stop();
            this.resetChargeTime();
            // Уход в панику или в покой — новый бой, рабочий рубеж надо занять заново.
            // Переход MELEE ↔ RANGED рубеж НЕ сбрасывает: именно из-за этого
            // страж после ближнего боя снова лезёл к врагу и «качели».
            if (next == GuardianState.PANIC || next == GuardianState.IDLE) {
                this.resetStandoff();
            }
        }
    }

    /**
     * Приводит оружие в соответствие с текущим состоянием.
     * Вызывается каждый тик — самовосстанавливается, если предмет выпал.
     */
    public void syncEquipmentToState() {
        if (this.isEating()) {
            return;
        }

        ItemStack current = this.getItemBySlot(EquipmentSlot.MAINHAND);
        if (this.getAIState() == GuardianState.MELEE) {
            if (!current.is(Items.IRON_SWORD)) {
                this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            }
        } else {
            // RANGED, PANIC и IDLE — всегда арбалет.
            if (!current.is(Items.CROSSBOW)) {
                this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
            }
        }
    }

    // ==================== Тик ====================

    @Override
    public void tick() {
        // Если в прошлом тике никто не просил двигаться (страж стоял на месте),
        // накопленная скорость гаснет — иначе следующий старт был бы мгновенным.
        if (!this.moveRequested) {
            this.moveSpeed = 0.0D;
        }
        this.moveRequested = false;

        super.tick();
        if (this.level().isClientSide) {
            return;
        }

        // После смерти сущность ещё 20 тиков доигрывает анимацию смерти
        // (LivingEntity.tickDeath()), и наша логика в tick() продолжает работать.
        //
        // Ванильные голы в это время уже отключены: LivingEntity.aiStep() при
        // isImmobile() (это isDeadOrDying()) не вызывает serverAiStep(), то есть
        // goalSelector.tick() не тикает. А наша стрельба вызывается напрямую
        // из tick(), мимо aiStep() — поэтому без этой проверки страж успевает
        // зарядить арбалет (CROSSBOW_CHARGE_TICKS = 20 ровно равен длительности
        // анимации смерти) и выстрелить уже после смерти.
        if (!this.isAlive()) {
            this.resetChargeTime();
            this.getNavigation().stop();
            return;
        }

        this.tickCountdowns();
        this.validateTarget();
        this.updateAIState();
        this.syncEquipmentToState();

        // Разворот тела делаем ДО выстрела: страж должен быть уже повёрнут
        // лицом к врагу в тот тик, когда из арбалета вылетает стрела.
        // Голы и навигация к этому моменту уже отработали — они тикают
        // внутри super.tick(), до нашей логики.
        this.applyFacing();

        this.updateRangedCombat();
        this.updateChargingFlag();

        // В покое голова держится строго ровно. LookControl каждый тик сбрасывает
        // наклон в ноль, но если активен LookAtPlayerGoal, он тут же снова наклоняет
        // голову к игроку. Гасим наклон принудительно — в покое страж смотрит перед собой.
        if (this.getAIState() == GuardianState.IDLE) {
            this.setXRot(0.0F);
        }
    }

    /**
     * Должен ли страж разворачиваться к цели.
     *
     * <p>В режиме сбора (союзник идёт к точке) разворот запрещён: навигация
     * поворачивает тело по направлению пути, и принудительный разворот
     * сломает направление движения.
     */
    public boolean shouldFaceTarget() {
        GuardianState state = this.getAIState();
        if (state != GuardianState.RANGED && state != GuardianState.PANIC
                && state != GuardianState.MELEE) {
            return false;
        }
        LivingEntity target = this.getTarget();
        // Разворот нужен в том числе в режиме сбора: иначе союзник, пришедший
        // на помощь, разворачивается по маршруту и стреляет в спину.
        // Движение в режиме сбора идёт в мировых координатах, поэтому разворот
        // тела не ломает направление.
        return target != null && target.isAlive();
    }

    /** Имена активных голов — для диагностической команды /sp_guard. */
    public String getActiveGoalNames() {
        return this.goalSelector.getRunningGoals()
                .map(wrapped -> wrapped.getGoal().getClass().getSimpleName())
                .collect(Collectors.joining(", "));
    }


    /**
     * Правильный курс на цель (в градусах).
     *
     * <p>Считается так же, как в ванильном {@code LookControl.getYRotD()}:
     * {@code atan2(dz, dx) * 180/PI - 90}. С перепутанными аргументами
     * ({@code atan2(dx, dz)}) курс получается зеркальным: страж доворачивается
     * в сторону, противоположную цели, и визуально стреляет боком или спиной.
     * Ошибка была не видна в диагностике, потому что {@link #getDesiredYaw()}
     * считался той же неверной формулой и всегда совпадал с фактическим курсом.
     */
    public static float yawToTarget(double dx, double dz) {
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
    }

    /** Курс, на который страж должен смотреть (в градусах), либо NaN если цели нет. */
    public float getDesiredYaw() {
        LivingEntity target = this.getTarget();
        if (target == null) {
            return Float.NaN;
        }
        return yawToTarget(target.getX() - this.getX(), target.getZ() - this.getZ());
    }

    /** Расхождение фактического курса тела с направлением на цель (градусы). */
    public float getBodyYawError(LivingEntity target) {
        if (target == null) {
            return Float.NaN;
        }
        return Mth.wrapDegrees(yawToTarget(target.getX() - this.getX(),
                target.getZ() - this.getZ()) - this.bodyYaw);
    }

    /** Наведён ли корпус на цель достаточно, чтобы стрелять. */
    public boolean isBodyAlignedWithTarget(LivingEntity target) {
        float error = this.getBodyYawError(target);
        return !Float.isNaN(error)
                && Math.abs(error) <= GuardianTuning.FIRE_AIM_TOLERANCE_DEG;
    }

    /** Синхронизированный наклон головы (градусы) — его подставляет рендерер. */
    public float getSyncedHeadPitch() {
        return this.entityData.get(DATA_HEAD_PITCH);
    }

    /** Синхронизированный курс головы (градусы) — его подставляет рендерер. */
    public float getSyncedHeadYaw() {
        return this.entityData.get(DATA_HEAD_YAW);
    }

    /**
     * Запустить анимацию взмаха мечом.
     *
     * <p>Счётчик синхронизируется с клиентом, поэтому анимация видна всегда —
     * в отличие от ванильного {@code swing()}, который зависит от пакета анимации.
     */
    public void startMeleeSwing() {
        this.meleeSwingTime = GuardianTuning.MELEE_SWING_ANIM_TICKS;
        this.entityData.set(DATA_SWING_TIME, (byte) this.meleeSwingTime);
    }

    /** Сколько тиков осталось анимации взмаха (0 — взмаха нет). */
    public int getSyncedSwingTime() {
        return this.entityData.get(DATA_SWING_TIME);
    }

    private void applyFacing() {
        if (!this.shouldFaceTarget()) {
            return;
        }
        LivingEntity target = this.getTarget();
        if (target != null && target.isAlive()) {
            this.faceTarget(target);
        }
    }

    private void tickCountdowns() {
        if (this.helpScreamCooldown > 0) this.helpScreamCooldown--;
        if (this.swingSoundCooldown > 0) this.swingSoundCooldown--;
        if (this.breadCooldown > 0) this.breadCooldown--;

        // Анимация взмаха мечом: считаем сами и синхронизируем с клиентом.
        // Ванильный swing() на клиенте может не дойти (пакет анимации), а своё
        // значение в SynchedEntityData доходит всегда.
        if (this.meleeSwingTime > 0) {
            this.meleeSwingTime--;
            this.entityData.set(DATA_SWING_TIME, (byte) this.meleeSwingTime);
        }

        if (this.rallyTimer > 0) {
            this.rallyTimer--;
            if (this.rallyTimer == 0) {
                this.clearRally();
            }
        }
    }

    /** Сбрасываем цель, если враг мёртв или слишком далеко. */
    private void validateTarget() {
        LivingEntity target = this.getTarget();
        if (target == null) {
            return;
        }
        double max = GuardianTuning.TARGET_DROP_DISTANCE * GuardianTuning.TARGET_DROP_DISTANCE;
        if (!target.isAlive() || this.distanceToSqr(target) > max) {
            this.setTarget(null);
            this.clearRally();
        }
    }

    private void updateChargingFlag() {
        ItemStack weapon = this.getMainHandItem();
        // Поза CROSSBOW_CHARGE — пока арбалет поднимается (заряд ещё идёт).
        // Как только заряд полный, поза меняется на CROSSBOW_HOLD: это видимый
        // «взвод» — страж держит арбалет готовым и наведённым, но ещё не стреляет.
        boolean raising = this.crossbowChargeTime > 0
                && this.crossbowChargeTime < GuardianTuning.CROSSBOW_CHARGE_TICKS
                && weapon.is(Items.CROSSBOW);
        if (this.entityData.get(DATA_CHARGING) != raising) {
            this.entityData.set(DATA_CHARGING, raising);
        }

        // Тег «Charged» на самом арбалете означает «заряжен» — он должен стоять
        // и во время взвода, когда заряд уже полный, а выстрела ещё нет.
        this.updateCrossbowNBT(this.crossbowChargeTime >= GuardianTuning.CROSSBOW_CHARGE_TICKS);
    }

    // ==================== Стрельба ====================

    /**
     * Заряд и выстрел. Вынесено из гола в сущность, чтобы паника и обычный бой
     * использовали одну и ту же логику и не зависели от того, какой гол активен.
     */
    private void updateRangedCombat() {
        GuardianState state = this.getAIState();
        if (state != GuardianState.RANGED && state != GuardianState.PANIC) {
            this.resetChargeTime();
            return;
        }

        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) {
            this.resetChargeTime();
            return;
        }

        if (!this.getMainHandItem().is(Items.CROSSBOW)) {
            this.resetChargeTime();
            return;
        }

        double distance = this.distanceTo(target);
        if (distance > GuardianTuning.FIRE_MAX_DISTANCE || distance < GuardianTuning.FIRE_MIN_DISTANCE) {
            // Слишком далеко (не долетает) или слишком близко (бессмысленно) — не стреляем.
            this.decrementChargeTime();
            this.shotDelay = 0;
            return;
        }

        if (this.getSensing().hasLineOfSight(target)) {
            this.incrementChargeTime();
            if (this.getCrossbowChargeTime() >= GuardianTuning.CROSSBOW_CHARGE_TICKS) {
                // Стреляем, только когда корпус доворачивается на цель: иначе
                // выстрел уходит боком, а если разворот невозможен — вообще
                // в произвольную сторону. Пока не наведён, ждём: заряд копится,
                // и выстрел пройдёт сразу, как только тело встанет на цель.
                if (this.isBodyAlignedWithTarget(target)) {
                    // «Взвод»: держим оружие на цели ещё PRE_SHOT_DELAY_TICKS,
                    // и только потом стреляем. Заряд при этом не сбрасывается,
                    // поэтому арбалет всё это время остаётся в позе готовности.
                    this.shotDelay++;
                    if (this.shotDelay >= GuardianTuning.PRE_SHOT_DELAY_TICKS) {
                        this.performRangedAttack(target, 1.0F);
                        this.resetChargeTime();
                        this.shotDelay = 0;
                    }
                }
            }
        } else {
            this.decrementChargeTime();
            this.shotDelay = 0;
        }
    }

    public void performRangedAttack(LivingEntity target, float pullProgress) {
        // Мёртвый страж не стреляет. Сущность ещё 20 тиков доигрывает анимацию
        // смерти, и вызов может прийти откуда угодно — проверяем здесь, у самого
        // выстрела, а не только в тике.
        if (!this.isAlive()) {
            return;
        }

        ItemStack weapon = this.getMainHandItem();
        if (!weapon.is(Items.CROSSBOW)) return;

        this.shotsFired++;

        // Ванильная стрела. Отдельный подкласс не нужен: «отскок» при отменённом
        // уроне — штатная ванильная механика (else-ветка AbstractArrow.onHitEntity),
        // одинаковая и для скелетов. Подавлять её было бессмысленно — отскок
        // пролетает меньше двух блоков, и на исходную проблему «стрела попала
        // в союзников» он не влияет вообще. Настоящая причина — промахи,
        // поэтому ниже добавлено упреждение по скорости цели.
        Arrow arrow = new Arrow(this.level(), this);

        Vec3 eyePos = this.getEyePosition();

        // Стрела появляется не из головы, а чуть впереди — из руки с арбалетом.
        // Смещение считаем по направлению на цель, а не по курсу тела: разворот
        // тела происходит в конце тика, и по курсу стрела на тик вылетала бы «из спины».
        Vec3 toTarget = new Vec3(target.getX() - eyePos.x, 0.0D, target.getZ() - eyePos.z);
        if (toTarget.lengthSqr() < 1.0E-6D) {
            Vec3 look = this.getLookAngle();
            toTarget = new Vec3(look.x, 0.0D, look.z);
            if (toTarget.lengthSqr() < 1.0E-6D) {
                toTarget = new Vec3(0.0D, 0.0D, 1.0D);
            }
        }
        toTarget = toTarget.normalize();

        // Базовая точка вылета — у арбалета, на уровне глаз.
        Vec3 spawnBase = eyePos.add(toTarget.scale(GuardianTuning.ARROW_SPAWN_OFFSET));

        // ---------- Упреждение (lead) ----------
        // Цель движется, пока стрела летит. Без упреждения стрела целится в
        // статичную точку: на 10+ блоках зомби смещается примерно на блок, и
        // около половины выстрелов уходит в молоко, продолжая полёт и попадая
        // в союзника на противоположной стороне. Отбрасывание цели первой
        // стрелой (LivingEntity.knockback, 0.4 блока) усиливает этот разброс.
        //
        // Целимся туда, где цель окажется к моменту попадания:
        // позиция цели + её скорость * время полёта.
        Vec3 targetDelta = target.getDeltaMovement();

        double flightTicks = spawnBase.distanceTo(target.position()) / GuardianTuning.ARROW_SPEED;
        double leadScale = flightTicks * GuardianTuning.ARROW_LEAD_FACTOR;
        Vec3 aimPoint = new Vec3(
                target.getX() + targetDelta.x * leadScale,
                target.getEyeY() + targetDelta.y * leadScale,
                target.getZ() + targetDelta.z * leadScale);

        // Второй проход: время полёта уточняем уже по предсказанной точке.
        flightTicks = spawnBase.distanceTo(aimPoint) / GuardianTuning.ARROW_SPEED;
        leadScale = flightTicks * GuardianTuning.ARROW_LEAD_FACTOR;
        aimPoint = new Vec3(
                target.getX() + targetDelta.x * leadScale,
                target.getEyeY() + targetDelta.y * leadScale,
                target.getZ() + targetDelta.z * leadScale);

        // Компенсация гравитации: за время полёта стрела уходит вниз на 0.5*g*t².
        // Без этого на 10+ блоках стрела падает в землю, не долетая до цели.
        double drop = 0.5D * GuardianTuning.ARROW_GRAVITY * flightTicks * flightTicks;

        Vec3 aim = new Vec3(aimPoint.x, aimPoint.y + drop, aimPoint.z)
                .subtract(spawnBase).normalize();

        // Смещение точки появления: вперёд по направлению выстрела и немного
        // вниз — чтобы стрела не возникала внутри модели стража и сразу
        // двигалась свободно. Направление и скорость при этом не меняются:
        // траектория остаётся прежней, меняется только точка старта.
        Vec3 aimFlat = new Vec3(aim.x, 0.0D, aim.z);
        if (aimFlat.lengthSqr() > 1.0E-6D) {
            aimFlat = aimFlat.normalize();
        } else {
            aimFlat = toTarget;
        }
        Vec3 spawn = spawnBase.add(aimFlat.scale(GuardianTuning.ARROW_SPAWN_FORWARD));
        spawn = new Vec3(spawn.x, spawn.y - GuardianTuning.ARROW_SPAWN_DOWN, spawn.z);

        arrow.setPos(spawn.x, spawn.y, spawn.z);
        arrow.setDeltaMovement(aim.scale(GuardianTuning.ARROW_SPEED));

        // Урон стрелы в 1.20.1 равен ceil(длина вектора скорости × baseDamage),
        // поэтому множитель подбирается под нужный урон.
        arrow.setBaseDamage(GuardianTuning.ARROW_DAMAGE_MULTIPLIER);

        // Без этого клиент получает позицию стрелы раз в 10-20 тиков — визуально
        // стрела «телепортируется» и выглядит лагающей.
        arrow.hasImpulse = true;

        this.level().addFreshEntity(arrow);
        this.playSound(SoundEvents.CROSSBOW_SHOOT, 1.0F,
                1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));

        this.entityData.set(DATA_CHARGING, false);
        this.updateCrossbowNBT(false);
    }

    private void updateCrossbowNBT(boolean charging) {
        ItemStack weapon = this.getMainHandItem();
        if (weapon.isEmpty()) return;

        CompoundTag tag = weapon.getTag();
        if (tag == null) {
            tag = new CompoundTag();
        }

        if (tag.getBoolean("Charged") != charging) {
            tag.putBoolean("Charged", charging);
            weapon.setTag(tag);
        }
    }

    // ==================== Заряд (доступ для голов) ====================

    public void incrementChargeTime() {
        this.crossbowChargeTime++;
    }

    public void resetChargeTime() {
        this.crossbowChargeTime = 0;
        this.shotDelay = 0;
    }

    /** Сколько тиков осталось до выстрела («взвод») — для диагностики. */
    public int getShotDelay() {
        return this.shotDelay;
    }

    public void decrementChargeTime() {
        this.crossbowChargeTime = Math.max(0, this.crossbowChargeTime - 1);
    }

    public boolean isCharging() {
        return this.entityData.get(DATA_CHARGING);
    }

    public int getCrossbowChargeTime() {
        return this.crossbowChargeTime;
    }

    // ==================== Статистика стрельбы ====================

    /** Регистрация попадания стрелы (вызывается из обработчика урона). */
    public void onArrowHit(float amount) {
        this.arrowsHit++;
        this.damageDealt += amount;
    }

    public int getShotsFired() {
        return this.shotsFired;
    }

    public int getArrowsHit() {
        return this.arrowsHit;
    }

    public float getDamageDealt() {
        return this.damageDealt;
    }

    // ==================== Зов о помощи ====================

    public boolean canScreamForHelp() {
        return this.helpScreamCooldown <= 0;
    }

    public void startScreamCooldown() {
        this.helpScreamCooldown = GuardianTuning.HELP_SCREAM_COOLDOWN;
    }

    /**
     * Зов о помощи. Союзники в радиусе получают точку сбора и цель,
     * после чего реально приходят к зовущему (а не просто «берут цель на себя»).
     */
    public void callForHelp(LivingEntity attacker) {
        if (this.level().isClientSide) return;
        if (!this.canScreamForHelp()) return;

        int responders = 0;
        for (BaseGuardianEntity ally : this.getAlliesInRadius(GuardianTuning.ALLY_SEARCH_RADIUS)) {
            if (ally == this) continue;
            if (this.distanceTo(ally) > GuardianTuning.RALLY_RADIUS) continue;

            ally.rallyTo(this.blockPosition(), attacker);
            ally.syncEquipmentToState();
            responders++;
        }

        if (responders > 0) {
            this.level().getServer().getPlayerList().broadcastSystemMessage(
                    Component.literal("§c[Страж] §fПомогите мне!"), false);
        }

        this.playSound(SoundEvents.VILLAGER_NO, 1.0F, 1.0F);
        this.startScreamCooldown();
    }

    /**
     * Союзник принял призыв: идёт к точке сбора и атакует указанную цель.
     */
    public void rallyTo(BlockPos point, LivingEntity attacker) {
        this.rallyPoint = point;
        this.rallyTarget = attacker;
        this.rallyTimer = GuardianTuning.RALLY_DURATION;

        if (attacker != null && attacker.isAlive()) {
            this.setTarget(attacker);
        }
    }

    public boolean hasRallyPoint() {
        return this.rallyPoint != null && this.rallyTimer > 0;
    }

    @Nullable
    public BlockPos getRallyPoint() {
        return this.rallyPoint;
    }

    @Nullable
    public LivingEntity getRallyTarget() {
        return this.rallyTarget;
    }

    public void clearRally() {
        this.rallyPoint = null;
        this.rallyTarget = null;
        this.rallyTimer = 0;
    }

    /**
     * Союзники рядом. Поиск выполняется не каждый тик, а раз в ALLY_SEARCH_INTERVAL,
     * результат кэшируется и фильтруется по реальной дистанции на момент вызова.
     */
    public List<BaseGuardianEntity> getAlliesInRadius(double radius) {
        if (this.level().isClientSide) {
            return List.of();
        }

        double r2 = radius * radius;
        List<BaseGuardianEntity> result = new ArrayList<>();
        // Список стражей берётся из общего кэша мода: один перебор сущностей
        // на весь мир, а не отдельный перебор на каждого стража.
        for (BaseGuardianEntity ally : ModEntityEvents.getGuardians(this.level())) {
            if (ally == this || !ally.isAlive()) continue;
            if (this.distanceToSqr(ally) > r2) continue;
            result.add(ally);
        }
        return result;
    }

    // ==================== Перемещение и застревание ====================

    /**
     * Поворачивает моба лицом к точке и записывает горизонтальную скорость
     * в мировых координатах. Направление не зависит от текущего курса моба,
     * поэтому страж может одновременно смотреть на врага и от него отходить.
     */
    public void moveDirectly(Vec3 direction, double speed) {
        if (direction.lengthSqr() < 1.0E-6D) {
            return;
        }
        this.moveRequested = true;
        Vec3 target = direction.normalize();

        // --- Плавный доворот направления ---
        // Курс в градусах: направление (x, z) разворачивается в yaw по той же
        // формуле, что и у ванильного getLookAngle(): yaw = atan2(-x, z).
        float targetYaw = (float) Math.toDegrees(Math.atan2(-target.x, target.z));
        if (!this.moveYawValid) {
            this.moveYaw = targetYaw;
            this.moveYawValid = true;
        } else {
            float diff = Mth.wrapDegrees(targetYaw - this.moveYaw);
            float step = Mth.clamp(diff,
                    -GuardianTuning.MOVE_TURN_SPEED_DEG, GuardianTuning.MOVE_TURN_SPEED_DEG);
            this.moveYaw = Mth.wrapDegrees(this.moveYaw + step);
        }
        double rad = Math.toRadians(this.moveYaw);
        double dirX = -Math.sin(rad);
        double dirZ = Math.cos(rad);

        // --- Разгон и торможение ---
        // Если страж только что стоял, первый тик даёт полную скорость: живой
        // моб отталкивается сразу, и ждать разгона нельзя — иначе отходящий
        // страж не успевает отойти от подошедшего вплотную врага (тот за это
        // время успевает подойти ещё на блок). На ходу скорость меняется плавно.
        double accel = this.moveSpeed <= 1.0E-4D ? speed : GuardianTuning.MOVE_ACCELERATION;
        double speedDiff = speed - this.moveSpeed;
        if (speedDiff > accel) {
            speedDiff = accel;
        } else if (speedDiff < -accel) {
            speedDiff = -accel;
        }
        this.moveSpeed = Math.max(0.0D, this.moveSpeed + speedDiff);

        Vec3 delta = this.getDeltaMovement();

        if (this.isInWater()) {
            // В воде вязкость каждый тик уменьшает скорость, поэтому задаём её заново —
            // иначе страж постепенно «замирает» на месте. Небольшая тяга вверх держит
            // его на поверхности, а не на дне.
            this.setDeltaMovement(dirX * this.moveSpeed * GuardianTuning.SWIM_SPEED_FACTOR,
                    Math.max(delta.y, GuardianTuning.SWIM_BUOYANCY),
                    dirZ * this.moveSpeed * GuardianTuning.SWIM_SPEED_FACTOR);
            return;
        }

        this.setDeltaMovement(dirX * this.moveSpeed, delta.y, dirZ * this.moveSpeed);
    }

    /** Гасит горизонтальное движение, оставляя вертикальную скорость. */
    public void stopHorizontalMovement() {
        this.moveSpeed = 0.0D;
        if (this.isInWater()) {
            return;
        }
        Vec3 delta = this.getDeltaMovement();
        this.setDeltaMovement(0.0D, delta.y, 0.0D);
    }

    /** Текущий сглаженный курс движения (градусы) — для диагностики. */
    public float getMoveYaw() {
        return this.moveYaw;
    }

    /** Текущая горизонтальная скорость — для диагностики. */
    public double getMoveSpeed() {
        return this.moveSpeed;
    }

    // ==================== Навигация ====================

    private double navTargetX = 0.0D;
    private double navTargetY = 0.0D;
    private double navTargetZ = 0.0D;
    private int navRetargetTimer = 0;

    /**
     * Задаёт точку, к которой нужно дойти.
     *
     * <p>Маршрут строит ванильная навигация: она обходит стены, ищет выход из ямы
     * и умеет подъёмы на блок. Раньше обход был самодельным (проверка одного блока
     * впереди плюс семь фиксированных углов), из-за чего страж «скользил вдоль стен»
     * и колебался между направлениями в яме.
     *
     * <p>Путь пересчитывается не каждый тик — это дорого. Только когда маршрут
     * закончился, цель сместилась заметно или подошло время планового обновления.
     */
    public void navigateTo(double x, double y, double z, double speed) {
        if (this.navRetargetTimer > 0) {
            this.navRetargetTimer--;
        }

        double dx = x - this.navTargetX;
        double dz = z - this.navTargetZ;
        // Порог намеренно большой. При отступлении точка отхода привязана к
        // движущемуся врагу, и при частом пересчёте маршрута менялись узлы пути —
        // направление шага дёргалось, и это читалось как тряска. Маршрут
        // пересчитываем по таймеру, а быстрое смещение цели учитываем только
        // тогда, когда цель ушла совсем.
        boolean targetMoved = (dx * dx + dz * dz) > 25.0D;

        if (this.getNavigation().isDone() || this.navRetargetTimer <= 0 || targetMoved) {
            this.getNavigation().moveTo(x, y, z, speed);
            this.navTargetX = x;
            this.navTargetY = y;
            this.navTargetZ = z;
            this.navRetargetTimer = GuardianTuning.NAV_RETARGET_INTERVAL;
        }
    }

    /** Сбрасывает привязку к навигационной цели — следующий вызов построит путь заново. */
    public void resetNavigationTarget() {
        this.navRetargetTimer = 0;
        this.getNavigation().stop();
    }

    /**
     * Двигает стража по маршруту навигации.
     *
     * <p>Направление берётся из {@code MoveControl} — это следующий узел маршрута,
     * который выставила навигация. Скорость пишется в мировых координатах, поэтому
     * курс тела (который держим развёрнутым к врагу) не искажает направление.
     *
     * <p>Навигация при этом не управляет скоростью: {@code MoveControl} сбрасывает
     * своё состояние в том же тике, поэтому вклад в движение от навигации равен нулю.
     *
     * @param fallbackDir прямое направление на случай, когда пути нет (например, в воде)
     * @return true, если страж двигается по маршруту
     */
    public boolean moveAlongPath(double speed, Vec3 fallbackDir) {
        if (this.isInWater()) {
            // Наземная навигация в воде не работает — плывём напрямую.
            this.moveDirectly(fallbackDir, speed);
            return true;
        }

        MoveControl moveControl = this.getMoveControl();
        double wantedX = moveControl.getWantedX();
        double wantedZ = moveControl.getWantedZ();

        Vec3 dir = new Vec3(wantedX - this.getX(), 0.0D, wantedZ - this.getZ());
        if (dir.lengthSqr() < 1.0E-4D) {
            // Маршрута нет — идти некуда, страж стоит на месте.
            return false;
        }

        this.moveDirectly(dir.normalize(), speed);
        return true;
    }





    /**
     * Подбирает направление, в котором можно двигаться: если «прямо» перекрыто,
     * пробует обойти препятствие под углами. Это убирает «зависание» в стене.
     */




    /**
     * Разворачивает тело и голову к цели. Страж всегда смотрит на врага лицом,
     * даже когда от него отступает.
     */
    public void faceTarget(LivingEntity target) {
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        double dy = target.getEyeY() - this.getEyeY();

        // --- Курс тела ---
        // Правильная формула (см. yawToTarget): с перепутанными аргументами
        // тело доворачивалось зеркально — мимо цели.
        float desiredYaw = yawToTarget(dx, dz);

        // Доворот плавный, как у ванильных мобов: доля от расхождения за тик
        // (экспоненциальное сглаживание) плюс жёсткий предел скорости.
        // Экспоненциальная часть даёт естественное замедление к цели, а предел
        // не даёт прыгнуть на 180° за один кадр.
        float diff = Mth.wrapDegrees(desiredYaw - this.bodyYaw);
        if (Math.abs(diff) <= GuardianTuning.FACE_SNAP_ANGLE) {
            // Доворачиваем точно и дальше не трогаем. Иначе угол вечно
            // приближается к цели и пакет поворота уходит каждый тик зря.
            this.bodyYaw = desiredYaw;
        } else {
            float step = Mth.clamp(diff * GuardianTuning.FACE_SMOOTHING,
                    -GuardianTuning.FACE_TURN_SPEED_DEG, GuardianTuning.FACE_TURN_SPEED_DEG);
            this.bodyYaw = Mth.wrapDegrees(this.bodyYaw + step);
        }

        // --- Курс головы ---
        // Голова следит за целью ОТДЕЛЬНО от корпуса и доворачивается быстрее
        // него — как у живого моба, который поворачивает голову, не разворачивая
        // всего тела. Но, как и в ванили, она не может отойти от корпуса дальше
        // чем на 75° (LookControl.clampHeadRotationToBody) — иначе гола
        // дёргалась бы через пределы.
        float desiredHeadYaw = yawToTarget(dx, dz);
        float headDiff = Mth.wrapDegrees(desiredHeadYaw - this.headYaw);
        if (Math.abs(headDiff) <= GuardianTuning.FACE_SNAP_ANGLE) {
            this.headYaw = desiredHeadYaw;
        } else {
            float headStep = Mth.clamp(headDiff * GuardianTuning.FACE_SMOOTHING,
                    -GuardianTuning.FACE_HEAD_SPEED_DEG, GuardianTuning.FACE_HEAD_SPEED_DEG);
            this.headYaw = Mth.wrapDegrees(this.headYaw + headStep);
        }
        float headOffset = Mth.clamp(Mth.wrapDegrees(this.headYaw - this.bodyYaw),
                -GuardianTuning.MAX_HEAD_YAW_OFFSET, GuardianTuning.MAX_HEAD_YAW_OFFSET);
        this.headYaw = Mth.wrapDegrees(this.bodyYaw + headOffset);

        // --- Наклон головы ---
        // Считаем вручную: LookControl каждый тик сбрасывает его в ноль и тут же
        // наклоняет голову заново. Плюс ограничиваем наклон, чтобы цель ниже
        // стража не заставляла страж разглядывать пол.
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float desiredPitch = 0.0F;
        if (horizontal > 1.0E-4D || Math.abs(dy) > 1.0E-4D) {
            desiredPitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        }
        desiredPitch = Mth.clamp(desiredPitch, -GuardianTuning.MAX_HEAD_PITCH, GuardianTuning.MAX_HEAD_PITCH);
        this.headPitch = Mth.lerp(GuardianTuning.FACE_PITCH_SPEED, this.headPitch, desiredPitch);

        this.setYRot(this.bodyYaw);
        this.setYHeadRot(this.headYaw);
        this.setXRot(this.headPitch);
        this.yBodyRot = this.bodyYaw;

        // Наклон головы клиент обнуляет каждый тик (LookControl), поэтому
        // его дублируем в SynchedEntityData. Курс тела передавать не нужно:
        // он идёт ванильным пакетом поворота и доступен клиенту как getYRot().
        this.entityData.set(DATA_HEAD_PITCH, this.headPitch);
        this.entityData.set(DATA_HEAD_YAW, this.headYaw);

        // Без hasImpulse пакет с поворотом не уходит клиенту (сервер шлёт его
        // только при движении или раз в 20 тиков).
        this.hasImpulse = true;
    }

    // ==================== Еда ====================

    public boolean isEating() {
        return this.eating;
    }

    public void setEating(boolean eating) {
        this.eating = eating;
    }

    public int getBreadCooldown() {
        return this.breadCooldown;
    }

    public void setBreadCooldown(int cooldown) {
        this.breadCooldown = cooldown;
    }

    // ==================== Звук и атака ====================

    @Override
    public void swing(net.minecraft.world.InteractionHand hand) {
        super.swing(hand);
        if (hand == net.minecraft.world.InteractionHand.MAIN_HAND
                && this.getCombatState() == CombatState.MELEE) {
            this.playSwordSwingSound();
        }
    }

    private void playSwordSwingSound() {
        if (this.swingSoundCooldown > 0) return;
        this.swingSoundCooldown = 8;
        this.playSound(SoundEvents.IRON_GOLEM_ATTACK, 0.6F,
                1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
    }

    @Override
    public boolean doHurtTarget(Entity entityIn) {
        boolean flag = super.doHurtTarget(entityIn);
        if (flag && this.getCombatState() == CombatState.MELEE) {
            this.playSound(SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.0F, 1.0F);
        }
        return flag;
    }

    // ==================== Сохранение ====================

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("CrossbowCharge", this.crossbowChargeTime);
        tag.putInt("HelpScreamCooldown", this.helpScreamCooldown);
        tag.putInt("BreadCooldown", this.breadCooldown);
        tag.putBoolean("Eating", this.eating);
        tag.putInt("RallyTimer", this.rallyTimer);
        tag.putBoolean("Standoff", this.standoffReached);
        if (this.standoffAnchor != null) {
            tag.putBoolean("HasStandoffAnchor", true);
            tag.putDouble("StandoffX", this.standoffAnchor.x);
            tag.putDouble("StandoffY", this.standoffAnchor.y);
            tag.putDouble("StandoffZ", this.standoffAnchor.z);
        } else {
            tag.putBoolean("HasStandoffAnchor", false);
        }

        if (this.rallyPoint != null) {
            tag.putBoolean("HasRally", true);
            tag.putInt("RallyX", this.rallyPoint.getX());
            tag.putInt("RallyY", this.rallyPoint.getY());
            tag.putInt("RallyZ", this.rallyPoint.getZ());
        } else {
            tag.putBoolean("HasRally", false);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.crossbowChargeTime = tag.getInt("CrossbowCharge");
        this.helpScreamCooldown = tag.getInt("HelpScreamCooldown");
        this.breadCooldown = tag.getInt("BreadCooldown");
        this.eating = tag.getBoolean("Eating");
        this.rallyTimer = tag.getInt("RallyTimer");
        this.standoffReached = tag.getBoolean("Standoff");
        if (tag.getBoolean("HasStandoffAnchor")) {
            this.standoffAnchor = new Vec3(tag.getDouble("StandoffX"),
                    tag.getDouble("StandoffY"), tag.getDouble("StandoffZ"));
        } else {
            this.standoffAnchor = null;
        }

        if (tag.getBoolean("HasRally")) {
            this.rallyPoint = new BlockPos(tag.getInt("RallyX"), tag.getInt("RallyY"), tag.getInt("RallyZ"));
        } else {
            this.rallyPoint = null;
        }
    }

    // ==================== Характеристики ====================

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FOLLOW_RANGE, 16.0D)
                .add(Attributes.ATTACK_DAMAGE, 3.0D);
    }

    @SuppressWarnings("deprecation")
    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, net.minecraft.world.DifficultyInstance difficulty,
                                        MobSpawnType spawnType, @Nullable SpawnGroupData spawnGroupData,
                                        @Nullable CompoundTag dataTag) {
        this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData, dataTag);
    }
}
