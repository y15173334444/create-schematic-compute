package com.example.create_schematic_compute.network;

import com.example.create_schematic_compute.SchematicCompute;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SaveRejectedPacket(BlockPos targetPos, int currentVersion) implements CustomPacketPayload {
    public static final Type<SaveRejectedPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "save_rejected"));
    public static final StreamCodec<ByteBuf, SaveRejectedPacket> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, SaveRejectedPacket::targetPos,
        ByteBufCodecs.VAR_INT, SaveRejectedPacket::currentVersion,
        SaveRejectedPacket::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            com.example.create_schematic_compute.client.PortableTerminalScreen.onSaveRejected(targetPos, currentVersion);
        });
    }
}
