package io.github.y15173334444.create_schematic_compute.client.colorpicker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Compact color picker: SV plane + hue bar + alpha bar (vertical, beside hue).
 * Favorites grid with vertical scroll, recent grid (no scroll).
 *
 * <p>Hit detection uses a spatial index ({@link HitRegion}) so every interactive
 * element registers its own bounding box — no element can be shadowed by another's
 * quick-reject guard.</p>
 */
public class ColorPickerWidget {

    // ── Spatial index types ──

    /** Every interactive region in the widget. */
    private enum RegionType {
        SV_PLANE, HUE_BAR, ALPHA_BAR,
        HEX_INPUT, ERASE_BTN, OK_BTN,
        FAV_MINUS, FAV_PLUS, FAV_RESET,
        FAV_SCROLLBAR, FAV_SWATCH,
        REC_SWATCH
    }

    /** A clickable region with absolute screen-space bounds. */
    private record HitRegion(int x, int y, int w, int h, RegionType type) {
        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

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
    // Swatch drag state
    private boolean draggingSwatch = false;
    private boolean dragSourceIsFav = false;
    private int dragSwatchIdx = -1;
    private int dragColor = 0;
    private double dragStartMx, dragStartMy;
    private boolean dragStarted = false;
    private static final int DRAG_THRESHOLD = 4;
    private Consumer<Integer> onSelect;
    private Consumer<Integer> liveUpdate; // fires on every HSV/alpha change
    private boolean persistent; // if true, OK/confirm doesn't close the picker
    private String feedbackText = "";
    private long feedbackUntil = 0;
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

    public void close() { visible = false; persistent = false; clearDragState();
        draggingSV = draggingHue = draggingAlpha = draggingFavSb = false; ColorPickerButton.clearSelection(); }

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
        // Feedback text — floats over the first row of favorites swatches
        if (System.currentTimeMillis() < feedbackUntil && !feedbackText.isEmpty()) {
            int fx = px + 4;
            int fy = py + FAV_Y + FAV_HEADER_H;
            int tw = font.width(feedbackText);
            // Dark pill background centered on the first swatch row
            g.fill(fx + (GRID_W - tw) / 2 - 3, fy + (CELL - font.lineHeight) / 2 - 1,
                   fx + (GRID_W + tw) / 2 + 3, fy + (CELL + font.lineHeight) / 2 + 1,
                   0xDD222222);
            g.drawString(font, feedbackText, fx + (GRID_W - tw) / 2, fy + (CELL - font.lineHeight) / 2 + 1, 0xFFFFAA44, false);
        }
        // Drag ghost — force full opacity and offset from cursor
        if (draggingSwatch) {
            int gx = (int)mx - 10, gy = (int)my - 20;
            // Checkerboard for semi-transparent ghost
            if ((dragColor >>> 24) < 0xFF) {
                for (int cy = 0; cy < 14; cy += 4)
                    for (int cx = 0; cx < 14; cx += 4)
                        g.fill(gx + cx, gy + cy,
                            gx + Math.min(cx + 4, 14), gy + Math.min(cy + 4, 14),
                            ((cx / 4 + cy / 4) & 1) == 0 ? 0xFFCCCCCC : 0xFF888888);
            }
            g.fill(gx, gy, gx + 14, gy + 14, dragColor);
            g.renderOutline(gx, gy, 14, 14, 0xFFFFAA44);
        }
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
            int a = 255 - (int)((float)y / (ALPHA_H - 1) * 255f);
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
        int totalRows = (colors.size() + GRID_COLS - 1) / GRID_COLS; // swatches use full 8-col layout
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
            int c = colors.get(i);
            // Checkerboard behind semi-transparent swatches
            if ((c >>> 24) < 0xFF) {
                for (int cy = 0; cy < SWATCH; cy += 4)
                    for (int cx = 0; cx < SWATCH; cx += 4)
                        g.fill(sx + cx, sy + cy,
                            sx + Math.min(cx + 4, SWATCH), sy + Math.min(cy + 4, SWATCH),
                            ((cx / 4 + cy / 4) & 1) == 0 ? 0xFFCCCCCC : 0xFF888888);
            }
            g.fill(sx, sy, sx + SWATCH, sy + SWATCH, c);
            boolean sel = isFav && i == selectedFavIdx;
            g.renderOutline(sx, sy, SWATCH, SWATCH, sel ? 0xFFD4A017 : 0xFF555555);
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

