package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.init.GuardianTrace;
import com.phian404.survivalpoints.init.GuardianTuning;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Ближний бой.
 *
 * <p>Заменяет ванильный {@code MeleeAttackGoal}: у того есть собственная логика
 * выбора цели и дистанции атаки, из-за чего он мог начать атаку в одном состоянии,
 * а состояние при этом оставалось прежним (меч в руке вместо арбалета).
 * Свой гол работает строго в состоянии {@link BaseGuardianEntity.GuardianState#MELEE}.
 */
public class GuardianMeleeAttackGoal extends Goal {

    private final BaseGuardianEntity guardian;
    private LivingEntity target;
    private int attackCooldown = 0;

    /**
     * Замах: сколько тиков страж заносит меч перед ударом.
     *
     * <p>Без этого удар прилетал в тот же тик, когда меч только появился в руке:
     * выглядело как «мгновенный удар». Теперь страж сначала разворачивается и
     * заносит оружие, и только потом бьёт.
     */
    private int windup = 0;

    public GuardianMeleeAttackGoal(BaseGuardianEntity guardian) {
        this.guardian = guardian;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // Мёртвый страж не бьёт. Ваниль отключает голы после смерти сама
        // (LivingEntity.aiStep() -> isImmobile() -> serverAiStep() не вызывается),
        // но проверка стоит здесь явно: состояние MELEE у мёртвого стража может
        // остаться прежним, а вызов прийти из любого места.
        if (!this.guardian.isAlive()) {
            return false;
        }
        if (this.guardian.getAIState() != BaseGuardianEntity.GuardianState.MELEE) {
            return false;
        }
        this.target = this.guardian.getTarget();
        return this.target != null && this.target.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return this.guardian.isAlive()
                && this.guardian.getAIState() == BaseGuardianEntity.GuardianState.MELEE
                && this.target != null && this.target.isAlive();
    }

    @Override
    public void start() {
        this.attackCooldown = 0;
        this.windup = 0;
    }

    @Override
    public void stop() {
        this.guardian.getNavigation().stop();
    }

    /**
     * Гол обновляется каждый тик.
     *
     * <p>Ванильный {@code GoalSelector} вызывает {@code tick()} цели только
     * каждый второй тик: на нечётных тиках работает {@code tickRunningGoals(false)},
     * который трогает только голы с {@code requiresUpdateEveryTick() == true}.
     * Без этого переопределения движение обновлялось бы 10 раз в секунду
     * вместо 20 — выходило рваным, а реакция на цель запаздывала.
     */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (this.target == null) {
            return;
        }

        if (this.attackCooldown > 0) {
            this.attackCooldown--;
        }

        double distance = this.guardian.distanceTo(this.target);

        if (distance <= GuardianTuning.MELEE_ENTER_DISTANCE) {
            // Взмах. Здесь же разворачиваемся к врагу и заносим меч.
            this.guardian.getNavigation().stop();
            this.guardian.faceTarget(this.target);

            if (this.windup == 0) {
                // Анимацию запускаем В НАЧАЛЕ замаха, а не в момент удара: тогда
                // видно, как рука заносится, и удар приходит в конце движения.
                // Раньше анимация стартовала вместе с ударом — выглядело лагом.
                this.guardian.startMeleeSwing();
                GuardianTrace.event(this.guardian, "замах мечом");
            }
            this.windup++;

            if (this.attackCooldown <= 0 && this.windup >= GuardianTuning.MELEE_WINDUP_TICKS) {
                this.guardian.doHurtTarget(this.target);
                this.guardian.swing(InteractionHand.MAIN_HAND);
                GuardianTrace.event(this.guardian, "удар мечом");
                this.attackCooldown = GuardianTuning.MELEE_ATTACK_COOLDOWN;
                this.windup = 0;
            }
            return;
        }

        // В ближнем бою страж ДЕРЁТСЯ, а не отходит: подходит к врагу и бьёт.
        // Отступление есть только в режиме паники (см. GuardianRetreatGoal) —
        // по требованию владельца: «раз перешёл на ближний бой — пусть дерётся».
        this.guardian.faceTarget(this.target);

        Vec3 toTarget = this.target.position().subtract(this.guardian.position());
        if (toTarget.lengthSqr() < 1.0E-6D) {
            return;
        }
        toTarget = toTarget.normalize();

        if (distance < 2.0D) {
            // Совсем близко — путь не строим: он всё равно перестраивался бы
            // каждый тик (путь до цели в паре шагов сразу считается пройденным).
            this.guardian.moveDirectly(toTarget, GuardianTuning.MELEE_SPEED);
            return;
        }

        this.guardian.navigateTo(this.target.getX(), this.target.getY(), this.target.getZ(),
                GuardianTuning.NAV_SPEED);
        this.guardian.moveAlongPath(GuardianTuning.MELEE_SPEED, toTarget);
    }
}
