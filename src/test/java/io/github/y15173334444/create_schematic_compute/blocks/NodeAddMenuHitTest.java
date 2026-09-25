package io.github.y15173334444.create_schematic_compute.blocks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 添加节点菜单面板命中：菜单盖在顶栏上时，面板内点击（含顶栏重叠带）归菜单。
 * Add-node menu panel hit-test: clicks on the panel — including the strip that
 * covers the top bar — belong to the menu.
 */
class NodeAddMenuHitTest {

    @Test
    void insidePanel_isHit() {
        assertTrue(NodeAddMenu.rectContains(50, 30, 20, 10, 160, 400));
    }

    @Test
    void topBarOverlapStrip_isStillMenu() {
        // 面板顶 0..22 压在顶栏上，仍是菜单命中区
        assertTrue(NodeAddMenu.rectContains(50, 5, 20, 0, 160, 400));
        assertTrue(NodeAddMenu.rectContains(50, 21, 20, 0, 160, 400));
    }

    @Test
    void outsidePanel_isNotHit() {
        assertFalse(NodeAddMenu.rectContains(10, 30, 20, 10, 160, 400));
        assertFalse(NodeAddMenu.rectContains(200, 30, 20, 10, 160, 400));
        assertFalse(NodeAddMenu.rectContains(50, 500, 20, 10, 160, 400));
    }

    @Test
    void zeroSizePanel_isNeverHit() {
        assertFalse(NodeAddMenu.rectContains(0, 0, 0, 0, 0, 0));
        assertFalse(NodeAddMenu.rectContains(0, 0, 0, 0, 160, 0));
    }
}
