package com.phian404.survivalpoints.init;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.phian404.survivalpoints.SurvivalPointsMod;
import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampPost;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampSavedData;
import com.phian404.survivalpoints.camp.CampTuning;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
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
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> remove(ctx, StringArgumentType.getString(ctx, "id")))))
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
        ctx.getSource().sendSuccess(() -> Component.literal("§aЛагерь «" + record.getName() + "» создан §7("
                + record.getShortId() + ", постов " + record.getPosts().size() + ")"), true);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx, String id) {
        ServerLevel level = ctx.getSource().getLevel();
        CampSavedData data = CampManager.data(level);
        CampRecord record = data.getCampByShortId(id);
        if (record == null) {
            ctx.getSource().sendFailure(Component.literal("§cЛагерь «" + id + "» не найден"));
            return 0;
        }

        data.removeCamp(record.getId());
        ctx.getSource().sendSuccess(() -> Component.literal("§aЛагерь «" + record.getName() + "» удалён"), true);
        return 1;
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
