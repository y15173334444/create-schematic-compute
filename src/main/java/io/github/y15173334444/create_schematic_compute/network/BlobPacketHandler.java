package io.github.y15173334444.create_schematic_compute.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Handles {@link BlobDataPacket} on both client and server.
 * <p>在客户端和服务端处理 {@link BlobDataPacket}。</p>
 *
 * <p>On the server: feeds chunks into {@link BlobRegistry} for reassembly.
 * On the client: feeds chunks into the client-side BlobRegistry (separate static instance).
 * 服务端：将分片送入 BlobRegistry 进行重组。客户端：将分片送入客户端独立的 BlobRegistry 实例。</p>
 */
public final class BlobPacketHandler {

    private BlobPacketHandler() {}

    /** Server receives blob chunks from a client. After reassembly the data
     *  is available for the corresponding {@code GraphOp} to apply.
     *  服务端接收来自客户端的 blob 分片。重组后数据可供相应的 GraphOp 使用。 */
    public static void handleServer(BlobDataPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // 安全校验：距离检查 + 编辑会话成员检查
            if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            if (!(sp.level() instanceof net.minecraft.server.level.ServerLevel sl)) return;
            double dx = sp.getX() - pkt.pos().getX();
            double dz = sp.getZ() - pkt.pos().getZ();
            if (dx * dx + dz * dz > 16384.0) return;
            if (!io.github.y15173334444.create_schematic_compute.blocks.EditSessionRegistry.getEditors(sl, pkt.pos()).contains(sp.getUUID()))
                return;
            BlobRegistry.receive(pkt);
        });
    }

    /** Client receives blob chunks from the server. Currently used when the
     *  server relays blob data to other editors during collaboration.
     *  客户端接收来自服务端的 blob 分片。当前用于协作编辑时服务端将 blob 数据转发给其他编辑器。 */
    public static void handleClient(BlobDataPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            BlobRegistry.receive(pkt);
        });
    }

    /** Send blob chunks from a server-side int[] to all tracking clients. / 将服务端 int[] 的 blob 分片发送给所有追踪客户端。 */
    public static void broadcastPixels(ServerLevel level, BlockPos pos,
                                        int blobId, int nodeId, int[] pixels) {
        var chunks = BlobDataPacket.fromIntArray(pos, blobId, nodeId, pixels);
        for (var chunk : chunks) {
            PacketDistributor.sendToPlayersTrackingChunk(level,
                new ChunkPos(pos), new BlobDataSyncPacket(chunk));
        }
    }
}
