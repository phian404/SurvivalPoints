package com.phian404.survivalpoints.init;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Команды диагностики поведения стража.
 *
 * <ul>
 *   <li>{@code /sp_trace on|off} — событийный лог стража (в лог сервера и в чат);</li>
 *   <li>{@code /sp_trace chat on|off} — дублировать события в чат;</li>
 *   <li>{@code /sp_trace status} — что сейчас включено;</li>
 *   <li>{@code /sp_hang on|off} — детектор «зависания в воздухе».</li>
 * </ul>
 *
 * <p>События пишутся в {@code run/logs/latest.log} с префиксом {@code [SP]} —
 * по логу можно построить хронологию боя, не полагаясь на пересказ.
 */
@Mod.EventBusSubscriber(modid = com.phian404.survivalpoints.SurvivalPointsMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public class GuardianTraceCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sp_trace")
                .requires(source -> source.hasPermission(2))
                .executes(GuardianTraceCommand::status)
                .then(Commands.literal("on")
                        .executes(ctx -> setTrace(ctx, true)))
                .then(Commands.literal("off")
                        .executes(ctx -> setTrace(ctx, false)))
                .then(Commands.literal("status")
                        .executes(GuardianTraceCommand::status))
                .then(Commands.literal("chat")
                        .then(Commands.literal("on")
                                .executes(ctx -> setChat(ctx, true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> setChat(ctx, false)))));

        dispatcher.register(Commands.literal("sp_hang")
                .requires(source -> source.hasPermission(2))
                .executes(GuardianTraceCommand::hangStatus)
                .then(Commands.literal("on")
                        .executes(ctx -> setHang(ctx, true)))
                .then(Commands.literal("off")
                        .executes(ctx -> setHang(ctx, false))));
    }

    private static int setTrace(CommandContext<CommandSourceStack> ctx, boolean value) {
        GuardianTrace.setEnabled(value);
        ctx.getSource().sendSuccess(() -> Component.literal("§7[SP] Трассировка стража: §f"
                + (value ? "ВКЛ" : "выкл")).withStyle(ChatFormatting.GRAY), false);
        return value ? 1 : 0;
    }

    private static int setChat(CommandContext<CommandSourceStack> ctx, boolean value) {
        GuardianTrace.setChatEnabled(value);
        ctx.getSource().sendSuccess(() -> Component.literal("§7[SP] События в чат: §f"
                + (value ? "ВКЛ" : "выкл")).withStyle(ChatFormatting.GRAY), false);
        return value ? 1 : 0;
    }

    private static int setHang(CommandContext<CommandSourceStack> ctx, boolean value) {
        GuardianHangDetector.setEnabled(value);
        ctx.getSource().sendSuccess(() -> Component.literal("§7[SP] Детектор зависания: §f"
                + (value ? "ВКЛ" : "выкл")).withStyle(ChatFormatting.GRAY), false);
        return value ? 1 : 0;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("§7[SP] Трассировка: §f"
                + (GuardianTrace.isEnabled() ? "ВКЛ" : "выкл")
                + " §7| события в чат: §f" + (GuardianTrace.isChatEnabled() ? "ВКЛ" : "выкл")
                + " §7| детектор зависания: §f"
                + (GuardianHangDetector.isEnabled() ? "ВКЛ" : "выкл"))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int hangStatus(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal("§7[SP] Детектор зависания: §f"
                + (GuardianHangDetector.isEnabled() ? "ВКЛ" : "выкл")
                + " §7(порог " + GuardianTuning.HANG_DETECT_TICKS + " тиков)")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}
