package com.phian404.survivalpoints.client;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Quaternionf;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import net.minecraft.world.item.Items;

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

            boolean isSword = itemstack.getItem() instanceof net.minecraft.world.item.SwordItem;
            if (isSword) {
                // МЕЧ: Смещаем вниз к кисти (увеличиваем Y) и центрируем
                poseStack.translate(-0.05D, 0.4D, -0.10D); // <-- ИЗМЕНЕНО ЗДЕСЬ
                poseStack.mulPose(new Quaternionf().rotationX((float) Math.toRadians(-85)));
                poseStack.mulPose(new Quaternionf().rotationZ((float) Math.toRadians(0)));
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
                poseStack.translate(0D, 0.7D + chargeOffset, -0.15D);
                poseStack.mulPose(new Quaternionf().rotationX((float) Math.toRadians(90)));
                poseStack.scale(0.8F, 0.8F, 0.8F);
            }

            // Отрисовываем предмет
            Minecraft.getInstance().getItemRenderer().renderStatic(
                itemstack,
                ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                packedLight,
                net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,
                poseStack,
                buffer,
                entity.level(),
                0
            );

            poseStack.popPose();
        }
    }
}

