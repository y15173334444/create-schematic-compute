package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.core.BlockPos;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Chunk reassembly cache for {@link BlobDataPacket}.
 * <p>{@link BlobDataPacket} 的分片重组缓存。</p>
 *
 * <p>Both server and client maintain independent instances (static fields are
 * per-JVM). Completed blobs return their reassembled byte[]; unfinished blobs
 * expire after 30 seconds.
 * 服务端和客户端各自维护独立的实例（静态字段按 JVM 隔离）。完整 blob 返回重组后的 byte[]；未完成的 blob 在 30 秒后过期。</p>
 */
public final class BlobRegistry {

    private BlobRegistry() {}

    private static final ConcurrentHashMap<Integer, PendingBlob> PENDING = new ConcurrentHashMap<>();
    private static final long TIMEOUT_MS = 30_000;

    private static class PendingBlob {
        final BlockPos pos;
        final BlobType type;
        final int nodeId;
        final byte[][] chunks;
        int received;
        long expiresAt;

        PendingBlob(BlobDataPacket pkt) {
            this.pos = pkt.pos();
            this.type = pkt.blobType();
            this.nodeId = pkt.nodeId();
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
     */
    public static byte[] receive(BlobDataPacket pkt) {
        int id = pkt.blobId();
        PendingBlob pending = PENDING.computeIfAbsent(id, k -> new PendingBlob(pkt));
        if (pkt.sequenceNum() < 0 || pkt.sequenceNum() >= pending.chunks.length) return null;
        if (pending.chunks[pkt.sequenceNum()] == null) {
            pending.chunks[pkt.sequenceNum()] = pkt.chunkData();
            pending.received++;
        }
        pending.touch();

        if (pending.received == pending.chunks.length) {
            PENDING.remove(id);
            return reassemble(pending.chunks);
        }
        return null;
    }

    /** Remove expired incomplete blobs. Call periodically (e.g. each tick). / 移除过期的未完成 blob。周期性调用（如每 tick）。 */
    public static void cleanup() {
        var it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue().isExpired()) {
                SchematicCompute.LOGGER.debug("BlobRegistry: expired blob {}", e.getKey());
                it.remove();
            }
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
