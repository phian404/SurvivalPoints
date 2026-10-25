package com.phian404.survivalpoints.camp.block;

import net.minecraft.world.level.block.state.properties.BooleanProperty;
import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampTuning;
import com.phian404.survivalpoints.camp.blockentity.CampRadioBlockEntity;
import com.phian404.survivalpoints.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.BlockBehaviour;
// Material import removed — using fully qualified reference
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

public class CampRadioBlock extends BaseEntityBlock {
    public static final BooleanProperty POWERED = net.minecraft.world.level.block.state.properties.BooleanProperty.create("powered");

    public CampRadioBlock() {
        super(Properties.of()
                .strength(3.0F, 6.0F)
                .requiresCorrectToolForDrops());
        this.registerDefaultState(this.stateDefinition.any().setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
        super.createBlockStateDefinition(builder);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CampRadioBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && level instanceof ServerLevel server) {
            CampRecord camp = CampManager.findCampAt(server, pos);
            if (camp != null) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§aЛагерь «" + camp.getName() + "» §7(якорь: радио вышка)"), false);
            }
        }
        return InteractionResult.SUCCESS;
    }

@Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        if (type != ModBlocks.CAMP_RADIO_BE.get()) return null;
        return (lvl, pos, st, be) -> {
            if (be instanceof CampRadioBlockEntity radio) {
                radio.tick(lvl, pos, st);
            }
        };
    }
}
