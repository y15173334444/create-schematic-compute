package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 显示布局模式软锁回归测试：锁必须**跟随选择**而非仅跟随拖动——
 * 旧实现只有拖动才锁、松手即解锁，但本端选择框还在，队友看到的是「无锁可抢」。
 * 现选中元素经 presence 的 selectedNodeId 上报，拖拽 id 与选中 id 任一命中即锁；
 * 且只认节点编辑模式的对手侧语义（mode 1），节点图 presence 不参与显示锁。
 * Regression: the display-layout soft lock must follow the selection, not just the drag.
 */
class DisplaySelectionLockTest {

    /** 造一条远端临场记录（纯 record，无头可构造）。 / A remote presence entry (plain record). */
    private static GraphPresencePacket presence(byte mode, int draggedId, int selectedId) {
        return new GraphPresencePacket(
            net.minecraft.core.BlockPos.ZERO, UUID.randomUUID(), "Dev2",
            -1, 0f, 0f, selectedId, -1, -1, 0, 0f, 0f, null, mode, draggedId, false);
    }

    private static Map<UUID, GraphPresencePacket> single(GraphPresencePacket p) {
        Map<UUID, GraphPresencePacket> map = new HashMap<>();
        map.put(UUID.randomUUID(), p);
        return map;
    }

    @Test
    @DisplayName("selection alone holds the lock / 仅选中即持锁")
    void selectedElementIsLocked() {
        assertTrue(GraphPresenceTracker.displayNodeLocked(single(presence((byte) 1, -1, 7)), 7),
            "a player merely SELECTING element 7 must lock it (the old bug: lock only while dragging)");
    }

    @Test
    @DisplayName("drag still locks / 拖拽仍然持锁")
    void draggedElementIsLocked() {
        assertTrue(GraphPresenceTracker.displayNodeLocked(single(presence((byte) 1, 7, -1)), 7),
            "an actively dragged element stays locked");
    }

    @Test
    @DisplayName("unselected undragged elements are free / 未选中未拖拽不锁")
    void untouchedElementIsFree() {
        assertFalse(GraphPresenceTracker.displayNodeLocked(single(presence((byte) 1, -1, -1)), 7),
            "no selection, no drag → no lock");
    }

    @Test
    @DisplayName("node-graph presences never lock display elements / 节点图临场不参与显示锁")
    void nodeModePresenceDoesNotLockDisplay() {
        // mode 0 的 selectedNodeId 是节点图语义——不得命中显示元素锁
        // A mode-0 selectedNodeId is node-graph semantics — never a display lock.
        assertFalse(GraphPresenceTracker.displayNodeLocked(single(presence((byte) 0, -1, 7)), 7),
            "node-editor presence must not lock display elements");
    }

    @Test
    @DisplayName("invalid ids never lock / 非法 id 恒不锁")
    void invalidIdsNeverLock() {
        assertFalse(GraphPresenceTracker.displayNodeLocked(single(presence((byte) 1, -1, -1)), -1),
            "the -1 sentinel must not match a dragging presence's -1");
        assertFalse(GraphPresenceTracker.displayNodeLocked(single(presence((byte) 1, -1, -1)), 0),
            "id 0 is never locked");
    }
}
