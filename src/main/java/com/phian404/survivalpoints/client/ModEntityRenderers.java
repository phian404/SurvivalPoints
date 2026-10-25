package com.phian404.survivalpoints.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.entity.BaseSurvivorNpcEntity;
import com.phian404.survivalpoints.init.ModEntities;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent;
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
                        // Текстура выбирается по синхронизированному индексу скина,
                        // поэтому превращённый NPC сохраняет свой внешний вид.
                        return SurvivorSkin.textureFor(entity.getSkinIndex());
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

                            if (entity.getAIState() != BaseGuardianEntity.GuardianState.MELEE) {
                                // Голова ведётся отдельно от корпуса: она доворачивается
                                // к цели быстрее него. Её курс клиентский LookControl
                                // портит (уводит к корпусу и зажимает 75°), поэтому
                                // берём его из синхронизированных данных.
                                entity.yHeadRot = entity.getSyncedHeadYaw();

                                // Наклон клиент обнуляет каждый тик (LookControl) —
                                // поэтому его берём из синхронизированных данных.
                                entity.setXRot(entity.getSyncedHeadPitch());
                            }
                            // В ближнем бою за головой следит ванильный
                            // MeleeAttackGoal (LookControl) — наши значения там
                            // были бы устаревшими.
                        }

                        // Анимацию удара теперь рисует ванильная HumanoidModel:
                        // ближний бой ведёт ванильный MeleeAttackGoal, он дёргает
                        // mob.swing(), и рука заносится штатным setupAttackAnimation.
                        super.render(entity, entityYaw, partialTicks, pose, buffer, packedLight);
                    }
                };

            renderer.addLayer(new CustomWeaponLayer<>(renderer));
            
            return renderer;
        });

        // Базовый выживший: та же гуманоидная модель, но пассивное поведение.
        event.registerEntityRenderer(ModEntities.BASE_SURVIVOR.get(), context -> {
            MobRenderer<BaseSurvivorNpcEntity, HumanoidModel<BaseSurvivorNpcEntity>> renderer =
                    new MobRenderer<>(context,
                            new SurvivorModel(context.bakeLayer(
                                    net.minecraft.client.model.geom.ModelLayers.PLAYER)),
                            0.5F) {
                        @Override
                        public ResourceLocation getTextureLocation(BaseSurvivorNpcEntity entity) {
                            return SurvivorSkin.textureFor(entity.getSkinIndex());
                        }
                    };
            return renderer;
        });
    }

    /**
     * Централизованный выбор текстуры скина выжившего.
     *
     * <p>Индекс приходит с сервера через {@code SynchedEntityData} и сохраняется
     * в NBT, поэтому все клиенты видят одинаковый вариант, а перезаход его не
     * меняет. Новые варианты добавляются здесь и увеличивают
     * {@code BaseSurvivorEntity.SKIN_COUNT}.
     */
    public static final class SurvivorSkin {
        /**
         * Набор скинов. Сейчас используются ванильные текстуры игрока, чтобы
         * не добавлять новые ресурсы; собственные варианты можно добавить
         * сюда же, увеличив {@code BaseSurvivorEntity.SKIN_COUNT}.
         */
        private static final ResourceLocation[] TEXTURES = new ResourceLocation[] {
                ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/steve.png"),
                ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/alex.png"),
                ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/ari.png"),
                ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/efe.png"),
                ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/kai.png"),
                ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/makena.png"),
                ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/noor.png"),
                ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/sunny.png"),
                ResourceLocation.fromNamespaceAndPath("minecraft", "textures/entity/player/wide/zuri.png")
        };

        private SurvivorSkin() {
        }

        public static ResourceLocation textureFor(int index) {
            if (index < 0 || index >= TEXTURES.length) {
                return TEXTURES[0];
            }
            return TEXTURES[index];
        }
    }

    /** Модель базового выжившего: без боевых поз стража. */
    public static class SurvivorModel extends HumanoidModel<BaseSurvivorNpcEntity> {

        public SurvivorModel(net.minecraft.client.model.geom.ModelPart root) {
            super(root);
        }
    }

    /**
     * Регистрирует ванильные pulling-модели арбалета как дополнительные модели.
     * Без этого ModelManager.getModel() для них возвращает missing-model
     * (куб с ошибочной текстурой), хотя сами модели существуют в ваниле.
     */
    @SubscribeEvent
    public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(net.minecraft.client.resources.model.ModelResourceLocation.vanilla("crossbow_pulling_0", "inventory"));
        event.register(net.minecraft.client.resources.model.ModelResourceLocation.vanilla("crossbow_pulling_1", "inventory"));
        event.register(net.minecraft.client.resources.model.ModelResourceLocation.vanilla("crossbow_pulling_2", "inventory"));
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
            boolean currentEating = entity.isEating();

            float chargeProgress = entity.getCrossbowChargeProgress();
            if (currentCombatState == BaseGuardianEntity.CombatState.RANGED && !currentEating) {
                if (currentCharging) {
                    this.rightArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY;
                    this.leftArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY;
                } else {
                    this.rightArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.CROSSBOW_HOLD;
                    this.leftArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.CROSSBOW_HOLD;
                }
            } else {
                this.rightArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY;
                this.leftArmPose = net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY;
            }

            super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
            if (currentCharging && !currentEating
                    && currentCombatState == BaseGuardianEntity.CombatState.RANGED) {
                // Повторяем ванильный animateCrossbowCharge для праворукой позы,
                // используя chargeProgress стража вместо getTicksUsingItem() игрока.
                float progress = net.minecraft.util.Mth.clamp(chargeProgress, 0.0F, 1.0F);
                float baseArmX = -0.97079635F;

                this.rightArm.xRot = baseArmX;
                this.rightArm.yRot = -0.8F;
                this.leftArm.xRot = net.minecraft.util.Mth.lerp(progress, baseArmX, (float) (-Math.PI / 2));
                this.leftArm.yRot = net.minecraft.util.Mth.lerp(progress, 0.4F, 0.85F);
            }
            if (currentEating) {
                this.rightArm.xRot = 0.0F;
                this.rightArm.yRot = 0.0F;
                this.rightArm.zRot = 0.0F;
                this.leftArm.xRot = 0.0F;
                this.leftArm.yRot = 0.0F;
                this.leftArm.zRot = 0.0F;
            }

            // Анимацию удара мечом не трогаем: её рисует ванильная
            // HumanoidModel (setupAttackAnimation по LivingEntity.swingAnimation).
        }
    }
}

