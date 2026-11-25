package com.phian404.survivalpoints.camp.site;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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

    /** Новые чанки, ещё не связанные с игроком: ключ → время загрузки. */
    private static final Map<ChunkKey, Long> NEW_CHUNKS = new HashMap<>();
    /** Просмотренные игроками чанки: ключ → UUID игрока → время просмотра. */
    private static final Map<ChunkKey, Map<UUID, Long>> WATCHED = new HashMap<>();

    private CampFrontierEvents() {
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ChunkPos pos = event.getChunk().getPos();
        NEW_CHUNKS.put(new ChunkKey(level.dimension(), pos.x, pos.z), level.getGameTime());
    }

    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        ServerLevel level = event.getLevel();
        ChunkPos pos = event.getPos();
        WATCHED.computeIfAbsent(new ChunkKey(level.dimension(), pos.x, pos.z),
                key -> new HashMap<>()).put(event.getPlayer().getUUID(), level.getGameTime());
    }

    /**
     * Снять и очистить накопленные события. Вызывается один раз за проход
     * Director на END-фазе тика. Возвращает только чанки, которые были и новыми,
     * и просмотренными игроком.
     */
    public static Map<ChunkKey, Map<UUID, Long>> drain() {
        Map<ChunkKey, Map<UUID, Long>> result = new HashMap<>();
        for (Map.Entry<ChunkKey, Map<UUID, Long>> entry : WATCHED.entrySet()) {
            if (NEW_CHUNKS.containsKey(entry.getKey())) {
                result.put(entry.getKey(), new HashMap<>(entry.getValue()));
            }
        }
        NEW_CHUNKS.clear();
        WATCHED.clear();
        return result;
    }
}
