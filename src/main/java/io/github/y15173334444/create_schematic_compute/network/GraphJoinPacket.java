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

    /** Max join distance (squared) — ~80 blocks range. */
    private static final double MAX_JOIN_DIST_SQ = 128.0 * 128.0;

    public static void handle(GraphJoinPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            // 1. Range check before touching chunk/block entity (Sable-aware)
            if (!io.github.y15173334444.create_schematic_compute.network.SablePacketHelper.isWithinReachableRange(sp, pkt.pos, MAX_JOIN_DIST_SQ)) return;
            // 2. Verify target is one of the 7 graph block entities BEFORE joining session
            var be = sp.level().getBlockEntity(pkt.pos);
            if (!(be instanceof GraphBlockEntity)) return;
            // 3. Safe to join
            EditSessionRegistry.join(sp.serverLevel(), pkt.pos, sp.getUUID());
            if (be instanceof BlueprintBlockEntity bbe) bbe.flagFullSync();
            else if (be instanceof MonitorBlockEntity mbe) mbe.flagFullSync();
            else if (be instanceof RadarBlockEntity rbe) rbe.flagFullSync();
            else if (be instanceof SensorBlockEntity sbe) sbe.flagFullSync();
            else if (be instanceof ControlSeatBlockEntity cbe) cbe.flagFullSync();
            else if (be instanceof SpeedProxyBlockEntity spbe) spbe.flagFullSync();
            else if (be instanceof ProgramComputerBlockEntity pbe) pbe.flagFullSync();
            var update = be.getUpdatePacket();
            if (update != null) sp.connection.send(update);
        });
    }
}
