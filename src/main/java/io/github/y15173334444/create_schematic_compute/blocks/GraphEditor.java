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

    final Host host;
    public final NodeRenderer renderer;
    private final SpatialIndex spatialIndex = new SpatialIndex();
    private Predicate<NodeType> nodeFilter;

    /** 每个图的最大节点数上限（含主图和每个封装子图） (Max nodes per graph, including main graph and each encapsulated sub-graph) */
    public static final int MAX_NODES = 1024;

    // ── op 撤销/重做历史（已拆至 GraphOpHistory，步骤 6c；编辑器与 NodeEditStateFactory 的
    //    recordOp / beginUndoBatch / endUndoBatch 调用点经下列一行委托转发 / op undo/redo history
    //    split into GraphOpHistory (step 6c); the editor's and NodeEditStateFactory's calls go
    //    through the one-line delegates below）──
    final GraphOpHistory history = new GraphOpHistory(this);
    // ── 远端编辑 op 应用器（onRemoteOp，步骤 6c 拆出 / remote op applier split in step 6c）──
    final GraphRemoteApplier remoteApplier = new GraphRemoteApplier(this);

    /** Record an emitted op for per-player undo. Call AFTER sendOp.
     *  If inside a batch, the op is deferred until endUndoBatch(). */
    void recordOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp op,
                          float oldX, float oldY, float oldVal, String oldStr) {
        history.recordOp(op, oldX, oldY, oldVal, oldStr);
    }
    /** Start a batch undo group. All recordOp calls between begin/end are
     *  treated as one atomic undo unit (one Ctrl+Z undoes the whole group).
     *  开始批量撤销组。begin/end 之间的所有 recordOp 调用被视为一个原子撤销单元。 */
    void beginUndoBatch() { history.beginUndoBatch(); }
    /** End a batch undo group. / 结束批量撤销组。 */
    void endUndoBatch() { history.endUndoBatch(); }
    /** Remap a client-assigned temp node ID to the server-assigned real ID（Host handleAck 调用，
     *  实现见 GraphOpHistory / called by the Host's handleAck; see GraphOpHistory). */
    public void remapNodeId(io.github.y15173334444.create_schematic_compute.network.GraphEditAckPacket ack) {
        history.remapNodeId(ack);
    }


    /** Encode control point arrays to a string (x0,y0;x1,y1;...), same format as GraphOp.setCtrlPoints.
     *  将控制点数组编码为字符串 (x0,y0;x1,y1;...)，与 GraphOp.setCtrlPoints 格式相同。
     *  @param cx 控制点 X 坐标数组 / control point X coordinates
     *  @param cy 控制点 Y 坐标数组 / control point Y coordinates
     *  @return 编码后的控制点字符串 / encoded control point string */
    static String encodeCtrlPoints(float[] cx, float[] cy) {
        var sb = new StringBuilder();
        for (int i = 0; i < cx.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(cx[i]).append(',').append(cy[i]);
        }
        return sb.toString();
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
            // 有差异就交给 commitBusBox 裁决：dirty 才落库，否则丢弃并提示（#10）
            // Hand any diff to commitBusBox: dirty commits, otherwise discard + hint (#10).
            if (st.busBox != null && st.busNode != null
                && !st.busBox.getValue().equals(st.busNode.signalName)) {
                bus.commitBusBox(st);
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
     * <p>为什么防抖而不是逐字符：总线名提交（{@code commitBusBox}，现位于
     * {@link GraphBusEditor}）要清旧频道的全局
     * 数据、重评估冲突、按 BUS_IN/BUS_OUT 分别处理频段，最后还会重建编辑区。逐字符触发
     * 会把"abc"打成"a"→"ab"→"abc"三次改名，中间名字全是无效频道，对端 BUS_IN 还会
     * 反复跟着换频段定义。静止 {@value GraphBusEditor#BUS_EDIT_DEBOUNCE_TICKS} tick 后再提交，
     * 一次只发最终值。
     * Why debounce instead of per keystroke: committing a bus name clears the old
     * channel's global data, re-evaluates conflicts, handles bands differently for
     * BUS_IN vs BUS_OUT, and finally rebuilds the edit state. Per-keystroke would
     * rename "a" then "ab" then "abc" — two throwaway channels in the middle, and the
     * peer's BUS_IN would keep swapping its band definition. Waiting for
     * {@value GraphBusEditor#BUS_EDIT_DEBOUNCE_TICKS} idle ticks sends only the final value.
     */
    private void tickDebouncedBusEdits() {
        var states = new java.util.ArrayList<>(nodeEditStatesById.values());
        for (var st : states) {
            if (st.busBox == null || st.busNode == null) continue;
            // 只推进用户敲键产生的改名；非 dirty 的差异不进入防抖（#10）
            // Only user-keystroke renames enter the debounce; non-dirty diffs do not (#10).
            boolean namePending = bus.shouldCommitBusName(
                st.busNameUserDirty, st.busBox.getValue(), st.busNode.signalName);
            boolean bandsPending = bandBoxesPending(st);
            if (!namePending && !bandsPending) { st.busEditIdleTicks = 0; continue; }
            if (++st.busEditIdleTicks < GraphBusEditor.BUS_EDIT_DEBOUNCE_TICKS) continue;
            st.busEditIdleTicks = 0;
            syncBandBoxes(st);
            if (namePending) bus.commitBusBox(st);
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

    // ── 编辑状态 (Edit state) ──
    /** 相机 X 偏移（图空间）/ camera X offset (graph space) */
    public float camX=0, camY=0, zoom=1f;
    /** 相机 Y 偏移（图空间）/ camera Y offset (graph space) */
    /** 缩放级别 0.25x~4x / zoom level 0.25x~4x */
    // ── 视角书签面板 + 相机过渡（已拆至 GraphViewBookmarks；调用点保留原控制流 / view bookmarks + camera transitions split into GraphViewBookmarks; call sites keep the original control flow）──
    final GraphViewBookmarks viewBookmarks = new GraphViewBookmarks(this);
    // ── 总线编辑器（commitBusBox / 冲突重评估 / 频段同步，步骤 6d 拆出 / bus editing split into GraphBusEditor, step 6d）──
    final GraphBusEditor bus = new GraphBusEditor(this);

    /** 清除所有临时视角（客户端断开/切换存档时调用，防止跨存档污染）。
     *  Clear all temp views (called on client disconnect/world switch, prevents cross-world pollution). */
    public static void clearTempView() { GraphViewBookmarks.clearTempView(); }
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
        /** 频道名是否由**用户敲键**产生待提交改动。程序装入的文本（面板重建保留草稿 /
         *  远端 SET_DISPLAY_TEXT 刷新）一律不得回写服务端（issue #10）。
         *  Whether the pending channel-name edit came from a real user keystroke.
         *  Programmatically loaded text (preserved draft on panel rebuild, remote
         *  SET_DISPLAY_TEXT refresh) must never be written back (issue #10). */
        public boolean busNameUserDirty = false;
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
    /** 顶栏方块名框是否正在编辑（图名软锁上报用，GraphPresenceTracker 读取）。
     *  Whether the top-bar block-name box is being edited (read by GraphPresenceTracker for the graph-name soft lock). */
    boolean isGraphNameEditing() { return topBarNameEdit != null && topBarNameEdit.isFocused(); }
    /** EditBox → 提交动作（回车或失焦时执行） (EditBox → commit action, executed on Enter or focus loss) */
    final java.util.Map<net.minecraft.client.gui.components.EditBox, Runnable> enterActions = new java.util.HashMap<>();
    boolean suppressEditBoxResponder = false; // suppress SET_PARAM echo from remote ops (H3) (抑制远程SET_PARAM回显)
    /** 取色器控件（注释节点颜色编辑共用；界面主题色调整已移至 EditorSettingsScreen） / the color picker widget instance
     *  (shared by the comment color popup; editor theme colors moved to EditorSettingsScreen) */
    public final ColorPickerWidget colorPicker = new ColorPickerWidget();
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
    /** 上次记录的鼠标坐标（图空间）/ last recorded mouse position (graph space)
     *  （包级：GraphPresenceTracker 上报临场光标时取用 / package level: read by GraphPresenceTracker for the presence cursor） */
    double lastMouseX, lastMouseY;

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

    // ── P2 Presence（已拆至 GraphPresenceTracker，下方保留门面委托 / split into GraphPresenceTracker; thin facade delegates below）──
    final GraphPresenceTracker presence = new GraphPresenceTracker(this);
    // 拖拽 op 限频（非 presence——供拖拽路径的 sendOp 节流） (Drag op-send throttle, not presence — used by the drag paths)
    private long lastDragSendTime = 0;
    private static final long DRAG_SEND_INTERVAL_MS = 50;

    /** True if any remote player is currently editing the given node (pixel editor etc).
     *  节点软锁（实现见 GraphPresenceTracker）。/ Node soft lock (see GraphPresenceTracker). */
    public boolean isNodeLocked(int nodeId, int owner) { return presence.isNodeLocked(nodeId, owner); }

    /** Store a remote player's presence. Called from packet handler.
     *  包处理器入口（实现见 GraphPresenceTracker）。 */
    public void storeRemotePresence(io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket pkt) { presence.storeRemotePresence(pkt); }

    /** Clear all remote presences (called when editor closes). 编辑器关闭时清空。 */
    public void clearRemotePresences() { presence.clearRemotePresences(); }

    /** 远端临场数据访问器（显示器布局界面的协作叠加层用）。
     *  Accessor for remote presences (used by the monitor screen's display-mode overlay). */
    public java.util.Map<java.util.UUID, io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket> getRemotePresences() {
        return presence.getRemotePresences();
    }

    /** 显示布局组件的软锁：是否有其他玩家正在显示布局模式拖拽该组件。
     *  Display-layout component soft lock (see GraphPresenceTracker). */
    public boolean isDisplayNodeLocked(int nodeId) { return presence.isDisplayNodeLocked(nodeId); }

    /** Remove stale remote presences (>30s). 过期清理（显示器显示模式叠加层也调用）。 */
    public void cleanupStalePresences() { presence.cleanupStalePresences(); }

    /** Send local presence to server (throttled). mouseMoved 与 renderBg 心跳、显示器显示模式
     *  持续调用（实现见 GraphPresenceTracker）。 */
    public void sendPresenceIfNeeded() { presence.sendPresenceIfNeeded(); }

    /** Render remote cursors and online player list (graph-mode overlay; see GraphPresenceTracker).
     *  渲染远程光标与在线玩家列表（图模式协作叠加层），由 renderBg 调用。 */
    public void renderPresenceOverlay(GuiGraphics g) { presence.renderPresenceOverlay(g); }

    // ── Comment node interaction state ──
    private long lastClickTimeMs = 0;
    private int lastClickNodeId = -1;
    /** 正在调整大小的注释节点（包级：GraphOpHistory / GraphRemoteApplier 的 ID 重映射触达 /
     * package level: reached by GraphOpHistory / GraphRemoteApplier ID remapping) */
    GraphNode resizingComment = null;
    private float resizeStartW, resizeStartH;
    private final java.util.Map<Integer, float[]> resizeStartNodePositions = new java.util.HashMap<>();
    /** 正在编辑颜色弹窗的注释节点（包级：GraphViewBookmarks 书签面板点击门禁读取 /
     * package level: read by GraphViewBookmarks' bookmark-panel click gate) */
    GraphNode editingCommentColorNode = null;
    private ColorPickerButton[] commentButtons = null; // created when popup opens
    private final java.util.Map<Integer, Integer> commentScrollOffsets = new java.util.HashMap<>();
    // Scrollbar drag state（注释/导入/书签滚动条共享；包级：GraphViewBookmarks 书签滚动条写入 /
    // shared by the comment/import/bookmark scrollbars; package level: written by GraphViewBookmarks' bookmark scrollbar）
    private GraphNode scrollingComment = null;
    float scrollDragStartY = 0;
    int scrollDragStartOff = 0;
    private boolean scrollingImport = false;
    private boolean scrollingMenu = false;        // 菜单滚动条拖拽 / menu scrollbar drag
    private float menuScrollDragStartY = 0;
    private int menuScrollDragStartOff = 0;

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
    /** 当前正在编辑的封装节点（null = 编辑主图）；包级：历史重映射与远端清理触达 (Currently edited encapsulation node, null = main graph; package level: reached by history remap and remote cleanup) */
    GraphNode encapsulationParent;
    private Predicate<NodeType> mainNodeFilter; // 进入子图前保存的主图过滤器 (Main graph filter saved before entering sub-graph)

    /** 是否正在编辑封装节点的子图（而非主图）。
     *  Whether currently editing an encapsulation node's sub-graph (rather than the main graph). */
    public boolean isInSubGraph() { return encapsulationParent != null; }
    /** -1 for main graph, otherwise the ENCAPSULATION node ID (sub-graph routing).
     *  -1 表示主图，否则为封装节点 ID（子图路由）。 */
    int ownerNodeId() { return isInSubGraph() ? encapsulationParent.id : -1; }
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
        // 子图顶栏没有名称框：进入时交出焦点，避免隐藏的输入框继续吃键盘。
        // The sub-graph top bar has no name box: release focus on entry so the hidden
        // EditBox keeps eating keystrokes.
        if (topBarNameEdit != null) topBarNameEdit.setFocused(false);
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

    /** Apply a remote edit op received from the server (multiplayer collaboration).
     *  （已拆至 GraphRemoteApplier，步骤 6c；此处保留公共委托 / split into GraphRemoteApplier in
     *  step 6c; kept as the public delegate for GraphEditOpSyncPacket.） */
    public void onRemoteOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp op) {
        remoteApplier.applyRemote(op);
    }

    /** 注册 Enter/失焦提交动作 (Register Enter/focus-lost commit action) */
    void registerEnter(net.minecraft.client.gui.components.EditBox eb, Runnable action) {
        enterActions.put(eb, action);
    }

    /** 展开节点的**结构指纹**：任何会改变输入框集合或含义的东西都要进指纹；
     *  漏掉一项，那种变化就再也不会触发重建（反过来，无关变化不该进指纹，否则又会误重建）。
     *  <p><b>契约例外</b>：参数<b>数值</b>不进指纹——值一变就重建会把草稿刷成 {@code ff3}
     *  （{@code "000"}→{@code "0.0"}）。数值同步走 SET_PARAM 草稿 + 失焦归位。</p>
     *  Structural fingerprint of an expanded node: everything that changes the set or the
     *  meaning of its edit boxes. Omit a field here and that change stops rebuilding.
     *  <p><b>Contract exception</b>: param <i>values</i> are excluded — a value change
     *  rebuilds the panel and {@code ff3} wipes drafts ({@code "000"}→{@code "0.0"}).
     *  Value sync rides SET_PARAM drafts + blur normalization instead.</p> */
    private int editStateSignature(GraphNode n) {
        int h = 1;
        h = 31 * h + n.id;
        h = 31 * h + n.type.ordinal();
        h = 31 * h + (n.expanded ? 1 : 0);
        h = 31 * h + n.type.inputs;
        h = 31 * h + n.outputs();
        h = 31 * h + n.inputs();
        h = 31 * h + n.params.length;
        // **不要**把参数数值编进指纹：值一变就 createEditState → setValue(ff3)，
        // 会把正在输入/对端草稿（"000"→"0.0"、"999999999"→"1.0E9"）刷成规范格式。
        // 数值同步走 SET_PARAM 的 stringValue 草稿 + 失焦归位，不经结构重建。
        // Do NOT fold param *values* into the fingerprint: a value change rebuilds
        // the EditState and setValue(ff3) wipes in-progress / peer drafts ("000"→"0.0").
        // Value sync rides SET_PARAM's draft string + blur normalization instead.
        h = 31 * h + (n.signalName == null ? 0 : n.signalName.hashCode());
        h = 31 * h + (n.signalBands == null ? 0 : n.signalBands.size());
        h = 31 * h + (n.formula == null ? 0 : n.formula.hashCode());
        h = 31 * h + n.bandCount();
        if (n.subGraph != null) h = 31 * h + n.subGraph.nodes.size();
        // 参数引脚连线会隐藏对应输入框（连线按稳定 pinId 绑定）
        // A connection on a param pin hides that edit box (bound by stable pinId).
        var g = getGraph();
        if (g != null && g.connections != null) {
            for (var c : g.connections) {
                if (c.toId != n.id) continue;
                int pi = (c.toPinId != null) ? n.inputPinIndex(c.toPinId) : c.toPin;
                if (pi >= n.inputs()) h = 31 * h + pi + 101;
            }
        }
        return h;
    }

    /** 清掉已离开图的节点残留的编辑状态与指纹；折叠但仍在图中的节点保留状态与指纹
     *  （再展开时按指纹复用，或经 createEditState 的旧状态引用保住 busBox 未提交文本），
     *  只把它移出展开集合。返回是否发生了真正移除（需要下一帧再跑一次恢复块）。
     *  Drop edit states and signatures for nodes that LEFT the graph; a collapsed-but-present
     *  node keeps its state and fingerprint (re-expanding then reuses the state via the
     *  fingerprint fast path, or preserves uncommitted busBox text through createEditState's
     *  old-state reference) and only leaves the expanded set. Returns whether anything was
     *  truly removed (another restore pass is needed next frame). */
    private boolean cullStaleEditStates(NodeGraph graph, java.util.Set<Integer> liveExpanded) {
        boolean culled = false;
        var it = nodeEditStatesById.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (graph.findNode(e.getKey()) == null) {
                for (var f : e.getValue().fields) f.setFocused(false);
                it.remove();
                editStateSignatures.remove(e.getKey());
                expandedNodeIds.remove(e.getKey());
                culled = true;
            } else if (!liveExpanded.contains(e.getKey())) {
                // 折叠但仍在图里：状态不删（删了会让再展开丢 busBox 未提交文本），
                // 只退出展开集合；这是幂等操作，不需要触发下一帧恢复。
                // Collapsed but still in the graph: keep the state (dropping it would lose
                // uncommitted busBox text on re-expand), just leave the expanded set;
                // idempotent, so no extra restore pass is triggered.
                expandedNodeIds.remove(e.getKey());
            }
        }
        // 指纹按「仍在图中」保留而不是按展开集合——折叠节点再展开时才能命中指纹复用。
        // Keep fingerprints for nodes still in the graph (not just the expanded ones) so a
        // re-expanded node can hit the fingerprint fast path.
        var liveIds = new java.util.HashSet<Integer>();
        for (var n : graph.nodes) liveIds.add(n.id);
        editStateSignatures.keySet().retainAll(liveIds);
        return culled;
    }

    /** 创建节点的编辑状态（实现拆至 NodeEditStateFactory，docs/gui-decomposition-plan.md 步骤 6a）。
     *  Create a node's edit state (implementation extracted to NodeEditStateFactory). */
    private EditState createEditState(GraphNode node) {
        return NodeEditStateFactory.create(this, node);
    }

    /** 处理 DEBUG_SIGNAL_GEN 模式切换点击（实现拆至 NodeEditStateFactory，步骤 6a）。
     *  Handle DEBUG_SIGNAL_GEN mode-toggle clicks (implementation extracted to NodeEditStateFactory). */
    private void handleModeToggleClick(GraphNode node, EditState st, String hit) {
        NodeEditStateFactory.handleModeToggleClick(this, node, st, hit);
    }

    /** 切换节点展开/折叠（封装节点双击进入子图编辑，其余节点内联展开） (Toggle node expand/collapse; encapsulation nodes enter sub-graph, others inline-expand) */
    private void toggleExpand(GraphNode node) {
        if (presence.isNodeLockedByOther(node.id, ownerNodeId())) return; // soft lock (same scope only)
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

    /** 构造编辑器实例，绑定到宿主屏幕，初始化节点渲染器和临时视角。
     *  Construct an editor instance bound to a host screen; initialize node renderer and temp view.
     *  @param host 实现 Host 接口的宿主屏幕 / the host screen implementing the Host interface
     *  @param screen 当前 Minecraft Screen 实例 / the current Minecraft Screen instance */
    public GraphEditor(Host host, Screen screen) {
        this.host = host;
        this.renderer = new NodeRenderer(this::c2sX, this::c2sY, screen);
        // 临时视角恢复（按方块位置，session 内同方块跨编辑器实例恢复）
        // temporary view restore (keyed by block position, cross-instance within session)
        viewBookmarks.restoreTempView();
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
            h += EditPanel.expandedEditHeight(n, nodeEditStatesById.get(n.id));
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
    /** 上次恢复展开状态时所用的**图实例**：代际计数是 per-instance 的，跨实例比较必然误判
     *  （实测出现 1 -&gt; 2 -&gt; 3 -&gt; 4 -&gt; 1 循环），会退化成「每次都有变化」→ 输入框被反复整批重建。
     *  The graph instance the expanded-state restore last ran against. Generations are
     *  per-instance, so comparing a single int across instances always looks "changed" — the
     *  runtime trace showed 1-2-3-4-1 cycling, which rebuilt every edit box over and over. */
    private NodeGraph lastInitGraph = null;
    /** 图代际变化时置位：下一帧做一次「按节点结构指纹」的增量重建，而不是整批重建。
     *  Set when the current graph generation moved: the next frame performs an incremental,
     *  per-node rebuild instead of recreating every expanded node edit state. */
    private boolean editStatesNeedRebuild = false;
    /** 节点 id → 结构指纹；指纹未变则跳过该节点的输入框重建。
     *  node id -> structural fingerprint; an unchanged fingerprint skips that rebuild. */
    private final java.util.Map<Integer, Integer> editStateSignatures = new java.util.HashMap<>();

    /** 客户端每 tick 调用（由各 Host Screen 的 containerTick 触发）。
     *  - 推进 DEBUG_PROBE 历史采样
     *  - 推进书签视角过渡动画
     *  - 子图模式下从 subOutputs 读取快照值（修复 #18：封装内 DEBUG 节点不可见）
     *  Client tick (called by each Host Screen's containerTick). */
    public void clientTick() {
        viewBookmarks.advanceCameraTransition();
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
        viewBookmarks.saveTempView();
    }

    /** 首次渲染/代际变化时从图数据恢复展开集合与编辑状态；此后只做增量重建（指纹未变则完全
     *  不动输入框）。自 renderBg 逐字抽出（含 expandedInitDone/editStatesNeedRebuild 门），
     *  包级供回归测试在无头环境驱动「进入子图 → 恢复展开状态」全链路。
     *  Restores the expanded set + edit states from graph data on first render / generation
     *  bump; afterwards only incremental rebuilds (unchanged fingerprint → untouched edit
     *  boxes). Moved verbatim out of renderBg (including the expandedInitDone /
     *  editStatesNeedRebuild gate), package-private so regression tests can drive the full
     *  enter-sub-graph → restore chain headlessly. */
    void restoreOrRebuildEditStates() {
        var graph = getGraph();
        if (!expandedInitDone || editStatesNeedRebuild) {
            boolean firstInit = !expandedInitDone;
            expandedInitDone = true;
            editStatesNeedRebuild = false;
            java.util.HashSet<Integer> liveExpanded = new java.util.HashSet<>();
            for (var n : graph.nodes) {
                if (n.expanded && n.type != NodeType.ENCAPSULATION && shouldOpenPanel(n)) {
                    liveExpanded.add(n.id);
                    expandedNodeIds.add(n.id);
                    int sig = editStateSignature(n);
                    Integer prev = editStateSignatures.get(n.id);
                    // 指纹未变且已有状态 → 不重建（这正是「输入中被换掉输入框」的根因对策）
                    // Unchanged fingerprint + existing state -> no rebuild.
                    if (!firstInit && prev != null && prev == sig && nodeEditStatesById.containsKey(n.id)) continue;
                    editStateSignatures.put(n.id, sig);
                    nodeEditStatesById.put(n.id, createEditState(n));
                }
            }
            editStatesNeedRebuild |= cullStaleEditStates(graph, liveExpanded);
        }
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
        viewBookmarks.advanceCameraTransition(); // 每帧推进视角过渡动画 / advance camera transition per frame
        var graph = getGraph();
        // 代际比较必须**绑定同一个图实例**：多实例（主图/子图/重载后的新实例）各有自己的计数器，
        // 跨实例用裸 int 比较会一直「看起来变了」，把展开节点的输入框整批重建（见分析文档第二节取证）。
        // The generation compare is bound to the graph instance: generations are per-instance, so
        // an int-only compare always looks changed and rebuilt every edit box (see the doc).
        if (lastInitGraph != graph) {
            lastInitGraph = graph;
            lastInitGeneration = -1;
            editStatesNeedRebuild = true;
        } else if (lastInitGeneration != graph.graphGeneration) {
            editStatesNeedRebuild = true;
        }
        lastInitGeneration = graph.graphGeneration;
        restoreOrRebuildEditStates();

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
        for (var rp : presence.getRemotePresences().values()) {
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

        // ── 子图 Back 按钮：已合并进顶栏（renderTopBar 子图分支）──
        // Sub-graph Back button: merged into the top bar (renderTopBar sub branch).

        // BUS_IN 的频段**不再**在这里按全局注册表同步（issue #11）。这段代码每帧运行，
        // 会把每个 BUS_IN 的频段改回**本端**注册表那一份并剪掉多余的连线——于是各端注册表
        // 一旦分叉，分岔就会被每帧重新坐实，连服务端下发的权威值也会被它立刻覆盖掉。
        // 现在 BUS_IN 的频段是服务端权威数据：改名时由服务端解析并以 SET_BANDS 下发，
        // 其余时刻随图同步（打开编辑器会拉取权威图）。
        // BUS_IN bands are no longer synced from the global registry here (issue #11). This ran
        // every frame and rewrote each BUS_IN's bands from the **local** registry (pruning the
        // surplus connections), so once registries diverged the divergence was re-asserted every
        // frame — even overwriting the server's authoritative value. A BUS_IN's bands are now
        // authoritative server data: resolved and pushed as SET_BANDS on rename, and otherwise
        // delivered with the graph (opening the editor pulls the authoritative graph).
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
                    bus.syncBusBands(n);
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
        if (!isInSubGraph() && !presence.getRemotePresences().isEmpty()) {
            var occ = new java.util.HashMap<Integer, String>();
            for (var rp : presence.getRemotePresences().values()) {
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
            renderer.showBookmarkPanel = viewBookmarks.panelVisible();
            renderer.renderButtons(g, true, host.isRunning(), cycleWarning, saveFeedbackUntil, gridSnapEnabled, 0, host.asScreen().width, host.asScreen().height);
            // 导入/导出封装节点按钮（仅蓝图计算机显示） (Import/export encapsulation node buttons, Blueprint computer only)
            if (host instanceof BlueprintScreen) {
                var mc = Minecraft.getInstance();
                int btnY = NodeRenderer.isToolbarBottom() ? host.asScreen().height - 22 : TOP_BAR_H + 2;
                int impX = 196, impW = 72, btnH = 18;
                // 仅选中单个封装节点时显示导出，否则显示导入 (Show export when single encapsulation node selected, otherwise show import)
                boolean hasSingleEncap = selectedNode != null && selectedNode.type == NodeType.ENCAPSULATION && selectedNodes.size() == 1;
                if (hasSingleEncap) {
                    g.fill(impX, btnY, impX + impW, btnY + btnH, 0xFF2A3A1A);
                    g.renderOutline(impX, btnY, impW, btnH, NodeRenderer.CSB());
                    g.renderOutline(impX + 1, btnY + 1, impW - 2, btnH - 2, 0xFF2A2822);
                    g.drawString(mc.font, "§a" + I18n.get("gui.create_schematic_compute.encap_export"), impX + 4, btnY + 4, 0xFFFFFFFF, false);
                } else {
                    g.fill(impX, btnY, impX + impW, btnY + btnH, NodeRenderer.PBG());
                    g.renderOutline(impX, btnY, impW, btnH, NodeRenderer.CSB());
                    g.renderOutline(impX + 1, btnY + 1, impW - 2, btnH - 2, 0xFF2A2822);
                    g.drawString(mc.font, "§b" + I18n.get("gui.create_schematic_compute.encap_import"), impX + 4, btnY + 4, 0xFFFFFFFF, false);
                }
            }
        }
        // 封装模式标识已合并进顶栏（renderTopBar 子图分支）——不再在这里画压在顶栏上的横条。
        // The encapsulation-mode indicator is merged into the top bar (renderTopBar sub
        // branch) — no more separate strip drawn over it here.
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
            g.fill(cx, cy, cx + w, cy + h, NodeRenderer.withAlpha(NodeRenderer.PBG(), 0xEE));
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
        // 书签列表面板 + 命名对话框（已拆至 GraphViewBookmarks，逐字搬迁 / split into GraphViewBookmarks, bodies moved verbatim）
        viewBookmarks.render(g, mx, my);
        // 导入封装节点对话框 (Import encapsulation node dialog)
        if (showImportDialog) {
            var mc = Minecraft.getInstance();
            int w = 280, visRows = 8;
            int fileCount = importFiles != null ? importFiles.size() : 0;
            int listH = Math.min(fileCount, visRows) * 18;
            int h = 56 + listH + 30; // 标题 + 列表 + 按钮区 (Title + list + button area)
            int cx = (host.asScreen().width - w) / 2, cy = (host.asScreen().height - h) / 2;
            g.fill(cx, cy, cx + w, cy + h, NodeRenderer.withAlpha(NodeRenderer.PBG(), 0xEE));
            g.renderOutline(cx, cy, w, h, NodeRenderer.CSB());
            g.drawString(mc.font, I18n.get("gui.create_schematic_compute.encap_import"), cx + 8, cy + 6, NodeRenderer.ACC(), false);
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
                    if (hover) g.fill(cx + 4, ry, cx + w - 20, ry + 16, NodeRenderer.HOV());
                    g.drawString(mc.font, (hover ? "§e" : "§7") + name, cx + 8, ry + 3, 0xFFFFFFFF, false);
                }
                // 右侧滚动条 (Right-side scrollbar)
                if (maxScroll > 0) {
                    int sbX = cx + w - 14, sbY = listY, sbH = visRows * 18;
                    g.fill(sbX, sbY, sbX + 8, sbY + sbH, 0xFF2A2822);
                    float thumbTop = sbY + (float) importScrollOff / maxScroll * (sbH - 12);
                    g.fill(sbX + 1, (int) thumbTop, sbX + 7, (int) thumbTop + 12, NodeRenderer.CSB());
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
                    g.renderOutline(hx, py + 16, 18, 18, NodeRenderer.CB());
                    var item = mc.player.getInventory().items.get(i);
                    if (!item.isEmpty()) { com.mojang.blaze3d.systems.RenderSystem.depthMask(false); g.renderItem(item, hx + 1, py + 17); com.mojang.blaze3d.systems.RenderSystem.depthMask(true); }
                }
            }
        }
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
            g.renderOutline(px, py, pw, ph, NodeRenderer.CSB());
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
        // 顶栏（名称 + 设置）固定在节点/工具栏之上。
        // Top bar (name + settings) sits above nodes and the toolbar.
        renderTopBar(g, mx, my);
        // 添加节点菜单最后画在顶栏之上：菜单是最高优先级浮层（命中顺序与此一致）。
        // Add-node menu draws last, above the top bar: it is the topmost overlay
        // (hit-testing follows the same order).
        if(showMenu) { selectedMenuType = renderer.renderAddNodeMenu(g, menuX, menuY, mx, my, nodeFilter); }
        // 框选矩形 (Box-select rectangle)
        if (boxSelecting) {
            float x1 = Math.min(boxSX, boxEX), y1 = Math.min(boxSY, boxEY);
            float x2 = Math.max(boxSX, boxEX), y2 = Math.max(boxSY, boxEY);
            g.fill((int)x1, (int)y1, (int)x2, (int)y2, 0x22D4A017);
            g.renderOutline((int)x1, (int)y1, (int)(x2-x1), (int)(y2-y1), NodeRenderer.ACC());
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
    /** 打开独立全屏设置界面（仅客户端 —— Screen 构造在专用服务器会崩，故经 @OnlyIn
     *  方法隔离；会话语义见 EditorSettingsScreen 的类注释）。
     *  Opens the standalone full-screen settings GUI (client only — constructing a
     *  Screen would crash a dedicated server, hence the @OnlyIn method; session
     *  semantics are documented on EditorSettingsScreen). */
    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    private void openSettingsScreen() {
                Minecraft.getInstance().setScreen(new EditorSettingsScreen(host.asScreen()));
    }

    public boolean mouseClicked(double mx, double my, int btn) {
        history.resetBatch(); // discard any incomplete batch to prevent undo stack freeze
        var graph = getGraph();
        // 添加节点菜单面板内点击优先于顶栏/工具栏——菜单最后渲染、盖在顶栏上，
        // 命中顺序与视觉层级一致。面板内非左键也吞掉（含右键：不再重开/定位菜单）。
        // In-panel add-menu clicks beat the top bar / toolbar — the menu renders last,
        // covering the top bar, and hits follow that stack. Non-left clicks are absorbed
        // too (including right-click: it no longer re-opens/repositions the menu).
        if (showMenu && renderer.isMenuHit(mx, my)) {
            // 先提交失焦编辑框，再交菜单（旧路径在菜单前有一轮 commitFocusedEnterActions）
            // Commit unfocused edit boxes first (the old path ran commitFocusedEnterActions
            // before the menu).
            commitFocusedEnterActions();
            tryAddMenuClick(mx, my, btn, graph);
            return true;
        }
        if (tryTopBarClick(mx, my, btn)) return true;
        // 命名对话框：确认/取消按钮、框内点击消费（模态）、点击外部取消（已拆至 GraphViewBookmarks）
        // Name dialog: Confirm/Cancel buttons, in-dialog clicks consumed (modal), outside click cancels
        if (viewBookmarks.handleNameDialogClick(mx, my)) return true;
        // 书签面板交互（仅在面板显示、无弹窗、无命名对话框时；门禁随方法内迁）
        if (viewBookmarks.handlePanelClick(mx, my, btn)) return true;
        if (tryDebugChartClick(mx, my, btn, graph)) return true;
        if (tryCommentColorPopupClick(mx, my, btn)) return true;
        if (tryExportDialogClick(mx, my, btn)) return true;
        if (tryImportDialogClick(mx, my, btn)) return true;
        commitFocusedEnterActions();
        if (tryChromeClick(mx, my, btn, graph)) return true;
        if (tryAddMenuClick(mx, my, btn, graph)) return true;
        // 上下文菜单键（默认右键，可重绑；查表）
        // Context-menu button (right by default, rebindable; looked up).
        if(btn == EditorKeys.mouseButton(EditorKeys.Action.CONTEXT_MENU)){
            menuX=(float)mx; menuY=(float)my; showMenu=true; renderer.resetMenuSearch(); return true;
        }
        if (tryHotbarPopupClick(mx, my, btn)) return true;
        cancelKeyboardBinding(mx, my, btn);
        // Color picker (after panels — absorbs clicks on picker, closes if outside)
        if (colorPicker.isVisible()) {
            return colorPicker.mouseClicked(mx, my, btn);
        }
        // 左键 = 完整交互；重绑后的平移键（非左键）= 只允许在注释体或空白画布上启动平移，
        // 下方所有交互子判断（编辑区 / 引脚 / 节点拖动 / 选中提交）全部跳过。
        // Left button = full interaction; the rebound pan button (non-left) may only start
        // panning on a comment body or blank canvas — every interactive sub-check below
        // (edit areas, pins, node drags, selection & commits) is skipped.
        boolean panOnlyClick = btn != 0 && btn == EditorKeys.mouseButton(EditorKeys.Action.PAN);
        if(btn==0 || panOnlyClick){
            showMenu=false;
            // 重绑平移键不作用于任何 chrome：工具栏 / 子图 Back / 右下角按钮上按下不平移。
            // The rebound pan button never acts on chrome: no panning over the toolbar,
            // the sub-graph Back button or the bottom-right corner buttons.
            if (panOnlyClick) {
                int scrW = host.asScreen().width, scrH = host.asScreen().height;
                int toolY = NodeRenderer.isToolbarBottom() ? scrH - 22 : TOP_BAR_H + 2;
                boolean overToolbar = !isInSubGraph() && my >= toolY && my <= toolY + 18 && mx >= 4
                    && mx <= (host instanceof BlueprintScreen ? 326 : 250);
                boolean overBack = isInSubGraph() && mx >= scrW - 52 && mx <= scrW - 6
                    && my >= 3 && my <= 19;
                boolean overCorner = mx >= scrW - 22 && my >= scrH - 44;
                if (overToolbar || overBack || overCorner) return true;
            }
            // 预计算 z-order 排序候选（供每个展开节点做遮挡判断） (Pre-compute z-order sorted candidates for occlusion checks on each expanded node)
            var clickCandidates = spatialIndex.queryPoint(s2cX(mx), s2cY(my)).stream()
                .sorted(GraphEditor::compareHitOrder)
                .collect(java.util.stream.Collectors.toList());
        if (tryExpandedEditAreaClicks(mx, my, panOnlyClick, clickCandidates, graph)) return true;
        if (tryTabInteractions(mx, my, panOnlyClick, graph)) return true;
            // ▶/▼ 折叠展开按钮（优先检测，不依赖选中状态） (Expand/collapse button, checked first, independent of selection state)
            var expandHit = panOnlyClick ? null : hitExpandIndicator(mx, my, graph);
            if (expandHit != null) { toggleExpand(expandHit); return true; }
        var nonCommentHit = hitNode(mx, my);
        if (tryCommentClick(mx, my, btn, panOnlyClick, nonCommentHit)) return true;
        if (tryWirePinClick(mx, my, panOnlyClick)) return true;
        if (tryNodeClickAndBlank(mx, my, btn, panOnlyClick, nonCommentHit)) return true;
        }
        // busBox 失焦提交（在按钮处理之后，避免 createEditState 冲掉频段编辑） (busBox focus-lost commit, after button handling to avoid createEditState overwriting band edits)
        // 注：已提交的 busBox 不再 isFocused，此循环无副作用；保留以防其他路径需要。
        for (var st : nodeEditStatesById.values()) {
            if (st.busBox != null && st.busBox.isFocused() && !st.busBox.getValue().equals(st.busNode.signalName))
                { bus.commitBusBox(st); break; }
        }
        return false;
    }

    /** 6f 拆出：原 mouseClicked 内联块，逐字搬迁（docs/gui-decomposition-plan.md 步骤 6f）。
     *  6f extraction: a verbatim inline block of the former mouseClicked. */
    private boolean tryNodeClickAndBlank(double mx, double my, int btn, boolean panOnlyClick, GraphNode nonCommentHit) {
    // 点击节点（不含 ▶/▼ 区域） (Click node, excluding expand indicator area)
    // 重绑平移键：不选中不拖动节点 —— 命中与否由下方非空判断统一处理。
    // Rebound pan button: never selects or drags a node — handled by the
    // not-blank guard below.
    var hit = panOnlyClick ? null : hitNode(mx,my);
    if(hit!=null && presence.isNodeLockedByOther(hit.id, ownerNodeId())) hit = null; // soft lock (same scope only)
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
    // 重绑平移键停在节点/注释上（非空白、非注释体）→ 什么都不做。
    // The rebound pan button over a node/comment (not blank, not a comment body) → no-op.
    if (panOnlyClick && nonCommentHit != null) return true;
    // 点击空白区域 → 先提交未保存的 busBox（回车以外的提交途径），再取消选中。
    // 修复：原逻辑 syncEditStateToSelection 先清除所有控件 focus，导致后续
    // busBox.isFocused() 检查失败，点击空白处提交无反应。
    // Click empty area -> commit any unsaved busBox FIRST (the non-Enter commit
    // path), then deselect. Fix: syncEditStateToSelection used to clear every
    // control's focus first, so the later busBox.isFocused() check failed and
    // clicking empty did nothing. Use a snapshot copy because commitBusBox
    // rebuilds the edit state (modifies nodeEditStatesById) during iteration.
    // （重绑平移键：跳过 busBox 提交与取消选中 —— 纯平移无副作用）
    // (Rebound pan button: skip busBox commits & deselection — panning only, no side effects)
    if (!panOnlyClick) {
        for (var st : java.util.List.copyOf(nodeEditStatesById.values())) {
            // 不依赖 isFocused()：mouseClicked 更早的编辑框处理已 setFocused(false)。
            // 只要 busBox 值 != 当前 signalName（用户改了名未提交），点击空白即提交。
            // Do not rely on isFocused(): earlier edit-box handling in mouseClicked
            // already cleared focus. Commit whenever the box value differs from the
            // node's signalName (the user typed a new name but didn't Enter).
            if (st.busBox != null && st.busNode != null
                && !st.busBox.getValue().equals(st.busNode.signalName)) {
                bus.commitBusBox(st);
            }
        }
        selectedNodes.clear(); selectedNode=null;
        syncEditStateToSelection(); // 取消选中后，同步清掉所有节点的控件状态
    }
    // 平移只在平移键上启动（默认左键，可重绑）——重绑后左键点空白不再平移。
    // Panning starts on the pan button only (left by default, rebindable) — after a
    // rebind, left-click on blank canvas no longer pans.
    if (btn == EditorKeys.mouseButton(EditorKeys.Action.PAN)) {
        panning=true; panLastX=(float)mx; panLastY=(float)my;
    }
        return false;
    }

    /** 6f 拆出：原 mouseClicked 内联块，逐字搬迁（docs/gui-decomposition-plan.md 步骤 6f）。
     *  6f extraction: a verbatim inline block of the former mouseClicked. */
    private boolean tryWirePinClick(double mx, double my, boolean panOnlyClick) {
    // BUS_IN edit area output pins — spatial-index aware for occlusion
    // （重绑平移键：跳过引脚连线拖动 / rebound pan button: skip pin wire drags）
    var pinCandidates = spatialIndex.queryPoint(s2cX(mx), s2cY(my));
    if (panOnlyClick) pinCandidates.clear();
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
        return false;
    }

    /** 6f 拆出：原 mouseClicked 内联块，逐字搬迁（docs/gui-decomposition-plan.md 步骤 6f）。
     *  6f extraction: a verbatim inline block of the former mouseClicked. */
    private boolean tryCommentClick(double mx, double my, int btn, boolean panOnlyClick, GraphNode nonCommentHit) {
    // ── Comment node interaction / 注释节点交互 ──
    // Only handle clicks on COMMENT chrome (resize, color dot) or
    // body clicks — spatial-index candidates sorted by compareHitOrder
    // (A=3 nodes first, then A=1 comments by B descending = innermost first)
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
        if (!panOnlyClick && !n2.displayText.isEmpty()) {
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
        if (onResize && !panOnlyClick) {
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
        if (onColorDot && !panOnlyClick) {
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
        if (!panOnlyClick && n2.id == lastClickNodeId && (now2 - lastClickTimeMs) < 400) {
            toggleExpand(n2);
            lastClickNodeId = -1;
            return true;
        }
        lastClickTimeMs = now2; lastClickNodeId = n2.id;
        // Only drag by header bar; expanded comments stay expanded — absorb click
        if (hitIsNonComment) continue;
        if (presence.isNodeLockedByOther(n2.id, ownerNodeId())) continue; // soft lock (same scope only)
        if (expandedNodeIds.contains(n2.id)) {
            // 展开注释的正文就是编辑区 —— 平移键既不平移也不选中。
            // An expanded comment's body IS its edit area — the pan button neither pans nor selects.
            if (panOnlyClick) continue;
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
            // 非平移键（重绑后的左键）点正文：只选中，不平移。
            // Non-pan button (left click after a rebind): select only, no panning.
            if (btn != EditorKeys.mouseButton(EditorKeys.Action.PAN)) {
                if (selectedNode != n2) {
                    selectedNode = n2; selectedNodes.clear(); selectedNodes.add(n2);
                }
                return true;
            }
            // 平移键（默认左键）点正文：选中并允许平移穿过；
            // 重绑后（panOnlyClick）只平移不选中。
            // Pan button (left by default): a body click selects and lets panning
            // through; when rebound (panOnlyClick) it pans without selecting.
            if (!panOnlyClick && selectedNode != n2) {
                selectedNode = n2; selectedNodes.clear(); selectedNodes.add(n2);
            }
            panning = true; panLastX = (float) mx; panLastY = (float) my;
            return true;
        }
        // 标题栏等 chrome 只认左键 —— 平移键不拖动注释。
        // Header chrome is left-button only — the pan button never drags a comment.
        if (panOnlyClick) continue;
        // Header click → drag / select
        if (!tabHeld) {
            if (selectedNode != n2) { selectedNode = n2; selectedNodes.clear(); selectedNodes.add(n2); }
        } else {
            if (selectedNodes.contains(n2)) selectedNodes.remove(n2);
            else selectedNodes.add(n2);
            selectedNode = selectedNodes.isEmpty() ? null : selectedNodes.iterator().next();
            if (selectedNodes.isEmpty()) {
                // 平移只在平移键上启动 —— 重绑后左键清空多选不再引发粘滞平移。
                // Panning starts on the pan button only — after a rebind, emptying the
                // multi-selection with left-click no longer causes sticky panning.
                if (btn == EditorKeys.mouseButton(EditorKeys.Action.PAN)) {
                    panning = true; panLastX = (float)mx; panLastY = (float)my;
                }
                return true;
            }
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
        return false;
    }

    /** 6f 拆出：原 mouseClicked 内联块，逐字搬迁（docs/gui-decomposition-plan.md 步骤 6f）。
     *  6f extraction: a verbatim inline block of the former mouseClicked. */
    private boolean tryTabInteractions(double mx, double my, boolean panOnlyClick, NodeGraph graph) {
    // TAB+左键 → 连线删除 / 多选 / 框选 (TAB+left-click → connection delete / multi-select / box-select)
    if (tabHeld && !panOnlyClick) {
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
        return false;
    }

    /** 6f 拆出：原 mouseClicked 内联块，逐字搬迁（docs/gui-decomposition-plan.md 步骤 6f）。
     *  6f extraction: a verbatim inline block of the former mouseClicked. */
    private boolean tryExpandedEditAreaClicks(double mx, double my, boolean panOnlyClick, java.util.List<GraphNode> clickCandidates, NodeGraph graph) {
    // 内联编辑区交互（局部坐标，与 pose 内渲染一致） (Inline edit-area interaction, local coords matching pose rendering)
    for (var en : getGraph().nodes) {
        if (panOnlyClick) break; // 重绑平移键不进编辑区交互 / the rebound pan button never enters edit areas
        if (!expandedNodeIds.contains(en.id)) continue;
        if (presence.isNodeLockedByOther(en.id, ownerNodeId())) continue; // soft lock (same scope only)
        // 逐个检查：是否有更高 z-order 的非 Comment 节点实际遮挡了点击位置 (Check: does a higher-z non-Comment node actually occlude the click?)
        boolean occluded = false;
        for (var n : clickCandidates) {
            if (n == en) break; // 到达当前节点，上方无遮挡 (Reached current node, no occluder above)
            if (n.type == NodeType.COMMENT) continue;
            float sx = c2sX(n.x), sy = c2sY(n.y);
            float sw = NodeRenderer.nw(n) * zoom;
            float nh = NodeRenderer.nh(n) * zoom + 4; // nh() 含图表区域 / includes chart area
            if (expandedNodeIds.contains(n.id))
                nh += EditPanel.expandedEditHeight(n, nodeEditStatesById.get(n.id)) * zoom;
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
        // 正/反转按钮：只翻 rev 槽（SET_PARAM 精确写 0/1），不改数值 EditBox——数值可被
        // 引脚覆盖，翻符号会改到一个运行时被忽略的陈旧值。MOVE/ROTATE 的 rev 在 params[1]
        // （params[0] 是可接线的行程数）。
        // Forward/reverse: SET_PARAM only on the rev slot (exact 0/1) — never touches
        // the value EditBox (wire-overridable; flipping its sign would edit a value
        // the runtime ignores). MOVE/ROTATE keep rev at params[1] (params[0] is the
        // wireable travel amount).
        if (en.type == NodeType.MOVE || en.type == NodeType.ROTATE
            || en.type == NodeType.TX_OUT || en.type == NodeType.SPEED_CTRL) {
            int revLocalY = editLocalY + 4 + numRows * 18;
            if (lmx >= 4 && lmx <= NW - 4 && lmy >= revLocalY && lmy <= revLocalY + 16) {
                int revIdx = (en.type == NodeType.MOVE || en.type == NodeType.ROTATE) ? 1 : 0;
                if (en.params.length > revIdx) {
                    float oldR = en.params[revIdx];
                    float newR = oldR > 0.5f ? 0f : 1f;
                    en.params[revIdx] = newR;
                    var sOp = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setParam(
                        host.getBlockPos(), ownerNodeId(), en.id, revIdx, newR, host.getPlayerUUID());
                    host.sendOp(sOp);
                    recordOp(sOp, 0, 0, oldR, null);
                }
                return true;
            }
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
                bus.commitBusBox(st);
        }
        if ((en.type == NodeType.BUS_IN || en.type == NodeType.BUS_OUT) && st.bandAddBtnW > 0) {
            if (lmx >= st.bandAddBtnX && lmx <= st.bandAddBtnX + st.bandAddBtnW
                && lmy >= st.bandAddBtnY && lmy <= st.bandAddBtnY + st.bandAddBtnH) {
                // + 按钮：添加新频段，同步同总线名节点 (+ button: add new band, sync same-bus-name nodes)
                if (en.signalBands == null) en.signalBands = new java.util.ArrayList<>();
                String name = "band_" + en.signalBands.size();
                en.signalBands.add(name);
                en.bandsDirty = true;
                bus.syncBusBands(en);
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
                    bus.syncBusBands(en);
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
        return false;
    }

    /** 6f 拆出：原 mouseClicked 内联块，逐字搬迁（docs/gui-decomposition-plan.md 步骤 6f）。
     *  6f extraction: a verbatim inline block of the former mouseClicked. */
    private void cancelKeyboardBinding(double mx, double my, int btn) {
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
    }

    /** 6f 拆出：原 mouseClicked 内联块，逐字搬迁（docs/gui-decomposition-plan.md 步骤 6f）。
     *  6f extraction: a verbatim inline block of the former mouseClicked. */
    private boolean tryHotbarPopupClick(double mx, double my, int btn) {
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
        return false;
    }

    /** 6f 拆出：原 mouseClicked 内联块，逐字搬迁（docs/gui-decomposition-plan.md 步骤 6f）。
     *  6f extraction: a verbatim inline block of the former mouseClicked. */
    private boolean tryAddMenuClick(double mx, double my, int btn, NodeGraph graph) {
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
        return false;
    }

    /** 6f 拆出：原 mouseClicked 内联块，逐字搬迁（docs/gui-decomposition-plan.md 步骤 6f）。
     *  6f extraction: a verbatim inline block of the former mouseClicked. */
    private boolean tryChromeClick(double mx, double my, int btn, NodeGraph graph) {
    if(btn==0){
        // 子图 Back 点击已合并进顶栏（tryTopBarClick 子图分支，几何与渲染一致）。
        // Sub-graph Back click lives in the top bar now (tryTopBarClick sub branch,
        // geometry matches the rendering).
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
            // 导入/导出封装节点按钮（仅蓝图计算机） (Import/export encapsulation node button, Blueprint computer only)
            if (host instanceof BlueprintScreen && mx >= 196 && mx <= 268 && my >= btnY && my <= btnY + 18) {
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
        // 右下角书签按钮（在三角形上方；已拆至 GraphViewBookmarks / split into GraphViewBookmarks）
        if (viewBookmarks.handleBookmarkButtonToggle(mx, my)) return true;
        // 右下角工具栏位置切换按钮（始终可见） (Bottom-right toolbar position toggle, always visible)
        { int w = host.asScreen().width, h = host.asScreen().height;
          if(mx>=w-22&&mx<=w-4&&my>=h-22&&my<=h-4){NodeRenderer.toggleToolbarBottom();return true;} }
    }
        return false;
    }

    /** 6f 拆出：原 mouseClicked 内联块，逐字搬迁（docs/gui-decomposition-plan.md 步骤 6f）。
     *  6f extraction: a verbatim inline block of the former mouseClicked. */
    private void commitFocusedEnterActions() {
    // 失焦提交：enterActions（频段 EditBox 等通过 enterActions 注册的控件） (Focus-lost commit via enterActions for band EditBoxes etc. registered via enterActions)
    boolean committed = false;
    for (var e : enterActions.entrySet()) {
        if (e.getKey().isFocused()) { e.getValue().run(); committed = true; break; }
    }
    if (committed) markDirty();
    }

    /** 6f 拆出：原 mouseClicked 内联块，逐字搬迁（docs/gui-decomposition-plan.md 步骤 6f）。
     *  6f extraction: a verbatim inline block of the former mouseClicked. */
    private boolean tryImportDialogClick(double mx, double my, int btn) {
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
        return false;
    }

    /** 6f 拆出：原 mouseClicked 内联块，逐字搬迁（docs/gui-decomposition-plan.md 步骤 6f）。
     *  6f extraction: a verbatim inline block of the former mouseClicked. */
    private boolean tryExportDialogClick(double mx, double my, int btn) {
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
        return false;
    }

    /** 6f 拆出：原 mouseClicked 内联块，逐字搬迁（docs/gui-decomposition-plan.md 步骤 6f）。
     *  6f extraction: a verbatim inline block of the former mouseClicked. */
    private boolean tryCommentColorPopupClick(double mx, double my, int btn) {
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
        return false;
    }

    /** 6f 拆出：原 mouseClicked 内联块，逐字搬迁（docs/gui-decomposition-plan.md 步骤 6f）。
     *  6f extraction: a verbatim inline block of the former mouseClicked. */
    private boolean tryDebugChartClick(double mx, double my, int btn, NodeGraph graph) {
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
        return false;
    }

    /** 6f 拆出：原 mouseClicked 内联块，逐字搬迁（docs/gui-decomposition-plan.md 步骤 6f）。
     *  6f extraction: a verbatim inline block of the former mouseClicked. */
    private boolean tryTopBarClick(double mx, double my, int btn) {
    // ── 顶栏（最上层，先于一切命中检测）──
    //    Top bar (topmost layer — hit-tested before everything else).
    // 子图：Back 占用设置按钮的同一槽位（与 renderTopBar 的子图分支几何一致）；
    // 其余顶栏点击整体吞掉，图名框不参与（子图顶栏没有名称框）。
    // Sub-graph: Back occupies the settings slot (geometry matches renderTopBar's sub
    // branch); all other top-bar clicks are consumed and the name box takes no part.
    if (isInSubGraph() && my < TOP_BAR_H) {
        int sbX = host.asScreen().width - 52;
        if (mx >= sbX && mx <= sbX + 46 && my >= 3 && my <= 19) {
            exitSubGraph();
        }
        return true;
    }
    if (topBarNameEdit != null && my < TOP_BAR_H) {
        int sbX = host.asScreen().width - 52;
        if (mx >= sbX && mx <= sbX + 46 && my >= 3 && my <= 19) {
            // 打开独立全屏设置界面。收起其下所有浮层；setScreen 只触发本屏
            // removed()（不发 LeavePacket），编辑会话保持，返回时 init 幂等重 join。
            // Open the standalone full-screen settings GUI. Collapse every floating
            // panel; setScreen only fires this screen's removed() (no LeavePacket),
            // so the edit session survives and returning re-joins idempotently.
            showMenu = false;
            colorPicker.close();
            openSettingsScreen();
            return true;
        }
        for (var st : nodeEditStatesById.values()) for (var f : st.fields) f.setFocused(false);
        // 方块名被他人软锁时不夺取焦点（renderTopBar 的强制只读也会在下一帧交出焦点，
        // 这里直接不进入，避免一帧闪烁）。
        // While the block name is soft-locked by someone else, don't steal focus here (the
        // read-only enforcement in renderTopBar would drop it next frame anyway) — avoids a
        // one-frame flicker.
        if (!presence.isGraphNameLocked()) {
            topBarNameEdit.setFocused(true);
            topBarNameEdit.mouseClicked(mx, my, btn);
        }
        return true;
    }
    if (topBarNameEdit != null && topBarNameEdit.isFocused()) topBarNameEdit.setFocused(false);
        return false;
    }

    // commitBusBox / releaseOldBusName / clearBusNode / reevaluateBusConflicts / syncBusBands
    // 已拆至 GraphBusEditor（docs/gui-decomposition-plan.md 步骤 6d），调用点经 bus.* 转发。
    // Split into GraphBusEditor (step 6d); call sites go through bus.*.
    // 原 reevaluateBusConflictsForBus 公共委托已随 issue #12 删除：频段同步包不再参与冲突判定
    //（冲突状态改由服务端权威值随图同步；客户端只合并可本地证明的「同图重名」）。
    // The reevaluateBusConflictsForBus delegate was removed with issue #12: a band-sync packet no
    // longer takes part in conflict evaluation (the state now comes from the authoritative
    // server value synced with the graph; the client only merges locally provable duplicate names).

    /** 子类可重写定义哪些节点左键打开编辑面板 (Override to define which nodes open edit panel on left-click) */

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
        if (viewBookmarks.handleRelease(my)) return;
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
        // 平移键松开 → 结束平移（按键可能被重绑，查表而非写死左键）
        // Pan button released → stop panning (rebindable; look it up, don't hardcode).
        }if(btn == EditorKeys.mouseButton(EditorKeys.Action.PAN)&&panning)panning=false;
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
        // 书签拖拽排序 / bookmark drag reorder（已拆至 GraphViewBookmarks / split into GraphViewBookmarks）
        if (viewBookmarks.handleDragReorder(my)) return;
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
        if (viewBookmarks.handleScrollbarDrag(my)) return;
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
        if (showExportDialog) return true;
        // 书签面板滚动 / bookmark panel scroll（已拆至 GraphViewBookmarks / split into GraphViewBookmarks）
        if (viewBookmarks.handleScroll(mx, sy)) return true;
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
        // 键位绑定查表用的修饰键快照 / modifier snapshot for the binding lookups
        boolean modC = net.minecraft.client.gui.screens.Screen.hasControlDown();
        boolean modS = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        boolean modA = net.minecraft.client.gui.screens.Screen.hasAltDown();
        // D: 搜索框菜单键盘 / search box menu keyboard
        if (showMenu) {
            if (renderer.isMenuSearchFocused()) {
                if (key == 256) { renderer.setMenuSearchFocused(false); return true; } // Esc unfocus
                if (key == 259) { renderer.menuSearchBackspace(); return true; }       // Backspace
            } else {
                if (key == 256) { showMenu = false; return true; } // Esc close menu
            }
        }
        // 书签命名对话框 / bookmark name dialog（已拆至 GraphViewBookmarks / split into GraphViewBookmarks）
        if (viewBookmarks.handleKey(key)) return true;
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
                if (editingCommentColorNode != null && commentButtons != null) { closeCommentColorPopup(); return true; }
                return true;
            }
            return colorPicker.keyPressed(key, sc, mod);
        }
        // ESC closes open panels first, then falls through to close UI
        if (key == 256) {
            if (viewBookmarks.handleEscClose()) return true;
            if (editingCommentColorNode != null && commentButtons != null) { closeCommentColorPopup(); return true; }
        }
        if (key == 257) { // Enter: 提交当前聚焦的编辑框 (Enter: commit current focused edit box)
            for (var e : enterActions.entrySet()) {
                if (e.getKey().isFocused()) { e.getValue().run(); return true; }
            }
            for (var st : nodeEditStatesById.values()) {
                if (st.busBox != null && st.busBox.isFocused()) { bus.commitBusBox(st); return true; }
            }
        }
        // 框选键（BOX_SELECT 绑定，默认 Tab）按下时补全弹层优先 —— 弹层可见则由弹层消费，
        // 不进入框选（原 258 硬编码行为；改绑后跟随绑定键）。
        // While the box-select key (BOX_SELECT binding, Tab by default) is pressed, the
        // suggestion popup takes priority — a visible popup consumes the key and no
        // box-select starts (the old hardcoded-258 behavior; follows rebinds).
        if (key == boxSelectKey()) {
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
        // 键盘动作序列引擎（画布交互态 —— 输入框聚焦已在前转发 return）：
        // 完整命中 → 分发执行；前缀推进 → 消费等待；无关键 → 落到后续硬编码键处理。
        // Keyboard-action sequence engine (canvas state — focused inputs returned
        // above): full match → dispatch, prefix advance → consumed while waiting,
        // irrelevant keys fall through to the hardcoded handlers below.
        int seqMods = (modC ? EditorKeys.MOD_CTRL : 0) | (modS ? EditorKeys.MOD_SHIFT : 0) | (modA ? EditorKeys.MOD_ALT : 0);
        var seqHit = EditorKeys.feedKey(key, seqMods, System.currentTimeMillis());
        if (seqHit == EditorKeys.Action.DELETE_NODE) {
            // 删除悬停节点（替代右键删除防误触）/ delete the hovered node
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
            return true; // 触发即消费（悬空 / 锁定不满足也归引擎）/ triggered keys are consumed
        } else if (seqHit == EditorKeys.Action.UNDO) { commitFocusedEditBox(); history.opUndo(); return true; }
        else if (seqHit == EditorKeys.Action.REDO) { commitFocusedEditBox(); history.opRedo(); return true; }
        else if (seqHit == EditorKeys.Action.SAVE_BOOKMARK) { // 视角书签快捷键 / view bookmark shortcut
            viewBookmarks.beginKeybindDraft();
            return true;
        } else if (seqHit == EditorKeys.Action.RESET_VIEW) { viewBookmarks.startTransition(0, 0, 1f); return true; }
        else if (seqHit == EditorKeys.Action.BOX_SELECT) { tabHeld = true; return true; } // 按住框选 / hold to box-select
        else if (seqHit == EditorKeys.Action.DUPLICATE && !selectedNodes.isEmpty()) {
            // 复制选中（支持多选）— 走服务端权威 ID 分配流程 / duplicate (multi-select) via server-authoritative IDs
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
        else if (seqHit == EditorKeys.Action.DELETE_SELECTED && !selectedNodes.isEmpty()) {
            // 删除选中节点（原 Backspace/Delete 硬编码，现可绑定，默认 Delete）
            // Delete the selected nodes (was hardcoded to Backspace/Delete; bindable now, Delete by default)
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
        if (seqHit != null) return true; // 触发但前置不满足也消费（动作拥有该键）/ triggered with a failed precondition still consumes
        if (EditorKeys.bufferActive()) return true; // 前缀等待：按键已被引擎消费 / prefix waiting: key consumed
        // C key: Create comment node around selection
        if (key == 67 && !net.minecraft.client.gui.screens.Screen.hasControlDown()
            && !selectedNodes.isEmpty()) {
            boolean anyFocused = false;
            for (var st : nodeEditStatesById.values())
                for (var f : st.fields) if (f.isFocused()) { anyFocused = true; break; }
            if (anyFocused) return false;
            if (showExportDialog || showImportDialog) return false;
            beginUndoBatch();
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (var n : selectedNodes) {
                float nw = NodeRenderer.nw(n);
                float nh = NodeRenderer.nh(n);
                if (expandedNodeIds.contains(n.id)) {
                    var es = nodeEditStatesById.get(n.id);
                    nh += EditPanel.expandedEditHeight(n, es) + 4;
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
    /** 处理按键释放——主要用于框选键释放时退出框选模式（跟随 BOX_SELECT 绑定键）。
     *  Handle key release — mainly used to exit box-select mode when the bound
     *  BOX_SELECT key (follows rebinds) is released.
     *  @return true 如果事件被消费 / true if consumed */
    public boolean keyReleased(int key, int sc, int mod) {
        if (key == boxSelectKey()) { tabHeld = false; return true; }
        return false;
    }

    /** 框选键（BOX_SELECT 绑定序列末步的键，默认 Tab）。
     *  The box-select key (last step of the BOX_SELECT binding, Tab by default). */
    private static int boxSelectKey() {
        var seq = EditorKeys.sequence(EditorKeys.Action.BOX_SELECT);
        return seq.isEmpty() ? 258 : seq.get(seq.size() - 1).key();
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
        if (viewBookmarks.handleChar(ch)) return true;
        if (colorPicker.isVisible()) return colorPicker.charTyped(ch, mod);
        if (showExportDialog && exportNameEdit != null) return exportNameEdit.charTyped(ch, mod);
        if (topBarNameEdit != null && topBarNameEdit.isFocused()) return topBarNameEdit.charTyped(ch, mod);
        for (var st : nodeEditStatesById.values()) for (var f : st.fields) if (f.isFocused()) return f.charTyped(ch, mod);
        return false;
    }

    /** 顶栏：固定在编辑器最上层的条 —— 左侧本图名称输入框（便携终端按此查找），
     *  右侧设置按钮。在 renderBg 的所有覆盖层之后调用，保证不被遮挡；工具栏顶/底
     *  两种位置都必须让开 {@link #TOP_BAR_H}。
     *  Top bar: a fixed strip at the very top of the editor — the graph-name box on
     *  the left (the portable terminal looks devices up by it), the settings button
     *  on the right. Called after every other overlay in renderBg so nothing covers
     *  it; both toolbar positions (top and bottom) must clear {@link #TOP_BAR_H}. */
    /** 合并顶栏：主图 = 名称 + 设置；封装子图 = 模式标识 + Back。两作用域共用同一根顶栏
     *  （同底色、同高度、右侧同一按钮槽位），子图不再另画压在顶栏上的模式横条。
     *  Merged top bar: main graph = name + settings; encapsulation sub-graph = mode indicator
     *  + Back. Both scopes share the one bar (same background, height and right-hand button
     *  slot); the sub-graph no longer draws a separate strip over it. */
    private void renderTopBar(GuiGraphics g, int mx, int my) {
        var mc = Minecraft.getInstance();
        int sw = host.asScreen().width;
        int sbX = sw - 52;
        // 子图分支：模式标识（含节点数/超限）+ Back。Back 占用主图设置按钮的同一槽位，
        // 几何与命中（tryTopBarClick）一致 —— 旧实现画在 y=4 但命中在 TOP_BAR_H+2，两处脱节。
        // Sub-graph branch: mode indicator (node count / over-limit) + Back. Back takes the
        // settings button's slot with matching geometry — the old code drew it at y=4 but
        // hit-tested it at TOP_BAR_H+2.
        if (isInSubGraph()) {
            g.fill(0, 0, sw, TOP_BAR_H, NodeRenderer.withAlpha(NodeRenderer.PBG(), 0xEE));
            g.fill(0, TOP_BAR_H - 1, sw, TOP_BAR_H, NodeRenderer.CSB());
            int nodeCount = getGraph().nodes.size();
            boolean overLimit = nodeCount > MAX_NODES;
            String countStr = " (" + nodeCount + "/" + MAX_NODES + ")" + (overLimit ? " §c⚠" : "");
            String modeText = "◈ " + net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.encap_mode") + " ◈" + countStr;
            g.drawString(mc.font, modeText, 6, 8, overLimit ? 0xFFFF6666 : 0xFFFFCC88, false);
            if (overLimit) {
                // 完整超限提示在 Back 左侧放得下才画；放不下时左侧的红色 ⚠ 兜底。
                // Full over-limit text only when it fits left of Back; otherwise the red ⚠ stands.
                String warn = net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.encap_node_limit");
                int wx = 6 + mc.font.width(modeText) + 12;
                if (wx + mc.font.width(warn) < sbX - 6)
                    g.drawString(mc.font, warn, wx, 8, 0xFFFF4444, false);
            }
            boolean hovBack = mx >= sbX && mx <= sbX + 46 && my >= 3 && my <= 19;
            g.fill(sbX, 3, sbX + 46, 19, hovBack ? NodeRenderer.HOV() : NodeRenderer.PBG());
            g.renderOutline(sbX, 3, 46, 16, NodeRenderer.CSB());
            String backLabel = "← " + net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.back");
            int btw = mc.font.width(backLabel);
            g.drawString(mc.font, backLabel, sbX + (46 - btw) / 2, 7, 0xFFCCCCCC, false);
            return;
        }
        if (topBarNameEdit == null) {
            topBarNameEdit = new EditBox(mc.font, 0, 0, 140, 16, Component.literal(""));
            topBarNameEdit.setMaxLength(32);
            // 透明背景：顶栏自身就是底色，去掉 EditBox 自带的黑底与边框
            // Transparent background: the top bar is the backing — drop the EditBox's
            // built-in black fill and border.
            topBarNameEdit.setBordered(false);
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
        // 方块名软锁：他人正在改名 → 本端输入框只读并交出焦点（防止两人同时改名）；锁释放后恢复。
        // 失焦后下方「未聚焦跟随权威值」逻辑会把文本拉回服务端真值。
        // Graph-name soft lock: while someone else is renaming, the local box goes read-only and
        // loses focus; editable again once the lock clears. After the blur, the
        // follow-authoritative-value logic below snaps the text back to the server's truth.
        boolean nameLocked = presence.isGraphNameLocked();
        if (nameLocked && topBarNameEdit.isFocused()) topBarNameEdit.setFocused(false);
        topBarNameEdit.setEditable(!nameLocked);
        // 方块名编辑期间 presence 持续流动（键盘输入不触发 mouseMoved；焦点翻转在
        // sendPresenceIfNeeded 内绕过 120ms 节流立即发送）。
        // Keep presence flowing while renaming (keyboard input never fires mouseMoved; a focus
        // flip bypasses the 120 ms throttle inside sendPresenceIfNeeded).
        presence.sendPresenceIfNeeded();
        // 图被整体替换（重载/多人同步）后 customName 可能变化 —— 未聚焦时跟随权威值，
        // 聚焦时绝不覆盖（用户正在输入）。
        // After a whole-graph replacement (reload / multiplayer sync) customName may
        // have changed — follow the authoritative value while unfocused, never while
        // the user is typing.
        if (!topBarNameEdit.isFocused() && !topBarNameEdit.getValue().equals(getGraph().customName))
            topBarNameEdit.setValue(getGraph().customName);

        g.fill(0, 0, sw, TOP_BAR_H, NodeRenderer.withAlpha(NodeRenderer.PBG(), 0xEE));
        g.fill(0, TOP_BAR_H - 1, sw, TOP_BAR_H, NodeRenderer.CSB());
        String label = I18n.get("gui.create_schematic_compute.topbar.name");
        g.drawString(mc.font, label, 6, 8, 0xFF888888, false);
        int bx = 10 + mc.font.width(label);
        int bw = Math.min(160, Math.max(80, sw / 4));
        topBarNameEdit.setX(bx);
        topBarNameEdit.setY(3);
        topBarNameEdit.setWidth(bw);
        topBarNameEdit.render(g, 0, 0, 0);
        // 透明背景下，聚焦时补一条细底边作为「可编辑」提示 / with the transparent background,
        // a thin underline marks the editable region while focused
        if (topBarNameEdit.isFocused())
            g.fill(bx, 20, bx + bw, 21, NodeRenderer.CSB());
        // 设置按钮（右侧，子图作用域该槽位为 Back） / settings button (right; Back takes
        // this slot in the sub-graph scope)
        boolean hov = mx >= sbX && mx <= sbX + 46 && my >= 3 && my <= 19;
        g.fill(sbX, 3, sbX + 46, 19, hov ? NodeRenderer.HOV() : NodeRenderer.PBG());
        g.renderOutline(sbX, 3, 46, 16, NodeRenderer.CSB());
        g.drawString(mc.font, I18n.get("gui.create_schematic_compute.topbar.settings"), sbX + 8, 7, NodeRenderer.ACC(), false);
        // 方块名软锁提示：他人改名中 → 名字框金色描边 + 编辑者名字（右侧放得下才画）。
        // Graph-name soft-lock hint: someone else is renaming — golden outline around the name
        // box plus the editor's name (drawn only when it fits before the settings button).
        if (nameLocked) {
            g.renderOutline(bx - 1, 2, bw + 2, 18, 0xFFFFCC44);
            String who = "✎ " + presence.graphNameEditingBy();
            int wx = bx + bw + 6;
            if (wx + mc.font.width(who) < sbX - 4)
                g.drawString(mc.font, who, wx, 7, 0xFFFFCC44, false);
        }
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
            case 0 -> c -> { int oldBg = editingCommentColorNode.commentBgColor, oldBr = editingCommentColorNode.commentBorderColor, oldTx = editingCommentColorNode.commentTextColor; editingCommentColorNode.commentBgColor = c; markDirty(); var op = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCommentColors(host.getBlockPos(), ownerNodeId(), editingCommentColorNode.id, editingCommentColorNode.commentBgColor, editingCommentColorNode.commentBorderColor, editingCommentColorNode.commentTextColor, host.getPlayerUUID()); host.sendOp(op); recordOp(op, oldBg, oldBr, oldTx, null); };
            case 1 -> c -> { int oldBg = editingCommentColorNode.commentBgColor, oldBr = editingCommentColorNode.commentBorderColor, oldTx = editingCommentColorNode.commentTextColor; editingCommentColorNode.commentBorderColor = c; markDirty(); var op = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCommentColors(host.getBlockPos(), ownerNodeId(), editingCommentColorNode.id, editingCommentColorNode.commentBgColor, editingCommentColorNode.commentBorderColor, editingCommentColorNode.commentTextColor, host.getPlayerUUID()); host.sendOp(op); recordOp(op, oldBg, oldBr, oldTx, null); };
            case 2 -> c -> { int oldBg = editingCommentColorNode.commentBgColor, oldBr = editingCommentColorNode.commentBorderColor, oldTx = editingCommentColorNode.commentTextColor; editingCommentColorNode.commentTextColor = c; markDirty(); var op = io.github.y15173334444.create_schematic_compute.graph.GraphOp.setCommentColors(host.getBlockPos(), ownerNodeId(), editingCommentColorNode.id, editingCommentColorNode.commentBgColor, editingCommentColorNode.commentBorderColor, editingCommentColorNode.commentTextColor, host.getPlayerUUID()); host.sendOp(op); recordOp(op, oldBg, oldBr, oldTx, null); };
            default -> c -> {};
        };
        colorPicker.setOnClose(() -> { if (editingCommentColorNode != null) closeCommentColorPopup(); });
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
            if (presence.isNodeLockedByOther(n.id, ownerNodeId())) continue;
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
            float sx=c2sX(n.x), sy=c2sY(n.y), sw=NodeRenderer.nw(n)*zoom;
            // 与渲染同源：nh() 含调试图表（DEBUG_SIGNAL_GEN/PROBE 的 XY 图区）
            // Same source as rendering: nh() includes the debug chart area.
            float nh = NodeRenderer.nh(n) * zoom + 4;
            if (n.type == NodeType.COMMENT) nh = n.commentHeight * zoom;
            if (expandedNodeIds.contains(n.id) && n.type != NodeType.COMMENT)
                nh += EditPanel.expandedEditHeight(n, nodeEditStatesById.get(n.id)) * zoom;
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
    /** 格式化浮点数为 3 位小数（四舍五入）/ format float to 3 decimal places (rounded)。
     *  <p>必须用 double 做 {@code Math.round}：float 重载返回 {@code int}，{@code |v|>2147483.647}
     *  时饱和成 {@code Integer.MAX_VALUE}，大数会被「压缩」成 2147483.x（issue：输入框同步）。
     *  Must round in double: the float overload of {@code Math.round} returns {@code int} and
     *  saturates at {@code Integer.MAX_VALUE}, crushing large magnitudes to 2147483.x. */
    static String ff3(float v) {
        return Float.toString((float) (Math.round((double) v * 1000.0) / 1000.0));
    }
    /** 格式化 int 为 8 位大写十六进制（前导零补齐）/ format int to 8-char uppercase hex (zero-padded) */
    static String hex8(int v) { String h = Integer.toHexString(v).toUpperCase(); return "00000000".substring(h.length()) + h; }
}
