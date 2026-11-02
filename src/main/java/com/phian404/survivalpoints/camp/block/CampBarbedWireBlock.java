package com.phian404.survivalpoints.camp.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Колючая проволока поверх периметра лагеря.
 *
 * <p>Свои четыре свойства сторон ({@code north/east/south/west}) и своя логика
 * соединений: проволока тянется непрерывной линией над пролётами, но
 * <b>не соединяется</b> с прутьями и столбами — стык проволоки с «Y»-верхом
 * столба выглядел плохо (правка владельца). Поэтому блок не наследует
 * {@code IronBarsBlock} (его {@code attachsTo} цепляется к любому
 * {@code IronBarsBlock}) и не реализует водозаполнение.
 *
 * <p>Геометрия повторяет референс владельца: две несущие нити, диагональные
 * крестовины и колючки (модели {@code camp_barbed_wire_side} /
 * {@code camp_barbed_wire_post}).
 *
 * <p>Коллизии нет — периметр держит забор под проволокой. Урон при контакте
 * появится вместе с электропериметром в под-волне 2.5: флаг «под напряжением»
 * будет жить в данных лагеря ({@code CampRecord}), а не в этом блоке.
 */
public class CampBarbedWireBlock extends Block {

    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");

    /** Форма для наведения: тонкая полоска проволоки, не полный куб. */
    private static final VoxelShape PICK_SHAPE = Block.box(7.0D, 0.0D, 7.0D, 9.0D, 8.5D, 9.0D);

    public CampBarbedWireBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(1.5F, 3.0F)
                .sound(SoundType.METAL)
                .noOcclusion());
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(NORTH, false)
                .setValue(EAST, false)
                .setValue(SOUTH, false)
                .setValue(WEST, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST);
    }

    private static BooleanProperty propertyFor(Direction dir) {
        return switch (dir) {
            case NORTH -> NORTH;
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            default -> NORTH;
        };
    }

    /** Соединяется только с такой же проволокой — ни с прутьями, ни со столбами. */
    private static boolean connectsTo(BlockState neighbor) {
        return neighbor.getBlock() instanceof CampBarbedWireBlock;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockGetter level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = this.defaultBlockState();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            state = state.setValue(propertyFor(dir),
                    connectsTo(level.getBlockState(pos.relative(dir))));
        }
        return state;
    }

    @Override
    public BlockState updateShape(BlockState state, Direction dir, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (dir.getAxis().isHorizontal()) {
            return state.setValue(propertyFor(dir), connectsTo(neighborState));
        }
        return super.updateShape(state, dir, neighborState, level, pos, neighborPos);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return PICK_SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                         CollisionContext context) {
        // Декоративный блок: не мешает движению. Периметр держит забор.
        return Shapes.empty();
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
