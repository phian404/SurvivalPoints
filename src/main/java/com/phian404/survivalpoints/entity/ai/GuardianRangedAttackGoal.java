package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.init.GuardianFeatureFlags;
import com.phian404.survivalpoints.init.GuardianTrace;
import com.phian404.survivalpoints.init.GuardianTuning;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Бой на расстоянии и ответ на зов о помощи.
 *
 * <p>Работает ТОЛЬКО в состоянии {@link BaseGuardianEntity.GuardianState#RANGED}.
 * Два режима:
 * <ul>
 *   <li><b>Сбор</b> — страж принял призыв союзника и идёт к точке сбора.</li>
 *   <li><b>Бой</b> — страж один раз сближается до рабочей дистанции, занимает
 *       <b>рубеж</b> и больше за врагом не идёт: если враг подошёл слишком близко —
 *       отходит на занятую позицию, если отошёл далеко — стоит и стреляет.
 *       Разворот тела всегда лицом к врагу.</li>
 * </ul>
 *
 * <p>Движение — ванильное:
 * <ul>
 *   <li><b>Сближение</b> — маршрут ({@code navigation.moveTo}): тело ведёт путь,
 *       голка смотрит на врага;</li>
 *   <li><b>Отход</b> — шаг назад относительно курса тела ({@code MoveControl.strafe}):
 *       страж пятится, оставаясь лицом к врагу;</li>
 *   <li><b>Стоять на рубеже</b> — вход движения обнуляется, страж стоит.</li>
 * </ul>
 */
public class GuardianRangedAttackGoal extends Goal {

    private final BaseGuardianEntity guardian;
    private LivingEntity target;

    /** Цель, для которой страж уже выходил на рабочий рубеж. */
    private LivingEntity standoffTarget;

    public GuardianRangedAttackGoal(BaseGuardianEntity guardian) {
        this.guardian = guardian;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.guardian.getAIState() != BaseGuardianEntity.GuardianState.RANGED) {
            return false;
        }
        this.target = this.guardian.getTarget();
        return this.target != null && this.target.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return this.guardian.getAIState() == BaseGuardianEntity.GuardianState.RANGED
                && this.target != null && this.target.isAlive();
    }

    @Override
    public void start() {
        this.guardian.resetNavigationTarget();
        // Флаг рабочего рубежа намеренно НЕ сбрасывается здесь: гол перезапускается
        // при каждом переходе MELEE ↔ RANGED, и сброс в этом месте возвращал
        // качели «приблизился — отошёл — приблизился». Рубеж сбрасывается только
        // при смене цели, уходе в панику и потере цели — см. BaseGuardianEntity.
    }

    @Override
    public void stop() {
        this.guardian.getNavigation().stop();
        this.guardian.standStill();
    }

    @Override
    public void tick() {
        if (this.target == null) {
            return;
        }

        // --- Режим сбора: идём к точке, где звал союзник ---
        if (this.guardian.hasRallyPoint()) {
            BlockPos point = this.guardian.getRallyPoint();
            if (point != null) {
                double dx = point.getX() + 0.5D - this.guardian.getX();
                double dz = point.getZ() + 0.5D - this.guardian.getZ();
                double distSq = dx * dx + dz * dz;

                if (distSq > GuardianTuning.RALLY_ARRIVE_DISTANCE * GuardianTuning.RALLY_ARRIVE_DISTANCE) {
                    this.guardian.followPath(point.getX() + 0.5D, point.getY(), point.getZ() + 0.5D,
                            GuardianTuning.RALLY_SPEED);
                    return;
                }

                // Дошли — призыв выполнен, дальше работаем с целью.
                this.guardian.clearRally();
            }
        }

        // --- Бой на расстоянии ---
        double distance = this.guardian.distanceTo(this.target);

        // Смена цели — новый бой, рабочий рубеж достигается заново.
        if (this.standoffTarget != this.target) {
            this.standoffTarget = this.target;
            this.guardian.resetStandoff();
        }

        boolean standoffEnabled = GuardianFeatureFlags.isEnabled(GuardianFeatureFlags.Flag.STANDOFF);

        if (standoffEnabled && this.guardian.isStandoffReached()) {
            // Рубеж занят. Сближаться больше НЕЛЬЗЯ — именно это и давало
            // «приблизился — отошёл — приблизился»: стоило врагу отойти, и страж
            // снова к нему подходил. Теперь сближение бывает ровно один раз.
            //
            // Но отступать страж по-прежнему должен: если враг подошёл слишком
            // близко — пятится, сохраняя дистанцию (лицом к врагу, стреляя).
            if (this.shouldRetreat(distance)) {
                this.retreatFromTarget();
                return;
            }
            this.holdPosition();
            return;
        }

        if (distance > GuardianTuning.IDEAL_MAX_DISTANCE) {
            // Ещё не вышли на рабочую дистанцию — сближаемся по маршруту.
            // Это происходит один раз за бой: дальше страж держит занятую
            // позицию и не лезет обратно, даже если враг отошёл.
            this.guardian.followPath(this.target.getX(), this.target.getY(), this.target.getZ(),
                    GuardianTuning.ADVANCE_SPEED);
            return;
        }

        // Дошли до рабочей дистанции — занимаем рубеж и запоминаем его точку.
        if (standoffEnabled) {
            this.guardian.setStandoffReached(this.guardian.position());
            GuardianTrace.event(this.guardian, "занял рубеж");
        }
        this.holdPosition();
    }

    /**
     * Стоять на месте: обнуляем вход движения и гасим навигацию.
     *
     * <p>Навигацию обязательно сбрасываем: иначе маршрут (а с ним прыжки
     * ванильного MoveControl) продолжает вести стража по старой цели.
     */
    private void holdPosition() {
        this.guardian.setRetreating(false);
        this.guardian.standStill();
    }

    /**
     * Отступать ли сейчас.
     *
     * <p>Гистерезис: начать отход — на близкой дистанции, прекратить — только
     * когда враг отошёл заметно дальше. Без гистерезиса страж дёргался туда-сюда
     * у самой границы: отступил на метр, вернулся к рубежу, снова отошёл.
     */
    private boolean shouldRetreat(double distance) {
        if (this.guardian.isRetreating()) {
            return distance < GuardianTuning.RETREAT_STOP_DISTANCE;
        }
        return distance < GuardianTuning.RETREAT_START_DISTANCE;
    }

    /**
     * Отход от подошедшего вплотную врага.
     *
     * <p>Пятится страж ОТ врага (лицом к нему): шаг назад относительно курса
     * тела. Отход непрерывный, поэтому пятится плавно.
     *
     * <p>Предел отхода — якорь рубежа плюс
     * {@link GuardianTuning#RETREAT_MAX_BACKSTEP}: иначе страж «убегает» за карту.
     */
    private void retreatFromTarget() {
        this.guardian.setRetreating(true);
        Vec3 anchor = this.guardian.getStandoffAnchor();
        if (anchor != null) {
            double fromAnchor = this.guardian.position().subtract(anchor).length();
            if (fromAnchor > GuardianTuning.RETREAT_MAX_BACKSTEP) {
                // Упёрлись в предел отхода — дальше не пятимся, стоим лицом к врагу.
                this.holdPosition();
                return;
            }
        }

        if (this.guardian.horizontalCollision) {
            // Упёрлись в стену — идём в обход по маршруту.
            Vec3 away = this.guardian.position().subtract(this.target.position());
            if (away.lengthSqr() < 1.0E-6D) {
                this.holdPosition();
                return;
            }
            Vec3 fleePoint = this.guardian.position().add(away.normalize().scale(2.0D));
            this.guardian.followPath(fleePoint.x, this.guardian.getY(), fleePoint.z,
                    GuardianTuning.RETREAT_SPEED);
            return;
        }

        if (GuardianFeatureFlags.isEnabled(GuardianFeatureFlags.Flag.CUSTOM_MOVE)) {
            // Старая самодельная физика — оставлена только для сравнения
            // с ванильным движением (A/B по флагу CUSTOM_MOVE).
            Vec3 away = this.guardian.position().subtract(this.target.position());
            if (away.lengthSqr() > 1.0E-6D) {
                this.guardian.moveDirectly(away.normalize(), GuardianTuning.RETREAT_SPEED);
            }
            return;
        }
        this.guardian.strafeWithSpeed(-1.0F, 0.0F, GuardianTuning.RETREAT_SPEED);
    }
}
