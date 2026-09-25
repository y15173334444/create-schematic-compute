package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeConnection;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 节点图渲染器 — 处理网格、节点、连线的绘制逻辑
 */
public class NodeRenderer {
    // ── Color palette stored in a single volatile array for atomic read/write (Phase 1) ──
    // Index constants for the 23 themeable colors (16 graph-editor semantic colors +
    // 7 GUI chrome colors added with the settings theme extension)
    static final int _CG=0,_CGL=1,_CN=2,_CH=3,_CB=4,_CPI=5,_CPO=6,_CW=7,_CWD=8;
    static final int _CMN=9,_CMH=10,_CNT=11,_CCT=12,_CSB=13,_CPIB=14,_CPOB=15;
    static final int _PBG=16,_PHT=17,_PBR=18,_PINS=19,_ACC=20,_ERR=21,_HOV=22;
    static final int _NUM_COLORS = 23;

    // Text and dim colors are constant across all themes
    static final int CT=0xFFFFFFFF, CD=0xFF888888;

    private static volatile int[] _c = {
        0xFF1F1E1A,0xFF2C2A24,0xFF3A3832,0xFF4A3F28,0xFF5A4D3A,0xFFD4A017,0xFFB87333,0xFFC5962B,0xFFFFDD55,
        0xFF888888,0xFFFFDD77,0xFFFFAA00,0xFFFFAA00,0xFF8B7533,0xFF8B6914,0xFF8A4A22,
        0xFF2A2822,0xFF4A3F28,0xFF5A4D3A,0xFF1A1814,0xFFFFAA00,0xFFFF4444,0xFF3A3428};

    // Inline accessors — JIT constant-folds the bounds checks
    static int CG() { return _c[_CG]; } static int CGL() { return _c[_CGL]; }
    static int CN() { return _c[_CN]; } static int CH() { return _c[_CH]; }
    static int CB() { return _c[_CB]; } static int CPI() { return _c[_CPI]; }
    static int CPO() { return _c[_CPO]; } static int CW() { return _c[_CW]; }
    static int CWD() { return _c[_CWD]; } static int CMN() { return _c[_CMN]; }
    static int CMH() { return _c[_CMH]; } static int CNT() { return _c[_CNT]; }
    static int CCT() { return _c[_CCT]; } static int CPIB() { return _c[_CPIB]; }
    static int CPOB() { return _c[_CPOB]; }
    // GUI-chrome colors (used across editor and peripheral screens; public for
    // cross-package consumers in client.*)
    public static int CSB() { return _c[_CSB]; }
    public static int PBG() { return _c[_PBG]; }  // panel_bg    面板底
    public static int PHT() { return _c[_PHT]; }  // panel_header 面板标题带
    public static int PBR() { return _c[_PBR]; }  // panel_border 面板边框
    public static int PINS() { return _c[_PINS]; }// inset_bg    内凹井底
    public static int ACC() { return _c[_ACC]; }  // accent      强调色
    public static int ERR() { return _c[_ERR]; }  // error       警示/错误
    public static int HOV() { return _c[_HOV]; }  // hover       悬停高亮

    /** 主题色的半透明变体：保留 RGB、替换透明度（alpha 0..255）。跨屏同源遮罩/淡入用，
     *  使叠层也随主题变色。 / Theme color with a given alpha (RGB kept, alpha 0..255);
     *  keeps shared scrim/fade layers theme-reactive. */
    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    static final int[][] THEMES = {
        // 23色: CG()…CPOB() + PBG(),PHT(),PBR(),PINS(),ACC(),ERR(),HOV()
        {0xFF1F1E1A,0xFF2C2A24,0xFF3A3832,0xFF4A3F28,0xFF5A4D3A,0xFFD4A017,0xFFB87333,0xFFC5962B,0xFFFFDD55,0xFF888888,0xFFFFDD77,0xFFFFAA00,0xFFFFAA00,0xFF8B7533,0xFF8B6914,0xFF8A4A22,0xFF2A2822,0xFF4A3F28,0xFF5A4D3A,0xFF1A1814,0xFFFFAA00,0xFFFF4444,0xFF3A3428},
        {0xFF0A1020,0xFF152040,0xFF1A2A4A,0xFF2A3A5A,0xFF3A5A7A,0xFF44AAFF,0xFFFFAA44,0xFF66BBFF,0xFFFFFF88,0xFF88AACC,0xFFFFDD77,0xFFFFDD77,0xFF88AACC,0xFF6688AA,0xFF2266AA,0xFFAA6622,0xFF141C2A,0xFF22304A,0xFF3A5A7A,0xFF0A1220,0xFFFFCC44,0xFFFF5544,0xFF2A3A5A},
        {0xFF0A0A0A,0xFF1A1A1A,0xFF2A2A2A,0xFF3A3A3A,0xFF555555,0xFF00FF88,0xFFFF4466,0xFF888888,0xFFFFFF88,0xFFAAAAAA,0xFFCCCCCC,0xFFCCCCCC,0xFF888888,0xFF666666,0xFF444444,0xFF444444,0xFF2A2A2A,0xFF3A3A3A,0xFF555555,0xFF1A1A1A,0xFFFFDD00,0xFFFF5555,0xFF444444},
        {0xFF1E1410,0xFF2A1C14,0xFF3A2820,0xFF4A3428,0xFF5A4438,0xFFFF8844,0xFFAA6633,0xFFDD8844,0xFFFFCC66,0xFFAA8866,0xFFFFCC77,0xFFFFCC77,0xFFAA8866,0xFF8B6B53,0xFF6B4A33,0xFF6B3A23,0xFF2A1C14,0xFF4A3428,0xFF5A4438,0xFF1E1410,0xFFFFCC66,0xFFFF6644,0xFF3A2820},
    };
    static int currentTheme = 0;

    static void applyTheme(int index) {
        if (index < 0 || index >= THEMES.length) index = 0;
        currentTheme = index;
        _c = THEMES[index]; // atomic array swap — all colors change at once
    }

    static int[] currentColors() { return _c.clone(); }
    static final int[] DEFAULT_COLORS = {0xFF1F1E1A,0xFF2C2A24,0xFF3A3832,0xFF4A3F28,0xFF5A4D3A,0xFFD4A017,0xFFB87333,0xFFC5962B,0xFFFFDD55,0xFF888888,0xFFFFDD77,0xFFFFAA00,0xFFFFAA00,0xFF8B7533,0xFF8B6914,0xFF8A4A22,0xFF2A2822,0xFF4A3F28,0xFF5A4D3A,0xFF1A1814,0xFFFFAA00,0xFFFF4444,0xFF3A3428};
    static final String[] COLOR_KEYS = {"bg","grid","node","header","border","input","output","wire","drag","menu_text","menu_hover","node_title","cat_text","sys_border","input_border","output_border","panel_bg","panel_header","panel_border","inset_bg","accent","error","hover"};
    static int[] stagingColors = DEFAULT_COLORS.clone();
    static void initStaging() { stagingColors = currentColors(); }

    static void setColors(int[] c) {
        if (c.length < _NUM_COLORS) return;
        _c = c.clone(); // atomic array swap — all colors change at once
    }

