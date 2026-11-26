package com.phian404.survivalpoints.entity;

import com.phian404.survivalpoints.entity.role.SurvivorRole;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;

import com.phian404.survivalpoints.entity.ai.GuardianEatBreadGoal;
import com.phian404.survivalpoints.entity.ai.GuardianLookAtPlayerGoal;
import com.phian404.survivalpoints.entity.ai.GuardianMeleeAttackGoal;
import com.phian404.survivalpoints.entity.ai.GuardianRangedAttackGoal;
import com.phian404.survivalpoints.entity.ai.GuardianRetreatGoal;
import com.phian404.survivalpoints.entity.ai.GuardianTargetGoal;
import com.phian404.survivalpoints.entity.ai.CampRestrictedRandomStrollGoal;
import com.phian404.survivalpoints.init.GuardianFeatureFlags;
import com.phian404.survivalpoints.init.GuardianHangDetector;
import com.phian404.survivalpoints.init.GuardianSnapshot;
import com.phian404.survivalpoints.init.GuardianTrace;
import com.phian404.survivalpoints.init.GuardianTuning;
import com.phian404.survivalpoints.init.ModEntityEvents;
import com.phian404.survivalpoints.init.PerfDiagnostics;
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
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Страж — гуманоидный защитник.
 *
 * <p>Архитектура: <b>боевое поведение определяют голы</b> ({@code GoalSelector}) —
 * они единственный источник истины. Каждый боевой гол сам проверяет живую цель
 * и дистанцию, поэтому одновременно перемещением управляет ровно один гол
 * (флаг {@code MOVE} у них один). Отдельного автомата состояний больше нет:
 * {@link GuardianState} остался только диагностическим описанием для {@code /sp_guard}.
 * Раньше два «мозга» рассинхронизировались, и это давало «статую с хлебом» и
 * задержку переключения в тик.
 *
 * <p>Перемещение в бою: <b>маршрут строит навигация</b> (она обходит стены, ищет
 * выход из ямы и умеет подъёмы на блок), а <b>скорость считает сам страж</b> — в
 * мировых координатах, от следующего узла маршрута. Благодаря этому тело можно
 * держать развёрнутым к врагу, даже когда маршрут уводит в сторону.
 */
public class BaseGuardianEntity extends BaseSurvivorEntity {

    public static EntityType<BaseGuardianEntity> TYPE;

    private static final EntityDataAccessor<Boolean> DATA_CHARGING =
            SynchedEntityData.defineId(BaseGuardianEntity.class, EntityDataSerializers.BOOLEAN);

    /**
     * Диагностический кэш фактического состояния.
     *
     * <p>Раньше это был автомат состояний, который САМ решал, что делать («второй
     * мозг» рядом с {@code GoalSelector}). Теперь значение только описывает уже
     * случившееся: боевое поведение определяют голы, а поле заполняется из них
     * один раз в тик для {@code /sp_guard}. Логика его не читает.
     */
    private static final EntityDataAccessor<Byte> DATA_AI_STATE =
            SynchedEntityData.defineId(BaseGuardianEntity.class, EntityDataSerializers.BYTE);

