package com.phian404.survivalpoints.camp.block;

import com.phian404.survivalpoints.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Пролёт периметра лагеря — металлические прутья с автосоединением
 * (наследник {@link IronBarsBlock}).
 *
 * <p>Периметр собирается генератором как ритм «столб → пролёт → столб»:
 * {@link CampFencePostBlock} на углах и через каждые 4 колонки, между ними —
 * два яруса прутьев (этот блок), сверху — колючая проволока
 * {@link CampBarbedWireBlock}. Внизу — цоколь из смешанной каменной ограды
 * (кирпич/замшелый кирпич с перебивками булыжника и андезита).
 *
 * <p>Генератор ставит блоки через {@code setBlock}, а он не вызывает
 * {@code getStateForPlacement} — поэтому стороны соединений заполняются
 * вручную вторым проходом генератора через {@link #withConnections}.
 */
public class CampFenceBlock extends IronBarsBlock {

    public CampFenceBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(3.0F, 6.0F)
                .requiresCorrectToolForDrops()
                .sound(SoundType.METAL)
                .noOcclusion());
    }

    /**
     * Заполнить стороны {@code NORTH/EAST/SOUTH/WEST} по тем же правилам,
     * что {@code getStateForPlacement} при ручной установке блока
     * (см. {@link IronBarsBlock#attachsTo}). Нужно генератору: без этого
     * прохода прутья не соединяются ни между собой, ни со столбами.
     *
     * @param state состояние, которому заполняются стороны
     * @param level уровень (для проверки соседей)
     * @param pos   позиция блока
     * @return состояние с заполненными сторонами
     */
    public static BlockState withConnections(BlockState state, LevelAccessor level, BlockPos pos) {
        IronBarsBlock fence = (IronBarsBlock) ModBlocks.CAMP_FENCE.get();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState neighbor = level.getBlockState(neighborPos);
            boolean connect = fence.attachsTo(neighbor,
                    neighbor.isFaceSturdy(level, neighborPos, dir.getOpposite()));
            state = state.setValue(PROPERTY_BY_DIRECTION.get(dir), connect);
        }
        return state;
    }

    @Override
    public boolean canPlaceLiquid(BlockGetter level, BlockPos pos, BlockState state, Fluid fluid) {
        // Пролёты не водозаполняемы: иначе соседняя вода затекает внутрь
        // прутьев через LiquidBlockContainer (FlowingFluid.canHoldFluid видит
        // полую коллизию и заливает блок WATERLOGGED).
        return false;
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        // Прутья не хранят воду вообще: унаследованный WATERLOGGED игнорируем,
        // иначе вода, оставшаяся в клетке от прежнего содержимого или пришедшая
        // растеканием, отображается и физически живёт внутри блока.
        return Fluids.EMPTY.defaultFluidState();
    }
}
