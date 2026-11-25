package com.phian404.survivalpoints.camp.site;

import com.phian404.survivalpoints.camp.CampManager;
import com.phian404.survivalpoints.camp.CampRecord;
import com.phian404.survivalpoints.camp.CampSitePlanner;
import com.phian404.survivalpoints.camp.CampTuning;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Автоматическое создание лагерей на фронтире впервые исследуемой территории.
 *
 * <p>Кандидаты приходят из {@link CampFrontierEvents}: новые чанки, которые
 * уже просмотрены игроком. Периодический обход всех ячеек вокруг игроков
 * убран. Обработка идёт на END-фазе серверного тика; чанки принудительно не
 * загружаются, все чтения — только по готовым чанкам.
 */
public final class CampSiteDirector {

    private static final Logger LOGGER = LoggerFactory.getLogger(CampSiteDirector.class);

    /** Кандидат фронтира: чанк, игрок и время события. */
    private record FrontierCandidate(CampFrontierEvents.ChunkKey chunk, UUID playerId,
                                     long eventTime) {
        private String dedupKey() {
            return chunk.cx() + ":" + chunk.cz() + ":" + playerId;
        }
    }

    /** Позиция входа игрока: UUID → {x, z, gameTime}. */
    private static final Map<UUID, double[]> JOIN_POS = new HashMap<>();
    /** Очередь кандидатов с дедупликацией по чанку+игроку. */
    private static final Deque<FrontierCandidate> QUEUE = new ArrayDeque<>();
    private static final Set<String> QUEUED = new HashSet<>();

    private CampSiteDirector() {
    }

    /** Тик сервера: вызывается на фазе END из ModEntityEvents. */
    public static void onServerTick(MinecraftServer server) {
        if (!CampSiteConfig.isAutoEnabled()) {
            return;
        }

        // Снимаем события фронтира только в момент скана: между сканами они
        // накапливаются в CampFrontierEvents и не теряются. Один drain на все
        // измерения — enqueueFrontier фильтрует по измерению уровня.
        ServerLevel first = server.getLevel(Level.OVERWORLD);
        if (first == null || first.getGameTime() % CampTuning.SITE_SCAN_INTERVAL != 0) {
            return;
        }
        Map<CampFrontierEvents.ChunkKey, Map<UUID, Long>> frontier = CampFrontierEvents.drain();

        for (ServerLevel level : server.getAllLevels()) {
            if (!CampSiteCandidates.isAllowedDimension(level.dimension())) {
                continue;
            }
            long gameTime = level.getGameTime();
            trackJoins(level, gameTime);
            enqueueFrontier(level, frontier, gameTime);
            scanLevel(level, gameTime);
        }
    }

