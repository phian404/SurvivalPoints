package com.phian404.survivalpoints.client;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import com.phian404.survivalpoints.entity.BaseGuardianEntity;

public class CustomWeaponLayer<T extends LivingEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {

    public CustomWeaponLayer(RenderLayerParent<T, M> renderer) {
        super(renderer);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity, float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        ItemStack itemstack = entity.getMainHandItem();

        if (!itemstack.isEmpty()) {
            M model = this.getParentModel();

            poseStack.pushPose();
            model.rightArm.translateAndRotate(poseStack);

            boolean isSword = itemstack.getItem() instanceof SwordItem;
            if (isSword) {
                // МЕЧ: Смещаем вниз к кисти (увеличиваем Y) и центрируем
                poseStack.translate(-0.05D, 0.4D, -0.10D); // <-- ИЗМЕНЕНО ЗДЕСЬ
                poseStack.mulPose(new Quaternionf().rotationX((float) Math.toRadians(-85)));
                poseStack.mulPose(new Quaternionf().rotationZ((float) Math.toRadians(0)));
            } else if (itemstack.is(Items.BREAD)) {
                // ХЛЕБ (еда): нейтральная поза в кисти, без поворотов арбалета.
                // Раньше хлеб попадал в ветку арбалета (не меч) и рисовался
                // с rotationZ(35°)+rotationX(90°) и scale(0.8) — «мини-арбалет».
                poseStack.translate(-0.1D, 0.55D, -0.10D);
                poseStack.mulPose(new Quaternionf().rotationX((float) Math.toRadians(90)));
                poseStack.mulPose(new Quaternionf().rotationZ((float) Math.toRadians(180)));
            } else {
                // АРБАЛЕТ: Используем более стабильные значения
                boolean isCharged = false;
                if (entity instanceof BaseGuardianEntity guardianEntity) {
                    isCharged = guardianEntity.isCharging();
                } else {
                    isCharged = itemstack.getTag() != null && itemstack.getTag().getBoolean("Charged");
                }

                float chargeOffset = isCharged ? -0.05F : 0.0F;
                
                // Проверенные координаты для арбалета
                poseStack.translate(-0.1D, 0.6D + chargeOffset, -0.15D);
                poseStack.mulPose(new Quaternionf().rotationZ((float) Math.toRadians(35)));
                poseStack.mulPose(new Quaternionf().rotationX((float) Math.toRadians(90)));
                poseStack.scale(0.8F, 0.8F, 0.8F);
            }

            // Контекст отображения фиксирован: оружие всегда в правой руке.
            // Один и тот же контекст нужен и для выборки модели, и для seed.
            ItemDisplayContext displayContext = ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;

            // Во время зарядки рисуем ТОЛЬКО ванильную pulling-модель арбалета
            // (crossbow_pulling_0/1/2): в ней тетива уже натянута. Стадию
            // выбираем по синхронизированному прогрессу. Обычную модель
            // арбалета в этот момент не рисуем, чтобы не накладывать её поверх.
            boolean charging = entity instanceof BaseGuardianEntity guardianEntity
                    && guardianEntity.isCharging();
            if (charging) {
                float progress = Mth.clamp(
                        ((BaseGuardianEntity) entity).getCrossbowChargeProgress(), 0.0F, 1.0F);
                String stage;
                if (progress < 0.33F) {
                    stage = "crossbow_pulling_0";
                } else if (progress < 0.66F) {
                    stage = "crossbow_pulling_1";
                } else {
                    stage = "crossbow_pulling_2";
                }

                ModelManager modelManager = Minecraft.getInstance().getModelManager();
                BakedModel pullingModel = modelManager.getModel(
                        ModelResourceLocation.vanilla(stage, "inventory"));
                // Overlay: NO_OVERLAY, а не FULL_BRIGHT. FULL_BRIGHT — это
                // координаты света, а не overlay; в слоте overlay он даёт
                // u=15, v=15 и попадает в полупрозрачную строку текстуры
                // урона. Шейдерпаки (Oculus/Iris) читают overlay как данные
                // эффектов, поэтому оружие начинало мигать/чернеть.
                Minecraft.getInstance().getItemRenderer().render(
                        itemstack,
                        displayContext,
                        false,
                        poseStack,
                        buffer,
                        packedLight,
                        OverlayTexture.NO_OVERLAY,
                        pullingModel
                );
            } else {
                // Обычная модель арбалета (или меча) вне зарядки.
                //
                // Ванильный ItemInHandRenderer.renderItem — единственный путь,
                // который сам подставляет OverlayTexture.NO_OVERLAY и seed
                // entity.getId() + displayContext.ordinal(), а также передаёт
                // сущность в ItemRenderer.getModel. Любая ручная комбинация
                // аргументов renderStatic расходится с этим контрактом и
                // ломает слой шейдеров Oculus.
                Minecraft.getInstance().getEntityRenderDispatcher()
                        .getItemInHandRenderer()
                        .renderItem(entity, itemstack, displayContext, false,
                                poseStack, buffer, packedLight);
            }

            poseStack.popPose();
        }
    }
}

