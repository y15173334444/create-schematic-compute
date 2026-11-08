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

/** 服务端→客户端：同步 BUS 频段列表变化 + 各频段**声明载荷类型**（发布方声明，编辑器着色
 *  与订阅方收敛用；typeCodes 为空 = 本包不带类型信息，客户端保留现值）。
 *  Server→client: band-list changes plus per-band declared payload types (declared by the
 *  publisher, for editor tinting and subscriber convergence; empty typeCodes = no type
 *  info, keep current). */
public record BusBandSyncPacket(BlockPos pos, String busName, List<String> bands, List<String> typeCodes) implements CustomPacketPayload {

    public static final Type<BusBandSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "bus_band_sync"));

    /** 不带类型信息的便捷构造（类型未知/不更新的发送方用）。
     *  Convenience constructor for senders without type info (types left untouched). */
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
            int typeCount = b.readVarInt();
            var codes = new ArrayList<String>(typeCount);
            for (int i = 0; i < typeCount; i++) codes.add(b.readUtf());
            return new BusBandSyncPacket(p, name, list, codes);
        }
        @Override public void encode(ByteBuf buf, BusBandSyncPacket pkt) {
            var b = new FriendlyByteBuf(buf);
            b.writeBlockPos(pkt.pos);
            b.writeUtf(pkt.busName);
            var bands = pkt.bands;
            b.writeVarInt(bands != null ? bands.size() : 0);
            if (bands != null) for (String s : bands) b.writeUtf(s);
            var codes = pkt.typeCodes;
            b.writeVarInt(codes != null ? codes.size() : 0);
            if (codes != null) for (String s : codes) b.writeUtf(s);
        }
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var be = ctx.player().level().getBlockEntity(pos);
            if (bands != null && !bands.isEmpty()) {
                SignalBus.registerBands(busName, bands);
                // 频段声明类型随定义走（空列表 = 发送方不带类型，保留现值）。
                // Declared band types ride the definition (empty list = sender carries none).
                if (typeCodes != null && !typeCodes.isEmpty()) {
                    var map = new java.util.HashMap<String, io.github.y15173334444.create_schematic_compute.graph.PinType>();
                    boolean trusted = true;
                    for (int i = 0; i < bands.size() && i < typeCodes.size(); i++) {
                        var t = io.github.y15173334444.create_schematic_compute.graph.PinType.byCode(typeCodes.get(i));
                        if (t == null) { trusted = false; break; } // 未知码 = 类型信息不可信 / unknown code = untrusted type info
                        map.put(bands.get(i), t);
                    }
                    // 未知码 = 类型信息不可信 → 整份类型表不应用（保留现值），绝不静默降 FLOAT：
                    // 降级出的 FLOAT 声明会经订阅收敛重绑剪掉 AUDIO/VEC3 线（数据流错 + 误剪）。
                    // 同「空列表 = 发送方不带类型，保留现值」契约。
                    // An unknown code means the type info is untrusted → don't apply the type
                    // table at all (keep current values), never silently degrade to FLOAT: a
                    // fabricated FLOAT declaration would retype-prune AUDIO/VEC3 wires via
                    // subscriber convergence (wrong data flow + wrongful cuts). Same contract
                    // as "empty list = sender carries none, keep current".
                    if (trusted) SignalBus.setBandTypes(busName, map);
                }
            } else {
                // 空表 = 频段列表同步，不是频道清账：名下有存活 owner（本端可见条目）时绝不碰
                // 定义与值——过期空包会把 owner 的频段定义与值连带清掉（镜像清理不带 owner 门
                // 的残余豁口，用户症状「A 的频段被一起清理」的一条产生路径）。节点侧的列表
                // 收敛仍由下面的 syncBusBandsFromServer 按权威值执行。
                // An empty list is a band-list sync, not a channel teardown: while a live owner
                // entry is visible locally, never touch the definition or values — a stale empty
                // packet would wipe the owner's definition and values along with it (the residual
                // owner-less mirror cleanup; one producer of the user symptom). The node lists
                // still converge below via syncBusBandsFromServer with the authoritative value.
                if (SignalBus.getChannel(busName) == null) SignalBus.clearBus(busName);
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
