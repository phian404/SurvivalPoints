package com.phian404.survivalpoints.camp.block.fence;

import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

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

    /*
     * Формы проволоки (решение §4.1 плана ржавых вариантов). Две роли
     * разделены:
     *
     * <p><b>Коллизия</b> — только стойка, когда проволока стоит на опоре.
     * Нить из коллизии исключена: сквозь неё можно пройти, и именно это
     * включает контактную механику {@link #entityInside}. Без стойки
     * коллизия пустая — висящая в воздухе нить не препятствие.
     *
     * <p><b>Выделение и рейкаст</b> ({@link #getShape}) — габарит полотна со
     * стойкой, то есть видимая геометрия. Если не переопределять
     * {@code getShape}, при наведении подсвечивалась бы сплошная панель
     * 2×16×16 из {@link SectionBlock}, которая к проволоке отношения не имеет.
     *
     * <p>Формы — статические константы: {@code Shapes.or} считается один раз
     * при загрузке класса, а не в каждом вызове метода.
     */

    /**
     * Полотно вдоль оси Z: один параллелепипед на весь крест нитей —
     * {@code X 4..12}, {@code Y 0..10}, вся длина блока. Высота взята с
     * запасом над диагоналями ({@code Y ≈ 0.8..9.2}), а не по одной
     * горизонтальной нити: {@code VoxelShape} не умеет диагоналей, поэтому
     * габарит — единственный способ покрыть крест без ступеней.
     *
     * <p>Запас по форме намеренный: рамка выделения и зона прицела
     * ({@code ClipContext.Block.OUTLINE}) крупнее видимой геометрии, как у
     * ванильного {@code SweetBerryBushBlock} ({@code box(1,0,1,15,16,15)}).
     */
    private static final VoxelShape SHAPE_Z = Block.box(4.0D, 0.0D, 0.0D, 12.0D, 10.0D, 16.0D);

    /** Полотно вдоль оси X: тот же габарит, развёрнутый по Z. */
    private static final VoxelShape SHAPE_X = Block.box(0.0D, 0.0D, 4.0D, 16.0D, 10.0D, 12.0D);

    /** Стойка центрального крепления: полновысотная, 1×1 px. */
    private static final VoxelShape SHAPE_BRACE =
            Block.box(7.5D, 0.0D, 7.5D, 8.5D, 16.0D, 8.5D);

    private static final VoxelShape SHAPE_Z_BRACED = Shapes.or(SHAPE_Z, SHAPE_BRACE);
    private static final VoxelShape SHAPE_X_BRACED = Shapes.or(SHAPE_X, SHAPE_BRACE);

    /** Множитель скорости внутри проволоки — как у сладких ягод. */
    private static final Vec3 SLOWDOWN = new Vec3(0.8D, 0.75D, 0.8D);

    /** Порог смещения за тик, ниже которого урон не наносится. */
    private static final float HURT_SPEED_THRESHOLD = 0.003F;

    /** Урон за тик движения; интервал ограничивает ванильная неуязвимость. */
    private static final float HURT_DAMAGE = 1.0F;

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

    /**
     * Поворот состояния проволоки.
     *
     * <p>Базовый {@link SectionBlock#rotate(BlockState, Rotation)} переставляет
     * ось и языки, но не знает про концы линии: они адресуются не сторонами
     * света, а понятием «positive вдоль оси», которое зависит от самой оси
     * (см. {@link #positiveDirection(Direction.Axis)}). При повороте на 90°/270°
     * ось меняется, и старый конец positive оказывается на месте negative —
     * без перестановки линия была бы срезана не с той стороны.
     *
     * <p>{@link #WIRE_CENTER} и {@link #WIRE_BRACED} асимметрии не имеют:
     * центр — свойство «нет соседей вдоль оси», стойка зависит только от блока
     * снизу.
     */
    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        BlockState rotated = super.rotate(state, rotation);
        if (rotation != Rotation.CLOCKWISE_90 && rotation != Rotation.COUNTERCLOCKWISE_90) {
            return rotated;
        }
        // 90° и 270° меняют ось местами: positive и negative смотрят врозь.
        return rotated
                .setValue(WIRE_END_POSITIVE, state.getValue(WIRE_END_NEGATIVE))
                .setValue(WIRE_END_NEGATIVE, state.getValue(WIRE_END_POSITIVE));
    }

    /**
     * Зеркалирование состояния проволоки.
     *
     * <p>Зеркало вдоль оси (FRONT_BACK для X, LEFT_RIGHT для Z) обращает
     * направление positive: концы меняются местами. Диагональных зеркал в
     * ванилле нет, поэтому остальные случаи сводятся к перестановке языков,
     * которую делает {@link SectionBlock#mirror(BlockState, Mirror)}.
     */
    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        BlockState mirrored = super.mirror(state, mirror);
        if (mirror == Mirror.NONE) {
            return mirrored;
        }
        return mirrored
                .setValue(WIRE_END_POSITIVE, state.getValue(WIRE_END_NEGATIVE))
                .setValue(WIRE_END_NEGATIVE, state.getValue(WIRE_END_POSITIVE));
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

    /**
     * Коллизия — только стойка, и только при {@link #WIRE_BRACED}. Нить
     * намеренно не входит: сквозь проволоку нужно проходить, иначе
     * {@link #entityInside} почти не срабатывает — игрок упирается в
     * невидимую стену вместо того, чтобы зацепиться за нить.
     *
     * <p>Без стойки форма пустая, поэтому висящая в воздухе проволока не
     * препятствие вообще.
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return Shapes.empty();
    }

    /**
     * Проволока проходима для поиска пути, в отличие от остальных секций
     * ({@link SectionBlock#isPathfindable} возвращает {@code false}).
     *
     * <p>Это осознанное исключение: проволока не стена, сквозь неё проходят и
     * получают урон — именно на этом построена её механика. Тип клетки для
     * мобов задаёт {@link #getBlockPathType} ({@code DAMAGE_OTHER}), поэтому
     * мобы всё равно обходят проволоку, но не считают её непроходимой.
     */
    @Override
    public boolean isPathfindable(BlockState state, BlockGetter level, BlockPos pos,
                                  PathComputationType type) {
        return true;
    }

    /**
     * Выделение блока и рейкаст — по видимой геометрии: габарит полотна плюс
     * стойка. Переопределение обязательно, потому что базовый
     * {@link SectionBlock#getShape} отдаёт сплошную панель 2×16×16, и при
     * наведении подсвечивалась бы она, а не проволока.
     */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                            CollisionContext context) {
        return state.getValue(AXIS) == Direction.Axis.X ? SHAPE_X : SHAPE_Z;
    }

    /**
     * Контакт с проволокой: замедление и урон при движении — та же механика,
     * что у сладких ягод ({@code SweetBerryBushBlock.entityInside}).
     *
     * <p>Замедление ставится всем, кроме лисы и пчелы (ванильные исключения),
     * а урон — только на сервере и только при фактическом смещении: стоящий
     * в проволоке игрок не получает урон. Частоту ограничивает ванильная
     * неуязвимость {@code LivingEntity}, поэтому собственный кулдаун не нужен.
     */
    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (!(entity instanceof LivingEntity)
                || entity.getType() == EntityType.FOX
                || entity.getType() == EntityType.BEE) {
            return;
        }
        entity.makeStuckInBlock(state, SLOWDOWN);
        if (level.isClientSide()) {
            return;
        }
        double dx = Math.abs(entity.getX() - entity.xOld);
        double dz = Math.abs(entity.getZ() - entity.zOld);
        if (dx >= HURT_SPEED_THRESHOLD || dz >= HURT_SPEED_THRESHOLD) {
            entity.hurt(level.damageSources().sweetBerryBush(), HURT_DAMAGE);
        }
    }

    /**
     * Мобы считают проволоку опасной клеткой и обходят её. Без этого хука
     * ванильный {@code WalkNodeEvaluator} узнаёт только {@code CACTUS} и
     * {@code SWEET_BERRY_BUSH} по явному {@code is(...)}, а проволока получила
     * бы тип от формы — {@code BLOCKED}, из-за чего моб упирался бы в неё,
     * вместо того чтобы искать обход.
     */
    @Nullable
    @Override
    public BlockPathTypes getBlockPathType(BlockState state, BlockGetter level, BlockPos pos,
                                           @Nullable Mob mob) {
        return BlockPathTypes.DAMAGE_OTHER;
    }
}
