package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.init.GuardianTuning;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Отступление в панике.
 *
 * <p>Работает ТОЛЬКО в состоянии {@link BaseGuardianEntity.GuardianState#PANIC}.
 *
 * <p>Маршрут отхода строит ванильная навигация: она обходит стены, ищет выход из ямы
 * и умеет подъёмы на блок. Раньше обход был самодельным (проверка одного блока
 * впереди и семь фиксированных углов), из-за чего страж «скользил вдоль стен»,
 * бесконечно колебался между направлениями и застревал в яме.
 *
 * <p>Скорость при этом считает сам страж — в мировых координатах, от следующего
 * узла маршрута. Поэтому курс тела можно держать развёрнутым к врагу: навигация
 * не управляет скоростью, только маршрутом.
 */
public class GuardianRetreatGoal extends Goal {

    private final BaseGuardianEntity guardian;
    private LivingEntity target;
    private int screamTimer = 0;

    public GuardianRetreatGoal(BaseGuardianEntity guardian) {
        this.guardian = guardian;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.guardian.getAIState() != BaseGuardianEntity.GuardianState.PANIC) {
            return false;
        }
        this.target = this.guardian.getTarget();
        return this.target != null && this.target.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return this.guardian.getAIState() == BaseGuardianEntity.GuardianState.PANIC
                && this.target != null && this.target.isAlive();
    }

    @Override
    public void start() {
        this.screamTimer = 0;
        this.guardian.resetNavigationTarget();
        if (this.target != null) {
            this.guardian.callForHelp(this.target);
        }
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

        // Зов о помощи — периодически, а не каждый тик.
        if (this.screamTimer > 0) {
            this.screamTimer--;
        } else {
            this.guardian.callForHelp(this.target);
            this.screamTimer = GuardianTuning.SCREAM_INTERVAL;
        }

        double distance = this.guardian.distanceTo(this.target);

        if (distance >= GuardianTuning.PANIC_RETREAT_DISTANCE) {
            // Безопасная дистанция достигнута — стоим и ведём огонь.
            this.guardian.stopHorizontalMovement();
            return;
        }

        Vec3 away = this.guardian.position().subtract(this.target.position());
        if (away.lengthSqr() < 1.0E-6D) {
            return;
        }
        away = away.normalize();

        // Точка отхода привязана к ВРАГУ, а не к стражу, иначе цель убегала бы
        // вместе с нами и маршрут пересчитывался бы каждый тик.
        Vec3 fleePoint = this.target.position().add(away.scale(GuardianTuning.PANIC_RETREAT_DISTANCE));

        this.guardian.navigateTo(fleePoint.x, this.target.getY(), fleePoint.z, GuardianTuning.NAV_SPEED);
        this.guardian.moveAlongPath(GuardianTuning.RETREAT_SPEED, away);
    }
}
