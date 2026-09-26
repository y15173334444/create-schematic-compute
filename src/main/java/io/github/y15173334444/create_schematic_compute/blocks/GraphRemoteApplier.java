package io.github.y15173334444.create_schematic_compute.blocks;

/**
 * 远端编辑 op 应用器（自 {@link GraphEditor} 拆分，docs/gui-decomposition-plan.md 步骤 6c）：
 * 处理从服务器收到的协作编辑 op——展开/折叠 op 拆数据半边（n.expanded 恒应用，跨作用域也要落
 * 到子图副本）与 UI 半边（展开集合/编辑状态仅同作用域）、REJECT 回滚本地被拒连线并
 * 归还待 ACK 计数（整图同步守卫）、按 ownerNodeId 定位子图后交 {@code OpExecutor.apply}
 * （带移动动画）、远程 REMOVE_NODE 的 UI 清理、数据类 op 的编辑面板刷新（SET_PARAM 回显
 * 抑制、DEBUG_SIGNAL_GEN 模式切换重建编辑区）、BUS_OUT 频段/改名后的冲突重评估。
 * The remote edit-op applier (split out of {@link GraphEditor}, roadmap step 6c): handles
 * collaborative ops received from the server — expand/collapse ops split into a data half
 * (n.expanded always applies, even cross-scope) and a UI half (expanded set/edit states are
 * same-scope only), REJECT rolls
 * back the locally-applied refused connection and returns the pending-ACK count (full-sync
 * guard), locates the sub-graph by ownerNodeId and applies via {@code OpExecutor.apply} (with
 * move animation), UI cleanup for remote REMOVE_NODE, edit-panel refresh for data ops (SET_PARAM
 * echo suppression, DEBUG_SIGNAL_GEN mode-toggle edit-state rebuild), and bus-conflict
 * re-evaluation after BUS_OUT renames/band edits.
 *
 * <p><b>行为零变更</b>：方法体逐字搬迁，编辑器状态经传入的 {@code ed} 引用访问（同包；
 * {@code ff3} 为编辑器包级静态工具）。外部契约不变：{@code GraphEditor.onRemoteOp}
 * （GraphEditOpSyncPacket 调用）保留为公共委托。
 * <b>Behaviour-preserving</b>: the body moved verbatim; editor state is reached through the
 * passed-in {@code ed} reference (same package; {@code ff3} is the editor's package-level
 * static helper). External contracts unchanged: {@code GraphEditor.onRemoteOp} (called by
 * GraphEditOpSyncPacket) remains as a public delegate.</p>
 */
final class GraphRemoteApplier {

    private final GraphEditor ed;

    GraphRemoteApplier(GraphEditor ed) {
        this.ed = ed;
    }

    /**
     * 按参数下标反查字段位次。{@code fieldParamIndices[i]} = {@code fields[i]} 对应的参数下标
     * （-1 = 非参数框）。找不到返回 -1。
     * Reverse-lookup a field slot by param index. {@code fieldParamIndices[i]} is the param
     * index of {@code fields[i]} (-1 = non-param box). Returns -1 when absent.
     */
    static int fieldIndexOf(java.util.List<Integer> fieldParamIndices, int paramIndex) {
        return fieldParamIndices.indexOf(paramIndex);
    }

