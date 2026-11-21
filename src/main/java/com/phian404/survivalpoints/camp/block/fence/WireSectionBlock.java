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
 * Секция колючей проволоки ({@code wire_section}) с опорной стойкой,
 * оформленными концами линии и центральным креплением.
 *
 * <p>Модель собирается из двух независимых признаков, которые дают четыре
 * комбинации:
 * <ul>
 *   <li>{@code section_wire} — две диагональные нити и горизонтальная нить
 *       посередине (полотно во всю ширину блока);</li>
 *   <li>{@code section_wire_brace} — то же плюс вертикальная стойка
 *       {@code Y 0..8} по центру, которая соединяет нити с нижним ярусом;</li>
 *   <li>{@code section_wire_end} / {@code section_wire_end_brace} — полотно,
 *       срезанное до центра блока со стороны, где соседа нет: нити не уходят
 *       в пустоту, а замыкаются на стойке;</li>
 *   <li>{@code section_wire_center_brace} — короткий центральный крест нитей
 *       со стойкой, без полотна во всю ширину: проволока стоит на опоре
 *       верхом, соседей вдоль оси нет вообще.</li>
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
 * от того, с какой стороны смотрит игрок. Одна и та же модель-конец
 * обслуживает вторую сторону поворотом на 180° в blockstate, поэтому геометрия
 * не дублируется.
 *
 * <p>Правило центра {@link #WIRE_CENTER}: соседей вдоль оси нет ни с одной
 * стороны, но под проволокой есть несущий элемент. Это единственный случай,
 * когда проволока может существовать без горизонтальных соседей — иначе её
 * нельзя поставить верхом на опору, сетку или лист. Такая секция рисует только
 * центральное крепление.
 *
 * <p>Правило существования ({@link #canSurvive}): сосед вдоль оси <b>или</b>
 * несущий блок снизу. Вертикального сшивания (как у
 * {@link StackedSectionBlock}) у проволоки нет: у неё нет реек на торцах,
 * поэтому свойства {@code above}/{@code below} не нужны. Модель пересчитывается
 * при установке и при изменении соседа снизу или соседа вдоль оси.
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

    /** Соседей вдоль оси нет вообще — рисуется только центральное крепление. */
    public static final BooleanProperty WIRE_CENTER = BooleanProperty.create("wire_center");

    public WireSectionBlock() {
        super(SoundType.METAL);
        // StateDefinition.any() берёт первое значение каждого свойства, а у
        // BooleanProperty это true. Без явного сброса блок по умолчанию считал
        // бы, что под ним панель и что линия разомкнута с обеих сторон, и
        // рисовал бы стойку с двумя обрезанными концами.
        this.registerDefaultState(this.defaultBlockState()
                .setValue(WIRE_BRACED, false)
                .setValue(WIRE_END_POSITIVE, false)
                .setValue(WIRE_END_NEGATIVE, false)
                .setValue(WIRE_CENTER, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(WIRE_BRACED, WIRE_END_POSITIVE, WIRE_END_NEGATIVE, WIRE_CENTER);
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
        return applyLine(state, level, pos)
                .setValue(WIRE_BRACED, isSupportBelow(level, pos));
    }

    /**
     * Секция существует при соседе вдоль оси <b>или</b> при несущем блоке
     * снизу. Второе условие добавлено ради установки проволоки верхом на
     * опору, сетку или лист: без горизонтальных соседей такая секция раньше
     * удалялась сразу после установки, потому что базовое правило
     * {@link SectionBlock#canSurvive} требует соединения вдоль оси.
     */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction positive = positiveDirection(state.getValue(AXIS));
        return isConnectedAt(level, pos, positive)
                || isConnectedAt(level, pos, positive.getOpposite())
                || isSupportBelow(level, pos);
    }

    /**
     * Пересчёт стойки, концов линии и центра при изменении соседа: снизу —
     * стойка, вдоль оси — замкнутость линии. Прочие направления влияют только
     * на языки и обрабатываются {@link SectionBlock#updateShape}.
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
            BlockState withLine = applyLine(updated, level, pos);
            if (!withLine.equals(updated)) {
                updated = withLine;
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
     * Выставить концы линии и центральное крепление по фактическим соседям
     * вдоль оси.
     *
     * <p>Три взаимоисключающих исхода: линия замкнута с обеих сторон (ни
     * концов, ни центра), разомкнута с одной стороны (один конец), либо не
     * имеет соседей вдоль оси вообще (центр). Последний вариант существует
     * только благодаря несущему блоку снизу — см. {@link #canSurvive}.
     *
     * <p>Совместимость с blockstate: у замкнутой линии оба свойства концов
     * {@code false}, поэтому ветки базовой модели обязаны проверять ещё и
     * {@link #WIRE_CENTER}, иначе {@code multipart} наложил бы полотно во всю
     * ширину поверх центрального крепления.
     */
    private static BlockState applyLine(BlockState state, LevelReader level, BlockPos pos) {
        Direction positive = positiveDirection(state.getValue(AXIS));
        boolean positiveConnected = isConnectedAt(level, pos, positive);
        boolean negativeConnected = isConnectedAt(level, pos, positive.getOpposite());
        boolean centered = !positiveConnected && !negativeConnected;
        return state
                .setValue(WIRE_CENTER, centered)
                .setValue(WIRE_END_POSITIVE, !centered && !positiveConnected)
                .setValue(WIRE_END_NEGATIVE, !centered && !negativeConnected);
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
