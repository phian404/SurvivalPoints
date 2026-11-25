package com.phian404.survivalpoints.entity.ai.crop;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Стандартный адаптер для ванильных культур на базе {@link CropBlock}.
 *
 * <p>Поддерживаются пшеница, морковь, картофель и свёкла. Соответствия семя →
 * культура заданы здесь и не проникают в {@code FarmerWorkGoal}. Модовые
 * культуры, не наследующие {@code CropBlock}, требуют своего адаптера.
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
    public ItemStack getSeedForCrop(BlockState state) {
        if (!(state.getBlock() instanceof CropBlock crop)) {
            return null;
        }
        Item seed = seedForBlock(crop);
        return seed == null ? null : new ItemStack(seed);
    }

    @Nullable
    @Override
    public BlockState getCropForSeed(ItemStack seed) {
        if (seed.isEmpty()) {
            return null;
        }
        Item item = seed.getItem();
        if (item == Items.WHEAT_SEEDS) {
            return Blocks.WHEAT.defaultBlockState();
        }
        if (item == Items.CARROT) {
            return Blocks.CARROTS.defaultBlockState();
        }
        if (item == Items.POTATO) {
            return Blocks.POTATOES.defaultBlockState();
        }
        if (item == Items.BEETROOT_SEEDS) {
            return Blocks.BEETROOTS.defaultBlockState();
        }
        return null;
    }

    @Override
    public List<ItemStack> getDrops(ServerLevel level, BlockPos pos, BlockState state) {
        return Block.getDrops(state, level, pos, null);
    }

    @Override
    public boolean plant(ServerLevel level, BlockPos pos, BlockState crop) {
        if (!(crop.getBlock() instanceof CropBlock block)) {
            return false;
        }
        if (!level.getBlockState(pos).isAir()) {
            return false;
        }
        if (!level.getBlockState(pos.below()).is(Blocks.FARMLAND)) {
            return false;
        }
        BlockState planted = block.getStateForAge(0);
        if (!planted.canSurvive(level, pos)) {
            return false;
        }
        return level.setBlock(pos, planted, Block.UPDATE_CLIENTS);
    }

    @Nullable
    private static Item seedForBlock(CropBlock crop) {
        Block block = crop;
        if (block == Blocks.WHEAT) {
            return Items.WHEAT_SEEDS;
        }
        if (block == Blocks.CARROTS) {
            return Items.CARROT;
        }
        if (block == Blocks.POTATOES) {
            return Items.POTATO;
        }
        if (block == Blocks.BEETROOTS) {
            return Items.BEETROOT_SEEDS;
        }
        return null;
    }
}
