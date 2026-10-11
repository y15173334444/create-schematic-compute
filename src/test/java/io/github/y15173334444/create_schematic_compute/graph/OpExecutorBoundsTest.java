package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K15 回归：执行器层的客户端可控索引上界（服务端 handleServer 的 K15 预检以 REJECT
 * 回执拒收，此处验证执行器自身的纵深防御 —— 纵使 op 绕过预检抵达执行器也不得炸/不得
 * 无界扩容）。
 * <p>K15 regressions: executor-level caps on client-controlled indices (the server's K15
 * pre-check refuses such ops with a REJECT receipt; these tests verify the executor's own
 * defense in depth — an op that bypasses the pre-check must neither crash nor expand
 * unbounded).</p>
 */
class OpExecutorBoundsTest {

    private static final BlockPos POS = new BlockPos(7, 8, 9);
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000011");

    /** 28 参构造的热栏 op（itemStack 传 null —— headless 不得引导 ItemStack.EMPTY）。 */
    private static GraphOp hotbarOp(int nodeId, int slot) {
        return new GraphOp(OpType.SET_HOTBAR_ITEM, POS, -1, nodeId,
            0, null, 0f, 0f, 0, 0, 0, 0, 0, 0f,
            null, 0, 0, 0, 0, null, 0, 0, slot,
            null, 0L, ACTOR, 0, null);
    }

    /** 28 参构造的像素帧 op。 */
    private static GraphOp pixelsOp(int nodeId, int frameIndex, int[] pixels) {
        return new GraphOp(OpType.SET_IMAGE_PIXELS, POS, -1, nodeId,
            0, null, 0f, 0f, 0, 0, 0, 0, frameIndex, 0f,
            null, 0, 0, 0, 0, null, 0, 0, 0,
            null, 0L, ACTOR, 0, pixels);
    }

    // ═══════════ SET_HOTBAR_ITEM：槽位上界 9 ═══════════

    @Test
    @DisplayName("热栏槽位 ≥ 9：不扩容不写入（原实现 new ItemStack[slot+1] 无界扩容）")
    void hotbarSlotBeyondNineRefused() {
        NodeGraph g = new NodeGraph();
        GraphNode node = g.addNode(NodeType.CONST, 0, 0);
        OpExecutor.apply(g, hotbarOp(node.id, OpExecutor.HOTBAR_SLOTS));
        assertEquals(0, node.itemParams.length, "越界槽位不得触发 itemParams 扩容");

        // 已有数组的节点同样不得被越界 op 改写
        node.itemParams = new ItemStack[OpExecutor.HOTBAR_SLOTS];
        OpExecutor.apply(g, hotbarOp(node.id, OpExecutor.HOTBAR_SLOTS + 5));
        assertEquals(OpExecutor.HOTBAR_SLOTS, node.itemParams.length, "数组不得被越界 op 扩容");
    }

    @Test
    @DisplayName("热栏槽位 0..8：仍可写入既有数组")
    void hotbarSlotWithinBoundsApplies() {
        NodeGraph g = new NodeGraph();
        GraphNode node = g.addNode(NodeType.CONST, 0, 0);
        node.itemParams = new ItemStack[OpExecutor.HOTBAR_SLOTS];
        long genBefore = g.graphGeneration;
        OpExecutor.apply(g, hotbarOp(node.id, 8));
        assertEquals(OpExecutor.HOTBAR_SLOTS, node.itemParams.length, "界内槽位不扩容");
        assertNull(node.itemParams[8], "界内写入落地（op 携带 null 物品）");
        assertTrue(g.graphGeneration > genBefore, "界内应用必须 bump 代际");
    }

    // ═══════════ SET_IMAGE_PIXELS：帧索引上界 ═══════════

    private static GraphNode sequenceNode(NodeGraph g, int frames) {
        GraphNode node = g.addNode(NodeType.IMAGE_SEQUENCE, 0, 0);
        node.imageSequenceFrames = new java.util.ArrayList<>();
        for (int i = 0; i < frames; i++) {
            int[] f = new int[node.imageWidth * node.imageHeight];
            java.util.Arrays.fill(f, 0x01010101);
            node.imageSequenceFrames.add(f);
        }
        return node;
    }

