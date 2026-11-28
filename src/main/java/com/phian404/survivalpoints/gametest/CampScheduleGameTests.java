package com.phian404.survivalpoints.gametest;

import com.phian404.survivalpoints.camp.CampPoi;
import com.phian404.survivalpoints.camp.CampPost;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampSavedData;
import com.phian404.survivalpoints.camp.CampSchedule;
import com.phian404.survivalpoints.camp.CampStructurePlacer;
import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.entity.BaseSurvivorNpcEntity;
import com.phian404.survivalpoints.entity.ai.CampRestGoal;
import com.phian404.survivalpoints.entity.ai.FarmerStandWorkGoal;
import com.phian404.survivalpoints.entity.ai.GuardPostGoal;
import com.phian404.survivalpoints.entity.role.SurvivorProfession;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * GameTest-сценарии суточного распорядка лагеря.
 *
 * <p>Фаза считается по времени суток, поэтому сценарий просто переставляет
 * время и проверяет поведение: ночью житель ложится спать и встаёт утром,
 * страж днём стоит у ворот и уходит на вышку с наступлением ночи, а фермер
 * работает только днём.
 */
@GameTestHolder("survivalpoints")
@PrefixGameTestTemplate(false)
public class CampScheduleGameTests {

    private static final String PLOT_SLOT_PREFIX = "schedule_plot_";

    /** 06:00 — рабочий день. */
    private static final long DAY_TIME = 1000L;

    /** 18:30 — вечер: работа окончена, посты пусты. */
    private static final long EVENING_TIME = 12500L;

    /** 20:00 — ночь: сон и вышки. */
    private static final long NIGHT_TIME = 14000L;

