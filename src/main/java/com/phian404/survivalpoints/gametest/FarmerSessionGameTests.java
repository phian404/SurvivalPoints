package com.phian404.survivalpoints.gametest;

import com.phian404.survivalpoints.camp.CampFarmManager;
import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampPoi;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampSavedData;
import com.phian404.survivalpoints.camp.CampStructurePlacer;
import com.phian404.survivalpoints.entity.BaseSurvivorNpcEntity;
import com.phian404.survivalpoints.entity.ai.FarmerStandWorkGoal;
import com.phian404.survivalpoints.entity.ai.task.NpcTask;
import com.phian404.survivalpoints.entity.role.SurvivorProfession;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * GameTest-сценарии фермера, работающего со станции.
 *
 * <p>Каждый тест строит мир программно: каменный пол, грядки, фермер, лагерь с
 * POI в {@link CampSavedData}. Никаких .snbt-структур — только код, чтобы
 * сценарии не расходились с изменениями генератора лагеря.
 */
@GameTestHolder("survivalpoints")
@PrefixGameTestTemplate(false)
public class FarmerSessionGameTests {

    /** Префикс слотов POI тестовых грядок. */
    private static final String PLOT_SLOT_PREFIX = "gametest_plot_";

    // ==================== станция ====================

    /**
     * Кольцо из восьми зрелых грядок вокруг люка: фермер обязан собрать всё,
     * не сходя с выбранной станции, за одну сессию.
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 700)
    public static void ring_harvested_from_one_station(GameTestHelper helper) {
        platform(helper);
        BaseSurvivorNpcEntity farmer = spawnFarmer(helper, 0, 1, 0);
        BlockPos center = helper.absolutePos(new BlockPos(1, 1, 1));
        List<BlockPos> plots = ring(helper, center);
        CampRecord camp = buildCamp(helper, center, plots, new HashSet<>(plots), true);
        linkFarmer(farmer, camp);

        BlockPos[] stand = new BlockPos[1];
        pollUntil(helper, 650, "сбор кольца с одной станции", helper::succeed, () -> {
            FarmerStandWorkGoal goal = farmer.getWorkGoal();
            if (goal == null) {
                throw new IllegalStateException("FarmerStandWorkGoal не зарегистрирован у фермера");
            }
            if (goal.getState() == FarmerStandWorkGoal.State.WORKING) {
                if (stand[0] == null) {
                    stand[0] = goal.getStand();
                }
                if (stand[0] != null && horizontalSqr(farmer.blockPosition(), stand[0]) > 4.0D) {
                    throw new IllegalStateException("фермер ушёл со станции между грядками: "
                            + farmer.blockPosition() + " вместо " + stand[0]);
                }
            }
            for (BlockPos plot : plots) {
                if (helper.getLevel().getBlockState(plot.above()).is(Blocks.WHEAT)) {
                    throw new IllegalStateException("ждём сбора " + plot + " " + describe(goal, farmer));
                }
            }
            if (goal.getSessionStarts() != 1) {
                throw new IllegalStateException("сбор кольца занял " + goal.getSessionStarts() + " сессий");
            }
            if (stand[0] == null) {
                throw new IllegalStateException("фермер ни разу не встал на рабочую станцию");
            }
        });
    }

    /** Две пустые грядки засеиваются одной сессией с одной станции. */
    @GameTest(template = "empty3x3x3", timeoutTicks = 700)
    public static void two_plants_in_one_session(GameTestHelper helper) {
        platform(helper);
        BaseSurvivorNpcEntity farmer = spawnFarmer(helper, 0, 1, 0);
        BlockPos center = helper.absolutePos(new BlockPos(1, 1, 1));
        List<BlockPos> plots = List.of(
                helper.absolutePos(new BlockPos(1, 1, 2)),
                helper.absolutePos(new BlockPos(3, 1, 2)));
        CampRecord camp = buildCamp(helper, center, plots, Set.of(), false);
        linkFarmer(farmer, camp);

        pollUntil(helper, 650, "посадка двух грядок одной сессией", helper::succeed, () -> {
            for (BlockPos plot : plots) {
                BlockState crop = helper.getLevel().getBlockState(plot.above());
                if (!crop.is(Blocks.WHEAT)) {
                    throw new IllegalStateException("ждём посадки " + plot);
                }
            }
            FarmerStandWorkGoal goal = farmer.getWorkGoal();
            if (goal == null) {
                throw new IllegalStateException("FarmerStandWorkGoal не зарегистрирован у фермера");
            }
            if (goal.getSessionStarts() != 1) {
                throw new IllegalStateException("посадка двух грядок заняла "
                        + goal.getSessionStarts() + " сессий");
            }
        });
    }

