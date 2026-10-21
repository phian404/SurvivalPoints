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
 *   <li><b>Бой</b> — страж держит дистанцию в коридоре
 *       [IDEAL_MIN, IDEAL_MAX] и стреляет. Если враг подошёл слишком близко —
 *       отходит, если ушёл далеко — сближается. Разворот тела всегда лицом к врагу.</li>
 * </ul>
 *
 * <p>Маршрут в обоих режимах строит навигация (обход стен, выход из ям, подъёмы),
 * а скорость страж считает сам в мировых координатах — так тело остаётся
 * развёрнутым к врагу независимо от направления маршрута.
 */
public class GuardianRangedAttackGoal extends Goal {

    private final BaseGuardianEntity guardian;
    private LivingEntity target;

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

        if (distance < GuardianTuning.IDEAL_MIN_DISTANCE) {
            // Слишком близко — отходим. Точка отхода привязана к врагу, чтобы
            // маршрут не пересчитывался каждый тик.
            Vec3 away = this.guardian.position().subtract(this.target.position());
            if (away.lengthSqr() < 1.0E-6D) {
                return;
            }
            away = away.normalize();

            Vec3 fleePoint = this.target.position()
                    .add(away.scale(GuardianTuning.IDEAL_MIN_DISTANCE + 2.0D));

            this.guardian.navigateTo(fleePoint.x, this.target.getY(), fleePoint.z, GuardianTuning.NAV_SPEED);
            this.guardian.moveAlongPath(GuardianTuning.RETREAT_SPEED, away);
            return;
        }

        if (distance > GuardianTuning.IDEAL_MAX_DISTANCE) {
            // Слишком далеко — сближаемся по маршруту.
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

        // Рабочий рубеж — стоим и стреляем.
        this.guardian.stopHorizontalMovement();
    }
}