    @GameTest(template = "empty3x3x3", timeoutTicks = 700, batch = "campScheduleSleep")
    public static void civilian_sleeps_at_night_and_wakes(GameTestHelper helper) {
        platform(helper, 4);
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 2));
        BaseSurvivorNpcEntity npc = spawnNpc(helper, new BlockPos(0, 1, 0));

        CampRecord camp = new CampRecord("gametest_sleep", center, 8, null);
        CampSavedData.get(level).addCamp(camp);
        npc.setCampId(camp.getId());

        level.setDayTime(NIGHT_TIME);
        pollUntil(helper, 300, "ночью житель лёг спать", () -> {
            level.setDayTime(DAY_TIME);
            pollUntil(helper, 200, "утром житель встал", helper::succeed, () -> {
                if (npc.getPose() == Pose.SLEEPING) {
                    throw new IllegalStateException("житель всё ещё спит: " + describe(npc));
                }
            });
        }, () -> {
            if (npc.getPose() != Pose.SLEEPING) {
                throw new IllegalStateException("житель не спит: " + describe(npc));
            }
            CampRestGoal goal = restGoal(npc);
            if (goal == null || !goal.isSleeping()) {
                throw new IllegalStateException("гол отдыха не удерживает сон: " + describe(npc));
            }
            if (horizontalSqr(npc.blockPosition(), center) > 16) {
                throw new IllegalStateException("житель спит далеко от лагеря: " + describe(npc));
            }
        });
    }

    @GameTest(template = "empty3x3x3", timeoutTicks = 1400, batch = "campScheduleGuard")
    public static void guard_post_follows_schedule(GameTestHelper helper) {
        platform(helper, 7);
        ServerLevel level = helper.getLevel();
        level.setDayTime(DAY_TIME);

        BlockPos center = helper.absolutePos(new BlockPos(3, 1, 3));
        BlockPos gatePos = helper.absolutePos(new BlockPos(3, 1, 0));
        BlockPos towerPos = helper.absolutePos(new BlockPos(6, 1, 3));
        BaseGuardianEntity guard = spawnGuardian(helper, new BlockPos(5, 1, 5));

        CampRecord camp = new CampRecord("gametest_post", center, 10, null);
        CampPost gate = new CampPost(CampPost.Type.GATE, gatePos, 0.0F);
        CampPost tower = new CampPost(CampPost.Type.TOWER, towerPos, 0.0F);
        camp.addPost(gate);
        camp.addPost(tower);
        camp.assignGuardian(guard.getIdentityId(), gate);
        CampSavedData.get(level).addCamp(camp);
        guard.setCampId(camp.getId());
        guard.setCampPostId(gate.getId());

        // День: свой пост у ворот.
        pollUntil(helper, 400, "днём страж на воротах", () -> {
            level.setDayTime(NIGHT_TIME);
            // Ночь: вышка.
            pollUntil(helper, 400, "ночью страж на вышке", () -> {
                level.setDayTime(EVENING_TIME);
                // Вечер: поста нет, страж отдыхает.
                pollUntil(helper, 150, "вечером страж с поста ушёл", helper::succeed, () -> {
                    if (postGoal(guard) != null) {
                        throw new IllegalStateException("вечером страж остался на посту: "
                                + describeGuard(guard));
                    }
                });
            }, () -> requireInCell(guard, towerPos, "ночью"));
        }, () -> requireInCell(guard, gatePos, "днём"));
    }

    @GameTest(template = "empty3x3x3", timeoutTicks = 900, batch = "campScheduleFarm")
    public static void farmer_works_only_by_day(GameTestHelper helper) {
        platform(helper, 4);
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 2));
        List<BlockPos> plots = ring(helper, center);
        buildMatureWheat(level, plots);

        List<CampPoi> pois = new ArrayList<>();
        for (int i = 0; i < plots.size(); i++) {
            pois.add(new CampPoi(PLOT_SLOT_PREFIX + i, CampStructurePlacer.FARM_PLOT_TYPE, plots.get(i)));
        }
        CampRecord camp = new CampRecord("gametest_farmer", center, 4, null);
        camp.replacePois(PLOT_SLOT_PREFIX, pois);
        CampSavedData.get(level).addCamp(camp);

        BaseSurvivorNpcEntity farmer = spawnNpc(helper, new BlockPos(0, 1, 0));
        farmer.setCampId(camp.getId());
        farmer.setProfession(SurvivorProfession.FARMER);
        farmer.setWorkPoiPos(center);
        farmer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WHEAT_SEEDS, 16));

        // День: работа идёт, даже если грядки ждут уборки с самого начала.
        level.setDayTime(DAY_TIME);
        pollUntil(helper, 300, "днём фермер начал работу", () -> {
            level.setDayTime(EVENING_TIME);
            pollUntil(helper, 300, "вечером фермер завершил работу", () -> {
                pollUntil(helper, 100, "вечером грядки не трогают", helper::succeed,
                        () -> requireUntouched(level, plots, "вечером"));
            }, () -> requireWorkGoal(farmer, false));
        }, () -> requireWorkGoal(farmer, true));
    }

    /** Спелая пшеница не тронута: её не сняли и не пересадили. */
    private static void requireUntouched(ServerLevel level, List<BlockPos> plots, String stage) {
        for (BlockPos plot : plots) {
            BlockPos cropPos = plot.above();
            var state = level.getBlockState(cropPos);
            if (!state.is(Blocks.WHEAT) || state.getValue(CropBlock.AGE) != 7) {
                throw new IllegalStateException(stage + ": грядка изменилась: " + cropPos + " " + state);
            }
        }
    }

    private static void requireWorkGoal(BaseSurvivorNpcEntity farmer, boolean expected) {
        FarmerStandWorkGoal goal = workGoal(farmer);
        if (expected && goal == null) {
            throw new IllegalStateException("фермер не начал работу: " + describe(farmer));
        }
        if (!expected && goal != null) {
            throw new IllegalStateException("фермер работает не в своё время: " + describe(farmer));
        }
    }

    private static void requireInCell(BaseGuardianEntity guard, BlockPos postPos, String stage) {
        if (!guard.blockPosition().equals(postPos)) {
            throw new IllegalStateException(stage + ": страж не в клетке поста: "
                    + guard.blockPosition() + " вместо " + postPos + " " + describeGuard(guard));
        }
    }

    private static String describe(BaseSurvivorNpcEntity npc) {
        return "[pos=" + npc.blockPosition() + " pose=" + npc.getPose()
                + " goal=" + (restGoal(npc) == null ? "нет" : "отдых")
                + " work=" + (workGoal(npc) == null ? "нет" : "работа")
                + " time=" + npc.level().getDayTime() % 24000L
                + " phase=" + CampSchedule.of(npc.level()) + "]";
    }

    private static String describeGuard(BaseGuardianEntity guard) {
        GuardPostGoal goal = postGoal(guard);
        return "[pos=" + guard.blockPosition()
                + " goal=" + (goal == null ? "нет" : goal.getPost() + " onPost=" + goal.isAtPost())
                + " time=" + guard.level().getDayTime() % 24000L
                + " phase=" + CampSchedule.of(guard.level()) + "]";
    }

    @Nullable
    private static CampRestGoal restGoal(BaseSurvivorNpcEntity npc) {
        for (WrappedGoal wrapped : npc.goalSelector.getRunningGoals().toList()) {
            if (wrapped.getGoal() instanceof CampRestGoal goal) {
                return goal;
            }
        }
        return null;
    }

    @Nullable
    private static FarmerStandWorkGoal workGoal(BaseSurvivorNpcEntity npc) {
        for (WrappedGoal wrapped : npc.goalSelector.getRunningGoals().toList()) {
            if (wrapped.getGoal() instanceof FarmerStandWorkGoal goal) {
                return goal;
            }
        }
        return null;
    }

    @Nullable
    private static GuardPostGoal postGoal(BaseGuardianEntity guard) {
        for (WrappedGoal wrapped : guard.goalSelector.getRunningGoals().toList()) {
            if (wrapped.getGoal() instanceof GuardPostGoal goal) {
                return goal;
            }
        }
        return null;
    }

    private static void platform(GameTestHelper helper, int size) {
        for (int x = 0; x <= size; x++) {
            for (int z = 0; z <= size; z++) {
                helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, z)),
                        Blocks.STONE.defaultBlockState(), 3);
            }
        }
    }

    /** Зрелая пшеница на всех грядках плюс свет: тестовая арена не освещена. */
    private static void buildMatureWheat(ServerLevel level, List<BlockPos> plots) {
        for (BlockPos plot : plots) {
            level.setBlock(plot.above(2), Blocks.LIGHT.defaultBlockState(), 3);
            level.setBlock(plot, Blocks.FARMLAND.defaultBlockState(), 2);
            BlockPos cropPos = plot.above();
            level.getChunkAt(cropPos).setBlockState(cropPos,
                    Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), false);
        }
    }

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

    private static BaseSurvivorNpcEntity spawnNpc(GameTestHelper helper, BlockPos localPos) {
        BaseSurvivorNpcEntity npc = EntityType.byString("survivalpoints:base_survivor")
                .map(type -> (BaseSurvivorNpcEntity) type.create(helper.getLevel()))
                .orElseThrow(() -> new IllegalStateException("base_survivor не зарегистрирован"));
        place(helper, npc, localPos);
        return npc;
    }

    private static BaseGuardianEntity spawnGuardian(GameTestHelper helper, BlockPos localPos) {
        BaseGuardianEntity guard = EntityType.byString("survivalpoints:base_guardian")
                .map(type -> (BaseGuardianEntity) type.create(helper.getLevel()))
                .orElseThrow(() -> new IllegalStateException("base_guardian не зарегистрирован"));
        place(helper, guard, localPos);
        return guard;
    }

    private static void place(GameTestHelper helper, net.minecraft.world.entity.Entity entity,
                              BlockPos localPos) {
        BlockPos pos = helper.absolutePos(localPos);
        entity.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        helper.getLevel().addFreshEntity(entity);
    }

    private static double horizontalSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Повторяет условие раз в тик, пока оно не выполнится, и падает с последней
     * причиной, если бюджет тиков исчерпан.
     */
    private static void pollUntil(GameTestHelper helper, int leftTicks, String what,
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
            helper.fail(what + ": не выполнено за отведённые тики, последнее: " + problem);
            return;
        }
        helper.runAfterDelay(1, () -> pollUntil(helper, leftTicks - 1, what, onSuccess, assertion));
    }
}
