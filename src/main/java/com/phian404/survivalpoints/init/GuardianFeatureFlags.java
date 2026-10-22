package com.phian404.survivalpoints.init;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Флаги механик стража.
 *
 * <p>Нужны, чтобы на время проверки одной механики отключить остальные и видеть
 * чистый эффект изменений. Например: чтобы проверить новый ближний бой, отключаем
 * еду ({@code EAT_BREAD}), удержание рубежа ({@code STANDOFF}), сбор союзников
 * ({@code RALLY}) и стрельбу ({@code RANGED}) — иначе в кадре мешаются все механики
 * сразу, и непонятно, что именно изменилось.
 *
 * <p>Все механики включены по умолчанию. Выключенные хранятся в наборе
 * {@link #DISABLED} — проверка идёт через {@link #isEnabled(Flag)}.
 *
 * <p>Команда: {@code /sp_flag <механика> on|off|toggle}, {@code /sp_flag list}.
 */
@Mod.EventBusSubscriber(modid = com.phian404.survivalpoints.SurvivalPointsMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GuardianFeatureFlags {

    /** Механики, которые можно временно отключить. */
    public enum Flag {
        CUSTOM_MOVE("CUSTOM_MOVE", "самописная физика движения (для сравнения с ванильной)"),
        EAT_BREAD("EAT_BREAD", "еда хлебом"),
        STANDOFF("STANDOFF", "удержание рабочего рубежа и отступление"),
        RALLY("RALLY", "сбор союзников (зов о помощи)"),
        LOOK_PLAYER("LOOK_PLAYER", "взгляд на игрока"),
        PANIC("PANIC", "режим паники"),
        RANGED("RANGED", "стрельба из арбалета");

        private final String id;
        private final String description;

        Flag(String id, String description) {
            this.id = id;
            this.description = description;
        }

        public String getId() {
            return this.id;
        }

        public String getDescription() {
            return this.description;
        }

        /** Найти механику по имени (без учёта регистра). */
        public static Flag byId(String raw) {
            for (Flag flag : values()) {
                if (flag.id.equalsIgnoreCase(raw)) {
                    return flag;
                }
            }
            return null;
        }
    }

    /** Выключенные механики. Всё остальное — включено. */
    private static final Set<Flag> DISABLED = EnumSet.noneOf(Flag.class);

    private GuardianFeatureFlags() {
    }

    /** Включена ли механика. */
    public static boolean isEnabled(Flag flag) {
        return !DISABLED.contains(flag);
    }

    /** Включить или выключить механику. */
    public static void setEnabled(Flag flag, boolean enabled) {
        if (enabled) {
            DISABLED.remove(flag);
        } else {
            DISABLED.add(flag);
        }
    }

    /** Переключить механику. */
    public static void toggle(Flag flag) {
        if (DISABLED.contains(flag)) {
            DISABLED.remove(flag);
        } else {
            DISABLED.add(flag);
        }
    }

    /** Список выключенных механик (копия, чтобы наружу не отдавали живой набор). */
    public static Set<Flag> getDisabled() {
        return EnumSet.copyOf(DISABLED);
    }

    // ==================== Команда ====================

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sp_flag")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(GuardianFeatureFlags::list))
                .then(Commands.argument("flag", StringArgumentType.word())
                        .suggests(SUGGESTIONS)
                        .then(Commands.literal("on")
                                .executes(ctx -> set(ctx, true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> set(ctx, false)))
                        .then(Commands.literal("toggle")
                                .executes(GuardianFeatureFlags::toggleCommand))));
    }

    private static final SuggestionProvider<CommandSourceStack> SUGGESTIONS =
            (source, builder) -> {
                for (Flag flag : Flag.values()) {
                    builder.suggest(flag.getId());
                }
                return builder.buildFuture();
            };

    private static int list(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder("§7[SP] Механики стража:");
        for (Flag flag : Flag.values()) {
            boolean on = isEnabled(flag);
            sb.append(" §f").append(flag.getId()).append("=");
            sb.append(on ? "§aвкл" : "§cвыкл");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString())
                .withStyle(ChatFormatting.GRAY), false);
        return Flag.values().length;
    }

    private static int set(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        String raw = StringArgumentType.getString(ctx, "flag");
        Flag flag = Flag.byId(raw);
        if (flag == null) {
            ctx.getSource().sendFailure(Component.literal("§cНеизвестная механика: " + raw));
            return 0;
        }
        setEnabled(flag, enabled);
        ctx.getSource().sendSuccess(() -> Component.literal("§7[SP] " + flag.getId()
                + " §f" + (enabled ? "включена" : "выключена")
                + " §8(" + flag.getDescription() + ")"), false);
        return 1;
    }

    private static int toggleCommand(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "flag");
        Flag flag = Flag.byId(raw);
        if (flag == null) {
            ctx.getSource().sendFailure(Component.literal("§cНеизвестная механика: " + raw));
            return 0;
        }
        toggle(flag);
        boolean on = isEnabled(flag);
        ctx.getSource().sendSuccess(() -> Component.literal("§7[SP] " + flag.getId()
                + " §f" + (on ? "включена" : "выключена")
                + " §8(" + flag.getDescription() + ")"), false);
        return 1;
    }
}
