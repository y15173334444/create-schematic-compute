package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * issue #10 / #17 回归测试。
 * <ul>
 *   <li>#10：频道名提交必须由用户敲键标脏；程序装入的草稿不得回写。</li>
 *   <li>#17：编译复位（触发器当前态回归初始态）在服务端权威图上执行，含子图递归。</li>
 * </ul>
 * Regression tests for issues #10 / #17.
 */
class CompileResetAndBusNameGateTest {

    // ══════════ issue #10 — dirty gate / 脏标记门闩 ══════════

    @Test
    @DisplayName("only a user-keystroke dirty diff may commit / 仅用户敲键标脏的差异可提交")
    void testDirtyGate() {
        assertTrue(GraphBusEditor.shouldCommitBusName(true, "CH2", "CH1"),
            "user typed a new name → commit");
        assertFalse(GraphBusEditor.shouldCommitBusName(false, "CH2", "CH1"),
            "preserved draft (not keystroke) must never commit");
        assertFalse(GraphBusEditor.shouldCommitBusName(true, "CH1", "CH1"),
            "unchanged text is a no-op");
        assertFalse(GraphBusEditor.shouldCommitBusName(false, "CH1", "CH1"),
            "unchanged + clean is a no-op");
    }

    @Test
    @DisplayName("panel-rebuild preserve must not look like a keystroke / 面板重建保留草稿不得视作敲键")
    void testPreservedDraftIsNotDirty() {
        // 执行路径：工厂的装入/保留/响应器共用 GraphBusEditor 的脏标记策略。
        // Walk the exact #10 sequence through the policy the factory calls.
        // 1) create() 初值装入（suppress setValue）→ dirty=false
        boolean dirty = GraphBusEditor.dirtyAfterProgrammaticLoad();
        assertFalse(dirty, "create() initial load is clean");

        // 2) 面板重建保留草稿（suppress setValue）→ dirty 保持 false
        dirty = GraphBusEditor.dirtyAfterResponder(true, dirty);
        assertFalse(dirty, "preserved draft setValue is suppressed → still clean");

        // 3) 防抖：草稿 != 权威名，但 dirty=false → 不得提交（#10 主诉）
        assertFalse(GraphBusEditor.shouldCommitBusName(dirty, "CH12", "CH123"),
            "stale draft must not enter debounce commit");

        // 4) 用户继续敲键（非 suppress）→ dirty=true，此时才允许提交
        dirty = GraphBusEditor.dirtyAfterResponder(false, dirty);
        assertTrue(dirty, "real keystroke marks dirty");
        assertTrue(GraphBusEditor.shouldCommitBusName(dirty, "CH12", "CH123"),
            "keystroke draft commits");

        // 5) 提交后复位（工厂重建走 dirtyAfterProgrammaticLoad）
        dirty = GraphBusEditor.dirtyAfterProgrammaticLoad();
        assertFalse(GraphBusEditor.shouldCommitBusName(dirty, "CH12", "CH12"),
            "after commit, equal text is a no-op even if dirty lingered");
    }

    @Test
    @DisplayName("EditState starts with busNameUserDirty=false / EditState 初始非脏")
    void testEditStateInitialDirty() {
        // EditState 是纯数据壳（泛型擦除，无 Minecraft 依赖），钉住出厂默认。
        var st = new GraphEditor.EditState();
        assertFalse(st.busNameUserDirty, "fresh EditState must start clean");
    }

    // ══════════ issue #17 — compile reset on the authoritative graph ══════════

    /** Minimal GraphBlockEntity stub carrying one graph. / 只带一张图的最小 stub。 */
    private static final class StubBE implements GraphBlockEntity {
        NodeGraph graph;
        StubBE(NodeGraph g) { this.graph = g; }
        @Override public NodeGraph getNodeGraph() { return graph; }
        @Override public net.minecraft.world.level.block.entity.BlockEntity asBlockEntity() { return null; }
        @Override public net.minecraft.world.level.Level getLevel() { return null; }
        @Override public net.minecraft.core.BlockPos getBlockPos() { return net.minecraft.core.BlockPos.ZERO; }
        @Override public void setChanged() {}
        @Override public void sendBlockUpdated() {}
    }

