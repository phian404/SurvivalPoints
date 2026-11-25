package com.phian404.survivalpoints.camp.site;

import com.phian404.survivalpoints.camp.CampTuning;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Сборщик событий фронтира: впервые сгенерированные чанки и их просмотр игроками.
 *
 * <p>Обработчики только запоминают координаты, UUID игрока и время. Они не
 * читают блоки, высоты и биомы и не запускают планировщик: согласно документации
 * Forge 1.20.1 {@link ChunkEvent.Load} может сработать до перевода чанка в
 * {@code ChunkStatus.FULL}. {@link ChunkWatchEvent.Watch}, напротив, fires при
 * отправке данных чанка игроку ({@code ChunkMap.playerLoadedChunk}), то есть
 * когда чанк уже готов.
 *
 * <p>Порядок в Forge 47.4.28: {@code Load(newChunk=true)} фиксирует новизну,
 * затем {@code Watch} связывает чанк с игроком. Director сопоставляет их на
 * END-фазе серверного тика.
 */
@Mod.EventBusSubscriber(modid = "survivalpoints", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CampFrontierEvents {

    /** Ключ: измерение + координаты чанка. */
    public record ChunkKey(ResourceKey<Level> dimension, int cx, int cz) {
    }

    /** Новые чанки: ключ → время загрузки. Удаляются только после Watch или по TTL. */
    private static final Map<ChunkKey, Long> NEW_CHUNKS = new HashMap<>();
    /** Просмотренные игроками чанки: ключ → UUID игрока → время просмотра. */
    private static final Map<ChunkKey, Map<UUID, Long>> WATCHED = new HashMap<>();

    private CampFrontierEvents() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!CampSiteConfig.isAutoEnabled() || !event.isNewChunk()
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ChunkPos pos = event.getChunk().getPos();
        NEW_CHUNKS.put(new ChunkKey(level.dimension(), pos.x, pos.z), level.getGameTime());
    }

    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        if (!CampSiteConfig.isAutoEnabled()) {
            return;
        }
        ServerLevel level = event.getLevel();
        ChunkPos pos = event.getPos();
        WATCHED.computeIfAbsent(new ChunkKey(level.dimension(), pos.x, pos.z),
                key -> new HashMap<>()).put(event.getPlayer().getUUID(), level.getGameTime());
    }

    /**
     * Снять сопоставленные события. Вызывается один раз за проход Director.
     *
     * <p>Новый чанк удаляется из {@code NEW_CHUNKS} только когда для него получен
     * {@code Watch}. Несопоставленные записи обоих карт истекут по TTL
     * {@link CampTuning#SITE_FRONTIER_TTL}, чтобы не копить утечку памяти.
     *
     * @param gameTime текущее время измерения
     * @return чанки, которые были и новыми, и просмотренными игроком
     */
    public static Map<ChunkKey, Map<UUID, Long>> drain(long gameTime) {
        Map<ChunkKey, Map<UUID, Long>> result = new HashMap<>();
        Iterator<Map.Entry<ChunkKey, Map<UUID, Long>>> it = WATCHED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkKey, Map<UUID, Long>> entry = it.next();
            Long loadTime = NEW_CHUNKS.get(entry.getKey());
            if (loadTime != null) {
                // Watch получен — чанк можно снимать с учёта.
                result.put(entry.getKey(), new HashMap<>(entry.getValue()));
                NEW_CHUNKS.remove(entry.getKey());
                it.remove();
            } else if (gameTime - latest(entry.getValue()) > CampTuning.SITE_FRONTIER_TTL) {
                it.remove(); // Watch без нового чанка — просрочен
            }
        }
        NEW_CHUNKS.values().removeIf(time -> gameTime - time > CampTuning.SITE_FRONTIER_TTL);
        return result;
    }

    /** Полностью очистить накопленные события (при выключении авто-режима). */
    public static void clear() {
        NEW_CHUNKS.clear();
        WATCHED.clear();
    }

    private static long latest(Map<UUID, Long> times) {
        long max = Long.MIN_VALUE;
        for (long time : times.values()) {
            max = Math.max(max, time);
        }
        return max;
    }
}