    /**
     * Сбор и посадка — две разные сессии: сначала снимается всё зрелое, затем
     * на освободившихся грядках начинается посадка.
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 1200)
    public static void harvest_then_plant_in_two_sessions(GameTestHelper helper) {
        platform(helper);
        BaseSurvivorNpcEntity farmer = spawnFarmer(helper, 0, 1, 0);
        BlockPos center = helper.absolutePos(new BlockPos(1, 1, 1));
        List<BlockPos> all = ring(helper, center);
        List<BlockPos> mature = all.subList(0, 4);
        List<BlockPos> empty = all.subList(4, 8);
        CampRecord camp = buildCamp(helper, center, all, new HashSet<>(mature), true);
        linkFarmer(farmer, camp);

        boolean[] plantSessionSeen = new boolean[1];
        pollUntil(helper, 1100, "сбор и посадка двумя сессиями", helper::succeed, () -> {
            FarmerStandWorkGoal goal = farmer.getWorkGoal();
            if (goal == null) {
                throw new IllegalStateException("FarmerStandWorkGoal не зарегистрирован у фермера");
            }
            if (goal.isSessionActive()
                    && CampFarmManager.TYPE_PLANT.equals(goal.getSessionType())) {
                plantSessionSeen[0] = true;
            }
            for (BlockPos plot : mature) {
                if (helper.getLevel().getBlockState(plot.above()).is(Blocks.WHEAT)) {
                    throw new IllegalStateException("ждём сбора " + plot + " " + describe(goal, farmer));
                }
            }
            for (BlockPos plot : empty) {
                if (!helper.getLevel().getBlockState(plot.above()).is(Blocks.WHEAT)) {
                    throw new IllegalStateException("ждём посадки " + plot + " " + describe(goal, farmer));
                }
            }
            if (!plantSessionSeen[0]) {
                throw new IllegalStateException("посадка шла без отдельной сессии посадки");
            }
            if (goal.getSessionStarts() < 2) {
                throw new IllegalStateException("сбор и посадка прошли в одной сессии: starts="
                        + goal.getSessionStarts());
            }
        });
    }

    // ==================== отказы ====================

    /**
     * Полный инвентарь: собирать некуда, сессия обязана не открываться вовсе —
     * фермер не зависает в ожидании и не бегает к грядке.
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 400)
    public static void full_inventory_ends_session(GameTestHelper helper) {
        platform(helper);
        BaseSurvivorNpcEntity farmer = spawnFarmer(helper, 0, 1, 0);
        BlockPos center = helper.absolutePos(new BlockPos(1, 1, 1));
        List<BlockPos> plots = List.of(helper.absolutePos(new BlockPos(2, 1, 1)));
        CampRecord camp = buildCamp(helper, center, plots, new HashSet<>(plots), false);
        linkFarmer(farmer, camp);
        fillInventory(farmer);

        helper.runAfterDelay(200, () -> {
            FarmerStandWorkGoal goal = farmer.getWorkGoal();
            if (goal == null) {
                helper.fail("FarmerStandWorkGoal не зарегистрирован у фермера");
            }
            if (goal.isSessionActive()) {
                helper.fail("Сессия идёт без свободного места в инвентаре");
            }
            if (!helper.getLevel().getBlockState(plots.get(0).above()).is(Blocks.WHEAT)) {
                helper.fail("Урожай собран, хотя инвентарь полон");
            }
            helper.succeed();
        });
    }

    // ==================== инфраструктура ====================

    /** Шаг между проверками условия, тиков. */
    private static final int POLL_TICKS = 10;

    /**
     * Опрашивает условие раз в {@link #POLL_TICKS} тиков, пока оно не выполнится,
     * и падает с последней причиной, если бюджет тиков исчерпан. Опора на
     * собственную проверку, а не на {@code succeedWhen}: состояние теста видно
     * в сообщении об ошибке, и повтор не зависит от внутренней семантики
     * retry в GameTest-фреймворке.
     */
    private static void pollUntil(GameTestHelper helper, int budgetTicks, String what,
                                  Runnable onSuccess, Runnable assertion) {
        pollOnce(helper, budgetTicks, budgetTicks, what, onSuccess, assertion);
    }

    private static void pollOnce(GameTestHelper helper, int totalTicks, int leftTicks, String what,
                                 Runnable onSuccess, Runnable assertion) {
        String problem;
        try {
            assertion.run();
            problem = null;
        } catch (RuntimeException e) {
            problem = String.valueOf(e.getMessage());
        }
        if (problem == null) {
            onSuccess.run();
            return;
        }
        if (leftTicks <= 0) {
            helper.fail(what + ": не выполнено за " + totalTicks + " тиков, последнее: " + problem);
            return;
        }
        int left = leftTicks - POLL_TICKS;
        helper.runAfterDelay(POLL_TICKS,
                () -> pollOnce(helper, totalTicks, left, what, onSuccess, assertion));
    }

    /** Состояние цели в сообщении об ошибке: без него таймаут ничего не объясняет. */
    private static String describe(FarmerStandWorkGoal goal, BaseSurvivorNpcEntity farmer) {
        if (goal == null) {
            return "[goal=null]";
        }
        return "[state=" + goal.getState()
                + " session=" + goal.isSessionActive()
                + " type=" + goal.getSessionType()
                + " starts=" + goal.getSessionStarts()
                + " planned=" + goal.getPlannedCount()
                + " stand=" + goal.getStand()
                + " npc=" + farmer.blockPosition() + "]";
    }


