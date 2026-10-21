package net.minecraft.world.entity.projectile;

import com.google.common.base.MoreObjects;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public abstract class Projectile extends Entity implements TraceableEntity {
   @Nullable
   private UUID ownerUUID;
   @Nullable
   private Entity cachedOwner;
   private boolean leftOwner;
   private boolean hasBeenShot;

   Projectile(EntityType<? extends Projectile> $$0, Level $$1) {
      super($$0, $$1);
   }

   public void setOwner(@Nullable Entity $$0) {
      if ($$0 != null) {
         this.ownerUUID = $$0.getUUID();
         this.cachedOwner = $$0;
      }
   }

   @Nullable
   @Override
   public Entity getOwner() {
      if (this.cachedOwner != null && !this.cachedOwner.isRemoved()) {
         return this.cachedOwner;
      } else if (this.ownerUUID != null && this.level() instanceof ServerLevel) {
         this.cachedOwner = ((ServerLevel)this.level()).getEntity(this.ownerUUID);
         return this.cachedOwner;
      } else {
         return null;
      }
   }

   public Entity getEffectSource() {
      return (Entity)MoreObjects.firstNonNull(this.getOwner(), this);
   }

   @Override
   protected void addAdditionalSaveData(CompoundTag $$0) {
      if (this.ownerUUID != null) {
         $$0.putUUID("Owner", this.ownerUUID);
      }

      if (this.leftOwner) {
         $$0.putBoolean("LeftOwner", true);
      }

      $$0.putBoolean("HasBeenShot", this.hasBeenShot);
   }

   protected boolean ownedBy(Entity $$0) {
      return $$0.getUUID().equals(this.ownerUUID);
   }

   @Override
   protected void readAdditionalSaveData(CompoundTag $$0) {
      if ($$0.hasUUID("Owner")) {
         this.ownerUUID = $$0.getUUID("Owner");
         this.cachedOwner = null;
      }

      this.leftOwner = $$0.getBoolean("LeftOwner");
      this.hasBeenShot = $$0.getBoolean("HasBeenShot");
   }

   @Override
   public void tick() {
      if (!this.hasBeenShot) {
         this.gameEvent(GameEvent.PROJECTILE_SHOOT, this.getOwner());
         this.hasBeenShot = true;
      }

      if (!this.leftOwner) {
         this.leftOwner = this.checkLeftOwner();
      }

      super.tick();
   }

   private boolean checkLeftOwner() {
      Entity $$0 = this.getOwner();
      if ($$0 != null) {
         for (Entity $$1 : this.level()
            .getEntities(this, this.getBoundingBox().expandTowards(this.getDeltaMovement()).inflate(1.0), $$0x -> !$$0x.isSpectator() && $$0x.isPickable())) {
            if ($$1.getRootVehicle() == $$0.getRootVehicle()) {
               return false;
            }
         }
      }

      return true;
   }

   public void shoot(double $$0, double $$1, double $$2, float $$3, float $$4) {
      Vec3 $$5 = new Vec3($$0, $$1, $$2)
         .normalize()
         .add(this.random.triangle(0.0, 0.0172275 * $$4), this.random.triangle(0.0, 0.0172275 * $$4), this.random.triangle(0.0, 0.0172275 * $$4))
         .scale($$3);
      this.setDeltaMovement($$5);
      double $$6 = $$5.horizontalDistance();
      this.setYRot((float)(Mth.atan2($$5.x, $$5.z) * 180.0F / (float)Math.PI));
      this.setXRot((float)(Mth.atan2($$5.y, $$6) * 180.0F / (float)Math.PI));
      this.yRotO = this.getYRot();
      this.xRotO = this.getXRot();
   }

   public void shootFromRotation(Entity $$0, float $$1, float $$2, float $$3, float $$4, float $$5) {
      float $$6 = -Mth.sin($$2 * (float) (Math.PI / 180.0)) * Mth.cos($$1 * (float) (Math.PI / 180.0));
      float $$7 = -Mth.sin(($$1 + $$3) * (float) (Math.PI / 180.0));
      float $$8 = Mth.cos($$2 * (float) (Math.PI / 180.0)) * Mth.cos($$1 * (float) (Math.PI / 180.0));
      this.shoot($$6, $$7, $$8, $$4, $$5);
      Vec3 $$9 = $$0.getDeltaMovement();
      this.setDeltaMovement(this.getDeltaMovement().add($$9.x, $$0.onGround() ? 0.0 : $$9.y, $$9.z));
   }

   protected void onHit(HitResult $$0) {
      HitResult.Type $$1 = $$0.getType();
      if ($$1 == HitResult.Type.ENTITY) {
         this.onHitEntity((EntityHitResult)$$0);
         this.level().gameEvent(GameEvent.PROJECTILE_LAND, $$0.getLocation(), GameEvent.Context.of(this, null));
      } else if ($$1 == HitResult.Type.BLOCK) {
         BlockHitResult $$2 = (BlockHitResult)$$0;
         this.onHitBlock($$2);
         BlockPos $$3 = $$2.getBlockPos();
         this.level().gameEvent(GameEvent.PROJECTILE_LAND, $$3, GameEvent.Context.of(this, this.level().getBlockState($$3)));
      }
   }

   protected void onHitEntity(EntityHitResult $$0) {
   }

   protected void onHitBlock(BlockHitResult $$0) {
      BlockState $$1 = this.level().getBlockState($$0.getBlockPos());
      $$1.onProjectileHit(this.level(), $$1, $$0, this);
   }

   @Override
   public void lerpMotion(double $$0, double $$1, double $$2) {
      this.setDeltaMovement($$0, $$1, $$2);
      if (this.xRotO == 0.0F && this.yRotO == 0.0F) {
         double $$3 = Math.sqrt($$0 * $$0 + $$2 * $$2);
         this.setXRot((float)(Mth.atan2($$1, $$3) * 180.0F / (float)Math.PI));
         this.setYRot((float)(Mth.atan2($$0, $$2) * 180.0F / (float)Math.PI));
         this.xRotO = this.getXRot();
         this.yRotO = this.getYRot();
         this.moveTo(this.getX(), this.getY(), this.getZ(), this.getYRot(), this.getXRot());
      }
   }

   protected boolean canHitEntity(Entity $$0) {
      if (!$$0.canBeHitByProjectile()) {
         return false;
      } else {
         Entity $$1 = this.getOwner();
         return $$1 == null || this.leftOwner || !$$1.isPassengerOfSameVehicle($$0);
      }
   }

   protected void updateRotation() {
      Vec3 $$0 = this.getDeltaMovement();
      double $$1 = $$0.horizontalDistance();
      this.setXRot(lerpRotation(this.xRotO, (float)(Mth.atan2($$0.y, $$1) * 180.0F / (float)Math.PI)));
      this.setYRot(lerpRotation(this.yRotO, (float)(Mth.atan2($$0.x, $$0.z) * 180.0F / (float)Math.PI)));
   }

   protected static float lerpRotation(float $$0, float $$1) {
      while ($$1 - $$0 < -180.0F) {
         $$0 -= 360.0F;
      }

      while ($$1 - $$0 >= 180.0F) {
         $$0 += 360.0F;
      }

      return Mth.lerp(0.2F, $$0, $$1);
   }

   @Override
   public Packet<ClientGamePacketListener> getAddEntityPacket() {
      Entity $$0 = this.getOwner();
      return new ClientboundAddEntityPacket(this, $$0 == null ? 0 : $$0.getId());
   }

   @Override
   public void recreateFromPacket(ClientboundAddEntityPacket $$0) {
      super.recreateFromPacket($$0);
      Entity $$1 = this.level().getEntity($$0.getData());
      if ($$1 != null) {
         this.setOwner($$1);
      }
   }

   @Override
   public boolean mayInteract(Level $$0, BlockPos $$1) {
      Entity $$2 = this.getOwner();
      return $$2 instanceof Player ? $$2.mayInteract($$0, $$1) : $$2 == null || $$0.getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING);
   }
}