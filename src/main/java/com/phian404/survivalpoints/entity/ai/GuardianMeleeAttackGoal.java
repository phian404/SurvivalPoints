package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
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
            this.windup++;

            // Вплотную — отходим на занятый рубеж, продолжая бить.
            if (distance < GuardianTuning.MELEE_BACKSTEP_DISTANCE) {
                this.retreatToStandoff();
            }

            if (this.attackCooldown <= 0 && this.windup >= GuardianTuning.MELEE_WINDUP_TICKS) {
                this.guardian.doHurtTarget(this.target);
                this.guardian.swing(InteractionHand.MAIN_HAND);
                this.attackCooldown = GuardianTuning.MELEE_ATTACK_COOLDOWN;
                this.windup = 0;
            }
            return;
        }

        // Слишком далеко для удара. Раньше здесь страж ШЁЛ к врагу через навигацию —
        // и получался «пинг-понг»: зомби отходит → страж догоняет → зомби снова
        // подходит → снова MELEE. Теперь страж стоит на месте и ждёт: рубеж не
        // нарушается, поведение выглядит как «держит позицию».
        this.guardian.getNavigation().stop();
        this.guardian.faceTarget(this.target);
        this.guardian.stopHorizontalMovement();
    }

    /**
     * Отход на занятый рубеж.
     *
     * <p>Точка отхода — якорь рубежа (она в мире и не уезжает за врагом), поэтому
     * маршрут стабилен. Если якорь недостижим, отходим просто прочь от врага.
     */
    private void retreatToStandoff() {
        Vec3 anchor = this.guardian.getStandoffAnchor();
        if (anchor != null) {
            double dx = anchor.x - this.guardian.getX();
            double dz = anchor.z - this.guardian.getZ();
            double anchorDistSq = dx * dx + dz * dz;
            if (anchorDistSq > 1.0D) {
                Vec3 toAnchor = new Vec3(dx, 0.0D, dz).normalize();
                this.guardian.navigateTo(anchor.x, anchor.y, anchor.z, GuardianTuning.NAV_SPEED);
                this.guardian.moveAlongPath(GuardianTuning.RETREAT_SPEED, toAnchor);
                return;
            }
        }

        Vec3 away = this.guardian.position().subtract(this.target.position());
        if (away.lengthSqr() < 1.0E-6D) {
            return;
        }
        away = away.normalize();
        Vec3 fleePoint = this.guardian.position().add(away.scale(2.0D));
        this.guardian.navigateTo(fleePoint.x, this.guardian.getY(), fleePoint.z, GuardianTuning.NAV_SPEED);
        this.guardian.moveAlongPath(GuardianTuning.RETREAT_SPEED, away);
    }
}
