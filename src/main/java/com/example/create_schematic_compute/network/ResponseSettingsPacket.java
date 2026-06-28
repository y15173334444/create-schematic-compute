package com.example.create_schematic_compute.network;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.client.PortableTerminalScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** S→C: full BE settings NBT for remote editing. */
public record ResponseSettingsPacket(BlockPos targetPos, byte[] nbt) implements CustomPacketPayload {
    public static final Type<ResponseSettingsPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "resp_settings"));
    public static final StreamCodec<ByteBuf, ResponseSettingsPacket> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, ResponseSettingsPacket::targetPos,
        ByteBufCodecs.BYTE_ARRAY, ResponseSettingsPacket::nbt,
        ResponseSettingsPacket::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> PortableTerminalScreen.onSettingsResponse(targetPos, nbt));
    }
}
