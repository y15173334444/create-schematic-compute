package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;

/**
 * 总线编辑器（自 {@link GraphEditor} 拆分，docs/gui-decomposition-plan.md 步骤 6d）：
 * 总线频道名提交（{@code commitBusBox}——清旧频道全局数据、按 BUS_IN/BUS_OUT 分别处理频段、
 * 重建编辑区）、旧频道释放（{@code releaseOldBusName}——清全局数据与旧 band 连线，不折叠编辑区）、
 * 节点清空（{@code clearBusNode}）、BUS_OUT 冲突合并（{@code reevaluateBusConflicts}——只合并
 * **本地可证明**的同图重名，跨方块归属一律以服务端同步来的值为准，见 issue #12）、频段列表同步
 * （{@code syncBusBands}——同频道节点对齐 + 上传 BAND_REGISTRY + 清理被删频段连线）。
 * The bus editor (split out of {@link GraphEditor}, roadmap step 6d): commits the bus channel
 * name (clears the old channel's global data, handles bands per BUS_IN/BUS_OUT, rebuilds the
 * edit state), releases an old channel (global data + old-band connections, no panel collapse),
 * clears a bus node, merges BUS_OUT conflicts (only the locally provable same-graph duplicate;
 * cross-block ownership is always taken from the server-synced value — issue #12), and syncs
 * band lists across same-channel nodes (BAND_REGISTRY upload + removed-band connection cleanup).
 *
 * <p><b>行为零变更</b>：方法体逐字搬迁，编辑器状态经传入的 {@code ed} 引用访问（同包）。
 * 防抖编排（{@code tickDebouncedBusEdits}）留在编辑器的 clientTick，仅引用本类的
 * {@link #BUS_EDIT_DEBOUNCE_TICKS}。
 * <b>Behaviour-preserving</b>: bodies moved verbatim; editor state is reached through the
 * passed-in {@code ed} reference (same package). The debounce orchestration
 * ({@code tickDebouncedBusEdits}) stays in the editor's clientTick and only references this
 * class's {@link #BUS_EDIT_DEBOUNCE_TICKS}.</p>
 *
 * <p>{@code clearBusNode} 当前无调用点（为节点删除/清空路径保留）。
 * {@code clearBusNode} currently has no callers (kept for the node deletion/clear paths).</p>
 */
final class GraphBusEditor {

    private final GraphEditor ed;

    GraphBusEditor(GraphEditor ed) {
        this.ed = ed;
    }

    /** 总线名/频段名输入框停止输入后、自动同步给协作者之前等待的 tick 数（约 0.5s）。
     *  Ticks to wait after typing stops before auto-syncing a bus/band name to
     *  collaborators (~0.5 s). */
    static final int BUS_EDIT_DEBOUNCE_TICKS = 10;

