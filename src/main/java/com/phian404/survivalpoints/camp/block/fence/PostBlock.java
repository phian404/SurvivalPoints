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
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
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
 * <p><b>Коллизия шире визуала.</b> Визуально к опоре примыкает «язык» секции
 * ({@code Z -4..0}), который закрывает зазор до колонны. В коллизии языка нет,
 * и на стыке оставалась щель 4 px: сквозь неё мобы видели двор, а стрелы и
 * луки проходили насквозь. Поэтому {@link #getCollisionShape} добавляет к
 * колонне сплошные крылья до границы блока в каждую сторону, где стоит
 * секция, — они перекрывают ровно тот зазор, который визуально закрыт языком.
 *
 * <p>Высота коллизии, как и у деревянного забора ({@link PlankSectionBlock}):
 * пока сверху воздух, опора поднимается до 1.5 блока, чтобы углы заборчика
 * нельзя было перепрыгнуть. Если сверху что-то стоит (следующая опора,
 * проволока, перекрытие вышки), высота остаётся {@code 0..16}.
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

    /*
     * Крылья коллизии: полоса от границы блока до колонны, толщиной 6 px —
     * вровень с полотном секции (SectionBlock, COLLISION_*), чтобы на стыке
     * не оставалось ни щели, ни ступеньки.
     */
    private static final VoxelShape WING_NORTH =
            Block.box(5.0D, 0.0D, 0.0D, 11.0D, 16.0D, 4.0D);
    private static final VoxelShape WING_SOUTH =
            Block.box(5.0D, 0.0D, 12.0D, 11.0D, 16.0D, 16.0D);
    private static final VoxelShape WING_WEST =
            Block.box(0.0D, 0.0D, 5.0D, 4.0D, 16.0D, 11.0D);
    private static final VoxelShape WING_EAST =
            Block.box(12.0D, 0.0D, 5.0D, 16.0D, 16.0D, 11.0D);

    /*
     * Те же крылья, поднятые до 1.5 блока: используются, когда сверху воздух
     * и опора работает как угол деревянного заборчика.
     */
    private static final VoxelShape TALL = Block.box(4.0D, 0.0D, 4.0D, 12.0D, 24.0D, 12.0D);
    private static final VoxelShape TALL_NORTH =
            Block.box(5.0D, 0.0D, 0.0D, 11.0D, 24.0D, 4.0D);
    private static final VoxelShape TALL_SOUTH =
            Block.box(5.0D, 0.0D, 12.0D, 11.0D, 24.0D, 16.0D);
    private static final VoxelShape TALL_WEST =
            Block.box(0.0D, 0.0D, 5.0D, 4.0D, 24.0D, 11.0D);
    private static final VoxelShape TALL_EAST =
            Block.box(12.0D, 0.0D, 5.0D, 16.0D, 24.0D, 11.0D);

    public PostBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(4.0F, 8.0F)
                .requiresCorrectToolForDrops()
                .sound(SoundType.WOOD)
                .noOcclusion()
                .dynamicShape());
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

    /**
     * Опора непроходима для поиска пути, как ванильный {@code FenceBlock}.
     * Без этого мобы считали клетку проходимой (коллизия не полный блок)
     * и упирались в столб вместо поиска обхода.
     */
    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos,
                                  PathComputationType type) {
        return false;
    }

    /**
     * Коллизия опоры: колонна плюс сплошные крылья в стороны, где стоит
     * секция, и высота 1.5 блока, пока сверху воздух.
     *
     * <p>Крыло повторяет язык модели: он тоже доходит до границы блока и
     * закрывает зазор до колонны. Визуальная форма ({@link #getShape})
     * остаётся чистым бревном.
     *
     * <p>Высота берётся из фактического соседа сверху. В свойствах блока
     * обязателен {@code dynamicShape()}: иначе {@code BlockStateBase.Cache}
     * запомнил бы форму, посчитанную при инициализации блоков, и признак
     * «сверху воздух» всегда был бы истинным.
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        boolean north = isSectionAt(level, pos, Direction.NORTH);
        boolean south = isSectionAt(level, pos, Direction.SOUTH);
        boolean west = isSectionAt(level, pos, Direction.WEST);
        boolean east = isSectionAt(level, pos, Direction.EAST);

        boolean tall = level.getBlockState(pos.above()).isAir();
        VoxelShape shape = tall ? TALL : SHAPE;
        if (north) {
            shape = Shapes.or(shape, tall ? TALL_NORTH : WING_NORTH);
        }
        if (south) {
            shape = Shapes.or(shape, tall ? TALL_SOUTH : WING_SOUTH);
        }
        if (west) {
            shape = Shapes.or(shape, tall ? TALL_WEST : WING_WEST);
        }
        if (east) {
            shape = Shapes.or(shape, tall ? TALL_EAST : WING_EAST);
        }
        return shape;
    }

    /**
     * Стоит ли в указанном направлении секция ограждения: лист, сетка,
     * доски. Проволока крыло не получает — она проходима и не должна
     * становиться стеной.
     */
    private static boolean isSectionAt(BlockGetter level, BlockPos pos, Direction dir) {
        Block block = level.getBlockState(pos.relative(dir)).getBlock();
        return block instanceof SheetSectionBlock
                || block instanceof MeshSectionBlock
                || block instanceof PlankSectionBlock;
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
