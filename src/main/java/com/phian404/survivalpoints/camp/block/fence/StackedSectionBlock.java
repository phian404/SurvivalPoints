package com.phian404.survivalpoints.camp.block.fence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Секция, которая сшивается с такой же секцией при вертикальном стековании.
 *
 * <p>У части секций на торце яруса стоит металлическая рейка
 * ({@code core_up} / {@code core_down}). Если поставить вторую такую секцию
 * вплотную сверху или снизу, рейки соседних блоков оказывались в одной
 * плоскости на границе: металл накладывался сам на себя и давал шов по стыку.
 *
 * <p>Правило сшивания: торец, к которому примыкает такая же секция, отдаётся
 * панели, а рейка на этом торце не рисуется. Геометрия панели продлевается до
 * границы блока (0 или 16), поэтому плоскости соседних блоков смыкаются
 * вплотную и щели по высоте не остаётся. UV по V пересчитывается под новую
 * высоту грани по общему правилу {@code V = 16 − Y}; U и горизонтальные грани
 * не меняются.
 *
 * <p>Состояния задаются двумя свойствами подкласса, которые пересчитываются
 * только по вертикальным соседям:
 * <ul>
 *   <li>{@link #aboveProperty()} — сверху вплотную стоит такая же секция;</li>
 *   <li>{@link #belowProperty()} — снизу вплотную стоит такая же секция.</li>
 * </ul>
 * Комбинации покрывают все положения секции в стеке, включая среднее звено,
 * у которого нет ни верхней, ни нижней рейки:
 * <table border="1">
 *   <caption>Свойства → модель</caption>
 *   <tr><th>above</th><th>below</th><th>модель</th></tr>
 *   <tr><td>false</td><td>false</td><td>{@code section_*} (одиночная секция)</td></tr>
 *   <tr><td>true</td><td>false</td><td>{@code section_*_bottom} (нижняя в стеке)</td></tr>
 *   <tr><td>false</td><td>true</td><td>{@code section_*_top} (верхняя в стеке)</td></tr>
 *   <tr><td>true</td><td>true</td><td>{@code section_*_middle} (средняя в стеке)</td></tr>
 * </table>
 *
 * <p>Сшивается только пара «та же секция — та же секция»: разные материалы
 * ({@code mesh}, {@code plank}, {@code wire}, {@code sheet}) между собой не
 * сшиваются, их торцы остаются в базовом виде.
 */
public abstract class StackedSectionBlock extends SectionBlock {

    protected StackedSectionBlock(SoundType sound) {
        super(sound);
        // StateDefinition.any() берёт первое значение каждого свойства, а у
        // BooleanProperty это true. Без явного сброса блок по умолчанию считал
        // бы себя сшитым с обеих сторон и не рисовал бы ни одной рейки.
        this.registerDefaultState(this.defaultBlockState()
                .setValue(aboveProperty(), false)
                .setValue(belowProperty(), false));
    }

    /** Свойство «сверху вплотную стоит такая же секция». */
    protected abstract BooleanProperty aboveProperty();

    /** Свойство «снизу вплотную стоит такая же секция». */
    protected abstract BooleanProperty belowProperty();

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(aboveProperty(), belowProperty());
    }

    /**
     * Состояние при установке: ось и языки считает {@link SectionBlock}, а
     * вертикальные соседи проверяются здесь — на момент установки они уже
     * стоят в мире.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        if (state == null) {
            return null;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        return state
                .setValue(aboveProperty(), isStackedAt(level, pos, Direction.UP))
                .setValue(belowProperty(), isStackedAt(level, pos, Direction.DOWN));
    }

    /**
     * Пересчёт сшивания при изменении вертикального соседа: поставили такую же
     * секцию сверху — рейка исчезла и панель выросла до границы; снесли —
     * вернулось базовое состояние. Горизонтальные направления обрабатывает
     * {@link SectionBlock#updateShape}.
     *
     * <p>Возврат изменённого состояния выполняется только при фактической
     * смене значения, иначе каждое обновление соседа порождало бы лишний
     * блок-апдейт.
     */
    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        BlockState updated = super.updateShape(state, direction, neighborState, level, pos, neighborPos);
        if (!direction.getAxis().isVertical()) {
            return updated;
        }
        boolean above = isStackedAt(level, pos, Direction.UP);
        boolean below = isStackedAt(level, pos, Direction.DOWN);
        if (updated.getValue(aboveProperty()) != above || updated.getValue(belowProperty()) != below) {
            return updated.setValue(aboveProperty(), above).setValue(belowProperty(), below);
        }
        return updated;
    }

    /**
     * Стоит ли в указанном направлении именно этот же блок: секции другого
     * материала не сшиваются, иначе их панели разной толщины стыковались бы
     * со сдвигом.
     */
    private boolean isStackedAt(LevelReader level, BlockPos pos, Direction dir) {
        return level.getBlockState(pos.relative(dir)).is(this);
    }
}
