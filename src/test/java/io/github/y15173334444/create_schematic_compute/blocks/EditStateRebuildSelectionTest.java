package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 面板重建选区回归：打字的 op 回声走重建路径（`NodeEditStateFactory.create` 换掉输入框），
 * 重建必须把光标与选区锚点**成对**还原。只还原光标会把锚点留在 setValue 归位的文末，
 * 渲染出「光标→文末」幽灵选区——下一个 Backspace/Delete 整段删掉（用户症状：删除/输入时
 * 部分内容被全选）。
 * Panel-rebuild selection regression: the op echo of typing rides the rebuild path
 * (NodeEditStateFactory.create replaces the boxes), and a rebuild must restore caret and
 * selection anchor **as a pair**. Restoring only the caret leaves the anchor at the end
 * where setValue parked it, rendering a phantom caret→end selection — the next
 * Backspace/Delete wipes that span (the reported symptom: part of the content becomes
 * all-selected while deleting/typing).
 */
class EditStateRebuildSelectionTest {

    // ── 无头 GUI 地基（与 EditPanelUploadRoundTripTest 同款）──────────────────
    //     Headless GUI foundation (same as EditPanelUploadRoundTripTest)

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

    /** 捕获 sendOp 的 Host 桩。 / Host stub capturing every sendOp. */
    private static final class StubHost implements GraphEditor.Host {
        final NodeGraph graph;
        final List<io.github.y15173334444.create_schematic_compute.graph.GraphOp> sent = new ArrayList<>();
        StubHost(NodeGraph graph) { this.graph = graph; }
        @Override public NodeGraph getGraph() { return graph; }
        @Override public void saveGraph() {}
        @Override public void toggleRunning(boolean start) {}
        @Override public boolean isRunning() { return false; }
        @Override public net.minecraft.client.gui.screens.Screen asScreen() { return null; }
        @Override public void sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp op) { sent.add(op); }
    }

    private static MultiLineEditBox mleOf(GraphEditor.EditState st) {
        for (var f : st.fields)
            if (f instanceof MultiLineEditBox m) return m;
        throw new AssertionError("edit state has no multi-line editor");
    }

    // ── 用例 / cases ─────────────────────────────────────────────────────

    @Test
    @DisplayName("重建成对还原光标+锚点：选中区不被换成「光标→文末」幽灵选区")
    void rebuildRestoresCaretAndAnchorAsAPair() {
        var host = new StubHost(new NodeGraph());
        var ed = new GraphEditor(host, null);
        var node = host.graph.addNode(NodeType.FORMULA, 0, 0);
        node.formula = "alpha = beta + gamma";
        var st1 = NodeEditStateFactory.create(ed, node);
        ed.nodeEditStatesById.put(node.id, st1); // create() 从这里读旧状态 / create() reads the old state from here
        var mle1 = mleOf(st1);
        mle1.setFocused(true);
        mle1.setCursorPosition(12);
        mle1.setHighlightPos(8); // 用户正选中 "beta"（8..12）/ the user has "beta" selected

        var st2 = NodeEditStateFactory.create(ed, node); // op 回声 → 重建 / op echo → rebuild
        var mle2 = mleOf(st2);
        assertTrue(mle2.isFocused(), "rebuild must not steal focus");
        assertEquals(12, mle2.getCursorPosition(), "caret restored");
        assertEquals(8, mle2.getSelectionAnchor(), "anchor restored as a pair with the caret");
    }

    @Test
    @DisplayName("无选区重建后锚点仍在光标处：不得凭空出现选区")
    void rebuildWithoutSelectionLeavesNoPhantomSelection() {
        var host = new StubHost(new NodeGraph());
        var ed = new GraphEditor(host, null);
        var node = host.graph.addNode(NodeType.FORMULA, 0, 0);
        node.formula = "alpha = beta + gamma";
        var st1 = NodeEditStateFactory.create(ed, node);
        ed.nodeEditStatesById.put(node.id, st1);
        var mle1 = mleOf(st1);
        mle1.setFocused(true);
        mle1.setCursorPosition(5);
        mle1.setHighlightPos(5); // 纯光标，无选区 / plain caret, no selection

        var mle2 = mleOf(NodeEditStateFactory.create(ed, node));
        assertEquals(5, mle2.getCursorPosition());
        assertEquals(5, mle2.getSelectionAnchor(), "anchor must stay at the caret — no phantom selection");
    }

    @Test
    @DisplayName("对端缩短文本时锚点 clamp 到新长度（不越界）")
    void rebuildClampsAnchorWhenTextShrinks() {
        var host = new StubHost(new NodeGraph());
        var ed = new GraphEditor(host, null);
        var node = host.graph.addNode(NodeType.FORMULA, 0, 0);
        node.formula = "0123456789abcdefghij"; // 20 chars
        var st1 = NodeEditStateFactory.create(ed, node);
        ed.nodeEditStatesById.put(node.id, st1);
        var mle1 = mleOf(st1);
        mle1.setFocused(true);
        mle1.setCursorPosition(3);
        mle1.setHighlightPos(20); // 选区尾在文末之外的未来位置 / selection tail at a position the future text no longer has

        node.formula = "shorter"; // 7 chars — 对端改短 / a peer shortened it
        var mle2 = mleOf(NodeEditStateFactory.create(ed, node));
        assertEquals(3, mle2.getCursorPosition(), "caret within the new text is restored");
        assertEquals(7, mle2.getSelectionAnchor(), "anchor clamped to the new length");
    }
}
