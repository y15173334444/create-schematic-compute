package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.network.GraphDragStreamPacket.Entry;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 拖动临场流批 1 的协议与预算钉子：codec 往返（两种条目种类 + S→C 包装）、P0-1 先校验后分配
 * （条目数声明超限/负数 → DecoderException 断连接，绝不按声明值分配）、DRAG_STREAM 桶
 * （burst 20 / refill 10 ≥ 客户端 10/s 发送速率 → 合法拖拽者零拒绝；持续超限仍会走到断开）。
 * Batch-1 pins for the drag presence stream: codec round-trip (both entry kinds + the S2C
 * wrapper), the P0-1 validate-before-allocate clamp (over-limit/negative declared counts →
 * DecoderException drops the connection, never allocated at the declared length), and the
 * DRAG_STREAM bucket (burst 20 / refill 10 ≥ the client's 10/s send rate → zero rejections
 * for a legitimate dragger; sustained over-limit still reaches DISCONNECT).
 */
class GraphDragStreamPacketTest {

    private static final BlockPos POS = new BlockPos(12, 64, -34);
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000042a0");

    @Test
    @DisplayName("codec round-trips both entry kinds / codec 往返两种条目种类")
    void codecRoundTrip() {
        var pkt = new GraphDragStreamPacket(POS, ACTOR, -1, List.of(
            Entry.pos(7, 12.5f, -3.25f),
            Entry.commentSize(9, 400f, 300f),
            Entry.pos(1024, 0.125f, 2048f)));
        var buf = Unpooled.buffer();
        GraphDragStreamPacket.CODEC.encode(buf, pkt);
        var out = GraphDragStreamPacket.CODEC.decode(buf);

        assertEquals(POS, out.pos());
        assertEquals(ACTOR, out.player());
        assertEquals(-1, out.ownerNodeId());
        assertEquals(3, out.entries().size());
        assertEquals(Entry.pos(7, 12.5f, -3.25f), out.entries().get(0));
        assertEquals(Entry.commentSize(9, 400f, 300f), out.entries().get(1));
        assertEquals(Entry.pos(1024, 0.125f, 2048f), out.entries().get(2));
        assertEquals(GraphDragStreamPacket.KIND_NODE_POS, out.entries().get(0).kind());
        assertEquals(GraphDragStreamPacket.KIND_COMMENT_SIZE, out.entries().get(1).kind());
        assertTrue(buf.readableBytes() == 0, "buffer fully consumed");
    }

    @Test
    @DisplayName("S2C wrapper shares the codec verbatim / S→C 包装与 C2S 编解码逐字同源")
    void syncWrapperRoundTrip() {
        var pkt = new GraphDragStreamPacket(POS, ACTOR, 5, List.of(Entry.pos(3, 1f, 2f)));
        var buf = Unpooled.buffer();
        GraphDragStreamSyncPacket.CODEC.encode(buf, new GraphDragStreamSyncPacket(pkt));
        var out = GraphDragStreamSyncPacket.CODEC.decode(buf);
        assertEquals(pkt, out.inner());
    }

    @Test
    @DisplayName("P0-1: over-limit declared count throws, never allocated / P0-1：声明超限抛异常，绝不按声明分配")
    void decodeRejectsOverLimitCount() {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        b.writeBlockPos(POS);
        b.writeLong(1L);
        b.writeLong(2L);
        b.writeVarInt(-1);
        b.writeVarInt(GraphDragStreamPacket.MAX_ENTRIES_PER_PACKET + 1);
        var ex = assertThrows(io.netty.handler.codec.DecoderException.class,
            () -> GraphDragStreamPacket.CODEC.decode(b));
        assertTrue(ex.getMessage().contains("entries count out of bounds"));
    }

    @Test
    @DisplayName("P0-1: negative declared count throws / P0-1：负数声明抛异常")
    void decodeRejectsNegativeCount() {
        var b = new FriendlyByteBuf(Unpooled.buffer());
        b.writeBlockPos(POS);
        b.writeLong(1L);
        b.writeLong(2L);
        b.writeVarInt(-1);
        b.writeVarInt(-1);
        assertThrows(io.netty.handler.codec.DecoderException.class,
            () -> GraphDragStreamPacket.CODEC.decode(b));
    }

