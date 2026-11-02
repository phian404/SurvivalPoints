package com.phian404.survivalpoints.camp.block;

/**
 * ТЕСТОВЫЙ вариант колючей проволоки — «паутина».
 *
 * <p>Полностью повторяет поведение {@link CampBarbedWireBlock} (свои стороны
 * {@code north/east/south/west}, соединение только с себе подобным, пустая
 * коллизия, отсутствие водозаполнения), но имеет отдельную модель и текстуру —
 * сетчатый рисунок с кастомной расцветкой.
 *
 * <p>Добавлен по просьбе владельца для сравнения с основной проволокой:
 * существующий {@code camp_barbed_wire} не изменялся, оба блока сосуществуют.
 */
public class CampWireWebBlock extends CampBarbedWireBlock {

    public CampWireWebBlock() {
        super();
    }
}
