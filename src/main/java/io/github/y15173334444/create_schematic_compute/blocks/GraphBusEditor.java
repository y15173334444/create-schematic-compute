package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;

/**
 * 总线编辑器（自 {@link GraphEditor} 拆分，docs/gui-decomposition-plan.md 步骤 6d）：
 * 总线频道名提交（{@code commitBusBox}——清旧频道全局数据、按 BUS_IN/BUS_OUT 分别处理频段、
 * 重建编辑区）、旧频道释放（{@code releaseOldBusName}——清全局数据与旧 band 连线，不折叠编辑区）、
 * 节点清空（{@code clearBusNode}）、BUS_OUT 冲突重评估（{@code reevaluateBusConflicts}——本地
 * 同名冲突 + 跨方块冲突，{@code localBusNames} 区分自身回声）、频段列表同步
 * （{@code syncBusBands}——同频道节点对齐 + 上传 BAND_REGISTRY + 清理被删频段连线）。
 * The bus editor (split out of {@link GraphEditor}, roadmap step 6d): commits the bus channel
 * name (clears the old channel's global data, handles bands per BUS_IN/BUS_OUT, rebuilds the
 * edit state), releases an old channel (global data + old-band connections, no panel collapse),
 * clears a bus node, re-evaluates BUS_OUT conflicts (local same-name + cross-block, with
 * {@code localBusNames} distinguishing our own echoes), and syncs band lists across same-channel
 * nodes (BAND_REGISTRY upload + removed-band connection cleanup).
 *
 * <p><b>行为零变更</b>：方法体逐字搬迁，编辑器状态经传入的 {@code ed} 引用访问（同包）。
 * 防抖编排（{@code tickDebouncedBusEdits}）留在编辑器的 clientTick，仅引用本类的
 * {@link #BUS_EDIT_DEBOUNCE_TICKS}。
 * <b>Behaviour-preserving</b>: bodies moved verbatim; editor state is reached through the
 * passed-in {@code ed} reference (same package). The debounce orchestration
 * ({@code tickDebouncedBusEdits}) stays in the editor's clientTick and only references this
 * class's {@link #BUS_EDIT_DEBOUNCE_TICKS}.</p>
 *
 * <p>外部契约不变：{@code GraphEditor.reevaluateBusConflictsForBus}（BusBandSyncPacket 处理器
 * 调用）保留为公共委托。{@code clearBusNode} 当前无调用点（为节点删除/清空路径保留）。
 * External contracts unchanged: {@code GraphEditor.reevaluateBusConflictsForBus} (called by the
 * BusBandSyncPacket handler) remains as a public delegate. {@code clearBusNode} currently has no
 * callers (kept for the node deletion/clear paths).</p>
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

    /** 本方块通过 syncBusBands 实际注册过的频道名（用于区分自身和跨方块冲突） (Bus names actually registered by this BE via syncBusBands; distinguishes self from cross-BE conflicts) */
    private final java.util.Set<String> localBusNames = new java.util.HashSet<>();

    /** 节点删除路径：忘记本方块曾注册过的频道名（配合 SignalBus.clearBus 调用方）。
     *  Node-deletion path: forget a channel name this block had registered (caller pairs it with SignalBus.clearBus). */
    void removeLocalBusName(String name) { localBusNames.remove(name); }

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
                // 只清全局旧频道数据 + 同步 localBusNames；不调 releaseOldBusName（它会清空
                // signalBands 并删除旧 band 连线——"携带的图丢失"根因）。
                // Clear only the global old-channel data + localBusNames; do NOT call
                // releaseOldBusName (it wipes signalBands and deletes old-band connections —
                // the "carried graph lost" root cause).
                io.github.y15173334444.create_schematic_compute.network.SignalBus.clearBus(oldName);
                localBusNames.remove(oldName);
            }
        }
        // Re-evaluate all BUS_OUT conflict state (renaming may create or resolve conflicts).
        // 重新评估所有 BUS_OUT 冲突状态（改名可能产生或解决冲突）。
        reevaluateBusConflicts(ed.getGraph());
        // 改名 band 处理：
        // - BUS_OUT：保留自身 band + 连线（用户期望改名不丢图）
        // - BUS_IN：采用新频道的 band 定义（从同频道节点或 BAND_REGISTRY 复制）。
        //   BUS_IN 是读取方，其 band 列表必须匹配频道定义才能读到值；若保留旧 band，
        //   改名后 key 与频道不匹配 → 读 0（回归审计：BUS_IN 改名不替换图）。
        // Rename band handling:
        // - BUS_OUT: keep its own bands + connections (user wants rename not to lose the graph)
        // - BUS_IN: adopt the new channel's band definition (copy from a same-channel node or
        //   BAND_REGISTRY). BUS_IN is a reader; its band list must match the channel definition
        //   to read values; keeping the old bands would mismatch the channel keys -> reads 0.
        if (node.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_IN && !t.isEmpty()) {
            boolean synced = false;
            for (var n : ed.getGraph().nodes) {
                if (n != node && n.signalName.equals(t) && n.bandCount() > 0) {
                    node.signalBands = new java.util.ArrayList<>(n.signalBands);
                    node.bandsDirty = true; synced = true; break;
                }
            }
            if (!synced) {
                var gb = io.github.y15173334444.create_schematic_compute.network.SignalBus.getBands(t);
                node.signalBands = (gb != null && !gb.isEmpty())
                    ? new java.util.ArrayList<>(gb)
                    : new java.util.ArrayList<>();
                node.bandsDirty = true;
            }
        }
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
        // 保持 localBusNames 与 BAND_REGISTRY 同步，防止改名后残留旧名
        // 掩盖后续同名频道上的真实跨 block 冲突（回归审计补充）。
        // Keep localBusNames in sync with BAND_REGISTRY so a stale entry cannot
        // mask a genuine later cross-block conflict on the reused name.
        localBusNames.remove(oldName);
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

    /** Re-evaluate busConflict for all BUS_OUT nodes in the given graph.
     *  <p>重新评估给定图中所有 BUS_OUT 节点的 busConflict 状态。</p>
     *  <p>Called both from local edits ({@link #commitBusBox}) and from remote op handling
     *  (the editor's onRemoteOp) so that all players see conflict warnings in real time.
     *  同时从本地编辑（commitBusBox）和远程操作处理（编辑器的 onRemoteOp）中调用，
     *  使所有玩家都能实时看到冲突警告。</p> */
    void reevaluateBusConflicts(io.github.y15173334444.create_schematic_compute.graph.NodeGraph graph) {
        for (var n : graph.nodes) {
            if (n.type != io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT || n.signalName.isEmpty()) {
                n.busConflict = false;
                continue;
            }
            // Check for local conflict (another BUS_OUT in the same graph with the same signalName)
            // 检查本地冲突（同一图中另一个同 signalName 的 BUS_OUT）
            boolean localConflict = false;
            for (var other : graph.nodes) {
                if (other != n && other.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT
                    && other.signalName.equals(n.signalName)) {
                    localConflict = true; break;
                }
            }
            // Check for cross-block conflict (band registry knows about this name from another block).
            // localBusNames distinguishes THIS block's own synced band definitions from another
            // block's: if this editor ran syncBusBands for the name, it's our own echo (no conflict);
            // otherwise BAND_REGISTRY carries a peer's bands (cross-block conflict).
            // 检查跨方块冲突（频段注册表知道此名称来自另一个方块）。
            // localBusNames 区分本 block 自己同步的频段定义与另一个 block 的：
            // 若本编辑器为此名运行过 syncBusBands，则是自己的回声（无冲突）；
            // 否则 BAND_REGISTRY 携带的是其他方块的频段（跨方块冲突）。
            // （原 anyBusOutOwns 循环缺少 other != n 守卫，匹配到节点自身导致
            // crossConflict 恒 false——死代码，已删除。回归审计：客户端从不显示跨 block 冲突。）
            // 跨 block 冲突：仅当本图完全没有同名 BUS_OUT（含自身）且 BAND_REGISTRY 有该名
            // bands 时成立。若本图有同名 BUS_OUT，BAND_REGISTRY 的 bands 可能是本 block 的
            // 自身 echo（服务端广播回来）——不构成跨 block 冲突（回归审计：加载后的
            // BUS_OUT 名字不在 localBusNames，导致自身 echo 被误标冲突）。
            // Cross-block conflict only when this graph has NO same-name BUS_OUT at all
            // (including itself) AND BAND_REGISTRY has the name. If the graph has one,
            // BAND_REGISTRY's bands may be this block's own echo — not a conflict.
            boolean crossConflict = false;
            boolean anyLocalSameName = false;
            for (var any : graph.nodes) {
                if (any.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT
                    && any.signalName.equals(n.signalName)) {
                    anyLocalSameName = true; break;
                }
            }
            if (!localConflict && !anyLocalSameName && !localBusNames.contains(n.signalName)) {
                var gb = io.github.y15173334444.create_schematic_compute.network.SignalBus.getBands(n.signalName);
                if (gb != null && !gb.isEmpty()) crossConflict = true;
            }
            n.busConflict = localConflict || crossConflict;
        }
    }

    /** 网络钩子：远端 BusBandSyncPacket 更新 BAND_REGISTRY 后，刷新本编辑器图中
     *  busName 相关节点的冲突状态。若图中无该 bus 的 BUS_OUT 则为 no-op。
     *  Network hook: after a remote BusBandSyncPacket updated BAND_REGISTRY, refresh
     *  the conflict state of nodes for {@code busName}. No-op when this editor's
     *  graph has no BUS_OUT for that name. */
    void reevaluateBusConflictsForBus(String busName) {
        if (busName == null || busName.isEmpty()) return;
        var graph = ed.getGraph();
        if (graph == null) return;
        for (var n : graph.nodes) {
            if (n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT
                && n.signalName.equals(busName)) {
                reevaluateBusConflicts(graph);
                return;
            }
        }
    }

    /** 同步所有同总线名的 BUS 节点的频段列表 (Sync band lists of all BUS nodes sharing the same bus name) */
    void syncBusBands(GraphNode src) {
        if (src.signalName.isEmpty()) return;
        // 冲突的 BUS_OUT 不上传频段（防止频道夺取） (Conflicting BUS_OUT does not upload bands, preventing channel takeover)
        if (src.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT && src.busConflict) return;
        var bands = src.signalBands;
        if (src.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT) {
            io.github.y15173334444.create_schematic_compute.network.SignalBus.registerBands(src.signalName, bands);
            localBusNames.add(src.signalName);
        }
        var g = ed.getGraph();
        for (var n : ed.getGraph().nodes) {
            if (n != src && (n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_IN || n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT)
                && n.signalName.equals(src.signalName)) {
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
