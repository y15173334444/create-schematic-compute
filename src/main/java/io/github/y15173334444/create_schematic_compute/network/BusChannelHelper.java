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
 *
 * <p>All methods are safe to call on either side; they no-op on the client
 * and when {@code level} is null.</p>
 */
public final class BusChannelHelper {

    private BusChannelHelper() {}

    // ── Channel registration / unregistration ──────────────

    /** Register every BUS_OUT node in {@code graph} with {@link SignalBus#registerChannel}.
     *  On success also immediately syncs bands to {@code BAND_REGISTRY} so that
     *  other clients' editors can detect cross-block conflicts before the next tick.
     *  @return true if at least one node changed conflict state (caller should trigger a full sync) */
    public static boolean registerChannels(NodeGraph graph, BlockPos pos, @Nullable Level level) {
        if (level == null || level.isClientSide() || graph == null) return false;
        boolean anyConflict = false;
        for (var n : graph.nodes) {
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty() && n.bandCount() > 0) {
                if (n.busInternalMap == null) n.busInternalMap = new HashMap<>();
                boolean ok = SignalBus.registerChannel(n.signalName, n.busInternalMap,
                    new ChannelOwner(pos, n.id));
                if (n.busConflict != !ok) anyConflict = true;
                n.busConflict = !ok;
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

    /** Unregister every BUS_OUT node in {@code graph} from {@link SignalBus#unregisterChannel}. */
    public static void unregisterChannels(NodeGraph graph, BlockPos pos, @Nullable Level level) {
        if (level == null || level.isClientSide() || graph == null) return;
        for (var n : graph.nodes) {
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty()) {
                SignalBus.unregisterChannel(n.signalName, new ChannelOwner(pos, n.id));
                n.busConflict = false;
            }
        }
    }

    // ── Client band-registry cleanup ───────────────────────

    /** Send an empty {@link BusBandSyncPacket} for every unique BUS_OUT name in {@code graph}
     *  so that tracking clients remove stale entries from their {@code BAND_REGISTRY}.
     *  Also clears PRIVATE_OUT signal entries from {@link SignalBus#SIGNALS} to prevent memory leaks.
     *  Called before a block entity is unloaded / destroyed. */
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
     *  send an empty {@link BusBandSyncPacket} so clients drop the stale band list. */
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

    // ── Client graph sync ──────────────────────────────────

    /** Apply a server-pushed band list to matching BUS_IN / BUS_OUT nodes in the local graph.
     *  Old connections on removed bands are pruned. */
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

    // ── Tick-time band-change detection ────────────────────

    /** Check every non-conflicted BUS_OUT node for band-list changes since the last tick
     *  and broadcast a {@link BusBandSyncPacket} when a change is detected.
     *  {@code lastHashMap} maps node id → (signalName.hashCode()*31 + bandCount). */
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

    // ── Conflict auto-recovery ─────────────────────────────

    /** Check every conflicted BUS_OUT node: if the previous channel owner is gone
     *  (CHANNELS has no entry for that name), this node takes over.
     *  Call once per tick before the evaluator runs. */
    public static void recoverConflictedChannels(NodeGraph graph, BlockPos pos, @Nullable Level level) {
        if (level == null || level.isClientSide() || graph == null) return;
        for (var n : graph.nodes) {
            if (n.type == NodeType.BUS_OUT && n.busConflict
                && !n.signalName.isEmpty() && n.bandCount() > 0) {
                if (SignalBus.getChannel(n.signalName) == null) {
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
