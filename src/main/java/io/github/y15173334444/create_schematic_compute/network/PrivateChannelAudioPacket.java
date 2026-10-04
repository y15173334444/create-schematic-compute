package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 私有频道定义同步（S→C）：频道名 → 当前是否承载音频（发布方按输入引脚对端域的拓扑判定，
 * 随版本戳变化推送）。订阅方编辑器据此做**引脚类型变换**——PRIVATE_IN 引脚的浮点/音频着色
 * 跟随频道属性，与 BUS 频段音频标志同步同款；属性跟频道走，不跟本地接线走（本地接线只是
 * 同一判定的另一来源）。空闲不发、变更才推（{@code GraphHost.pushAudioFlagChanges}）。
 * <p>Private-channel definition sync (server→client): channel name → whether it currently
 * carries audio (topology-derived by the publisher's pin peer, pushed on version change).
 * Subscribers' editors follow it for pin typing — the same discipline as the BUS per-band
 * audio flags; the property rides the channel, and local wiring is just the other input to
 * the same verdict. Sent only on change.</p>
 */
public record PrivateChannelAudioPacket(String channel, boolean audio) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PrivateChannelAudioPacket> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "private_channel_audio"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PrivateChannelAudioPacket> CODEC = StreamCodec.of(
        (buf, pkt) -> {
            buf.writeUtf(pkt.channel);
            buf.writeBoolean(pkt.audio);
        },
        buf -> new PrivateChannelAudioPacket(buf.readUtf(), buf.readBoolean()));

    @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        // 只写客户端侧定义表（SignalBus 同款静态表，编辑器着色读取）
        // Client-side definition table only (same SignalBus statics the editor tinting reads).
        ctx.enqueueWork(() -> SignalBus.setPrivateAudio(channel, audio));
    }
}
