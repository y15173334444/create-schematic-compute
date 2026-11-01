package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 观测埋点（批 E /cscperf）的行为锚：分派层全节点墙钟进 EMA 表、只读视图、汇总格式化
 * （CUSTOM 行带 modid+id）。Headless 可测——求值器直接驱动（GearboxNodesEvalTest 同款
 * evaluate() 口）；节点 id 用 addNode 分配的真实 id（手工重指派会与 nodeMap 脱钩）。
 * Behaviour anchors for the observation probe (batch E /cscperf): the dispatch-level
 * per-node wall clock lands in the EMA table, the read-only view, and the summary
 * formatter (CUSTOM lines carry modid + id). Headless-safe — the evaluator is driven
 * directly (the same evaluate() entry the gearbox eval tests use); node ids are the
 * real addNode-assigned ones (reassigning by hand desyncs nodeMap).
 */
class NodePerfProbeTest {

    /** 埋点面：跑若干 tick 后 EMA 表覆盖全节点且值为正；视图不可变；EMA 收敛有界。
     *  The probe face: after some ticks the EMA table covers every node with positive
     *  values; the view is immutable; the EMA stays bounded. */
    @Test
    @DisplayName("埋点：EMA 覆盖全节点为正值；视图只读；重跑收敛有界")
    void probePopulatesEma() {
        NodeGraph g = new NodeGraph();
        GraphNode c = g.addNode(NodeType.CONST, 0f, 0f);
        c.params[0] = 2f;
        GraphNode m = g.addNode(NodeType.ADD, 3f, 3f);
        g.addConnection(c.id, 0, m.id, 0);
        g.addConnection(c.id, 0, m.id, 1);
        // 时序档代表（审查修复锚）：DELAY 在 evalExt 入口直转 TimingEval.evalState、
        // 永不落 eval() —— 埋点上移到分派外圈后必须同样记账（否则对 /cscperf 失明）。
        // A timing-tier representative (review-fix anchor): DELAY turns to
        // TimingEval.evalState at the evalExt entry and never lands in eval() — with the
        // probe hoisted to the dispatch outer ring it must be accounted all the same
        // (otherwise invisible to /cscperf).
        GraphNode d = g.addNode(NodeType.DELAY, 6f, 0f);
        g.addConnection(m.id, 0, d.id, 0);

        var evaluator = new GraphEvaluator(g);
        for (int i = 0; i < 5; i++)
            evaluator.evaluate(List.of(), Map.of(), 0.05f,
                new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        var view = evaluator.nodeCostNsView();
        assertEquals(3, view.size(), "all three nodes are probed");
        assertTrue(view.containsKey(c.id) && view.get(c.id) > 0, "CONST carries a positive EMA");
        assertTrue(view.containsKey(m.id) && view.get(m.id) > 0, "ADD carries a positive EMA");
        assertTrue(view.containsKey(d.id) && view.get(d.id) > 0,
            "DELAY (a timing-tier node that never enters eval()) carries a positive EMA");
        assertThrows(UnsupportedOperationException.class, () -> view.put(99, 1L),
            "the view is read-only");
        for (int i = 0; i < 50; i++)
            evaluator.evaluate(List.of(), Map.of(), 0.05f,
                new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertTrue(evaluator.nodeCostNsView().get(m.id) < 1_000_000_000L,
            "the EMA stays bounded under repeated ticks");
    }

    /** 汇总格式化：降序、行含节点 id 与类型标签、CUSTOM 行带 modid+id、top-N 截断。
     *  The summary formatter: descending, lines carry node id and the type label,
     *  CUSTOM lines carry modid + id, top-N truncates. */
    @Test
    @DisplayName("格式化：类型标签、CUSTOM 带 modid+id、top-N 截断")
    void formatterShapesOutput() {
        NodeGraph g = new NodeGraph();
        GraphNode c = g.addNode(NodeType.CONST, 0f, 0f);
        c.params[0] = 2f;
        GraphNode m = g.addNode(NodeType.ADD, 3f, 3f);
        g.addConnection(c.id, 0, m.id, 0);
        g.addConnection(c.id, 0, m.id, 1);

        String cid = "perfmod:widget";
        CustomNodeRegistry.register(new io.github.y15173334444.create_schematic_compute.api.CustomNodeType(
            cid, "P widget", List.of(), List.of(), List.of()));
        GraphNode custom = g.addNode(NodeType.CUSTOM, cid, 6f, 0f);
        var evaluator = new GraphEvaluator(g);
        for (int i = 0; i < 3; i++)
            evaluator.evaluate(List.of(), Map.of(), 0.05f,
                new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        var lines = io.github.y15173334444.create_schematic_compute.SchematicComputeCommands
            .formatPerfSummary(evaluator, g, 20);
        assertEquals(3, lines.size(), "all three nodes listed");
        assertTrue(lines.stream().anyMatch(l -> l.contains("(" + cid + ")")),
            "the CUSTOM line carries modid + node id");
        assertTrue(lines.stream().anyMatch(l -> l.contains("(const)")), "built-ins carry the type id");
        assertTrue(lines.stream().allMatch(l -> l.contains("µs avg")), "the µs unit rides every line");
        var one = io.github.y15173334444.create_schematic_compute.SchematicComputeCommands
            .formatPerfSummary(evaluator, g, 1);
        assertEquals(1, one.size(), "topN caps the output");
    }
}
