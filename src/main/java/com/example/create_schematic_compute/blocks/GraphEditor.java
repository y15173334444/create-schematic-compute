package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.graph.GraphNode;
import com.example.create_schematic_compute.graph.NodeConnection;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

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
        default Map<Integer, Boolean> getFlipflopStates() { return null; }
    }

    private final Host host;
    public final NodeRenderer renderer;
    private Predicate<NodeType> nodeFilter;

    // 编辑状态
    public float camX=0, camY=0, zoom=1f;
    public GraphNode draggingNode=null, selectedNode=null;
    public final Set<GraphNode> selectedNodes = new HashSet<>();
    public float dragOffX, dragOffY;
    public boolean panning=false;
    public float panLastX, panLastY;
    public boolean draggingWire=false;
    public int wireFromNode=-1, wireFromPin=-1;
    public float wireEndX, wireEndY;
    public boolean showMenu=false;
    public float menuX, menuY;
    public NodeType selectedMenuType=null;
    public long saveFeedbackUntil=0;
    public String cycleWarning=null;
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
    }
    public final java.util.Map<Integer, EditState> nodeEditStatesById = new java.util.HashMap<>();
    // 颜色配置面板
    public boolean showColorConfig = false;
    public final net.minecraft.client.gui.components.EditBox[] colorFields = new net.minecraft.client.gui.components.EditBox[NodeRenderer._NUM_COLORS];
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
        host.saveGraph(); // 保存以便子图变更持久化
    }

    public NodeGraph getGraph() {
        return isInSubGraph() ? encapsulationParent.subGraph : host.getGraph();
    }

    public void saveGraph() {
        host.saveGraph();
    }

    /** 创建节点的编辑状态 */
    private EditState createEditState(GraphNode node) {
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
            b.setValue(String.format("%.3f", node.params[i]));
            float oldVal = node.params[idx];
            b.setResponder(t -> { try { node.params[idx] = Float.parseFloat(t.trim()); } catch (Exception e) { node.params[idx] = oldVal; } });
            s.fields.add(b);
            s.fieldParamIndices.add(i);
        }
        if ((node.type == NodeType.REDSTONE_IN || node.type == NodeType.REDSTONE_OUT) && node.itemParams.length < 2)
            node.itemParams = new ItemStack[]{ItemStack.EMPTY, ItemStack.EMPTY};
        if (node.type == NodeType.PRIVATE_IN || node.type == NodeType.PRIVATE_OUT) {
            var sb = new EditBox(mc.font, 0, 0, 120, 16, Component.literal(""));
            sb.setMaxLength(32); sb.setValue(node.signalName);
            sb.setResponder(t -> node.signalName = t);
            s.fields.add(sb);
        }
        if (node.type == NodeType.TEXT) {
            var tb = new EditBox(mc.font, 0, 0, 120, 16, Component.literal(""));
            tb.setMaxLength(256); tb.setValue(node.displayText);
            tb.setResponder(t -> node.displayText = t);
            s.fields.add(tb);
            var cb = new EditBox(mc.font, 0, 0, 70, 16, Component.literal(""));
            cb.setMaxLength(8); cb.setValue(String.format("%08X", node.textColor != 0 ? node.textColor : 0xFFCCCCCC));
            cb.setResponder(t -> { try { node.textColor = (int)(Long.parseLong(t.trim(), 16) & 0xFFFFFFFFL); } catch (Exception e) {} });
            s.fields.add(cb);
            s.paramKeys = new String[]{"text", "color"};
        }
        if (node.type == NodeType.DATA) {
            var cb = new EditBox(mc.font, 0, 0, 70, 16, Component.literal(""));
            cb.setMaxLength(8); cb.setValue(String.format("%08X", node.textColor != 0 ? node.textColor : 0xFF88FF88));
            cb.setResponder(t -> { try { node.textColor = (int)(Long.parseLong(t.trim(), 16) & 0xFFFFFFFFL); } catch (Exception e) {} });
            s.fields.add(cb);
            s.paramKeys = new String[]{"color"};
        }
        if (node.type == NodeType.IMAGE || node.type == NodeType.IMAGE_SEQUENCE) {
            String[] keys = {"moveX", "moveY", "rotScl"};
            float[] defaults = {0.01f, 0.01f, 1f};
            for (int pi = 0; pi < 3; pi++) {
                int idx = pi;
                var b = new EditBox(mc.font, 0, 0, 50, 16, Component.literal(""));
                b.setMaxLength(8); b.setValue(String.format("%.3f", node.params.length > idx ? node.params[idx] : defaults[idx]));
                b.setResponder(t -> { try { if (node.params.length > idx) node.params[idx] = Float.parseFloat(t.trim()); } catch (Exception e) {} });
                s.fields.add(b);
            }
            s.paramKeys = keys;
        }
        if (node.type == NodeType.FORMULA) {
            var fb = new EditBox(mc.font, 0, 0, 140, 16, Component.literal(""));
            fb.setMaxLength(64); fb.setValue(node.formula.isEmpty() ? "A+B" : node.formula);
            fb.setResponder(t -> {
                String clean = t.replaceAll("[^a-zA-Z0-9+\\-*/%^(). ]", "");
                if (!clean.equals(t)) fb.setValue(clean);
                node.formula = clean;
                node.dynamicInputCount = com.example.create_schematic_compute.graph.FormulaParser.extractVariables(clean).size();
            });
            s.fields.add(fb);
        }
        if (node.type == NodeType.ENCAP_INPUT || node.type == NodeType.ENCAP_OUTPUT) {
            var nb = new EditBox(mc.font, 0, 0, 100, 16, Component.literal(""));
            nb.setMaxLength(32); nb.setValue(node.displayText);
            nb.setResponder(t -> node.displayText = t);
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
    }

    public GraphEditor(Host host, Screen screen) {
        this.host = host;
        this.renderer = new NodeRenderer(this::c2sX, this::c2sY, screen);
        var mc = net.minecraft.client.Minecraft.getInstance();
        for (int i = 0; i < NodeRenderer._NUM_COLORS; i++) {
            int idx = i;
            colorFields[i] = new net.minecraft.client.gui.components.EditBox(mc.font, 0, 0, 80, 14, net.minecraft.network.chat.Component.literal(""));
            colorFields[i].setMaxLength(8);
            int[] cur = NodeRenderer.currentColors();
            colorFields[i].setValue(String.format("%08X", cur[i]));
            colorFields[i].setResponder(s -> {
                if (s.length() == 8) try {
                    NodeRenderer.stagingColors[idx] = (int)(Long.parseLong(s, 16) & 0xFFFFFFFFL);
                } catch (Exception ignored) {}
            });
        }
    }

    public void setNodeFilter(Predicate<NodeType> filter) { this.nodeFilter = filter; this.mainNodeFilter = filter; }

    // 坐标转换
    public float c2sX(float cx) { Screen s = host.asScreen(); return s.width/2f+(cx+camX)*zoom; }
    public float c2sY(float cy) { Screen s = host.asScreen(); return s.height/2f+(cy+camY)*zoom; }
    public float s2cX(double sx) { Screen s = host.asScreen(); return(float)((sx-s.width/2f)/zoom-camX); }
    public float s2cY(double sy) { Screen s = host.asScreen(); return(float)((sy-s.height/2f)/zoom-camY); }

    // 渲染优先级（低→高，高的覆盖低的）
    //  0: 网格
    //  1: 连线
    //  2: 节点主体 + 展开编辑区（背景，pose内渲染）
    //    编辑控件（EditBox/物品图标）仅在无覆盖层时渲染，避免穿透
    //  3: 按钮栏
    //  4: 热栏弹出
    //  5: 颜色设置面板 / Nodes菜单
    //  6: 框选矩形
    private boolean expandedInitDone = false;

    public void renderBg(GuiGraphics g, int mx, int my) {
        var graph = getGraph();
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
        renderer.renderGrid(g, camX, camY, zoom, host.asScreen().width, host.asScreen().height);

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

        renderer.renderConnections(g, graph, camX, camY, zoom);
        if(draggingWire) renderer.renderDraggingWire(g, graph, wireFromNode, wireFromPin, wireEndX, wireEndY, camX, camY, zoom);
        renderer.suppressControls = showColorConfig || showMenu;
        Map<Integer, Boolean> flipflopStates = host.getFlipflopStates();
        renderer.renderNodes(g, graph.nodes, selectedNodes, selectedNode, expandedNodeIds, nodeEditStatesById,
            camX, camY, zoom, mx, my, flipflopStates);
        if (!isInSubGraph()) {
            renderer.renderButtons(g, true, host.isRunning(), cycleWarning, saveFeedbackUntil, gridSnapEnabled, 0, host.asScreen().width, host.asScreen().height);
        } else {
            // 封装模式标识 (替换按钮栏)
            var mc2 = Minecraft.getInstance();
            g.fill(2, 2, host.asScreen().width - 2, 22, 0xFF3A2A1A);
            String modeText = "◈ " + net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.encap_mode") + " ◈";
            int mtw = mc2.font.width(modeText);
            g.drawString(mc2.font, modeText, (host.asScreen().width - mtw) / 2, 6, 0xFFFFCC88);
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
                g.renderOutline(px, py, pw, ph, NodeRenderer.CSB);
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
        // 框选矩形
        if (boxSelecting) {
            float x1 = Math.min(boxSX, boxEX), y1 = Math.min(boxSY, boxEY);
            float x2 = Math.max(boxSX, boxEX), y2 = Math.max(boxSY, boxEY);
            g.fill((int)x1, (int)y1, (int)x2, (int)y2, 0x22D4A017);
            g.renderOutline((int)x1, (int)y1, (int)(x2-x1), (int)(y2-y1), 0xFFD4A017);
        }
        // GAMEPAD_BUTTON binding capture: poll gamepad each frame since gamepad buttons don't fire key events
        if (!nodeEditStatesById.isEmpty()) {
            var gamepadNodes = new java.util.ArrayList<com.example.create_schematic_compute.graph.GraphNode>();
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
                        for (int i = 0; i < NodeRenderer._NUM_COLORS; i++)
                            colorFields[i].setValue(String.format("%08X", NodeRenderer.stagingColors[i]));
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
            if(selectedMenuType!=null)graph.addNode(selectedMenuType,s2cX(mx),s2cY(my));showMenu=false;return true;}
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
        // 颜色配置面板打开时阻止所有画布交互
        if (showColorConfig && btn == 0) {
            var mc = Minecraft.getInstance();
            int colW = 185, pw = colW * 2 + 30, ph = 36 + 8 * 18 + 24;
            int px = (host.asScreen().width - pw) / 2, py = (host.asScreen().height - ph) / 2;
            // 点击面板外部 → 关闭
            if (mx < px || mx > px + pw || my < py || my > py + ph) { showColorConfig = false; return true; }
            // 关闭按钮
            if (mx >= px + pw - 18 && mx <= px + pw - 2 && my >= py + 2 && my <= py + 18) { showColorConfig = false; return true; }
            // Defaults
            if (mx >= px + 8 && mx <= px + 72 && my >= py + ph - 22 && my <= py + ph - 6) {
                NodeRenderer.setColors(NodeRenderer.DEFAULT_COLORS.clone());
                for (int i = 0; i < NodeRenderer._NUM_COLORS; i++) colorFields[i].setValue(String.format("%08X", NodeRenderer.DEFAULT_COLORS[i]));
                return true;
            }
            // Apply
            if (mx >= px + pw - 72 && mx <= px + pw - 8 && my >= py + ph - 22 && my <= py + ph - 6) {
                NodeRenderer.setColors(NodeRenderer.stagingColors.clone());
                NodeRenderer.saveColorConfig();
                showColorConfig = false;
                return true;
            }
            // 颜色字段焦点（先全部清除再设置单个，避免多个光标）
            for (int i = 0; i < NodeRenderer._NUM_COLORS; i++) colorFields[i].setFocused(false);
            for (int i = 0; i < NodeRenderer._NUM_COLORS; i++) {
                if (mx >= colorFields[i].getX() && mx <= colorFields[i].getX() + 80
                    && my >= colorFields[i].getY() && my <= colorFields[i].getY() + 14) {
                    colorFields[i].setFocused(true);
                    colorFields[i].mouseClicked(mx, my, btn);
                    break;
                }
            }
            return true;
        }
        // 旧的颜色配置面板交互（隔离用，新逻辑在上面）
        if(btn==0){
            showMenu=false;
            // 内联编辑区交互（局部坐标，与 pose 内渲染一致）
            for (var en : getGraph().nodes) {
                if (!expandedNodeIds.contains(en.id)) continue;
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
                if ((en.type == NodeType.IMAGE || en.type == NodeType.IMAGE_SEQUENCE) && en.params.length > 3) {
                    for (int ti = 0; ti < 2; ti++) {
                        int tgY = editLocalY + 4 + (numRows + ti) * 18;
                        if (lmx >= 4 && lmx <= NW - 4 && lmy >= tgY && lmy <= tgY + 14)
                        { en.params[3 + ti] = en.params[3 + ti] > 0.5f ? 0 : 1; return true; }
                    }
                }
                if (en.type == NodeType.KEYBOARD || en.type == NodeType.GAMEPAD_BUTTON) {
                    int kbLocalY = editLocalY + 4;
                    if (EditPanel.handleKeyboardClick(en, st, lmx, lmy - kbLocalY, NW)) return true;
                }
                for (int fi = 0; fi < st.fields.size(); fi++) {
                    var b = st.fields.get(fi);
                    int fy = editLocalY + 4 + fi * 18;
                    if (lmx >= 0 && lmx <= NW && lmy >= fy && lmy <= fy + 18)
                    { b.setFocused(true); b.mouseClicked(lmx, lmy, 0); }
                    else b.setFocused(false);
                }
            }
            // TAB+左键 → 连线删除 / 多选 / 框选
            if (tabHeld) {
                var hc = hitConn(mx, my);
                if (hc != null) {
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
            // 拖拽连线
            for(var node:graph.nodes){if(node.type==NodeType.SPEED_CTRL)continue;float sx=c2sX(node.x),sy=c2sY(node.y);for(int i=0;i<node.outputs();i++){float py=sy+HH*zoom+PH*zoom*(node.functionalInputs()+i)+PH*zoom/2f;if(Math.abs(mx-(sx+NW*zoom))<8&&Math.abs(my-py)<PH*zoom/2f+2){draggingWire=true;wireFromNode=node.id;wireFromPin=i;wireEndX=s2cX(mx);wireEndY=s2cY(my);return true;}}}
            // 点击节点（不含 ▶/▼ 区域）
            var hit=hitNode(mx,my);
            if(hit!=null){
                // 仅在非 ▶/▼ 区域允许拖拽
                float sy=c2sY(hit.y);
                boolean inHeader = my>=sy && my<=sy+HH*zoom+4;
                if (inHeader) { draggingNode=hit; dragOffX=hit.x-s2cX(mx); dragOffY=hit.y-s2cY(my); }
                if (selectedNode != hit) {
                    selectedNode=hit; selectedNodes.clear(); selectedNodes.add(hit);
                }
                return true;
            }
            // 点击空白区域 → 取消选中（不折叠编辑区）
            selectedNodes.clear(); selectedNode=null;
            panning=true; panLastX=(float)mx; panLastY=(float)my;
        }
        return false;
    }

    /** 子类可重写定义哪些节点左键打开编辑面板 */
    protected boolean shouldOpenPanel(GraphNode node) {
        return node.type.paramNames.length > 0 || node.type == NodeType.REDSTONE_IN
            || node.type == NodeType.REDSTONE_OUT || node.type == NodeType.PRIVATE_IN
            || node.type == NodeType.PRIVATE_OUT || node.type == NodeType.PID_POWER
            || node.type == NodeType.FORMULA || node.type == NodeType.KEYBOARD
            || node.type == NodeType.GAMEPAD_BUTTON
            || node.type == NodeType.TEXT || node.type == NodeType.IMAGE
            || node.type == NodeType.IMAGE_SEQUENCE || node.type == NodeType.DATA
            || node.type == NodeType.ENCAPSULATION || node.type == NodeType.ENCAP_INPUT
            || node.type == NodeType.ENCAP_OUTPUT;
    }

    public void mouseReleased(double mx, double my, int btn) {
        var graph = getGraph();
        if(btn==0&&multiDragging){
            multiDragging = false;
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
            if(bestNodeId>=0){
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
        if(btn==0&&draggingNode!=null)draggingNode=null;if(btn==0&&panning)panning=false;
    }
    public void mouseMoved(double mx, double my) {
        lastMouseX = mx; lastMouseY = my;
        if(boxSelecting){boxEX=(float)mx;boxEY=(float)my;return;}
        if(multiDragging){
            float dx = (s2cX(mx) - dragOffX) - multiCenterX;
            float dy = (s2cY(my) - dragOffY) - multiCenterY;
            for (var sn : selectedNodes) {
                float[] orig = multiDragOrigins.get(sn);
                if (orig != null) {
                    float nx = orig[0] + dx, ny = orig[1] + dy;
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
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (showColorConfig) return true; // 颜色面板打开时禁止缩放
        float oz=zoom; zoom*=(sy>0)?1.12f:(1f/1.12f); zoom=Math.max(0.25f,Math.min(4f,zoom));
        camX+=(mx-host.asScreen().width/2f)*(1f/zoom-1f/oz); camY+=(my-host.asScreen().height/2f)*(1f/zoom-1f/oz); return true;
    }
    public boolean keyPressed(int key, int sc, int mod) {
        var graph = getGraph();
        if (showColorConfig) {
            for (var f : colorFields) if (f.isFocused()) { return f.keyPressed(key, sc, mod); }
            if (key == 256) { showColorConfig = false; return true; }
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
                    int idx = com.example.create_schematic_compute.blocks.EditPanel.glfwKeyToIndex(key);
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
                g2.removeNode(hit.id);
                expandedNodeIds.remove(hit.id);
                nodeEditStatesById.remove(hit.id);
                selectedNodes.remove(hit);
                if (selectedNode == hit) selectedNode = null;
                return true;
            }
        }
        // Ctrl+D 复制（支持多选）
        if(key==68&&net.minecraft.client.gui.screens.Screen.hasControlDown()&&!selectedNodes.isEmpty()){
            var idMap = new java.util.HashMap<Integer, Integer>();
            var newNodes = new java.util.ArrayList<GraphNode>();
            float ofs = 30;
            // 克隆所有选中节点（含子图等所有字段）
            for (var n : selectedNodes) {
                int newId = graph.nextNodeId++;
                var dup = n.shallowCopyWithNewId(newId);
                dup.x += ofs; dup.y += ofs;
                graph.adoptNode(dup);
                idMap.put(n.id, dup.id);
                newNodes.add(dup);
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
            for (var n : List.copyOf(selectedNodes)) {
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
        return false;
    }
    public boolean keyReleased(int key, int sc, int mod) {
        if (key == 258) { tabHeld = false; return true; }
        return false;
    }
    public boolean charTyped(char ch, int mod) {
        if (showColorConfig) for (var f : colorFields) if (f.isFocused()) return f.charTyped(ch, mod);
        for (var st : nodeEditStatesById.values()) for (var f : st.fields) if (f.isFocused()) return f.charTyped(ch, mod);
        return false;
    }

    /** 颜色配置面板（双列布局） */
    private void renderColorPanel(GuiGraphics g, int mx, int my) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        int itemsPerCol = 8, numRows = 8; // 16色分 8+8 两列
        int colW = 185, pw = colW * 2 + 30, ph = 36 + numRows * 18 + 24;
        int px = (host.asScreen().width - pw) / 2, py = (host.asScreen().height - ph) / 2;
        g.fill(px, py, px + pw, py + ph, 0xFF2A2822);
        g.renderOutline(px, py, pw, ph, NodeRenderer.CSB);
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
            // 色块（预览暂存颜色）
            g.fill(cx + 2, ry + 2, cx + 18, ry + 14, NodeRenderer.stagingColors[i]);
            g.renderOutline(cx + 2, ry + 2, 16, 12, 0xFF666666);
            // 名称
            g.drawString(mc.font, net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.color." + NodeRenderer.COLOR_KEYS[i]), cx + 22, ry + 2, 0xFFCCCCCC, false);
            // HEX 输入框
            var f = colorFields[i];
            f.setX(cx + colW - 90);
            f.setY(ry + 1);
            f.render(g, mx, my, 0);
        }
        int by = py + ph - 22;
        g.fill(px + 8, by, px + 72, by + 16, 0xFF3A3428);
        g.renderOutline(px + 8, by, 64, 16, NodeRenderer.CSB);
        g.drawString(mc.font, "§7" + net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.color.defaults"), px + 14, by + 3, 0xFFFFFFFF, false);
        g.fill(px + pw - 72, by, px + pw - 8, by + 16, 0xFF3A5A2A);
        g.renderOutline(px + pw - 72, by, 64, 16, 0xFF5A8A3A);
        g.drawString(mc.font, "§a" + net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.color.apply"), px + pw - 62, by + 3, 0xFFFFFFFF, false);
    }

    private void recompile(NodeGraph graph) {
        cycleWarning=null;
        // 编译时当前状态回归初始值
        for (var n : graph.nodes) {
            if ((n.type == NodeType.GATE || n.type == NodeType.T_FLIPFLOP || n.type == NodeType.LATCH) && n.params.length > 1) {
                n.params[1] = n.params[0];
            }
        }
        saveGraph();
        host.toggleRunning(false);
    }

    /** 检测 ▶/▼ 展开按钮点击 */
    private GraphNode hitExpandIndicator(double mx, double my, NodeGraph graph) {
        float indicatorSize = 12 * zoom;
        for (int i = graph.nodes.size() - 1; i >= 0; i--) {
            var n = graph.nodes.get(i);
            if (n.type.paramNames.length == 0 && n.type != NodeType.REDSTONE_IN && n.type != NodeType.REDSTONE_OUT
                && n.type != NodeType.PRIVATE_IN && n.type != NodeType.PRIVATE_OUT && n.type != NodeType.FORMULA
                && n.type != NodeType.KEYBOARD && n.type != NodeType.GAMEPAD_BUTTON
                && n.type != NodeType.IMAGE && n.type != NodeType.IMAGE_SEQUENCE && n.type != NodeType.TEXT && n.type != NodeType.DATA
                && n.type != NodeType.ENCAPSULATION && n.type != NodeType.ENCAP_INPUT && n.type != NodeType.ENCAP_OUTPUT) continue;
            float sx = c2sX(n.x), sy = c2sY(n.y);
            float ix = sx + (NW - 22) * zoom;
            float iy = sy + 2 * zoom;
            if (mx >= ix && mx <= ix + indicatorSize && my >= iy && my <= iy + indicatorSize)
                return n;
        }
        return null;
    }

    private GraphNode hitNode(double mx, double my) {
        var graph = getGraph();
        for(int i=graph.nodes.size()-1;i>=0;i--){
            var n=graph.nodes.get(i);
            float sx=c2sX(n.x), sy=c2sY(n.y), sw=NW*zoom;
            float nh = (HH+PH*(n.functionalInputs() + n.outputs()))*zoom+4;
            if (expandedNodeIds.contains(n.id))
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
            float fx=c2sX(fn.x+NW), fy=c2sY(fn.y+HH+PH*(fn.functionalInputs() + c.fromPin)+PH/2f);
            float ty;
            if (c.toPin < tn.functionalInputs())
                ty=c2sY(tn.y+HH+PH*c.toPin+PH/2f);                       // 功能引脚
            else {
                int pi=c.toPin-tn.functionalInputs();
                ty=c2sY(tn.y+HH+PH*(tn.functionalInputs()+tn.outputs())+4/zoom+pi*18+12); // 参数引脚
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

    static final int NW=140, HH=18, PH=16;
}
