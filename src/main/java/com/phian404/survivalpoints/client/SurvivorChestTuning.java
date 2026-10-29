package com.phian404.survivalpoints.client;

/**
 * Зафиксированные параметры выступа торса женской модели.
 *
 * <p>Класс — хранилище констант: значения формы груди читаются отсюда и не
 * меняются в рантайме. Раньше здесь были сеттеры и {@code revision} для подбора
 * формы командой {@code /spdebug chest}; команда удалена, поэтому остались
 * только константы и геттеры.
 *
 * <p><b>Почему сегменты.</b> Один куб не давал нужной формы, поэтому одна
 * деталь собирается из двух кубов по {@code width<=2}, а две детали — из
 * четырёх. {@code texOffs} сдвигается на {@code width} за сегмент, поэтому
 * передние грани четырёх кубов образуют непрерывную развёртку передней грани
 * торса и стыки невидимы. При {@code offsetXL=-2}, {@code offsetXR=+2}
 * и {@code width=2} полоса идёт от {@code x=-4} до {@code x=+4} — вся ширина
 * торса. Подробности UV — в
 * {@code SurvivorBodyModel.chestLayer(boolean)}.
 */
public final class SurvivorChestTuning {

    private SurvivorChestTuning() {
    }

    public static final float DEFAULT_WIDTH = 2.0F;
    public static final float DEFAULT_HEIGHT = 3.0F;
    public static final float DEFAULT_DEPTH = 3.0F;
    public static final float DEFAULT_OFFSET_Y = 3.0F;
    public static final float DEFAULT_OFFSET_Z = 0.5F;
    public static final float DEFAULT_OFFSET_X_LEFT = -2.0F;
    public static final float DEFAULT_OFFSET_X_RIGHT = 2.0F;
    public static final float DEFAULT_TILT_LEFT = -0.5F;
    public static final float DEFAULT_TILT_RIGHT = -0.5F;

    // ==================== Чтение ====================

    public static float width() {
        return DEFAULT_WIDTH;
    }

    public static float height() {
        return DEFAULT_HEIGHT;
    }

    public static float depth() {
        return DEFAULT_DEPTH;
    }

    public static float offsetY() {
        return DEFAULT_OFFSET_Y;
    }

    public static float offsetZ() {
        return DEFAULT_OFFSET_Z;
    }

    public static float offsetXLeft() {
        return DEFAULT_OFFSET_X_LEFT;
    }

    public static float offsetXRight() {
        return DEFAULT_OFFSET_X_RIGHT;
    }

    public static float tiltLeft() {
        return DEFAULT_TILT_LEFT;
    }

    public static float tiltRight() {
        return DEFAULT_TILT_RIGHT;
    }

}
