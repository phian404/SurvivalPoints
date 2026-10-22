package com.phian404.survivalpoints.entity;

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

    @Override
    public void tick() {
        float savedYaw = this.mob.getYRot();
        super.tick();
        if (this.keepYaw) {
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