    /**
     * Синхронизированный признак «страж развёрнут к цели».
     *
     * <p>Рендеру нужно знать, подставлять ли серверный курс тела. Из ванильного
     * состояния для этого годится только {@code isAggressive()} — но в дальнем
     * бою он {@code false}, поэтому отдельный признак.
     */
    private static final EntityDataAccessor<Boolean> DATA_FACING_TARGET =
            SynchedEntityData.defineId(BaseGuardianEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<Float> DATA_CROSSBOW_CHARGE_PROGRESS =
            SynchedEntityData.defineId(BaseGuardianEntity.class, EntityDataSerializers.FLOAT);

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

    /** Наш MoveControl: шаг относительно курса тела + удержание курса тела. */
    private final GuardianMoveControl guardianMoveControl;

    /**
     * Развёрнуто ли тело на врага.
     *
     * <p>В бою — да: тело смотрит на врага, а движение идёт шагом назад.
     * При ходьбе по маршруту — нет: тело ведёт маршрут, на цель смотрит голова.
     */
    private boolean bodyFacesTarget = true;


    /**
     * Диагностическое описание того, что страж делает прямо сейчас.
     *
     * <p><b>Не управляет поведением.</b> Раньше это был автомат состояний, и
     * каждый боевой гол работал «только в своём состоянии». Теперь решения
     * принимают сами голы (живая цель + дистанция), а это значение
     * восстанавливается из факта: активен ли меч-гол, паникует ли страж.
     * Нужно только для {@code /sp_guard}.
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

    private int helpScreamCooldown = 0;

    /**
     * Сколько тиков после полученного урона нельзя рулить движением.
     *
     * <p>Ванильный отброс подбрасывает стража вверх и толкает в сторону. Наша
     * система движения каждый тик перезаписывает горизонтальную скорость —
     * горизонтальная часть отброса гасилась, а вертикальный подброс оставался,
     * и страж «зависал» в воздухе. В эти несколько тиков мы не рулим движением
     * и даём отбросу отработать как положено.
     */
    private int damageStunTicks = 0;

    /**
     * Сколько тиков страж находится в воздухе, не падая.
     *
     * <p>Нужен детектору «зависания»: если страж висит в воздухе и не падает
     * дольше {@link GuardianTuning#HANG_DETECT_TICKS} тиков — это тот самый
     * редкий случай, который не удалось воспроизвести вручную.
     */
    private int airborneTicks = 0;

    /** Отчёт о зависании уже отправлен — чтобы не спамить. */
    private boolean hangReported = false;

    // ==================== Диагностика подскоков и погружения ====================
    // Поля нужны детектору GuardianHangDetector: он ловит НЕ «вишу и не падаю»
    // (старый симптом), а два наблюдаемых в игре случая:
    //   1) серия коротких подскоков в углу — airborne → grounded → airborne;
    //   2) погружение в воде — isInWater() и вертикальная скорость вниз.

    /** Был ли страж в воздухе в прошлом тике (для распознавания приземления). */
    private boolean wasAirborne = false;

    /**
     * Операция MoveControl в момент отрыва от земли.
     *
     * <p>Нужна для диагностики подскоков: к моменту приземления операция уже
     * {@code WAIT} (ваниль снимает {@code JUMPING} по {@code onGround()}), поэтому
     * по отчёту нельзя понять, что именно спровоцировало прыжок. Запоминаем
     * состояние на отрыве.
     */
    private String takeoffOperation = "";

    /** Тик последнего приземления после полёта (для окна серии подскоков). */
    private int lastLandingTick = -1000;

    /** Сколько подскоков в текущей серии. */
    private int bounceCount = 0;

    /** Серия подскоков уже отчиталась — не спамим лог. */
    private boolean bounceReported = false;

    /** Сколько тиков страж непрерывно погружается в воду. */
    private int sinkingTicks = 0;

    /** Погружение уже отчиталось — не спамим лог. */
    private boolean sinkingReported = false;

    // ==================== Диагностика «невидимого полублока» ====================
    // Владелец описал дефект как «встаёт на невидимый полублок, затем отвисает
    // и падает». Дословно это значит: onGround() переключается, а высота y при
    // этом НЕ меняется — то есть настоящего полёта не было.
    //
    // Прежний детектор подскоков такое не видел: он считал приземления после
    // реального полёта, а здесь полёта нет вовсе.

    /** Значение onGround в прошлом тике — чтобы заметить переключение. */
    private boolean prevGroundedDiag = true;

    /** y в момент последнего переключения onGround. */
    private double lastGroundToggleY = 0.0D;

    /** Тик последнего переключения onGround. */
    private int lastGroundToggleTick = -1000;

    /** Сколько переключений onGround в текущей серии. */
    private int floatHangCount = 0;

    /**
     * Максимальное отклонение по высоте за текущую «воздушную» фазу.
     *
     * <p>Ключевой замер: прыжок даёт подъём ~0.42 блока, а «невидимый
     * полублок» — почти ноль. Поэтому настоящий полёт и зависание различаются
     * именно этой величиной, а не фактом переключения onGround.
     */
    private double airborneYSpan = 0.0D;

    /** Серия «невидимого полублока» уже отчиталась. */
    private boolean floatHangReported = false;

    // ==================== Диагностика отбрасывания (D1) ====================
    //
    // Разбор следа движения показал настоящую причину «зависания в воздухе»:
    //   - страж поднялся с -62.00 до -61.04, то есть ПОЧТИ НА БЛОК (0.96);
    //   - дуга ровная, симметричная: 0.275 → 0.191 → 0.109 → 0.028 → -0.050 …
    //   - флаг jumping всё это время false.
    // Это не прыжок (прыжок даёт подъём 0.42 и начинается с jumping=true),
    // а отбрасывание от удара: ванильный knockback даёт vy ≈ 0.4, что и
    // поднимает моба почти на блок.
    //
    // Наблюдаемая «полусекундная задержка» — это время полёта такой дуги.
    // Отдельно ловим аномалию: если vy у вершины замер на > KNOCKBACK_HANG_TICKS
    // тиков, значит вертикаль кто-то гасит.

    /** Тиков подряд у вершины дуги (|vy| мала, но страж в воздухе и высоко). */
    private int knockbackHangTicks = 0;

    /** Максимальная высота текущей воздушной фазы (для отличия от шага). */
    private double airPhaseMaxY = 0.0D;

    /** Высота, с которой началась текущая воздушная фаза. */
    private double airPhaseStartY = 0.0D;

    /** Фаза «отбрасывание» уже отчиталась — не спамим лог. */
    private boolean knockbackReported = false;

    // След последних тиков движения (замер M1).
    //
    // Хранится ПРИМИТИВАМИ, а не строками: снимок берётся каждый тик у каждого
    // стража, и строковая сборка на каждый тик — это постоянный мусор для GC.
    // По логу нагрузки (D4) наш код и без того выходил за бюджет, поэтому
    // диагностика не должна добавлять аллокаций в горячий путь. Строка
    // собирается один раз — только в момент отчёта.

    private final double[] trailY = new double[GuardianTuning.MOTION_TRAIL_TICKS];
    private final double[] trailVy = new double[GuardianTuning.MOTION_TRAIL_TICKS];
    private final float[] trailZza = new float[GuardianTuning.MOTION_TRAIL_TICKS];
    private final float[] trailXxa = new float[GuardianTuning.MOTION_TRAIL_TICKS];
    private final boolean[] trailGrounded = new boolean[GuardianTuning.MOTION_TRAIL_TICKS];
    private final boolean[] trailJumping = new boolean[GuardianTuning.MOTION_TRAIL_TICKS];
    private final int[] trailOp = new int[GuardianTuning.MOTION_TRAIL_TICKS];
    /**
     * Тики «ступора после урона» в этом сэмпле — замер M8.
     *
     * <p>След показал подъём почти на блок с баллистической дугой, но флаг
     * {@code jumping} при этом всегда {@code false}. Значит это не прыжок, а
     * отбрасывание от удара: ванильный {@code knockback} даёт начальную
     * вертикальную скорость 0.4, что как раз поднимает на ~1 блок.
     * Столбец {@code stun} подтверждает или опровергает это числами.
     */
    private final int[] trailStun = new int[GuardianTuning.MOTION_TRAIL_TICKS];

    /** Горизонтальное столкновение в этом сэмпле. */
    private final boolean[] trailHcoll = new boolean[GuardianTuning.MOTION_TRAIL_TICKS];

    /** Куда писать следующий снимок. */
    private int trailHead = 0;

    /** Сколько снимков накоплено (до {@link GuardianTuning#MOTION_TRAIL_TICKS}). */
    private int trailSize = 0;

    // ==================== Диагностика источника прыжка ====================

    /**
     * Кто последний выставил {@code jumping = true} (класс.метод из стека).
     *
     * <p>Нужно для разбора «отрыва при op=WAIT»: MoveControl в WAIT прыжок не
     * заказывает, значит флаг выставил кто-то другой — подозреваемый
     * {@code FloatGoal}.
     */
    private String jumpSource = "";

    /** Сколько раз за жизнь страж отрывался от земли. */
    private int jumpCount = 0;

    /** Тик последнего отрыва. */
    private int lastJumpTick = -1000;

    /** Сколько тиков подряд {@code jumping} остаётся true. */
    private int jumpingStreak = 0;

    /** Серия «jumping не снимается» уже отчиталась. */
    private boolean jumpingStreakReported = false;

    /** Тик последней записи «сброс навигации» — ограничитель частоты. */
    private int lastNavResetLogTick = -1000;

    /** Ограничитель частоты записи источника прыжка. */
    private int jumpSourceCooldown = 0;

    /**
     * Идёт ли сейчас отступление.
     *
     * <p>Живёт в сущности, а не в голу: гол дальнего боя перезапускается при
     * каждом переходе MELEE ↔ RANGED, и флаг в голе забывался бы — страж
     * дёргался: отошёл на метр, вернулся к рубежу, снова отошёл.
     */
    private boolean retreating = false;

    private int swingSoundCooldown = 0;
    private int breadCooldown = 0;
    private boolean eating = false;

    /**
     * Сколько тиков подряд цель не видна.
     *
     * <p>Защита от недостижимой цели: зомби под землёй или за глухой стеной не
     * виден, поэтому страж не может стрелять, но цель не теряет — дистанция в
     * пределах {@link GuardianTuning#TARGET_DROP_DISTANCE}. Счётчик доходит до
     * {@link GuardianTuning#NO_LINE_OF_SIGHT_TICKS} и цель сбрасывается, иначе
     * страж навсегда остаётся «статуей» с бесконечным зовом на помощь.
     */
    private int noLineOfSightTicks = 0;

    /**
     * Кого страж уже признал недостижимым: UUID → тик, с которого цель можно
     * брать снова.
     *
     * <p>Без этой памяти сброс не работает: поиск цели идёт по расстоянию, и тот
     * же монстр назначается обратно следующим сканом — цикл «сбросил → назначил»
     * каждые {@link GuardianTuning#NO_LINE_OF_SIGHT_TICKS} тиков, бой не
     * прекращается и хлеб недоступен.
     */
    private final Map<UUID, Integer> unreachableTargets = new HashMap<>();

    /**
     * Сколько тиков осталось «пульсу» еды.
     *
     * <p>Флаг {@code eating} верен, только пока активен гол еды. Гол каждый
     * свой тик продлевает пульс ({@link #pulseEating()}). Если подтверждения
     * нет дольше {@link GuardianTuning#EAT_PULSE_TIMEOUT_TICKS} тиков — флаг
     * гаснет сам, и страж возвращается к оружию.
     *
     * <p>Без этого флаг залипал навсегда: {@code GuardianEatBreadGoal.canUse()}
     * требует {@code !isEating()}, значит гол больше не запускался, а
     * {@code stop()} — единственное место сброса — уже не вызывался.
     * Именно это наблюдалось как «страж застыл с хлебом в руках и не
     * реагирует на врагов».
     */
    private int eatPulse = 0;

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
        this.setRoleInternal(SurvivorRole.GUARDIAN);
        this.setCanPickUpLoot(true);

        // Свой MoveControl: шагает относительно курса тела (ванильный strafe) и
        // не даёт ванильному MoveControl доворачивать корпус к маршруту, когда
        // страж в бою должен смотреть на врага.
        this.guardianMoveControl = new GuardianMoveControl(this);
        this.moveControl = this.guardianMoveControl;
    }

