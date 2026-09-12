package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;

/**
 * 图 op 撤销/重做历史（自 {@link GraphEditor} 拆分，docs/gui-decomposition-plan.md 步骤 6c）：
 * per-player op 撤销栈 / 重做栈（{@code undoStack2} / {@code redoStack2}，上限 100）、
 * 批量撤销组（{@code beginUndoBatch} / {@code endUndoBatch} / {@code resetBatch}——信号发生器
 * 模式切换等多步操作记为一个原子单元）、反向 op 生成（{@code reverseOp}——按 OpType 逐个求逆）、
 * 撤销/重做执行（{@code opUndo} / {@code opRedo}——OpExecutor.apply + sendOp 同步协作者）、
 * 服务端 ID 重映射（{@code remapNodeId}——ADD_NODE_REQUEST 的 ACK 把临时 ID 重映射为真实 ID，
 * 同步更新栈内 op、选中集、展开集、编辑态与拖拽状态）。
 * The graph op undo/redo history (split out of {@link GraphEditor}, roadmap step 6c): the
 * per-player op undo/redo stacks (cap 100), batch undo groups (multi-step actions such as the
 * debug generator's mode switch record as one atomic unit), reverse-op generation per OpType,
 * undo/redo execution (OpExecutor.apply + sendOp to collaborators), and the server ID remap
 * (ADD_NODE_REQUEST ACK rewires temp IDs across the stacks, selection, expansion, edit states
 * and drag state).
 *
 * <p><b>行为零变更</b>：方法体逐字搬迁，编辑器状态经传入的 {@code ed} 引用访问（同包，
 * {@code encapsulationParent} / {@code resizingComment} 放宽到包级）。{@code UndoEntry} 的
 * op 引用可变（服务端 ID 重映射就地改写），旧值在记录时捕获。
 * <b>Behaviour-preserving</b>: bodies moved verbatim; editor state is reached through the
 * passed-in {@code ed} reference (same package; {@code encapsulationParent} /
 * {@code resizingComment} widened to package level). The {@code UndoEntry} op reference is
 * mutable (server ID remapping rewrites it in place); old values are captured at record time.</p>
 *
 * <p>外部契约不变：{@code GraphEditor} 保留 {@code recordOp} / {@code beginUndoBatch} /
 * {@code endUndoBatch}（编辑器内部约 14 处 + NodeEditStateFactory 约 17 处调用）与
 * {@code remapNodeId}（Host 的 handleAck 调用）作为一行委托。
 * External contracts unchanged: {@code GraphEditor} keeps one-line delegates for
 * {@code recordOp} / {@code beginUndoBatch} / {@code endUndoBatch} (~14 internal + ~17 factory
 * call sites) and {@code remapNodeId} (called by the Host's handleAck).</p>
 */
final class GraphOpHistory {

    private final GraphEditor ed;

    GraphOpHistory(GraphEditor ed) {
        this.ed = ed;
    }

    /**
     * <p>
     * Op references are mutable (for server-assigned ID remapping). Old values (x, y, val, str)
     * are captured at record time so the reverse op can restore prior state without re-reading the graph.
     * Op 引用是可变的（用于服务端分配的 ID 重映射）。旧值（x, y, val, str）在记录时捕获，
     * 使反向 op 无需重新读取图即可恢复先前状态。
     */
    private static final class UndoEntry {
        io.github.y15173334444.create_schematic_compute.graph.GraphOp op; // mutable for ID remapping
        final float oldX, oldY, oldVal;
        final String oldStr;
        final java.util.List<UndoEntry> batch; // null = single op; non-null = batch marker
        UndoEntry(io.github.y15173334444.create_schematic_compute.graph.GraphOp op,
                  float oldX, float oldY, float oldVal, String oldStr) {
            this.op = op; this.oldX = oldX; this.oldY = oldY; this.oldVal = oldVal; this.oldStr = oldStr;
            this.batch = null;
        }
        UndoEntry(java.util.List<UndoEntry> batch) { this.op = null; this.oldX = this.oldY = this.oldVal = 0; this.oldStr = null; this.batch = batch; }
        boolean isBatch() { return batch != null; }
    }
    private final java.util.ArrayDeque<UndoEntry> undoStack2 = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<UndoEntry> redoStack2 = new java.util.ArrayDeque<>();
    private static final int MAX_UNDO2 = 100;
    private int batchDepth = 0;
    private final java.util.List<UndoEntry> currentBatch = new java.util.ArrayList<>();

