package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 索引覆盖实测编辑区高度回归：长公式脚本的面板尾部必须落在索引 AABB 内——
 * 尾部点击时 queryPoint 找不到节点自身，z 序遮挡门控就会失效（编辑区下部看得见摸不着）。
 * Index-covers-measured-edit-height regression: a long formula script's panel tail must lie
 * inside the indexed AABB — when queryPoint misses the node itself for tail clicks, the
 * z-order occlusion gate is defeated (the bottom of the edit panel is visible yet untouchable).
 */
class SpatialIndexExpandedHeightTest {

    private static GraphNode formulaNode(int id, int lines) {
        var n = new GraphNode(id, NodeType.FORMULA, 0, 0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            if (i > 0) sb.append('\n');
            sb.append("a").append(i).append(" = ").append(i);
        }
        n.formula = sb.toString();
        return n;
    }

    @Test
    void measuredEditHeight_indexesTheFullPanelTail() {
        var n = formulaNode(1, 60);
        var idx = new SpatialIndex();
        int measured = 3000; // 实测面板高（含折行）远超静态估算 / measured panel height (wrap included), far beyond the static estimate
        idx.build(List.of(n), Set.of(n.id), node -> measured);

        int tailY = (int) SpatialIndex.nhStatic(n) + measured - 20; // 面板尾部 / panel tail
        assertTrue(idx.queryPoint(10, tailY).contains(n),
            "a point inside the measured edit height must resolve to the node");
    }

    @Test
    void staticEstimateAbsent_stillCoversItsOwnRegion() {
        var n = formulaNode(1, 60);
        var idx = new SpatialIndex();
        idx.build(List.of(n), Set.of(n.id));

        int coveredY = (int) SpatialIndex.nhStatic(n) + SpatialIndex.editHeight(n) - 20;
        assertTrue(idx.queryPoint(10, coveredY).contains(n),
            "the fallback static estimate must cover the region it claims");
    }

    @Test
    void staticFormulaHeight_hasNo32LineCap() {
        int h32 = SpatialIndex.editHeight(formulaNode(1, 32));
        int h60 = SpatialIndex.editHeight(formulaNode(2, 60));
        assertEquals(28 * 12, h60 - h32,
            "every logical line beyond 32 must still add height (the old 32-line cap)");
    }

    @Test
    void measuredContentHeight_overridesTheStaticEstimate() {
        var n = formulaNode(1, 5);
        int contentH = 3000; // 少量长逻辑行折出的高面板 / few long logical lines wrapping into a tall panel
        assertTrue(SpatialIndex.editHeight(n, -1, contentH) >= contentH + 22,
            "the measured content height must feed the estimate");
    }
}
