package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import io.netty.buffer.ByteBuf;

/** S→C: 服务端把拖动临场流中继给其他编辑者（actor 已权威回填）。 / S2C: the server relays the
 * drag presence stream to other editors (actor already backfilled server-side). */
public record GraphDragStreamSyncPacket(GraphDragStreamPacket inner) implements CustomPacketPayload {

    public static final Type<GraphDragStreamSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_drag_stream_sync"));

    public static final StreamCodec<ByteBuf, GraphDragStreamSyncPacket> CODEC =
        new StreamCodec<>() {
            @Override public GraphDragStreamSyncPacket decode(ByteBuf buf) {
                return new GraphDragStreamSyncPacket(GraphDragStreamPacket.CODEC.decode(buf));
            }
            @Override public void encode(ByteBuf buf, GraphDragStreamSyncPacket p) {
                GraphDragStreamPacket.CODEC.encode(buf, p.inner());
            }
        };

    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(GraphDragStreamSyncPacket pkt, IPayloadContext ctx) {
        GraphDragStreamPacket.handleClient(pkt.inner(), ctx);
    }
}
