package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
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
                // 共享模型（回归审计）：所有同名 BUS_OUT（可跨方块）都加入共享频道，
                // registerChannel 恒成功。busConflict 现在表示"同图内重名"（本地冲突）。
                // Shared model: same-name BUS_OUT across blocks all join the shared channel;
                // registerChannel always succeeds. busConflict now means same-graph duplicate.
                SignalBus.registerChannel(n.signalName, new ChannelOwner(pos, n.id));
                boolean localDup = hasLocalDuplicate(graph, n);
                if (n.busConflict != localDup) anyConflict = true;
                n.busConflict = localDup;
                // 非本地冲突节点才让频段定义成为频道权威列表（同图重名的频段可能不一致）
                // Only non-locally-conflicted nodes publish their bands as the channel list
                if (!localDup && n.signalBands != null && !n.signalBands.isEmpty()) {
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

    /** 同图内是否存在另一个同 signalName 的 BUS_OUT（本地冲突 = 共享同频段导致相互覆盖）。 */
    private static boolean hasLocalDuplicate(NodeGraph graph, GraphNode n) {
        if (graph == null) return false;
        int count = 0;
        for (var other : graph.nodes) {
            if (other.type == NodeType.BUS_OUT && other.signalName.equals(n.signalName)) {
                count++;
                if (count > 1) return true;
            }
        }
        return false;
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
     *  Only connections on bands that were actually removed are pruned (matched by band name / pinId).
     *  将服务端推送的频段列表应用到本地图中匹配的 BUS_IN / BUS_OUT 节点。
     *  仅删除被实际移除的频段（按频段名 / pinId 匹配）上的连接。 */
    public static void syncBandsFromServer(String busName, List<String> bands, NodeGraph graph) {
        if (graph == null) return;
        List<String> newBands = bands != null ? bands : Collections.emptyList();
        for (var n : graph.nodes) {
            if ((n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT)
                && n.signalName.equals(busName)) {
                // 共享模型（回归审计）：频道 band 列表对所有同名 BUS_OUT 权威统一，
                // 不再跳过 busConflict 节点（busConflict 现在仅表示同图重名警告）。
                // Shared model: the channel band list is authoritative for every same-name
                // BUS_OUT; no longer skip conflicted nodes (busConflict now just warns).
                if (!newBands.equals(n.signalBands)) {
                    // Collect removed band names (pinIds) by comparing old vs new
                    // 通过对比新旧集合，收集被删除的频段名（pinId）
                    var oldBands = n.signalBands != null ? n.signalBands : Collections.<String>emptyList();
                    var removed = new ArrayList<>(oldBands);
                    removed.removeAll(newBands);
                    n.signalBands = new ArrayList<>(newBands);
                    n.bandsDirty = true;
                    // Only remove connections on bands that were actually deleted,
                    // matched by band name (= pinId). This preserves connections
                    // on bands that were merely reordered.
                    // 仅删除实际被移除频段上的连接（按频段名 = pinId 匹配）。
                    // 仅被重排的频段上的连接得以保留。
                    for (String removedBand : removed) {
                        graph.connections.removeIf(c ->
                            (c.fromId == n.id && removedBand.equals(c.fromPinId)) ||
                            (c.toId == n.id && removedBand.equals(c.toPinId)));
                    }
                    graph.rebuildNodeMap(); // invalidate inputCache / 刷新 inputCache
                    graph.rebuildInputCache();
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

    // ── Diff-based re-registration (preserves channel ownership across recompiles) ──
    // ── 基于差异的重新注册（在重编译期间保留频道所有权） ──

    /**
     * Re-register BUS channels after a graph change, preserving existing ownership.
     * <p>图变更后重新注册 BUS 频道，保留现有所有权。</p>
     * <p>Unlike the naive unregister-all-then-register-all pattern, this method:
     * <ul>
     *   <li>Unregisters only BUS_OUT nodes that were <b>removed</b> from the graph</li>
     *   <li>Updates the internalMap reference for nodes that <b>remain</b> in the graph
     *       (without changing ref-count, so ownership is never lost)</li>
     *   <li>Registers <b>new</b> BUS_OUT nodes normally (first-registrant-wins)</li>
     * </ul>
     * This prevents a newly-added BUS_OUT with the same signalName from stealing
     * the channel during the brief window when all channels are unregistered.
     * 与简单的"先全部取消注册再全部注册"模式不同，此方法：
     * <ul>
     *   <li>仅取消注册从图中<b>移除</b>的 BUS_OUT 节点</li>
     *   <li>更新<b>保留</b>在图中节点的 internalMap 引用（不改变引用计数，因此所有权永不丢失）</li>
     *   <li>正常注册<b>新增</b>的 BUS_OUT 节点（先注册者胜）</li>
     * </ul>
     * 这防止了新添加的同名 BUS_OUT 在所有频道被取消注册的短暂窗口期间窃取频道。</p>
     *
     * @param newGraph the graph after the change / 变更后的图
     * @param oldGraph the graph before the change (may be null, treated as all-new) / 变更前的图（可为 null，视为全新）
     * @param pos      the block position for owner identification / 用于所有者识别的方块坐标
     * @param level    the server level / 服务端世界
     * @return true if at least one node changed conflict state / 若至少一个节点的冲突状态变化则返回 true
     */
    public static boolean reRegisterChannels(NodeGraph newGraph, @Nullable NodeGraph oldGraph,
                                              BlockPos pos, @Nullable Level level) {
        if (level == null || level.isClientSide() || newGraph == null) return false;
        boolean anyConflict = false;

        // Build a set of (signalName, nodeId) keys that exist in the new graph
        // 构建新图中存在的 (signalName, nodeId) 键集合
        var newKeys = new HashSet<String>();
        for (var n : newGraph.nodes)
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty())
                newKeys.add(n.signalName + "@" + n.id);

        // Step 1: Unregister only REMOVED nodes (in oldGraph but not in newGraph)
        // 步骤1：仅取消注册已移除的节点（在 oldGraph 中但不在 newGraph 中）
        if (oldGraph != null) {
            for (var n : oldGraph.nodes) {
                if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty()
                    && !newKeys.contains(n.signalName + "@" + n.id)) {
                    SignalBus.unregisterChannel(n.signalName, new ChannelOwner(pos, n.id));
                }
            }
        }

        // Step 2: Register NEW nodes and update EXISTING nodes
        // 步骤2：注册新节点并更新现有节点
        // Build a set of keys that existed in the old graph (for distinguishing new vs existing)
        // 构建旧图中存在的键集合（用于区分新增与现有）
        var oldKeys = new HashSet<String>();
        if (oldGraph != null) {
            for (var n : oldGraph.nodes)
                if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty())
                    oldKeys.add(n.signalName + "@" + n.id);
        }

        for (var n : newGraph.nodes) {
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty()) {
                // 共享模型（回归审计）：所有同名 BUS_OUT 都加入共享频道（join 幂等，
                // 无需 updateChannel 的 map 引用刷新——共享 map 存在 ChannelEntry 中）。
                // busConflict 表示同图内重名（本地冲突）。
                // Shared model: same-name BUS_OUT all join the shared channel (idempotent
                // join; updateChannel's map-ref refresh is obsolete — the shared map lives
                // in ChannelEntry). busConflict means same-graph duplicate.
                SignalBus.registerChannel(n.signalName, new ChannelOwner(pos, n.id));
                boolean localDup = hasLocalDuplicate(newGraph, n);
                if (n.busConflict != localDup) anyConflict = true;
                n.busConflict = localDup;
                if (!localDup && n.signalBands != null && !n.signalBands.isEmpty()) {
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

    // ── Conflict auto-recovery / 冲突自动恢复 ─────────────────────────────

}
