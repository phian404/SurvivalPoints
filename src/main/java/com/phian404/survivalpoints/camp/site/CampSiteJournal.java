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
 * <p>Три состояния:
 * <ul>
 *     <li>{@link SiteState#REJECTED} — окончательный отказ, ячейка больше не
 *     рассматривается;</li>
 *     <li>{@link SiteState#CREATED} — созданный лагерь (хранится его UUID);</li>
 *     <li>{@link SiteState#NOT_READY} — временная недоступность. После
 *     {@link CampTuning#SITE_NOT_READY_MAX_ATTEMPTS} неудач вводится пауза
 *     {@link CampTuning#SITE_NOT_READY_RETRY_DELAY}, затем проверка снова
 *     разрешена. Временная недоступность никогда не становится отказом.</li>
 * </ul>
 */
public final class CampSiteJournal extends SavedData {

    public enum SiteState {
        REJECTED,
        CREATED,
        NOT_READY
    }

    private static final String TAG_CELLS = "Cells";
    private static final String TAG_KEY = "Key";
    private static final String TAG_STATE = "State";
    private static final String TAG_REASON = "Reason";
    private static final String TAG_CAMP_ID = "CampId";
    private static final String TAG_ATTEMPTS = "Attempts";
    private static final String TAG_NEXT_ATTEMPT = "NextAttempt";

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
            entry.attempts = entryTag.getInt(TAG_ATTEMPTS);
            entry.nextAttemptTick = entryTag.getLong(TAG_NEXT_ATTEMPT);
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
            entryTag.putInt(TAG_ATTEMPTS, entry.attempts);
            entryTag.putLong(TAG_NEXT_ATTEMPT, entry.nextAttemptTick);
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
        entry.attempts = 0;
        entry.nextAttemptTick = 0L;
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

    /**
     * Отметить временную недоступность.
     *
     * <p>Счётчик попыток растёт. При достижении
     * {@link CampTuning#SITE_NOT_READY_MAX_ATTEMPTS} устанавливается пауза
     * {@link CampTuning#SITE_NOT_READY_RETRY_DELAY} и счётчик сбрасывается —
     * после паузы ячейка проверяется заново, а не отклоняется навсегда.
     *
     * @param cellKey    ключ ячейки
     * @param currentTick текущий тик сервера
     */
    public void markNotReady(long cellKey, long currentTick) {
        Entry entry = this.cells.computeIfAbsent(cellKey, key -> new Entry(SiteState.NOT_READY));
        entry.state = SiteState.NOT_READY;
        entry.reason = null;
        entry.campId = null;
        entry.attempts++;
        if (entry.attempts >= CampTuning.SITE_NOT_READY_MAX_ATTEMPTS) {
            entry.nextAttemptTick = currentTick + CampTuning.SITE_NOT_READY_RETRY_DELAY;
            entry.attempts = 0;
        } else {
            entry.nextAttemptTick = currentTick;
        }
        this.setDirty();
    }

    /** Срок следующей разрешённой проверки ячейки. */
    public long nextAttemptTick(long cellKey) {
        Entry entry = this.cells.get(cellKey);
        return entry != null ? entry.nextAttemptTick : Long.MIN_VALUE;
    }

    /** Можно ли проверять ячейку в данный тик. */
    public boolean isDue(long cellKey, long currentTick) {
        Entry entry = this.cells.get(cellKey);
        if (entry == null || entry.state != SiteState.NOT_READY) {
            return true;
        }
        return currentTick >= entry.nextAttemptTick;
    }

    private static final class Entry {
        private SiteState state;
        private String reason;
        private UUID campId;
        private int attempts;
        private long nextAttemptTick;

        private Entry(SiteState state) {
            this.state = state;
        }
    }
}
