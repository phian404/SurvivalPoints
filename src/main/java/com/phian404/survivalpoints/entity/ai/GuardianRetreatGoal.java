package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.init.GuardianFeatureFlags;
import com.phian404.survivalpoints.init.GuardianTrace;
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
 * <p>Пока враг не подошёл вплотную, страж пятится лицом к нему: шаг назад
 * относительно курса тела ({@code MoveControl.strafe}). Если упёрся в стену —
 * идёт в обход по маршруту.
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
        // Паника — вычисляемый предикат (мало ХП + живая цель), а не состояние
        // автомата: он не может «залипнуть» и виден сразу, без задержки в тик.
        if (!this.guardian.isPanicking()) {
            return false;
        }
        this.target = this.guardian.getTarget();
        return this.target != null && this.target.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return this.guardian.isPanicking()
                && this.target != null && this.target.isAlive();
    }

    @Override
    public void start() {
        this.screamTimer = 0;
        this.guardian.resetNavigationTarget();
        // Уход в панику — новый бой: рабочий рубеж надо занять заново.
        // Раньше это делал автомат состояний (ветка PANIC); после его удаления
        // сброс выполняется здесь, на входе в панику (риск R4 решения R0.1).
        this.guardian.resetStandoff();
        if (this.target != null) {
            this.guardian.callForHelp(this.target);
        }
        GuardianTrace.event(this.guardian, "паника: отступление");
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
            this.guardian.standStill();
            return;
        }

        Vec3 away = this.guardian.position().subtract(this.target.position());
        if (away.lengthSqr() < 1.0E-6D) {
            return;
        }
        away = away.normalize();

        if (this.guardian.horizontalCollision) {
            // Упёрлись в стену — идём в обход по маршруту.
            Vec3 fleePoint = this.guardian.position().add(away.scale(2.0D));
            this.guardian.followPath(fleePoint.x, this.guardian.getY(), fleePoint.z,
                    GuardianTuning.RETREAT_SPEED);
            return;
        }

        if (GuardianFeatureFlags.isEnabled(GuardianFeatureFlags.Flag.CUSTOM_MOVE)) {
            // Старая самодельная физика — оставлена только для сравнения
            // с ванильным движением (A/B по флагу CUSTOM_MOVE).
            this.guardian.moveDirectly(away, GuardianTuning.RETREAT_SPEED);
            return;
        }

        // Пятится лицом к врагу: тело развёрнуто на цель, шаг делается назад.
        this.guardian.strafeWithSpeed(-1.0F, 0.0F, GuardianTuning.RETREAT_SPEED);
    }
}
