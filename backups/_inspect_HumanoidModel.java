package net.minecraft.client.model;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.function.Function;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;

public class HumanoidModel<T extends LivingEntity> extends AgeableListModel<T> implements ArmedModel, HeadedModel {
   public static final float OVERLAY_SCALE = 0.25F;
   public static final float HAT_OVERLAY_SCALE = 0.5F;
   public static final float LEGGINGS_OVERLAY_SCALE = -0.1F;
   private static final float DUCK_WALK_ROTATION = 0.005F;
   private static final float SPYGLASS_ARM_ROT_Y = (float) (Math.PI / 12);
   private static final float SPYGLASS_ARM_ROT_X = 1.9198622F;
   private static final float SPYGLASS_ARM_CROUCH_ROT_X = (float) (Math.PI / 12);
   public static final float TOOT_HORN_XROT_BASE = 1.4835298F;
   public static final float TOOT_HORN_YROT_BASE = (float) (Math.PI / 6);
   public final ModelPart head;
   public final ModelPart hat;
   public final ModelPart body;
   public final ModelPart rightArm;
   public final ModelPart leftArm;
   public final ModelPart rightLeg;
   public final ModelPart leftLeg;
   public HumanoidModel.ArmPose leftArmPose = HumanoidModel.ArmPose.EMPTY;
   public HumanoidModel.ArmPose rightArmPose = HumanoidModel.ArmPose.EMPTY;
   public boolean crouching;
   public float swimAmount;

   public HumanoidModel(ModelPart $$0) {
      this($$0, RenderType::entityCutoutNoCull);
   }

   public HumanoidModel(ModelPart $$0, Function<ResourceLocation, RenderType> $$1) {
      super($$1, true, 16.0F, 0.0F, 2.0F, 2.0F, 24.0F);
      this.head = $$0.getChild("head");
      this.hat = $$0.getChild("hat");
      this.body = $$0.getChild("body");
      this.rightArm = $$0.getChild("right_arm");
      this.leftArm = $$0.getChild("left_arm");
      this.rightLeg = $$0.getChild("right_leg");
      this.leftLeg = $$0.getChild("left_leg");
   }

