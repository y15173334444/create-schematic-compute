package io.github.y15173334444.create_schematic_compute.client;

import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 浮点坐标模型回归（ModernUI 光标错位修复）：字体模组把字形间距改成浮点（HarfBuzz 整形）后，
 * {@code font.width()}（= {@code Mth.ceil(stringWidth())}）不能再用于逐段累加定位——
 * 光标/选区/命中/绘制落点必须共用 {@link TextRuler} 的浮点前缀宽。本类用小数步进（4.27px）
 * 的桩字形钉住：测量是浮点、与 ceil 值可区分、逆映射自洽。
 * Float coordinate-model regression (the ModernUI caret-drift fix): once a font mod lays
 * out fractional advances (HarfBuzz-shaped), {@code font.width()}
 * (= {@code Mth.ceil(stringWidth())}) must never be accumulated per segment — caret,
 * selection, hit-testing and draw positions all share {@link TextRuler}'s float prefix
 * widths. Pinned here with a fractional-advance stub glyph (4.27px): the measure is
 * fractional, distinguishable from the ceiled value, and the inverse mapping round-trips.
 */
class TextRulerTest {

    /** 桩字形步进：故意取非整数，模拟字体模组的浮点字距 / stub advance, deliberately non-integral */
    private static final float ADV = 4.27f;

    @BeforeAll
    static void bootstrapHeadlessGui() throws Exception {
        var mcClass = net.minecraft.client.Minecraft.class;
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var mc = unsafe.allocateInstance(mcClass);
        var font = new net.minecraft.client.gui.Font(rl -> stubFontSet(), false);
        unsafe.putObject(mc, unsafe.objectFieldOffset(mcClass.getField("font")), font);
        var instField = mcClass.getDeclaredField("instance");
        instField.setAccessible(true);
        instField.set(null, mc);
    }

    private static net.minecraft.client.gui.font.FontSet stubFontSet() {
        return new net.minecraft.client.gui.font.FontSet(null,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("csc_test", "stub")) {
            @Override public com.mojang.blaze3d.font.GlyphInfo getGlyphInfo(int codepoint, boolean bold) {
                return new com.mojang.blaze3d.font.GlyphInfo() {
                    @Override public float getAdvance() { return ADV; }
                    @Override public net.minecraft.client.gui.font.glyphs.BakedGlyph bake(
                        java.util.function.Function<com.mojang.blaze3d.font.SheetGlyphInfo,
                            net.minecraft.client.gui.font.glyphs.BakedGlyph> baker) { return null; }
                };
            }
        };
    }

    // ── 用例 / cases ─────────────────────────────────────────────────────

    @Test
    @DisplayName("测量是浮点：与 ceil 语义的 font.width 可区分（错位的根源就是混用两者）")
    void widthIsFractionalDistinguishableFromCeiled() {
        var font = Minecraft.getInstance().font;
        float w = TextRuler.width(font, "aaaa");
        assertEquals(4 * ADV, w, 0.001f, "float measure must be the plain advance sum");
        assertEquals((int) Math.ceil(w), font.width("aaaa"), "font.width is the ceiled value");
        assertNotEquals(w, (float) font.width("aaaa"),
            "the two yardsticks must be distinguishable under a fractional advance — that divergence was the drift");
    }

    @Test
    @DisplayName("前缀宽单调、逆映射自洽：charIndexAt(prefixWidth(k)) == k")
    void prefixWidthMonotonicAndInverseRoundTrips() {
        var font = Minecraft.getInstance().font;
        String s = "output = a + b";
        float prev = -1f;
        for (int k = 0; k <= s.length(); k++) {
            float x = TextRuler.prefixWidth(font, s, k);
            assertTrue(x > prev || k == 0, "prefix widths must be strictly increasing");
            prev = x;
            assertEquals(k, TextRuler.charIndexAt(font, s, x),
                "clicking at the caret x of column k must land on column k");
        }
    }

    @Test
    @DisplayName("命中取最后一个前缀宽 ≤ x 的下标（与点击落点语义一致）")
    void charIndexAtTakesLastPrefixWithinX() {
        var font = Minecraft.getInstance().font;
        String s = "abc";
        float x0 = TextRuler.prefixWidth(font, s, 1);
        float x1 = TextRuler.prefixWidth(font, s, 2);
        assertEquals(1, TextRuler.charIndexAt(font, s, x0 + (x1 - x0) / 2), "mid-gap lands on the earlier column");
        assertEquals(3, TextRuler.charIndexAt(font, s, TextRuler.width(font, s)), "x at line end lands past the last char");
        assertEquals(0, TextRuler.charIndexAt(font, s, -5f), "negative x clamps to the start");
    }

    @Test
    @DisplayName("光标定位走浮点模型：getCaretLocalXY 不再是 ceil 值（小数步进下两者相差 1px）")
    void caretUsesFloatMeasureNotCeiled() {
        var font = Minecraft.getInstance().font;
        var box = new MultiLineEditBox(font, 0, 0, 200, 60);
        box.setValue("aaaa");
        box.setCursorPosition(4);
        box.setHighlightPos(4);
        int expected = Math.round(2 + TextRuler.prefixWidth(font, "aaaa", 4)); // float model
        int ceiled = 2 + font.width("aaaa");                                   // the old formula
        assertNotEquals(expected, ceiled, "the stub advance must separate the two formulas");
        assertEquals(expected, box.getCaretLocalXY()[0], "caret must ride the float model");
    }
}
