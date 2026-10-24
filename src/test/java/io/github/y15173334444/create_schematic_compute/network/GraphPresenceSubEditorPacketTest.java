package io.github.y15173334444.create_schematic_compute.network;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 子编辑器临场保活包回归：NBS/像素编辑器转移自图编辑器后 GraphEditor 的每帧保活不再运行，
 * 占用软锁由本包维持（编辑/选中 id = 正在编辑的节点、mode 0、连线字段置空、ownerNodeId
 * 透传）——其他玩家重开图后锁表能立即重建（与封装同屏路径同口径）。
 * Sub-editor presence keep-alive packet regression: after the NBS/pixel editors transfer away
 * from the graph editor, GraphEditor's per-frame keep-alive stops; the occupancy soft lock
 * rides on this packet (editing/selected id = the node being edited, mode 0, wire fields
 * zeroed, ownerNodeId carried through) so other players' lock tables rebuild immediately
 * (same contract as the same-screen encapsulation path).
 */
class GraphPresenceSubEditorPacketTest {

    @Test
    @DisplayName("保活包语义：编辑=选中=目标节点、mode 0、连线置空、ownerNodeId 透传 / keep-alive semantics: edit=select=target, mode 0, wires zeroed, ownerNodeId carried")
    void subEditorPacketSemantics() {
        var pos = new BlockPos(1, 2, 3);
        var uuid = UUID.nameUUIDFromBytes("dev".getBytes());
        var pkt = GraphPresencePacket.subEditor(pos, uuid, "Dev", 7, 42, 100.5f, 60.25f);

        assertEquals(pos, pkt.pos(), "方块坐标透传 / block pos carried");
        assertEquals(uuid, pkt.player(), "玩家 UUID 透传 / player uuid carried");
        assertEquals("Dev", pkt.playerName(), "玩家名透传 / player name carried");
        assertEquals(7, pkt.ownerNodeId(), "来源作用域透传（-1=主图，>0=封装）/ source scope carried");
        assertEquals(42, pkt.editingNodeId(), "编辑中节点 = 占用锁的锚 / the edited node anchors the lock");
        assertEquals(42, pkt.selectedNodeId(), "选中 = 同节点（锁语义 = 选中且编辑中）/ selected = same node");
        assertEquals(100.5f, pkt.cursorX(), 1e-6f, "光标 X 透传（停在节点上）/ cursor X carried");
        assertEquals(60.25f, pkt.cursorY(), 1e-6f, "光标 Y 透传 / cursor Y carried");
        assertEquals((byte) 0, pkt.mode(), "节点图编辑模式 / node-graph mode");
        assertEquals(-1, pkt.wireFromNode(), "无连线拖拽 / no wire drag");
        assertEquals(-1, pkt.wireFromPin(), "无连线引脚 / no wire pin");
        assertEquals(-1, pkt.displayDraggedNodeId(), "无显示布局拖拽 / no display drag");
        assertNull(pkt.selectedNodeIds(), "无多选集合 / no multi-select set");
        assertFalse(pkt.editingGraphName(), "非图名编辑 / not editing the graph name");
    }
}
