package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.init.GuardianTrace;
import com.phian404.survivalpoints.init.GuardianTuning;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

/**
 * Ближний бой на ванильном {@link MeleeAttackGoal}.
 *
 * <p>Свой гол был самописной копией ванильной механики: свой замах, своя
 * анимация взмаха, свой расчёт дистанции, свой подход. Выглядело криво и
 * тянуло за собой ~150 строк кода (собственный рендер взмаха, своя синхронизация
 * фазы удара).
 *
 * <p>Ванильный гол уже умеет всё нужное: строит маршрут к цели, ведёт моба по
 * нему, доворачивает взгляд через {@code LookControl} и бьёт через
 * {@code mob.swing()} + {@code doHurtTarget()} — вместе с ДЕФОЛТНОЙ анимацией
 * удара.
 *
 * <p>Переопределяем только гейт по дистанции: когда вступать в ближний бой и
 * когда из него выходить.
 */
public class GuardianMeleeAttackGoal extends MeleeAttackGoal {

    private final BaseGuardianEntity guardian;

    public GuardianMeleeAttackGoal(BaseGuardianEntity guardian) {
        super(guardian, GuardianTuning.MELEE_SPEED, true);
        this.guardian = guardian;
        // Ванильный MeleeAttackGoal флагов не объявляет — значит, он не берёт
        // MOVE и мирно сосуществует с голом дальнего боя. Без флагов оба гола
        // работали бы одновременно и толкали стража в разные стороны.
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.guardian.isAlive()) {
            return false;
        }
        if (this.guardian.getAIState() != BaseGuardianEntity.GuardianState.MELEE) {
            return false;
        }
        LivingEntity target = this.guardian.getTarget();
        return target != null && target.isAlive()
                && this.guardian.distanceTo(target) <= GuardianTuning.MELEE_ENTER_DISTANCE;
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.guardian.isAlive()) {
            return false;
        }
        if (this.guardian.getAIState() != BaseGuardianEntity.GuardianState.MELEE) {
            return false;
        }
        LivingEntity target = this.guardian.getTarget();
        return target != null && target.isAlive()
                && this.guardian.distanceTo(target) <= GuardianTuning.MELEE_EXIT_DISTANCE;
    }

    /**
     * Старт ближнего боя.
     *
     * <p>Ванильный {@code start()} строит путь и может получить {@code null}
     * (маршрута нет) — тогда он падает. Поэтому строим путь сами, с проверкой.
     */
    @Override
    public void start() {
        LivingEntity target = this.guardian.getTarget();
        if (target != null) {
            Path path = this.guardian.getNavigation().createPath(target, 0);
            if (path != null) {
                this.guardian.getNavigation().moveTo(path, GuardianTuning.MELEE_SPEED);
            }
        }
        this.guardian.setAggressive(true);
        GuardianTrace.event(this.guardian, "ближний бой");
    }

    @Override
    public void stop() {
        this.guardian.setAggressive(false);
        this.guardian.getNavigation().stop();
        this.guardian.standStill();
    }
}