    /** Каменный пол тестовой площадки: станции нужна опора под ногами. */
    private static void platform(GameTestHelper helper) {
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, z)),
                        Blocks.STONE.defaultBlockState(), 3);
            }
        }
    }

    /** Восемь грядок вокруг центральной клетки. */
    private static List<BlockPos> ring(GameTestHelper helper, BlockPos center) {
        List<BlockPos> plots = new ArrayList<>(8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                plots.add(center.offset(dx, 0, dz));
            }
        }
        return plots;
    }

    private static BaseSurvivorNpcEntity spawnFarmer(GameTestHelper helper, int x, int y, int z) {
        BlockPos spawnPos = helper.absolutePos(new BlockPos(x, y, z));
        BaseSurvivorNpcEntity farmer = EntityType.byString("survivalpoints:base_survivor")
                .map(type -> (BaseSurvivorNpcEntity) type.create(helper.getLevel()))
                .orElseThrow(() -> new IllegalStateException("base_survivor не зарегистрирован"));
        farmer.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        helper.getLevel().addFreshEntity(farmer);
        return farmer;
    }

    private static CampRecord buildCamp(GameTestHelper helper, BlockPos center,
                                        List<BlockPos> plots, Set<BlockPos> maturePlots,
                                        boolean waterCenter) {
        ServerLevel level = helper.getLevel();
        List<CampPoi> pois = new ArrayList<>();
        for (BlockPos plot : plots) {
            // Тестовая арена не освещена (sky=0): без источника света пшеница
            // не переживает установку. Блок света прозрачен для лучей и коллизий.
            level.setBlock(plot.above(2), Blocks.LIGHT.defaultBlockState(), 3);
        }
        if (waterCenter) {
            level.setBlock(center, Blocks.WATER.defaultBlockState(), 3);
        }
        for (int i = 0; i < plots.size(); i++) {
            BlockPos plot = plots.get(i);
            pois.add(new CampPoi(PLOT_SLOT_PREFIX + i, CampStructurePlacer.FARM_PLOT_TYPE, plot));
            // Флаг 2 (только клиенты) без соседних обновлений: свет успевает
            // разойтись до первой проверки роста, и сцена не зависит от порядка.
            level.setBlock(plot, Blocks.FARMLAND.defaultBlockState(), 2);
            BlockPos cropPos = plot.above();
            BlockState cropState = maturePlots.contains(plot)
                    ? Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7)
                    : Blocks.AIR.defaultBlockState();
            // Прямая запись в чанк: на первом тике теста обычный setBlock иногда
            // откатывается отложенными обновлениями поставленной структуры.
            level.getChunkAt(cropPos).setBlockState(cropPos, cropState, false);
            if (maturePlots.contains(plot) && !level.getBlockState(cropPos).is(Blocks.WHEAT)) {
                throw new IllegalStateException("кроп исчез сразу после установки #" + i
                        + " " + plot + " level=" + level.getBlockState(cropPos)
                        + " chunk=" + level.getChunkAt(cropPos).getBlockState(cropPos));
            }
        }
        requireScene("после установки", level, plots, maturePlots);

        CampRecord record = new CampRecord("gametest", center, 4, null);
        record.replacePois(PLOT_SLOT_PREFIX, pois);
        CampSavedData.get(level).addCamp(record);
        requireScene("после addCamp", level, plots, maturePlots);
        return record;
    }

    /** Страховка от пустых тестов: сцена обязана действительно содержать то, что проверяем. */
    private static void requireScene(String stage, ServerLevel level, List<BlockPos> plots,
                                     Set<BlockPos> maturePlots) {
        for (BlockPos plot : plots) {
            if (!level.getBlockState(plot).is(Blocks.FARMLAND)) {
                throw new IllegalStateException(stage + ": грядка не построена: " + plot
                        + " state=" + level.getBlockState(plot));
            }
            BlockState crop = level.getBlockState(plot.above());
            boolean mature = crop.is(Blocks.WHEAT) && crop.getValue(CropBlock.AGE) >= 7;
            if (maturePlots.contains(plot) != mature) {
                throw new IllegalStateException(stage + ": культура не совпала со сценарием: " + plot
                        + " expectedMature=" + maturePlots.contains(plot)
                        + " crop=" + crop
                        + " plotState=" + level.getBlockState(plot)
                        + " light=" + level.getMaxLocalRawBrightness(plot.above())
                        + " above=" + level.getBlockState(plot.above(2)));
            }
        }
    }

    private static void linkFarmer(BaseSurvivorNpcEntity farmer, CampRecord record) {
        farmer.setCampId(record.getId());
        farmer.setProfession(SurvivorProfession.FARMER);
        farmer.setWorkPoiPos(record.getCenter());
        farmer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WHEAT_SEEDS, 16));
    }

    private static void fillInventory(BaseSurvivorNpcEntity farmer) {
        for (int slot = 0; slot < farmer.getInventory().getSlots(); slot++) {
            farmer.getInventory().setStackInSlot(slot, new ItemStack(Items.STONE, 64));
        }
    }

    private static double horizontalSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}
