package com.phian404.survivalpoints.entity.ai;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import net.minecraft.core.BlockPos;

public class GuardianRangedAttackGoal extends Goal {
    private final BaseGuardianEntity guardian;
    private LivingEntity target;
    private int seeTime;
    private int postShotCooldown = 0;

    public GuardianRangedAttackGoal(BaseGuardianEntity guardian) {
        this.guardian = guardian;
    }

    @Override
    public boolean canUse() {
        this.target = this.guardian.getTarget();
        if (this.target == null || !this.target.isAlive()) return false;
        return this.guardian.getMainHandItem().is(Items.CROSSBOW);
    }

    @Override
    public void start() {
        this.seeTime = 0;
        this.postShotCooldown = 0;
    }

    @Override
    public void tick() {
        if (this.target == null) return;
        
        // DEBUG LOG удален для очистки чата
        // 1. ЖЕСТКИЙ ОТКЛЮЧЕНИЕ НАВИГАЦИИ (Как в старом коде)
        this.guardian.getNavigation().stop();

        // 2. ЧИСТЫЙ РАСЧЕТ УГЛА (Как в старом коде)
        double dx = this.target.getX() - this.guardian.getX();
        double dz = this.target.getZ() - this.guardian.getZ();
        float angle = (float) Math.toDegrees(Math.atan2(dx, dz));
        
        this.guardian.setYRot(angle);
        this.guardian.setYHeadRot(angle);
        this.guardian.yBodyRot = angle;
        this.guardian.getLookControl().setLookAt(this.target, 360.0F, 360.0F);
        
        double distanceSqr = this.guardian.distanceToSqr(this.target);
        boolean canSee = this.guardian.getSensing().hasLineOfSight(this.target);
        boolean isLowHealth = this.guardian.getHealth() < this.guardian.getMaxHealth() * 0.4F;

        if (canSee) {
            this.seeTime++;
        } else {
            this.seeTime--;
        }

        // 3. ЛОГИКА ДВИЖЕНИЯ (С учетом паники, но без потери поворота)
        if (this.seeTime >= 10 && distanceSqr < 400.0D) {
            
            // Если мало здоровья — бежим к ближайшему союзнику, но ПРОДОЛЖАЕМ смотреть на врага
            if (isLowHealth) {
                var allies = this.guardian.level().getEntitiesOfClass(BaseGuardianEntity.class, 
                    this.guardian.getBoundingBox().inflate(64.0D));
                
                BaseGuardianEntity nearestAlly = null;
                double closestDist = Double.MAX_VALUE;
                for (var ally : allies) {
                    if (ally != this.guardian) {
                        double d = this.guardian.distanceToSqr(ally);
                        if (d < closestDist) {
                            closestDist = d;
                            nearestAlly = ally;
                        }
                    }
                }

                if (nearestAlly != null) {
                    Vec3 toAlly = nearestAlly.position().subtract(this.guardian.position()).normalize();
                    // Двигаемся к союзнику, но угол поворота уже задан выше (на врага)
                    this.guardian.setDeltaMovement(toAlly.x * 1.2D, this.guardian.getDeltaMovement().y, toAlly.z * 1.2D);
                    this.guardian.callForHelp(this.target);
                }
            } 
            // Если здоровье в норме — работаем по старой тактической схеме
            else {
                this.guardian.incrementChargeTime();
                
                Vec3 targetPos = this.target.position();
                Vec3 currentPos = this.guardian.position();
                
                if (this.guardian.getCrossbowChargeTime() < 15) {
                    // Отступление
                    Vec3 retreatDir = currentPos.subtract(targetPos).normalize();
                    double speed = 0.15D;
                    this.guardian.setDeltaMovement(retreatDir.x * speed, this.guardian.getDeltaMovement().y, retreatDir.z * speed);
                } else if (this.guardian.getCrossbowChargeTime() < 18) {
                    // Остановка
                    this.guardian.setDeltaMovement(0, this.guardian.getDeltaMovement().y, 0);
                } else {
                    // Микро-шаг вперед
                    Vec3 forwardDir = targetPos.subtract(currentPos).normalize();
                    double forwardSpeed = 0.05D;
                    this.guardian.setDeltaMovement(forwardDir.x * forwardSpeed, this.guardian.getDeltaMovement().y, forwardDir.z * forwardSpeed);
                }

                if (this.guardian.getCrossbowChargeTime() >= 20) {
                    this.guardian.performRangedAttack(this.target, 1.0F);
                    this.guardian.resetChargeTime();
                    this.seeTime = 10; // Изменено с -20 на 10, чтобы не было паузы в движении
                    this.postShotCooldown = 30;
                }
            }
        } else {
            this.guardian.decrementChargeTime();
            // Если не видим цель или далеко — просто стоим или медленно идем к ней, сохраняя поворот
             if (!isLowHealth) {
                 this.guardian.setDeltaMovement(0, this.guardian.getDeltaMovement().y, 0);
             }
        }

        if (this.postShotCooldown > 0) this.postShotCooldown--;
    }
}