    /** Apply a remote edit op received from the server (multiplayer collaboration). */
    void applyRemote(io.github.y15173334444.create_schematic_compute.graph.GraphOp op) {
        // 先按 ownerNodeId 定位目标图（子图 / 主图），EXPAND/COLLAPSE 也要走同一张图——
        // 否则子图节点会落到主图撞号节点上（封装子图同步异常）。
        // Resolve the target graph first (sub-graph / top-level); EXPAND/COLLAPSE must
        // use it too, or sub-graph ops land on a colliding main-graph id.
        io.github.y15173334444.create_schematic_compute.graph.NodeGraph graph;
        if (op.ownerNodeId() >= 0) {
            var encap = ed.host.getGraph().findNode(op.ownerNodeId());
            if (encap == null) return; // 封装节点不存在 / encap node doesn't exist
            if (encap.subGraph == null) encap.subGraph = new io.github.y15173334444.create_schematic_compute.graph.NodeGraph();
            graph = encap.subGraph;
        } else {
            graph = ed.host.getGraph();
        }
        // UI 刷新只作用于当前正在编辑的同一作用域，避免子图/主图同 id 互相污染。
        // UI refresh only for the scope currently on screen — main/sub ids can collide.
        boolean sameScope = op.ownerNodeId() == ed.ownerNodeId();

        // Handle UI-state ops before graph-level apply
        if (op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.EXPAND_NODE) {
            var n = graph.findNode(op.targetNodeId());
            // 数据半边恒应用：n.expanded 是图数据（随 NBT 持久化，也是进入子图时 init 恢复的
            // 唯一依据），不属于作用域 UI —— 门在 sameScope 里，停在主图的玩家进子图时就会
            // 恢复出过期的展开状态（其他玩家看到的展开/折叠与实际不符）。
            // Data half always applies: n.expanded is graph data (NBT-persisted and the sole
            // input of the init-restore when entering the sub-graph) — gating it on sameScope
            // leaves players outside the sub-graph with a stale flag on entry.
            if (n != null) n.expanded = true;
            // UI 半边（展开集合 + 编辑状态）只作用于当前正在编辑的同一作用域。
            // UI half (expanded set + edit states) only for the scope currently on screen.
            if (sameScope && n != null && !ed.expandedNodeIds.contains(n.id)) {
                ed.expandedNodeIds.add(n.id);
                ed.nodeEditStatesById.put(n.id, NodeEditStateFactory.create(ed, n));
            }
            return;
        }
        if (op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.COLLAPSE_NODE) {
            var n = graph.findNode(op.targetNodeId());
            // 同 EXPAND：数据半边恒应用，UI 半边按作用域（见上注）。
            // Same as EXPAND: data half always applies, UI half is scope-gated (see above).
            if (n != null) n.expanded = false;
            if (sameScope && n != null) {
                ed.expandedNodeIds.remove(n.id);
                ed.nodeEditStatesById.remove(n.id);
            }
            return;
        }
        // REJECT: roll back the locally-applied change that the server refused
        if (op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.REJECT) {
            // The op carries the rejected ADD_CONN details — remove the local connection.
            // For non-originator editors this is a no-op (they never applied it).
            graph.removeConnection(op.fromId(), op.fromPin(), op.toId(), op.toPin());
            // A rejected op never receives an ACK — decrement the pending-op counter so the
            // bounce-back guard doesn't stay latched. / 被拒 op 不会收到 ACK —— 递减待 ACK 计数。
            if (ed.host.getBlockPos() != null
                && net.minecraft.client.Minecraft.getInstance().level != null
                && net.minecraft.client.Minecraft.getInstance().level.getBlockEntity(ed.host.getBlockPos()) instanceof GraphBlockEntity gbe) {
                gbe.setPendingLocalOps(Math.max(0, gbe.getPendingLocalOps() - 1));
            }
            return;
        }
        io.github.y15173334444.create_schematic_compute.graph.OpExecutor.apply(graph, op, /*animateMoves=*/true);
        // After a sub-graph edit, rebuild the parent graph's input cache so that
        // external connections on the ENCAPSULATION node follow the correct pin
        // positions (ENCAP_INPUT/OUTPUT ordering may have changed due to MOVE/ADD/REMOVE).
        // 子图编辑后重建父图的输入缓存，使封装节点上的外部连线跟随正确的引脚位置
        //（ENCAP_INPUT/OUTPUT 的顺序可能因 MOVE/ADD/REMOVE 而改变）。
        if (op.ownerNodeId() >= 0 && ed.host.getGraph() != null) {
            ed.host.getGraph().rebuildInputCache();
        }
        // Clean up UI state for remote REMOVE_NODE (local delete path does this manually) (M5)
        if (sameScope && op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.REMOVE_NODE) {
            int rid = op.targetNodeId();
            if (ed.selectedNode != null && ed.selectedNode.id == rid) ed.selectedNode = null;
            ed.selectedNodes.removeIf(n -> n.id == rid);
            ed.expandedNodeIds.remove(rid);
            ed.nodeEditStatesById.remove(rid);
            if (ed.draggingNode != null && ed.draggingNode.id == rid) ed.draggingNode = null;
            if (ed.encapsulationParent != null && ed.encapsulationParent.id == rid) ed.encapsulationParent = null;
            if (ed.resizingComment != null && ed.resizingComment.id == rid) ed.resizingComment = null;
            if (ed.wireFromNode == rid) { ed.wireFromNode = -1; ed.wireFromPin = 0; }
        }
        // Refresh edit panel UI for data changes
        if (sameScope && (op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_PARAM
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_FORMULA
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_COMMENT_TEXT
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_TEXT_COLOR
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_COMMENT_COLORS
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_COMMENT_SIZE
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_IMAGE_SIZE
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.TOGGLE_BOOL
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_CTRL_POINTS)) {
            var st = ed.nodeEditStatesById.get(op.targetNodeId());
            if (st != null && op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_PARAM
                && op.paramIndex() < st.fieldParamIndices.size()) {
                // fieldParamIndices[i] = fields[i] 对应的参数下标（-1 = 非参数框，如 busBox）。
                // 反查必须 indexOf；get(paramIndex) 会把位次当下标，fi==-1 时 fields.get(-1) 越界。
                // fieldParamIndices[i] is the param index of fields[i] (-1 = non-param, e.g. busBox).
                // Reverse lookup must be indexOf; get(paramIndex) treats a slot as a param id.
                int fi = fieldIndexOf(st.fieldParamIndices, op.paramIndex());
                if (fi >= 0 && fi < st.fields.size() && st.fields.get(fi) instanceof net.minecraft.client.gui.components.EditBox eb) {
                    ed.suppressEditBoxResponder = true;
                    eb.setValue(GraphEditor.ff3(op.paramValue()));
                    ed.suppressEditBoxResponder = false;
                }
            } else if (st != null && op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_PARAM) {
                // DEBUG_SIGNAL_GEN: setMode/outMode changes → recreate EditState to update visible fields
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.DEBUG_SIGNAL_GEN && (op.paramIndex() == 0 || op.paramIndex() == 1)) {
                    ed.suppressEditBoxResponder = true;
                    ed.nodeEditStatesById.put(n.id, NodeEditStateFactory.create(ed, n));
                    ed.suppressEditBoxResponder = false;
                }
            } else if (st == null || op.type() != io.github.y15173334444.create_schematic_compute.graph.OpType.SET_PARAM) {
                // Recreate entire EditState for non-param ops or if expanded.
                var n = graph.findNode(op.targetNodeId());
                if (n != null && ed.expandedNodeIds.contains(n.id))
                    ed.nodeEditStatesById.put(n.id, NodeEditStateFactory.create(ed, n));
            }
        }
        // When a remote player edits a BUS_OUT signalName (SET_DISPLAY_TEXT) or band list
        // (SET_BANDS), re-evaluate busConflict so all editors see the conflict warning in
        // real time — not just the player who made the edit.
        // 当远程玩家编辑 BUS_OUT 的 signalName（SET_DISPLAY_TEXT）或频段列表（SET_BANDS）时，
        // 重新评估 busConflict 使所有编辑者实时看到冲突警告 —— 而不仅是进行编辑的玩家。
        if (sameScope && (op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_BANDS)) {
            var affected = graph.findNode(op.targetNodeId());
            if (affected != null) {
                if (affected.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT) {
                    ed.bus.reevaluateBusConflicts(graph);
                }
                // 频段**不再**在此按频段注册表同步（issue #11）：BUS_IN 改名后的权威频段列表由
                // 服务端唯一解析，并作为一条权威 SET_BANDS 下发到本端 —— 本端只应用那个值。
                // Bands are no longer synced from the band registry here (issue #11): a renamed
                // BUS_IN's authoritative list is resolved once on the server and delivered to this
                // side as an authoritative SET_BANDS op; this side only applies that value.
                // Refresh edit state (conflict warning may change appearance for BUS_OUT)
                // 刷新编辑状态（BUS_OUT 冲突警告可能改变外观）
                if (ed.expandedNodeIds.contains(affected.id))
                    ed.nodeEditStatesById.put(affected.id, NodeEditStateFactory.create(ed, affected));
            }
        }
    }
}
