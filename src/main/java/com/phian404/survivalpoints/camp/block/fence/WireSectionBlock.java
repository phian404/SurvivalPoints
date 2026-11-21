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
 * Секция колючей проволоки ({@code wire_section}) с опорной стойкой.
 *
 * <p>У проволоки есть два вида модели:
 * <ul>
 *   <li>{@code section_wire} — две диагональные нити и горизонтальная нить
 *       посередине;</li>
 *   <li>{@code section_wire_brace} — то же плюс вертикальная стойка
 *       {@code Y 0..8} по центру, которая соединяет нити с нижним ярусом.</li>
 * </ul>
 *
 * <p>Правило выбора: стойка нужна только тогда, когда проволока стоит вплотную
 * над сплошной панелью — {@code mesh_section} или {@code sheet_section}.
 * В остальных случаях (пусто, опора, доски, другая проволока) рисуется
 * базовый {@code section_wire}: стойка, висящая в воздухе, выглядела бы как
 * обрубок.
 *
 * <p>Состояние задаётся одним свойством {@link #WIRE_BRACED}, которое
 * пересчитывается только по соседу снизу. Вертикального сшивания (как у
 * {@link StackedSectionBlock}) у проволоки нет: у неё нет реек на торцах,
 * поэтому свойства {@code above}/{@code below} не нужны.
 */
public class WireSectionBlock extends SectionBlock {

    /** Снизу вплотную стоит сплошная панель — рисуется опорная стойка. */
    public static final BooleanProperty WIRE_BRACED = BooleanProperty.create("wire_braced");

    public WireSectionBlock() {
        super(SoundType.METAL);
        // StateDefinition.any() берёт первое значение каждого свойства, а у
        // BooleanProperty это true. Без явного сброса блок по умолчанию считал
        // бы, что под ним панель, и всегда рисовал бы стойку.
        this.registerDefaultState(this.defaultBlockState().setValue(WIRE_BRACED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(WIRE_BRACED);
    }

    /**
     * Состояние при установке: ось и языки считает {@link SectionBlock}, а
     * наличие панели снизу проверяется здесь — на момент установки она уже
     * стоит в мире.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        if (state == null) {
            return null;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        return state.setValue(WIRE_BRACED, isPanelBelow(level, pos));
    }

    /**
     * Пересчёт стойки при изменении соседа снизу: поставили панель — стойка
     * появилась, снесли — исчезла. Прочие направления не влияют на выбор
     * модели и обрабатываются {@link SectionBlock#updateShape}.
     *
     * <p>Возврат изменённого состояния выполняется только при фактической
     * смене значения, иначе каждое обновление соседа порождало бы лишний
     * блок-апдейт.
     */
    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        BlockState updated = super.updateShape(state, direction, neighborState, level, pos, neighborPos);
        if (direction != Direction.DOWN) {
            return updated;
        }
        boolean braced = isPanelBelow(level, pos);
        if (updated.getValue(WIRE_BRACED) != braced) {
            return updated.setValue(WIRE_BRACED, braced);
        }
        return updated;
    }

    /** Стоит ли вплотную снизу сплошная панель, к которой крепится стойка. */
    private static boolean isPanelBelow(LevelReader level, BlockPos pos) {
        Block block = level.getBlockState(pos.below()).getBlock();
        return block instanceof MeshSectionBlock || block instanceof SheetSectionBlock;
    }
}
