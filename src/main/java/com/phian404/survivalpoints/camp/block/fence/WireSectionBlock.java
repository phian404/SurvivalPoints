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
 * Секция колючей проволоки ({@code wire_section}) с опорной стойкой и
 * оформленными концами линии.
 *
 * <p>Модель собирается из двух независимых признаков, которые дают четыре
 * комбинации плюс два варианта конца на каждую:
 * <ul>
 *   <li>{@code section_wire} — две диагональные нити и горизонтальная нить
 *       посередине (полотно во всю ширину блока);</li>
 *   <li>{@code section_wire_brace} — то же плюс вертикальная стойка
 *       {@code Y 0..8} по центру, которая соединяет нити с нижним ярусом;</li>
 *   <li>{@code section_wire_end} / {@code section_wire_end_brace} — полотно,
 *       срезанное до центра блока со стороны, где соседа нет: нити не уходят
 *       в пустоту, а замыкаются на стойке.</li>
 * </ul>
 *
 * <p>Правило стойки {@link #WIRE_BRACED}: стойка нужна тогда, когда под
 * проволокой стоит несущий элемент, к которому её можно прикрепить — сплошная
 * панель ({@code mesh_section}, {@code sheet_section}) или опора
 * ({@code post}). Над опорой стойка продолжает её колонну по центру и
 * стыкуется с ней без зазора. В остальных случаях (пусто, доски, другая
 * проволока) стойка не рисуется: висящая в воздухе, она выглядела бы как
 * обрубок.
 *
 * <p>Правило концов {@link #WIRE_END_POSITIVE} / {@link #WIRE_END_NEGATIVE}:
 * конец линии — это отсутствие соседа вдоль оси секции. Стороны названы по
 * знаку координаты вдоль оси (positive — {@code SOUTH} для оси Z и
 * {@code EAST} для оси X), а не «право/лево», потому что право и лево зависят
 * от того, с какой стороны смотрит игрок. Оба конца одновременно пустыми быть
 * не могут: {@link #canSurvive} требует хотя бы одного соседа вдоль оси, и
 * такая секция удаляется. Одна и та же модель-конец обслуживает вторую
 * сторону поворотом на 180° в blockstate, поэтому геометрия не дублируется.
 *
 * <p>Вертикального сшивания (как у {@link StackedSectionBlock}) у проволоки
 * нет: у неё нет реек на торцах, поэтому свойства {@code above}/{@code below}
 * не нужны. Модель концов и стойки пересчитывается при установке и при
 * изменении соседа снизу или соседа вдоль оси.
 */
public class WireSectionBlock extends SectionBlock {

    /** Снизу вплотную стоит панель или опора — рисуется стойка. */
    public static final BooleanProperty WIRE_BRACED = BooleanProperty.create("wire_braced");

    /** Нет соседа со стороны positive вдоль оси — полотно срезано к центру. */
    public static final BooleanProperty WIRE_END_POSITIVE =
            BooleanProperty.create("wire_end_positive");

    /** Нет соседа со стороны negative вдоль оси — полотно срезано к центру. */
    public static final BooleanProperty WIRE_END_NEGATIVE =
            BooleanProperty.create("wire_end_negative");

    public WireSectionBlock() {
        super(SoundType.METAL);
        // StateDefinition.any() берёт первое значение каждого свойства, а у
        // BooleanProperty это true. Без явного сброса блок по умолчанию считал
        // бы, что под ним панель и что линия разомкнута с обеих сторон, и
        // рисовал бы стойку с двумя обрезанными концами.
        this.registerDefaultState(this.defaultBlockState()
                .setValue(WIRE_BRACED, false)
                .setValue(WIRE_END_POSITIVE, false)
                .setValue(WIRE_END_NEGATIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(WIRE_BRACED, WIRE_END_POSITIVE, WIRE_END_NEGATIVE);
    }

    /**
     * Состояние при установке: ось и языки считает {@link SectionBlock}, а
     * соседи снизу и вдоль оси проверяются здесь — на момент установки они
     * уже стоят в мире.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        if (state == null) {
            return null;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        return applyEnds(state, level, pos)
                .setValue(WIRE_BRACED, isSupportBelow(level, pos));
    }


    /**
     * Пересчёт стойки и концов линии при изменении соседа: снизу — стойка,
     * вдоль оси — замкнутость линии. Прочие направления влияют только на
     * языки и обрабатываются {@link SectionBlock#updateShape}.
     *
     * <p>Возврат изменённого состояния выполняется только при фактической
     * смене значения, иначе каждое обновление соседа порождало бы лишний
     * блок-апдейт.
     */
    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        BlockState updated = super.updateShape(state, direction, neighborState, level, pos, neighborPos);

        if (direction.getAxis() == updated.getValue(AXIS)) {
            BlockState withEnds = applyEnds(updated, level, pos);
            if (!withEnds.equals(updated)) {
                updated = withEnds;
            }
        }

        if (direction == Direction.DOWN) {
            boolean braced = isSupportBelow(level, pos);
            if (updated.getValue(WIRE_BRACED) != braced) {
                updated = updated.setValue(WIRE_BRACED, braced);
            }
        }
        return updated;
    }

    /**
     * Выставить концы линии по фактическим соседям вдоль оси.
     *
     * <p>Конец — это отсутствие соседа, но только если противоположный конец
     * замкнут: секция без соседей вдоль оси не существует
     * ({@link #canSurvive}), а состояние с двумя концами рисовало бы два
     * среза одновременно.
     */
    private static BlockState applyEnds(BlockState state, LevelReader level, BlockPos pos) {
        Direction positive = positiveDirection(state.getValue(AXIS));
        boolean positiveConnected = isConnectedAt(level, pos, positive);
        boolean negativeConnected = isConnectedAt(level, pos, positive.getOpposite());
        return state
                .setValue(WIRE_END_POSITIVE, !positiveConnected && negativeConnected)
                .setValue(WIRE_END_NEGATIVE, !negativeConnected && positiveConnected);
    }

    /**
     * Направление positive вдоль оси: {@code SOUTH} для оси Z ({@code +Z}) и
     * {@code EAST} для оси X ({@code +X}). Модель-конец в blockstate построена
     * именно под {@code positive}, вторая сторона получает её поворот на 180°.
     */
    private static Direction positiveDirection(Direction.Axis axis) {
        return axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
    }

    /** Есть ли в указанном направлении сосед, к которому линия примыкает. */
    private static boolean isConnectedAt(LevelReader level, BlockPos pos, Direction dir) {
        return isConnectable(level.getBlockState(pos.relative(dir)));
    }

    /**
     * Стоит ли вплотную снизу несущий элемент, к которому крепится стойка:
     * сплошная панель или опора. Доски и другая проволока стойку не получают.
     */
    private static boolean isSupportBelow(LevelReader level, BlockPos pos) {
        Block block = level.getBlockState(pos.below()).getBlock();
        return block instanceof PostBlock
                || block instanceof MeshSectionBlock
                || block instanceof SheetSectionBlock;
    }
}
