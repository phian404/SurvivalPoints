package com.phian404.survivalpoints.camp.blockentity;

import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampTuning;
import com.phian404.survivalpoints.camp.block.CampRadioBlock;
import com.phian404.survivalpoints.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

public class CampRadioBlockEntity extends BlockEntity {
    private UUID campId;
    private int charge = 0;

    public CampRadioBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.CAMP_RADIO_BE.get(), pos, state);
    }

    public void tick(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide) return;
        // Удалённый лагерь мог оставить старый id в загруженном ретрансляторе.
        // Сбрасываем такую привязку и разрешаем обычный поиск лагеря заново.
        if (level instanceof net.minecraft.server.level.ServerLevel server) {
            if (campId != null && level.getGameTime() % 20L == 0L
                    && com.phian404.survivalpoints.camp.CampManager.getCamp(server, campId) == null) {
                campId = null;
                setChanged();
            }
            // Ленивая привязка не зависит от порядка onPlace/setBlockEntity.
            // Ретранслятор рядом с чужим лагерем привязывается к ближайшему.
            if (campId == null) {
                CampRecord camp = com.phian404.survivalpoints.camp.CampManager.findCampAt(server, pos);
                if (camp != null) {
                    campId = camp.getId();
                    setChanged();
                }
            }
        }
        // Radio works during day, off at night
        boolean isDay = !level.isNight();
        if (isPowered() != isDay) {
            setPowered(isDay);
            level.setBlock(pos, state.setValue(CampRadioBlock.POWERED, isDay), 3);
        }
        // Decrease charge
        if (charge > 0) charge--;
    }

    public boolean isPowered() {
        BlockState blockState = this.getBlockState();
        if (blockState == null) return false;
        return blockState.getValue(CampRadioBlock.POWERED);
    }

    public void setPowered(boolean powered) {
        // Handled via block state update in tick
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (campId != null) tag.putUUID("CampId", campId);
        tag.putInt("Charge", charge);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.hasUUID("CampId")) campId = tag.getUUID("CampId");
        charge = tag.getInt("Charge");
    }

    @Nullable
    public UUID getCampId() { return campId; }
    public void setCampId(UUID id) { this.campId = id; }
}
