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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Секция пролёта модульной системы ограждений (спека v1.1.7-final, §5.2).
 *
 * <p>Один класс на четыре block-id: {@code plank_section}, {@code wire_section},
 * {@code mesh_section}, {@code sheet_section}. Материал задаётся только
 * звуком через конструктор; визуал полностью определяется моделью и текстурой.
 *
 * <p>Ориентация — свойством {@link #AXIS} по горизонтальному направлению игрока
 * при установке. Вертикальных ограничений нет (D-18): секция может висеть
 * на пустом ярусе ниже.
 *
 * <p>Правило существования (D-06): вдоль оси хотя бы с одной стороны на том же Y
 * должен быть {@link PostBlock} или другая секция. При потере последнего соседа
 * секция удаляется с дропом за один шаг, без рекурсивных цепных реакций.
 *
 * <p>Коллизия (D-08) — полновысотная панель 2 px: ось Z —
 * {@code box(7,0,0,9,16,16)}, ось X — {@code box(0,0,7,16,16,9)}.
 */
public class SectionBlock extends Block implements SimpleWaterloggedBlock {

    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    /**
     * Направленные свойства «языка» (tongue). Свойство включено только тогда,
     * когда в этом направлении стоит {@link PostBlock}. Язык — короткий
     * визуальный выступ {@code Z -4..0} за границу блока, который закрывает
     * 4 px зазора до поверхности колонны опоры ({@code Z 4..12}).
     *
     * <p>Выступ к плотному соседу (ванильный блок, другая секция) не рисуется:
     * именно его наложение в одной плоскости давало z-fighting на стыках.
     */
    public static final BooleanProperty NORTH = BlockStateProperties.NORTH;
    public static final BooleanProperty SOUTH = BlockStateProperties.SOUTH;
    public static final BooleanProperty EAST = BlockStateProperties.EAST;
    public static final BooleanProperty WEST = BlockStateProperties.WEST;

    private static final VoxelShape SHAPE_Z = Block.box(7.0D, 0.0D, 0.0D, 9.0D, 16.0D, 16.0D);
    private static final VoxelShape SHAPE_X = Block.box(0.0D, 0.0D, 7.0D, 16.0D, 16.0D, 9.0D);

    public SectionBlock(SoundType sound) {
        super(BlockBehaviour.Properties.of()
                .strength(3.0F, 6.0F)
                .sound(sound)
                .noOcclusion());
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(AXIS, Direction.Axis.Z)
                .setValue(WATERLOGGED, false)
                .setValue(NORTH, false)
                .setValue(SOUTH, false)
                .setValue(EAST, false)
                .setValue(WEST, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS, WATERLOGGED, NORTH, SOUTH, EAST, WEST);
    }

    /**
     * Ось секции определяется по фактическим соседям, а не по направлению
     * взгляда игрока: если вдоль X есть опора или секция — берём X, иначе Z.
     * Это устраняет случай, когда секция ставится между столбами, но
     * получает перпендикулярную ось и визуально не соединяется.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        FluidState fluid = level.getFluidState(pos);

        Direction.Axis axis = pickAxis(level, pos, context.getHorizontalDirection().getAxis());

        BlockState state = this.defaultBlockState()
                .setValue(AXIS, axis)
                .setValue(WATERLOGGED, fluid.getType() == Fluids.WATER);
        return applyTongues(state, level, pos);
    }

    /**
     * Выставить направленные свойства по фактическим опорам вокруг блока.
     * Язык рисуется только в сторону {@link PostBlock}; к плотным блокам
     * и к другим секциям выступ не создаётся.
     */
    private static BlockState applyTongues(BlockState state, LevelReader level, BlockPos pos) {
        return state
                .setValue(NORTH, isPostAt(level, pos, Direction.NORTH))
                .setValue(SOUTH, isPostAt(level, pos, Direction.SOUTH))
                .setValue(EAST, isPostAt(level, pos, Direction.EAST))
                .setValue(WEST, isPostAt(level, pos, Direction.WEST));
    }

    /** Стоит ли в указанном направлении опора. */
    private static boolean isPostAt(LevelReader level, BlockPos pos, Direction dir) {
        return level.getBlockState(pos.relative(dir)).getBlock() instanceof PostBlock;
    }

    /** Свойство стороны для горизонтального направления; для UP/DOWN — {@code null}. */
    private static BooleanProperty sideProperty(Direction dir) {
        return switch (dir) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST -> EAST;
            case WEST -> WEST;
            default -> null;
        };
    }

    /**
     * Выбрать ось по соседям. Приоритет — оси, вдоль которой есть
     * соединяемый сосед. Если соседей нет ни по одной оси, используется
     * ось взгляда игрока как запасной вариант.
     */
    private static Direction.Axis pickAxis(LevelReader level, BlockPos pos, Direction.Axis fallback) {
        boolean xConnected = connectsAlong(level, pos, Direction.Axis.X);
        boolean zConnected = connectsAlong(level, pos, Direction.Axis.Z);

        if (xConnected && !zConnected) {
            return Direction.Axis.X;
        }
        if (zConnected && !xConnected) {
            return Direction.Axis.Z;
        }
        if (xConnected && zConnected) {
            // Оба направления заняты: предпочитаем ось взгляда, если она
            // среди них, иначе X как детерминированный выбор.
            return fallback == Direction.Axis.Z ? Direction.Axis.Z : Direction.Axis.X;
        }
        return fallback;
    }

    /** Есть ли вдоль оси сосед, с которым секция соединяется. */
    private static boolean connectsAlong(LevelReader level, BlockPos pos, Direction.Axis axis) {
        for (Direction dir : axisDirections(axis)) {
            if (isConnectable(level.getBlockState(pos.relative(dir)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Сосед, к которому секция может примкнуть: опора или другая секция.
     *
     * <p>Доступен подклассам: семейство проволоки определяет по этому правилу
     * незамкнутые концы линии ({@code wire_end_positive} / {@code
     * wire_end_negative}) и должно считать соседа ровно так же, как
     * {@link #canSurvive}, иначе конец линии мог бы не совпасть с условием
     * существования блока.
     */
    protected static boolean isConnectable(BlockState state) {
        return state.getBlock() instanceof PostBlock
                || state.getBlock() instanceof SectionBlock;
    }

    /** Направления вдоль оси секции. */
    private static Direction[] axisDirections(Direction.Axis axis) {
        return axis == Direction.Axis.X
                ? new Direction[]{Direction.EAST, Direction.WEST}
                : new Direction[]{Direction.NORTH, Direction.SOUTH};
    }

    /**
     * Секция существует, пока вдоль своей оси на том же Y есть хотя бы одна
     * опора или другая секция (D-06). Вертикальных требований нет (D-18):
     * секция может висеть на пустом ярусе ниже.
     */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return connectsAlong(level, pos, state.getValue(AXIS));
    }

    /**
     * Изолированная секция удаляется с дропом за один шаг (D-06).
     * Только на сервере, чтобы не дублировать разрушение на клиенте.
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
        return state.getValue(AXIS) == Direction.Axis.X ? SHAPE_X : SHAPE_Z;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return state.getValue(AXIS) == Direction.Axis.X ? SHAPE_X : SHAPE_Z;
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

    /**
     * Пересчёт направленных свойств при изменении соседа. Единственное место,
     * где состояние синхронизируется с реальным окружением: поставили опору —
     * появился язык, снесли — язык исчез.
     *
     * <p>Возврат изменённого состояния выполняется только при фактической
     * смене значения, иначе каждое обновление соседа порождало бы лишний
     * блок-апдейт. Waterlog-тик планируется независимо, как и раньше.
     */
    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        BooleanProperty side = sideProperty(direction);
        if (side != null) {
            boolean post = neighborState.getBlock() instanceof PostBlock;
            if (state.getValue(side) != post) {
                return state.setValue(side, post);
            }
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }
}
