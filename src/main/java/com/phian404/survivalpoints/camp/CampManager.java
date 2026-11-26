package com.phian404.survivalpoints.camp;

import com.phian404.survivalpoints.camp.site.CampSiteRequest;
import com.phian404.survivalpoints.entity.ai.task.NpcTaskController;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Управление лагерями: создание, поиск, тик.
 *
 * <p>Сейчас это только каркас данных: лагерь создаётся командой, вокруг его
 * центра раскладываются виртуальные посты (позже они встанут на забор и вышки),
 * а лагерь тикает раз в секунду — обновляет режим «день/ночь» и зону тревоги.
 */
public final class CampManager {

    /** Runtime-диспетчеры ферм по id лагеря; не сохраняются в NBT. */
    private static final Map<UUID, CampFarmManager> FARM_MANAGERS = new HashMap<>();

    /** Runtime-контроллеры задач по identityId NPC; не сохраняются в NBT. */
    private static final Map<UUID, NpcTaskController> TASK_CONTROLLERS = new HashMap<>();

    private CampManager() {
    }

    /** Хранилище лагерей измерения. */
    public static CampSavedData data(ServerLevel level) {
        return CampSavedData.get(level);
    }

    // ==================== Создание и поиск ====================

    /**
     * Можно ли создать лагерь.
     *
     * <p>Потолки: не больше {@link CampTuning#CAMP_MAX_PER_WORLD} лагерей в мире
     * и не больше {@link CampTuning#CAMP_MAX_PER_PLAYER} личных лагерей у игрока.
     *
     * @param level серверный уровень
     * @param owner владелец (null — общий лагерь)
     * @return {@code null}, если можно, иначе — причина отказа
     */
    @Nullable
    public static String canCreateCamp(ServerLevel level, @Nullable UUID owner) {
        CampSavedData data = data(level);
        if (data.getCampCount() >= CampTuning.CAMP_MAX_PER_WORLD) {
            return "В мире уже максимум лагерей (" + CampTuning.CAMP_MAX_PER_WORLD + ")";
        }
        if (owner != null) {
            int personal = 0;
            for (CampRecord record : data.getCamps()) {
                if (owner.equals(record.getOwner())) {
                    personal++;
                }
            }
            if (personal >= CampTuning.CAMP_MAX_PER_PLAYER) {
                return "У игрока уже есть личный лагерь";
            }
        }
        return null;
    }

    /**
     * Атомарно создать лагерь: проверки → подготовка площадки → запись → застройка.
     *
     * <p>Порядок важен и держит отказ атомарным:
     * <ol>
     *     <li>наличие NBT-шаблонов модулей;</li>
     *     <li>план участка и проверка рельефа (перепад, жидкости, пересечения);</li>
     *     <li>проверка зоны подготовки — отсутствие {@code BlockEntity};</li>
     *     <li>подготовка площадки (расчистка, подсыпка, срез, край);</li>
     *     <li>запись лагеря в {@link CampSavedData};</li>
     *     <li>застройка периметра, модулей и дорожек.</li>
     * </ol>
     *
     * <p>Все проверки идут до первого изменения мира: при отказе не остаётся
     * ни частично выровненной площадки, ни записи-призрака без стен.
     *
     * <p>Виртуальная расстановка постов по кругу удалена: посты создаются
     * из POI модулей в {@link CampGenerator#createPostsFromPois} — они
     * привязаны к реальной застройке.
     *
     * @param level серверный уровень
     * @param requestPos исходная точка запроса (радио вышка)
     * @param owner владелец (null — общий лагерь)
     * @param name название
     * @param radius радиус лагеря
     * @return созданный лагерь или {@code null} — участок не прошёл проверку
     */
    @Nullable
    public static CampRecord createCamp(ServerLevel level, BlockPos requestPos, @Nullable UUID owner,
                                         String name, int radius) {
        String missingModule = CampSitePlanner.checkModules(level);
        if (missingModule != null) {
            return null;
        }
        CampSitePlanner.Outcome outcome = CampSitePlanner.plan(level, requestPos, radius);
        if (!outcome.isSuccess()) {
            return null;
        }
        CampSitePlanner.Plan plan = outcome.getPlan();

        String blocked = CampSitePlanner.checkPreparationZone(level, plan);
        if (blocked != null) {
            return null;
        }

        // Подготовка участка — единственный вызов, до записи лагеря: если
        // выравнивание не удалось, в CampSavedData не остаётся следа.
        CampGenerator.prepareTerrain(level, plan);

        CampRecord record = new CampRecord(name, plan.getRadioPos(), radius, owner);
        record.setCenter(plan.getCenter());
        data(level).addCamp(record);
        CampGenerator.generate(level, plan, record);
        CampGenerator.createPostsFromPois(record, plan);
        CampPopulation.spawn(level, record, plan);
        data(level).setDirty();
        return record;
    }

