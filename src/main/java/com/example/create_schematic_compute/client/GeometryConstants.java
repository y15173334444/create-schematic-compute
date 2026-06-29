package com.example.create_schematic_compute.client;

/**
 * Shared geometry and layout constants for the Create: Schematic Compute GUI.
 * Phase 3: eliminates duplicated magic numbers across the codebase.
 */
public final class GeometryConstants {

    // ── Font / world-space rendering ──
    /** World blocks per font-pixel (used to match 3D renderer and 2D GUI preview) */
    public static final float FONT_BLOCK_SCALE = 0.015f;
    /** Bezel margin in blocks on each edge of the monitor screen */
    public static final float BEZEL_MARGIN = 0.04f;
    /** IMAGE pixel cell size in blocks (= 2 font-pixels) */
    public static final float IMAGE_CELL_BLOCK = 0.03f;
    /** IMAGE pixel cell size in font-pixels */
    public static final float IMAGE_CELL_FONT = 2f;
    /** IMAGE grid dimension (always 16×16) */
    public static final int IMAGE_GRID = 16;

    // ── Node layout (graph editor) ──
    public static final int NODE_WIDTH = 140;
    public static final int NODE_WIDE_WIDTH = 240;
    public static final int NODE_HEADER_H = 18;
    public static final int NODE_PIN_H = 16;
    public static final int NODE_PIN_RADIUS = 4;
    public static final int NODE_GRID_SNAP = 30;

    // ── Monitor display ──
    public static final int MONITOR_MARGIN = 30;
    public static final int MONITOR_TOOLBAR_H = 20;
    public static final int MONITOR_SETTINGS_PANEL_W = 220;

    // ── Layer panel (right side of display mode) ──
    public static final int LAYER_PANEL_W = 108;        // was 64 — widened for thumbnails
    public static final int LAYER_ROW_H = 30;           // was 14 — taller for thumbnails
    public static final int LAYER_THUMB_SIZE = 24;      // thumbnail square dimension
    public static final int LAYER_THUMB_MARGIN = 4;     // gap between thumbnail and text
    public static final int LAYER_PANEL_PADDING = 4;    // inner padding from panel edge
    public static final int LAYER_DRAG_THRESHOLD = 5;   // pixels of mouse movement to start drag
    public static final int LAYER_AUTOSCROLL_ZONE = 24; // pixels from panel top/bottom for auto-scroll
    public static final int LAYER_AUTOSCROLL_TICK = 150;// ms between auto-scroll steps

    // ── Pixel editor ──
    public static final int PALETTE_CELL = 16;
    public static final int PALETTE_GAP = 2;
    public static final int PALETTE_LEFT = 8;
    public static final int PALETTE_COLS = 2;
    public static final int PALETTE_COLOR_COUNT = 23;
    /** 23-color palette (ARGB). Index 22 = transparent sentinel. */
    public static final int[] PIXEL_PALETTE = {
        0xFFFFFFFF, 0xFFCCCCCC, 0xFF888888, 0xFF444444, 0xFF000000,
        0xFFFF0000, 0xFFCC0000, 0xFFFF8800, 0xFF8B4513, 0xFFFFFF00,
        0xFF88FF00, 0xFF00FF00, 0xFF008800, 0xFF00FFFF, 0xFF008888,
        0xFF88CCFF, 0xFF0000FF, 0xFF000088, 0xFF8800FF, 0xFFFF00FF,
        0xFFFF88CC, 0xFF880044, 0x00000000
    };

    // ── Toolbar button layout ──
    public static final int TOOLBAR_BTN_H = 18;
    /** (x, width) pairs for the 5 toolbar buttons */
    public static final int[][] TOOLBAR_BUTTONS = {
        {4, 18}, {26, 52}, {82, 48}, {134, 58}, {196, 54}
    };

    // ── Shared geometry utilities ──

    /**
     * Clamp an IMAGE/IMAGE_SEQUENCE node's normalized position so its rotated
     * bounding box stays within [0,1]×[0,1]. Matches the 3D renderer's clamping.
     *
     * @param displayScale node's display scale
     * @param rawX       unclamped normalized X
     * @param rawY       unclamped normalized Y
     * @param rotation   rotation in degrees
     * @param screenW    effective screen width in blocks
     * @param screenL    effective screen length in blocks
     * @return {clampedX, clampedY}
     */
    public static float[] clampImageNorm(float displayScale, float rawX, float rawY,
                                          float rotation, float screenW, float screenL) {
        float cww = Math.max(screenW - 2 * BEZEL_MARGIN, 0.01f);
        float cwl = Math.max(screenL - 2 * BEZEL_MARGIN, 0.01f);
        float hw = 8f * IMAGE_CELL_BLOCK * displayScale;
        float hh = 8f * IMAGE_CELL_BLOCK * displayScale;
        float rA = (float) Math.abs(Math.cos(Math.toRadians(rotation)));
        float rB = (float) Math.abs(Math.sin(Math.toRadians(rotation)));
        float bbHalfW = (hw * rA + hh * rB) / cww;
        float bbHalfH = (hw * rB + hh * rA) / cwl;
        return new float[]{
            Math.max(0, Math.min(1 - bbHalfW, rawX)),
            Math.max(0, Math.min(1 - bbHalfH, rawY))
        };
    }

    /**
     * Compute the axis-aligned bounding box of a rotated rectangle.
     * Rotation is center-based.
     *
     * @return {minX, minY, maxX, maxY}
     */
    public static float[] elemRotAABB(float ex, float ey, float w, float h, float rot) {
        float hw = w / 2, hh = h / 2;
        float cx = ex + hw, cy = ey + hh;
        float mnX = Float.MAX_VALUE, mnY = Float.MAX_VALUE,
              mxX = -Float.MAX_VALUE, mxY = -Float.MAX_VALUE;
        float[] lx = {-hw, hw, hw, -hw}, ly = {-hh, -hh, hh, hh};
        if (rot != 0) {
            float rad = (float) Math.toRadians(rot),
                  c = (float) Math.cos(rad), s = (float) Math.sin(rad);
            for (int i = 0; i < 4; i++) {
                float rx = lx[i] * c - ly[i] * s, ry = lx[i] * s + ly[i] * c;
                mnX = Math.min(mnX, cx + rx); mxX = Math.max(mxX, cx + rx);
                mnY = Math.min(mnY, cy + ry); mxY = Math.max(mxY, cy + ry);
            }
        } else {
            mnX = ex; mxX = ex + w; mnY = ey; mxY = ey + h;
        }
        return new float[]{mnX, mnY, mxX, mxY};
    }

    private GeometryConstants() {}
}
