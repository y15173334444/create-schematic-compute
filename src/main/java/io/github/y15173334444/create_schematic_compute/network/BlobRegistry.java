package io.github.y15173334444.create_schematic_compute.network;

import net.minecraft.core.BlockPos;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chunk reassembly cache for {@link BlobDataPacket}.
 * <p>{@link BlobDataPacket} 的分片重组缓存。</p>
 *
 * <p>Both server and client maintain independent instances (static fields are
 * per-JVM). Completed blobs return their reassembled byte[]; unfinished blobs
 * expire after 30 seconds.
 * 服务端和客户端各自维护独立的实例（静态字段按 JVM 隔离）。完整 blob 返回重组后的 byte[]；未完成的 blob 在 30 秒后过期。</p>
 *
 * <p><b>P0-2 归属作用域 / P0-2 ownership scoping</b>：blobId 由客户端自选，全局命名空间下
 * 玩家 A 可用自选 id 预置字节、玩家 B 的 SET_SONG 按 id 取走 —— 同 id 跨玩家串包投毒。
 * 因此每个 PENDING/COMPLETED 条目记录**提交者**（服务端入站路径经
 * {@link BlobPacketHandler#handleServer} 传入已认证的玩家 UUID），取用方经
 * {@link #beginOwnerScope(UUID)} 绑定请求者身份（{@code GraphEditOpPacket.handleServer}
 * 在 {@code applyOp} 外层绑定），poll/peek 只能取到**同一位提交者**的条目。
 * 无归属条目（客户端 S2C 中继 / 测试直喂）只在**无作用域**时可取 —— fail-closed：
 * 服务端任何绕过作用域的取用都拿不到归属数据。
 * <p><b>P0-2 ownership scoping</b>: blobId is client-chosen and globally namespaced, so
 * player A can pre-plant bytes under a self-chosen id and player B's SET_SONG would poll
 * them by id — same-id cross-player poisoning. Every PENDING/COMPLETED entry therefore
 * records its <b>submitter</b> (the server inbound path passes the authenticated player UUID
 * via {@link BlobPacketHandler#handleServer}), and consumers bind the requester via
 * {@link #beginOwnerScope(UUID)} ({@code GraphEditOpPacket.handleServer} wraps {@code applyOp}
 * with it); poll/peek only return entries submitted by the <b>same</b> player. Owner-less
 * entries (client S2C relays / test feeds) are only visible when <b>no scope</b> is bound —
 * fail-closed: any scope-bypassing server lookup gets nothing.</p>
 *
 * <p><b>P0-2 容量上限 / P0-2 capacity caps</b>：分片总数在解码层钳到
 * {@link BlobDataPacket#MAX_TOTAL_CHUNKS}（64）；此处对注册表条目数再加软上限
 * （30 秒窗口内恶意刷 pending/completed 的占内存面被收敛）。
 * <p><b>P0-2 capacity caps</b>: the chunk count is clamped at decode time to
 * {@link BlobDataPacket#MAX_TOTAL_CHUNKS} (64); this layer additionally caps the number of
 * live entries (bounding the memory a 30-second flood of pending/completed blobs can hold).</p>
 */
public final class BlobRegistry {

    private BlobRegistry() {}

    /**
     * 本地 logger（LogUtils 直取，不经 {@code SchematicCompute.<clinit>}）：receive 的
     * 拒绝路径在 headless 测试（JUnit 直喂分片）中也会走到，引用 SchematicCompute 的
     * 静态 logger 会触发注册表引导（DeferredRegister <clinit>）直接炸测试 JVM。
     * <p>Local logger (fetched via LogUtils directly, bypassing
     * {@code SchematicCompute.<clinit>}): receive's refusal paths are reachable from headless
     * tests (JUnit feeding chunks directly), and referencing SchematicCompute's static logger
     * would trigger registry bootstrap (DeferredRegister <clinit>) and blow up the test JVM.</p>
     */
    private static final org.slf4j.Logger LOGGER =
        com.mojang.logging.LogUtils.getLogger();

    /** 单层条目软上限（pending 与 completed 各自独立计数）。远超正常协作规模
     *  （一次曲目上传 = 1 条），仅拦截恶意刷条目。
     *  Soft cap on live entries (pending and completed counted separately). Far beyond normal
     *  collaboration scale (one song upload = one entry); it only curbs malicious flooding. */
    private static final int MAX_ENTRIES = 256;

    /**
     * 归属复合键：(提交者 UUID, 客户端自选 blobId)。owner 可为 null（无归属条目），
     * record 的 equals/hashCode 恰好支持 null 分量作 HashMap 键。
     * <p>Ownership composite key: (submitter UUID, client-chosen blobId). owner may be null
     * (owner-less entries); record equals/hashCode supports null components as map keys.</p>
     */
    private record BlobKey(UUID owner, int blobId) {}

    private static final ConcurrentHashMap<BlobKey, PendingBlob> PENDING = new ConcurrentHashMap<>();
    /** 已重组完成的 blob（{@code GraphOp.blobRefId} 按引用取用；30 秒过期自清）。
     *  Reassembled blobs, consumed by reference from {@code GraphOp.blobRefId} (self-expiring after 30 s). */
    private static final ConcurrentHashMap<BlobKey, CompletedBlob> COMPLETED = new ConcurrentHashMap<>();
    private static final long TIMEOUT_MS = 30_000;

    /**
     * 当前取用请求者（线程作用域）。{@code GraphEditOpPacket.handleServer} 在 applyOp 外层
     * 绑定发送者身份；null = 无作用域（客户端路径 / 测试），只能取到无归属条目。
     * <p>The current lookup requester (thread scope). {@code GraphEditOpPacket.handleServer}
     * binds the sender around applyOp; null = no scope (client path / tests), which only sees
     * owner-less entries.</p>
     */
    private static final ThreadLocal<UUID> REQUESTER = new ThreadLocal<>();

    /** 绑定本次取用的请求者身份（配对 {@link #endOwnerScope}，必须 try/finally）。
     *  Bind the requester identity for subsequent lookups (paired with {@link #endOwnerScope};
     *  always use try/finally). */
    public static void beginOwnerScope(UUID requester) { REQUESTER.set(requester); }

    /** 清除归属作用域（finally 中调用）。/ Clear the ownership scope (call in finally). */
    public static void endOwnerScope() { REQUESTER.remove(); }

    private static final class CompletedBlob {
        final byte[] data;
        final UUID owner;
        final long expiresAt;
        CompletedBlob(byte[] data, UUID owner) {
            this.data = data; this.owner = owner;
            this.expiresAt = System.currentTimeMillis() + TIMEOUT_MS;
        }
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    private static class PendingBlob {
        final BlockPos pos;
        final BlobType type;
        final int nodeId;
        final UUID owner;
        final byte[][] chunks;
        int received;
        long expiresAt;

        PendingBlob(BlobDataPacket pkt, UUID owner) {
            this.pos = pkt.pos();
            this.type = pkt.blobType();
            this.nodeId = pkt.nodeId();
            this.owner = owner;
            this.chunks = new byte[pkt.totalChunks()][];
            this.received = 0;
            touch();
        }

        void touch() { this.expiresAt = System.currentTimeMillis() + TIMEOUT_MS; }

        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

    /**
     * Feed a chunk into the registry. Returns the reassembled complete byte[]
     * when all chunks have arrived, or null if the blob is still incomplete.
     * 向注册表送入一个分片。所有分片到齐后返回重组完成的 byte[]，若 blob 仍不完整则返回 null。
     *
     * <p>无归属重载（客户端 S2C 中继 / 测试直喂使用）。
     * Owner-less overload (used by the client S2C relay and direct test feeds).</p>
     */
    public static byte[] receive(BlobDataPacket pkt) {
        return receive(pkt, null);
    }

    /**
     * 带归属地送入一个分片（服务端入站路径使用，owner = 已认证的提交者）。
     * 返回重组完成的 byte[]（全部到齐时），否则 null。
     * Feed a chunk with ownership (server inbound path; owner = the authenticated submitter).
     * Returns the reassembled byte[] when complete, null otherwise.
     *
     * <p>防御性钳制：解码层已钳 totalChunks（协议错误），此处对**绕过解码直构造**的包
     * 再拦一道 —— 超限 / 非正总数直接拒绝，绝不分配引用数组（P0-2）。
     * <p>Defensive clamp: the decoder already caps totalChunks (protocol error); this is the
     * second line against packets built **directly, bypassing decode** — over-limit or
     * non-positive totals are refused outright, never allocating the reference array (P0-2).</p>
     */
    public static byte[] receive(BlobDataPacket pkt, UUID owner) {
        int total = pkt.totalChunks();
        if (total <= 0 || total > BlobDataPacket.MAX_TOTAL_CHUNKS) {
            LOGGER.debug("BlobRegistry: refused blob {} with totalChunks={}",
                pkt.blobId(), total);
            return null;
        }
        BlobKey key = new BlobKey(owner, pkt.blobId());
        if (!PENDING.containsKey(key) && PENDING.size() >= MAX_ENTRIES) {
            LOGGER.debug("BlobRegistry: pending entries at cap ({}), refusing blob {}",
                MAX_ENTRIES, pkt.blobId());
            return null;
        }
        PendingBlob pending = PENDING.computeIfAbsent(key, k -> new PendingBlob(pkt, owner));
        if (pkt.sequenceNum() < 0 || pkt.sequenceNum() >= pending.chunks.length) return null;
        if (pending.chunks[pkt.sequenceNum()] == null) {
            pending.chunks[pkt.sequenceNum()] = pkt.chunkData();
            pending.received++;
        }
        pending.touch();

        if (pending.received == pending.chunks.length) {
            PENDING.remove(key);
            byte[] full = reassemble(pending.chunks);
            if (COMPLETED.size() >= MAX_ENTRIES && !COMPLETED.containsKey(key)) {
                LOGGER.debug("BlobRegistry: completed entries at cap ({}), dropping blob {}",
                    MAX_ENTRIES, pkt.blobId());
                return full;
            }
            COMPLETED.put(key, new CompletedBlob(full, owner));
            return full;
        }
        return null;
    }

    /**
     * 按句柄取已重组完成的字节（不消费；过期未取返回 null）。
     * Fetch a reassembled blob by handle without consuming it (null once expired).
     *
     * <p>归属校验：请求者取线程作用域身份（见 {@link #beginOwnerScope}），只能看见
     * 同一提交者的条目（P0-2）。/ Ownership check: the requester is read from the thread
     * scope (see {@link #beginOwnerScope}) and only same-submitter entries are visible (P0-2).</p>
     */
    public static byte[] peek(int blobId) {
        return peek(blobId, REQUESTER.get());
    }

    /** 显式请求者变体（测试 / 非线程作用域调用方使用）。/ Explicit-requester variant (tests / non-scoped callers). */
    public static byte[] peek(int blobId, UUID requester) {
        CompletedBlob b = COMPLETED.get(new BlobKey(requester, blobId));
        if (b == null) return null;
        if (b.isExpired()) { COMPLETED.remove(new BlobKey(requester, blobId)); return null; }
        return b.data;
    }

    /**
     * 按句柄取走已重组完成的字节（取后删除）。
     * Fetch and remove a reassembled blob by handle.
     *
     * <p>归属校验同 {@link #peek(int)}：请求者只能取走自己上传的 blob（P0-2 串包投毒
     * 消除；fail-closed —— 绕过作用域的服务端取用拿不到归属数据）。
     * Ownership as in {@link #peek(int)}: a requester can only take blobs it uploaded itself
     * (P0-2 cross-player poisoning eliminated; fail-closed — scope-bypassing server lookups
     * get nothing).</p>
     */
    public static byte[] poll(int blobId) {
        return poll(blobId, REQUESTER.get());
    }

    /** 显式请求者变体（测试 / 非线程作用域调用方使用）。/ Explicit-requester variant (tests / non-scoped callers). */
    public static byte[] poll(int blobId, UUID requester) {
        BlobKey key = new BlobKey(requester, blobId);
        CompletedBlob b = COMPLETED.remove(key);
        return b == null || b.isExpired() ? null : b.data;
    }

    /** Remove expired incomplete blobs. Call periodically (e.g. each tick). / 移除过期的未完成 blob。周期性调用（如每 tick）。 */
    public static void cleanup() {
        var it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue().isExpired()) {
                LOGGER.debug("BlobRegistry: expired blob {}", e.getKey());
                it.remove();
            }
        }
        var it2 = COMPLETED.entrySet().iterator();
        while (it2.hasNext()) {
            if (it2.next().getValue().isExpired()) it2.remove();
        }
    }

    private static byte[] reassemble(byte[][] chunks) {
        int totalLen = 0;
        for (byte[] c : chunks) totalLen += c != null ? c.length : 0;
        byte[] result = new byte[totalLen];
        int offset = 0;
        for (byte[] c : chunks) {
            if (c != null) {
                System.arraycopy(c, 0, result, offset, c.length);
                offset += c.length;
            }
        }
        return result;
    }
}
