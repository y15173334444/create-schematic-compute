package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.client.colorpicker.ColorPickerButton;
import io.github.y15173334444.create_schematic_compute.client.colorpicker.ColorPickerWidget;
import io.github.y15173334444.create_schematic_compute.client.colorpicker.ColorUtils;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeConnection;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.SpatialIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * 节点图编辑器 — 封装两屏共享的编辑、渲染、输入逻辑
 * Node graph editor — encapsulates editing, rendering, and input logic shared across screens
 */
public class GraphEditor {

    /**
     * Resolve the active Host, unwrapping portable terminal wrapper screens.
     * Use this instead of {@code instanceof Host} checks so collaboration
     * features work through the portable terminal.
     */
    public static Host getActiveHost() {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof Host host) return host;
        // Portable terminal wrapper — delegate to inner screen
        if (mc.screen instanceof io.github.y15173334444.create_schematic_compute.client.PortableTerminalScreen.HostWrapper w) {
            var inner = w.getInnerScreen();
            if (inner instanceof Host host) return host;
        }
        return null;
    }

    /** 宿主屏需要实现的接口 (Interface the host screen must implement) */
    public interface Host {
        NodeGraph getGraph();
        void saveGraph();
        void toggleRunning(boolean start);
        boolean isRunning();
        Screen asScreen();
        default void pushUndoSnapshot() {}
        default void performUndo() {}
        default void performRedo() {}
        default Map<Integer, Boolean> getFlipflopStates() { return null; }
        default net.minecraft.core.BlockPos getBlockPos() { return net.minecraft.core.BlockPos.ZERO; }
        // ── Multiplayer collaboration (Phase 0+) ──
        /** Emit an edit op to the server. Default no-op for single-player. */
        default void sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp op) {}
        /** Apply a remote edit op received from the server. */
        default void onRemoteOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp op) {}
        /** Handle a server acknowledgment (assigned node ID, edit version). */
        /** Handle server-assigned node ID from ADD_NODE_REQUEST. */
        default void handleAck(io.github.y15173334444.create_schematic_compute.network.GraphEditAckPacket ack) {
            if (ack.tempId() <= 0 || ack.assignedId() <= 0) return;
            var ed = getEditor();
            if (ed == null) return;
            ed.remapNodeId(ack);
            // 检查是否有待发送的 Ctrl+D 复制数据（等待全部节点获得服务端 ID 后批量发送）
            // Check pending copy groups — flush data ops once all nodes have real IDs
            int tid = ack.tempId(), rid = ack.assignedId();
            for (var it = ed.pendingCopyGroups.entrySet().iterator(); it.hasNext(); ) {
                var g = it.next().getValue();
                if (g.tempToReal.containsKey(tid)) {
                    g.tempToReal.put(tid, rid);
                    if (g.allRemapped()) {
                        ed.flushCopyGroup(g);
                        it.remove();
                    }
                }
            }
        }
        /** Get the local player UUID for soft-lock attribution. */
        default java.util.UUID getPlayerUUID() { return java.util.UUID.randomUUID(); }
        /** Get the local player name for presence display. */
        default String getPlayerName() { return ""; }
        /** Get the client-cached eval snapshot (for DEBUG_PROBE sampling). Null on server or no BE.
         *  获取客户端缓存的求值快照（供 DEBUG_PROBE 采样）。服务端或无 BE 时返回 null。 */
        default io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot getCachedEvalSnapshot() { return null; }
        default GraphEditor getEditor() { return null; }
        /** 像素编辑器是否打开（整图同步守卫：画布编辑期间禁止用服务端数据替换本地图）。
         *  Whether the pixel editor is open (full-sync guard: never replace the local graph
         *  with server data while canvas editing is in progress). */
        default boolean isPixelEditorOpen() { return false; }
        /** 显示区拖拽是否进行中（整图同步守卫：拖拽中替换本地图会孤儿化 draggedDisplayNode，
         *  实时更新全部落空、松手时才跳变 —— "拖拽不跟手、松手才同步"的根因）。
         *  Whether a display-area drag is in progress (full-sync guard: replacing the local
         *  graph mid-drag orphans draggedDisplayNode, so live updates go nowhere and the
         *  element jumps on release). */
        default boolean isDisplayDragInProgress() { return false; }
        /** 存在包编辑模式：0=节点图编辑器，1=显示器布局编辑器。
         *  Presence editing mode: 0 = node graph editor, 1 = monitor display layout editor. */
        default int getPresenceMode() { return 0; }
        /** 显示布局模式下光标的屏幕 X 坐标；返回 -1 时走节点图光标坐标。
         *  Display-layout cursor screen X; -1 falls back to the node-graph cursor. */
        default float getPresenceCursorX() { return -1f; }
        /** 显示布局模式下光标的屏幕 Y 坐标；返回 -1 时走节点图光标坐标。
         *  Display-layout cursor screen Y; -1 falls back to the node-graph cursor. */
        default float getPresenceCursorY() { return -1f; }
        /** 显示布局编辑器中正在拖拽的节点 id（-1 = 无）。
         *  Node currently dragged in the display layout editor, or -1. */
        default int getPresenceDraggedNodeId() { return -1; }
    }

    private final Host host;
    public final NodeRenderer renderer;
    private final SpatialIndex spatialIndex = new SpatialIndex();
    private Predicate<NodeType> nodeFilter;

    /** 每个图的最大节点数上限（含主图和每个封装子图） (Max nodes per graph, including main graph and each encapsulated sub-graph) */
    public static final int MAX_NODES = 1024;

    // ── Per-instance op-based undo (collaboration-safe) ──
    // 基于 op 的每实例撤销（协作安全）

    /**
     * A single undo entry — either a single GraphOp or a batch of ops treated as one atomic unit.
     * 单个撤销条目 —— 要么是单条 GraphOp，要么是作为原子单元处理的批量 op。
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
    /** 总线名/频段名输入框停止输入后、自动同步给协作者之前等待的 tick 数（约 0.5s）。
     *  Ticks to wait after typing stops before auto-syncing a bus/band name to
     *  collaborators (~0.5 s). See {@link #tickDebouncedBusEdits()}. */
    private static final int BUS_EDIT_DEBOUNCE_TICKS = 10;
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
    private void resetBatch() {
        if (batchDepth > 0) { batchDepth = 0; currentBatch.clear(); }
    }

    /** Record an emitted op for per-player undo. Call AFTER sendOp.
     *  If inside a batch, the op is deferred until endUndoBatch(). */
    private void recordOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp op,
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

    /** Encode control point arrays to a string (x0,y0;x1,y1;...), same format as GraphOp.setCtrlPoints.
     *  将控制点数组编码为字符串 (x0,y0;x1,y1;...)，与 GraphOp.setCtrlPoints 格式相同。
     *  @param cx 控制点 X 坐标数组 / control point X coordinates
     *  @param cy 控制点 Y 坐标数组 / control point Y coordinates
     *  @return 编码后的控制点字符串 / encoded control point string */
    private static String encodeCtrlPoints(float[] cx, float[] cy) {
        var sb = new StringBuilder();
        for (int i = 0; i < cx.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(cx[i]).append(',').append(cy[i]);
        }
        return sb.toString();
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

    /** Save a node to NBT string for undo snapshot (REMOVE_NODE restore).
     *  将节点保存为 NBT 字符串，用于撤销快照（REMOVE_NODE 恢复）。 */
    private String saveNodeNbt(io.github.y15173334444.create_schematic_compute.graph.GraphNode node) {
        try {
            var lvl = net.minecraft.client.Minecraft.getInstance().level;
            if (lvl != null) return node.save(lvl.registryAccess()).toString();
        } catch (Exception e) {
            io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.debug("saveNodeNbt failed", e);
        }
        return "";
    }

    /** Commit any pending EditBox edit (enterAction) before undo/redo so the
     *  current edit session is captured in the undo stack.
     *  在撤销/重做前提交任何未完成的 EditBox 编辑，确保当前编辑会话入栈。 */
    private void commitFocusedEditBox() {
        for (var e : enterActions.entrySet()) {
            if (e.getKey().isFocused()) { e.getValue().run(); break; }
        }
    }

    /** 提交所有未同步的 busBox 与频段改名编辑（编译与关屏共用）。
     *  全部走定向 op / BusBandUploadPacket，不做整图上传。
     *  Commit all unsynced busBox and band-rename edits (shared by compile and
     *  screen-close). All via targeted ops / BusBandUploadPacket — no whole-graph upload. */
    private void commitPendingBusEdits() {
        var pendingCommits = new java.util.ArrayList<>(nodeEditStatesById.values());
        for (var st : pendingCommits) {
            // 先同步频段（不重建编辑区），再提交总线名——总线名提交会重建编辑区，
            // 频段值已先落进 signalBands 才不会被冲掉。
            // Sync bands first (no rebuild); the bus-name commit rebuilds the edit
            // state, so the band values must reach signalBands before that happens.
            syncBandBoxes(st);
            if (st.busBox != null && st.busNode != null
                && !st.busBox.getValue().equals(st.busNode.signalName)) {
                commitBusBox(st);
            }
        }
    }

    /** 频段 EditBox 的值是否与节点 signalBands 不一致（即有待同步的改名）。
     *  True when any band EditBox differs from the node's signalBands. */
    private boolean bandBoxesPending(EditState st) {
        var node = st.busNode;
        if (node == null || node.type != NodeType.BUS_OUT || st.fields.size() <= 1) return false;
        for (int bi = 1; bi < st.fields.size(); bi++) {
            int sigIdx = bi - 1;
            if (sigIdx < node.signalBands.size()
                && !st.fields.get(bi).getValue().equals(node.signalBands.get(sigIdx))) return true;
        }
        return false;
    }

    /** 把频段 EditBox 的值写回 signalBands 并上传服务端。
     *  **不重建编辑区**，因此不会打断正在输入的用户。
     *  Write band EditBox values back into signalBands and upload them. Never rebuilds
     *  the edit state, so it cannot interrupt someone mid-typing.
     *
     * @return 是否有改动被同步 / whether anything was synced
     */
    private boolean syncBandBoxes(EditState st) {
        var node = st.busNode;
        if (node == null || node.type != NodeType.BUS_OUT || st.fields.size() <= 1) return false;
        boolean changed = false;
        for (int bi = 1; bi < st.fields.size(); bi++) {
            int sigIdx = bi - 1;
            if (sigIdx < node.signalBands.size()) {
                String val = st.fields.get(bi).getValue();
                if (!val.equals(node.signalBands.get(sigIdx))) {
                    node.signalBands.set(sigIdx, val);
                    node.bandsDirty = true;
                    changed = true;
                }
            }
        }
        if (changed && !node.busConflict) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new io.github.y15173334444.create_schematic_compute.network.BusBandUploadPacket(
                    host.getBlockPos(), node.signalName, node.signalBands));
        }
        return changed;
    }

    /**
     * 总线名 / 频段名的**防抖自动提交**（每 tick 调用）。
     * Debounced auto-commit for bus-name / band-name edits (called each tick).
     *
     * <p>背景：这两个输入框既没有 responder 也不走 enterActions —— 注释写得很清楚，
     * "由 recompile 批量同步"。结果就是协作者只有等编辑者点编译或关屏才能看到改名，
     * 而 PRIVATE/TEXT 等同类命名框都是逐字符实时同步的，行为不一致。
     * Background: neither box has a responder and neither goes through enterActions —
     * the comments say so outright ("synced in batch by recompile"). Collaborators
     * therefore only saw a rename once the editor hit compile or closed the screen,
     * while the PRIVATE/TEXT name boxes synced on every keystroke.
     *
     * <p>为什么防抖而不是逐字符：总线名提交（{@link #commitBusBox}）要清旧频道的全局
     * 数据、重评估冲突、按 BUS_IN/BUS_OUT 分别处理频段，最后还会重建编辑区。逐字符触发
     * 会把"abc"打成"a"→"ab"→"abc"三次改名，中间名字全是无效频道，对端 BUS_IN 还会
     * 反复跟着换频段定义。静止 {@value #BUS_EDIT_DEBOUNCE_TICKS} tick 后再提交，
     * 一次只发最终值。
     * Why debounce instead of per keystroke: committing a bus name clears the old
     * channel's global data, re-evaluates conflicts, handles bands differently for
     * BUS_IN vs BUS_OUT, and finally rebuilds the edit state. Per-keystroke would
     * rename "a" then "ab" then "abc" — two throwaway channels in the middle, and the
     * peer's BUS_IN would keep swapping its band definition. Waiting for
     * {@value #BUS_EDIT_DEBOUNCE_TICKS} idle ticks sends only the final value.
     */
    private void tickDebouncedBusEdits() {
        var states = new java.util.ArrayList<>(nodeEditStatesById.values());
        for (var st : states) {
            if (st.busBox == null || st.busNode == null) continue;
            boolean namePending = !st.busBox.getValue().equals(st.busNode.signalName);
            boolean bandsPending = bandBoxesPending(st);
            if (!namePending && !bandsPending) { st.busEditIdleTicks = 0; continue; }
            if (++st.busEditIdleTicks < BUS_EDIT_DEBOUNCE_TICKS) continue;
            st.busEditIdleTicks = 0;
            syncBandBoxes(st);
            if (namePending) commitBusBox(st);
        }
    }

    /** 关屏前提交所有未同步的局部编辑：所有 enterActions（每个动作都有"值未变即
     *  空操作"的守卫，幂等——覆盖已聚焦的 W/H 等提交型输入框，以及 TAB 切走焦点后
     *  遗留的未提交文本）+ busBox + 频段改名。全部走定向 op/包，不做整图上传——
     *  整图上传会用本客户端旧快照覆盖服务端，冲掉其他玩家并发的编辑。
     *  Commit all unsynced local edits before the screen closes: every enterAction
     *  (each is guarded to no-op when unchanged — covers the focused commit-type
     *  boxes like IMAGE W/H plus text left uncommitted after TAB moved focus away)
     *  + busBox + band renames. All via targeted ops/packets, no whole-graph upload,
     *  which would overwrite the server graph with this client's stale snapshot and
     *  clobber other players' concurrent edits. */
    public void commitPendingEditsForClose() {
        for (var e : new java.util.ArrayList<>(enterActions.entrySet())) {
            e.getValue().run();
        }
        commitPendingBusEdits();
    }

    /** Undo last entry (single op or batch). One Ctrl+Z = one call.
     *  撤销最后一个条目（单条 op 或批量组）。一次 Ctrl+Z = 一次调用。 */
    private void opUndo() {
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
                    io.github.y15173334444.create_schematic_compute.graph.OpExecutor.apply(getGraph(), rev);
                    host.sendOp(rev);
                }
            }
            // Push entire batch as one redo entry
            java.util.Collections.reverse(redone);
            redoStack2.add(new UndoEntry(redone));
        } else {
            var rev = reverseOp(entry);
            if (rev != null) {
                redoStack2.add(entry);
                io.github.y15173334444.create_schematic_compute.graph.OpExecutor.apply(getGraph(), rev);
                host.sendOp(rev);
            }
        }
    }

    /** Redo last undone entry (single op or batch).
     *  重做上一个被撤销的条目（单条 op 或批量组）。
     *  Re-applies the most recent entry from the redo stack to the graph, and syncs via sendOp. */
    private void opRedo() {
        var entry = redoStack2.pollLast();
        if (entry == null) return;
        if (entry.isBatch()) {
            var batch = entry.batch;
            var redone = new java.util.ArrayList<UndoEntry>();
            for (var e : batch) {
                redone.add(e);
                io.github.y15173334444.create_schematic_compute.graph.OpExecutor.apply(getGraph(), e.op);
                host.sendOp(e.op);
            }
            undoStack2.add(new UndoEntry(redone));
        } else {
            undoStack2.add(entry);
            io.github.y15173334444.create_schematic_compute.graph.OpExecutor.apply(getGraph(), entry.op);
            host.sendOp(entry.op);
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
        var graph = getGraph();
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
        if (selectedNode != null && selectedNode.id == tid) selectedNode = node;
        selectedNodes.removeIf(n -> n.id == tid);
        selectedNodes.add(node); // add with remapped node identity
        var expand = expandedNodeIds.remove(tid);
        if (expand) expandedNodeIds.add(rid);
        var state = nodeEditStatesById.remove(tid);
        if (state != null) nodeEditStatesById.put(rid, state);
        if (draggingNode != null && draggingNode.id == tid) draggingNode = node;
        if (wireFromNode == tid) wireFromNode = rid;
        if (encapsulationParent != null && encapsulationParent.id == tid) encapsulationParent = node;
        if (resizingComment != null && resizingComment.id == tid) resizingComment = node;
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

    // ── 编辑状态 (Edit state) ──
    /** 相机 X 偏移（图空间）/ camera X offset (graph space) */
    public float camX=0, camY=0, zoom=1f;
    /** 相机 Y 偏移（图空间）/ camera Y offset (graph space) */
    /** 缩放级别 0.25x~4x / zoom level 0.25x~4x */
    // ── 视角书签 UI 状态 / view bookmark UI state ──
    /** 书签列表面板是否可见 / whether the bookmark list panel is visible */
    private boolean showBookmarkPanel = false;
    /** 书签名称草稿（新建/重命名时使用）/ draft bookmark name (used when creating/renaming) */
    private String bookmarkNameDraft = "";
    /** 是否正在编辑书签名称 / whether bookmark name editing is active */
    private boolean editingBookmarkName = false;
    private int editingBookmarkIndex = -1; // -1 = 新建, >= 0 = 重命名 / -1 = new, >= 0 = renaming
    private int bookmarkScrollOff = 0; // 书签面板滚动偏移 / bookmark panel scroll offset
    // 临时视角（按方块位置存储，session 内同一方块跨编辑器实例恢复，不持久化）
    // Temporary view (keyed by block position, restored across editor instances for the same
    // block within a session; not persisted, not shared across different blocks)
    private static final java.util.Map<net.minecraft.core.BlockPos, float[]> tempViewByPos = new java.util.HashMap<>();

    /** 清除所有临时视角（客户端断开/切换存档时调用，防止跨存档污染）。
     *  Clear all temp views (called on client disconnect/world switch, prevents cross-world pollution). */
    public static void clearTempView() { tempViewByPos.clear(); }
    // ── Phase 2 渲染缓存 —— 状态未变时跳过昂贵的渲染层 ──
    // Phase 2 render cache — skip expensive layers when nothing changed
    /** 上次渲染时的图代数 / graph generation at last render */
    private int lastRenderedGen = -1;
    /** 上次渲染时的相机位置和缩放 / camera position and zoom at last render */
    private float lastRenderedCamX, lastRenderedCamY, lastRenderedZoom;
    /** 上次渲染时的屏幕尺寸 / screen dimensions at last render */
    private int lastRenderedScreenW, lastRenderedScreenH;
    /** 当前正在拖拽的节点 / the node currently being dragged */
    public GraphNode draggingNode=null, selectedNode=null;
    /** 当前选中的单个节点 / the currently selected single node */
    /** 多选节点集合 / set of selected nodes (for multi-select) */
    public final Set<GraphNode> selectedNodes = new HashSet<>();
    /** 拖拽时鼠标相对于节点左上角的偏移 / mouse offset from node top-left during drag */
    public float dragOffX, dragOffY;
    /** 是否正在平移视图 / whether view is being panned */
    public boolean panning=false;
    /** 平移起始鼠标坐标 / pan start mouse position */
    public float panLastX, panLastY;
    /** 是否正在拖拽连线 / whether a wire is being dragged */
    public boolean draggingWire=false;
    /** 连线源节点和引脚索引 / wire source node and pin index */
    public int wireFromNode=-1, wireFromPin=-1;
    /** 连线当前末端坐标（图空间）/ current wire end position (graph space) */
    public float wireEndX, wireEndY;
    // ── DEBUG_SIGNAL_GEN 控制点拖拽 / control point drag ──
    /** 正在拖拽的控制点所属节点 ID / node ID of control point being dragged */
    private int draggingCtrlNode = -1;
    /** 正在拖拽的控制点索引 / index of control point being dragged */
    private int draggingCtrlIdx = -1;
    private String preDragCtrlStr = ""; // control point string before drag, for undo
    private boolean ctrlPointsChanged = false; // true if any control point was modified since last sync
    private long lastClickMs = 0; // 双击检测 / double-click detection
    // ── DEBUG_SIGNAL_GEN x 标记拖拽 / x marker drag ──
    /** 正在拖拽 x 标记线的节点 ID / node ID whose x marker is being dragged */
    private int draggingXMarkerNode = -1;
    private int editBoxDragNodeId = -1; // node id whose EditBox is being drag-selected
    /** 添加节点菜单是否可见 / whether the add-node menu is visible */
    public boolean showMenu=false;
    /** 菜单位置（屏幕坐标）/ menu position (screen coords) */
    public float menuX, menuY;
    /** 菜单中当前选中的节点类型 / currently selected node type in menu */
    public NodeType selectedMenuType=null;
    /** 保存反馈文字显示的截止时间戳 / expiration timestamp for save feedback text */
    public long saveFeedbackUntil=0;
    /** 保存反馈文字内容 / save feedback text content */
    public String saveFeedbackText="";
    /** 导入反馈文字显示的截止时间戳 / expiration timestamp for import feedback text */
    public long importFeedbackUntil=0;
    /** 循环依赖警告文字 / cycle dependency warning text */
    public String cycleWarning=null;
    // ── 导入/导出封装节点对话框 (Import/export encapsulation node dialog) ──
    /** 导出对话框是否可见 / whether the export dialog is visible */
    public boolean showExportDialog = false;
    /** 导入对话框是否可见 / whether the import dialog is visible */
    public boolean showImportDialog = false;
    /** 导出名称编辑框 / export name EditBox */
    public EditBox exportNameEdit = null;
    /** 可导入的文件列表 / list of importable files */
    public java.util.List<java.nio.file.Path> importFiles = null;
    /** 导入列表滚动偏移 / import list scroll offset */
    public int importScrollOff = 0;
    /** 是否启用网格吸附 / whether grid snap is enabled */
    public boolean gridSnapEnabled = NodeRenderer.loadGridSnap();
    /** 当前显示热栏弹窗的节点（点击频率槽时弹出）/ node currently showing hotbar popup (shown when clicking frequency slot) */
    public GraphNode hotbarNode = null;
    // ── 多节点展开：Set + 每节点独立编辑状态 (Multi-node expand: Set + per-node independent edit states) ──
    /** 当前展开的节点 ID 集合 / set of currently expanded node IDs */
    public final java.util.Set<Integer> expandedNodeIds = new java.util.HashSet<>();
    /** 每个节点的独立编辑控件状态（EditBox、按钮位置、频段等）。
     *  Per-node independent edit control state (EditBoxes, button positions, bands, etc.). */
    public static class EditState {
        public final java.util.List<net.minecraft.client.gui.components.EditBox> fields = new java.util.ArrayList<>();
        /** 每个 field 对应的参数索引（用于参数引脚映射和渲染） (Param index each field maps to, for param pin mapping and rendering) */
        public final java.util.List<Integer> fieldParamIndices = new java.util.ArrayList<>();
        public String[] paramKeys;
        public int freqSlotSelected = -1; // -1 = none selected, 0/1 = slot index
        public float boolBtnX, boolBtnY, boolBtnW, boolBtnH;
        public float freqSlotX, freqSlotY;
        public boolean listeningForKey = false;
        public NodeGraph graph;
        /** 有参数引脚连线时阻止折叠（值由连线决定，编辑区已隐藏） (Block collapse when param pin has a connection — value is connection-driven, edit field hidden) */
        public boolean blockCollapse;
        /** 频段 +/- 按钮位置（仅 BUS_IN/OUT 用） (Band +/- button positions, BUS_IN/OUT only) */
        public float bandAddBtnX, bandAddBtnY, bandAddBtnW, bandAddBtnH;
        public float bandRemoveBtnX, bandRemoveBtnY, bandRemoveBtnW, bandRemoveBtnH;
        /** 每个频段引脚的 node-local Y 坐标（同步编辑区渲染与连线检测） (Node-local Y offset for each band pin, syncing edit-area rendering with connection hit-test) */
        public float[] bandPinY;
        /** BUS 总线名 EditBox（用于失焦/Enter 提交检测） (BUS name EditBox, for focus-lost / Enter commit detection) */
        public net.minecraft.client.gui.components.EditBox busBox;
        public GraphNode busNode;
        /** 总线名/频段名输入框自检测到未同步改动以来经过的 tick，供防抖自动提交用。
         *  Ticks elapsed since an unsynced bus-name / band-name edit was noticed, for
         *  the debounced auto-commit. */
        public int busEditIdleTicks = 0;
        /** ColorPickerButton for TEXT/DATA node color editing */
        public ColorPickerButton colorButton;
        /** Mode toggle pending confirmation state (DEBUG_SIGNAL_GEN) */
        public int pendingSetMode = -1;       // -1=none, 0/1=target setMode
        public long pendingSetModeExpireMs = 0;
        public int pendingOutMode = -1;       // -1=none, 0/1=target outMode
        public long pendingOutModeExpireMs = 0;
    }
    /** 节点 ID → 编辑状态 的映射 / node ID → EditState mapping */
    public final java.util.Map<Integer, EditState> nodeEditStatesById = new java.util.HashMap<>();

    // ── 顶栏（名称 + 设置） / top bar (name + settings) ─────────────────

    /** 顶栏高度。工具栏与其余覆盖层在其下排布（顶/底两种工具栏位置都要让开它）。
     *  Top-bar height. The toolbar and every other overlay must clear it in both
     *  toolbar positions (top and bottom). */
    public static final int TOP_BAR_H = 22;

    /** 顶栏名称输入框：值提交到 {@code graph.customName}（SET_BLOCK_NAME op，逐字符同步
     *  —— 与 PRIVATE/TEXT 命名框一致；名称是纯视觉数据，无 commitBusBox 那样的重副作用，
     *  所以不需要防抖）。/ Top-bar name EditBox: commits to {@code graph.customName}
     *  (SET_BLOCK_NAME, synced per keystroke — same as the PRIVATE/TEXT name boxes; the
     *  name is pure visual data with none of commitBusBox's heavy side effects, so no
     *  debounce is needed). */
    private EditBox topBarNameEdit;
    /** 设置弹窗开关（设置界面框架在后续步骤接入；占位）。
     *  Settings dialog toggle (the dialog itself lands in a later step; placeholder). */
    public boolean showSettings = false;
    /** EditBox → 提交动作（回车或失焦时执行） (EditBox → commit action, executed on Enter or focus loss) */
    private final java.util.Map<net.minecraft.client.gui.components.EditBox, Runnable> enterActions = new java.util.HashMap<>();
    // ── 颜色配置面板 (Color configuration panel) ──
    /** 颜色配置面板是否可见 / whether the color configuration panel is visible */
    public boolean showColorConfig = false;
    private boolean suppressEditBoxResponder = false; // suppress SET_PARAM echo from remote ops (H3) (抑制远程SET_PARAM回显)
    /** 颜色选择器控件 / the color picker widget instance */
    public final ColorPickerWidget colorPicker = new ColorPickerWidget();
    /** 主题颜色按钮组 / array of theme color swatch buttons */
    private final ColorPickerButton[] themeButtons = new ColorPickerButton[NodeRenderer._NUM_COLORS];
    // ── 框选 + 多选拖拽状态 (Box-select + multi-drag state) ──
    /** TAB 键是否按下（进入框选/多选模式）/ whether TAB is held (box-select/multi-select mode) */
    private boolean tabHeld = false;
    /** 是否正在进行框选 / whether box-select is active */
    private boolean boxSelecting = false;
    /** 框选起止坐标（屏幕空间）/ box-select start and end coords (screen space) */
    private float boxSX, boxSY, boxEX, boxEY;
    /** 是否正在进行多选拖拽 / whether multi-drag is active */
    private boolean multiDragging = false;
    /** 多选拖拽中被点击的节点 / the node clicked during multi-drag */
    private GraphNode multiClickedNode = null;
    /** 多选节点组的几何中心 / geometric center of the multi-selected node group */
    private float multiCenterX, multiCenterY;
    private long prevGpadButtons = 0; // for gamepad button edge detection in binding mode (手柄按键边缘检测)
    /** 多选拖拽时每个节点的起始位置 / per-node starting positions during multi-drag */
    private final java.util.Map<GraphNode, float[]> multiDragOrigins = new java.util.HashMap<>();
    // ── 鼠标坐标缓存（供 X 键删除用） (Cached mouse coords for X-key deletion) ──
    /** 上次记录的鼠标坐标（图空间）/ last recorded mouse position (graph space) */
    private double lastMouseX, lastMouseY;

    // ── Z-order (B-layer) drag state ──
    private int preDragSortB = 0;
    private final java.util.Map<GraphNode, Integer> preDragSortBs = new java.util.HashMap<>();
    private final java.util.List<GraphNode> containedDragNodes = new java.util.ArrayList<>();
    // Comment push-aside: nodes pushed out of the way during comment drag (sync + undo)
    // 注释撞开：拖动注释时被推开的框外节点（同步+撤销）
    private final java.util.Set<GraphNode> pushedDragNodes = new java.util.HashSet<>();
    private final java.util.Map<Integer, float[]> pushOrigins = new java.util.HashMap<>();
    private final java.util.Map<Integer, float[]> containedOrigins = new java.util.HashMap<>();
    private static final float PUSH_MARGIN = 4f;
    // Old position for MOVE_NODE undo (移动撤销旧坐标)
    private float preDragX, preDragY;
    private final java.util.Map<Integer, float[]> preDragPositions = new java.util.HashMap<>(); // H7: per-node pre-drag coords (每节点拖动前坐标)

    // ── P2 Presence ──
    private final java.util.Map<java.util.UUID, io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket> remotePresences = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Long> remotePresenceTimestamps = new java.util.HashMap<>();
    // Cursor smoothstep lerp: {startX, startY, targetX, targetY, t}
    private final java.util.Map<java.util.UUID, float[]> cursorLerp = new java.util.HashMap<>();
    private long lastPresenceSendTime = 0;
    private static final long PRESENCE_INTERVAL_MS = 120;
    private long lastDragSendTime = 0;
    private static final long DRAG_SEND_INTERVAL_MS = 50;
    private static final long PRESENCE_TIMEOUT_MS = 30_000; // 30s timeout for disconnected players

    /** True if any remote player is currently editing the given node (pixel editor etc).
     *  owner = 当前作用域（-1=主图，>0=封装节点 ID）/ current scope (-1=main graph, >0=encap node ID). */
    public boolean isNodeLocked(int nodeId, int owner) {
        for (var p : remotePresences.values()) {
            if (p.ownerNodeId() != owner) continue;
            if (p.editingNodeId() == nodeId) return true;
            if (p.selectedNodeIds() != null) {
                for (int id : p.selectedNodeIds())
                    if (id == nodeId) return true;
            }
        }
        return false;
    }

    /** Store a remote player's presence. Called from packet handler.
     *  Empty playerName = player left → remove immediately. */
    public void storeRemotePresence(io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket pkt) {
        if (pkt.playerName() == null || pkt.playerName().isEmpty()) {
            remotePresences.remove(pkt.player());
            remotePresenceTimestamps.remove(pkt.player());
            cursorLerp.remove(pkt.player());
            return;
        }
        remotePresences.put(pkt.player(), pkt);
        remotePresenceTimestamps.put(pkt.player(), System.currentTimeMillis());
        float tx = c2sX(pkt.cursorX()), ty = c2sY(pkt.cursorY());
        var cl = cursorLerp.get(pkt.player());
        if (cl == null) {
            cursorLerp.put(pkt.player(), new float[]{tx, ty, tx, ty, 1f});
        } else {
            cl[2] = tx; cl[3] = ty; cl[4] = 0f; // target + reset t
            cl[0] = cl[0] + (tx - cl[0]) * 0.3f; // gentle start from current display
        }
    }

    /** Clear all remote presences (called when editor closes). */
    public void clearRemotePresences() {
        remotePresences.clear();
        remotePresenceTimestamps.clear();
        cursorLerp.clear();
    }

    /** 远端临场数据访问器（显示器布局界面的协作叠加层用）。
     *  Accessor for remote presences (used by the monitor screen's display-mode overlay). */
    public java.util.Map<java.util.UUID, io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket> getRemotePresences() {
        return remotePresences;
    }

    /** 显示布局组件的软锁：是否有其他玩家正在显示布局模式拖拽该组件。
     *  Display-layout component soft lock: is another player dragging this component
     *  in the display layout editor right now? */
    public boolean isDisplayNodeLocked(int nodeId) {
        for (var p : remotePresences.values()) {
            if (p.mode() == 1 && p.displayDraggedNodeId() == nodeId) return true;
        }
        return false;
    }

    /** Remove stale remote presences that haven't been updated within the timeout window.
     *  Public so the monitor screen's display-mode presence overlay can also clean up. */
    public void cleanupStalePresences() {
        long now = System.currentTimeMillis();
        var it = remotePresenceTimestamps.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (now - e.getValue() > PRESENCE_TIMEOUT_MS) {
                remotePresences.remove(e.getKey());
                cursorLerp.remove(e.getKey());
                it.remove();
            }
        }
    }

    /** Check if a node is selected/edited by another player in the same scope (soft lock).
     *  owner = 当前作用域（-1=主图，>0=封装节点 ID）/ current scope (-1=main graph, >0=encap node ID). */
    private boolean isNodeLockedByOther(int nodeId, int owner) {
        for (var rp : remotePresences.values()) {
            if (rp.ownerNodeId() != owner) continue;
            if (rp.selectedNodeId() == nodeId || rp.editingNodeId() == nodeId) return true;
            if (rp.selectedNodeIds() != null) {
                for (int id : rp.selectedNodeIds())
                    if (id == nodeId) return true;
            }
        }
        return false;
    }

    /** Send local presence to server (throttled). Called from mouseMoved and — for the monitor
     *  display layout editor — from MonitorScreen.renderGraphCanvas so presence keeps flowing
     *  in display mode too (the graph-mode renderBg does not run there).
     *  发送本地临场数据到服务端（节流）。由 mouseMoved 调用；显示器布局模式下由
     *  MonitorScreen.renderGraphCanvas 调用，保证显示模式也持续发送。 */
    public void sendPresenceIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastPresenceSendTime < PRESENCE_INTERVAL_MS) return;
        lastPresenceSendTime = now;
        int selId = selectedNode != null ? selectedNode.id : -1;
        int editId = (selectedNode != null && expandedNodeIds.contains(selectedNode.id)) ? selectedNode.id : -1;
        int wfn = draggingWire ? wireFromNode : -1;
        int wfp = draggingWire ? wireFromPin : -1;
        float wex = draggingWire ? wireEndX : 0;
        float wey = draggingWire ? wireEndY : 0;
        // Collect all selected node IDs for multi-select lock display
        int[] selIds = selectedNodes.stream().mapToInt(n -> n.id).toArray();
        // 编辑模式感知：显示布局模式下光标与拖拽节点由 Host 提供
        // Mode-aware presence: in the display layout editor the cursor and dragged node come from the Host
        int mode = host.getPresenceMode();
        float pcx = host.getPresenceCursorX();
        float pcy = host.getPresenceCursorY();
        float cx = pcx >= 0 ? pcx : s2cX(lastMouseX);
        float cy = pcy >= 0 ? pcy : s2cY(lastMouseY);
        int dragId = host.getPresenceDraggedNodeId();
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket(
                host.getBlockPos(), host.getPlayerUUID(), host.getPlayerName(),
                ownerNodeId(), cx, cy,
                selId, editId, wfn, wfp, wex, wey, selIds, (byte)mode, dragId));
    }

    // ── Comment node interaction state ──
    private long lastClickTimeMs = 0;
    private int lastClickNodeId = -1;
    private GraphNode resizingComment = null;
    private float resizeStartW, resizeStartH;
    private final java.util.Map<Integer, float[]> resizeStartNodePositions = new java.util.HashMap<>();
    private GraphNode editingCommentColorNode = null;
    private ColorPickerButton[] commentButtons = null; // created when popup opens
    private final java.util.Map<Integer, Integer> commentScrollOffsets = new java.util.HashMap<>();
    // Scrollbar drag state
    private GraphNode scrollingComment = null;
    private float scrollDragStartY = 0;
    private int scrollDragStartOff = 0;
    private boolean scrollingImport = false;
    private boolean scrollingBookmark = false;
    private boolean scrollingMenu = false;        // 菜单滚动条拖拽 / menu scrollbar drag
    private float menuScrollDragStartY = 0;
    private int menuScrollDragStartOff = 0;
    private int draggingBookmarkIdx = -1; // 书签拖拽排序 / bookmark drag reorder
    private float bookmarkDragY = 0;       // 拖拽时的鼠标 Y / mouse Y during drag

    // ── Ctrl+D 复制待发送数据（等待服务端 ACK 分配真实 ID 后批量发送）
    // Pending copy data (deferred until server ACK assigns real IDs for all nodes in the batch)
    /**
     * Groups all nodes, connections, and data ops for a single Ctrl+D copy operation.
     * 将单次 Ctrl+D 复制操作的所有节点、连线和数据 op 分组。
     * <p>
     * Copy operations use a two-phase protocol: (1) send ADD_NODE_REQUEST for each cloned node,
     * (2) after server ACK assigns real IDs to all nodes, flush deferred data ops (params, formula,
     * connections, etc.) in a batch. This avoids data loss from stale local IDs.
     * 复制操作使用两阶段协议：(1) 为每个克隆节点发送 ADD_NODE_REQUEST，
     * (2) 在服务器 ACK 为所有节点分配真实 ID 后，批量发送延迟的数据 op（参数、公式、连线等）。
     * 这避免了因本地 ID 过期导致的数据丢失。
     */
    private static class PendingCopyGroup {
        final java.util.Map<Integer, Integer> tempToReal = new java.util.HashMap<>(); // tempId → realId (-1 = pending)
        final java.util.List<GraphNode> nodes = new java.util.ArrayList<>();
        final java.util.List<int[]> conns = new java.util.ArrayList<>(); // {fromId, fromPin, toId, toPin} (tempIds)
        final int oid; final net.minecraft.core.BlockPos gpos; final java.util.UUID uid;
        PendingCopyGroup(int oid, net.minecraft.core.BlockPos gpos, java.util.UUID uid) {
            this.oid = oid; this.gpos = gpos; this.uid = uid;
        }
        boolean allRemapped() { return !tempToReal.containsValue(-1); }
    }
    final java.util.Map<Integer, PendingCopyGroup> pendingCopyGroups = new java.util.HashMap<>();
    int nextCopyGroupId = 1;

    // ── 子图编辑栈（封装节点） (Sub-graph edit stack for encapsulation nodes) ──
    /** 快照视图状态（相机位置 + 缩放 + 过滤器），用于进入/退出子图时恢复。
     *  Snapshot of view state (camera position + zoom + filter) for enter/exit sub-graph restore. */
    private record GraphEditState(GraphNode parentNode, Predicate<NodeType> parentFilter,
                                   float camX, float camY, float zoom) {}
    /** 子图编辑栈，支持嵌套封装节点 / sub-graph edit stack, supports nested encapsulation nodes */
    private final java.util.Deque<GraphEditState> graphStack = new java.util.ArrayDeque<>();
    private GraphNode encapsulationParent; // 当前正在编辑的封装节点（null = 编辑主图） (Currently edited encapsulation node; null = editing main graph)
    private Predicate<NodeType> mainNodeFilter; // 进入子图前保存的主图过滤器 (Main graph filter saved before entering sub-graph)

    /** 是否正在编辑封装节点的子图（而非主图）。
     *  Whether currently editing an encapsulation node's sub-graph (rather than the main graph). */
    public boolean isInSubGraph() { return encapsulationParent != null; }
    /** -1 for main graph, otherwise the ENCAPSULATION node ID (sub-graph routing).
     *  -1 表示主图，否则为封装节点 ID（子图路由）。 */
    private int ownerNodeId() { return isInSubGraph() ? encapsulationParent.id : -1; }
    /** 获取当前正在编辑的封装父节点（null = 编辑主图）。
     *  Get the encapsulation parent node currently being edited (null = editing main graph). */
    public GraphNode getEncapsulationParent() { return encapsulationParent; }
    /** Get sub-graph flipflop states for the current encapsulation (synced from server). */
    private Map<Integer, Boolean> getSubFlipflopStates() {
        if (!isInSubGraph()) return null;
        var be = host.getBlockPos() != null && net.minecraft.client.Minecraft.getInstance().level != null
            ? net.minecraft.client.Minecraft.getInstance().level.getBlockEntity(host.getBlockPos())
            : null;
        // 面向 GraphBlockEntity 接口读取（支持继承线与组合线两类宿主）
        // Read through the GraphBlockEntity interface (supports both inheritance-line and
        // composition-line hosts).
        if (be instanceof GraphBlockEntity gbe) {
            var ff = gbe.peekSubStateFlipflops(encapsulationParent.id);
            if (ff != null && !ff.isEmpty()) return ff;
        }
        return java.util.Collections.emptyMap();
    }

    /** 进入封装节点的子图编辑 (Enter sub-graph editing for an encapsulation node) */
    public void enterSubGraph(GraphNode encapNode) {
        if (encapNode.type != NodeType.ENCAPSULATION) return;
        if (encapNode.subGraph == null) encapNode.subGraph = new NodeGraph();
        var parentFilter = mainNodeFilter != null ? mainNodeFilter : nodeFilter;
        graphStack.push(new GraphEditState(encapNode, parentFilter, camX, camY, zoom));
        encapsulationParent = encapNode;
        camX = 0; camY = 0; zoom = 1f;
        expandedNodeIds.clear(); nodeEditStatesById.clear();
        lastInitGeneration = -1; // force re-init for sub-graph expanded nodes
        selectedNode = null; selectedNodes.clear();
        // 子图过滤器：允许 ENCAP_INPUT, ENCAP_OUTPUT 及所有非 I/O 节点 (Sub-graph filter: allow ENCAP_INPUT, ENCAP_OUTPUT and all non-I/O nodes)
        nodeFilter = nt -> nt == NodeType.ENCAP_INPUT || nt == NodeType.ENCAP_OUTPUT
            || (nt != NodeType.REDSTONE_IN && nt != NodeType.REDSTONE_OUT
                && nt != NodeType.PRIVATE_IN && nt != NodeType.PRIVATE_OUT
                && nt != NodeType.BUS_IN && nt != NodeType.BUS_OUT
                && nt != NodeType.ENCAPSULATION
                && nt != NodeType.TEXT && nt != NodeType.DATA
                && nt != NodeType.IMAGE && nt != NodeType.IMAGE_SEQUENCE
                && parentFilter != null && parentFilter.test(nt));
        mainNodeFilter = parentFilter;
    }

    /** 退出子图，返回父图 (Exit sub-graph, return to parent graph) */
    public void exitSubGraph() {
        if (graphStack.isEmpty()) return;
        var state = graphStack.pop();
        encapsulationParent = graphStack.isEmpty() ? null : graphStack.peek().parentNode();
        camX = state.camX(); camY = state.camY(); zoom = state.zoom();
        expandedNodeIds.clear(); nodeEditStatesById.clear();
        lastInitGeneration = -1; // force re-init for parent graph expanded nodes
        selectedNode = null; selectedNodes.clear();
        nodeFilter = state.parentFilter();
        mainNodeFilter = state.parentFilter();
        // 子图修改已写入 encapsulationParent.subGraph，随 Recompile 统一保存 (Sub-graph changes written to encapsulationParent.subGraph, saved on recompile)
    }

    /** 获取当前活动的图（在子图模式下返回子图，否则返回主图）。
     *  Get the currently active graph (returns sub-graph in sub-graph mode, otherwise main graph). */
    public NodeGraph getGraph() {
        return isInSubGraph() ? encapsulationParent.subGraph : host.getGraph();
    }

    /** 委托到宿主屏保存当前图。 / Delegate to host screen to save the current graph. */
    public void saveGraph() {
        host.saveGraph();
    }

    /** Apply a remote edit op received from the server (multiplayer collaboration). */
    public void onRemoteOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp op) {
        // Handle UI-state ops before graph-level apply
        if (op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.EXPAND_NODE) {
            var n = host.getGraph().findNode(op.targetNodeId());
            if (n != null && !expandedNodeIds.contains(n.id)) {
                expandedNodeIds.add(n.id);
                nodeEditStatesById.put(n.id, createEditState(n));
                n.expanded = true;
            }
            return;
        }
        if (op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.COLLAPSE_NODE) {
            var n = host.getGraph().findNode(op.targetNodeId());
            if (n != null) {
                expandedNodeIds.remove(n.id);
                nodeEditStatesById.remove(n.id);
                n.expanded = false;
            }
            return;
        }
        var graph = (op.ownerNodeId() >= 0 && isInSubGraph())
            ? getGraph()
            : host.getGraph();
        if (op.ownerNodeId() >= 0) {
            var encap = host.getGraph().findNode(op.ownerNodeId());
            if (encap == null) return; // 封装节点不存在 / encap node doesn't exist
            if (encap.subGraph == null) encap.subGraph = new io.github.y15173334444.create_schematic_compute.graph.NodeGraph();
            graph = encap.subGraph;
        }
        // REJECT: roll back the locally-applied change that the server refused
        if (op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.REJECT) {
            // The op carries the rejected ADD_CONN details — remove the local connection.
            // For non-originator editors this is a no-op (they never applied it).
            graph.removeConnection(op.fromId(), op.fromPin(), op.toId(), op.toPin());
            // A rejected op never receives an ACK — decrement the pending-op counter so the
            // bounce-back guard doesn't stay latched. / 被拒 op 不会收到 ACK —— 递减待 ACK 计数。
            if (host.getBlockPos() != null
                && net.minecraft.client.Minecraft.getInstance().level != null
                && net.minecraft.client.Minecraft.getInstance().level.getBlockEntity(host.getBlockPos()) instanceof GraphBlockEntity gbe) {
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
        if (op.ownerNodeId() >= 0 && host.getGraph() != null) {
            host.getGraph().rebuildInputCache();
        }
        // Clean up UI state for remote REMOVE_NODE (local delete path does this manually) (M5)
        if (op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.REMOVE_NODE) {
            int rid = op.targetNodeId();
            if (selectedNode != null && selectedNode.id == rid) selectedNode = null;
            selectedNodes.removeIf(n -> n.id == rid);
            expandedNodeIds.remove(rid);
            nodeEditStatesById.remove(rid);
            if (draggingNode != null && draggingNode.id == rid) draggingNode = null;
            if (encapsulationParent != null && encapsulationParent.id == rid) encapsulationParent = null;
            if (resizingComment != null && resizingComment.id == rid) resizingComment = null;
            if (wireFromNode == rid) { wireFromNode = -1; wireFromPin = 0; }
        }
        // Refresh edit panel UI for data changes
        if (op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_PARAM
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_FORMULA
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_COMMENT_TEXT
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_TEXT_COLOR
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_COMMENT_COLORS
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_COMMENT_SIZE
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_IMAGE_SIZE
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.TOGGLE_BOOL
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_CTRL_POINTS) {
            var st = nodeEditStatesById.get(op.targetNodeId());
            if (st != null && op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_PARAM
                && op.paramIndex() < st.fieldParamIndices.size()) {
                int fi = st.fieldParamIndices.get(op.paramIndex());
                if (fi < st.fields.size() && st.fields.get(fi) instanceof net.minecraft.client.gui.components.EditBox eb) {
                    suppressEditBoxResponder = true;
                    eb.setValue(ff3(op.paramValue()));
                    suppressEditBoxResponder = false;
                }
            } else if (st != null && op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_PARAM) {
                // DEBUG_SIGNAL_GEN: setMode/outMode changes → recreate EditState to update visible fields
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.type == NodeType.DEBUG_SIGNAL_GEN && (op.paramIndex() == 0 || op.paramIndex() == 1)) {
                    suppressEditBoxResponder = true;
                    nodeEditStatesById.put(n.id, createEditState(n));
                    suppressEditBoxResponder = false;
                }
            } else if (st == null || op.type() != io.github.y15173334444.create_schematic_compute.graph.OpType.SET_PARAM) {
                // Recreate entire EditState for non-param ops or if expanded.
                var n = graph.findNode(op.targetNodeId());
                if (n != null && expandedNodeIds.contains(n.id))
                    nodeEditStatesById.put(n.id, createEditState(n));
            }
        }
        // When a remote player edits a BUS_OUT signalName (SET_DISPLAY_TEXT) or band list
        // (SET_BANDS), re-evaluate busConflict so all editors see the conflict warning in
        // real time — not just the player who made the edit.
        // 当远程玩家编辑 BUS_OUT 的 signalName（SET_DISPLAY_TEXT）或频段列表（SET_BANDS）时，
        // 重新评估 busConflict 使所有编辑者实时看到冲突警告 —— 而不仅是进行编辑的玩家。
        if (op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT
            || op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_BANDS) {
            var affected = graph.findNode(op.targetNodeId());
            if (affected != null) {
                if (affected.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT) {
                    reevaluateBusConflicts(graph);
                }
                // Sync bands from BAND_REGISTRY when BUS_IN/BUS_OUT is renamed
                // BUS_IN/BUS_OUT 改名时从 BAND_REGISTRY 同步频段
                if ((affected.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_IN
                    || affected.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT)
                    && !affected.signalName.isEmpty()) {
                    var gb = io.github.y15173334444.create_schematic_compute.network.SignalBus.getBands(affected.signalName);
                    if (gb != null && !gb.isEmpty()) {
                        if (!gb.equals(affected.signalBands)) {
                            affected.signalBands = new java.util.ArrayList<>(gb);
                            affected.bandsDirty = true;
                        }
                    }
                }
                // Refresh edit state (conflict warning may change appearance for BUS_OUT)
                // 刷新编辑状态（BUS_OUT 冲突警告可能改变外观）
                if (expandedNodeIds.contains(affected.id))
                    nodeEditStatesById.put(affected.id, createEditState(affected));
            }
        }
    }

    /** 注册 Enter/失焦提交动作 (Register Enter/focus-lost commit action) */
    private void registerEnter(net.minecraft.client.gui.components.EditBox eb, Runnable action) {
        enterActions.put(eb, action);
    }

    /** 创建节点的编辑状态 (Create the edit state for a node) */
    private EditState createEditState(GraphNode node) {
        // 保存旧状态引用（供 busBox 保留输入值） (Save old state ref for busBox value preservation)
        final var oldStRef = nodeEditStatesById.get(node.id);
        // 先移除本节点的旧 EditState（避免旧 EditBox 仍在旧 state 中被保留） (Remove old EditState first to avoid stale EditBox references)
        nodeEditStatesById.remove(node.id);
        // 清除不再被任何 EditState 引用的旧 EditBox 的 enterActions (Clean up enterActions for old EditBoxes no longer referenced)
        enterActions.keySet().removeIf(eb -> {
            for (var st : nodeEditStatesById.values())
                if (st.fields.contains(eb)) return false;
            return true;
        });
        var s = new EditState();
        s.graph = getGraph(); // 用于检查参数引脚连线状态 (Used to check param pin connection state)
        s.paramKeys = node.type.paramNames.clone();
        var mc = Minecraft.getInstance();
        for (int i = 0; i < node.params.length; i++) {
            if (node.type == NodeType.BOOL || node.type == NodeType.GATE || node.type == NodeType.T_FLIPFLOP || node.type == NodeType.LATCH || node.type == NodeType.KEYBOARD || node.type == NodeType.GAMEPAD_BUTTON
                || node.type == NodeType.ENCAP_INPUT || node.type == NodeType.ENCAP_OUTPUT
                || node.type == NodeType.IMAGE || node.type == NodeType.IMAGE_SEQUENCE
                || node.type == NodeType.DEBUG_SIGNAL_GEN || node.type == NodeType.MOUSE_JOYSTICK
                // FORMULA 的 warm 参数(刀5)由编辑区自己渲染为切换按钮(GATE 同款),不走通用 EditBox——EditBox 会抢走脚本编辑区的键盘焦点
                // FORMULA's warm param (knife 5) renders as its own toggle button (GATE-style) — a generic EditBox would steal keyboard focus from the script editor
                || node.type == NodeType.FORMULA) continue;
            // 参数输入引脚已连线 → 阻止折叠（值由连线提供，但引脚仍可见） (Param input pin has connection → block collapse; value driven by connection but pin still visible)
            // 刀5:参数引脚索引用 paramPinIndex(FORMULA 功能引脚数动态,参数在其后)
            // Knife 5: param pin index via paramPinIndex (FORMULA's functional count is dynamic, params follow it)
            int pinIdx = node.paramPinIndex(i);
            if (node.type.editableParamCount() > 0 && getGraph().hasInputConnection(node.id, pinIdx)) {
                s.blockCollapse = true;
            }
            int idx = i;
            var b = new EditBox(mc.font, 0, 0, 60, 16, Component.literal(""));
            b.setMaxLength(12);
            b.setValue(ff3(node.params[i]));
            final float[] preEditParam = {node.params[idx]}; // captured before edit session / 编辑会话开始前捕获
            final float[] lastSentParam = {node.params[idx]};
            b.setResponder(text -> { try {
                if (suppressEditBoxResponder) return; // remote SET_PARAM setValue → don't echo back
                float newV = Float.parseFloat(text.trim());
                if (Math.abs(newV - lastSentParam[0]) > 0.0001f) {
                    node.params[idx] = newV;
                    var op = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(host.getBlockPos(), ownerNodeId(), node.id, idx, newV, host.getPlayerUUID());
                    host.sendOp(op); // sync to server, undo recorded on commit / 同步到服务器，撤销在提交时记录
                    lastSentParam[0] = newV;
                }
            } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.debug("Invalid float in EditBox: {}", b.getValue().trim()); } });
            enterActions.put(b, () -> {
                if (Math.abs(lastSentParam[0] - preEditParam[0]) > 0.0001f) {
                    var op = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(host.getBlockPos(), ownerNodeId(), node.id, idx, lastSentParam[0], host.getPlayerUUID());
                    recordOp(op, 0, 0, preEditParam[0], null);
                    preEditParam[0] = lastSentParam[0];
                }
            });
            s.fields.add(b);
            s.fieldParamIndices.add(i);
        }
        if ((node.type == NodeType.REDSTONE_IN || node.type == NodeType.REDSTONE_OUT) && node.itemParams.length < 2)
            node.itemParams = new ItemStack[]{ItemStack.EMPTY, ItemStack.EMPTY};
        if (node.type == NodeType.PRIVATE_IN || node.type == NodeType.PRIVATE_OUT) {
            var sb = new EditBox(mc.font, 0, 0, 120, 16, Component.literal(""));
            sb.setMaxLength(32); sb.setValue(node.signalName);
            final String[] preEditSig = {node.signalName}; // initial value for undo
            final String[] lastSig = {node.signalName};
            sb.setResponder(text -> {
                if (!text.equals(lastSig[0])) {
                    node.signalName = text;
                    // Sync bands from new channel's BAND_REGISTRY (or clear if none)
                    // 从新频道 BAND_REGISTRY 同步频段（无则清空）
                    var gb = io.github.y15173334444.create_schematic_compute.network.SignalBus.getBands(text);
                    node.signalBands = (gb != null && !gb.isEmpty())
                        ? new java.util.ArrayList<>(gb)
                        : new java.util.ArrayList<>();
                    node.bandsDirty = true;
                    var op = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT,
                        host.getBlockPos(), ownerNodeId(), node.id, 0, null, 0f, 0f,
                        0, 0, 0, 0, 0, 0f, text, 0, 0, 0, 0, null, 0, 0, 0,
                        net.minecraft.world.item.ItemStack.EMPTY, 0L, host.getPlayerUUID());
                    host.sendOp(op); // sync, undo recorded on commit / 同步，撤销在提交时记录
                    lastSig[0] = text;
                }
            });
            enterActions.put(sb, () -> {
                if (!lastSig[0].equals(preEditSig[0])) {
                    var op = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT,
                        host.getBlockPos(), ownerNodeId(), node.id, 0, null, 0f, 0f,
                        0, 0, 0, 0, 0, 0f, lastSig[0], 0, 0, 0, 0, null, 0, 0, 0,
                        net.minecraft.world.item.ItemStack.EMPTY, 0L, host.getPlayerUUID());
                    recordOp(op, 0, 0, 0, preEditSig[0]);
                    preEditSig[0] = lastSig[0];
                }
            });
            s.fields.add(sb);
        }
        if (node.type == NodeType.BUS_IN || node.type == NodeType.BUS_OUT) {
            // 预计算编辑区引脚位置（避免连线首帧跳动） (Pre-compute edit-area pin positions to avoid first-frame connection jitter)
            int bandCnt = node.bandCount();
            if (bandCnt > 0) {
                s.bandPinY = new float[bandCnt];
                for (int i = 0; i < bandCnt; i++) {
                    s.bandPinY[i] = bandPinY(node, i, zoom);
                    // 检查频段引脚是否有连线，有则阻止折叠 (Check if band pin has a connection, block collapse if so)
                    if (node.type == NodeType.BUS_OUT && getGraph().hasInputConnection(node.id, i))
                        s.blockCollapse = true;
                    else if (node.type == NodeType.BUS_IN) {
                        final int bi = i;
                        if (getGraph().connections.stream().anyMatch(c -> c.fromId == node.id && c.fromPin == bi))
                            s.blockCollapse = true;
                    }
                }
            }
            // BUS_IN 展开时自动同步频段（先本地 BUS_OUT，后全局注册表） (Auto-sync bands when BUS_IN expands: local BUS_OUT first, then global registry)
            if (node.type == NodeType.BUS_IN && !node.signalName.isEmpty() && node.signalBands.isEmpty()) {
                boolean synced = false;
                // 先从同图内 BUS_OUT 同步 (Sync from local BUS_OUT in same graph first)
                for (var n : getGraph().nodes) {
                    if (n.type == NodeType.BUS_OUT && n.signalName.equals(node.signalName) && n.bandCount() > 0) {
                        node.signalBands = new java.util.ArrayList<>(n.signalBands); synced = true; break;
                    }
                }
                // 本地没有则从全局注册表同步 (Fallback: sync from global registry)
                if (!synced) {
                    var gb = io.github.y15173334444.create_schematic_compute.network.SignalBus.getBands(node.signalName);
                    if (gb != null && !gb.isEmpty()) node.signalBands = new java.util.ArrayList<>(gb);
                }
            }
            var busBox = new EditBox(mc.font, 0, 0, 120, 16, Component.literal(""));
            busBox.setMaxLength(32); busBox.setValue(node.signalName);
            // busBox 不通过 enterActions 提交；保留旧聚焦 busBox 的输入值 (busBox not committed via enterActions; preserve old focused busBox input)
            var oldSt = oldStRef;
            s.busNode = node;
            if (oldSt != null && oldSt.busBox != null && oldSt.busBox.isFocused()) {
                busBox.setValue(oldSt.busBox.getValue()); // 保留用户正在输入的内容 (Preserve user's in-progress input)
                busBox.setFocused(true);
            }
            s.busBox = busBox;
            s.fields.add(busBox);
            s.fieldParamIndices.add(-1);
            if (node.signalBands == null) node.signalBands = new java.util.ArrayList<>();
            // 同步旧频段 EditBox 的值到 signalBands（仅同步未聚焦的，防止干扰正在编辑的框） (Sync old band EditBox values to signalBands; only unfocused ones to avoid disrupting active edits)
            if (oldSt != null && oldSt.fields.size() > 1 && node.type == NodeType.BUS_OUT) {
                for (int bi = 1; bi < oldSt.fields.size(); bi++) {
                    int sigIdx = bi - 1;
                    var oldBox = oldSt.fields.get(bi);
                    if (sigIdx < node.signalBands.size() && !oldBox.isFocused()) {
                        String val = oldBox.getValue();
                        if (!val.isEmpty()) node.signalBands.set(sigIdx, val);
                        node.bandsDirty = true;
                    }
                }
            }
            for (int bi = 0; bi < node.signalBands.size(); bi++) {
                final int idx = bi;
                var bandBox = new EditBox(mc.font, 0, 0, 80, 16, Component.literal(""));
                bandBox.setMaxLength(16); bandBox.setValue(node.signalBands.get(bi));
                if (node.type == NodeType.BUS_IN) bandBox.setEditable(false);
                // BUS_OUT 频段可编辑但不在 enterActions 中（由 recompile 批量同步） (BUS_OUT bands editable but not in enterActions; synced in batch by recompile)
                s.fields.add(bandBox);
                s.fieldParamIndices.add(bi);
            }
        }
        if (node.type == NodeType.TEXT) {
            var tb = new EditBox(mc.font, 0, 0, 120, 16, Component.literal(""));
            tb.setMaxLength(256); tb.setValue(node.displayText);
            final String[] preEditText = {node.displayText}; // initial value for undo
            final String[] lastText = {node.displayText};
            tb.setResponder(text -> {
                if (!text.equals(lastText[0])) {
                    node.displayText = text;
                    var op = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT,
                        host.getBlockPos(), ownerNodeId(), node.id, 0, null, 0f, 0f,
                        0, 0, 0, 0, 0, 0f, text, 0, 0, 0, 0, null, 0, 0, 0,
                        net.minecraft.world.item.ItemStack.EMPTY, 0L, host.getPlayerUUID());
                    host.sendOp(op); // sync, undo recorded on commit / 同步，撤销在提交时记录
                    lastText[0] = text;
                }
            });
            enterActions.put(tb, () -> {
                if (!lastText[0].equals(preEditText[0])) {
                    var op = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT,
                        host.getBlockPos(), ownerNodeId(), node.id, 0, null, 0f, 0f,
                        0, 0, 0, 0, 0, 0f, lastText[0], 0, 0, 0, 0, null, 0, 0, 0,
                        net.minecraft.world.item.ItemStack.EMPTY, 0L, host.getPlayerUUID());
                    recordOp(op, 0, 0, 0, preEditText[0]);
                    preEditText[0] = lastText[0];
                }
            });
            s.fields.add(tb);
            // Color swatch button replaces old hex EditBox
            s.colorButton = new ColorPickerButton(
                () -> node.textColor != 0 ? node.textColor : 0xFFCCCCCC,
                c -> { int oldC = node.textColor; node.textColor = c; markDirty();
                    var tcOp = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.SET_TEXT_COLOR,
                        host.getBlockPos(), ownerNodeId(), node.id, 0, null, 0f, 0f,
                        0, 0, 0, 0, 0, 0f, null, 0, 0, c, 0, null, 0, 0, 0,
                        net.minecraft.world.item.ItemStack.EMPTY, 0L, host.getPlayerUUID());
                    host.sendOp(tcOp); recordOp(tcOp, 0, 0, oldC, null); },
                colorPicker
            );
            s.paramKeys = new String[]{"text", "color"};
        }
        if (node.type == NodeType.DATA) {
            // Color swatch button replaces old hex EditBox
            s.colorButton = new ColorPickerButton(
                () -> node.textColor != 0 ? node.textColor : 0xFF88FF88,
                c -> { int oldC = node.textColor; node.textColor = c; markDirty();
                    var tcOp = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.SET_TEXT_COLOR,
                        host.getBlockPos(), ownerNodeId(), node.id, 0, null, 0f, 0f,
                        0, 0, 0, 0, 0, 0f, null, 0, 0, c, 0, null, 0, 0, 0,
                        net.minecraft.world.item.ItemStack.EMPTY, 0L, host.getPlayerUUID());
                    host.sendOp(tcOp); recordOp(tcOp, 0, 0, oldC, null); },
                colorPicker
            );
            s.paramKeys = new String[]{"color"};
        }
        if (node.type == NodeType.IMAGE || node.type == NodeType.IMAGE_SEQUENCE) {
            float[] defaults = {0.01f, 0.01f, 1f};
            for (int pi = 0; pi < 3; pi++) {
                int idx = pi;
                var b = new EditBox(mc.font, 0, 0, 50, 16, Component.literal(""));
                b.setMaxLength(8); b.setValue(ff3(node.params.length > idx ? node.params[idx] : defaults[idx]));
                int iidx = idx; registerEnter(b, () -> { try { if (node.params.length > iidx) node.params[iidx] = Float.parseFloat(b.getValue().trim()); } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.debug("Invalid float in EditBox: {}", b.getValue().trim()); } });
                s.fields.add(b);
            }
            // 画布尺寸 W/H 已移入像素编辑器（双击 IMAGE/IMAGE_SEQUENCE 打开，顶部 Canvas W/H 输入框），
            // 此处不再显示尺寸字段。s.paramKeys 与字段数保持一致（3 个：moveX/moveY/rotScl）。
            // Canvas W/H moved into the pixel editor (double-click an IMAGE/IMAGE_SEQUENCE node;
            // Canvas W/H fields sit at the top of that overlay). No size fields here anymore —
            // paramKeys stays aligned with the 3 remaining fields (moveX/moveY/rotScl).
            s.paramKeys = new String[]{"moveX", "moveY", "rotScl"};
        }
        if (node.type == NodeType.COMMENT) {
            int editW = Math.max(40, Math.round(node.commentWidth) - 28);
            var mle = new io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox(
                mc.font, 0, 0, editW, 18);
            mle.setMaxLength(4096);
            mle.setValue(node.displayText);
            mle.setBackgroundColor(0x00000000); // transparent, let comment bg show through
            mle.setTextColor(node.commentTextColor);
            mle.setCursorColor(node.commentTextColor);
            mle.setDrawBorder(false);
            mle.setResponder(t -> { node.displayText = t;
                host.sendOp(new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                    io.github.y15173334444.create_schematic_compute.graph.OpType.SET_COMMENT_TEXT,
                    host.getBlockPos(), ownerNodeId(), node.id, 0, null, 0f, 0f,
                    0, 0, 0, 0, 0, 0f, t, 0, 0, 0, 0, null, 0, 0, 0,
                    net.minecraft.world.item.ItemStack.EMPTY, 0L, host.getPlayerUUID())); });
            mle.setFocused(true); // auto-focus so user can type immediately
            s.fields.add(mle);
        }
        if (node.type == NodeType.FORMULA) {
            // Multi-line script editor — single MultiLineEditBox
            int editW = NodeRenderer.WIDE_NW - 36;
            var mle = new io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox(
                mc.font, 0, 0, editW, 18);
            // 16KB 上限:火控等大脚本(~5KB)可整篇粘贴 / 16KB cap: fire-control-scale scripts (~5KB) paste whole
            mle.setMaxLength(io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox.MAX_LENGTH);
            mle.setValue(node.formula);
            node.cachedScript = null;

            // ── Syntax highlighting / 语法高亮 ──
            mle.setHighlighter(io.github.y15173334444.create_schematic_compute.graph.FormulaParser::tokenize);
            mle.setTokenPalette(io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox.DEFAULT_PALETTE);

            // ── Autocomplete / 自动补全 ──
            mle.setCompletionProvider(
                io.github.y15173334444.create_schematic_compute.client.FormulaCompletion::candidates, node);

            // Initial parse and validation from the actual formula text
            if (!node.formula.isEmpty()) {
                var initScript = io.github.y15173334444.create_schematic_compute.graph.FormulaParser.parseScript(node.formula);
                node.dynamicInputCount = initScript.inputVars.size();
                node.dynamicOutputCount = Math.max(1, initScript.outputLabels.size());
                node.outputLabels = initScript.outputLabels;
                node.formulaIssues = io.github.y15173334444.create_schematic_compute.graph.FormulaParser.validate(node.formula);
                mle.setHasError(hasErrors(node.formulaIssues));
            }

            final int formulaNodeId = node.id; // capture id, re-fetch node each call
            mle.setResponder(t -> {
                // 全角/中文符号兜底转换(insertText 已实时转换,此处覆盖 setValue/撤销等路径)
                // Full-width fallback conversion (insertText already converts live; this covers setValue/undo paths)
                String sanitized = io.github.y15173334444.create_schematic_compute.graph.FormulaParser.sanitizeFullwidth(t);
                // Re-fetch from current graph — the graph reference may have been
                // replaced by an NBT sync between keystrokes.
                // 每次按键重新获取图引用——NBT 同步可能在两次按键之间替换了图对象。
                var cur = host.getGraph().findNode(formulaNodeId);
                if (cur == null || cur.type != NodeType.FORMULA) return;
                cur.formula = sanitized;
                var res = io.github.y15173334444.create_schematic_compute.graph.FormulaParser.parseScript(sanitized);
                cur.cachedScript = res; // cache for ensureScriptParsed() — avoids double-parse
                int newIn = res.inputVars.size();
                int newOut = Math.max(1, res.outputLabels.size());
                cur.dynamicInputCount = newIn;
                cur.dynamicOutputCount = newOut;
                cur.outputLabels = res.outputLabels;

                // Clean up connections to now-removed pins using stable pinId (v1.2.4).
                // Uses pinIndex resolution, not list.contains, to correctly handle
                // default output labels ("out0" vs "") and cachedScript-based resolution.
                // 使用 pinIndex 解析判断（而非 list.contains），正确处理默认输出标签
                // （"out0" vs ""）以及基于 cachedScript 的解析。
                host.getGraph().connections.removeIf(c -> {
                    if (c.toId == cur.id) {
                        if (c.toPinId != null) return cur.inputPinIndex(c.toPinId) < 0;
                        else return c.toPin >= cur.inputs(); // legacy fallback
                    }
                    if (c.fromId == cur.id) {
                        if (c.fromPinId != null) return cur.outputPinIndex(c.fromPinId) < 0;
                        else return c.fromPin >= cur.outputs(); // legacy fallback
                    }
                    return false;
                });
                host.getGraph().rebuildInputCache();

                // ── Real-time validation (client-side only) ──
                cur.formulaIssues = io.github.y15173334444.create_schematic_compute.graph.FormulaParser.validate(sanitized);
                mle.setHasError(hasErrors(cur.formulaIssues));

                host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setFormula(
                    host.getBlockPos(), ownerNodeId(), formulaNodeId, sanitized, host.getPlayerUUID()));
            });
            s.fields.add(mle);
        }
        if (node.type == NodeType.DEBUG_SIGNAL_GEN) {
            createDebugSignalGenEditState(node, s, mc);
        }
        if (node.type == NodeType.ENCAP_INPUT || node.type == NodeType.ENCAP_OUTPUT) {
            var nb = new EditBox(mc.font, 0, 0, 100, 16, Component.literal(""));
            nb.setMaxLength(32); nb.setValue(node.displayText);
            final String[] preEditName = {node.displayText}; // initial value for undo
            final String[] lastName = {node.displayText};
            nb.setResponder(text -> {
                if (!text.equals(lastName[0])) {
                    node.displayText = text;
                    var op = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT,
                        host.getBlockPos(), ownerNodeId(), node.id, 0, null, 0f, 0f,
                        0, 0, 0, 0, 0, 0f, text, 0, 0, 0, 0, null, 0, 0, 0,
                        net.minecraft.world.item.ItemStack.EMPTY, 0L, host.getPlayerUUID());
                    host.sendOp(op); // sync, undo recorded on commit / 同步，撤销在提交时记录
                    lastName[0] = text;
                }
            });
            enterActions.put(nb, () -> {
                if (!lastName[0].equals(preEditName[0])) {
                    var op = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT,
                        host.getBlockPos(), ownerNodeId(), node.id, 0, null, 0f, 0f,
                        0, 0, 0, 0, 0, 0f, lastName[0], 0, 0, 0, 0, null, 0, 0, 0,
                        net.minecraft.world.item.ItemStack.EMPTY, 0L, host.getPlayerUUID());
                    recordOp(op, 0, 0, 0, preEditName[0]);
                    preEditName[0] = lastName[0];
                }
            });
            s.fields.add(nb);
            s.paramKeys = new String[]{"name"};
        }
        return s;
    }

    /** 为 DEBUG_SIGNAL_GEN 创建条件编辑状态（公式/参数按 setMode/outMode 动态可见）。
     *  Create conditional edit state for DEBUG_SIGNAL_GEN (formula/params visible per setMode/outMode). */
    /** Helper: true if the issue list contains at least one ERROR-level item.
     *  辅助方法：如果问题列表中至少有一个 ERROR 级别的问题则返回 true。 */
    private static boolean hasErrors(java.util.List<io.github.y15173334444.create_schematic_compute.graph.FormulaParser.FormulaIssue> issues) {
        if (issues == null) return false;
        for (var iss : issues)
            if (iss.severity() == io.github.y15173334444.create_schematic_compute.graph.FormulaParser.Severity.ERROR)
                return true;
        return false;
    }

    private void createDebugSignalGenEditState(GraphNode node, EditState s, Minecraft mc) {
        int setMode = node.params.length > 0 ? (int) node.params[0] : 0;
        int outMode = node.params.length > 1 ? (int) node.params[1] : 0;
        int editW = NodeRenderer.WIDE_NW - 36;

        // 公式 EditBox（仅 SET_FORMULA 模式）
        if (setMode == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.SET_FORMULA) {
            var fe = new EditBox(mc.font, 0, 0, editW, 16, Component.literal(""));
            fe.setMaxLength(256);
            fe.setValue(node.formula);
            fe.setHint(Component.literal("f(x)=... (sin/cos 为度, x∈[0,1])"));
            fe.setResponder(t -> {
                // 全角/中文符号实时转半角(与 FORMULA 编辑器同款);转换时写回输入框显示,响应器以干净文本重入
                // Convert full-width/CJK symbols live (same as the FORMULA editor); write back to the box
                // on conversion — the responder re-enters with clean text
                String sanitized = io.github.y15173334444.create_schematic_compute.graph.FormulaParser.sanitizeFullwidth(t);
                if (!sanitized.equals(t)) { fe.setValue(sanitized); return; }
                node.formula = sanitized;
                node.debugFormulaRpn = null;
                host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setFormula(
                    host.getBlockPos(), ownerNodeId(), node.id, sanitized, host.getPlayerUUID()));
            });
            s.fields.add(fe);
        }

        // speed EditBox（仅 SET_MANUAL + OUT_FREQ 模式）
        if (setMode == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.SET_MANUAL
            && outMode == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.OUT_FREQ) {
            int idx = 2;
            var b = new EditBox(mc.font, 0, 0, 60, 16, Component.literal(""));
            b.setMaxLength(12);
            b.setValue(ff3(node.params.length > idx ? node.params[idx] : (1f / 20f)));
            final float[] preEditSpd = {node.params.length > idx ? node.params[idx] : (1f / 20f)};
            final float[] lastSentSpd = {preEditSpd[0]};
            b.setResponder(text -> { try {
                if (suppressEditBoxResponder) return;
                float newV = Float.parseFloat(text.trim());
                if (Math.abs(newV - lastSentSpd[0]) > 0.0001f) {
                    if (node.params.length > idx) node.params[idx] = newV;
                    host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                        host.getBlockPos(), ownerNodeId(), node.id, idx, newV, host.getPlayerUUID()));
                    lastSentSpd[0] = newV;
                }
            } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.debug("Invalid float in EditBox: {}", b.getValue().trim()); } });
            enterActions.put(b, () -> {
                if (Math.abs(lastSentSpd[0] - preEditSpd[0]) > 0.0001f) {
                    var op = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(host.getBlockPos(), ownerNodeId(), node.id, idx, lastSentSpd[0], host.getPlayerUUID());
                    recordOp(op, 0, 0, preEditSpd[0], null);
                    preEditSpd[0] = lastSentSpd[0];
                }
            });
            s.fields.add(b);
        }

        // amplitude EditBox（仅 SET_MANUAL 模式）
        if (setMode == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.SET_MANUAL) {
            int idx = 3;
            var b = new EditBox(mc.font, 0, 0, 60, 16, Component.literal(""));
            b.setMaxLength(12);
            b.setValue(ff3(node.params.length > idx ? node.params[idx] : 1f));
            final float[] preEditAmp = {node.params.length > idx ? node.params[idx] : 1f};
            final float[] lastSentAmp = {preEditAmp[0]};
            b.setResponder(text -> { try {
                if (suppressEditBoxResponder) return;
                float newV = Float.parseFloat(text.trim());
                if (Math.abs(newV - lastSentAmp[0]) > 0.0001f) {
                    if (node.params.length > idx) node.params[idx] = newV;
                    host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                        host.getBlockPos(), ownerNodeId(), node.id, idx, newV, host.getPlayerUUID()));
                    lastSentAmp[0] = newV;
                }
            } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.debug("Invalid float in EditBox: {}", b.getValue().trim()); } });
            enterActions.put(b, () -> {
                if (Math.abs(lastSentAmp[0] - preEditAmp[0]) > 0.0001f) {
                    var op = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(host.getBlockPos(), ownerNodeId(), node.id, idx, lastSentAmp[0], host.getPlayerUUID());
                    recordOp(op, 0, 0, preEditAmp[0], null);
                    preEditAmp[0] = lastSentAmp[0];
                }
            });
            s.fields.add(b);
        }

        // inputX 不显示 EditBox — 用户直接拖拽 XY 图上的天蓝色扫描线设置 x 值
        // inputX has no EditBox — set x by dragging the sky-blue marker on the chart
    }

    /** 处理 DEBUG_SIGNAL_GEN 模式切换按钮点击（含二次确认状态机）。
     *  Handle DEBUG_SIGNAL_GEN mode toggle button click (with confirm-on-second-click state machine).
     *  @param hit format: "setMode:0", "setMode:1", "outMode:0", "outMode:1" */
    private void handleModeToggleClick(GraphNode node, EditState st, String hit) {
        long now = System.currentTimeMillis();
        String[] parts = hit.split(":");
        boolean isSetMode = parts[0].equals("setMode");
        int targetVal = Integer.parseInt(parts[1]);
        int paramIdx = isSetMode ? 0 : 1;
        int currentVal = node.params.length > paramIdx ? (int) node.params[paramIdx] : 0;

        // 点击当前已激活的模式 → 忽略
        if (targetVal == currentVal) {
            // clear any pending state
            if (isSetMode) { st.pendingSetMode = -1; st.pendingSetModeExpireMs = 0; }
            else { st.pendingOutMode = -1; st.pendingOutModeExpireMs = 0; }
            return;
        }

        // 检查是否匹配待确认的目标
        int pendingTarget = isSetMode ? st.pendingSetMode : st.pendingOutMode;
        long pendingExpire = isSetMode ? st.pendingSetModeExpireMs : st.pendingOutModeExpireMs;
        boolean hasPending = pendingTarget >= 0 && now < pendingExpire;

        if (hasPending && pendingTarget == targetVal) {
            // 二次点击确认 → 执行切换并清空原模式数据
            // Second click confirmed → execute switch and clear old mode data
            beginUndoBatch();
            float oldMode = node.params.length > paramIdx ? node.params[paramIdx] : 0;
            float oldSpeed = node.params.length > 2 ? node.params[2] : 1f / 20f;
            float oldAmp = node.params.length > 3 ? node.params[3] : 1f;
            String oldFormula = node.formula != null ? node.formula : "";
            float[] oldCtrlX = node.debugCtrlX;
            float[] oldCtrlY = node.debugCtrlY;
            node.params[paramIdx] = targetVal;
            if (isSetMode) {
                // 切换设置模式 → 清空原模式数据 + 重置参数为默认，同步
                // Switching set mode → clear old data + reset params to default, sync
                if (targetVal == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.SET_MANUAL) {
                    // 切换到手动曲线 → 清空公式，重置 speed/amp 为默认
                    node.formula = "";
                    node.debugFormulaRpn = null;
                    var opF = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setFormula(
                        host.getBlockPos(), ownerNodeId(), node.id, "", host.getPlayerUUID());
                    host.sendOp(opF); recordOp(opF, 0, 0, 0, oldFormula);
                    node.params[2] = 1f / 20f; // speed 默认
                    node.params[3] = 1f;       // amplitude 默认
                    var opSp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                        host.getBlockPos(), ownerNodeId(), node.id, 2, 1f / 20f, host.getPlayerUUID());
                    host.sendOp(opSp); recordOp(opSp, 0, 0, oldSpeed, null);
                    var opAmp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                        host.getBlockPos(), ownerNodeId(), node.id, 3, 1f, host.getPlayerUUID());
                    host.sendOp(opAmp); recordOp(opAmp, 0, 0, oldAmp, null);
                } else {
                    // 切换到 f(x) → 重置控制点为默认，speed/amp 恢复默认
                    node.debugCtrlX = new float[]{0f, 1f};
                    node.debugCtrlY = new float[]{0f, 0f};
                    String oldCtrlStr = oldCtrlX != null ? encodeCtrlPoints(oldCtrlX, oldCtrlY) : "";
                    var opCp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCtrlPoints(
                        host.getBlockPos(), ownerNodeId(), node.id, node.debugCtrlX, node.debugCtrlY, host.getPlayerUUID());
                    host.sendOp(opCp); recordOp(opCp, 0, 0, 0, oldCtrlStr);
                    node.params[2] = 1f / 20f;
                    node.params[3] = 1f;
                    var opSp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                        host.getBlockPos(), ownerNodeId(), node.id, 2, 1f / 20f, host.getPlayerUUID());
                    host.sendOp(opSp); recordOp(opSp, 0, 0, oldSpeed, null);
                    var opAmp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                        host.getBlockPos(), ownerNodeId(), node.id, 3, 1f, host.getPlayerUUID());
                    host.sendOp(opAmp); recordOp(opAmp, 0, 0, oldAmp, null);
                }
            }
            // 发送 SET_PARAM op
            var modeOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                host.getBlockPos(), ownerNodeId(), node.id, paramIdx, (float) targetVal, host.getPlayerUUID());
            host.sendOp(modeOp); recordOp(modeOp, 0, 0, oldMode, null);
            endUndoBatch();
            // 清除待确认状态
            if (isSetMode) { st.pendingSetMode = -1; st.pendingSetModeExpireMs = 0; }
            else { st.pendingOutMode = -1; st.pendingOutModeExpireMs = 0; }
            // 重建编辑状态以更新可见的 EditBox
            nodeEditStatesById.put(node.id, createEditState(node));
            markDirty();
        } else {
            // 首次点击 → 设置待确认状态（3 秒超时）
            // First click → set pending confirmation (3 second timeout)
            if (isSetMode) { st.pendingSetMode = targetVal; st.pendingSetModeExpireMs = now + 3000; }
            else { st.pendingOutMode = targetVal; st.pendingOutModeExpireMs = now + 3000; }
        }
    }

    /** 切换节点展开/折叠（封装节点双击进入子图编辑，其余节点内联展开） (Toggle node expand/collapse; encapsulation nodes enter sub-graph, others inline-expand) */
    private void toggleExpand(GraphNode node) {
        if (isNodeLockedByOther(node.id, ownerNodeId())) return; // soft lock (same scope only)
        if (node.type == NodeType.ENCAPSULATION) {
            enterSubGraph(node);
            return;
        }
        if (!shouldOpenPanel(node)) return;
        if (expandedNodeIds.contains(node.id)) {
            var st = nodeEditStatesById.get(node.id);
            if (st != null && st.blockCollapse) return;
            expandedNodeIds.remove(node.id); nodeEditStatesById.remove(node.id);
            node.expanded = false;
            host.sendOp(new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                io.github.y15173334444.create_schematic_compute.graph.OpType.COLLAPSE_NODE,
                host.getBlockPos(), ownerNodeId(), node.id, host.getPlayerUUID()));
        } else {
            expandedNodeIds.add(node.id); nodeEditStatesById.put(node.id, createEditState(node));
            node.expanded = true;
            host.sendOp(new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                io.github.y15173334444.create_schematic_compute.graph.OpType.EXPAND_NODE,
                host.getBlockPos(), ownerNodeId(), node.id, host.getPlayerUUID()));
        }
        markDirty();
    }

    /** 构造编辑器实例，绑定到宿主屏幕，初始化节点渲染器、主题色按钮和临时视角。
     *  Construct an editor instance bound to a host screen; initialize node renderer, theme color buttons, and temp view.
     *  @param host 实现 Host 接口的宿主屏幕 / the host screen implementing the Host interface
     *  @param screen 当前 Minecraft Screen 实例 / the current Minecraft Screen instance */
    public GraphEditor(Host host, Screen screen) {
        this.host = host;
        this.renderer = new NodeRenderer(this::c2sX, this::c2sY, screen);
        var mc = net.minecraft.client.Minecraft.getInstance();
        for (int i = 0; i < NodeRenderer._NUM_COLORS; i++) {
            final int idx = i;
            themeButtons[i] = new ColorPickerButton(
                () -> NodeRenderer.stagingColors[idx],
                c -> NodeRenderer.stagingColors[idx] = c,
                colorPicker
            );
        }
        // 临时视角恢复（按方块位置，session 内同方块跨编辑器实例恢复）
        // temporary view restore (keyed by block position, cross-instance within session)
        var bp = host.getBlockPos();
        if (bp != null) {
            float[] saved = tempViewByPos.get(bp);
            if (saved != null) { camX = saved[0]; camY = saved[1]; zoom = saved[2]; }
        }
    }

    /** 设置添加节点菜单的节点类型过滤器，同时更新主图过滤器缓存。
     *  Set the node type filter for the add-node menu, also updates the main filter cache.
     *  @param filter 过滤谓词 / filter predicate */
    public void setNodeFilter(Predicate<NodeType> filter) { this.nodeFilter = filter; this.mainNodeFilter = filter; }

    // ── 坐标转换 (Coordinate transforms) ──
    // 图空间 → 屏幕空间 / graph-space → screen-space
    /** 图空间 X → 屏幕 X / graph-space X → screen-space X */
    public float c2sX(float cx) { Screen s = host.asScreen(); return s.width/2f+(cx+camX)*zoom; }
    /** 图空间 Y → 屏幕 Y / graph-space Y → screen-space Y */
    public float c2sY(float cy) { Screen s = host.asScreen(); return s.height/2f+(cy+camY)*zoom; }
    /** 屏幕 X → 图空间 X / screen-space X → graph-space X */
    public float s2cX(double sx) { Screen s = host.asScreen(); return(float)((sx-s.width/2f)/zoom-camX); }
    /** 屏幕 Y → 图空间 Y / screen-space Y → graph-space Y */
    public float s2cY(double sy) { Screen s = host.asScreen(); return(float)((sy-s.height/2f)/zoom-camY); }

    /** 增加图代数以作废渲染缓存（Phase 2 脏标记框架）。
     *  Bump graph generation to invalidate render caches (Phase 2 dirty flag framework).
     *  Any change that should trigger a re-render calls this. / 任何应触发重渲染的变更都调用此方法。 */
    void markDirty() { getGraph().bumpGeneration(); }

    /** 子图结构变更后，重建父图的输入缓存，使封装节点的外部连线引脚位置跟随子节点变化。
     *  Rebuild parent graph input cache after sub-graph structural changes,
     *  so that external connection pin positions on ENCAPSULATION follow sub-node changes. */
    private void rebuildParentCacheIfInSubGraph() {
        if (isInSubGraph() && host.getGraph() != null) {
            host.getGraph().rebuildInputCache();
        }
    }

    /** Sort nodes by B-layer ascending (lower B = rendered first = behind, higher B = on top).
     *  按 B 层升序排列节点（B 值越小越先渲染/越靠后，B 值越大越靠前）。
     *  @param nodes 待排序节点列表 / list of nodes to sort
     *  @return 按 sortB 升序排列的新列表 / new list sorted by sortB ascending */
    private List<GraphNode> sortNodesByB(List<GraphNode> nodes) {
        return nodes.stream()
            .sorted((a, b) -> Integer.compare(a.sortB, b.sortB))
            .collect(java.util.stream.Collectors.toList());
    }

    /** Find the overlapping node with the largest sortB. The dragged node will be
     *  inserted above it (sortB = max + 1). Returns null if no node overlaps.
     *  查找重叠节点中 sortB 最大的那个。拖拽的节点将插入到它上方（sortB = max + 1）。
     *  无重叠节点时返回 null。
     *  @param dragged 被拖拽的节点 / the node being dragged
     *  @return 下方重叠节点中 sortB 最大的，或 null / the overlapped node with highest sortB, or null */
    private GraphNode findNodeBelow(GraphNode dragged) {
        float w = NodeRenderer.nw(dragged);
        float h = fullNodeHeight(dragged);
        var candidates = spatialIndex.queryRect(dragged.x, dragged.y, w, h);
        GraphNode best = null;
        int bestB = Integer.MIN_VALUE;
        for (var n : candidates) {
            if (n == dragged) continue;
            if (n.sortB <= bestB) continue;
            if (rectsOverlap(dragged, n)) {
                best = n;
                bestB = n.sortB;
            }
        }
        return best;
    }

    /** Full node height including expanded edit panel (for occlusion/AABB calculations).
     *  完整节点高度（含展开编辑面板），用于遮挡/AABB 计算。
     *  @param n 目标节点 / the target node
     *  @return 包含展开区域的完整节点高度 / total height including expanded edit area */
    private float fullNodeHeight(GraphNode n) {
        float h = NodeRenderer.nh(n);
        if (expandedNodeIds.contains(n.id)) {
            h += io.github.y15173334444.create_schematic_compute.blocks.EditPanel
                .calcRenderHeight(n, 1.0f);
        }
        return h;
    }

    /** AABB overlap test between two nodes (graph-space).
     *  两节点 AABB 重叠检测（图空间）。
     *  @param a 节点 A / node A
     *  @param b 节点 B / node B
     *  @return true 如果两个节点的包围盒重叠 / true if the two nodes' bounding boxes overlap */
    private boolean rectsOverlap(GraphNode a, GraphNode b) {
        float aw = NodeRenderer.nw(a), ah = fullNodeHeight(a);
        float bw = NodeRenderer.nw(b), bh = fullNodeHeight(b);
        return rectsOverlap(a.x, a.y, aw, ah, b.x, b.y, bw, bh);
    }
    /** AABB overlap test with raw coordinates (graph-space).
     *  使用原始坐标的 AABB 重叠检测（图空间）。
     *  @return true 如果两个矩形重叠 / true if the two rectangles overlap */
    private static boolean rectsOverlap(float ax, float ay, float aw, float ah,
                                         float bx, float by, float bw, float bh) {
        return ax < bx + bw && ax + aw > bx
            && ay < by + bh && ay + ah > by;
    }

    /** Renormalize all sortB values to [0, N-1] preserving relative order.
     *  将所有 sortB 值重新规范化为 [0, N-1]，保持相对顺序。
     *  Called when sortB values approach Integer.MAX_VALUE to prevent overflow.
     *  当 sortB 值接近 Integer.MAX_VALUE 时调用，防止溢出。
     *  @param graph 目标图 / the target graph */
    private void renormalizeSortB(NodeGraph graph) {
        var sorted = graph.nodes.stream()
            .sorted((a, b) -> Integer.compare(a.sortB, b.sortB))
            .toList();
        for (int i = 0; i < sorted.size(); i++) {
            sorted.get(i).sortB = i;
        }
    }

    // A.B.C occlusion system render layers (higher A = later = on top):
    //  A=0: Grid
    //  A=1: Comment backgrounds (behind connections)
    //  A=2: Connections (bezier curves)
    //  A=3: Node bodies + expanded edit areas (within poses)
    //       Edit controls only when no overlay is on top (avoid bleed-through)
    //  A=4: Overlays (toolbar, hotbar popup, color config, nodes menu, box-select)
    //  A=5: Tooltips / right-click menu
    private boolean expandedInitDone = false;
    private int lastInitGeneration = -1;
    /** 本方块通过 syncBusBands 实际注册过的频道名（用于区分自身和跨方块冲突） (Bus names actually registered by this BE via syncBusBands; distinguishes self from cross-BE conflicts) */
    private final java.util.Set<String> localBusNames = new java.util.HashSet<>();

    // ── 视角书签过渡动画 / View bookmark transition animation ──
    /** 过渡起始相机状态 / transition start camera state */
    private float transFromX, transFromY, transFromZoom;
    /** 过渡目标相机状态 / transition target camera state */
    private float transToX, transToY, transToZoom;
    /** 过渡开始时间戳 / transition start timestamp (ms) */
    private long transStartMs = 0;
    /** 视角过渡持续时间（毫秒）/ camera transition duration in milliseconds */
    private static final long TRANSITION_MS = 200;

    /** 启动视角过渡动画。 / Start a camera transition animation. */
    private void startTransition(float toX, float toY, float toZoom) {
        transFromX = camX; transFromY = camY; transFromZoom = zoom;
        transToX = toX; transToY = toY; transToZoom = toZoom;
        transStartMs = System.currentTimeMillis();
    }

    /** 每帧推进过渡动画（ease-in-out）。 / Advance transition animation per frame (ease-in-out). */
    private void advanceCameraTransition() {
        if (transStartMs == 0) return;
        long elapsed = System.currentTimeMillis() - transStartMs;
        float t = Math.min(1f, elapsed / (float) TRANSITION_MS);
        float e = t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
        camX = lerp(transFromX, transToX, e);
        camY = lerp(transFromY, transToY, e);
        zoom = lerp(transFromZoom, transToZoom, e);
        if (t >= 1f) transStartMs = 0;
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    /** 客户端每 tick 调用（由各 Host Screen 的 containerTick 触发）。
     *  - 推进 DEBUG_PROBE 历史采样
     *  - 推进书签视角过渡动画
     *  - 子图模式下从 subOutputs 读取快照值（修复 #18：封装内 DEBUG 节点不可见）
     *  Client tick (called by each Host Screen's containerTick). */
    public void clientTick() {
        advanceCameraTransition();
        // 必须放在 snap 判空的 early-return 之前：图没运行时（snap 为空）也照样要
        // 把用户敲进去的总线名同步出去。
        // Must sit before the snap null-check early return: bus names must sync even
        // when the graph isn't running (empty snapshot).
        tickDebouncedBusEdits();
        var snap = host.getCachedEvalSnapshot();
        if (snap == null || snap == io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot.EMPTY) return;
        var graph = getGraph();
        boolean inSub = isInSubGraph();
        int encapId = inSub ? encapsulationParent.id : -1;
        for (GraphNode n : graph.nodes) {
            if (n.type != NodeType.DEBUG_PROBE) continue;
            if (n.probeFrozen) continue;
            float v = inSub ? snap.getSub(encapId, n.id, 0) : snap.get(n.id, 0);
            n.probeHistory[n.probeHead] = v;
            n.probeHead = (n.probeHead + 1) % n.probeHistory.length;
            if (n.probeCount < n.probeHistory.length) n.probeCount++;
        }
    }

    /** 服务端 ACK 到达后，发送复制节点的所有数据 op 和连接。
     *  Called after server ACK assigns real IDs to all nodes in a copy batch.
     *  Sends all data ops (params, formula, displayText, comment, image, debug) + connections. */
    void flushCopyGroup(PendingCopyGroup g) {
        for (var dup : g.nodes) {
            int realId = g.tempToReal.get(dup.id);
            if (realId < 0) continue;
            // 参数 / params
            if (dup.params != null) {
                for (int pi = 0; pi < dup.params.length; pi++) {
                    if (dup.params[pi] != 0) host.sendOp(
                        io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(g.gpos, g.oid, realId, pi, dup.params[pi], g.uid));
                }
            }
            // 公式 / formula
            if (dup.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.FORMULA && dup.formula != null && !dup.formula.isEmpty())
                host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setFormula(g.gpos, g.oid, realId, dup.formula, g.uid));
            // 显示文本 / displayText
            if (dup.displayText != null && !dup.displayText.isEmpty()) host.sendOp(
                new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                    io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT, g.gpos, g.oid, realId,
                    0, null, 0f, 0f, 0, 0, 0, 0, 0, 0f, dup.displayText, 0, 0, 0, 0, null, 0, 0, 0,
                    net.minecraft.world.item.ItemStack.EMPTY, 0L, g.uid));
            // 文字颜色 / text color
            if (dup.textColor != 0) host.sendOp(
                io.github.y15173334444.create_schematic_compute.graph.GraphOp.setTextColor(g.gpos, g.oid, realId, dup.textColor, g.uid));
            // 物品栏 / hotbar items
            if (dup.itemParams != null) {
                for (int si = 0; si < dup.itemParams.length; si++) {
                    if (!dup.itemParams[si].isEmpty())
                        host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setHotbarItem(g.gpos, g.oid, realId, si, dup.itemParams[si], g.uid));
                }
            }
            // 信号频段 / signal bands
            if (dup.signalBands != null && !dup.signalBands.isEmpty())
                host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setBands(g.gpos, g.oid, realId, dup.signalBands, g.uid));
            // 注释节点 / comment node
            if (dup.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.COMMENT) {
                host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCommentSize(g.gpos, g.oid, realId, dup.commentWidth, dup.commentHeight, g.uid));
                host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCommentColors(g.gpos, g.oid, realId, dup.commentBgColor, dup.commentBorderColor, dup.commentTextColor, g.uid));
            }
            // 图像像素 / image pixels
            if ((dup.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.IMAGE || dup.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.IMAGE_SEQUENCE) && dup.imagePixels != null)
                host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setImagePixels(g.gpos, g.oid, realId, 0, dup.imagePixels, g.uid));
            // 图像序列剩余帧 / remaining image sequence frames
            if (dup.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.IMAGE_SEQUENCE
                && dup.imageSequenceFrames != null && dup.imageSequenceFrames.size() > 1) {
                for (int fi = 1; fi < dup.imageSequenceFrames.size(); fi++) {
                    int[] frame = dup.imageSequenceFrames.get(fi);
                    if (frame != null)
                        host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setImagePixels(g.gpos, g.oid, realId, fi, frame, g.uid));
                }
            }
            // DEBUG 控制点 / DEBUG control points
            if (dup.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.DEBUG_SIGNAL_GEN && dup.debugCtrlX != null && dup.debugCtrlY != null
                && dup.debugCtrlX.length > 0)
                host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCtrlPoints(g.gpos, g.oid, realId, dup.debugCtrlX, dup.debugCtrlY, g.uid));
            // 显示布局 / display layout (layoutX, layoutY, displayScale, displayRotation, moveScale)
            host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setDisplayLayout(
                g.gpos, g.oid, realId,
                dup.layoutX, dup.layoutY,
                dup.displayScale, dup.displayRotation,
                dup.moveScale, g.uid));
            // Z 序 / z-order (sortB)
            if (dup.sortB != 0) host.sendOp(
                io.github.y15173334444.create_schematic_compute.graph.GraphOp.setZOrder(g.gpos, g.oid, realId, dup.sortB, g.uid));
            // 展开状态 / expand state
            if (dup.expanded) host.sendOp(new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                io.github.y15173334444.create_schematic_compute.graph.OpType.EXPAND_NODE, g.gpos, g.oid, realId, g.uid));
        }
        // 发送内部连接 / send internal connections (with remapped IDs)
        for (int[] c : g.conns) {
            int fromReal = g.tempToReal.getOrDefault(c[0], -1);
            int toReal = g.tempToReal.getOrDefault(c[2], -1);
            if (fromReal >= 0 && toReal >= 0) {
                host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.addConn(g.gpos, g.oid, fromReal, c[1], toReal, c[3], g.uid));
            }
        }
        // 封装节点含子图时，递归发送子图内所有节点/连线/数据
        // For ENCAPSULATION nodes with sub-graphs, recursively send all subGraph content
        for (var dup : g.nodes) {
            if (dup.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.ENCAPSULATION
                && dup.subGraph != null && !dup.subGraph.nodes.isEmpty()) {
                int encapRealId = g.tempToReal.get(dup.id);
                if (encapRealId > 0) sendSubGraphOps(dup.subGraph, encapRealId, g.gpos, g.uid);
            }
        }
    }

    /** 递归发送子图中所有节点的 ADD_NODE + 数据 op + 连线。
     *  新创建的子图（来自复制）为空命名空间，直接用本地 ID 发送 ADD_NODE 是安全的。
     *  Recursively send ADD_NODE + data ops + connections for all nodes in a subGraph.
     *  Safe to use local IDs with ADD_NODE because the subGraph is freshly created (empty namespace). */
    private void sendSubGraphOps(io.github.y15173334444.create_schematic_compute.graph.NodeGraph subGraph,
                                  int ownerNodeId, net.minecraft.core.BlockPos gpos, java.util.UUID uid) {
        // 先发所有节点 / send all nodes first
        for (var sn : subGraph.nodes) {
            host.sendOp(new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                io.github.y15173334444.create_schematic_compute.graph.OpType.ADD_NODE,
                gpos, ownerNodeId, sn.id, sn.id, sn.type, sn.x, sn.y, 0, 0, 0, 0, 0, 0f,
                null, 0, 0, 0, 0, null, 0, 0, 0,
                net.minecraft.world.item.ItemStack.EMPTY, 0L, uid));
            // 节点数据 / node data
            if (sn.params != null) {
                for (int pi = 0; pi < sn.params.length; pi++) {
                    if (sn.params[pi] != 0) host.sendOp(
                        io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(gpos, ownerNodeId, sn.id, pi, sn.params[pi], uid));
                }
            }
            if (sn.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.FORMULA && sn.formula != null && !sn.formula.isEmpty())
                host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setFormula(gpos, ownerNodeId, sn.id, sn.formula, uid));
            if (sn.displayText != null && !sn.displayText.isEmpty()) host.sendOp(
                new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                    io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT, gpos, ownerNodeId, sn.id,
                    0, null, 0f, 0f, 0, 0, 0, 0, 0, 0f, sn.displayText, 0, 0, 0, 0, null, 0, 0, 0,
                    net.minecraft.world.item.ItemStack.EMPTY, 0L, uid));
            if (sn.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.COMMENT) {
                host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCommentSize(gpos, ownerNodeId, sn.id, sn.commentWidth, sn.commentHeight, uid));
                host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCommentColors(gpos, ownerNodeId, sn.id, sn.commentBgColor, sn.commentBorderColor, sn.commentTextColor, uid));
            }
            // 递归处理嵌套封装 / recurse into nested encapsulations
            if (sn.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.ENCAPSULATION
                && sn.subGraph != null && !sn.subGraph.nodes.isEmpty()) {
                sendSubGraphOps(sn.subGraph, sn.id, gpos, uid);
            }
        }
        // 再发所有连线 / then send all connections
        for (var sc : subGraph.connections) {
            host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.addConn(gpos, ownerNodeId, sc.fromId, sc.fromPin, sc.toId, sc.toPin, uid));
        }
    }

    /** 编辑器关闭时调用，按方块位置保存临时视角。 / Called when editor closes, saves temporary view keyed by block position. */
    public void onClose() {
        var bp = host.getBlockPos();
        if (bp != null) tempViewByPos.put(bp, new float[]{camX, camY, zoom});
    }

    /** 渲染编辑器背景（网格、连线、节点、叠加层/UI 等全部内容）。
     *  Render the editor background — grid, connections, nodes, overlays, UI, everything.
     *  <p>
     *  Uses a layered rendering system (A=0 through A=5) to ensure correct occlusion:
     *  A=0 Grid, A=1 Comment backgrounds, A=2 Connections, A=3 Node bodies,
     *  A=4 Overlays (toolbar, menus, etc.), A=5 Tooltips/right-click menu.
     *  使用分层渲染系统（A=0 到 A=5）确保正确的遮挡关系。
     *  @param g GuiGraphics 渲染上下文 / rendering context
     *  @param mx 鼠标 X 坐标（屏幕空间）/ mouse X (screen space)
     *  @param my 鼠标 Y 坐标（屏幕空间）/ mouse Y (screen space) */
    public void renderBg(GuiGraphics g, int mx, int my) {
        advanceCameraTransition(); // 每帧推进视角过渡动画 / advance camera transition per frame
        var graph = getGraph();
        if (lastInitGeneration != graph.graphGeneration) {
            lastInitGeneration = graph.graphGeneration;
            expandedInitDone = false;
        }
        // 首次渲染时从 NBT 恢复展开状态 (Restore expand state from NBT on first render)
        if (!expandedInitDone) {
            for (var n : graph.nodes) {
                if (n.expanded && n.type != NodeType.ENCAPSULATION && shouldOpenPanel(n)) {
                    expandedNodeIds.add(n.id);
                    nodeEditStatesById.put(n.id, createEditState(n));
                }
            }
            expandedInitDone = true;
        }

        // Phase 2: update render generation tracking (used by MonitorScreen cache)
        lastRenderedGen = graph.graphGeneration;
        lastRenderedCamX = camX; lastRenderedCamY = camY; lastRenderedZoom = zoom;
        lastRenderedScreenW = host.asScreen().width; lastRenderedScreenH = host.asScreen().height;

        // ── A=0: Grid ──
        renderer.renderGrid(g, camX, camY, zoom, lastRenderedScreenW, lastRenderedScreenH);

        // Advance remote move lerp (smooth multiplayer drag)
        for (var n : graph.nodes) {
            if (n.remoteLerpT < 1f) {
                n.remoteLerpT = Math.min(1f, n.remoteLerpT + 0.12f);
                float t = n.remoteLerpT * n.remoteLerpT * (3f - 2f * n.remoteLerpT); // smoothstep
                n.x = n.remoteStartX + (n.remoteTargetX - n.remoteStartX) * t;
                n.y = n.remoteStartY + (n.remoteTargetY - n.remoteStartY) * t;
            }
        }

        // Rebuild spatial index once per frame (used by all spatial queries below)
        spatialIndex.build(graph.nodes, expandedNodeIds);

        // Sort nodes by B-layer ascending (lower B = rendered first = behind, higher B = on top)
        var sortedByB = sortNodesByB(graph.nodes);

        // Build soft-lock map: selected or editing by another player (same scope only)
        var lockedNodes = new java.util.HashMap<Integer, String>();
        int myOwner = ownerNodeId();
        for (var rp : remotePresences.values()) {
            if (rp.ownerNodeId() != myOwner) continue; // 不同作用域不显示锁 / skip different scopes
            if (rp.selectedNodeId() > 0) lockedNodes.put(rp.selectedNodeId(), rp.playerName());
            if (rp.editingNodeId() > 0) lockedNodes.put(rp.editingNodeId(), rp.playerName());
            if (rp.selectedNodeIds() != null) {
                for (int id : rp.selectedNodeIds())
                    if (id > 0) lockedNodes.put(id, rp.playerName());
            }
        }

        // ── A=1: Complete COMMENT nodes (bg, border, text) — container mats behind connections ──
        Map<Integer, Boolean> flipflopStates = isInSubGraph()
            ? getSubFlipflopStates()
            : host.getFlipflopStates();
        renderer.renderCommentNodes(g, sortedByB, selectedNodes, selectedNode, expandedNodeIds,
            nodeEditStatesById, camX, camY, zoom, mx, my, flipflopStates, lockedNodes);

        // ── 子图 Back 按钮 ──
        if (isInSubGraph()) {
            int bw = 60, bh = 16;
            int bx = host.asScreen().width - bw - 8, by = 4;
            g.fill(bx - 1, by - 1, bx + bw + 1, by + bh + 1, 0xFF3A3A3A);
            g.fill(bx, by, bx + bw, by + bh, 0xFF2A2822);
            var mc = Minecraft.getInstance();
            String backLabel = "← " + I18n.get("gui.create_schematic_compute.back");
            int tw = mc.font.width(backLabel);
            g.drawString(mc.font, backLabel, bx + (bw - tw) / 2, by + 4, 0xFFCCCCCC);
        }

        // 从全局 BAND_REGISTRY 同步 BUS_IN 的频段（BUS_OUT 自己定义，不同步） (Sync BUS_IN bands from global BAND_REGISTRY; BUS_OUT self-defines, not synced)
        for (var n : graph.nodes) {
            if (n.type != NodeType.BUS_IN) continue;
            if (n.signalName.isEmpty()) continue;
            var gb = io.github.y15173334444.create_schematic_compute.network.SignalBus.getBands(n.signalName);
            if (gb != null && !gb.isEmpty() && !gb.equals(n.signalBands)) {
                // Collect removed band names (pinIds) before replacing
                // 在替换前收集被删除的频段名（pinId）
                var oldBands = n.signalBands != null ? n.signalBands : java.util.Collections.<String>emptyList();
                var newBands = new java.util.ArrayList<>(gb);
                var removed = new java.util.ArrayList<>(oldBands);
                removed.removeAll(newBands);
                for (String removedBand : removed) {
                    graph.connections.removeIf(c ->
                        (c.fromId == n.id && removedBand.equals(c.fromPinId)) ||
                        (c.toId == n.id && removedBand.equals(c.toPinId)));
                }
                n.signalBands = newBands;
                graph.rebuildInputCache();
            // Note: we deliberately do NOT clear BUS_IN bands when BAND_REGISTRY is empty.
            // Registry may be empty transiently (e.g. during recompile after a rename) or
            // when the channel has BUS_IN readers but no BUS_OUT writer — clearing would
            // destroy bands and connections on still-valid BUS_IN nodes.
            // 注意：当 BAND_REGISTRY 为空时，刻意不清除 BUS_IN 频段。
            // 注册表可能短暂为空（如改名触发重编译窗口），或频道仅有 BUS_IN 读取者而无 BUS_OUT——
            // 清除会摧毁仍然有效的 BUS_IN 节点的频段和连线。
            }
        }
        // BUS_IN/OUT 展开面板刷新：比较 band 数量 + 内容是否与 EditState 一致 (BUS_IN/OUT expand panel refresh: compare band count + content against EditState)
        for (var n : graph.nodes) {
            if ((n.type != NodeType.BUS_IN && n.type != NodeType.BUS_OUT)
                || !expandedNodeIds.contains(n.id)) continue;
            var st = nodeEditStatesById.get(n.id);
            if (st == null) continue;
            // 跳过正在编辑的频段输入框（用户正在输入中，不要重建 EditState） (Skip band input boxes being edited to avoid rebuilding EditState while user types)
            boolean editingBand = false;
            for (int bi = 1; bi < st.fields.size(); bi++) {
                if (st.fields.get(bi).isFocused()) { editingBand = true; break; }
            }
            if (editingBand) continue;
            boolean changed = st.fields.size() - 1 != n.bandCount();
            if (!changed && n.bandCount() > 0) {
                for (int bi = 0; bi < n.bandCount(); bi++) {
                    if (!n.signalBands.get(bi).equals(st.fields.get(bi + 1).getValue())) {
                        changed = true; break;
                    }
                }
            }
            if (changed) {
                // 判断是否为纯名称变化（非数量变化） (Determine if this is a name-only change, not a count change)
                boolean nameOnlyChange = st.fields.size() - 1 == n.bandCount() && n.bandCount() > 0;
                nodeEditStatesById.put(n.id, createEditState(n));
                // 纯名称变化时同步到同总线名节点并上传服务器 (On name-only change, sync to same-bus-name nodes and upload to server)
                if (nameOnlyChange && n.type == NodeType.BUS_OUT && !n.busConflict) {
                    syncBusBands(n);
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new io.github.y15173334444.create_schematic_compute.network.BusBandUploadPacket(
                            host.getBlockPos(), n.signalName, n.signalBands));
                }
            }
        }
        // ── Ensure FORMULA scripts are parsed before connection rendering ──
        //    Pin counts must be up-to-date before A=2 so connections are drawn
        //    at the correct Y positions. / 在连线渲染前确保 FORMULA 脚本已解析，
        //    引脚计数必须在 A=2 之前更新，确保连线画在正确的 Y 位置。
        for (var n : graph.nodes) n.ensureScriptParsed();

        // ── A=2: Connections (bezier curves) ──
        renderer.renderConnections(g, graph, camX, camY, zoom);
        if(draggingWire) renderer.renderDraggingWire(g, graph, wireFromNode, wireFromPin, wireEndX, wireEndY, camX, camY, zoom);

        // ── A=3: Regular node bodies (sorted by B ascending, comments excluded — rendered at A=1) ──
        renderer.evalSnapshot = host.getCachedEvalSnapshot();
        if (renderer.evalSnapshot == null) renderer.evalSnapshot = io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot.EMPTY;
        renderer.currentEncapId = isInSubGraph() ? encapsulationParent.id : -1;
        // 构建封装占用者表（主图中哪些封装节点内有玩家在编辑）
        // Build encapsulation occupant map (which encap nodes have players editing inside)
        if (!isInSubGraph() && !remotePresences.isEmpty()) {
            var occ = new java.util.HashMap<Integer, String>();
            for (var rp : remotePresences.values()) {
                int oid = rp.ownerNodeId();
                if (oid <= 0) continue;
                String cur = occ.get(oid);
                if (cur == null) occ.put(oid, rp.playerName());
                else if (!cur.contains(rp.playerName())) occ.put(oid, cur + ", " + rp.playerName());
            }
            renderer.encapOccupants = occ.isEmpty() ? java.util.Collections.emptyMap() : occ;
        } else {
            renderer.encapOccupants = java.util.Collections.emptyMap();
        }
        renderer.renderNodes(g, sortedByB, selectedNodes, selectedNode, expandedNodeIds, nodeEditStatesById,
            camX, camY, zoom, mx, my, flipflopStates, lockedNodes);
        if (!isInSubGraph()) {
            renderer.showBookmarkPanel = showBookmarkPanel;
            renderer.renderButtons(g, true, host.isRunning(), cycleWarning, saveFeedbackUntil, gridSnapEnabled, 0, host.asScreen().width, host.asScreen().height);
            // 导入/导出封装节点按钮（仅蓝图计算机显示） (Import/export encapsulation node buttons, Blueprint computer only)
            if (host instanceof BlueprintScreen) {
                var mc = Minecraft.getInstance();
                int btnY = NodeRenderer.isToolbarBottom() ? host.asScreen().height - 22 : TOP_BAR_H + 2;
                int impX = 254, impW = 72, btnH = 18;
                // 仅选中单个封装节点时显示导出，否则显示导入 (Show export when single encapsulation node selected, otherwise show import)
                boolean hasSingleEncap = selectedNode != null && selectedNode.type == NodeType.ENCAPSULATION && selectedNodes.size() == 1;
                if (hasSingleEncap) {
                    g.fill(impX, btnY, impX + impW, btnY + btnH, 0xFF2A3A1A);
                    g.renderOutline(impX, btnY, impW, btnH, NodeRenderer.CSB());
                    g.renderOutline(impX + 1, btnY + 1, impW - 2, btnH - 2, 0xFF2A2822);
                    g.drawString(mc.font, "§a" + I18n.get("gui.create_schematic_compute.encap_export"), impX + 4, btnY + 4, 0xFFFFFFFF, false);
                } else {
                    g.fill(impX, btnY, impX + impW, btnY + btnH, 0xFF2A2A3A);
                    g.renderOutline(impX, btnY, impW, btnH, NodeRenderer.CSB());
                    g.renderOutline(impX + 1, btnY + 1, impW - 2, btnH - 2, 0xFF2A2822);
                    g.drawString(mc.font, "§b" + I18n.get("gui.create_schematic_compute.encap_import"), impX + 4, btnY + 4, 0xFFFFFFFF, false);
                }
            }
        } else {
            // 封装模式标识 (替换按钮栏) (Encapsulation mode indicator, replaces button bar)
            var mc2 = Minecraft.getInstance();
            int nodeCount = getGraph().nodes.size();
            boolean overLimit = nodeCount > MAX_NODES;
            int barH = overLimit ? 36 : 22;
            g.fill(2, 2, host.asScreen().width - 2, barH, 0xFF3A2A1A);
            String countStr = " (" + nodeCount + "/" + MAX_NODES + ")" + (overLimit ? " §c⚠" : "");
            String modeText = "◈ " + net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.encap_mode") + " ◈" + countStr;
            int mtw = mc2.font.width(modeText);
            g.drawString(mc2.font, modeText, (host.asScreen().width - mtw) / 2, 6, overLimit ? 0xFFFF6666 : 0xFFFFCC88);
            if (overLimit) {
                String warn = net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.encap_node_limit");
                int ww = mc2.font.width(warn);
                g.drawString(mc2.font, warn, (host.asScreen().width - ww) / 2, 22, 0xFFFF4444);
            }
        }
        // 导入/导出反馈文字 (Import/export feedback text)
        if (System.currentTimeMillis() < importFeedbackUntil && !saveFeedbackText.isEmpty()) {
            var mc = Minecraft.getInstance();
            int tw = mc.font.width(saveFeedbackText) + 20;
            int fy = NodeRenderer.isToolbarBottom() ? host.asScreen().height - 60 : 26;
            g.fill(host.asScreen().width / 2 - tw / 2, fy, host.asScreen().width / 2 + tw / 2, fy + 18, 0xCC2A3A2A);
            g.renderOutline(host.asScreen().width / 2 - tw / 2, fy, tw, 18, 0xFF6A8A4A);
            g.drawString(mc.font, saveFeedbackText, host.asScreen().width / 2 - tw / 2 + 10, fy + 4, 0xFFFFFFFF, false);
        }
        // 导出封装节点对话框 (Export encapsulation node dialog)
        if (showExportDialog && exportNameEdit != null && selectedNode != null) {
            var mc = Minecraft.getInstance();
            int w = 280, h = 80;
            int cx = (host.asScreen().width - w) / 2, cy = (host.asScreen().height - h) / 2;
            g.fill(cx, cy, cx + w, cy + h, 0xEE1A1A2A);
            g.renderOutline(cx, cy, w, h, NodeRenderer.CSB());
            g.drawString(mc.font, I18n.get("gui.create_schematic_compute.encap_export"), cx + 8, cy + 6, 0xFFFFCC88, false);
            exportNameEdit.setX(cx + 8);
            exportNameEdit.setY(cy + 26);
            exportNameEdit.setWidth(w - 70);
            exportNameEdit.render(g, 0, 0, 0);
            // 保存按钮 (Save button)
            int sx = cx + w - 60, sy = cy + 24;
            g.fill(sx, sy, sx + 50, sy + 20, 0xFF3A5A2A);
            g.renderOutline(sx, sy, 50, 20, 0xFF6A8A4A);
            g.drawString(mc.font, "§a" + I18n.get("gui.create_schematic_compute.save"), sx + 8, sy + 5, 0xFFFFFFFF, false);
            // 取消按钮 (Cancel button)
            g.fill(cx + 8, cy + 50, cx + 58, cy + 68, 0xFF4A3030);
            g.renderOutline(cx + 8, cy + 50, 50, 18, 0xFF8B5333);
            g.drawString(mc.font, "§c" + I18n.get("gui.create_schematic_compute.cancel"), cx + 12, cy + 53, 0xFFFFFFFF, false);
        }
        // 书签列表面板（右下角，带滚动条） / bookmark list panel (bottom-right, with scrollbar)
        if (showBookmarkPanel) {
            var mc = Minecraft.getInstance();
            var bks = getGraph().bookmarks;
            int panelW = 180;
            int rowH = 16;
            int maxRows = 5;
            int titleH = 16;
            int btnRowH = 18;
            int totalRows = bks.size();
            int visibleRows = Math.min(totalRows, maxRows);
            int panelH = titleH + btnRowH + 6 + Math.max(visibleRows, 1) * rowH + 10;
            int panelX = host.asScreen().width - panelW - 4;
            int panelY = host.asScreen().height - 44 - panelH; // 在 ★ 按钮上方
            // 限制滚动偏移 / clamp scroll offset
            if (bookmarkScrollOff < 0) bookmarkScrollOff = 0;
            if (bookmarkScrollOff > Math.max(0, totalRows - maxRows)) bookmarkScrollOff = Math.max(0, totalRows - maxRows);
            // 面板背景 / panel background
            g.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xEE1A1A2A);
            g.renderOutline(panelX, panelY, panelW, panelH, NodeRenderer.CSB());
            // 标题 / title
            g.drawString(mc.font, I18n.get("gui.create_schematic_compute.bookmark.title"), panelX + 6, panelY + 4, 0xFFFFCC88, false);
            // 操作按钮行 / action button row
            int btnY = panelY + titleH + 2;
            // [+ 保存当前] 按钮
            int addBX = panelX + 4, addBW = 72;
            boolean addHover = mx >= addBX && mx < addBX + addBW && my >= btnY && my < btnY + btnRowH;
            g.fill(addBX, btnY, addBX + addBW, btnY + btnRowH, addHover ? 0xFF3A5A3A : 0xFF3A3A2A);
            g.renderOutline(addBX, btnY, addBW, btnRowH, NodeRenderer.CSB());
            g.drawString(mc.font, "+ " + I18n.get("gui.create_schematic_compute.bookmark.add"), addBX + 4, btnY + 4, 0xFFAAFFAA, false);
            // [↺ 重置] 按钮
            int rstBX = panelX + 78, rstBW = 96;
            boolean rstHover = mx >= rstBX && mx < rstBX + rstBW && my >= btnY && my < btnY + btnRowH;
            g.fill(rstBX, btnY, rstBX + rstBW, btnY + btnRowH, rstHover ? 0xFF3A5A3A : 0xFF3A3A2A);
            g.renderOutline(rstBX, btnY, rstBW, btnRowH, NodeRenderer.CSB());
            g.drawString(mc.font, "↺ " + I18n.get("gui.create_schematic_compute.bookmark.reset_view"), rstBX + 4, btnY + 4, 0xFFCCCCCC, false);
            // 分隔线 / separator
            int sepY = btnY + btnRowH + 2;
            g.fill(panelX + 4, sepY, panelX + panelW - 4, sepY + 1, 0xFF3A3A5E);
            // 书签列表 / bookmark list
            int listTopY = sepY + 3;
            for (int i = 0; i < visibleRows; i++) {
                int idx = i + bookmarkScrollOff;
                if (idx >= totalRows) break;
                var bm = bks.get(idx);
                int ry = listTopY + i * rowH;
                // 行背景（悬停高亮） / row hover highlight
                boolean hover = mx >= panelX && mx < panelX + panelW - 10 && my >= ry && my < ry + rowH;
                if (hover) g.fill(panelX + 2, ry, panelX + panelW - 2, ry + rowH, 0xFF3A3A5E);
                // 序号 + 名称 / index + name
                int nameMaxW = panelW - 48;
                String label = (idx < 9 ? (idx + 1) + ". " : "   ") + bm.name();
                if (mc.font.width(label) > nameMaxW) {
                    String trunc = mc.font.plainSubstrByWidth(label, nameMaxW - 8) + "…";
                    g.drawString(mc.font, trunc, panelX + 6, ry + 4, 0xFFCCCCCC, false);
                } else {
                    g.drawString(mc.font, label, panelX + 6, ry + 4, 0xFFCCCCCC, false);
                }
                // 重命名按钮 ✎ / rename button
                boolean renHover = hover && mx >= panelX + panelW - 58 && mx < panelX + panelW - 44;
                g.drawString(mc.font, renHover ? "§e✎" : "§7✎", panelX + panelW - 58, ry + 4, 0xFFFFCC44, false);
                // 跳转按钮 → / jump button
                boolean jmpHover = hover && mx >= panelX + panelW - 42 && mx < panelX + panelW - 28;
                g.drawString(mc.font, jmpHover ? "§a→" : "§7→", panelX + panelW - 42, ry + 4, 0xFF88FF88, false);
                // 删除按钮 × / delete button
                boolean delHover = hover && mx >= panelX + panelW - 26;
                g.drawString(mc.font, delHover ? "§c×" : "§7×", panelX + panelW - 26, ry + 4, 0xFFFF6666, false);
            }
            // 空列表提示 / empty list hint
            if (totalRows == 0) {
                g.drawString(mc.font, "§7(" + I18n.get("gui.create_schematic_compute.bookmark.empty") + ")", panelX + 6, listTopY + 4, 0xFF888888, false);
            }
            // 拖拽幽灵行 / drag ghost row
            if (draggingBookmarkIdx >= 0 && draggingBookmarkIdx < totalRows) {
                int ghostRowH = rowH + 2;
                int ghostY = Math.max(listTopY, Math.min((int)bookmarkDragY - ghostRowH / 2, listTopY + visibleRows * rowH - ghostRowH));
                g.fill(panelX + 2, ghostY, panelX + panelW - 12, ghostY + ghostRowH, 0xBB3A3A38);
                g.renderOutline(panelX + 2, ghostY, panelW - 14, ghostRowH, 0xFFFFCC44);
                var bm = bks.get(draggingBookmarkIdx);
                g.drawString(mc.font, "↕ " + bm.name(), panelX + 8, ghostY + 3, 0xFFFFFF88, false);
            }
            // 滚动条 / scrollbar
            if (totalRows > maxRows) {
                int sbX = panelX + panelW - 8;
                int sbH = visibleRows * rowH;
                int sbY = listTopY;
                g.fill(sbX, sbY, sbX + 6, sbY + sbH, 0xFF2A2A4E);
                int thumbH = Math.max(10, sbH * maxRows / totalRows);
                int maxOff = Math.max(1, totalRows - maxRows);
                int thumbY = sbY + (sbH - thumbH) * bookmarkScrollOff / maxOff;
                g.fill(sbX, thumbY, sbX + 6, thumbY + thumbH, 0xFF6A6A8E);
            }
        }
        // 书签命名对话框（在面板之后渲染，位于上方）/ bookmark name dialog (rendered after panel, on top)
        if (editingBookmarkName) {
            var mc = Minecraft.getInstance();
            int w = 280, h = 70;
            int cx = (host.asScreen().width - w) / 2, cy = (host.asScreen().height - h) / 2;
            g.fill(cx, cy, cx + w, cy + h, 0xEE1A1A2A);
            g.renderOutline(cx, cy, w, h, NodeRenderer.CSB());
            g.drawString(mc.font, I18n.get("gui.create_schematic_compute.bookmark.name"), cx + 8, cy + 6, 0xFFFFCC88, false);
            g.fill(cx + 8, cy + 26, cx + w - 8, cy + 46, 0xFF000000);
            g.renderOutline(cx + 8, cy + 26, w - 16, 20, 0xFF6A6A6A);
            g.drawString(mc.font, bookmarkNameDraft + "_", cx + 12, cy + 31, 0xFFFFFFFF, false);
            g.drawString(mc.font, "§7Enter §r确认 | §7Esc §r取消", cx + 8, cy + 52, 0xFFAAAAAA, false);
        }
        // 导入封装节点对话框 (Import encapsulation node dialog)
        if (showImportDialog) {
            var mc = Minecraft.getInstance();
            int w = 280, visRows = 8;
            int fileCount = importFiles != null ? importFiles.size() : 0;
            int listH = Math.min(fileCount, visRows) * 18;
            int h = 56 + listH + 30; // 标题 + 列表 + 按钮区 (Title + list + button area)
            int cx = (host.asScreen().width - w) / 2, cy = (host.asScreen().height - h) / 2;
            g.fill(cx, cy, cx + w, cy + h, 0xEE1A1A2A);
            g.renderOutline(cx, cy, w, h, NodeRenderer.CSB());
            g.drawString(mc.font, I18n.get("gui.create_schematic_compute.encap_import"), cx + 8, cy + 6, 0xFFCCCCFF, false);
            if (fileCount == 0) {
                g.drawString(mc.font, "§7" + I18n.get("gui.create_schematic_compute.encap_import_failed"), cx + 8, cy + 30, 0xFFFFFFFF, false);
            } else {
                int maxScroll = Math.max(0, fileCount - visRows);
                if (importScrollOff < 0) importScrollOff = 0;
                if (importScrollOff > maxScroll) importScrollOff = maxScroll;
                int listY = cy + 28;
                int endIdx = Math.min(fileCount, importScrollOff + visRows);
                for (int i = importScrollOff; i < endIdx; i++) {
                    var p = importFiles.get(i);
                    String name = p.getFileName().toString();
                    if (name.endsWith(".nbt")) name = name.substring(0, name.length() - 4);
                    int ry = listY + (i - importScrollOff) * 18;
                    boolean hover = mx >= cx + 4 && mx <= cx + w - 20 && my >= ry && my <= ry + 16;
                    if (hover) g.fill(cx + 4, ry, cx + w - 20, ry + 16, 0xFF3A4A6A);
                    g.drawString(mc.font, (hover ? "§e" : "§7") + name, cx + 8, ry + 3, 0xFFFFFFFF, false);
                }
                // 右侧滚动条 (Right-side scrollbar)
                if (maxScroll > 0) {
                    int sbX = cx + w - 14, sbY = listY, sbH = visRows * 18;
                    g.fill(sbX, sbY, sbX + 8, sbY + sbH, 0xFF2A2822);
                    float thumbTop = sbY + (float) importScrollOff / maxScroll * (sbH - 12);
                    g.fill(sbX + 1, (int) thumbTop, sbX + 7, (int) thumbTop + 12, 0xFF8B7533);
                }
            }
            // 取消按钮 (Cancel)
            int cby = cy + h - 22;
            g.fill(cx + 8, cby, cx + 58, cby + 16, 0xFF4A3030);
            g.renderOutline(cx + 8, cby, 50, 16, 0xFF8B5333);
            g.drawString(mc.font, "§c" + I18n.get("gui.create_schematic_compute.cancel"), cx + 12, cby + 2, 0xFFFFFFFF, false);
        }
        // 热栏弹出（点击频率槽后在节点下方显示） (Hotbar popup, shown below node after clicking frequency slot)
        if (hotbarNode != null) {
            var mc = Minecraft.getInstance();
            if (mc.player != null) {
                float nsx = c2sX(hotbarNode.x), nsy = c2sY(hotbarNode.y);
                float nch = (HH + PH*(hotbarNode.functionalInputs() + hotbarNode.outputs()))*zoom+4;
                var st = hotbarNode != null ? nodeEditStatesById.get(hotbarNode.id) : null;
                int numRows = st != null ? st.fields.size() : 0;
                int editLocalY = (int)(HH + PH*(hotbarNode.functionalInputs() + hotbarNode.outputs()) + 4/zoom);
                int freqLocalY = editLocalY + 4 + numRows * 18;
                float popupY = nsy + nch + (freqLocalY - editLocalY + 20 + 4) * zoom;
                int pw = 196, ph = 36;
                int px = (int)(nsx + NW*zoom/2 - pw/2);
                int py = (int)popupY;
                g.fill(px, py, px+pw, py+ph, 0xFF2A2822);
                g.renderOutline(px, py, pw, ph, NodeRenderer.CSB());
                g.drawString(mc.font, "§6§l" + net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.hotbar.select"), px + 4, py + 2, 0xFFFFFFFF, false);
                for (int i = 0; i < 9; i++) {
                    int hx = px + 4 + i * 20;
                    g.fill(hx, py + 16, hx + 18, py + 34, 0xFF1A1814);
                    g.renderOutline(hx, py + 16, 18, 18, 0xFF5A4D3A);
                    var item = mc.player.getInventory().items.get(i);
                    if (!item.isEmpty()) { com.mojang.blaze3d.systems.RenderSystem.depthMask(false); g.renderItem(item, hx + 1, py + 17); com.mojang.blaze3d.systems.RenderSystem.depthMask(true); }
                }
            }
        }
        // 颜色配置面板 (Color configuration panel)
        if (showColorConfig) renderColorPanel(g, mx, my);
        if(showMenu) { selectedMenuType = renderer.renderAddNodeMenu(g, menuX, menuY, mx, my, nodeFilter); }
        // ── A=5: Tooltips（公式报错报告框等延迟覆盖层——A/B/C 分层的工具提示层，节点与 A=4 覆盖层均无法遮挡）
        // ── A=5: Tooltips (deferred overlay such as the formula error report box — the layered-system
        //     tooltip tier; no node body or A=4 overlay can cover it)
        renderer.flushPendingOverlay(g);
        // Comment color edit popup — fixed left-aligned, vertically centered
        if (editingCommentColorNode != null && commentButtons != null) {
            int pw = 200, ph = 74;
            int px = 8;
            int py = Math.max(4, (host.asScreen().height - ph) / 2);
            g.fill(px, py, px + pw, py + ph, 0xFF2A2822);
            g.renderOutline(px, py, pw, ph, 0xFFD4A017);
            String[] labels = {
                I18n.get("gui.create_schematic_compute.comment.bg_color"),
                I18n.get("gui.create_schematic_compute.comment.border_color"),
                I18n.get("gui.create_schematic_compute.comment.text_color")
            };
            for (int row = 0; row < 3; row++) {
                int ry = py + 4 + row * 22;
                g.drawString(Minecraft.getInstance().font, labels[row], px + 6, ry + 2, 0xFFCCCCCC, false);
                // Color swatch button
                commentButtons[row].setPosition(px + pw - 100, ry);
                commentButtons[row].render(g, mx, my);
            }
        }
        // Color picker popup — renders LAST to stay on top of all other overlays
        if (colorPicker.isVisible()) colorPicker.render(g, mx, my);
        // 顶栏最后渲染 —— 固定在所有覆盖层之上（名称 + 设置）。
        // Top bar renders LAST — it sits above every other overlay (name + settings).
        renderTopBar(g, mx, my);
        // 框选矩形 (Box-select rectangle)
        if (boxSelecting) {
            float x1 = Math.min(boxSX, boxEX), y1 = Math.min(boxSY, boxEY);
            float x2 = Math.max(boxSX, boxEX), y2 = Math.max(boxSY, boxEY);
            g.fill((int)x1, (int)y1, (int)x2, (int)y2, 0x22D4A017);
            g.renderOutline((int)x1, (int)y1, (int)(x2-x1), (int)(y2-y1), 0xFFD4A017);
        }
        // GAMEPAD_BUTTON binding capture: poll gamepad each frame since gamepad buttons don't fire key events
        if (!nodeEditStatesById.isEmpty()) {
            var gamepadNodes = new java.util.ArrayList<io.github.y15173334444.create_schematic_compute.graph.GraphNode>();
            for (var en : getGraph().nodes) {
                var es = nodeEditStatesById.get(en.id);
                if (es != null && es.listeningForKey && en.type == NodeType.GAMEPAD_BUTTON)
                    gamepadNodes.add(en);
            }
            if (!gamepadNodes.isEmpty()) {
                long curBtns = 0;
                var gState = org.lwjgl.glfw.GLFWGamepadState.malloc();
                try {
                    if (org.lwjgl.glfw.GLFW.glfwGetGamepadState(org.lwjgl.glfw.GLFW.GLFW_JOYSTICK_1, gState)) {
                        var btns = gState.buttons();
                        for (int bi = 0; bi < 15 && bi < btns.capacity(); bi++)
                            if (btns.get(bi) == 1) curBtns |= (1L << bi);
                    }
                } finally { gState.free(); }
                long rising = curBtns & ~prevGpadButtons; // edge detect: 0→1
                if (rising != 0) {
                    int bi = Long.numberOfTrailingZeros(rising);
                    for (var en : gamepadNodes) { en.params[0] = bi; }
                    for (var en : gamepadNodes) {
                        var es = nodeEditStatesById.get(en.id);
                        if (es != null) es.listeningForKey = false;
                    }
                }
                prevGpadButtons = curBtns;
            } else {
                prevGpadButtons = 0; // reset when not listening
            }
        }
        // ── P2 Presence ──
        sendPresenceIfNeeded(); // periodic keep-alive even without mouse movement
        renderPresenceOverlay(g);
    }

    /** Render remote cursors and online player list. Called from renderBg + MonitorScreen.displayMode.
     *  渲染远程光标和在线玩家列表。由 renderBg 和 MonitorScreen.displayMode 调用。
     *  <p>
     *  Draws remote player cursors with smoothstep interpolation, remote dragging wires,
     *  and a player list overlay on the right side. Stale presences (>30s) are cleaned up.
     *  使用 smoothstep 插值绘制远程玩家光标、远程拖拽中的连线，以及右侧的玩家列表叠加层。
     *  过期（>30 秒）的在线状态会被清理。
     *  @param g GuiGraphics 渲染上下文 / rendering context */
    public void renderPresenceOverlay(GuiGraphics g) {
        cleanupStalePresences();
        if (remotePresences.isEmpty()) return;
        var mc = Minecraft.getInstance();
        int sw = host.asScreen().width;
        // Render remote dragging wires (same scope only)
        for (var e : remotePresences.entrySet()) {
            var p = e.getValue();
            if (p.wireFromNode() < 0) continue;
            if (p.ownerNodeId() != ownerNodeId()) continue; // 不同作用域不画 / skip different scopes
            var graph = getGraph();
            var fn = graph.findNode(p.wireFromNode());
            if (fn == null) continue;
            int h = p.player().hashCode();
            int color = 0xFF000000 | (((h >> 16) & 0xFF) << 16) | (((h >> 8) & 0xFF) << 8) | (h & 0xFF) | 0xFF000000;
            float fromX = c2sX(fn.x + io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer.nw(fn));
            float fromY = c2sY(fn.y + NodeRenderer.HH + NodeRenderer.PH * (fn.functionalInputs() + p.wireFromPin()) + NodeRenderer.PH / 2f);
            float toX = c2sX(p.wireEndX()), toY = c2sY(p.wireEndY());
            float dx = Math.abs(toX - fromX) * 0.4f;
            float dist = (float)Math.sqrt((toX-fromX)*(toX-fromX)+(toY-fromY)*(toY-fromY));
            int steps = Math.max(10, (int)(dist * 0.15f));
            float px = fromX, py = fromY;
            for (int i = 1; i <= steps; i++) {
                float t = i / (float)steps, inv = 1 - t;
                float nx = inv*inv*inv*fromX + 3*inv*inv*t*(fromX+dx) + 3*inv*t*t*(toX-dx) + t*t*t*toX;
                float ny = inv*inv*inv*fromY + 3*inv*inv*t*fromY + 3*inv*t*t*toY + t*t*t*toY;
                int sdx = (int)nx - (int)px, sdy = (int)ny - (int)py;
                int segLen = Math.max(Math.abs(sdx), Math.abs(sdy));
                if (segLen == 0) g.fill((int)px, (int)py, (int)px + 1, (int)py + 1, color);
                else {
                    int runStart = (int)px, runY = (int)py;
                    for (int j = 1; j <= segLen; j++) {
                        int cx2 = (int)px + sdx * j / segLen;
                        int cy2 = (int)py + sdy * j / segLen;
                        if (cy2 != runY || j == segLen) {
                            int endX = j == segLen ? (int)nx : (int)px + sdx * (j - 1) / segLen;
                            int x1 = Math.min(runStart, endX), x2 = Math.max(runStart, endX);
                            g.fill(x1, runY, x2 + 1, runY + 1, color);
                            runStart = cx2; runY = cy2;
                        }
                    }
                }
                px = nx; py = ny;
            }
        }
        // Render remote cursors (same scope only; skip display-layout presences — the monitor
        // screen renders those on its own display-area overlay)
        // 渲染远端光标（仅同作用域；跳过显示布局的临场数据——由显示器界面自行渲染）
        for (var e : remotePresences.entrySet()) {
            var p = e.getValue();
            if (p.ownerNodeId() != ownerNodeId()) continue; // 不同作用域不显示光标 / skip different scopes
            if (p.mode() == 1) continue; // 显示布局模式的光标由 MonitorScreen 渲染 / display-mode cursors render on MonitorScreen
            var cl = cursorLerp.get(p.player());
            if (cl == null) { cl = new float[]{0,0,0,0,1f}; cursorLerp.put(p.player(), cl); }
            // Smoothstep cursor lerp (same algorithm as node move)
            if (cl[4] < 1f) {
                cl[4] = Math.min(1f, cl[4] + 0.1f);
                float t2 = cl[4] * cl[4] * (3f - 2f * cl[4]);
                cl[0] = cl[0] + (cl[2] - cl[0]) * t2 * 0.5f + (cl[2] - cl[0]) * 0.15f;
                cl[1] = cl[1] + (cl[3] - cl[1]) * t2 * 0.5f + (cl[3] - cl[1]) * 0.15f;
            }
            float sx = cl[0], sy = cl[1]; // render from lerped position
            if (sx < -20 || sx > sw + 20 || sy < -20 || sy > host.asScreen().height + 20) continue;
            int h = p.player().hashCode();
            int color = 0xFF000000 | (((h >> 16) & 0xFF) << 16) | (((h >> 8) & 0xFF) << 8) | (h & 0xFF);
            g.fill((int)sx - 6, (int)sy - 1, (int)sx + 7, (int)sy, color);
            g.fill((int)sx - 1, (int)sy - 6, (int)sx, (int)sy + 7, color);
            g.drawString(mc.font, p.playerName(), (int)sx + 8, (int)sy - 4, color);
        }
        // Online player list — right side, below toolbar, vertical
        var players = new java.util.ArrayList<String>();
        players.add("● " + host.getPlayerName());
        for (var p : remotePresences.values()) players.add(p.playerName());
        int maxW = 0;
        for (var name : players) maxW = Math.max(maxW, mc.font.width(name));
        int lx = sw - maxW - 14, ly = TOP_BAR_H + 24;
        g.fill(lx, ly, sw - 6, ly + 2 + players.size() * 12, 0xAA222222);
        for (int i = 0; i < players.size(); i++) {
            int color = i == 0 ? 0xFFFFFF88 : 0xFFCCCCCC;
            g.drawString(mc.font, players.get(i), lx + 4, ly + 2 + i * 12, color);
        }
    }

    /** 处理鼠标点击事件——节点选择、拖拽、连线、菜单、按钮等所有点击交互。
     *  Handle mouse click — node selection, drag, wiring, menus, buttons, all click interactions.
     *  <p>
     *  This is the main input dispatch: hit-testing, selection, UI panels (bookmarks, export/import,
     *  color config, comment popup), inline edit areas, node creation menu, connection drag, etc.
     *  这是主要的输入分发方法：碰撞检测、选择、UI 面板（书签、导入/导出、颜色配置、注释弹窗）、
     *  内联编辑区、节点创建菜单、连线拖拽等。
     *  @param mx 鼠标 X（屏幕坐标）/ mouse X (screen coords)
     *  @param my 鼠标 Y（屏幕坐标）/ mouse Y (screen coords)
     *  @param btn 鼠标按键（0=左键, 1=右键）/ mouse button (0=left, 1=right)
     *  @return true 如果事件被消费 / true if the event was consumed */
    public boolean mouseClicked(double mx, double my, int btn) {
        resetBatch(); // discard any incomplete batch to prevent undo stack freeze
        var graph = getGraph();
        // ── 顶栏（最上层，先于一切命中检测）──
        //    Top bar (topmost layer — hit-tested before everything else).
        if (topBarNameEdit != null && my < TOP_BAR_H) {
            int sbX = host.asScreen().width - 52;
            if (mx >= sbX && mx <= sbX + 46 && my >= 3 && my <= 19) {
                showSettings = true; // 设置界面在后续步骤接入 / settings dialog lands in a later step
                return true;
            }
            for (var st : nodeEditStatesById.values()) for (var f : st.fields) f.setFocused(false);
            topBarNameEdit.setFocused(true);
            topBarNameEdit.mouseClicked(mx, my, btn);
            return true;
        }
        if (topBarNameEdit != null && topBarNameEdit.isFocused()) topBarNameEdit.setFocused(false);
        // 命名对话框：点击外部取消
        if (editingBookmarkName) {
            int w = 280, h = 70;
            int cx = (host.asScreen().width - w) / 2, cy = (host.asScreen().height - h) / 2;
            if (mx < cx || mx > cx + w || my < cy || my > cy + h) {
                editingBookmarkName = false; editingBookmarkIndex = -1; return true;
            }
        }
        // 书签面板交互（仅在面板显示、无弹窗、无命名对话框时）
        if (showBookmarkPanel && !editingBookmarkName && !showExportDialog && !showImportDialog && !colorPicker.isVisible() && editingCommentColorNode == null) {
            int panelW = 180, rowH = 16, maxRows = 5, titleH = 16, btnRowH = 18;
            var bks = graph.bookmarks;
            int totalRows = bks.size();
            int visibleRows = Math.min(totalRows, maxRows);
            int panelX = host.asScreen().width - panelW - 4;
            int panelY = host.asScreen().height - 44 - (titleH + btnRowH + 6 + Math.max(visibleRows, 1) * rowH + 10) - 4;
            int panelH = titleH + btnRowH + 6 + Math.max(visibleRows, 1) * rowH + 10;
            int listTopY = panelY + titleH + btnRowH + 4;
            // [+ 保存] [↺ 重置] 按钮行
            if (btn == 0 && my >= panelY + titleH && my < listTopY) {
                if (mx >= panelX + 4 && mx < panelX + 4 + 70) {
                    // [+ 保存当前]
                    editingBookmarkName = true;
                    editingBookmarkIndex = -1;
                    bookmarkNameDraft = I18n.get("gui.create_schematic_compute.bookmark.new") + " " + (bks.size() + 1);
                    return true;
                }
                if (mx >= panelX + 78 && mx < panelX + 78 + 96) {
                    // [↺ 重置视角]
                    startTransition(0, 0, 1f);
                    return true;
                }
            }
            if (btn == 0 && mx >= panelX && mx < panelX + panelW && my >= listTopY && my < panelY + panelH) {
                // 滚动条拖拽/点击优先（拦截整个滚动条区域）
                if (totalRows > maxRows && mx >= panelX + panelW - 14) {
                    int sbY = listTopY, sbH = visibleRows * rowH;
                    int thumbH = Math.max(10, sbH * maxRows / totalRows);
                    int maxOff = Math.max(1, totalRows - maxRows);
                    int thumbY = sbY + (sbH - thumbH) * bookmarkScrollOff / maxOff;
                    if (my < thumbY) { bookmarkScrollOff = Math.max(0, bookmarkScrollOff - 3); }  // 点上方→上滚
                    else if (my > thumbY + thumbH) { bookmarkScrollOff = Math.min(maxOff, bookmarkScrollOff + 3); } // 点下方→下滚
                    else { scrollingBookmark = true; scrollDragStartY = (float)my; scrollDragStartOff = bookmarkScrollOff; } // 拖拽thumb
                    return true;
                }
                int ry = (int)((my - listTopY) / rowH);
                if (ry >= 0 && ry < visibleRows) {
                    int idx = ry + bookmarkScrollOff;
                    if (idx >= 0 && idx < totalRows) {
                        if (mx >= panelX + panelW - 26) {
                            bks.remove(idx); graph.bumpGeneration();
                            host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.removeBookmark(
                                host.getBlockPos(), ownerNodeId(), idx, host.getPlayerUUID()));
                        } else if (mx >= panelX + panelW - 58 && mx < panelX + panelW - 44) {
                            editingBookmarkName = true; editingBookmarkIndex = idx;
                            bookmarkNameDraft = bks.get(idx).name();
                        } else if (mx >= panelX + panelW - 42 && mx < panelX + panelW - 28) {
                            startTransition(bks.get(idx).camX(), bks.get(idx).camY(), bks.get(idx).zoom());
                        } else {
                            // 点击名称 → 开始拖拽 / name → start drag (release without move = jump)
                            draggingBookmarkIdx = idx;
                            bookmarkDragY = (float)my;
                        }
                    }
                    return true;
                }
            }
        }
        // DEBUG_SIGNAL_GEN 控制点交互（仅在无弹窗时）
        if (!showExportDialog && !showImportDialog && !colorPicker.isVisible() && editingCommentColorNode == null) {
            if (btn == 0) {
                // 1. 控制点命中 → 开始拖拽
                int[] cpHit = hitControlPoint(mx, my);
                if (cpHit != null) {
                    draggingCtrlNode = cpHit[0];
                    draggingCtrlIdx = cpHit[1];
                    // Save pre-drag control points for undo / 保存拖拽前控制点用于撤销
                    GraphNode pcn = graph.findNode(cpHit[0]);
                    if (pcn != null && pcn.debugCtrlX != null)
                        preDragCtrlStr = encodeCtrlPoints(pcn.debugCtrlX, pcn.debugCtrlY);
                    ctrlPointsChanged = true;
                    lastClickMs = 0;
                    return true;
                }
                // 1.5. x 标记线命中（OUT_INPUT 模式）→ 开始拖拽
                int xmNode = hitXMarker(mx, my);
                if (xmNode >= 0) {
                    draggingXMarkerNode = xmNode;
                    lastClickMs = 0;
                    return true;
                }
                // 2. 双击空白处添加控制点（仅在 XY 图区域内）
                long now = System.currentTimeMillis();
                boolean isDoubleClick = (now - lastClickMs < 300);
                lastClickMs = now;
                if (isDoubleClick) {
                    GraphNode hover = hitNode(mx, my);
                    if (hover != null && hover.type == NodeType.DEBUG_SIGNAL_GEN) {
                        int hsetMode = hover.params.length > 0 ? (int) hover.params[0] : 0;
                        if (hsetMode == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.SET_MANUAL) {
                            // 仅在 XY 图区域内添加控制点 / only add within chart area
                            if (isInChartArea(hover, mx, my)) {
                                addControlPoint(hover, mx, my);
                                return true;
                            }
                        }
                    }
                    // DEBUG_PROBE 双击切换冻结
                    if (hover != null && hover.type == NodeType.DEBUG_PROBE) {
                        hover.probeFrozen = !hover.probeFrozen;
                        return true;
                    }
                }
            }
            if (btn == 1) {
                int[] cpHit = hitControlPoint(mx, my);
                if (cpHit != null) {
                    removeControlPoint(graph.findNode(cpHit[0]), cpHit[1]);
                    return true;
                }
            }
        }
        // ── Comment color edit popup (handled BEFORE picker so buttons can rebind) ──
        // Comment popup: skip if picker is open and click is on it
        if (editingCommentColorNode != null && commentButtons != null && btn == 0
            && !(colorPicker.isVisible() && colorPicker.contains((int)mx, (int)my))) {
            int pw = 200, ph = 74;
            int px = 8;
            int py = Math.max(4, (host.asScreen().height - ph) / 2);
            if (mx < px || mx > px + pw || my < py || my > py + ph) {
                closeCommentColorPopup();
                return true;
            }
            // Click inside → delegate to comment buttons, keep picker persistent
            colorPicker.setPersistent(true);
            for (int ci = 0; ci < 3; ci++) {
                if (commentButtons[ci].mouseClicked(mx, my, btn)) return true;
            }
            return true;
        }
        // ── 导出对话框处理 (Export dialog handling) ──
        if (showExportDialog && btn == 0) {
            int w = 280, h = 80;
            int cx = (host.asScreen().width - w) / 2, cy = (host.asScreen().height - h) / 2;
            // Save 按钮 (Save button)
            if (mx >= cx + w - 60 && mx <= cx + w - 10 && my >= cy + 24 && my <= cy + 44) {
                if (exportNameEdit != null && selectedNode != null) {
                    String name = exportNameEdit.getValue().trim();
                    if (!name.isEmpty()) exportEncapNode(selectedNode, name);
                }
                showExportDialog = false; exportNameEdit = null; return true;
            }
            // Cancel 按钮
            if (mx >= cx + 8 && mx <= cx + 58 && my >= cy + 50 && my <= cy + 68) {
                showExportDialog = false; exportNameEdit = null; return true;
            }
            // 点击对话框外部 → 关闭 (Click outside dialog → close)
            if (mx < cx || mx > cx + w || my < cy || my > cy + h) {
                showExportDialog = false; exportNameEdit = null; return true;
            }
            if (exportNameEdit != null) { exportNameEdit.mouseClicked(mx, my, btn); }
            return true;
        }
        // ── 导入对话框处理 ──
        if (showImportDialog && btn == 0) {
            int w = 280, visRows = 8;
            int fileCount = importFiles != null ? importFiles.size() : 0;
            int listH = Math.min(fileCount, visRows) * 18;
            int h = 56 + listH + 30;
            int cx = (host.asScreen().width - w) / 2, cy = (host.asScreen().height - h) / 2;
            // Cancel 按钮
            int cby = cy + h - 22;
            if (mx >= cx + 8 && mx <= cx + 58 && my >= cby && my <= cby + 16) {
                showImportDialog = false; importFiles = null; return true;
            }
            // 点击对话框外部 (Click outside dialog)
            if (mx < cx || mx > cx + w || my < cy || my > cy + h) {
                showImportDialog = false; importFiles = null; return true;
            }
            // 滚动条拖动 (Scrollbar drag)
            if (fileCount > 0) {
                int listY2 = cy + 28, sbX2 = cx + w - 14;
                int maxScroll2 = Math.max(0, fileCount - visRows);
                if (maxScroll2 > 0) {
                    int sbH2 = visRows * 18;
                    float thumbY2 = listY2 + (float) importScrollOff / maxScroll2 * (sbH2 - 12);
                    if (mx >= sbX2 && mx <= sbX2 + 8 && my >= (int) thumbY2 && my <= (int) thumbY2 + 12) {
                        scrollingImport = true;
                        scrollDragStartY = (float) my;
                        scrollDragStartOff = importScrollOff;
                        return true;
                    }
                }
            }
            // 文件列表点击（留出滚动条区域） (File list click, leaving room for scrollbar)
            if (fileCount > 0) {
                int endIdx = Math.min(fileCount, importScrollOff + visRows);
                for (int i = importScrollOff; i < endIdx; i++) {
                    int ry = cy + 28 + (i - importScrollOff) * 18;
                    if (mx >= cx + 4 && mx <= cx + w - 20 && my >= ry && my <= ry + 16) {
                        importEncapNode(importFiles.get(i));
                        showImportDialog = false; importFiles = null; return true;
                    }
                }
            }
            return true;
        }
        // 失焦提交：enterActions（频段 EditBox 等通过 enterActions 注册的控件） (Focus-lost commit via enterActions for band EditBoxes etc. registered via enterActions)
        boolean committed = false;
        for (var e : enterActions.entrySet()) {
            if (e.getKey().isFocused()) { e.getValue().run(); committed = true; break; }
        }
        if (committed) markDirty();
        if(btn==0){
            // ── 子图 Back 按钮 ──
            if (isInSubGraph()) {
                int bw = 60, bh = 16;
                int bx = host.asScreen().width - bw - 8, by = TOP_BAR_H + 2;
                if (mx >= bx && mx <= bx + bw && my >= by && my <= by + bh) {
                    exitSubGraph(); return true;
                }
            }
            // 工具栏按钮（子图模式下隐藏） (Toolbar buttons, hidden in sub-graph mode)
            if (!isInSubGraph()) {
                int btnY = NodeRenderer.isToolbarBottom() ? host.asScreen().height - 22 : TOP_BAR_H + 2;
                if(mx>=4&&mx<=22&&my>=btnY&&my<=btnY+18){host.asScreen().onClose();return true;}
                if(mx>=26&&mx<=78&&my>=btnY&&my<=btnY+18){recompile(graph);return true;}
                if(mx>=82&&mx<=130&&my>=btnY&&my<=btnY+18){
                    boolean ws=!host.isRunning();
                    if(ws && graph.hasCycles()){cycleWarning=I18n.get("gui.create_schematic_compute.cycle_detected");return true;}
                    cycleWarning=null;
                    host.toggleRunning(ws);
                    return true;
                }
                if(mx>=134&&mx<=192&&my>=btnY&&my<=btnY+18){gridSnapEnabled=!gridSnapEnabled;NodeRenderer.saveGridSnap(gridSnapEnabled);return true;}
                if(mx>=196&&mx<=250&&my>=btnY&&my<=btnY+18){
                    showMenu = false;
                    showColorConfig = !showColorConfig;
                    if (showColorConfig) {
                        NodeRenderer.initStaging();
                        openColorPickerForTheme(0);
                    } else {
                        
                    }
                    return true;
                }
                // 导入/导出封装节点按钮（仅蓝图计算机） (Import/export encapsulation node button, Blueprint computer only)
                if (host instanceof BlueprintScreen && mx >= 254 && mx <= 326 && my >= btnY && my <= btnY + 18) {
                    boolean hasEncapSelected = selectedNode != null && selectedNode.type == NodeType.ENCAPSULATION && selectedNodes.size() == 1;
                    if (hasEncapSelected) {
                        showExportDialog = true;
                        String defName = selectedNode.displayText.isEmpty() ? "encap" : selectedNode.displayText;
                        exportNameEdit = new EditBox(Minecraft.getInstance().font, host.asScreen().width / 2 - 80, host.asScreen().height / 2 - 10, 160, 20, Component.literal(defName));
                        exportNameEdit.setValue(defName);
                        exportNameEdit.setFocused(true);
                    } else {
                        showImportDialog = true;
                        importScrollOff = 0;
                        try {
                            var dir = getExportPath().getParent();
                            if (Files.exists(dir)) {
                                try (var s = Files.list(dir)) {
                                    importFiles = s.filter(p -> p.toString().endsWith(".nbt")).sorted().toList();
                                }
                            } else importFiles = java.util.Collections.emptyList();
                        } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.warn("Failed to list import files: {}", e.getMessage()); importFiles = java.util.Collections.emptyList(); }
                    }
                    return true;
                }
            }
            // 右下角书签按钮（在三角形上方） (Bottom-right bookmark button, above triangle)
            { int w = host.asScreen().width, h = host.asScreen().height;
              if(mx>=w-22&&mx<=w-4&&my>=h-44&&my<=h-26){
                showBookmarkPanel = !showBookmarkPanel;
                bookmarkScrollOff = 0;
                if (showBookmarkPanel) { showColorConfig = false; colorPicker.close(); showExportDialog = false; showImportDialog = false; }
                return true;
              } }
            // 右下角工具栏位置切换按钮（始终可见） (Bottom-right toolbar position toggle, always visible)
            { int w = host.asScreen().width, h = host.asScreen().height;
              if(mx>=w-22&&mx<=w-4&&my>=h-22&&my<=h-4){NodeRenderer.toggleToolbarBottom();return true;} }
        }
        if(showMenu&&btn==0){
            // 菜单滚动条拖拽优先（参考书签UI实现）/ menu scrollbar drag first (matching bookmark UI pattern)
            if (renderer.menuHasScrollbar()) {
                int[] track = renderer.menuScrollbarTrack();
                int[] thumb = renderer.menuScrollbarThumb();
                int maxOff = renderer.menuMaxScrollOff();
                if (mx >= track[0] && mx <= track[0] + track[2] && my >= track[1] && my <= track[1] + track[3]) {
                    if (my < thumb[0]) { renderer.setMenuScrollOff(renderer.menuScrollOff() - 3 * 14); return true; }
                    else if (my > thumb[0] + thumb[1]) { renderer.setMenuScrollOff(renderer.menuScrollOff() + 3 * 14); return true; }
                    else { scrollingMenu = true; menuScrollDragStartY = (float)my; menuScrollDragStartOff = (int)renderer.menuScrollOff(); return true; }
                }
            }
            if(renderer.handleCategoryClick((int)mx, (int)my)) return true;
            if(selectedMenuType!=null){
                if(graph.nodes.size()>=MAX_NODES){
                    cycleWarning=I18n.get("gui.create_schematic_compute.node_limit");
                }else{
                    var added = graph.addNode(selectedMenuType,s2cX(mx),s2cY(my));
                    rebuildParentCacheIfInSubGraph(); // rebuild parent ENCAP pin mapping
                    var addOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.addNodeRequest(
                        host.getBlockPos(), ownerNodeId(), added.id,
                        selectedMenuType, s2cX(mx), s2cY(my), host.getPlayerUUID());
                    host.sendOp(addOp);
                    recordOp(addOp, 0, 0, added.id, null); // oldVal=localId for pre-ACK undo
                }
            }showMenu=false;return true;}
        if(btn==1){
            if (showColorConfig) return true; // 颜色面板打开时禁止操作 (Disable operations while color panel is open)
            menuX=(float)mx; menuY=(float)my; showMenu=true; renderer.resetMenuSearch(); return true;
        }
        // 热栏弹出交互 (Hotbar popup interaction)
        if (hotbarNode != null && btn == 0) {
            var mc2 = Minecraft.getInstance();
            var st = hotbarNode != null ? nodeEditStatesById.get(hotbarNode.id) : null;
            float nsx2 = c2sX(hotbarNode.x), nsy2 = c2sY(hotbarNode.y);
            float nch2 = (HH + PH*(hotbarNode.functionalInputs() + hotbarNode.outputs()))*zoom+4;
            int numRows2 = st != null ? st.fields.size() : 0;
            int editLocalY2 = (int)(HH + PH*(hotbarNode.functionalInputs() + hotbarNode.outputs()) + 4/zoom);
            int freqLocalY2 = editLocalY2 + 4 + numRows2 * 18;
            float popupY2 = nsy2 + nch2 + (freqLocalY2 - editLocalY2 + 20 + 4) * zoom;
            int pw2 = 196, ph2 = 36;
            int px2 = (int)(nsx2 + NW*zoom/2 - pw2/2);
            int py2 = (int)popupY2;
            // 点击热栏面板内部 (Click inside hotbar panel)
            if (mx >= px2 && mx <= px2 + pw2 && my >= py2 && my <= py2 + ph2) {
                int si = (int)((mx - px2 - 4) / 20);
                if (si >= 0 && si < 9 && mc2.player != null && hotbarNode.itemParams != null && st != null
                    && st.freqSlotSelected >= 0 && st.freqSlotSelected < hotbarNode.itemParams.length) {
                    var inv = mc2.player.getInventory().items.get(si);
                    var is = inv.isEmpty() ? ItemStack.EMPTY : inv.copy();
                    if (!inv.isEmpty()) is.setCount(1);
                    // Save old item for undo / 保存旧物品用于撤销
                    var oldItem = hotbarNode.itemParams[st.freqSlotSelected];
                    String oldItemNbt = oldItem.isEmpty() ? "" :
                        oldItem.saveOptional(mc2.level.registryAccess()).toString();
                    hotbarNode.itemParams[st.freqSlotSelected] = is;
                    var hoOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setHotbarItem(
                        host.getBlockPos(), ownerNodeId(), hotbarNode.id, st.freqSlotSelected, is, host.getPlayerUUID());
                    host.sendOp(hoOp); recordOp(hoOp, 0, 0, 0, oldItemNbt);
                }
                hotbarNode = null; // 点击面板内始终关闭 (Always close on click inside panel)
                return true;
            }
            hotbarNode = null; // 点击面板外部 → 关闭 (Click outside panel → close)
        }
        // KEYBOARD 绑定监听中 → 点击任何地方取消绑定（点击绑定区域本身除外，那里由 edit 区处理） (KEYBOARD binding active → click anywhere to cancel, except on the binding area itself handled by edit panel)
        if (btn == 0 && !nodeEditStatesById.isEmpty()) {
            boolean anyListening = false;
            for (var st : nodeEditStatesById.values()) if (st.listeningForKey) { anyListening = true; break; }
            if (anyListening) {
                // 检查是否点击了 KEYBOARD 编辑区域的内联范围 (Check if click is within KEYBOARD's inline edit area)
                // 如果不是，取消所有监听 (If not, cancel all listening)
                for (var en : getGraph().nodes) {
                    if (!expandedNodeIds.contains(en.id)) continue;
                    var st = nodeEditStatesById.get(en.id);
                    if (st == null || !st.listeningForKey) continue;
                    float nsx = c2sX(en.x), nsy = c2sY(en.y);
                    int lmx = (int)((mx - nsx) / zoom), lmy = (int)((my - nsy) / zoom);
                    int editLocalY = (int)(HH + PH*(en.functionalInputs() + en.outputs()) + 4/zoom);
                    int kbLocalY = editLocalY + 4;
                    if (!(lmx >= 4 && lmx <= NW && lmy >= kbLocalY && lmy <= kbLocalY + 18)) {
                        st.listeningForKey = false;
                    }
                }
            }
        }
        // Theme color panel: if picker is open and click is on it, skip panel entirely
        if (showColorConfig && btn == 0 && !(colorPicker.isVisible() && colorPicker.contains((int)mx, (int)my))) {
            var mc = Minecraft.getInstance();
            int colW = 100, pw = colW * 2 + 22, ph = 36 + 8 * 18 + 24;
            int px = 8, py = Math.max(4, (host.asScreen().height - ph) / 2); // left-aligned
            // 点击面板外部 → 关闭 (Click outside panel → close)
            if (mx < px || mx > px + pw || my < py || my > py + ph) { showColorConfig = false; colorPicker.close(); return true;
            }
            // 关闭按钮 (Close button)
            if (mx >= px + pw - 18 && mx <= px + pw - 2 && my >= py + 2 && my <= py + 18) { showColorConfig = false; colorPicker.close(); return true; }
            // Defaults
            if (mx >= px + 8 && mx <= px + 72 && my >= py + ph - 22 && my <= py + ph - 6) {
                NodeRenderer.stagingColors = NodeRenderer.DEFAULT_COLORS.clone();
                return true;
            }
            // Apply
            if (mx >= px + pw - 72 && mx <= px + pw - 8 && my >= py + ph - 22 && my <= py + ph - 6) {
                NodeRenderer.setColors(NodeRenderer.stagingColors.clone());
                NodeRenderer.saveColorConfig();
                showColorConfig = false; colorPicker.close();
                
                return true;
            }
            // Theme color buttons — keep picker persistent in this context
            colorPicker.setPersistent(true);
            for (int i = 0; i < NodeRenderer._NUM_COLORS; i++) {
                if (themeButtons[i].mouseClicked(mx, my, btn)) return true;
            }
            return true;
        }
        // Color picker (after panels — absorbs clicks on picker, closes if outside)
        if (colorPicker.isVisible()) {
            return colorPicker.mouseClicked(mx, my, btn);
        }
        if(btn==0){
            showMenu=false;
            // 预计算 z-order 排序候选（供每个展开节点做遮挡判断） (Pre-compute z-order sorted candidates for occlusion checks on each expanded node)
            var clickCandidates = spatialIndex.queryPoint(s2cX(mx), s2cY(my)).stream()
                .sorted(GraphEditor::compareHitOrder)
                .collect(java.util.stream.Collectors.toList());
            // 内联编辑区交互（局部坐标，与 pose 内渲染一致） (Inline edit-area interaction, local coords matching pose rendering)
            for (var en : getGraph().nodes) {
                if (!expandedNodeIds.contains(en.id)) continue;
                if (isNodeLockedByOther(en.id, ownerNodeId())) continue; // soft lock (same scope only)
                // 逐个检查：是否有更高 z-order 的非 Comment 节点实际遮挡了点击位置 (Check: does a higher-z non-Comment node actually occlude the click?)
                boolean occluded = false;
                for (var n : clickCandidates) {
                    if (n == en) break; // 到达当前节点，上方无遮挡 (Reached current node, no occluder above)
                    if (n.type == NodeType.COMMENT) continue;
                    float sx = c2sX(n.x), sy = c2sY(n.y);
                    float sw = NodeRenderer.nw(n) * zoom;
                    float nh = NodeRenderer.nh(n) * zoom + 4; // 使用 nh() 含图表区域 / use nh() to include chart area
                    if (expandedNodeIds.contains(n.id)) nh += EditPanel.calcRenderHeight(n, zoom) * zoom;
                    if (mx >= sx && mx <= sx + sw && my >= sy && my <= sy + nh) {
                        occluded = true;
                        break;
                    }
                }
                if (occluded) {
                    var st0 = nodeEditStatesById.get(en.id);
                    if (st0 != null) for (var b : st0.fields) b.setFocused(false);
                    continue;
                }
                var st = nodeEditStatesById.get(en.id);
                if (st == null) continue;
                float nsx = c2sX(en.x), nsy = c2sY(en.y);
                int lmx = (int)((mx - nsx) / zoom), lmy = (int)((my - nsy) / zoom);
                int editLocalY = (int)(NodeRenderer.nh(en) + 4/zoom); // 使用 nh() 含图表区域 / use nh() to include chart area
                int numRows = st.fields.size();
                // Frequency slots only exist for REDSTONE_IN/OUT nodes
                if (en.type == NodeType.REDSTONE_IN || en.type == NodeType.REDSTONE_OUT) {
                    int freqLocalY = editLocalY + 8 + numRows * 18;
                    for (int fi = 0; fi < 2; fi++) {
                        int bx = 4 + fi * 24;
                        if (lmx >= bx && lmx <= bx + 20 && lmy >= freqLocalY && lmy <= freqLocalY + 20)
                        {
                            // 切换热栏弹窗时，先复位旧节点的高亮态 (Reset old node's highlight when switching hotbar)
                            if (hotbarNode != null && hotbarNode != en) {
                                var old = nodeEditStatesById.get(hotbarNode.id);
                                if (old != null) old.freqSlotSelected = -1;
                            }
                            st.freqSlotSelected = fi;
                            hotbarNode = (hotbarNode == en) ? null : en;
                            return true;
                        }
                    }
                }
                if (en.type == NodeType.BOOL && en.params.length > 0) {
                    int boolLocalY = editLocalY + 4 + numRows * 18;
                    if (lmx >= 4 && lmx <= NW - 4 && lmy >= boolLocalY && lmy <= boolLocalY + 16)
                    { en.params[0] = en.params[0] > 0.5f ? 0 : 1;
                    var tOp = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.TOGGLE_BOOL,
                        host.getBlockPos(), ownerNodeId(), en.id, host.getPlayerUUID());
                    host.sendOp(tOp); recordOp(tOp, 0, 0, 0, null);
                    return true; }}
                if (en.type == NodeType.MOUSE_JOYSTICK && en.params.length > 0) {
                    // Toggle absolute/incremental mode via TOGGLE_BOOL op (same pipeline as BOOL)
                    int mjLocalY = editLocalY + 4 + numRows * 18;
                    if (lmx >= 4 && lmx <= NW - 4 && lmy >= mjLocalY && lmy <= mjLocalY + 16)
                    { en.params[0] = en.params[0] > 0.5f ? 0 : 1;
                    var tOp = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.TOGGLE_BOOL,
                        host.getBlockPos(), ownerNodeId(), en.id, host.getPlayerUUID());
                    host.sendOp(tOp); recordOp(tOp, 0, 0, 0, null);
                    return true; }
                }
                if (en.type == NodeType.GATE && en.params.length > 0) {
                    int gateLocalY = editLocalY + 4 + numRows * 18;
                    if (lmx >= 4 && lmx <= NW - 4 && lmy >= gateLocalY && lmy <= gateLocalY + 16)
                    { en.params[0] = en.params[0] > 0.5f ? 0 : 1;
                    var tOp = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.TOGGLE_BOOL,
                        host.getBlockPos(), ownerNodeId(), en.id, host.getPlayerUUID());
                    host.sendOp(tOp); recordOp(tOp, 0, 0, 0, null);
                    return true; }
                }
                if (en.type == NodeType.T_FLIPFLOP && en.params.length > 0) {
                    int ffLocalY = editLocalY + 4 + numRows * 18;
                    if (lmx >= 4 && lmx <= NW - 4 && lmy >= ffLocalY && lmy <= ffLocalY + 16)
                    { en.params[0] = en.params[0] > 0.5f ? 0 : 1;
                    var tOp = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.TOGGLE_BOOL,
                        host.getBlockPos(), ownerNodeId(), en.id, host.getPlayerUUID());
                    host.sendOp(tOp); recordOp(tOp, 0, 0, 0, null);
                    return true; }
                }
                if (en.type == NodeType.LATCH && en.params.length > 0) {
                    int latchLocalY = editLocalY + 4 + numRows * 18;
                    if (lmx >= 4 && lmx <= NW - 4 && lmy >= latchLocalY && lmy <= latchLocalY + 16)
                    { en.params[0] = en.params[0] > 0.5f ? 0 : 1;
                    var tOp = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                        io.github.y15173334444.create_schematic_compute.graph.OpType.TOGGLE_BOOL,
                        host.getBlockPos(), ownerNodeId(), en.id, host.getPlayerUUID());
                    host.sendOp(tOp); recordOp(tOp, 0, 0, 0, null);
                    return true; }
                }
                // FORMULA warm 两段式切换（刀5）：摘要行后第一行；求值策略设置、无引脚。
                // 左半=严格冻结(0)、右半=温启动(1)，SET_PARAM 精确设值（信号发生器模式切换同款 op）。
                // FORMULA warm segmented toggle (knife 5): first row after the summary; pinless eval-policy
                // setting. Left = strict freeze (0), right = warm (1) — exact-value SET_PARAM (same op as the
                // signal generator's mode switch).
                if (en.type == NodeType.FORMULA && en.params.length > 0) {
                    int warmLocalY = editLocalY + 4 + 18; // 摘要行(row 0)之后 / after the summary row
                    int warmW = NodeRenderer.nw(en); // FORMULA = WIDE_NW / wide node panel width
                    int gap = 4, btnW = (warmW - 12 - gap) / 2;
                    for (int i = 0; i < 2; i++) {
                        int bx = 4 + i * (btnW + gap);
                        if (lmy >= warmLocalY && lmy <= warmLocalY + 16 && lmx >= bx && lmx <= bx + btnW) {
                            int target = i; // 0=严格冻结 1=温启动 / 0 = strict freeze, 1 = warm
                            if ((en.params[0] > 0.5f ? 1 : 0) != target) {
                                float oldWarm = en.params[0];
                                en.params[0] = target;
                                var wOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                                    host.getBlockPos(), ownerNodeId(), en.id, 0, (float) target, host.getPlayerUUID());
                                host.sendOp(wOp); recordOp(wOp, 0, 0, oldWarm, null);
                            }
                            return true;
                        }
                    }
                }
                // BUS_IN/OUT 频段 +/- 按钮（先提交未保存的 busBox，防止名称丢失） (BUS_IN/OUT band +/- buttons; commit unsaved busBox first to avoid name loss)
                if ((en.type == NodeType.BUS_IN || en.type == NodeType.BUS_OUT) && st.bandAddBtnW > 0) {
                    // 提交当前节点的 busBox（如有未保存的频道名编辑） (Commit current node's busBox if unsaved channel name edits exist)
                    if (st.busBox != null && st.busNode != null
                        && !st.busBox.getValue().equals(st.busNode.signalName))
                        commitBusBox(st);
                }
                if ((en.type == NodeType.BUS_IN || en.type == NodeType.BUS_OUT) && st.bandAddBtnW > 0) {
                    if (lmx >= st.bandAddBtnX && lmx <= st.bandAddBtnX + st.bandAddBtnW
                        && lmy >= st.bandAddBtnY && lmy <= st.bandAddBtnY + st.bandAddBtnH) {
                        // + 按钮：添加新频段，同步同总线名节点 (+ button: add new band, sync same-bus-name nodes)
                        if (en.signalBands == null) en.signalBands = new java.util.ArrayList<>();
                        String name = "band_" + en.signalBands.size();
                        en.signalBands.add(name);
                        en.bandsDirty = true;
                        syncBusBands(en);
                        if (!en.busConflict)
                            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new io.github.y15173334444.create_schematic_compute.network.BusBandUploadPacket(
                                    host.getBlockPos(), en.signalName, en.signalBands));
                        nodeEditStatesById.put(en.id, createEditState(en));
                        return true;
                    }
                    if (lmx >= st.bandRemoveBtnX && lmx <= st.bandRemoveBtnX + st.bandRemoveBtnW
                        && lmy >= st.bandRemoveBtnY && lmy <= st.bandRemoveBtnY + st.bandRemoveBtnH) {
                        if (en.signalBands != null && !en.signalBands.isEmpty()) {
                            int removedPin = en.signalBands.size() - 1;
                            String removedBand = en.signalBands.get(removedPin);
                            // Remove connections by pinId (band name), not by index.
                            // 按 pinId（频段名）而非索引清理连线。
                            graph.connections.removeIf(c ->
                                (c.fromId == en.id && removedBand.equals(c.fromPinId))
                                || (c.toId == en.id && removedBand.equals(c.toPinId)));
                            // Legacy fallback: also remove by index for unmigrated connections
                            graph.connections.removeIf(c ->
                                (c.fromId == en.id && c.fromPin == removedPin && c.fromPinId == null)
                                || (c.toId == en.id && c.toPin == removedPin && c.toPinId == null));
                            graph.rebuildNodeMap();
                            graph.rebuildInputCache();
                            en.signalBands.remove(removedPin);
                            en.bandsDirty = true;
                            syncBusBands(en);
                            if (!en.busConflict)
                                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                    new io.github.y15173334444.create_schematic_compute.network.BusBandUploadPacket(
                                        host.getBlockPos(), en.signalName, en.signalBands));
                            nodeEditStatesById.put(en.id, createEditState(en));
                        }
                        return true;
                    }
                }
                if ((en.type == NodeType.IMAGE || en.type == NodeType.IMAGE_SEQUENCE) && en.params.length > 3) {
                    for (int ti = 0; ti < 2; ti++) {
                        int tgY = editLocalY + 4 + (numRows + ti) * 18;
                        if (lmx >= 4 && lmx <= NW - 4 && lmy >= tgY && lmy <= tgY + 14) {
                            en.params[3 + ti] = en.params[3 + ti] > 0.5f ? 0 : 1;
                            var toggleOp = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                                io.github.y15173334444.create_schematic_compute.graph.OpType.SET_IMAGE_FRAME_TOGGLE,
                                host.getBlockPos(), ownerNodeId(), en.id, 0, null, 0f, 0f,
                                0, 0, 0, 0, 0, 0f, null, 0, 0, 0, 0, null, 0, ti, 0,
                                net.minecraft.world.item.ItemStack.EMPTY, 0L, host.getPlayerUUID());
                            host.sendOp(toggleOp); recordOp(toggleOp, 0, 0, 0, null);
                            return true; }
                    }
                }
                if (en.type == NodeType.KEYBOARD || en.type == NodeType.GAMEPAD_BUTTON) {
                    int kbLocalY = editLocalY + 4;
                    if (EditPanel.handleKeyboardClick(en, st, lmx, lmy - kbLocalY, io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer.nw(en))) return true;
                }
                // DEBUG_SIGNAL_GEN mode toggle buttons
                if (en.type == NodeType.DEBUG_SIGNAL_GEN) {
                    String hit = EditPanel.hitModeToggle(0, editLocalY, NodeRenderer.nw(en), en, lmx, lmy);
                    if (hit != null) {
                        handleModeToggleClick(en, st, hit);
                        return true;
                    }
                }
                // FORMULA multi-line editor: single MultiLineEditBox covers full edit panel height
                int enW = io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer.nw(en);
                // EditBox focus/click
                // FORMULA / COMMENT multi-line editor: MultiLineEditBox covers full edit panel height
                if (en.type == NodeType.FORMULA || en.type == NodeType.COMMENT) {
                    // 刀5:FORMULA 的 MLE 在摘要行 + warm 参数行之后,偏移 = 4 + (1 + 参数行数) * 18
                    // Knife 5: FORMULA's MLE sits below the summary + warm param rows; offset = 4 + (1 + paramRows) * 18
                    int mleRowOff = en.type == NodeType.FORMULA ? 4 + (1 + en.type.editableParamCount()) * 18 : -1;
                    for (int fi = 0; fi < st.fields.size(); fi++) {
                        var b = st.fields.get(fi);
                        // Check suggestion popup first (rendered on top of the MLE)
                        if (b instanceof io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox mleBox) {
                            var popup = mleBox.getSuggestPopup();
                            if (popup.isVisible()) {
                                // popup rendered at C=5.5 in screen space → use screen coords
                                // 候选框在 C=5.5 屏幕空间渲染 → 使用屏幕坐标
                                String accepted = popup.mouseClicked((int)mx, (int)my);
                                if (accepted != null) {
                                    b.setFocused(true);
                                    mleBox.replaceCurrentWordForPopup(accepted);
                                    return true;
                                }
                                // Click outside popup but on MLE: close popup, don't steal focus
                                int mleY2, mleH2;
                                if (en.type == NodeType.COMMENT) {
                                    mleY2 = 6;
                                    mleH2 = Math.round(en.commentHeight) - 12;
                                } else {
                                    mleY2 = editLocalY + mleRowOff;
                                    mleH2 = Math.max(b.getHeight(), 18);
                                }
                                if (lmx >= 0 && lmx <= enW && lmy >= mleY2 && lmy <= mleY2 + mleH2) {
                                    popup.close();
                                    // fall through: let normal MLE handling below focus & position cursor
                                } else {
                                    continue; // click outside both popup and MLE
                                }
                            }
                        }
                        int mleY, mleH;
                        if (en.type == NodeType.COMMENT) {
                            // MLE fills body minus edit button: X=6..w-18, Y=6, H=body-12
                            mleY = 6;
                            mleH = Math.round(en.commentHeight) - 12;
                            enW = Math.round(en.commentWidth) - 28; // leave room for left button
                        } else {
                            mleY = editLocalY + mleRowOff;
                            mleH = Math.max(b.getHeight(), 18);
                        }
                        if (lmx >= 0 && lmx <= enW && lmy >= mleY && lmy <= mleY + mleH) {
                            b.setFocused(true);
                            // MLE coordinates are graph-space; convert mouse to graph-space
                            // MLE 坐标为图空间，将鼠标转换为图空间坐标
                            float gx = (float)((mx - nsx) / zoom), gy = (float)((my - nsy) / zoom);
                            if (b.mouseClicked(gx, gy, 0)) editBoxDragNodeId = en.id;
                            if (!tabHeld && selectedNode != en) {
                                selectedNode = en; selectedNodes.clear(); selectedNodes.add(en);
                            }
                        } else b.setFocused(false);
                    }
                } else if (en.type == NodeType.DEBUG_SIGNAL_GEN) {
                    // EditBox positions match EditPanel.renderAt layout: mode toggles (2 rows) + conditional fields
                    int dsgFieldRow = 2; // mode toggle rows come first
                    int setMode = en.params.length > 0 ? (int) en.params[0] : 0;
                    int outMode = en.params.length > 1 ? (int) en.params[1] : 0;
                    int fieldIdx = 0;
                    // formula field (if SET_FORMULA)
                    if (setMode == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.SET_FORMULA) {
                        if (fieldIdx < st.fields.size()) {
                            var b = st.fields.get(fieldIdx);
                            int fy = editLocalY + 4 + dsgFieldRow * 18;
                            if (lmx >= 0 && lmx <= enW && lmy >= fy && lmy <= fy + 18) {
                                b.setFocused(true); b.mouseClicked(mx, my, 0);
                                if (!tabHeld && selectedNode != en) {
                                    selectedNode = en; selectedNodes.clear(); selectedNodes.add(en);
                                }
                            } else b.setFocused(false);
                            fieldIdx++;
                        }
                        dsgFieldRow++;
                    }
                    // speed (manual+OUT_FREQ), amplitude (manual only)
                    for (int ci = 0; ci < 2; ci++) {
                        boolean visible = switch (ci) {
                            case 0 -> setMode == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.SET_MANUAL
                                && outMode == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.OUT_FREQ;
                            case 1 -> setMode == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.SET_MANUAL;
                            default -> false;
                        };
                        if (!visible) continue;
                        if (fieldIdx < st.fields.size()) {
                            var b = st.fields.get(fieldIdx);
                            int fy = editLocalY + 4 + dsgFieldRow * 18;
                            if (lmx >= 0 && lmx <= enW && lmy >= fy && lmy <= fy + 18) {
                                b.setFocused(true); b.mouseClicked(mx, my, 0);
                                if (!tabHeld && selectedNode != en) {
                                    selectedNode = en; selectedNodes.clear(); selectedNodes.add(en);
                                }
                            } else b.setFocused(false);
                            fieldIdx++;
                        }
                        dsgFieldRow++;
                    }
                    // Unfocus remaining fields
                    while (fieldIdx < st.fields.size()) {
                        st.fields.get(fieldIdx).setFocused(false);
                        fieldIdx++;
                    }
                } else if (en.type != NodeType.COMMENT) {
                    // Color button click for TEXT/DATA nodes
                    if ((en.type == NodeType.TEXT || en.type == NodeType.DATA) && st.colorButton != null) {
                        // Color swatch is rendered after the generic fields, at row = st.fields.size()
                        int colorFieldRow = st.fields.size();
                        int swatchLabelW = Minecraft.getInstance().font.width(
                            net.minecraft.client.resources.language.I18n.get("param.create_schematic_compute.color") + ":") + 6;
                        int swatchX = 4 + swatchLabelW;
                        int swatchY = editLocalY + 4 + colorFieldRow * 18;
                        int swatchSize = 16;
                        if (lmx >= swatchX && lmx <= swatchX + swatchSize
                            && lmy >= swatchY && lmy <= swatchY + swatchSize) {
                            st.colorButton.setPosition(swatchX, swatchY);
                            st.colorButton.mouseClicked(lmx, lmy, 0);
                            return true;
                        }
                    }
                    for (int fi = 0; fi < st.fields.size(); fi++) {
                        var b = st.fields.get(fi);
                        int fy = editLocalY + 4 + fi * 18;
                        if (lmx >= 0 && lmx <= enW && lmy >= fy && lmy <= fy + 18) {
                            b.setFocused(true); b.mouseClicked(mx, my, 0);
                            // 点击编辑区时自动选中所属节点 (auto-select owning node on edit-area click)
                            if (!tabHeld && selectedNode != en) {
                                selectedNode = en; selectedNodes.clear(); selectedNodes.add(en);
                            }
                        }
                        else b.setFocused(false);
                    }
                }
            }
            // TAB+左键 → 连线删除 / 多选 / 框选 (TAB+left-click → connection delete / multi-select / box-select)
            if (tabHeld) {
                var hc = hitConn(mx, my);
                if (hc != null) {
                    graph.removeConnection(hc.fromId, hc.fromPin, hc.toId, hc.toPin);
                    var rcOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.removeConn(
                        host.getBlockPos(), ownerNodeId(), hc.fromId, hc.fromPin, hc.toId, hc.toPin, host.getPlayerUUID());
                    host.sendOp(rcOp); recordOp(rcOp, hc.fromId, hc.fromPin, hc.toId, null);
                    // 删除参数引脚连线后刷新编辑区（恢复输入框） (Refresh edit area after removing param pin connection, restoring input box)
                    var tn = graph.findNode(hc.toId);
                    if (tn != null && hc.toPin >= tn.functionalInputs() && expandedNodeIds.contains(hc.toId)) {
                        nodeEditStatesById.remove(hc.toId);
                        nodeEditStatesById.put(hc.toId, createEditState(tn));
                    }
                    return true;
                }
                var hit = hitNode(mx, my);
                if (hit != null && selectedNodes.contains(hit)) {
                    multiDragging = true; multiClickedNode = hit; multiDragOrigins.clear();
                    multiCenterX = 0; multiCenterY = 0;
                    for (var sn : selectedNodes) { multiCenterX += sn.x; multiCenterY += sn.y; }
                    multiCenterX /= selectedNodes.size(); multiCenterY /= selectedNodes.size();
                    for (var sn : selectedNodes) multiDragOrigins.put(sn, new float[]{sn.x, sn.y});
                    dragOffX = s2cX(mx) - multiCenterX; dragOffY = s2cY(my) - multiCenterY;
                    return true;
                }
                if (hit != null) { selectedNodes.add(hit); selectedNode = hit; return true; }
                boxSelecting = true; boxSX = boxEX = (float)mx; boxSY = boxEY = (float)my;
                return true;
            }
            // ▶/▼ 折叠展开按钮（优先检测，不依赖选中状态） (Expand/collapse button, checked first, independent of selection state)
            var expandHit = hitExpandIndicator(mx, my, graph);
            if (expandHit != null) { toggleExpand(expandHit); return true; }
            // ── Comment node interaction / 注释节点交互 ──
            // Only handle clicks on COMMENT chrome (resize, color dot) or
            // body clicks — spatial-index candidates sorted by compareHitOrder
            // (A=3 nodes first, then A=1 comments by B descending = innermost first)
            var nonCommentHit = hitNode(mx, my);
            boolean hitIsNonComment = nonCommentHit != null && nonCommentHit.type != NodeType.COMMENT;
            var commentCandidates = spatialIndex.queryPoint(s2cX(mx), s2cY(my)).stream()
                .filter(n -> n.type == NodeType.COMMENT)
                .sorted(GraphEditor::compareHitOrder)
                .collect(java.util.stream.Collectors.toList());
            for (var n2 : commentCandidates) {
                float sx2 = c2sX(n2.x), sy2 = c2sY(n2.y);
                float sw2 = n2.commentWidth * zoom, sh2 = n2.commentHeight * zoom;
                if (mx < sx2 || mx > sx2 + sw2 || my < sy2 || my > sy2 + sh2) continue;
                float locX = (float)(mx - sx2) / zoom;
                float locY = (float)(my - sy2) / zoom;
                boolean onResize = locX > n2.commentWidth - 22 && locY > n2.commentHeight - 22;
                boolean onColorDot = locX < 18 && locY < 18;
                // Scrollbar thumb drag — check before resize for better UX
                if (!n2.displayText.isEmpty()) {
                    float headerH2 = Math.max(6f, 12f * zoom);
                    int sbXc = (int) (sx2 + sw2 - 10 * zoom);
                    int sbYc = (int) (sy2 + headerH2 + 4 * zoom);
                    int sbHc = (int) (sh2 - headerH2 - 8 * zoom);
                    int maxTextW2 = Math.max(1, (int) ((sw2 - 26 * zoom) / zoom));
                    int visibleH2 = Math.max(1, (int) ((sh2 - 16 * zoom) / zoom));
                    int maxVis2b = Math.max(1, visibleH2 / 12);
                    int totalWraps2b = countWrappedLines(n2.displayText, maxTextW2);
                    int scrollMax2b = Math.max(0, totalWraps2b - maxVis2b);
                    if (scrollMax2b > 0 && mx >= sbXc && mx <= sbXc + Math.max(2, (int)(6 * zoom))
                        && my >= sbYc && my <= sbYc + sbHc) {
                        float thumbH2b = Math.max(12 * zoom, (float) maxVis2b / totalWraps2b * sbHc);
                        float thumbYc = sbYc + (float) n2.commentScrollOff / scrollMax2b * (sbHc - thumbH2b);
                        if (my >= thumbYc && my <= thumbYc + thumbH2b) {
                            scrollingComment = n2;
                            scrollDragStartY = (float) my;
                            scrollDragStartOff = n2.commentScrollOff;
                            return true;
                        }
                    }
                }
                // Resize handle (bottom-right) — checked after scrollbar
                if (onResize) {
                    resizingComment = n2; resizeStartW = n2.commentWidth; resizeStartH = n2.commentHeight;
                    // Capture contained node positions before resize for undo
                    resizeStartNodePositions.clear();
                    var depthMap2 = new java.util.HashMap<GraphNode, Integer>();
                    collectContainedNodesDepth(n2, depthMap2, 0);
                    for (var cn2 : depthMap2.keySet())
                        resizeStartNodePositions.put(cn2.id, new float[]{cn2.x, cn2.y});
                    return true;
                }
                // Edit button (top-right 14x14) — open 3-color edit panel
                if (onColorDot) {
                    editingCommentColorNode = n2;
                    // Capture old colors for undo (saved per-change in recordOp)
                    // 捕获旧颜色用于撤销（每次变更时在 recordOp 中保存）
                    final int[] oldColors = {
                        n2.commentBgColor, n2.commentBorderColor, n2.commentTextColor
                    };
                    commentButtons = new ColorPickerButton[3];
                    for (int ci = 0; ci < 3; ci++) {
                        final int idx = ci;
                        commentButtons[ci] = new ColorPickerButton(
                            () -> {
                                if (editingCommentColorNode == null) return 0xFF000000;
                                return switch (idx) {
                                    case 0 -> editingCommentColorNode.commentBgColor;
                                    case 1 -> editingCommentColorNode.commentBorderColor;
                                    case 2 -> editingCommentColorNode.commentTextColor;
                                    default -> 0xFF000000;
                                };
                            },
                            c -> {
                                if (editingCommentColorNode == null) return;
                                // Save pre-change color for undo / 保存变更前颜色用于撤销
                                int oldC = switch (idx) {
                                    case 0 -> editingCommentColorNode.commentBgColor;
                                    case 1 -> editingCommentColorNode.commentBorderColor;
                                    case 2 -> editingCommentColorNode.commentTextColor;
                                    default -> 0;
                                };
                                switch (idx) {
                                    case 0 -> editingCommentColorNode.commentBgColor = c;
                                    case 1 -> editingCommentColorNode.commentBorderColor = c;
                                    case 2 -> editingCommentColorNode.commentTextColor = c;
                                }
                                markDirty();
                                var ccOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCommentColors(
                                    host.getBlockPos(), ownerNodeId(), editingCommentColorNode.id,
                                    editingCommentColorNode.commentBgColor,
                                    editingCommentColorNode.commentBorderColor,
                                    editingCommentColorNode.commentTextColor,
                                    host.getPlayerUUID());
                                host.sendOp(ccOp); recordOp(ccOp,
                                    oldColors[0], oldColors[1], oldColors[2], null);
                            },
                            colorPicker
                        );
                    }
                    // Auto-open picker alongside comment popup
                    openColorPickerForComment(0);
                    lastClickNodeId = -1;
                    return true;
                }
                // Body double-click → toggle expand (works regardless of expand state)
                long now2 = System.currentTimeMillis();
                if (n2.id == lastClickNodeId && (now2 - lastClickTimeMs) < 400) {
                    toggleExpand(n2);
                    lastClickNodeId = -1;
                    return true;
                }
                lastClickTimeMs = now2; lastClickNodeId = n2.id;
                // Only drag by header bar; expanded comments stay expanded — absorb click
                if (hitIsNonComment) continue;
                if (isNodeLockedByOther(n2.id, ownerNodeId())) continue; // soft lock (same scope only)
                if (expandedNodeIds.contains(n2.id)) {
                    // Keep this comment focused, don't let click fall through to nodes behind
                    if (selectedNode != n2) {
                        selectedNode = n2; selectedNodes.clear(); selectedNodes.add(n2);
                    }
                    return true;
                }
                // Header bar in local coords: headerH/zoom pixels from the top edge
                float commentHeaderLocal = Math.max(6f / zoom, 12f);
                boolean inCommentHeader = locY >= 0 && locY < commentHeaderLocal;
                if (!inCommentHeader) {
                    // Non-header click → select only, allow panning through
                    if (selectedNode != n2) {
                        selectedNode = n2; selectedNodes.clear(); selectedNodes.add(n2);
                    }
                    panning = true; panLastX = (float) mx; panLastY = (float) my;
                    return true;
                }
                // Header click → drag / select
                if (!tabHeld) {
                    if (selectedNode != n2) { selectedNode = n2; selectedNodes.clear(); selectedNodes.add(n2); }
                } else {
                    if (selectedNodes.contains(n2)) selectedNodes.remove(n2);
                    else selectedNodes.add(n2);
                    selectedNode = selectedNodes.isEmpty() ? null : selectedNodes.iterator().next();
                    if (selectedNodes.isEmpty()) { panning = true; panLastX = (float)mx; panLastY = (float)my; return true; }
                }
                // Start drag with parent-move snapshot + z-order top
                beginUndoBatch(); // batch all contained-node moves + comment move as one undo unit
                preDragSortB = n2.sortB;
                // Pin contained nodes with depth-based B: outermost=lowest B
                // (rendered first=behind), innermost=highest B (rendered last=on top)
                preDragSortBs.clear();
                containedDragNodes.clear();
                containedOrigins.clear();
                var depthMap = new java.util.HashMap<GraphNode, Integer>();
                collectContainedNodesDepth(n2, depthMap, 1);
                containedDragNodes.addAll(depthMap.keySet());
                for (var cn : depthMap.keySet())
                    containedOrigins.put(cn.id, new float[]{cn.x, cn.y});
                int maxDepth = depthMap.values().stream().mapToInt(Integer::intValue).max().orElse(0);
                for (var e : depthMap.entrySet()) {
                    GraphNode cn = e.getKey();
                    int depth = e.getValue();
                    preDragSortBs.put(cn, cn.sortB);  // save original
                    cn.sortB = Integer.MAX_VALUE - (maxDepth - depth + 1);
                }
                // Outermost comment = lowest B (MAX_VALUE - maxDepth - 1)
                n2.sortB = Integer.MAX_VALUE - maxDepth - 2;
                draggingNode = n2; dragOffX = n2.x - s2cX(mx); dragOffY = n2.y - s2cY(my);
                preDragX = n2.x; preDragY = n2.y; // for undo
                return true;
            }
            // BUS_IN edit area output pins — spatial-index aware for occlusion
            var pinCandidates = spatialIndex.queryPoint(s2cX(mx), s2cY(my));
            pinCandidates.sort(GraphEditor::compareHitOrder);
            for (var node : pinCandidates) {
                if (node.type != NodeType.BUS_IN || !expandedNodeIds.contains(node.id) || node.signalBands == null) continue;
                float sx = c2sX(node.x), sy = c2sY(node.y);
                int nw = io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer.nw(node);
                for (int i = 0; i < node.signalBands.size(); i++) {
                    float py = sy + bandPinY(node, i, zoom) * zoom;
                    float pinCenterX = sx + (nw - 12) * zoom; // EditPanel 引脚绘于 px+pw-12=128 (EditPanel pin drawn at px+pw-12)
                    if (Math.abs(mx - pinCenterX) < 8 && Math.abs(my - py) < PH * zoom / 2f + 2) {
                        draggingWire = true; wireFromNode = node.id; wireFromPin = i;
                        wireEndX = s2cX(mx); wireEndY = s2cY(my); return true;
                    }
                }
            }
            // Wire drag — node body output pins, z-order aware
            for (var node : pinCandidates) {
                // 渲染器不绘制 SPEED_CTRL/DEBUG_PROBE 的输出引脚,命中检测必须一致——否则出现可拖连线的隐形引脚
                // The renderer draws no output pins for SPEED_CTRL/DEBUG_PROBE — hit testing must match,
                // otherwise an invisible pin could start a wire drag
                if (node.type == NodeType.SPEED_CTRL || node.type == NodeType.DEBUG_PROBE) continue;
                float sx = c2sX(node.x), sy = c2sY(node.y);
                int nw = io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer.nw(node);
                for (int i = 0; i < node.outputs(); i++) {
                    float py = sy + HH * zoom + PH * zoom * (node.functionalInputs() + i) + PH * zoom / 2f;
                    if (Math.abs(mx - (sx + nw * zoom)) < 8 && Math.abs(my - py) < PH * zoom / 2f + 2) {
                        draggingWire = true; wireFromNode = node.id; wireFromPin = i;
                        wireEndX = s2cX(mx); wireEndY = s2cY(my); return true;
                    }
                }
            }
            // 点击节点（不含 ▶/▼ 区域） (Click node, excluding expand indicator area)
            var hit=hitNode(mx,my);
            if(hit!=null && isNodeLockedByOther(hit.id, ownerNodeId())) hit = null; // soft lock (same scope only)
            if(hit!=null){
                // 仅在非 ▶/▼ 区域允许拖拽 (Only allow drag outside the expand indicator area)
                float sy=c2sY(hit.y);
                boolean inHeader = my>=sy && my<=sy+HH*zoom+4;
                if (inHeader) {
                    preDragSortB = hit.sortB;
                    hit.sortB = Integer.MAX_VALUE;
                    draggingNode=hit; dragOffX=hit.x-s2cX(mx); dragOffY=hit.y-s2cY(my);
                    preDragX = hit.x; preDragY = hit.y; // for undo
                    preDragPositions.clear();
                    for (var sn : selectedNodes) {
                        if (sn != hit) preDragPositions.put(sn.id, new float[]{sn.x, sn.y});
                    }
                }
                if (selectedNode != hit) {
                    selectedNode=hit; selectedNodes.clear(); selectedNodes.add(hit);
                    syncEditStateToSelection(); // 切换选中节点后，清掉旧节点的控件状态（新节点保持聚焦）
                }
                return true;
            }
            // 点击空白区域 → 先提交未保存的 busBox（回车以外的提交途径），再取消选中。
            // 修复：原逻辑 syncEditStateToSelection 先清除所有控件 focus，导致后续
            // busBox.isFocused() 检查失败，点击空白处提交无反应。
            // Click empty area -> commit any unsaved busBox FIRST (the non-Enter commit
            // path), then deselect. Fix: syncEditStateToSelection used to clear every
            // control's focus first, so the later busBox.isFocused() check failed and
            // clicking empty did nothing. Use a snapshot copy because commitBusBox
            // rebuilds the edit state (modifies nodeEditStatesById) during iteration.
            for (var st : java.util.List.copyOf(nodeEditStatesById.values())) {
                // 不依赖 isFocused()：mouseClicked 更早的编辑框处理已 setFocused(false)。
                // 只要 busBox 值 != 当前 signalName（用户改了名未提交），点击空白即提交。
                // Do not rely on isFocused(): earlier edit-box handling in mouseClicked
                // already cleared focus. Commit whenever the box value differs from the
                // node's signalName (the user typed a new name but didn't Enter).
                if (st.busBox != null && st.busNode != null
                    && !st.busBox.getValue().equals(st.busNode.signalName)) {
                    commitBusBox(st);
                }
            }
            selectedNodes.clear(); selectedNode=null;
            syncEditStateToSelection(); // 取消选中后，同步清掉所有节点的控件状态
            panning=true; panLastX=(float)mx; panLastY=(float)my;
        }
        // busBox 失焦提交（在按钮处理之后，避免 createEditState 冲掉频段编辑） (busBox focus-lost commit, after button handling to avoid createEditState overwriting band edits)
        // 注：已提交的 busBox 不再 isFocused，此循环无副作用；保留以防其他路径需要。
        for (var st : nodeEditStatesById.values()) {
            if (st.busBox != null && st.busBox.isFocused() && !st.busBox.getValue().equals(st.busNode.signalName))
                { commitBusBox(st); break; }
        }
        return false;
    }

    /** 提交 busBox 的值到 node.signalName (Commit busBox value to node.signalName) */
    private void commitBusBox(EditState st) {
        if (st == null || st.busBox == null || st.busNode == null) return;
        var node = st.busNode;
        String oldName = node.signalName;
        String t = st.busBox.getValue();
        if (t.equals(oldName)) return;
        node.signalName = t;
        host.sendOp(new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
            io.github.y15173334444.create_schematic_compute.graph.OpType.SET_DISPLAY_TEXT,
            host.getBlockPos(), ownerNodeId(), node.id, 0, null, 0f, 0f,
            0, 0, 0, 0, 0, 0f, t, 0, 0, 0, 0, null, 0, 0, 0,
            net.minecraft.world.item.ItemStack.EMPTY, 0L, host.getPlayerUUID()));
        // 改名：保留自身 band 与连线，只改频道名（回归审计：用户期望改名不丢图）。
        // 仅清理旧频道的全局数据（SIGNALS/BAND_REGISTRY 残留），不动节点的 signalBands/连线。
        // Rename: keep the node's own bands and connections — only change the channel
        // name. Only clear the old channel's GLOBAL data (SIGNALS/BAND_REGISTRY residue),
        // never the node's signalBands or its band connections.
        if (!oldName.isEmpty()) {
            boolean othersUseOldName = false;
            for (var n : getGraph().nodes) {
                if (n != node && (n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT)
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
        reevaluateBusConflicts(getGraph());
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
            for (var n : getGraph().nodes) {
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
        nodeEditStatesById.put(node.id, createEditState(node));
    }

    /** 仅释放旧频道名的全局数据与连线，不折叠编辑区。
     *  供 commitBusBox 改名时使用——改名不应关闭正在编辑的节点。
     *  (Release old channel global data and connections without collapsing the edit panel.
     *   Used by commitBusBox when renaming — renaming should not close the node being edited.) */
    private void releaseOldBusName(GraphNode n, String oldName) {
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
        var g = getGraph();
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
    private void clearBusNode(GraphNode n) {
        releaseOldBusName(n, n.signalName);
        expandedNodeIds.remove(n.id);
        nodeEditStatesById.remove(n.id);
        n.expanded = false;
    }

    /** 失焦所有编辑控件，并关闭不属于当前选中节点的热栏弹窗（选中态与控件态联动复位）。
     *  (Blur all edit controls and close hotbar popups for nodes no longer selected.) */
    private void syncEditStateToSelection() {
        GraphNode sel = selectedNode;
        for (int nid : nodeEditStatesById.keySet()) {
            var st = nodeEditStatesById.get(nid);
            if (st == null) continue;
            boolean keepFocus = (sel != null && nid == sel.id);
            if (!keepFocus) {
                for (var f : st.fields) f.setFocused(false);
                st.freqSlotSelected = -1;
            }
        }
        if (hotbarNode != null && (sel == null || hotbarNode.id != sel.id)) {
            hotbarNode = null;
        }
    }

    /** Re-evaluate busConflict for all BUS_OUT nodes in the given graph.
     *  <p>重新评估给定图中所有 BUS_OUT 节点的 busConflict 状态。</p>
     *  <p>Called both from local edits ({@link #commitBusBox}) and from remote op handling
     *  ({@link #onRemoteOp}) so that all players see conflict warnings in real time.
     *  同时从本地编辑（commitBusBox）和远程操作处理（onRemoteOp）中调用，
     *  使所有玩家都能实时看到冲突警告。</p> */
    private void reevaluateBusConflicts(io.github.y15173334444.create_schematic_compute.graph.NodeGraph graph) {
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
    public void reevaluateBusConflictsForBus(String busName) {
        if (busName == null || busName.isEmpty()) return;
        var graph = getGraph();
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
    private void syncBusBands(GraphNode src) {
        if (src.signalName.isEmpty()) return;
        // 冲突的 BUS_OUT 不上传频段（防止频道夺取） (Conflicting BUS_OUT does not upload bands, preventing channel takeover)
        if (src.type == NodeType.BUS_OUT && src.busConflict) return;
        var bands = src.signalBands;
        if (src.type == NodeType.BUS_OUT) {
            io.github.y15173334444.create_schematic_compute.network.SignalBus.registerBands(src.signalName, bands);
            localBusNames.add(src.signalName);
        }
        var g = getGraph();
        for (var n : getGraph().nodes) {
            if (n != src && (n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT)
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
                var st = nodeEditStatesById.get(n.id);
                if (st != null) nodeEditStatesById.put(n.id, createEditState(n));
            }
        }
    }

    /** 子类可重写定义哪些节点左键打开编辑面板 (Override to define which nodes open edit panel on left-click) */
    protected boolean shouldOpenPanel(GraphNode node) {
        return node.type.paramNames.length > 0 || node.type == NodeType.REDSTONE_IN
            || node.type == NodeType.REDSTONE_OUT || node.type == NodeType.PRIVATE_IN
            || node.type == NodeType.PRIVATE_OUT || node.type == NodeType.BUS_IN || node.type == NodeType.BUS_OUT || node.type == NodeType.PID_POWER
            || node.type == NodeType.FORMULA || node.type == NodeType.KEYBOARD
            || node.type == NodeType.GAMEPAD_BUTTON
            || node.type == NodeType.TEXT || node.type == NodeType.IMAGE
            || node.type == NodeType.IMAGE_SEQUENCE || node.type == NodeType.DATA
            || node.type == NodeType.ENCAPSULATION || node.type == NodeType.ENCAP_INPUT
            || node.type == NodeType.ENCAP_OUTPUT || node.type == NodeType.COMMENT;
    }

    /** 处理鼠标释放——完成拖拽、连线、框选等操作，发送同步 op 并记录撤销。
     *  Handle mouse release — finalize drag, wiring, box-select, send sync ops and record undo.
     *  @param mx 鼠标 X（屏幕坐标）/ mouse X (screen coords)
     *  @param my 鼠标 Y（屏幕坐标）/ mouse Y (screen coords)
     *  @param btn 鼠标按键 / mouse button */
    public void mouseReleased(double mx, double my, int btn) {
        if (colorPicker.isVisible()) { colorPicker.mouseReleased(mx, my, btn); return; }
        var graph = getGraph();
        editBoxDragNodeId = -1;
        // 清除 DEBUG_SIGNAL_GEN 控制点拖拽状态 — 若有变更则同步
        if (draggingCtrlNode >= 0 && ctrlPointsChanged) {
            GraphNode cn = graph.findNode(draggingCtrlNode);
            if (cn != null && cn.debugCtrlX != null && cn.debugCtrlY != null) {
                var cpOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCtrlPoints(
                    host.getBlockPos(), ownerNodeId(), cn.id, cn.debugCtrlX, cn.debugCtrlY, host.getPlayerUUID());
                host.sendOp(cpOp);
                if (!preDragCtrlStr.isEmpty()) {
                    recordOp(cpOp, 0, 0, 0, preDragCtrlStr);
                    preDragCtrlStr = "";
                }
            }
        }
        draggingCtrlNode = -1;
        draggingCtrlIdx = -1;
        ctrlPointsChanged = false;
        // 清除 DEBUG_SIGNAL_GEN x 标记拖拽状态 — 同步 inputX 参数
        if (draggingXMarkerNode >= 0) {
            GraphNode xn = graph.findNode(draggingXMarkerNode);
            if (xn != null && xn.params.length > 4) {
                host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                    host.getBlockPos(), ownerNodeId(), xn.id, 4, xn.params[4], host.getPlayerUUID()));
            }
        }
        draggingXMarkerNode = -1;
        // Comment resize complete
        if (resizingComment != null) {
            if (Math.abs(resizingComment.commentWidth - resizeStartW) > 1
                || Math.abs(resizingComment.commentHeight - resizeStartH) > 1) {
                beginUndoBatch();
                var csOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCommentSize(
                    host.getBlockPos(), ownerNodeId(), resizingComment.id,
                    resizingComment.commentWidth, resizingComment.commentHeight, host.getPlayerUUID());
                host.sendOp(csOp); recordOp(csOp, resizeStartW, resizeStartH, 0, null);
                // Sync positions of nodes that were pushed/contained by the resize
                var pushed = new java.util.HashMap<GraphNode, Integer>();
                collectContainedNodesDepth(resizingComment, pushed, 0);
                for (var cn : pushed.keySet()) {
                    var mnOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.moveNode(
                        host.getBlockPos(), ownerNodeId(), cn.id, cn.x, cn.y, host.getPlayerUUID());
                    host.sendOp(mnOp);
                    float[] old = resizeStartNodePositions.get(cn.id);
                    if (old != null) recordOp(mnOp, old[0], old[1], 0, null);
                }
                resizeStartNodePositions.clear();
                endUndoBatch();
            }
            resizingComment = null;
            return;
        }
        // Scrollbar drag release
        if (scrollingMenu) { scrollingMenu = false; return; }
        if (scrollingBookmark) { scrollingBookmark = false; return; }
        if (draggingBookmarkIdx >= 0) {
            var bks = getGraph().bookmarks;
            if (draggingBookmarkIdx < bks.size() && showBookmarkPanel) {
                int panelW = 180, rowH = 16, maxRows = 5, titleH = 16, btnRowH = 18;
                int panelY = host.asScreen().height - 44 - (titleH + btnRowH + 6 + Math.max(Math.min(bks.size(), maxRows), 1) * rowH + 10) - 4;
                int listTopY = panelY + titleH + btnRowH + 4;
                int toRow = (int)((my - listTopY) / rowH);
                int toIdx = toRow + bookmarkScrollOff;
                if (toIdx >= 0 && toIdx < bks.size() && toIdx != draggingBookmarkIdx) {
                    // 移动书签 / move bookmark
                    var bm = bks.remove(draggingBookmarkIdx);
                    bks.add(toIdx, bm);
                    getGraph().bumpGeneration();
                    host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.moveBookmark(
                        host.getBlockPos(), ownerNodeId(), draggingBookmarkIdx, toIdx, host.getPlayerUUID()));
                } else {
                    // 未移动 → 跳转 / not moved → jump
                    var bm = bks.get(draggingBookmarkIdx);
                    startTransition(bm.camX(), bm.camY(), bm.zoom());
                }
            }
            draggingBookmarkIdx = -1;
            return;
        }
        if (scrollingComment != null || scrollingImport) {
            scrollingComment = null;
            scrollingImport = false;
            return;
        }
        if(btn==0&&multiDragging){
            multiDragging = false; markDirty();
            // If nodes actually moved, send MOVE ops and record undo for all dragged nodes
            // 如果节点确实移动了，为所有拖拽节点发送 MOVE op 并记录撤销
            boolean anyMoved = false;
            for (var sn : selectedNodes) {
                float[] orig = multiDragOrigins.get(sn);
                if (orig != null && (Math.abs(sn.x - orig[0]) >= 2 || Math.abs(sn.y - orig[1]) >= 2)) {
                    anyMoved = true; break;
                }
            }
            if (anyMoved) {
                beginUndoBatch();
                for (var sn : selectedNodes) {
                    float[] orig = multiDragOrigins.get(sn);
                    if (orig == null) continue;
                    var mop = io.github.y15173334444.create_schematic_compute.graph.GraphOp.moveNode(
                        host.getBlockPos(), ownerNodeId(), sn.id, sn.x, sn.y, host.getPlayerUUID());
                    host.sendOp(mop);
                    recordOp(mop, orig[0], orig[1], 0, null);
                }
                endUndoBatch();
            } else if (multiClickedNode != null) {
                // Barely moved → deselect / 微移 → 去选
                float[] orig = multiDragOrigins.get(multiClickedNode);
                if (orig != null && Math.abs(multiClickedNode.x - orig[0]) < 2
                    && Math.abs(multiClickedNode.y - orig[1]) < 2) {
                    selectedNodes.remove(multiClickedNode);
                    selectedNode = selectedNodes.isEmpty() ? null : selectedNodes.iterator().next();
                }
            }
            multiClickedNode = null;
            multiDragOrigins.clear();
            return;
        }
        if(btn==0&&boxSelecting){
            boxSelecting=false;
            if (!tabHeld) selectedNodes.clear();
            float x1 = Math.min(boxSX, boxEX), x2 = Math.max(boxSX, boxEX);
            float y1 = Math.min(boxSY, boxEY), y2 = Math.max(boxSY, boxEY);
            for(var n : graph.nodes) {
                float nx = c2sX(n.x), ny = c2sY(n.y);
                float nw = NW*zoom, nh = (HH+PH*(n.functionalInputs() + n.outputs()))*zoom+4;
                if(nx < x2 && nx+nw > x1 && ny < y2 && ny+nh > y1) {
                    // TAB按住时框选切换选中状态 (TAB held: box-select toggles selection)
                    if (tabHeld && selectedNodes.contains(n)) selectedNodes.remove(n);
                    else selectedNodes.add(n);
                }
            }
            if(!selectedNodes.isEmpty()) selectedNode = selectedNodes.iterator().next();
            else selectedNode = null;
            syncEditStateToSelection(); // 框选结束后同步控件状态，清掉未选中节点的残留聚焦/高亮
            return;
        }
        if(btn==0&&draggingWire){
            // 找最近的输入引脚（只连一个，避免多个引脚全连上） (Find nearest input pin; connect only one to avoid all pins connecting)
            int bestNodeId=-1, bestPin=-1;
            float bestDist=Float.MAX_VALUE;
            float xTol=20;
            // 第一阶段：节点主体上的功能引脚 (Phase 1: functional pins on node bodies)
            for(var node:graph.nodes){
                float sx=c2sX(node.x), sy=c2sY(node.y);
                for(int i=0;i<node.functionalInputs();i++){
                    float py=sy+HH*zoom+PH*zoom*i+PH*zoom/2f;
                    float dx=(float)Math.abs(mx-sx), dy=(float)Math.abs(my-py);
                    if(dx<xTol&&dy<PH*zoom/2f+2&&wireFromNode!=node.id){
                        float dist=dx+dy;
                        if(dist<bestDist){bestDist=dist;bestNodeId=node.id;bestPin=i;}
                    }
                }
            }
            // 第二阶段：编辑区内的参数引脚（展开的节点） (Phase 2: param pins in edit area of expanded nodes)
            for (int nid : expandedNodeIds) {
                var n = graph.findNode(nid);
                if (n == null || n.type.editableParamCount() == 0) continue;
                var st = nodeEditStatesById.get(nid);
                if (st == null) continue;
                float sx = c2sX(n.x), sy = c2sY(n.y);
                float editBaseY = sy + (HH + PH*(n.functionalInputs() + n.outputs()))*zoom + 4;
                for (int fi = 0; fi < st.fields.size() && fi < st.fieldParamIndices.size(); fi++) {
                    int pinIdx = n.functionalInputs() + st.fieldParamIndices.get(fi);
                    if (getGraph().hasInputConnection(nid, pinIdx)) continue;
                    float py = editBaseY + (12 + fi * 18)*zoom;
                    float px = sx + 10*zoom;
                    float dx = (float)Math.abs(mx - px), dy = (float)Math.abs(my - py);
                    if (dx < 16*zoom && dy < 10*zoom && wireFromNode != nid) {
                        float dist = dx + dy;
                        if (dist < bestDist) { bestDist = dist; bestNodeId = nid; bestPin = pinIdx; }
                    }
                }
            }
            // BUS_OUT 编辑区输入引脚 (BUS_OUT edit-area input pins)
            if(bestNodeId<0){for(int nid:expandedNodeIds){var n=graph.findNode(nid);if(n==null||n.type!=NodeType.BUS_OUT||n.signalBands==null)continue;float sx=c2sX(n.x),sy2=c2sY(n.y);for(int bi=0;bi<n.signalBands.size();bi++){float py2=sy2+bandPinY(n,bi,zoom)*zoom;float px2=sx+10*zoom;float dx2=(float)Math.abs(mx-px2),dy2=(float)Math.abs(my-py2);if(dx2<16*zoom&&dy2<10*zoom&&wireFromNode!=nid){float dist2=dx2+dy2;if(dist2<bestDist){bestDist=dist2;bestNodeId=nid;bestPin=bi;}}}}}
            if(bestNodeId>=0){
                graph.addConnection(wireFromNode,wireFromPin,bestNodeId,bestPin);
                var connOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.addConn(
                    host.getBlockPos(), ownerNodeId(), wireFromNode, wireFromPin, bestNodeId, bestPin, host.getPlayerUUID());
                host.sendOp(connOp);
                recordOp(connOp, 0, 0, 0, null);
                // 参数引脚连线后刷新编辑区（隐藏对应输入框） (Refresh edit area after param pin connection, hiding the corresponding input box)
                var targetNode = graph.findNode(bestNodeId);
                if (targetNode != null && bestPin >= targetNode.type.inputs) {
                    nodeEditStatesById.remove(bestNodeId);
                    var newSt = createEditState(targetNode);
                    nodeEditStatesById.put(bestNodeId, newSt);
                }
            }
            draggingWire=false;
        }
        if(btn==0&&draggingNode!=null){
            // Drop-insert: find max sortB among all overlapping nodes and slot above them
            GraphNode below = findNodeBelow(draggingNode);
            if (below != null) {
                draggingNode.sortB = below.sortB + 1;
            } else {
                draggingNode.sortB = 0;
            }
            if (draggingNode.sortB >= Integer.MAX_VALUE - 100) {
                renormalizeSortB(getGraph());
            }
            // Restore contained nodes' sortB, ensuring they stay above the outer
            // comment (outer must have the lowest B so nested renders on top)
            for (var e : preDragSortBs.entrySet()) e.getKey().sortB = e.getValue();
            if (!preDragSortBs.isEmpty()) {
                int outerB = draggingNode.sortB;
                // Find the minimum sortB among contained — if any are <= outerB,
                // shift them all up so outer remains the lowest
                int minContained = Integer.MAX_VALUE;
                for (int v : preDragSortBs.values())
                    if (v < minContained) minContained = v;
                if (minContained <= outerB) {
                    int shift = outerB - minContained + 1;
                    for (var e : preDragSortBs.entrySet())
                        e.getKey().sortB = e.getValue() + shift;
                }
            }
            // Send MOVE ops for comment-contained nodes (moved locally by moveContainedNodes)
            // + record for undo so Ctrl+Z moves them back together with the comment
            // 发送框内节点的 MOVE op + 记录用于撤销，使 Ctrl+Z 同时回退内部节点
            if (draggingNode.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.COMMENT) {
                for (var cn : preDragSortBs.keySet()) {
                    var op = io.github.y15173334444.create_schematic_compute.graph.GraphOp.moveNode(
                        host.getBlockPos(), ownerNodeId(), cn.id, cn.x, cn.y, host.getPlayerUUID());
                    host.sendOp(op);
                    float[] orig = containedOrigins.get(cn.id);
                    if (orig != null) recordOp(op, orig[0], orig[1], 0, null);
                }
            }
            containedOrigins.clear();
            // Send final MOVE ops for pushed-aside nodes (sync only, no undo).
            // Pushed nodes stay at their new positions on Ctrl+Z — this is by design:
            // if a pushed node was soft-locked by another player, undoing it would be confusing.
            // 发送被撞开节点的最终 MOVE op（仅同步，不入撤销栈）。
            // 被撞开节点不随 Ctrl+Z 归位 —— 设计如此：若被其他玩家软锁，撤回会令人困惑。
            if (draggingNode.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.COMMENT) {
                for (var pn : pushedDragNodes) {
                    host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.moveNode(
                        host.getBlockPos(), ownerNodeId(), pn.id, pn.x, pn.y, host.getPlayerUUID()));
                }
            }
            pushedDragNodes.clear();
            pushOrigins.clear();
            containedOrigins.clear();
            preDragSortBs.clear();
            markDirty();
            // Sync Z-order to other editors
            host.sendOp(new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                io.github.y15173334444.create_schematic_compute.graph.OpType.SET_ZORDER,
                host.getBlockPos(), ownerNodeId(), draggingNode.id, 0, null, 0f, 0f,
                0, 0, 0, 0, 0, 0f, null, 0, 0, 0, draggingNode.sortB, null, 0, 0, 0,
                net.minecraft.world.item.ItemStack.EMPTY, 0L, host.getPlayerUUID()));
            // Send MOVE op to server (collaboration)
            var moved = draggingNode;
            var moveOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.moveNode(
                host.getBlockPos(), ownerNodeId(), moved.id, moved.x, moved.y, host.getPlayerUUID());
            host.sendOp(moveOp);
            recordOp(moveOp, preDragX, preDragY, 0, null);
            if (selectedNodes.size() > 1) {
                for (var sn : selectedNodes) {
                    if (sn != moved) {
                        var mop = io.github.y15173334444.create_schematic_compute.graph.GraphOp.moveNode(
                            host.getBlockPos(), ownerNodeId(), sn.id, sn.x, sn.y, host.getPlayerUUID());
                        host.sendOp(mop);
                        var rec = preDragPositions.get(sn.id);
                        float oldX = rec != null ? rec[0] : preDragX;
                        float oldY = rec != null ? rec[1] : preDragY;
                        recordOp(mop, oldX, oldY, 0, null);
                    }
                }
            }
            if (moved.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.COMMENT)
                endUndoBatch(); // close the batch started at drag begin
            rebuildParentCacheIfInSubGraph(); // rebuild parent ENCAP pin mapping after drag
            draggingNode=null;
        }if(btn==0&&panning)panning=false;
    }

    // ── DEBUG_SIGNAL_GEN 控制点辅助方法 ──
    // Control point helper methods for DEBUG_SIGNAL_GEN

    /** 获取节点的 Y 轴映射参数。返回 {minV, scale, chartY, chartH}。
     *  Get Y-axis mapping params for a node. Returns {minV, scale, chartY, chartH}.
     *  Screen-Y → value: v = minV + (chartY + chartH - screenY) / scale */
    private float[] yScale(GraphNode n) {
        float bodyH = NodeRenderer.HH + NodeRenderer.PH * (n.functionalInputs() + n.outputs());
        int chartY = (int) bodyH, chartH = 80;
        int setMode = n.params.length > 0 ? (int) n.params[0] : 0;
        float[] vr = io.github.y15173334444.create_schematic_compute.graph.DebugSignals.computeVisibleRange(
            setMode, n.debugCtrlX, n.debugCtrlY, n.formula, n.debugFormulaRpn);
        return new float[]{vr[0], chartH / vr[2], chartY, chartH};
    }

    /** 将屏幕 Y 坐标转为曲线值。 / Convert screen Y to curve value. */
    private float screenYToValue(float[] ys, float screenY) {
        return ys[0] + (ys[2] + ys[3] - screenY) / ys[1];
    }

    /** 将曲线值转为屏幕 Y 坐标。 / Convert curve value to screen Y. */
    private float valueToScreenY(float[] ys, float v) {
        return ys[2] + ys[3] - (v - ys[0]) * ys[1];
    }

    /** 检测鼠标是否命中控制点。返回 [nodeId, ctrlIdx] 或 null。 */
    private int[] hitControlPoint(double mx, double my) {
        for (GraphNode n : getGraph().nodes) {
            if (n.type != NodeType.DEBUG_SIGNAL_GEN) continue;
            int setMode = n.params.length > 0 ? (int) n.params[0] : 0;
            if (setMode != io.github.y15173334444.create_schematic_compute.graph.DebugSignals.SET_MANUAL || n.debugCtrlX == null) continue;
            float sx = c2sX(n.x), sy = c2sY(n.y);
            int nodeW = NodeRenderer.WIDE_NW;
            float[] ys = yScale(n);
            int chartX = 2, chartW = nodeW - 4;
            for (int i = 0; i < n.debugCtrlX.length; i++) {
                float cpx = sx + (chartX + n.debugCtrlX[i] * chartW) * zoom;
                float cpy = sy + valueToScreenY(ys, n.debugCtrlY[i]) * zoom;
                if (Math.abs(mx - cpx) <= 5 * zoom && Math.abs(my - cpy) <= 5 * zoom) {
                    return new int[]{n.id, i};
                }
            }
        }
        return null;
    }

    /** 检测鼠标是否命中 x 标记线（仅 OUT_INPUT 模式可拖拽）。返回 nodeId 或 -1。 */
    private int hitXMarker(double mx, double my) {
        for (GraphNode n : getGraph().nodes) {
            if (n.type != NodeType.DEBUG_SIGNAL_GEN) continue;
            int outMode = n.params.length > 1 ? (int) n.params[1] : 0;
            if (outMode != io.github.y15173334444.create_schematic_compute.graph.DebugSignals.OUT_INPUT) continue;
            float sx = c2sX(n.x), sy = c2sY(n.y);
            int nodeW = NodeRenderer.WIDE_NW;
            float bodyH = NodeRenderer.HH + NodeRenderer.PH * (n.functionalInputs() + n.outputs());
            int chartX = 2, chartY = (int) bodyH, chartW = nodeW - 4, chartH = 80;
            float xPos = n.params.length > 4 ? n.params[4] : 0.5f;
            float mxLine = sx + (chartX + xPos * chartW) * zoom;
            float myTop = sy + chartY * zoom;
            float myBot = sy + (chartY + chartH) * zoom;
            if (Math.abs(mx - mxLine) <= 5 * zoom && my >= myTop && my <= myBot) {
                return n.id;
            }
        }
        return -1;
    }

    /** 拖拽时更新 x 标记的 x 值。 */
    private void updateXMarkerX(GraphNode n, double mx) {
        float sx = c2sX(n.x);
        int nodeW = NodeRenderer.WIDE_NW;
        int chartX = 2, chartW = nodeW - 4;
        float graphX = (float) ((mx - sx) / zoom);
        float t = (graphX - chartX) / chartW;
        t = Math.max(0f, Math.min(1f, t));
        if (n.params.length > 4) n.params[4] = t;
    }

    /** 检测鼠标是否在节点 XY 图区域内。 */
    private boolean isInChartArea(GraphNode n, double mx, double my) {
        float sx = c2sX(n.x), sy = c2sY(n.y);
        int nodeW = NodeRenderer.WIDE_NW;
        float bodyH = NodeRenderer.HH + NodeRenderer.PH * (n.functionalInputs() + n.outputs());
        int chartX = 2, chartY = (int) bodyH, chartW = nodeW - 4, chartH = 80;
        float cx = sx + chartX * zoom;
        float cy = sy + chartY * zoom;
        float cw = chartW * zoom;
        float ch = chartH * zoom;
        return mx >= cx && mx <= cx + cw && my >= cy && my <= cy + ch;
    }

    /** 拖拽时更新控制点 X 和 Y 值（X 被夹在相邻点之间，保证不跨越）。
     *  Update control point X and Y during drag (X clamped between neighbors to prevent crossing). */
    private void updateControlPoint(GraphNode n, int idx, double mx, double my) {
        float sx = c2sX(n.x), sy = c2sY(n.y);
        int nodeW = NodeRenderer.WIDE_NW;
        float[] ys = yScale(n);
        int chartX = 2, chartW = nodeW - 4;
        // Y: 自动缩放范围
        float graphY = (float) ((my - sy) / zoom);
        n.debugCtrlY[idx] = screenYToValue(ys, graphY);
        // Y: 钳制在可见范围内 / Y: clamp to visible range
        float minV = ys[0], maxV = ys[0] + ys[3] / ys[1];
        if (n.debugCtrlY[idx] < minV) n.debugCtrlY[idx] = minV;
        if (n.debugCtrlY[idx] > maxV) n.debugCtrlY[idx] = maxV;
        // X: 夹在前后点之间（首点≥0，末点≤1）
        float graphX = (float) ((mx - sx) / zoom);
        float t = (graphX - chartX) / chartW;
        float minX = (idx > 0) ? n.debugCtrlX[idx - 1] : 0f;
        float maxX = (idx < n.debugCtrlX.length - 1) ? n.debugCtrlX[idx + 1] : 1f;
        n.debugCtrlX[idx] = Math.max(minX, Math.min(maxX, t));
    }

    /** 在鼠标位置添加控制点（按 X 升序插入）。 */
    private void addControlPoint(GraphNode n, double mx, double my) {
        float sx = c2sX(n.x), sy = c2sY(n.y);
        int nodeW = NodeRenderer.WIDE_NW;
        float[] ys = yScale(n);
        int chartX = 2, chartW = nodeW - 4;
        float graphX = (float) ((mx - sx) / zoom);
        float graphY = (float) ((my - sy) / zoom);
        float t = (graphX - chartX) / chartW;
        float v = screenYToValue(ys, graphY);
        // Y: 钳制在可见范围内 / Y: clamp to visible range
        float minV = ys[0], maxV = ys[0] + ys[3] / ys[1];
        if (v < minV) v = minV;
        if (v > maxV) v = maxV;
        t = Math.max(0f, Math.min(1f, t));
        int idx = 0;
        while (idx < n.debugCtrlX.length && n.debugCtrlX[idx] < t) idx++;
        var oldCtrlStr = encodeCtrlPoints(n.debugCtrlX, n.debugCtrlY);
        n.debugCtrlX = insertFloat(n.debugCtrlX, idx, t);
        n.debugCtrlY = insertFloat(n.debugCtrlY, idx, v);
        ctrlPointsChanged = true;
        var cpOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCtrlPoints(
            host.getBlockPos(), ownerNodeId(), n.id, n.debugCtrlX, n.debugCtrlY, host.getPlayerUUID());
        host.sendOp(cpOp); recordOp(cpOp, 0, 0, 0, oldCtrlStr);
    }

    /** 删除指定控制点（保留至少 2 个）。 */
    private void removeControlPoint(GraphNode n, int idx) {
        if (n == null || n.debugCtrlX == null || n.debugCtrlX.length <= 2) return;
        var oldCtrlStr = encodeCtrlPoints(n.debugCtrlX, n.debugCtrlY);
        n.debugCtrlX = removeFloat(n.debugCtrlX, idx);
        n.debugCtrlY = removeFloat(n.debugCtrlY, idx);
        ctrlPointsChanged = true;
        var cpOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCtrlPoints(
            host.getBlockPos(), ownerNodeId(), n.id, n.debugCtrlX, n.debugCtrlY, host.getPlayerUUID());
        host.sendOp(cpOp); recordOp(cpOp, 0, 0, 0, oldCtrlStr);
    }

    /** 在浮点数组指定索引处插入值，返回新数组。 / Insert a float into an array at the given index, returns a new array. */
    private static float[] insertFloat(float[] arr, int idx, float val) {
        float[] r = new float[arr.length + 1];
        System.arraycopy(arr, 0, r, 0, idx);
        r[idx] = val;
        System.arraycopy(arr, idx, r, idx + 1, arr.length - idx);
        return r;
    }

    /** 从浮点数组中删除指定索引处的值，返回新数组。 / Remove a float at the given index from the array, returns a new array. */
    private static float[] removeFloat(float[] arr, int idx) {
        float[] r = new float[arr.length - 1];
        System.arraycopy(arr, 0, r, 0, idx);
        System.arraycopy(arr, idx + 1, r, idx, arr.length - idx - 1);
        return r;
    }

    /** 处理鼠标移动——更新拖拽中的节点/注释位置，处理滚动条拖拽，发送在线状态。
     *  Handle mouse move — update node/comment position during drag, handle scrollbar drags, send presence.
     *  @param mx 鼠标 X（屏幕坐标）/ mouse X (screen coords)
     *  @param my 鼠标 Y（屏幕坐标）/ mouse Y (screen coords) */
    public void mouseMoved(double mx, double my) {
        lastMouseX = mx; lastMouseY = my;
        sendPresenceIfNeeded();
        // Comment resize
        if (resizingComment != null) {
            float newW = resizeStartW + (float)(mx / zoom - (c2sX(resizingComment.x) + resizeStartW * zoom) / zoom);
            float newH = resizeStartH + (float)(my / zoom - (c2sY(resizingComment.y) + resizeStartH * zoom) / zoom);
            newW = Math.max(80, Math.min(8000, newW));
            newH = Math.max(40, Math.min(6000, newH));
            if (gridSnapEnabled) {
                newW = Math.round(newW / NodeRenderer.GS) * NodeRenderer.GS;
                newH = Math.round(newH / NodeRenderer.GS) * NodeRenderer.GS;
            }
            resizingComment.commentWidth = newW;
            resizingComment.commentHeight = newH;
            // Real-time size sync (throttled) for collaboration
            long nowRs = System.currentTimeMillis();
            if (nowRs - lastDragSendTime >= DRAG_SEND_INTERVAL_MS) {
                lastDragSendTime = nowRs;
                host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCommentSize(
                    host.getBlockPos(), ownerNodeId(), resizingComment.id, newW, newH, host.getPlayerUUID()));
            }
            markDirty();
            return;
        }
        // 书签拖拽排序 / bookmark drag reorder
        if (draggingBookmarkIdx >= 0) { bookmarkDragY = (float)my; return; }
        // 菜单滚动条拖拽 / menu scrollbar drag
        if (scrollingMenu) {
            int[] track = renderer.menuScrollbarTrack();
            int[] thumb = renderer.menuScrollbarThumb();
            int trackH = track[3], thumbH = thumb[1];
            int maxOff = renderer.menuMaxScrollOff();
            if (maxOff > 0 && trackH > thumbH) {
                float delta = (float)(my - menuScrollDragStartY) / (trackH - thumbH);
                renderer.setMenuScrollOff(menuScrollDragStartOff + Math.round(delta * maxOff));
            }
            return;
        }
        // 书签滚动条拖拽 / bookmark scrollbar drag
        if (scrollingBookmark) {
            int panelW = 180, rowH = 16, maxRows = 5;
            var bks = getGraph().bookmarks;
            int totalRows = bks.size();
            if (totalRows > maxRows) {
                int visibleRows = Math.min(totalRows, maxRows);
                int sbH = visibleRows * rowH;
                int thumbH = Math.max(10, sbH * maxRows / totalRows);
                int maxOff = Math.max(1, totalRows - maxRows);
                float delta = (float)(my - scrollDragStartY) / (sbH - thumbH);
                int newOff = scrollDragStartOff + Math.round(delta * maxOff);
                bookmarkScrollOff = Math.max(0, Math.min(maxOff, newOff));
            }
            return;
        }
        if (scrollingComment != null) {
            int maxTextW = Math.max(1, Math.round(scrollingComment.commentWidth) - 26);
            int visibleH = Math.max(1, Math.round(scrollingComment.commentHeight) - 16);
            int maxVis = Math.max(1, visibleH / 12);
            int totalWraps = countWrappedLines(scrollingComment.displayText, maxTextW);
            int scrollMax = Math.max(0, totalWraps - maxVis);
            float sbH = Math.round(scrollingComment.commentHeight * zoom) - Math.max(6f, 12f * zoom) - 8 * zoom;
            float thumbH = Math.max(12 * zoom, (float) maxVis / totalWraps * sbH);
            float delta = (float) (my - scrollDragStartY) / (sbH - thumbH);
            int newOff = scrollDragStartOff + Math.round(delta * scrollMax);
            if (newOff < 0) newOff = 0;
            if (newOff > scrollMax) newOff = scrollMax;
            scrollingComment.commentScrollOff = newOff;
            return;
        }
        // Import dialog scrollbar drag
        if (scrollingImport) {
            int fileCount = importFiles != null ? importFiles.size() : 0;
            int visRows = 8;
            int maxScroll = Math.max(0, fileCount - visRows);
            float sbH = visRows * 18;
            float thumbH = 12;
            float delta = (float) (my - scrollDragStartY) / (sbH - thumbH);
            int newOff = scrollDragStartOff + Math.round(delta * maxScroll);
            if (newOff < 0) newOff = 0;
            if (newOff > maxScroll) newOff = maxScroll;
            importScrollOff = newOff;
            return;
        }
        // Comment parent-move
        if (draggingNode != null && draggingNode.type == NodeType.COMMENT) {
            float oldX = draggingNode.x, oldY = draggingNode.y;
            float nx = s2cX(mx) + dragOffX, ny = s2cY(my) + dragOffY;
            if (gridSnapEnabled) {
                nx = Math.round(nx / NodeRenderer.GS) * NodeRenderer.GS;
                ny = Math.round(ny / NodeRenderer.GS) * NodeRenderer.GS;
            }
            float dx = nx - oldX, dy = ny - oldY;
            draggingNode.x = nx; draggingNode.y = ny;
            moveContainedNodes(draggingNode, dx, dy);
            // Push aside overlapping out-of-bounds nodes (MTV on shortest axis)
            // 撞开重叠的框外节点（最短轴 MTV）
            pushedDragNodes.clear();
            pushAsideNodes(draggingNode, pushedDragNodes);
            if (gridSnapEnabled) {
                for (var pn : pushedDragNodes) {
                    pn.x = Math.round(pn.x / NodeRenderer.GS) * NodeRenderer.GS;
                    pn.y = Math.round(pn.y / NodeRenderer.GS) * NodeRenderer.GS;
                }
            }
            // Real-time drag sync (throttled) — include contained + pushed nodes
            long now3 = System.currentTimeMillis();
            if (now3 - lastDragSendTime >= DRAG_SEND_INTERVAL_MS) {
                lastDragSendTime = now3;
                host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.moveNode(
                    host.getBlockPos(), ownerNodeId(), draggingNode.id, nx, ny, host.getPlayerUUID()));
                for (var cn : containedDragNodes)
                    host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.moveNode(
                        host.getBlockPos(), ownerNodeId(), cn.id, cn.x, cn.y, host.getPlayerUUID()));
                for (var pn : pushedDragNodes)
                    host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.moveNode(
                        host.getBlockPos(), ownerNodeId(), pn.id, pn.x, pn.y, host.getPlayerUUID()));
            }
            // 不在此处 markDirty()：拖拽中每帧 bump 代际会触发 renderBg 重建全部展开节点的
            // 编辑区（20Hz 全量刷新）。注释移动是纯视觉操作——松手时的 op 已负责持久化与协作同步。
            // No per-move markDirty() here: bumping the generation every frame makes renderBg
            // rebuild every expanded EditState (full refresh at drag rate). Comment moves are
            // purely visual — the drop ops already handle persistence and collaboration sync.
            return;
        }
        // Drag-select in expanded FORMULA/COMMENT EditBox
        if (editBoxDragNodeId >= 0 && (org.lwjgl.glfw.GLFW.glfwGetMouseButton(
            org.lwjgl.glfw.GLFW.glfwGetCurrentContext(), 0) == org.lwjgl.glfw.GLFW.GLFW_PRESS)) {
            var en = getGraph().findNode(editBoxDragNodeId);
            if (en != null && expandedNodeIds.contains(en.id)) {
                var st = nodeEditStatesById.get(en.id);
                if (st != null && !st.fields.isEmpty()) {
                    // MLE coordinates are graph-space; convert mouse to graph-space
                    // MLE 坐标为图空间，将鼠标转换为图空间坐标
                    float gx = (float)((mx - c2sX(en.x)) / zoom);
                    float gy = (float)((my - c2sY(en.y)) / zoom);
                    st.fields.get(0).mouseDragged(gx, gy, 0, 0, 0);
                    return;
                }
            }
        }
        if(boxSelecting){boxEX=(float)mx;boxEY=(float)my;return;}
        if(multiDragging){
            float dmx = (s2cX(mx) - dragOffX) - multiCenterX;
            float dmy = (s2cY(my) - dragOffY) - multiCenterY;
            for (var sn : selectedNodes) {
                float[] orig = multiDragOrigins.get(sn);
                if (orig != null) {
                    float nx = orig[0] + dmx, ny = orig[1] + dmy;
                    if(gridSnapEnabled){nx=Math.round(nx/NodeRenderer.GS)*NodeRenderer.GS;ny=Math.round(ny/NodeRenderer.GS)*NodeRenderer.GS;}
                    sn.x=nx; sn.y=ny;
                }
            }
            // Real-time sync for multiplayer / 多人实时同步
            long nowMulti = System.currentTimeMillis();
            if (nowMulti - lastDragSendTime >= DRAG_SEND_INTERVAL_MS) {
                lastDragSendTime = nowMulti;
                for (var sn : selectedNodes) {
                    host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.moveNode(
                        host.getBlockPos(), ownerNodeId(), sn.id, sn.x, sn.y, host.getPlayerUUID()));
                }
            }
            return;
        }
        if(panning){camX+=(float)(mx-panLastX)/zoom;camY+=(float)(my-panLastY)/zoom;panLastX=(float)mx;panLastY=(float)my;}
        if(draggingNode!=null){
            float nx=s2cX(mx)+dragOffX, ny=s2cY(my)+dragOffY;
            if(gridSnapEnabled){nx=Math.round(nx/NodeRenderer.GS)*NodeRenderer.GS;ny=Math.round(ny/NodeRenderer.GS)*NodeRenderer.GS;}
            float dx=nx-draggingNode.x, dy=ny-draggingNode.y;
            draggingNode.x=nx;draggingNode.y=ny;
            // Real-time drag sync (throttled)
            long now2 = System.currentTimeMillis();
            if (now2 - lastDragSendTime >= DRAG_SEND_INTERVAL_MS) {
                lastDragSendTime = now2;
                host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.moveNode(
                    host.getBlockPos(), ownerNodeId(), draggingNode.id, nx, ny, host.getPlayerUUID()));
            }
        }if(draggingWire){wireEndX=s2cX(mx);wireEndY=s2cY(my);}
    }
    /** 处理鼠标拖拽——控制点拖拽、EditBox 文本选择拖拽、x 标记线拖拽。
     *  Handle mouse drag — control point drag, EditBox text selection drag, x-marker drag.
     *  @param mx 鼠标 X（屏幕坐标）/ mouse X (screen coords)
     *  @param my 鼠标 Y（屏幕坐标）/ mouse Y (screen coords)
     *  @param btn 鼠标按键 / mouse button
     *  @param dx X 方向拖拽增量（屏幕空间）/ drag delta X (screen space)
     *  @param dy Y 方向拖拽增量（屏幕空间）/ drag delta Y (screen space)
     *  @return true 如果事件被消费 / true if consumed */
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (colorPicker.isVisible()) return colorPicker.mouseDragged(mx, my, btn, dx, dy);
        // DEBUG_SIGNAL_GEN 控制点拖拽（X 被夹在相邻点之间 / X clamped between neighbors）
        if (draggingCtrlNode >= 0 && draggingCtrlIdx >= 0) {
            GraphNode cn = getGraph().findNode(draggingCtrlNode);
            if (cn != null && cn.debugCtrlY != null && draggingCtrlIdx < cn.debugCtrlY.length) {
                updateControlPoint(cn, draggingCtrlIdx, mx, my);
                ctrlPointsChanged = true;
            }
            return true;
        }
        // DEBUG_SIGNAL_GEN x 标记拖拽
        if (draggingXMarkerNode >= 0) {
            GraphNode xn = getGraph().findNode(draggingXMarkerNode);
            if (xn != null) updateXMarkerX(xn, mx);
            return true;
        }
        for (var en : getGraph().nodes) {
            if (!expandedNodeIds.contains(en.id)) continue;
            var st = nodeEditStatesById.get(en.id);
            if (st == null) continue;
            float sx = c2sX(en.x), sy = c2sY(en.y);
            int lmx = (int)((mx - sx) / zoom);
            int lmy = (int)((my - sy) / zoom);
            for (var b : st.fields) {
                if (b.mouseDragged(lmx, lmy, btn, dx / zoom, dy / zoom)) return true;
            }
        }
        return false;
    }
    /** 处理鼠标滚轮——缩放、注释文本滚动、菜单滚动、书签面板滚动、导入列表滚动。
     *  Handle mouse scroll — zoom, comment text scroll, menu scroll, bookmark panel scroll, import list scroll.
     *  <p>
     *  Ctrl+滚轮滚动注释内文本；普通滚轮缩放视图。
     *  Ctrl+scroll scrolls comment text; normal scroll zooms the view.
     *  @param mx 鼠标 X（屏幕坐标）/ mouse X (screen coords)
     *  @param my 鼠标 Y（屏幕坐标）/ mouse Y (screen coords)
     *  @param sx X 方向滚动量 / scroll delta X
     *  @param sy Y 方向滚动量 / scroll delta Y (positive = scroll up/zoom in)
     *  @return true 如果事件被消费 / true if consumed */
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (colorPicker.isVisible() && colorPicker.mouseScrolled(mx, my, sy)) return true;
        if (showMenu) { renderer.scrollMenu((float)(-sy * 14)); return true; }
        if (showImportDialog) { importScrollOff += (sy > 0) ? -1 : 1; if (importScrollOff < 0) importScrollOff = 0; return true; }
        if (showExportDialog || showColorConfig) return true;
        // 书签面板滚动 / bookmark panel scroll
        if (showBookmarkPanel) {
            int panelW = 180, maxRows = 5;
            int panelX = host.asScreen().width - panelW - 4;
            if (mx >= panelX && mx < panelX + panelW) {
                var bks = getGraph().bookmarks;
                int totalRows = bks.size();
                int maxOff = Math.max(0, totalRows - maxRows);
                bookmarkScrollOff += (sy > 0) ? -1 : 1;
                if (bookmarkScrollOff < 0) bookmarkScrollOff = 0;
                if (bookmarkScrollOff > maxOff) bookmarkScrollOff = maxOff;
                return true;
            }
        }
        // Ctrl+scroll → comment text scroll; normal scroll → zoom
        boolean ctrlHeld = org.lwjgl.glfw.GLFW.glfwGetKey(
            Minecraft.getInstance().getWindow().getWindow(), org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS
            || org.lwjgl.glfw.GLFW.glfwGetKey(
            Minecraft.getInstance().getWindow().getWindow(), org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        if (ctrlHeld) {
            var graph = getGraph();
            var scrollCandidates = spatialIndex.queryPoint(s2cX(mx), s2cY(my)).stream()
                .filter(n -> n.type == NodeType.COMMENT && !n.displayText.isEmpty())
                .sorted((a, b) -> Integer.compare(b.sortB, a.sortB))
                .collect(java.util.stream.Collectors.toList());
            for (var n : scrollCandidates) {
                float csx = c2sX(n.x), csy = c2sY(n.y);
                float csw = n.commentWidth * zoom, csh = n.commentHeight * zoom;
                if (mx >= csx && mx <= csx + csw && my >= csy && my <= csy + csh) {
                    int maxTextW = Math.max(1, (int)((csw - 26 * zoom) / zoom));
                    int lineH = 12, visibleH = Math.max(1, (int)((csh - 16 * zoom) / zoom));
                    int maxVis = Math.max(1, visibleH / lineH);
                    int totalWraps = countWrappedLines(n.displayText, maxTextW);
                    if (totalWraps > maxVis) {
                        n.commentScrollOff += (sy > 0) ? -1 : 1;
                        int scrollMax = Math.max(0, totalWraps - maxVis);
                        if (n.commentScrollOff < 0) n.commentScrollOff = 0;
                        if (n.commentScrollOff > scrollMax) n.commentScrollOff = scrollMax;
                        return true;
                    }
                    break;
                }
            }
        }
        float oz=zoom; zoom*=(sy>0)?1.12f:(1f/1.12f); zoom=Math.max(0.25f,Math.min(4f,zoom));
        camX+=(mx-host.asScreen().width/2f)*(1f/zoom-1f/oz); camY+=(my-host.asScreen().height/2f)*(1f/zoom-1f/oz); return true;
    }
    /** 处理键盘按键——ESC 关闭面板、Enter 提交编辑、Ctrl+Z/Y 撤销重做、
     *  Ctrl+D 复制、X 键删除、Delete 删除、C 键创建注释、TAB 框选模式等。
     *  Handle keyboard input — ESC closes panels, Enter commits edits, Ctrl+Z/Y undo/redo,
     *  Ctrl+D duplicate, X/Delete remove nodes, C create comment, TAB box-select mode, etc.
     *  @param key GLFW 键码 / GLFW key code
     *  @param sc 扫描码 / scan code
     *  @param mod 修饰键位掩码 / modifier bitmask
     *  @return true 如果事件被消费 / true if consumed */
    public boolean keyPressed(int key, int sc, int mod) {
        var graph = getGraph();
        // D: 搜索框菜单键盘 / search box menu keyboard
        if (showMenu) {
            if (renderer.isMenuSearchFocused()) {
                if (key == 256) { renderer.setMenuSearchFocused(false); return true; } // Esc unfocus
                if (key == 259) { renderer.menuSearchBackspace(); return true; }       // Backspace
            } else {
                if (key == 256) { showMenu = false; return true; } // Esc close menu
            }
        }
        // 书签命名对话框 / bookmark name dialog
        if (editingBookmarkName) {
            if (key == 257) { // Enter: 提交（新建/重命名）/ submit (add or rename)
                if (!bookmarkNameDraft.isEmpty()) {
                    var bmGraph = getGraph();
                    if (editingBookmarkIndex >= 0) {
                        // 重命名：本地先应用 / rename: apply locally first
                        var bks = bmGraph.bookmarks;
                        if (editingBookmarkIndex >= 0 && editingBookmarkIndex < bks.size()) {
                            var old = bks.get(editingBookmarkIndex);
                            bks.set(editingBookmarkIndex, new io.github.y15173334444.create_schematic_compute.graph.NodeGraph.Bookmark(bookmarkNameDraft, old.camX(), old.camY(), old.zoom()));
                            bmGraph.bumpGeneration();
                        }
                        host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.renameBookmark(
                            host.getBlockPos(), ownerNodeId(), editingBookmarkIndex, bookmarkNameDraft, host.getPlayerUUID()));
                    } else {
                        // 新建：本地先应用 / add: apply locally first
                        bmGraph.bookmarks.add(new io.github.y15173334444.create_schematic_compute.graph.NodeGraph.Bookmark(bookmarkNameDraft, camX, camY, zoom));
                        bmGraph.bumpGeneration();
                        host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.addBookmark(
                            host.getBlockPos(), ownerNodeId(), bookmarkNameDraft, camX, camY, zoom, host.getPlayerUUID()));
                    }
                }
                editingBookmarkName = false;
                editingBookmarkIndex = -1;
                return true;
            }
            if (key == 259 && !bookmarkNameDraft.isEmpty()) { // Backspace
                bookmarkNameDraft = bookmarkNameDraft.substring(0, bookmarkNameDraft.length() - 1);
                return true;
            }
            return true; // 消费其他键
        }
        // 导出对话框键盘 (Export dialog keyboard)
        if (showExportDialog) {
            if (key == 256) { showExportDialog = false; exportNameEdit = null; return true; } // Esc (退出)
            if (key == 257 && exportNameEdit != null && selectedNode != null) { // Enter (确认)
                String name = exportNameEdit.getValue().trim();
                if (!name.isEmpty()) exportEncapNode(selectedNode, name);
                showExportDialog = false; exportNameEdit = null; return true;
            }
            if (exportNameEdit != null) return exportNameEdit.keyPressed(key, sc, mod);
            return true;
        }
        // 导入对话框键盘 (Import dialog keyboard)
        if (showImportDialog) {
            if (key == 256) { showImportDialog = false; importFiles = null; return true; } // Esc (退出)
            return true;
        }
        // Color picker keyboard delegation (close callback handles panel cleanup)
        if (colorPicker.isVisible()) {
            // ESC: close color picker AND comment color panel together
            // ESC：同时关闭调色板与注释颜色面板
            if (key == 256) {
                colorPicker.close();
                if (showColorConfig) { showColorConfig = false; return true; }
                if (editingCommentColorNode != null && commentButtons != null) { closeCommentColorPopup(); return true; }
                return true;
            }
            return colorPicker.keyPressed(key, sc, mod);
        }
        // ESC closes open panels first, then falls through to close UI
        if (key == 256) {
            if (editingBookmarkName) { editingBookmarkName = false; editingBookmarkIndex = -1; return true; }
            if (showColorConfig) { showColorConfig = false; return true; }
            if (editingCommentColorNode != null && commentButtons != null) { closeCommentColorPopup(); return true; }
        }
        if (key == 257) { // Enter: 提交当前聚焦的编辑框 (Enter: commit current focused edit box)
            for (var e : enterActions.entrySet()) {
                if (e.getKey().isFocused()) { e.getValue().run(); return true; }
            }
            for (var st : nodeEditStatesById.values()) {
                if (st.busBox != null && st.busBox.isFocused()) { commitBusBox(st); return true; }
            }
        }
        if (key == 258) { // TAB — let popup consume first, otherwise use for box-select
            for (var st : nodeEditStatesById.values()) {
                for (var f : st.fields) {
                    if (f.isFocused() && f instanceof io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox mleBox) {
                        var popup = mleBox.getSuggestPopup();
                        if (popup.isVisible()) {
                            String insert = popup.acceptSelected();
                            if (insert != null) mleBox.replaceCurrentWordForPopup(insert);
                            return true;
                        }
                    }
                }
            }
            tabHeld = true; return true;
        }
        // KEYBOARD 按键绑定捕获（GAMEPAD_BUTTON 由 renderBg 每帧轮询处理） (KEYBOARD key binding capture; GAMEPAD_BUTTON polled by renderBg each frame)
        if (!nodeEditStatesById.isEmpty()) {
            for (var st : nodeEditStatesById.values()) {
                if (st.listeningForKey) {
                    // GAMEPAD_BUTTON handled by renderBg() — only ESC cancels, other keys ignored (GAMEPAD_BUTTON由renderBg处理，仅ESC取消)
                    boolean isGpad = false;
                    for (var en : getGraph().nodes) {
                        var es = nodeEditStatesById.get(en.id);
                        if (es == st && en.type == NodeType.GAMEPAD_BUTTON) { isGpad = true; break; }
                    }
                    if (isGpad) {
                        if (key == 256) { st.listeningForKey = false; return true; }
                        return true; // consume event, let renderBg() handle capture
                    }
                    // 键盘绑定 (Keyboard binding)
                    if (key == 256) { st.listeningForKey = false; return true; }
                    int idx = io.github.y15173334444.create_schematic_compute.blocks.EditPanel.glfwKeyToIndex(key);
                    if (idx >= 0) {
                        for (var en : getGraph().nodes) {
                            var es = nodeEditStatesById.get(en.id);
                            if (es == st && en.params.length > 0) {
                                var oldIdx = (int)en.params[0]; // save for undo
                                en.params[0] = idx;
                                var kbOp = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                                    io.github.y15173334444.create_schematic_compute.graph.OpType.SET_KEY_BINDING,
                                    host.getBlockPos(), ownerNodeId(), en.id, 0, null, 0f, 0f,
                                    0, 0, 0, 0, 0, 0f, null, 0, 0, 0, 0, null, idx, 0, 0,
                                    net.minecraft.world.item.ItemStack.EMPTY, 0L, host.getPlayerUUID());
                                host.sendOp(kbOp); recordOp(kbOp, 0, 0, oldIdx, null);
                                break; }
                        }
                        st.listeningForKey = false;
                    }
                    return true;
                }
            }
        }
        // 顶栏名称框键盘（Esc/Enter 提交并失焦，其余交给 EditBox）
        // Top-bar name box keyboard (Esc/Enter commit + unfocus, the rest to the EditBox).
        if (topBarNameEdit != null && topBarNameEdit.isFocused()) {
            if (key == 256 || key == 257) { topBarNameEdit.setFocused(false); return true; }
            return topBarNameEdit.keyPressed(key, sc, mod);
        }
        for (var st : nodeEditStatesById.values()) for (var f : st.fields) if (f.isFocused()) return f.keyPressed(key, sc, mod);
        // X 键删除悬停节点（替代右键删除防误触） (X key deletes hovered node, replacing right-click delete to prevent accidental deletion)
        if (key == 88) { // GLFW_KEY_X
            var g2 = getGraph();
            var hit = hitNode(lastMouseX, lastMouseY);
            if (hit != null && !isNodeLocked(hit.id, ownerNodeId())) {
                beginUndoBatch();
                var savedX = hit.x; var savedY = hit.y; var savedType = hit.type.ordinal();
                var savedNbt = saveNodeNbt(hit); // snapshot for undo restore
                g2.removeNode(hit.id);
                rebuildParentCacheIfInSubGraph(); // rebuild parent ENCAP pin mapping
                var removeOp = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                    io.github.y15173334444.create_schematic_compute.graph.OpType.REMOVE_NODE,
                    host.getBlockPos(), ownerNodeId(), hit.id, host.getPlayerUUID());
                host.sendOp(removeOp);
                recordOp(removeOp, savedX, savedY, savedType, savedNbt);
                endUndoBatch();
                expandedNodeIds.remove(hit.id);
                nodeEditStatesById.remove(hit.id);
                selectedNodes.remove(hit);
                if (selectedNode == hit) selectedNode = null;
                return true;
            }
        }
        // Ctrl+Z / Ctrl+Y undo / redo
        if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
            if (key == 90) { commitFocusedEditBox(); opUndo(); return true; }  // Ctrl+Z
            if (key == 89) { commitFocusedEditBox(); opRedo(); return true; }  // Ctrl+Y
            // 视角书签快捷键 / view bookmark shortcuts
            if (key == 77) { // Ctrl+M: 保存书签 / Ctrl+M: save bookmark
                editingBookmarkName = true;
                editingBookmarkIndex = -1;
                bookmarkNameDraft = "书签 " + (getGraph().bookmarks.size() + 1);
                return true;
            }
        }
        // Home: 重置视角 / reset view
        if (key == 268) { startTransition(0, 0, 1f); return true; }
        // Ctrl+D 复制（支持多选） — 走服务端权威 ID 分配流程
        // Ctrl+D duplicate (supports multi-select) — uses server-authoritative ID allocation
        if(key==68&&net.minecraft.client.gui.screens.Screen.hasControlDown()&&!selectedNodes.isEmpty()){
            beginUndoBatch();
            var idMap = new java.util.HashMap<Integer, Integer>();
            var newNodes = new java.util.ArrayList<GraphNode>();
            float ofs = 30;
            var uid = host.getPlayerUUID();
            var gpos = host.getBlockPos();
            int oid = ownerNodeId();
            var group = new PendingCopyGroup(oid, gpos, uid);
            // 克隆所有选中节点（含子图等所有字段） / Clone all selected nodes (incl. sub-graphs, all fields)
            for (var n : selectedNodes) {
                int tempId = graph.nextNodeId++;
                var dup = n.shallowCopyWithNewId(tempId);
                dup.x += ofs; dup.y += ofs;
                // BUS_OUT 复制后清空频道名（防止两个 BUS_OUT 同频道冲突）。
                // BUS_IN 保留频道名——多个 BUS_IN 读同一频道是合法场景。
                // Clear channel name on BUS_OUT duplicate to prevent conflicts.
                // BUS_IN keeps its name — multiple readers on the same channel is valid.
                if (dup.type == NodeType.BUS_OUT) {
                    dup.signalName = "";
                    dup.displayText = "";
                }
                graph.adoptNode(dup);
                idMap.put(n.id, dup.id);
                newNodes.add(dup);
                group.nodes.add(dup);
                group.tempToReal.put(tempId, -1); // pending
                // 复制展开状态（本地） / Copy expand state (local only)
                if (n.expanded) {
                    expandedNodeIds.add(dup.id);
                    nodeEditStatesById.put(dup.id, createEditState(dup));
                }
                // 发送 ADD_NODE_REQUEST（服务端分配真实 ID）/ Send ADD_NODE_REQUEST (server assigns real ID)
                var anOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.addNodeRequest(gpos, oid, tempId, dup.type, dup.x, dup.y, uid);
                host.sendOp(anOp); recordOp(anOp, 0, 0, dup.id, null); // oldVal=localId
            }
            // 复制选中节点之间的连接（本地 + 待发送）/ Copy connections between selected nodes (local + pending)
            for (var c : List.copyOf(graph.connections)) {
                if (idMap.containsKey(c.fromId) && idMap.containsKey(c.toId)) {
                    graph.addConnection(idMap.get(c.fromId), c.fromPin, idMap.get(c.toId), c.toPin);
                    group.conns.add(new int[]{idMap.get(c.fromId), c.fromPin, idMap.get(c.toId), c.toPin});
                }
            }
            endUndoBatch();
            // 更新选中为新节点 / Update selection to new nodes
            selectedNodes.clear();
            selectedNodes.addAll(newNodes);
            selectedNode = newNodes.isEmpty() ? null : newNodes.get(0);
            // 注册待发送组 — handleAck 在所有节点获得服务端真实 ID 后批量发送数据 op
            // Register pending group — handleAck flushes data ops once all nodes have real IDs
            int groupId = nextCopyGroupId++;
            pendingCopyGroups.put(groupId, group);
            return true;
        }
        // Delete 删除选中节点 (Delete key removes selected nodes)
        if ((key == 259 || key == 261) && !selectedNodes.isEmpty()) {
            beginUndoBatch();
            for (var n : List.copyOf(selectedNodes)) {
                if (isNodeLocked(n.id, ownerNodeId())) continue;
                if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty()) {
                    boolean hasOther = false;
                    for (var other : graph.nodes) {
                        if (other != n && other.type == NodeType.BUS_OUT && other.signalName.equals(n.signalName))
                            { hasOther = true; break; }
                    }
                    if (!hasOther) {
                        io.github.y15173334444.create_schematic_compute.network.SignalBus.clearBus(n.signalName);
                        localBusNames.remove(n.signalName);
                    }
                }
                var savedX = n.x; var savedY = n.y; var savedType = n.type.ordinal();
                var savedNbt = saveNodeNbt(n); // snapshot for undo restore
                graph.removeNode(n.id);
                var removeOp = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                    io.github.y15173334444.create_schematic_compute.graph.OpType.REMOVE_NODE,
                    host.getBlockPos(), ownerNodeId(), n.id, host.getPlayerUUID());
                host.sendOp(removeOp);
                recordOp(removeOp, savedX, savedY, savedType, savedNbt);
            }
            endUndoBatch();
            if (selectedNode != null) {
                expandedNodeIds.remove(selectedNode.id);
                nodeEditStatesById.remove(selectedNode.id);
            }
            selectedNodes.clear();
            selectedNode = null;
            return true;
        }
        // C key: Create comment node around selection
        if (key == 67 && !net.minecraft.client.gui.screens.Screen.hasControlDown()
            && !selectedNodes.isEmpty()) {
            boolean anyFocused = false;
            for (var st : nodeEditStatesById.values())
                for (var f : st.fields) if (f.isFocused()) { anyFocused = true; break; }
            if (anyFocused) return false;
            if (showExportDialog || showImportDialog || showColorConfig) return false;
            beginUndoBatch();
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (var n : selectedNodes) {
                float nw = NodeRenderer.nw(n);
                float nh = NodeRenderer.nh(n);
                if (expandedNodeIds.contains(n.id)) {
                    var es = nodeEditStatesById.get(n.id);
                    nh += EditPanel.calcRenderHeight(n, 1f, es) + 4;
                }
                if (n.x < minX) minX = n.x;
                if (n.y < minY) minY = n.y;
                if (n.x + nw > maxX) maxX = n.x + nw;
                if (n.y + nh > maxY) maxY = n.y + nh;
            }
            float padding = 30;
            float cw = maxX - minX + padding * 2;
            float ch = maxY - minY + padding * 2;
            cw = Math.max(80, Math.min(8000, cw));
            ch = Math.max(40, Math.min(6000, ch));
            var comment = graph.addNode(NodeType.COMMENT, minX - padding, minY - padding);
            comment.commentWidth = cw;
            comment.commentHeight = ch;
            // Ensure wrapper comment renders behind all contained nodes
            int minSelSortB = Integer.MAX_VALUE;
            for (var n : selectedNodes) {
                if (n.sortB < minSelSortB) minSelSortB = n.sortB;
            }
            if (minSelSortB != Integer.MAX_VALUE) {
                comment.sortB = minSelSortB - 1;
                if (comment.sortB < 0) renormalizeSortB(graph);
            }
            var addOp = new io.github.y15173334444.create_schematic_compute.graph.GraphOp(
                io.github.y15173334444.create_schematic_compute.graph.OpType.ADD_NODE,
                host.getBlockPos(), ownerNodeId(), comment.id,
                comment.id, NodeType.COMMENT, minX - padding, minY - padding, 0, 0, 0, 0, 0, 0f,
                null, 0, 0, 0, 0, null, 0, 0, 0,
                net.minecraft.world.item.ItemStack.EMPTY, 0L, host.getPlayerUUID());
            host.sendOp(addOp); recordOp(addOp, 0, 0, 0, null);
            var szOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCommentSize(
                host.getBlockPos(), ownerNodeId(), comment.id, cw, ch, host.getPlayerUUID());
            host.sendOp(szOp); recordOp(szOp, 0, 0, 0, null);
            endUndoBatch();
            return true;
        }
        return false;
    }
    /** 处理按键释放——主要用于 TAB 键释放时退出框选模式。
     *  Handle key release — mainly used to exit box-select mode when TAB is released.
     *  @return true 如果事件被消费 / true if consumed */
    public boolean keyReleased(int key, int sc, int mod) {
        if (key == 258) { tabHeld = false; return true; }
        return false;
    }
    /** 处理字符输入——菜单搜索、书签命名、EditBox 文本输入。
     *  Handle character input — menu search, bookmark naming, EditBox text input.
     *  @param ch 输入的字符 / the typed character
     *  @param mod 修饰键位掩码 / modifier bitmask
     *  @return true 如果事件被消费 / true if consumed */
    public boolean charTyped(char ch, int mod) {
        // D: 菜单搜索输入 / menu search input
        if (showMenu) {
            if (renderer.isMenuSearchFocused() || java.lang.Character.isLetterOrDigit(ch)
                    || ch == ' ' || ch == '_' || ch == '-' || ch == '/') {
                renderer.appendMenuSearch(ch);
                renderer.setMenuSearchFocused(true);
                return true;
            }
        }
        if (editingBookmarkName) {
            if (ch >= 32 && bookmarkNameDraft.length() < 30) bookmarkNameDraft += ch;
            return true;
        }
        if (colorPicker.isVisible()) return colorPicker.charTyped(ch, mod);
        if (showExportDialog && exportNameEdit != null) return exportNameEdit.charTyped(ch, mod);
        if (topBarNameEdit != null && topBarNameEdit.isFocused()) return topBarNameEdit.charTyped(ch, mod);
        for (var st : nodeEditStatesById.values()) for (var f : st.fields) if (f.isFocused()) return f.charTyped(ch, mod);
        return false;
    }

    /** 颜色配置面板（双列布局） (Color configuration panel, two-column layout) */
    /** 顶栏：固定在编辑器最上层的条 —— 左侧本图名称输入框（便携终端按此查找），
     *  右侧设置按钮。在 renderBg 的所有覆盖层之后调用，保证不被遮挡；工具栏顶/底
     *  两种位置都必须让开 {@link #TOP_BAR_H}。
     *  Top bar: a fixed strip at the very top of the editor — the graph-name box on
     *  the left (the portable terminal looks devices up by it), the settings button
     *  on the right. Called after every other overlay in renderBg so nothing covers
     *  it; both toolbar positions (top and bottom) must clear {@link #TOP_BAR_H}. */
    private void renderTopBar(GuiGraphics g, int mx, int my) {
        var mc = Minecraft.getInstance();
        int sw = host.asScreen().width;
        if (topBarNameEdit == null) {
            topBarNameEdit = new EditBox(mc.font, 0, 0, 140, 16, Component.literal(""));
            topBarNameEdit.setMaxLength(32);
            topBarNameEdit.setValue(getGraph().customName);
            // 逐字符同步（与 PRIVATE/TEXT 命名框同模式）：SET_BLOCK_NAME 是纯视觉 op，
            // 无 commitBusBox 那样的重副作用，不需要防抖。
            // Per-keystroke sync (same pattern as the PRIVATE/TEXT boxes):
            // SET_BLOCK_NAME is a visual-only op with none of commitBusBox's heavy
            // side effects, so no debounce.
            topBarNameEdit.setResponder(text -> {
                if (!text.equals(getGraph().customName)) {
                    getGraph().customName = text;
                    host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setBlockName(
                        host.getBlockPos(), ownerNodeId(), text, host.getPlayerUUID()));
                }
            });
        }
        // 图被整体替换（重载/多人同步）后 customName 可能变化 —— 未聚焦时跟随权威值，
        // 聚焦时绝不覆盖（用户正在输入）。
        // After a whole-graph replacement (reload / multiplayer sync) customName may
        // have changed — follow the authoritative value while unfocused, never while
        // the user is typing.
        if (!topBarNameEdit.isFocused() && !topBarNameEdit.getValue().equals(getGraph().customName))
            topBarNameEdit.setValue(getGraph().customName);

        g.fill(0, 0, sw, TOP_BAR_H, 0xEE1A1A2A);
        g.fill(0, TOP_BAR_H - 1, sw, TOP_BAR_H, NodeRenderer.CSB());
        String label = I18n.get("gui.create_schematic_compute.topbar.name");
        g.drawString(mc.font, label, 6, 8, 0xFF888888, false);
        int bx = 10 + mc.font.width(label);
        int bw = Math.min(160, Math.max(80, sw / 4));
        topBarNameEdit.setX(bx);
        topBarNameEdit.setY(3);
        topBarNameEdit.setWidth(bw);
        topBarNameEdit.render(g, 0, 0, 0);
        // 设置按钮（右侧） / settings button (right)
        int sbX = sw - 52;
        boolean hov = mx >= sbX && mx <= sbX + 46 && my >= 3 && my <= 19;
        g.fill(sbX, 3, sbX + 46, 19, hov ? 0xFF3A4A6A : 0xFF2A3A5A);
        g.renderOutline(sbX, 3, 46, 16, NodeRenderer.CSB());
        g.drawString(mc.font, I18n.get("gui.create_schematic_compute.topbar.settings"), sbX + 8, 7, 0xFFCCCCFF, false);
    }

    private void renderColorPanel(GuiGraphics g, int mx, int my) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        int itemsPerCol = 8, numRows = 8; // 16色分 8+8 两列 (16 colors split 8+8 in two columns)
        int colW = 100, pw = colW * 2 + 22, ph = 36 + numRows * 18 + 24;
        int px = 8, py = Math.max(4, (host.asScreen().height - ph) / 2); // left-aligned
        g.fill(px, py, px + pw, py + ph, 0xFF2A2822);
        g.renderOutline(px, py, pw, ph, NodeRenderer.CSB());
        g.fill(px + 2, py + 2, px + pw - 2, py + 18, 0xFF4A3F28);
        g.drawString(mc.font, "§6§l" + net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.color.title"), px + 6, py + 5, 0xFFFFFFFF, false);
        g.fill(px + pw - 18, py + 2, px + pw - 2, py + 18, 0xFF4A3028);
        g.renderOutline(px + pw - 18, py + 2, 16, 16, 0xFF8B5333);
        g.drawString(mc.font, "§cX", px + pw - 14, py + 5, 0xFFFFFFFF, false);
        for (int i = 0; i < NodeRenderer._NUM_COLORS; i++) {
            int col = i < itemsPerCol ? 0 : 1;
            int row = i < itemsPerCol ? i : i - itemsPerCol;
            int cx = px + 8 + col * (colW + 14);
            int ry = py + 24 + row * 18;
            // 色块按钮（预览暂存颜色） (Color swatch button, previewing staging color)
            themeButtons[i].setPosition(cx + 2, ry + 2);
            themeButtons[i].render(g, mx, my);
            // 名称 (Name label)
            g.drawString(mc.font, net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.color." + NodeRenderer.COLOR_KEYS[i]), cx + 22, ry + 2, 0xFFCCCCCC, false);
        }
        int by = py + ph - 22;
        g.fill(px + 8, by, px + 72, by + 16, 0xFF3A3428);
        g.renderOutline(px + 8, by, 64, 16, NodeRenderer.CSB());
        g.drawString(mc.font, "§7" + net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.color.defaults"), px + 14, by + 3, 0xFFFFFFFF, false);
        g.fill(px + pw - 72, by, px + pw - 8, by + 16, 0xFF3A5A2A);
        g.renderOutline(px + pw - 72, by, 64, 16, 0xFF5A8A3A);
        g.drawString(mc.font, "§a" + net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.color.apply"), px + pw - 62, by + 3, 0xFFFFFFFF, false);
    }

    /** 重新编译图——自动折叠所有注释节点，同步未保存编辑，保存并重启运行状态。
     *  Recompile the graph — auto-close all COMMENT nodes, sync unsaved edits, save and restart running state.
     *  @param graph 待编译的图 / the graph to recompile */
    private void recompile(NodeGraph graph) {
        cycleWarning=null;
        // Auto-close all COMMENT nodes before compile
        for (var n : graph.nodes) {
            if (n.type == NodeType.COMMENT && expandedNodeIds.contains(n.id)) {
                expandedNodeIds.remove(n.id);
                nodeEditStatesById.remove(n.id);
                n.expanded = false;
            }
        }
        // 编译前同步所有未保存的编辑（busBox + 频段改名） (Sync all unsaved edits before compile: busBox + band renames)
        commitPendingBusEdits();
        // 编译时当前状态回归初始值 (Reset current state to initial values on compile)
        for (var n : graph.nodes) {
            if ((n.type == NodeType.GATE || n.type == NodeType.T_FLIPFLOP || n.type == NodeType.LATCH) && n.params.length > 1) {
                n.params[1] = n.params[0];
            }
        }
        saveGraph();
        host.toggleRunning(false);
        markDirty();
    }

    /** 检测 ▶/▼ 展开按钮点击 (Detect expand/collapse indicator button click) */
    private GraphNode hitExpandIndicator(double mx, double my, NodeGraph graph) {
        float indicatorSize = 12 * zoom;
        float scx = s2cX(mx), scy = s2cY(my);
        var candidates = spatialIndex.queryPoint(scx, scy);
        boolean anyCapable = false;
        for (var n : candidates)
            if (n.type != NodeType.COMMENT && hasExpandIndicator(n)) { anyCapable = true; break; }
        if (!anyCapable) return null;
        // 触摸优先级:按绘制顺序(sortB 升序)扫描全部节点。上层节点的实体矩形(含展开编辑区)
        // 覆盖此点时清掉下层指示器命中;上层节点自身指示器命中则覆盖下层——与视觉遮挡一致。
        // Touch priority: scan all nodes in draw order (sortB ascending). A higher node's body rect
        // (expanded edit area included) covering the point cancels any lower indicator hit; the higher
        // node's own indicator hit overrides lower ones — matching visual occlusion.
        var all = new java.util.ArrayList<>(graph.nodes);
        all.sort(java.util.Comparator.comparingInt(n -> n.sortB));
        GraphNode hit = null;
        for (var n : all) {
            if (!hasExpandIndicator(n)) continue;
            float sx = c2sX(n.x), sy = c2sY(n.y);
            float ix = sx + (io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer.nw(n) - 22) * zoom;
            float iy = sy + 2 * zoom;
            if (mx >= ix && mx <= ix + indicatorSize && my >= iy && my <= iy + indicatorSize) {
                hit = n;
                continue;
            }
            // 节点实体(含展开编辑区)覆盖此点 → 此节点之上不再有指示器可穿透 / body covers the point → no lower indicator may receive it
            float nwpx = io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer.nw(n) * zoom;
            float nhpx = (fullNodeHeight(n) + 4) * zoom;
            if (mx >= sx && mx <= sx + nwpx && my >= sy && my <= sy + nhpx) hit = null;
        }
        return hit;
    }

    /** 该类型是否渲染 ▶/▼ 展开指示器(与 NodeRenderer 的绘制条件一致)。
     *  Whether this type renders the ▶/▼ expand indicator (same condition as NodeRenderer). */
    private static boolean hasExpandIndicator(GraphNode n) {
        return n.type == NodeType.FORMULA || n.type.paramNames.length > 0
            || n.type == NodeType.REDSTONE_IN || n.type == NodeType.REDSTONE_OUT
            || n.type == NodeType.PRIVATE_IN || n.type == NodeType.PRIVATE_OUT
            || n.type == NodeType.IMAGE || n.type == NodeType.IMAGE_SEQUENCE
            || n.type == NodeType.TEXT || n.type == NodeType.DATA
            || n.type == NodeType.ENCAPSULATION || n.type == NodeType.ENCAP_INPUT || n.type == NodeType.ENCAP_OUTPUT
            || n.type == NodeType.COMMENT
            || n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT;
    }

    /** 关闭注释颜色编辑弹窗，重置所有相关状态。
     *  Close the comment color edit popup and reset all related state. */
    private void closeCommentColorPopup() {
        editingCommentColorNode = null;
        commentButtons = null;
        colorPicker.close();
    }

    /** Open/rebind the color picker to a theme staging color. */
    private void openColorPickerForTheme(int idx) {
        Consumer<Integer> setter = c -> { NodeRenderer.stagingColors[idx] = c; };
        colorPicker.setOnClose(() -> { showColorConfig = false; if (editingCommentColorNode != null) closeCommentColorPopup(); });
        if (colorPicker.isVisible()) {
            colorPicker.rebind(NodeRenderer.stagingColors[idx], setter);
        } else {
            int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            colorPicker.open(sw - ColorPickerWidget.WIDTH / 2, Minecraft.getInstance().getWindow().getGuiScaledHeight() / 2,
                NodeRenderer.stagingColors[idx], setter);
        }
    }

    /** Open/rebind the color picker to a comment color field (0=bg, 1=border, 2=text). */
    private void openColorPickerForComment(int field) {
        if (editingCommentColorNode == null) return;
        int color = switch (field) {
            case 0 -> editingCommentColorNode.commentBgColor;
            case 1 -> editingCommentColorNode.commentBorderColor;
            case 2 -> editingCommentColorNode.commentTextColor;
            default -> 0xFF000000;
        };
        Consumer<Integer> setter = switch (field) {
            case 0 -> c -> { int oldBg = editingCommentColorNode.commentBgColor, oldBr = editingCommentColorNode.commentBorderColor, oldTx = editingCommentColorNode.commentTextColor; editingCommentColorNode.commentBgColor = c; markDirty(); var op = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCommentColors(host.getBlockPos(), ownerNodeId(), editingCommentColorNode.id, editingCommentColorNode.commentBgColor, editingCommentColorNode.commentBorderColor, editingCommentColorNode.commentTextColor, host.getPlayerUUID()); host.sendOp(op); recordOp(op, oldBg, oldBr, oldTx, null); showColorConfig = false; };
            case 1 -> c -> { int oldBg = editingCommentColorNode.commentBgColor, oldBr = editingCommentColorNode.commentBorderColor, oldTx = editingCommentColorNode.commentTextColor; editingCommentColorNode.commentBorderColor = c; markDirty(); var op = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCommentColors(host.getBlockPos(), ownerNodeId(), editingCommentColorNode.id, editingCommentColorNode.commentBgColor, editingCommentColorNode.commentBorderColor, editingCommentColorNode.commentTextColor, host.getPlayerUUID()); host.sendOp(op); recordOp(op, oldBg, oldBr, oldTx, null); showColorConfig = false; };
            case 2 -> c -> { int oldBg = editingCommentColorNode.commentBgColor, oldBr = editingCommentColorNode.commentBorderColor, oldTx = editingCommentColorNode.commentTextColor; editingCommentColorNode.commentTextColor = c; markDirty(); var op = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCommentColors(host.getBlockPos(), ownerNodeId(), editingCommentColorNode.id, editingCommentColorNode.commentBgColor, editingCommentColorNode.commentBorderColor, editingCommentColorNode.commentTextColor, host.getPlayerUUID()); host.sendOp(op); recordOp(op, oldBg, oldBr, oldTx, null); showColorConfig = false; };
            default -> c -> {};
        };
        colorPicker.setOnClose(() -> { showColorConfig = false; if (editingCommentColorNode != null) closeCommentColorPopup(); });
        if (colorPicker.isVisible()) {
            colorPicker.rebind(color, setter);
        } else {
            int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            colorPicker.open(sw - ColorPickerWidget.WIDTH / 2, Minecraft.getInstance().getWindow().getGuiScaledHeight() / 2,
                color, setter);
        }
    }

    /** 去除 Markdown 格式标记（粗体、斜体、代码、标题），返回纯文本。
     *  Strip Markdown formatting markers (bold, italic, code, heading) and return plain text.
     *  @param line 原始文本行 / raw text line
     *  @return 去除格式标记后的纯文本 / plain text with formatting stripped */
    private static String plainText(String line) {
        return line.replaceAll("\\*\\*|\\*|`|#\\s?", "");
    }

    /** 计算文本在给定宽度下的自动换行行数。
     *  Count the number of wrapped lines when text is rendered within a given pixel width.
     *  @param text 待计算的文本 / text to measure
     *  @param availW 可用像素宽度 / available pixel width
     *  @return 换行后的总行数 / total number of wrapped lines */
    private static int countWrappedLines(String text, int availW) {
        var font = Minecraft.getInstance().font;
        int total = 0;
        for (String line : text.split("\n", -1)) {
            String rem = line;
            if (rem.isEmpty()) { total++; continue; }
            while (!rem.isEmpty()) {
                if (font.width(plainText(rem)) <= availW) { total++; break; }
                String chunk = font.plainSubstrByWidth(rem, availW);
                if (chunk.isEmpty()) chunk = rem.substring(0, 1);
                total++;
                rem = rem.substring(chunk.length());
            }
        }
        return Math.max(1, total);
    }

    /** Collect all nodes whose center is inside the comment, saving their sortB
     *  and nesting depth. Recursive for nested comments.
     *  收集中心在注释框内的所有节点，保存其 sortB 和嵌套深度。递归处理嵌套注释。
     *  @param comment 父注释节点 / the parent comment node
     *  @param out 输出 map（节点 → sortB）/ output map (node → sortB) */
    private void collectContainedNodes(GraphNode comment, java.util.Map<GraphNode, Integer> out) {
        collectContainedNodesDepth(comment, out, 0);
    }
    /** Recursive depth-aware collection of contained nodes. Depth 0 = directly contained,
     *  higher depths = nested inside inner comments. 递归收集包含节点，带深度感知。*/
    private void collectContainedNodesDepth(GraphNode comment, java.util.Map<GraphNode, Integer> out, int depth) {
        float commentH = fullNodeHeight(comment);
        var candidates = spatialIndex.queryRect(
            comment.x, comment.y, comment.commentWidth, commentH);
        for (var n : candidates) {
            if (n == comment || out.containsKey(n)) continue;
            float nw = NodeRenderer.nw(n);
            float nh = fullNodeHeight(n);
            // Only collect nodes fully inside the comment
            if (n.x >= comment.x && n.x + nw <= comment.x + comment.commentWidth
                && n.y >= comment.y && n.y + nh <= comment.y + commentH) {
                out.put(n, n.sortB);          // save original
                if (n.type == NodeType.COMMENT) {
                    collectContainedNodesDepth(n, out, depth + 1);
                }
            }
        }
    }

    /** Recursively move all nodes whose center is inside the given comment's rectangle.
     *  递归移动中心在给定注释框内的所有节点。
     *  @param comment 父注释节点 / the parent comment node
     *  @param dx X 方向位移（图空间）/ X translation (graph space)
     *  @param dy Y 方向位移（图空间）/ Y translation (graph space) */
    private void moveContainedNodes(GraphNode comment, float dx, float dy) {
        moveContainedNodes(comment, dx, dy, new java.util.HashSet<>());
    }
    /** 内部递归实现，使用 visited set 避免重复移动。 / Internal recursive impl, uses visited set to avoid double-move. */
    private void moveContainedNodes(GraphNode comment, float dx, float dy, java.util.Set<Integer> moved) {
        float commentH = fullNodeHeight(comment);
        var candidates = spatialIndex.queryRect(
            comment.x, comment.y, comment.commentWidth, commentH);
        for (var n : candidates) {
            if (n == comment || moved.contains(n.id)) continue;
            float nw = NodeRenderer.nw(n);
            float nh = fullNodeHeight(n);
            // Only move nodes fully inside the comment (not parent comments that contain it)
            if (n.x >= comment.x && n.x + nw <= comment.x + comment.commentWidth
                && n.y >= comment.y && n.y + nh <= comment.y + commentH) {
                n.x += dx; n.y += dy;
                moved.add(n.id);
                if (n.type == NodeType.COMMENT) {
                    moveContainedNodes(n, dx, dy, moved);
                }
            }
        }
    }

    /** Push aside nodes that the comment rectangle overlaps but does NOT fully contain.
     *  Uses MTV (Minimum Translation Vector) to push nodes out along the shortest axis.
     *  Skips nodes locked by other players. Records original positions for undo.
     *  将被注释矩形覆盖但不完全包含的节点推开。使用 MTV（最小平移向量）沿最短轴推出。
     *  跳过被其他玩家锁定的节点。记录原始位置用于撤销。 */
    private void pushAsideNodes(GraphNode comment, java.util.Set<GraphNode> out) {
        float commentH = fullNodeHeight(comment);
        float x0 = comment.x, y0 = comment.y;
        float x1 = comment.x + comment.commentWidth, y1 = comment.y + commentH;
        // Iterate all nodes directly instead of using spatialIndex.queryRect.
        // The spatial index was built at frame start with old positions and its own
        // nwStatic/nhStatic sizing, which disagrees with NodeRenderer.nw/fullNodeHeight
        // used by moveContainedNodes — causing inconsistent collision detection.
        // 直接遍历所有节点而非使用 spatialIndex.queryRect。
        // spatialIndex 在帧开始时用旧位置和自身的 nwStatic/nhStatic 构建，
        // 与 moveContainedNodes 使用的 NodeRenderer.nw/fullNodeHeight 不一致，
        // 导致碰撞检测不一致。
        for (var n : getGraph().nodes) {
            if (n == comment || out.contains(n)) continue;
            // Comments can nest — don't push aside other comments (they move with their own parent)
            // 注释可以嵌套 — 不推开其他注释（它们随自己的父级移动）
            if (n.type == NodeType.COMMENT) continue;
            if (isNodeLockedByOther(n.id, ownerNodeId())) continue;
            float nw = NodeRenderer.nw(n);
            float nh = fullNodeHeight(n);
            // Skip nodes that were already pushed aside by this drag session
            // 跳过已在本次拖拽中被推开的节点
            float nx0 = n.x, ny0 = n.y, nx1 = n.x + nw, ny1 = n.y + nh;
            // Fully inside → handled by moveContainedNodes, skip
            // 完全在内部 → 由 moveContainedNodes 处理，跳过
            if (nx0 >= x0 && nx1 <= x1 && ny0 >= y0 && ny1 <= y1) continue;
            // No overlap → skip
            // 无重叠 → 跳过
            if (nx0 >= x1 || nx1 <= x0 || ny0 >= y1 || ny1 <= y0) continue;
            // Compute penetration on each axis
            // 计算各轴穿透量
            float penLeft = nx1 - x0, penRight = x1 - nx0;
            float penUp = ny1 - y0, penDown = y1 - ny0;
            float minX = Math.min(penLeft, penRight);
            float minY = Math.min(penUp, penDown);
            float dx, dy;
            if (minX < minY) {
                dx = (penLeft <= penRight ? -penLeft : penRight);
                dy = 0;
            } else {
                dx = 0;
                dy = (penUp <= penDown ? -penUp : penDown);
            }
            // Add margin to prevent re-collision next frame
            // 加间距防止下一帧又相交抖动
            if (dx < -0.01f) dx -= PUSH_MARGIN;
            else if (dx > 0.01f) dx += PUSH_MARGIN;
            if (dy < -0.01f) dy -= PUSH_MARGIN;
            else if (dy > 0.01f) dy += PUSH_MARGIN;
            n.x += dx; n.y += dy;
            // Record original position for undo (only on first push)
            // 记录原始位置用于撤销（仅首次撞开时）
            if (!pushOrigins.containsKey(n.id))
                pushOrigins.put(n.id, new float[]{nx0, ny0});
            out.add(n);
        }
    }

    /** Sort candidates by A-layer first (higher A = visually on top), then B descending within the same A.
     *  先按 A 层排序（A 值越大越靠上），同 A 层内按 B 降序排列。
     *  <p>
     *  A=1 for COMMENT nodes (behind A=3 nodes), A=3 for regular nodes.
     *  This ensures that when a click overlaps both a comment and a regular node,
     *  the regular node (visually on top) is hit first.
     *  注释节点 A=1（在 A=3 的常规节点之后），确保点击同时覆盖注释和常规节点时，
     *  视觉上在上的常规节点优先被命中。
     *  @param a 节点 A / node A
     *  @param b 节点 B / node B
     *  @return 比较结果（负值 a 在前，正值 b 在前）/ comparison result */
    private static int compareHitOrder(GraphNode a, GraphNode b) {
        int aA = a.type == NodeType.COMMENT ? 1 : 3;  // A=1 comments behind A=3 nodes
        int bA = b.type == NodeType.COMMENT ? 1 : 3;
        int cmp = Integer.compare(bA, aA); // higher A first
        if (cmp != 0) return cmp;
        return Integer.compare(b.sortB, a.sortB); // higher B first within same A
    }

    /** 检测鼠标位置下最上层的节点（按 A 层排序，含展开面板高度）。
     *  Hit-test the topmost node under the mouse cursor (sorted by A-layer, includes expanded panel height).
     *  @param mx 鼠标 X（屏幕坐标）/ mouse X (screen coords)
     *  @param my 鼠标 Y（屏幕坐标）/ mouse Y (screen coords)
     *  @return 命中的节点，无命中返回 null / the hit node, or null */
    private GraphNode hitNode(double mx, double my) {
        float scx = s2cX(mx), scy = s2cY(my);
        var candidates = spatialIndex.queryPoint(scx, scy);
        if (candidates.isEmpty()) return null;
        candidates.sort(GraphEditor::compareHitOrder);
        for (var n : candidates) {
            float sx=c2sX(n.x), sy=c2sY(n.y), sw=io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer.nw(n)*zoom;
            float nh = (HH+PH*(n.functionalInputs() + n.outputs()))*zoom+4;
            if (n.type == NodeType.COMMENT) nh = n.commentHeight * zoom;
            if (expandedNodeIds.contains(n.id) && n.type != NodeType.COMMENT)
                nh += EditPanel.calcRenderHeight(n, zoom) * zoom;
            if(mx>=sx&&mx<=sx+sw&&my>=sy&&my<=sy+nh) return n;
        }
        return null;
    }
    /** 检测鼠标位置下的连线（对贝塞尔曲线做逐段距离检测，阈值 12px）。
     *  Hit-test connections under the mouse cursor (segment-by-segment distance check on bezier curves, 12px threshold).
     *  @param mx 鼠标 X（屏幕坐标）/ mouse X (screen coords)
     *  @param my 鼠标 Y（屏幕坐标）/ mouse Y (screen coords)
     *  @return 命中的连线，无命中返回 null / the hit connection, or null */
    private NodeConnection hitConn(double mx, double my) {
        var graph = getGraph();
        NodeConnection best=null;
        float globalMin=12; // 阈值 (Hit threshold)
        for(NodeConnection c:graph.connections){
            GraphNode fn=graph.findNode(c.fromId), tn=graph.findNode(c.toId);
            if(fn==null||tn==null)continue;
            float fx = c2sX(fn.x+NW), fy;
            if (fn.type == NodeType.BUS_IN) {
                fy = c2sY(fn.y + bandPinY(fn, c.fromPin, zoom));
            } else {
                fy = c2sY(fn.y+HH+PH*(fn.functionalInputs() + c.fromPin)+PH/2f);
            }
            float ty;
            if (tn.type == NodeType.BUS_OUT) {
                ty = c2sY(tn.y + bandPinY(tn, c.toPin, zoom));
            } else if (c.toPin < tn.functionalInputs())
                ty=c2sY(tn.y+HH+PH*c.toPin+PH/2f);
            else {
                int pi=c.toPin-tn.functionalInputs();
                ty=c2sY(tn.y+HH+PH*(tn.functionalInputs()+tn.outputs())+4/zoom+pi*18+12);
            }
            float tx=c2sX(tn.x);
            float dx=Math.abs(tx-fx)*0.4f, dist=(float)Math.sqrt((tx-fx)*(tx-fx)+(ty-fy)*(ty-fy));
            int steps=Math.max(10,(int)(dist*0.3f));
            float minDist=Float.MAX_VALUE, px=fx, py=fy;
            for(int i=1;i<=steps;i++){
                float t=i/(float)steps, inv=1-t;
                float nx=inv*inv*inv*fx+3*inv*inv*t*(fx+dx)+3*inv*t*t*(tx-dx)+t*t*t*tx;
                float ny=inv*inv*inv*fy+3*inv*inv*t*fy+3*inv*t*t*ty+t*t*t*ty;
                float segDist=distanceToSegment((float)mx,(float)my,px,py,nx,ny);
                if(segDist<minDist) minDist=segDist; px=nx; py=ny;
            }
            if(minDist<globalMin){globalMin=minDist;best=c;}
        }
        return best;
    }
    /** 计算点到线段的最短距离。 / Compute the shortest distance from a point to a line segment. */
    private static float distanceToSegment(float px,float py,float x1,float y1,float x2,float y2){
        float abx=x2-x1, aby=y2-y1, apx=px-x1, apy=py-y1;
        float dot=apx*abx+apy*aby, len2=abx*abx+aby*aby;
        float t=len2==0?0:Math.max(0,Math.min(1,dot/len2));
        float cx=x1+t*abx, cy=y1+t*aby;
        float dx=px-cx, dy=py-cy;
        return (float)Math.sqrt(dx*dx+dy*dy);
    }

    /** 计算 BUS 编辑面板中第 pinIndex 个 band pin 的本地 Y 偏移（从节点顶部算起） (Calculate local Y offset from node top for band pin at index pinIndex in BUS edit panel) */
    static float bandPinY(GraphNode node, int pinIndex, double zoom) {
        int editLY = (int)(HH + PH * (node.functionalInputs() + node.outputs()) + 4 / zoom);
        return editLY + 30 + pinIndex * 18;
    }

    // ── 封装节点导入/导出 (Encapsulation node import/export) ──────────────────────────────────

    /** 获取封装节点导出目录的默认路径。 / Get the default export directory path for encapsulation nodes.
     *  @return 导出路径（create_schematic_compute/exports/ 目录） */
    private static Path getExportPath() {
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("create_schematic_compute").resolve("exports").resolve("encap_export.nbt");
    }

    /** 将封装节点导出为 NBT 文件。自动跳过重名文件（追加序号），导出时移除调试节点。
     *  Export an encapsulation node as an NBT file. Auto-renames to avoid overwrites, strips debug nodes.
     *  @param node 待导出的封装节点 / the encapsulation node to export
     *  @param name 导出文件名（不含 .nbt 后缀）/ export filename (without .nbt extension) */
    private void exportEncapNode(GraphNode node, String name) {
        if (node.type != NodeType.ENCAPSULATION) return;
        try {
            var level = Minecraft.getInstance().level;
            if (level == null) return;
            Path dir = getExportPath().getParent();
            Files.createDirectories(dir);
            // 同名文件自动追加序号，避免覆盖 (Auto-append sequence number to same-name files to avoid overwrites)
            Path file = dir.resolve(name + ".nbt");
            String finalName = name;
            if (Files.exists(file)) {
                for (int n = 2; n < 1000; n++) {
                    Path alt = dir.resolve(name + "_" + n + ".nbt");
                    if (!Files.exists(alt)) { file = alt; finalName = name + "_" + n; break; }
                }
            }
            // 克隆节点并从子图移除调试节点（导出时跳过调试节点）
            // Clone node and remove debug nodes from sub-graph (skip debug nodes on export)
            GraphNode exportCopy = node.shallowCopyWithNewId(node.id);
            if (exportCopy.subGraph != null) {
                exportCopy.subGraph.nodes.removeIf(n -> n.type.isDebug());
                exportCopy.subGraph.connections.removeIf(c ->
                    exportCopy.subGraph.findNode(c.fromId) == null
                    || exportCopy.subGraph.findNode(c.toId) == null);
                exportCopy.subGraph.rebuildNodeMap();
            }
            CompoundTag tag = exportCopy.save(level.registryAccess());
            NbtIo.writeCompressed(tag, file);
            importFeedbackUntil = System.currentTimeMillis() + 3000;
            saveFeedbackText = "§a" + I18n.get("gui.create_schematic_compute.encap_exported") + ": " + finalName;
        } catch (IOException e) {
            importFeedbackUntil = System.currentTimeMillis() + 3000;
            saveFeedbackText = "§c" + e.getMessage();
        }
    }

    /** 从 NBT 文件导入封装节点，分配新 ID 并加入当前图。
     *  Import an encapsulation node from an NBT file, assign new ID and add to the current graph.
     *  @param file 包含封装节点的 .nbt 文件路径 / path to the .nbt file containing the encapsulation node */
    private void importEncapNode(Path file) {
        try {
            var level = Minecraft.getInstance().level;
            if (level == null) return;
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.create(2 * 1024 * 1024));
            GraphNode imported = GraphNode.load(tag, level.registryAccess());
            // 分配到当前图中，分配新 ID (Assign to current graph with a new ID)
            var g = getGraph();
            imported.id = g.nextNodeId++;
            imported.x = 100; imported.y = 100; // 默认位置 (Default position)
            imported.expanded = false;
            g.nodes.add(imported);
            selectedNode = imported;
            selectedNodes.clear();
            selectedNodes.add(imported);
            importFeedbackUntil = System.currentTimeMillis() + 3000;
            saveFeedbackText = I18n.get("gui.create_schematic_compute.encap_imported");
        } catch (Exception e) {
            importFeedbackUntil = System.currentTimeMillis() + 3000;
            saveFeedbackText = "§c" + I18n.get("gui.create_schematic_compute.encap_import_failed");
        }
    }

    /** 节点渲染常量缓存（避免每次通过 NodeRenderer 引用）/ cached node rendering constants (avoid NodeRenderer indirection each time) */
    static final int NW=NodeRenderer.NW, HH=NodeRenderer.HH, PH=NodeRenderer.PH;

    // ── Fast number formatting to avoid String.format allocation (Phase 1) ──
    // 快速数字格式化，避免 String.format 分配开销
    /** 格式化浮点数为 3 位小数（四舍五入）/ format float to 3 decimal places (rounded) */
    static String ff3(float v) { return Float.toString((float)Math.round(v * 1000) / 1000); }
    /** 格式化 int 为 8 位大写十六进制（前导零补齐）/ format int to 8-char uppercase hex (zero-padded) */
    static String hex8(int v) { String h = Integer.toHexString(v).toUpperCase(); return "00000000".substring(h.length()) + h; }
}
