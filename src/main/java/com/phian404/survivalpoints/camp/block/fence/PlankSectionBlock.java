package com.phian404.survivalpoints.camp.block.fence;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Секция из досок ({@code plank_section}) — деревянный заборчик.
 *
 * <p>Отличается от остальных секций только коллизией. Визуально это две доски
 * с большим просветом: игрок и мобы перешагивали такой блок с места, а через
 * просвет между досками мобы видели и простреливали двор. Поэтому коллизия
 * поднята до 1.5 блока ({@code 0..24}) и сделана сплошной, толщиной 4 px
 * ({@code 6..10}) — по видимой толщине доски.
 *
 * <p><b>Почему не всегда 1.5 блока.</b> Форма выше 1 блока залезает в
 * соседнюю клетку. Пока сверху воздух — это безопасно и как раз даёт
 * «забор, через который не перепрыгнуть». Но если сверху стоит следующий ярус
 * секций, проволока, перекрытие или пол вышки, форма должна оставаться
 * {@code 0..16}: иначе она перекрывала бы проход по второму ярусу и
 * препятствовала бы размещению блоков сверху.
 *
 * <p>Из-за зависимости от соседа сверху в свойствах блока обязателен
 * {@link net.minecraft.world.level.block.state.BlockBehaviour.Properties#dynamicShape()}:
 * без него {@code BlockStateBase.Cache} запоминает коллизию, посчитанную при
 * инициализации блоков, и {@code pos.above()} в ней всегда воздух.
 */
public class PlankSectionBlock extends SectionBlock {

    /** Коллизия вдоль оси Z ниже 1.5 блока: доска 4 px на высоту блока. */
    private static final VoxelShape COLLISION_Z =
            Block.box(6.0D, 0.0D, 0.0D, 10.0D, 16.0D, 16.0D);
    /** Коллизия вдоль оси X ниже 1.5 блока: доска 4 px на высоту блока. */
    private static final VoxelShape COLLISION_X =
            Block.box(0.0D, 0.0D, 6.0D, 16.0D, 16.0D, 10.0D);
    /** Коллизия вдоль оси Z высотой 1.5 блока: через такой забор не перепрыгнуть. */
    private static final VoxelShape COLLISION_Z_TALL =
            Block.box(6.0D, 0.0D, 0.0D, 10.0D, 24.0D, 16.0D);
    /** Коллизия вдоль оси X высотой 1.5 блока. */
    private static final VoxelShape COLLISION_X_TALL =
            Block.box(0.0D, 0.0D, 6.0D, 16.0D, 24.0D, 10.0D);

    /*
     * Форма выделения досок. Верх — 15, а не 16: в модели верхняя доска идёт
     * {@code Y 10..15}, выше просвет до потолка клетки. Рамка, доходящая до 16,
     * торчала над доской на лишний пиксель.
     *
     * <p>Толщина 2 px ({@code 7..9}) — по самой доске, как у металлических панелей. Языки той же толщины.
     */
    private static final VoxelShape SHAPE_Z =
            Block.box(7.0D, 2.0D, 0.0D, 9.0D, 15.0D, 16.0D);
    private static final VoxelShape SHAPE_X =
            Block.box(0.0D, 2.0D, 7.0D, 16.0D, 15.0D, 9.0D);

    private static final VoxelShape TONGUE_Z_NEGATIVE =
            Block.box(7.0D, 2.0D, -4.0D, 9.0D, 15.0D, 0.0D);
    private static final VoxelShape TONGUE_Z_POSITIVE =
            Block.box(7.0D, 2.0D, 16.0D, 9.0D, 15.0D, 20.0D);
    private static final VoxelShape TONGUE_X_NEGATIVE =
            Block.box(-4.0D, 2.0D, 7.0D, 0.0D, 15.0D, 9.0D);
    private static final VoxelShape TONGUE_X_POSITIVE =
            Block.box(16.0D, 2.0D, 7.0D, 20.0D, 15.0D, 9.0D);

    public PlankSectionBlock() {
        super(SoundType.WOOD);
    }

    /**
     * Коллизия забора: сплошная доска, поднятая до 1.5 блока, пока сверху
     * воздух.
     *
     * <p>Сосед сверху читается из {@code level} в момент вызова, а не берётся
     * из состояния: признака «сверху блок» в blockstate нет, и заводить его
     * ради коллизии значило бы держать лишнее свойство в каждой секции.
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        boolean tall = level.getBlockState(pos.above()).isAir();
        boolean xAxis = state.getValue(AXIS) == Direction.Axis.X;
        if (tall) {
            return xAxis ? COLLISION_X_TALL : COLLISION_Z_TALL;
        }
        return xAxis ? COLLISION_X : COLLISION_Z;
    }

    /**
     * Рамка выделения по видимой геометрии досок: доска 4 px, верх на 15,
     * языки той же толщины.
     *
     * <p>Верх на пиксель ниже потолка клетки — потому что верхняя доска модели
     * заканчивается на {@code 15}, и рамка до {@code 16} выглядела как лишняя
     * полоса над забором. Коллизия при этом остаётся до {@code 16}
     * (или до {@code 24} без соседа сверху): физику и обзор определяет
     * {@link #getCollisionShape}, а не эта форма.
     */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                               CollisionContext context) {
        return assembleShape(state,
                state.getValue(AXIS) == Direction.Axis.X ? SHAPE_X : SHAPE_Z,
                TONGUE_Z_NEGATIVE, TONGUE_Z_POSITIVE, TONGUE_X_NEGATIVE, TONGUE_X_POSITIVE);
    }
}
