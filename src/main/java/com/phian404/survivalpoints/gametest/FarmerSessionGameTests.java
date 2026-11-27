package com.phian404.survivalpoints.gametest;

import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampPoi;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampSavedData;
import com.phian404.survivalpoints.camp.CampStructurePlacer;
import com.phian404.survivalpoints.entity.BaseSurvivorNpcEntity;
import com.phian404.survivalpoints.entity.ai.FarmerWorkGoal;
import com.phian404.survivalpoints.entity.role.SurvivorProfession;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * GameTest-сценарии рабочей сессии фермера.
 *
 * <p>Каждый тест строит мир программно: грядка, фермер, лагерь с POI в
 * {@link CampSavedData}. Никаких .snbt-структур — только код, чтобы сценарии
 * не расходились с изменениями генератора лагеря.
 */
@GameTestHolder("survivalpoints")
@PrefixGameTestTemplate(false)
public class FarmerSessionGameTests {

    /** Префикс слотов POI тестовых грядок. */
    private static final String PLOT_SLOT_PREFIX = "gametest_plot_";

    /** Сколько тиков ждём, пока фермер возьмёт задачу и начнёт движение. */
    private static final int WORK_START_TIMEOUT = 200;

    @GameTest(template = "empty3x3x3")
    public static void chunk_reload_resumes_work(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BaseSurvivorNpcEntity farmer = spawnFarmer(helper);
        UUID campId = registerCamp(helper, farmer);
        BlockPos plot = helper.absolutePos(new BlockPos(3, 1, 3));

        // Симуляция возврата из выгруженного чанка: runtime-состояние сброшено,
        // сохранённое (SavedData) обязано пережить это и снова отдавать задачу.
        CampManager.clearRuntimeState();

        helper.runAfterDelay(20, () ->
                helper.succeedWhen(() -> {
                    CampRecord camp = CampManager.getCamp(level, campId);
                    if (camp == null) {
                        helper.fail("Лагерь исчез после сброса runtime-состояния");
                    }
                    boolean poiRegistered = false;
                    for (CampPoi poi : camp.getPois()) {
                        if (CampStructurePlacer.FARM_PLOT_TYPE.equals(poi.getType())
                                && plot.equals(poi.getPos())) {
                            poiRegistered = true;
                            break;
                        }
                    }
                    if (!poiRegistered) {
                        helper.fail("POI грядки потерян после сброса runtime-состояния");
                    }
                    helper.succeed();
                }));
    }

    @GameTest(template = "empty3x3x3", timeoutTicks = 400)
    public static void farmer_takes_task_from_scan(GameTestHelper helper) {
        BaseSurvivorNpcEntity farmer = spawnFarmer(helper);
        registerCamp(helper, farmer);

        // Полная цепочка: регистрация POI → скан → take() → цель выходит из
        // IDLE и начинает движение к грядке. Опрашиваем с первого тика: работа
        // короткая, и позже фермер законно уходит в паузу (снова IDLE).
        helper.succeedWhen(() -> {
            FarmerWorkGoal workGoal = farmer.getWorkGoal();
            if (workGoal == null) {
                helper.fail("FarmerWorkGoal не зарегистрирован у фермера");
            }
            if (workGoal.getState() != FarmerWorkGoal.State.IDLE) {
                helper.succeed();
            }
        });

        helper.runAfterDelay(WORK_START_TIMEOUT, () ->
                helper.fail("Фермер не взял задачу: цель так и осталась в IDLE"));
    }

    @GameTest(template = "empty3x3x3", timeoutTicks = 800)
    public static void two_plots_in_a_row(GameTestHelper helper) {
        BaseSurvivorNpcEntity farmer = spawnFarmer(helper);
        List<BlockPos> plots = List.of(
                helper.absolutePos(new BlockPos(2, 1, 2)),
                helper.absolutePos(new BlockPos(4, 1, 4)));
        registerCamp(helper, farmer, plots, true);

        // Сессия обязана переживать паузу между задачами: обе зрелые грядки
        // должны быть собраны за одну серию, без остановки и перезапуска цели.
        boolean[] harvested = new boolean[plots.size()];
        helper.succeedWhen(() -> {
            for (int i = 0; i < plots.size(); i++) {
                if (harvested[i]) {
                    continue;
                }
                BlockState crop = helper.getLevel().getBlockState(plots.get(i).above());
                if (!crop.is(Blocks.WHEAT) || crop.getValue(CropBlock.AGE) < 7) {
                    harvested[i] = true;
                }
            }
            for (boolean done : harvested) {
                if (!done) {
                    return;
                }
            }
            FarmerWorkGoal goal = farmer.getWorkGoal();
            if (goal == null) {
                helper.fail("FarmerWorkGoal не зарегистрирован у фермера");
            }
            if (goal.getSessionStarts() != 1) {
                helper.fail("Обе грядки собраны за " + goal.getSessionStarts()
                        + " серии(й): FarmerWorkGoal останавливался между задачами");
            }
            helper.succeed();
        });

        helper.runAfterDelay(700, () ->
                helper.fail("Фермер не обработал обе грядки: сессия оборвалась между задачами"));
    }

