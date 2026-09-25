package io.github.y15173334444.create_schematic_compute.blocks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 展开节点视口裁剪回归：节点体出屏、编辑区仍在画面时不得整节点剔除。
 * Expanded-node viewport-cull regression: a node whose body is off-screen must not be
 * culled while its edit panel is still visible.
 */
class EditPanelViewportCullTest {

    private static final float W = 800, H = 600, MARGIN = 50;

    @Test
    void bodyOffScreenAboveButEditPanelVisible_isNotCulled() {
        // 节点体完全在屏幕上方（sy=-100, bodyH=80 → 体底 -20），编辑区 400px 延伸进画面
        float sx = 100, sy = -100, sw = 140, bodyH = 80, editH = 400;
        assertTrue(EditPanel.isOnScreen(sx, sy, sw, bodyH, editH, W, H, MARGIN),
            "edit panel still on-screen — must not cull");
    }

    @Test
    void bodyPartiallyBelowScreen_isNotCulled() {
        // 体底 630 超出视口底 600，但体上沿 590 仍在画面内 → 不剔除
        // Body bottom 630 is past the 600px edge, but the top (590) is still visible.
        float sx = 100, sy = 590, sw = 140, bodyH = 40, editH = 30;
        assertTrue(EditPanel.isOnScreen(sx, sy, sw, bodyH, editH, W, H, MARGIN));
    }

    @Test
    void entireExpandedNodeOffScreen_isCulled() {
        float sx = 100, sy = -500, sw = 140, bodyH = 80, editH = 100;
        // 体 -500..-420，编辑区 -420..-320，全部在顶部之上
        assertFalse(EditPanel.isOnScreen(sx, sy, sw, bodyH, editH, W, H, MARGIN));
    }

    @Test
    void collapsedNodeBodyVisible_isNotCulled() {
        assertTrue(EditPanel.isOnScreen(10, 10, 140, 80, 0, W, H, MARGIN));
    }

    @Test
    void sideOffScreen_isCulled() {
        assertFalse(EditPanel.isOnScreen(-200, 100, 140, 80, 400, W, H, MARGIN));
        assertFalse(EditPanel.isOnScreen(900, 100, 140, 80, 400, W, H, MARGIN));
    }

    @Test
    void expandedEditHeightIsConservative() {
        // 空 EditState 时与无状态估算一致；null st 同样返回该值
        var n = new io.github.y15173334444.create_schematic_compute.graph.GraphNode(
            1, io.github.y15173334444.create_schematic_compute.graph.NodeType.ADD, 0, 0);
        int bare = EditPanel.calcRenderHeight(n, 1f);
        assertEquals(bare, EditPanel.expandedEditHeight(n, null));
        assertEquals(bare, EditPanel.expandedEditHeight(n, new GraphEditor.EditState()));
    }
}
