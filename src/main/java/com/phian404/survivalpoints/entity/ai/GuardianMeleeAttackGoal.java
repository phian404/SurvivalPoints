package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.init.GuardianTuning;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

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

        if (this.attackCooldown > 0) {
            this.attackCooldown--;
        }

        double distance = this.guardian.distanceTo(this.target);

        if (distance <= GuardianTuning.MELEE_ENTER_DISTANCE) {
            // Взмах. Здесь же разворачиваемся к врагу.
            this.guardian.getNavigation().stop();
            this.guardian.faceTarget(this.target);

            if (this.attackCooldown <= 0) {
                this.guardian.doHurtTarget(this.target);
                this.guardian.swing(InteractionHand.MAIN_HAND);
                this.attackCooldown = GuardianTuning.MELEE_ATTACK_COOLDOWN;
            }
        } else {
            // Подходим на дистанцию удара через навигацию (обходит препятствия).
            this.guardian.getNavigation().moveTo(this.target.getX(), this.target.getY(),
                    this.target.getZ(), GuardianTuning.MELEE_SPEED);
        }
    }
}
