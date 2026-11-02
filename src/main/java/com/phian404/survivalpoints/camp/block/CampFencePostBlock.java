package com.phian404.survivalpoints.camp.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Несущий столб периметра.
 *
 * <p>Два варианта одного блока через свойство {@link #TOP}:
 * <ul>
 *   <li>{@code top=false} — простая балка (нижние ярусы);</li>
 *   <li>{@code top=true} — балка с наклонными подпорками-«Y» (верхний ярус).</li>
 * </ul>
 *
 * <p>Наследует {@link IronBarsBlock} ради двух вещей:
 * <ul>
 *   <li>пролёты прутьев ({@link CampFenceBlock}) соединяются со столбом
 *       автоматически — {@code attachsTo} соединяется с любым
 *       {@code IronBarsBlock};</li>
 *   <li>форма наведения и коллизия ({@link #getShape},
 *       {@link #getCollisionShape}) повторяют модель, а не полный куб.</li>
 * </ul>
 *
 * <p>Рёбра-перемычки в сторону соседних пролётов рисуются multipart-моделью по
 * свойствам {@code north/east/south/west} (как у прутьев).
 */
public class CampFencePostBlock extends IronBarsBlock {

    /** Верхний ярус столба (с подпорками) или простая балка. */
    public static final BooleanProperty TOP = BooleanProperty.create("top");

    /** Форма простой балки — стойка 4×16×4 px по центру блока. */
    private static final VoxelShape BEAM_SHAPE = Block.box(6.0D, 0.0D, 6.0D, 10.0D, 16.0D, 10.0D);

    /** Форма верхнего яруса: стойка + основание с наклонными подпорками. */
    private static final VoxelShape POST_SHAPE = Shapes.or(
            BEAM_SHAPE,
            Block.box(6.0D, 0.0D, 6.0D, 10.0D, 3.0D, 10.0D));

    public CampFencePostBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(4.0F, 8.0F)
                .requiresCorrectToolForDrops()
                .sound(SoundType.WOOD)
                .noOcclusion());
        this.registerDefaultState(this.stateDefinition.any().setValue(TOP, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TOP);
        super.createBlockStateDefinition(builder);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(TOP) ? POST_SHAPE : BEAM_SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return state.getValue(TOP) ? POST_SHAPE : BEAM_SHAPE;
    }

    @Override
    public boolean canPlaceLiquid(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        // Столбы не водозаполняемы — вода не должна затекать в периметр.
        return false;
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        // Столбы не хранят воду: WATERLOGGED-механику унаследованного
        // IronBarsBlock игнорируем — вода не отображается и не живёт в блоке.
        return Fluids.EMPTY.defaultFluidState();
    }
}
