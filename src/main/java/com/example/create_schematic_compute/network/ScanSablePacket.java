package com.example.create_schematic_compute.network;

import com.example.create_schematic_compute.SchematicCompute;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/** Client→Server: request a scan of Sable sub-levels for nearby programmable blocks. */
public record ScanSablePacket(BlockPos playerPos, int scanRange) implements CustomPacketPayload {
    public static final Type<ScanSablePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "scan_sable"));
    public static final StreamCodec<ByteBuf, ScanSablePacket> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, ScanSablePacket::playerPos,
        ByteBufCodecs.VAR_INT,  ScanSablePacket::scanRange,
        ScanSablePacket::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ServerLevel sw = (ServerLevel) ctx.player().level();
            List<SablePacketHelper.SableDeviceEntry> devs =
                SablePacketHelper.scanDevices(sw, playerPos, scanRange);
            PacketDistributor.sendToPlayer((ServerPlayer) ctx.player(),
                new ScanSableResponsePacket(devs));
        });
    }
}
