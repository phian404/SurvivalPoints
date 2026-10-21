package com.phian404.survivalpoints.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.init.GuardianTuning;
import com.phian404.survivalpoints.init.ModEntities;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;

@Mod.EventBusSubscriber(modid = "survivalpoints", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ModEntityRenderers {

    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.BASE_GUARDIAN.get(), context -> {
            
            MobRenderer<BaseGuardianEntity, HumanoidModel<BaseGuardianEntity>> renderer = 
                new MobRenderer<>(context, 
                    // GuardianModel replaces inline model definition
                    new GuardianModel(context.bakeLayer(net.minecraft.client.model.geom.ModelLayers.PLAYER)),
                    0.5F) {
                    // setupRotations removed to restore interpolation

                    @Override
                    public ResourceLocation getTextureLocation(BaseGuardianEntity entity) {
                        return ResourceLocation.fromNamespaceAndPath("survivalpoints", "textures/entity/base_guardian.png");
                    }

                    /**
                     * Тело моба рисуется по {@code yBodyRot}
                     * ({@code LivingEntityRenderer.setupRotations}), а эта величина
                     * ванильными пакетами НЕ синхронизируется: клиент считает её
                     * сам, из направления движения. Поэтому серверный разворот к
                     * врагу на клиент не попадал, и страж выглядел развёрнутым
                     * боком или спиной, хотя стрелял точно в цель.
                     *
                     * <p>Курс тела и наклон головы дублируются сервером в
                     * {@code SynchedEntityData} — подставляем их здесь. Заодно
                     * приравниваем {@code yHeadRot}: клиентский {@code LookControl}
                     * (наш гол LookAtPlayerGoal) уводит голову к ближайшему игроку,
                     * из-за чего голова доворачивалась отдельно от тела.
                     */
                    @Override
                    public void render(BaseGuardianEntity entity, float entityYaw, float partialTicks,
                                       PoseStack pose, MultiBufferSource buffer, int packedLight) {
                        if (entity.getAIState() != BaseGuardianEntity.GuardianState.IDLE) {
                            // Тело рисуется по yBodyRot, а он ванильными пакетами
                            // НЕ передаётся: клиент считает его сам, из направления
                            // движения. Поэтому подставляем курс, который приходит
                            // ванильным пакетом поворота (getYRot).
                            //
                            // Значения «*O» (предыдущие) НЕ трогаем: они
                            // обновляются сами в baseTick() — из значения, которое
                            // мы подставили в прошлом кадре. Благодаря этому
                            // рендерер интерполирует поворот штатным rotLerp, ровно
                            // как у обычных мобов, и ступенек не видно.
                            entity.yBodyRot = entity.getYRot();

                            // Голова ведётся отдельно от корпуса: она доворачивается
                            // к цели быстрее него. Её курс клиентский LookControl
                            // портит (уводит к корпусу и зажимает 75°), поэтому
                            // берём его из синхронизированных данных.
                            entity.yHeadRot = entity.getSyncedHeadYaw();

                            // Наклон клиент обнуляет каждый тик (LookControl) —
                            // поэтому его берём из синхронизированных данных.
                            entity.setXRot(entity.getSyncedHeadPitch());
                        }
                        super.render(entity, entityYaw, partialTicks, pose, buffer, packedLight);
                    }
                };

            renderer.addLayer(new CustomWeaponLayer<>(renderer));
            
            return renderer;
        });
    }

    // GuardianModel class definition
    public static class GuardianModel extends HumanoidModel<BaseGuardianEntity> {
        public GuardianModel(net.minecraft.client.model.geom.ModelPart root) {
            super(root);
        }

        @Override
        public void setupAnim(BaseGuardianEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
            BaseGuardianEntity.CombatState currentCombatState = entity.getCombatState();
            boolean currentCharging = entity.isCharging();

            if (currentCombatState == BaseGuardianEntity.CombatState.RANGED) {
                if (currentCharging) {
                    this.rightArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.CROSSBOW_CHARGE;
                    this.leftArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.CROSSBOW_CHARGE;
                } else {
                    this.rightArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.CROSSBOW_HOLD;
                    this.leftArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.CROSSBOW_HOLD;
                }
            } else {
                this.rightArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY;
                this.leftArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY;
            }

            super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

            if (currentCombatState == BaseGuardianEntity.CombatState.MELEE) {
                // Фаза взмаха считается по ВРЕМЕНИ: клиент сам выводит её из
                // тика начала взмаха (он синхронизирован). Раньше фаза бралась
                // из счётчика, который обновлялся раз в тик на сервере и
                // приходил на клиент пачками — рука дёргалась ступеньками.
                int swingStart = entity.getSyncedSwingStart();
                float phase = (entity.tickCount - swingStart)
                        / (float) GuardianTuning.MELEE_SWING_ANIM_TICKS;

                if (phase > 0.0F && phase < 1.0F) {
                    // Плавный подъём и такое же плавное возвращение (ease-in-out).
                    float swing = 0.5F - 0.5F * (float) Math.cos(phase * (float) (Math.PI * 2.0));
                    this.rightArm.xRot = -0.2F - swing * 1.6F;
                    this.rightArm.yRot = -0.35F * swing;
                    this.rightArm.zRot = 0.3F * swing;
                    this.leftArm.xRot = -0.45F * swing;
                    this.leftArm.yRot = 0.0F;
                    this.leftArm.zRot = 0.0F;
                } else {
                    if (limbSwingAmount > 0.01F) {
                        float armSwing = (float) Math.sin(limbSwing * 0.66624F) * 1.4F * limbSwingAmount;
                        this.rightArm.xRot += armSwing * 1.5F;
                        this.rightArm.zRot = -armSwing * 0.5F;
                        this.leftArm.xRot -= armSwing;
                    } else {
                        this.rightArm.xRot += 0.1F;
                        this.leftArm.xRot += 0.1F;
                    }
                    this.rightArm.xRot -= 0.2F;
                    this.leftArm.xRot -= 0.1F;
                }
            }
        }
    }
}

