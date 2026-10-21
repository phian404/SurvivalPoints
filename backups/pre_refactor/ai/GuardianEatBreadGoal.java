package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.EnumSet;
import java.util.Random;

public class GuardianEatBreadGoal extends Goal {
    private final BaseGuardianEntity guardian;
    private final Random random = new Random();
    private int eatTimer = 0;
    private int duration = 0;
    private ItemStack previousItem;

    public GuardianEatBreadGoal(BaseGuardianEntity guardian) {
        this.guardian = guardian;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return guardian.getTarget() == null 
                && !guardian.isEating() 
                && guardian.getBreadCooldown() <= 0 
                && guardian.getHealth() < guardian.getMaxHealth() * 0.7F;
    }

    @Override
    public void start() {
        guardian.setEating(true);
        previousItem = guardian.getMainHandItem().copy();
        guardian.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD));
        duration = 20 + random.nextInt(21);
        eatTimer = 0;
    }

    @Override
    public void tick() {
        eatTimer++;

        // Каждые 2 тика - звук, анимация руки и частицы
        if (eatTimer % 2 == 0) {
            guardian.swing(InteractionHand.MAIN_HAND, true);
            guardian.playSound(net.minecraft.sounds.SoundEvents.GENERIC_EAT, 0.5F, 1.0F);

            // Правильный спавн частиц с сервера
            if (guardian.level() instanceof ServerLevel serverLevel) {
                double x = guardian.getX();
                double y = guardian.getEyeY();
                double z = guardian.getZ();

                // Спавним несколько частиц CRIT вокруг головы
                for (int i = 0; i < 3; i++) {
                    serverLevel.sendParticles(
                        ParticleTypes.CRIT,
                        x + (random.nextDouble() - 0.5) * 0.4,
                        y + (random.nextDouble() - 0.5) * 0.4,
                        z + (random.nextDouble() - 0.5) * 0.4,
                        0, // количество частиц за один вызов
                        (int) (random.nextDouble() * 2),
                        (int) (random.nextDouble() * 2),
                        (int) (random.nextDouble() * 2),
                        0.05D // скорость
                    );
                }
            }
        }

        if (guardian.getHealth() < guardian.getMaxHealth()) {
            guardian.heal(0.2F);
        }

        if (eatTimer == duration) {
            if (!guardian.level().isClientSide) {
            }
            guardian.setBreadCooldown(200);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return eatTimer < duration && guardian.getTarget() == null;
    }

    @Override
    public void stop() {
        guardian.setEating(false);
        if (previousItem != null) {
            guardian.setItemInHand(InteractionHand.MAIN_HAND, previousItem);
        }
    }
}
