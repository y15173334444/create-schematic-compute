package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.EditSessionRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/** Client→Server: player closed the edit UI for a graph. */
public record GraphLeavePacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<GraphLeavePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_leave"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GraphLeavePacket> CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, GraphLeavePacket::pos,
            GraphLeavePacket::new
        );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(GraphLeavePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)
                EditSessionRegistry.leave(sp.serverLevel(), pkt.pos, sp.getUUID());
        });
    }
}