    /**
     * Атомарно создать лагерь из уже рассчитанного STRICT-плана.
     *
     * <p>Автоматический путь: планировщик уже оценил все 49 центров и вернул
     * готовый {@link CampSitePlanner.Plan}. Повторного вызова {@code plan()}
     * здесь нет — иначе строгая оценка была бы заменена BEST_EFFORT.
     *
     * <p>Последовательность та же, что в ручном методе: подготовка → запись →
     * застройка → посты. {@code request.source()} определяет, применяются ли
     * исключения для исходного радио при повторной проверке зоны подготовки.
     *
     * @param level  серверный уровень
     * @param request источник запроса (AUTO_CELL)
     * @param owner  владелец (null — общий лагерь)
     * @param name   название
     * @param plan   готовый STRICT-план
     * @return созданный лагерь или {@code null}, если зона подготовки заблокирована
     */
    @Nullable
    public static CampRecord createCamp(ServerLevel level, CampSiteRequest request,
                                        @Nullable UUID owner, String name,
                                        CampSitePlanner.Plan plan) {
        String missingModule = CampSitePlanner.checkModules(level);
        if (missingModule != null) {
            return null;
        }

        boolean manualRadio = request.source() == CampSiteRequest.Source.MANUAL_RADIO;
        String blocked = CampSitePlanner.checkPreparationZone(level, plan, manualRadio);
        if (blocked != null) {
            return null;
        }

        int radius = plan.getRadius();

        // Финальные проверки непосредственно перед изменением мира. Для
        // AUTO_CELL план пришёл из STRICT-оценки, но перепроверка здесь
        // гарантирует, что площадка не пересекает существующие лагеря и не
        // накрывает игроков даже если состояние изменилось после plan().
        if (!manualRadio) {
            BlockPos center = plan.getCenter();
            for (CampRecord other : data(level).getCamps()) {
                int minDistance = radius + other.getRadius() + CampTuning.CAMP_MIN_GAP_BETWEEN;
                long dx = Math.abs((long) other.getCenter().getX() - center.getX());
                long dz = Math.abs((long) other.getCenter().getZ() - center.getZ());
                if (dx < minDistance && dz < minDistance) {
                    return null;
                }
            }
            double minPlayerSqr = (double) CampTuning.SITE_MIN_DISTANCE_TO_PLAYER
                    * CampTuning.SITE_MIN_DISTANCE_TO_PLAYER;
            // Генератор меняет рельеф и за полосой периметра: подсыпка края и
            // подход к воротам занимают CAMP_CLEAR_MARGIN. Поэтому границей
            // зоны воздействия считаем radius + margin, а не только периметр.
            double affected = radius + CampTuning.CAMP_CLEAR_MARGIN;
            for (net.minecraft.server.level.ServerPlayer player : level.players()) {
                // Точные double-разности без усечения координат игрока.
                double px = player.getX() - center.getX();
                double pz = player.getZ() - center.getZ();
                double outsideX = Math.max(0.0D, Math.abs(px) - affected);
                double outsideZ = Math.max(0.0D, Math.abs(pz) - affected);
                if (outsideX * outsideX + outsideZ * outsideZ < minPlayerSqr) {
                    return null;
                }
            }
        }

        CampGenerator.prepareTerrain(level, plan);

        CampRecord record = new CampRecord(name, plan.getRadioPos(), radius, owner);
        record.setCenter(plan.getCenter());
        data(level).addCamp(record);
        CampGenerator.generate(level, plan, record);
        CampGenerator.createPostsFromPois(record, plan);
        CampPopulation.spawn(level, record, plan);
        data(level).setDirty();
        return record;
    }

