package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
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
 * <p>Маршрут в обоих режимах строит навигация (обход стен, выход из ям, подъёмы),
 * а скорость страж считает сам в мировых координатах — так тело остаётся
 * развёрнутым к врагу независимо от направления маршрута.
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
                    Vec3 toPoint = new Vec3(dx, 0.0D, dz).normalize();

                    this.guardian.navigateTo(point.getX() + 0.5D, point.getY(), point.getZ() + 0.5D,
                            GuardianTuning.NAV_SPEED);
                    this.guardian.moveAlongPath(GuardianTuning.RALLY_SPEED, toPoint);
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

        if (this.guardian.isStandoffReached()) {
            // Рубеж занят. Сближаться больше НЕЛЬЗЯ — именно это и давало
            // «приблизился — отошёл — приблизился»: стоило врагу отойти, и страж
            // снова к нему подходил. Теперь сближение бывает ровно один раз.
            //
            // Но отступать страж по-прежнему должен: если враг подошёл слишком
            // близко — пятится, сохраняя дистанцию (лицом к врагу, стреляя).
            if (distance < GuardianTuning.IDEAL_MIN_DISTANCE) {
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
            Vec3 toTarget = this.target.position().subtract(this.guardian.position());
            if (toTarget.lengthSqr() < 1.0E-6D) {
                return;
            }
            toTarget = toTarget.normalize();

            this.guardian.navigateTo(this.target.getX(), this.target.getY(), this.target.getZ(),
                    GuardianTuning.NAV_SPEED);
            this.guardian.moveAlongPath(GuardianTuning.ADVANCE_SPEED, toTarget);
            return;
        }

        // Дошли до рабочей дистанции — занимаем рубеж и запоминаем его точку.
        this.guardian.setStandoffReached(this.guardian.position());
        this.holdPosition();
    }

    /**
     * Стоять на месте: гасим скорость и гасим навигацию.
     *
     * <p>Навигацию обязательно сбрасываем: иначе маршрут (а с ним прыжки
     * ванильного MoveControl) продолжает вести стража по старой цели — он
     * подпрыгивал и «зависал» в воздухе, хотя стоять должен был на месте.
     */
    private void holdPosition() {
        this.guardian.stopHorizontalMovement();
        this.guardian.resetNavigationTarget();
    }

    /**
     * Отход от подошедшего вплотную врага.
     *
     * <p>Пятится страж ОТ врага (а не к якорю): якорь — это точка, где страж
     * стоял, и привязка к ней давала «отход на 2 блока, зомби подошёл, снова
     * отход» — дёрганье. Отход непрерывный, поэтому пятится плавно.
     *
     * <p>Предел отхода — якорь плюс {@link GuardianTuning#RETREAT_MAX_BACKSTEP}:
     * иначе страж «убегает» за карту.
     */
    private void retreatFromTarget() {
        Vec3 away = this.guardian.position().subtract(this.target.position());
        if (away.lengthSqr() < 1.0E-6D) {
            this.holdPosition();
            return;
        }
        away = away.normalize();

        Vec3 anchor = this.guardian.getStandoffAnchor();
        if (anchor != null) {
            double fromAnchor = this.guardian.position().subtract(anchor).length();
            if (fromAnchor > GuardianTuning.RETREAT_MAX_BACKSTEP) {
                // Упёрлись в предел отхода — дальше не пятимся, стоим лицом к врагу.
                this.holdPosition();
                return;
            }
        }

        Vec3 fleePoint = this.guardian.position().add(away.scale(2.0D));
        this.guardian.navigateTo(fleePoint.x, this.guardian.getY(), fleePoint.z, GuardianTuning.NAV_SPEED);
        this.guardian.moveAlongPath(GuardianTuning.RETREAT_SPEED, away);
    }
}
