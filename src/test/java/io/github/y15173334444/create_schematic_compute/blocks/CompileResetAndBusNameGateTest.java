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
        assertTrue(GraphRemoteApplier.fieldIndexOf(idx, 99) < 0, "guard: negative slot must not index fields");
        // busBox 占位 -1 不可被参数下标命中；param 0 落到其后的首个 0 槽
        assertEquals(1, GraphRemoteApplier.fieldIndexOf(java.util.List.of(-1, 0), 0),
            "param 0 skips the busBox(-1) slot");
    }
}
