package com.phian404.survivalpoints.camp.site;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Серверная конфигурация автоматического поиска площадок.
 *
 * <p>Один параметр: {@link #SITE_AUTO_ENABLED}. По умолчанию {@code false} —
 * мод не создаёт лагеря сам, пока владелец явно не включит режим в серверном
 * TOML. Остальные параметры намеренно оставлены внутренними константами
 * {@code CampTuning}: это не пользовательские настройки, а параметры алгоритма.
 */
public final class CampSiteConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.BooleanValue SITE_AUTO_ENABLED = BUILDER
            .comment("Automatically create survivor camps at deterministic sites "
                    + "while players explore the world. Default: false.")
            .define("siteAutoEnabled", false);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private CampSiteConfig() {
    }

    /** Включён ли автоматический режим. */
    public static boolean isAutoEnabled() {
        return SITE_AUTO_ENABLED.get();
    }
}