    // ── Spatial index builder ──

    /** Build every clickable region for the current widget position. */
    private List<HitRegion> buildHitRegions(int px, int py) {
        var list = new ArrayList<HitRegion>();
        // Bottom row (highest priority)
        list.add(new HitRegion(px + HEX_X, py + BOTTOM_Y, HEX_W, HEX_H, RegionType.HEX_INPUT));
        if (showErase) list.add(new HitRegion(px + ERASE_X, py + BOTTOM_Y, ERASE_W, ERASE_H, RegionType.ERASE_BTN));
        list.add(new HitRegion(px + OK_X, py + BOTTOM_Y, OK_W, OK_H, RegionType.OK_BTN));

        // Favorites header buttons
        int btnS = 12, bx = px + BTN_X, by = py + FAV_Y + 1;
        list.add(new HitRegion(bx, by, btnS, btnS, RegionType.FAV_MINUS));
        bx += btnS + 2;
        list.add(new HitRegion(bx, by, btnS, btnS, RegionType.FAV_PLUS));
        bx += btnS + 2;
        list.add(new HitRegion(bx, by, btnS, btnS, RegionType.FAV_RESET));

        // Favorites scrollbar
        List<Integer> favs = RecentColors.getFavorites();
        int favTotalRows = (favs.size() + GRID_COLS - 1) / GRID_COLS;
        int favGridH = FAV_VISIBLE_ROWS * CELL;
        int favMaxOff = Math.max(0, favTotalRows * CELL - favGridH);
        if (favMaxOff > 0) {
            int sbX = px + 4 + GRID_W + 2;
            int sbY = py + FAV_Y + FAV_HEADER_H;
            list.add(new HitRegion(sbX, sbY, SCROLLBAR_W, favGridH, RegionType.FAV_SCROLLBAR));
        }

        // Favorites swatch grid
        int favGridX = px + 4, favGridY = py + FAV_Y + FAV_HEADER_H;
        list.add(new HitRegion(favGridX, favGridY, GRID_W, favGridH, RegionType.FAV_SWATCH));

        // Recents swatch grid
        int recGridX = px + 4, recGridY = py + REC_Y + REC_HEADER_H;
        int recGridH = REC_VISIBLE_ROWS * CELL;
        list.add(new HitRegion(recGridX, recGridY, GRID_W, recGridH, RegionType.REC_SWATCH));

        // Alpha / Hue / SV (lowest priority — dragged, not clicked)
        list.add(new HitRegion(px + ALPHA_X - 2, py + ALPHA_Y, ALPHA_W + 4, ALPHA_H, RegionType.ALPHA_BAR));
        list.add(new HitRegion(px + HUE_X - 3, py + HUE_Y, HUE_W + 6, HUE_H, RegionType.HUE_BAR));
        list.add(new HitRegion(px + SV_X, py + SV_Y, SV_SIZE, SV_SIZE, RegionType.SV_PLANE));

        return list;
    }

    // ── Mouse ──

    public boolean mouseClicked(double mx, double my, int btn) {
        if (!visible) return false;
        // Always consume clicks inside bounds, even non-left-click
        if (inBounds(mx, my)) {
            if (btn != 0) return true;
        } else {
            close();
            return true;
        }
        if (btn != 0) return false;
        int px = screenX, py = screenY;

        // Spatial index lookup — find the first region containing the click
        for (var r : buildHitRegions(px, py)) {
            if (!r.contains(mx, my)) continue;
            switch (r.type()) {
                case HEX_INPUT -> {
                    hexInput.setX(px + HEX_X); hexInput.setY(py + BOTTOM_Y);
                    hexInput.setFocused(true);
                    hexInput.mouseClicked(mx, my, btn);
                    return true;
                }
                case ERASE_BTN -> {
                    alpha = 0; syncHexFromHsv();
                    if (liveUpdate != null) liveUpdate.accept(currentArgb());
                    return true;
                }
                case OK_BTN -> { confirm(); return true; }
                case FAV_MINUS -> {
                    var fc = RecentColors.getFavorites();
                    if (selectedFavIdx >= 0 && selectedFavIdx < fc.size()) {
                        RecentColors.removeFavorite(fc.get(selectedFavIdx));
                        selectedFavIdx = -1;
                    }
                    return true;
                }
                case FAV_PLUS -> {
                    if (!RecentColors.addFavorite(currentArgb())) {
                        feedbackText = I18n.get("gui.create_schematic_compute.colorpicker.fav_full");
                        feedbackUntil = System.currentTimeMillis() + 2000;
                    }
                    return true;
                }
                case FAV_RESET -> { RecentColors.resetFavorites(); selectedFavIdx = -1; return true; }
                case FAV_SCROLLBAR -> { draggingFavSb = true; updateFavScroll(my); return true; }
                case FAV_SWATCH -> {
                    if (handleGridClick(mx, my, px, py + FAV_Y, true)) return true;
                }
                case REC_SWATCH -> {
                    if (handleGridClick(mx, my, px, py + REC_Y, false)) return true;
                }
                case ALPHA_BAR -> { draggingAlpha = true; updateAlphaFromMouse(my); return true; }
                case HUE_BAR   -> { draggingHue = true; updateHueFromMouse(my); return true; }
                case SV_PLANE   -> { draggingSV = true; updateSVFromMouse(mx, my); return true; }
            }
        }
        return true;
    }

