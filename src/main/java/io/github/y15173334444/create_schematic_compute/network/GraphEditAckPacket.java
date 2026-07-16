package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.GraphEditor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * Server→Client acknowledgment for an edit op.
 * Carries the server-assigned node ID for ADD_NODE_REQUEST,
 * plus the new editVersion so the client can reconcile.
 */
public record GraphEditAckPacket(BlockPos pos, int tempId, int assignedId, long editVersion)
    implements CustomPacketPayload {

    public static final Type<GraphEditAckPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_edit_ack"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GraphEditAckPacket> CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, GraphEditAckPacket::pos,
            ByteBufCodecs.VAR_INT, GraphEditAckPacket::tempId,
            ByteBufCodecs.VAR_INT, GraphEditAckPacket::assignedId,
            ByteBufCodecs.VAR_LONG, GraphEditAckPacket::editVersion,
            GraphEditAckPacket::new
        );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Client-side: update the local graph with the server-assigned node ID. */
    public static void handle(GraphEditAckPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof GraphEditor.Host host && host.getBlockPos().equals(pkt.pos)) {
                host.handleAck(pkt);
            }
        });
    }
}
