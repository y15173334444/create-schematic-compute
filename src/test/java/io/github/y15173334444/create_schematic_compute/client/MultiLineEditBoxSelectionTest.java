package io.github.y15173334444.create_schematic_compute.client;

import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多行编辑框选区契约回归：键入/回车**替换选区**、删除键吃掉选区、锚点与父类 highlightPos
 * 单一来源（Ctrl+C/X 读父类 getHighlighted()，两处锚点曾分叉拷出「光标→文末」幽灵区间）。
 * Multi-line edit-box selection contract: typing / Enter **replace the selection**, the
 * Delete keys consume it, and the anchor has a single source (the parent's highlightPos —
 * Ctrl+C/X read getHighlighted(), and the two anchors once diverged, copying a phantom
 * caret→end range).
 */
class MultiLineEditBoxSelectionTest {

    // ── 无头 GUI 地基（Minecraft 僵尸实例 + stub FontSet，与 EditPanelUploadRoundTripTest 同款）──
    //     Headless GUI foundation (zombie Minecraft + stub FontSet, same as EditPanelUploadRoundTripTest)

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
                    @Override public float getAdvance() { return 6f; }
                    @Override public net.minecraft.client.gui.font.glyphs.BakedGlyph bake(
                        java.util.function.Function<com.mojang.blaze3d.font.SheetGlyphInfo,
                            net.minecraft.client.gui.font.glyphs.BakedGlyph> baker) { return null; }
                };
            }
        };
    }

    private static MultiLineEditBox box(String text) {
        var b = new MultiLineEditBox(Minecraft.getInstance().font, 0, 0, 200, 60);
        b.setValue(text);
        return b;
    }

    // ── 用例 / cases ─────────────────────────────────────────────────────

    @Test
    @DisplayName("键入替换选区（原版契约）：选中一段打字 = 替换，不是插到选区尾")
    void typingReplacesActiveSelection() {
        var b = box("hello world");
        b.setCursorPosition(11);   // caret at the end of "world"
        b.setHighlightPos(6);      // select "world" (anchor 6..caret 11)
        b.insertText("x");
        assertEquals("hello x", b.getValue(), "the selection must be replaced, not preserved");
        assertEquals(7, b.getCursorPosition());
        assertEquals(7, b.getSelectionAnchor(), "anchor collapses to the caret after typing");
    }

    @Test
    @DisplayName("无选区时键入照常插入光标处")
    void typingWithoutSelectionInsertsAtCaret() {
        var b = box("ac");
        b.setCursorPosition(1);
        b.setHighlightPos(1);
        b.insertText("b");
        assertEquals("abc", b.getValue());
        assertEquals(2, b.getCursorPosition());
        assertEquals(2, b.getSelectionAnchor());
    }

    @Test
    @DisplayName("Delete / Backspace 都吃掉整个选区（锚点在光标前/后同语义）")
    void deleteKeysConsumeTheWholeSelection() {
        var del = box("hello world");
        del.setCursorPosition(11);
        del.setHighlightPos(6);
        del.deleteText(1); // Delete key
        assertEquals("hello ", del.getValue());
        assertEquals(6, del.getCursorPosition());

        var back = box("hello world");
        back.setCursorPosition(6);
        back.setHighlightPos(11); // anchor after the caret — same span
        back.deleteText(-1); // Backspace key
        assertEquals("hello ", back.getValue());
        assertEquals(6, back.getCursorPosition());
    }

    @Test
    @DisplayName("锚点单一来源：父类 getHighlighted()（Ctrl+C/X）读到的必须是画出来的选区")
    void anchorMirrorsParentHighlightSoCopyReadsTheDrawnRange() {
        var b = box("hello world");
        b.setCursorPosition(11);
        b.setHighlightPos(6);
        assertEquals("world", b.getHighlighted(), "Ctrl+C must copy exactly the drawn selection");
        b.insertText("x");
        assertTrue(b.getHighlighted().isEmpty(), "no phantom range may survive a keystroke");
    }

    @Test
    @DisplayName("回车同样替换选区（与键入同一条 insertText 路径）")
    void enterReplacesActiveSelection() {
        var b = box("hello world");
        b.setFocused(true);
        b.setCursorPosition(11);
        b.setHighlightPos(6);
        b.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);
        assertEquals("hello \n", b.getValue(), "Enter replaces the selection like any typed char");
        assertEquals(7, b.getCursorPosition());
        assertEquals(7, b.getSelectionAnchor());
    }
}
