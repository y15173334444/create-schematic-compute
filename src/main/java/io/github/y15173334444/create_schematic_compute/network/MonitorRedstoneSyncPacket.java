package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.MonitorBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 服务端→客户端：同步 Monitor 的红石输入信号直 */
public record MonitorRedstoneSyncPacket(BlockPos pos, long freqKey, int signal) implements CustomPacketPayload {
    public static final Type<MonitorRedstoneSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "monitor_rs_sync"));
    public static final StreamCodec<ByteBuf, MonitorRedstoneSyncPacket> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, MonitorRedstoneSyncPacket::pos,
        ByteBufCodecs.VAR_LONG, MonitorRedstoneSyncPacket::freqKey,
        ByteBufCodecs.VAR_INT, MonitorRedstoneSyncPacket::signal,
        MonitorRedstoneSyncPacket::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var be = ctx.player().level().getBlockEntity(pos);
            if (be instanceof MonitorBlockEntity mbe) {
                mbe.putRedstoneInput(freqKey, signal);
            }
        });
    }
}
