package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/** Client→Server: player opened the edit UI for a graph. */
public record GraphJoinPacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<GraphJoinPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_join"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GraphJoinPacket> CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, GraphJoinPacket::pos,
            GraphJoinPacket::new
        );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(GraphJoinPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            EditSessionRegistry.join(pkt.pos, ctx.player().getUUID());
            // Force full graph sync for all BE types - send directly to joiner
            var be = ctx.player().level().getBlockEntity(pkt.pos);
            if (be != null && ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                if (be instanceof BlueprintBlockEntity bbe) bbe.flagFullSync();
                else if (be instanceof MonitorBlockEntity mbe) mbe.flagFullSync();
                else if (be instanceof RadarBlockEntity rbe) rbe.flagFullSync();
                else if (be instanceof SensorBlockEntity sbe) sbe.flagFullSync();
                else if (be instanceof ControlSeatBlockEntity cbe) cbe.flagFullSync();
                else if (be instanceof SpeedProxyBlockEntity spbe) spbe.flagFullSync();
                else if (be instanceof ProgramComputerBlockEntity pbe) pbe.flagFullSync();
                else { be.setChanged(); be.getLevel().sendBlockUpdated(pkt.pos, be.getBlockState(), be.getBlockState(), 3); }
                // Immediately push full NBT to the joining player (don't wait for async sendBlockUpdated)
                sp.connection.send(be.getUpdatePacket());
            }
        });
    }
}
