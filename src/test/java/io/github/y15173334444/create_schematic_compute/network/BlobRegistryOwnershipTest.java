package io.github.y15173334444.create_schematic_compute.network;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-2 回归：BlobRegistry 归属作用域与容量防御。
 * <p>P0-2 regressions: BlobRegistry ownership scoping and capacity defenses.</p>
 *
 * <p>安全语义：blobId 由客户端自选且曾为全局命名空间 —— 玩家 A 用自选 id 预置字节、
 * 玩家 B 的 SET_SONG 按 id poll 即可拿到 A 的字节（同 id 跨玩家投毒）。修复后条目按
 * (提交者, blobId) 分键，poll/peek 校验请求者；无归属条目只在无作用域时可见（fail-closed）。</p>
 * <p>Security semantics: blobId is client-chosen and used to be a global namespace — player A
 * could pre-plant bytes under a self-chosen id and player B's SET_SONG would poll them by id
 * (same-id cross-player poisoning). Entries are now keyed by (submitter, blobId); poll/peek
 * verify the requester; owner-less entries are only visible without a scope (fail-closed).</p>
 */
class BlobRegistryOwnershipTest {

    private static final BlockPos POS = new BlockPos(4, 5, 6);
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID MALLORY = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    /** 直喂一个单分片 blob（绕过网络，等价 BlobPacketHandler 校验通过后的路径）。 */
    private static byte[] feed(int blobId, byte[] data, UUID owner) {
        var pkt = new BlobDataPacket(POS, blobId, BlobType.SONG_BYTES, 0, 0, 1, data);
        return BlobRegistry.receive(pkt, owner);
    }

    @Test
    @DisplayName("归属: poll/peek 只取到同一提交者的 blob")
    void pollOnlyReturnsSameSubmittersBlob() {
        byte[] poison = {1, 1, 1, 1};
        assertNotNull(feed(101, poison, ALICE), "夹具前提：Alice 上传完成");

        assertNull(BlobRegistry.poll(101, MALLORY), "Mallory 不得取走 Alice 的 blob");
        assertNull(BlobRegistry.peek(101, MALLORY));
        assertArrayEquals(poison, BlobRegistry.peek(101, ALICE), "Alice 自己可见");
        assertArrayEquals(poison, BlobRegistry.poll(101, ALICE), "Alice 可取走");
        assertNull(BlobRegistry.poll(101, ALICE), "取后即删");
    }

    @Test
    @DisplayName("归属: 线程作用域绑定请求者（handleServer 的绑定方式）")
    void threadScopeBindsRequester() {
        byte[] data = {2, 2, 2, 2};
        assertNotNull(feed(102, data, ALICE));
        assertNull(BlobRegistry.poll(102), "无作用域时 fail-closed：归属条目不可见");
        BlobRegistry.beginOwnerScope(MALLORY);
        try {
            assertNull(BlobRegistry.poll(102), "作用域内非提交者不可见");
            BlobRegistry.endOwnerScope();
            BlobRegistry.beginOwnerScope(ALICE);
            assertArrayEquals(data, BlobRegistry.poll(102), "作用域内提交者可见");
        } finally {
            BlobRegistry.endOwnerScope();
        }
        assertNull(BlobRegistry.poll(102), "取后即删");
    }

    @Test
    @DisplayName("归属: 同 id 分片互不串包（A/B 各自重组各自的数据）")
    void sameIdChunksDoNotMixAcrossPlayers() {
        // Mallory 先用同一 blobId 预置一个 2 片未完成 blob
        var m1 = new BlobDataPacket(POS, 103, BlobType.SONG_BYTES, 0, 0, 2, new byte[]{9, 9});
        BlobRegistry.receive(m1, MALLORY);
        // Alice 用同一 blobId 上传 1 片完整数据
        byte[] aliceData = {7, 7, 7, 7};
        assertNotNull(feed(103, aliceData, ALICE), "Alice 的单片 blob 独立完成");
        assertArrayEquals(aliceData, BlobRegistry.poll(103, ALICE));
        // Mallory 的 pending 未受影响：补齐第 2 片后按自己的字节重组
        var m2 = new BlobDataPacket(POS, 103, BlobType.SONG_BYTES, 0, 1, 2, new byte[]{8, 8});
        assertNotNull(BlobRegistry.receive(m2, MALLORY));
        assertArrayEquals(new byte[]{9, 9, 8, 8}, BlobRegistry.poll(103, MALLORY));
    }

    @Test
    @DisplayName("容量: 绕过解码直构造的超限 totalChunks 包在 receive 层再拒一道")
    void receiveRefusesOversizedTotalWithoutAllocating() {
        var evil = new BlobDataPacket(POS, 104, BlobType.SONG_BYTES, 0, 0,
            BlobDataPacket.MAX_TOTAL_CHUNKS + 1, new byte[]{1});
        assertNull(BlobRegistry.receive(evil, ALICE), "超限分片总数直接拒绝");
        assertNull(BlobRegistry.poll(104, ALICE), "拒绝后不得留下任何条目");
        var negative = new BlobDataPacket(POS, 105, BlobType.SONG_BYTES, 0, 0, -3, new byte[]{1});
        assertNull(BlobRegistry.receive(negative, ALICE));
    }

    @Test
    @DisplayName("无归属条目: 无作用域可见（客户端 S2C 中继 / 测试直喂路径不受影响）")
    void ownerlessEntriesStayVisibleWithoutScope() {
        byte[] relayed = {3, 3, 3, 3};
        assertNotNull(feed(106, relayed, null), "客户端中继路径 = 无归属接收");
        assertArrayEquals(relayed, BlobRegistry.poll(106), "无作用域请求者可取无归属条目");
    }
}
