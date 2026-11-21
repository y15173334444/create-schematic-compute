package io.github.y15173334444.create_schematic_compute.client.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-1 文字 LOD 判定（docs/graph-editor-render-performance-plan.md §四/§八 Q2）：
 * 「次要文字」（引脚标签 / encap 摘要）在 zoom 低于阈值时跳过——阈值含等号（0.25 恰好画；
 * 2026-11-21 实测拍板从 0.5 下调：0.5 时节点还清晰可见）；展开编辑中的节点永不降档
 * （编辑面需要引脚名）。判定是纯逻辑，headless 可锚。
 * PERF-1 text-LOD decision (plan §4 / Q2): secondary text (pin labels / encap summary)
 * skips below the zoom threshold — inclusive at the boundary (0.25 exactly still draws;
 * live-ruled 2026-11-21 down from 0.5, where nodes are still clearly visible); an expanded
 * node never degrades (the edit surface needs pin names). The decision is pure logic and
 * headless-testable.
 */
class NodeRendererTextLodTest {

    @Test
    @DisplayName("阈值含等号：0.25 恰好画 / the threshold is inclusive: 0.25 exactly still draws")
    void thresholdIsInclusive() {
        assertTrue(NodeRenderer.detailText(0.25f, false));
        assertTrue(NodeRenderer.detailText(0.5f, false));
        assertTrue(NodeRenderer.detailText(1.0f, false));
        assertTrue(NodeRenderer.detailText(2.0f, false));
    }

    @Test
    @DisplayName("低于阈值跳过次要文字 / below the threshold secondary text skips")
    void belowThresholdSkips() {
        assertFalse(NodeRenderer.detailText(0.24f, false));
        assertFalse(NodeRenderer.detailText(0.125f, false));
        assertFalse(NodeRenderer.detailText(0f, false));
    }

    @Test
    @DisplayName("展开编辑中永不降档 / an expanded node never degrades")
    void expandedNeverDegrades() {
        assertTrue(NodeRenderer.detailText(0.125f, true));
        assertTrue(NodeRenderer.detailText(0f, true));
    }
}
