package com.phian404.survivalpoints.init;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.phian404.survivalpoints.SurvivalPointsMod;
import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Диагностика нагрузки сервера.
 *
 * <p>Показывает, во сколько обходится мод: сколько миллисекунд занимают наши
 * обработчики, сколько стоит построение навигационных путей и сколько путей
 * перестраивается в секунду. Команда:
 *
 * <ul>
 *   <li>{@code /sp_perf} — разовая сводка в чат;</li>
 *   <li>{@code /sp_perf on} — при каждом ударе по стражу кидать сводку в чат
 *       игрокам рядом (нужно, чтобы поймать момент лага);</li>
 *   <li>{@code /sp_perf off} — выключить;</li>
 *   <li>{@code /sp_perf reset} — сбросить счётчики.</li>
 * </ul>
 *
 * <p>Счётчики обновляются раз в секунду, чтобы их чтение само не давало нагрузки.
 */
@Mod.EventBusSubscriber(modid = SurvivalPointsMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PerfDiagnostics {

    /** Автосообщения при ударе по стражу. */
    private static boolean autoReport = false;

    // --- Окно измерения (1 секунда) ---
    private static long windowStartNanos = 0L;
    private static int windowTicks = 0;
    private static double lastTps = 20.0D;
    private static double lastTickMs = 0.0D;
    private static long ourNanos = 0L;
    private static long pathNanos = 0L;
    private static int pathRebuilds = 0;
    private static int entityCount = 0;
    private static int chunkCount = 0;

    // --- Накопители (обнуляются раз в секунду) ---
    private static long tickStartNanos = 0L;
    private static long tickTotalNanos = 0L;
    private static long ourWindowNanos = 0L;
    private static long pathWindowNanos = 0L;
    private static int pathWindowRebuilds = 0;
    private static int hurtEvents = 0;

    // ==================== Замеры ====================

    /** Начало серверного тика. */
    public static void onTickStart() {
        PerfDiagnostics.tickStartNanos = System.nanoTime();
    }

    /** Конец серверного тика: считаем длительность тика и закрываем окно. */
    public static void onTickEnd() {
        long now = System.nanoTime();
        PerfDiagnostics.tickTotalNanos = now - PerfDiagnostics.tickStartNanos;
        PerfDiagnostics.windowTicks++;

        if (PerfDiagnostics.windowStartNanos == 0L) {
            PerfDiagnostics.windowStartNanos = now;
        }

        long windowNanos = now - PerfDiagnostics.windowStartNanos;
        if (windowNanos >= 1_000_000_000L) {
            double seconds = windowNanos / 1_000_000_000.0D;
            PerfDiagnostics.lastTps = PerfDiagnostics.windowTicks / seconds;
            PerfDiagnostics.lastTickMs = PerfDiagnostics.tickTotalNanos / 1_000_000.0D;
            PerfDiagnostics.ourNanos = PerfDiagnostics.ourWindowNanos;
            PerfDiagnostics.pathNanos = PerfDiagnostics.pathWindowNanos;
            PerfDiagnostics.pathRebuilds = PerfDiagnostics.pathWindowRebuilds;
            PerfDiagnostics.entityCount = countEntities();
            PerfDiagnostics.chunkCount = countChunks();

            PerfDiagnostics.windowStartNanos = now;
            PerfDiagnostics.windowTicks = 0;
            PerfDiagnostics.ourWindowNanos = 0L;
            PerfDiagnostics.pathWindowNanos = 0L;
            PerfDiagnostics.pathWindowRebuilds = 0;
        }
    }

    /** Наш код в серверном тике (лагеря, обработчики сущностей). */
    public static void addOurNanos(long nanos) {
        PerfDiagnostics.ourWindowNanos += nanos;
    }

    /** Построение навигационного пути (самая дорогая операция нашего мода). */
    public static void addPathRebuild(long nanos) {
        PerfDiagnostics.pathWindowNanos += nanos;
        PerfDiagnostics.pathWindowRebuilds++;
    }

    private static int countEntities() {
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return 0;
        }
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity ignored : level.getAllEntities()) {
                count++;
            }
        }
        return count;
    }

    private static int countChunks() {
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return 0;
        }
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            count += level.getChunkSource().getLoadedChunksCount();
        }
        return count;
    }

    // ==================== Событие урона ====================

    /**
     * Страж получил урон.
     *
     * <p>Если включён режим {@code /sp_perf on} — кидаем сводку по нагрузке
     * игрокам рядом. Так ловятся моменты, когда сервер «заикает».
     */
    public static void onGuardianHurt(ServerLevel level, BaseGuardianEntity guardian,
                                      DamageSource source, float amount) {
        PerfDiagnostics.hurtEvents++;
        if (!PerfDiagnostics.autoReport) {
            return;
        }

        Component report = Component.literal(buildReport(level, guardian, source, amount));
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() == level && player.distanceToSqr(guardian) < 64.0D * 64.0D) {
                player.sendSystemMessage(report);
            }
        }
    }

    private static String buildReport(ServerLevel level, BaseGuardianEntity guardian,
                                       DamageSource source, float amount) {
        return "§6[SP] §7урон стражу §f" + String.format("%.1f", amount)
                + " §7(" + source.getMsgId() + ")"
                + " §8| §7тик §f" + String.format("%.1f", PerfDiagnostics.lastTickMs) + " мс"
                + " §8| §7TPS §f" + String.format("%.1f", PerfDiagnostics.lastTps)
                + " §8| §7наш код §f" + String.format("%.2f", PerfDiagnostics.ourNanos / 1_000_000.0D) + " мс"
                + " §8(путь §f" + String.format("%.2f", PerfDiagnostics.pathNanos / 1_000_000.0D)
                + " §7мс, §f" + PerfDiagnostics.pathRebuilds + "§7 путей/с)"
                + " §8| §7сущностей §f" + PerfDiagnostics.entityCount
                + " §8| §7чанков §f" + PerfDiagnostics.chunkCount
                + " §8| §7стражей §f" + ModEntityEvents.getGuardians(level).size();
    }

    // ==================== Команда ====================

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sp_perf")
                .requires(source -> source.hasPermission(2))
                .executes(CampPerfCommands::report)
                .then(Commands.literal("on")
                        .executes(ctx -> CampPerfCommands.setAuto(ctx, true)))
                .then(Commands.literal("off")
                        .executes(ctx -> CampPerfCommands.setAuto(ctx, false)))
                .then(Commands.literal("reset")
                        .executes(ctx -> CampPerfCommands.reset(ctx))));
    }

    /** Служебные команды (внутри пакета, чтобы не плодить публичный API). */
    static final class CampPerfCommands {

        private CampPerfCommands() {
        }

        static int report(CommandContext<CommandSourceStack> ctx) {
            ServerLevel level = ctx.getSource().getLevel();
            ctx.getSource().sendSuccess(() -> Component.literal(buildReport(level, null, null, 0.0F)), false);
            return 1;
        }

        static int setAuto(CommandContext<CommandSourceStack> ctx, boolean value) {
            PerfDiagnostics.autoReport = value;
            ctx.getSource().sendSuccess(() -> Component.literal(value
                    ? "§aАвтосообщения при ударе по стражу: ВКЛ"
                    : "§7Автосообщения при ударе по стражу: выкл"), false);
            return 1;
        }

        static int reset(CommandContext<CommandSourceStack> ctx) {
            PerfDiagnostics.pathWindowRebuilds = 0;
            PerfDiagnostics.pathWindowNanos = 0L;
            PerfDiagnostics.ourWindowNanos = 0L;
            PerfDiagnostics.hurtEvents = 0;
            ctx.getSource().sendSuccess(() -> Component.literal("§aСчётчики сброшены"), false);
            return 1;
        }
    }
}
