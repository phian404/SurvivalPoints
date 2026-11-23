package com.phian404.survivalpoints.camp.block.fence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
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
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
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
 * <p><b>Коллизия отделена от визуала.</b> Модель панели тонкая (2 px), и по
 * ней мобы видели сквозь забор, стреляли через щели, а игрок перешагивал
 * низкие секции. Поэтому {@link #getCollisionShape} отдаёт сплошное полотно
 * толщиной 6 px ({@code 5..11}) на всю высоту блока. Визуальная форма
 * ({@link #getShape}) и модели не меняются.
 *
 * <p>Высотную коллизию 1.5 блока добавляет только деревянный забор
 * ({@link PlankSectionBlock}): металлические панели всегда стоят ярусом
 * друг на друге, и поднятая форма торчала бы в соседний блок.
 *
 * <p>{@link BlockBehaviour.Properties#dynamicShape()} включён на всякий
 * случай: коллизия наследников может зависеть от соседей, а без этого флага
 * {@code BlockStateBase.Cache} запоминает форму, посчитанную при инициализации
 * блоков, и пересчёт по {@code pos.above()} не выполнялся бы.
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

    /** Визуальная форма вдоль оси Z: панель 2 px. */
    private static final VoxelShape SHAPE_Z = Block.box(7.0D, 0.0D, 0.0D, 9.0D, 16.0D, 16.0D);
    /** Визуальная форма вдоль оси X: панель 2 px. */
    private static final VoxelShape SHAPE_X = Block.box(0.0D, 0.0D, 7.0D, 16.0D, 16.0D, 9.0D);

    /** Коллизия вдоль оси Z: сплошное полотно 6 px на высоту блока. */
    protected static final VoxelShape COLLISION_Z =
            Block.box(5.0D, 0.0D, 0.0D, 11.0D, 16.0D, 16.0D);
    /** Коллизия вдоль оси X: сплошное полотно 6 px на высоту блока. */
    protected static final VoxelShape COLLISION_X =
            Block.box(0.0D, 0.0D, 5.0D, 16.0D, 16.0D, 11.0D);

    /*
     * Выступы языка в форме выделения. Координаты НАМЕРЕННО выходят за
     * границу клетки: модель языка рисуется за пределами блока
     * ({@code Z -4..0} для северного), то есть визуально торец продолжается
     * в клетку опоры.
     *
     * <p>Раньше секция отдавала только панель {@code 0..16}, поэтому рамка
     * выделения обрывалась на границе клетки и «резала» панель у торцов,
     * хотя геометрия там есть. Форма вне {@code 0..16} допустима:
     * {@code Shapes.create} строит {@code ArrayVoxelShape} с произвольными
     * координатами, а рейкаст прибавляет позицию клетки целиком.
     *
     * <p>Выступы добавлены ТОЛЬКО в {@link #getShape}. Коллизию, обзор мобов
     * и попадания считает {@link #getCollisionShape}, рендер берёт
     * {@code getVisualShape} из коллизии — они не меняются.
     */
    private static final VoxelShape TONGUE_Z_NEGATIVE =
            Block.box(7.0D, 0.0D, -4.0D, 9.0D, 16.0D, 0.0D);
    private static final VoxelShape TONGUE_Z_POSITIVE =
            Block.box(7.0D, 0.0D, 16.0D, 9.0D, 16.0D, 20.0D);
    private static final VoxelShape TONGUE_X_NEGATIVE =
            Block.box(-4.0D, 0.0D, 7.0D, 0.0D, 16.0D, 9.0D);
    private static final VoxelShape TONGUE_X_POSITIVE =
            Block.box(16.0D, 0.0D, 7.0D, 20.0D, 16.0D, 9.0D);

    public SectionBlock(SoundType sound) {
        super(BlockBehaviour.Properties.of()
                .strength(3.0F, 6.0F)
                .sound(sound)
                .noOcclusion()
                .dynamicShape());
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

    /**
     * Форма прицела и рамки выделения: панель плюс языки в стороны, где стоит
     * опора, — ровно то, что видно в модели.
     *
     * <p>Язык выходит за границу клетки, поэтому без него рамка выделения
     * обрывалась на торце и не совпадала с панелью.
     */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                               CollisionContext context) {
        return assembleShape(state,
                state.getValue(AXIS) == Direction.Axis.X ? SHAPE_X : SHAPE_Z,
                TONGUE_Z_NEGATIVE, TONGUE_Z_POSITIVE, TONGUE_X_NEGATIVE, TONGUE_X_POSITIVE);
    }

    /**
     * Собрать форму выделения из панели и языков.
     *
     * <p>Языки берутся только вдоль оси секции: поперечные свойства
     * {@code north/south} при оси X (и наоборот) в blockstate не используются
     * и моделью не рисуются.
     *
     * <p>Метод вынесен, потому что у разных материалов разная высота панели:
     * металл занимает весь блок, доски заканчиваются выше просвета
     * ({@link PlankSectionBlock}). Набор языков при этом один и тот же.
     */
    protected static VoxelShape assembleShape(BlockState state, VoxelShape base,
                                              VoxelShape zNegative, VoxelShape zPositive,
                                              VoxelShape xNegative, VoxelShape xPositive) {
        if (state.getValue(AXIS) == Direction.Axis.X) {
            VoxelShape shape = base;
            if (state.getValue(WEST)) {
                shape = Shapes.or(shape, xNegative);
            }
            if (state.getValue(EAST)) {
                shape = Shapes.or(shape, xPositive);
            }
            return shape;
        }
        VoxelShape shape = base;
        if (state.getValue(NORTH)) {
            shape = Shapes.or(shape, zNegative);
        }
        if (state.getValue(SOUTH)) {
            shape = Shapes.or(shape, zPositive);
        }
        return shape;
    }

    /**
     * Коллизия — сплошное полотно 6 px на всю высоту блока.
     *
     * <p>По визуальной панели 2 px мобы видели сквозь забор и стреляли через
     * щели между досками и сеткой; сплошная форма закрывает прострел, не меняя
     * ни модель, ни текстуру. Толщина 6 px выбрана так, чтобы форма совпадала
     * по габариту с центральной колонной опоры ({@code 4..12}) и с крыльями
     * опоры — тогда на стыке «секция-опора» не остаётся 4 px щели у языка.
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return state.getValue(AXIS) == Direction.Axis.X ? COLLISION_X : COLLISION_Z;
    }

    /**
     * Секция непроходима для поиска пути: как ванильный {@code FenceBlock},
     * она должна считаться препятствием, а не проходимой клеткой. Без этого
     * мобы пытались идти прямо через забор.
     */
    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos,
                                  PathComputationType type) {
        return false;
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
    /**
     * Поворот состояния секции при размещении шаблона структуры.
     *
     * <p><b>Почему это обязательно.</b> Ванильный
     * {@link BlockBehaviour#rotate(BlockState, Rotation)} по умолчанию
     * возвращает состояние без изменений. Пока секции ставились только рукой,
     * этого хватало: {@link #getStateForPlacement} сам выбирал ось по соседям.
     * Но {@code StructureTemplate.placeInWorld} поворачивает блоки через
     * {@link BlockState#rotate(Rotation)}, и без переопределения:
     * <ul>
     *   <li>свойство {@code axis} оставалось исходным ({@code z}), из-за чего
     *       геометрия вставала поперёк повёрнутого плана;</li>
     *   <li>свойства языков {@code north/south} не переезжали в
     *       {@code east/west}, и модели-выступы смотрели не туда.</li>
     * </ul>
     * Внешне это и читается как «под углами секция ломается».
     *
     * <p>Порядок операций важен: опорное направление берётся по ТЕКУЩЕЙ оси
     * состояния (EAST для X, SOUTH для Z), поворачивается через
     * {@link Rotation#rotate(Direction)}, и ось результата становится новой
     * осью. Затем каждое направление-язык переносится на своё повёрнутое
     * место. {@code waterlogged} и вертикальные свойства асимметрии не имеют
     * и переносятся как есть.
     */
    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        // Перегрузки Rotation.rotate(Direction.Axis) нет: поворачиваем
        // опорное горизонтальное направление и берём ось результата.
        // Опорное направление должно соответствовать исходной оси состояния:
        // EAST представляет ось X, SOUTH — ось Z. Якорь «всегда EAST» ломал
        // секции с axis=z: при повороте 0°/180° они ошибочно получали ось X,
        // и стены вставали поперёк плана.
        Direction.Axis current = state.getValue(AXIS);
        Direction anchor = current == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        Direction.Axis axis = rotation.rotate(anchor).getAxis();
        BlockState rotated = state.setValue(AXIS, axis);
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BooleanProperty from = sideProperty(dir);
            BooleanProperty to = sideProperty(rotation.rotate(dir));
            if (from != null && to != null) {
                rotated = rotated.setValue(to, state.getValue(from));
            }
        }
        return rotated;
    }

    /**
     * Зеркалирование состояния секции.
     *
     * <p>Горизонтальная ось при зеркалировании не меняется (обе ванильные оси
     * горизонтальны), меняются только места языков. Правило нужно ради полноты:
     * {@code StructurePlaceSettings} по умолчанию использует
     * {@link Mirror#NONE}, но шаблон может задать зеркало — и тогда выступы
     * должны переехать вместе с блоками.
     */
    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        BlockState mirrored = state;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BooleanProperty from = sideProperty(dir);
            BooleanProperty to = sideProperty(mirror.mirror(dir));
            if (from != null && to != null) {
                mirrored = mirrored.setValue(to, state.getValue(from));
            }
        }
        return mirrored;
    }

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
