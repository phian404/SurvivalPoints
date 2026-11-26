package com.phian404.survivalpoints.init;

import com.mojang.logging.LogUtils;
import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Событийная трассировка поведения стража.
 *
 * <p>Нужна, чтобы разбирать поведение по фактам, а не по впечатлениям. Каждое
 * событие пишется в два места:
 * <ul>
 *   <li>в лог сервера ({@code run/logs/latest.log}) с префиксом {@code [SP]} —
 *       по нему потом можно грепать и строить хронологию боя;</li>
 *   <li>в чат игрокам рядом со стражем — чтобы видеть событие в моменте.</li>
 * </ul>
 *
 * <p>Включается командой {@code /sp_trace on}. Пока выключено, трассировка не
 * стоит ничего: проверка флага — одно сравнение булева поля.
 *
 * <p>Сообщения, которые важны всегда (например, детектор «зависания в воздухе»),
 * пишутся через {@link #always(String)} — они попадают в лог независимо от флага.
 */
public final class GuardianTrace {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Радиус, в котором игроки видят события в чате. */
    private static final double CHAT_RADIUS = 64.0D;

    /** Включён ли сбор событий. */
    private static boolean enabled = false;

    /** Дублировать ли события в чат (в лог они пишутся всегда). */
    private static boolean chatEnabled = true;

    private GuardianTrace() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isChatEnabled() {
        return chatEnabled;
    }

    public static void setChatEnabled(boolean value) {
        chatEnabled = value;
    }

    /**
     * Записать событие стража.
     *
     * <p>Ничего не делает, если трассировка выключена.
     *
     * @param guardian страж, с которым связано событие
     * @param message  текст события
     */
    public static void event(BaseGuardianEntity guardian, String message) {
        if (!enabled) {
            return;
        }
        String line = "[SP] g" + guardian.getId() + " | " + message;
        LOGGER.info(line);
        if (!chatEnabled) {
            return;
        }
        if (!(guardian.level() instanceof ServerLevel level)) {
            return;
        }
        Component component = Component.literal("§8[SP] §7" + message);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() == level && player.distanceToSqr(guardian) <= CHAT_RADIUS * CHAT_RADIUS) {
                player.sendSystemMessage(component);
            }
        }
    }

    /**
     * Записать важное сообщение в лог — независимо от флага трассировки.
     *
     * <p>Используется детекторами (зависание в воздухе, ошибки), которые должны
     * сработать всегда.
     *
     * @param message текст сообщения
     */
    public static void always(String message) {
        LOGGER.info("[SP] " + message);
    }

    /**
     * Записать событие без привязки к сущности (ферма, диспетчер задач).
     *
     * <p>Как и {@link #event(BaseGuardianEntity, String)}: лог-файл — всегда,
     * чат — при включённом {@code /sp_trace chat}. Частоту (дедупликацию)
     * решает вызывающая сторона — у неё свои счётчики.
     *
     * @param level   серверный уровень для рассылки в чат
     * @param message текст события
     */
    public static void always(ServerLevel level, String message) {
        always(message);
        if (!chatEnabled) {
            return;
        }
        Component component = Component.literal("§8[SP] §7" + message);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.level() == level) {
                player.sendSystemMessage(component);
            }
        }
    }
}
