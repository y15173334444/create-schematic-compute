package com.example.create_schematic_compute.network;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.blocks.GraphBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SaveGraphPacket(BlockPos targetPos, byte[] graphNBT, int expectedVersion, boolean isSable) implements CustomPacketPayload {
    public static final Type<SaveGraphPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "save_graph_terminal"));
    public static final StreamCodec<ByteBuf, SaveGraphPacket> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, SaveGraphPacket::targetPos,
        ByteBufCodecs.BYTE_ARRAY, SaveGraphPacket::graphNBT,
        ByteBufCodecs.VAR_INT, SaveGraphPacket::expectedVersion,
        ByteBufCodecs.BOOL, SaveGraphPacket::isSable,
        SaveGraphPacket::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (graphNBT.length > 256 * 1024) return;
            Level level = ctx.player().level();
            if (isSable) {
                Level sl = SablePacketHelper.findSubLevel(level, targetPos);
                if (sl != null) level = sl;
                else return;
            }
            if (level.getBlockEntity(targetPos) instanceof GraphBlockEntity gbe) {
                var ng = gbe.getNodeGraph();
                int currentVersion = ng != null ? ng.graphGeneration : -1;
                if (expectedVersion != currentVersion && currentVersion >= 0) {
                    PacketDistributor.sendToPlayer((net.minecraft.server.level.ServerPlayer) ctx.player(),
                        new SaveRejectedPacket(targetPos, currentVersion));
                    return;
                }
                gbe.loadGraphFromBytes(graphNBT);
            }
        });
    }
}