    /**
     * Лагерь, в который попадает точка (по центру и радиусу).
     *
     * @param level серверный уровень
     * @param pos точка
     * @return лагерь или {@code null}
     */
    @Nullable
    public static CampRecord findCampAt(ServerLevel level, BlockPos pos) {
        CampRecord best = null;
        double bestDistance = Double.MAX_VALUE;
        for (CampRecord record : data(level).getCamps()) {
            double distance = record.distanceToCenter(pos);
            if (distance <= record.getRadius() && distance < bestDistance) {
                best = record;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * Лагерь по id.
     *
     * @param level серверный уровень
     * @param id id лагеря
     * @return лагерь или {@code null}
     */
    @Nullable
    public static CampRecord getCamp(ServerLevel level, UUID id) {
        return data(level).getCamp(id);
    }

    // ==================== Посты ====================

    /**
     * Слот лагеря, содержащий точку (по границам слота).
     *
     * <p>Нужен обработчикам событий: {@code BlockEvent} даёт только мировую
     * координату, а решение «изменил ли игрок слот» принимается по
     * {@link CampModuleSlot#getBounds()}.
     *
     * @param record лагерь
     * @param pos    точка в мире
     * @return слот или {@code null}
     */
    @Nullable
    public static CampModuleSlot slotAt(CampRecord record, BlockPos pos) {
        for (CampModuleSlot slot : record.getModuleSlots().values()) {
            if (slot.getBounds().contains(pos)) {
                return slot;
            }
        }
        return null;
    }

    // ==================== Тик ====================

    /**
     * Тик сервера: раз в {@link CampTuning#CAMP_TICK_INTERVAL} тиков обновляет лагеря.
     *
     * @param server сервер
     */
    public static void onServerTick(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (server.getTickCount() % CampTuning.CAMP_TICK_INTERVAL != 0) {
                continue;
            }
            tickCamps(level);
        }
    }

    /**
     * Обновить состояние лагерей уровня.
     *
     * @param level серверный уровень
     */
    public static void tickCamps(ServerLevel level) {
        boolean night = level.isNight();
        int radius = night ? CampTuning.CAMP_ALERT_RADIUS_NIGHT : CampTuning.CAMP_ALERT_RADIUS_DAY;

        CampSavedData data = data(level);
        boolean changed = false;
        for (CampRecord record : data.getCamps()) {
            if (record.isNight() != night) {
                record.setNight(night);
                changed = true;
            }
            if (record.getAlertRadius() != radius) {
                record.setAlertRadius(radius);
                changed = true;
            }
            record.setStaffCheckTimer(record.getStaffCheckTimer() + CampTuning.CAMP_TICK_INTERVAL);
            record.setRadioCallTimer(record.getRadioCallTimer() + CampTuning.CAMP_TICK_INTERVAL);
            updateGate(level, record, night);
            farmManager(record).refresh(level, record, level.getGameTime());
        }

        if (changed) {
            data.setDirty();
        }
    }
/** Диспетчер фермы лагеря (создаётся лениво, живёт только в runtime). */
public static CampFarmManager farmManager(CampRecord record) {
    return FARM_MANAGERS.computeIfAbsent(record.getId(), id -> new CampFarmManager());
}

/** Диспетчер без создания нового runtime-состояния — только для диагностики. */
@Nullable
public static CampFarmManager peekFarmManager(CampRecord record) {
    return FARM_MANAGERS.get(record.getId());
}

/** Убрать runtime-диспетчер удалённого лагеря. */
public static void removeFarmManager(UUID campId) {
    CampFarmManager manager = FARM_MANAGERS.remove(campId);
    if (manager != null) {
        manager.clear();
    }
}

/**
 * Контроллер задач NPC по постоянному identityId.
 *
 * <p>Хранится вне сущности: при смене роли создаётся новая сущность, и
 * устаревшая активная задача не переносится. Контроллер живёт до явной
 * очистки (смерть, потеря лагеря, остановка сервера).
 */
public static NpcTaskController taskController(UUID identityId) {
    return TASK_CONTROLLERS.computeIfAbsent(identityId, id -> new NpcTaskController());
}

/** Контроллер без создания — для чтения диагностикой. */
@Nullable
public static NpcTaskController peekTaskController(UUID identityId) {
    return TASK_CONTROLLERS.get(identityId);
}

/** Освободить и удалить контроллер задач NPC. */
public static void removeTaskController(UUID identityId) {
    NpcTaskController controller = TASK_CONTROLLERS.remove(identityId);
    if (controller != null) {
        controller.cancel();
    }
}

/** Очистить runtime-диспетчеры при остановке сервера. */
public static void clearRuntimeState() {
    for (CampFarmManager manager : FARM_MANAGERS.values()) {
        manager.clear();
    }
    FARM_MANAGERS.clear();
    for (NpcTaskController controller : TASK_CONTROLLERS.values()) {
        controller.cancel();
    }
    TASK_CONTROLLERS.clear();
}


    /**
     * Ворота: ночью закрыты, днём открыты.
     *
     * <p>Створка одна, в центре проёма. {@code setOpen} переключает обе
     * половины {@code DoorBlock} (их синхронизирует
     * {@code DoorBlock.updateShape}) и сам пропускает состояние, которое уже
     * установлено, — лишних {@code setBlock} не будет.
     */
    private static void updateGate(ServerLevel level, CampRecord record, boolean night) {
        boolean open = !night && !record.isClosed();
        setGateOpen(level, record.getGatePos(), open);
    }

    /**
     * Переключить одну створку ворот, если в этой точке действительно дверь.
     *
     * @param level серверный уровень
     * @param pos   нижняя половина створки ({@code null} — створки нет)
     * @param open  открыть или закрыть
     */
    private static void setGateOpen(ServerLevel level, @Nullable BlockPos pos, boolean open) {
        if (pos == null) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock door) {
            door.setOpen(null, level, state, pos, open);
        }
    }

    /**
     * Центр лагеря как точка в мире (для отрисовки маркера и расчётов).
     *
     * @param record лагерь
     * @return точка центра
     */
    public static Vec3 getCenterVec(CampRecord record) {
        BlockPos center = record.getCenter();
        return new Vec3(center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D);
    }
}
