package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.OpExecutor;
import io.github.y15173334444.create_schematic_compute.graph.OpType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 恶意输入回归（P0-1 / P2-9 / K15 安全批）：解码器"先分配后校验"三处钳制、未知 op
 * 序号的优雅拒绝与 K15 越界索引前置拒收。
 * <p>Malicious-input regressions (P0-1 / P2-9 / K15 security batch): the three
 * "allocate-after-validate" decoder clamps, graceful refusal of unknown op ordinals, and the
 * K15 out-of-bounds index pre-check.</p>
 *
 * <p>安全语义：超限 = 协议错误（DecoderException → 断开该连接），绝不按声明长度分配
 * 数组；未知 op 序号 = REJECT 回执而非 AIOOBE 断连。</p>
 * <p>Security semantics: over-limit = protocol error (DecoderException drops the
 * connection), never allocating at the declared length; an unknown op ordinal answers a
 * REJECT receipt instead of crashing with AIOOBE.</p>
 */
class NetworkMalformedInputTest {

    private static final BlockPos POS = new BlockPos(1, 2, 3);
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    // ═══════════ P0-1：GraphPresencePacket 多选数组先校验后分配 ═══════════

    /** 按解码顺序手工构造 presence 包头部，count 字段取给定值（后续字段留空供越界测试）。 */
    private static ByteBuf presenceBufWithCount(int count) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        b.writeBlockPos(POS);
        b.writeLong(0L); b.writeLong(0L);       // player UUID
        b.writeUtf("mallory");
        b.writeVarInt(-1);                      // ownerNodeId
        b.writeFloat(0f); b.writeFloat(0f);     // cursor
        b.writeVarInt(-1); b.writeVarInt(-1);   // selected / editing
        b.writeVarInt(-1); b.writeVarInt(0);    // wireFromNode / wireFromPin
        b.writeFloat(0f); b.writeFloat(0f);     // wireEnd
        b.writeVarInt(count);
        return b;
    }

    @Test
    @DisplayName("presence: count 声明 2^31-1 不分配数组，抛 DecoderException（P0-1）")
    void presenceHugeCountIsProtocolError() {
        ByteBuf buf = presenceBufWithCount(Integer.MAX_VALUE);
        try {
            assertThrows(DecoderException.class, () -> GraphPresencePacket.CODEC.decode(buf),
                "声明的 count 不得直接成为分配长度");
        } finally { buf.release(); }
    }

    @Test
    @DisplayName("presence: count 超上限/负数同为协议错误")
    void presenceOverLimitAndNegativeCountRejected() {
        for (int bad : new int[]{GraphPresencePacket.MAX_SELECTED_NODE_IDS + 1, -1}) {
            ByteBuf buf = presenceBufWithCount(bad);
            try {
                assertThrows(DecoderException.class, () -> GraphPresencePacket.CODEC.decode(buf),
                    "count=" + bad + " 必须拒绝");
            } finally { buf.release(); }
        }
    }

    @Test
    @DisplayName("presence: 合法包 round-trip 不受钳制影响")
    void presenceRoundTripUnaffected() {
        var pkt = new GraphPresencePacket(POS, ACTOR, "alice", -1, 1f, 2f, 3, 4, 5, 6, 7f, 8f,
            new int[]{10, 20, 30}, (byte) 1, 42, true);
        ByteBuf buf = Unpooled.buffer();
        try {
            GraphPresencePacket.CODEC.encode(buf, pkt);
            var out = GraphPresencePacket.CODEC.decode(buf);
            assertEquals(pkt.pos(), out.pos());
            assertEquals(pkt.player(), out.player());
            assertEquals(pkt.playerName(), out.playerName());
            assertArrayEquals(new int[]{10, 20, 30}, out.selectedNodeIds());
            assertEquals((byte) 1, out.mode());
            assertEquals(42, out.displayDraggedNodeId());
            assertTrue(out.editingGraphName());
        } finally { buf.release(); }
    }

    // ═══════════ P0-2/P0-1：BlobDataPacket 分片长度与总数先校验后分配 ═══════════

    /** 手工构造 blob 分片包字节（绕过 encode，便于注入畸形 total/len）。 */
    private static ByteBuf blobBuf(int total, int len, int payloadBytes) {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        BlockPos.STREAM_CODEC.encode(b, POS);
        VarInt.write(b, 7);                 // blobId
        VarInt.write(b, BlobType.SONG_BYTES.id);
        VarInt.write(b, 0);                 // nodeId
        VarInt.write(b, 0);                 // seq
        VarInt.write(b, total);
        VarInt.write(b, len);
        b.writeZero(payloadBytes);
        return b;
    }

    @Test
    @DisplayName("blob: totalChunks 超上限不分配引用数组，抛 DecoderException（P0-2）")
    void blobTotalChunksOverCapIsProtocolError() {
        ByteBuf buf = blobBuf(BlobDataPacket.MAX_TOTAL_CHUNKS + 1, 4, 0);
        try {
            assertThrows(DecoderException.class, () -> BlobDataPacket.CODEC.decode(buf),
                "声明 2^31-1 片也不得分配 byte[][]");
        } finally { buf.release(); }
    }

    @Test
    @DisplayName("blob: 分片长度超 30KB 上限/负数为协议错误（P0-1）")
    void blobChunkLengthOverCapIsProtocolError() {
        ByteBuf big = blobBuf(1, BlobDataPacket.MAX_CHUNK_SIZE + 1, 0);
        try {
            assertThrows(DecoderException.class, () -> BlobDataPacket.CODEC.decode(big));
        } finally { big.release(); }
        ByteBuf negative = blobBuf(1, -1, 0);
        try {
            assertThrows(DecoderException.class, () -> BlobDataPacket.CODEC.decode(negative));
        } finally { negative.release(); }
    }

    @Test
    @DisplayName("blob: 恰好 30KB 的合法分片仍可解码")
    void blobMaxSizeChunkStillDecodes() {
        int len = BlobDataPacket.MAX_CHUNK_SIZE;
        ByteBuf buf = blobBuf(1, len, len);
        try {
            var pkt = BlobDataPacket.CODEC.decode(buf);
            assertEquals(len, pkt.chunkData().length);
            assertEquals(1, pkt.totalChunks());
        } finally { buf.release(); }
    }

    // ═══════════ P0-1：GraphEditOpPacket imageData 先校验后分配 ═══════════

    /** 编码一个无 imageData 的 op，返回其字节（imageData 是最后一个字段，便于尾部改写）。 */
    private static byte[] encodedOpTailReady() {
        var op = GraphOp.setParam(POS, -1, 5, 0, 1f, null, ACTOR);
        ByteBuf buf = Unpooled.buffer();
        try {
            GraphEditOpPacket.CODEC.encode(buf, new GraphEditOpPacket(op));
            byte[] out = new byte[buf.readableBytes()];
            buf.readBytes(out);
            return out;
        } finally { buf.release(); }
    }

    @Test
    @DisplayName("op 包: imageData 声明长度超画布上限（32×32）抛 DecoderException（P0-1）")
    void opImageDataOverCapIsProtocolError() {
        byte[] base = encodedOpTailReady(); // 尾部为 VarInt(0)（无图像哨兵，1 字节）
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.writeBytes(base, 0, base.length - 1);
            VarInt.write(buf, 1025);
            assertThrows(DecoderException.class, () -> GraphEditOpPacket.CODEC.decode(buf),
                "imageData 不得按声明长度分配");
        } finally { buf.release(); }
    }

    @Test
    @DisplayName("op 包: 恰好画布上限（1024 像素）仍可解码")
    void opImageDataAtCanvasCapStillDecodes() {
        byte[] base = encodedOpTailReady();
        ByteBuf buf = Unpooled.buffer();
        try {
            buf.writeBytes(base, 0, base.length - 1);
            VarInt.write(buf, 1024);
            for (int i = 0; i < 1024; i++) buf.writeInt(i);
            var pkt = GraphEditOpPacket.CODEC.decode(buf);
            assertEquals(1024, pkt.op().imageData().length);
        } finally { buf.release(); }
    }

    // ═══════════ P2-9：未知 op 序号优雅拒绝（不断连） ═══════════

    @Test
    @DisplayName("op 包: 未知序号解码为 REJECT 载体并记录原始序号，不断连")
    void unknownOpOrdinalDecodesGracefully() {
        var op = GraphOp.addNodeRequest(POS, -1, 3, null, 0f, 0f, ACTOR); // ordinal 0 → 1 字节 varint
        ByteBuf buf = Unpooled.buffer();
        try {
            GraphEditOpPacket.CODEC.encode(buf, new GraphEditOpPacket(op));
            byte[] rest = new byte[buf.readableBytes() - 1];
            buf.skipBytes(1);
            buf.readBytes(rest);

            ByteBuf forged = Unpooled.buffer();
            try {
                VarInt.write(forged, 9999);   // 越界序号（改包/更新版客户端）
                forged.writeBytes(rest);
                var pkt = GraphEditOpPacket.CODEC.decode(forged);
                assertEquals(OpType.REJECT, pkt.op().type(), "未知序号落地为 REJECT 载体");
                assertEquals(9999, pkt.unknownWireOrdinal(), "原始线缆序号必须保留供回执");
                assertEquals(POS, pkt.op().graphPos(), "其余字段按位置读满，流不错位");
                assertEquals(3, pkt.op().tempId());
            } finally { forged.release(); }
        } finally { buf.release(); }
    }

    @Test
    @DisplayName("op 包: 未知序号的 REJECT 回执 paramIndex 携带线缆原始序号")
    void rejectUnknownOrdinalCarriesWireOrdinal() {
        var carrier = GraphOp.setParam(POS, -1, 5, 7, 0f, null, ACTOR);
        var receipt = GraphOp.rejectUnknownOrdinal(carrier, 9999, ACTOR);
        assertEquals(OpType.REJECT, receipt.type());
        assertEquals(9999, receipt.paramIndex(), "paramIndex 必须回显线缆原始序号（对端按自己的枚举定位）");
        assertEquals(carrier.tempId(), receipt.tempId());
    }

    // ═══════════ K15：越界索引前置拒收（REJECT 决策） ═══════════

    @Test
    @DisplayName("K15 预检: 热栏槽位 9+ / 负数拒绝；缺物品的伪造 op 拒绝")
    void k15HotbarSlotBounds() {
        // headless 无法构造非空 ItemStack —— 界内放行断言仅能以"违规消息区分"方式覆盖：
        // 槽位检查先于物品检查，因此槽位违规不受 itemStack=null 干扰。
        // A non-null ItemStack cannot be built headless — in-bounds pass-through is covered by
        // the violation-message split: the slot check precedes the item check, so slot
        // violations are unaffected by itemStack=null.
        for (int bad : new int[]{9, 100, -1})
            assertTrue(GraphEditOpPacket.opBoundsViolation(
                    GraphOp.setHotbarItem(POS, -1, 1, bad, null, ACTOR)).startsWith("hotbarSlot="),
                "slot=" + bad + " 必须以槽位违规拒绝");
        assertEquals("itemStack=null", GraphEditOpPacket.opBoundsViolation(
                GraphOp.setHotbarItem(POS, -1, 1, 0, null, ACTOR)),
            "缺物品的 SET_HOTBAR_ITEM（伪造旗标）必须拒绝——落 null 槽会让 GraphNode.save NPE");
    }

    @Test
    @DisplayName("K15 预检: 像素帧索引越界拒绝（执行器上限 MAX_SEQUENCE_FRAMES）")
    void k15FrameIndexBounds() {
        int[] pixels = new int[]{1, 2, 3, 4};
        assertNotNull(GraphEditOpPacket.opBoundsViolation(
                GraphOp.setImagePixels(POS, -1, 1, OpExecutor.MAX_SEQUENCE_FRAMES + 1, pixels, ACTOR)),
            "超限帧索引必须拒绝");
        assertNotNull(GraphEditOpPacket.opBoundsViolation(
                GraphOp.setImagePixels(POS, -1, 1, -1, pixels, ACTOR)),
            "负帧索引必须拒绝（原实现 set(fi) 会 IOOBE）");
        assertNull(GraphEditOpPacket.opBoundsViolation(
                GraphOp.setImagePixels(POS, -1, 1, 0, pixels, ACTOR)));
    }

    @Test
    @DisplayName("K15 预检: 控制点数超上限拒绝，上限内放行")
    void k15CtrlPointsBounds() {
        float[] over = new float[GraphOp.MAX_CTRL_POINTS + 1];
        java.util.Arrays.fill(over, 1f);
        assertNotNull(GraphEditOpPacket.opBoundsViolation(
                GraphOp.setCtrlPoints(POS, -1, 1, over, over, ACTOR)));
        float[] ok = new float[GraphOp.MAX_CTRL_POINTS];
        java.util.Arrays.fill(ok, 1f);
        assertNull(GraphEditOpPacket.opBoundsViolation(
                GraphOp.setCtrlPoints(POS, -1, 1, ok, ok, ACTOR)));
    }

    @Test
    @DisplayName("K15 预检: 其他 op 一律放行")
    void k15OtherOpsPass() {
        assertNull(GraphEditOpPacket.opBoundsViolation(GraphOp.moveNode(POS, -1, 1, 0, 0, ACTOR)));
        assertNull(GraphEditOpPacket.opBoundsViolation(GraphOp.setParam(POS, -1, 1, 0, 5f, null, ACTOR)));
    }
}