    /** 从配置文件加载颜色 */
    static void loadColorConfig() {
        try {
            var path = java.nio.file.Path.of("config", "create_schematic_compute-client.properties");
            if (java.nio.file.Files.exists(path)) {
                var props = new java.util.Properties();
                try (var is = java.nio.file.Files.newInputStream(path)) { props.load(is); }
                int[] c = DEFAULT_COLORS.clone();
                for (int i = 0; i < _NUM_COLORS; i++) {
                    String v = props.getProperty("color." + COLOR_KEYS[i]);
                    if (v != null && v.length() == 8) try { c[i] = (int)(Long.parseLong(v, 16) & 0xFFFFFFFFL); } catch (Exception ignored) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.debug("Bad hex color value: {}", v); }
                }
                setColors(c);
            } else if (currentTheme > 0) {
                applyTheme(currentTheme);
            }
        } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.error("Failed to load color config", e); }
    }

    /** 保存颜色到配置文件（保留已有非颜色属性） */
    static void saveColorConfig() {
        try {
            var path = java.nio.file.Path.of("config", "create_schematic_compute-client.properties");
            java.nio.file.Files.createDirectories(path.getParent());
            var props = new java.util.Properties();
            if (java.nio.file.Files.exists(path))
                try (var is = java.nio.file.Files.newInputStream(path)) { props.load(is); }
            int[] c = currentColors();
            for (int i = 0; i < _NUM_COLORS; i++) props.setProperty("color." + COLOR_KEYS[i], String.format("%08X", c[i]));
            try (var os = java.nio.file.Files.newOutputStream(path)) { props.store(os, "Create: Schematic Compute Theme"); }
        } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.error("Failed to save color config", e); }
    }

    static { loadColorConfig(); }

    /** 保存网格吸附状态（保留已有颜色属性） */
    static void saveGridSnap(boolean on) {
        try {
            var path = java.nio.file.Path.of("config", "create_schematic_compute-client.properties");
            var props = new java.util.Properties();
            if (java.nio.file.Files.exists(path))
                try (var is = java.nio.file.Files.newInputStream(path)) { props.load(is); }
            props.setProperty("grid_snap", String.valueOf(on));
            try (var os = java.nio.file.Files.newOutputStream(path)) { props.store(os, null); }
        } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.error("Failed to save grid snap setting", e); }
    }

    /** 加载网格吸附状态 */
    static boolean loadGridSnap() {
        try {
            var path = java.nio.file.Path.of("config", "create_schematic_compute-client.properties");
            if (java.nio.file.Files.exists(path)) {
                var props = new java.util.Properties();
                try (var is = java.nio.file.Files.newInputStream(path)) { props.load(is); }
                return Boolean.parseBoolean(props.getProperty("grid_snap", "true"));
            }
        } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.error("Failed to load grid snap setting", e); }
        return true;
    }

    /** 工具栏位置持久化 */
    private static boolean toolbarBottom = false;
    static { toolbarBottom = loadToolbarBottom(); }
    public static boolean isToolbarBottom() { return toolbarBottom; }

    public static void toggleToolbarBottom() { toolbarBottom = !toolbarBottom; saveToolbarBottom(); }

    static void saveToolbarBottom() {
        try {
            var path = java.nio.file.Path.of("config", "create_schematic_compute-client.properties");
            var props = new java.util.Properties();
            if (java.nio.file.Files.exists(path))
                try (var is = java.nio.file.Files.newInputStream(path)) { props.load(is); }
            props.setProperty("toolbar_bottom", String.valueOf(toolbarBottom));
            java.nio.file.Files.createDirectories(path.getParent());
            try (var os = java.nio.file.Files.newOutputStream(path)) { props.store(os, null); }
        } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.error("Failed to save toolbar position", e); }
    }

    static boolean loadToolbarBottom() {
        try {
            var path = java.nio.file.Path.of("config", "create_schematic_compute-client.properties");
            if (java.nio.file.Files.exists(path)) {
                var props = new java.util.Properties();
                try (var is = java.nio.file.Files.newInputStream(path)) { props.load(is); }
                return Boolean.parseBoolean(props.getProperty("toolbar_bottom", "false"));
            }
        } catch (Exception e) { io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.error("Failed to load toolbar position", e); }
        return false;
    }

    // 尺寸常量
    public static final int NW=140, WIDE_NW=240, HH=18, PH=16, PR=4, GS=30;
    /** Dynamic node width: FORMULA gets 240px for long expressions, COMMENT uses its own width */
    public static int nw(GraphNode n) {
        if (n == null) return NW;
        if (n.type == NodeType.COMMENT) return Math.round(n.commentWidth);
        if (n.type == NodeType.FORMULA) return WIDE_NW;
        if (n.type == NodeType.DEBUG_SIGNAL_GEN || n.type == NodeType.DEBUG_PROBE) return WIDE_NW;
        return NW;
    }
    /** Node body height in graph-space pixels (excluding edit panel expansion). */
    public static float nh(GraphNode n) {
        if (n == null) return HH + PH * 2;
        if (n.type == NodeType.COMMENT) return n.commentHeight;
        float base = HH + PH * (n.functionalInputs() + n.outputs());
        if (n.type == NodeType.DEBUG_SIGNAL_GEN) return base + 84; // XY 图区域
        if (n.type == NodeType.DEBUG_PROBE) return base + 64;      // 数值 + 趋势图
        return base;
    }

    // 坐标转换接口
    public interface CoordMapper { float apply(float coord); }
    private final CoordMapper c2sX, c2sY;
    private final net.minecraft.client.gui.screens.Screen screen;


    /** 添加节点菜单（已拆至 NodeAddMenu，docs/gui-decomposition-plan.md 步骤 5；本类保留门面）。
     *  Add-node menu (extracted to NodeAddMenu, roadmap step 5; this class keeps the facade). */
    private final NodeAddMenu addMenu;
    /** 注释节点渲染器（已拆至 NodeCommentRenderer，同批第二刀）。 / Comment-node renderer (extracted, second cut). */
    private final NodeCommentRenderer commentRenderer;
    /** 连线渲染器（已拆至 NodeWireRenderer，同批第三刀）。 / Wire renderer (extracted, third cut). */
    private final NodeWireRenderer wireRenderer;

    public NodeRenderer(CoordMapper c2sX, CoordMapper c2sY, net.minecraft.client.gui.screens.Screen screen) {
        this.c2sX = c2sX; this.c2sY = c2sY;
        this.screen = screen;
        this.addMenu = new NodeAddMenu(screen);
        this.commentRenderer = new NodeCommentRenderer(c2sX, c2sY, screen);
        this.wireRenderer = new NodeWireRenderer(c2sX, c2sY, screen);
    }

    // ══════════════ 连线门面（实现已拆至 NodeWireRenderer，docs/gui-decomposition-plan.md 步骤 5）══════════════
    // ══════════════ Wire facade (the implementation lives in NodeWireRenderer) ══════════════

    public void renderConnections(GuiGraphics g, NodeGraph graph, float camX, float camY, float zoom) {
        wireRenderer.renderConnections(g, graph, camX, camY, zoom);
    }

    public void renderDraggingWire(GuiGraphics g, NodeGraph graph, int wireFromNode, int wireFromPin,
                                    float wireEndX, float wireEndY, float camX, float camY, float zoom) {
        wireRenderer.renderDraggingWire(g, graph, wireFromNode, wireFromPin, wireEndX, wireEndY, camX, camY, zoom);
    }

    public void renderGrid(GuiGraphics g, float camX, float camY, float zoom, int width, int height) {
        g.fill(-10,-10,width+10,height+10,CG());
        float ox=(camX*zoom)%(GS*zoom), oy=(camY*zoom)%(GS*zoom);
        for(float x=width/2f+ox; x<width; x+=GS*zoom) { int ix=Math.round(x); g.fill(ix,0,ix+1,height,CGL()); }
        for(float y=height/2f+oy; y<height; y+=GS*zoom) { int iy=Math.round(y); g.fill(0,iy,width,iy+1,CGL()); }
        for(float x=width/2f+ox; x>=0; x-=GS*zoom) { int ix=Math.round(x); g.fill(ix,0,ix+1,height,CGL()); }
        for(float y=height/2f+oy; y>=0; y-=GS*zoom) { int iy=Math.round(y); g.fill(0,iy,width,iy+1,CGL()); }
    }

    // 编辑区高度（像素，本地坐标空间）
    public java.util.Set<Integer> expandedNodeIds = java.util.Collections.emptySet();
    public java.util.Map<Integer, io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.EditState> nodeEditStatesById = java.util.Collections.emptyMap();
    public io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot evalSnapshot = io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot.EMPTY;
    /** 当前渲染的封装子图节点 ID（-1 = 主图）。用于从 EvalSnapshot 读取子图 debugTime。
     *  Current encap node ID being rendered (-1 = main graph). Used to read sub-graph debugTime from EvalSnapshot. */
    public int currentEncapId = -1;
    /** 封装占用者表（encapId → "Player1, Player2"）。主图中哪些封装节点内有玩家在编辑。
     *  Encapsulation occupant map (encapId → "Player1, Player2"). Which encap nodes have players editing inside. */
    public java.util.Map<Integer, String> encapOccupants = java.util.Collections.emptyMap();
    public boolean showBookmarkPanel = false;

    /** 某个节点是否在选中集里（**按 id** 判定）。
     *  历史上这里用对象相等（selectedNodes.contains(n)），一旦整图同步/重载替换了节点实例，
     *  旧实例就再也匹配不上 → 高亮框消失、下一个 op 又回弹（用户实测）。按 id 判定与
     *  GraphNode.id 的既有语义一致，且不依赖实例存活。
     *  Whether a node is selected, matched **by id**. This used to be object identity: a
     *  whole-graph sync or reload replaced the node instances, the old instances never matched
     *  again and the highlight vanished (then bounced back on the next op). Id matching is
     *  stable across instance replacement and matches how the rest of the code keys nodes. */
    static boolean isSelectedById(java.util.Set<GraphNode> selectedNodes, GraphNode n) {
        if (selectedNodes == null || selectedNodes.isEmpty()) return false;
        for (var sn : selectedNodes) {
            if (sn != null && sn.id == n.id) return true;
        }
        return false;
    }

    /** 主选中节点判定（**按 id**）。 / Primary-selection test, matched by id. */
    static boolean isPrimaryById(GraphNode primaryNode, GraphNode n) {
        return primaryNode != null && primaryNode.id == n.id;
    }

    /** A=1: Render complete COMMENT nodes (background, border, text, handles) behind connections.
     *  Comment nodes act as container mats — everything renders at A=1, sorted by B.
     *  实现已拆至 NodeCommentRenderer（docs/gui-decomposition-plan.md 步骤 5），本方法保留门面。
     *  The implementation lives in NodeCommentRenderer (roadmap step 5); this method is the facade. */
    public void renderCommentNodes(GuiGraphics g, List<GraphNode> nodes, Set<GraphNode> selectedNodes,
                                    GraphNode primaryNode, java.util.Set<Integer> editNodeIds,
                                    java.util.Map<Integer, io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.EditState> editStates,
                                    float camX, float camY, float zoom, int mx, int my,
                                    Map<Integer, Boolean> flipflopStates,
                                    Map<Integer, String> lockedNodes) {
        commentRenderer.renderCommentNodes(g, nodes, selectedNodes, primaryNode, editNodeIds, editStates,
            camX, camY, zoom, mx, my, flipflopStates, lockedNodes);
    }

    public void renderNodes(GuiGraphics g, List<GraphNode> nodes, Set<GraphNode> selectedNodes,
                             GraphNode primaryNode, java.util.Set<Integer> editNodeIds,
                             java.util.Map<Integer, io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.EditState> editStates,
                             float camX, float camY, float zoom, int mx, int my,
                             Map<Integer, Boolean> flipflopStates,
                             Map<Integer, String> lockedNodes) {
        expandedNodeIds = editNodeIds != null ? editNodeIds : java.util.Collections.emptySet();
        nodeEditStatesById = editStates != null ? editStates : java.util.Collections.emptyMap();
        int w = screen.width, h = screen.height;
        float margin = 50;
        for(var n : nodes) {
            if (n.type == NodeType.COMMENT) continue; // rendered at A=1 by renderCommentNodes
            float sx = c2sX.apply(n.x), sy = c2sY.apply(n.y);
            float sw = nw(n)*zoom;
            float bodyH = NodeRenderer.nh(n)*zoom+4;
            float editH = 0;
            boolean expanded = expandedNodeIds.contains(n.id) && n.type != NodeType.COMMENT;
            if (expanded) {
                // 与 drawNode 同源的保守高度（含 EditState 动态行），体/编辑区分别判交
                // Conservative height shared with drawNode; body and edit panel each tested.
                editH = EditPanel.expandedEditHeight(n, nodeEditStatesById.get(n.id)) * zoom;
            }
            if (!EditPanel.isOnScreen(sx, sy, sw, bodyH, editH, w, h, margin))
                continue;
            drawNode(g, n, isSelectedById(selectedNodes, n), isPrimaryById(primaryNode, n), expanded, camX, camY, zoom, mx, my, flipflopStates, lockedNodes);
        }
    }


    private void drawNode(GuiGraphics g, GraphNode n, boolean selected, boolean isPrimary, boolean editing,
                           float camX, float camY, float zoom, int mx, int my,
                           Map<Integer, Boolean> flipflopStates,
                           Map<Integer, String> lockedNodes) {
        // COMMENT nodes are rendered entirely at A=1 via renderCommentNodes
        if (n.type == NodeType.COMMENT) return;
        String lockedBy = lockedNodes != null ? lockedNodes.get(n.id) : null;
        float sx = c2sX.apply(n.x), sy = c2sY.apply(n.y);
        int nodeW = nw(n);
        float sw = nodeW*zoom;
        float contentH = nh(n)*zoom+4;
        // 编辑模式：各节点独立计算高度
        float extraH = editing ? io.github.y15173334444.create_schematic_compute.blocks.EditPanel.calcRenderHeight(n, zoom,
            editing ? nodeEditStatesById.get(n.id) : null) * zoom : 0;
        float nh = contentH + extraH;
        // BUS_OUT 通道冲突警告 — 在节点上方渲染
        if (n.type == NodeType.BUS_OUT && n.busConflict) {
            String warn = net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.bus_conflict");
            int warnW = Minecraft.getInstance().font.width(warn) + 20;
            int warnH = 14;
            int wx = (int)(sx + (sw - warnW * zoom) / 2);
            int wy = (int)(sy - warnH * zoom - 2);
            g.fill(wx, wy, (int)(wx + warnW * zoom), (int)(wy + warnH * zoom), 0xCC660000);
            g.renderOutline(wx, wy, (int)(warnW * zoom), (int)(warnH * zoom), ERR());
            var warnPose = g.pose();
            warnPose.pushPose();
            warnPose.translate(wx + 4 * zoom, wy + 1 * zoom, 0);
            warnPose.scale(zoom, zoom, 1);
            drawStr(g, "§c⚠ " + warn, 0, 0, ERR());
            warnPose.popPose();
        }
        // ENCAPSULATION 节点数量超出警告 — 在节点上方渲染
        if (n.type == NodeType.ENCAPSULATION && n.subGraph != null && n.subGraph.nodes.size() > GraphEditor.MAX_NODES) {
            String warn = I18n.get("gui.create_schematic_compute.encap_node_limit");
            int warnW = Minecraft.getInstance().font.width(warn) + 20;
            int warnH = 14;
            // 避开已有的 BUS_OUT 冲突警告（如果同时存在则移到更上方）
            int yOff = (n.type == NodeType.BUS_OUT && n.busConflict) ? -(warnH + 6) : 0;
            int wx = (int)(sx + (sw - warnW * zoom) / 2);
            int wy = (int)(sy + yOff * zoom - warnH * zoom - 2);
            g.fill(wx, wy, (int)(wx + warnW * zoom), (int)(wy + warnH * zoom), 0xCC330000);
            g.renderOutline(wx, wy, (int)(warnW * zoom), (int)(warnH * zoom), ERR());
            var warnPose = g.pose();
            warnPose.pushPose();
            warnPose.translate(wx + 4 * zoom, wy + 1 * zoom, 0);
            warnPose.scale(zoom, zoom, 1);
            drawStr(g, "§c" + warn, 0, 0, ERR());
            warnPose.popPose();
        }
        // Per-node buffer isolation: flush before drawing to ensure this node's
        // fills and text are not interleaved with previous nodes' text batches.
        g.flush();
        // C=0: 节点体背景（暖钢色）
        g.fill((int)sx,(int)sy,(int)(sx+sw),(int)(sy+nh),CN());
        // C=1: 节点头部
        g.fill((int)sx+2,(int)sy+2,(int)(sx+sw-2),(int)(sy+HH*zoom),CH());
        var pose = g.pose();
        pose.pushPose();
        pose.translate(sx,sy,0);
        pose.scale(zoom,zoom,1);
        // C=1: 标题文字
        drawStr(g, I18n.get(n.type.getTitle()), 4, 4, CNT());
        // 刀5:FORMULA spread 进度条(无数值,只有进度)/ knife 5: spread progress bar (no value shown)
        if (n.type == NodeType.FORMULA && evalSnapshot != null) {
            float prog = evalSnapshot.getFormulaSpread(n.id);
            if (prog != 0f) {
                int barX = 4, barY = HH + 3, barW = nodeW - 8, barH = 3;
                g.fill(barX, barY, barX + barW, barY + barH, 0xFF333340);
                if (prog > 0f) {
                    int w = (int)(barW * Math.min(1f, prog));
                    g.fill(barX, barY, barX + Math.max(1, w), barY + barH, 0xFF6FC3FF);
                } else {
                    // 不定(while 循环):呼吸式填充 / indeterminate (while loop): breathing fill
                    double phase = (System.currentTimeMillis() % 2000) / 2000.0 * Math.PI * 2;
                    int w = (int)(barW * (0.35 + 0.3 * Math.sin(phase)));
                    g.fill(barX, barY, barX + Math.max(1, w), barY + barH, 0xFF6FC3FF);
                }
            }
        }
        // FORMULA error badge: red ⚠ in the title bar.
        // Prefer cached issues set by the edit-panel responder; validate only on
        // first frame after NBT reload (when formulaIssues is null).
        // FORMULA 错误徽章：标题栏红色 ⚠。优先使用编辑面板 responder 设置的
        // 缓存问题列表；仅在 NBT 重载后首帧（formulaIssues 为 null 时）重新校验。
        java.util.List<io.github.y15173334444.create_schematic_compute.graph.FormulaParser.FormulaIssue> formulaIssuesLive = null;
        if (n.type == NodeType.FORMULA && !n.formula.isEmpty()) {
            formulaIssuesLive = n.formulaIssues;
            if (formulaIssuesLive == null) {
                formulaIssuesLive = io.github.y15173334444.create_schematic_compute.graph.FormulaParser.validate(n.formula);
                n.formulaIssues = formulaIssuesLive; // cache until next NBT reload or edit
            }
        }
        boolean formulaHasIssues = formulaIssuesLive != null && !formulaIssuesLive.isEmpty();
        if (formulaHasIssues) {
            drawStr(g, "§c⚠", nodeW - 30, 4, ERR());
        }
        // C=2: 展开指示器
        if (n.type == NodeType.FORMULA || n.type.paramNames.length > 0
            || n.type == NodeType.REDSTONE_IN || n.type == NodeType.REDSTONE_OUT
            || n.type == NodeType.PRIVATE_IN || n.type == NodeType.PRIVATE_OUT
            || n.type == NodeType.IMAGE || n.type == NodeType.IMAGE_SEQUENCE
            || n.type == NodeType.TEXT || n.type == NodeType.DATA
            || n.type == NodeType.ENCAPSULATION || n.type == NodeType.ENCAP_INPUT || n.type == NodeType.ENCAP_OUTPUT
            || n.type == NodeType.COMMENT
            || n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT) {
            drawStr(g, editing ? (n.type == NodeType.ENCAPSULATION ? "§b▶▶" : "§6▼") : (n.type == NodeType.ENCAPSULATION ? "§b▶" : "§7▶"), nodeW - 18, 4, CT);
        }
        // C=2: 封装节点体部摘要
        if (n.type == NodeType.ENCAPSULATION && !editing) {
            String summary = java.text.MessageFormat.format(I18n.get("gui.create_schematic_compute.encap_summary"), n.functionalInputs(), n.outputs());
            drawStr(g, "§8" + summary, 4, HH + PH * Math.max(n.functionalInputs(), n.outputs()) + 4, CD);
        }
        // C=3: 编辑区
        if (editing) {
            int editLocalY = (int)(nh(n) + 4/zoom); // nh(n) 含图表区域高度，编辑区在图表之后
            var editSt = nodeEditStatesById.get(n.id);
            int editLocalH = io.github.y15173334444.create_schematic_compute.blocks.EditPanel.calcRenderHeight(n, zoom, editSt);
            g.fill(2, editLocalY - 2, nodeW - 2, editLocalY, CB());
            g.fill(2, editLocalY, nodeW - 2, editLocalY + editLocalH, 0xFF2A2822);
            if (editSt != null) {
                io.github.y15173334444.create_schematic_compute.blocks.EditPanel.renderAt(g, 0, editLocalY, nodeW, n, editSt, zoom, mx, my, flipflopStates);
            }
        }
        // C=3.5: 调试节点图表区域（graph space，坐标相对于节点左上角）
        java.util.List<float[]> debugCtrlPoints = null;
        if (n.type == NodeType.DEBUG_SIGNAL_GEN) {
            debugCtrlPoints = renderDebugSignalGenChart(g, n, nodeW);
        } else if (n.type == NodeType.DEBUG_PROBE) {
            renderDebugProbeChart(g, n, nodeW);
        }
        pose.popPose();
        // C=4: 边框
        int borderColor = isPrimary ? ACC() : selected ? ACC() : CB();
        g.renderOutline((int)sx,(int)sy,(int)sw,(int)nh, borderColor);
        g.renderOutline((int)sx+1,(int)sy+1,(int)sw-2,(int)nh-2, 0xFF2A2822);
        // C=4.3: 手动曲线控制点（边框上方，屏幕空间）/ manual curve control points (above border, screen space)
        if (debugCtrlPoints != null) {
            for (float[] cp : debugCtrlPoints) {
                float csx = sx + cp[0] * zoom;
                float csy = sy + cp[1] * zoom;
                g.fill((int)(csx - 2 * zoom), (int)(csy - 2 * zoom), (int)(csx + 3 * zoom), (int)(csy + 3 * zoom), 0xFFFBBF24);
            }
        }
        // C=4.5: 封装节点占用高亮（有玩家在内部编辑时显示金色外框 + 玩家名）
        if (n.type == NodeType.ENCAPSULATION && !encapOccupants.isEmpty()) {
            String occupants = encapOccupants.get(n.id);
            if (occupants != null) {
                // 金色外框 / gold outer border
                g.renderOutline((int)sx-2,(int)sy-2,(int)sw+4,(int)nh+4, 0xFFFFD700);
                g.renderOutline((int)sx-1,(int)sy-1,(int)sw+2,(int)nh+2, 0xFFB8960F);
                // 玩家名文本 / player names text
                var occPose = g.pose();
                occPose.pushPose();
                occPose.translate(sx, sy, 0);
                occPose.scale(zoom, zoom, 1);
                int occY = HH + (int)(PH * Math.max(n.functionalInputs(), n.outputs())) + 4 + 12;
                drawStr(g, "§6👤 " + occupants, 4, occY, 0xFFFFD700);
                occPose.popPose();
            }
        }
        // C=5: 引脚（在边框之上，始终可见）
        var pinPose = g.pose();
        pinPose.pushPose();
        pinPose.translate(sx,sy,0);
        pinPose.scale(zoom,zoom,1);
        int funcInputs = n.functionalInputs();
        for(int i=0; i<funcInputs; i++) {
            float py = HH+PH*i+PH/2f;
            int r = PR;
            g.fill(-r - 1, (int)(py - r - 1), r + 1, (int)(py + r + 1), CPIB());
            g.fill(-r, (int)(py - r), r, (int)(py + r), CPI());
            String inlbl = n.inputLabel(i);
            drawStr(g, (n.type == NodeType.BUS_OUT || n.type == NodeType.FORMULA || n.type == NodeType.ENCAPSULATION) ? inlbl : I18n.get(inlbl), 10, py-3, CD);
        }
        for(int i=0; i<n.outputs() && n.type != NodeType.SPEED_CTRL && n.type != NodeType.DEBUG_PROBE; i++) {
            float py = HH+PH*(funcInputs + i)+PH/2f;
            int r = PR;
            g.fill(nodeW - r - 1, (int)(py - r - 1), nodeW + r + 1, (int)(py + r + 1), CPOB());
            g.fill(nodeW - r, (int)(py - r), nodeW + r, (int)(py + r), CPO());
            String rawOutLbl = n.outputLabel(i);
            String outlbl = (n.type == NodeType.BUS_IN || n.type == NodeType.ENCAPSULATION) ? rawOutLbl : I18n.get(rawOutLbl);
            int olw = Minecraft.getInstance().font.width(outlbl);
            drawStr(g, outlbl, nodeW - olw - 6, py-3, CD);
        }
        pinPose.popPose();
        // Soft lock: colored border + name label (no overlay)
        if (lockedBy != null) {
            int h = lockedBy.hashCode();
            int lockColor = 0xFF000000 | (((h >> 16) & 0xFF) << 16) | (((h >> 8) & 0xFF) << 8) | (h & 0xFF) | 0xFF000000;
            // Thick outer border in player color
            g.renderOutline((int)sx - 3, (int)sy - 3, (int)sw + 6, (int)(nh + 6), lockColor);
            g.renderOutline((int)sx - 2, (int)sy - 2, (int)sw + 4, (int)(nh + 4), lockColor);
            // Name label above node
            int lw = Minecraft.getInstance().font.width(lockedBy);
            int lx = (int)(sx + (sw - lw * zoom) / 2);
            int ly = (int)(sy - 22 * zoom);
            g.fill(lx - 4, ly - 2, (int)(lx + lw * zoom + 4), (int)(ly + 12 * zoom), 0xCC222222);
            var lockPose = g.pose();
            lockPose.pushPose();
            lockPose.translate(lx, ly, 0);
            lockPose.scale(zoom, zoom, 1);
            drawStr(g, "§e" + lockedBy, 0, 0, 0xFFFFAA44);
            lockPose.popPose();
        }
        // C=5.5: Autocomplete popup — rendered in screen space with zoom-aware scaling.
        // The MLE stores anchorX/Y (MLE-relative caret bottom) during renderWidget.
        // C=5.5: 自动补全候选框 — 屏幕空间渲染，支持缩放感知。
        // MLE 在 renderWidget 中存储 anchorX/Y（MLE 相对光标底部位置）。
        if (editing) {
            var editSt = nodeEditStatesById.get(n.id);
            if (editSt != null) {
                for (var f : editSt.fields) {
                    if (f instanceof io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox mle) {
                        var popup = mle.getSuggestPopup();
                        if (popup.isVisible()) {
                            int editLocalY = (int)(nh(n) + 4 / zoom);
                            int screenX = (int)(sx + (28 + popup.anchorX) * zoom);
                            int screenY = (int)(sy + (editLocalY + 4 + popup.anchorY + 18) * zoom);
                            int maxW = Math.min(200, nodeW - 36); // unscaled, pose will scale it

                            // Estimate popup height (unscaled) to flip above if it doesn't fit below
                            int estH = Math.min(popup.getCandidates().size(), 8) * 14 + 6;
                            int screenEstH = (int)(estH * zoom);
                            int panelBottom = (int)(sy + (editLocalY + io.github.y15173334444.create_schematic_compute.blocks.EditPanel.calcRenderHeight(n, zoom, editSt)) * zoom);
                            if (screenY + screenEstH > panelBottom) {
                                // Flip above caret
                                screenY = (int)(sy + (editLocalY + 4 + popup.anchorY) * zoom) - (int)(Minecraft.getInstance().font.lineHeight * zoom) - screenEstH;
                                if (screenY < (int)sy) screenY = (int)sy;
                            }
                            // Horizontal clamp
                            int screenW = (int)((nodeW - 36) * zoom);
                            if (screenX + screenW > (int)(sx + nodeW * zoom)) screenX = (int)(sx + nodeW * zoom) - screenW;
                            if (screenX < (int)sx) screenX = (int)sx;

                            var popPose = g.pose();
                            popPose.pushPose();
                            popPose.translate(screenX, screenY, 0);
                            popPose.scale(zoom, zoom, 1);
                            popup.render(g, Minecraft.getInstance().font, 0, 0, maxW);
                            popPose.popPose();

                            // Convert rendered bounds (pose-local) back to screen space for click detection
                            popup.renderedX = screenX;
                            popup.renderedY = screenY;
                            popup.renderedW = (int)(popup.renderedW * zoom);
                            popup.renderedH = (int)(popup.renderedH * zoom);
                        }
                        break;
                    }
                }
            }
        }
        // C=5.5: FORMULA error tooltip (after pins, above all — screen space)
        if (formulaHasIssues) {
            int badgeX = (int)(sx + (nodeW - 30) * zoom);
            int badgeY = (int)(sy + 4 * zoom);
            int badgeW = (int)(14 * zoom);
            int badgeH = (int)(14 * zoom);
            if (mx >= badgeX && mx <= badgeX + badgeW && my >= badgeY && my <= badgeY + badgeH) {
                var lines = new java.util.ArrayList<String>();
                for (var iss : formulaIssuesLive) {
                    String sev = iss.severity() == io.github.y15173334444.create_schematic_compute.graph.FormulaParser.Severity.ERROR ? "§c" : "§e";
                    lines.add(sev + "L" + (iss.line() + 1) + ":" + (iss.col() + 1) + " " + iss.message());
                }
                int maxW = 0;
                for (String l : lines) maxW = Math.max(maxW, Minecraft.getInstance().font.width(l));
                int tw = Math.min(maxW + 10, 260);
                int rows = lines.size();
                int rowH = 11;
                int th = rows * rowH + 4;
                int tx = mx, ty = my + 8;
                if (tx + tw > Minecraft.getInstance().getWindow().getGuiScaledWidth()) tx = Minecraft.getInstance().getWindow().getGuiScaledWidth() - tw;
                if (tx < 0) tx = 0;
                // 存入延迟覆盖层:节点循环内不直接绘制——本节点先画、z 序更高的节点后画会盖住报告框。
                // Store into the deferred overlay: don't draw inside the node loop — nodes drawn later
                // (higher z) would cover the report box. GraphEditor flushes it above all nodes.
                pendingOverlayLines = lines;
                pendingOverlayX = tx; pendingOverlayY = ty; pendingOverlayW = tw; pendingOverlayH = th;
                pendingOverlayBg = 0xDD1A0000; pendingOverlayBorder = ERR(); pendingOverlayText = ERR();
            }
        }
        // Flush per-node to prevent text (font buffer) from later nodes'
        // fills covering earlier nodes' text due to Minecraft's two-pass
        // buffer flush (all fills before all text).
        g.flush();
    }

    // ── A=5 工具提示层:延迟渲染覆盖层(报告框等)/ A=5 tooltip tier: deferred overlay (report box etc.) ──

    /** 待绘制覆盖层(公式报错报告框)。悬停状态在节点绘制循环(A=3)内计算并收集于此;
     *  GraphEditor 在 A=5 工具提示层调用 {@link #flushPendingOverlay} 统一绘制,
     *  与 A/B/C 分层一致——任何节点体(A=3)或覆盖层(A=4)都无法遮挡。
     *  Pending overlay (formula error report box). Hover state is computed during the node pass (A=3)
     *  and collected here; GraphEditor calls {@link #flushPendingOverlay} at the A=5 tooltip tier —
     *  consistent with the A/B/C layering, no node body (A=3) or overlay (A=4) can cover it. */
    private java.util.List<String> pendingOverlayLines = null;
    private int pendingOverlayX, pendingOverlayY, pendingOverlayW, pendingOverlayH;
    private int pendingOverlayBg, pendingOverlayBorder, pendingOverlayText;

    /** A=5 层绘制延迟覆盖层并清空。 / Draw the deferred overlay at the A=5 tier and clear it. */
    public void flushPendingOverlay(GuiGraphics g) {
        if (pendingOverlayLines == null) return;
        g.fill(pendingOverlayX, pendingOverlayY, pendingOverlayX + pendingOverlayW, pendingOverlayY + pendingOverlayH, pendingOverlayBg);
        g.renderOutline(pendingOverlayX, pendingOverlayY, pendingOverlayW, pendingOverlayH, pendingOverlayBorder);
        for (int i = 0; i < pendingOverlayLines.size(); i++) {
            g.drawString(Minecraft.getInstance().font, pendingOverlayLines.get(i),
                pendingOverlayX + 4, pendingOverlayY + 2 + i * 11, pendingOverlayText, false);
        }
        pendingOverlayLines = null;
    }

    // ── 调试节点图表渲染（graph space，坐标相对于节点左上角）──
    // Debug node chart rendering (graph space, coords relative to node top-left)

    /** DEBUG_SIGNAL_GEN：XY 坐标图 + 波形曲线。返回控制点本地坐标列表（供边框上方渲染）。
     *  XY chart + waveform curve. Returns control point local coords for above-border rendering. */
    private java.util.List<float[]> renderDebugSignalGenChart(GuiGraphics g, GraphNode n, int nodeW) {
        float bodyH = HH + PH * (n.functionalInputs() + n.outputs());
        int chartX = 2;
        int chartY = (int) bodyH;
        int chartW = nodeW - 4;
        int chartH = 80;
        int setMode = n.params.length > 0 ? (int) n.params[0] : 0;
        int samples = 60;

        // ── 计算 Y 范围（自动缩放）/ compute Y range (auto-scale) ──
        float[] visRange = io.github.y15173334444.create_schematic_compute.graph.DebugSignals.computeVisibleRange(
            setMode, n.debugCtrlX, n.debugCtrlY, n.formula, n.debugFormulaRpn);
        float minV = visRange[0], maxV = visRange[1], range = visRange[2];
        float scale = chartH / range;

        // ── 渲染 / rendering ──
        // 背景
        g.fill(chartX, chartY, chartX + chartW, chartY + chartH, 0xFF1A1A2E);
        // 网格线（每 1/4 一条）
        for (int i = 1; i < 4; i++) {
            int gx = chartX + chartW * i / 4;
            g.fill(gx, chartY, gx + 1, chartY + chartH, 0xFF2A2A4E);
            int gy = chartY + chartH * i / 4;
            g.fill(chartX, gy, chartX + chartW, gy + 1, 0xFF2A2A4E);
        }
        // 0 值线（如果在可见范围内）/ zero line (if within visible range)
        if (minV <= 0 && maxV >= 0) {
            int zeroY = chartY + chartH - (int) ((0 - minV) * scale);
            g.fill(chartX, zeroY, chartX + chartW, zeroY + 1, 0xFF3A3A6E);
        }

        // 曲线 / curve
        int prevPX = -1, prevPY = -1;
        float prevV = 0;
        boolean prevValid = false;
        for (int i = 0; i <= samples; i++) {
            float x = (float) i / samples;
            float v = io.github.y15173334444.create_schematic_compute.graph.DebugSignals.computeCurve(
                setMode, x, n.debugCtrlX, n.debugCtrlY, n.formula, n.debugFormulaRpn);
            boolean discontinuity = prevValid && Math.abs(v - prevV) > range * 1.5f;
            int px = chartX + (int) (x * chartW);
            int py = chartY + chartH - (int) ((v - minV) * scale);
            py = Math.max(chartY, Math.min(chartY + chartH - 1, py));
            if (prevPX >= 0 && !discontinuity) drawLine(g, prevPX, prevPY, px, py, 0xFF4ADE80);
            prevPX = px; prevPY = py;
            prevV = v;
            prevValid = true;
        }

        // 控制点位置收集（仅手动曲线模式）— 在边框上方渲染
        // Collect control point positions (manual curve only) — rendered above border
        java.util.List<float[]> ctrlPoints = null;
        boolean showCtrl = (setMode == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.SET_MANUAL);
        if (showCtrl && n.debugCtrlX != null) {
            ctrlPoints = new java.util.ArrayList<>();
            for (int i = 0; i < n.debugCtrlX.length; i++) {
                float cpx = chartX + n.debugCtrlX[i] * chartW;
                float cpy = chartY + chartH - (n.debugCtrlY[i] - minV) * scale;
                ctrlPoints.add(new float[]{cpx, cpy});
            }
        }

        // x 标记线
        int outMode = n.params.length > 1 ? (int) n.params[1] : 0;
        float xPos;
        if (outMode == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.OUT_FREQ) {
            xPos = evalSnapshot != null
                ? (currentEncapId >= 0 ? evalSnapshot.getSubDebugTime(currentEncapId, n.id) : evalSnapshot.getDebugTime(n.id))
                : 0f;
        } else {
            xPos = n.params.length > 4 ? n.params[4] : 0.5f;
        }
        xPos = Math.max(0f, Math.min(1f, xPos));
        int mxLine = chartX + (int) (xPos * chartW);
        int xMarkerColor = outMode == io.github.y15173334444.create_schematic_compute.graph.DebugSignals.OUT_FREQ
            ? 0xFF00BFFF : 0xFF44DDFF;
        g.fill(mxLine, chartY, mxLine + 1, chartY + chartH, xMarkerColor);

        // 模式标签
        drawStr(g, io.github.y15173334444.create_schematic_compute.graph.DebugSignals.setModeName(setMode),
            chartX + 4, chartY + 2, 0xFFCCCCCC);
        // Y 轴范围标注（右上角）/ Y-axis range label (top-right)
        String rangeStr = String.format("%.1f … %.1f", minV, maxV);
        int rangeW = Minecraft.getInstance().font.width(rangeStr);
        drawStr(g, rangeStr, chartX + chartW - rangeW - 3, chartY + 2, 0xFF888899);
        return ctrlPoints;
    }

    /** DEBUG_PROBE：当前数值 + 迷你趋势折线图。 */
    private void renderDebugProbeChart(GuiGraphics g, GraphNode n, int nodeW) {
        float bodyH = HH + PH * (n.functionalInputs() + n.outputs());
        int valY = (int) bodyH;
        int chartX = 2;
        int chartY = valY + 20;
        int chartW = nodeW - 4;
        int chartH = 44;

        // 读取最新采样值
        int lastIdx = (n.probeHead - 1 + n.probeHistory.length) % n.probeHistory.length;
        float curVal = n.probeCount > 0 ? n.probeHistory[lastIdx] : 0f;
        boolean hasData = n.probeCount > 0;

        // 数值显示
        String valStr = hasData ? String.format("%.3f", curVal) : "---";
        int valCol = !hasData ? 0xFF888888
            : (Float.isNaN(curVal) || Float.isInfinite(curVal) ? 0xFF888888
            : (Math.abs(curVal) > 10f ? 0xFFFF4444 : 0xFF4ADE80));
        drawStr(g, valStr, chartX + 4, valY + 2, valCol);

        // 趋势图背景
        g.fill(chartX, chartY, chartX + chartW, chartY + chartH, 0xFF1A1A2E);
        int midY = chartY + chartH / 2;
        g.fill(chartX, midY, chartX + chartW, midY + 1, 0xFF2A2A4E);

        if (!hasData || n.probeCount < 2) {
            drawStr(g, "...", chartX + chartW / 2 - 8, chartY + chartH / 2 - 3, 0xFF666666);
            return;
        }

        // 读取参数
        int windowSize = n.params.length > 0 ? (int) n.params[0] : 50;
        windowSize = Math.max(2, Math.min(n.probeHistory.length, windowSize));
        boolean autoScale = n.params.length > 1 ? n.params[1] != 0 : true;
        float fixedRange = 10f;

        int count = Math.min(n.probeCount, windowSize);
        int start = (n.probeHead - count + n.probeHistory.length) % n.probeHistory.length;

        // 计算窗口内数据范围（百分位数稳健范围，与信号发生器一致）
        // 不再使用固定 ±5 截断，避免大值域数据（如 x*360）的范围被错误压垮。
        // Compute window data range (percentile-based robust range, same as signal generator).
        // Fixed ±5 clipping is removed so large-scale data (e.g. x*360) displays correctly.
        java.util.List<Float> pvals = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int idx = (start + i) % n.probeHistory.length;
            float v = n.probeHistory[idx];
            if (Float.isFinite(v) && Math.abs(v) < 1e6f) pvals.add(v);
        }
        float minV, maxV;
        if (pvals.isEmpty()) { minV = -1f; maxV = 1f; }
        else {
            java.util.Collections.sort(pvals);
            int lo = (int)(pvals.size() * 0.01f);
            int hi = (int)(pvals.size() * 0.99f);
            if (lo >= hi) { lo = 0; hi = pvals.size() - 1; }
            minV = pvals.get(lo);
            maxV = pvals.get(hi);
        }
        if (minV > maxV) { minV = -1f; maxV = 1f; }
        float range = autoScale ? Math.max(maxV - minV, 0.001f) : (2 * fixedRange);
        float base = autoScale ? minV : -fixedRange;
        if (autoScale && range > 0.001f) {
            float pad = range * 0.1f;
            base -= pad;
            range += pad * 2;
        }

        // 绘制折线
        int prevPX = -1, prevPY = -1;
        int lineCol = 0xFF4ADE80;
        for (int i = 0; i < count; i++) {
            int idx = (start + i) % n.probeHistory.length;
            float v = n.probeHistory[idx];
            int px = chartX + (int) ((float) i / (count - 1) * chartW);
            int py = chartY + chartH - (int) ((v - base) / range * chartH);
            py = Math.max(chartY, Math.min(chartY + chartH - 1, py));
            if (prevPX >= 0) drawLine(g, prevPX, prevPY, px, py, lineCol);
            prevPX = px; prevPY = py;
        }

        // 冻结指示
        if (n.probeFrozen) {
            drawStr(g, "FROZEN", chartX + chartW - 36, valY + 2, 0xFFFFAA00);
        }
    }

    /** Bresenham 逐像素画线（GuiGraphics 无直接画线 API）。 */
    private static void drawLine(GuiGraphics g, int x0, int y0, int x1, int y1, int col) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        int x = x0, y = y0;
        int guard = 0;
        while (guard++ < 2000) {
            g.fill(x, y, x + 1, y + 1, col);
            if (x == x1 && y == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x += sx; }
            if (e2 < dx) { err += dx; y += sy; }
        }
    }


    // ══════════════ 添加节点菜单门面（实现已拆至 NodeAddMenu，docs/gui-decomposition-plan.md 步骤 5）══════════════
    // ══════════════ Add-node menu facade (the implementation lives in NodeAddMenu) ══════════════

    public NodeType renderAddNodeMenu(GuiGraphics g, float menuX, float menuY, int mx, int my) {
        return addMenu.renderAddNodeMenu(g, menuX, menuY, mx, my);
    }
    public NodeType renderAddNodeMenu(GuiGraphics g, float menuX, float menuY, int mx, int my, java.util.function.Predicate<NodeType> filter) {
        return addMenu.renderAddNodeMenu(g, menuX, menuY, mx, my, filter);
    }
    /** Handle category expand/collapse click + search box focus. Returns true if consumed. */
    public boolean handleCategoryClick(int mx, int my) { return addMenu.handleCategoryClick(mx, my); }
    /** 切换添加节点菜单的双列布局（搜索列表同步跟随）。 / Toggle the add-node menu's two-column layout. */
    public void toggleMenuColumns() { addMenu.toggleMenuColumns(); }
    /** @return 双列布局是否开启 / whether two-column layout is on */
    public boolean isMenuTwoColumns() { return addMenu.isMenuTwoColumns(); }
    public void scrollMenu(float delta) { addMenu.scrollMenu(delta); }
    /** 菜单是否有滚动条。 / Whether the menu has a scrollbar. */
    public boolean menuHasScrollbar() { return addMenu.menuHasScrollbar(); }
    /** 滚动条轨道区（屏幕坐标）。 / Scrollbar track area (screen coords). */
    public int[] menuScrollbarTrack() { return addMenu.menuScrollbarTrack(); }
    /** 按当前 scrollOff 计算 thumb Y 与高度。 */
    public int[] menuScrollbarThumb() { return addMenu.menuScrollbarThumb(); }
    public int menuScrollOff() { return addMenu.menuScrollOff(); }
    public int menuMaxScrollOff() { return addMenu.menuMaxScrollOff(); }
    public void setMenuScrollOff(int off) { addMenu.setMenuScrollOff(off); }
    public void appendMenuSearch(char c) { addMenu.appendMenuSearch(c); }
    public void menuSearchBackspace() { addMenu.menuSearchBackspace(); }
    public void resetMenuSearch() { addMenu.resetMenuSearch(); }
    public boolean isMenuSearchFocused() { return addMenu.isMenuSearchFocused(); }
    public void setMenuSearchFocused(boolean f) { addMenu.setMenuSearchFocused(f); }
    public void setMenuSearchText(String t) { addMenu.setMenuSearchText(t); }

    public void renderButtons(GuiGraphics g, boolean compiled, boolean running, String cycleWarning,
                               long saveFeedbackUntil, boolean gridSnap, int themeIdx, int width, int height) {
        long now = System.currentTimeMillis();
        boolean fb = now < saveFeedbackUntil;
        int btnH = 18;
        // 工具栏位置：顶部(默认)或底部。顶置时必须让开编辑器顶栏（GraphEditor.TOP_BAR_H），
        // 否则顶栏（最后渲染）会把工具栏整个盖住，且与命中区（TOP_BAR_H+2 起）错位。
        // Toolbar position: top (default) or bottom. The top position must clear the
        // editor's top bar (GraphEditor.TOP_BAR_H) — the top bar renders last and would
        // cover a toolbar drawn at y=4, and the hit area starts at TOP_BAR_H+2.
        int btnY = toolbarBottom ? height - btnH - 4 : GraphEditor.TOP_BAR_H + 2;
        // 关闭按钮（最左）
        int cX = 4, cW = 18;
        g.fill(cX, btnY, cX+cW, btnY+btnH, 0xFF4A3028);
        g.renderOutline(cX, btnY, cW, btnH, 0xFF8B5333);
        g.renderOutline(cX+1, btnY+1, cW-2, btnH-2, 0xFF2A2822);
        drawStr(g, "§cX", cX+4, btnY+4, CT);
        // Compile 按钮
        int cX2 = 26, cW2 = 52;
        g.fill(cX2, btnY, cX2+cW2, btnY+btnH, fb ? 0xFF3A5A2A : 0xFF3A3832);
        g.renderOutline(cX2, btnY, cW2, btnH, CSB());
        g.renderOutline(cX2+1, btnY+1, cW2-2, btnH-2, 0xFF2A2822);
        drawStr(g, fb ? "§a" + I18n.get("gui.create_schematic_compute.compiled") : "§e" + I18n.get("gui.create_schematic_compute.compile"), cX2+4, btnY+4, CT);
        // Run/Stop 按钮
        int cX3 = 82, cW3 = 48;
        g.fill(cX3, btnY, cX3+cW3, btnY+btnH, running ? 0xFF3A5A2A : 0xFF3A3832);
        g.renderOutline(cX3, btnY, cW3, btnH, CSB());
        g.renderOutline(cX3+1, btnY+1, cW3-2, btnH-2, 0xFF2A2822);
        drawStr(g, running ? "§a" + I18n.get("gui.create_schematic_compute.stop") : "§e" + I18n.get("gui.create_schematic_compute.run"), cX3+4, btnY+4, CT);
        // 网格吸附按钮
        int cX4 = 134, cW4 = 58;
        g.fill(cX4, btnY, cX4+cW4, btnY+btnH, gridSnap ? 0xFF3A5A2A : 0xFF3A3428);
        g.renderOutline(cX4, btnY, cW4, btnH, CSB());
        g.renderOutline(cX4+1, btnY+1, cW4-2, btnH-2, 0xFF2A2822);
        drawStr(g, (gridSnap ? "§a" : "§7") + net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.grid"), cX4+6, btnY+4, CT);

        // 右下角书签按钮 / bottom-right bookmark button
        int bmX = width - 22, bmY = height - 44, bmW = 18, bmH = 18;
        boolean bmOpen = showBookmarkPanel;
        g.fill(bmX, bmY, bmX+bmW, bmY+bmH, bmOpen ? 0xFF4A4A2A : 0xFF3A3832);
        g.renderOutline(bmX, bmY, bmW, bmH, bmOpen ? ACC() : CSB());
        drawStr(g, bmOpen ? "§e★" : "§7☆", bmX+2, bmY+2, CT);

        // 右下角工具栏位置切换按钮
        int tX = width - 22, tY = height - 22, tW = 18, tH = 18;
        g.fill(tX, tY, tX+tW, tY+tH, 0xFF3A3832);
        g.renderOutline(tX, tY, tW, tH, CSB());
        drawStr(g, toolbarBottom ? "§7▲" : "§7▼", tX+5, tY+3, CT);

        // 环警告
        int warnY = toolbarBottom ? btnY - 24 : btnY + btnH + 6;
        if(cycleWarning != null) {
            int ww = Minecraft.getInstance().font.width(cycleWarning)+20;
            int cx = width/2;
            g.fill(cx-ww/2, warnY, cx+ww/2, warnY+22, 0xCC4A2820);
            g.renderOutline(cx-ww/2, warnY, ww, 22, 0xFFFF5533);
            drawStr(g, "§c" + cycleWarning, cx-ww/2+10, warnY+4, 0xFFFFFFFF);
        }
    }

    void drawStr(GuiGraphics g, String t, float x, float y, int c) {
        g.drawString(Minecraft.getInstance().font, t, (int)x, (int)y, c, false);
    }
}