    // ==================== Регистрация ====================

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_CHARGING, false);
        this.entityData.define(DATA_CROSSBOW_CHARGE_PROGRESS, 0.0F);
        this.entityData.define(DATA_AI_STATE, (byte) GuardianState.IDLE.ordinal());
        this.entityData.define(DATA_FACING_TARGET, false);
        this.entityData.define(DATA_HEAD_PITCH, 0.0F);
        this.entityData.define(DATA_HEAD_YAW, 0.0F);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();

        // ВАНИЛЬНОЕ ПЛАВАНИЕ — первый по важности (FloatGoal).
        //
        // Без него страж тонет: по логу видно `sink=10, vy=-0.005, state=IDLE,
        // goal=- , onGround=true` — он лежит на дне, никакой гол движения не
        // работает, поэтому всплытие не запускается. Ванильный FloatGoal берёт
        // флаг JUMP и на 80 % тиков зовёт getJumpControl().jump(); это ровно то,
        // что делают все ванильные мобы (зомби, скелеты).
        //
        // Регистрируем ПЕРВЫМ (priority 0 по флагу JUMP): флаг JUMP больше никто
        // не объявляет, поэтому конфликтов с боевыми голами нет.
        this.goalSelector.addGoal(0, new FloatGoal(this));

        // ПОРЯДОК РЕГИСТРАЦИИ — ЭТО ПРИОРИТЕТ (К1): обход идёт по порядку
        // регистрации, а не по числам priority (числа сравниваются только при
        // вытеснении). Флаг MOVE у боевых голов один (К2), поэтому одновременно
        // перемещением управляет ровно один гол — первый подходящий.
        //
        // Паника → ближний бой → дальний бой → еда → прогулка → взгляд.
        this.goalSelector.addGoal(0, new GuardianRetreatGoal(this));       // паника
        this.goalSelector.addGoal(1, new GuardianMeleeAttackGoal(this));   // меч
        this.goalSelector.addGoal(2, new GuardianRangedAttackGoal(this));  // арбалет
        this.goalSelector.addGoal(3, new GuardianEatBreadGoal(this));      // еда
        this.goalSelector.addGoal(4, new CampRestrictedRandomStrollGoal(this, 1.0D));     // покой

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

    /**
     * Диагностическое описание текущего состояния.
     *
     * <p>Только чтение для {@code /sp_guard}. Поведение на нём не строится —
     * источник истины для боевых решений это голы и живая цель.
     */
    public GuardianState getAIState() {
        byte id = this.entityData.get(DATA_AI_STATE);
        GuardianState[] values = GuardianState.values();
        if (id < 0 || id >= values.length) {
            return GuardianState.IDLE;
        }
        return values[id];
    }

    /**
     * Паникует ли страж: мало ХП и есть живая цель.
     *
     * <p>Раньше это было состояние автомата. Теперь — вычисляемый предикат:
     * он не может «залипнуть» и не требует синхронизации с порядком тиков.
     */
    public boolean isPanicking() {
        if (!GuardianFeatureFlags.isEnabled(GuardianFeatureFlags.Flag.PANIC)) {
            return false;
        }
        LivingEntity target = this.getTarget();
        return target != null && target.isAlive()
                && this.getHealth() < this.getMaxHealth() * GuardianTuning.PANIC_HP_RATIO;
    }

    /** Активен ли гол ближнего боя — он владеет мечом и подходом вплотную. */
    public boolean isMeleeActive() {
        return this.goalSelector.getRunningGoals()
                .anyMatch(wrapped -> wrapped.getGoal() instanceof GuardianMeleeAttackGoal);
    }

    /** Активен ли любой из боевых голов (меч, арбалет, отступление). */
    public boolean isCombatGoalActive() {
        return this.goalSelector.getRunningGoals()
                .anyMatch(wrapped -> {
                    Goal goal = wrapped.getGoal();
                    return goal instanceof GuardianMeleeAttackGoal
                            || goal instanceof GuardianRangedAttackGoal
                            || goal instanceof GuardianRetreatGoal;
                });
    }

    /**
     * Обновляет диагностический кэш состояния из фактического положения дел.
     *
     * <p>Вычисление идёт <b>из голы</b>, а не наоборот: если активен меч-гол —
     * состояние MELEE, если паника — PANIC, иначе RANGED либо IDLE. Раньше было
     * наоборот (голы спрашивали состояние), и это давало «второй мозг» с
     * задержкой в тик (К3/К4).
     */
    private void updateStateCache() {
        GuardianState next;
        LivingEntity target = this.getTarget();
        if (this.isEating() || target == null || !target.isAlive()) {
            next = GuardianState.IDLE;
        } else if (this.isPanicking()) {
            next = GuardianState.PANIC;
        } else if (this.isMeleeActive()) {
            next = GuardianState.MELEE;
        } else {
            next = GuardianState.RANGED;
        }

        byte id = (byte) next.ordinal();
        if (this.entityData.get(DATA_AI_STATE) != id) {
            GuardianTrace.event(this, "state -> " + next);
            this.entityData.set(DATA_AI_STATE, id);
        }

        // Синхронный флаг разворота — для рендера. isAggressive() тут не годится:
        // в дальнем бою он false, а тело всё равно должно смотреть на цель.
        boolean facing = this.shouldFaceTarget();
        if (this.entityData.get(DATA_FACING_TARGET) != facing) {
            this.entityData.set(DATA_FACING_TARGET, facing);
        }
    }

    /** Развёрнут ли страж к цели (синхронизировано; читается рендером). */
    public boolean isFacingTarget() {
        return this.entityData.get(DATA_FACING_TARGET);
    }

    /**
     * Приводит оружие в соответствие с активным боевым голом.
     * Вызывается каждый тик — самовосстанавливается, если предмет выпал.
     */
    public void syncEquipmentToState() {
        if (this.isEating()) {
            return;
        }

        ItemStack current = this.getItemBySlot(EquipmentSlot.MAINHAND);
        if (this.isMeleeActive()) {
            if (!current.is(Items.IRON_SWORD)) {
                this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                GuardianTrace.event(this, "оружие: меч");
            }
        } else {
            // Арбалет — во всех остальных случаях (дальний бой, паника, покой).
            if (!current.is(Items.CROSSBOW)) {
                this.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
                GuardianTrace.event(this, "оружие: арбалет");
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
        this.updateSwingTime();
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
        // Снимаем метки «недостижим» с тех, кто снова виден или подошёл вплотную,
        // ДО выбора цели — иначе подошедший враг остался бы проигнорированным.
        this.tickUnreachableTargets();
        this.validateTarget();
        // Сначала кэш состояния — он описывает уже принятые голами решения
        // этого тика (голы тикали внутри super.tick() выше). Затем экипировка:
        // она зависит от того, какой боевой гол активен.
        this.updateStateCache();
        this.syncEquipmentToState();

        // Разворот тела делаем ДО выстрела: страж должен быть уже повёрнут
        // лицом к врагу в тот тик, когда из арбалета вылетает стрела.
        // Голы и навигация к этому моменту уже отработали — они тикают
        // внутри super.tick(), до нашей логики.
        this.applyFacing();

        this.updateRangedCombat();
        this.updateChargingFlag();

        // Замер M1: снимок тика в кольцевой след. Копится всегда, но никуда
        // не пишется, пока не сработает детектор — так диагностика не заливает
        // лог и не создаёт мусор для GC (см. D4 — нагрузка и без того высокая).
        this.recordMotionSample();

        // Детектор «зависания в воздухе»: ловим редкий случай, когда страж
        // остаётся висеть после отбрасывания.
        GuardianHangDetector.check(this);

        // В покое голова держится строго ровно. LookControl каждый тик сбрасывает
        // наклон в ноль, но если активен LookAtPlayerGoal, он тут же снова наклоняет
        // голову к игроку. Гасим наклон принудительно — в покое страж смотрит перед собой.
        // Нет активного боевого гола — страж в покое, наклон гасим.
        if (!this.isCombatGoalActive()) {
            this.setXRot(0.0F);
        }
    }

    /**
     * Снимок тика для замера M1 (без аллокаций и без записи в лог).
     *
     * <p>Пишет в кольцевой след всё, чего не хватало прежним отчётам:
     * высоту, вертикальную скорость, вход движения, флаг прыжка и операцию
     * {@code MoveControl}. Строка собирается только когда детектор решит
     * отчитаться.
     */
    private void recordMotionSample() {
        this.pushMotionTrail(this.onGround(), this.getY(), this.getDeltaMovement().y,
                this.zza, this.xxa, this.jumping,
                this.guardianMoveControl.getOperationOrdinal(),
                this.damageStunTicks, this.horizontalCollision);
    }

    /**
     * Перехват заказа прыжка — замер M2.
     *
     * <p>Разбор дефекта D1 упёрся в вопрос: при {@code opОтрыва=WAIT} отрыв от
     * земли даёт не {@code MoveControl} (в состоянии {@code WAIT} он прыжок не
     * заказывает). Значит флаг выставляет кто-то ещё — подозреваемый
     * {@code FloatGoal}. Чтобы не гадать, запоминаем стек в момент установки
     * флага и пишем событие.
     *
     * <p>Поведение не меняется: вызов уходит в {@code super} как обычно.
     */
    @Override
    public void setJumping(boolean jumping) {
        boolean wasJumping = this.jumping;
        super.setJumping(jumping);

        if (!jumping || wasJumping || this.level().isClientSide) {
            return;
        }

        // Определение источника — снятие стека, операция дорогая. Делаем её не
        // чаще JUMP_SOURCE_LOG_COOLDOWN тиков: важен сам факт «кто заказывает»,
        // а не каждый тик.
        if (this.jumpSourceCooldown > 0) {
            return;
        }
        this.jumpSourceCooldown = GuardianTuning.JUMP_SOURCE_LOG_COOLDOWN;
        this.jumpSource = resolveJumpSource();
        GuardianTrace.event(this, "прыжок заказан: " + this.jumpSource
                + " | op=" + this.guardianMoveControl.getOperationName()
                + " | grounded=" + this.onGround()
                + " | water=" + this.isInWater());
    }

    /**
     * Определяет, кто вызвал {@link #setJumping(boolean)}.
     *
     * <p>Идём по стеку вверх и берём первый кадр, который не относится к
     * самому перехвату. Так в логе появляется либо ванильный {@code FloatGoal},
     * либо {@code MoveControl}, либо наш собственный код.
     */
    private String resolveJumpSource() {
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : frames) {
            String cls = frame.getClassName();
            // Пропускаем сам перехват и цепочку вызова внутри иерархии сущностей:
            // ближайший «содержательный» кадр — это тот, кто РЕАЛЬНО заказал
            // прыжок (FloatGoal, MoveControl.JumpControl, наш гол и т.п.).
            if (cls.equals(BaseGuardianEntity.class.getName())
                    || cls.equals(Thread.class.getName())
                    || cls.equals("net.minecraft.world.entity.Entity")
                    || cls.equals("net.minecraft.world.entity.LivingEntity")
                    || cls.equals("net.minecraft.world.entity.Mob")
                    || cls.equals("net.minecraft.world.entity.PathfinderMob")) {
                continue;
            }
            String simple = cls.substring(cls.lastIndexOf('.') + 1);
            return simple + "." + frame.getMethodName();
        }
        return "неизвестно";
    }

    /**
     * Должен ли страж разворачиваться к цели.
     *
     * <p>В режиме сбора (союзник идёт к точке) разворот запрещён: навигация
     * поворачивает тело по направлению пути, и принудительный разворот
     * сломает направление движения.
     */
    public boolean shouldFaceTarget() {
        // Разворот — пока работает боевой гол (или союзник идёт на точку сбора).
        // Раньше проверялось состояние автомата; теперь — факт активности гола.
        if (!this.isCombatGoalActive() && !this.hasRallyPoint()) {
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

    private void applyFacing() {
        if (!this.shouldFaceTarget()) {
            return;
        }
        if (this.isMeleeActive()) {
            // A/B-тест: кастомный доповорот корпуса и ограничение головы отключены.
            // Направление корпуса определяется ванильной системой движения,
            // взгляд — ванильным LookControl. Синхронизируем только фактическое
            // положение головы для рендера.
            this.entityData.set(DATA_HEAD_PITCH, this.getXRot());
            this.entityData.set(DATA_HEAD_YAW, this.getYHeadRot());
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
        if (this.damageStunTicks > 0) this.damageStunTicks--;
        if (this.jumpSourceCooldown > 0) this.jumpSourceCooldown--;

        // Сторож флага еды: гол еды обязан каждый тик продлевать пульс
        // (pulseEating). Если подтверждения нет — флаг гаснет сам. Срабатывает,
        // когда гол был оборван без stop(): выгрузка чанка, выход игрока из
        // мира, перезагрузка. Экипировку поправит общий syncEquipmentToState()
        // ниже в tick() — состояние к этому моменту уже пересчитано.
        if (this.eating) {
            if (this.eatPulse > 0) {
                this.eatPulse--;
            } else {
                this.setEating(false);
                GuardianTrace.event(this, "watchdog: флаг еды снят (гол еды не подтверждает)");
            }
        }


        if (this.rallyTimer > 0) {
            this.rallyTimer--;
            if (this.rallyTimer == 0) {
                this.clearRally();
            }
        }
    }

    /**
     * Сбрасываем цель, если враг мёртв, слишком далеко **или недостижим**.
     *
     * <p>Последнее — ключевое: цель за землёй/стеной не видна, поэтому страж
     * не может ни выстрелить, ни сблизиться, но формально держит её. Без этого
     * он остаётся в панике навсегда и бесконечно зовёт на помощь.
     */
    private void validateTarget() {
        LivingEntity target = this.getTarget();
        if (target == null) {
            this.noLineOfSightTicks = 0;
            return;
        }

        // Сколько тиков цель остаётся невидимой. Счётчик копится только пока
        // цель есть и жива: при сбросе цели он обнуляется ниже.
        if (target.isAlive() && this.getSensing().hasLineOfSight(target)) {
            this.noLineOfSightTicks = 0;
        } else if (target.isAlive()) {
            this.noLineOfSightTicks++;
        }

        double max = GuardianTuning.TARGET_DROP_DISTANCE * GuardianTuning.TARGET_DROP_DISTANCE;
        boolean unreachable = this.noLineOfSightTicks >= GuardianTuning.NO_LINE_OF_SIGHT_TICKS;

        if (!target.isAlive() || this.distanceToSqr(target) > max || unreachable) {
            if (unreachable) {
                // Запоминаем монстра, иначе поиск цели (он идёт только по
                // расстоянию) назначит его же следующим сканом, и цикл
                // «сбросил → назначил» не даст стражу выйти из боя.
                this.markTargetUnreachable(target);
                GuardianTrace.event(this, "цель недостижима ("
                        + this.noLineOfSightTicks + " тиков без обзора) — сбрасываю");
            }
            this.setTarget(null);
            this.clearRally();
            this.noLineOfSightTicks = 0;
            // Цель потеряна — рубеж больше не актуален, следующий бой начнётся
            // со сближения. Раньше это делал автомат состояний; после его удаления
            // сброс обязан жить там, где теряется цель (риск R4 решения R0.1).
            this.resetStandoff();
        }
    }

    /**
     * Обслужить память о недостижимых целях: снять устаревшие метки и метки с
     * тех, кто снова стал досягаемым.
     *
     * <p>Ключевое — снятие по факту: если монстр, помеченный недостижимым,
     * **сам пошёл на стража** (вышел из укрытия) или подошёл вплотную, метку
     * надо снять немедленно. Иначе страж до конца кулдауна игнорирует врага,
     * который стоит рядом и бьёт его, — то есть отходит вместо боя.
     */
    private void tickUnreachableTargets() {
        if (this.unreachableTargets.isEmpty()) {
            return;
        }

        int now = this.tickCount;
        Iterator<Map.Entry<UUID, Integer>> iterator = this.unreachableTargets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Integer> entry = iterator.next();

            // Срок истёк — метка больше не действует.
            if (entry.getValue() <= now) {
                iterator.remove();
                continue;
            }

            // Монстр снова досягаем? Проверяем только живых: мёртвых снимаем
            // вместе с истечением, отдельная ветка не нужна.
            if (!(this.level() instanceof ServerLevel serverLevel)) {
                continue;
            }
            Entity flagged = serverLevel.getEntity(entry.getKey());
            if (flagged instanceof LivingEntity living && living.isAlive()
                    && this.isReachableAgain(living)) {
                GuardianTrace.event(this, "цель снова досягаема — метка снята: "
                        + living.getType().getDescription().getString());
                iterator.remove();
            }
        }
    }

    /**
     * Стал ли помеченный недостижимым монстр снова доступен для атаки.
     *
     * <p>Два независимых признака: он снова виден, либо подошёл вплотную —
     * в упор он опасен независимо от обзора, и страж обязан реагировать.
     */
    private boolean isReachableAgain(LivingEntity flagged) {
        if (this.getSensing().hasLineOfSight(flagged)) {
            return true;
        }
        return this.distanceTo(flagged) <= GuardianTuning.MELEE_ENTER_DISTANCE;
    }

    /**
     * Запомнить монстра как недостижимого на
     * {@link GuardianTuning#UNREACHABLE_TARGET_COOLDOWN_TICKS} тиков.
     *
     * <p>Память чистится здесь же: истёкшие записи удаляются, так что карта
     * не растёт при долгой жизни стража.
     */
    private void markTargetUnreachable(LivingEntity target) {
        int now = this.tickCount;
        this.unreachableTargets.entrySet().removeIf(entry -> entry.getValue() <= now);

        if (this.unreachableTargets.size() >= GuardianTuning.UNREACHABLE_TARGETS_MAX
                && !this.unreachableTargets.containsKey(target.getUUID())) {
            // Карта переполнена: освобождаем место под новую запись, удаляя
            // запись с самым ранним сроком истечения.
            this.unreachableTargets.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .ifPresent(this.unreachableTargets::remove);
        }

        this.unreachableTargets.put(target.getUUID(),
                now + GuardianTuning.UNREACHABLE_TARGET_COOLDOWN_TICKS);
    }

    /** Считается ли монстр недостижимым прямо сейчас (для выбора цели). */
    public boolean isTargetUnreachable(LivingEntity candidate) {
        if (this.unreachableTargets.isEmpty()) {
            return false;
        }
        Integer until = this.unreachableTargets.get(candidate.getUUID());
        return until != null && until > this.tickCount;
    }

    /** Сколько монстров сейчас в памяти недостижимых (для диагностики). */
    public int getUnreachableTargetCount() {
        return this.unreachableTargets.size();
    }

    /**
     * Смена цели — новый бой, значит и обход препятствия начинается заново.
     *
     * <p>Без сброса страж мог бы унаследовать «упор» от прошлой цели и сразу
     * пойти по подобранному ранее углу, хотя перед ним уже другое окружение.
     */

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

        float chargeProgress = weapon.is(Items.CROSSBOW)
                ? Mth.clamp((float) this.crossbowChargeTime / GuardianTuning.CROSSBOW_CHARGE_TICKS, 0.0F, 1.0F)
                : 0.0F;
        if (this.entityData.get(DATA_CROSSBOW_CHARGE_PROGRESS) != chargeProgress) {
            this.entityData.set(DATA_CROSSBOW_CHARGE_PROGRESS, chargeProgress);
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
        if (!GuardianFeatureFlags.isEnabled(GuardianFeatureFlags.Flag.RANGED)) {
            this.resetChargeTime();
            return;
        }
        // Стреляет только дальний бой и паника. В ближнем бою арбалет убран,
        // поэтому проверка по оружию ниже и так отсечёт лишнее — но явный
        // выход дешевле и не даёт копить заряд «в мечевом» бою.
        if (!this.isCombatGoalActive() || (this.isMeleeActive() && !this.isPanicking())) {
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
        GuardianTrace.event(this, "выстрел");

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

    public float getCrossbowChargeProgress() {
        return this.entityData.get(DATA_CROSSBOW_CHARGE_PROGRESS);
    }

    public int getCrossbowChargeTime() {
        return this.crossbowChargeTime;
    }

    // ==================== Статистика стрельбы ====================

    /** Регистрация попадания стрелы (вызывается из обработчика урона). */
    public void onArrowHit(float amount) {
        this.arrowsHit++;
        this.damageDealt += amount;
        GuardianTrace.event(this, "попадание стрелы " + GuardianSnapshot.fmt(amount, 1));
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
    /**
     * Сколько тиков цель остаётся без прямой видимости.
     *
     * <p>Для диагностики: при приближении к
     * {@link GuardianTuning#NO_LINE_OF_SIGHT_TICKS} видно, что страж вот-вот
     * откажется от недостижимой цели.
     */
    public int getNoLineOfSightTicks() {
        return this.noLineOfSightTicks;
    }

    public void callForHelp(LivingEntity attacker) {
        if (this.level().isClientSide) return;
        if (!GuardianFeatureFlags.isEnabled(GuardianFeatureFlags.Flag.RALLY)) return;
        if (!this.canScreamForHelp()) return;
        // Зов имеет смысл, только если угроза реальна — то есть видна.
        // Иначе страж, застрявший на цели под землёй, зовёт бесконечно.
        if (attacker != null && !this.getSensing().hasLineOfSight(attacker)) return;

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
     * Шаг относительно курса тела.
     *
     * <p>Тело при этом развёрнуто на врага, поэтому шаг назад (-1, 0) — это
     * отход лицом к врагу. Движение считает ванильная физика: трение, гравитация
     * и отбрасывание работают как положено (своя перезапись скорости их гасила).
     *
     * @param forwards      1 — вперёд, -1 — назад
     * @param right         1 — вправо, -1 — влево
     * @param blocksPerTick скорость в блоках за тик
     */
    public void strafeWithSpeed(float forwards, float right, double blocksPerTick) {
        this.guardianMoveControl.setKeepYaw(true);
        this.bodyFacesTarget = true;
        this.guardianMoveControl.strafeWithSpeed(forwards, right, blocksPerTick);
    }

    /**
     * Текущая операция MoveControl — только для диагностики.
     *
     * <p>Показывает, что реально делает ваниль в этот тик: {@code STRAFE} (наш
     * шаг), {@code MOVE_TO} (маршрут), {@code JUMPING} (прыжок),
     * {@code WAIT} (стоит). По {@code JUMPING} видно, прыгает ли страж в углу.
     */
    public String getMoveControlOperation() {
        return this.guardianMoveControl.getOperationName();
    }

    /**
     * Операция, которую заказал гол в этом тике (до ванильного тика).
     *
     * <p>Это правильный ответ на вопрос «что страж хотел делать». Прежний
     * {@link #getMoveControlOperation()} читал операцию ПОСЛЕ тика, а ванильный
     * {@code MoveControl} сбрасывает её в {@code WAIT} прямо внутри своего
     * тика — поэтому в логах почти всегда был {@code WAIT}, и вывод
     * «гол ничего не заказывал» был ошибочным.
     */
    public String getMoveControlOperationBeforeTick() {
        return this.guardianMoveControl.getOperationBeforeTickName();
    }

    /** Операция, оставшаяся после ванильного тика (для сравнения с заказом). */
    public String getMoveControlOperationAfterTick() {
        return this.guardianMoveControl.getOperationAfterTickName();
    }

    /** Задан ли наш шаг (strafe) прямо сейчас — для диагностики. */
    public boolean isStrafeStepActive() {
        return this.guardianMoveControl.isOurStepActive();
    }

    /**
     * Держится ли тело развёрнутым к цели прямо сейчас — для замера M3.
     *
     * <p>Если {@code true} во время движения по маршруту, значит курс тела
     * перезаписывается на цель после {@code MoveControl}, и шаг считается не
     * по узлам пути, а «в сторону врага». Это и есть проверяемая гипотеза C1.
     */
    public boolean isBodyFacesTarget() {
        return this.bodyFacesTarget;
    }

    /**
     * Идти к точке по маршруту.
     *
     * <p>Тело ведёт маршрут (ванильная навигация обходит стены, ищет выход из
     * ямы и умеет подъёмы), на цель смотрит только голова.
     *
     * @param blocksPerTick скорость в блоках за тик
     */
    public void followPath(double x, double y, double z, double blocksPerTick) {
        this.guardianMoveControl.clearStrafe();
        this.guardianMoveControl.setKeepYaw(false);
        this.bodyFacesTarget = false;
        double modifier = blocksPerTick
                / Math.max(0.01D, this.getAttributeValue(Attributes.MOVEMENT_SPEED));
        this.navigateTo(x, y, z, modifier);
    }

    /** Стоять на месте: обнулить вход движения и сбросить маршрут. */
    public void standStill() {
        this.guardianMoveControl.clearStrafe();
        this.guardianMoveControl.setKeepYaw(true);
        this.bodyFacesTarget = true;
        this.resetNavigationTarget();
    }

    /**
     * Сбросить наш шаг (strafe), не трогая маршрут.
     *
     * <p>Нужен голам, которые ведут моба по навигации: остаток шага назад
     * от предыдущего RANGED-состояния иначе перебивает маршрут ближнего боя.
     */
    public void clearStrafeInput() {
        this.guardianMoveControl.clearStrafe();
        this.guardianMoveControl.setKeepYaw(false); // разрешить поворот к цели
        // Сбросить курс движения: иначе stale moveYaw (например, 72,6° от
        // отступления) перебивает маршрут ближнего боя.
        this.moveYawValid = false;
        this.moveYaw = 0.0F;
        this.moveRequested = false;
        this.moveSpeed = 0.0D;
    }

    /**
     * Поворачивает моба лицом к точке и записывает горизонтальную скорость
     * в мировых координатах. Направление не зависит от текущего курса моба,
     * поэтому страж может одновременно смотреть на врага и от него отходить.
     */
    public void moveDirectly(Vec3 direction, double speed) {
        if (this.isMeleeActive()) {
            return;
        }
        if (direction.lengthSqr() < 1.0E-6D) {
            return;
        }
        // Механика отключена флагом — тогда движением ведёт только ванильная
        // навигация (так сравниваем самописную физику с ванильной).
        if (!GuardianFeatureFlags.isEnabled(GuardianFeatureFlags.Flag.CUSTOM_MOVE)) {
            return;
        }
        this.moveRequested = true;

        // Только что отбросили: не рулим движением, пока страж в воздухе.
        // Иначе горизонтальная часть отброса гасится нашей перезаписью
        // скорости, а подброс остаётся — получается «зависание» в воздухе.
        if (this.damageStunTicks > 0 && !this.onGround()) {
            return;
        }

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

        // Путь перестраивается по таймеру, а не «как только закончился»: на
        // короткой дистанции маршрут строился бы каждый тик (дорого).
        if (this.navRetargetTimer <= 0 || targetMoved) {
            long start = System.nanoTime();
            this.getNavigation().moveTo(x, y, z, speed);
            PerfDiagnostics.addPathRebuild(System.nanoTime() - start);
            this.navTargetX = x;
            this.navTargetY = y;
            this.navTargetZ = z;
            this.navRetargetTimer = GuardianTuning.NAV_RETARGET_INTERVAL;
        }
    }

    /**
     * Сбрасывает привязку к навигационной цели — следующий вызов построит путь заново.
     *
     * <p>Замер M4: пишем, КТО сбрасывает маршрут. Дефект D2 выглядит как
     * «маршрут есть ({@code navDone=false}), но страж стоит». Один из
     * кандидатов — голы удержания, которые зовут {@code standStill()} каждый
     * тик, а тот сбрасывает навигацию. Без записи источника это не проверить.
     *
     * <p>Запись ограничена по частоте: информативен факт «кто сбрасывает»,
     * а не количество сбросов в секунду.
     */
    public void resetNavigationTarget() {
        this.navRetargetTimer = 0;
        this.getNavigation().stop();

        if (this.level().isClientSide || !GuardianTrace.isEnabled()) {
            return;
        }
        if (this.tickCount - this.lastNavResetLogTick < GuardianTuning.NAV_RESET_LOG_COOLDOWN) {
            return;
        }
        this.lastNavResetLogTick = this.tickCount;
        GuardianTrace.event(this, "сброс маршрута: " + resolveNavResetCaller()
                + " | op=" + this.guardianMoveControl.getOperationName()
                + " | strafe=" + this.guardianMoveControl.isOurStepActive());
    }

    /**
     * Кто вызвал {@link #resetNavigationTarget()} — по стеку.
     *
     * <p>Возвращает ближайший кадр вне этого класса: по нему видно, какой гол
     * или какое состояние сбрасывает маршрут.
     */
    private String resolveNavResetCaller() {
        StackTraceElement[] frames = Thread.currentThread().getStackTrace();
        String self = BaseGuardianEntity.class.getName();
        for (StackTraceElement frame : frames) {
            String cls = frame.getClassName();
            if (cls.equals(self) || cls.equals(Thread.class.getName())) {
                continue;
            }
            String simple = cls.substring(cls.lastIndexOf('.') + 1);
            return simple + "." + frame.getMethodName();
        }
        return "неизвестно";
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

        if (this.bodyFacesTarget) {
            // Тело развёрнуто на врага — курс применяем. При ходьбе по маршруту
            // тело ведёт маршрут, и курс трогать нельзя.
            this.setYRot(this.bodyYaw);
            this.yBodyRot = this.bodyYaw;
        }
        this.setYHeadRot(this.headYaw);
        this.setXRot(this.headPitch);

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

    /** Сколько тиков после урона нельзя рулить движением. */
    public int getDamageStunTicks() {
        return this.damageStunTicks;
    }

    /** Сколько тиков страж висит в воздухе, не падая. */
    public int getAirborneTicks() {
        return this.airborneTicks;
    }

    public void setAirborneTicks(int ticks) {
        this.airborneTicks = ticks;
    }

    // ==================== Доступ для GuardianHangDetector ====================

    /** Операция MoveControl в момент отрыва от земли — для диагностики подскоков. */
    public String getTakeoffOperation() {
        return this.takeoffOperation;
    }

    public void setTakeoffOperation(String operation) {
        this.takeoffOperation = operation;
    }

    public boolean isWasAirborne() {
        return this.wasAirborne;
    }

    public void setWasAirborne(boolean value) {
        this.wasAirborne = value;
    }

    public int getLastLandingTick() {
        return this.lastLandingTick;
    }

    public void setLastLandingTick(int tick) {
        this.lastLandingTick = tick;
    }

    public int getBounceCount() {
        return this.bounceCount;
    }

    public void setBounceCount(int count) {
        this.bounceCount = count;
    }

    public boolean isBounceReported() {
        return this.bounceReported;
    }

    public void setBounceReported(boolean reported) {
        this.bounceReported = reported;
    }

    public int getSinkingTicks() {
        return this.sinkingTicks;
    }

    public void setSinkingTicks(int ticks) {
        this.sinkingTicks = ticks;
    }

    public boolean isSinkingReported() {
        return this.sinkingReported;
    }

    public void setSinkingReported(boolean reported) {
        this.sinkingReported = reported;
    }

    /** Отчёт о зависании уже был отправлен. */
    public boolean isHangReported() {
        return this.hangReported;
    }

    public void setHangReported(boolean reported) {
        this.hangReported = reported;
    }

    // ==================== Доступ для замеров M1–M5 ====================
    // Ниже — только диагностика. Поведение стражa эти методы не меняют:
    // ни один из них не вызывается из игровой логики, кроме самого детектора.

    /** Значение onGround в прошлом тике (для поиска переключений). */
    public boolean isPrevGroundedDiag() {
        return this.prevGroundedDiag;
    }

    public void setPrevGroundedDiag(boolean value) {
        this.prevGroundedDiag = value;
    }

    /** y в момент последнего переключения onGround. */
    public double getLastGroundToggleY() {
        return this.lastGroundToggleY;
    }

    public void setLastGroundToggleY(double value) {
        this.lastGroundToggleY = value;
    }

    /** Тик последнего переключения onGround. */
    public int getLastGroundToggleTick() {
        return this.lastGroundToggleTick;
    }

    public void setLastGroundToggleTick(int tick) {
        this.lastGroundToggleTick = tick;
    }

    /** Сколько переключений onGround в текущей серии. */
    public int getFloatHangCount() {
        return this.floatHangCount;
    }

    public void setFloatHangCount(int count) {
        this.floatHangCount = count;
    }

    /** Наибольшее отклонение по высоте за текущую воздушную фазу. */
    public double getAirborneYSpan() {
        return this.airborneYSpan;
    }

    public void setAirborneYSpan(double value) {
        this.airborneYSpan = value;
    }

    /** Серия «невидимого полублока» уже отчиталась. */
    public boolean isFloatHangReported() {
        return this.floatHangReported;
    }

    public void setFloatHangReported(boolean reported) {
        this.floatHangReported = reported;
    }

    /**
     * Снимок тика для замера M1 — БЕЗ аллокаций.
     *
     * <p>Копится каждый тик, но никуда не пишется, пока не сработает детектор.
     * Так сделано специально: диагностика не должна стоить памяти в горячем
     * пути, иначе она сама станет источником лагов.
     *
     * @param grounded  {@code onGround()} в этот тик
     * @param y         высота стражa
     * @param vy        вертикальная скорость
     * @param zza       вход «вперёд/назад» ({@code MoveControl} пишет его)
     * @param xxa       вход «влево/вправо»
     * @param jumping   флаг прыжка
     * @param opOrdinal порядковый номер операции {@code MoveControl}
     */
    public void pushMotionTrail(boolean grounded, double y, double vy,
                                float zza, float xxa, boolean jumping, int opOrdinal,
                                int stunTicks, boolean horizontalCollision) {
        this.trailY[this.trailHead] = y;
        this.trailVy[this.trailHead] = vy;
        this.trailZza[this.trailHead] = zza;
        this.trailXxa[this.trailHead] = xxa;
        this.trailGrounded[this.trailHead] = grounded;
        this.trailJumping[this.trailHead] = jumping;
        this.trailOp[this.trailHead] = opOrdinal;
        this.trailStun[this.trailHead] = stunTicks;
        this.trailHcoll[this.trailHead] = horizontalCollision;
        this.trailHead = (this.trailHead + 1) % GuardianTuning.MOTION_TRAIL_TICKS;
        if (this.trailSize < GuardianTuning.MOTION_TRAIL_TICKS) {
            this.trailSize++;
        }
    }

    /** Есть ли накопленный след движения. */
    public boolean hasMotionTrail() {
        return this.trailSize > 0;
    }

    /**
     * След движения одной строкой — от самого старого снимка к самому свежему.
     *
     * <p>Формат: {@code y/vy/g/j/zza/xxa/op}. Строка собирается только в момент
     * отчёта, поэтому её стоимость не важна.
     */
    public String describeMotionTrail() {
        if (this.trailSize == 0) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        int start = (this.trailHead - this.trailSize + GuardianTuning.MOTION_TRAIL_TICKS)
                % GuardianTuning.MOTION_TRAIL_TICKS;
        for (int i = 0; i < this.trailSize; i++) {
            int idx = (start + i) % GuardianTuning.MOTION_TRAIL_TICKS;
            if (i > 0) {
                sb.append(" ; ");
            }
            sb.append(GuardianSnapshot.fmt(this.trailY[idx], 2))
                    .append('/').append(GuardianSnapshot.fmt(this.trailVy[idx], 3))
                    .append('/').append(this.trailGrounded[idx] ? "G" : "a")
                    .append('/').append(this.trailJumping[idx] ? "J" : "-")
                    .append('/').append(GuardianSnapshot.fmt(this.trailZza[idx], 2))
                    .append('/').append(GuardianSnapshot.fmt(this.trailXxa[idx], 2))
                    .append('/').append(GuardianMoveControl.operationName(this.trailOp[idx]))
                    // Замер M8: ступор после урона и упор в стену.
                    .append("/stun=").append(this.trailStun[idx])
                    .append("/hColl=").append(this.trailHcoll[idx] ? "1" : "0");
        }
        return sb.toString();
    }

    /** Очистить след движения после отчёта. */
    public void clearMotionTrail() {
        this.trailSize = 0;
        this.trailHead = 0;
    }

    /**
     * Снимок по требованию — то, что видно в момент «зависания».
     *
     * <p>Зачем отдельная команда: три прогона дали три разные картины дефекта,
     * и по усреднённой статистике причину выделить не удалось. Здесь мы
     * фиксируем ровно тот момент, когда владелец видит зависание, и получаем
     * полный след движения вместо догадок.
     *
     * <p>Пишет одну запись в лог — независимо от флага трассировки, потому что
     * это действие пользователя, а не фоновый шум.
     */
    public void dumpSnapshot(String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("СНИМОК g").append(this.getId())
                .append(" | причина=").append(reason)
                .append(" | state=").append(this.getAIState())
                .append(" | goals=").append(this.getActiveGoalNames())
                .append("\n    поз: x=").append(GuardianSnapshot.fmt(this.getX(), 3))
                .append(" y=").append(GuardianSnapshot.fmt(this.getY(), 3))
                .append(" z=").append(GuardianSnapshot.fmt(this.getZ(), 3))
                .append(" | onGround=").append(this.onGround())
                .append(" | надБлоком=").append(GuardianSnapshot.fmt(this.getHeightAboveBlock(), 3))
                .append(" | блокПод=").append(this.getBlockBelowName())
                .append("\n    скорость: dx=").append(GuardianSnapshot.fmt(this.getDeltaMovement().x, 3))
                .append(" dy=").append(GuardianSnapshot.fmt(this.getDeltaMovement().y, 3))
                .append(" dz=").append(GuardianSnapshot.fmt(this.getDeltaMovement().z, 3))
                .append(" | вода=").append(this.isInWater())
                .append(" | лава=").append(this.isInLava())
                .append(" | лиана=").append(this.onClimbable())
                .append(" | hColl=").append(this.horizontalCollision)
                .append(" | vColl=").append(this.verticalCollision)
                .append("\n    вход: zza=").append(GuardianSnapshot.fmt(this.zza, 3))
                .append(" xxa=").append(GuardianSnapshot.fmt(this.xxa, 3))
                .append(" | jumping=").append(this.jumping)
                .append(" | источникПрыжка=").append(this.jumpSource.isEmpty() ? "-" : this.jumpSource)
                .append(" | прыжков=").append(this.jumpCount)
                .append("\n    MoveControl: заказ=").append(this.getMoveControlOperationBeforeTick())
                .append(" после=").append(this.getMoveControlOperationAfterTick())
                .append(" | strafe=").append(this.isStrafeStepActive())
                .append(" | keepYaw=").append(this.guardianMoveControl.isKeepYawOn())
                .append("\n    навигация: done=").append(this.getNavigation().isDone())
                .append(" stuck=").append(this.getNavigation().isStuck())
                .append(" | конец=").append(this.describePathEnd())
                .append(" | порогНав=").append(GuardianSnapshot.fmt(
                        this.getNavigation().getMaxDistanceToWaypoint(), 2))
                // Замер M8: что РЕАЛЬНО вернул pathfinder (а не что мы с ним сделали).
                .append("\n    M8-path: ").append(this.describePathStatus())
                .append("\n    M8-types: ").append(this.describePathTypes())
                .append("\n    M8-hit: ").append(this.describePostHitDisplacement())
                .append("\n    M8-atk: ").append(this.describeAttackCounters())
                .append("\n    бой: stun=").append(this.damageStunTicks)
                .append(" | еда=").append(this.eating)
                .append(" | заряд=").append(this.crossbowChargeTime)
                .append(" | агрессия=").append(this.isAggressive())
                .append(" | паника=").append(this.isPanicking())
                .append(" | цель=").append(GuardianSnapshot.targetName(this))
                .append("\n    курс: yaw=").append(GuardianSnapshot.fmt(this.getYRot(), 1))
                .append(" bodyYaw=").append(GuardianSnapshot.fmt(this.yBodyRot, 1))
                .append(" headYaw=").append(GuardianSnapshot.fmt(this.yHeadRot, 1))
                .append(" wantYaw=").append(GuardianSnapshot.fmt(this.getDesiredYaw(), 1))
                .append("\n    след(тик y/vy/g/j/zza/xxa/op/stun/hColl): ")
                .append(this.describeMotionTrail());

        GuardianTrace.always(sb.toString());
    }

    /** Кто последний выставил {@code jumping = true}. */
    public String getJumpSource() {
        return this.jumpSource;
    }

    public void setJumpSource(String source) {
        this.jumpSource = source;
    }

    /** Сколько раз страж отрывался от земли. */
    public int getJumpCount() {
        return this.jumpCount;
    }

    public void setJumpCount(int count) {
        this.jumpCount = count;
    }

    /** Тик последнего отрыва от земли. */
    public int getLastJumpTick() {
        return this.lastJumpTick;
    }

    public void setLastJumpTick(int tick) {
        this.lastJumpTick = tick;
    }

    /** Сколько тиков подряд {@code jumping} остаётся true. */
    public int getJumpingStreak() {
        return this.jumpingStreak;
    }

    public void setJumpingStreak(int ticks) {
        this.jumpingStreak = ticks;
    }

    /** Серия «jumping не снимается» уже отчиталась. */
    public boolean isJumpingStreakReported() {
        return this.jumpingStreakReported;
    }

    public void setJumpingStreakReported(boolean reported) {
        this.jumpingStreakReported = reported;
    }

    /** Значение флага {@code jumping} — только для диагностики. */
    public boolean isJumpFlagSet() {
        return this.jumping;
    }

    // ==================== Доступ для детектора ОТБРОС (M8) ====================

    /** Тиков подряд у вершины дуги отбрасывания. */
    public int getKnockbackHangTicks() {
        return this.knockbackHangTicks;
    }

    public void setKnockbackHangTicks(int ticks) {
        this.knockbackHangTicks = ticks;
    }

    /** Высота, с которой началась текущая воздушная фаза. */
    public double getAirPhaseStartY() {
        return this.airPhaseStartY;
    }

    public void setAirPhaseStartY(double y) {
        this.airPhaseStartY = y;
    }

    /** Максимальная высота текущей воздушной фазы. */
    public double getAirPhaseMaxY() {
        return this.airPhaseMaxY;
    }

    public void setAirPhaseMaxY(double y) {
        this.airPhaseMaxY = y;
    }

    /** Фаза «отбрасывание» уже отчиталась. */
    public boolean isKnockbackReported() {
        return this.knockbackReported;
    }

    public void setKnockbackReported(boolean reported) {
        this.knockbackReported = reported;
    }

    /**
     * Блок под ногами стражa в виде {@code minecraft:id[y=..]}.
     *
     * <p>Нужен замеру M1: если {@code onGround() == true}, а под ногами воздух —
     * это и есть «невидимый полублок» из описания владельца.
     */
    public String getBlockBelowName() {
        net.minecraft.core.BlockPos below =
                net.minecraft.core.BlockPos.containing(this.getX(), this.getY() - 0.2D, this.getZ());
        return this.level().getBlockState(below).getBlock()
                .builtInRegistryHolder().key().location().toString();
    }

    /** Высота над блоком под ногами (дробная часть координаты y). */
    public double getHeightAboveBlock() {
        return this.getY() - Math.floor(this.getY());
    }

    /**
     * Конечный узел текущего маршрута — замер M7.
     *
     * <p>Нужен, чтобы проверить найденную причину D2: навигация ведёт стража не
     * к самой цели, а к последнему узлу пути, и этот узел может стоять дальше
     * радиуса удара. Тогда страж «дошёл» (маршрут завершён), но бить не может —
     * и стоит, пока цель не сдвинется.
     *
     * <p>Формат: {@code x/z} и расстояние до конечного узла, либо {@code -}.
     */
    public String describePathEnd() {
        net.minecraft.world.level.pathfinder.Path path = this.getNavigation().getPath();
        if (path == null) {
            return "-";
        }
        net.minecraft.world.level.pathfinder.Node end = path.getEndNode();
        if (end == null) {
            return "-";
        }
        double dx = end.x + 0.5D - this.getX();
        double dz = end.z + 0.5D - this.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        return GuardianSnapshot.fmt(end.x + 0.5D, 1) + "/" + GuardianSnapshot.fmt(end.z + 0.5D, 1)
                + "@" + GuardianSnapshot.fmt(dist, 2)
                + "(узлов=" + path.getNodeCount()
                + ",пройдено=" + path.getNextNodeIndex() + ")";
    }

    /** Достигнут ли последний узел маршрута (маршрут построен, но исчерпан). */
    public boolean isPathExhausted() {
        net.minecraft.world.level.pathfinder.Path path = this.getNavigation().getPath();
        return path != null && path.getNextNodeIndex() >= path.getNodeCount() - 1;
    }

    // ==================== Замер M8: сырое состояние пути ====================
    //
    // Зачем. Разбор «зависания у стены» дважды упёрся в догадки: сначала
    // решили, что путь выбраковывается нашим isUsablePath(), но удаление
    // проверки ничего не изменило. Значит вопрос не в том, ЧТО МЫ делаем с
    // путём, а в том, ЧТО ВОЗВРАЩАЕТ pathfinder. Эти три метода печатают
    // ровно это и ничего не меняют в поведении.

    /**
     * Сырое состояние текущего маршрута: достижимость, остаток, узлы.
     *
     * <p>{@code canReach} — ключевое поле: {@code true} при неполном пути
     * означало бы, что pathfinder считает цель достигнутой, хотя моб до неё
     * не дойдёт. Это ровно тот случай, который давал «стоит с
     * {@code navDone=true}».
     */
    public String describePathStatus() {
        net.minecraft.world.level.pathfinder.Path path = this.getNavigation().getPath();
        if (path == null) {
            return "path=null";
        }
        net.minecraft.world.level.pathfinder.Node end = path.getEndNode();
        // Только ASCII: консоль Windows ломает кириллицу, а разбирать числа надо.
        return "canReach=" + path.canReach()
                + " distToTarget=" + GuardianSnapshot.fmt(path.getDistToTarget(), 2)
                + " nodes=" + path.getNodeCount()
                + " nextIdx=" + path.getNextNodeIndex()
                + " targetBlk=" + path.getTarget().toShortString()
                + " endNode=" + (end == null ? "-" : end.x + "/" + end.y + "/" + end.z);
    }

    /**
     * Тип проходимости блоков вокруг стража и у цели (замер M8).
     *
     * <p>Проверяемая версия: если блок, в котором стоит страж, помечен не как
     * {@code WALKABLE}, {@code WalkNodeEvaluator} может не отдать соседей —
     * тогда путь вырождается в ОДИН узел независимо от нашего кода. Именно
     * это объясняло бы, почему удаление нашего обхода ничего не изменило.
     */
    public String describePathTypes() {
        net.minecraft.world.level.pathfinder.NodeEvaluator evaluator =
                this.getNavigation().getNodeEvaluator();
        if (evaluator == null) {
            return "no-nodeEvaluator";
        }
        net.minecraft.core.BlockPos feet =
                net.minecraft.core.BlockPos.containing(this.getX(), this.getY(), this.getZ());
        net.minecraft.core.BlockPos below = feet.below();
        String inFeet = String.valueOf(evaluator.getBlockPathType(
                this.level(), feet.getX(), feet.getY(), feet.getZ()));
        String underFeet = String.valueOf(evaluator.getBlockPathType(
                this.level(), below.getX(), below.getY(), below.getZ()));

        String atTarget = "-";
        LivingEntity target = this.getTarget();
        if (target != null) {
            net.minecraft.core.BlockPos tp = target.blockPosition();
            atTarget = String.valueOf(evaluator.getBlockPathType(
                    this.level(), tp.getX(), tp.getY(), tp.getZ()));
        }
        return "inFeet=" + inFeet + " underFeet=" + underFeet + " atTarget=" + atTarget;
    }

    /** Тик последнего полученного урона — для замера сдвига после удара. */
    private int lastHurtTick = -1;
    private double lastHurtX = 0.0D;
    private double lastHurtZ = 0.0D;

    /**
     * Насколько страж сместился по горизонтали с момента последнего урона.
     *
     * <p>Отвечает на вопрос, который не решается снимком: после удара у стены
     * он реально пытается сдвинуться (пусть и незначительно) или полностью
     * зафиксирован. В снимке видно только текущую позицию.
     */
    public String describePostHitDisplacement() {
        if (this.lastHurtTick < 0) {
            return "-";
        }
        double dx = this.getX() - this.lastHurtX;
        double dz = this.getZ() - this.lastHurtZ;
        double moved = Math.sqrt(dx * dx + dz * dz);
        return "moved=" + GuardianSnapshot.fmt(moved, 3) + " ticks=" + (this.tickCount - this.lastHurtTick);
    }

    // Счётчики боя. Работают ВСЕГДА, в отличие от трассировки (/sp_trace),
    // которую легко забыть включить — именно на этом был потерян прогон:
    // «0 событий удар мечом» означало лишь «трассировка выключена».
    // По ним видно в любом /sp_snap, бьёт страж или только стоит.
    private int meleeSwingCount = 0;
    private int meleeHitCount = 0;

    /** Строка для снимка: сколько раз махнул и сколько раз попал. */
    public String describeAttackCounters() {
        return "swings=" + this.meleeSwingCount + " hits=" + this.meleeHitCount;
    }

    /** Учесть взмах мечом (вызывается из {@link #swing}). */
    private void countSwing() {
        if (this.meleeSwingCount < Integer.MAX_VALUE) {
            this.meleeSwingCount++;
        }
    }

    /** Учесть попадание (вызывается из {@link #doHurtTarget}). */
    private void countHit() {
        if (this.meleeHitCount < Integer.MAX_VALUE) {
            this.meleeHitCount++;
        }
    }

    /** Идёт ли сейчас отступление от врага. */
    public boolean isRetreating() {
        return this.retreating;
    }

    public void setRetreating(boolean retreating) {
        this.retreating = retreating;
    }

    public void setEating(boolean eating) {
        this.eating = eating;
        this.eatPulse = eating ? GuardianTuning.EAT_PULSE_TIMEOUT_TICKS : 0;
    }

    /**
     * Продлить «пульс» еды на этот тик.
     *
     * <p>Вызывается голом еды каждый его тик. Пока гол тикает — флаг еды
     * живёт; как только тики прекратились (гол остановлен, сущность выгружена
     * или мир перезагружен), пульс истекает и состояние еды снимает сторож в
     * {@link #tickCountdowns()}.
     */
    public void pulseEating() {
        this.eating = true;
        this.eatPulse = GuardianTuning.EAT_PULSE_TIMEOUT_TICKS;
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
                && this.isMeleeActive()) {
            this.countSwing();
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
        if (this.isMeleeActive()) {
            // Считаем ПОПЫТКУ, а не только успех: если урон не прошёл
            // (неуязвимость, щит, имунность), flag = false, но удар был.
            this.countHit();
        }
        if (flag && this.isMeleeActive()) {
            this.playSound(SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.0F, 1.0F);
            GuardianTrace.event(this, "удар мечом");
        }
        return flag;
    }

    /**
     * Страж получил урон.
     *
     * <p>Несколько тиков после удара мы не рулим движением: наша система
     * ежетиково перезаписывает горизонтальную скорость, и она гасит
     * отбрасывание, тогда как вертикальный подброс остаётся — из-за этого
     * страж «зависал» в воздухе после попадания.
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt) {
            this.damageStunTicks = 3;
            // Замер M8: запоминаем точку удара, чтобы потом посчитать, сдвинулся
            // ли страж вообще. Снимок показывает только текущую позицию и на
            // вопрос «он пытался выйти или зафиксирован» не отвечает.
            this.lastHurtTick = this.tickCount;
            this.lastHurtX = this.getX();
            this.lastHurtZ = this.getZ();
            // Сводка по нагрузке сервера в момент удара (если включён /sp_perf on).
            if (!this.level().isClientSide && this.level() instanceof ServerLevel serverLevel) {
                PerfDiagnostics.onGuardianHurt(serverLevel, this, source, amount);
            }
        }
        return hurt;
    }

    // ==================== Сохранение ====================

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("CrossbowCharge", this.crossbowChargeTime);
        tag.putInt("HelpScreamCooldown", this.helpScreamCooldown);
        tag.putInt("BreadCooldown", this.breadCooldown);
        // «Еда» намеренно НЕ сохраняется: состояние голов не сериализуется,
        // поэтому после загрузки активного гола еды нет. Восстановленный флаг
        // означал бы «навсегда застыл с хлебом». Гол запустится заново сам,
        // если здоровье всё ещё ниже порога.
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
        // Скин выбирается один раз при первичном появлении и сохраняется в NBT.
        // Рост назначается следом: диапазон зависит от уже установленного пола.
        // Имя назначается последним: набор имён зависит от пола.
        if (!this.level().isClientSide && dataTag == null) {
            this.rollRandomSkin();
            this.rollRandomScale();
            this.ensureName();
        }
        return super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData, dataTag);
    }
}
