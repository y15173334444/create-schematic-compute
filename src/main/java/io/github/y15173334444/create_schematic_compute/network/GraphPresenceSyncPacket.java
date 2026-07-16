package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import io.netty.buffer.ByteBuf;

/** S→C: server relays presence to other editors. */
public record GraphPresenceSyncPacket(GraphPresencePacket inner) implements CustomPacketPayload {

    public static final Type<GraphPresenceSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_presence_sync"));

    public static final StreamCodec<ByteBuf, GraphPresenceSyncPacket> CODEC =
        new StreamCodec<>() {
            @Override public GraphPresenceSyncPacket decode(ByteBuf buf) {
                return new GraphPresenceSyncPacket(GraphPresencePacket.CODEC.decode(buf));
            }
            @Override public void encode(ByteBuf buf, GraphPresenceSyncPacket p) {
                GraphPresencePacket.CODEC.encode(buf, p.inner());
            }
        };

    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(GraphPresenceSyncPacket pkt, IPayloadContext ctx) {
        GraphPresencePacket.handleClient(pkt.inner(), ctx);
    }
}
