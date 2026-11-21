package com.phian404.survivalpoints.camp.block.fence;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Секция листа ({@code sheet_section}) со сшиванием вертикально стекованных листов.
 *
 * <p>Лист — единственная секция, у которой металлическая рейка ({@code core_up} /
 * {@code core_down}) стоит на торце яруса. Если поставить второй лист вплотную
 * сверху или снизу, рейки соседних блоков оказывались в одной плоскости на
 * границе: металл накладывался сам на себя давал шов по стыку.
 *
 * <p>Правило сшивания: торец, к которому примыкает лист, отдаётся листу, а рейка
 * на этом торце не рисуется. Геометрия листа продлевается до границы блока
 * (0 или 16), поэтому плоскости соседних блоков смыкаются вплотную и щели по
 * высоте не остаётся. UV по V пересчитывается под новую высоту грани по общему
 * правилу {@code V = 16 − Y}, U и горизонтальные грани не меняются.
 *
 * <p>Состояния задаются двумя свойствами, которые пересчитываются только по
 * вертикальным соседям:
 * <ul>
 *   <li>{@link #SHEET_ABOVE} — сверху вплотную стоит лист;</li>
 *   <li>{@link #SHEET_BELOW} — снизу вплотную стоит лист.</li>
 * </ul>
 * Комбинации покрывают все положения листа в стеке, включая среднее звено, у
 * которого нет ни верхней, ни нижней рейки:
 * <table border="1">
 *   <caption>Свойства → модель</caption>
 *   <tr><th>above</th><th>below</th><th>модель</th></tr>
 *   <tr><td>false</td><td>false</td><td>section_sheet (одиночный лист)</td></tr>
 *   <tr><td>true</td><td>false</td><td>section_sheet_bottom (нижний в стеке)</td></tr>
 *   <tr><td>false</td><td>true</td><td>section_sheet_top (верхний в стеке)</td></tr>
 *   <tr><td>true</td><td>true</td><td>section_sheet_middle (средний в стеке)</td></tr>
 * </table>
 *
 * <p>Сшивается только пара «лист-лист»: с {@code mesh}, {@code plank}, {@code wire}
 * и {@code post} торцы остаются в базовом виде.
 */
public class SheetSectionBlock extends StackedSectionBlock {

    /** Сверху вплотную стоит лист — верхний торец отдан листу. */
    public static final BooleanProperty SHEET_ABOVE = BooleanProperty.create("sheet_above");

    /** Снизу вплотную стоит лист — нижний торец отдан листу. */
    public static final BooleanProperty SHEET_BELOW = BooleanProperty.create("sheet_below");

    public SheetSectionBlock() {
        super(SoundType.METAL);
    }

    @Override
    protected BooleanProperty aboveProperty() {
        return SHEET_ABOVE;
    }

    @Override
    protected BooleanProperty belowProperty() {
        return SHEET_BELOW;
    }

    /** Базовый и ржавые варианты листа — один материал и сшиваются между собой. */
    @Override
    protected boolean isSameMaterial(Block block) {
        return block instanceof SheetSectionBlock;
    }
}