    private long lastSwatchAutoScroll = 0;
    private static final int SWATCH_AUTOSCROLL_MS = 350;

    /**
     * Handle a click inside a swatch grid.  Buttons and scrollbar are dispatched
     * directly by the spatial index — this method only deals with swatch tiles.
     */
    private boolean handleGridClick(double mx, double my, int px, int py, boolean isFav) {
        List<Integer> colors = isFav ? RecentColors.getFavorites() : RecentColors.getRecents();
        int totalRows = (colors.size() + GRID_COLS - 1) / GRID_COLS;
        int visibleRows = isFav ? FAV_VISIBLE_ROWS : REC_VISIBLE_ROWS;
        int headerH = isFav ? FAV_HEADER_H : REC_HEADER_H;
        int gridH = visibleRows * CELL;
        int off = isFav ? favScrollRow * CELL : 0;
        int gridX = px + 4, gridY = py + headerH;

        // Quick reject: outside grid Y bounds (buttons live in the header row above and
        // are handled by their own spatial-index regions, so they never reach here).
        if (my < gridY || my > gridY + gridH) return false;

        // Swatches — use GRID_COLS layout
        for (int i = 0; i < colors.size(); i++) {
            int row = i / GRID_COLS, col = i % GRID_COLS;
            int sx = gridX + col * CELL, sy = gridY + row * CELL - off;
            if (hit(mx, my, sx, sy, SWATCH, SWATCH)) {
                dragSwatchIdx = i;
                dragSourceIsFav = isFav;
                dragColor = colors.get(i);
                dragStartMx = mx; dragStartMy = my;
                dragStarted = false;
                draggingSwatch = false;
                return true;
            }
        }
        return false;
    }

