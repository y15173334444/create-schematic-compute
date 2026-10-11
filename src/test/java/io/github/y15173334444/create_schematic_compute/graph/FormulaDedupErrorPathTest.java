package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 去重缓存排除出错结果回归（K7）：FORMULA AST 路径的 storeDedup 以往在
 * Suspend/Shed/catch(Exception) 三出口无条件执行 —— 第一个节点 exec 抛错时把全零输出
 * 写进缓存，同脚本同输入的第二个节点被"本 tick 已算"命中喂零，失去了自己算的机会。
 * 修复：成功与挂起（冻结）照常缓存，异常路径不写缓存。
 * <p>
 * Dedup-cache-excludes-error-results regression (K7): the FORMULA AST path's storeDedup
 * used to run unconditionally on all three exits (Suspend/Shed/catch(Exception)) — a node
 * whose exec threw wrote its all-zero output into the cache, and a sibling node with the
 * same script+inputs got a dedup HIT fed zeros instead of computing for itself. Fix:
 * success and suspend (frozen) still cache; the exception path never writes.
 */
class FormulaDedupErrorPathTest {

    @BeforeEach void beginTick() { FormulaCompute.beginTick(); }
    @AfterEach void clearCache() { FormulaCompute.beginTick(); }

    /** 主图上放两个同脚本 FORMULA 节点（无输入 → 去重键相同）。
     *  Two same-script FORMULA nodes on one graph (no inputs → identical dedup keys). */
    private record TwoNodes(GraphEvaluator ev, GraphNode a, GraphNode b) {}

    private static TwoNodes twoNodes(String formula) {
        var graph = new NodeGraph();
        var a = graph.addNode(NodeType.FORMULA, 0, 0);
        a.formula = formula;
        var b = graph.addNode(NodeType.FORMULA, 100, 0);
        b.formula = formula;
        return new TwoNodes(new GraphEvaluator(graph), a, b);
    }

    @Test
    @DisplayName("第一个节点抛错：结果不进缓存，第二个节点必须自己算（未命中去重）")
    void errorThrownResultNeverEntersDedupCache() {
        // "vec3()"：AST 模式（vec3 是触发器），执行时栈下溢抛 NoSuchElementException
        // —— 确定性的受控 Exception 抛错源。
        // "vec3()": AST mode (vec3 is an AST trigger); exec underflows the value stack and
        // throws NoSuchElementException — a deterministic controlled-Exception thrower.
        TwoNodes t = twoNodes("vec3()");
        assertDoesNotThrow(() -> t.ev().evaluate(List.of(), Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0)));
        assertEquals(0, FormulaCompute.dedupHits(),
            "an error result must not be cached — sibling B must MISS the dedup cache and run its own exec "
            + "(the bug fed it a cached all-zero)");
        assertEquals(0f, t.ev().getNodeOutput(t.a().id, 0), 0.0001f, "A errors to 0 (unchanged)");
        assertEquals(0f, t.ev().getNodeOutput(t.b().id, 0), 0.0001f, "B errors to 0 on its own (same script)");
    }

    @Test
    @DisplayName("对照组：成功结果照常进缓存，第二个节点命中去重")
    void successResultsStillCacheAndHit() {
        // AST 模式的可算脚本：v = vec3(1,2,3); s = v.x + 1; @output s → 2.0
        // A computable AST-mode script: v = vec3(1,2,3); s = v.x + 1; @output s → 2.0
        TwoNodes t = twoNodes("v = vec3(1, 2, 3)\ns = v.x + 1\n@output s");
        t.ev().evaluate(List.of(), Map.of(), 0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(2f, t.ev().getNodeOutput(t.a().id, 0), 0.0001f, "A computes 2");
        assertEquals(2f, t.ev().getNodeOutput(t.b().id, 0), 0.0001f, "B gets the same 2");
        assertEquals(1, FormulaCompute.dedupHits(),
            "success results must still cache — B hits the dedup cache exactly once");
    }
}
