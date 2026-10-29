package com.phian404.survivalpoints.entity;

import com.phian404.survivalpoints.entity.role.SurvivorGender;
import net.minecraft.resources.ResourceLocation;

/**
 * Централизованный выбор текстуры и геометрии скина выжившего.
 *
 * <p>Индекс скина приходит с сервера через {@code SynchedEntityData} и
 * сохраняется в NBT, поэтому все клиенты видят одинаковый вариант, а перезаход
 * его не меняет. Новые варианты добавляются здесь и увеличивают
 * {@link BaseSurvivorEntity#SKIN_COUNT}.
 *
 * <p><b>Почему класс живёт в {@code entity}, а не в {@code client}.</b> Таблица
 * нужна серверной стороне: при загрузке старых сохранений пол восстанавливается
 * по индексу скина, а при создании NPC пол назначается из этой же таблицы.
 * Клиентский класс на dedicated server отсутствует, и обращение к нему из
 * {@link BaseSurvivorEntity} дало бы {@code NoClassDefFoundError}. Единственная
 * зависимость таблицы — {@link ResourceLocation}, доступный на обеих сторонах.
 */
public final class SurvivorSkin {

    /**
     * Тип геометрии тела скина.
     *
     * <p>WIDE — руки 4px (Steve), SLIM — руки 3px (Alex). Назначение задано
     * явно в таблице {@link #VARIANTS} по соглашению проекта: имя PNG с
     * {@code male} → WIDE, с {@code female} → SLIM. Таблица отделена от
     * механики выбора скина, поэтому тип тела можно исправить без изменения
     * логики {@code getSkinIndex()}/{@code rollRandomSkin()}.
     */
    public enum SkinBodyType {
        WIDE,
        SLIM
    }

    /** Один вариант скина: текстура, тип геометрии тела и пол персонажа. */
    public static final class SkinVariant {
        public final ResourceLocation texture;
        public final SkinBodyType bodyType;
        public final SurvivorGender gender;

        public SkinVariant(ResourceLocation texture, SkinBodyType bodyType, SurvivorGender gender) {
            this.texture = texture;
            this.bodyType = bodyType;
            this.gender = gender;
        }
    }

    /**
     * Набор скинов обычных NPC. Индекс в массиве соответствует индексу
     * скина {@link BaseSurvivorEntity#SKIN_COUNT} (0..13). Имена файлов
     * должны точно совпадать с путями в {@code src/main/resources}.
     */
    private static final SkinVariant[] VARIANTS = new SkinVariant[] {
            new SkinVariant(ResourceLocation.fromNamespaceAndPath("survivalpoints", "textures/entity/skin/npc_carpenter_0.png"), SkinBodyType.WIDE, SurvivorGender.MALE),
            new SkinVariant(ResourceLocation.fromNamespaceAndPath("survivalpoints", "textures/entity/skin/npc_cook_0.png"), SkinBodyType.SLIM, SurvivorGender.FEMALE),
            new SkinVariant(ResourceLocation.fromNamespaceAndPath("survivalpoints", "textures/entity/skin/npc_farmer_0.png"), SkinBodyType.SLIM, SurvivorGender.FEMALE),
            new SkinVariant(ResourceLocation.fromNamespaceAndPath("survivalpoints", "textures/entity/skin/npc_farmer_1.png"), SkinBodyType.SLIM, SurvivorGender.FEMALE),
            new SkinVariant(ResourceLocation.fromNamespaceAndPath("survivalpoints", "textures/entity/skin/npc_farmer_2.png"), SkinBodyType.WIDE, SurvivorGender.MALE),
            new SkinVariant(ResourceLocation.fromNamespaceAndPath("survivalpoints", "textures/entity/skin/npc_guard_0.png"), SkinBodyType.WIDE, SurvivorGender.MALE),
            new SkinVariant(ResourceLocation.fromNamespaceAndPath("survivalpoints", "textures/entity/skin/npc_hunter_0.png"), SkinBodyType.SLIM, SurvivorGender.FEMALE),
            new SkinVariant(ResourceLocation.fromNamespaceAndPath("survivalpoints", "textures/entity/skin/npc_mechanic_0.png"), SkinBodyType.WIDE, SurvivorGender.MALE),
            new SkinVariant(ResourceLocation.fromNamespaceAndPath("survivalpoints", "textures/entity/skin/npc_mechanic_1.png"), SkinBodyType.WIDE, SurvivorGender.MALE),
            new SkinVariant(ResourceLocation.fromNamespaceAndPath("survivalpoints", "textures/entity/skin/npc_medic_0.png"), SkinBodyType.SLIM, SurvivorGender.FEMALE),
            new SkinVariant(ResourceLocation.fromNamespaceAndPath("survivalpoints", "textures/entity/skin/npc_ranger_0.png"), SkinBodyType.WIDE, SurvivorGender.MALE),
            new SkinVariant(ResourceLocation.fromNamespaceAndPath("survivalpoints", "textures/entity/skin/npc_scavenger_0.png"), SkinBodyType.WIDE, SurvivorGender.MALE),
            new SkinVariant(ResourceLocation.fromNamespaceAndPath("survivalpoints", "textures/entity/skin/npc_settler_0.png"), SkinBodyType.SLIM, SurvivorGender.FEMALE),
            new SkinVariant(ResourceLocation.fromNamespaceAndPath("survivalpoints", "textures/entity/skin/npc_trader_0.png"), SkinBodyType.WIDE, SurvivorGender.MALE)
    };

    private SurvivorSkin() {
    }

    public static ResourceLocation textureFor(int index) {
        if (index < 0 || index >= VARIANTS.length) {
            return VARIANTS[0].texture;
        }
        return VARIANTS[index].texture;
    }

    /** Тип геометрии тела для индекса скина. При выходе за границы — WIDE. */
    public static SkinBodyType bodyTypeFor(int index) {
        if (index < 0 || index >= VARIANTS.length) {
            return SkinBodyType.WIDE;
        }
        return VARIANTS[index].bodyType;
    }

    /**
     * Пол, закреплённый за индексом скина в таблице.
     *
     * <p>Используется только для первоначального назначения пола и для миграции
     * старых сохранений, где тега {@code Gender} ещё нет. Если сохранённый пол
     * расходится с таблицей, источником истины остаётся сохранённый пол —
     * этот метод его не переопределяет. При выходе за границы — {@code MALE}.
     */
    public static SurvivorGender genderFor(int index) {
        if (index < 0 || index >= VARIANTS.length) {
            return SurvivorGender.MALE;
        }
        return VARIANTS[index].gender;
    }

    static {
        // Инвариант: длина таблицы должна совпадать с SKIN_COUNT, иначе индексы
        // за пределами массива молча откатывались бы на скин 0. SKIN_COUNT —
        // константа времени компиляции, поэтому циклической загрузки классов нет.
        if (VARIANTS.length != BaseSurvivorEntity.SKIN_COUNT) {
            throw new IllegalStateException("SurvivorSkin.VARIANTS.length=" + VARIANTS.length
                    + " не совпадает с BaseSurvivorEntity.SKIN_COUNT=" + BaseSurvivorEntity.SKIN_COUNT);
        }
        for (int i = 0; i < VARIANTS.length; i++) {
            SkinVariant variant = VARIANTS[i];
            if (variant.texture == null || variant.bodyType == null || variant.gender == null) {
                throw new IllegalStateException("Незаполненный вариант скина #" + i);
            }
        }
    }
}
