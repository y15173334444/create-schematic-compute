package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FormulaCompute} — 预算门面 + tick 级去重缓存。
 * Tests for {@link FormulaCompute} — budget facade + tick-level dedup cache.
 */
class FormulaComputeTest {

    @BeforeEach
    void setUp() {
        FormulaCompute.beginTick();
    }

    // ── 去重缓存 / Dedup cache ──

    @Test
    @DisplayName("Dedup: miss → store → hit with same script + inputs")
    void testDedupHitAfterStore() {
        assertNull(FormulaCompute.lookupDedup("A*2", new float[]{5f}));
        FormulaCompute.storeDedup("A*2", new float[]{5f}, new float[]{10f});
        float[] cached = FormulaCompute.lookupDedup("A*2", new float[]{5f});
        assertNotNull(cached);
        assertArrayEquals(new float[]{10f}, cached);
    }

    @Test
    @DisplayName("Dedup: different inputs or different script do not hit")
    void testDedupKeyMismatch() {
        FormulaCompute.storeDedup("A*2", new float[]{5f}, new float[]{10f});
        assertNull(FormulaCompute.lookupDedup("A*2", new float[]{6f}));
        assertNull(FormulaCompute.lookupDedup("A*3", new float[]{5f}));
    }

    @Test
    @DisplayName("Dedup: cache clones internally — caller-side mutation does not leak")
    void testDedupArrayIsolation() {
        float[] result = new float[]{10f, 20f};
        FormulaCompute.storeDedup("f", new float[]{1f}, result);
        result[0] = 999f; // mutate caller-side array after store
        float[] cached = FormulaCompute.lookupDedup("f", new float[]{1f});
        assertArrayEquals(new float[]{10f, 20f}, cached);
    }

    @Test
    @DisplayName("Dedup: beginTick clears the cache")
    void testDedupClearedByBeginTick() {
        FormulaCompute.storeDedup("f", new float[]{1f}, new float[]{2f});
        FormulaCompute.beginTick();
        assertNull(FormulaCompute.lookupDedup("f", new float[]{1f}));
    }

    // ── 预算状态 / Budget state ──

    @Test
    @DisplayName("sliceNs: no heavy yielders last tick → full budget as slice")
    void testSliceFullBudgetWhenNoYielders() {
        assertEquals(FormulaCompute.budgetNs(), FormulaCompute.sliceNs());
    }

    @Test
    @DisplayName("sliceNs: yield count rotates across ticks and divides the budget")
    void testYieldCountRotation() {
        FormulaCompute.reportYield();
        FormulaCompute.reportYield();
        FormulaCompute.reportYield();
        FormulaCompute.beginTick();
        assertEquals(3, FormulaCompute.heavyYieldCountPrev());
        assertEquals(FormulaCompute.budgetNs() / 3, FormulaCompute.sliceNs());
        FormulaCompute.beginTick(); // this tick had no yields
        assertEquals(0, FormulaCompute.heavyYieldCountPrev());
    }

    @Test
    @DisplayName("deadlineExhausted: false right after beginTick")
    void testDeadlineNotExhaustedAfterBeginTick() {
        assertFalse(FormulaCompute.deadlineExhausted());
    }

    @Test
    @DisplayName("clearAll resets counters and cache")
    void testClearAll() {
        FormulaCompute.reportYield();
        FormulaCompute.storeDedup("f", new float[]{1f}, new float[]{2f});
        FormulaCompute.clearAll();
        assertNull(FormulaCompute.lookupDedup("f", new float[]{1f}));
        FormulaCompute.beginTick();
        assertEquals(0, FormulaCompute.heavyYieldCountPrev());
    }

    // ── 配置默认值 / Config defaults ──

    @Test
    @DisplayName("Config: unloaded spec falls back to default 3.0ms budget")
    void testConfigDefaultBudget() {
        // 单元测试环境配置未加载,get() 抛 IllegalStateException → 回退默认
        // In unit tests the spec is unloaded (get() throws) → fall back to default
        assertThrows(IllegalStateException.class, () -> Config.FORMULA_BUDGET_MS.get());
        assertEquals(3_000_000L, FormulaCompute.budgetNs());
    }

    // ── 与 GraphEvaluator 的集成:同脚本同输入复用 / Integration with GraphEvaluator ──

    @Test
    @DisplayName("Two identical FORMULA nodes with same inputs: second node hits dedup")
    void testGraphDedupIntegration() {
        var graph = new NodeGraph();
        var c1 = graph.addNode(NodeType.CONST, 0, 0);
        var c2 = graph.addNode(NodeType.CONST, 100, 0);
        var f1 = graph.addNode(NodeType.FORMULA, 0, 100);
        var f2 = graph.addNode(NodeType.FORMULA, 100, 100);
        c1.params[0] = 5f;
        c2.params[0] = 5f;
        f1.formula = "A*2+1";
        f2.formula = "A*2+1";
        graph.addConnection(c1.id, 0, f1.id, 0);
        graph.addConnection(c2.id, 0, f2.id, 0);
        var evaluator = new GraphEvaluator(graph);

        FormulaCompute.beginTick();
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(11f, evaluator.getNodeOutput(f1.id, 0), 0.0001f);
        assertEquals(11f, evaluator.getNodeOutput(f2.id, 0), 0.0001f);
        assertEquals(1, FormulaCompute.dedupHits()); // f2 命中 f1 的缓存 / f2 hits f1's cache entry
    }

    @Test
    @DisplayName("Identical script but different inputs: no dedup, independent results")
    void testGraphDedupDifferentInputs() {
        var graph = new NodeGraph();
        var c1 = graph.addNode(NodeType.CONST, 0, 0);
        var c2 = graph.addNode(NodeType.CONST, 100, 0);
        var f1 = graph.addNode(NodeType.FORMULA, 0, 100);
        var f2 = graph.addNode(NodeType.FORMULA, 100, 100);
        c1.params[0] = 5f;
        c2.params[0] = 7f;
        f1.formula = "A*2";
        f2.formula = "A*2";
        graph.addConnection(c1.id, 0, f1.id, 0);
        graph.addConnection(c2.id, 0, f2.id, 0);
        var evaluator = new GraphEvaluator(graph);

        FormulaCompute.beginTick();
        evaluator.evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(10f, evaluator.getNodeOutput(f1.id, 0), 0.0001f);
        assertEquals(14f, evaluator.getNodeOutput(f2.id, 0), 0.0001f);
        assertEquals(0, FormulaCompute.dedupHits());
    }
}
