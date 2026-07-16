package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.OpExecutor;
import io.github.y15173334444.create_schematic_compute.graph.OpType;
import io.github.y15173334444.create_schematic_compute.network.GraphEditAckPacket;
import io.github.y15173334444.create_schematic_compute.network.GraphEditOpPacket;
import io.github.y15173334444.create_schematic_compute.network.GraphEditOpSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/**
 * Lightweight server-side registry tracking active edit sessions per graph.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Maintain the set of online editors per {@link BlockPos}</li>
 *   <li>Assign monotonically increasing editVersion per graph</li>
 *   <li>Validate and apply incoming ops, then broadcast to other editors</li>
 *   <li>Send acks back to the originating player</li>
 *   <li>Keep a bounded opLog for reconnection replay (future Phase 4)</li>
 * </ul>
 */
public final class EditSessionRegistry {

    private static final Map<BlockPos, Set<UUID>> editors = new HashMap<>();
    private static final Map<BlockPos, Long> editVersions = new HashMap<>();
    private static final Map<BlockPos, Deque<GraphOp>> opLogs = new HashMap<>();
    private static final int MAX_OP_LOG = 200;

    private EditSessionRegistry() {}

    // ── Session management ──

    public static void join(BlockPos pos, UUID player) {
        editors.computeIfAbsent(pos, k -> new LinkedHashSet<>()).add(player);
        // Init version if this is the first editor
        editVersions.putIfAbsent(pos, 1L);
    }

    public static void leave(BlockPos pos, UUID player) {
        var set = editors.get(pos);
        if (set != null) {
            set.remove(player);
            if (set.isEmpty()) {
                editors.remove(pos);
                // Keep editVersion and opLog for a while in case of reconnect
            }
        }
    }

    public static Set<UUID> getEditors(BlockPos pos) {
        var set = editors.get(pos);
        return set != null ? Collections.unmodifiableSet(set) : Collections.emptySet();
    }

    public static boolean hasEditors(BlockPos pos) {
        var set = editors.get(pos);
        return set != null && !set.isEmpty();
    }

    // ── Versioning ──

    public static long currentVersion(BlockPos pos) {
        return editVersions.getOrDefault(pos, 0L);
    }

    private static long nextVersion(BlockPos pos) {
        long v = editVersions.getOrDefault(pos, 0L) + 1;
        editVersions.put(pos, v);
        return v;
    }

    // ── Op log ──

    public static List<GraphOp> getOpsSince(BlockPos pos, long sinceVersion) {
        var log = opLogs.get(pos);
        if (log == null) return Collections.emptyList();
        var recent = new ArrayList<GraphOp>();
        for (var op : log) {
            if (op.editVersion() > sinceVersion) recent.add(op);
        }
        return recent;
    }

    private static void appendToLog(BlockPos pos, GraphOp op) {
        var log = opLogs.computeIfAbsent(pos, k -> new ArrayDeque<>());
        log.addLast(op);
        while (log.size() > MAX_OP_LOG) log.removeFirst();
    }

    // ── Core: applyOp ──

    /**
     * Validate, apply, broadcast, and ack an edit op received from a client.
     *
     * @param level  the server level
     * @param pos    the block entity position
     * @param op     the incoming operation (from client)
     * @param actor  the player who sent the op
     */
    public static void applyOp(ServerLevel level, BlockPos pos, GraphOp op, ServerPlayer actor) {
        io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.info("[Collab] applyOp {} from {} editors={}", op.type(), actor.getGameProfile().getName(), getEditors(pos).size());
        // 1. Get BE and graph
        if (!(level.getBlockEntity(pos) instanceof GraphBlockEntity gbe)) return;
        var graph = gbe.getNodeGraph();
        if (graph == null) return;

        // 2. Route to sub-graph
        var targetGraph = graph;
        if (op.ownerNodeId() >= 0) {
            var encap = graph.findNode(op.ownerNodeId());
            if (encap != null && encap.subGraph != null) {
                targetGraph = encap.subGraph;
            } else return;
        }

        // 3. Validate structural ops
        if (op.type() == OpType.ADD_CONN) {
            // Check both endpoints exist in the graph the op actually targets
            // (sub-graphs have independent ID spaces — must NOT check the outer graph)
            if (targetGraph.findNode(op.toId()) == null || targetGraph.findNode(op.fromId()) == null) {
                var reject = new GraphOp(OpType.REJECT, pos, op.ownerNodeId(), op.targetNodeId(), op.actor());
                PacketDistributor.sendToPlayer(actor, new GraphEditOpSyncPacket(reject));
                return;
            }
            // Prevent cycles — read-only reachability check on the target graph itself.
            // (NodeGraph.copy() remaps node IDs, so testing op IDs against a copy is invalid.)
            if (targetGraph.wouldCreateCycle(op.fromId(), op.toId())) {
                var reject = new GraphOp(OpType.REJECT, pos, op.ownerNodeId(), op.targetNodeId(), op.actor());
                PacketDistributor.sendToPlayer(actor, new GraphEditOpSyncPacket(reject));
                return;
            }
        }

        // 5. Execute
        OpExecutor.apply(targetGraph, op);
        long version = nextVersion(pos);

        // 6. Broadcast to other editors (skip originator to avoid double-apply)
        var broadcastOp = new GraphOp(
            op.type(), op.graphPos(), op.ownerNodeId(), op.targetNodeId(),
            op.tempId(), op.nodeType(), op.x(), op.y(),
            op.fromId(), op.fromPin(), op.toId(), op.toPin(),
            op.paramIndex(), op.paramValue(), op.stringValue(),
            op.colorBg(), op.colorBorder(), op.colorText(),
            op.sortB(), op.bands(), op.keyIndex(), op.imageFrameIndex(),
            op.hotbarSlot(), op.itemStack(),
            version, op.actor()
        );
        var syncPkt = new GraphEditOpSyncPacket(broadcastOp);
        var editors = getEditors(pos);
        var chunkPos = new ChunkPos(pos);
        for (var editorId : editors) {
            if (editorId.equals(actor.getUUID())) continue;
            var editorPlayer = level.getServer().getPlayerList().getPlayer(editorId);
            if (editorPlayer != null) {
                PacketDistributor.sendToPlayer(editorPlayer, syncPkt);
            }
        }

        // 7. Ack to originator
        PacketDistributor.sendToPlayer(actor,
            new GraphEditAckPacket(pos, 0, 0, version));

        // 8. Log
        appendToLog(pos, broadcastOp);

        // 9. Mark dirty
        gbe.getNodeGraph().bumpGeneration();
        if (gbe instanceof BlueprintBlockEntity bbe) {
            bbe.setChanged();
        }
    }

    // ── Cleanup ──

    /** Call on server stopping to clear all state. */
    public static void clearAll() {
        editors.clear();
        editVersions.clear();
        opLogs.clear();
    }
}
