package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.init.GuardianTrace;
import com.phian404.survivalpoints.init.GuardianTuning;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
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
        super(guardian, GuardianTuning.MELEE_SPEED_MODIFIER, true);
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
        // Паника имеет приоритет над всем — в ней страж отходит с арбалетом,
        // меч не достаёт.
        if (this.guardian.isPanicking()) {
            return false;
        }
        LivingEntity target = this.guardian.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        // Против крипера держим арбалет даже вплотную: подходить к нему с мечом
        // смертельно. Ranged-гол сам отступит, если крипер подойдёт слишком близко.
        if (target instanceof Creeper) {
            return false;
        }
        return this.guardian.distanceTo(target) <= GuardianTuning.MELEE_ENTER_DISTANCE;
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.guardian.isAlive() || this.guardian.isPanicking()) {
            return false;
        }
        LivingEntity target = this.guardian.getTarget();
        // Гистерезис удержан: из боя выходим только на дальней дистанции,
        // иначе на границе включения страж дёргался меч ↔ арбалет.
        return target != null && target.isAlive()
                && !(target instanceof Creeper)
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
                this.guardian.getNavigation().moveTo(path, GuardianTuning.MELEE_SPEED_MODIFIER);
            }
        }
        this.guardian.setAggressive(true);
        this.guardian.clearStrafeInput(); // сбросить остаток шага назад от RANGED
        GuardianTrace.event(this.guardian, "ближний бой");
    }

    @Override
    public void stop() {
        this.guardian.setAggressive(false);
        this.guardian.getNavigation().stop();
        this.guardian.standStill();
    }

    /**
     * Гол должен тикать каждый тик (20 Гц), а не через тик (10 Гц).
     *
     * <p>Без этого атака задерживается: ванильный {@code Mob.serverAiStep()}
     * тикает голы полностью только на чётных тиках, а на нечётных — только те,
     * у которых {@code requiresUpdateEveryTick() == true}.
     *
     * <p>Ванильный {@link MeleeAttackGoal} переопределяет этот метод и возвращает
     * {@code true} — без этого зомби бил бы игрока в 2 раза реже.
     */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /**
     * Удар мечом.
     *
     * <p>Саму атаку делает ванильный код (свинг + урон + отбрасывание).
     * Переопределяем только чтобы записать событие в лог: по нему видно,
     * что страж реально бьёт, а не только бежит.
     */
    @Override
    protected void checkAndPerformAttack(LivingEntity target, double distanceSqr) {
        double reach = this.guardian.getMeleeAttackRangeSqr(target);
        if (distanceSqr <= reach && this.getTicksUntilNextAttack() <= 0) {
            GuardianTrace.event(this.guardian, "удар мечом");
            this.guardian.swing(InteractionHand.MAIN_HAND);
            this.guardian.playSound(SoundEvents.PLAYER_ATTACK_SWEEP, 1.0F, 1.0F);
        }
        super.checkAndPerformAttack(target, distanceSqr);
    }
}
