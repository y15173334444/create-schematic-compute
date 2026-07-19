package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S / S→C bidirectional chunked bulk-data transfer packet.
 * <p>C→S / S→C 双向分片大数据传输包。</p>
 *
 * <p>Carries data too large for a single {@link io.github.y15173334444.create_schematic_compute.graph.GraphOp}
 * (image pixels as int[], ItemStack NBT, etc.).
 * 用于承载超出 GraphOp 单包能力的大数据（图像像素 int[]、ItemStack NBT 等）。
 * Each chunk ≤ 30 KB; the receiver reassembles via {@link BlobRegistry}.
 * 每片 ≤30KB，接收端由 BlobRegistry 重组。</p>
 *
 * <p>Send flow / 发送流程：</p>
 * <ol>
 *   <li>Send all BlobDataPacket chunks / 发送所有 BlobDataPacket 分片</li>
 *   <li>Send a GraphOp with {@code blobRefId} to trigger application / 发送带 blobRefId 的 GraphOp 触发应用</li>
 * </ol>
 */
public record BlobDataPacket(
    BlockPos pos,
    int blobId,
    BlobType blobType,
    int nodeId,
    int sequenceNum,
    int totalChunks,
    byte[] chunkData
) implements CustomPacketPayload {

    public static final Type<BlobDataPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "blob_data"));

    /** Max payload per chunk: 30 KB (Minecraft 32 KB limit minus headers). / 每片最大负载：30KB（Minecraft 32KB 限制减去头部）。 */
    public static final int MAX_CHUNK_SIZE = 30 * 1024;

    public static final StreamCodec<ByteBuf, BlobDataPacket> CODEC = new StreamCodec<>() {
        @Override
        public BlobDataPacket decode(ByteBuf buf) {
            BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
            int blobId = VarInt.read(buf);
            BlobType type = BlobType.fromId(VarInt.read(buf));
            int nodeId = VarInt.read(buf);
            int seq = VarInt.read(buf);
            int total = VarInt.read(buf);
            int len = VarInt.read(buf);
            byte[] data = new byte[len];
            buf.readBytes(data);
            return new BlobDataPacket(pos, blobId, type, nodeId, seq, total, data);
        }

        @Override
        public void encode(ByteBuf buf, BlobDataPacket pkt) {
            BlockPos.STREAM_CODEC.encode(buf, pkt.pos);
            VarInt.write(buf, pkt.blobId);
            VarInt.write(buf, pkt.blobType.id);
            VarInt.write(buf, pkt.nodeId);
            VarInt.write(buf, pkt.sequenceNum);
            VarInt.write(buf, pkt.totalChunks);
            VarInt.write(buf, pkt.chunkData.length);
            buf.writeBytes(pkt.chunkData);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    // ── Helper: split int[] into chunks / 辅助：将 int[] 拆分为分片 ──

    public static java.util.List<BlobDataPacket> fromIntArray(BlockPos pos, int blobId,
                                                               int nodeId, int[] pixels) {
        var buf = java.nio.ByteBuffer.allocate(pixels.length * 4);
        for (int v : pixels) buf.putInt(v);
        return chunkBytes(pos, blobId, BlobType.IMAGE_PIXELS, nodeId, buf.array());
    }

    public static int[] toIntArray(byte[] data) {
        var buf = java.nio.ByteBuffer.wrap(data);
        int[] pixels = new int[data.length / 4];
        for (int i = 0; i < pixels.length; i++) pixels[i] = buf.getInt();
        return pixels;
    }

    private static java.util.List<BlobDataPacket> chunkBytes(BlockPos pos, int blobId,
                                                              BlobType blobType, int nodeId, byte[] raw) {
        int total = (raw.length + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE;
        var list = new java.util.ArrayList<BlobDataPacket>(total);
        for (int i = 0; i < total; i++) {
            int start = i * MAX_CHUNK_SIZE;
            int end = Math.min(start + MAX_CHUNK_SIZE, raw.length);
            byte[] chunk = new byte[end - start];
            System.arraycopy(raw, start, chunk, 0, chunk.length);
            list.add(new BlobDataPacket(pos, blobId, blobType, nodeId, i, total, chunk));
        }
        return list;
    }
}
