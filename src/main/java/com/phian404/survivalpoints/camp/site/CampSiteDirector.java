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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Автоматический поиск площадок и создание процедурных лагерей.
 *
 * <p>Работает только при {@link CampSiteConfig#isAutoEnabled()} и только в
 * измерениях с игроками. Кандидаты — центры фиксированной сетки; сид влияет
 * лишь на порядок обхода. Все чтения идут по уже готовым чанкам, чанки
 * принудительно не загружаются.
 */
public final class CampSiteDirector {

    private static final Logger LOGGER = LoggerFactory.getLogger(CampSiteDirector.class);

    /** Порядок последней рассмотренной ячейки (стабильный курсор обхода). */
    private static long lastProcessedOrder = Long.MIN_VALUE;

    private CampSiteDirector() {
    }

    /** Тик сервера: вызывается на фазе END из ModEntityEvents. */
    public static void onServerTick(MinecraftServer server) {
        if (!CampSiteConfig.isAutoEnabled()) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (!CampSiteCandidates.isAllowedDimension(level.dimension())) {
                continue;
            }
            if (level.players().isEmpty()) {
                continue;
            }
            long gameTime = level.getGameTime();
            if (gameTime % CampTuning.SITE_SCAN_INTERVAL != 0) {
                continue;
            }
            scanLevel(level, gameTime);
        }
    }

    private static void scanLevel(ServerLevel level, long gameTime) {
        long worldSeed = level.getSeed();
        ResourceKey<Level> dimension = level.dimension();
        TagKey<Biome> allowedBiome = CampSiteCandidates.allowedBiomeTag();

        // Дедупликация ячеек при нескольких игроках.
        Set<Long> cellKeys = new HashSet<>();
        int radiusCells = (int) Math.ceil(CampTuning.SITE_PLAYER_SCAN_RADIUS
                / (double) CampTuning.SITE_CELL_SIZE);
        for (ServerPlayer player : level.players()) {
            int px = CampSiteCandidates.cellOf((int) player.getX());
            int pz = CampSiteCandidates.cellOf((int) player.getZ());
            for (int dx = -radiusCells; dx <= radiusCells; dx++) {
                for (int dz = -radiusCells; dz <= radiusCells; dz++) {
                    cellKeys.add(CampSiteCandidates.cellKey(px + dx, pz + dz));
                }
            }
        }

        // Стабильный детерминированный порядок, не зависящий от порядка игроков.
        List<Long> ordered = new ArrayList<>(cellKeys);
        ordered.sort(Comparator.comparingLong(key -> {
            int[] coords = CampSiteCandidates.cellCoords(key);
            return CampSiteCandidates.candidateOrder(worldSeed, coords[0], coords[1], dimension);
        }));

        CampSiteJournal journal = CampSiteJournal.get(level);
        int reviewed = 0;
        int created = 0;

        // Старт с ячейки после последней обработанной в прошлом проходе.
        // Курсор привязан к порядку, а не к индексу списка — перемещение
        // игроков и изменение набора ячеек не сбивают обход.
        int startIndex = 0;
        for (int i = 0; i < ordered.size(); i++) {
            Long key = ordered.get(i);
            int[] c = CampSiteCandidates.cellCoords(key);
            if (CampSiteCandidates.candidateOrder(worldSeed, c[0], c[1], dimension)
                    > lastProcessedOrder) {
                startIndex = i;
                break;
            }
            startIndex = 0; // все orders <= курсора — начинаем с начала
        }

        for (int i = 0; i < ordered.size(); i++) {
            if (reviewed >= CampTuning.SITE_MAX_CELLS_PER_SCAN) {
                break;
            }
            if (created >= CampTuning.SITE_MAX_CREATED_PER_SCAN) {
                break;
            }
            Long cellKey = ordered.get((startIndex + i) % ordered.size());
            reviewed++;

            int[] coords = CampSiteCandidates.cellCoords(cellKey);
            long order = CampSiteCandidates.candidateOrder(worldSeed, coords[0], coords[1], dimension);
            lastProcessedOrder = order;

            CampSiteJournal.SiteState state = journal.state(cellKey);
            if (state == CampSiteJournal.SiteState.REJECTED
                    || state == CampSiteJournal.SiteState.CREATED) {
                continue;
            }
            if (!journal.isDue(cellKey, gameTime)) {
                continue;
            }

            // Временный лимит — не окончательный отказ.
            if (CampManager.canCreateCamp(level, null) != null) {
                continue;
            }

            // Чистая арифметика, без чтения мира.
            BlockPos centerXZ = CampSiteCandidates.cellCenterXZ(cellKey);
            int radius = CampTuning.CAMP_DEFAULT_RADIUS;

            // Гейт готовности ПЕРВЫЙ: до любого чтения высоты или биома.
            if (!CampSitePlanner.isSearchAreaReady(level, centerXZ, radius)) {
                journal.markNotReady(cellKey, gameTime);
                LOGGER.info("[SP][SITE] deferred cell {},{}: area not ready",
                        coords[0], coords[1]);
                continue;
            }

            BlockPos anchor = CampSiteCandidates.candidateAnchor(level, cellKey);
            if (!CampSiteCandidates.isAllowedBiome(level, anchor, allowedBiome)) {
                journal.markRejected(cellKey, "Биом точки кандидата не разрешён");
                LOGGER.info("[SP][SITE] rejected cell {},{}: candidate biome",
                        coords[0], coords[1]);
                continue;
            }

            CampSiteRequest request = new CampSiteRequest(
                    CampSiteRequest.Source.AUTO_CELL, anchor, radius);
            CampSitePlanner.Outcome outcome = CampSitePlanner.plan(
                    level, request, CampSitePlanner.SearchMode.STRICT);

            if (outcome.status() == CampSitePlanner.Outcome.Status.NOT_READY) {
                journal.markNotReady(cellKey, gameTime);
                LOGGER.info("[SP][SITE] deferred cell {},{}: {}",
                        coords[0], coords[1], outcome.getRefusal());
                continue;
            }
            if (outcome.status() == CampSitePlanner.Outcome.Status.REJECTED) {
                journal.markRejected(cellKey, outcome.getRefusal());
                LOGGER.info("[SP][SITE] rejected cell {},{}: {}",
                        coords[0], coords[1], outcome.getRefusal());
                continue;
            }

            CampSitePlanner.Plan plan = outcome.getPlan();

            // Биом фактического центра (не точки запроса).
            if (!CampSiteCandidates.isAllowedBiome(level, plan.getCenter(), allowedBiome)) {
                journal.markRejected(cellKey, "Биом центра лагеря не разрешён");
                LOGGER.info("[SP][SITE] rejected cell {},{}: center biome",
                        coords[0], coords[1]);
                continue;
            }

            // Дистанция до игроков — пропускаем ВСЮ ячейку, без REJECTED.
            if (tooCloseToPlayer(level, plan.getCenter())) {
                continue;
            }

            // AABB-конфликт уже корректно проверен планировщиком для всех
            // 49 центров (findCampConflict с фактическими радиусами и зазором),
            // поэтому здесь проверка не дублируется.

            CampRecord record = CampManager.createCamp(level, request, null,
                    "Лагерь выживших", plan);
            if (record == null) {
                // Неуспех создания — временная проблема, не окончательный отказ.
                journal.markNotReady(cellKey, gameTime);
                LOGGER.info("[SP][SITE] deferred cell {},{}: creation failed",
                        coords[0], coords[1]);
                continue;
            }

            journal.markCreated(cellKey, record.getId());
            created++;
            LOGGER.info("[SP][SITE] created camp {} at cell {},{}",
                    record.getShortId(), coords[0], coords[1]);
        }
    }

    /** Слишком ли центр лагеря близко к любому игроку измерения. */
    private static boolean tooCloseToPlayer(ServerLevel level, BlockPos center) {
        long minSqr = (long) CampTuning.SITE_MIN_DISTANCE_TO_PLAYER
                * CampTuning.SITE_MIN_DISTANCE_TO_PLAYER;
        for (ServerPlayer player : level.players()) {
            long dx = (long) player.getX() - center.getX();
            long dz = (long) player.getZ() - center.getZ();
            if (dx * dx + dz * dz < minSqr) {
                return true;
            }
        }
        return false;
    }
}
