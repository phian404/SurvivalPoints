package com.phian404.survivalpoints.camp.site;

import com.phian404.survivalpoints.camp.CampTuning;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Журнал автоматических кандидатов лагерей.
 *
 * <p>Хранилище, как и {@code CampSavedData}, живёт в мире и создаётся на
 * измерение через {@code getDataStorage().computeIfAbsent(...)}. Поэтому ключ
 * ячейки содержит только обратимо упакованные координаты X/Z — измерение
 * задано самим файлом журнала.
 *
 * <p>Журнал сохраняет только терминальные результаты:
 * <ul>
 *     <li>{@link SiteState#REJECTED} — окончательный отказ, ячейка больше не
 *     рассматривается;</li>
 *     <li>{@link SiteState#CREATED} — созданный лагерь (хранится его UUID).</li>
 * </ul>
 *
 * <p>Временная недоступность не записывается в журнал: кандидат остаётся
 * в очереди фронтира и может быть проверен на следующем проходе.
 */
public final class CampSiteJournal extends SavedData {

    public enum SiteState {
        REJECTED,
        CREATED,
        // Legacy value: retained so older journal entries remain readable.
        NOT_READY
    }

    private static final String TAG_CELLS = "Cells";
    private static final String TAG_KEY = "Key";
    private static final String TAG_STATE = "State";
    private static final String TAG_REASON = "Reason";
    private static final String TAG_CAMP_ID = "CampId";

    private final Map<Long, Entry> cells = new HashMap<>();

    private CampSiteJournal() {
    }

    /** Получить (или создать) журнал ячеек измерения. */
    public static CampSiteJournal get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(CampSiteJournal::load, CampSiteJournal::new,
                CampTuning.SITE_JOURNAL_DATA_ID);
    }

    private static CampSiteJournal load(CompoundTag tag) {
        CampSiteJournal journal = new CampSiteJournal();
        ListTag list = tag.getList(TAG_CELLS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            long key = entryTag.getLong(TAG_KEY);
            SiteState state;
            try {
                state = SiteState.valueOf(entryTag.getString(TAG_STATE));
            } catch (IllegalArgumentException ignored) {
                continue; // повреждённая запись — пропускаем, не ломаем мир
            }
            Entry entry = new Entry(state);
            entry.reason = entryTag.getString(TAG_REASON);
            if (entryTag.hasUUID(TAG_CAMP_ID)) {
                entry.campId = entryTag.getUUID(TAG_CAMP_ID);
            }
            journal.cells.put(key, entry);
        }
        return journal;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, Entry> cell : this.cells.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong(TAG_KEY, cell.getKey());
            Entry entry = cell.getValue();
            entryTag.putString(TAG_STATE, entry.state.name());
            if (entry.reason != null) {
                entryTag.putString(TAG_REASON, entry.reason);
            }
            if (entry.campId != null) {
                entryTag.putUUID(TAG_CAMP_ID, entry.campId);
            }
            list.add(entryTag);
        }
        tag.put(TAG_CELLS, list);
        return tag;
    }

    // ==================== Доступ к состояниям ====================

    @Nullable
    public SiteState state(long cellKey) {
        Entry entry = this.cells.get(cellKey);
        return entry != null ? entry.state : null;
    }

    /** Окончательно отклонить ячейку с причиной отказа. */
    public void markRejected(long cellKey, String reason) {
        Entry entry = this.cells.computeIfAbsent(cellKey, key -> new Entry(SiteState.REJECTED));
        entry.state = SiteState.REJECTED;
        entry.reason = reason;
        entry.campId = null;
        this.setDirty();
    }

    /** Отклонить ячейку без уточнения причины. */
    public void markRejected(long cellKey) {
        markRejected(cellKey, "");
    }

    /** Отметить ячейку как обработанную созданным лагерем. */
    public void markCreated(long cellKey, UUID campId) {
        Entry entry = this.cells.computeIfAbsent(cellKey, key -> new Entry(SiteState.CREATED));
        entry.state = SiteState.CREATED;
        entry.reason = null;
        entry.campId = campId;
        entry.attempts = 0;
        entry.nextAttemptTick = 0L;
        this.setDirty();
    }

    private static final class Entry {
        private SiteState state;
        private String reason;
        private UUID campId;

        private Entry(SiteState state) {
            this.state = state;
        }
    }
}