   public static MeshDefinition createMesh(CubeDeformation $$0, float $$1) {
      MeshDefinition $$2 = new MeshDefinition();
      PartDefinition $$3 = $$2.getRoot();
      $$3.addOrReplaceChild(
         "head", CubeListBuilder.create().texOffs(0, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, $$0), PartPose.offset(0.0F, 0.0F + $$1, 0.0F)
      );
      $$3.addOrReplaceChild(
         "hat",
         CubeListBuilder.create().texOffs(32, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, $$0.extend(0.5F)),
         PartPose.offset(0.0F, 0.0F + $$1, 0.0F)
      );
      $$3.addOrReplaceChild(
         "body", CubeListBuilder.create().texOffs(16, 16).addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, $$0), PartPose.offset(0.0F, 0.0F + $$1, 0.0F)
      );
      $$3.addOrReplaceChild(
         "right_arm", CubeListBuilder.create().texOffs(40, 16).addBox(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, $$0), PartPose.offset(-5.0F, 2.0F + $$1, 0.0F)
      );
      $$3.addOrReplaceChild(
         "left_arm",
         CubeListBuilder.create().texOffs(40, 16).mirror().addBox(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, $$0),
         PartPose.offset(5.0F, 2.0F + $$1, 0.0F)
      );
      $$3.addOrReplaceChild(
         "right_leg", CubeListBuilder.create().texOffs(0, 16).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, $$0), PartPose.offset(-1.9F, 12.0F + $$1, 0.0F)
      );
      $$3.addOrReplaceChild(
         "left_leg",
         CubeListBuilder.create().texOffs(0, 16).mirror().addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, $$0),
         PartPose.offset(1.9F, 12.0F + $$1, 0.0F)
      );
      return $$2;
   }

   @Override
   protected Iterable<ModelPart> headParts() {
      return ImmutableList.of(this.head);
   }

   @Override
   protected Iterable<ModelPart> bodyParts() {
      return ImmutableList.of(this.body, this.rightArm, this.leftArm, this.rightLeg, this.leftLeg, this.hat);
   }

   public void prepareMobModel(T $$0, float $$1, float $$2, float $$3) {
      this.swimAmount = $$0.getSwimAmount($$3);
      super.prepareMobModel($$0, $$1, $$2, $$3);
   }

   public void setupAnim(T $$0, float $$1, float $$2, float $$3, float $$4, float $$5) {
      boolean $$6 = $$0.getFallFlyingTicks() > 4;
      boolean $$7 = $$0.isVisuallySwimming();
      this.head.yRot = $$4 * (float) (Math.PI / 180.0);
      if ($$6) {
         this.head.xRot = (float) (-Math.PI / 4);
      } else if (this.swimAmount > 0.0F) {
         if ($$7) {
            this.head.xRot = this.rotlerpRad(this.swimAmount, this.head.xRot, (float) (-Math.PI / 4));
         } else {
            this.head.xRot = this.rotlerpRad(this.swimAmount, this.head.xRot, $$5 * (float) (Math.PI / 180.0));
         }
      } else {
         this.head.xRot = $$5 * (float) (Math.PI / 180.0);
      }

      this.body.yRot = 0.0F;
      this.rightArm.z = 0.0F;
      this.rightArm.x = -5.0F;
      this.leftArm.z = 0.0F;
      this.leftArm.x = 5.0F;
      float $$8 = 1.0F;
      if ($$6) {
         $$8 = (float)$$0.getDeltaMovement().lengthSqr();
         $$8 /= 0.2F;
         $$8 *= $$8 * $$8;
      }

      if ($$8 < 1.0F) {
         $$8 = 1.0F;
      }

      this.rightArm.xRot = Mth.cos($$1 * 0.6662F + (float) Math.PI) * 2.0F * $$2 * 0.5F / $$8;
      this.leftArm.xRot = Mth.cos($$1 * 0.6662F) * 2.0F * $$2 * 0.5F / $$8;
      this.rightArm.zRot = 0.0F;
      this.leftArm.zRot = 0.0F;
      this.rightLeg.xRot = Mth.cos($$1 * 0.6662F) * 1.4F * $$2 / $$8;
      this.leftLeg.xRot = Mth.cos($$1 * 0.6662F + (float) Math.PI) * 1.4F * $$2 / $$8;
      this.rightLeg.yRot = 0.005F;
      this.leftLeg.yRot = -0.005F;
      this.rightLeg.zRot = 0.005F;
      this.leftLeg.zRot = -0.005F;
      if (this.riding) {
         this.rightArm.xRot += (float) (-Math.PI / 5);
         this.leftArm.xRot += (float) (-Math.PI / 5);
         this.rightLeg.xRot = -1.4137167F;
         this.rightLeg.yRot = (float) (Math.PI / 10);
         this.rightLeg.zRot = 0.07853982F;
         this.leftLeg.xRot = -1.4137167F;
         this.leftLeg.yRot = (float) (-Math.PI / 10);
         this.leftLeg.zRot = -0.07853982F;
      }

      this.rightArm.yRot = 0.0F;
      this.leftArm.yRot = 0.0F;
      boolean $$9 = $$0.getMainArm() == HumanoidArm.RIGHT;
      if ($$0.isUsingItem()) {
         boolean $$10 = $$0.getUsedItemHand() == InteractionHand.MAIN_HAND;
         if ($$10 == $$9) {
            this.poseRightArm($$0);
         } else {
            this.poseLeftArm($$0);
         }
      } else {
         boolean $$11 = $$9 ? this.leftArmPose.isTwoHanded() : this.rightArmPose.isTwoHanded();
         if ($$9 != $$11) {
            this.poseLeftArm($$0);
            this.poseRightArm($$0);
         } else {
            this.poseRightArm($$0);
            this.poseLeftArm($$0);
         }
      }

      this.setupAttackAnimation($$0, $$3);
      if (this.crouching) {
         this.body.xRot = 0.5F;
         this.rightArm.xRot += 0.4F;
         this.leftArm.xRot += 0.4F;
         this.rightLeg.z = 4.0F;
         this.leftLeg.z = 4.0F;
         this.rightLeg.y = 12.2F;
         this.leftLeg.y = 12.2F;
         this.head.y = 4.2F;
         this.body.y = 3.2F;
         this.leftArm.y = 5.2F;
         this.rightArm.y = 5.2F;
      } else {
         this.body.xRot = 0.0F;
         this.rightLeg.z = 0.0F;
         this.leftLeg.z = 0.0F;
         this.rightLeg.y = 12.0F;
         this.leftLeg.y = 12.0F;
         this.head.y = 0.0F;
         this.body.y = 0.0F;
         this.leftArm.y = 2.0F;
         this.rightArm.y = 2.0F;
      }

      if (this.rightArmPose != HumanoidModel.ArmPose.SPYGLASS) {
         AnimationUtils.bobModelPart(this.rightArm, $$3, 1.0F);
      }

      if (this.leftArmPose != HumanoidModel.ArmPose.SPYGLASS) {
         AnimationUtils.bobModelPart(this.leftArm, $$3, -1.0F);
      }

      if (this.swimAmount > 0.0F) {
         float $$12 = $$1 % 26.0F;
         HumanoidArm $$13 = this.getAttackArm($$0);
         float $$14 = $$13 == HumanoidArm.RIGHT && this.attackTime > 0.0F ? 0.0F : this.swimAmount;
         float $$15 = $$13 == HumanoidArm.LEFT && this.attackTime > 0.0F ? 0.0F : this.swimAmount;
         if (!$$0.isUsingItem()) {
            if ($$12 < 14.0F) {
               this.leftArm.xRot = this.rotlerpRad($$15, this.leftArm.xRot, 0.0F);
               this.rightArm.xRot = Mth.lerp($$14, this.rightArm.xRot, 0.0F);
               this.leftArm.yRot = this.rotlerpRad($$15, this.leftArm.yRot, (float) Math.PI);
               this.rightArm.yRot = Mth.lerp($$14, this.rightArm.yRot, (float) Math.PI);
               this.leftArm.zRot = this.rotlerpRad(
                  $$15, this.leftArm.zRot, (float) Math.PI + 1.8707964F * this.quadraticArmUpdate($$12) / this.quadraticArmUpdate(14.0F)
               );
               this.rightArm.zRot = Mth.lerp(
                  $$14, this.rightArm.zRot, (float) Math.PI - 1.8707964F * this.quadraticArmUpdate($$12) / this.quadraticArmUpdate(14.0F)
               );
            } else if ($$12 >= 14.0F && $$12 < 22.0F) {
               float $$16 = ($$12 - 14.0F) / 8.0F;
               this.leftArm.xRot = this.rotlerpRad($$15, this.leftArm.xRot, (float) (Math.PI / 2) * $$16);
               this.rightArm.xRot = Mth.lerp($$14, this.rightArm.xRot, (float) (Math.PI / 2) * $$16);
               this.leftArm.yRot = this.rotlerpRad($$15, this.leftArm.yRot, (float) Math.PI);
               this.rightArm.yRot = Mth.lerp($$14, this.rightArm.yRot, (float) Math.PI);
               this.leftArm.zRot = this.rotlerpRad($$15, this.leftArm.zRot, 5.012389F - 1.8707964F * $$16);
               this.rightArm.zRot = Mth.lerp($$14, this.rightArm.zRot, 1.2707963F + 1.8707964F * $$16);
            } else if ($$12 >= 22.0F && $$12 < 26.0F) {
               float $$17 = ($$12 - 22.0F) / 4.0F;
               this.leftArm.xRot = this.rotlerpRad($$15, this.leftArm.xRot, (float) (Math.PI / 2) - (float) (Math.PI / 2) * $$17);
               this.rightArm.xRot = Mth.lerp($$14, this.rightArm.xRot, (float) (Math.PI / 2) - (float) (Math.PI / 2) * $$17);
               this.leftArm.yRot = this.rotlerpRad($$15, this.leftArm.yRot, (float) Math.PI);
               this.rightArm.yRot = Mth.lerp($$14, this.rightArm.yRot, (float) Math.PI);
               this.leftArm.zRot = this.rotlerpRad($$15, this.leftArm.zRot, (float) Math.PI);
               this.rightArm.zRot = Mth.lerp($$14, this.rightArm.zRot, (float) Math.PI);
            }
         }

         float $$18 = 0.3F;
         float $$19 = 0.33333334F;
         this.leftLeg.xRot = Mth.lerp(this.swimAmount, this.leftLeg.xRot, 0.3F * Mth.cos($$1 * 0.33333334F + (float) Math.PI));
         this.rightLeg.xRot = Mth.lerp(this.swimAmount, this.rightLeg.xRot, 0.3F * Mth.cos($$1 * 0.33333334F));
      }

      this.hat.copyFrom(this.head);
   }

   private void poseRightArm(T $$0) {
      switch (this.rightArmPose) {
         case EMPTY:
            this.rightArm.yRot = 0.0F;
            break;
         case BLOCK:
            this.rightArm.xRot = this.rightArm.xRot * 0.5F - 0.9424779F;
            this.rightArm.yRot = (float) (-Math.PI / 6);
            break;
         case ITEM:
            this.rightArm.xRot = this.rightArm.xRot * 0.5F - (float) (Math.PI / 10);
            this.rightArm.yRot = 0.0F;
            break;
         case THROW_SPEAR:
            this.rightArm.xRot = this.rightArm.xRot * 0.5F - (float) Math.PI;
            this.rightArm.yRot = 0.0F;
            break;
         case BOW_AND_ARROW:
            this.rightArm.yRot = -0.1F + this.head.yRot;
            this.leftArm.yRot = 0.1F + this.head.yRot + 0.4F;
            this.rightArm.xRot = (float) (-Math.PI / 2) + this.head.xRot;
            this.leftArm.xRot = (float) (-Math.PI / 2) + this.head.xRot;
            break;
         case CROSSBOW_CHARGE:
            AnimationUtils.animateCrossbowCharge(this.rightArm, this.leftArm, $$0, true);
            break;
         case CROSSBOW_HOLD:
            AnimationUtils.animateCrossbowHold(this.rightArm, this.leftArm, this.head, true);
            break;
         case BRUSH:
            this.rightArm.xRot = this.rightArm.xRot * 0.5F - (float) (Math.PI / 5);
            this.rightArm.yRot = 0.0F;
            break;
         case SPYGLASS:
            this.rightArm.xRot = Mth.clamp(this.head.xRot - 1.9198622F - ($$0.isCrouching() ? (float) (Math.PI / 12) : 0.0F), -2.4F, 3.3F);
            this.rightArm.yRot = this.head.yRot - (float) (Math.PI / 12);
            break;
         case TOOT_HORN:
            this.rightArm.xRot = Mth.clamp(this.head.xRot, -1.2F, 1.2F) - 1.4835298F;
            this.rightArm.yRot = this.head.yRot - (float) (Math.PI / 6);
      }
   }

   private void poseLeftArm(T $$0) {
      switch (this.leftArmPose) {
         case EMPTY:
            this.leftArm.yRot = 0.0F;
            break;
         case BLOCK:
            this.leftArm.xRot = this.leftArm.xRot * 0.5F - 0.9424779F;
            this.leftArm.yRot = (float) (Math.PI / 6);
            break;
         case ITEM:
            this.leftArm.xRot = this.leftArm.xRot * 0.5F - (float) (Math.PI / 10);
            this.leftArm.yRot = 0.0F;
            break;
         case THROW_SPEAR:
            this.leftArm.xRot = this.leftArm.xRot * 0.5F - (float) Math.PI;
            this.leftArm.yRot = 0.0F;
            break;
         case BOW_AND_ARROW:
            this.rightArm.yRot = -0.1F + this.head.yRot - 0.4F;
            this.leftArm.yRot = 0.1F + this.head.yRot;
            this.rightArm.xRot = (float) (-Math.PI / 2) + this.head.xRot;
            this.leftArm.xRot = (float) (-Math.PI / 2) + this.head.xRot;
            break;
         case CROSSBOW_CHARGE:
            AnimationUtils.animateCrossbowCharge(this.rightArm, this.leftArm, $$0, false);
            break;
         case CROSSBOW_HOLD:
            AnimationUtils.animateCrossbowHold(this.rightArm, this.leftArm, this.head, false);
            break;
         case BRUSH:
            this.leftArm.xRot = this.leftArm.xRot * 0.5F - (float) (Math.PI / 5);
            this.leftArm.yRot = 0.0F;
            break;
         case SPYGLASS:
            this.leftArm.xRot = Mth.clamp(this.head.xRot - 1.9198622F - ($$0.isCrouching() ? (float) (Math.PI / 12) : 0.0F), -2.4F, 3.3F);
            this.leftArm.yRot = this.head.yRot + (float) (Math.PI / 12);
            break;
         case TOOT_HORN:
            this.leftArm.xRot = Mth.clamp(this.head.xRot, -1.2F, 1.2F) - 1.4835298F;
            this.leftArm.yRot = this.head.yRot + (float) (Math.PI / 6);
      }
   }

   protected void setupAttackAnimation(T $$0, float $$1) {
      if (!(this.attackTime <= 0.0F)) {
         HumanoidArm $$2 = this.getAttackArm($$0);
         ModelPart $$3 = this.getArm($$2);
         float $$4 = this.attackTime;
         this.body.yRot = Mth.sin(Mth.sqrt($$4) * (float) (Math.PI * 2)) * 0.2F;
         if ($$2 == HumanoidArm.LEFT) {
            this.body.yRot *= -1.0F;
         }

         this.rightArm.z = Mth.sin(this.body.yRot) * 5.0F;
         this.rightArm.x = -Mth.cos(this.body.yRot) * 5.0F;
         this.leftArm.z = -Mth.sin(this.body.yRot) * 5.0F;
         this.leftArm.x = Mth.cos(this.body.yRot) * 5.0F;
         this.rightArm.yRot = this.rightArm.yRot + this.body.yRot;
         this.leftArm.yRot = this.leftArm.yRot + this.body.yRot;
         this.leftArm.xRot = this.leftArm.xRot + this.body.yRot;
         $$4 = 1.0F - this.attackTime;
         $$4 *= $$4;
         $$4 *= $$4;
         $$4 = 1.0F - $$4;
         float $$5 = Mth.sin($$4 * (float) Math.PI);
         float $$6 = Mth.sin(this.attackTime * (float) Math.PI) * -(this.head.xRot - 0.7F) * 0.75F;
         $$3.xRot -= $$5 * 1.2F + $$6;
         $$3.yRot = $$3.yRot + this.body.yRot * 2.0F;
         $$3.zRot = $$3.zRot + Mth.sin(this.attackTime * (float) Math.PI) * -0.4F;
      }
   }

   protected float rotlerpRad(float $$0, float $$1, float $$2) {
      float $$3 = ($$2 - $$1) % (float) (Math.PI * 2);
      if ($$3 < (float) -Math.PI) {
         $$3 += (float) (Math.PI * 2);
      }

      if ($$3 >= (float) Math.PI) {
         $$3 -= (float) (Math.PI * 2);
      }

      return $$1 + $$0 * $$3;
   }

   private float quadraticArmUpdate(float $$0) {
      return -65.0F * $$0 + $$0 * $$0;
   }

   public void copyPropertiesTo(HumanoidModel<T> $$0) {
      super.copyPropertiesTo($$0);
      $$0.leftArmPose = this.leftArmPose;
      $$0.rightArmPose = this.rightArmPose;
      $$0.crouching = this.crouching;
      $$0.head.copyFrom(this.head);
      $$0.hat.copyFrom(this.hat);
      $$0.body.copyFrom(this.body);
      $$0.rightArm.copyFrom(this.rightArm);
      $$0.leftArm.copyFrom(this.leftArm);
      $$0.rightLeg.copyFrom(this.rightLeg);
      $$0.leftLeg.copyFrom(this.leftLeg);
   }

   public void setAllVisible(boolean $$0) {
      this.head.visible = $$0;
      this.hat.visible = $$0;
      this.body.visible = $$0;
      this.rightArm.visible = $$0;
      this.leftArm.visible = $$0;
      this.rightLeg.visible = $$0;
      this.leftLeg.visible = $$0;
   }

   @Override
   public void translateToHand(HumanoidArm $$0, PoseStack $$1) {
      this.getArm($$0).translateAndRotate($$1);
   }

   protected ModelPart getArm(HumanoidArm $$0) {
      return $$0 == HumanoidArm.LEFT ? this.leftArm : this.rightArm;
   }

   @Override
   public ModelPart getHead() {
      return this.head;
   }

   private HumanoidArm getAttackArm(T $$0) {
      HumanoidArm $$1 = $$0.getMainArm();
      return $$0.swingingArm == InteractionHand.MAIN_HAND ? $$1 : $$1.getOpposite();
   }

   public static enum ArmPose {
      EMPTY(false),
      ITEM(false),
      BLOCK(false),
      BOW_AND_ARROW(true),
      THROW_SPEAR(false),
      CROSSBOW_CHARGE(true),
      CROSSBOW_HOLD(true),
      SPYGLASS(false),
      TOOT_HORN(false),
      BRUSH(false);

      private final boolean twoHanded;

      private ArmPose(boolean $$0) {
         this.twoHanded = $$0;
      }

      public boolean isTwoHanded() {
         return this.twoHanded;
      }
   }
}