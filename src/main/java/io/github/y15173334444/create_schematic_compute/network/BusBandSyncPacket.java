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
        // Check if any BUS_OUT in THIS graph owns busName. If so, the bands in the
        // registry were registered by this graph's own BUS_OUT (the server broadcasts
        // them back to us) — NOT a cross-block conflict.
        // 检查当前图中是否有 BUS_OUT 拥有 busName。如果是，注册表中的频段是由
        // 此图自己的 BUS_OUT 注册的（服务端广播回来的）—— 不是跨方块冲突。
        boolean anyBusOutOwns = false;
        for (var n : graph.nodes) {
            if (n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT
                && n.signalName.equals(busName)) {
                anyBusOutOwns = true; break;
            }
        }
        var gb = SignalBus.getBands(busName);
        boolean crossBlockExists = !anyBusOutOwns && (gb != null && !gb.isEmpty());
        for (var n : graph.nodes) {
            if (n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT
                && n.signalName.equals(busName)) {
                boolean localConflict = false;
                for (var other : graph.nodes) {
                    if (other != n && other.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT
                        && other.signalName.equals(busName)) {
                        localConflict = true; break;
                    }
                }
                n.busConflict = localConflict || crossBlockExists;
            }
        }
    }
}
