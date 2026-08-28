package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指令栈图节点（MOVE/ROTATE/WAIT）的求值测试：上升沿入队去重、数值快照、完成脉冲。
 * Evaluation tests for the command-stack nodes (MOVE/ROTATE/WAIT): rising-edge dedup,
 * value snapshotting, and the completion pulse.
 */
class GearboxCommandStackEvalTest {

    /** 记录型 sink。 Recording sink. */
    private static final class RecordingSink implements GearboxCommandSink {
        final List<MotionCommand> enqueued = new ArrayList<>();
        boolean stopped = false;
        @Override public void enqueue(MotionCommand command) { enqueued.add(command); }
        @Override public void emergencyStop() { stopped = true; }
    }

    /** 构造 触点+数值 → MOVE/ROTATE/WAIT 的最小图与配套求值器。
     *  Build a minimal graph with trigger+value feeding the command node.
     *  （运动方块不动转速 —— 指令节点已无 rpm 引脚 / the motion block never sets
     *    speed — command nodes no longer carry an rpm pin.） */
    private record Setup(NodeGraph graph, GraphEvaluator evaluator, GraphNode cmd, GraphNode trig, RecordingSink sink) {}

    private static Setup setup(NodeType kind) {
        var graph = new NodeGraph();
        var trig = graph.addNode(NodeType.CONST, 0, 0);
        trig.params[0] = 0f;   // CONST 默认参数是 1，显式归零 / CONST defaults to 1; zero it
        var val = graph.addNode(NodeType.CONST, 2, 0);
        val.params[0] = 2f;
        var cmd = graph.addNode(kind, 4, 0);
        graph.addConnection(trig.id, 0, cmd.id, 0);
        graph.addConnection(val.id, 0, cmd.id, 1);
        var evaluator = new GraphEvaluator(graph);
        var rs = new RuntimeState();
        evaluator.restoreSubState(rs);   // nodeEdge 需要 runtimeState 引用 / nodeEdge needs the runtimeState ref
        var sink = new RecordingSink();
        evaluator.setCommandSink(sink);
        return new Setup(graph, evaluator, cmd, trig, sink);
    }

