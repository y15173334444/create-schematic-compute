package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 空公式 NBT 往返回归（实测 2026-11-21：全量同步把空公式节点重置回构造默认 "A+B"）：
 * `save` 曾按 `!formula.isEmpty()` 跳键——空串不落盘，load 的 `contains("formula")` 不命中
 * → 构造默认 "A+B" 回来，且与如实落盘的 din/dout（0/1）错位、首 parse 后 face 弹回 2。
 * 触发面 = 任何 NBT 往返（存档重载 / 全量同步——改名迁移的冲突旗标变化会 requestFullSync，
 * 实测正是这个链把重置带出来的）。修复 = FORMULA 节点恒写 formula 键（含空串）。
 * The empty-formula NBT round-trip regression (measured live 2026-11-21: a full sync reset an
 * emptied formula node to the constructor default "A+B"): `save` skipped the key for empty
 * strings, so load's `contains("formula")` missed and the default came back — mismatched with
 * the faithfully saved din/dout (0/1), the face snapping back to 2 on the first parse. The
 * trigger is any NBT round-trip (save reload / full sync — a rename migration's conflict-flag
 * change calls requestFullSync, which is exactly the chain that surfaced it). Fix = FORMULA
 * nodes always write the formula key (empty string included).
 */
class FormulaEmptyRoundTripTest {

    @Test
    @DisplayName("空公式往返：formula 保持空串，din/dout 原样，不回默认 A+B")
    void emptyFormulaSurvivesRoundTrip() {
        NodeGraph g = new NodeGraph();
        GraphNode f = g.addNode(NodeType.FORMULA, 0f, 0f);
        f.formula = "";
        f.dynamicInputCount = 0;
        f.dynamicOutputCount = 1;
        f.outputLabels = new ArrayList<>(List.of(""));

        var tag = g.save(null);
        NodeGraph back = NodeGraph.load(tag, null);
        GraphNode fb = back.findNode(f.id);
        assertNotNull(fb);
        assertEquals("", fb.formula, "the emptied formula must round-trip as empty (no key-drop → default)");
        assertEquals(0, fb.dynamicInputCount, "the pin face rides din verbatim");
        assertEquals(1, fb.dynamicOutputCount);
    }

    @Test
    @DisplayName("非空公式与 din/dout 往返形状不变（回归对照）")
    void nonEmptyFormulaRoundTripUnchanged() {
        NodeGraph g = new NodeGraph();
        GraphNode f = g.addNode(NodeType.FORMULA, 0f, 0f);
        f.formula = "a+b";
        f.dynamicInputCount = 2;
        f.dynamicOutputCount = 1;
        f.outputLabels = new ArrayList<>(List.of(""));

        var tag = g.save(null);
        NodeGraph back = NodeGraph.load(tag, null);
        GraphNode fb = back.findNode(f.id);
        assertEquals("a+b", fb.formula);
        assertEquals(2, fb.dynamicInputCount);
    }

    @Test
    @DisplayName("非公式节点不写 formula 键（既有形状不漂移）")
    void nonFormulaNodesStayKeyless() {
        NodeGraph g = new NodeGraph();
        g.addNode(NodeType.CONST, 0f, 0f);
        var tag = g.save(null);
        var nl = tag.getList("nodes", net.minecraft.nbt.Tag.TAG_COMPOUND);
        assertEquals(1, nl.size());
        assertFalse(nl.getCompound(0).contains("formula"),
            "non-formula nodes keep the keyless shape (no drift from the fix)");
    }
}
