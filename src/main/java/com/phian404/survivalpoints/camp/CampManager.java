package com.phian404.survivalpoints.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Управление лагерями: создание, поиск, тик.
 *
 * <p>Сейчас это только каркас данных: лагерь создаётся командой, вокруг его
 * центра раскладываются виртуальные посты (позже они встанут на забор и вышки),
 * а лагерь тикает раз в секунду — обновляет режим «день/ночь» и зону тревоги.
 */
public final class CampManager {

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
     * @param anchor точка якоря (радио вышка)
     * @param owner владелец (null — общий лагерь)
     * @param name название
     * @param radius радиус лагеря
     * @return созданный лагерь или {@code null} — участок не прошёл проверку
     */
    @Nullable
    public static CampRecord createCamp(ServerLevel level, BlockPos anchor, @Nullable UUID owner,
                                         String name, int radius) {
        String missingModule = CampSitePlanner.checkModules(level);
        if (missingModule != null) {
            return null;
        }
        CampSitePlanner.Outcome outcome = CampSitePlanner.plan(level, anchor, radius);
        if (!outcome.isSuccess()) {
            return null;
        }
        CampSitePlanner.Plan plan = outcome.getPlan();

        String blocked = CampSitePlanner.checkPreparationZone(level, plan, anchor);
        if (blocked != null) {
            return null;
        }

        // Подготовка участка — единственный вызов, до записи лагеря: если
        // выравнивание не удалось, в CampSavedData не остаётся следа.
        CampGenerator.prepareTerrain(level, plan, anchor);

        CampRecord record = new CampRecord(name, anchor, radius, owner);
        record.setCenter(anchor);
        data(level).addCamp(record);
        CampGenerator.generate(level, plan, record);
        CampGenerator.createPostsFromPois(record, plan);
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
        }

        if (changed) {
            data.setDirty();
        }
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
