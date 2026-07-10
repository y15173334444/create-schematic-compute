package io.github.y15173334444.create_schematic_compute.client.colorpicker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Consumer;

/**
 * Compact color picker: SV plane + hue bar + alpha bar (vertical, beside hue).
 * Favorites grid with vertical scroll, recent grid (no scroll).
 */
public class ColorPickerWidget {

    // ── Layout ──
    public static final int WIDTH = 164;
    private static final int HEIGHT = 246;

    private static final int TITLE_H = 10;
    private static final int SV_X = 4, SV_Y = TITLE_H + 2, SV_SIZE = 120;
    private static final int HUE_X = SV_X + SV_SIZE + 4, HUE_Y = SV_Y, HUE_W = 14, HUE_H = SV_SIZE;
    private static final int ALPHA_X = HUE_X + HUE_W + 2, ALPHA_Y = SV_Y, ALPHA_W = 14, ALPHA_H = SV_SIZE;

    // Grid
    private static final int GRID_COLS = 8;
    private static final int SWATCH = 13, GAP = 2;
    private static final int CELL = SWATCH + GAP;
    private static final int GRID_W = GRID_COLS * CELL;
    private static final int SCROLLBAR_W = 6;
    private static final int BTN_X = 98; // +/-/reset fixed position, clear of alpha bar

    private static final int FAV_Y = SV_Y + SV_SIZE + 3;
    private static final int FAV_VISIBLE_ROWS = 2;
    private static final int FAV_HEADER_H = 14; // room for +/-/reset buttons (12px)

    private static final int REC_Y = FAV_Y + FAV_HEADER_H + FAV_VISIBLE_ROWS * CELL + 3;
    private static final int REC_VISIBLE_ROWS = 2;
    private static final int REC_HEADER_H = 14;

    private static final int HEX_X = 4, HEX_W = 76, HEX_H = 16;
    private static final int ERASE_X = HEX_X + HEX_W + 3, ERASE_W = 28, ERASE_H = 16;
    private static final int OK_X = ERASE_X + ERASE_W + 3, OK_W = 50, OK_H = 16;
    private static final int BOTTOM_Y = REC_Y + REC_HEADER_H + REC_VISIBLE_ROWS * CELL + 3;

    // ── State ──
    private boolean visible;
    private int screenX, screenY;
    private float hue, saturation, brightness;
    private int alpha = 0xFF;
    private boolean draggingSV, draggingHue, draggingAlpha;
    private int favScrollRow; // row offset (0, 1, 2...) — snaps to whole rows
    private boolean draggingFavSb;
    private int selectedFavIdx = -1;
    private Consumer<Integer> onSelect;
    private Consumer<Integer> liveUpdate; // fires on every HSV/alpha change
    private boolean showErase; // only in pixel editor context
    private final EditBox hexInput;
    private final Font font;
    private boolean updatingHexFromPicker;

