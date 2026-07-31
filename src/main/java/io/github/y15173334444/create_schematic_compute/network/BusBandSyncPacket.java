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

/** 服务端→客户端：同步 BUS 频段列表变化 */
public record BusBandSyncPacket(BlockPos pos, String busName, List<String> bands) implements CustomPacketPayload {

    public static final Type<BusBandSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "bus_band_sync"));

    public static final StreamCodec<ByteBuf, BusBandSyncPacket> CODEC = new StreamCodec<>() {
        @Override public BusBandSyncPacket decode(ByteBuf buf) {
            var b = new FriendlyByteBuf(buf);
            BlockPos p = b.readBlockPos();
            String name = b.readUtf();
            int count = b.readVarInt();
            var list = new ArrayList<String>();
            for (int i = 0; i < count; i++) list.add(b.readUtf());
            return new BusBandSyncPacket(p, name, list);
        }
        @Override public void encode(ByteBuf buf, BusBandSyncPacket pkt) {
            var b = new FriendlyByteBuf(buf);
            b.writeBlockPos(pkt.pos);
            b.writeUtf(pkt.busName);
            var bands = pkt.bands;
            b.writeVarInt(bands != null ? bands.size() : 0);
            if (bands != null) for (String s : bands) b.writeUtf(s);
        }
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var be = ctx.player().level().getBlockEntity(pos);
            if (bands != null && !bands.isEmpty()) {
                SignalBus.registerBands(busName, bands);
            } else {
                SignalBus.clearBus(busName);
            }
            if (be instanceof GraphBlockEntity gbe) {
                var emptyBands = java.util.Collections.<String>emptyList();
                gbe.syncBusBandsFromServer(busName,
                    bands != null && !bands.isEmpty() ? bands : emptyBands);
                // Re-evaluate busConflict for BUS_OUT nodes in this block's graph.
                // When another block registers a BUS_OUT with the same signalName, the
                // BandSyncPacket updates BAND_REGISTRY here, and we must check whether
                // any local BUS_OUT now has a cross-block conflict.
                // 重新评估此方块图中 BUS_OUT 节点的 busConflict。
                // 当另一个方块注册了相同 signalName 的 BUS_OUT 时，
                // BandSyncPacket 在此更新 BAND_REGISTRY，我们必须检查是否有本地 BUS_OUT 现在存在跨方块冲突。
                reevaluateBusConflicts(gbe.getNodeGraph(), busName);
            }
            // 同时刷新当前活跃编辑器（玩家正在编辑的 block），使跨 block 频道变化
            // 实时反映到编辑器 UI 的冲突警告。
            // Also refresh the active editor so cross-block channel changes show up
            // in the editor UI's conflict warnings in real time.
            var host = io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.getActiveHost();
            if (host != null) {
                var ed = host.getEditor();
                if (ed != null) ed.reevaluateBusConflictsForBus(busName);
            }
        });
    }

    /** Check BUS_OUT nodes in {@code graph} for conflicts on {@code busName}.
     *  A conflict exists when the local graph has a BUS_OUT with the given signalName
     *  and the band registry knows about this name from another block (meaning another
     *  block's BUS_OUT already claimed the channel). When the band registry entry is
     *  cleared (empty bands), any cross-block conflict on this name is resolved.
     *  检查 graph 中 BUS_OUT 节点在 busName 上的冲突。
     *  当本地图有一个带有给定 signalName 的 BUS_OUT 且频段注册表从另一个方块知道此名称时
     *  （意味着另一个方块的 BUS_OUT 已声明该频道），存在冲突。
     *  当频段注册表条目被清除（空频段）时，此名称上的任何跨方块冲突将被解决。 */
    private static void reevaluateBusConflicts(
            io.github.y15173334444.create_schematic_compute.graph.NodeGraph graph,
            String busName) {
        if (graph == null || busName == null || busName.isEmpty()) return;
        // 本地冲突 = 图内另一个同名 BUS_OUT（同图重名会互相覆盖，警告）。
        // crossBlockExists = 本图没有任何同名 BUS_OUT（含自身），但 BAND_REGISTRY
        // 有该名 bands —— 说明是另一个 block 的频道（跨 block 冲突）。
        // 注意：anyBusOutOwns 必须匹配含自身的同名节点——若本图有同名 BUS_OUT，
        // BAND_REGISTRY 的 bands 可能是本 block 自己的 echo（服务端广播回来），
        // 不构成跨 block 冲突。回归审计：上一轮加 other != n 移除了自我回显保护，
        // 导致单一 BUS_OUT 自己的 echo 被误标 busConflict=true。
        // Local conflict = another same-name BUS_OUT in this graph (warning: same-graph
        // duplicates overwrite each other). crossBlockExists = this graph has NO same-name
        // BUS_OUT at all (including itself) but BAND_REGISTRY has the name — another block's
        // channel (cross-block conflict). anyBusOutOwns must match same-name nodes INCLUDING
        // itself: if this graph has one, BAND_REGISTRY's bands may be this block's own echo
        // (broadcast back by the server), not a cross-block conflict.
        boolean anyBusOutOwns = false;
        var localConflictByNode = new java.util.HashSet<Integer>();
        for (var n : graph.nodes) {
            if (n.type != io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT
                || !n.signalName.equals(busName)) continue;
            anyBusOutOwns = true; // 本图有同名 BUS_OUT（含自身）
            for (var other : graph.nodes) {
                if (other != n && other.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT
                    && other.signalName.equals(busName)) {
                    localConflictByNode.add(n.id);
                }
            }
        }
        var gb = SignalBus.getBands(busName);
        boolean crossBlockExists = !anyBusOutOwns && (gb != null && !gb.isEmpty());
        for (var n : graph.nodes) {
            if (n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT
                && n.signalName.equals(busName)) {
                n.busConflict = localConflictByNode.contains(n.id) || crossBlockExists;
            }
        }
    }
}
