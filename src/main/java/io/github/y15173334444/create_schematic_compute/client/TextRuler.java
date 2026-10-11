package io.github.y15173334444.create_schematic_compute.client;

import net.minecraft.client.gui.Font;

/**
 * 编辑控件的**浮点**文本测量——光标/选区/鼠标命中/绘制落点共用的单一坐标模型。
 * <p>
 * 测量走 {@link Font#getSplitter()}（原版测宽缝）：字体模组（如 ModernUI）重定向的正是
 * 这条缝，测量与它们的绘制同口径。绝不能用 {@code font.width()} 做逐段累加——它是
 * {@code Mth.ceil(stringWidth())} 的整数，而 ModernUI 一类模组的字形间距是浮点（HarfBuzz
 * 整形），语法高亮逐 token 落笔时逐段 ceil 累积会把整行系统性拉宽，光标与实际字符错位。
 * <p>
 * Float text measurement for edit widgets — the single coordinate model shared by the
 * caret, selection, mouse hit-testing and draw positions. Measurement rides
 * {@link Font#getSplitter()} (the vanilla width seam) — exactly the seam font mods such as
 * ModernUI redirect, so measuring matches their drawing. Never accumulate
 * {@code font.width()} across segments: it is {@code Mth.ceil(stringWidth())}, while mods
 * like ModernUI lay out fractional advances (HarfBuzz-shaped) — accumulating ceiled widths
 * per syntax token systematically stretches the line and misaligns the caret.
 */
public final class TextRuler {

    private TextRuler() {}

    /** 整串宽度（浮点，与绘制同口径）/ width of a whole string (float, same yardstick as drawing). */
    public static float width(Font font, String s) {
        if (s == null || s.isEmpty()) return 0f;
        return font.getSplitter().stringWidth(s);
    }

    /** 字符偏移 {@code charCount} 处的 x（浮点）——光标、选区边界、绘制落点的共同公式。
     *  The x at character offset {@code charCount} (float) — the shared formula for the
     *  caret, selection edges and draw positions. */
    public static float prefixWidth(Font font, String s, int charCount) {
        if (charCount <= 0) return 0f;
        String full = s == null ? "" : s;
        return width(font, charCount >= full.length() ? full : full.substring(0, charCount));
    }

    /** 逆映射：x → 字符下标（鼠标命中）。取最后一个前缀宽 ≤ x 的下标。
     *  Inverse mapping: x → character index (mouse hit-testing) — the last index whose
     *  prefix width is ≤ x. */
    public static int charIndexAt(Font font, String s, float x) {
        if (s == null || s.isEmpty() || x <= 0f) return 0;
        int best = 0;
        for (int c = 1; c <= s.length(); c++) {
            if (prefixWidth(font, s, c) <= x) best = c;
            else break;
        }
        return best;
    }
}
