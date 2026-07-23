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
 * 轻量级服务端注册表，跟踪每个图的活跃编辑会话。
 *
 * <p>Responsibilities: / 职责:</p>
 * <ul>
 *   <li>Maintain the set of online editors per {@link GlobalPos} (dimension + pos)
 *       / 维护每个 {@link GlobalPos}（维度 + 坐标）的在线编辑者集合</li>
 *   <li>Assign monotonically increasing editVersion per graph
 *       / 为每个图分配单调递增的 editVersion</li>
 *   <li>Validate and apply incoming ops, then broadcast to other editors
 *       / 验证并应用传入的操作，然后广播给其他编辑者</li>
 *   <li>Send acks back to the originating player
 *       / 向发起操作的玩家发送确认</li>
 *   <li>Keep a bounded opLog for reconnection replay (future Phase 4)
 *       / 保持有界 opLog 用于重连回放（未来第四阶段）</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> All methods must be called from the main server thread.
 * NeoForge's {@code ctx.enqueueWork()} guarantees this for all current call sites
 * via the network packet handlers.</p>
 */
public final class EditSessionRegistry {

    private static final Map<GlobalPos, Set<UUID>> editors = new HashMap<>();
    private static final Map<GlobalPos, Long> editVersions = new HashMap<>();
    private static final Map<GlobalPos, Deque<GraphOp>> opLogs = new HashMap<>();
    private static final int MAX_OP_LOG = 200;

    private EditSessionRegistry() {}

    /** Build a dimension-aware key from the player's current level.
     *  从玩家当前所在维度构建维度感知的键。 */
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

    /** Remove a player from every session they are in (called on disconnect).
     *  将玩家从其所在的所有会话中移除（断开连接时调用）。 */
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
     * 验证、应用、广播并确认从客户端收到的编辑操作。
     *
     * @param level  the server level / 服务器世界
     * @param pos    the block entity position / 方块实体坐标
     * @param op     the incoming operation (from client) / 传入的操作（来自客户端）
     * @param actor  the player who sent the op / 发送操作的玩家
     */
    public static void applyOp(ServerLevel level, BlockPos pos, GraphOp op, ServerPlayer actor) {
        var gk = key(level, pos);

        // 1. Get BE and graph / 获取方块实体和图
        if (!(level.getBlockEntity(pos) instanceof GraphBlockEntity gbe)) return;
        var graph = gbe.getNodeGraph();
        if (graph == null) return;

        // 2. Route to sub-graph / 路由到子图
        var targetGraph = graph;
        if (op.ownerNodeId() >= 0) {
            var encap = graph.findNode(op.ownerNodeId());
            if (encap == null) return; // 封装节点不存在 / encap node doesn't exist
            if (encap.subGraph == null) encap.subGraph = new io.github.y15173334444.create_schematic_compute.graph.NodeGraph();
            targetGraph = encap.subGraph;
        }

        // 3. Validate structural ops / 验证结构操作
        if (op.type() == OpType.ADD_CONN) {
            if (targetGraph.findNode(op.toId()) == null || targetGraph.findNode(op.fromId()) == null) {
                var reject = new GraphOp(OpType.REJECT, pos, op.ownerNodeId(), op.targetNodeId(),
                    0, null, 0f, 0f, op.fromId(), op.fromPin(), op.toId(), op.toPin(),
                    0, 0f, null, 0, 0, 0, 0, null, 0, 0, 0, net.minecraft.world.item.ItemStack.EMPTY, 0L, op.actor());
                PacketDistributor.sendToPlayer(actor, new GraphEditOpSyncPacket(reject));
                return;
            }
            if (targetGraph.wouldCreateCycle(op.fromId(), op.toId())) {
                var reject = new GraphOp(OpType.REJECT, pos, op.ownerNodeId(), op.targetNodeId(),
                    0, null, 0f, 0f, op.fromId(), op.fromPin(), op.toId(), op.toPin(),
                    0, 0f, null, 0, 0, 0, 0, null, 0, 0, 0, net.minecraft.world.item.ItemStack.EMPTY, 0L, op.actor());
                PacketDistributor.sendToPlayer(actor, new GraphEditOpSyncPacket(reject));
                return;
            }
        }

        // 4. ADD_NODE_REQUEST: server allocates real ID → ACK originator → broadcast to others
        // 4. ADD_NODE_REQUEST: 服务端分配真实 ID → 确认发起者 → 广播给其他人
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
                net.minecraft.world.item.ItemStack.EMPTY, version, op.actor(), 0, null
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

        // 5. Execute / 执行
        OpExecutor.apply(targetGraph, op);
        long version = nextVersion(gk);

        // 6. Broadcast to other editors / 广播给其他编辑者
        var broadcastOp = new GraphOp(
            op.type(), op.graphPos(), op.ownerNodeId(), op.targetNodeId(),
            op.tempId(), op.nodeType(), op.x(), op.y(),
            op.fromId(), op.fromPin(), op.toId(), op.toPin(),
            op.paramIndex(), op.paramValue(), op.stringValue(),
            op.colorBg(), op.colorBorder(), op.colorText(),
            op.sortB(), op.bands(), op.keyIndex(), op.imageFrameIndex(),
            op.hotbarSlot(), op.itemStack(),
            version, op.actor(), op.blobRefId(), op.imageData()
        );
        var syncPkt = new GraphEditOpSyncPacket(broadcastOp);
        var editorsOuter = getEditors(level, pos);
        for (var editorId : editorsOuter) {
            if (editorId.equals(actor.getUUID())) continue;
            var editorPlayer = level.getServer().getPlayerList().getPlayer(editorId);
            if (editorPlayer != null) PacketDistributor.sendToPlayer(editorPlayer, syncPkt);
        }

        // 7. Ack to originator / 确认给发起者
        PacketDistributor.sendToPlayer(actor,
            new GraphEditAckPacket(pos, 0, 0, version));

        // 8. Log / 记录日志
        appendToLog(gk, broadcastOp);

        // 9. Mark dirty / 标记脏数据
        gbe.getNodeGraph().bumpGeneration();
        markDirty(gbe);
        // 10. For display-affecting ops, trigger a block update so tracking clients
        //     (including players without the UI open) get the latest graph via getUpdateTag().
        // 10. 对于影响显示的操作，触发放块更新，使跟踪客户端
        //     （包括未打开 UI 的玩家）通过 getUpdateTag() 获取最新图。
        if (op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_LAYOUT
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_PARAM
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_TEXT_COLOR
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_IMAGE_PIXELS
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_IMAGE_FRAME_TOGGLE
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.ADD_NODE
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.REMOVE_NODE) {
            if (gbe instanceof MonitorBlockEntity mbe) {
                mbe.flagFullSync();
            }
        }
    }

    /** Mark the block entity dirty so the chunk is saved.
     *  标记方块实体为脏数据以便区块保存。 */
    private static void markDirty(GraphBlockEntity gbe) {
        if (gbe instanceof net.minecraft.world.level.block.entity.BlockEntity be) be.setChanged();
    }

    // ── Cleanup ──

    /** Call on server stopping to clear all state.
     *  服务端停止时调用以清除所有状态。 */
    public static void clearAll() {
        editors.clear();
        editVersions.clear();
        opLogs.clear();
    }
}
