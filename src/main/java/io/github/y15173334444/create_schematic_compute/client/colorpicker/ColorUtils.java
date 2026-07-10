package io.github.y15173334444.create_schematic_compute.client.colorpicker;

import net.minecraft.util.Mth;

/**
 * Pure Java color conversion utilities — no dependency on java.awt.Color.
 * All methods are static and allocation-free where possible.
 */
public final class ColorUtils {

    private ColorUtils() {}

    // ── Hex formatting / parsing ──

    /** Format an ARGB int as an 8-char uppercase hex string (AARRGGBB). */
    public static String hex8(int argb) {
        return String.format("%08X", argb);
    }

    /**
     * Parse a hex color string to ARGB int.
     * Accepts "RRGGBB", "AARRGGBB", "#RRGGBB", "#AARRGGBB".
     * Returns 0xFF000000 (opaque black) on parse failure.
     */
    public static int parseHex(String hex) {
        if (hex == null || hex.isEmpty()) return 0xFF000000;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 6) s = "FF" + s;
        if (s.length() != 8) return 0xFF000000;
        try {
            return (int) (Long.parseLong(s, 16) & 0xFFFFFFFFL);
        } catch (NumberFormatException e) {
            return 0xFF000000;
        }
    }

    // ── Channel accessors ──

    public static int alpha(int argb) { return (argb >>> 24) & 0xFF; }
    public static int red(int argb)   { return (argb >> 16) & 0xFF; }
    public static int green(int argb) { return (argb >> 8) & 0xFF; }
    public static int blue(int argb)  { return argb & 0xFF; }

    public static int argb(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static int setAlpha(int rgb, int alpha) {
        return (rgb & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    // ── HSB ↔ ARGB conversion (pure Java, no java.awt) ──

    /**
     * Convert HSB (hue [0,1], saturation [0,1], brightness [0,1]) to opaque ARGB int.
     * Implements the same algorithm as java.awt.Color.HSBtoRGB.
     */
    public static int hsbToRgb(float hue, float sat, float bri) {
        int r = 0, g = 0, b = 0;
        if (sat <= 0.0f) {
            r = g = b = (int) (bri * 255.0f + 0.5f);
        } else {
            float h = (hue - (float) Math.floor(hue)) * 6.0f;
            int sector = (int) Math.floor(h);
            float f = h - sector;
            float p = bri * (1.0f - sat);
            float q = bri * (1.0f - sat * f);
            float t = bri * (1.0f - sat * (1.0f - f));
            switch (sector) {
                case 0: r = (int) (bri * 255.0f + 0.5f); g = (int) (t * 255.0f + 0.5f);   b = (int) (p * 255.0f + 0.5f); break;
                case 1: r = (int) (q * 255.0f + 0.5f);   g = (int) (bri * 255.0f + 0.5f); b = (int) (p * 255.0f + 0.5f); break;
                case 2: r = (int) (p * 255.0f + 0.5f);   g = (int) (bri * 255.0f + 0.5f); b = (int) (t * 255.0f + 0.5f); break;
                case 3: r = (int) (p * 255.0f + 0.5f);   g = (int) (q * 255.0f + 0.5f);   b = (int) (bri * 255.0f + 0.5f); break;
                case 4: r = (int) (t * 255.0f + 0.5f);   g = (int) (p * 255.0f + 0.5f);   b = (int) (bri * 255.0f + 0.5f); break;
                case 5: r = (int) (bri * 255.0f + 0.5f); g = (int) (p * 255.0f + 0.5f);   b = (int) (q * 255.0f + 0.5f); break;
            }
        }
        return 0xFF000000 | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    /**
     * Convert an ARGB int to HSB float array: [hue, saturation, brightness].
     * Hue/saturation return 0 for black (brightness == 0).
     */
    public static float[] rgbToHsb(int argb) {
        int r = red(argb), g = green(argb), b = blue(argb);
        int cmax = Math.max(r, Math.max(g, b));
        int cmin = Math.min(r, Math.min(g, b));
        float delta = cmax - cmin;

        float bri = cmax / 255.0f;
        float sat = (cmax == 0) ? 0.0f : delta / (float) cmax;
        float hue = 0.0f;

        if (delta > 0) {
            if (cmax == r) {
                hue = ((float) (g - b) / delta + (g < b ? 6.0f : 0.0f)) / 6.0f;
            } else if (cmax == g) {
                hue = ((float) (b - r) / delta + 2.0f) / 6.0f;
            } else {
                hue = ((float) (r - g) / delta + 4.0f) / 6.0f;
            }
        }

        return new float[] { hue, sat, bri };
    }

    // ── Linear interpolation between two ARGB colors (in RGB space) ──

    public static int lerpRgb(int colorA, int colorB, float t) {
        float ti = 1.0f - t;
        int a = Mth.clamp((int) (alpha(colorA) * ti + alpha(colorB) * t), 0, 255);
        int r = Mth.clamp((int) (red(colorA) * ti + red(colorB) * t), 0, 255);
        int g = Mth.clamp((int) (green(colorA) * ti + green(colorB) * t), 0, 255);
        int b = Mth.clamp((int) (blue(colorA) * ti + blue(colorB) * t), 0, 255);
        return argb(a, r, g, b);
    }
}
