package com.phian404.survivalpoints.init;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.phian404.survivalpoints.SurvivalPointsMod;
import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampModuleSlot;
import com.phian404.survivalpoints.camp.CampPoi;
import com.phian404.survivalpoints.camp.CampPost;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampSavedData;
import com.phian404.survivalpoints.camp.CampGenerator;
import com.phian404.survivalpoints.camp.CampStructurePlacer;
import com.phian404.survivalpoints.camp.CampTuning;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Диагностическая команда {@code /sp_camp}.
 *
 * <p>Показывает и создаёт лагеря: список, подробности, создание (общий или личный)
 * и удаление. Нужна на этапе, когда блоков лагеря ещё нет (волна 1) — чтобы
 * проверить сами данные лагеря: посты, штат, ресурсы, режим, сохранение в мире.
 */
@Mod.EventBusSubscriber(modid = SurvivalPointsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class CampDebugCommand {

    /** Модуль для проверки среза «шаблон + маркеры». */
    private static final String TEST_MODULE = "survivalpoints:test_farm";

    /** Фиксированный слот тестового модуля внутри лагеря. */
    private static final String TEST_SLOT_ID = "test_farm";

    /** На сколько блоков перед игроком ставится тестовый модуль. */
    private static final int TEST_SLOT_DISTANCE = 4;

    /** На сколько опустить точку размещения: первый ярус ложится вровень с полом. */
    private static final int TEST_SLOT_Y_OFFSET = 1;

    /** Сколько раз подсвечивать POI. */
    private static final int HIGHLIGHT_STEPS = 10;

    /** Пауза между вспышками подсветки (тики). */
    private static final int HIGHLIGHT_INTERVAL_TICKS = 10;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sp_camp")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(CampDebugCommand::listCamps))
                .then(Commands.literal("info")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> info(ctx, StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("generate")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> generate(ctx, StringArgumentType.getString(ctx, "name"),
                                        CampTuning.CAMP_DEFAULT_RADIUS))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(8, 64))
                                        .executes(ctx -> generate(ctx, StringArgumentType.getString(ctx, "name"),
                                                IntegerArgumentType.getInteger(ctx, "radius"))))))
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "name"),
                                        CampTuning.CAMP_DEFAULT_RADIUS, false))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(8, 64))
                                        .executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "name"),
                                                IntegerArgumentType.getInteger(ctx, "radius"), false)))))
                .then(Commands.literal("personal")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "name"),
                                        CampTuning.CAMP_DEFAULT_RADIUS, true))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(8, 64))
                                        .executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "name"),
                                                IntegerArgumentType.getInteger(ctx, "radius"), true)))))
                .then(Commands.literal("remove")
                        .executes(CampDebugCommand::removeAllCamps)
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("upgrade")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("slot", StringArgumentType.word())
                                        .executes(ctx -> upgrade(ctx,
                                                StringArgumentType.getString(ctx, "id"),
                                                StringArgumentType.getString(ctx, "slot"), false))
                                        .then(Commands.literal("force")
                                                .executes(ctx -> upgrade(ctx,
                                                        StringArgumentType.getString(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "slot"),
                                                        true))))))
                .then(Commands.literal("test_module")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> testModule(ctx, StringArgumentType.getString(ctx, "id"), 0))
                                .then(Commands.argument("rotation", IntegerArgumentType.integer())
                                        .executes(ctx -> testModule(ctx, StringArgumentType.getString(ctx, "id"),
                                                IntegerArgumentType.getInteger(ctx, "rotation"))))))
                .then(Commands.literal("show")
                        .executes(ctx -> show(ctx, null))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> show(ctx, StringArgumentType.getString(ctx, "id"))))));
    }

    // ==================== Команды ====================

    private static int listCamps(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        CampSavedData data = CampManager.data(level);
        if (data.getCampCount() == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7Лагерей в этом измерении нет"), false);
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal("§eЛагеря в измерении ("
                + data.getCampCount() + "/" + CampTuning.CAMP_MAX_PER_WORLD + "):"), false);
        for (CampRecord record : data.getCamps()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§f" + record.getShortId()
                    + " §7" + record.getName()
                    + " §8| §7центр " + record.getCenter().toShortString()
                    + " §8| §7r=" + record.getRadius()
                    + " §8| §7постов " + record.getPosts().size()
                    + " §8| §7стражей " + record.getGuardianCount()
                    + " §8| §7" + (record.isNight() ? "ночь" : "день")
                    + (record.isClosed() ? " §8| §cзакрыт" : "")
                    + (record.isPersonal() ? " §8| §bличный" : "")), false);
        }
        return data.getCampCount();
    }

    private static int info(CommandContext<CommandSourceStack> ctx, String id) {
        ServerLevel level = ctx.getSource().getLevel();
        CampRecord record = CampManager.data(level).getCampByShortId(id);
        if (record == null) {
            ctx.getSource().sendFailure(Component.literal("§cЛагерь «" + id + "» не найден"));
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal("§e" + record.getName() + " §7(" + record.getShortId() + ")"), false);
        ctx.getSource().sendSuccess(() -> Component.literal(" §7центр: §f" + record.getCenter().toShortString()
                + " §7якорь: §f" + record.getAnchor().toShortString()
                + " §7радиус: §f" + record.getRadius()), false);
        ctx.getSource().sendSuccess(() -> Component.literal(" §7режим: §f" + (record.isNight() ? "ночь" : "день")
                + " §7закрыт: §f" + record.isClosed()
                + " §7тревога: §f" + record.getAlertRadius()
                + " §7владелец: §f" + (record.isPersonal() ? record.getOwner() : "мир")), false);
        ctx.getSource().sendSuccess(() -> Component.literal(" §7ресурсы: §fеда " + record.getFood()
                + " §7хлеб " + record.getBread()
                + " §7патроны " + record.getAmmo()
                + " §7оружие " + record.getWeapons()
                + " §7материалы " + record.getMaterials()), false);

        StringBuilder posts = new StringBuilder(" §7посты (").append(record.getPosts().size()).append("):");
        for (CampPost post : record.getPosts()) {
            posts.append(" §f").append(post.getType().name().toLowerCase(Locale.ROOT))
                    .append("@").append(post.getPos().toShortString())
                    .append(post.isFree() ? "§8[свободен]" : "§8[занят]");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(posts.toString()), false);

        // Слоты и POI печатаются из сохранённых данных, а не из мира: это
        // проверка того, что переживает перезапуск.
        ctx.getSource().sendSuccess(() -> Component.literal(" §7слоты модулей ("
                + record.getModuleSlotCount() + "):"), false);
        for (CampModuleSlot slot : record.getModuleSlots().values()) {
            ctx.getSource().sendSuccess(() -> Component.literal(" §f" + slot.getSlotId()
                    + " §8→ §7" + slot.getModuleId()
                    + " §8| §7ярус " + slot.getLevel()
                    + " §8| §7поворот " + slot.getRotation() + "°"
                    + " §8| §7угол " + slot.getOrigin().toShortString()
                    + " §8| §7рамка " + slot.getBounds()
                    + (slot.isModified() ? " §8| §eизменён" : "")), false);
        }

        ctx.getSource().sendSuccess(() -> Component.literal(" §7POI (" + record.getPoiCount() + "):"), false);
        for (CampPoi poi : record.getPois()) {
            ctx.getSource().sendSuccess(() -> Component.literal(" §f" + poi.getType()
                    + " §8| §7слот §f" + poi.getSlotId()
                    + " §8| §7точка §f" + poi.getPos().toShortString()), false);
        }
        return 1;
    }

    private static int generate(CommandContext<CommandSourceStack> ctx, String name, int radius) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 pos = ctx.getSource().getPosition();
        BlockPos anchor = new BlockPos(Mth.floor(pos.x), Mth.floor(pos.y), Mth.floor(pos.z));

        String refusal = CampManager.canCreateCamp(level, null);
        if (refusal != null) {
            ctx.getSource().sendFailure(Component.literal("§c" + refusal));
            return 0;
        }

        String trimmed = name.length() > CampTuning.CAMP_NAME_MAX_LENGTH
                ? name.substring(0, CampTuning.CAMP_NAME_MAX_LENGTH) : name;

        // createCamp атомарен: сначала проверка участка и шаблонов, при
        // провале запись в CampSavedData не появляется и блоки не ставятся.
        CampRecord record = CampManager.createCamp(level, anchor, null, trimmed, radius);
        if (record == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "§cУчасток не подходит (неровный, вода, пересечение с другим лагерем) "
                            + "или шаблоны модулей не найдены"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§aЛагерь «" + record.getName()
                + "» построен §7(" + record.getShortId() + ", якорь " + anchor.toShortString()
                + ", слотов " + record.getModuleSlotCount() + ")"), true);
        return 1;
    }

    private static int create(CommandContext<CommandSourceStack> ctx, String name, int radius, boolean personal) {
        ServerLevel level = ctx.getSource().getLevel();
        Vec3 pos = ctx.getSource().getPosition();
        BlockPos anchor = new BlockPos(Mth.floor(pos.x), Mth.floor(pos.y), Mth.floor(pos.z));

        java.util.UUID owner = null;
        if (personal) {
            if (ctx.getSource().getPlayer() == null) {
                ctx.getSource().sendFailure(Component.literal("§cЛичный лагерь может создать только игрок"));
                return 0;
            }
            owner = ctx.getSource().getPlayer().getUUID();
        }

        String refusal = CampManager.canCreateCamp(level, owner);
        if (refusal != null) {
            ctx.getSource().sendFailure(Component.literal("§c" + refusal));
            return 0;
        }

        String trimmed = name.length() > CampTuning.CAMP_NAME_MAX_LENGTH
                ? name.substring(0, CampTuning.CAMP_NAME_MAX_LENGTH) : name;

        CampRecord record = CampManager.createCamp(level, anchor, owner, trimmed, radius);
        if (record == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "§cУчасток не подходит (неровный, вода, пересечение с другим лагерем) "
                            + "или шаблоны модулей не найдены"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§aЛагерь «" + record.getName() + "» создан §7("
                + record.getShortId() + ", слотов " + record.getModuleSlotCount()
                + ", постов " + record.getPosts().size() + ")"), true);
        return 1;
    }

    /**
     * Апгрейд слота фермы: {@code farm_lvl1} → {@code farm_lvl2}.
     *
     * <p>Изменённый игроком слот защищён: апгрейд отклоняется с понятным
     * сообщением, пока не передан {@code force}.
     *
     * @param ctx    контекст команды
     * @param id     короткий id лагеря
     * @param slotId id слота (ожидается {@code farm})
     * @param force  принудительный апгрейд с потерей изменений игрока
     * @return {@code 1} при успехе
     */
    private static int upgrade(CommandContext<CommandSourceStack> ctx, String id,
                               String slotId, boolean force) {
        ServerLevel level = ctx.getSource().getLevel();
        CampSavedData data = CampManager.data(level);
        CampRecord record = data.getCampByShortId(id);
        if (record == null) {
            ctx.getSource().sendFailure(Component.literal("§cЛагерь «" + id + "» не найден"));
            return 0;
        }

        String refusal = CampGenerator.upgradeFarm(level, data, record, slotId, force);
        if (refusal != null) {
            ctx.getSource().sendFailure(Component.literal("§c" + refusal));
            return 0;
        }

        CampModuleSlot slot = record.getModuleSlot(slotId);
        ctx.getSource().sendSuccess(() -> Component.literal("§aСлот §f" + slotId
                + " §aапгрейден до §f" + (slot == null ? "?" : slot.getModuleId())
                + " §7(поворот " + (slot == null ? "?" : slot.getRotation()) + "°)"), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx, String id) {
        if (id.equalsIgnoreCase("all")) {
            return removeAllCamps(ctx);
        }
        ServerLevel level = ctx.getSource().getLevel();
        CampSavedData data = CampManager.data(level);
        CampRecord record = data.getCampByShortId(id);
        if (record == null) {
            ctx.getSource().sendFailure(Component.literal("§cЛагерь «" + id + "» не найден"));
            return 0;
        }

        // Створка ворот не принадлежит слотам модулей, поэтому снимается
        // отдельно: без этого после удаления лагеря в проёме оставалась бы
        // висящая в воздухе дверь.
        CampStructurePlacer.clearGateLeaf(level, record.getGatePos());

        CampManager.removeCamp(level, record.getId());
        ctx.getSource().sendSuccess(() -> Component.literal("§aЛагерь «" + record.getName() + "» удалён"), true);
        return 1;
    }

    private static int removeAllCamps(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        CampSavedData data = CampManager.data(level);
        if (data.getCampCount() == 0) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7Лагерей в этом измерении нет"), false);
            return 0;
        }

        int count = data.getCampCount();
        for (CampRecord record : new ArrayList<>(data.getCamps())) {
            CampStructurePlacer.clearGateLeaf(level, record.getGatePos());
            CampManager.removeCamp(level, record.getId());
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§aУдалено лагерей: " + count), true);
        return count;
    }

    /**
     * Показать границу лагеря частицами.
     *
     * <p>Пока блоков лагеря нет (они появятся в волне 2), границу можно увидеть
     * только в данных. Частицы рисуют кольцо по радиусу и точки постов — чтобы
     * было видно, где лагерь находится и где стоят посты.
     */
    private static int show(CommandContext<CommandSourceStack> ctx, @Nullable String id) {
        ServerLevel level = ctx.getSource().getLevel();
        CampSavedData data = CampManager.data(level);

        CampRecord record;
        if (id != null) {
            record = data.getCampByShortId(id);
        } else {
            Vec3 pos = ctx.getSource().getPosition();
            record = CampManager.findCampAt(level,
                    new BlockPos(Mth.floor(pos.x), Mth.floor(pos.y), Mth.floor(pos.z)));
            if (record == null) {
                // Ничего не нашли рядом — показываем все лагеря измерения.
                for (CampRecord camp : data.getCamps()) {
                    drawCamp(level, camp);
                }
                ctx.getSource().sendSuccess(() -> Component.literal("§7Показаны все лагеря измерения"), false);
                return data.getCampCount();
            }
        }

        if (record == null) {
            ctx.getSource().sendFailure(Component.literal("§cЛагерь не найден"));
            return 0;
        }

        drawCamp(level, record);
        ctx.getSource().sendSuccess(() -> Component.literal("§aГраница лагеря «" + record.getName()
                + "» показана частицами §7(блоков ещё нет — это волна 2)"), false);
        return 1;
    }

    /**
     * Тестовое размещение модуля: проверка «шаблон + маркеры → слот + POI».
     *
     * <p>Ставит {@value #TEST_MODULE} в фиксированный слот рядом с игроком,
     * печатает найденные POI и подсвечивает их частицами. Смысл — убедиться,
     * что координаты маркеров после поворота совпадают с ожидаемыми: рамка
     * структуры и точки POI должны лежать в одних и тех же местах.
     *
     * @param ctx      контекст команды
     * @param id       короткий id лагеря
     * @param rotation поворот в градусах ({@code 0|90|180|270})
     * @return {@code 1} при успехе
     */
    private static int testModule(CommandContext<CommandSourceStack> ctx, String id, int rotation) {
        ServerLevel level = ctx.getSource().getLevel();
        CampSavedData data = CampManager.data(level);
        CampRecord record = data.getCampByShortId(id);
        if (record == null) {
            ctx.getSource().sendFailure(Component.literal("§cЛагерь «" + id + "» не найден"));
            return 0;
        }
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
            ctx.getSource().sendFailure(Component.literal("§cПоворот должен быть 0, 90, 180 или 270"));
            return 0;
        }

        Vec3 pos = ctx.getSource().getPosition();
        // Точка строго перед игроком по направлению взгляда. Раньше смещение
        // задавалось как (+3, +3) по мировым X и Z — это всегда «на юго-восток»
        // от игрока, поэтому структура появлялась справа-впереди независимо от
        // того, куда он смотрит. Направление берём у игрока; для не-игрового
        // источника (консоль) остаётся SOUTH как детерминированное значение.
        Direction facing = Direction.SOUTH;
        if (ctx.getSource().getEntity() instanceof LivingEntity living) {
            facing = Direction.fromYRot(living.getYRot());
        }
        BlockPos target = new BlockPos(
                Mth.floor(pos.x) + facing.getStepX() * TEST_SLOT_DISTANCE,
                Mth.floor(pos.y) - TEST_SLOT_Y_OFFSET,
                Mth.floor(pos.z) + facing.getStepZ() * TEST_SLOT_DISTANCE);

        CampStructurePlacer.PlacementResult result = CampStructurePlacer.placeModule(
                level, data, record, TEST_SLOT_ID, ResourceLocation.parse(TEST_MODULE),
                0, target, CampStructurePlacer.rotationFromDegrees(rotation));

        if (!result.isSuccess()) {
            ctx.getSource().sendFailure(Component.literal("§c" + result.getMessage()));
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal("§aМодуль §f" + TEST_MODULE
                + " §aразмещён §7(слот " + TEST_SLOT_ID + ", поворот " + rotation
                + "°, центр " + target.toShortString() + ")"), true);

        List<CampPoi> pois = result.getPois();
        ctx.getSource().sendSuccess(() -> Component.literal("§7POI слота (" + pois.size() + "):"), false);
        for (CampPoi poi : pois) {
            ctx.getSource().sendSuccess(() -> Component.literal(" §f" + poi.getType()
                    + " §7→ §f" + poi.getPos().toShortString()), false);
        }

        highlight(level, pois);
        return 1;
    }

    /**
     * Подсветить точки частицами на пару секунд.
     *
     * <p>Повторяется несколько раз через {@code MinecraftServer.execute}: POI —
     * точки в мире, и однократная вспышка не даёт времени посмотреть на
     * совпадение координат с блоками структуры.
     */
    private static void highlight(ServerLevel level, List<CampPoi> pois) {
        if (pois.isEmpty()) {
            return;
        }
        MinecraftServer server = level.getServer();
        int now = server.getTickCount();
        for (int step = 0; step < HIGHLIGHT_STEPS; step++) {
            // TickTask запускается на указанном тике, а не «как можно скорее»:
            // вспышки идут с паузой, и точки видно, а не одну слитую строку.
            server.tell(new TickTask(now + step * HIGHLIGHT_INTERVAL_TICKS, () -> {
                for (CampPoi poi : pois) {
                    BlockPos pos = poi.getPos();
                    level.sendParticles(ParticleTypes.END_ROD,
                            pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D,
                            3, 0.1D, 0.1D, 0.1D, 0.0D);
                }
            }));
        }
    }

    private static void drawCamp(ServerLevel level, CampRecord record) {
        BlockPos center = record.getCenter();
        int radius = record.getRadius();
        double y = center.getY() + 1.0D;

        // Кольцо по периметру.
        int steps = Math.max(32, radius * 4);
        for (int i = 0; i < steps; i++) {
            double angle = (Math.PI * 2.0D) * i / steps;
            double x = center.getX() + 0.5D + Math.cos(angle) * radius;
            double z = center.getZ() + 0.5D + Math.sin(angle) * radius;
            level.sendParticles(ParticleTypes.END_ROD, x, y, z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        // Посты — отдельным цветом.
        for (CampPost post : record.getPosts()) {
            BlockPos pos = post.getPos();
            level.sendParticles(ParticleTypes.SOUL, pos.getX() + 0.5D, pos.getY() + 1.2D,
                    pos.getZ() + 0.5D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    /** Лагерь в точке (для будущих команд и проверок). */
    @Nullable
    public static CampRecord campAt(ServerLevel level, BlockPos pos) {
        return CampManager.findCampAt(level, pos);
    }
}
