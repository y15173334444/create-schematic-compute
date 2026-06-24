package com.example.create_schematic_compute.network;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.blocks.RadarBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RadarLockPacket(BlockPos pos, int entityId, boolean lock) implements CustomPacketPayload {

    public static final Type<RadarLockPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "radar_lock"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RadarLockPacket> CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, RadarLockPacket::pos,
            ByteBufCodecs.VAR_INT, RadarLockPacket::entityId,
            ByteBufCodecs.BOOL, RadarLockPacket::lock,
            RadarLockPacket::new);

    @Override public Type<RadarLockPacket> type() { return TYPE; }

    public static void handle(RadarLockPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var level = ctx.player().level();
            if (level == null) return;
            var be = level.getBlockEntity(pkt.pos);
            if (be instanceof RadarBlockEntity radar) {
                int nodeCount = 0;
                for (var n : radar.graph.nodes)
                    if (n.type == com.example.create_schematic_compute.graph.NodeType.TARGET_OUT) nodeCount++;
                int maxLocks = radar.scanMode == 1 ? 1 : Math.max(1, nodeCount);
                boolean ok = radar.toggleLock(pkt.entityId, maxLocks);
                if (ok) {
                    radar.setChanged();
                    level.sendBlockUpdated(pkt.pos, radar.getBlockState(), radar.getBlockState(), 3);
                }
            }
        });
    }
}
