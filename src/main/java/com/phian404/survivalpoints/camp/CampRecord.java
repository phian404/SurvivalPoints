package com.phian404.survivalpoints.camp;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Данные одного лагеря.
 *
 * <p>Лагерь — общность мира, а не собственность игрока: у мировых лагерей
 * {@code owner == null}. Единственный «якорь» лагеря — радио вышка
 * ({@link #getAnchor()}): пока она стоит, лагерь существует.
 *
 * <p>Класс хранит только данные: блоки лагеря живут в мире, а связи «страж ↔ пост»
 * и «лагерь ↔ лагерь» — здесь.
 */
public class CampRecord {

    private final UUID id;
    private String name;
    private BlockPos center;
    private int radius;

    /** Владелец лагеря; {@code null} — лагерь общий для мира. */
    @Nullable
    private UUID owner;

    /** Якорь лагеря — радио вышка. Пока стоит, лагерь существует. */
    private BlockPos anchor;

    private final List<CampPost> posts = new ArrayList<>();

    /** Привязка стража к посту: id стража → id поста. */
    private final Map<UUID, UUID> guardianPosts = new HashMap<>();

    /** Отношения с другими лагерями: id лагеря → отношение. */
    private final Map<UUID, CampRelation> relations = new HashMap<>();

    // --- Ресурсы лагеря ---
    private int food = 0;
    private int bread = 0;
    private int ammo = 0;
    private int weapons = 0;
    private int materials = 0;

    // --- Режим ---
    private boolean night = false;
    /** Ручной флаг «лагерь закрыт» (глава или игрок закрыл лагерь днём). */
    private boolean closed = false;
    private int alertRadius = CampTuning.CAMP_ALERT_RADIUS_DAY;

    // --- Таймеры ---
    private int staffCheckTimer = 0;
    private int radioCallTimer = 0;

    public CampRecord(String name, BlockPos anchor, int radius, @Nullable UUID owner) {
        this(UUID.randomUUID(), name, anchor, radius, owner);
    }

    private CampRecord(UUID id, String name, BlockPos anchor, int radius, @Nullable UUID owner) {
        this.id = id;
        this.name = name;
        this.anchor = anchor;
        this.center = anchor;
        this.radius = radius;
        this.owner = owner;
    }

    // ==================== Идентификация ====================

    public UUID getId() {
        return this.id;
    }

    /** Короткое имя лагеря для команд и чата (первые 8 символов id). */
    public String getShortId() {
        return this.id.toString().substring(0, 8);
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Nullable
    public UUID getOwner() {
        return this.owner;
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
    }

    /** Личный ли это лагерь (принадлежит игроку). */
    public boolean isPersonal() {
        return this.owner != null;
    }

    // ==================== Геометрия ====================

    public BlockPos getCenter() {
        return this.center;
    }

    public void setCenter(BlockPos center) {
        this.center = center;
    }

    public int getRadius() {
        return this.radius;
    }

    public void setRadius(int radius) {
        this.radius = radius;
    }

    public BlockPos getAnchor() {
        return this.anchor;
    }

    public void setAnchor(BlockPos anchor) {
        this.anchor = anchor;
    }

    /** Содержит ли лагерь точку (с запасом на радиус тревоги). */
    public boolean contains(BlockPos pos) {
        double dx = pos.getX() - this.center.getX();
        double dz = pos.getZ() - this.center.getZ();
        double r = this.radius + this.alertRadius;
        return dx * dx + dz * dz <= r * r;
    }

    /** Расстояние от центра лагеря по горизонтали. */
    public double distanceToCenter(BlockPos pos) {
        double dx = pos.getX() - this.center.getX();
        double dz = pos.getZ() - this.center.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ==================== Посты ====================

    public List<CampPost> getPosts() {
        return this.posts;
    }

    public void addPost(CampPost post) {
        this.posts.add(post);
    }

    public void removePost(CampPost post) {
        this.posts.remove(post);
        this.guardianPosts.values().removeIf(id -> id.equals(post.getId()));
    }

    /** Найти пост по id. */
    @Nullable
    public CampPost getPost(UUID postId) {
        for (CampPost post : this.posts) {
            if (post.getId().equals(postId)) {
                return post;
            }
        }
        return null;
    }

    /** Свободный пост (если есть). */
    @Nullable
    public CampPost getFreePost() {
        for (CampPost post : this.posts) {
            if (post.isFree()) {
                return post;
            }
        }
        return null;
    }

    // ==================== Штат ====================

    public Map<UUID, UUID> getGuardianPosts() {
        return this.guardianPosts;
    }

    /** Назначить стража на пост. */
    public void assignGuardian(UUID guardianId, CampPost post) {
        post.assign(guardianId);
        this.guardianPosts.put(guardianId, post.getId());
    }

    /** Освободить пост стража. */
    public void releaseGuardian(UUID guardianId) {
        UUID postId = this.guardianPosts.remove(guardianId);
        if (postId != null) {
            CampPost post = this.getPost(postId);
            if (post != null) {
                post.assign(null);
            }
        }
    }

    /** Пост, на котором стоит страж (или {@code null}). */
    @Nullable
    public CampPost getPostOf(UUID guardianId) {
        UUID postId = this.guardianPosts.get(guardianId);
        return postId == null ? null : this.getPost(postId);
    }

    /** Сколько стражей привязано к лагерю. */
    public int getGuardianCount() {
        return this.guardianPosts.size();
    }

    // ==================== Ресурсы ====================

    public int getFood() {
        return this.food;
    }

    public int getBread() {
        return this.bread;
    }

    public int getAmmo() {
        return this.ammo;
    }

    public int getWeapons() {
        return this.weapons;
    }

    public int getMaterials() {
        return this.materials;
    }

    public void addFood(int amount) {
        this.food = Math.max(0, this.food + amount);
    }

    public void addBread(int amount) {
        this.bread = Math.max(0, this.bread + amount);
    }

    public void addAmmo(int amount) {
        this.ammo = Math.max(0, this.ammo + amount);
    }

    public void addWeapons(int amount) {
        this.weapons = Math.max(0, this.weapons + amount);
    }

    public void addMaterials(int amount) {
        this.materials = Math.max(0, this.materials + amount);
    }

    /** Забрать хлеб (для кормления стражей). */
    public boolean takeBread(int amount) {
        if (this.bread < amount) {
            return false;
        }
        this.bread -= amount;
        return true;
    }

    // ==================== Режим ====================

    public boolean isNight() {
        return this.night;
    }

    public void setNight(boolean night) {
        this.night = night;
    }

    public boolean isClosed() {
        return this.closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public int getAlertRadius() {
        return this.alertRadius;
    }

    public void setAlertRadius(int alertRadius) {
        this.alertRadius = alertRadius;
    }

    // ==================== Таймеры ====================

    public int getStaffCheckTimer() {
        return this.staffCheckTimer;
    }

    public void setStaffCheckTimer(int timer) {
        this.staffCheckTimer = timer;
    }

    public int getRadioCallTimer() {
        return this.radioCallTimer;
    }

    public void setRadioCallTimer(int timer) {
        this.radioCallTimer = timer;
    }

    // ==================== Отношения ====================

    public Map<UUID, CampRelation> getRelations() {
        return this.relations;
    }

    public CampRelation getRelation(UUID campId) {
        return this.relations.getOrDefault(campId, CampRelation.NEUTRAL);
    }

    public void setRelation(UUID campId, CampRelation relation) {
        this.relations.put(campId, relation);
    }

    // ==================== Сохранение ====================

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", this.id);
        tag.putString("Name", this.name);
        tag.putLong("Center", this.center.asLong());
        tag.putInt("Radius", this.radius);
        tag.putLong("Anchor", this.anchor.asLong());
        if (this.owner != null) {
            tag.putUUID("Owner", this.owner);
        }

        ListTag postList = new ListTag();
        for (CampPost post : this.posts) {
            postList.add(post.save());
        }
        tag.put("Posts", postList);

        ListTag guardianList = new ListTag();
        for (Map.Entry<UUID, UUID> entry : this.guardianPosts.entrySet()) {
            CompoundTag pair = new CompoundTag();
            pair.putUUID("Guard", entry.getKey());
            pair.putUUID("Post", entry.getValue());
            guardianList.add(pair);
        }
        tag.put("Guardians", guardianList);

        ListTag relationList = new ListTag();
        for (Map.Entry<UUID, CampRelation> entry : this.relations.entrySet()) {
            CompoundTag pair = new CompoundTag();
            pair.putUUID("Camp", entry.getKey());
            pair.putString("Relation", entry.getValue().name());
            relationList.add(pair);
        }
        tag.put("Relations", relationList);

        tag.putInt("Food", this.food);
        tag.putInt("Bread", this.bread);
        tag.putInt("Ammo", this.ammo);
        tag.putInt("Weapons", this.weapons);
        tag.putInt("Materials", this.materials);
        tag.putBoolean("Night", this.night);
        tag.putBoolean("Closed", this.closed);
        tag.putInt("AlertRadius", this.alertRadius);
        tag.putInt("StaffCheck", this.staffCheckTimer);
        tag.putInt("RadioCall", this.radioCallTimer);
        return tag;
    }

    public static CampRecord load(CompoundTag tag) {
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        String name = tag.getString("Name");
        BlockPos center = BlockPos.of(tag.getLong("Center"));
        int radius = tag.getInt("Radius");
        UUID owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        BlockPos anchor = BlockPos.of(tag.getLong("Anchor"));

        CampRecord record = new CampRecord(id, name, anchor, radius, owner);
        record.center = center;

        ListTag postList = tag.getList("Posts", Tag.TAG_COMPOUND);
        for (int i = 0; i < postList.size(); i++) {
            record.posts.add(CampPost.load(postList.getCompound(i)));
        }

        ListTag guardianList = tag.getList("Guardians", Tag.TAG_COMPOUND);
        for (int i = 0; i < guardianList.size(); i++) {
            CompoundTag pair = guardianList.getCompound(i);
            record.guardianPosts.put(pair.getUUID("Guard"), pair.getUUID("Post"));
        }

        ListTag relationList = tag.getList("Relations", Tag.TAG_COMPOUND);
        for (int i = 0; i < relationList.size(); i++) {
            CompoundTag pair = relationList.getCompound(i);
            record.relations.put(pair.getUUID("Camp"),
                    CampRelation.byName(pair.getString("Relation")));
        }

        record.food = tag.getInt("Food");
        record.bread = tag.getInt("Bread");
        record.ammo = tag.getInt("Ammo");
        record.weapons = tag.getInt("Weapons");
        record.materials = tag.getInt("Materials");
        record.night = tag.getBoolean("Night");
        record.closed = tag.getBoolean("Closed");
        record.alertRadius = tag.getInt("AlertRadius");
        record.staffCheckTimer = tag.getInt("StaffCheck");
        record.radioCallTimer = tag.getInt("RadioCall");
        return record;
    }
}
