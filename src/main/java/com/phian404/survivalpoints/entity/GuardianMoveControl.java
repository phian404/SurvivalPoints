package com.phian404.survivalpoints.entity;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;

/**
 * Управление движением стража.
 *
 * <p>Ванильный {@link MoveControl} умеет двигать моба относительно его курса
 * ({@code strafe}) — это ровно то, что нужно для отхода лицом к врагу. Но у него
 * две особенности, которые ломают отход:
 * <ul>
 *   <li>в состоянии WAIT он каждый тик обнуляет вход движения
 *       ({@code setZza(0)}), а вход задаёт гол, который тикает раньше;</li>
 *   <li>если направление непроходимо, он подменяет шаг на «вперёд» — то есть
 *       отступление превратилось бы в шаг к врагу.</li>
 * </ul>
 *
 * <p>Здесь наш шаг запоминается и восстанавливается после {@link #tick()}.
 * Заодно удерживается курс тела: ванильный {@code MoveControl} доворачивает
 * корпус к маршруту на 90° за тик, а в бою страж должен смотреть на врага.
 *
 * <p>Побочный эффект отказа от своей физики: страж больше не перезаписывает
 * скорость каждый тик, поэтому отбрасывание, трение и гравитация работают как
 * положено (раньше горизонтальная часть отброса гасилась).
 */
public class GuardianMoveControl extends MoveControl {

    /** Наш шаг: вперёд/назад и вбок. */
    private float ourForwards = 0.0F;
    private float ourRight = 0.0F;

    /** Задан ли наш шаг (иначе моб идёт по маршруту или стоит). */
    private boolean ourActive = false;

    /** Держать ли курс тела (в бою — да, при движении по маршруту — нет). */
    private boolean keepYaw = true;

    /**
     * Операция {@code MoveControl} ДО ванильного {@link #tick()} — то есть
     * то, что заказал гол в этом тике.
     *
     * <p>Зачем отдельное поле: ванильный {@code MoveControl.tick()} в ветках
     * {@code STRAFE} и {@code MOVE_TO} первым делом ставит
     * {@code operation = WAIT} — то есть к моменту, когда мы читаем операцию
     * снаружи, она уже сброшена. Именно поэтому в прошлых отчётах
     * {@code op} почти всегда был {@code WAIT}, и вывод «никто не звал
     * strafe/moveTo» был ошибочным: {@code WAIT} означал лишь «тик уже
     * отработал».
     */
    private Operation operationBeforeTick = Operation.WAIT;

    /** Операция после ванильного {@code tick()} — для сравнения. */
    private Operation operationAfterTick = Operation.WAIT;

    public GuardianMoveControl(BaseGuardianEntity guardian) {
        super(guardian);
    }

    /**
     * Шаг относительно курса тела.
     *
     * @param forwards      1 — вперёд, -1 — назад (лицом к врагу)
     * @param right         1 — вправо, -1 — влево
     * @param blocksPerTick скорость в блоках за тик
     */
    public void strafeWithSpeed(float forwards, float right, double blocksPerTick) {
        this.ourForwards = forwards;
        this.ourRight = right;
        this.ourActive = true;

        // Ванильный strafe() жёстко ставит speedModifier = 0.25, а фактическая
        // скорость = speedModifier * Attributes.MOVEMENT_SPEED. Ставим свой множитель.
        this.strafe(forwards, right);
        this.speedModifier = blocksPerTick
                / Math.max(0.01D, this.mob.getAttributeValue(Attributes.MOVEMENT_SPEED));
    }

    /** Отменить наш шаг: дальше моб либо идёт по маршруту, либо стоит. */
    public void clearStrafe() {
        this.ourActive = false;
    }

    /** Держать курс тела (в бою — да, при движении по маршруту — нет). */
    public void setKeepYaw(boolean keep) {
        this.keepYaw = keep;
    }

