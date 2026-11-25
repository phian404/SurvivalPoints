package com.phian404.survivalpoints.camp;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Хранилище лагерей измерения.
 *
 * <p>Данные лежат в мире, а не в игроке: лагерь — общность мира, и он должен
 * пережить перезапуск, выгрузку чанков и смену игрока. Стандартный для Forge
 * {@link SavedData} решает ровно эту задачу: файл в мире, dirty-флаг, чтение
 * при загрузке.
 */
public class CampSavedData extends SavedData {

    private final Map<UUID, CampRecord> camps = new HashMap<>();

    /**
     * Получить (или создать) хранилище лагерей измерения.
     *
     * @param level серверный уровень
     * @return хранилище лагерей
     */
    public static CampSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(CampSavedData::load, CampSavedData::new,
                CampTuning.CAMP_DATA_ID);
    }

    private static CampSavedData load(CompoundTag tag) {
        CampSavedData data = new CampSavedData();
        ListTag list = tag.getList("Camps", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CampRecord record = CampRecord.load(list.getCompound(i));
            data.camps.put(record.getId(), record);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (CampRecord record : this.camps.values()) {
            list.add(record.save());
        }
        tag.put("Camps", list);
        return tag;
    }

    // ==================== Доступ к лагерям ====================

    /** Все лагеря измерения. */
    public Collection<CampRecord> getCamps() {
        return this.camps.values();
    }

    /** Лагерь по id. */
    @Nullable
    public CampRecord getCamp(UUID id) {
        return this.camps.get(id);
    }

    /** Найти лагерь по короткому префиксу id (для команд). */
    @Nullable
    public CampRecord getCampByShortId(String shortId) {
        String needle = shortId.toLowerCase(java.util.Locale.ROOT);
        for (CampRecord record : this.camps.values()) {
            if (record.getShortId().toLowerCase(java.util.Locale.ROOT).startsWith(needle)) {
                return record;
            }
        }
        return null;
    }

    /** Добавить лагерь в хранилище. */
    public void addCamp(CampRecord record) {
        this.camps.put(record.getId(), record);
        this.setDirty();
    }

    /** Удалить лагерь из хранилища. */
    @Nullable
    public CampRecord removeCamp(UUID id) {
        CampRecord removed = this.camps.remove(id);
        if (removed != null) {
            CampManager.removeFarmManager(id);
            this.setDirty();
        }
        return removed;
    }

    /** Сколько лагерей в мире. */
    public int getCampCount() {
        return this.camps.size();
    }
}