    @Test
    @DisplayName("帧索引超上限：帧列表不被无界扩容（原实现按声明索引 while 加帧）")
    void frameIndexBeyondCapDoesNotExpand() {
        NodeGraph g = new NodeGraph();
        GraphNode node = sequenceNode(g, 2);
        int[] pixels = new int[node.imageWidth * node.imageHeight];
        OpExecutor.apply(g, pixelsOp(node.id, OpExecutor.MAX_SEQUENCE_FRAMES, pixels));
        assertEquals(2, node.imageSequenceFrames.size(), "超限帧索引不得扩容帧列表");
        assertEquals(0x01010101, node.imageSequenceFrames.get(1)[0], "既有帧内容不得被改写");
    }

    @Test
    @DisplayName("负帧索引：拒绝且不崩（原实现 set(fi) 抛 IOOBE 打断 apply）")
    void negativeFrameIndexRefusedWithoutCrash() {
        NodeGraph g = new NodeGraph();
        GraphNode node = sequenceNode(g, 2);
        int[] pixels = new int[node.imageWidth * node.imageHeight];
        assertDoesNotThrow(() -> OpExecutor.apply(g, pixelsOp(node.id, -1, pixels)));
        assertEquals(2, node.imageSequenceFrames.size());
    }

    @Test
    @DisplayName("界内新帧索引：仍可扩容并写入")
    void frameIndexWithinCapStillExpands() {
        NodeGraph g = new NodeGraph();
        GraphNode node = sequenceNode(g, 2);
        int[] pixels = new int[node.imageWidth * node.imageHeight];
        java.util.Arrays.fill(pixels, 0x00ABCDEF);
        OpExecutor.apply(g, pixelsOp(node.id, 4, pixels));
        assertEquals(5, node.imageSequenceFrames.size(), "界内索引按需扩容");
        assertEquals(0x00ABCDEF, node.imageSequenceFrames.get(4)[0], "帧内容落地");
        assertSame(node.imageSequenceFrames.get(4), node.imagePixels, "imagePixels 重链到当前帧");
    }

    // ═══════════ SET_CTRL_POINTS：点数上界 ═══════════

    @Test
    @DisplayName("parseCtrlPoints: 超上限点数返回 null（拒绝），上限内正常解析")
    void ctrlPointsCapped() {
        var sb = new StringBuilder();
        for (int i = 0; i <= GraphOp.MAX_CTRL_POINTS; i++) {
            if (i > 0) sb.append(';');
            sb.append(i).append(',').append(i * 2);
        }
        assertNull(GraphOp.parseCtrlPoints(sb.toString()), "超限点数 = 拒绝");

        var sb2 = new StringBuilder();
        for (int i = 0; i < GraphOp.MAX_CTRL_POINTS; i++) {
            if (i > 0) sb2.append(';');
            sb2.append(i).append(',').append(i * 2);
        }
        float[][] ok = GraphOp.parseCtrlPoints(sb2.toString());
        assertNotNull(ok);
        assertEquals(GraphOp.MAX_CTRL_POINTS, ok[0].length);
        assertEquals(0f, ok[0][0]);
        assertEquals((GraphOp.MAX_CTRL_POINTS - 1) * 2f, ok[1][GraphOp.MAX_CTRL_POINTS - 1]);
    }

    @Test
    @DisplayName("SET_CTRL_POINTS 超限 op 经执行器不落盘（纵深防御）")
    void ctrlPointsOverCapNotAppliedByExecutor() {
        NodeGraph g = new NodeGraph();
        GraphNode node = g.addNode(NodeType.DEBUG_SIGNAL_GEN, 0, 0);
        // curveX 有字段默认值 {0,1} —— 先置哨兵再断言未被改写
        // curveX has a field default {0,1} — plant a sentinel, then assert it is untouched
        node.curveX = new float[]{9f, 9f};
        node.curveY = new float[]{9f, 9f};
        var sb = new StringBuilder();
        for (int i = 0; i <= GraphOp.MAX_CTRL_POINTS; i++) {
            if (i > 0) sb.append(';');
            sb.append(i).append(',').append(i);
        }
        GraphOp op = new GraphOp(OpType.SET_CTRL_POINTS, POS, -1, node.id,
            0, null, 0f, 0f, 0, 0, 0, 0, 0, 0f,
            sb.toString(), 0, 0, 0, 0, null, 0, 0, 0,
            null, 0L, ACTOR, 0, null);
        OpExecutor.apply(g, op);
        assertArrayEquals(new float[]{9f, 9f}, node.curveX, "超限控制点不得写入节点");
        assertArrayEquals(new float[]{9f, 9f}, node.curveY);
    }
}
