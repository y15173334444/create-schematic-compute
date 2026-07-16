package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import io.netty.buffer.ByteBuf;

/** S→C: server broadcasts an applied op to other editors. Needed because NeoForge
 *  doesn't allow registering the same TYPE for both playToServer and playToClient. */
public record GraphEditOpSyncPacket(GraphOp op) implements CustomPacketPayload {

    public static final Type<GraphEditOpSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_edit_op_sync"));

    /** Reuse GraphEditOpPacket's codec logic for the wrapped GraphOp. */
    public static final StreamCodec<ByteBuf, GraphEditOpSyncPacket> CODEC =
        new StreamCodec<>() {
            @Override public GraphEditOpSyncPacket decode(ByteBuf buf) {
                // Decode via GraphEditOpPacket's codec, extract the op
                var inner = GraphEditOpPacket.CODEC.decode(buf);
                return new GraphEditOpSyncPacket(inner.op());
            }
            @Override public void encode(ByteBuf buf, GraphEditOpSyncPacket pkt) {
                GraphEditOpPacket.CODEC.encode(buf, new GraphEditOpPacket(pkt.op()));
            }
        };

    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Client-side: apply a remote op from the server. */
    public static void handle(GraphEditOpSyncPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() == null) return;
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.Host host) {
                host.onRemoteOp(pkt.op());
            }
        });
    }
}
