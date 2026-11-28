package com.phian404.survivalpoints.gametest;

import com.phian404.survivalpoints.camp.CampPost;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampSavedData;
import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.entity.ai.GuardPostGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
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

    /** Предельный шаг стража за тик при подходе к посту (блоки). */
    private static final double MAX_STEP_PER_TICK = 0.6D;

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

    /**
     * Страж идёт на пост шагом, а не рывками.
     *
     * <p>Раньше пост передавал в {@code followPath} скорость {@code 1.0} —
     * это около блока за тик: страж перелетал клетку поста, не мог встать и
     * со стороны двигался рывками. Замеряем фактический шаг за тик.
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 400)
    public static void guard_walks_to_post_without_jerks(GameTestHelper helper) {
        platform(helper);
        ServerLevel level = helper.getLevel();

        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos postPos = helper.absolutePos(new BlockPos(4, 1, 2));
        BaseGuardianEntity guard = spawnGuardian(helper, new BlockPos(0, 1, 2));

        CampRecord camp = new CampRecord("gametest_guard_step", center, 8, null);
        CampPost post = new CampPost(CampPost.Type.WALL, postPos, 45.0F);
        camp.addPost(post);
        camp.assignGuardian(guard.getIdentityId(), post);
        CampSavedData.get(level).addCamp(camp);
        guard.setCampId(camp.getId());
        guard.setCampPostId(post.getId());

        double[] last = {guard.getX(), guard.getZ()};
        double[] maxStep = {0.0D};
        pollUntil(helper, 300, "страж дошёл до поста ровным шагом", () -> {
            if (maxStep[0] > MAX_STEP_PER_TICK) {
                throw new IllegalStateException("рывок: " + maxStep[0] + " блока за тик, "
                        + describe(postGoal(guard), guard));
            }
            helper.succeed();
        }, () -> {
            double step = Math.hypot(guard.getX() - last[0], guard.getZ() - last[1]);
            if (step > maxStep[0]) {
                maxStep[0] = step;
            }
            last[0] = guard.getX();
            last[1] = guard.getZ();
            requireInCell(guard, postPos, "на подходе");
        });
    }

    /**
     * Враг снимает стража с поста.
     *
     * <p>Пока страж стоит на посту, он держит корпус «наружу» и не двигается.
     * Цель должна вытеснить гол поста, иначе страж так и стоит, глядя в стену,
     * пока его бьют.
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 700)
    public static void combat_takes_guard_off_post(GameTestHelper helper) {
        platform(helper);
        ServerLevel level = helper.getLevel();

        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos postPos = helper.absolutePos(new BlockPos(4, 1, 2));
        BaseGuardianEntity guard = spawnGuardian(helper, new BlockPos(0, 1, 2));

        CampRecord camp = new CampRecord("gametest_guard_combat", center, 8, null);
        CampPost post = new CampPost(CampPost.Type.WALL, postPos, 45.0F);
        camp.addPost(post);
        camp.assignGuardian(guard.getIdentityId(), post);
        CampSavedData.get(level).addCamp(camp);
        guard.setCampId(camp.getId());
        guard.setCampPostId(post.getId());

        pollUntil(helper, 300, "страж встал на пост", () -> {
            Mob zombie = (Mob) EntityType.ZOMBIE.create(level);
            if (zombie == null) {
                throw new IllegalStateException("зомби не создан");
            }
            BlockPos zombiePos = helper.absolutePos(new BlockPos(4, 1, 4));
            zombie.moveTo(zombiePos.getX() + 0.5D, zombiePos.getY(), zombiePos.getZ() + 0.5D);
            level.addFreshEntity(zombie);

            pollUntil(helper, 200, "страж сошёл с поста на врага", helper::succeed, () -> {
                if (guard.getTarget() != zombie) {
                    throw new IllegalStateException("страж не взял цель: "
                            + describe(postGoal(guard), guard));
                }
                GuardPostGoal goal = postGoal(guard);
                if (goal != null && goal.isAtPost()) {
                    throw new IllegalStateException("страж остался на посту при враге: "
                            + describe(goal, guard));
                }
            });
        }, () -> requireInCell(guard, postPos, "на посту"));
    }

    /** Страж стоит ровно в клетке поста. */
    private static void requireInCell(BaseGuardianEntity guard, BlockPos postPos, String stage) {
        if (!guard.blockPosition().equals(postPos)) {
            throw new IllegalStateException(stage + ": страж не в клетке поста: "
                    + guard.blockPosition() + " вместо " + postPos
                    + " " + describe(postGoal(guard), guard));
        }
    }

    /**
     * Страж поднимается на пост по ступени в один блок.
     *
     * <p>Настил вышки выше двора, поэтому пост на вышке достижим только если
     * pathfinder стража берёт подъём на блок. Тест ставит одну ступень и ждёт,
     * что страж стоит в клетке над ней.
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 600)
    public static void guard_climbs_one_block_step_to_post(GameTestHelper helper) {
        platform(helper);
        ServerLevel level = helper.getLevel();
        level.setBlock(helper.absolutePos(new BlockPos(1, 1, 2)),
                Blocks.STONE.defaultBlockState(), 3);

        BlockPos center = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos postPos = helper.absolutePos(new BlockPos(1, 2, 2));
        BaseGuardianEntity guard = spawnGuardian(helper, new BlockPos(0, 1, 2));

        CampRecord camp = new CampRecord("gametest_guard_step", center, 8, null);
        CampPost post = new CampPost(CampPost.Type.WALL, postPos, 270.0F);
        camp.addPost(post);
        camp.assignGuardian(guard.getIdentityId(), post);
        CampSavedData.get(level).addCamp(camp);
        guard.setCampId(camp.getId());
        guard.setCampPostId(post.getId());

        pollUntil(helper, 300, "страж поднялся на ступень к посту", helper::succeed,
                () -> requireInCell(guard, postPos, "на ступени"));
    }

    /**
     * Страж проходит от входа в вышку до клетки, отмеченной структурным блоком.
     *
     * <p>Шаблон {@code survivalpoints:watchtower} ставится в мир как есть, маркер
     * снимается, а пост назначается ровно в его координаты. Тест проверяет не
     * геометрию, а реальный pathfinder моба: подъём по stairs и настилу.
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 1200)
    public static void guard_reaches_watchtower_post(GameTestHelper helper) {
        platform(helper);
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(0, 1, 0));
        StructureTemplate template = level.getStructureManager()
                .get(new ResourceLocation("survivalpoints", "watchtower"))
                .orElseThrow(() -> new IllegalStateException("шаблон вышки не читается"));
        template.placeInWorld(level, origin, origin,
                new StructurePlaceSettings().setIgnoreEntities(true), level.random, 3);

        BlockPos postPos = origin.offset(2, 4, 3);
        level.setBlock(postPos, Blocks.AIR.defaultBlockState(), 3);

        BaseGuardianEntity guard = spawnGuardian(helper, new BlockPos(2, 1, 0));

        CampRecord camp = new CampRecord("gametest_guard_tower", origin.offset(2, 0, 2), 8, null);
        CampPost post = new CampPost(CampPost.Type.WALL, postPos, 0.0F);
        camp.addPost(post);
        camp.assignGuardian(guard.getIdentityId(), post);
        CampSavedData.get(level).addCamp(camp);
        guard.setCampId(camp.getId());
        guard.setCampPostId(post.getId());

        pollUntil(helper, 600, "страж поднялся в клетку поста на вышке", helper::succeed,
                () -> requireInCell(guard, postPos, "на вышке"));
    }

    /**
     * Шаблон вышки читается игрой и не повреждён правкой настила.
     */
    @GameTest(template = "empty3x3x3", timeoutTicks = 100)
    public static void watchtower_template_is_readable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var template = level.getStructureManager()
                .get(new ResourceLocation("survivalpoints", "watchtower")).orElse(null);
        if (template == null) {
            throw new IllegalStateException("шаблон survivalpoints:watchtower не читается");
        }
        var size = template.getSize();
        if (size.getX() != 5 || size.getY() != 8 || size.getZ() != 5) {
            throw new IllegalStateException("неожиданный размер шаблона вышки: "
                    + size.getX() + "x" + size.getY() + "x" + size.getZ());
        }
        helper.succeed();
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
