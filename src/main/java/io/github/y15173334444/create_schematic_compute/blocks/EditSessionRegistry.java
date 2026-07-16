package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.OpExecutor;
import io.github.y15173334444.create_schematic_compute.graph.OpType;
import io.github.y15173334444.create_schematic_compute.network.GraphEditAckPacket;
import io.github.y15173334444.create_schematic_compute.network.GraphEditOpSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

/**
 * Lightweight server-side registry tracking active edit sessions per graph.
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Maintain the set of online editors per {@link GlobalPos} (dimension + pos)</li>
 *   <li>Assign monotonically increasing editVersion per graph</li>
 *   <li>Validate and apply incoming ops, then broadcast to other editors</li>
 *   <li>Send acks back to the originating player</li>
 *   <li>Keep a bounded opLog for reconnection replay (future Phase 4)</li>
 * </ul>
 */
public final class EditSessionRegistry {

    private static final Map<GlobalPos, Set<UUID>> editors = new HashMap<>();
    private static final Map<GlobalPos, Long> editVersions = new HashMap<>();
    private static final Map<GlobalPos, Deque<GraphOp>> opLogs = new HashMap<>();
    private static final int MAX_OP_LOG = 200;

    private EditSessionRegistry() {}

    /** Build a dimension-aware key from the player's current level. */
    private static GlobalPos key(ServerLevel level, BlockPos pos) {
        return GlobalPos.of(level.dimension(), pos);
    }

    // ── Session management ──

    public static void join(ServerLevel level, BlockPos pos, UUID player) {
        var k = key(level, pos);
        editors.computeIfAbsent(k, kg -> new LinkedHashSet<>()).add(player);
        editVersions.putIfAbsent(k, 1L);
    }

    public static void leave(ServerLevel level, BlockPos pos, UUID player) {
        var k = key(level, pos);
        var set = editors.get(k);
        if (set != null) {
            set.remove(player);
            if (set.isEmpty()) {
                editors.remove(k);
            }
        }
    }

    /** Remove a player from every session they are in (called on disconnect). */
    public static void leaveAll(UUID player) {
        var toRemove = new ArrayList<GlobalPos>();
        for (var entry : editors.entrySet()) {
            entry.getValue().remove(player);
            if (entry.getValue().isEmpty()) toRemove.add(entry.getKey());
        }
        toRemove.forEach(editors::remove);
    }

    public static Set<UUID> getEditors(ServerLevel level, BlockPos pos) {
        var set = editors.get(key(level, pos));
        return set != null ? Collections.unmodifiableSet(set) : Collections.emptySet();
    }

    public static boolean hasEditors(ServerLevel level, BlockPos pos) {
        var set = editors.get(key(level, pos));
        return set != null && !set.isEmpty();
    }

    // ── Versioning ──

    private static long nextVersion(GlobalPos key) {
        long v = editVersions.getOrDefault(key, 0L) + 1;
        editVersions.put(key, v);
        return v;
    }

    // ── Op log ──

    private static void appendToLog(GlobalPos key, GraphOp op) {
        var log = opLogs.computeIfAbsent(key, kg -> new ArrayDeque<>());
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
        var gk = key(level, pos);

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
            if (targetGraph.findNode(op.toId()) == null || targetGraph.findNode(op.fromId()) == null) {
                var reject = new GraphOp(OpType.REJECT, pos, op.ownerNodeId(), op.targetNodeId(), op.actor());
                PacketDistributor.sendToPlayer(actor, new GraphEditOpSyncPacket(reject));
                return;
            }
            if (targetGraph.wouldCreateCycle(op.fromId(), op.toId())) {
                var reject = new GraphOp(OpType.REJECT, pos, op.ownerNodeId(), op.targetNodeId(), op.actor());
                PacketDistributor.sendToPlayer(actor, new GraphEditOpSyncPacket(reject));
                return;
            }
        }

        // 4. ADD_NODE_REQUEST: server allocates real ID → ACK originator → broadcast to others
        if (op.type() == OpType.ADD_NODE_REQUEST) {
            var node = OpExecutor.apply(targetGraph, op);
            long version = nextVersion(gk);
            PacketDistributor.sendToPlayer(actor,
                new GraphEditAckPacket(pos, op.tempId(), node.id, version));
            var broadcastOp = new GraphOp(
                OpType.ADD_NODE, op.graphPos(), op.ownerNodeId(), node.id,
                node.id, op.nodeType(), op.x(), op.y(),
                0, 0, 0, 0, 0, 0f,
                null, 0, 0, 0, 0, null, 0, 0, 0,
                net.minecraft.world.item.ItemStack.EMPTY, version, op.actor()
            );
            var syncPkt = new GraphEditOpSyncPacket(broadcastOp);
            var editors = getEditors(level, pos);
            for (var editorId : editors) {
                if (editorId.equals(actor.getUUID())) continue;
                var editorPlayer = level.getServer().getPlayerList().getPlayer(editorId);
                if (editorPlayer != null) PacketDistributor.sendToPlayer(editorPlayer, syncPkt);
            }
            appendToLog(gk, broadcastOp);
            gbe.getNodeGraph().bumpGeneration();
            markDirty(gbe);
            return;
        }

        // 5. Execute
        OpExecutor.apply(targetGraph, op);
        long version = nextVersion(gk);

        // 6. Broadcast to other editors
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
        var editorsOuter = getEditors(level, pos);
        for (var editorId : editorsOuter) {
            if (editorId.equals(actor.getUUID())) continue;
            var editorPlayer = level.getServer().getPlayerList().getPlayer(editorId);
            if (editorPlayer != null) PacketDistributor.sendToPlayer(editorPlayer, syncPkt);
        }

        // 7. Ack to originator
        PacketDistributor.sendToPlayer(actor,
            new GraphEditAckPacket(pos, 0, 0, version));

        // 8. Log
        appendToLog(gk, broadcastOp);

        // 9. Mark dirty
        gbe.getNodeGraph().bumpGeneration();
        markDirty(gbe);
    }

    /** Mark the block entity dirty so the chunk is saved. Covers all 7 graph host types. */
    private static void markDirty(GraphBlockEntity gbe) {
        if (gbe instanceof BlueprintBlockEntity bbe) bbe.setChanged();
        else if (gbe instanceof MonitorBlockEntity mbe) mbe.setChanged();
        else if (gbe instanceof ProgramComputerBlockEntity pbe) pbe.setChanged();
        else if (gbe instanceof RadarBlockEntity rbe) rbe.setChanged();
        else if (gbe instanceof SensorBlockEntity sbe) sbe.setChanged();
        else if (gbe instanceof SpeedProxyBlockEntity spbe) spbe.setChanged();
        else if (gbe instanceof ControlSeatBlockEntity cbe) cbe.setChanged();
    }

    // ── Cleanup ──

    /** Call on server stopping to clear all state. */
    public static void clearAll() {
        editors.clear();
        editVersions.clear();
        opLogs.clear();
    }
}