    /** Compute insertion index for a drop at (mx, my) on the given grid, or -1 if outside. */
    private int swatchIndexAt(double mx, double my, boolean isFav) {
        int px = screenX, py = screenY;
        int gridX = px + 4;
        int headerH = isFav ? FAV_HEADER_H : REC_HEADER_H;
        int gridY = (isFav ? FAV_Y : REC_Y) + py + headerH;
        List<Integer> colors = isFav ? RecentColors.getFavorites() : RecentColors.getRecents();
        int visibleRows = isFav ? FAV_VISIBLE_ROWS : REC_VISIBLE_ROWS;
        int gridH = visibleRows * CELL;
        if (mx < gridX || mx > gridX + GRID_W || my < gridY || my > gridY + gridH) return -1;
        int off = isFav ? favScrollRow * CELL : 0;
        int col = (int)((mx - gridX) / CELL);
        int row = (int)((my - gridY + off) / CELL);
        if (col < 0 || col >= GRID_COLS) return -1;
        int idx = row * GRID_COLS + col;
        return Math.min(idx, colors.size());
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
        // Start swatch drag if threshold exceeded
        if (dragSwatchIdx >= 0 && !dragStarted) {
            if (Math.abs(mx - dragStartMx) + Math.abs(my - dragStartMy) >= DRAG_THRESHOLD) {
                dragStarted = true; draggingSwatch = true;
            }
        }
        if (draggingSwatch) {
            // Throttled auto-scroll when dragging near edges (like iOS app reorder)
            long now = System.currentTimeMillis();
            if (now - lastSwatchAutoScroll >= SWATCH_AUTOSCROLL_MS) {
                List<Integer> favs = RecentColors.getFavorites();
                int totalRows2 = (favs.size() + GRID_COLS - 1) / GRID_COLS;
                int gridH2 = FAV_VISIBLE_ROWS * CELL;
                int maxRow = Math.max(0, totalRows2 - FAV_VISIBLE_ROWS);
                int favHeaderY = screenY + FAV_Y + FAV_HEADER_H;
                int recHeaderY = screenY + REC_Y + REC_HEADER_H;
                if (maxRow > 0 && my < favHeaderY && favScrollRow > 0) {
                    favScrollRow = Math.max(0, favScrollRow - 1);
                    lastSwatchAutoScroll = now;
                } else if (maxRow > 0 && my > recHeaderY && favScrollRow < maxRow) {
                    favScrollRow = Math.min(maxRow, favScrollRow + 1);
                    lastSwatchAutoScroll = now;
                }
            }
            return true;
        }
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
        if (mx >= gridX && mx < gridX + GRID_W + SCROLLBAR_W && my >= gridY && my < gridY + gridH) {
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
        // Handle swatch drag drop
        if (draggingSwatch) {
            int targetFav = swatchIndexAt(mx, my, true);
            int targetRec = swatchIndexAt(mx, my, false);
            if (targetFav >= 0) {
                if (dragSourceIsFav) {
                    // Fav → Fav: reorder within favorites
                    if (dragSwatchIdx != targetFav)
                        RecentColors.moveFavorite(dragSwatchIdx, targetFav);
                } else {
                    // Rec → Fav: move to favorites at target position
                    int removed = RecentColors.removeRecentByIndex(dragSwatchIdx);
                    if (!RecentColors.insertFavorite(targetFav, removed)) {
                        // Favorites full — put back and warn
                        RecentColors.insertRecent(dragSwatchIdx, removed);
                        feedbackText = I18n.get("gui.create_schematic_compute.colorpicker.fav_full");
                        feedbackUntil = System.currentTimeMillis() + 2000;
                    }
                }
            } else if (targetRec >= 0) {
                if (dragSourceIsFav) {
                    // Fav → Rec: move to recents at target position
                    int removed = RecentColors.removeFavoriteByIndex(dragSwatchIdx);
                    RecentColors.insertRecent(targetRec, removed);
                } else {
                    // Rec → Rec: reorder within recents
                    if (dragSwatchIdx != targetRec)
                        RecentColors.moveRecent(dragSwatchIdx, targetRec);
                }
            }
            clearDragState();
            return true;
        }
        // Handle short click on swatch (no drag)
        if (dragSwatchIdx >= 0 && !dragStarted) {
            List<Integer> colors = dragSourceIsFav ? RecentColors.getFavorites() : RecentColors.getRecents();
            if (dragSwatchIdx < colors.size()) {
                int c = colors.get(dragSwatchIdx);
                float[] hs = ColorUtils.rgbToHsb(c);
                hue = hs[0]; saturation = hs[1]; brightness = hs[2];
                alpha = ColorUtils.alpha(c);
                syncHexFromHsv();
                if (dragSourceIsFav) {
                    selectedFavIdx = (selectedFavIdx == dragSwatchIdx) ? -1 : dragSwatchIdx;
                }
            }
            clearDragState();
            return true;
        }
        clearDragState();
        boolean was = draggingSV || draggingHue || draggingAlpha || draggingFavSb;
        draggingSV = draggingHue = draggingAlpha = draggingFavSb = false;
        return was;
    }

    private void clearDragState() {
        draggingSwatch = false; dragSwatchIdx = -1;
        dragStarted = false; dragColor = 0;
    }

    // ── Keyboard ──

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!visible) return false;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { close(); return true; }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (hexInput.isFocused()) { onHexChanged(hexInput.getValue()); return true; }
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
        alpha = 255 - Mth.clamp((int)((float)(my - screenY - ALPHA_Y) / (ALPHA_H - 1) * 255f), 0, 255);
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
    /** Set whether OK/confirm keeps the picker open (for multi-swatch panels). */
    public void setPersistent(boolean p) { this.persistent = p; }

    private void confirm() {
        int color = currentArgb();
        RecentColors.addRecent(color);
        if (onSelect != null) onSelect.accept(color);
        if (!persistent) close();
    }
}
