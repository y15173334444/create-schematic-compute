package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NbsSong;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 曲目（NBS 字节）的客户端上传：{@code BlobDataPacket} 分片 + {@code SET_SONG} 引用 op
 * （plan §3.5 数据面）。C2S 自定义负载单包上限 32 KB（实测口径），曲目必须分片；
 * op 只带 {@code blobRefId}，服务端在 {@code BlobRegistry} 重组后应用。
 * <p>Client-side song (NBS byte) upload: {@code BlobDataPacket} chunks plus a referencing
 * {@code SET_SONG} op (plan §3.5 data plane). The C2S custom-payload cap is 32 KB
 * (verified), so songs travel chunked; the op carries only a {@code blobRefId} and the
 * server applies from its reassembled {@code BlobRegistry} entry.</p>
 * <p>顺序契约：分片先于 op 发送（同一 TCP 连接保序，两端 handler 依序入主线程），op 到达时
 * 重组必已完成。Ordering contract: chunks are sent before the op (same TCP connection, both
 * handlers enqueue in order), so reassembly is done by the time the op is applied.</p>
 */
public final class SongSync {

    private SongSync() {}

    /**
     * 上传一首曲目到服务端（写入/替换 MUSIC 节点曲目）。超限（&gt; {@link NbsSong#MAX_BYTES}）
     * 直接拒绝返回 false，由调用方提示。op 经 {@code opSender} 发送（走 sendOp 的
     * pendingLocalOps 计数）。
     * <p>Upload a song to the server (write/replace a MUSIC node's song). Oversized songs
     * ({@code > NbsSong.MAX_BYTES}) are rejected with {@code false} for the caller to surface;
     * the op goes through {@code opSender} so the pendingLocalOps guard counts it.</p>
     */
    public static boolean upload(BlockPos pos, int ownerNodeId, int nodeId,
                                 NbsSong song, UUID actor, Consumer<GraphOp> opSender) {
        byte[] bytes = song == null ? new byte[0] : song.write();
        return uploadBytes(pos, ownerNodeId, nodeId, bytes, actor, opSender);
    }

    /** 同 {@link #upload}，但直接给字节（复制粘贴路径复用）。 / like {@link #upload}, raw bytes. */
    public static boolean uploadBytes(BlockPos pos, int ownerNodeId, int nodeId,
                                      byte[] bytes, UUID actor, Consumer<GraphOp> opSender) {
        if (bytes == null || bytes.length == 0 || bytes.length > NbsSong.MAX_BYTES) return false;
        int blobId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        for (BlobDataPacket chunk : BlobDataPacket.fromBytes(pos, blobId, nodeId, bytes)) {
            PacketDistributor.sendToServer(chunk);
        }
        opSender.accept(GraphOp.setSong(pos, ownerNodeId, nodeId, blobId, actor));
        return true;
    }
}