    @Test
    @DisplayName("512 条正好在限内（图容量 1024 靠旋转续传分两窗）/ exactly 512 entries decode fine")
    void decodeAcceptsExactlyAtCap() {
        var es = new java.util.ArrayList<Entry>();
        for (int i = 0; i < GraphDragStreamPacket.MAX_ENTRIES_PER_PACKET; i++) es.add(Entry.pos(i, i, i));
        var buf = Unpooled.buffer();
        GraphDragStreamPacket.CODEC.encode(buf, new GraphDragStreamPacket(POS, ACTOR, -1, es));
        var out = GraphDragStreamPacket.CODEC.decode(buf);
        assertEquals(GraphDragStreamPacket.MAX_ENTRIES_PER_PACKET, out.entries().size());
    }

    @Test
    @DisplayName("DRAG_STREAM bucket: burst 20 then reject, refill 10 per second / DRAG_STREAM 桶：突发 20 后拒绝，每秒回填 10")
    void dragStreamBucketBurstAndRefill() {
        long[] t = {0L};
        var guard = new C2SPacketRateGuard(() -> t[0]);
        for (int i = 0; i < C2SPacketRateGuard.DRAG_STREAM.burstTokens(); i++)
            assertEquals(C2SPacketRateGuard.Verdict.ALLOWED,
                guard.check("p", "graph_drag_stream", C2SPacketRateGuard.DRAG_STREAM));
        assertEquals(C2SPacketRateGuard.Verdict.REJECTED,
            guard.check("p", "graph_drag_stream", C2SPacketRateGuard.DRAG_STREAM));
        // 1 秒后回填 10 个令牌：恰好放行 10 个再拒。 / One second later 10 tokens refilled:
        // exactly 10 more pass, then reject again.
        t[0] += 1_000_000_000L;
        for (int i = 0; i < 10; i++)
            assertEquals(C2SPacketRateGuard.Verdict.ALLOWED,
                guard.check("p", "graph_drag_stream", C2SPacketRateGuard.DRAG_STREAM));
        assertEquals(C2SPacketRateGuard.Verdict.REJECTED,
            guard.check("p", "graph_drag_stream", C2SPacketRateGuard.DRAG_STREAM));
    }

    @Test
    @DisplayName("DRAG_STREAM 桶在合法 10/s 拖拽节奏下恒满零拒绝 / bucket stays full at the legitimate 10/s cadence")
    void legitimateDraggerIsNeverRejected() {
        long[] t = {0L};
        var guard = new C2SPacketRateGuard(() -> t[0]);
        // 60 秒、每 100ms 一包（= 客户端节流速率）→ 每次都放行。 / 60 s at one packet per
        // 100 ms (the client throttle rate) → every single packet passes.
        for (int i = 0; i < 600; i++) {
            t[0] += 100_000_000L;
            assertEquals(C2SPacketRateGuard.Verdict.ALLOWED,
                guard.check("p", "graph_drag_stream", C2SPacketRateGuard.DRAG_STREAM),
                "a paced dragger must never be rejected (packet #" + i + ")");
        }
    }

    @Test
    @DisplayName("持续超限仍会断开（限流器语义不在本批放宽）/ sustained flood still disconnects")
    void sustainedFloodStillDisconnects() {
        long[] t = {0L};
        var guard = new C2SPacketRateGuard(() -> t[0]);
        // 50/s 机速灌 2 秒 → strike 累计必须触达 DISCONNECT。 / Machine-rate 50/s for 2 s →
        // strikes must reach DISCONNECT.
        boolean disconnected = false;
        for (int i = 0; i < 100 && !disconnected; i++) {
            t[0] += 20_000_000L;
            disconnected = guard.check("p", "graph_drag_stream", C2SPacketRateGuard.DRAG_STREAM)
                == C2SPacketRateGuard.Verdict.DISCONNECT;
        }
        assertTrue(disconnected, "a 50/s flooder must reach DISCONNECT within 2 s");
    }
}
