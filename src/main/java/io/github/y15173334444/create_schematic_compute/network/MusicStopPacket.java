package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.client.audio.CscAudioEngine;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：MUSIC 停止/跳转沿的停止标记到达了这台音响——客户端清除该音响预播窗口内
 * 已下发未播的排队声部。没有它，加宽预播窗口（{@code MusicTransport.PREROLL_SECONDS}）
 * 之后，传输停止会让音乐再拖一个窗口长度的尾巴。
 * S2C: a stop marker from a MUSIC stop/seek edge reached this speaker - the client cancels
 * the speaker's queued dispatched-but-unplayed voices. Without it, a widened pre-roll
 * ({@code MusicTransport.PREROLL_SECONDS}) leaves a tail up to one window long after a stop.
 */
public record MusicStopPacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<MusicStopPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "music_stop"));

    public static final StreamCodec<ByteBuf, MusicStopPacket> CODEC = new StreamCodec<>() {
        @Override public MusicStopPacket decode(ByteBuf buf) {
            return new MusicStopPacket(new FriendlyByteBuf(buf).readBlockPos());
        }
        @Override public void encode(ByteBuf buf, MusicStopPacket pkt) {
            new FriendlyByteBuf(buf).writeBlockPos(pkt.pos);
        }
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> CscAudioEngine.cancelFutureForSpeaker(pos.asLong()));
    }
}
