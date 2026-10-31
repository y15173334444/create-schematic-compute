package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.api.CustomNodeType;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.OpType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K15 静态预检的 CUSTOM 槽位界锚（公开 API 波 1 批 C）：SET_HOTBAR_ITEM 在 CUSTOM 哨兵
 * 下承载 schema ITEM 控件序位，静态上界 = {@link CustomNodeType#MAX_SCHEMA_PARAMS}
 * （精确界 = 节点实际 schema 槽位数，由执行器纵深防御裁定）；内置类型维持 0..8。
 * Anchors for the K15 static pre-check's CUSTOM slot bound (wave 1 batch C).
 */
class CustomItemSlotK15Test {

    private static final UUID ACTOR = UUID.nameUUIDFromBytes("k15-custom".getBytes());

    private static GraphOp hotbarOp(NodeType nt, int slot) {
        return new GraphOp(OpType.SET_HOTBAR_ITEM, BlockPos.ZERO, -1, 1,
            0, nt, 0f, 0f, 0, 0, 0, 0, 0, 0f, null,
            0, 0, 0, 0, null, 0, 0, slot, null, 0L, ACTOR, 0, null, null);
    }

    @Test
    @DisplayName("CUSTOM 槽位：槽检查先于物品检查——槽位合格只剩 itemStack=null，超帽报 customItemSlot")
    void customSlotBound() {
        // 槽检查在前、itemStack 检查在后：headless 无法给合法 ItemStack，用「报什么错」
        // 区分两关 —— 槽位合格 = 只剩 itemStack=null；超帽 = customItemSlot。
        // The slot check precedes the item check: headless cannot supply a legal ItemStack,
        // so the *message* distinguishes the gates — a passing slot leaves only
        // "itemStack=null", an over-cap slot reports customItemSlot.
        assertEquals("itemStack=null", GraphEditOpPacket.opBoundsViolation(
            hotbarOp(NodeType.CUSTOM, CustomNodeType.MAX_SCHEMA_PARAMS - 1)),
            "槽位上界内 = 槽关通过");
        assertTrue(GraphEditOpPacket.opBoundsViolation(
            hotbarOp(NodeType.CUSTOM, CustomNodeType.MAX_SCHEMA_PARAMS)).startsWith("customItemSlot"),
            "超帽 = 槽关拒绝");
        assertTrue(GraphEditOpPacket.opBoundsViolation(hotbarOp(NodeType.CUSTOM, -1))
            .startsWith("customItemSlot"), "负槽位拒");
    }

    @Test
    @DisplayName("内置类型维持 0..8（不受 CUSTOM 上界影响）")
    void builtinSlotBoundUnchanged() {
        assertEquals("hotbarSlot=9",
            GraphEditOpPacket.opBoundsViolation(hotbarOp(NodeType.REDSTONE_OUT, 9)));
        // REDSTONE_OUT 构造器分配 ItemStack —— headless 只到 K15 静态检查（不触构造器）✓
    }
}
