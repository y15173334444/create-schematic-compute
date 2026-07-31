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
                // any local BUS_OUT now has a conflict.
                // 重新评估此方块图中 BUS_OUT 节点的 busConflict。
                // 当另一个方块注册了相同 signalName 的 BUS_OUT 时，
                // BandSyncPacket 在此更新 BAND_REGISTRY，我们必须检查是否有本地 BUS_OUT 现在存在冲突。
                reevaluateBusConflicts(gbe.getNodeGraph(), busName);
            }
            // 同时刷新当前活跃编辑器（玩家正在编辑的 block）的冲突显示，
            // 使跨方块 bus 变化实时反映到编辑器 UI。
            // Also refresh the active editor (the block the player is editing) so
            // cross-block bus changes show up in the editor UI in real time.
            var host = io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.getActiveHost();
            if (host != null) {
                var ed = host.getEditor();
                if (ed != null) ed.reevaluateBusConflictsForBus(busName);
            }
        });
    }

    /** Check BUS_OUT nodes in {@code graph} for LOCAL conflicts on {@code busName}.
     *  Shared model (回归审计): same-name BUS_OUT across blocks SHARE a channel, so a
     *  cross-block same-name is legitimate, not a conflict. busConflict now means only
     *  "another BUS_OUT in the SAME graph uses this name" (a warning — same-named bands
     *  would overwrite each other within one computer).
     *  检查 graph 中 BUS_OUT 节点在 busName 上的本地冲突。
     *  共享模型：跨方块同名 BUS_OUT 共享频道，是合法的，不再是冲突。busConflict
     *  现在仅表示"同图内另一个 BUS_OUT 使用此名称"（警告——同图内同频段会相互覆盖）。 */
    private static void reevaluateBusConflicts(
            io.github.y15173334444.create_schematic_compute.graph.NodeGraph graph,
            String busName) {
        if (graph == null || busName == null || busName.isEmpty()) return;
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
                n.busConflict = localConflict;
            }
        }
    }
}
