package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** 服务端→客户端：同步 BUS 频段列表变化 + 各频段是否音频（发布方引脚对端域判定，
 *  编辑器着色用；audioFlags 为空 = 本包不带标志信息，客户端保留现值）。
 *  Server→client: band-list changes plus per-band audio flags (decided by the publisher's
 *  pin peers, for editor tinting; empty audioFlags = no flag info, keep current). */
public record BusBandSyncPacket(BlockPos pos, String busName, List<String> bands, List<Boolean> audioFlags) implements CustomPacketPayload {

    public static final Type<BusBandSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "bus_band_sync"));

    /** 不带标志信息的便捷构造（标志未知/不更新的发送方用）。
     *  Convenience constructor for senders without flag info (flags left untouched). */
    public BusBandSyncPacket(BlockPos pos, String busName, List<String> bands) {
        this(pos, busName, bands, java.util.List.of());
    }

    public static final StreamCodec<ByteBuf, BusBandSyncPacket> CODEC = new StreamCodec<>() {
        @Override public BusBandSyncPacket decode(ByteBuf buf) {
            var b = new FriendlyByteBuf(buf);
            BlockPos p = b.readBlockPos();
            String name = b.readUtf();
            int count = b.readVarInt();
            var list = new ArrayList<String>();
            for (int i = 0; i < count; i++) list.add(b.readUtf());
            int flagCount = b.readVarInt();
            var flags = new ArrayList<Boolean>(flagCount);
            for (int i = 0; i < flagCount; i++) flags.add(b.readBoolean());
            return new BusBandSyncPacket(p, name, list, flags);
        }
        @Override public void encode(ByteBuf buf, BusBandSyncPacket pkt) {
            var b = new FriendlyByteBuf(buf);
            b.writeBlockPos(pkt.pos);
            b.writeUtf(pkt.busName);
            var bands = pkt.bands;
            b.writeVarInt(bands != null ? bands.size() : 0);
            if (bands != null) for (String s : bands) b.writeUtf(s);
            var flags = pkt.audioFlags;
            b.writeVarInt(flags != null ? flags.size() : 0);
            if (flags != null) for (Boolean f : flags) b.writeBoolean(f);
        }
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var be = ctx.player().level().getBlockEntity(pos);
            if (bands != null && !bands.isEmpty()) {
                SignalBus.registerBands(busName, bands);
                // 频段音频标志随定义走（空列表 = 发送方不带标志，保留现值）。
                // Audio flags ride the definition (empty list = sender carries none, keep current).
                if (audioFlags != null && !audioFlags.isEmpty()) {
                    var set = new java.util.HashSet<String>();
                    for (int i = 0; i < bands.size() && i < audioFlags.size(); i++)
                        if (audioFlags.get(i)) set.add(bands.get(i));
                    SignalBus.setAudioBands(busName, set);
                }
            } else {
                SignalBus.clearBus(busName);
            }
            if (be instanceof GraphBlockEntity gbe) {
                var emptyBands = java.util.Collections.<String>emptyList();
                gbe.syncBusBandsFromServer(busName,
                    bands != null && !bands.isEmpty() ? bands : emptyBands);
            }
            // 冲突状态**不再**由频段同步包推导（issue #12）：频段表无法区分「自身回声」与
            // 「对端已占用」，而服务端的权威 busConflict 随图同步到达（冲突状态变化时服务端
            // 会推方块更新）。这里原先的两处重算会把服务端送来的值改回 false。
            // Conflict state is no longer derived from a band-sync packet (issue #12): the band
            // registry cannot tell our own echo from a peer's claim, and the authoritative
            // busConflict arrives with the graph (the server pushes a block update when it
            // changes). The recomputes that used to live here reset the server's value to false.
        });
    }
}
