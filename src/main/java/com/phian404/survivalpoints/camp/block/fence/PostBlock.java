package com.phian404.survivalpoints.camp.block.fence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Опора модульной системы ограждений (спека v1.1.7-final, §5.1).
 *
 * <p>Чистое обтёсанное бревно: колонна 8×8 px по центру блока
 * (координаты {@code 4..12} по X и Z), полная высота. Никаких металлических
 * креплений, муфт, хомутов и пластин — они отклонены навсегда (D-09).
 * Направленных свойств соединений у опоры нет: секции примыкают к ней
 * по своим правилам ({@link SectionBlock}).
 *
 * <p>Стекируется вертикально без ограничения высоты: ставится на твёрдую
 * поверхность или на такую же опору. Установка на секцию запрещена.
 *
 * <p>Каскад разрушения опор (снос верхних опор при разрушении нижней)
 * в текущей волне не реализован — вынесен в отдельную волну.
 *
 * <p>Waterlog — стандартный паттерн {@link SimpleWaterloggedBlock}.
 */
public class PostBlock extends Block implements SimpleWaterloggedBlock {

    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    /** Колонна 8×8 px полной высоты (спека v1.1.7, §P). */
    private static final VoxelShape SHAPE = Block.box(4.0D, 0.0D, 4.0D, 12.0D, 16.0D, 12.0D);

    public PostBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(4.0F, 8.0F)
                .requiresCorrectToolForDrops()
                .sound(SoundType.WOOD)
                .noOcclusion());
        this.registerDefaultState(this.stateDefinition.any().setValue(WATERLOGGED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WATERLOGGED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        FluidState fluid = context.getLevel().getFluidState(context.getClickedPos());
        return this.defaultBlockState()
                .setValue(WATERLOGGED, fluid.getType() == Fluids.WATER);
    }

    /**
     * Опору можно ставить на твёрдую поверхность или на другую опору.
     * Установка на секцию запрещена (D-04): секции не несущие.
     */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos belowPos = pos.below();
        BlockState below = level.getBlockState(belowPos);
        if (below.getBlock() instanceof SectionBlock) {
            return false;
        }
        return below.getBlock() instanceof PostBlock
                || below.isFaceSturdy(level, belowPos, Direction.UP);
    }

    /**
     * Опора не переживает потерю основания: если под ней больше нет твёрдой
     * поверхности или другой опоры, она удаляется с дропом. Секции при этом
     * не затрагиваются (D-05) — они пересчитывают своё существование сами.
     */
    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
                                BlockPos fromPos, boolean isMoving) {
        if (!level.isClientSide() && !state.canSurvive(level, pos)) {
            level.destroyBlock(pos, true);
        }
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                               CollisionContext context) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return SHAPE;
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED)
                ? Fluids.WATER.getSource(false)
                : super.getFluidState(state);
    }

    @Override
    public boolean canPlaceLiquid(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        return !state.getValue(WATERLOGGED)
                && (fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER);
    }

    @Override
    public boolean placeLiquid(LevelAccessor level, BlockPos pos, BlockState state,
                               FluidState fluidState) {
        if (!state.getValue(WATERLOGGED) && fluidState.getType() == Fluids.WATER) {
            if (!level.isClientSide()) {
                level.setBlock(pos, state.setValue(WATERLOGGED, true), Block.UPDATE_ALL);
                level.scheduleTick(pos, fluidState.getType(), fluidState.getType().getTickDelay(level));
            }
            return true;
        }
        return false;
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }
}