    @GameTest(template = "empty3x3x3", timeoutTicks = 800)
    public static void two_plants_in_a_row(GameTestHelper helper) {
        BaseSurvivorNpcEntity farmer = spawnFarmer(helper);
        List<BlockPos> plots = List.of(
                helper.absolutePos(new BlockPos(2, 1, 2)),
                helper.absolutePos(new BlockPos(4, 1, 4)));
        registerCamp(helper, farmer, plots, false);

        // Обе пустые грядки должны быть засажены одной серией: посадка не
        // наполняет инвентарь, поэтому здесь серия не имеет права рваться.
        int[] planted = new int[1];
        helper.succeedWhen(() -> {
            planted[0] = 0;
            for (BlockPos plot : plots) {
                BlockState crop = helper.getLevel().getBlockState(plot.above());
                if (crop.is(Blocks.WHEAT)) {
                    planted[0]++;
                }
            }
            if (planted[0] < plots.size()) {
                return;
            }
            FarmerWorkGoal goal = farmer.getWorkGoal();
            if (goal == null) {
                helper.fail("FarmerWorkGoal не зарегистрирован у фермера");
            }
            if (goal.getSessionStarts() != 1) {
                helper.fail("Обе грядки засажены за " + goal.getSessionStarts()
                        + " серии(й): FarmerWorkGoal останавливался между задачами");
            }
            helper.succeed();
        });

        helper.runAfterDelay(700, () ->
                helper.fail("Фермер не засадил обе грядки: посажено " + planted[0]));
    }

    @GameTest(template = "empty3x3x3", timeoutTicks = 400)
    public static void full_inventory_ends_session(GameTestHelper helper) {
        BaseSurvivorNpcEntity farmer = spawnFarmer(helper);
        List<BlockPos> plots = List.of(helper.absolutePos(new BlockPos(3, 1, 3)));
        registerCamp(helper, farmer, plots, true);

        // Вместить урожай некуда: ждать бессмысленно, сессию держать нельзя.
        for (int slot = 0; slot < farmer.getInventory().getSlots(); slot++) {
            farmer.getInventory().setStackInSlot(slot, new ItemStack(Items.STONE, 64));
        }

        BlockPos plot = plots.get(0);
        helper.runAfterDelay(60, () -> helper.succeedWhen(() -> {
            FarmerWorkGoal goal = farmer.getWorkGoal();
            if (goal == null) {
                helper.fail("FarmerWorkGoal не зарегистрирован у фермера");
            }
            if (goal.isSessionActive()) {
                helper.fail("Фермер удерживает сессию сбора при полном инвентаре");
            }
            BlockState crop = helper.getLevel().getBlockState(plot.above());
            if (!crop.is(Blocks.WHEAT) || crop.getValue(CropBlock.AGE) < 7) {
                helper.fail("Сбор прошёл, хотя инвентарь полон");
            }
            helper.succeed();
        }));
    }

    // ==================== инфраструктура ====================

    private static BaseSurvivorNpcEntity spawnFarmer(GameTestHelper helper) {
        BlockPos spawnPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BaseSurvivorNpcEntity farmer = EntityType.byString("survivalpoints:base_survivor")
                .map(type -> (BaseSurvivorNpcEntity) type.create(helper.getLevel()))
                .orElseThrow(() -> new IllegalStateException("base_survivor не зарегистрирован"));
        farmer.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        helper.getLevel().addFreshEntity(farmer);
        return farmer;
    }

    private static UUID registerCamp(GameTestHelper helper, BaseSurvivorNpcEntity farmer) {
        return registerCamp(helper, farmer,
                List.of(helper.absolutePos(new BlockPos(3, 1, 3))), true);
    }

    private static UUID registerCamp(GameTestHelper helper, BaseSurvivorNpcEntity farmer,
                                     List<BlockPos> plots) {
        return registerCamp(helper, farmer, plots, true);
    }

    private static UUID registerCamp(GameTestHelper helper, BaseSurvivorNpcEntity farmer,
                                     List<BlockPos> plots, boolean mature) {
        ServerLevel level = helper.getLevel();
        BlockPos center = plots.get(0);
        List<CampPoi> pois = new ArrayList<>();
        for (int i = 0; i < plots.size(); i++) {
            BlockPos plot = plots.get(i);
            level.setBlock(plot, Blocks.FARMLAND.defaultBlockState(), 3);
            level.setBlock(plot.above(),
                    mature
                            ? Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7)
                            : Blocks.AIR.defaultBlockState(),
                    3);
            pois.add(new CampPoi(PLOT_SLOT_PREFIX + i, CampStructurePlacer.FARM_PLOT_TYPE, plot));
        }

        CampRecord record = new CampRecord("gametest", center, 3, null);
        record.replacePois(PLOT_SLOT_PREFIX, pois);
        CampSavedData.get(level).addCamp(record);

        farmer.setCampId(record.getId());
        farmer.setProfession(SurvivorProfession.FARMER);
        farmer.setWorkPoiPos(center);
        farmer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WHEAT_SEEDS, 16));
        return record.getId();
    }
}
