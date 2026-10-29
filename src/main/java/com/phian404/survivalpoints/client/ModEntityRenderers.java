package com.phian404.survivalpoints.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import com.phian404.survivalpoints.entity.BaseSurvivorNpcEntity;
import com.phian404.survivalpoints.entity.SurvivorSkin;
import com.phian404.survivalpoints.entity.role.SurvivorGender;
import com.phian404.survivalpoints.init.ModEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
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

    /**
     * Квадрат радиуса, в пределах которого рисуется имя NPC (16 блоков).
     *
     * <p>Проверка выполняется в {@code shouldShowName} рендерера, то есть на
     * клиенте и относительно локального игрока. Поэтому каждый игрок видит
     * имена по своему расстоянию, без серверной синхронизации и без тика на
     * каждую сущность.
     */
    private static final double NAME_VISIBLE_RADIUS_SQR = 16.0 * 16.0;

    /**
     * Общее условие показа имени: ванильные правила плюс дистанция до игрока.
     *
     * <p>{@code super.shouldShowName} сохраняет штатные условия (имя задано,
     * {@code CustomNameVisible} включён, подсказка по прицелу), а расстояние
     * только сужает их.
     */
    private static boolean shouldShowNameWithinRange(BaseSurvivorEntity entity) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && entity.distanceToSqr(player) <= NAME_VISIBLE_RADIUS_SQR;
    }

    @SubscribeEvent
    public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.BASE_GUARDIAN.get(), context -> {
            
            // Две геометрии модели: WIDE (Steve, руки 4px) и SLIM (Alex, руки 3px).
            // Выбор делается в render() по полу сущности. Слои свои, а не
            // ModelLayers.PLAYER/PLAYER_SLIM: к ванильному мешу добавлен
            // клиновидный выступ торса (см. SurvivorBodyModel).
            //
            // Массив, а не две локальные переменные: геометрия выбирается по полу
            // в render(). Параметры выступа — константы (SurvivorChestTuning),
            // поэтому модели строятся один раз при регистрации рендерера.
            GuardianModel[] models = new GuardianModel[]{
                    new GuardianModel(SurvivorBodyModel.chestLayer(false).bakeRoot()),
                    new GuardianModel(SurvivorBodyModel.chestLayer(true).bakeRoot())
            };

            MobRenderer<BaseGuardianEntity, HumanoidModel<BaseGuardianEntity>> renderer =
                new MobRenderer<>(context, models[0], 0.5F) {
                    // setupRotations removed to restore interpolation

                    /** Имя рисуется только в радиусе 16 блоков от игрока. */
                    @Override
                    protected boolean shouldShowName(BaseGuardianEntity entity) {
                        return super.shouldShowName(entity)
                                && shouldShowNameWithinRange(entity);
                    }

                    /**
                     * Индивидуальный рост стража.
                     *
                     * <p>{@code LivingEntityRenderer.render} вызывает этот метод
                     * внутри {@code pushPose()}, до отрисовки модели и слоёв,
                     * поэтому тело, броня, оружие ({@link CustomWeaponLayer}) и
                     * тень масштабируются согласованно. Хитбокс сущности при
                     * этом не меняется — это осознанно.
                     */
                    @Override
                    protected void scale(BaseGuardianEntity entity, PoseStack pose, float partialTicks) {
                        float scale = entity.getScale();
                        pose.scale(scale, scale, scale);
                    }

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
                        // Геометрия выбирается по полу, а не по таблице скинов:
                        // пол — источник истины, таблица применяется только при
                        // первичном назначении и миграции. Модель подменяется до
                        // super.render(), чтобы и тело, и слои (CustomWeaponLayer)
                        // использовали актуальную геометрию.
                        this.model = entity.getGender() == SurvivorGender.FEMALE ? models[1] : models[0];
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

            // Броня рисуется до оружия, как в ванильном PlayerRenderer, поэтому
            // арбалет/меч остаются поверх нагрудника. Один слой покрывает оба
            // пола: ваниль использует один LayerDefinition для SLIM и WIDE брони
            // (ModelLayers.PLAYER_SLIM_INNER_ARMOR == PLAYER_INNER_ARMOR).
            renderer.addLayer(new HumanoidArmorLayer<>(renderer,
                    new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                    new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                    context.getModelManager()));
            renderer.addLayer(new CustomWeaponLayer<>(renderer));
            
            return renderer;
        });

        // Базовый выживший: та же гуманоидная модель, но пассивное поведение.
        event.registerEntityRenderer(ModEntities.BASE_SURVIVOR.get(), context -> {
            SurvivorModel[] models = new SurvivorModel[]{
                    new SurvivorModel(SurvivorBodyModel.chestLayer(false).bakeRoot()),
                    new SurvivorModel(SurvivorBodyModel.chestLayer(true).bakeRoot())
            };
            MobRenderer<BaseSurvivorNpcEntity, HumanoidModel<BaseSurvivorNpcEntity>> renderer =
                    new MobRenderer<>(context, models[0], 0.5F) {
                        /** Имя рисуется только в радиусе 16 блоков от игрока. */
                        @Override
                        protected boolean shouldShowName(BaseSurvivorNpcEntity entity) {
                            return super.shouldShowName(entity)
                                    && shouldShowNameWithinRange(entity);
                        }

                        /** Индивидуальный рост мирного NPC (см. рендерер стража). */
                        @Override
                        protected void scale(BaseSurvivorNpcEntity entity, PoseStack pose, float partialTicks) {
                            float scale = entity.getScale();
                            pose.scale(scale, scale, scale);
                        }

                        @Override
                        public ResourceLocation getTextureLocation(BaseSurvivorNpcEntity entity) {
                            return SurvivorSkin.textureFor(entity.getSkinIndex());
                        }

                        @Override
                        public void render(BaseSurvivorNpcEntity entity, float entityYaw, float partialTicks,
                                          PoseStack pose, MultiBufferSource buffer, int packedLight) {
                            // Геометрия выбирается по полу (источник истины).
                            this.model = entity.getGender() == SurvivorGender.FEMALE ? models[1] : models[0];
                            super.render(entity, entityYaw, partialTicks, pose, buffer, packedLight);
                        }
                    };
            // Мирному NPC броня нужна так же, как стражу: игрок может надеть
            // на него нагрудник. Порядок тот же — броня под оружием.
            renderer.addLayer(new HumanoidArmorLayer<>(renderer,
                    new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                    new HumanoidArmorModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                    context.getModelManager()));
            return renderer;
        });
    }

    /** Модель базового выжившего: без боевых поз стража. */
    public static class SurvivorModel extends SurvivorBodyModel<BaseSurvivorNpcEntity> {

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
    public static class GuardianModel extends SurvivorBodyModel<BaseGuardianEntity> {

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

