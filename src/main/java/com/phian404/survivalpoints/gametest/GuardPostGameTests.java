package com.phian404.survivalpoints.gametest;

import com.phian404.survivalpoints.camp.CampPost;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampSavedData;
import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.entity.ai.GuardPostGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;

/**
 * GameTest-сценарий поста стража.
 *
 * <p>Стража ставят вдали от поста с курсом наружу, который не совпадает ни с
 * направлением подхода, ни с направлением от центра лагеря. Тест проверяет,
 * что страж сам доходит до клетки поста, остаётся в ней и разворачивается
 * наружу — то есть пост из данных лагеря действительно управляет поведением.
 */
@GameTestHolder("survivalpoints")
@PrefixGameTestTemplate(false)
public class GuardPostGameTests {

    /** Сектор осмотра у стены — как в голе (градусы в каждую сторону). */
    private static final float SCAN_AMPLITUDE = 20.0F;

    /** Окно наблюдения за осмотром (тики): половина периода качания. */
    private static final int SAMPLE_TICKS = 70;

    @GameTest(template = "empty3x3x3", timeoutTicks = 600)
    public static void guard_reaches_post_and_faces_out(GameTestHelper helper) {
        platform(helper);
        ServerLevel level = helper.getLevel();

        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos postPos = helper.absolutePos(new BlockPos(4, 1, 2));
        BaseGuardianEntity guard = spawnGuardian(helper, new BlockPos(0, 1, 2));

        // Курс 45° задан постом явно: он не совпадает с направлением подхода
        // (запад) и с направлением от центра лагеря (восток).
        float outwardYaw = 45.0F;
        CampRecord camp = new CampRecord("gametest_guard", center, 8, null);
        CampPost post = new CampPost(CampPost.Type.WALL, postPos, outwardYaw);
        camp.addPost(post);
        camp.assignGuardian(guard.getIdentityId(), post);
        CampSavedData.get(level).addCamp(camp);
        guard.setCampId(camp.getId());
        guard.setCampPostId(post.getId());

        // Вторая фаза: страж в клетке поста и осматривается. Разброс курса за
        // окно наблюдения доказывает, что доворот действительно работает: без
        // него тело смотрит в одну сторону и разброс равен нулю.
        float[] seen = {Float.NaN, Float.NaN};
        int[] samples = {0};
        pollUntil(helper, 300, "страж дошёл до поста",
                () -> pollUntil(helper, 120, "страж осматривается на посту", helper::succeed, () -> {
                    requireInCell(guard, postPos, "на посту");
                    GuardPostGoal goal = postGoal(guard);
                    if (goal == null || !goal.isAtPost()) {
                        throw new IllegalStateException("гол поста не удерживает стража: "
                                + describe(goal, guard));
                    }
                    float body = guard.yBodyRot;
                    float error = Math.abs(Mth.wrapDegrees(body - outwardYaw));
                    if (error > SCAN_AMPLITUDE + 8.0F) {
                        throw new IllegalStateException("страж смотрит не наружу: тело "
                                + body + "°, пост " + outwardYaw + "°, ошибка " + error + "°");
                    }
                    seen[0] = Float.isNaN(seen[0]) ? body : Math.min(seen[0], body);
                    seen[1] = Float.isNaN(seen[1]) ? body : Math.max(seen[1], body);
                    if (++samples[0] < SAMPLE_TICKS) {
                        throw new IllegalStateException("наблюдаем " + samples[0] + "/" + SAMPLE_TICKS);
                    }
                    float swing = seen[1] - seen[0];
                    if (swing < 12.0F) {
                        throw new IllegalStateException("страж не осматривается: разброс курса "
                                + swing + "° за " + SAMPLE_TICKS + " тиков");
                    }
                }),
                () -> requireInCell(guard, postPos, "на подходе"));
    }

    /** Страж стоит ровно в клетке поста. */
    private static void requireInCell(BaseGuardianEntity guard, BlockPos postPos, String stage) {
        if (!guard.blockPosition().equals(postPos)) {
            throw new IllegalStateException(stage + ": страж не в клетке поста: "
                    + guard.blockPosition() + " вместо " + postPos
                    + " " + describe(postGoal(guard), guard));
        }
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

    private static String describe(@Nullable GuardPostGoal goal, BaseGuardianEntity guard) {
        return "[goal=" + (goal == null ? "нет" : "пост yaw=" + (int) goal.getOutwardYaw())
                + " onPost=" + (goal != null && goal.isAtPost())
                + " pos=" + guard.blockPosition()
                + " body=" + (int) guard.yBodyRot
                + " target=" + guard.getTarget() + "]";
    }

    private static void platform(GameTestHelper helper) {
        // Пост занимается по расписанию: днём — свой, ночью — вышка. Сценарии
        // расписания перематывают сутки в общем мире, поэтому день выставляем сами.
        helper.getLevel().setDayTime(1000L);
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                helper.getLevel().setBlock(helper.absolutePos(new BlockPos(x, 0, z)),
                        Blocks.STONE.defaultBlockState(), 3);
            }
        }
    }

    private static BaseGuardianEntity spawnGuardian(GameTestHelper helper, BlockPos localPos) {
        BaseGuardianEntity guard = EntityType.byString("survivalpoints:base_guardian")
                .map(type -> (BaseGuardianEntity) type.create(helper.getLevel()))
                .orElseThrow(() -> new IllegalStateException("base_guardian не зарегистрирован"));
        BlockPos pos = helper.absolutePos(localPos);
        guard.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
        helper.getLevel().addFreshEntity(guard);
        return guard;
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