    /** 提交 busBox 的值到 node.signalName (Commit busBox value to node.signalName) */
    void commitBusBox(GraphEditor.EditState st) {
        if (st == null || st.busBox == null || st.busNode == null) return;
        var node = st.busNode;
        String oldName = node.signalName;
        String t = st.busBox.getValue();
        if (t.equals(oldName)) return;
        node.signalName = t;
        ed.host.sendOp(new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
            io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT,
            ed.host.getBlockPos(), ed.ownerNodeId(), node.id, 0, null, 0f, 0f,
            0, 0, 0, 0, 0, 0f, t, 0, 0, 0, 0, null, 0, 0, 0,
            net.minecraft.world.item.ItemStack.EMPTY, 0L, ed.host.getPlayerUUID()));
        // 改名：保留自身 band 与连线，只改频道名（回归审计：用户期望改名不丢图）。
        // 仅清理旧频道的全局数据（SIGNALS/BAND_REGISTRY 残留），不动节点的 signalBands/连线。
        // Rename: keep the node's own bands and connections — only change the channel
        // name. Only clear the old channel's GLOBAL data (SIGNALS/BAND_REGISTRY residue),
        // never the node's signalBands or its band connections.
        if (!oldName.isEmpty()) {
            boolean othersUseOldName = false;
            for (var n : ed.getGraph().nodes) {
                if (n != node && (n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_IN || n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT)
                    && n.signalName.equals(oldName)) {
                    othersUseOldName = true; break;
                }
            }
            if (!othersUseOldName) {
                // 只清全局旧频道数据；不调 releaseOldBusName（它会清空
                // signalBands 并删除旧 band 连线——"携带的图丢失"根因）。
                // Clear only the global old-channel data; do NOT call
                // releaseOldBusName (it wipes signalBands and deletes old-band connections —
                // the "carried graph lost" root cause).
                io.github.y15173334444.create_schematic_compute.network.SignalBus.clearBus(oldName);
            }
        }
        // Re-evaluate all BUS_OUT conflict state (renaming may create or resolve conflicts).
        // 重新评估所有 BUS_OUT 冲突状态（改名可能产生或解决冲突）。
        reevaluateBusConflicts(ed.getGraph());
        // 改名频段处理：客户端**不再自行解析**频段列表（issue #11）。
        // - BUS_OUT：保留自身 band + 连线（用户期望改名不丢图）
        // - BUS_IN：新频段列表由服务端**唯一解析**，并以权威 SET_BANDS 下发给全部编辑者，
        //   各端只应用同一个值 —— 避免各端查各自的频段注册表得出不同结果。
        // Rename band handling: the client no longer resolves the band list itself (issue #11).
        // - BUS_OUT: keep its own bands + connections (rename must not lose the graph).
        // - BUS_IN: the new list is resolved *once on the server* and pushed to every editor
        //   as an authoritative SET_BANDS, so all sides apply the same value instead of each
        //   resolving against its own band registry.
        // 重建编辑区（在最后调用，确保所有状态已更新） (Rebuild edit state last, ensuring all state is up to date)
        ed.nodeEditStatesById.put(node.id, NodeEditStateFactory.create(ed, node));
    }

    /** 仅释放旧频道名的全局数据与连线，不折叠编辑区。
     *  供 commitBusBox 改名时使用——改名不应关闭正在编辑的节点。
     *  (Release old channel global data and connections without collapsing the edit panel.
     *   Used by commitBusBox when renaming — renaming should not close the node being edited.) */
    void releaseOldBusName(GraphNode n, String oldName) {
        if (oldName == null || oldName.isEmpty()) return;
        io.github.y15173334444.create_schematic_compute.network.SignalBus.clearBus(oldName);
        // 在清空前捕获旧频段名和数量，用于连线清理 (Capture old band names and count before clearing)
        java.util.List<String> oldBands = n.signalBands != null
            ? new java.util.ArrayList<>(n.signalBands) : java.util.List.of();
        int oldCount = oldBands.size();
        n.signalBands.clear();
        n.bandsDirty = true;
        var g = ed.getGraph();
        // 按 pinId（频段名）清理连线，并带 legacy 索引回退（与 bandRemoveBtn 路径一致）
        // Remove connections by pinId (band name), with legacy index fallback
        for (int pi = 0; pi < oldCount; pi++) {
            final int p = pi;
            String band = pi < oldBands.size() ? oldBands.get(pi) : null;
            g.connections.removeIf(c ->
                (c.fromId == n.id && band != null && band.equals(c.fromPinId))
                || (c.toId == n.id && band != null && band.equals(c.toPinId)));
            // Legacy fallback: also remove by index for unmigrated connections
            g.connections.removeIf(c ->
                (c.fromId == n.id && c.fromPin == p && c.fromPinId == null)
                || (c.toId == n.id && c.toPin == p && c.fromPinId == null));
        }
    }

    /** 清除 BUS 节点的频段、连线，并折叠编辑区 (Clear BUS node bands and connections, then collapse edit panel).
     *  保留给节点删除/清空路径使用。改名路径请用 {@link #releaseOldBusName}。
     *  (Preserved for node deletion/clear paths. Use releaseOldBusName for rename paths.) */
    void clearBusNode(GraphNode n) {
        releaseOldBusName(n, n.signalName);
        ed.expandedNodeIds.remove(n.id);
        ed.nodeEditStatesById.remove(n.id);
        n.expanded = false;
    }

    /** 合并 BUS_OUT 冲突标志（issue #12）。
     *  <p>只合并**本地可证明**的部分（同图同名 BUS_OUT），跨方块归属一律以随图同步来的服务端
     *  值为准 —— 客户端无法区分「服务端广播回来的自身回声」与「对端已占用频道」，此前用全局
     *  频段表去猜正是假冲突的来源。标志在这里**只增不减**，绝不下调权威值。</p>
     *  <p>调用点：本地改名提交（{@link #commitBusBox}）与远端 op 应用（GraphRemoteApplier）。</p>
     *  Merge BUS_OUT conflict flags (issue #12). Only the locally provable part is merged (a
     *  same-name duplicate in this graph); cross-block ownership is always taken from the
     *  server-synced value — a client cannot tell its own echo from a peer's claim, and guessing
     *  from the global band registry is what used to produce false conflicts. The flag is only
     *  ever raised here, never lowered.</p>
     *  <p>Callers: local rename commit ({@link #commitBusBox}) and remote op application
     *  (GraphRemoteApplier).</p> */
    void reevaluateBusConflicts(io.github.y15173334444.create_schematic_compute.graph.NodeGraph graph) {
        io.github.y15173334444.create_schematic_compute.network.BusChannelHelper.mergeLocalBusConflicts(graph);
    }

    /** 同步所有同总线名的 BUS 节点的频段列表 (Sync band lists of all BUS nodes sharing the same bus name) */
    void syncBusBands(GraphNode src) {
        if (src.signalName.isEmpty()) return;
        // 冲突的 BUS_OUT 不上传频段（防止频道夺取） (Conflicting BUS_OUT does not upload bands, preventing channel takeover)
        if (src.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT && src.busConflict) return;
        var bands = src.signalBands;
        if (src.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT) {
            io.github.y15173334444.create_schematic_compute.network.SignalBus.registerBands(src.signalName, bands);
        }
        var g = ed.getGraph();
        for (var n : ed.getGraph().nodes) {
            if (n != src && (n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_IN || n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT)
                && n.signalName.equals(src.signalName)) {
                // 冲突的 BUS_OUT **目标不参与对齐**：它并不拥有这个频道（issue #14 定下的规则），
                // 把 owner 的列表复制进去会**毁掉它自己的频段图** —— 而原 owner 一旦被删除，
                // 它接替成为 owner 时就会带着**别人的图**上线。
                // 实测复现：两个同名 BUS_OUT，删掉第一个（owner）后，接替者的图变成第一个的。
                // 其它同名对齐路径（syncBandsFromServer / BusBandUploadPacket / convergeBusInBands）
                // 都已有这条守卫，此处是遗漏。
                // A conflicted BUS_OUT **target is never aligned**: it does not own the channel
                // (the rule settled in issue #14), and copying the owner's list into it destroys
                // its own band graph — so once the original owner is deleted and this node takes
                // over, it comes online carrying someone else's graph. Every other same-channel
                // alignment path (syncBandsFromServer / BusBandUploadPacket / convergeBusInBands)
                // already has this guard; this one was missing it.
                if (n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT && n.busConflict) continue;
                // Collect removed band names (pinIds) before replacing the list
                // 在替换列表前收集被删除的频段名（pinId）
                var oldBands = n.signalBands != null ? n.signalBands : java.util.Collections.<String>emptyList();
                java.util.List<String> newBands = bands != null ? new java.util.ArrayList<>(bands) : new java.util.ArrayList<>();
                var removed = new java.util.ArrayList<>(oldBands);
                removed.removeAll(newBands);
                n.signalBands = newBands;
                n.bandsDirty = true;
                // Only remove connections on bands that were actually deleted,
                // matched by band name (= pinId). Preserves connections on
                // bands that were merely reordered.
                // 仅删除实际被移除频段上的连接（按频段名 = pinId 匹配）。
                // 仅被重排的频段上的连接得以保留。
                for (String removedBand : removed) {
                    g.connections.removeIf(c ->
                        (c.fromId == n.id && removedBand.equals(c.fromPinId)) ||
                        (c.toId == n.id && removedBand.equals(c.toPinId)));
                }
                g.rebuildNodeMap(); // invalidate inputCache / 刷新 inputCache
                g.rebuildInputCache();
                var st = ed.nodeEditStatesById.get(n.id);
                if (st != null) ed.nodeEditStatesById.put(n.id, NodeEditStateFactory.create(ed, n));
            }
        }
    }
}
