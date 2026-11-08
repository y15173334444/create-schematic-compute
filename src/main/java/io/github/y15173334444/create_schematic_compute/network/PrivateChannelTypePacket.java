package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 私有频道定义同步（S→C）：频道名 → **声明载荷类型**（发布方声明，随版本戳变化推送）。
 * 订阅方编辑器据此做引脚类型变换——PRIVATE_IN 引脚的着色跟随频道声明类型，与 BUS 频段
 * 类型同步同款；属性跟频道走，不跟本地接线走。空闲不发、变更才推（{@code GraphHost}）。
 * <p>Private-channel definition sync (server→client): channel name → declared payload type
 * (declared by the publisher, pushed on version change). Subscribers' editors follow it for
 * pin typing — the same discipline as the BUS per-band types; the property rides the
 * channel, not local wiring. Sent only on change.</p>
 */
public record PrivateChannelTypePacket(String channel, String typeCode) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PrivateChannelTypePacket> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "private_channel_type"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PrivateChannelTypePacket> CODEC = StreamCodec.of(
        (buf, pkt) -> {
            buf.writeUtf(pkt.channel);
            buf.writeUtf(pkt.typeCode);
        },
        buf -> new PrivateChannelTypePacket(buf.readUtf(), buf.readUtf()));

    @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        // 只写客户端侧定义表（SignalBus 同款静态表，编辑器着色读取）
        // Client-side definition table only (same SignalBus statics the editor tinting reads).
        ctx.enqueueWork(() -> {
            // 未知码 = 类型信息不可信 → 保留现值，绝不静默降 FLOAT（同 BusBandSyncPacket
            // 契约）——降级出的 FLOAT 定义会经生效类型合并/订阅收敛误剪 AUDIO/VEC3 线。
            // An unknown code means the type info is untrusted → keep the current value,
            // never silently degrade to FLOAT (same contract as BusBandSyncPacket) — a
            // fabricated FLOAT definition would wrongly prune AUDIO/VEC3 wires via the
            // effective merge / subscriber convergence.
            var t = io.github.y15173334444.create_schematic_compute.graph.PinType.byCode(typeCode);
            if (t != null) SignalBus.setPrivateType(channel, t);
        });
    }
}
