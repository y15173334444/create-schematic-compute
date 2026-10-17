package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「当前状态切换」回归测试（GATE / T_FLIPFLOP 编辑区「当前状态」按钮）：
 * <ul>
 *   <li>TOGGLE_CUR_STATE 只翻触发器的 {@code params[1]} 当前态镜像，且自反（撤销无需旧值）。</li>
 *   <li>非触发器类型的 {@code params[1]} 是普通参数，畸形 op 不得改写。</li>
 *   <li>运行时触发器映射按主图 / 封装子图路由（{@link RuntimeState#putFlipflop}），
 *       子图条目随写随建。</li>
 * </ul>
 * Regression tests for the manual current-state toggle (the "Current state" row button on
 * GATE / T_FLIPFLOP): TOGGLE_CUR_STATE flips only the trigger {@code params[1]} mirror and is
 * self-inverse (undo needs no old value); an ordinary type's {@code params[1]} is a plain
 * parameter a malformed op must not rewrite; the runtime trigger map routes by main /
 * sub-graph ({@link RuntimeState#putFlipflop}) with sub-graph entries created on demand.
 */
class CurrentStateToggleTest {

    // ══════════ TOGGLE_CUR_STATE — params[1] 镜像 / the params[1] mirror ══════════

    @Test
    @DisplayName("toggle flips the T_FLIPFLOP current-state mirror, twice = identity / T触发器双次切换还原")
    void testToggleFlipsTFlipFlopMirror() {
        NodeGraph g = new NodeGraph();
        GraphNode ff = g.addNode(NodeType.T_FLIPFLOP, 0, 0);
        ff.params[0] = 1f;
        ff.params[1] = 1f;
        var op = GraphOp.toggleCurState(BlockPos.ZERO, -1, ff.id, null);

        OpExecutor.apply(g, op);
        assertEquals(0f, ff.params[1], 0f, "first toggle flips the current state");
        OpExecutor.apply(g, op);
        assertEquals(1f, ff.params[1], 0f, "second toggle restores it (self-inverse)");
        assertEquals(1f, ff.params[0], 0f, "the default/initial state param is untouched");
    }

    @Test
    @DisplayName("toggle flips the GATE current-state mirror / 闸门当前态镜像同样翻转")
    void testToggleFlipsGateMirror() {
        NodeGraph g = new NodeGraph();
        GraphNode gate = g.addNode(NodeType.GATE, 0, 0);
        gate.params[0] = 0f;
        gate.params[1] = 0f;
        var op = GraphOp.toggleCurState(BlockPos.ZERO, -1, gate.id, null);

        OpExecutor.apply(g, op);
        assertEquals(1f, gate.params[1], 0f, "closed → open");
        assertEquals(0f, gate.params[0], 0f, "the default/initial state param is untouched");
    }

    @Test
    @DisplayName("LATCH mirror flips too (executor guard covers the trigger family) / 锁存器镜像同族可翻")
    void testToggleFlipsLatchMirror() {
        NodeGraph g = new NodeGraph();
        GraphNode latch = g.addNode(NodeType.LATCH, 0, 0);
        latch.params[1] = 1f;
        OpExecutor.apply(g, GraphOp.toggleCurState(BlockPos.ZERO, -1, latch.id, null));
        assertEquals(0f, latch.params[1], 0f, "trigger-family mirror flips");
    }

    @Test
    @DisplayName("a non-trigger node's params[1] is never rewritten / 非触发器的 params[1] 不得被改写")
    void testNonTriggerParamUntouched() {
        NodeGraph g = new NodeGraph();
        GraphNode loop = g.addNode(NodeType.LOOP, 0, 0); // params[1] = interval / params[1] 是间隔
        loop.params[1] = 7f;
        OpExecutor.apply(g, GraphOp.toggleCurState(BlockPos.ZERO, -1, loop.id, null));
        assertEquals(7f, loop.params[1], 0f, "LOOP's interval must survive a malformed toggle op");
    }

    // ══════════ RuntimeState.putFlipflop — 主图/子图路由 / main vs sub-graph routing ══════════

    @Test
    @DisplayName("main-graph put lands in the top-level flipflop map / 主图写入顶层触发器映射")
    void testPutMainGraph() {
        var rs = new RuntimeState();
        rs.putFlipflop(-1, 42, true);
        assertEquals(Boolean.TRUE, rs.flipflopStates.get(42));
        assertTrue(rs.subStates.isEmpty(), "main-graph write must not touch sub-states");
    }

    @Test
    @DisplayName("sub-graph put routes to the encapsulation sub-state, created on demand / 子图按封装节点路由、随写随建")
    void testPutSubGraphCreatesEntry() {
        var rs = new RuntimeState();
        rs.putFlipflop(7, 42, true);
        assertTrue(rs.flipflopStates.isEmpty(), "sub-graph write must not touch the main map");
        var ss = rs.subStates.get(7);
        assertNotNull(ss, "the sub-state is created on demand (no evaluator run yet)");
        assertEquals(Boolean.TRUE, ss.flipflopStates.get(42));
    }

    @Test
    @DisplayName("op then runtime adoption lands the flipped value / 先翻镜像后采纳，落地翻转值")
    void testOpThenRuntimeAdoption() {
        // 走真实两段顺序（EditSessionRegistry.applyOp）：OpExecutor 翻 params[1] 镜像，
        // 宿主 applyCurrentState 再按 ownerNodeId 把镜像值采纳进运行时映射。
        // The real two-step order (EditSessionRegistry.applyOp): OpExecutor flips the
        // params[1] mirror, then the host's applyCurrentState adopts the mirror value into
        // the runtime map routed by ownerNodeId.
        var rs = new RuntimeState();
        NodeGraph g = new NodeGraph();
        GraphNode ff = g.addNode(NodeType.T_FLIPFLOP, 0, 0);
        ff.params[1] = 1f;
        var op = GraphOp.toggleCurState(BlockPos.ZERO, 5, ff.id, null);

        OpExecutor.apply(g, op);
        rs.putFlipflop(op.ownerNodeId(), op.targetNodeId(), ff.params[1] > 0.5f);

        var ss = rs.subStates.get(5);
        assertNotNull(ss, "ownerNodeId 5 → the entry lands in the encapsulation sub-state");
        assertEquals(Boolean.FALSE, ss.flipflopStates.get(ff.id), "runtime map holds the flipped value");
    }

    @Test
    @DisplayName("toggle on a missing target node is a no-op / 目标节点缺失时为空操作")
    void testToggleMissingNodeIsNoop() {
        NodeGraph g = new NodeGraph();
        GraphNode ff = g.addNode(NodeType.T_FLIPFLOP, 0, 0);
        ff.params[1] = 1f;
        var op = GraphOp.toggleCurState(BlockPos.ZERO, -1, ff.id + 999, null);
        assertDoesNotThrow(() -> OpExecutor.apply(g, op));
        assertEquals(1f, ff.params[1], 0f, "an unrelated node is never rewritten");
    }
}