    /** Start a batch undo group. All recordOp calls between begin/end are
     *  treated as one atomic undo unit (one Ctrl+Z undoes the whole group).
     *  开始批量撤销组。begin/end 之间的所有 recordOp 调用被视为一个原子撤销单元。 */
    void beginUndoBatch() {
        if (batchDepth > 0) { batchDepth = 0; currentBatch.clear(); } // safety: discard stale batch
        batchDepth++;
    }
    /** End a batch undo group. / 结束批量撤销组。 */
    void endUndoBatch() {
        if (batchDepth <= 0) return;
        batchDepth--;
        if (batchDepth == 0 && !currentBatch.isEmpty()) {
            undoStack2.add(new UndoEntry(new java.util.ArrayList<>(currentBatch)));
            while (undoStack2.size() > MAX_UNDO2) undoStack2.removeFirst();
            currentBatch.clear();
            redoStack2.clear();
        }
    }
    /** Abandon any incomplete batch (called at start of new actions to prevent stack freeze).
     *  丢弃任何未完成的批量组（在新操作开始时调用，防止栈冻结）。 */
    void resetBatch() {
        if (batchDepth > 0) { batchDepth = 0; currentBatch.clear(); }
    }

    /** Record an emitted op for per-player undo. Call AFTER sendOp.
     *  If inside a batch, the op is deferred until endUndoBatch(). */
    void recordOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp op,
                          float oldX, float oldY, float oldVal, String oldStr) {
        var entry = new UndoEntry(op, oldX, oldY, oldVal, oldStr);
        if (batchDepth > 0) {
            currentBatch.add(entry);
        } else {
            undoStack2.add(entry);
            while (undoStack2.size() > MAX_UNDO2) undoStack2.removeFirst();
            redoStack2.clear();
        }
    }

    /** Generate the reverse op for an undo entry, or null if not reversible. */
    private io.github.y15173334444.create_schematic_compute.graph.GraphOp reverseOp(UndoEntry e) {
        var op = e.op;
        var bp = op.graphPos();
        int oid = op.ownerNodeId();
        var uid = op.actor();
        return switch (op.type()) {
            case ADD_NODE -> new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                io.github.y15173334444.create_schematic_compute.graph.OpType.REMOVE_NODE, bp, oid, op.targetNodeId(), uid);
            case ADD_NODE_REQUEST -> {
                // Use targetNodeId if ACK has remapped it; fall back to oldVal (local node id)
                // 如果 ACK 已重映射则用 targetNodeId；否则用 oldVal（本地节点 ID）
                int nid = op.targetNodeId() > 0 ? op.targetNodeId() : (int)e.oldVal;
                yield new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                    io.github.y15173334444.create_schematic_compute.graph.OpType.REMOVE_NODE, bp, oid, nid, uid);
            }
            case REMOVE_NODE -> new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                io.github.y15173334444.create_schematic_compute.graph.OpType.ADD_NODE, bp, oid, op.targetNodeId(),
                op.tempId(), io.github.y15173334444.create_schematic_compute.graph.NodeType.values()[(int)e.oldVal],
                e.oldX, e.oldY, 0, 0, 0, 0, 0, 0f,
                e.oldStr, 0, 0, 0, 0, null, 0, 0, 0,
                net.minecraft.world.item.ItemStack.EMPTY, 0L, uid);
            case MOVE_NODE -> io.github.y15173334444.create_schematic_compute.graph.GraphOp.moveNode(bp, oid, op.targetNodeId(), e.oldX, e.oldY, uid);
            case ADD_CONN -> io.github.y15173334444.create_schematic_compute.graph.GraphOp.removeConn(bp, oid, op.fromId(), op.fromPin(), op.toId(), op.toPin(), uid);
            case REMOVE_CONN -> io.github.y15173334444.create_schematic_compute.graph.GraphOp.addConn(bp, oid,
                (int)e.oldX, (int)e.oldY, (int)e.oldVal, op.toPin(), uid);
            case SET_PARAM -> io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(bp, oid, op.targetNodeId(), op.paramIndex(), e.oldVal, uid);
            case SET_FORMULA -> io.github.y15173334444.create_schematic_compute.graph.GraphOp.setFormula(bp, oid, op.targetNodeId(), e.oldStr, uid);
            case SET_DISPLAY_TEXT -> new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT, bp, oid, op.targetNodeId(),
                0, null, 0f, 0f, 0, 0, 0, 0, 0, 0f,
                e.oldStr, 0, 0, 0, 0, null, 0, 0, 0,
                net.minecraft.world.item.ItemStack.EMPTY, 0L, uid);
            case SET_COMMENT_SIZE -> io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCommentSize(
                bp, oid, op.targetNodeId(), e.oldX, e.oldY, uid);
            case SET_COMMENT_TEXT -> new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                io.github.y15173334444.create_schematic_compute.graph.OpType.SET_COMMENT_TEXT, bp, oid, op.targetNodeId(), 0, null, 0f, 0f,
                0, 0, 0, 0, 0, 0f, e.oldStr, 0, 0, 0, 0, null, 0, 0, 0,
                net.minecraft.world.item.ItemStack.EMPTY, 0L, uid);
            case SET_COMMENT_COLORS -> new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                io.github.y15173334444.create_schematic_compute.graph.OpType.SET_COMMENT_COLORS, bp, oid, op.targetNodeId(),
                0, null, 0f, 0f, 0, 0, 0, 0, 0, 0f,
                null, (int)e.oldX, (int)e.oldY, (int)e.oldVal, 0, null, 0, 0, 0,
                net.minecraft.world.item.ItemStack.EMPTY, 0L, uid);
            case SET_HOTBAR_ITEM -> io.github.y15173334444.create_schematic_compute.graph.GraphOp.setHotbarItem(
                bp, oid, op.targetNodeId(), op.hotbarSlot(),
                restoreItemFromNbt(e.oldStr), uid);
            case SET_IMAGE_FRAME_TOGGLE -> new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                io.github.y15173334444.create_schematic_compute.graph.OpType.SET_IMAGE_FRAME_TOGGLE, bp, oid, op.targetNodeId(),
                0, null, 0f, 0f, 0, 0, 0, 0, 0, 0f,
                null, 0, 0, 0, 0, null, 0, op.imageFrameIndex(), 0,
                net.minecraft.world.item.ItemStack.EMPTY, 0L, uid);
            case SET_IMAGE_SIZE -> io.github.y15173334444.create_schematic_compute.graph.GraphOp.setImageSize(
                bp, oid, op.targetNodeId(), (int)e.oldX, (int)e.oldY, uid);
            case SET_KEY_BINDING -> new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                io.github.y15173334444.create_schematic_compute.graph.OpType.SET_KEY_BINDING, bp, oid, op.targetNodeId(),
                0, null, 0f, 0f, 0, 0, 0, 0, 0, 0f,
                null, 0, 0, 0, 0, null, (int)e.oldVal, 0, 0,
                net.minecraft.world.item.ItemStack.EMPTY, 0L, uid);
            case SET_TEXT_COLOR -> io.github.y15173334444.create_schematic_compute.graph.GraphOp.setTextColor(
                bp, oid, op.targetNodeId(), (int)e.oldVal, uid);
            case SET_CTRL_POINTS -> {
                float[][] parsed = io.github.y15173334444.create_schematic_compute.graph.GraphOp.parseCtrlPoints(e.oldStr);
                yield parsed != null
                    ? io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCtrlPoints(
                        bp, oid, op.targetNodeId(), parsed[0], parsed[1], uid)
                    : null;
            }
            case TOGGLE_BOOL -> new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                io.github.y15173334444.create_schematic_compute.graph.OpType.TOGGLE_BOOL, bp, oid, op.targetNodeId(), uid);
            default -> null;
        };
    }

    /** Parse an ItemStack from its NBT string representation (saved via saveOptional).
     *  从 NBT 字符串表示中解析 ItemStack（通过 saveOptional 保存的）。
     *  @param nbtStr NBT 字符串 / NBT string
     *  @return 解析出的 ItemStack，失败时返回 EMPTY / parsed ItemStack, or EMPTY on failure */
    private static net.minecraft.world.item.ItemStack restoreItemFromNbt(String nbtStr) {
        if (nbtStr == null || nbtStr.isEmpty()) return net.minecraft.world.item.ItemStack.EMPTY;
        try {
            var tag = net.minecraft.nbt.TagParser.parseTag(nbtStr);
            if (tag instanceof net.minecraft.nbt.CompoundTag ct)
                return net.minecraft.world.item.ItemStack.parseOptional(
                    net.minecraft.client.Minecraft.getInstance().level.registryAccess(), ct);
        } catch (Exception e) {
            io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.debug("restoreItemFromNbt failed", e);
        }
        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    /** Undo last entry (single op or batch). One Ctrl+Z = one call.
     *  撤销最后一个条目（单条 op 或批量组）。一次 Ctrl+Z = 一次调用。 */
    void opUndo() {
        var entry = undoStack2.pollLast();
        if (entry == null) return;
        if (entry.isBatch()) {
            // Undo batch in reverse order (newest op first so positions cascade correctly)
            // 逆序撤销批量组中的 op（最新 op 先撤销，使位置级联正确）
            var batch = entry.batch;
            var redone = new java.util.ArrayList<UndoEntry>();
            for (int i = batch.size() - 1; i >= 0; i--) {
                var e = batch.get(i);
                var rev = reverseOp(e);
                if (rev != null) {
                    redone.add(e);
                    io.github.y15173334444.create_schematic_compute.graph.OpExecutor.apply(ed.getGraph(), rev);
                    ed.host.sendOp(rev);
                }
            }
            // Push entire batch as one redo entry
            java.util.Collections.reverse(redone);
            redoStack2.add(new UndoEntry(redone));
        } else {
            var rev = reverseOp(entry);
            if (rev != null) {
                redoStack2.add(entry);
                io.github.y15173334444.create_schematic_compute.graph.OpExecutor.apply(ed.getGraph(), rev);
                ed.host.sendOp(rev);
            }
        }
    }

    /** Redo last undone entry (single op or batch).
     *  重做上一个被撤销的条目（单条 op 或批量组）。
     *  Re-applies the most recent entry from the redo stack to the graph, and syncs via sendOp. */
    void opRedo() {
        var entry = redoStack2.pollLast();
        if (entry == null) return;
        if (entry.isBatch()) {
            var batch = entry.batch;
            var redone = new java.util.ArrayList<UndoEntry>();
            for (var e : batch) {
                redone.add(e);
                io.github.y15173334444.create_schematic_compute.graph.OpExecutor.apply(ed.getGraph(), e.op);
                ed.host.sendOp(e.op);
            }
            undoStack2.add(new UndoEntry(redone));
        } else {
            undoStack2.add(entry);
            io.github.y15173334444.create_schematic_compute.graph.OpExecutor.apply(ed.getGraph(), entry.op);
            ed.host.sendOp(entry.op);
        }
    }

    /** Remap a client-assigned temp node ID to the server-assigned real ID
     *  (ACK for ADD_NODE_REQUEST). Updates every reference: nodes, connections,
     *  undo/redo stacks, and UI selections.
     *  将客户端分配的临时节点 ID 重映射为服务端分配的真实 ID（ADD_NODE_REQUEST 的 ACK）。
     *  更新所有引用：节点、连线、撤销/重做栈和 UI 选择。
     *  @param ack 服务端发送的 ACK 包，包含 tempId 和 assignedId / server ACK packet with tempId and assignedId */
    void remapNodeId(io.github.y15173334444.create_schematic_compute.network.GraphEditAckPacket ack) {
        int tid = ack.tempId(), rid = ack.assignedId();
        if (tid == rid) return;
        var graph = ed.getGraph();
        var node = graph.findNode(tid);
        if (node == null) return; // already gone or already remapped
        // Update the node itself
        graph.nodeMap().remove(tid);
        node.id = rid;
        graph.nodeMap().put(rid, node);
        // 防止客户端 nextNodeId 漂移：服务端分配的 rid 可能比本地计数器大
        // Prevent client nextNodeId drift: server-assigned rid may be larger than local counter
        graph.nextNodeId = Math.max(graph.nextNodeId, rid + 1);
        // Rewire connections referencing the tempId
        for (var c : graph.connections) {
            if (c.fromId == tid) c.fromId = rid;
            if (c.toId == tid) c.toId = rid;
        }
        // Update undo/redo stacks (ops targeting or referencing this temp node ID)
        // ADD_NODE_REQUEST entries store the temp ID in op.tempId(), not op.targetNodeId().
        // 更新 undo/redo 栈（目标或引用此临时节点 ID 的操作）。
        // ADD_NODE_REQUEST 条目将临时 ID 存储在 op.tempId() 中，而非 op.targetNodeId()。
        for (var entry : undoStack2) {
            if (entry.isBatch()) {
                for (var be : entry.batch) {
                    remapEntryOp(be, tid, rid);
                }
            } else {
                remapEntryOp(entry, tid, rid);
            }
        }
        for (var entry : redoStack2) {
            if (entry.isBatch()) {
                for (var be : entry.batch) {
                    remapEntryOp(be, tid, rid);
                }
            } else {
                remapEntryOp(entry, tid, rid);
            }
        }
        // UI selections
        if (ed.selectedNode != null && ed.selectedNode.id == tid) ed.selectedNode = node;
        ed.selectedNodes.removeIf(n -> n.id == tid);
        ed.selectedNodes.add(node); // add with remapped node identity
        var expand = ed.expandedNodeIds.remove(tid);
        if (expand) ed.expandedNodeIds.add(rid);
        var state = ed.nodeEditStatesById.remove(tid);
        if (state != null) ed.nodeEditStatesById.put(rid, state);
        if (ed.draggingNode != null && ed.draggingNode.id == tid) ed.draggingNode = node;
        if (ed.wireFromNode == tid) ed.wireFromNode = rid;
        if (ed.encapsulationParent != null && ed.encapsulationParent.id == tid) ed.encapsulationParent = node;
        if (ed.resizingComment != null && ed.resizingComment.id == tid) ed.resizingComment = node;
        graph.rebuildInputCache();
        graph.bumpGeneration();
    }

    /** Return a copy of {@code op} with {@code targetNodeId} replaced.
     *  返回 op 的副本，将其 targetNodeId 替换为指定值。
     *  @param op 原始操作 / original operation
     *  @param newId 新的目标节点 ID / new target node ID
     *  @return 修改了 targetNodeId 的操作副本 / copy of op with targetNodeId replaced */
    private static io.github.y15173334444.create_schematic_compute.graph.GraphOp withTargetId(
        io.github.y15173334444.create_schematic_compute.graph.GraphOp op, int newId) {
        return new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
            op.type(), op.graphPos(), op.ownerNodeId(), newId,
            op.tempId(), op.nodeType(), op.x(), op.y(),
            op.fromId(), op.fromPin(), op.toId(), op.toPin(),
            op.paramIndex(), op.paramValue(), op.stringValue(),
            op.colorBg(), op.colorBorder(), op.colorText(),
            op.sortB(), op.bands(), op.keyIndex(), op.imageFrameIndex(),
            op.hotbarSlot(), op.itemStack(), op.editVersion(), op.actor());
    }

    /** Return a copy of {@code op} with {@code fromId}/{@code toId} replaced.
     *  返回 op 的副本，将其 fromId/toId 替换为指定值。
     *  @param op 原始操作 / original operation
     *  @param newFromId 新的来源节点 ID / new source node ID
     *  @param newToId 新的目标节点 ID / new target node ID
     *  @return 修改了 fromId/toId 的操作副本 / copy of op with fromId/toId replaced */
    private static io.github.y15173334444.create_schematic_compute.graph.GraphOp withFromToId(
        io.github.y15173334444.create_schematic_compute.graph.GraphOp op, int newFromId, int newToId) {
        return new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
            op.type(), op.graphPos(), op.ownerNodeId(), op.targetNodeId(),
            op.tempId(), op.nodeType(), op.x(), op.y(),
            newFromId, op.fromPin(), newToId, op.toPin(),
            op.paramIndex(), op.paramValue(), op.stringValue(),
            op.colorBg(), op.colorBorder(), op.colorText(),
            op.sortB(), op.bands(), op.keyIndex(), op.imageFrameIndex(),
            op.hotbarSlot(), op.itemStack(), op.editVersion(), op.actor());
    }

    /** Remap one UndoEntry's op when the server assigns real ID for temp ID.
     *  当服务器为临时 ID 分配真实 ID 时，重映射单个 UndoEntry 的操作。 */
    private static void remapEntryOp(UndoEntry be, int tid, int rid) {
        var op = be.op;
        // ADD_NODE_REQUEST: temp ID is in tempId(), targetNodeId is 0 placeholder
        if (op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.ADD_NODE_REQUEST
            && op.tempId() == tid) {
            be.op = withTargetId(op, rid);
            return;
        }
        if (op.targetNodeId() == tid) be.op = withTargetId(op, rid);
        if (op.fromId() == tid) be.op = withFromToId(op, rid, op.toId());
        if (op.toId() == tid) be.op = withFromToId(op, op.fromId(), rid);
    }
}