    /**
     * Текущая операция MoveControl — только для диагностики.
     *
     * <p>Поле {@code operation} объявлено {@code protected} в ванильном
     * {@link MoveControl}, поэтому из подкласса доступно. По нему видно, что
     * реально делает ваниль: {@code STRAFE} (наш шаг), {@code MOVE_TO} (маршрут),
     * {@code JUMPING} (прыжок — подозреваемый в «подскоках в углу»),
     * {@code WAIT} (стоит).
     */
    public String getOperationName() {
        return this.operation.name();
    }

    /**
     * Порядковый номер операции — для компактного следа движения.
     *
     * <p>След пишется каждый тик, поэтому хранит операцию числом: строковое
     * имя создавало бы мусор для GC в горячем пути.
     */
    public int getOperationOrdinal() {
        return this.operationBeforeTick.ordinal();
    }

    /**
     * Имя операции, которую заказал гол в этом тике (до ванильного тика).
     *
     * <p>Это и есть ответ на вопрос «что хотел делать страж»: {@code STRAFE}
     * (наш шаг), {@code MOVE_TO} (маршрут), {@code JUMPING} (прыжок),
     * {@code WAIT} (гол ничего не заказывал).
     */
    public String getOperationBeforeTickName() {
        return this.operationBeforeTick.name();
    }

    /** Имя операции, оставшейся после ванильного тика (обычно {@code WAIT}). */
    public String getOperationAfterTickName() {
        return this.operationAfterTick.name();
    }

    /**
     * Имя операции по порядковому номеру — расшифровка следа при отчёте.
     *
     * <p>Живёт здесь, а не в снимке, потому что тип {@code Operation} —
     * вложенный и {@code protected}: снаружи пакета он не виден.
     */
    public static String operationName(int ordinal) {
        Operation[] values = Operation.values();
        if (ordinal < 0 || ordinal >= values.length) {
            return "?";
        }
        return values[ordinal].name();
    }

    /** Задан ли наш шаг прямо сейчас (диагностика). */
    public boolean isOurStepActive() {
        return this.ourActive;
    }

    /** Держится ли курс тела принудительно — для снимка по требованию. */
    public boolean isKeepYawOn() {
        return this.keepYaw;
    }

    @Override
    public void tick() {
        // В ближнем бою (ванильный MeleeAttackGoal) самописный шаг не восстанавливаем:
        // GuardianMoveControl работает как обычный ванильный MoveControl.
        if (this.mob instanceof BaseGuardianEntity guardian && guardian.isMeleeActive()) {
            this.ourActive = false;
            this.keepYaw = false;
            this.operationBeforeTick = this.operation;
            super.tick();
            this.operationAfterTick = this.operation;
            return;
        }

        // Операция ДО ванильного тика = заказ гола в этом тике.
        // Ванильный tick() в ветках STRAFE и MOVE_TO сразу ставит WAIT,
        // поэтому снаружи (после тика) операция уже сброшена.
        this.operationBeforeTick = this.operation;

        float savedYaw = this.mob.getYRot();
        super.tick();
        this.operationAfterTick = this.operation;

        // Курс восстанавливаем ТОЛЬКО когда активен наш шаг (strafe).
        //
        // Раньше условие было просто `keepYaw`, а флаг по умолчанию true и
        // выставляется в true из standStill(). При движении по маршруту
        // (MOVE_TO: меч-гол, сближение, прогулка) это давало баг: MoveControl
        // уже повернул моба к следующему узлу, но мы откатывали курс назад, а
        // travel() → moveRelative() считает направление по ТЕКУЩЕМУ yRot. Итог:
        // моб едет в направлении старого курса, «не поворачивая» и «застревая».
        if (this.keepYaw && this.ourActive) {
            this.mob.setYRot(savedYaw);
        }
        if (this.ourActive) {
            // MoveControl мог обнулить наш шаг (состояние WAIT) или подменить
            // направление («вперёд» при непроходимом пути). Наш шаг важнее.
            this.mob.setZza(this.ourForwards);
            this.mob.setXxa(this.ourRight);
        }

    }
}