    private static GraphNode flip(NodeGraph g, float init, float current) {
        GraphNode n = g.addNode(NodeType.T_FLIPFLOP, 0, 0);
        n.params[0] = init;
        n.params[1] = current;
        return n;
    }

    @Test
    @DisplayName("compile reset restores current state to initial (incl. sub-graphs) / 编译复位含子图")
    void testApplyCompileReset() {
        NodeGraph main = new NodeGraph();
        GraphNode mainFf = flip(main, 1f, 0f);
        GraphNode gate = main.addNode(NodeType.GATE, 0, 0);
        gate.params[0] = 1f;
        gate.params[1] = 0f;

        GraphNode encap = main.addNode(NodeType.ENCAPSULATION, 0, 0);
        NodeGraph sub = new NodeGraph();
        GraphNode subFf = flip(sub, 1f, 0f);
        encap.subGraph = sub;

        new StubBE(main).applyCompileReset();

        assertEquals(1f, mainFf.params[1], 0f, "main flip-flop current → initial");
        assertEquals(1f, gate.params[1], 0f, "main gate current → initial");
        assertEquals(1f, subFf.params[1], 0f, "sub-graph flip-flop current → initial");
    }

    @Test
    @DisplayName("compile reset leaves ordinary params alone / 普通参数不受编译复位影响")
    void testCompileResetIgnoresOrdinaryNodes() {
        NodeGraph g = new NodeGraph();
        GraphNode rot = g.addNode(NodeType.ROTATE, 0, 0);
        rot.params[0] = 360f;
        if (rot.params.length > 1) rot.params[1] = 99f;

        new StubBE(g).applyCompileReset();

        assertEquals(360f, rot.params[0], 0f, "param 0 untouched");
        if (rot.params.length > 1)
            assertEquals(99f, rot.params[1], 0f, "non-trigger param 1 untouched");
    }

    // ══════════ 参数输入框同步 — ff3 / 草稿文本 / param box sync ══════════

    @Test
    @DisplayName("ff3 must not saturate at Integer.MAX_VALUE/1000 / ff3 不得把大数压成 2147483.x")
    void testFf3DoesNotClampLargeMagnitudes() {
        // 旧实现 Math.round(float)→int 在 |v|>2147483.647 时饱和，显示成 2147483.8
        // Old Math.round(float)->int saturated above 2147483.647 and displayed 2147483.8
        String big = GraphEditor.ff3(999999995904f);
        assertFalse(big.startsWith("2147483"), "large param must not crush to Integer.MAX_VALUE/1000, got " + big);
        // 旧实现下 mid 恰为 2147483.8 —— 断言必须能区分新旧
        // Under the old ff3 this mid value printed exactly 2147483.8 — assert that.
        assertEquals("2147483.8", GraphEditor.ff3(2147483.8f), "mid-range keeps ordinary formatting");
        assertEquals("1.5", GraphEditor.ff3(1.5f), "ordinary values still format");
    }

    @Test
    @DisplayName("empty/partial input keeps the last committed number / 空/半截输入保持上次已提交数")
    void testResolveParamDraftValueKeepsLastOnInvalid() {
        assertEquals(5f, NodeEditStateFactory.resolveParamDraftValue("", 5f), 0f, "cleared box");
        assertEquals(5f, NodeEditStateFactory.resolveParamDraftValue("  ", 5f), 0f, "whitespace");
        assertEquals(5f, NodeEditStateFactory.resolveParamDraftValue("1e", 5f), 0f, "partial exponent");
        assertEquals(5f, NodeEditStateFactory.resolveParamDraftValue("abc", 5f), 0f, "unparseable keeps last");
        assertEquals(12f, NodeEditStateFactory.resolveParamDraftValue("12.", 5f), 0f, "trailing dot parses");
        assertEquals(0f, NodeEditStateFactory.resolveParamDraftValue("000", 5f), 0f, "000 is a real zero");
    }

