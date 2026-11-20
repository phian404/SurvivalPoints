package com.phian404.survivalpoints.camp.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import com.phian404.survivalpoints.init.ModBlocks;

public class CampBarbedWireBracketBlock extends HorizontalDirectionalBlock {

    public CampBarbedWireBracketBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(1.5F, 3.0F)
                .noOcclusion());
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        LevelAccessor level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockPos belowPos = pos.below();
        
        // Разрешаем установку только на забор или колючую проволоку
        BlockState below = level.getBlockState(belowPos);
        Block fence = ModBlocks.CAMP_FENCE.get();
        Block wire = ModBlocks.CAMP_BARBED_WIRE.get();
        if (below.is(fence) ||
            below.is(wire)) {
            for (Direction direction : context.getNearestLookingDirections()) {
                if (direction.getAxis().isHorizontal()) {
                    return this.defaultBlockState().setValue(FACING, direction.getOpposite());
                }
            }
        }
        
        return null;
    }
}
