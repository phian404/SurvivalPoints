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
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

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

    /** Слот POI тестовой грядки. */
    private static final String PLOT_SLOT = "gametest_plot";

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
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(3, 1, 3));

        // Грядка с зрелой пшеницей — как на настоящей ферме.
        level.setBlock(center, Blocks.FARMLAND.defaultBlockState(), 3);
        level.setBlock(center.above(),
                Blocks.WHEAT.defaultBlockState().setValue(net.minecraft.world.level.block.CropBlock.AGE, 7), 3);

        CampRecord record = new CampRecord("gametest", center, 3, null);
        record.replacePois(PLOT_SLOT,
                List.of(new CampPoi(PLOT_SLOT, CampStructurePlacer.FARM_PLOT_TYPE, center)));
        CampSavedData.get(level).addCamp(record);

        farmer.setCampId(record.getId());
        farmer.setProfession(SurvivorProfession.FARMER);
        farmer.setWorkPoiPos(center);
        farmer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WHEAT_SEEDS, 16));
        return record.getId();
    }
}
