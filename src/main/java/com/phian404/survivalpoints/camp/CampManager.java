package com.phian404.survivalpoints.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
     * Создать лагерь с центром в указанной точке.
     *
     * @param level серверный уровень
     * @param anchor точка якоря (радио вышка)
     * @param owner владелец (null — общий лагерь)
     * @param name название
     * @param radius радиус лагеря
     * @return созданный лагерь
     */
    public static CampRecord createCamp(ServerLevel level, BlockPos anchor, @Nullable UUID owner,
                                         String name, int radius) {
        CampRecord record = new CampRecord(name, anchor, radius, owner);
        record.setCenter(anchor);
        generateWallPosts(record);
        data(level).addCamp(record);
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
     * Разложить посты по окружности лагеря.
     *
     * <p>Посты смотрят наружу от центра — так страж видит подходы, а не внутренности
     * лагеря. Реальный забор и вышки появятся в волне 2, и посты встанут на них.
     */
    private static void generateWallPosts(CampRecord record) {
        int count = CampTuning.postsForRadius(record.getRadius());
        int ring = Math.max(4, record.getRadius() - CampTuning.CAMP_POST_INSET);
        BlockPos center = record.getCenter();

        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2.0D) * i / count;
            int x = center.getX() + (int) Math.round(Math.cos(angle) * ring);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * ring);
            // Тот же курс, что и у сущности: atan2(dz, dx) в градусах минус 90.
            float yaw = (float) Math.toDegrees(angle) - 90.0F;
            record.addPost(new CampPost(CampPost.Type.WALL, new BlockPos(x, center.getY(), z), yaw));
        }
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
     * Ворота: ночью закрыты, днём открыты. {@code DoorBlock.setOpen} сам
     * пропускает состояние, которое уже установлено — лишних {@code setBlock}
     * не будет. Нижняя половина через {@code updateShape} синхронизирует верхнюю.
     */
    private static void updateGate(ServerLevel level, CampRecord record, boolean night) {
        BlockPos gatePos = record.getGatePos();
        if (gatePos == null) {
            return;
        }
        BlockState state = level.getBlockState(gatePos);
        if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock door) {
            door.setOpen(null, level, state, gatePos, !night && !record.isClosed());
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