    public ColorPickerWidget() {
        this.font = Minecraft.getInstance().font;
        this.hexInput = new EditBox(font, 0, 0, HEX_W, HEX_H, Component.literal(""));
        this.hexInput.setMaxLength(8);
        this.hexInput.setResponder(this::onHexChanged);
        this.hexInput.setFilter(s -> {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')))
                    return false;
            }
            return true;
        });
    }

    public boolean isVisible() { return visible; }
    public boolean contains(int mx, int my) { return visible && mx >= screenX && mx < screenX + WIDTH && my >= screenY && my < screenY + HEIGHT; }

    public void open(int anchorX, int anchorY, int startColor, Consumer<Integer> onSelect) {
        open(anchorX, anchorY, startColor, onSelect, null, false);
    }

    /** Open with liveUpdate (fires on every change, for pixel editor). */
    public void open(int anchorX, int anchorY, int startColor, Consumer<Integer> onSelect,
                     Consumer<Integer> liveUpdate, boolean leftSide) {
        open(anchorX, anchorY, startColor, onSelect, liveUpdate, leftSide, false);
    }

    /** Open with full control: liveUpdate + showErase (pixel editor only). */
    public void open(int anchorX, int anchorY, int startColor, Consumer<Integer> onSelect,
                     Consumer<Integer> liveUpdate, boolean leftSide, boolean showErase) {
        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int sh = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        this.screenX = leftSide ? 8 : sw - WIDTH - 8;
        this.screenY = Mth.clamp(anchorY - HEIGHT / 2, 4, sh - HEIGHT - 4);
        this.onSelect = onSelect;
        this.liveUpdate = liveUpdate;
        this.showErase = showErase;

        float[] hsb = ColorUtils.rgbToHsb(startColor);
        this.hue = hsb[0]; this.saturation = hsb[1]; this.brightness = hsb[2];
        this.alpha = ColorUtils.alpha(startColor);

        this.updatingHexFromPicker = true;
        this.hexInput.setValue(ColorUtils.hex8(startColor));
        this.updatingHexFromPicker = false;
        this.hexInput.setCursorPosition(0);
        this.hexInput.setHighlightPos(0);

        this.visible = true;
        draggingSV = draggingHue = draggingAlpha = false;
        favScrollRow = 0;
        draggingFavSb = false;
        selectedFavIdx = -1;
        RecentColors.load();
    }

    public void close() { visible = false; ColorPickerButton.clearSelection(); }

    /** Update color + callbacks without repositioning. */
    public void rebind(int newColor, Consumer<Integer> newCallback) {
        rebind(newColor, newCallback, null);
    }

    public void rebind(int newColor, Consumer<Integer> newCallback, Consumer<Integer> newLiveUpdate) {
        float[] hsb = ColorUtils.rgbToHsb(newColor);
        this.hue = hsb[0]; this.saturation = hsb[1]; this.brightness = hsb[2];
        this.alpha = ColorUtils.alpha(newColor);
        this.onSelect = newCallback;
        this.liveUpdate = newLiveUpdate;
        this.updatingHexFromPicker = true;
        this.hexInput.setValue(ColorUtils.hex8(newColor));
        this.updatingHexFromPicker = false;
        this.selectedFavIdx = -1;
    }

    // ── Rendering ──

    public void render(GuiGraphics g, int mx, int my) {
        if (!visible) return;
        int px = screenX, py = screenY;

        g.fill(px, py, px + WIDTH, py + HEIGHT, 0xEE2A2822);
        g.renderOutline(px, py, WIDTH, HEIGHT, 0xFFD4A017);
        g.renderOutline(px + 1, py + 1, WIDTH - 2, HEIGHT - 2, 0xFF444444);

        g.fill(px + 2, py + 2, px + WIDTH - 2, py + TITLE_H, 0xFF4A3F28);
        g.drawString(font, I18n.get("gui.create_schematic_compute.colorpicker.title"), px + 6, py + 3, 0xFFFFCC88, false);

        renderSVPlane(g, px + SV_X, py + SV_Y);
        renderHueBar(g, px + HUE_X, py + HUE_Y);
        renderAlphaBar(g, px + ALPHA_X, py + ALPHA_Y);
        renderColorGrid(g, px, py + FAV_Y, true, mx, my);
        renderColorGrid(g, px, py + REC_Y, false, mx, my);

        hexInput.setX(px + HEX_X);
        hexInput.setY(py + BOTTOM_Y);
        hexInput.render(g, mx, my, 0);
        int okY = py + BOTTOM_Y;
        // Eraser button (pixel editor only)
        if (showErase) {
            boolean erH = hit(mx, my, px + ERASE_X, okY, ERASE_W, ERASE_H);
            g.fill(px + ERASE_X, okY, px + ERASE_X + ERASE_W, okY + ERASE_H, erH ? 0xFF5A4A3A : 0xFF4A3A2A);
            g.renderOutline(px + ERASE_X, okY, ERASE_W, ERASE_H, 0xFF8A6A5A);
            g.drawString(font, I18n.get("gui.create_schematic_compute.colorpicker.erase"), px + ERASE_X + (ERASE_W - font.width(I18n.get("gui.create_schematic_compute.colorpicker.erase"))) / 2, okY + 3, 0xFFFFCC88, false);
        }
        // OK button
        boolean okH = hit(mx, my, px + OK_X, okY, OK_W, OK_H);
        g.fill(px + OK_X, okY, px + OK_X + OK_W, okY + OK_H, okH ? 0xFF4A6A3A : 0xFF3A5A2A);
        g.renderOutline(px + OK_X, okY, OK_W, OK_H, 0xFF5A8A3A);
        g.drawString(font, I18n.get("gui.create_schematic_compute.colorpicker.ok"), px + OK_X + (OK_W - font.width(I18n.get("gui.create_schematic_compute.colorpicker.ok"))) / 2, okY + 3, 0xFF88FF88, false);
    }

    private void renderSVPlane(GuiGraphics g, int sx, int sy) {
        // Per-column vertical gradients (fillGradient is vertical: top→bottom)
        for (int x = 0; x < SV_SIZE; x++) {
            float sat = (float) x / SV_SIZE;
            g.fillGradient(sx + x, sy, sx + x + 1, sy + SV_SIZE,
                ColorUtils.hsbToRgb(hue, sat, 1f),   // top = full brightness
                ColorUtils.hsbToRgb(hue, sat, 0f));  // bottom = black
        }
        int cx = sx + Mth.clamp((int)(saturation * SV_SIZE), 0, SV_SIZE - 1);
        int cy = sy + Mth.clamp((int)((1f - brightness) * SV_SIZE), 0, SV_SIZE - 1);
        int cc = brightness > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
        g.fill(cx - 4, cy, cx + 5, cy + 1, cc);
        g.fill(cx, cy - 4, cx + 1, cy + 5, cc);
    }

    private void renderHueBar(GuiGraphics g, int hx, int hy) {
        for (int y = 0; y < HUE_H; y++)
            g.fill(hx, hy + y, hx + HUE_W, hy + y + 1, ColorUtils.hsbToRgb((float)y / HUE_H, 1f, 1f));
        int cy = hy + Mth.clamp((int)(hue * HUE_H), 0, HUE_H - 1);
        g.fill(hx - 1, cy - 2, hx + 2, cy + 2, 0xFFFFFFFF);
        g.fill(hx + HUE_W - 2, cy - 2, hx + HUE_W + 1, cy + 2, 0xFFFFFFFF);
    }

    /** Vertical alpha bar — checkerboard + gradient, cursor arrows. */
    private void renderAlphaBar(GuiGraphics g, int ax, int ay) {
        // Checkerboard
        for (int y = 0; y < ALPHA_H; y += 5)
            for (int x = 0; x < ALPHA_W; x += 5)
                g.fill(ax + x, ay + y, ax + Math.min(x + 5, ALPHA_W), ay + Math.min(y + 5, ALPHA_H),
                    ((x / 5 + y / 5) & 1) == 0 ? 0xFFCCCCCC : 0xFF888888);
        // Gradient (top=opaque, bottom=transparent)
        int rgb = ColorUtils.setAlpha(ColorUtils.hsbToRgb(hue, saturation, brightness), 0xFF);
        for (int y = 0; y < ALPHA_H; y++) {
            int a = 255 - (int)((float)y / ALPHA_H * 255f);
            g.fill(ax, ay + y, ax + ALPHA_W, ay + y + 1, ColorUtils.setAlpha(rgb, a));
        }
        g.renderOutline(ax, ay, ALPHA_W, ALPHA_H, 0xFF888888);
        int cy = ay + Mth.clamp(ALPHA_H - (int)(alpha / 255f * ALPHA_H), 0, ALPHA_H - 1);
        g.fill(ax - 1, cy - 2, ax + 2, cy + 2, 0xFFFFFFFF);
        g.fill(ax + ALPHA_W - 2, cy - 2, ax + ALPHA_W + 1, cy + 2, 0xFFFFFFFF);
        // Label below
        g.drawString(font, "α", ax + 1, ay + ALPHA_H + 1, 0xFFAAAAAA, false);
    }

    private void renderColorGrid(GuiGraphics g, int px, int py, boolean isFav, int mx, int my) {
        List<Integer> colors = isFav ? RecentColors.getFavorites() : RecentColors.getRecents();
        int cols = isFav ? GRID_COLS - 3 : GRID_COLS; // favorites: last 3 cols are buttons
        int totalRows = (colors.size() + cols - 1) / cols;
        int visibleRows = isFav ? FAV_VISIBLE_ROWS : REC_VISIBLE_ROWS;
        int headerH = isFav ? FAV_HEADER_H : REC_HEADER_H;
        int gridH = visibleRows * CELL;
        int off = isFav ? favScrollRow * CELL : 0;
        int maxOff = Math.max(0, totalRows * CELL - gridH);
        off = Mth.clamp(off, 0, maxOff);

        // Header
        g.drawString(font, (isFav ? "★" : "◉") + " " + (isFav ? I18n.get("gui.create_schematic_compute.colorpicker.favorites") : I18n.get("gui.create_schematic_compute.colorpicker.recent")),
            px + 4, py + 3, isFav ? 0xFFFFCC66 : 0xFFAAAAAA, false);

        int gridX = px + 4, gridY = py + headerH;

        // +/-/reset buttons in header row (favorites only)
        if (isFav) {
            int btnS = 12, bx = px + BTN_X, by = py + 1;
            boolean hasSel = selectedFavIdx >= 0 && selectedFavIdx < colors.size();
            int mBg = hasSel ? 0xFF5A2A2A : 0xFF3A3028, mFg = hasSel ? 0xFFFF8888 : 0xFF666666;
            g.fill(bx, by, bx + btnS, by + btnS, mBg);
            g.renderOutline(bx, by, btnS, btnS, mFg);
            g.drawString(font, "-", bx + 3, by + 1, mFg, false);
            bx += btnS + 2;
            g.fill(bx, by, bx + btnS, by + btnS, 0xFF2A4A2A);
            g.renderOutline(bx, by, btnS, btnS, 0xFF5A8A3A);
            g.drawString(font, "+", bx + 3, by + 1, 0xFF88FF88, false);
            bx += btnS + 2;
            g.fill(bx, by, bx + btnS, by + btnS, 0xFF2A3A4A);
            g.renderOutline(bx, by, btnS, btnS, 0xFF4A6A8A);
            g.drawString(font, "↺", bx + 1, by + 1, 0xFF88AAFF, false);
        }
        for (int i = 0; i < colors.size(); i++) {
            int row = i / GRID_COLS, col = i % GRID_COLS;
            int sx = gridX + col * CELL;
            int sy = gridY + row * CELL - off;
            if (sy + SWATCH <= gridY || sy >= gridY + gridH) continue;
            g.fill(sx, sy, sx + SWATCH, sy + SWATCH, colors.get(i));
            boolean hover = hit(mx, my, sx, sy, SWATCH, SWATCH);
            boolean sel = isFav && i == selectedFavIdx;
            int border = sel ? 0xFFD4A017 : (hover ? 0xFFFFAA44 : 0xFF555555);
            g.renderOutline(sx, sy, SWATCH, SWATCH, border);
            if ((colors.get(i) & 0x00FFFFFF) == (currentArgb() & 0x00FFFFFF))
                g.renderOutline(sx + 1, sy + 1, SWATCH - 2, SWATCH - 2, 0xFFD4A017);
        }

        if (isFav && maxOff > 0) {
            int sbX = gridX + GRID_W + 2;
            g.fill(sbX, gridY, sbX + SCROLLBAR_W, gridY + gridH, 0xFF333333);
            float ratio = (float)gridH / (totalRows * CELL);
            int thumbH = Math.max(8, (int)(gridH * ratio));
            int thumbY = gridY + (int)((float)off / maxOff * (gridH - thumbH));
            g.fill(sbX + 1, thumbY, sbX + SCROLLBAR_W - 1, thumbY + thumbH, 0xFF888888);
            g.renderOutline(sbX, gridY, SCROLLBAR_W, gridH, 0xFF555555);
        }

        if (isFav) favScrollRow = off / CELL;
    }

    // ── Hex ──

    private void onHexChanged(String s) {
        if (updatingHexFromPicker || s.isEmpty()) return;
        try {
            String hex = s.trim();
            if (hex.length() == 6) hex = "FF" + hex;
            if (hex.length() != 8) return;
            int color = (int)(Long.parseLong(hex, 16) & 0xFFFFFFFFL);
            float[] hsb = ColorUtils.rgbToHsb(color);
            if (!Float.isNaN(hsb[0])) {
                hue = hsb[0]; saturation = hsb[1]; brightness = hsb[2];
                alpha = ColorUtils.alpha(color);
                if (liveUpdate != null) liveUpdate.accept(color);
            }
        } catch (NumberFormatException ignored) {}
    }

    // ── Mouse ──

    public boolean mouseClicked(double mx, double my, int btn) {
        if (!visible) return false;
        // Always consume clicks inside bounds, even non-left-click
        if (inBounds(mx, my)) {
            if (btn != 0) return true; // absorb right-clicks etc.
        } else {
            close();
            return true;
        }
        if (btn != 0) return false;
        int px = screenX, py = screenY;
        if (showErase && hit(mx, my, px + ERASE_X, py + BOTTOM_Y, ERASE_W, ERASE_H)) {
            alpha = 0; syncHexFromHsv();
            if (liveUpdate != null) liveUpdate.accept(currentArgb());
            return true;
        }
        if (hit(mx, my, px + OK_X, py + BOTTOM_Y, OK_W, OK_H)) { confirm(); return true; }
        if (handleGridClick(mx, my, px, py + FAV_Y, true)) return true;
        if (handleGridClick(mx, my, px, py + REC_Y, false)) return true;

        // Alpha bar (vertical)
        if (hit(mx, my, px + ALPHA_X - 2, py + ALPHA_Y, ALPHA_W + 4, ALPHA_H)) {
            draggingAlpha = true; updateAlphaFromMouse(my); return true;
        }
        // Hue bar
        if (hit(mx, my, px + HUE_X - 3, py + HUE_Y, HUE_W + 6, HUE_H)) {
            draggingHue = true; updateHueFromMouse(my); return true;
        }
        // SV plane
        if (hit(mx, my, px + SV_X, py + SV_Y, SV_SIZE, SV_SIZE)) {
            draggingSV = true; updateSVFromMouse(mx, my); return true;
        }
        hexInput.mouseClicked(mx, my, btn);
        return true;
    }

    private boolean handleGridClick(double mx, double my, int px, int py, boolean isFav) {
        List<Integer> colors = isFav ? RecentColors.getFavorites() : RecentColors.getRecents();
        int cols = isFav ? GRID_COLS - 3 : GRID_COLS;
        int totalRows = (colors.size() + cols - 1) / cols;
        int visibleRows = isFav ? FAV_VISIBLE_ROWS : REC_VISIBLE_ROWS;
        int headerH = isFav ? FAV_HEADER_H : REC_HEADER_H;
        int gridH = visibleRows * CELL;
        int off = isFav ? favScrollRow * CELL : 0;
        int maxOff = Math.max(0, totalRows * CELL - gridH);
        int gridX = px + 4, gridY = py + headerH;

        // +/-/reset buttons in header (favorites only)
        if (isFav) {
            int btnS = 12, bx = px + BTN_X, by = py + 1;
            if (hit(mx, my, bx, by, btnS, btnS)) {
                if (selectedFavIdx >= 0 && selectedFavIdx < colors.size()) {
                    RecentColors.removeFavorite(colors.get(selectedFavIdx)); selectedFavIdx = -1;
                }
                return true;
            }
            bx += btnS + 2;
            if (hit(mx, my, bx, by, btnS, btnS)) { RecentColors.addFavorite(currentArgb()); return true; }
            bx += btnS + 2;
            if (hit(mx, my, bx, by, btnS, btnS)) { RecentColors.resetFavorites(); selectedFavIdx = -1; return true; }
        }

        // Scrollbar
        if (isFav && maxOff > 0 && hit(mx, my, gridX + GRID_W + 2, gridY, SCROLLBAR_W, gridH)) {
            draggingFavSb = true;
            updateFavScroll(my);
            return true;
        }

        // Swatches
        for (int i = 0; i < colors.size(); i++) {
            int row = i / cols, col = i % cols;
            int sx = gridX + col * CELL, sy = gridY + row * CELL - off;
            if (hit(mx, my, sx, sy, SWATCH, SWATCH)) {
                if (isFav) selectedFavIdx = (selectedFavIdx == i) ? -1 : i;
                int c = colors.get(i);
                float[] hs = ColorUtils.rgbToHsb(c);
                hue = hs[0]; saturation = hs[1]; brightness = hs[2];
                alpha = ColorUtils.alpha(c);
                syncHexFromHsv();
                if (!isFav) confirm();
                return true;
            }
        }
        return false;
    }

    private void updateFavScroll(double my) {
        List<Integer> colors = RecentColors.getFavorites();
        int cols = GRID_COLS - 3;
        int totalRows = (colors.size() + cols - 1) / cols;
        int gridH = FAV_VISIBLE_ROWS * CELL;
        int maxOff = Math.max(0, totalRows * CELL - gridH);
        if (maxOff == 0) return;
        int gridY = screenY + FAV_Y + FAV_HEADER_H;
        float t = Mth.clamp((float)(my - gridY) / gridH, 0f, 1f);
        favScrollRow = Mth.clamp(Math.round(t * totalRows), 0, maxOff / CELL);
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (!visible) return false;
        if (draggingSV)    { updateSVFromMouse(mx, my); return true; }
        if (draggingHue)   { updateHueFromMouse(my); return true; }
        if (draggingAlpha) { updateAlphaFromMouse(my); return true; }
        if (draggingFavSb) { updateFavScroll(my); return true; }
        return false;
    }

    /** Mouse wheel: scroll favorites grid. */
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!visible) return false;
        // Check if mouse is over favorites grid area
        int gridX = screenX + 4;
        int gridY = screenY + FAV_Y + FAV_HEADER_H;
        int gridH = FAV_VISIBLE_ROWS * CELL;
        if (mx >= gridX && mx < gridX + GRID_W && my >= gridY && my < gridY + gridH) {
            List<Integer> colors = RecentColors.getFavorites();
            int fcols = GRID_COLS - 3;
            int totalRows = (colors.size() + fcols - 1) / fcols;
            int maxRow = Math.max(0, totalRows - FAV_VISIBLE_ROWS);
            if (maxRow > 0) {
                favScrollRow = Mth.clamp(favScrollRow - (int)Math.signum(delta), 0, maxRow);
            }
            return true;
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        boolean was = draggingSV || draggingHue || draggingAlpha || draggingFavSb;
        draggingSV = draggingHue = draggingAlpha = draggingFavSb = false;
        return was;
    }

    // ── Keyboard ──

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!visible) return false;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (hexInput.isFocused()) onHexChanged(hexInput.getValue());
            confirm(); return true;
        }
        return hexInput.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char ch, int mod) {
        if (!visible) return false;
        return hexInput.charTyped(ch, mod);
    }

    // ── Helpers ──

    private static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
    private boolean inBounds(double mx, double my) { return hit(mx, my, screenX, screenY, WIDTH, HEIGHT); }
    private void updateSVFromMouse(double mx, double my) {
        saturation = Mth.clamp((float)(mx - screenX - SV_X) / SV_SIZE, 0f, 1f);
        brightness  = Mth.clamp(1f - (float)(my - screenY - SV_Y) / SV_SIZE, 0f, 1f);
        syncHexFromHsv();
    }
    private void updateHueFromMouse(double my) {
        hue = Mth.clamp((float)(my - screenY - HUE_Y) / HUE_H, 0f, 1f);
        syncHexFromHsv();
    }
    private void updateAlphaFromMouse(double my) {
        alpha = 255 - Mth.clamp((int)((float)(my - screenY - ALPHA_Y) / ALPHA_H * 255f), 0, 255);
        syncHexFromHsv();
    }
    private void syncHexFromHsv() {
        updatingHexFromPicker = true;
        hexInput.setValue(ColorUtils.hex8(currentArgb()));
        updatingHexFromPicker = false;
        if (liveUpdate != null) liveUpdate.accept(currentArgb());
    }
    private int currentArgb() {
        return ColorUtils.setAlpha(ColorUtils.hsbToRgb(hue, saturation, brightness), alpha);
    }
    private void confirm() {
        int color = currentArgb();
        RecentColors.addRecent(color);
        if (onSelect != null) onSelect.accept(color);
        close();
    }
}
