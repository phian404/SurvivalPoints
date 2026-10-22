package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.init.GuardianFeatureFlags;
import com.phian404.survivalpoints.init.GuardianTrace;
import com.phian404.survivalpoints.init.GuardianTuning;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumSet;
import java.util.Random;

/**
 * Страж ест хлеб, чтобы восстановить здоровье.
 *
 * <p>Раньше гол восстанавливал в руке предмет, который был ДО еды. Если за время
 * еды оружие поменялось (переключение меч/арбалет), в руке оставался хлеб или
 * устаревший меч. Теперь оружие восстанавливается из текущего состояния ИИ
 * ({@code syncEquipmentToState()}).
 */
public class GuardianEatBreadGoal extends Goal {

    private final BaseGuardianEntity guardian;
    private final Random random = new Random();
    private int eatTimer = 0;
    private int duration = 0;

    public GuardianEatBreadGoal(BaseGuardianEntity guardian) {
        this.guardian = guardian;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!GuardianFeatureFlags.isEnabled(GuardianFeatureFlags.Flag.EAT_BREAD)) {
            return false;
        }
        return this.guardian.getTarget() == null
                && !this.guardian.isEating()
                && this.guardian.getBreadCooldown() <= 0
                && this.guardian.getHealth() < this.guardian.getMaxHealth() * GuardianTuning.EAT_BREAD_HP_RATIO;
    }

    @Override
    public void start() {
        this.guardian.setEating(true);
        this.guardian.getNavigation().stop();
        this.guardian.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD));
        this.duration = 20 + this.random.nextInt(21);
        this.eatTimer = 0;
        GuardianTrace.event(this.guardian, "начал есть хлеб (" + this.duration + " тиков)");
    }

    @Override
    public void tick() {
        this.eatTimer++;

        // Каждые 2 тика — звук, анимация руки и частицы.
        if (this.eatTimer % 2 == 0) {
            this.guardian.swing(InteractionHand.MAIN_HAND, true);
            this.guardian.playSound(net.minecraft.sounds.SoundEvents.GENERIC_EAT, 0.5F, 1.0F);

            if (this.guardian.level() instanceof ServerLevel serverLevel) {
                double x = this.guardian.getX();
                double y = this.guardian.getEyeY();
                double z = this.guardian.getZ();

                for (int i = 0; i < 3; i++) {
                    serverLevel.sendParticles(
                            ParticleTypes.CRIT,
                            x + (this.random.nextDouble() - 0.5) * 0.4,
                            y + (this.random.nextDouble() - 0.5) * 0.4,
                            z + (this.random.nextDouble() - 0.5) * 0.4,
                            0,
                            (int) (this.random.nextDouble() * 2),
                            (int) (this.random.nextDouble() * 2),
                            (int) (this.random.nextDouble() * 2),
                            0.05D
                    );
                }
            }
        }

        if (this.guardian.getHealth() < this.guardian.getMaxHealth()) {
            this.guardian.heal(0.2F);
        }

        if (this.eatTimer == this.duration) {
            this.guardian.setBreadCooldown(GuardianTuning.BREAD_COOLDOWN);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return this.eatTimer < this.duration && this.guardian.getTarget() == null;
    }

    @Override
    public void stop() {
        this.guardian.setEating(false);
        // Возвращаем правильное оружие (меч или арбалет) по текущему состоянию ИИ.
        this.guardian.syncEquipmentToState();
        GuardianTrace.event(this.guardian, " закончил есть хлеб");
    }

    /**
     * Гол обновляется каждый тик.
     *
     * <p>Ванильный {@code GoalSelector} вызывает {@code tick()} цели только
     * каждый второй тик: на нечётных тиках работает {@code tickRunningGoals(false)},
     * который трогает только голы с {@code requiresUpdateEveryTick() == true}.
     * Без этого еда лечила бы вдвое медленнее задуманного (0.2 HP за 2 тика).
     */
    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
