package com.phian404.survivalpoints.entity.ai.crop;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Стандартный адаптер для культур на базе {@link CropBlock}.
 *
 * <p>Семена определяются через публичный {@link Block#getCloneItemStack()} —
 * для пшеницы это семена пшеницы. Это ограничение API изолировано в адаптере:
 * модовые культуры, не наследующие {@code CropBlock}, требуют своего адаптера.
 */
public final class CropBlockAdapter implements CropAdapter {

    public static final CropBlockAdapter INSTANCE = new CropBlockAdapter();

    private CropBlockAdapter() {
    }

    @Override
    public boolean supports(BlockState state) {
        return state.getBlock() instanceof CropBlock;
    }

    @Override
    public boolean isMature(BlockState state) {
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }

    @Nullable
    @Override
    public ItemStack getSeed(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof CropBlock crop)) {
            return null;
        }
        ItemStack seed = crop.getCloneItemStack(level, pos, state);
        return seed.isEmpty() ? null : seed;
    }

    @Override
    public List<ItemStack> getDrops(ServerLevel level, BlockPos pos, BlockState state) {
        return Block.getDrops(state, level, pos, null);
    }

    @Override
    public boolean plant(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof CropBlock crop)) {
            return false;
        }
        BlockState planted = crop.getStateForAge(0);
        if (!planted.canSurvive(level, pos)) {
            return false;
        }
        level.setBlock(pos, planted, Block.UPDATE_CLIENTS);
        return true;
    }
}
