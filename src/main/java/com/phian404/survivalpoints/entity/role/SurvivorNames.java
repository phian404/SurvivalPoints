package com.phian404.survivalpoints.entity.role;

import net.minecraft.util.RandomSource;

/**
 * Источник имён обычных NPC.
 *
 * <p>Имя назначается один раз при первичном создании и дальше живёт в ванильном
 * {@code CustomName} сущности: отдельного поля в NBT нет. Поэтому списки нужны
 * только в момент назначения и не участвуют в рендере или загрузке мира.
 *
 * <p><b>Почему списки разделены по полу.</b> Пол уже хранится у NPC
 * ({@link SurvivorGender}) и является источником истины, поэтому имя выбирается
 * из соответствующего набора без обращения к скину или другим клиентским данным.
 *
 * <p><b>Расширение.</b> Новые имена и фамилии добавляются строками в массивы
 * ниже; логика выбора при этом не меняется. Язык — английский, чтобы имена
 * согласовывались с лором лидеров (Cole Reed, Martha Hill, Mike Carter,
 * Sam Walker).
 */
public final class SurvivorNames {

    private static final String[] MALE_FIRST = new String[] {
            "Cole", "Mike", "Sam", "Dean", "Ethan", "Frank", "Gabe", "Hank",
            "Isaac", "Jake", "Kurt", "Leon", "Miles", "Nolan", "Owen", "Ray",
            "Shane", "Troy", "Vince", "Wade", "Adam", "Boris", "Caleb", "Dylan"
    };

    private static final String[] FEMALE_FIRST = new String[] {
            "Martha", "Anna", "Beth", "Clara", "Dana", "Elena", "Faye", "Grace",
            "Hazel", "Iris", "June", "Kate", "Lena", "Mia", "Nora", "Olga",
            "Paula", "Rita", "Sara", "Tess", "Vera", "Wendy", "Zoe", "Alice"
    };

    private static final String[] LAST_NAMES = new String[] {
            "Reed", "Hill", "Carter", "Walker", "Baker", "Boyd", "Burke", "Cole",
            "Doyle", "Foster", "Grant", "Hayes", "Holt", "Kane", "Lane", "Marsh",
            "Mercer", "Nash", "Page", "Quinn", "Rhodes", "Stone", "Vance", "Webb"
    };

    private SurvivorNames() {
    }

    /**
     * Случайное имя вида {@code "First Last"} для указанного пола.
     *
     * <p>Метод не проверяет уникальность: имя — идентификатор личности, а не
     * ключ. Уникальным ключом NPC остаётся identity UUID.
     */
    public static String random(SurvivorGender gender, RandomSource random) {
        String[] firstNames = gender == SurvivorGender.FEMALE ? FEMALE_FIRST : MALE_FIRST;
        String first = firstNames[random.nextInt(firstNames.length)];
        String last = LAST_NAMES[random.nextInt(LAST_NAMES.length)];
        return first + " " + last;
    }
}
