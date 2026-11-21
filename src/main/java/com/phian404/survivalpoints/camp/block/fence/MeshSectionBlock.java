package com.phian404.survivalpoints.camp.block.fence;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Секция сетки «рабица» ({@code mesh_section}) со сшиванием вертикально
 * стекованных панелей.
 *
 * <p>У сетки на торце яруса стоит металлическая рейка ({@code core_up} /
 * {@code core_down}). Если поставить вторую сетку вплотную сверху или снизу,
 * рейки соседних блоков оказывались в одной плоскости на границе: металл
 * накладывался сам на себя и давал шов по стыку.
 *
 * <p>Правило сшивания и пересчёт UV описаны в {@link StackedSectionBlock}.
 * Комбинации свойств покрывают все положения панели в стеке:
 * <table border="1">
 *   <caption>Свойства → модель</caption>
 *   <tr><th>above</th><th>below</th><th>модель</th></tr>
 *   <tr><td>false</td><td>false</td><td>section_mesh (одиночная)</td></tr>
 *   <tr><td>true</td><td>false</td><td>section_mesh_bottom (нижняя в стеке)</td></tr>
 *   <tr><td>false</td><td>true</td><td>section_mesh_top (верхняя в стеке)</td></tr>
 *   <tr><td>true</td><td>true</td><td>section_mesh_middle (средняя в стеке)</td></tr>
 * </table>
 */
public class MeshSectionBlock extends StackedSectionBlock {

    /** Сверху вплотную стоит сетка — верхний торец отдан панели. */
    public static final BooleanProperty MESH_ABOVE = BooleanProperty.create("mesh_above");

    /** Снизу вплотную стоит сетка — нижний торец отдан панели. */
    public static final BooleanProperty MESH_BELOW = BooleanProperty.create("mesh_below");

    public MeshSectionBlock() {
        super(SoundType.METAL);
    }

    @Override
    protected BooleanProperty aboveProperty() {
        return MESH_ABOVE;
    }

    @Override
    protected BooleanProperty belowProperty() {
        return MESH_BELOW;
    }
}
