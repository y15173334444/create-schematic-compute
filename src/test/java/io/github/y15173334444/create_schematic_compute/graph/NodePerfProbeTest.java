package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 观测埋点（批 E /cscperf）的行为锚：分派层全节点墙钟进 EMA 表、{@link NodePerfView}
 * 只读窄视图（条目 19①）、原始类型开放寻址表（条目 19②：扩容重散列 / 缺节点零值 /
 * 副本数组）、汇总格式化（CUSTOM 行带 modid+id）。Headless 可测——求值器直接驱动
 * （GearboxNodesEvalTest 同款 evaluate() 口）；节点 id 用 addNode 分配的真实 id
 * （手工重指派会与 nodeMap 脱钩）。
 * Behaviour anchors for the observation probe (batch E /cscperf): the dispatch-level
 * per-node wall clock lands in the EMA table, the {@link NodePerfView} read-only narrow
 * view (entry 19 ①), the primitive open-addressed table (entry 19 ②: rehash growth /
 * zero for unrecorded nodes / copied arrays), and the summary formatter (CUSTOM lines
 * carry modid + id). Headless-safe — the evaluator is driven directly (the same
 * evaluate() entry the gearbox eval tests use); node ids are the real addNode-assigned
 * ones (reassigning by hand desyncs nodeMap).
 */
class NodePerfProbeTest {

    /** 埋点面：跑若干 tick 后 EMA 表覆盖全节点且值为正；EMA 收敛有界。
     *  The probe face: after some ticks the EMA table covers every node with positive
     *  values; the EMA stays bounded. */
    @Test
    @DisplayName("埋点：EMA 覆盖全节点为正值；重跑收敛有界")
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
        assertFalse(evaluator.hasCosts(), "an evaluator that never ran carries no costs");
        assertArrayEquals(new int[0], evaluator.nodeIds(), "no ids before the first tick");
        for (int i = 0; i < 5; i++)
            evaluator.evaluate(List.of(), Map.of(), 0.05f,
                new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertTrue(evaluator.hasCosts(), "ticking populates the table");
        assertArrayEquals(new int[]{c.id, m.id, d.id}, sorted(evaluator.nodeIds()),
            "all three nodes are probed");
        assertTrue(evaluator.nodeCostNs(c.id) > 0, "CONST carries a positive EMA");
        assertTrue(evaluator.nodeCostNs(m.id) > 0, "ADD carries a positive EMA");
        assertTrue(evaluator.nodeCostNs(d.id) > 0,
            "DELAY (a timing-tier node that never enters eval()) carries a positive EMA");
        assertEquals(0L, evaluator.nodeCostNs(99_999),
            "unrecorded ids read as zero (no data, not an error)");
        for (int i = 0; i < 50; i++)
            evaluator.evaluate(List.of(), Map.of(), 0.05f,
                new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertTrue(evaluator.nodeCostNs(m.id) < 1_000_000_000L,
            "the EMA stays bounded under repeated ticks");
    }

    /** 开放寻址表面：超过初始容量（16 槽，0.5 负载门）后重散列不丢节点——20 节点
     *  图跨过 8/16 两道扩容门，全部 id 仍在表且值为正。
     *  Open-addressing face: growth past the initial capacity (16 slots, 0.5 load gate)
     *  loses no node — a 20-node graph crosses the 8/16 growth gates and every id is
     *  still present with a positive value. */
    @Test
    @DisplayName("扩容重散列：跨初始容量的节点一个不丢")
    void tableSurvivesRehashGrowth() {
        NodeGraph g = new NodeGraph();
        int n = 20;
        for (int i = 0; i < n; i++) {
            GraphNode c = g.addNode(NodeType.CONST, i * 4f, 0f);
            c.params[0] = 1f;
        }
        var evaluator = new GraphEvaluator(g);
        for (int i = 0; i < 3; i++)
            evaluator.evaluate(List.of(), Map.of(), 0.05f,
                new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        int[] ids = evaluator.nodeIds();
        assertEquals(n, ids.length, "every node survived the rehashes");
        for (int id : ids)
            assertTrue(evaluator.nodeCostNs(id) > 0, "node " + id + " carries a positive EMA");
    }

    /** 视图只读形态：nodeIds() 返回副本（改写返回数组不影响后续读取）。
     *  The view's read-only shape: nodeIds() hands out a copy (mutating the returned
     *  array does not affect later reads). */
    @Test
    @DisplayName("视图副本：改写返回数组不伤表")
    void viewReturnsCopies() {
        NodeGraph g = new NodeGraph();
        GraphNode c = g.addNode(NodeType.CONST, 0f, 0f);
        c.params[0] = 2f;
        var evaluator = new GraphEvaluator(g);
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        int[] ids = evaluator.nodeIds();
        assertEquals(1, ids.length);
        ids[0] = -12345;
        assertArrayEquals(new int[]{c.id}, evaluator.nodeIds(), "the table is untouched");
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

    private static int[] sorted(int[] a) {
        int[] copy = a.clone();
        java.util.Arrays.sort(copy);
        return copy;
    }
}
