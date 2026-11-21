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
            .formatPerfSummary(evaluator, 20);
        assertEquals(3, lines.size(), "all three nodes listed");
        assertTrue(lines.stream().anyMatch(l -> l.contains("(" + cid + ")")),
            "the CUSTOM line carries modid + node id");
        assertTrue(lines.stream().anyMatch(l -> l.contains("(const)")), "built-ins carry the type id");
        assertTrue(lines.stream().allMatch(l -> l.contains("µs avg")), "the µs unit rides every line");
        var one = io.github.y15173334444.create_schematic_compute.SchematicComputeCommands
            .formatPerfSummary(evaluator, 1);
        assertEquals(1, one.size(), "topN caps the output");
    }

    /** 观测面窄口（条目『观测面 evaluatorView 交出活求值器 + 探针每 tick 装箱』①）：
     *  接口恰两成员（快照 + 标签）、evaluatorView 静态返回类型收敛为视图、标签解析口径
     *  （内建 = 类型 id / CUSTOM = customId / 陈旧键与未知 id = "?"）。
     *  The narrow observation port (register entry part 1): exactly two members
     *  (snapshot + label), evaluatorView's static return type narrowed to the view, and
     *  the label contract (built-ins = type id / CUSTOM = customId / stale keys and
     *  unknown ids = "?"). */
    @Test
    @DisplayName("窄口：两成员接口 + evaluatorView 返回类型收敛 + 标签解析含陈旧键")
    void observationViewIsNarrow() throws Exception {
        var declared = EvaluatorObservationView.class.getDeclaredMethods();
        assertEquals(2, declared.length, "the narrow view exposes exactly snapshot + label");
        assertEquals(EvaluatorObservationView.class,
            io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity.class
                .getMethod("evaluatorView").getReturnType(),
            "evaluatorView hands out the narrow view, not the evaluator");

        NodeGraph g = new NodeGraph();
        GraphNode c = g.addNode(NodeType.CONST, 0f, 0f);
        String cid = "perfmod:labeled";
        CustomNodeRegistry.register(new io.github.y15173334444.create_schematic_compute.api.CustomNodeType(
            cid, "Labeled", List.of(), List.of(), List.of()));
        GraphNode custom = g.addNode(NodeType.CUSTOM, cid, 3f, 0f);
        var evaluator = new GraphEvaluator(g);
        assertEquals("const", evaluator.nodeLabel(c.id), "built-ins carry the type id");
        assertEquals(cid, evaluator.nodeLabel(custom.id), "CUSTOM carries modid + node id");
        assertEquals("?", evaluator.nodeLabel(9999), "unknown ids resolve to ?");
        g.removeNode(custom.id);
        assertEquals("?", evaluator.nodeLabel(custom.id), "stale keys (deleted nodes) resolve to ?");
    }

    /** 原始表行为锚（同条目 ②）：id 超 127 与跨初始容量 rehash 后取值/覆盖/快照正确
     *  ——去装箱改的是存储，不是语义。
     *  Primitive-table behaviour anchor (part 2 of the same entry): ids above 127 and
     *  rehash past the initial capacity read back, overwrite and snapshot correctly —
     *  de-boxing changes the storage, not the semantics. */
    @Test
    @DisplayName("原始表：大 id + rehash 后取值正确；快照物化覆盖全条目")
    void primitiveTableSurvivesGrowAndBigIds() {
        var t = new GraphEvaluator.NodeCostTable();
        for (int id = 1; id <= 300; id++) t.put(id, id * 1000L);
        assertEquals(300, t.size());
        for (int id = 1; id <= 300; id++) assertEquals(id * 1000L, t.get(id), "id=" + id);
        assertEquals(0L, t.get(301), "absent ids read zero");
        t.put(200, 7L);
        assertEquals(7L, t.get(200), "update overwrites in place");
        var snapshot = new java.util.HashMap<Integer, Long>();
        t.collect(snapshot);
        assertEquals(300, snapshot.size(), "the snapshot covers every entry");
        assertEquals(7L, snapshot.get(200), "the snapshot carries the updated value");
    }

    /** 源码锚（PixelEditorThemeAdoptionTest 同款防回潮）：每 tick 热路径耗时表不得
     *  回潮为装箱 Map——开放寻址原始表在场。
     *  Source anchor (the PixelEditorThemeAdoptionTest anti-regression pattern): the
     *  per-tick hot-path cost table must not regress to a boxed map — the primitive
     *  open-addressed table stays in place. */
    @Test
    @DisplayName("源码锚：耗时表保持原始类型开放寻址表，无装箱容器回潮")
    void costTableStaysPrimitive() throws Exception {
        String t = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/graph/GraphEvaluator.java"));
        assertFalse(t.contains("HashMap<Integer, Long> nodeCostNs"),
            "the per-tick cost table must not regress to a boxed map field");
        assertTrue(t.contains("NodeCostTable nodeCostNs"),
            "the primitive open-addressed table stays in place");
        assertTrue(t.contains("implements EvaluatorObservationView"),
            "the evaluator keeps implementing the narrow observation view");
    }
}