    @Test
    @DisplayName("SET_PARAM carries raw draft text for peers / SET_PARAM 携带草稿原文")
    void testSetParamCarriesDraftText() {
        // 钉 record 的 stringValue 槽（工厂 GraphOp.setParam(..., draftText, ...) 也写这一槽）。
        // 7 参工厂内部用 ItemStack.EMPTY，其 <clinit> 需注册表引导，纯 JUnit 不可调；
        // 这里用 28 参直构 + null itemStack 等价钉住同一字段。
        // Pins the record's stringValue slot (the setParam factory writes the same field).
        // The 7-arg factory needs ItemStack.EMPTY's registry bootstrap, so plain JUnit
        // builds the canonical 28-arg form with null itemStack instead.
        var uuid = java.util.UUID.randomUUID();
        var withDraft = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
            io.github.y15173334444.create_schematic_compute.graph.OpType.SET_PARAM,
            net.minecraft.core.BlockPos.ZERO, -1, 3, 0, null, 0f, 0f,
            0, 0, 0, 0, 0, 0f, "", 0, 0, 0, 0, null, 0, 0, 0,
            (net.minecraft.world.item.ItemStack) null, 0L, uuid, 0, null);
        assertEquals("", withDraft.stringValue(), "cleared box must ship empty draft");
        var typed = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
            io.github.y15173334444.create_schematic_compute.graph.OpType.SET_PARAM,
            net.minecraft.core.BlockPos.ZERO, -1, 3, 0, null, 0f, 0f,
            0, 0, 0, 0, 0, 999999995904f, "999999999999", 0, 0, 0, 0, null, 0, 0, 0,
            (net.minecraft.world.item.ItemStack) null, 0L, uuid, 0, null);
        assertEquals("999999999999", typed.stringValue(), "in-progress text ships verbatim");
        var noDraft = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
            io.github.y15173334444.create_schematic_compute.graph.OpType.SET_PARAM,
            net.minecraft.core.BlockPos.ZERO, -1, 3, 0, null, 0f, 0f,
            0, 0, 0, 0, 0, 1f, null, 0, 0, 0, 0, null, 0, 0, 0,
            (net.minecraft.world.item.ItemStack) null, 0L, uuid, 0, null);
        assertNull(noDraft.stringValue(), "ops without a draft fall back to ff3 on peers");
    }

    // ══════════ 封装子图同步 — 字段反查 / subgraph sync field reverse-lookup ══════════

    @Test
    @DisplayName("fieldIndexOf maps param index → field slot, -1 for busBox / 参数下标反查字段位次")
    void testFieldIndexOf() {
        // 典型 BUS 节点字段序：参数框、busBox(-1)、频段框（位次上又标成 0/1/2）
        // Typical BUS field order: param boxes, busBox(-1), band boxes (slots re-use 0/1/2).
        var idx = java.util.List.of(0, 1, -1, 0, 1, 2);
        assertEquals(0, GraphRemoteApplier.fieldIndexOf(idx, 0), "param 0 → first matching field slot");
        assertEquals(1, GraphRemoteApplier.fieldIndexOf(idx, 1), "param 1 → second field slot");
        assertEquals(-1, GraphRemoteApplier.fieldIndexOf(idx, 7), "missing param → -1, never fields.get(-1)");
        // 反查结果若为 -1，调用方必须跳过 —— 旧实现 get(paramIndex) 在 fi==-1 时 IOOBE
        // A -1 result must be skipped by the caller — the old get(paramIndex) IOOBE'd on fi==-1.
        assertTrue(GraphRemoteApplier.fieldIndexOf(idx, 99) < 0, "guard: negative slot must not index fields");
        // busBox 占位 -1 不可被参数下标命中；param 0 落到其后的首个 0 槽
        // The busBox(-1) slot is never a param target; param 0 lands on the next 0 slot.
        assertEquals(1, GraphRemoteApplier.fieldIndexOf(java.util.List.of(-1, 0), 0),
            "param 0 skips the busBox(-1) slot");
    }

    @Test
    @DisplayName("SET_PARAM unchanged value must not bump generation / 值相同不得 bump 代际")
    void testSetParamUnchangedSkipsBump() {
        // 草稿-only op（paramValue 不变、stringValue 变）每键到达 OpExecutor；若写值+bump，
        // 服务端会全量重编译并 runtimeState.clear()，打断 DELAY/触发器/PID。
        // Draft-only ops keep paramValue; a write+bump forces full recompile + state clear.
        var g = new io.github.y15173334444.create_schematic_compute.graph.NodeGraph();
        var n = g.addNode(NodeType.CONST, 0, 0);
        n.params[0] = 5f;
        int genBefore = g.graphGeneration;
        var sameValue = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
            io.github.y15173334444.create_schematic_compute.graph.OpType.SET_PARAM,
            net.minecraft.core.BlockPos.ZERO, -1, n.id, 0, null, 0f, 0f,
            0, 0, 0, 0, 0, 5f, "", 0, 0, 0, 0, null, 0, 0, 0,
            (net.minecraft.world.item.ItemStack) null, 0L, java.util.UUID.randomUUID(), 0, null);
        io.github.y15173334444.create_schematic_compute.graph.OpExecutor.apply(g, sameValue, false);
        assertEquals(5f, n.params[0], 0f, "value unchanged");
        assertEquals(genBefore, g.graphGeneration, "draft-only op must not bump generation");
        var changed = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
            io.github.y15173334444.create_schematic_compute.graph.OpType.SET_PARAM,
            net.minecraft.core.BlockPos.ZERO, -1, n.id, 0, null, 0f, 0f,
            0, 0, 0, 0, 0, 7f, "7", 0, 0, 0, 0, null, 0, 0, 0,
            (net.minecraft.world.item.ItemStack) null, 0L, java.util.UUID.randomUUID(), 0, null);
        io.github.y15173334444.create_schematic_compute.graph.OpExecutor.apply(g, changed, false);
        assertEquals(7f, n.params[0], 0f, "real change applies");
        assertEquals(genBefore + 1, g.graphGeneration, "real change still bumps");
    }

    @Test
    @DisplayName("param values must not enter the edit-state fingerprint / 参数值不得进编辑状态指纹")
    void testParamValuesNotInEditStateSignature() {
        // 指纹含参数值时，SET_PARAM 一变就 createEditState → ff3 把草稿 "000" 刷成 "0.0"。
        // 用反射调用私有 editStateSignature：改 params[0] 后指纹必须不变。
        // If values were in the fingerprint, SET_PARAM rebuilds the panel and ff3 wipes drafts.
        var g = new io.github.y15173334444.create_schematic_compute.graph.NodeGraph();
        var n = g.addNode(NodeType.CONST, 0, 0);
        n.params[0] = 0f;
        // 无头 GraphEditor：Host 桩只供图引用
        var host = new GraphEditor.Host() {
            @Override public io.github.y15173334444.create_schematic_compute.graph.NodeGraph getGraph() { return g; }
            @Override public void saveGraph() {}
            @Override public void toggleRunning(boolean start) {}
            @Override public boolean isRunning() { return false; }
            @Override public net.minecraft.client.gui.screens.Screen asScreen() { return null; }
        };
        var ed = new GraphEditor(host, null);
        try {
            var m = GraphEditor.class.getDeclaredMethod("editStateSignature", GraphNode.class);
            m.setAccessible(true);
            int before = (int) m.invoke(ed, n);
            n.params[0] = 1.0E12f;
            int after = (int) m.invoke(ed, n);
            assertEquals(before, after, "changing a param value must not change the fingerprint");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("editStateSignature must stay accessible for this pin", e);
        }
    }
}
