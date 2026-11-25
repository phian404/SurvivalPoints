package com.phian404.survivalpoints.entity.role;

import com.phian404.survivalpoints.entity.BaseGuardianEntity;
import com.phian404.survivalpoints.entity.BaseSurvivorEntity;
import com.phian404.survivalpoints.entity.BaseSurvivorNpcEntity;
import com.phian404.survivalpoints.init.ModEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Единая точка создания выживших и смены их роли.
 *
 * <p>Все сценарии — взаимодействие игрока (арбалет), будущий экран управления
 * лагерем и генерация NPC сразу нужной роли — должны идти через этот сервис,
 * чтобы правила переноса данных были одинаковыми.
 *
 * <p><b>Почему замена сущности, а не смена поля.</b> В Minecraft тип сущности
 * задаётся её {@code EntityType} и не может быть изменён у существующего
 * объекта. Поэтому смена роли создаёт новую сущность нужного типа и удаляет
 * старую.
 *
 * <p><b>UUID.</b> Minecraft UUID сущности у новой сущности будет новым: движок
 * регистрирует сущности по UUID с проверкой уникальности, и повторное
 * использование старого UUID потребовало бы вмешательства во внутренний
 * реестр. Лагерные связи поэтому опираются на постоянный identity UUID
 * ({@link BaseSurvivorEntity#getIdentityId()}), который переносится явно.
 */
public final class RoleAssignmentService {

    private RoleAssignmentService() {
    }

    /**
     * Создаёт выжившего нужной роли «с нуля» — для генерации лагеря и яиц.
     *
     * @param level    уровень, в котором создаётся сущность
     * @param role     желаемая роль
     * @param campId   лагерь или {@code null} для независимого NPC
     * @param postId   пост лагеря или {@code null}
     * @return созданная сущность или {@code null}, если тип недоступен
     */
    @Nullable
    public static BaseSurvivorEntity create(Level level, SurvivorRole role,
                                            @Nullable UUID campId, @Nullable UUID postId) {
        BaseSurvivorEntity entity = switch (role) {
            case GUARDIAN -> ModEntities.BASE_GUARDIAN.get().create(level);
            case CIVILIAN -> ModEntities.BASE_SURVIVOR.get().create(level);
        };
        if (entity == null) {
            return null;
        }
        // Новый NPC получает собственный identity UUID, не привязанный к runtime UUID.
        entity.setIdentityId(UUID.randomUUID());
        entity.setCampId(campId);
        entity.setCampPostId(postId);
        if (!level.isClientSide) {
            // Скин, рост и имя назначаются один раз здесь же: этот метод создаёт
            // сущность «с нуля» и не вызывает finalizeSpawn, поэтому дубля нет.
            // Имя — последним, потому что набор имён зависит от пола.
            entity.rollRandomSkin();
            entity.rollRandomScale();
            entity.ensureName();
        }
        return entity;
    }

    /**
     * Проверяет, можно ли назначить роль текущей сущности.
     *
     * <p>Отдельный метод нужен, чтобы вызывающие (взаимодействие, UI) могли
     * заранее показать отказ и не начинать операцию.
     */
    public static boolean canAssign(BaseSurvivorEntity source, SurvivorRole target) {
        return source.getRole() != target && !source.isRemoved();
    }

    /**
     * Меняет роль существующего выжившего, заменяя сущность.
     *
     * <p>Порядок намеренно «сначала создать, потом удалять»: если создание или
     * добавление новой сущности не удалось, исходная остаётся в мире и игрок
     * не теряет NPC.
     *
     * @return новая сущность или {@code null}, если замена не удалась
     */
    @Nullable
    public static BaseSurvivorEntity reassign(BaseSurvivorEntity source, SurvivorRole target) {
        if (!canAssign(source, target)) {
            return null;
        }
        if (source.level().isClientSide || !(source.level() instanceof ServerLevel serverLevel)) {
            return null;
        }

        BaseSurvivorEntity replacement = switch (target) {
            case GUARDIAN -> ModEntities.BASE_GUARDIAN.get().create(serverLevel);
            case CIVILIAN -> ModEntities.BASE_SURVIVOR.get().create(serverLevel);
        };
        if (replacement == null) {
            return null;
        }

        copySharedState(source, replacement);

        // addFreshEntity возвращает false, если UUID уже занят или сущность
        // помечена удалённой. В этом случае исходник не трогаем.
        if (!serverLevel.addFreshEntity(replacement)) {
            replacement.discard();
            return null;
        }

        // Новая сущность успешно в мире — теперь можно убрать старую.
        source.discard();
        return replacement;
    }

    /**
     * Переносит общие данные выжившего: identity, скин, лагерную привязку,
     * позицию, имя, здоровье и экипировку.
     *
     * <p>Runtime UUID не копируется сознательно — см. javadoc класса.
     */
    private static void copySharedState(BaseSurvivorEntity source, BaseSurvivorEntity replacement) {
        replacement.setIdentityId(source.getIdentityId());
        replacement.setSkinIndex(source.getSkinIndex());
        // Пол переносится явно и не пересчитывается из скина: смена роли не
        // должна менять пол, даже если таблица скинов говорит иное.
        replacement.setGender(source.getGender());
        // Рост тоже переносится, а не перегенерируется: иначе NPC «прыгал» бы
        // в размере при каждой смене роли.
        replacement.setScale(source.getScale());
        replacement.setCampId(source.getCampId());
        replacement.setCampPostId(source.getCampPostId());
        // Профессия и рабочая точка — независимые от роли данные: смена типа
        // сущности не должна сбрасывать фермера в NONE или терять его POI.
        replacement.setProfession(source.getProfession());
        replacement.setWorkPoiPos(source.getWorkPoiPos());

        // Внутренний инвентарь: копируем каждый стек, чтобы новая сущность не
        // делила изменяемые ItemStack со старой. Пустые слоты остаются пустыми.
        for (int slot = 0; slot < source.getInventory().getSlots(); slot++) {
            ItemStack stored = source.getInventory().getStackInSlot(slot);
            if (!stored.isEmpty() && slot < replacement.getInventory().getSlots()) {
                replacement.getInventory().setStackInSlot(slot, stored.copy());
            }
        }

        replacement.moveTo(source.getX(), source.getY(), source.getZ(),
                source.getYRot(), source.getXRot());
        replacement.setYHeadRot(source.getYHeadRot());

        // Имя к этому моменту уже назначено (ensureName() в точках создания),
        // поэтому перенос всегда сохраняет прежнее имя: смена роли не должна
        // генерировать новое. Проверка hasCustomName() оставлена как защита для
        // сущностей, созданных в обход сервиса.
        if (source.hasCustomName()) {
            replacement.setCustomName(source.getCustomName());
            replacement.setCustomNameVisible(source.isCustomNameVisible());
        }
        if (source.isPersistenceRequired()) {
            replacement.setPersistenceRequired();
        }
        replacement.setHealth(Math.min(source.getHealth(), replacement.getMaxHealth()));

        // Экипировка: переносим только основную руку. Для стража оружие всё
        // равно приведётся к состоянию AI на первом тике.
        ItemStack mainHand = source.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!mainHand.isEmpty()) {
            replacement.setItemSlot(EquipmentSlot.MAINHAND, mainHand.copy());
        }
    }

    /**
     * Проверяет, что предмет в руке игрока — арбалет.
     *
     * <p>Вынесено сюда, чтобы ручное взаимодействие и будущий UI проверяли
     * условие одинаково.
     */
    public static boolean isGuardianAssignmentItem(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.is(Items.CROSSBOW);
    }

    /**
     * Приводит экипировку созданного стража в согласованное состояние,
     * не дожидаясь первого серверного тика.
     */
    public static void prepareGuardianEquipment(BaseSurvivorEntity entity) {
        if (entity instanceof BaseGuardianEntity guardian) {
            guardian.syncEquipmentToState();
        }
    }

    /**
     * Удобный помощник для проверки типа перед приведением.
     */
    public static boolean isCivilian(@Nullable Entity entity) {
        return entity instanceof BaseSurvivorNpcEntity;
    }

    /**
     * Проверяет, что сущность — выживший, которым можно управлять ролями.
     */
    public static boolean isSurvivor(@Nullable Mob entity) {
        return entity instanceof BaseSurvivorEntity;
    }
}