    /** Запомнить позицию входа и время для новых игроков. */
    private static void trackJoins(ServerLevel level, long gameTime) {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            online.add(player.getUUID());
            JOIN_POS.computeIfAbsent(player.getUUID(), id ->
                    new double[]{player.getX(), player.getZ(), (double) gameTime});
        }
        JOIN_POS.keySet().removeIf(id -> !online.contains(id));
    }

    /** Добавить новые просмотренные чанки в ограниченную очередь. */
    private static void enqueueFrontier(ServerLevel level,
                                        Map<CampFrontierEvents.ChunkKey, Map<UUID, Long>> frontier,
                                        long gameTime) {
        ResourceKey<Level> dimension = level.dimension();
        for (Map.Entry<CampFrontierEvents.ChunkKey, Map<UUID, Long>> entry : frontier.entrySet()) {
            CampFrontierEvents.ChunkKey key = entry.getKey();
            if (!key.dimension().equals(dimension)) {
                continue;
            }
            for (Map.Entry<UUID, Long> watcher : entry.getValue().entrySet()) {
                UUID playerId = watcher.getKey();
                if (!isEligible(level, playerId, gameTime)) {
                    continue;
                }
                String dedupKey = key.cx() + ":" + key.cz() + ":" + playerId;
                if (!QUEUED.add(dedupKey)) {
                    continue;
                }
                if (QUEUE.size() >= CampTuning.SITE_FRONTIER_QUEUE_LIMIT) {
                    QUEUED.remove(expireOne().dedupKey());
                }
                QUEUE.addLast(new FrontierCandidate(key, playerId, watcher.getValue()));
            }
        }
    }

    /** Найти онлайн-игрока по UUID среди игроков измерения. */
    private static ServerPlayer findPlayer(ServerLevel level, UUID playerId) {
        for (ServerPlayer player : level.players()) {
            if (player.getUUID().equals(playerId)) {
                return player;
            }
        }
        return null;
    }

    /** Прошло ли 10 секунд после входа и переместился ли игрок на 32+ блока. */
    private static boolean isEligible(ServerLevel level, UUID playerId, long gameTime) {
        double[] join = JOIN_POS.get(playerId);
        if (join == null || gameTime - (long) join[2] < CampTuning.SITE_JOIN_GRACE_TICKS) {
            return false;
        }
        ServerPlayer player = findPlayer(level, playerId);
        if (player == null) {
            return false;
        }
        double dx = player.getX() - join[0];
        double dz = player.getZ() - join[1];
        return dx * dx + dz * dz >= (double) CampTuning.SITE_MIN_PLAYER_TRAVEL
                * CampTuning.SITE_MIN_PLAYER_TRAVEL;
    }

    /** Удалить самый старый элемент очереди (для ограничения размера). */
    private static FrontierCandidate expireOne() {
        FrontierCandidate oldest = QUEUE.removeFirst();
        QUEUED.remove(oldest.dedupKey());
        return oldest;
    }

    private static void scanLevel(ServerLevel level, long gameTime) {
        long worldSeed = level.getSeed();
        ResourceKey<Level> dimension = level.dimension();
        TagKey<Biome> allowedBiome = CampSiteCandidates.allowedBiomeTag();
        CampSiteJournal journal = CampSiteJournal.get(level);

        // Удаляем просроченные кандидаты (TTL 600 тиков).
        QUEUE.removeIf(candidate -> {
            boolean expired = gameTime - candidate.eventTime() > CampTuning.SITE_FRONTIER_TTL;
            if (expired) {
                QUEUED.remove(candidate.dedupKey());
            }
            return expired;
        });

        // Приоритет: предпочтительный диапазон 96–140, затем наиболее удалённый.
        List<FrontierCandidate> ordered = new ArrayList<>(QUEUE);
        ordered.sort(Comparator.comparingInt((FrontierCandidate c) -> distanceBand(level, c))
                .thenComparingLong(c -> CampSiteCandidates.candidateOrder(worldSeed,
                        c.chunk().cx(), c.chunk().cz(), dimension)));

        int reviewed = 0;
        int created = 0;
        for (FrontierCandidate candidate : ordered) {
            if (reviewed >= CampTuning.SITE_MAX_CELLS_PER_SCAN
                    || created >= CampTuning.SITE_MAX_CREATED_PER_SCAN) {
                break;
            }
            reviewed++;

            CampFrontierEvents.ChunkKey chunk = candidate.chunk();
            int cellX = Math.floorDiv(chunk.cx() * 16 + 8, CampTuning.SITE_CELL_SIZE);
            int cellZ = Math.floorDiv(chunk.cz() * 16 + 8, CampTuning.SITE_CELL_SIZE);
            long cellKey = CampSiteCandidates.cellKey(cellX, cellZ);

            CampSiteJournal.SiteState state = journal.state(cellKey);
            if (state == CampSiteJournal.SiteState.REJECTED
                    || state == CampSiteJournal.SiteState.CREATED) {
                continue;
            }
            if (CampManager.canCreateCamp(level, null) != null) {
                continue;
            }

            BlockPos centerXZ = CampSiteCandidates.cellCenterXZ(cellKey);
            int radius = CampTuning.CAMP_DEFAULT_RADIUS;

            // Гейт готовности ПЕРВЫЙ: до чтения высоты или биома.
            if (!CampSitePlanner.isSearchAreaReady(level, centerXZ, radius)) {
                continue; // временная недоступность — не отложенное задание
            }

            BlockPos anchor = CampSiteCandidates.candidateAnchor(level, cellKey);
            if (!CampSiteCandidates.isAllowedBiome(level, anchor, allowedBiome)) {
                journal.markRejected(cellKey, "Биом точки кандидата не разрешён");
                LOGGER.info("[SP][SITE] rejected cell {},{}: candidate biome", cellX, cellZ);
                continue;
            }

            CampSiteRequest request = new CampSiteRequest(
                    CampSiteRequest.Source.AUTO_CELL, anchor, radius);
            CampSitePlanner.Outcome outcome = CampSitePlanner.plan(
                    level, request, CampSitePlanner.SearchMode.STRICT);

            if (outcome.status() == CampSitePlanner.Outcome.Status.NOT_READY) {
                continue; // временная недоступность — не отложенное задание
            }
            if (outcome.status() == CampSitePlanner.Outcome.Status.REJECTED) {
                journal.markRejected(cellKey, outcome.getRefusal());
                LOGGER.info("[SP][SITE] rejected cell {},{}: {}", cellX, cellZ, outcome.getRefusal());
                continue;
            }

            CampSitePlanner.Plan plan = outcome.getPlan();

            if (!CampSiteCandidates.isAllowedBiome(level, plan.getCenter(), allowedBiome)) {
                journal.markRejected(cellKey, "Биом центра лагеря не разрешён");
                LOGGER.info("[SP][SITE] rejected cell {},{}: center biome", cellX, cellZ);
                continue;
            }

            // Фактическая изменяемая зона должна пересекать новый чанк.
            int affected = radius + CampTuning.CAMP_CLEAR_MARGIN;
            if (!intersectsChunk(plan.getCenter(), affected, chunk)) {
                continue;
            }

            // Безопасный минимум 64 до всей зоны — для каждого игрока измерения.
            if (tooCloseToPlayer(level, plan.getCenter(), affected)) {
                continue;
            }

            CampRecord record = CampManager.createCamp(level, request, null,
                    "Лагерь выживших", plan);
            if (record == null) {
                continue; // временная проблема — не отложенное задание
            }

            journal.markCreated(cellKey, record.getId());
            created++;
            QUEUE.remove(candidate);
            QUEUED.remove(candidate.dedupKey());
            LOGGER.info("[SP][SITE] created camp {} at cell {},{}",
                    record.getShortId(), cellX, cellZ);
        }
    }

    /** Группа расстояния: 0 — предпочтительный диапазон, 1 — остальные. */
    private static int distanceBand(ServerLevel level, FrontierCandidate candidate) {
        ServerPlayer player = findPlayer(level, candidate.playerId());
        if (player == null) {
            return 1;
        }
        int affected = CampTuning.CAMP_DEFAULT_RADIUS + CampTuning.CAMP_CLEAR_MARGIN;
        double distance = distanceToAffected(player, chunkCenter(candidate.chunk()), affected);
        return distance >= CampTuning.SITE_PREFERRED_DISTANCE_MIN
                && distance <= CampTuning.SITE_PREFERRED_DISTANCE_MAX ? 0 : 1;
    }

    private static BlockPos chunkCenter(CampFrontierEvents.ChunkKey chunk) {
        return new BlockPos(chunk.cx() * 16 + 8, 0, chunk.cz() * 16 + 8);
    }

    /** Расстояние от игрока до ближайшей точки зоны radius+margin. */
    private static double distanceToAffected(ServerPlayer player, BlockPos center, int affected) {
        double px = player.getX() - center.getX();
        double pz = player.getZ() - center.getZ();
        double outsideX = Math.max(0.0D, Math.abs(px) - affected);
        double outsideZ = Math.max(0.0D, Math.abs(pz) - affected);
        return Math.sqrt(outsideX * outsideX + outsideZ * outsideZ);
    }

    /** Пересекает ли зона center±affected новый чанк. */
    private static boolean intersectsChunk(BlockPos center, int affected,
                                           CampFrontierEvents.ChunkKey chunk) {
        int minX = center.getX() - affected;
        int maxX = center.getX() + affected;
        int minZ = center.getZ() - affected;
        int maxZ = center.getZ() + affected;
        ChunkPos pos = new ChunkPos(chunk.cx(), chunk.cz());
        return maxX >= pos.getMinBlockX() && minX <= pos.getMaxBlockX()
                && maxZ >= pos.getMinBlockZ() && minZ <= pos.getMaxBlockZ();
    }

    /** Безопасный минимум 64 до зоны для каждого игрока измерения. */
    private static boolean tooCloseToPlayer(ServerLevel level, BlockPos center, int affected) {
        double minSqr = (double) CampTuning.SITE_MIN_DISTANCE_TO_PLAYER
                * CampTuning.SITE_MIN_DISTANCE_TO_PLAYER;
        for (ServerPlayer player : level.players()) {
            double px = player.getX() - center.getX();
            double pz = player.getZ() - center.getZ();
            double outsideX = Math.max(0.0D, Math.abs(px) - affected);
            double outsideZ = Math.max(0.0D, Math.abs(pz) - affected);
            if (outsideX * outsideX + outsideZ * outsideZ < minSqr) {
                return true;
            }
        }
        return false;
    }
}
