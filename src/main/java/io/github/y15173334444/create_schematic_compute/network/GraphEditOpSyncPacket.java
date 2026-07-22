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
            // If the editor UI is open, delegate to the host for full UI-aware handling
            if (mc.screen instanceof io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.Host host
                && host.getBlockPos().equals(pkt.op().graphPos())) {
                host.onRemoteOp(pkt.op());
            } else {
                // UI closed: apply op directly to the BE graph so the in-world renderer sees changes
                var level = mc.level;
                if (level != null) {
                    var be = level.getBlockEntity(pkt.op().graphPos());
                    if (be instanceof io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity gbe) {
                        var graph = gbe.getNodeGraph();
                        if (graph == null) return;
                        // Resolve sub-graph for encapsulation nodes
                        if (pkt.op().ownerNodeId() >= 0) {
                            var encapNode = graph.findNode(pkt.op().ownerNodeId());
                            if (encapNode == null) return; // 封装节点不存在
                            if (encapNode.subGraph == null) encapNode.subGraph = new io.github.y15173334444.create_schematic_compute.graph.NodeGraph();
                            graph = encapNode.subGraph;
                        }
                        io.github.y15173334444.create_schematic_compute.graph.OpExecutor.apply(graph, pkt.op(), false);
                    }
                }
            }
        });
    }
}
