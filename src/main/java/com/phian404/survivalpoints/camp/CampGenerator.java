package com.phian404.survivalpoints.camp;

import com.phian404.survivalpoints.camp.blockentity.CampRadioBlockEntity;
import com.phian404.survivalpoints.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Генерация материального лагеря в мире.
 *
 * <p>Под-волна 2.1: ставит радио-вышку (якорь) в центр лагеря. Под-волна 2.2:
 * периметр — цоколь из смешанных каменных оград (первый блок от земли), над
 * ним три яруса «столб → пролёт» (посты {@code camp_fence_post} на углах и
 * через каждые 4 колонки, между ними — прутья {@code camp_fence}), сверху —
 * колючая проволока. В середине южной стороны — ворота 1×2 (фейс SOUTH).
 */
public class CampGenerator {

    private CampGenerator() {
    }

    /**
     * Построить материальную часть лагеря.
     *
     * @param level  серверный уровень
     * @param record лагерь
     */
    public static void generate(ServerLevel level, CampRecord record) {
        BlockPos anchor = record.getAnchor();
        // Очистить место под якорь (на случай, если там что-то стоит).
        level.setBlock(anchor, Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(anchor, ModBlocks.CAMP_RADIO.get().defaultBlockState(), 3);
        if (level.getBlockEntity(anchor) instanceof CampRadioBlockEntity radio) {
            radio.setCampId(record.getId());
        }
        generatePerimeter(level, record);
    }

    /**
     * Периметр: квадрат стороной 2*radius вокруг якоря.
     *
     * <p>Первый блок от земли — цоколь из ванильных каменных оград: кирпичная
     * и замшелая кирпичная вперемешку, с редкими вставками замшелой булыжной
     * и андезитовой. Над цоколем — ритм «столб → пролёт»: столбы
     * ({@code camp_fence_post}) на углах и через каждые 4 колонки, на блок
     * выше основания пролётов (ярусы y2–y4), между ними — три яруса прутьев
     * ({@code camp_fence}), над пролётами — колючая проволока. В середине
     * южной стороны — ворота 1×2 (фейс SOUTH).
     *
     * <p>Соединения прутьев и столбов рассчитываются вторым проходом через
     * {@code updateShape}: {@code setBlock} не вызывает
     * {@code getStateForPlacement}, поэтому {@code defaultBlockState} ставится
     * со всеми сторонами {@code false}.
     */
    private static void generatePerimeter(ServerLevel level, CampRecord record) {
        int r = record.getRadius();
        BlockPos center = record.getCenter();
        BlockState fence = ModBlocks.CAMP_FENCE.get().defaultBlockState();
        BlockState post = ModBlocks.CAMP_FENCE_POST.get().defaultBlockState();
        BlockState postTop = post.setValue(
                com.phian404.survivalpoints.camp.block.CampFencePostBlock.TOP, true);
        BlockState wire = ModBlocks.CAMP_BARBED_WIRE.get().defaultBlockState();
        BlockState gateLower = ModBlocks.CAMP_GATE.get().defaultBlockState()
                .setValue(DoorBlock.FACING, Direction.SOUTH)
                .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
        BlockState gateUpper = gateLower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);

        int postSpacing = 4;
        // Проход 1: цоколь и материя периметра (без расчёта соединений).
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                boolean edge = x == -r || x == r || z == -r || z == r;
                if (!edge) {
                    continue;
                }
                BlockPos pos = center.offset(x, 0, z);
                // Ворота — в середине южной стороны (z = r), проём в двух ярусах.
                if (z == r && x == 0) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(pos.above(2), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(pos.above(3), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(pos.above(), gateLower, 3);
                    level.setBlock(pos.above(2), gateUpper, 3);
                    record.setGatePos(pos.above());
                    continue;
                }
                // Цоколь — нижний блок; смешанные каменные ограды.
                level.setBlock(pos, plinthState(pos), 3);
                // Столб — на углах и по сетке (0, ±4, ±8, ...), во всю высоту
                // периметра от цоколя: ярусы y1–y3 — простая балка, y4 — с
                // подпорками-«Y». Решётки в столбе нет (решение владельца).
                boolean corner = (x == -r || x == r) && (z == -r || z == r);
                boolean onGrid = x % postSpacing == 0 && z % postSpacing == 0;
                if (corner || onGrid) {
                    level.setBlock(pos.above(), post, 3);
                    level.setBlock(pos.above(2), post, 3);
                    level.setBlock(pos.above(3), post, 3);
                    level.setBlock(pos.above(4), postTop, 3);
                    continue;
                }
                level.setBlock(pos.above(), fence, 3);
                level.setBlock(pos.above(2), fence, 3);
                level.setBlock(pos.above(3), fence, 3);
                level.setBlock(pos.above(4), wire, 3);
            }
        }
        // Проход 2: соединения прутьев/столбов по фактическим соседям.
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                boolean edge = x == -r || x == r || z == -r || z == r;
                if (!edge) {
                    continue;
                }
                BlockPos pos = center.offset(x, 0, z);
                if (z == r && x == 0) {
                    continue;
                }
                // Цоколь — стена, соединения по соседям.
                BlockState plinth = level.getBlockState(pos);
                if (plinth.getBlock() instanceof WallBlock) {
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos neighbor = pos.relative(dir);
                        plinth = plinth.updateShape(dir, level.getBlockState(neighbor),
                                level, pos, neighbor);
                    }
                    level.setBlock(pos, plinth, 3);
                }
                // Ярусы прутьев/столбов: y1–y3 — пролёты, y4 — верх столбов.
                for (int y = 1; y <= 4; y++) {
                    BlockPos barPos = pos.above(y);
                    BlockState bar = level.getBlockState(barPos);
                    if (!(bar.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock)) {
                        continue;
                    }
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos neighbor = barPos.relative(dir);
                        bar = bar.updateShape(dir, level.getBlockState(neighbor),
                                level, barPos, neighbor);
                    }
                    level.setBlock(barPos, bar, 3);
                }
                // Колючая проволока (y4, над пролётами) — свой блок со своими
                // сторонами: соединяется только с такой же проволокой, не с
                // прутьями и не с «Y»-верхом столба.
                BlockPos wirePos = pos.above(4);
                BlockState wireState = level.getBlockState(wirePos);
                if (wireState.getBlock() instanceof com.phian404.survivalpoints.camp.block.CampBarbedWireBlock) {
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        BlockPos neighbor = wirePos.relative(dir);
                        wireState = wireState.updateShape(dir, level.getBlockState(neighbor),
                                level, wirePos, neighbor);
                    }
                    level.setBlock(wirePos, wireState, 3);
                }
            }
        }
    }

    /**
     * Цоколь периметра — каменная ограда с детерминированным смешением:
     * каменный кирпич и замшелый каменный кирпич пополам, редкие вставки
     * (≈1/12) замшелого булыжника и андезита.
     */
    private static BlockState plinthState(BlockPos pos) {
        int h = Math.floorMod(pos.getX() * 31 + pos.getZ() * 17, 24);
        if (h == 0) {
            return Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState();
        }
        if (h == 12) {
            return Blocks.ANDESITE_WALL.defaultBlockState();
        }
        // Решение владельца: палитра из камня, без красного кирпича.
        return (h % 2 == 0)
                ? Blocks.MOSSY_STONE_BRICK_WALL.defaultBlockState()
                : Blocks.STONE_BRICK_WALL.defaultBlockState();
    }
}
