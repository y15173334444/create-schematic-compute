package io.github.y15173334444.create_schematic_compute.client.colorpicker;

import net.minecraft.client.gui.GuiGraphics;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A small square button that displays a color swatch and opens a
 * {@link ColorPickerWidget} when clicked.
 *
 * Uses Supplier / Consumer lambdas for binding to any data source
 * (node field, staging array, etc.) without coupling to data structure.
 */
public class ColorPickerButton {

    private int x, y;
    private static final int SIZE = 16;

    private static ColorPickerButton activeButton; // last-clicked, shows highlight

    private final Supplier<Integer> colorGetter;
    private final Consumer<Integer> colorSetter;
    private final ColorPickerWidget picker;
    private final boolean leftSide;
    private final Consumer<Integer> liveUpdate;
    private final boolean showErase;
    private boolean selected;

    public ColorPickerButton(Supplier<Integer> getter, Consumer<Integer> setter,
                             ColorPickerWidget picker) {
        this(getter, setter, picker, false, null, false);
    }

    public ColorPickerButton(Supplier<Integer> getter, Consumer<Integer> setter,
                             ColorPickerWidget picker, boolean leftSide) {
        this(getter, setter, picker, leftSide, null, false);
    }

    public ColorPickerButton(Supplier<Integer> getter, Consumer<Integer> setter,
                             ColorPickerWidget picker, boolean leftSide, Consumer<Integer> liveUpdate) {
        this(getter, setter, picker, leftSide, liveUpdate, false);
    }

    public ColorPickerButton(Supplier<Integer> getter, Consumer<Integer> setter,
                             ColorPickerWidget picker, boolean leftSide, Consumer<Integer> liveUpdate,
                             boolean showErase) {
        this.colorGetter = getter;
        this.colorSetter = setter;
        this.picker = picker;
        this.leftSide = leftSide;
        this.liveUpdate = liveUpdate;
        this.showErase = showErase;
    }

    public void setPosition(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getSize() { return SIZE; }

    public int getColor() { return colorGetter.get(); }

    /** Render the swatch and ▼ indicator. */
    public void render(GuiGraphics g, int mx, int my) {
        int color = colorGetter.get();
        g.fill(x, y, x + SIZE, y + SIZE, color);

        boolean hover = isHovered(mx, my);
        int border = selected ? 0xFFD4A017 : (hover ? 0xFFFFAA44 : 0xFF888888);
        g.renderOutline(x, y, SIZE, SIZE, border);

        // Small ▼ triangle indicator in bottom-right corner
        if (selected || hover) {
            int cx = x + SIZE - 4;
            int cy = y + SIZE - 3;
            g.fill(cx, cy, cx + 3, cy + 1, border);
            g.fill(cx + 1, cy + 1, cx + 2, cy + 2, border);
        }
    }

    /**
     * Handle mouse click. If picker is already open, just rebind; otherwise open.
     * @return true if the click was on this button
     */
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn == 0 && isHovered((int) mx, (int) my)) {
            // Highlight this button, un-highlight previous
            if (activeButton != null) activeButton.selected = false;
            this.selected = true;
            activeButton = this;

            if (picker.isVisible()) {
                picker.rebind(colorGetter.get(), selected -> colorSetter.accept(selected), liveUpdate);
            } else {
                picker.open((int) mx, (int) my, colorGetter.get(), selected -> colorSetter.accept(selected), liveUpdate, leftSide, showErase);
            }
            return true;
        }
        return false;
    }

    public boolean isHovered(int mx, int my) {
        return mx >= x && mx < x + SIZE && my >= y && my < y + SIZE;
    }

    /** Clear the persistent selection highlight on all buttons. */
    public static void clearSelection() {
        if (activeButton != null) { activeButton.selected = false; activeButton = null; }
    }
}
