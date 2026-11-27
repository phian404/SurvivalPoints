package com.phian404.survivalpoints.gametest;

import com.phian404.survivalpoints.camp.CampFarmManager;
import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampPoi;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampSavedData;
import com.phian404.survivalpoints.camp.CampStructurePlacer;
import com.phian404.survivalpoints.camp.FarmWorkStation;
import com.phian404.survivalpoints.entity.BaseSurvivorNpcEntity;
import com.phian404.survivalpoints.entity.ai.FarmerStandWorkGoal;
import com.phian404.survivalpoints.entity.ai.task.NpcTask;
import com.phian404.survivalpoints.entity.role.SurvivorProfession;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
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
 * <p>Сцены кольца, сбора и посадки строятся программно: каменный пол, грядки,
 * фермер, лагерь с POI в {@link CampSavedData}. Тест реальной фермы берёт
 * грядки и вход из шаблона {@code farm_lvl1}, чтобы проверять ту же геометрию,
 * что игрок строит в мире.
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
        // Проверка каждый тик: пустое кольцо существует только в паузу после
        // последнего сбора (3–6 тиков), опрос раз в десять тиков её проскакивает.
        pollEveryTick(helper, 650, "сбор кольца с одной станции", helper::succeed, () -> {
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
                BlockState crop = helper.getLevel().getBlockState(plot.above());
                if (crop.is(Blocks.WHEAT)) {
                    throw new IllegalStateException("ждём сбора " + plot + " crop=" + crop
                            + " " + describe(goal, farmer));
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

    /**
     * Сбор идёт только из клетки, с которой грядка проходит
     * {@link FarmWorkStation#canWorkFrom}: иначе проверка покрытия ничего не
     * значит — фермер может «достать» грядку из соседней или нижней клетки.
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 700)
    public static void harvest_only_from_workable_position(GameTestHelper helper) {
        platform(helper);
        BaseSurvivorNpcEntity farmer = spawnFarmer(helper, 0, 1, 0);
        BlockPos center = helper.absolutePos(new BlockPos(1, 1, 1));
        List<BlockPos> plots = ring(helper, center);
        CampRecord camp = buildCamp(helper, center, plots, new HashSet<>(plots), true);
        linkFarmer(farmer, camp);

        boolean[] seen = new boolean[plots.size()];
        boolean[] workable = new boolean[plots.size()];
        pollEveryTick(helper, 650, "сбор только с рабочей клетки", helper::succeed, () -> {
            for (int i = 0; i < plots.size(); i++) {
                BlockPos plot = plots.get(i);
                boolean present = helper.getLevel().getBlockState(plot.above()).is(Blocks.WHEAT);
                // workable[i] — значение с прошлого тика, до того как культура исчезла.
                if (seen[i] && !present && !workable[i]) {
                    throw new IllegalStateException("культура " + plot + " убрана из клетки "
                            + farmer.blockPosition() + ", с которой canWorkFrom = false");
                }
                seen[i] = present;
                workable[i] = FarmWorkStation.canWorkFrom(
                        helper.getLevel(), farmer.blockPosition(), plot);
                if (present) {
                    throw new IllegalStateException("ждём сбора " + plot
                            + " " + describe(farmer.getWorkGoal(), farmer));
                }
            }
        });
    }

    /**
     * Голова фермера не дёргается: наклон не меняется больше чем на 15° за тик,
     * а на рабочей позиции — и азимут не прыгает. Ванильный LookControl
     * обнуляет наклон за один тик, если цель взгляда перестаёт обновляться
     * (ожидание cooldown, пауза после неудачи, конец сессии).
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 700)
    public static void head_turns_are_smooth(GameTestHelper helper) {
        platform(helper);
        BaseSurvivorNpcEntity farmer = spawnFarmer(helper, 0, 1, 0);
        BlockPos center = helper.absolutePos(new BlockPos(1, 1, 1));
        List<BlockPos> plots = ring(helper, center);
        CampRecord camp = buildCamp(helper, center, plots, new HashSet<>(plots), true);
        linkFarmer(farmer, camp);

        float[] prevPitch = {farmer.getXRot()};
        float[] prevYaw = {farmer.getYHeadRot()};
        String[] jump = new String[1];
        int[] tick = {0};
        helper.onEachTick(() -> {
            tick[0]++;
            float pitch = farmer.getXRot();
            float yaw = farmer.getYHeadRot();
            float dp = Math.abs(Mth.wrapDegrees(pitch - prevPitch[0]));
            float dy = Math.abs(Mth.wrapDegrees(yaw - prevYaw[0]));
            FarmerStandWorkGoal goal = farmer.getWorkGoal();
            boolean working = goal != null && goal.getState() == FarmerStandWorkGoal.State.WORKING;
            prevPitch[0] = pitch;
            prevYaw[0] = yaw;
            if (jump[0] != null) {
                return;
            }
            if (dp > 15.0F) {
                jump[0] = "рывок наклона на тике " + tick[0] + ": " + dp + "°";
            } else if (working && dy > 12.0F) {
                jump[0] = "рывок поворота на тике " + tick[0] + ": " + dy + "°";
            }
        });
        pollEveryTick(helper, 650, "кольцо собрано без рывков головы", helper::succeed, () -> {
            if (jump[0] != null) {
                FarmerStandWorkGoal goal = farmer.getWorkGoal();
                throw new IllegalStateException(jump[0] + " "
                        + (goal == null ? "goal=null" : describe(goal, farmer)));
            }
            for (BlockPos plot : plots) {
                if (helper.getLevel().getBlockState(plot.above()).is(Blocks.WHEAT)) {
                    FarmerStandWorkGoal goal = farmer.getWorkGoal();
                    throw new IllegalStateException("ждём сбора " + plot + " "
                            + (goal == null ? "goal=null" : describe(goal, farmer)));
                }
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
        CampRecord camp = buildCamp(helper, center, all, new HashSet<>(mature), true);
        linkFarmer(farmer, camp);

        boolean[] plantSessionSeen = new boolean[1];
        boolean[] harvestStarted = new boolean[1];
        boolean[] cleared = new boolean[mature.size()];
        pollEveryTick(helper, 1100, "сбор и посадка двумя сессиями", helper::succeed, () -> {
            FarmerStandWorkGoal goal = farmer.getWorkGoal();
            if (goal == null) {
                throw new IllegalStateException("FarmerStandWorkGoal не зарегистрирован у фермера");
            }
            String type = goal.isSessionActive() ? goal.getSessionType() : null;
            if (CampFarmManager.TYPE_HARVEST.equals(type)) {
                harvestStarted[0] = true;
            }
            // Сбор доказывается по пустой грядке хотя бы на один тик: сразу
            // после сбора на ней начинается посадка, и по конечному состоянию
            // убранное поле не отличить от только что посаженного ростка.
            for (int i = 0; i < mature.size(); i++) {
                if (!helper.getLevel().getBlockState(mature.get(i).above()).is(Blocks.WHEAT)) {
                    cleared[i] = true;
                }
            }
            if (CampFarmManager.TYPE_PLANT.equals(type)) {
                if (!harvestStarted[0]) {
                    throw new IllegalStateException("посадка началась без сессии сбора");
                }
                for (int i = 0; i < cleared.length; i++) {
                    if (!cleared[i]) {
                        throw new IllegalStateException("посадка началась до сбора " + mature.get(i)
                                + " " + describe(goal, farmer));
                    }
                }
                plantSessionSeen[0] = true;
            }
            if (!plantSessionSeen[0]) {
                throw new IllegalStateException("посадка шла без отдельной сессии посадки");
            }
            // Итог: заняты все восемь грядок — освободившиеся тоже засеяны.
            for (BlockPos plot : all) {
                if (!helper.getLevel().getBlockState(plot.above()).is(Blocks.WHEAT)) {
                    throw new IllegalStateException("ждём посадки " + plot + " " + describe(goal, farmer));
                }
            }
            if (!goal.isSessionActive() && goal.getSessionStarts() != 2) {
                throw new IllegalStateException("сбор и посадка заняли не две сессии: starts="
                        + goal.getSessionStarts());
            }
        });
    }

    /**
     * Зрелая грядка, сбор которой ждёт cooldown, не даёт начать посадку:
     * фермер сначала дособирает лагерь и только потом сеет. Раньше сессия
     * сбора закрывалась по idle-таймауту, посадка стартовала раньше досбора,
     * и грядка в cooldown оставалась зрелой до следующего цикла.
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 900)
    public static void mature_plot_in_cooldown_blocks_planting(GameTestHelper helper) {
        platform(helper);
        BaseSurvivorNpcEntity farmer = spawnFarmer(helper, 0, 1, 0);
        BlockPos center = helper.absolutePos(new BlockPos(1, 1, 1));
        List<BlockPos> plots = ring(helper, center);
        // Грядки подальше от точки спавна: под самим фермером культуру не берём.
        List<BlockPos> mature = plots.subList(4, 7);
        BlockPos delayed = mature.get(0);
        CampRecord camp = buildCamp(helper, center, plots, new HashSet<>(mature), true);

        boolean[] delayedHarvested = new boolean[1];
        boolean[] plantingStarted = new boolean[1];
        String[] violation = new String[1];
        helper.runAfterDelay(1, () -> {
            // Две неудачи подряд: cooldown 120 тиков длиннее idle-таймаута
            // сессии сбора (80), поэтому без проверки лагеря посадка начнётся
            // раньше, чем эта грядка будет собрана.
            ServerLevel level = helper.getLevel();
            CampFarmManager manager = CampManager.farmManager(camp);
            long now = level.getGameTime();
            manager.refresh(level, camp, now);
            NpcTask task = manager.takeAt(level, camp, null, UUID.randomUUID(), now,
                    CampFarmManager.TYPE_HARVEST, delayed);
            if (task == null) {
                throw new IllegalStateException("задача сбора " + delayed + " не выдана: "
                        + manager.diagnosticSummary(now));
            }
            manager.markFailed(task.getId(), now);
            manager.markFailed(task.getId(), now);
            linkFarmer(farmer, camp);

            pollEveryTick(helper, 850, "посадка не опережает досбор", helper::succeed, () -> {
                FarmerStandWorkGoal goal = farmer.getWorkGoal();
                if (goal == null) {
                    throw new IllegalStateException("FarmerStandWorkGoal не зарегистрирован у фермера");
                }
                boolean delayedMature = isMature(helper, delayed);
                if (!delayedMature) {
                    delayedHarvested[0] = true;
                }
                // Нарушение запоминается: посадка могла успеть пройти и до
                // следующего опроса, а провал должен остаться заметным.
                if (delayedMature && goal.isSessionActive()
                        && CampFarmManager.TYPE_PLANT.equals(goal.getSessionType())) {
                    violation[0] = "посадка началась при зрелой грядке " + delayed + " "
                            + describe(goal, farmer);
                }
                if (violation[0] != null) {
                    throw new IllegalStateException(violation[0]);
                }
                if (CampFarmManager.TYPE_PLANT.equals(goal.getSessionType())) {
                    plantingStarted[0] = true;
                }
                String note = describe(goal, farmer)
                        + " delayed=" + (delayedMature ? "зрелая" : "свободна")
                        + " " + CampManager.farmManager(camp)
                                .diagnosticSummary(helper.getLevel().getGameTime());
                if (!plantingStarted[0]) {
                    throw new IllegalStateException("посадка так и не началась " + note);
                }
                if (!delayedHarvested[0]) {
                    throw new IllegalStateException("грядка в cooldown так и не собрана " + note);
                }
                for (BlockPos plot : plots) {
                    if (!helper.getLevel().getBlockState(plot.above()).is(Blocks.WHEAT)) {
                        throw new IllegalStateException("ждём посадки " + plot + " " + note);
                    }
                }
            });
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

    // ==================== реальная ферма ====================

    /**
     * Ферма из шаблона {@code farm_lvl1}: восемь грядок вокруг люка, забор с
     * трёх сторон и вход с четвёртой. Грядки и вход берутся из блоков
     * структуры, а не из координат, поэтому тот же сценарий годится для
     * {@code farm_lvl2}. Проверяется главное: весь урожай снимается с одной
     * рабочей позиции, без перебежек между грядками.
     */
    @GameTest(template = "farm_lvl1", timeoutTicks = 900)
    public static void real_farm_harvested_from_one_station(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> plots = farmland(helper);
        if (plots.size() != 8) {
            throw new IllegalStateException("в шаблоне ожидали 8 грядок, нашли " + plots.size()
                    + ": " + plots);
        }
        BlockPos center = plotsCenter(plots);
        for (BlockPos plot : plots) {
            level.setBlock(plot.above(2), Blocks.LIGHT.defaultBlockState(), 3);
            BlockPos crop = plot.above();
            level.getChunkAt(crop).setBlockState(crop,
                    Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), false);
        }
        List<CampPoi> pois = new ArrayList<>();
        for (int i = 0; i < plots.size(); i++) {
            pois.add(new CampPoi(PLOT_SLOT_PREFIX + i, CampStructurePlacer.FARM_PLOT_TYPE, plots.get(i)));
        }
        CampRecord camp = new CampRecord("gametest_farm_lvl1", center, 5, null);
        camp.replacePois(PLOT_SLOT_PREFIX, pois);
        CampSavedData.get(level).addCamp(camp);

        // Вход — проём в заборе: дорожка снаружи ведёт к нему с севера шаблона.
        // Грядки вкопаны в землю, поэтому дорожка и рабочая позиция — на
        // уровень выше самой грядки.
        BlockPos entrance = center.offset(0, 1, -4);
        BaseSurvivorNpcEntity farmer = spawnFarmerAt(helper, entrance);
        linkFarmer(farmer, camp);

        BlockPos[] stand = new BlockPos[1];
        int[] harvestStarts = {-1};
        pollEveryTick(helper, 800, "сбор фермы из шаблона с одной станции", helper::succeed, () -> {
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
            String type = goal.isSessionActive() ? goal.getSessionType() : null;
            if (CampFarmManager.TYPE_HARVEST.equals(type)) {
                if (harvestStarts[0] < 0) {
                    harvestStarts[0] = goal.getSessionStarts();
                } else if (harvestStarts[0] != goal.getSessionStarts()) {
                    throw new IllegalStateException("сбор фермы растянулся на несколько сессий: "
                            + harvestStarts[0] + " и " + goal.getSessionStarts());
                }
            }
            for (BlockPos plot : plots) {
                if (helper.getLevel().getBlockState(plot.above()).is(Blocks.WHEAT)) {
                    throw new IllegalStateException("ждём сбора " + plot + " " + describe(goal, farmer));
                }
            }
            if (stand[0] == null) {
                throw new IllegalStateException("фермер ни разу не встал на рабочую станцию");
            }
            if (harvestStarts[0] != 1) {
                throw new IllegalStateException("сбор фермы начался не первой сессией: "
                        + harvestStarts[0] + " вход=" + entrance);
            }
        });
    }

    /**
     * Семя берётся из инвентаря в руку на старте сессии посадки и остаётся там,
     * пока NPC идёт к грядкам: игрок видит предмет, а не пустую руку. Расход —
     * ровно один предмет на посадку, остальные семена возвращаются в инвентарь.
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 900)
    public static void seed_taken_from_inventory_for_planting(GameTestHelper helper) {
        platform(helper);
        BaseSurvivorNpcEntity farmer = spawnFarmer(helper, 0, 1, 0);
        BlockPos center = helper.absolutePos(new BlockPos(1, 1, 1));
        List<BlockPos> plots = List.of(
                helper.absolutePos(new BlockPos(1, 1, 2)),
                helper.absolutePos(new BlockPos(3, 1, 2)));
        CampRecord camp = buildCamp(helper, center, plots, Set.of(), false);
        linkFarmer(farmer, camp);
        farmer.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        farmer.getInventory().setStackInSlot(0, new ItemStack(Items.WHEAT_SEEDS, 16));

        boolean[] handSeen = new boolean[1];
        pollEveryTick(helper, 800, "семя в руке во время сессии посадки", helper::succeed, () -> {
            FarmerStandWorkGoal goal = farmer.getWorkGoal();
            if (goal == null) {
                throw new IllegalStateException("FarmerStandWorkGoal не зарегистрирован у фермера");
            }
            if (!handSeen[0] && goal.isSessionActive()
                    && CampFarmManager.TYPE_PLANT.equals(goal.getSessionType())) {
                if (!farmer.getMainHandItem().is(Items.WHEAT_SEEDS)) {
                    throw new IllegalStateException("в сессии посадки рука без семян: "
                            + farmer.getMainHandItem());
                }
                handSeen[0] = true;
            }
            for (BlockPos plot : plots) {
                if (!helper.getLevel().getBlockState(plot.above()).is(Blocks.WHEAT)) {
                    return;
                }
            }
            if (!handSeen[0]) {
                throw new IllegalStateException("посадка прошла без семени в руке");
            }
            int seeds = countSeeds(farmer);
            if (seeds != 14) {
                throw new IllegalStateException("после двух посадок семян " + seeds + ", а не 14");
            }
            helper.succeed();
        });
    }

    // ==================== инфраструктура ====================

    private static int countSeeds(BaseSurvivorNpcEntity farmer) {
        int count = farmer.getMainHandItem().is(Items.WHEAT_SEEDS)
                ? farmer.getMainHandItem().getCount() : 0;
        for (int slot = 0; slot < farmer.getInventory().getSlots(); slot++) {
            ItemStack stack = farmer.getInventory().getStackInSlot(slot);
            if (stack.is(Items.WHEAT_SEEDS)) {
                count += stack.getCount();
            }
        }
        return count;
    }

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
        pollOnce(helper, budgetTicks, budgetTicks, POLL_TICKS, what, onSuccess, assertion);
    }

    /**
     * То же, но с проверкой каждый тик: короткое состояние (грядка пуста между
     * сбором и посадкой) опросом раз в десять тиков можно проскочить.
     */
    private static void pollEveryTick(GameTestHelper helper, int budgetTicks, String what,
                                      Runnable onSuccess, Runnable assertion) {
        pollOnce(helper, budgetTicks, budgetTicks, 1, what, onSuccess, assertion);
    }

    private static void pollOnce(GameTestHelper helper, int totalTicks, int leftTicks, int step,
                                 String what, Runnable onSuccess, Runnable assertion) {
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
        int left = leftTicks - step;
        helper.runAfterDelay(step,
                () -> pollOnce(helper, totalTicks, left, step, what, onSuccess, assertion));
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

    /** Все грядки (farmland) из поставленного шаблона — сценарий не знает координат. */
    private static List<BlockPos> farmland(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        List<BlockPos> plots = new ArrayList<>();
        for (int y = -1; y <= 3; y++) {
            for (int x = -1; x <= 11; x++) {
                for (int z = -1; z <= 11; z++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (level.getBlockState(pos).is(Blocks.FARMLAND)) {
                        plots.add(pos);
                    }
                }
            }
        }
        return plots;
    }

    /** Центр кольца грядок: у симметричной фермы совпадает с люком. */
    private static BlockPos plotsCenter(List<BlockPos> plots) {
        int x = 0;
        int y = 0;
        int z = 0;
        for (BlockPos plot : plots) {
            x += plot.getX();
            y += plot.getY();
            z += plot.getZ();
        }
        int n = plots.size();
        return new BlockPos(Math.round(x / (float) n), Math.round(y / (float) n),
                Math.round(z / (float) n));
    }

    private static BaseSurvivorNpcEntity spawnFarmer(GameTestHelper helper, int x, int y, int z) {
        return spawnFarmerAt(helper, helper.absolutePos(new BlockPos(x, y, z)));
    }

    private static BaseSurvivorNpcEntity spawnFarmerAt(GameTestHelper helper, BlockPos spawnPos) {
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
        fillScene(level, plots, maturePlots, center, waterCenter);
        // Арена применяет отложенные обновления структуры уже после setup и
        // иногда убирает только что поставленные ростки. На первом тике сцену
        // возвращаем и проверяем ещё раз — тогда тест не зависит от порядка.
        helper.runAfterDelay(1, () -> {
            fillScene(level, plots, maturePlots, center, waterCenter);
            requireScene("после первого тика", level, plots, maturePlots);
        });

        List<CampPoi> pois = new ArrayList<>();
        for (int i = 0; i < plots.size(); i++) {
            pois.add(new CampPoi(PLOT_SLOT_PREFIX + i, CampStructurePlacer.FARM_PLOT_TYPE, plots.get(i)));
        }
        CampRecord record = new CampRecord("gametest", center, 4, null);
        record.replacePois(PLOT_SLOT_PREFIX, pois);
        CampSavedData.get(level).addCamp(record);
        return record;
    }

    private static void fillScene(ServerLevel level, List<BlockPos> plots, Set<BlockPos> maturePlots,
                                  BlockPos center, boolean waterCenter) {
        for (BlockPos plot : plots) {
            // Тестовая арена не освещена (sky=0): без источника света пшеница
            // не переживает установку. Блок света прозрачен для лучей и коллизий.
            level.setBlock(plot.above(2), Blocks.LIGHT.defaultBlockState(), 3);
        }
        if (waterCenter) {
            level.setBlock(center, Blocks.WATER.defaultBlockState(), 3);
        }
        for (BlockPos plot : plots) {
            // Флаг 2 (только клиенты) без соседних обновлений: свет успевает
            // разойтись до первой проверки роста, и сцена не зависит от порядка.
            level.setBlock(plot, Blocks.FARMLAND.defaultBlockState(), 2);
            BlockPos cropPos = plot.above();
            BlockState cropState = maturePlots.contains(plot)
                    ? Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7)
                    : Blocks.AIR.defaultBlockState();
            // Прямая запись в чанк: обычный setBlock иногда откатывается
            // отложенными обновлениями поставленной структуры.
            level.getChunkAt(cropPos).setBlockState(cropPos, cropState, false);
        }
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

    /** Зрелая пшеница над грядкой. */
    private static boolean isMature(GameTestHelper helper, BlockPos plot) {
        BlockState crop = helper.getLevel().getBlockState(plot.above());
        return crop.is(Blocks.WHEAT) && crop.getValue(CropBlock.AGE) >= 7;
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