    private static void tick(Setup s) {
        s.evaluator().evaluate(new ArrayList<>(), new HashMap<>(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("MOVE: held-high trigger enqueues exactly once (edge dedup)")
    void testRisingEdgeDedup() {
        var s = setup(NodeType.MOVE);
        s.graph().findNode(s.cmd().id).params = new float[0]; // 无参数引脚干扰
        s.trig().params[0] = 1f;   // 拉高触点 / raise trigger
        // trig=1 持续 3 tick → 只入队一条
        // held high for 3 ticks → exactly one enqueue
        for (int i = 0; i < 3; i++) tick(s);
        assertEquals(1, s.sink().enqueued.size());
        // 数值在上升沿当帧快照（CONST=2 米）
        assertEquals(2f, s.sink().enqueued.get(0).value(), 0.0001f);
        assertEquals(NodeType.MOVE, s.sink().enqueued.get(0).kind());
    }

    @Test
    @DisplayName("MOVE: low-then-high enqueues a second command")
    void testSecondEdgeEnqueuesAgain() {
        var s = setup(NodeType.MOVE);
        tick(s);   // trig=0 → 无
        assertEquals(0, s.sink().enqueued.size());
        // trig 0→1→0→1：两条
        s.trig().params[0] = 1f; tick(s);
        s.trig().params[0] = 0f; tick(s);
        s.trig().params[0] = 1f; tick(s);
        assertEquals(2, s.sink().enqueued.size());
    }

    @Test
    @DisplayName("ROTATE: unwired value falls back to the node's editable param (90)")
    void testParamDefaultValueSnapshot() {
        var graph = new NodeGraph();
        var trig = graph.addNode(NodeType.CONST, 0, 0);
        trig.params[0] = 0f;
        var cmd = graph.addNode(NodeType.ROTATE, 2, 0);   // 不连数值引脚
        cmd.params[0] = 90f;                              // 节点编辑区里填 90（度）
        graph.addConnection(trig.id, 0, cmd.id, 0);
        var evaluator = new GraphEvaluator(graph);
        var rs = new RuntimeState();
        evaluator.restoreSubState(rs);
        var sink = new RecordingSink();
        evaluator.setCommandSink(sink);

        trig.params[0] = 1f;
        evaluator.evaluate(new ArrayList<>(), new HashMap<>(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(1, sink.enqueued.size());
        assertEquals(90f, sink.enqueued.get(0).value(), 0.0001f, "param EditBox value must be snapshotted");
    }

    @Test
    @DisplayName("CLUTCH: engaged intent passthrough (boolean threshold)")
    void testClutchPassthrough() {
        var graph = new NodeGraph();
        var sig = graph.addNode(NodeType.CONST, 0, 0);
        var clutch = graph.addNode(NodeType.CLUTCH, 2, 0);
        graph.addConnection(sig.id, 0, clutch.id, 0);
        var evaluator = new GraphEvaluator(graph);

        sig.params[0] = 1f;
        evaluator.evaluate(new ArrayList<>(), new HashMap<>(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(1f, evaluator.getNodeOutput(clutch.id, 0), 0.0001f);

        sig.params[0] = 0f;
        evaluator.evaluate(new ArrayList<>(), new HashMap<>(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(0f, evaluator.getNodeOutput(clutch.id, 0), 0.0001f);
    }

    @Test
    @DisplayName("Done pulse: node outputs 1 for one tick after completedNodeId is set")
    void testCompletionPulse() {
        var s = setup(NodeType.WAIT);
        s.graph().findNode(s.cmd().id).params = new float[0];
        tick(s);   // trig=0，不入队
        // 模拟宿主完成指令 → 置 completedNodeId → 下一 tick o[0]=1
        s.evaluator().setCompletedNodeId(s.cmd().id);
        tick(s);
        assertEquals(1f, s.evaluator().getNodeOutput(s.cmd().id, 0), 0.0001f);
        // 宿主清空 → 归 0
        s.evaluator().clearCompletedNodeId();
        tick(s);
        assertEquals(0f, s.evaluator().getNodeOutput(s.cmd().id, 0), 0.0001f);
        // edge 去重基线：全程 trig=0，无入队
        assertEquals(0, s.sink().enqueued.size());
    }

    @Test
    @DisplayName("held-high trigger with persisted edge memory does NOT re-fire after recompile/reload")
    void testHeldHighDoesNotRefireWhenEdgePersisted() {
        var s = setup(NodeType.ROTATE);
        s.graph().findNode(s.cmd().id).params = new float[0];
        s.trig().params[0] = 1f;
        tick(s);   // 上升沿 → 入队一条
        assertEquals(1, s.sink().enqueued.size());

        // 模拟重编译/实体重建后恢复的运行时状态（图未变、触点仍常高）
        // Simulate the runtime state restored after a recompile / BE recreation
        var restored = new RuntimeState();
        restored.nodeEdge.putAll(evaluatorStateOf(s).nodeEdge);
        var evaluator2 = new GraphEvaluator(s.graph());
        evaluator2.restoreSubState(restored);
        var sink2 = new RecordingSink();
        evaluator2.setCommandSink(sink2);
        evaluator2.evaluate(new ArrayList<>(), new HashMap<>(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(0, sink2.enqueued.size(), "held-high must not re-fire after state restore");
    }

    private RuntimeState evaluatorStateOf(Setup s) {
        var rs = new RuntimeState();
        rs.nodeEdge.putAll(new java.util.HashMap<>(java.util.Map.of(s.cmd().id, true)));
        return rs;
    }

    @Test
    @DisplayName("nodeEdge is pruned with its node (no NBT leaks)")
    void testNodeEdgePruned() {
        var s = setup(NodeType.WAIT);
        var rs = new RuntimeState();
        s.evaluator().restoreSubState(rs);
        var g2 = new NodeGraph();
        g2.addNode(NodeType.WAIT, 0, 0);
        rs.pruneToAliveIds(java.util.Set.of(999));   // 节点 1 不在存活集
        assertTrue(rs.nodeEdge.isEmpty() || !rs.nodeEdge.containsKey(1));
    }
}
