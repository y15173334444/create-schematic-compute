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
 */
public class GraphEditor {
    /** 宿主屏需要实现的接口 */
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
    }

    private final Host host;
    public final NodeRenderer renderer;
    private final SpatialIndex spatialIndex = new SpatialIndex();
    private Predicate<NodeType> nodeFilter;

    /** 每个图的最大节点数上限（含主图和每个封装子图） */
    public static final int MAX_NODES = 1024;

    // ── Static undo/redo stacks (shared across all editor instances) ──
    private static final List<CompoundTag> undoStack = new ArrayList<>();
    private static final List<CompoundTag> redoStack = new ArrayList<>();
    private static final int MAX_UNDO = 50;

    /** Check if undo stack is empty */
    public static boolean isUndoEmpty() { return undoStack.isEmpty(); }

    /** Access the static undo/redo stacks (for direct manipulation by MonitorScreen) */
    public static java.util.List<net.minecraft.nbt.CompoundTag> undoStack() { return undoStack; }
    public static java.util.List<net.minecraft.nbt.CompoundTag> redoStack() { return redoStack; }

    /** Take an undo snapshot of the given graph */
    public static void takeSnapshot(NodeGraph graph, HolderLookup.Provider reg) {
        try {
            undoStack.add(graph.save(reg));
            redoStack.clear();
            while (undoStack.size() > MAX_UNDO) undoStack.remove(0);
        } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.error("takeSnapshot", e); }
    }

    /** Restore the host's graph from the undo stack */
    public static void performUndo(Host host, HolderLookup.Provider reg) {
        if (undoStack.isEmpty()) return;
        try {
            redoStack.add(host.getGraph().save(reg));
            NodeGraph restored = NodeGraph.load(undoStack.remove(undoStack.size() - 1), reg);
            replaceGraph(host, restored);
        } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.error("performUndo", e); }
    }

    /** Restore the host's graph from the redo stack */
    public static void performRedo(Host host, HolderLookup.Provider reg) {
        if (redoStack.isEmpty()) return;
        try {
            undoStack.add(host.getGraph().save(reg));
            NodeGraph restored = NodeGraph.load(redoStack.remove(redoStack.size() - 1), reg);
            replaceGraph(host, restored);
        } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.error("performRedo", e); }
    }

    private static void replaceGraph(Host host, NodeGraph restored) {
        var current = host.getGraph();
        current.nodes.clear();
        current.connections.clear();
        current.nodes.addAll(restored.nodes);
        current.connections.addAll(restored.connections);
        current.nextNodeId = restored.nextNodeId;
        current.nextLayerIndex = restored.nextLayerIndex;
        current.rebuildNodeMap();
        current.bumpGeneration();
    }

    // 编辑状态
    public float camX=0, camY=0, zoom=1f;
    // Phase 2 render cache — skip expensive layers when nothing changed
    private int lastRenderedGen = -1;
    private float lastRenderedCamX, lastRenderedCamY, lastRenderedZoom;
    private int lastRenderedScreenW, lastRenderedScreenH;
    public GraphNode draggingNode=null, selectedNode=null;
    public final Set<GraphNode> selectedNodes = new HashSet<>();
    public float dragOffX, dragOffY;
    public boolean panning=false;
    public float panLastX, panLastY;
    public boolean draggingWire=false;
    public int wireFromNode=-1, wireFromPin=-1;
    public float wireEndX, wireEndY;
    private int editBoxDragNodeId = -1; // node id whose EditBox is being drag-selected
    public boolean showMenu=false;
    public float menuX, menuY;
    public NodeType selectedMenuType=null;
    public long saveFeedbackUntil=0;
    public String saveFeedbackText="";
    public long importFeedbackUntil=0;
    public String cycleWarning=null;
    // 导入/导出封装节点对话框
    public boolean showExportDialog = false;
    public boolean showImportDialog = false;
    public EditBox exportNameEdit = null;
    public java.util.List<java.nio.file.Path> importFiles = null;
    public int importScrollOff = 0;
    public boolean gridSnapEnabled = NodeRenderer.loadGridSnap();
    public GraphNode hotbarNode = null; // 当前显示热栏的节点（点击频率槽时弹出）
    // 多节点展开：Set + 每节点独立编辑状态
    public final java.util.Set<Integer> expandedNodeIds = new java.util.HashSet<>();
    public static class EditState {
        public final java.util.List<net.minecraft.client.gui.components.EditBox> fields = new java.util.ArrayList<>();
        /** 每个 field 对应的参数索引（用于参数引脚映射和渲染） */
        public final java.util.List<Integer> fieldParamIndices = new java.util.ArrayList<>();
        public String[] paramKeys;
        public int freqSlotSelected = 0;
        public float boolBtnX, boolBtnY, boolBtnW, boolBtnH;
        public float freqSlotX, freqSlotY;
        public boolean listeningForKey = false;
        public NodeGraph graph;
        /** 有参数引脚连线时阻止折叠（值由连线决定，编辑区已隐藏） */
        public boolean blockCollapse;
        /** 频段 +/- 按钮位置（仅 BUS_IN/OUT 用） */
        public float bandAddBtnX, bandAddBtnY, bandAddBtnW, bandAddBtnH;
        public float bandRemoveBtnX, bandRemoveBtnY, bandRemoveBtnW, bandRemoveBtnH;
        /** 每个频段引脚的 node-local Y 坐标（同步编辑区渲染与连线检测） */
        public float[] bandPinY;
        /** BUS 总线名 EditBox（用于失焦/Enter 提交检测） */
        public net.minecraft.client.gui.components.EditBox busBox;
        public GraphNode busNode;
        /** ColorPickerButton for TEXT/DATA node color editing */
        public ColorPickerButton colorButton;
    }
    public final java.util.Map<Integer, EditState> nodeEditStatesById = new java.util.HashMap<>();
    /** EditBox → 提交动作（回车或失焦时执行） */
    private final java.util.Map<net.minecraft.client.gui.components.EditBox, Runnable> enterActions = new java.util.HashMap<>();
    // 颜色配置面板
    public boolean showColorConfig = false;
    public final ColorPickerWidget colorPicker = new ColorPickerWidget();
    private final ColorPickerButton[] themeButtons = new ColorPickerButton[NodeRenderer._NUM_COLORS];
    // 框选 + 多选拖拽状态
    private boolean tabHeld = false;
    private boolean boxSelecting = false;
    private float boxSX, boxSY, boxEX, boxEY;
    private boolean multiDragging = false;
    private GraphNode multiClickedNode = null;
    private float multiCenterX, multiCenterY;
    private long prevGpadButtons = 0; // for gamepad button edge detection in binding mode
    private final java.util.Map<GraphNode, float[]> multiDragOrigins = new java.util.HashMap<>();
    // 鼠标坐标缓存（供 X 键删除用）
    private double lastMouseX, lastMouseY;

    // ── Z-order (B-layer) drag state ──
    private int preDragSortB = 0;
    private final java.util.Map<GraphNode, Integer> preDragSortBs = new java.util.HashMap<>();

    // ── Comment node interaction state ──
    private long lastClickTimeMs = 0;
    private int lastClickNodeId = -1;
    private GraphNode resizingComment = null;
    private float resizeStartW, resizeStartH;
    private GraphNode editingCommentColorNode = null;
    private ColorPickerButton[] commentButtons = null; // created when popup opens
    private final java.util.Map<Integer, Integer> commentScrollOffsets = new java.util.HashMap<>();

    // ── 子图编辑栈（封装节点） ──
    private record GraphEditState(GraphNode parentNode, Predicate<NodeType> parentFilter,
                                   float camX, float camY, float zoom) {}
    private final java.util.Deque<GraphEditState> graphStack = new java.util.ArrayDeque<>();
    private GraphNode encapsulationParent; // 当前正在编辑的封装节点（null = 编辑主图）
    private Predicate<NodeType> mainNodeFilter; // 进入子图前保存的主图过滤器

    public boolean isInSubGraph() { return encapsulationParent != null; }
    public GraphNode getEncapsulationParent() { return encapsulationParent; }

    /** 进入封装节点的子图编辑 */
    public void enterSubGraph(GraphNode encapNode) {
        if (encapNode.type != NodeType.ENCAPSULATION) return;
        if (encapNode.subGraph == null) encapNode.subGraph = new NodeGraph();
        var parentFilter = mainNodeFilter != null ? mainNodeFilter : nodeFilter;
        graphStack.push(new GraphEditState(encapNode, parentFilter, camX, camY, zoom));
        encapsulationParent = encapNode;
        camX = 0; camY = 0; zoom = 1f;
        expandedNodeIds.clear(); nodeEditStatesById.clear();
        selectedNode = null; selectedNodes.clear();
        // 子图过滤器：允许 ENCAP_INPUT, ENCAP_OUTPUT 及所有非 I/O 节点
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

    /** 退出子图，返回父图 */
    public void exitSubGraph() {
        if (graphStack.isEmpty()) return;
        var state = graphStack.pop();
        encapsulationParent = graphStack.isEmpty() ? null : graphStack.peek().parentNode();
        camX = state.camX(); camY = state.camY(); zoom = state.zoom();
        expandedNodeIds.clear(); nodeEditStatesById.clear();
        selectedNode = null; selectedNodes.clear();
        nodeFilter = state.parentFilter();
        mainNodeFilter = state.parentFilter();
        // 子图修改已写入 encapsulationParent.subGraph，随 Recompile 统一保存
    }

    public NodeGraph getGraph() {
        return isInSubGraph() ? encapsulationParent.subGraph : host.getGraph();
    }

    public void saveGraph() {
        host.saveGraph();
    }

    /** 注册 Enter/失焦提交动作 */
    private void registerEnter(net.minecraft.client.gui.components.EditBox eb, Runnable action) {
        enterActions.put(eb, action);
    }

    /** 创建节点的编辑状态 */
    private EditState createEditState(GraphNode node) {
        // 保存旧状态引用（供 busBox 保留输入值）
        final var oldStRef = nodeEditStatesById.get(node.id);
        // 先移除本节点的旧 EditState（避免旧 EditBox 仍在旧 state 中被保留）
        nodeEditStatesById.remove(node.id);
        // 清除不再被任何 EditState 引用的旧 EditBox 的 enterActions
        enterActions.keySet().removeIf(eb -> {
            for (var st : nodeEditStatesById.values())
                if (st.fields.contains(eb)) return false;
            return true;
        });
        var s = new EditState();
        s.graph = getGraph(); // 用于检查参数引脚连线状态
        s.paramKeys = node.type.paramNames.clone();
        var mc = Minecraft.getInstance();
        for (int i = 0; i < node.params.length; i++) {
            if (node.type == NodeType.BOOL || node.type == NodeType.GATE || node.type == NodeType.T_FLIPFLOP || node.type == NodeType.LATCH || node.type == NodeType.KEYBOARD || node.type == NodeType.GAMEPAD_BUTTON
                || node.type == NodeType.ENCAP_INPUT || node.type == NodeType.ENCAP_OUTPUT
                || node.type == NodeType.IMAGE || node.type == NodeType.IMAGE_SEQUENCE) continue;
            // 参数输入引脚已连线 → 阻止折叠（值由连线提供，但引脚仍可见）
            int pinIdx = node.type.inputs + i;
            if (node.type.editableParamCount() > 0 && getGraph().hasInputConnection(node.id, pinIdx)) {
                s.blockCollapse = true;
            }
            int idx = i;
            var b = new EditBox(mc.font, 0, 0, 60, 16, Component.literal(""));
            b.setMaxLength(12);
            b.setValue(ff3(node.params[i]));
            float oldVal = node.params[idx];
            registerEnter(b, () -> { try { node.params[idx] = Float.parseFloat(b.getValue().trim()); } catch (Exception e) {} });
            s.fields.add(b);
            s.fieldParamIndices.add(i);
        }
        if ((node.type == NodeType.REDSTONE_IN || node.type == NodeType.REDSTONE_OUT) && node.itemParams.length < 2)
            node.itemParams = new ItemStack[]{ItemStack.EMPTY, ItemStack.EMPTY};
        if (node.type == NodeType.PRIVATE_IN || node.type == NodeType.PRIVATE_OUT) {
            var sb = new EditBox(mc.font, 0, 0, 120, 16, Component.literal(""));
            sb.setMaxLength(32); sb.setValue(node.signalName);
            registerEnter(sb, () -> node.signalName = sb.getValue());
            s.fields.add(sb);
        }
        if (node.type == NodeType.BUS_IN || node.type == NodeType.BUS_OUT) {
            // 预计算编辑区引脚位置（避免连线首帧跳动）
            int bandCnt = node.bandCount();
            if (bandCnt > 0) {
                s.bandPinY = new float[bandCnt];
                for (int i = 0; i < bandCnt; i++) {
                    s.bandPinY[i] = bandPinY(node, i, zoom);
                    // 检查频段引脚是否有连线，有则阻止折叠
                    if (node.type == NodeType.BUS_OUT && getGraph().hasInputConnection(node.id, i))
                        s.blockCollapse = true;
                    else if (node.type == NodeType.BUS_IN) {
                        final int bi = i;
                        if (getGraph().connections.stream().anyMatch(c -> c.fromId == node.id && c.fromPin == bi))
                            s.blockCollapse = true;
                    }
                }
            }
            // BUS_IN 展开时自动同步频段（先本地 BUS_OUT，后全局注册表）
            if (node.type == NodeType.BUS_IN && !node.signalName.isEmpty() && node.signalBands.isEmpty()) {
                boolean synced = false;
                // 先从同图内 BUS_OUT 同步
                for (var n : getGraph().nodes) {
                    if (n.type == NodeType.BUS_OUT && n.signalName.equals(node.signalName) && n.bandCount() > 0) {
                        node.signalBands = new java.util.ArrayList<>(n.signalBands); synced = true; break;
                    }
                }
                // 本地没有则从全局注册表同步
                if (!synced) {
                    var gb = io.github.y15173334444.create_schematic_compute.network.SignalBus.getBands(node.signalName);
                    if (gb != null && !gb.isEmpty()) node.signalBands = new java.util.ArrayList<>(gb);
                }
            }
            var busBox = new EditBox(mc.font, 0, 0, 120, 16, Component.literal(""));
            busBox.setMaxLength(32); busBox.setValue(node.signalName);
            // busBox 不通过 enterActions 提交；保留旧聚焦 busBox 的输入值
            var oldSt = oldStRef;
            s.busNode = node;
            if (oldSt != null && oldSt.busBox != null && oldSt.busBox.isFocused()) {
                busBox.setValue(oldSt.busBox.getValue()); // 保留用户正在输入的内容
                busBox.setFocused(true);
            }
            s.busBox = busBox;
            s.fields.add(busBox);
            s.fieldParamIndices.add(-1);
            if (node.signalBands == null) node.signalBands = new java.util.ArrayList<>();
            // 同步旧频段 EditBox 的值到 signalBands（仅同步未聚焦的，防止干扰正在编辑的框）
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
                // BUS_OUT 频段可编辑但不在 enterActions 中（由 recompile 批量同步）
                s.fields.add(bandBox);
                s.fieldParamIndices.add(bi);
            }
        }
        if (node.type == NodeType.TEXT) {
            var tb = new EditBox(mc.font, 0, 0, 120, 16, Component.literal(""));
            tb.setMaxLength(256); tb.setValue(node.displayText);
            registerEnter(tb, () -> node.displayText = tb.getValue());
            s.fields.add(tb);
            // Color swatch button replaces old hex EditBox
            s.colorButton = new ColorPickerButton(
                () -> node.textColor != 0 ? node.textColor : 0xFFCCCCCC,
                c -> { node.textColor = c; markDirty(); },
                colorPicker
            );
            s.paramKeys = new String[]{"text", "color"};
        }
        if (node.type == NodeType.DATA) {
            // Color swatch button replaces old hex EditBox
            s.colorButton = new ColorPickerButton(
                () -> node.textColor != 0 ? node.textColor : 0xFF88FF88,
                c -> { node.textColor = c; markDirty(); },
                colorPicker
            );
            s.paramKeys = new String[]{"color"};
        }
        if (node.type == NodeType.IMAGE || node.type == NodeType.IMAGE_SEQUENCE) {
            String[] keys = {"moveX", "moveY", "rotScl"};
            float[] defaults = {0.01f, 0.01f, 1f};
            for (int pi = 0; pi < 3; pi++) {
                int idx = pi;
                var b = new EditBox(mc.font, 0, 0, 50, 16, Component.literal(""));
                b.setMaxLength(8); b.setValue(ff3(node.params.length > idx ? node.params[idx] : defaults[idx]));
                int iidx = idx; registerEnter(b, () -> { try { if (node.params.length > iidx) node.params[iidx] = Float.parseFloat(b.getValue().trim()); } catch (Exception e) {} });
                s.fields.add(b);
            }
            s.paramKeys = keys;
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
            mle.setResponder(t -> node.displayText = t);
            s.fields.add(mle);
        }
        if (node.type == NodeType.FORMULA) {
            // Multi-line script editor — single MultiLineEditBox
            int editW = NodeRenderer.WIDE_NW - 36;
            var mle = new io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox(
                mc.font, 0, 0, editW, 18);
            mle.setMaxLength(4096);
            String initialText = node.formula.isEmpty() ? "A+B" : node.formula;
            mle.setValue(initialText);
            node.formula = initialText;
            node.cachedScript = null;
            // Initial parse from displayed text (not empty node.formula)
            var initScript = io.github.y15173334444.create_schematic_compute.graph.FormulaParser.parseScript(initialText);
            node.dynamicInputCount = initScript.inputVars.size();
            node.dynamicOutputCount = Math.max(1, initScript.outputLabels.size());
            node.outputLabels = initScript.outputLabels;
            mle.setResponder(t -> {
                node.formula = t;
                node.cachedScript = null; // invalidate label cache
                var res = io.github.y15173334444.create_schematic_compute.graph.FormulaParser.parseScript(t);
                int newIn = res.inputVars.size();
                int newOut = Math.max(1, res.outputLabels.size());
                // Clean up connections to removed pins
                if (newOut < node.dynamicOutputCount) {
                    int oldOut = node.dynamicOutputCount;
                    for (int pi = newOut; pi < oldOut; pi++) {
                        final int p = pi;
                        host.getGraph().connections.removeIf(c -> c.fromId == node.id && c.fromPin == p);
                    }
                }
                if (newIn < node.dynamicInputCount) {
                    int oldIn = node.dynamicInputCount;
                    for (int pi = newIn; pi < oldIn; pi++) {
                        final int p = pi;
                        host.getGraph().connections.removeIf(c -> c.toId == node.id && c.toPin == p);
                    }
                }
                node.dynamicInputCount = newIn;
                node.dynamicOutputCount = newOut;
                node.outputLabels = res.outputLabels;
            });
            s.fields.add(mle);
        }
        if (node.type == NodeType.ENCAP_INPUT || node.type == NodeType.ENCAP_OUTPUT) {
            var nb = new EditBox(mc.font, 0, 0, 100, 16, Component.literal(""));
            nb.setMaxLength(32); nb.setValue(node.displayText);
            registerEnter(nb, () -> node.displayText = nb.getValue());
            s.fields.add(nb);
            s.paramKeys = new String[]{"name"};
        }
        return s;
    }

    /** 切换节点展开/折叠（封装节点双击进入子图编辑，其余节点内联展开） */
    private void toggleExpand(GraphNode node) {
        if (node.type == NodeType.ENCAPSULATION) {
            enterSubGraph(node);
            return;
        }
        if (!shouldOpenPanel(node)) return;
        if (expandedNodeIds.contains(node.id)) {
            var st = nodeEditStatesById.get(node.id);
            if (st != null && st.blockCollapse) return; // 有参数引脚连线，阻止折叠
            expandedNodeIds.remove(node.id); nodeEditStatesById.remove(node.id);
            node.expanded = false;
        } else {
            expandedNodeIds.add(node.id); nodeEditStatesById.put(node.id, createEditState(node));
            node.expanded = true;
        }
        markDirty();
    }

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
    }

    public void setNodeFilter(Predicate<NodeType> filter) { this.nodeFilter = filter; this.mainNodeFilter = filter; }

    // 坐标转换
    public float c2sX(float cx) { Screen s = host.asScreen(); return s.width/2f+(cx+camX)*zoom; }
    public float c2sY(float cy) { Screen s = host.asScreen(); return s.height/2f+(cy+camY)*zoom; }
    public float s2cX(double sx) { Screen s = host.asScreen(); return(float)((sx-s.width/2f)/zoom-camX); }
    public float s2cY(double sy) { Screen s = host.asScreen(); return(float)((sy-s.height/2f)/zoom-camY); }

    /** Bump graph generation to invalidate render caches (Phase 2 dirty flag framework) */
    void markDirty() { getGraph().bumpGeneration(); }

    /** Sort nodes by B-layer ascending (lower B = rendered first = behind, higher B = on top). */
    private List<GraphNode> sortNodesByB(List<GraphNode> nodes) {
        return nodes.stream()
            .sorted((a, b) -> Integer.compare(a.sortB, b.sortB))
            .collect(java.util.stream.Collectors.toList());
    }

    /** Find the overlapping node with the largest sortB. The dragged node will be
     *  inserted above it (sortB = max + 1). Returns null if no node overlaps. */
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

    /** Full node height including expanded edit panel (for occlusion/AABB calculations). */
    private float fullNodeHeight(GraphNode n) {
        float h = NodeRenderer.nh(n);
        if (expandedNodeIds.contains(n.id)) {
            h += io.github.y15173334444.create_schematic_compute.blocks.EditPanel
                .calcRenderHeight(n, 1.0f);
        }
        return h;
    }

    /** AABB overlap test between two nodes (graph-space). */
    private boolean rectsOverlap(GraphNode a, GraphNode b) {
        float aw = NodeRenderer.nw(a), ah = fullNodeHeight(a);
        float bw = NodeRenderer.nw(b), bh = fullNodeHeight(b);
        return rectsOverlap(a.x, a.y, aw, ah, b.x, b.y, bw, bh);
    }
    /** AABB overlap test with raw coordinates (graph-space). */
    private static boolean rectsOverlap(float ax, float ay, float aw, float ah,
                                         float bx, float by, float bw, float bh) {
        return ax < bx + bw && ax + aw > bx
            && ay < by + bh && ay + ah > by;
    }

    /** Renormalize all sortB values to [0, N-1] preserving relative order. */
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
    private io.github.y15173334444.create_schematic_compute.graph.NodeGraph lastInitGraph = null;
    /** 本方块通过 syncBusBands 实际注册过的频道名（用于区分自身和跨方块冲突） */
    private final java.util.Set<String> localBusNames = new java.util.HashSet<>();

    public void renderBg(GuiGraphics g, int mx, int my) {
        var graph = getGraph();
        if (graph != lastInitGraph) {
            lastInitGraph = graph;
            expandedInitDone = false;
        }
        // 首次渲染时从 NBT 恢复展开状态
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

        // Rebuild spatial index once per frame (used by all spatial queries below)
        spatialIndex.build(graph.nodes, expandedNodeIds);

        // Sort nodes by B-layer ascending (lower B = rendered first = behind, higher B = on top)
        var sortedByB = sortNodesByB(graph.nodes);

        // ── A=1: Complete COMMENT nodes (bg, border, text) — container mats behind connections ──
        Map<Integer, Boolean> flipflopStates = host.getFlipflopStates();
        renderer.renderCommentNodes(g, sortedByB, selectedNodes, selectedNode, expandedNodeIds,
            nodeEditStatesById, camX, camY, zoom, mx, my, flipflopStates);

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

        // 从全局 BAND_REGISTRY 同步 BUS_IN 的频段（BUS_OUT 自己定义，不同步）
        for (var n : graph.nodes) {
            if (n.type != NodeType.BUS_IN) continue;
            if (n.signalName.isEmpty()) continue;
            var gb = io.github.y15173334444.create_schematic_compute.network.SignalBus.getBands(n.signalName);
            if (gb != null && !gb.isEmpty() && !gb.equals(n.signalBands)) {
                // 断开已删除频段引脚上的连线
                int oldCount = n.bandCount();
                for (int pi = 0; pi < oldCount; pi++) {
                    if (pi >= gb.size()) {
                        final int p = pi;
                        graph.connections.removeIf(c ->
                            (c.fromId == n.id && c.fromPin == p) || (c.toId == n.id && c.toPin == p));
                    }
                }
                n.signalBands = new java.util.ArrayList<>(gb);
            } else if ((gb == null || gb.isEmpty()) && !n.signalBands.isEmpty() && n.type == NodeType.BUS_IN) {
                // BUS_IN 被动同步：BAND_REGISTRY 为空 → BUS_OUT 不存在 → 清空
                int oldCount = n.bandCount();
                for (int pi = 0; pi < oldCount; pi++) {
                    final int p = pi;
                    graph.connections.removeIf(c ->
                        (c.fromId == n.id && c.fromPin == p) || (c.toId == n.id && c.toPin == p));
                }
                n.signalBands.clear();
                n.bandsDirty = true;
                if (expandedNodeIds.contains(n.id))
                    nodeEditStatesById.put(n.id, createEditState(n));
            }
        }
        // BUS_IN/OUT 展开面板刷新：比较 band 数量 + 内容是否与 EditState 一致
        for (var n : graph.nodes) {
            if ((n.type != NodeType.BUS_IN && n.type != NodeType.BUS_OUT)
                || !expandedNodeIds.contains(n.id)) continue;
            var st = nodeEditStatesById.get(n.id);
            if (st == null) continue;
            // 跳过正在编辑的频段输入框（用户正在输入中，不要重建 EditState）
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
                // 判断是否为纯名称变化（非数量变化）
                boolean nameOnlyChange = st.fields.size() - 1 == n.bandCount() && n.bandCount() > 0;
                nodeEditStatesById.put(n.id, createEditState(n));
                // 纯名称变化时同步到同总线名节点并上传服务器
                if (nameOnlyChange && n.type == NodeType.BUS_OUT && !n.busConflict) {
                    syncBusBands(n);
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new io.github.y15173334444.create_schematic_compute.network.BusBandUploadPacket(
                            host.getBlockPos(), n.signalName, n.signalBands));
                }
            }
        }
        // ── A=2: Connections (bezier curves) ──
        renderer.renderConnections(g, graph, camX, camY, zoom);
        if(draggingWire) renderer.renderDraggingWire(g, graph, wireFromNode, wireFromPin, wireEndX, wireEndY, camX, camY, zoom);

        // ── A=3: Regular node bodies (sorted by B ascending, comments excluded — rendered at A=1) ──
        renderer.renderNodes(g, sortedByB, selectedNodes, selectedNode, expandedNodeIds, nodeEditStatesById,
            camX, camY, zoom, mx, my, flipflopStates);
        if (!isInSubGraph()) {
            renderer.renderButtons(g, true, host.isRunning(), cycleWarning, saveFeedbackUntil, gridSnapEnabled, 0, host.asScreen().width, host.asScreen().height);
            // 导入/导出封装节点按钮（仅蓝图计算机显示）
            if (host instanceof BlueprintScreen) {
                var mc = Minecraft.getInstance();
                int btnY = NodeRenderer.isToolbarBottom() ? host.asScreen().height - 22 : 4;
                int impX = 254, impW = 72, btnH = 18;
                // 仅选中单个封装节点时显示导出，否则显示导入
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
            // 封装模式标识 (替换按钮栏)
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
        // 导入/导出反馈文字
        if (System.currentTimeMillis() < importFeedbackUntil && !saveFeedbackText.isEmpty()) {
            var mc = Minecraft.getInstance();
            int tw = mc.font.width(saveFeedbackText) + 20;
            int fy = NodeRenderer.isToolbarBottom() ? host.asScreen().height - 60 : 26;
            g.fill(host.asScreen().width / 2 - tw / 2, fy, host.asScreen().width / 2 + tw / 2, fy + 18, 0xCC2A3A2A);
            g.renderOutline(host.asScreen().width / 2 - tw / 2, fy, tw, 18, 0xFF6A8A4A);
            g.drawString(mc.font, saveFeedbackText, host.asScreen().width / 2 - tw / 2 + 10, fy + 4, 0xFFFFFFFF, false);
        }
        // 导出封装节点对话框
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
            // 保存按钮
            int sx = cx + w - 60, sy = cy + 24;
            g.fill(sx, sy, sx + 50, sy + 20, 0xFF3A5A2A);
            g.renderOutline(sx, sy, 50, 20, 0xFF6A8A4A);
            g.drawString(mc.font, "§a" + I18n.get("gui.create_schematic_compute.save"), sx + 8, sy + 5, 0xFFFFFFFF, false);
            // 取消按钮
            g.fill(cx + 8, cy + 50, cx + 58, cy + 68, 0xFF4A3030);
            g.renderOutline(cx + 8, cy + 50, 50, 18, 0xFF8B5333);
            g.drawString(mc.font, "§c" + I18n.get("gui.create_schematic_compute.cancel"), cx + 12, cy + 53, 0xFFFFFFFF, false);
        }
        // 导入封装节点对话框
        if (showImportDialog) {
            var mc = Minecraft.getInstance();
            int w = 280, visRows = 8;
            int fileCount = importFiles != null ? importFiles.size() : 0;
            int listH = Math.min(fileCount, visRows) * 18;
            int h = 56 + listH + 30; // 标题 + 列表 + 按钮区
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
                // 右侧滚动条
                if (maxScroll > 0) {
                    int sbX = cx + w - 14, sbY = listY, sbH = visRows * 18;
                    g.fill(sbX, sbY, sbX + 8, sbY + sbH, 0xFF2A2822);
                    float thumbTop = sbY + (float) importScrollOff / maxScroll * (sbH - 12);
                    g.fill(sbX + 1, (int) thumbTop, sbX + 7, (int) thumbTop + 12, 0xFF8B7533);
                }
            }
            // 取消按钮
            int cby = cy + h - 22;
            g.fill(cx + 8, cby, cx + 58, cby + 16, 0xFF4A3030);
            g.renderOutline(cx + 8, cby, 50, 16, 0xFF8B5333);
            g.drawString(mc.font, "§c" + I18n.get("gui.create_schematic_compute.cancel"), cx + 12, cby + 2, 0xFFFFFFFF, false);
        }
        // 热栏弹出（点击频率槽后在节点下方显示）
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
                    if (!item.isEmpty()) g.renderItem(item, hx + 1, py + 17);
                }
            }
        }
        // 颜色配置面板
        if (showColorConfig) renderColorPanel(g, mx, my);
        if(showMenu) { selectedMenuType = renderer.renderAddNodeMenu(g, menuX, menuY, mx, my, nodeFilter); }
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
        // 框选矩形
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
    }

    public boolean mouseClicked(double mx, double my, int btn) {
        var graph = getGraph();
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
            // Click inside → delegate to comment buttons
            for (int ci = 0; ci < 3; ci++) {
                if (commentButtons[ci].mouseClicked(mx, my, btn)) return true;
            }
            return true;
        }
        // ── 导出对话框处理 ──
        if (showExportDialog && btn == 0) {
            int w = 280, h = 80;
            int cx = (host.asScreen().width - w) / 2, cy = (host.asScreen().height - h) / 2;
            // Save 按钮
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
            // 点击对话框外部 → 关闭
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
            // 点击对话框外部
            if (mx < cx || mx > cx + w || my < cy || my > cy + h) {
                showImportDialog = false; importFiles = null; return true;
            }
            // 文件列表点击（留出滚动条区域）
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
        // 失焦提交：enterActions（频段 EditBox 等通过 enterActions 注册的控件）
        boolean committed = false;
        for (var e : enterActions.entrySet()) {
            if (e.getKey().isFocused()) { e.getValue().run(); committed = true; break; }
        }
        if (committed) markDirty();
        if(btn==0){
            // ── 子图 Back 按钮 ──
            if (isInSubGraph()) {
                int bw = 60, bh = 16;
                int bx = host.asScreen().width - bw - 8, by = 4;
                if (mx >= bx && mx <= bx + bw && my >= by && my <= by + bh) {
                    exitSubGraph(); return true;
                }
            }
            // 工具栏按钮（子图模式下隐藏）
            if (!isInSubGraph()) {
                int btnY = NodeRenderer.isToolbarBottom() ? host.asScreen().height - 22 : 4;
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
                // 导入/导出封装节点按钮（仅蓝图计算机）
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
                        } catch (Exception e) { importFiles = java.util.Collections.emptyList(); }
                    }
                    return true;
                }
            }
            // 右下角工具栏位置切换按钮（始终可见）
            { int w = host.asScreen().width, h = host.asScreen().height;
              if(mx>=w-22&&mx<=w-4&&my>=h-22&&my<=h-4){NodeRenderer.toggleToolbarBottom();return true;} }
        }
        if(showMenu&&btn==0){
            if(renderer.handleCategoryClick((int)mx, (int)my)) return true;
            if(selectedMenuType!=null){
                if(graph.nodes.size()>=MAX_NODES){
                    cycleWarning=I18n.get("gui.create_schematic_compute.node_limit");
                }else{
                    {var l=Minecraft.getInstance().level;if(l!=null)takeSnapshot(getGraph(),l.registryAccess());}
                    graph.addNode(selectedMenuType,s2cX(mx),s2cY(my));
                }
            }showMenu=false;return true;}
        if(btn==1){
            if (showColorConfig) return true; // 颜色面板打开时禁止操作
            menuX=(float)mx; menuY=(float)my; showMenu=true; return true;
        }
        // 热栏弹出交互
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
            // 点击热栏面板内部
            if (mx >= px2 && mx <= px2 + pw2 && my >= py2 && my <= py2 + ph2) {
                int si = (int)((mx - px2 - 4) / 20);
                if (si >= 0 && si < 9 && mc2.player != null && hotbarNode.itemParams != null && st != null
                    && st.freqSlotSelected < hotbarNode.itemParams.length) {
                    var inv = mc2.player.getInventory().items.get(si);
                    hotbarNode.itemParams[st.freqSlotSelected] = inv.isEmpty() ? ItemStack.EMPTY : inv.copy();
                    if (!inv.isEmpty()) hotbarNode.itemParams[st.freqSlotSelected].setCount(1);
                }
                hotbarNode = null; // 点击面板内始终关闭
                return true;
            }
            hotbarNode = null; // 点击面板外部 → 关闭
        }
        // KEYBOARD 绑定监听中 → 点击任何地方取消绑定（点击绑定区域本身除外，那里由 edit 区处理）
        if (btn == 0 && !nodeEditStatesById.isEmpty()) {
            boolean anyListening = false;
            for (var st : nodeEditStatesById.values()) if (st.listeningForKey) { anyListening = true; break; }
            if (anyListening) {
                // 检查是否点击了 KEYBOARD 编辑区域的内联范围
                // 如果不是，取消所有监听
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
            // 点击面板外部 → 关闭
            if (mx < px || mx > px + pw || my < py || my > py + ph) { showColorConfig = false; colorPicker.close(); return true;
            }
            // 关闭按钮
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
            // Theme color buttons
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
            // 预计算 z-order 排序候选（供每个展开节点做遮挡判断）
            var clickCandidates = spatialIndex.queryPoint(s2cX(mx), s2cY(my)).stream()
                .sorted(GraphEditor::compareHitOrder)
                .collect(java.util.stream.Collectors.toList());
            // 内联编辑区交互（局部坐标，与 pose 内渲染一致）
            for (var en : getGraph().nodes) {
                if (!expandedNodeIds.contains(en.id)) continue;
                // 逐个检查：是否有更高 z-order 的非 Comment 节点实际遮挡了点击位置
                boolean occluded = false;
                for (var n : clickCandidates) {
                    if (n == en) break; // 到达当前节点，上方无遮挡
                    if (n.type == NodeType.COMMENT) continue;
                    float sx = c2sX(n.x), sy = c2sY(n.y);
                    float sw = NodeRenderer.nw(n) * zoom;
                    float nh = (HH + PH * (n.functionalInputs() + n.outputs())) * zoom + 4;
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
                int editLocalY = (int)(HH + PH*(en.functionalInputs() + en.outputs()) + 4/zoom);
                int numRows = st.fields.size();
                // Frequency slots only exist for REDSTONE_IN/OUT nodes
                if (en.type == NodeType.REDSTONE_IN || en.type == NodeType.REDSTONE_OUT) {
                    int freqLocalY = editLocalY + 8 + numRows * 18;
                    for (int fi = 0; fi < 2; fi++) {
                        int bx = 4 + fi * 24;
                        if (lmx >= bx && lmx <= bx + 20 && lmy >= freqLocalY && lmy <= freqLocalY + 20)
                        { st.freqSlotSelected = fi; hotbarNode = (hotbarNode == en) ? null : en; return true; }
                    }
                }
                if (en.type == NodeType.BOOL && en.params.length > 0) {
                    int boolLocalY = editLocalY + 4 + numRows * 18;
                    if (lmx >= 4 && lmx <= NW - 4 && lmy >= boolLocalY && lmy <= boolLocalY + 16)
                    { en.params[0] = en.params[0] > 0.5f ? 0 : 1; return true; }
                }
                if (en.type == NodeType.GATE && en.params.length > 0) {
                    int gateLocalY = editLocalY + 4 + numRows * 18;
                    if (lmx >= 4 && lmx <= NW - 4 && lmy >= gateLocalY && lmy <= gateLocalY + 16)
                    { en.params[0] = en.params[0] > 0.5f ? 0 : 1; return true; }
                }
                if (en.type == NodeType.T_FLIPFLOP && en.params.length > 0) {
                    int ffLocalY = editLocalY + 4 + numRows * 18;
                    if (lmx >= 4 && lmx <= NW - 4 && lmy >= ffLocalY && lmy <= ffLocalY + 16)
                    { en.params[0] = en.params[0] > 0.5f ? 0 : 1; return true; }
                }
                if (en.type == NodeType.LATCH && en.params.length > 0) {
                    int latchLocalY = editLocalY + 4 + numRows * 18;
                    if (lmx >= 4 && lmx <= NW - 4 && lmy >= latchLocalY && lmy <= latchLocalY + 16)
                    { en.params[0] = en.params[0] > 0.5f ? 0 : 1; return true; }
                }
                // BUS_IN/OUT 频段 +/- 按钮（先提交未保存的 busBox，防止名称丢失）
                if ((en.type == NodeType.BUS_IN || en.type == NodeType.BUS_OUT) && st.bandAddBtnW > 0) {
                    // 提交当前节点的 busBox（如有未保存的频道名编辑）
                    if (st.busBox != null && st.busNode != null
                        && !st.busBox.getValue().equals(st.busNode.signalName))
                        commitBusBox(st);
                }
                if ((en.type == NodeType.BUS_IN || en.type == NodeType.BUS_OUT) && st.bandAddBtnW > 0) {
                    if (lmx >= st.bandAddBtnX && lmx <= st.bandAddBtnX + st.bandAddBtnW
                        && lmy >= st.bandAddBtnY && lmy <= st.bandAddBtnY + st.bandAddBtnH) {
                        // + 按钮：添加新频段，同步同总线名节点
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
                            // 断开被移除引脚上的连线
                            graph.connections.removeIf(c ->
                                (c.fromId == en.id && c.fromPin == removedPin)
                                || (c.toId == en.id && c.toPin == removedPin));
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
                        if (lmx >= 4 && lmx <= NW - 4 && lmy >= tgY && lmy <= tgY + 14)
                        { en.params[3 + ti] = en.params[3 + ti] > 0.5f ? 0 : 1; return true; }
                    }
                }
                if (en.type == NodeType.KEYBOARD || en.type == NodeType.GAMEPAD_BUTTON) {
                    int kbLocalY = editLocalY + 4;
                    if (EditPanel.handleKeyboardClick(en, st, lmx, lmy - kbLocalY, io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer.nw(en))) return true;
                }
                // FORMULA multi-line editor: single MultiLineEditBox covers full edit panel height
                int enW = io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer.nw(en);
                // EditBox focus/click
                // FORMULA / COMMENT multi-line editor: MultiLineEditBox covers full edit panel height
                if (en.type == NodeType.FORMULA || en.type == NodeType.COMMENT) {
                    for (int fi = 0; fi < st.fields.size(); fi++) {
                        var b = st.fields.get(fi);
                        int mleY, mleH;
                        if (en.type == NodeType.COMMENT) {
                            // MLE fills body minus edit button: X=6..w-18, Y=6, H=body-12
                            mleY = 6;
                            mleH = Math.round(en.commentHeight) - 12;
                            enW = Math.round(en.commentWidth) - 28; // leave room for left button
                        } else {
                            mleY = editLocalY + 4 + 18;
                            mleH = EditPanel.calcRenderHeight(en, zoom, st);
                        }
                        if (lmx >= 0 && lmx <= enW && lmy >= mleY && lmy <= mleY + mleH) {
                            b.setFocused(true);
                            if (b.mouseClicked(lmx, lmy, 0)) editBoxDragNodeId = en.id;
                        } else b.setFocused(false);
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
                        if (lmx >= 0 && lmx <= enW && lmy >= fy && lmy <= fy + 18)
                        { b.setFocused(true); b.mouseClicked(lmx, lmy, 0); }
                        else b.setFocused(false);
                    }
                }
            }
            // TAB+左键 → 连线删除 / 多选 / 框选
            if (tabHeld) {
                var hc = hitConn(mx, my);
                if (hc != null) {
                    {var l=Minecraft.getInstance().level;if(l!=null)takeSnapshot(getGraph(),l.registryAccess());}
                    graph.removeConnection(hc.fromId, hc.fromPin, hc.toId, hc.toPin);
                    // 删除参数引脚连线后刷新编辑区（恢复输入框）
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
            // ▶/▼ 折叠展开按钮（优先检测，不依赖选中状态）
            var expandHit = hitExpandIndicator(mx, my, graph);
            if (expandHit != null) { toggleExpand(expandHit); return true; }
            // ── Comment node interaction ──
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
                boolean onResize = locX > n2.commentWidth - 14 && locY > n2.commentHeight - 14;
                boolean onColorDot = locX < 18 && locY < 18;
                // Resize handle (bottom-right 14x14) — always handled
                if (onResize) {
                    resizingComment = n2; resizeStartW = n2.commentWidth; resizeStartH = n2.commentHeight;
                    return true;
                }
                // Edit button (top-right 14x14) — open 3-color edit panel
                if (onColorDot) {
                    var lvl = Minecraft.getInstance().level;
                    if (lvl != null) takeSnapshot(getGraph(), lvl.registryAccess());
                    editingCommentColorNode = n2;
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
                                switch (idx) {
                                    case 0 -> editingCommentColorNode.commentBgColor = c;
                                    case 1 -> editingCommentColorNode.commentBorderColor = c;
                                    case 2 -> editingCommentColorNode.commentTextColor = c;
                                }
                                markDirty();
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
                // Only drag by header bar; non-header body clicks → select or skip
                if (hitIsNonComment || expandedNodeIds.contains(n2.id)) continue;
                // Header bar in local coords: headerH/zoom pixels from the top edge
                float commentHeaderLocal = Math.max(6f / zoom, 12f);
                boolean inCommentHeader = locY >= 0 && locY < commentHeaderLocal;
                if (!inCommentHeader) {
                    // Non-header click → select only, don't drag (like regular nodes)
                    if (selectedNode != n2) {
                        selectedNode = n2; selectedNodes.clear(); selectedNodes.add(n2);
                    }
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
                var lvl = Minecraft.getInstance().level;
                if (lvl != null) takeSnapshot(getGraph(), lvl.registryAccess());
                preDragSortB = n2.sortB;
                // Pin contained nodes with depth-based B: outermost=lowest B
                // (rendered first=behind), innermost=highest B (rendered last=on top)
                preDragSortBs.clear();
                var depthMap = new java.util.HashMap<GraphNode, Integer>();
                collectContainedNodesDepth(n2, depthMap, 1);
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
                    float pinCenterX = sx + (nw - 12) * zoom; // EditPanel 引脚绘于 px+pw-12=128
                    if (Math.abs(mx - pinCenterX) < 8 && Math.abs(my - py) < PH * zoom / 2f + 2) {
                        draggingWire = true; wireFromNode = node.id; wireFromPin = i;
                        wireEndX = s2cX(mx); wireEndY = s2cY(my); return true;
                    }
                }
            }
            // Wire drag — node body output pins, z-order aware
            for (var node : pinCandidates) {
                if (node.type == NodeType.SPEED_CTRL) continue;
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
            // 点击节点（不含 ▶/▼ 区域）
            var hit=hitNode(mx,my);
            if(hit!=null){
                // 仅在非 ▶/▼ 区域允许拖拽
                float sy=c2sY(hit.y);
                boolean inHeader = my>=sy && my<=sy+HH*zoom+4;
                if (inHeader) {
                    var lvl2 = Minecraft.getInstance().level;
                    if (lvl2 != null) takeSnapshot(getGraph(), lvl2.registryAccess());
                    preDragSortB = hit.sortB;
                    hit.sortB = Integer.MAX_VALUE;
                    draggingNode=hit; dragOffX=hit.x-s2cX(mx); dragOffY=hit.y-s2cY(my);
                }
                if (selectedNode != hit) {
                    selectedNode=hit; selectedNodes.clear(); selectedNodes.add(hit);
                }
                return true;
            }
            // 点击空白区域 → 取消选中（不折叠编辑区）
            selectedNodes.clear(); selectedNode=null;
            panning=true; panLastX=(float)mx; panLastY=(float)my;
        }
        // busBox 失焦提交（在按钮处理之后，避免 createEditState 冲掉频段编辑）
        for (var st : nodeEditStatesById.values()) {
            if (st.busBox != null && st.busBox.isFocused() && !st.busBox.getValue().equals(st.busNode.signalName))
                { commitBusBox(st); break; }
        }
        return false;
    }

    /** 提交 busBox 的值到 node.signalName */
    private void commitBusBox(EditState st) {
        if (st == null || st.busBox == null || st.busNode == null) return;
        var node = st.busNode;
        String oldName = node.signalName;
        String t = st.busBox.getValue();
        if (t.equals(oldName)) return;
        node.signalName = t;
        boolean localConflict = false, crossConflict = false;
        if (node.type == NodeType.BUS_OUT && !t.isEmpty()) {
            for (var n : getGraph().nodes)
                if (n != node && n.type == NodeType.BUS_OUT && n.signalName.equals(t)) { localConflict = true; break; }
            if (!localConflict && !localBusNames.contains(t)) {
                var gb = io.github.y15173334444.create_schematic_compute.network.SignalBus.getBands(t);
                if (gb != null && !gb.isEmpty()) crossConflict = true;
            }
        }
        node.busConflict = localConflict || crossConflict;
        // 旧名清理（不调用 createEditState）
        if (!oldName.isEmpty()) {
            boolean hasOldBusOut = false;
            for (var n : getGraph().nodes) if (n.type == NodeType.BUS_OUT && n.signalName.equals(oldName)) { hasOldBusOut = true; break; }
            if (!hasOldBusOut) {
                // 清除所有引用旧名称的 BUS_IN 节点（它们已无 BUS_OUT 提供数据）
                for (var n : getGraph().nodes)
                    if ((n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT) && n.signalName.equals(oldName))
                        { clearBusNode(n); }
                io.github.y15173334444.create_schematic_compute.network.SignalBus.clearBus(oldName);
            }
        }
        if (!localConflict && !crossConflict) {
            boolean synced = false;
            for (var n : getGraph().nodes) {
                if (n != node && n.type == NodeType.BUS_OUT && n.signalName.equals(t) && n.bandCount() > 0) {
                    node.signalBands = new java.util.ArrayList<>(n.signalBands); node.bandsDirty = true; synced = true; break;
                }
            }
            if (!synced) {
                var gb = io.github.y15173334444.create_schematic_compute.network.SignalBus.getBands(t);
                if (gb != null && !gb.isEmpty()) { node.signalBands = new java.util.ArrayList<>(gb); node.bandsDirty = true; synced = true; }
            }
        }
        // 重新评估所有 BUS_OUT 的冲突状态（改名可能解除其他节点的冲突）
        for (var n : getGraph().nodes) {
            if (n.type != NodeType.BUS_OUT || n.signalName.isEmpty()) continue;
            boolean c = false;
            for (var other : getGraph().nodes) {
                if (other != n && other.type == NodeType.BUS_OUT && other.signalName.equals(n.signalName)) {
                    c = true; break;
                }
            }
            n.busConflict = c;
        }
        // 重建编辑区（在最后调用，确保所有状态已更新）
        nodeEditStatesById.put(node.id, createEditState(node));
    }

    /** 清除 BUS 节点的频段、连线，并折叠编辑区 */
    private void clearBusNode(GraphNode n) {
        int oldCount = n.bandCount();
        // 清除该总线名的全局信号数据
        if (!n.signalName.isEmpty())
            io.github.y15173334444.create_schematic_compute.network.SignalBus.clearBus(n.signalName);
        n.signalBands.clear();
        n.bandsDirty = true;
        var g = getGraph();
        for (int pi = 0; pi < oldCount; pi++) {
            final int p = pi;
            g.connections.removeIf(c ->
                (c.fromId == n.id && c.fromPin == p) || (c.toId == n.id && c.toPin == p));
        }
        expandedNodeIds.remove(n.id);
        nodeEditStatesById.remove(n.id);
        n.expanded = false;
    }

    /** 同步所有同总线名的 BUS 节点的频段列表 */
    private void syncBusBands(GraphNode src) {
        if (src.signalName.isEmpty()) return;
        // 冲突的 BUS_OUT 不上传频段（防止频道夺取）
        if (src.type == NodeType.BUS_OUT && src.busConflict) return;
        var bands = src.signalBands;
        if (src.type == NodeType.BUS_OUT) {
            io.github.y15173334444.create_schematic_compute.network.SignalBus.registerBands(src.signalName, bands);
            localBusNames.add(src.signalName);
        }
        int newCount = bands != null ? bands.size() : 0;
        var g = getGraph();
        for (var n : getGraph().nodes) {
            if (n != src && (n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT)
                && n.signalName.equals(src.signalName)) {
                int oldCount = n.bandCount();
                n.signalBands = bands != null ? new java.util.ArrayList<>(bands) : new java.util.ArrayList<>();
                // 删除超出新频段数的旧连线
                for (int pi = newCount; pi < oldCount; pi++) {
                    final int p = pi;
                    g.connections.removeIf(c ->
                        (c.fromId == n.id && c.fromPin == p) || (c.toId == n.id && c.toPin == p));
                }
                var st = nodeEditStatesById.get(n.id);
                if (st != null) nodeEditStatesById.put(n.id, createEditState(n));
            }
        }
    }

    /** 子类可重写定义哪些节点左键打开编辑面板 */
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

    public void mouseReleased(double mx, double my, int btn) {
        if (colorPicker.isVisible()) { colorPicker.mouseReleased(mx, my, btn); return; }
        var graph = getGraph();
        editBoxDragNodeId = -1;
        // Comment resize complete
        if (resizingComment != null) {
            if (Math.abs(resizingComment.commentWidth - resizeStartW) > 1
                || Math.abs(resizingComment.commentHeight - resizeStartH) > 1) {
                var lvl = Minecraft.getInstance().level;
                if (lvl != null) takeSnapshot(getGraph(), lvl.registryAccess());
            }
            resizingComment = null;
            return;
        }
        if(btn==0&&multiDragging){
            multiDragging = false; markDirty();
            if (multiClickedNode != null) {
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
                    // TAB按住时框选切换选中状态
                    if (tabHeld && selectedNodes.contains(n)) selectedNodes.remove(n);
                    else selectedNodes.add(n);
                }
            }
            if(!selectedNodes.isEmpty()) selectedNode = selectedNodes.iterator().next();
            else selectedNode = null;
            return;
        }
        if(btn==0&&draggingWire){
            // 找最近的输入引脚（只连一个，避免多个引脚全连上）
            int bestNodeId=-1, bestPin=-1;
            float bestDist=Float.MAX_VALUE;
            float xTol=20;
            // 第一阶段：节点主体上的功能引脚
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
            // 第二阶段：编辑区内的参数引脚（展开的节点）
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
            // BUS_OUT 编辑区输入引脚
            if(bestNodeId<0){for(int nid:expandedNodeIds){var n=graph.findNode(nid);if(n==null||n.type!=NodeType.BUS_OUT||n.signalBands==null)continue;float sx=c2sX(n.x),sy2=c2sY(n.y);for(int bi=0;bi<n.signalBands.size();bi++){float py2=sy2+bandPinY(n,bi,zoom)*zoom;float px2=sx+10*zoom;float dx2=(float)Math.abs(mx-px2),dy2=(float)Math.abs(my-py2);if(dx2<16*zoom&&dy2<10*zoom&&wireFromNode!=nid){float dist2=dx2+dy2;if(dist2<bestDist){bestDist=dist2;bestNodeId=nid;bestPin=bi;}}}}}
            if(bestNodeId>=0){
                {var l=Minecraft.getInstance().level;if(l!=null)takeSnapshot(getGraph(),l.registryAccess());}
                graph.addConnection(wireFromNode,wireFromPin,bestNodeId,bestPin);
                // 参数引脚连线后刷新编辑区（隐藏对应输入框）
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
            preDragSortBs.clear();
            markDirty();
            draggingNode=null;
        }if(btn==0&&panning)panning=false;
    }
    public void mouseMoved(double mx, double my) {
        lastMouseX = mx; lastMouseY = my;
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
            markDirty();
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
            markDirty();
            return;
        }
        // Drag-select in expanded FORMULA EditBox
        if (editBoxDragNodeId >= 0 && (org.lwjgl.glfw.GLFW.glfwGetMouseButton(
            org.lwjgl.glfw.GLFW.glfwGetCurrentContext(), 0) == org.lwjgl.glfw.GLFW.GLFW_PRESS)) {
            var en = getGraph().findNode(editBoxDragNodeId);
            if (en != null && expandedNodeIds.contains(en.id)) {
                var st = nodeEditStatesById.get(en.id);
                if (st != null && !st.fields.isEmpty()) {
                    float sx = c2sX(en.x), sy = c2sY(en.y);
                    st.fields.get(0).mouseDragged((mx - sx) / zoom, (my - sy) / zoom, 0, 0, 0);
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
            return;
        }
        if(panning){camX+=(float)(mx-panLastX)/zoom;camY+=(float)(my-panLastY)/zoom;panLastX=(float)mx;panLastY=(float)my;}
        if(draggingNode!=null){
            float nx=s2cX(mx)+dragOffX, ny=s2cY(my)+dragOffY;
            if(gridSnapEnabled){nx=Math.round(nx/NodeRenderer.GS)*NodeRenderer.GS;ny=Math.round(ny/NodeRenderer.GS)*NodeRenderer.GS;}
            draggingNode.x=nx;draggingNode.y=ny;
        }if(draggingWire){wireEndX=s2cX(mx);wireEndY=s2cY(my);}
    }
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (colorPicker.isVisible()) return colorPicker.mouseDragged(mx, my, btn, dx, dy);
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
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (colorPicker.isVisible() && colorPicker.mouseScrolled(mx, my, sy)) return true;
        if (showImportDialog) { importScrollOff += (sy > 0) ? -1 : 1; if (importScrollOff < 0) importScrollOff = 0; return true; }
        if (showExportDialog || showColorConfig) return true;
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
    public boolean keyPressed(int key, int sc, int mod) {
        var graph = getGraph();
        // 导出对话框键盘
        if (showExportDialog) {
            if (key == 256) { showExportDialog = false; exportNameEdit = null; return true; } // Esc
            if (key == 257 && exportNameEdit != null && selectedNode != null) { // Enter
                String name = exportNameEdit.getValue().trim();
                if (!name.isEmpty()) exportEncapNode(selectedNode, name);
                showExportDialog = false; exportNameEdit = null; return true;
            }
            if (exportNameEdit != null) return exportNameEdit.keyPressed(key, sc, mod);
            return true;
        }
        // 导入对话框键盘
        if (showImportDialog) {
            if (key == 256) { showImportDialog = false; importFiles = null; return true; } // Esc
            return true;
        }
        // Color picker keyboard delegation (takes priority when open)
        if (colorPicker.isVisible()) {
            return colorPicker.keyPressed(key, sc, mod);
        }
        if (editingCommentColorNode != null && commentButtons != null) {
            if (key == 256) { closeCommentColorPopup(); return true; } // Esc
            return false; // no keyboard for comment popup — only button clicks
        }
        if (showColorConfig) {
            if (key == 256) { showColorConfig = false; colorPicker.close(); return true; }
        }
        if (key == 257) { // Enter: 提交当前聚焦的编辑框
            for (var e : enterActions.entrySet()) {
                if (e.getKey().isFocused()) { e.getValue().run(); return true; }
            }
            for (var st : nodeEditStatesById.values()) {
                if (st.busBox != null && st.busBox.isFocused()) { commitBusBox(st); return true; }
            }
        }
        if (key == 258) { tabHeld = true; return true; } // TAB
        // KEYBOARD 按键绑定捕获（GAMEPAD_BUTTON 由 renderBg 每帧轮询处理）
        if (!nodeEditStatesById.isEmpty()) {
            for (var st : nodeEditStatesById.values()) {
                if (st.listeningForKey) {
                    // GAMEPAD_BUTTON handled by renderBg() — only ESC cancels, other keys ignored
                    boolean isGpad = false;
                    for (var en : getGraph().nodes) {
                        var es = nodeEditStatesById.get(en.id);
                        if (es == st && en.type == NodeType.GAMEPAD_BUTTON) { isGpad = true; break; }
                    }
                    if (isGpad) {
                        if (key == 256) { st.listeningForKey = false; return true; }
                        return true; // consume event, let renderBg() handle capture
                    }
                    // 键盘绑定
                    if (key == 256) { st.listeningForKey = false; return true; }
                    int idx = io.github.y15173334444.create_schematic_compute.blocks.EditPanel.glfwKeyToIndex(key);
                    if (idx >= 0) {
                        for (var en : getGraph().nodes) {
                            var es = nodeEditStatesById.get(en.id);
                            if (es == st && en.params.length > 0) { en.params[0] = idx; break; }
                        }
                        st.listeningForKey = false;
                    }
                    return true;
                }
            }
        }
        for (var st : nodeEditStatesById.values()) for (var f : st.fields) if (f.isFocused()) return f.keyPressed(key, sc, mod);
        // X 键删除悬停节点（替代右键删除防误触）
        if (key == 88) { // GLFW_KEY_X
            var g2 = getGraph();
            var hit = hitNode(lastMouseX, lastMouseY);
            if (hit != null) {
                {var l=Minecraft.getInstance().level;if(l!=null)takeSnapshot(g2,l.registryAccess());}
                g2.removeNode(hit.id);
                expandedNodeIds.remove(hit.id);
                nodeEditStatesById.remove(hit.id);
                selectedNodes.remove(hit);
                if (selectedNode == hit) selectedNode = null;
                return true;
            }
        }
        // Ctrl+Z / Ctrl+Y undo / redo
        if (net.minecraft.client.gui.screens.Screen.hasControlDown()) {
            if (key == 90) { var lvl = Minecraft.getInstance().level; if (lvl != null) performUndo(host, lvl.registryAccess()); return true; }
            if (key == 89) { var lvl = Minecraft.getInstance().level; if (lvl != null) performRedo(host, lvl.registryAccess()); return true; }
        }
        // Ctrl+D 复制（支持多选）
        if(key==68&&net.minecraft.client.gui.screens.Screen.hasControlDown()&&!selectedNodes.isEmpty()){
            {var l=Minecraft.getInstance().level;if(l!=null)takeSnapshot(getGraph(),l.registryAccess());}
            var idMap = new java.util.HashMap<Integer, Integer>();
            var newNodes = new java.util.ArrayList<GraphNode>();
            float ofs = 30;
            // 克隆所有选中节点（含子图等所有字段）
            for (var n : selectedNodes) {
                int newId = graph.nextNodeId++;
                var dup = n.shallowCopyWithNewId(newId);
                dup.x += ofs; dup.y += ofs;
                // BUS 节点复制后清空频道名，只保留 MAP 结构（避免与原节点频道冲突）
                if (dup.type == NodeType.BUS_IN || dup.type == NodeType.BUS_OUT) {
                    dup.signalName = "";
                }
                graph.adoptNode(dup);
                idMap.put(n.id, dup.id);
                newNodes.add(dup);
                // 复制展开状态
                if (n.expanded) {
                    expandedNodeIds.add(dup.id);
                    nodeEditStatesById.put(dup.id, createEditState(dup));
                }
            }
            // 复制选中节点之间的连接
            for (var c : List.copyOf(graph.connections)) {
                if (idMap.containsKey(c.fromId) && idMap.containsKey(c.toId)) {
                    graph.addConnection(idMap.get(c.fromId), c.fromPin, idMap.get(c.toId), c.toPin);
                }
            }
            // 更新选中为新节点
            selectedNodes.clear();
            selectedNodes.addAll(newNodes);
            selectedNode = newNodes.isEmpty() ? null : newNodes.get(0);
            return true;
        }
        // Delete 删除选中节点
        if ((key == 259 || key == 261) && !selectedNodes.isEmpty()) {
            {var l=Minecraft.getInstance().level;if(l!=null)takeSnapshot(getGraph(),l.registryAccess());}
            for (var n : List.copyOf(selectedNodes)) {
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
                graph.removeNode(n.id);
            }
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
            var l = Minecraft.getInstance().level;
            if (l != null) takeSnapshot(getGraph(), l.registryAccess());
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
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
            float padding = 20;
            float cw = maxX - minX + padding * 2 + 8;
            float ch = maxY - minY + padding * 2 + 4;
            cw = Math.max(80, Math.min(8000, cw));
            ch = Math.max(40, Math.min(6000, ch));
            var comment = graph.addNode(NodeType.COMMENT, minX - padding, minY - padding);
            comment.commentWidth = cw;
            comment.commentHeight = ch;
            return true;
        }
        return false;
    }
    public boolean keyReleased(int key, int sc, int mod) {
        if (key == 258) { tabHeld = false; return true; }
        return false;
    }
    public boolean charTyped(char ch, int mod) {
        if (colorPicker.isVisible()) return colorPicker.charTyped(ch, mod);
        if (showExportDialog && exportNameEdit != null) return exportNameEdit.charTyped(ch, mod);
        for (var st : nodeEditStatesById.values()) for (var f : st.fields) if (f.isFocused()) return f.charTyped(ch, mod);
        return false;
    }

    /** 颜色配置面板（双列布局） */
    private void renderColorPanel(GuiGraphics g, int mx, int my) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        int itemsPerCol = 8, numRows = 8; // 16色分 8+8 两列
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
            // 色块按钮（预览暂存颜色）
            themeButtons[i].setPosition(cx + 2, ry + 2);
            themeButtons[i].render(g, mx, my);
            // 名称
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
        // 编译前同步所有未保存的编辑（busBox + 频段改名）
        var pendingCommits = new java.util.ArrayList<>(nodeEditStatesById.values());
        for (var st : pendingCommits) {
            if (st.busBox != null && st.busNode != null
                && !st.busBox.getValue().equals(st.busNode.signalName)) {
                commitBusBox(st);
            }
            // 同步频段 EditBox 的值
            var node = st.busNode;
            if (node != null && node.type == NodeType.BUS_OUT && st.fields.size() > 1) {
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
            }
        }
        // 编译时当前状态回归初始值
        for (var n : graph.nodes) {
            if ((n.type == NodeType.GATE || n.type == NodeType.T_FLIPFLOP || n.type == NodeType.LATCH) && n.params.length > 1) {
                n.params[1] = n.params[0];
            }
        }
        saveGraph();
        host.toggleRunning(false);
        markDirty();
    }

    /** 检测 ▶/▼ 展开按钮点击 */
    private GraphNode hitExpandIndicator(double mx, double my, NodeGraph graph) {
        float indicatorSize = 12 * zoom;
        float scx = s2cX(mx), scy = s2cY(my);
        var candidates = spatialIndex.queryPoint(scx, scy);
        candidates.sort(GraphEditor::compareHitOrder);
        for (var n : candidates) {
            if (n.type == NodeType.COMMENT) continue;
            if (n.type.paramNames.length == 0 && n.type != NodeType.REDSTONE_IN && n.type != NodeType.REDSTONE_OUT
                && n.type != NodeType.PRIVATE_IN && n.type != NodeType.PRIVATE_OUT && n.type != NodeType.FORMULA
                && n.type != NodeType.KEYBOARD && n.type != NodeType.GAMEPAD_BUTTON
                && n.type != NodeType.IMAGE && n.type != NodeType.IMAGE_SEQUENCE && n.type != NodeType.TEXT && n.type != NodeType.DATA
                && n.type != NodeType.ENCAPSULATION && n.type != NodeType.ENCAP_INPUT && n.type != NodeType.ENCAP_OUTPUT
                && n.type != NodeType.BUS_IN && n.type != NodeType.BUS_OUT) continue;
            float sx = c2sX(n.x), sy = c2sY(n.y);
            float ix = sx + (io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer.nw(n) - 22) * zoom;
            float iy = sy + 2 * zoom;
            if (mx >= ix && mx <= ix + indicatorSize && my >= iy && my <= iy + indicatorSize)
                return n;
        }
        return null;
    }

    private void closeCommentColorPopup() {
        editingCommentColorNode = null;
        commentButtons = null;
        colorPicker.close();
    }

    /** Open/rebind the color picker to a theme staging color. */
    private void openColorPickerForTheme(int idx) {
        Consumer<Integer> setter = c -> { NodeRenderer.stagingColors[idx] = c; };
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
            case 0 -> c -> { editingCommentColorNode.commentBgColor = c; markDirty(); };
            case 1 -> c -> { editingCommentColorNode.commentBorderColor = c; markDirty(); };
            case 2 -> c -> { editingCommentColorNode.commentTextColor = c; markDirty(); };
            default -> c -> {};
        };
        if (colorPicker.isVisible()) {
            colorPicker.rebind(color, setter);
        } else {
            int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            colorPicker.open(sw - ColorPickerWidget.WIDTH / 2, Minecraft.getInstance().getWindow().getGuiScaledHeight() / 2,
                color, setter);
        }
    }

    private static String plainText(String line) {
        return line.replaceAll("\\*\\*|\\*|`|#\\s?", "");
    }

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
     *  and nesting depth. Recursive for nested comments. */
    private void collectContainedNodes(GraphNode comment, java.util.Map<GraphNode, Integer> out) {
        collectContainedNodesDepth(comment, out, 0);
    }
    private void collectContainedNodesDepth(GraphNode comment, java.util.Map<GraphNode, Integer> out, int depth) {
        var candidates = spatialIndex.queryRect(
            comment.x, comment.y, comment.commentWidth, comment.commentHeight);
        for (var n : candidates) {
            if (n == comment || out.containsKey(n)) continue;
            float nw = NodeRenderer.nw(n);
            float nh = NodeRenderer.nh(n);
            // Only collect nodes fully inside the comment
            if (n.x >= comment.x && n.x + nw <= comment.x + comment.commentWidth
                && n.y >= comment.y && n.y + nh <= comment.y + comment.commentHeight) {
                out.put(n, n.sortB);          // save original
                if (n.type == NodeType.COMMENT) {
                    collectContainedNodesDepth(n, out, depth + 1);
                }
            }
        }
    }

    /** Recursively move all nodes whose center is inside the given comment's rectangle. */
    private void moveContainedNodes(GraphNode comment, float dx, float dy) {
        moveContainedNodes(comment, dx, dy, new java.util.HashSet<>());
    }
    private void moveContainedNodes(GraphNode comment, float dx, float dy, java.util.Set<Integer> moved) {
        var candidates = spatialIndex.queryRect(
            comment.x, comment.y, comment.commentWidth, comment.commentHeight);
        for (var n : candidates) {
            if (n == comment || moved.contains(n.id)) continue;
            float nw = NodeRenderer.nw(n);
            float nh = NodeRenderer.nh(n);
            // Only move nodes fully inside the comment (not parent comments that contain it)
            if (n.x >= comment.x && n.x + nw <= comment.x + comment.commentWidth
                && n.y >= comment.y && n.y + nh <= comment.y + comment.commentHeight) {
                n.x += dx; n.y += dy;
                moved.add(n.id);
                if (n.type == NodeType.COMMENT) {
                    moveContainedNodes(n, dx, dy, moved);
                }
            }
        }
    }

    /** Sort candidates by A-layer first (higher A = visually on top), then B descending. */
    private static int compareHitOrder(GraphNode a, GraphNode b) {
        int aA = a.type == NodeType.COMMENT ? 1 : 3;  // A=1 comments behind A=3 nodes
        int bA = b.type == NodeType.COMMENT ? 1 : 3;
        int cmp = Integer.compare(bA, aA); // higher A first
        if (cmp != 0) return cmp;
        return Integer.compare(b.sortB, a.sortB); // higher B first within same A
    }

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
    private NodeConnection hitConn(double mx, double my) {
        var graph = getGraph();
        NodeConnection best=null;
        float globalMin=12; // 阈值
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
    private static float distanceToSegment(float px,float py,float x1,float y1,float x2,float y2){
        float abx=x2-x1, aby=y2-y1, apx=px-x1, apy=py-y1;
        float dot=apx*abx+apy*aby, len2=abx*abx+aby*aby;
        float t=len2==0?0:Math.max(0,Math.min(1,dot/len2));
        float cx=x1+t*abx, cy=y1+t*aby;
        float dx=px-cx, dy=py-cy;
        return (float)Math.sqrt(dx*dx+dy*dy);
    }

    /** 计算 BUS 编辑面板中第 pinIndex 个 band pin 的本地 Y 偏移（从节点顶部算起） */
    static float bandPinY(GraphNode node, int pinIndex, double zoom) {
        int editLY = (int)(HH + PH * (node.functionalInputs() + node.outputs()) + 4 / zoom);
        return editLY + 30 + pinIndex * 18;
    }

    // ── 封装节点导入/导出 ──────────────────────────────────

    private static Path getExportPath() {
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("create_schematic_compute").resolve("exports").resolve("encap_export.nbt");
    }

    private void exportEncapNode(GraphNode node, String name) {
        if (node.type != NodeType.ENCAPSULATION) return;
        try {
            var level = Minecraft.getInstance().level;
            if (level == null) return;
            Path dir = getExportPath().getParent();
            Files.createDirectories(dir);
            // 同名文件自动追加序号，避免覆盖
            Path file = dir.resolve(name + ".nbt");
            String finalName = name;
            if (Files.exists(file)) {
                for (int n = 2; n < 1000; n++) {
                    Path alt = dir.resolve(name + "_" + n + ".nbt");
                    if (!Files.exists(alt)) { file = alt; finalName = name + "_" + n; break; }
                }
            }
            CompoundTag tag = node.save(level.registryAccess());
            NbtIo.writeCompressed(tag, file);
            importFeedbackUntil = System.currentTimeMillis() + 3000;
            saveFeedbackText = "§a" + I18n.get("gui.create_schematic_compute.encap_exported") + ": " + finalName;
        } catch (IOException e) {
            importFeedbackUntil = System.currentTimeMillis() + 3000;
            saveFeedbackText = "§c" + e.getMessage();
        }
    }

    private void importEncapNode(Path file) {
        try {
            var level = Minecraft.getInstance().level;
            if (level == null) return;
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.create(2 * 1024 * 1024));
            GraphNode imported = GraphNode.load(tag, level.registryAccess());
            // 分配到当前图中，分配新 ID
            var g = getGraph();
            imported.id = g.nextNodeId++;
            imported.x = 100; imported.y = 100; // 默认位置
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

    static final int NW=NodeRenderer.NW, HH=NodeRenderer.HH, PH=NodeRenderer.PH;

    // ── Fast number formatting to avoid String.format allocation (Phase 1) ──
    static String ff3(float v) { return Float.toString((float)Math.round(v * 1000) / 1000); }
    static String hex8(int v) { String h = Integer.toHexString(v).toUpperCase(); return "00000000".substring(h.length()) + h; }
}
