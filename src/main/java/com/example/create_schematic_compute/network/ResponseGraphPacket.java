package com.example.create_schematic_compute.network;

import com.example.create_schematic_compute.SchematicCompute;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ResponseGraphPacket(BlockPos targetPos, byte[] graphNBT, int graphVersion) implements CustomPacketPayload {
    public static final Type<ResponseGraphPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "response_graph"));
    public static final StreamCodec<ByteBuf, ResponseGraphPacket> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, ResponseGraphPacket::targetPos,
        ByteBufCodecs.BYTE_ARRAY, ResponseGraphPacket::graphNBT,
        ByteBufCodecs.VAR_INT, ResponseGraphPacket::graphVersion,
        ResponseGraphPacket::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            com.example.create_schematic_compute.client.PortableTerminalScreen.onGraphResponse(targetPos, graphNBT, graphVersion);
        });
    }
}
