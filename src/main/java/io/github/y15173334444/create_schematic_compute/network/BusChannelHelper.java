package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Shared bus channel lifecycle methods, extracted from the four
 * GraphBlockEntity implementations to eliminate ~160 lines of duplication.
 * <p>共享总线频道生命周期方法，从四个 GraphBlockEntity 实现中提取，消除了约 160 行重复代码。</p>
 *
 * <p>All methods are safe to call on either side; they no-op on the client
 * and when {@code level} is null.
 * 所有方法可在任意端安全调用；在客户端和 level 为 null 时为空操作。</p>
 */
public final class BusChannelHelper {

    private BusChannelHelper() {}

    // ── Channel registration / unregistration / 频道注册 / 取消注册 ──────────────

    /** Register every BUS_OUT node in {@code graph} with {@link SignalBus#registerChannel}.
     *  将 graph 中每个 BUS_OUT 节点注册到 SignalBus.registerChannel。
     *  On success also immediately syncs bands to {@code BAND_REGISTRY} so that
     *  other clients' editors can detect cross-block conflicts before the next tick.
     *  成功时立即将频段同步到 BAND_REGISTRY，使其他客户端编辑器能在下个 tick 前检测跨方块冲突。
     *  @return true if at least one node changed conflict state (caller should trigger a full sync) / 若至少一个节点的冲突状态变化则返回 true（调用方应触发完整同步） */
    public static boolean registerChannels(NodeGraph graph, BlockPos pos, @Nullable Level level) {
        if (level == null || level.isClientSide() || graph == null) return false;
        boolean anyConflict = false;
        for (var n : graph.nodes) {
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty()) {
                if (n.busInternalMap == null) n.busInternalMap = new HashMap<>();
                boolean ok = SignalBus.registerChannel(n.signalName, n.busInternalMap,
                    new ChannelOwner(pos, n.id));
                if (n.busConflict != !ok) anyConflict = true;
                n.busConflict = !ok;
                // EN: Registration succeeded → immediately sync bands to BAND_REGISTRY and broadcast to clients
                // 注册成功 → 立即同步 bands 到 BAND_REGISTRY 并广播客户端
                if (ok && n.signalBands != null && !n.signalBands.isEmpty()) {
                    SignalBus.registerBands(n.signalName, n.signalBands);
                    n.bandsDirty = false;
                    if (level instanceof ServerLevel sl) {
                        PacketDistributor.sendToPlayersTrackingChunk(sl,
                            new ChunkPos(pos),
                            new BusBandSyncPacket(pos, n.signalName, n.signalBands));
                    }
                }
            }
        }
        return anyConflict;
    }

    /** Unregister every BUS_OUT node in {@code graph} from {@link SignalBus#unregisterChannel}. / 将 graph 中每个 BUS_OUT 节点从 SignalBus.unregisterChannel 取消注册。 */
    public static void unregisterChannels(NodeGraph graph, BlockPos pos, @Nullable Level level) {
        if (level == null || level.isClientSide() || graph == null) return;
        for (var n : graph.nodes) {
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty()) {
                SignalBus.unregisterChannel(n.signalName, new ChannelOwner(pos, n.id));
                n.busConflict = false;
            }
        }
    }

    // ── Client band-registry cleanup / 客户端频段注册表清理 ───────────────────────

    /** Send an empty {@link BusBandSyncPacket} for every unique BUS_OUT name in {@code graph}
     *  so that tracking clients remove stale entries from their {@code BAND_REGISTRY}.
     *  Also clears PRIVATE_OUT signal entries from {@link SignalBus#SIGNALS} to prevent memory leaks.
     *  Called before a block entity is unloaded / destroyed.
     *  为 graph 中每个唯一 BUS_OUT 名称发送空 BusBandSyncPacket，使追踪客户端从其 BAND_REGISTRY 中移除过期条目。
     *  同时清除 SignalBus.SIGNALS 中的 PRIVATE_OUT 信号条目以防止内存泄漏。在方块实体卸载/销毁前调用。 */
    public static void cleanupClientBands(NodeGraph graph, BlockPos pos, @Nullable Level level) {
        if (level == null || level.isClientSide() || graph == null) return;
        if (level instanceof ServerLevel sl) {
            var names = new HashSet<String>();
            for (var n : graph.nodes) {
                if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty()) names.add(n.signalName);
                else if (n.type == NodeType.PRIVATE_OUT && !n.signalName.isEmpty())
                    SignalBus.clearSignal(n.signalName);
            }
            for (var name : names) {
                PacketDistributor.sendToPlayersTrackingChunk(sl,
                    new ChunkPos(pos),
                    new BusBandSyncPacket(pos, name, Collections.emptyList()));
            }
        }
    }

    /** For every BUS_OUT name present in {@code oldGraph} but absent in {@code newGraph},
     *  send an empty {@link BusBandSyncPacket} so clients drop the stale band list.
     *  对于存在于 oldGraph 但不在 newGraph 中的每个 BUS_OUT 名称，发送空 BusBandSyncPacket 使客户端丢弃过期的频段列表。 */
    public static void syncDeletedBusNames(NodeGraph oldGraph, @Nullable NodeGraph newGraph,
                                            BlockPos pos, @Nullable Level level) {
        if (!(level instanceof ServerLevel sl) || oldGraph == null) return;
        var oldBusNames = new HashSet<String>();
        for (var n : oldGraph.nodes)
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty()) oldBusNames.add(n.signalName);
        if (oldBusNames.isEmpty()) return;
        for (var name : oldBusNames) {
            boolean stillExists = false;
            if (newGraph != null) {
                for (var n : newGraph.nodes) {
                    if (n.type == NodeType.BUS_OUT && n.signalName.equals(name)) {
                        stillExists = true; break;
                    }
                }
            }
            if (!stillExists) {
                PacketDistributor.sendToPlayersTrackingChunk(sl,
                    new ChunkPos(pos),
                    new BusBandSyncPacket(pos, name, Collections.emptyList()));
            }
        }
    }

    // ── Client graph sync / 客户端图同步 ──────────────────────────────────

    /** Apply a server-pushed band list to matching BUS_IN / BUS_OUT nodes in the local graph.
     *  Old connections on removed bands are pruned.
     *  将服务端推送的频段列表应用到本地图中匹配的 BUS_IN / BUS_OUT 节点。被移除频段上的旧连接会被修剪。 */
    public static void syncBandsFromServer(String busName, List<String> bands, NodeGraph graph) {
        if (graph == null) return;
        List<String> newBands = bands != null ? bands : Collections.emptyList();
        for (var n : graph.nodes) {
            if ((n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT)
                && n.signalName.equals(busName)) {
                if (!newBands.equals(n.signalBands)) {
                    int oldCount = n.bandCount();
                    for (int pi = 0; pi < oldCount; pi++) {
                        final int p = pi;
                        graph.connections.removeIf(c ->
                            (c.fromId == n.id && c.fromPin == p) || (c.toId == n.id && c.toPin == p));
                    }
                    n.signalBands = new ArrayList<>(newBands);
                    n.bandsDirty = true;
                }
            }
        }
    }

    // ── Tick-time band-change detection / Tick 时刻频段变更检测 ────────────────────

    /** Check every non-conflicted BUS_OUT node for band-list changes since the last tick
     *  and broadcast a {@link BusBandSyncPacket} when a change is detected.
     *  {@code lastHashMap} maps node id → (signalName.hashCode()*31 + bandCount).
     *  检查每个无冲突的 BUS_OUT 节点自上次 tick 以来的频段列表变更，检测到变更时广播 BusBandSyncPacket。
     *  lastHashMap 映射 节点id → (signalName.hashCode()*31 + bandCount)。 */
    public static void syncIfBandsChanged(NodeGraph graph, BlockPos pos,
                                           Map<Integer, Integer> lastHashMap, @Nullable Level level) {
        if (!(level instanceof ServerLevel sl) || graph == null) return;
        for (var n : graph.nodes) {
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty() && !n.busConflict) {
                int h = n.signalName.hashCode() * 31 + n.bandCount();
                Integer prev = lastHashMap.get(n.id);
                if (prev == null || prev != h) {
                    lastHashMap.put(n.id, h);
                    PacketDistributor.sendToPlayersTrackingChunk(sl,
                        new ChunkPos(pos),
                        new BusBandSyncPacket(pos, n.signalName, n.signalBands));
                }
            }
        }
    }

    // ── Conflict auto-recovery / 冲突自动恢复 ─────────────────────────────

    /** Check every conflicted BUS_OUT node: if the previous channel owner is gone
     *  (CHANNELS has no entry for that name), this node takes over.
     *  Call once per tick before the evaluator runs.
     *  检查每个冲突的 BUS_OUT 节点：若原频道所有者已消失（CHANNELS 中无该名称条目），则由此节点接管。
     *  每 tick 在评估器运行前调用一次。 */
    public static void recoverConflictedChannels(NodeGraph graph, BlockPos pos, @Nullable Level level) {
        if (level == null || level.isClientSide() || graph == null) return;
        for (var n : graph.nodes) {
            if (n.type == NodeType.BUS_OUT && n.busConflict
                && !n.signalName.isEmpty() && n.bandCount() > 0) {
                if (SignalBus.getChannel(n.signalName) == null) {
                    // EN: First owner is gone → take over the channel and immediately sync bands to clients
                    // 首个 owner 已消失 → 接管频道并立即同步 bands 到客户端
                    if (n.busInternalMap == null) n.busInternalMap = new java.util.HashMap<>();
                    SignalBus.registerChannel(n.signalName, n.busInternalMap,
                        new ChannelOwner(pos, n.id));
                    n.busConflict = false;
                    if (n.signalBands != null && !n.signalBands.isEmpty()) {
                        SignalBus.registerBands(n.signalName, n.signalBands);
                        if (level instanceof ServerLevel sl) {
                            PacketDistributor.sendToPlayersTrackingChunk(sl,
                                new ChunkPos(pos),
                                new BusBandSyncPacket(pos, n.signalName, n.signalBands));
                        }
                    }
                    n.bandsDirty = false;
                }
            }
        }
    }
}
