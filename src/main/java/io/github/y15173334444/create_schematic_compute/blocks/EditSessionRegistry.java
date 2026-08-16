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

    /** Active editors per graph key. / 每个图键的活跃编辑者集合。 */
    private static final Map<GlobalPos, Set<UUID>> editors = new HashMap<>();

    /** Monotonically increasing edit version per graph key.
     *  每个图键的单调递增编辑版本号。 */
    private static final Map<GlobalPos, Long> editVersions = new HashMap<>();

    /** Bounded operation log per graph key, for future reconnection replay.
     *  每个图键的有界操作日志，用于未来的重连回放。 */
    private static final Map<GlobalPos, Deque<GraphOp>> opLogs = new HashMap<>();

    /** Maximum number of ops retained in each graph's op log.
     *  每个图操作日志中保留的最大操作数。 */
    private static final int MAX_OP_LOG = 200;

    /** Utility class — no instances. / 工具类 — 不允许实例化。 */
    private EditSessionRegistry() {}

    /**
     * Build a dimension-aware key from the player's current level.
     * 从玩家当前所在维度构建维度感知的键。
     *
     * <p>A {@link GlobalPos} bundles the dimension with the position, so two blocks
     * at the same coordinates but in different dimensions (e.g. Overworld vs Nether)
     * produce distinct keys. This is essential because graph state is dimension-local.</p>
     * {@link GlobalPos} 将维度与坐标绑定，因此位于相同坐标但不同维度（如主世界与下界）
     * 的两个方块会生成不同的键。这一点至关重要，因为图状态是维度局部的。
     *
     * @param level the server level holding the dimension / 包含维度信息的服务端世界
     * @param pos   the block position / 方块坐标
     * @return a dimension-qualified key / 维度限定的键
     */
    private static GlobalPos key(ServerLevel level, BlockPos pos) {
        return GlobalPos.of(level.dimension(), pos);
    }

    // ── Session management / 会话管理 ──

    /**
     * Register a player as an active editor of the graph at the given position.
     * 将玩家注册为指定位置图的活跃编辑者。
     *
     * <p>Uses {@link LinkedHashSet} for the editor set so iteration order is
     * deterministic across the same session (useful for UI ordering).
     * 使用 {@link LinkedHashSet} 作为编辑者集合，使同一会话中的迭代顺序可预测（有助于 UI 排序）。</p>
     *
     * @param level  the server level / 服务端世界
     * @param pos    the block entity position / 方块实体坐标
     * @param player the joining player's UUID / 加入玩家的 UUID
     */
    public static void join(ServerLevel level, BlockPos pos, UUID player) {
        var k = key(level, pos);
        editors.computeIfAbsent(k, kg -> new LinkedHashSet<>()).add(player);
        // Initialize edit version to 1 so the first op gets version ≥ 2,
        // leaving room for version 1 as an implicit "initial state" sentinel.
        // 将编辑版本初始化为 1，使第一个操作获得版本号 ≥ 2，
        // 为版本号 1 留出空间作为隐式的"初始状态"标记。
        editVersions.putIfAbsent(k, 1L);
    }

    /**
     * Remove a player from the editor set for a specific graph.
     * 将玩家从指定图的编辑者集合中移除。
     *
     * <p>When the last editor leaves, the empty set is removed from the map
     * to avoid leaking memory for graphs that are no longer being edited.
     * 当最后一位编辑者离开时，空集合会从映射中移除，以避免为不再被编辑的图泄漏内存。</p>
     *
     * @param level  the server level / 服务端世界
     * @param pos    the block entity position / 方块实体坐标
     * @param player the leaving player's UUID / 离开玩家的 UUID
     */
    public static void leave(ServerLevel level, BlockPos pos, UUID player) {
        var k = key(level, pos);
        var set = editors.get(k);
        if (set != null) {
            set.remove(player);
            // Clean up the map entry when no editors remain — prevents unbounded growth.
            // 当没有编辑者残留时清理映射条目 — 防止无限制增长。
            if (set.isEmpty()) {
                editors.remove(k);
            }
        }
    }

    /**
     * Remove a player from every session they are in (called on disconnect).
     * 将玩家从其所在的所有会话中移除（断开连接时调用）。
     *
     * <p>Iterates over all graph sessions and removes the player. Sessions that become
     * empty after removal are cleaned up to prevent memory leaks. Deferred removal
     * ({@code toRemove}) avoids {@link ConcurrentModificationException} when removing
     * entries during iteration.
     * 遍历所有图会话并移除玩家。移除后变为空的会话将被清理以防止内存泄漏。
     * 延迟移除（{@code toRemove}）可避免在迭代期间移除条目时出现 {@link ConcurrentModificationException}。</p>
     *
     * @param player the disconnecting player's UUID / 断开连接玩家的 UUID
     */
    public static void leaveAll(UUID player) {
        var toRemove = new ArrayList<GlobalPos>();
        for (var entry : editors.entrySet()) {
            entry.getValue().remove(player);
            if (entry.getValue().isEmpty()) toRemove.add(entry.getKey());
        }
        // Deferred removal: removing during iteration would cause ConcurrentModificationException.
        // 延迟移除：在迭代期间移除会导致 ConcurrentModificationException。
        toRemove.forEach(editors::remove);
    }

    /**
     * Get an unmodifiable view of all editors for a graph.
     * 获取某个图所有编辑者的不可修改视图。
     *
     * <p>Returns an unmodifiable set to prevent external code from mutating
     * the editor list directly — all mutations must go through {@link #join}
     * and {@link #leave}.
     * 返回不可修改集合，防止外部代码直接修改编辑者列表 — 所有修改必须通过
     * {@link #join} 和 {@link #leave} 进行。</p>
     *
     * @param level the server level / 服务端世界
     * @param pos   the block entity position / 方块实体坐标
     * @return an unmodifiable set of editor UUIDs, or an empty set if none
     *         / 编辑者 UUID 的不可修改集合，如果没有编辑者则返回空集合
     */
    public static Set<UUID> getEditors(ServerLevel level, BlockPos pos) {
        var set = editors.get(key(level, pos));
        return set != null ? Collections.unmodifiableSet(set) : Collections.emptySet();
    }

    /**
     * Check whether any player is currently editing the graph at the given position.
     * 检查是否有玩家正在编辑指定位置的图。
     *
     * @param level the server level / 服务端世界
     * @param pos   the block entity position / 方块实体坐标
     * @return true if at least one editor is active / 如果至少有一位活跃编辑者则返回 true
     */
    public static boolean hasEditors(ServerLevel level, BlockPos pos) {
        var set = editors.get(key(level, pos));
        return set != null && !set.isEmpty();
    }

    // ── Versioning / 版本管理 ──

    /**
     * Allocate and return the next edit version for a graph.
     * 分配并返回一个图的下一个编辑版本号。
     *
     * <p>Version numbers start at 1 (initial state sentinel). The first op
     * applied will get version 2. Clients use the version to detect
     * conflicts and order concurrent edits deterministically.
     * 版本号从 1 开始（初始状态标记）。第一个应用的操作将获得版本号 2。
     * 客户端使用版本号检测冲突并对并发编辑进行确定性排序。</p>
     *
     * @param key the graph key / 图键
     * @return the newly assigned version number / 新分配的版本号
     */
    private static long nextVersion(GlobalPos key) {
        long v = editVersions.getOrDefault(key, 0L) + 1;
        editVersions.put(key, v);
        return v;
    }

    // ── Op log / 操作日志 ──

    /**
     * Append an applied operation to the graph's bounded operation log.
     * 将已应用的操作追加到图的有界操作日志中。
     *
     * <p>The log is kept at most {@link #MAX_OP_LOG} entries. Once full, the
     * oldest entry is evicted (FIFO). This log is intended for future
     * reconnection replay in Phase 4 — late-joining clients can catch up
     * without receiving a full graph resync.
     * 日志最多保留 {@link #MAX_OP_LOG} 条记录。满时移除最旧的记录（FIFO）。
     * 此日志旨在用于未来第四阶段的重连回放 — 后加入的客户端可以追赶进度
     * 而无需接收完整的图重同步。</p>
     *
     * @param key the graph key / 图键
     * @param op  the applied operation (with version already assigned)
     *            / 已应用的操作（已分配版本号）
     */
    private static void appendToLog(GlobalPos key, GraphOp op) {
        var log = opLogs.computeIfAbsent(key, kg -> new ArrayDeque<>());
        log.addLast(op);
        // Evict oldest entries when the log exceeds the cap — bounded memory use.
        // 日志超过上限时驱逐最旧的记录 — 确保内存使用有界。
        while (log.size() > MAX_OP_LOG) log.removeFirst();
    }

    // ── Core: applyOp / 核心：applyOp ──

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
        // Server-side validation prevents desync: if a client sends a stale op
        // referencing already-deleted nodes or creating cycles, we reject it
        // before it corrupts the authoritative graph state.
        // 服务端验证防止不同步：如果客户端发送的操作引用了已删除的节点或会创建循环，
        // 我们在其破坏权威图状态之前就拒绝它。
        if (op.type() == OpType.ADD_CONN) {
            // Both endpoints must still exist in the current graph — a concurrent
            // REMOVE_NODE from another editor may have deleted them.
            // 两个端点必须仍然存在于当前图中 — 其他编辑者并发的 REMOVE_NODE 可能已将其删除。
            if (targetGraph.findNode(op.toId()) == null || targetGraph.findNode(op.fromId()) == null) {
                var reject = new GraphOp(OpType.REJECT, pos, op.ownerNodeId(), op.targetNodeId(),
                    0, null, 0f, 0f, op.fromId(), op.fromPin(), op.toId(), op.toPin(),
                    0, 0f, null, 0, 0, 0, 0, null, 0, 0, 0, net.minecraft.world.item.ItemStack.EMPTY, 0L, op.actor());
                PacketDistributor.sendToPlayer(actor, new GraphEditOpSyncPacket(reject));
                return;
            }
            // Prevent introducing directed cycles — the graph must remain a DAG
            // for correct topological evaluation order.
            // 防止引入有向环 — 图必须保持为有向无环图（DAG）以保证正确的拓扑求值顺序。
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
        //
        // Two-phase: the client sends a temporary (negative) ID; the server
        // allocates a permanent positive ID, ACKs the mapping back to the
        // originator, and broadcasts a real ADD_NODE to everyone else.
        // This prevents ID collisions when multiple editors add nodes concurrently.
        // 两阶段机制：客户端发送临时（负数）ID；服务端分配永久正数 ID，
        // 将映射通过 ACK 发回给发起者，并将真实的 ADD_NODE 广播给所有其他人。
        // 这防止了多个编辑者同时添加节点时的 ID 冲突。
        if (op.type() == OpType.ADD_NODE_REQUEST) {
            var node = OpExecutor.apply(targetGraph, op);
            long version = nextVersion(gk);
            // ACK carries the tempId→realId mapping so the originator can
            // replace its placeholder with the authoritative node ID.
            // ACK 携带 tempId→realId 映射，使发起者可以用权威节点 ID 替换其占位符。
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
                // Skip originator — they already got the ACK with the real ID.
                // 跳过发起者 — 他们已经通过 ACK 收到了真实 ID。
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
        // After sub-graph edits, rebuild parent graph's input cache so that
        // the server-side evaluator uses correct ENCAP pin→index mappings.
        // 子图编辑后重建父图的输入缓存，使服务端评估器使用正确的 ENCAP 引脚→索引映射。
        if (op.ownerNodeId() >= 0 && gbe.getNodeGraph() != null) {
            gbe.getNodeGraph().rebuildInputCache();
        }
        // Assign version AFTER applying — the op is already committed to graph
        // state, so the version reflects the definitive order.
        // 在应用之后分配版本号 — 操作已提交到图状态，版本号反映最终顺序。
        long version = nextVersion(gk);

        // 6. Broadcast to other editors / 广播给其他编辑者
        // Rebuild GraphOp with the server-assigned version. The client's version
        // field is ignored — the server is the single source of truth for ordering.
        // 使用服务端分配的版本号重建 GraphOp。客户端版本字段被忽略 — 服务端是排序的唯一权威来源。
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
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_LAYER_INDEX
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_PARAM
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_TEXT_COLOR
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_IMAGE_PIXELS
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_IMAGE_FRAME_TOGGLE
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_IMAGE_SIZE
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.ADD_NODE
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.REMOVE_NODE) {
            if (gbe instanceof MonitorBlockEntity mbe) {
                mbe.flagFullSync();
            }
        }
    }

    /**
     * Mark the block entity dirty so the chunk is saved to disk.
     * 标记方块实体为脏数据以便区块保存到磁盘。
     *
     * <p>This ensures graph state changes persist across server restarts.
     * Without this call, Minecraft would skip saving the chunk because it
     * sees no block-level state change.
     * 这确保图状态变更在服务端重启后持续存在。没有此调用，
     * Minecraft 会因为没有检测到方块级状态变更而跳过保存该区块。</p>
     *
     * @param gbe the graph block entity whose state changed / 状态发生变更的图方块实体
     */
    private static void markDirty(GraphBlockEntity gbe) {
        if (gbe instanceof net.minecraft.world.level.block.entity.BlockEntity be) be.setChanged();
    }

    // ── Cleanup / 清理 ──

    /**
     * Call on server stopping to clear all in-memory state.
     * 服务端停止时调用以清除所有内存状态。
     *
     * <p>All {@code static} maps are cleared. On the next tick a new server
     * instance will start with a clean registry. This is a hard reset — no
     * state is preserved across server restarts through this registry.
     * 清除所有 {@code static} 映射。下一个 tick 启动的新服务端实例
     * 将以干净的注册表开始。这是硬重置 — 通过此注册表无法在服务端重启间保留状态。</p>
     */
    public static void clearAll() {
        editors.clear();
        editVersions.clear();
        opLogs.clear();
    }
}
