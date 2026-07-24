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
    // Index constants for the 16 themeable colors
    static final int _CG=0,_CGL=1,_CN=2,_CH=3,_CB=4,_CPI=5,_CPO=6,_CW=7,_CWD=8;
    static final int _CMN=9,_CMH=10,_CNT=11,_CCT=12,_CSB=13,_CPIB=14,_CPOB=15;
    static final int _NUM_COLORS = 16;

    // Text and dim colors are constant across all themes
    static final int CT=0xFFFFFFFF, CD=0xFF888888;

    private static volatile int[] _c = {
        0xFF1F1E1A,0xFF2C2A24,0xFF3A3832,0xFF4A3F28,0xFF5A4D3A,0xFFD4A017,0xFFB87333,0xFFC5962B,0xFFFFDD55,
        0xFF888888,0xFFFFDD77,0xFFFFAA00,0xFFFFAA00,0xFF8B7533,0xFF8B6914,0xFF8A4A22};

    // Inline accessors — JIT constant-folds the bounds checks
    static int CG() { return _c[_CG]; } static int CGL() { return _c[_CGL]; }
    static int CN() { return _c[_CN]; } static int CH() { return _c[_CH]; }
    static int CB() { return _c[_CB]; } static int CPI() { return _c[_CPI]; }
    static int CPO() { return _c[_CPO]; } static int CW() { return _c[_CW]; }
    static int CWD() { return _c[_CWD]; } static int CMN() { return _c[_CMN]; }
    static int CMH() { return _c[_CMH]; } static int CNT() { return _c[_CNT]; }
    static int CCT() { return _c[_CCT]; } static int CSB() { return _c[_CSB]; }
    static int CPIB() { return _c[_CPIB]; } static int CPOB() { return _c[_CPOB]; }

    static final int[][] THEMES = {
        // 16色: CG(),CGL(),CN(),CH(),CB(),CPI(),CPO(),CW(),CWD(),CMN(),CMH(),CNT(),CCT(),CSB(),CPIB(),CPOB()
        {0xFF1F1E1A,0xFF2C2A24,0xFF3A3832,0xFF4A3F28,0xFF5A4D3A,0xFFD4A017,0xFFB87333,0xFFC5962B,0xFFFFDD55,0xFF888888,0xFFFFDD77,0xFFFFAA00,0xFFFFAA00,0xFF8B7533,0xFF8B6914,0xFF8A4A22},
        {0xFF0A1020,0xFF152040,0xFF1A2A4A,0xFF2A3A5A,0xFF3A5A7A,0xFF44AAFF,0xFFFFAA44,0xFF66BBFF,0xFFFFFF88,0xFF88AACC,0xFFFFDD77,0xFFFFDD77,0xFF88AACC,0xFF6688AA,0xFF2266AA,0xFFAA6622},
        {0xFF0A0A0A,0xFF1A1A1A,0xFF2A2A2A,0xFF3A3A3A,0xFF555555,0xFF00FF88,0xFFFF4466,0xFF888888,0xFFFFFF88,0xFFAAAAAA,0xFFCCCCCC,0xFFCCCCCC,0xFF888888,0xFF666666,0xFF444444,0xFF444444},
        {0xFF1E1410,0xFF2A1C14,0xFF3A2820,0xFF4A3428,0xFF5A4438,0xFFFF8844,0xFFAA6633,0xFFDD8844,0xFFFFCC66,0xFFAA8866,0xFFFFCC77,0xFFFFCC77,0xFFAA8866,0xFF8B6B53,0xFF6B4A33,0xFF6B3A23},
    };
    static int currentTheme = 0;

    static void applyTheme(int index) {
        if (index < 0 || index >= THEMES.length) index = 0;
        currentTheme = index;
        _c = THEMES[index]; // atomic array swap — all colors change at once
    }

    static int[] currentColors() { return _c.clone(); }
    static final int[] DEFAULT_COLORS = {0xFF1F1E1A,0xFF2C2A24,0xFF3A3832,0xFF4A3F28,0xFF5A4D3A,0xFFD4A017,0xFFB87333,0xFFC5962B,0xFFFFDD55,0xFF888888,0xFFFFDD77,0xFFFFAA00,0xFFFFAA00,0xFF8B7533,0xFF8B6914,0xFF8A4A22};
    static final String[] COLOR_KEYS = {"bg","grid","node","header","border","input","output","wire","drag","menu_text","menu_hover","node_title","cat_text","sys_border","input_border","output_border"};
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

    // 分类菜单
    private record NodeCategory(String langKey, NodeType[] types, int columns) {
        NodeCategory(String langKey, NodeType[] types) { this(langKey, types, 1); }
    }
    private static final NodeCategory[] CATEGORIES = {
        new NodeCategory("category.create_schematic_compute.values", new NodeType[]{NodeType.CONST, NodeType.REDSTONE_IN, NodeType.PRIVATE_IN, NodeType.BUS_IN}),
        new NodeCategory("category.create_schematic_compute.math_basic", new NodeType[]{NodeType.ADD, NodeType.SUB, NodeType.MUL, NodeType.DIV, NodeType.MOD, NodeType.POW, NodeType.ROOT, NodeType.ABS, NodeType.CEIL, NodeType.FLOOR}),
        new NodeCategory("category.create_schematic_compute.math_advanced", new NodeType[]{NodeType.FORMULA, NodeType.POSE_CONVERT, NodeType.SPLIT, NodeType.INTERP, NodeType.ROUND}),
        new NodeCategory("category.create_schematic_compute.trig", new NodeType[]{NodeType.SIN, NodeType.COS, NodeType.TAN, NodeType.ASIN, NodeType.ACOS, NodeType.ATAN2, NodeType.SINH, NodeType.COSH, NodeType.SQRT, NodeType.LN, NodeType.LOG, NodeType.EXP, NodeType.SEC, NodeType.CSC, NodeType.COT, NodeType.ANGLE_UNWRAP, NodeType.DIRECTION}, 2),
        new NodeCategory("category.create_schematic_compute.logic", new NodeType[]{NodeType.GT, NodeType.LT, NodeType.GE, NodeType.LE, NodeType.EQ, NodeType.BOOL, NodeType.GATE, NodeType.OR}),
        new NodeCategory("category.create_schematic_compute.control", new NodeType[]{NodeType.PID, NodeType.PID_POWER, NodeType.CLAMP, NodeType.MAP}),
        new NodeCategory("category.create_schematic_compute.output", new NodeType[]{NodeType.REDSTONE_OUT, NodeType.PRIVATE_OUT, NodeType.SPEED_CTRL, NodeType.BUS_OUT}),
        new NodeCategory("category.create_schematic_compute.sequential", new NodeType[]{NodeType.DELAY, NodeType.LATCH, NodeType.T_FLIPFLOP, NodeType.PULSE_EXTEND, NodeType.LOOP, NodeType.FUSE, NodeType.ACCUMULATOR, NodeType.INTEGRATOR}),
        // F: input_ctrl + input_sensor 合并 / merged
        new NodeCategory("category.create_schematic_compute.input",
            new NodeType[]{NodeType.KEYBOARD, NodeType.MOUSE_BUTTON, NodeType.MOUSE_JOYSTICK, NodeType.GAMEPAD_JOYSTICK, NodeType.GAMEPAD_BUTTON, NodeType.GAMEPAD_TRIGGER,
                           NodeType.VIEW_ANGLE, NodeType.WORLD_VIEW, NodeType.ATTITUDE, NodeType.FORWARD, NodeType.ACCELERATION, NodeType.VELOCITY, NodeType.POSITION, NodeType.TARGET_OUT}),
        // F: COMMENT 并入 display / COMMENT merged into display
        new NodeCategory("category.create_schematic_compute.display",
            new NodeType[]{NodeType.TEXT, NodeType.DATA, NodeType.IMAGE, NodeType.IMAGE_SEQUENCE}),
        // F: encap_io 并入 structure / encap_io merged into structure
        new NodeCategory("category.create_schematic_compute.structure",
            new NodeType[]{NodeType.ENCAPSULATION, NodeType.ENCAP_INPUT, NodeType.ENCAP_OUTPUT}),
        new NodeCategory("category.create_schematic_compute.debug", new NodeType[]{NodeType.DEBUG_SIGNAL_GEN, NodeType.DEBUG_PROBE, NodeType.COMMENT}),
    };
    private final java.util.Map<Integer, Boolean> catExpanded = new java.util.HashMap<>();
    private float menuRX, menuRY;
    private java.util.function.Predicate<NodeType> currentFilter = null;
    // —— ADF 滚动+搜索字段 / scroll + search fields ——
    private float menuW = 0;
    private float menuScrollOff = 0;
    private int menuTotalH = 0;       // 列表总高（不含封顶）/ total list height (uncapped)
    private int menuMaxH = 0;         // 封顶后的可见高度 / capped visible height
    private String menuSearchText = "";
    private boolean menuSearchFocused = false;
    private static final int TOP_H = 34;   // 标题(18) + 搜索框(16) 固定不滚动区 / fixed non-scroll area
    private static final int SCROLLBAR_W = 6;

    public NodeRenderer(CoordMapper c2sX, CoordMapper c2sY, net.minecraft.client.gui.screens.Screen screen) {
        this.c2sX = c2sX; this.c2sY = c2sY;
        this.screen = screen;
    }

    public void renderGrid(GuiGraphics g, float camX, float camY, float zoom, int width, int height) {
        g.fill(-10,-10,width+10,height+10,CG());
        float ox=(camX*zoom)%(GS*zoom), oy=(camY*zoom)%(GS*zoom);
        for(float x=width/2f+ox; x<width; x+=GS*zoom) { int ix=Math.round(x); g.fill(ix,0,ix+1,height,CGL()); }
        for(float y=height/2f+oy; y<height; y+=GS*zoom) { int iy=Math.round(y); g.fill(0,iy,width,iy+1,CGL()); }
        for(float x=width/2f+ox; x>=0; x-=GS*zoom) { int ix=Math.round(x); g.fill(ix,0,ix+1,height,CGL()); }
        for(float y=height/2f+oy; y>=0; y-=GS*zoom) { int iy=Math.round(y); g.fill(0,iy,width,iy+1,CGL()); }
    }

    public void renderConnections(GuiGraphics g, NodeGraph graph, float camX, float camY, float zoom) {
        int sw = screen.width, sh = screen.height;
        // viewport in world coords (with generous margin for bezier curves that extend beyond endpoints)
        float vpLeft = -sw / (2f * zoom) - camX - 100 / zoom, vpRight = sw / (2f * zoom) - camX + 100 / zoom;
        float vpTop = -sh / (2f * zoom) - camY - 100 / zoom, vpBottom = sh / (2f * zoom) - camY + 100 / zoom;
        for(NodeConnection c : graph.connections) {
            GraphNode fn = graph.findNode(c.fromId);
            GraphNode tn = graph.findNode(c.toId);
            if(fn==null||tn==null) continue;
            // cull: both endpoints outside viewport edge
            float wx1 = fn.x + nw(fn), wx2 = tn.x;
            if ((wx1 < vpLeft && wx2 < vpLeft) || (wx1 > vpRight && wx2 > vpRight)) continue;
            // 输出引脚Y（BUS_IN 编辑区引脚动态计算）
            float wy1;
            if (fn.type == NodeType.BUS_IN) {
                wy1 = fn.y + GraphEditor.bandPinY(fn, c.fromPin, zoom);
            } else {
                wy1 = fn.y + HH + PH*(fn.functionalInputs() + c.fromPin) + PH/2f;
            }
            // 输入引脚Y（BUS_OUT 编辑区引脚动态计算）
            float wy2;
            if (tn.type == NodeType.BUS_OUT) {
                wy2 = tn.y + GraphEditor.bandPinY(tn, c.toPin, zoom);
            } else if (c.toPin < tn.functionalInputs()) {
                wy2 = tn.y + HH + PH*c.toPin + PH/2f;
            } else {
                int paramIdx = c.toPin - tn.functionalInputs();
                wy2 = tn.y + HH + PH*(tn.functionalInputs() + tn.outputs()) + 4/zoom + paramIdx*18 + 12;
            }
            if ((wy1 < vpTop && wy2 < vpTop) || (wy1 > vpBottom && wy2 > vpBottom)) continue;
            float x1 = c2sX.apply(wx1), y1 = c2sY.apply(wy1);
            float x2 = c2sX.apply(wx2), y2 = c2sY.apply(wy2);
            bezier(g, x1, y1, x2, y2, CW());
        }
    }

    public void renderDraggingWire(GuiGraphics g, NodeGraph graph, int wireFromNode, int wireFromPin,
                                    float wireEndX, float wireEndY, float camX, float camY, float zoom) {
        var fn = graph.findNode(wireFromNode);
        if(fn==null) return;
        float y1;
        if (fn.type == NodeType.BUS_IN) {
            y1 = c2sY.apply(fn.y + GraphEditor.bandPinY(fn, wireFromPin, zoom));
        } else {
            y1 = c2sY.apply(fn.y+HH+PH*(fn.functionalInputs() + wireFromPin)+PH/2f);
        }
        float x1 = c2sX.apply(fn.x + nw(fn));
        float x2 = c2sX.apply(wireEndX), y2 = c2sY.apply(wireEndY);
        bezier(g, x1, y1, x2, y2, CWD());
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

    /** A=1: Render complete COMMENT nodes (background, border, text, handles) behind connections.
     *  Comment nodes act as container mats — everything renders at A=1, sorted by B. */
    public void renderCommentNodes(GuiGraphics g, List<GraphNode> nodes, Set<GraphNode> selectedNodes,
                                    GraphNode primaryNode, java.util.Set<Integer> editNodeIds,
                                    java.util.Map<Integer, io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.EditState> editStates,
                                    float camX, float camY, float zoom, int mx, int my,
                                    Map<Integer, Boolean> flipflopStates,
                                    Map<Integer, String> lockedNodes) {
        expandedNodeIds = editNodeIds != null ? editNodeIds : java.util.Collections.emptySet();
        nodeEditStatesById = editStates != null ? editStates : java.util.Collections.emptyMap();
        int w = screen.width, h = screen.height;
        float margin = 50;
        for (var n : nodes) {
            if (n.type != NodeType.COMMENT) continue;
            float sx = c2sX.apply(n.x), sy = c2sY.apply(n.y);
            float sw = n.commentWidth * zoom;
            float sh = n.commentHeight * zoom;
            if (sx + sw < -margin || sx > w + margin || sy + sh < -margin || sy > h + margin)
                continue;
            drawCommentNode(g, n, selectedNodes.contains(n), n == primaryNode,
                expandedNodeIds.contains(n.id), camX, camY, zoom, mx, my, false,
                lockedNodes != null ? lockedNodes.get(n.id) : null);
        }
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
            float sw = nw(n)*zoom, nh = NodeRenderer.nh(n)*zoom+4;
            if (expandedNodeIds.contains(n.id) && n.type != NodeType.COMMENT)
                nh += io.github.y15173334444.create_schematic_compute.blocks.EditPanel.calcRenderHeight(n, zoom) * zoom;
            if (sx + sw < -margin || sx > w + margin || sy + nh < -margin || sy > h + margin)
                continue;
            drawNode(g, n, selectedNodes.contains(n), n == primaryNode, expandedNodeIds.contains(n.id), camX, camY, zoom, mx, my, flipflopStates, lockedNodes);
        }
    }

    /** Render a COMMENT node with sticky-note styling, edit button, resize handle, and markdown text. */
    private void drawCommentNode(GuiGraphics g, GraphNode n, boolean selected, boolean isPrimary,
                                  boolean editing, float camX, float camY, float zoom, int mx, int my,
                                  boolean skipBackground, String lockedBy) {
        float sx = c2sX.apply(n.x), sy = c2sY.apply(n.y);
        float sw = n.commentWidth * zoom;
        float sh = n.commentHeight * zoom;
        int isx = (int) sx, isy = (int) sy, isw = (int) (sx + sw), ish = (int) (sy + sh);
        int headerH = Math.max(6, (int)(12 * zoom)); // drag header bar

        // Background — skip when already rendered at A=1
        if (!skipBackground) g.fill(isx, isy, isw, ish, n.commentBgColor);
        // Header drag bar (allows drag-only-by-header, like regular nodes)
        int argb = n.commentBgColor;
        int headerBg = ((Math.min((argb >> 16) & 0xFF, 220) << 16)
                      | (Math.min((argb >> 8) & 0xFF, 220) << 8)
                      | Math.min(argb & 0xFF, 220)
                      | (argb & 0xFF000000));
        g.fill(isx, isy, isw, (int)(isy + headerH), headerBg);
        // Border: brighter when editing
        int borderColor;
        if (editing) borderColor = 0xFFC0A060;                // warm tan = active editing
        else if (isPrimary) borderColor = 0xFFFFAA00;          // gold = primary selection
        else if (selected) borderColor = 0xFFE6C060;           // light gold = selected
        else borderColor = n.commentBorderColor;                // custom = normal
        g.renderOutline(isx, isy, (int) sw, (int) sh, borderColor);
        // Remote lock/selection indicator — golden border when another player has this comment selected
        // 远程锁定/选中指示器 — 其他玩家选中此注释时显示金色边框
        if (lockedBy != null && !lockedBy.isEmpty()) {
            int lockCol = 0xFFD4A017;
            g.renderOutline(isx - 2, isy - 2, (int) sw + 4, (int) sh + 4, lockCol);
            g.renderOutline(isx - 3, isy - 3, (int) sw + 6, (int) sh + 6, lockCol);
            int lw = Minecraft.getInstance().font.width(lockedBy);
            int lx = isx + ((int) sw - lw) / 2;
            int ly = isy - (int)(16 * zoom) - 2;
            g.fill(lx - 3, ly - 2, lx + lw + 3, ly + (int)(12 * zoom), 0xCC2A2822);
            var lockPose = g.pose();
            lockPose.pushPose();
            lockPose.translate(lx, ly, 0);
            lockPose.scale(zoom, zoom, 1);
            drawStr(g, "§e" + lockedBy, 0, 0, 0xFFFFAA44);
            lockPose.popPose();
        }
        // Editing banner — rendered above the node
        if (editing) {
            int bannerH = (int)(16 * zoom);
            String editHint = I18n.get("gui.create_schematic_compute.comment.edit_hint");
            int textW = Minecraft.getInstance().font.width(editHint);
            float bannerW = (textW + 16) * zoom;
            float bx = sx + sw/2 - bannerW/2;
            float by = sy - bannerH - 2 * zoom;
            g.fill((int)bx, (int)by, (int)(bx + bannerW), (int)(by + bannerH), 0xCC2A2822);
            g.renderOutline((int)bx, (int)by, (int)bannerW, bannerH, 0xFFC0A060);
            var bannerPose = g.pose();
            bannerPose.pushPose();
            bannerPose.translate(bx + bannerW/2, by + bannerH/2, 0);
            bannerPose.scale(zoom, zoom, 1);
            drawStr(g, "§e" + editHint, -textW/2, -4, 0xFFFFDD77);
            bannerPose.popPose();
        }
        // Inner subtle border
        if ((int) sw > 6 && (int) sh > 6) {
            int ib = n.commentBorderColor;
            int innerB = (Math.min((ib >> 16) & 0xFF, 248) << 16)
                       | (Math.min((ib >> 8) & 0xFF, 248) << 8)
                       | Math.min(ib & 0xFF, 248)
                       | (ib & 0xFF000000);
            g.renderOutline(isx + 2, isy + 2, (int) sw - 4, (int) sh - 4, innerB);
        }

        // Edit button — top-right 14×14 px, highlighted when editing
        int btnBg = editing ? 0x88C0A060 : 0x66000000;
        int btnOutline = editing ? 0xFFC0A060 : 0xFF888888;
        float btnSize = 14 * zoom;
        int btnX = (int) (sx + 2 * zoom);
        int btnY = (int) (sy + 2 * zoom);
        int btnS = (int) btnSize;
        // Button background
        g.fill(btnX, btnY, btnX + btnS, btnY + btnS, btnBg);
        g.renderOutline(btnX, btnY, btnS, btnS, btnOutline);
        // Gear icon
        var btPose = g.pose();
        btPose.pushPose();
        btPose.translate(btnX + btnS/2f, btnY + btnS/2f, 0);
        btPose.scale(zoom, zoom, 1);
        drawStr(g, "§7⚙", -4, -4, 0xFFCCCCCC);
        btPose.popPose();

        // Resize handle — bottom-right corner, darker contrasting L-shape with grip lines
        int handleW = (int) (18 * zoom);
        int hx2 = (int) (sx + sw - handleW);
        int hy2 = (int) (sy + sh - handleW);
        // Darker contrasting background corner
        int handleBg = 0x88000000;
        g.fill(hx2, hy2, (int) (sx + sw), (int) (sy + sh), handleBg);
        // L-shaped corner lines — thick, visible dark gray
        int handleColor = 0xFF777777;
        int lineW = Math.max(1, (int) (3 * zoom));
        g.fill(hx2, (int) (sy + sh - lineW), (int) (sx + sw), (int) (sy + sh), handleColor);
        g.fill((int) (sx + sw - lineW), hy2, (int) (sx + sw), (int) (sy + sh), handleColor);
        // Diagonal grip stripes — 3 short diagonal lines (classic resize grip pattern)
        int gripColor = 0xFFAAAAAA;
        for (int i = 0; i < 3; i++) {
            int gx = (int) (sx + sw - (5 * zoom) - i * (5 * zoom));
            int gy = (int) (sy + sh - (2 * zoom));
            g.fill(gx, gy, gx + Math.max(1, (int)(3 * zoom)), gy + Math.max(1, (int)(2 * zoom)), gripColor);
        }

        // Render text body (markdown-simplified), below the header bar
        var mc = Minecraft.getInstance();
        float textX = sx + 6 * zoom;
        float textY = sy + headerH + 4 * zoom;
        float maxTextW = sw - 26 * zoom;
        int availW = Math.max(1, (int) (maxTextW / zoom));
        int lineH = 12;
        float visibleH = (sh - headerH - 8 * zoom) / zoom;
        int maxVis = Math.max(1, (int)(visibleH / lineH));
        String renderText = editing ? (nodeEditStatesById.containsKey(n.id)
            ? getEditStateText(n) : n.displayText) : n.displayText;

        // Compute scroll info — use edit state text when editing, displayText otherwise
        String scrollText = n.displayText;
        if (editing) {
            var st = nodeEditStatesById.get(n.id);
            if (st != null && !st.fields.isEmpty()
                && st.fields.get(0) instanceof io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox mle) {
                scrollText = mle.getValue();
            }
        }
        int totalWraps = 0;
        if (!scrollText.isEmpty()) {
            totalWraps = countWrappedLinesLocal(scrollText, availW);
        }
        int scrollMax = Math.max(0, totalWraps - maxVis);
        int scrollOff = n.commentScrollOff;
        if (scrollOff < 0) scrollOff = 0;
        if (scrollOff > scrollMax) { scrollOff = scrollMax; n.commentScrollOff = scrollOff; }

        // Scrollbar — only visible when text overflows
        if (scrollMax > 0) {
            int sbX = (int)(sx + sw - 10 * zoom);
            int sbY = (int)(sy + headerH + 4 * zoom);
            int sbH = (int)(sh - headerH - 8 * zoom);
            int sbW = Math.max(2, (int)(6 * zoom));
            g.fill(sbX, sbY, sbX + sbW, sbY + sbH, 0xFF3A3832);
            float thumbH = Math.max(12 * zoom, (float) maxVis / totalWraps * sbH);
            float thumbY = sbY + (float) scrollOff / scrollMax * (sbH - thumbH);
            g.fill(sbX + 1, (int)thumbY, sbX + sbW - 1, (int)(thumbY + thumbH), n.commentBorderColor);
        }

        if (editing) {
            // Render the MLE on the sticky-note surface via EditPanel, with scroll offset
            var editSt = nodeEditStatesById.get(n.id);
            if (editSt != null) {
                int scX2 = (int) sx, scY2 = (int) (sy + headerH);
                int scW2 = (int) sw, scH2 = (int) (sh - headerH);
                g.enableScissor(scX2, scY2, scX2 + scW2, scY2 + scH2);
                int commentEditY = (int)(headerH / zoom) + 4 - scrollOff * lineH;
                var pose = g.pose();
                pose.pushPose();
                pose.translate(sx, sy, 0);
                pose.scale(zoom, zoom, 1);
                io.github.y15173334444.create_schematic_compute.blocks.EditPanel.renderAt(
                    g, 0, commentEditY, Math.round(n.commentWidth), n, editSt, zoom, mx, my, null);
                pose.popPose();
                g.disableScissor();
            }
        } else if (!n.displayText.isEmpty()) {
            // Build all wrapped segments into a flat list
            var segments = new java.util.ArrayList<String>();
            for (String line : n.displayText.split("\n", -1)) {
                String remaining = line;
                if (remaining.isEmpty()) { segments.add(""); continue; }
                while (!remaining.isEmpty()) {
                    if (mc.font.width(plainText(remaining)) <= availW) {
                        segments.add(remaining);
                        break;
                    }
                    String chunk = mc.font.plainSubstrByWidth(remaining, availW);
                    if (chunk.isEmpty()) chunk = remaining.substring(0, 1);
                    segments.add(chunk);
                    remaining = remaining.substring(chunk.length());
                }
            }

            // Render visible segments with scissor clipping
            int scX = (int) sx, scY = (int) (sy + headerH);
            int scW = (int) sw, scH = (int) (sh - headerH);
            g.enableScissor(scX, scY, scX + scW, scY + scH);
            float ly = textY;
            int endIdx = Math.min(segments.size(), scrollOff + maxVis);
            for (int i = scrollOff; i < endIdx; i++) {
                if (ly > sy + sh - 10 * zoom) break;
                var txtPose = g.pose();
                txtPose.pushPose();
                txtPose.translate(textX, ly, 0);
                txtPose.scale(zoom, zoom, 1);
                renderMarkdownLine(g, segments.get(i), 0, 0, availW, n.commentTextColor);
                txtPose.popPose();
                ly += 12 * zoom;
            }
            g.disableScissor();
        }
        g.flush(); // per-node flush for buffer ordering (see drawNode)
    }

    private static String getEditStateText(GraphNode n) {
        // Return empty — MLE handles its own text display
        return "";
    }

    private static int countWrappedLinesLocal(String text, int availW) {
        var font = Minecraft.getInstance().font;
        int total = 0;
        for (String line : text.split("\n", -1)) {
            String rem = line;
            if (rem.isEmpty()) { total++; continue; }
            while (!rem.isEmpty()) {
                if (font.width(rem) <= availW) { total++; break; }
                String chunk = font.plainSubstrByWidth(rem, availW);
                if (chunk.isEmpty()) chunk = rem.substring(0, 1);
                total++;
                rem = rem.substring(chunk.length());
            }
        }
        return Math.max(1, total);
    }

    private static String plainText(String line) {
        return line.replaceAll("\\*\\*|\\*|`|#\\s?", "");
    }

    private void renderMarkdownLine(GuiGraphics g, String line, int x, int y, int maxW, int textColor) {
        if (line.startsWith("# ")) {
            drawStr(g, "§l" + line.substring(2), x, y, textColor);
        } else if (line.startsWith("- ")) {
            drawStr(g, "§7•§r " + inlineMarkdown(line.substring(2)), x, y, textColor);
        } else {
            drawStr(g, inlineMarkdown(line), x, y, textColor);
        }
    }

    private static String inlineMarkdown(String text) {
        var sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (i + 1 < text.length() && text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                int end = text.indexOf("**", i + 2);
                if (end >= 0) {
                    sb.append("§l").append(text, i + 2, end).append("§r");
                    i = end + 2;
                } else { sb.append(text.charAt(i)); i++; }
            } else if (text.charAt(i) == '*' && (i == 0 || text.charAt(i - 1) != '\\')) {
                int end = text.indexOf('*', i + 1);
                if (end >= 0) {
                    sb.append("§o").append(text, i + 1, end).append("§r");
                    i = end + 1;
                } else { sb.append(text.charAt(i)); i++; }
            } else if (text.charAt(i) == '`') {
                int end = text.indexOf('`', i + 1);
                if (end >= 0) {
                    sb.append("§7§o").append(text, i + 1, end).append("§r");
                    i = end + 1;
                } else { sb.append(text.charAt(i)); i++; }
            } else {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return sb.toString();
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
            g.renderOutline(wx, wy, (int)(warnW * zoom), (int)(warnH * zoom), 0xFFFF4444);
            var warnPose = g.pose();
            warnPose.pushPose();
            warnPose.translate(wx + 4 * zoom, wy + 1 * zoom, 0);
            warnPose.scale(zoom, zoom, 1);
            drawStr(g, "§c⚠ " + warn, 0, 0, 0xFFFF8888);
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
            g.renderOutline(wx, wy, (int)(warnW * zoom), (int)(warnH * zoom), 0xFFFF4444);
            var warnPose = g.pose();
            warnPose.pushPose();
            warnPose.translate(wx + 4 * zoom, wy + 1 * zoom, 0);
            warnPose.scale(zoom, zoom, 1);
            drawStr(g, "§c" + warn, 0, 0, 0xFFFF8888);
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
            g.fill(2, editLocalY - 2, nodeW - 2, editLocalY, 0xFF5A4D3A);
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
        int borderColor = isPrimary ? 0xFFFFAA00 : selected ? 0xFFD4A017 : CB();
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
        // Flush per-node to prevent text (font buffer) from later nodes'
        // fills covering earlier nodes' text due to Minecraft's two-pass
        // buffer flush (all fills before all text).
        g.flush();
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

        // 计算窗口内数据范围（极端值截断 + 边距，与信号发生器一致）
        // Compute window data range (clip extremes + padding, same as signal generator)
        float CLIP = 5f;
        float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int idx = (start + i) % n.probeHistory.length;
            float v = n.probeHistory[idx];
            if (!Float.isFinite(v)) continue;
            if (v < -CLIP) v = -CLIP;
            if (v > CLIP) v = CLIP;
            if (v < minV) minV = v;
            if (v > maxV) maxV = v;
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

    public NodeType renderAddNodeMenu(GuiGraphics g, float menuX, float menuY, int mx, int my) {
        return renderAddNodeMenu(g, menuX, menuY, mx, my, null);
    }

    /** 计算分类中可见节点数 */
    private int visibleCount(NodeCategory cat, java.util.function.Predicate<NodeType> filter) {
        int n = 0;
        for (var nt : cat.types) if (filter == null || filter.test(nt)) n++;
        return n;
    }

    public NodeType renderAddNodeMenu(GuiGraphics g, float menuX, float menuY, int mx, int my, java.util.function.Predicate<NodeType> filter) {
        // D: 组合外部 filter + 搜索文本 / combine external filter + search text
        java.util.function.Predicate<NodeType> combined = nt -> {
            if (filter != null && !filter.test(nt)) return false;
            if (menuSearchText.isEmpty()) return true;
            String q = menuSearchText.toLowerCase();
            return I18n.get(nt.displayName).toLowerCase().contains(q);
        };
        currentFilter = combined;

        int ih=14, ch=16, colW=144;
        int maxCols = 1;
        for (int ci = 0; ci < CATEGORIES.length; ci++) {
            if (visibleCount(CATEGORIES[ci], combined) == 0) continue;
            if (catExpanded.getOrDefault(ci, false))
                maxCols = Math.max(maxCols, CATEGORIES[ci].columns);
        }
        menuW = 16 + maxCols * colW;
        int totalH = TOP_H;
        for (int ci = 0; ci < CATEGORIES.length; ci++) {
            if (visibleCount(CATEGORIES[ci], combined) == 0) continue;
            totalH += ch;
            if (catExpanded.getOrDefault(ci, false)) {
                int cols = CATEGORIES[ci].columns;
                int items = visibleCount(CATEGORIES[ci], combined);
                totalH += (int)Math.ceil((double)items / cols) * ih;
            }
        }
        // A: 真实高度封顶 / height cap
        int maxH = Math.min(totalH, screen.height - 12);
        menuTotalH = totalH; menuMaxH = maxH;
        menuScrollOff = Math.max(0, Math.min(menuScrollOff, totalH - maxH));
        menuRX = Math.max(0, Math.min(menuX, screen.width - menuW));
        menuRY = Math.max(0, Math.min(menuY, screen.height - maxH));

        // 面板框（固定 maxH 高）/ panel frame (fixed maxH height)
        g.fill((int)menuRX, (int)menuRY, (int)(menuRX + menuW), (int)(menuRY + maxH), 0xFF2A2822);
        g.renderOutline((int)menuRX, (int)menuRY, (int)menuW, maxH, CSB());
        g.renderOutline((int)menuRX + 1, (int)menuRY + 1, (int)menuW - 2, maxH - 2, 0xFF1A1814);

        // —— 固定顶部区（标题 + 搜索框，不滚动）/ fixed top area (title + search, non-scrolling) ——
        drawStr(g, "§l" + I18n.get("gui.create_schematic_compute.nodes"), menuRX + 6, menuRY + 4, CCT());
        // D: 搜索框 / search box
        int sbX = (int)menuRX + 6, sbY = (int)menuRY + 18, sbW = (int)menuW - 12, sbH = 12;
        g.fill(sbX, sbY, sbX + sbW, sbY + sbH, 0xFF1A1814);
        g.renderOutline(sbX, sbY, sbW, sbH, menuSearchFocused ? 0xFFD4A017 : 0xFF5A4D3A);
        String shown = menuSearchText.isEmpty()
            ? I18n.get("gui.create_schematic_compute.search_hint")
            : menuSearchText + (menuSearchFocused && (System.currentTimeMillis() / 500 % 2 == 0) ? "_" : "");
        drawStr(g, shown, sbX + 3, sbY + 2, menuSearchText.isEmpty() ? 0xFF777777 : CMN());

        NodeType hovered = null;
        // A: scissor 裁剪列表区 / scissor-clip list area (screen coords, y=0=top)
        g.enableScissor((int)menuRX, (int)(menuRY + TOP_H), (int)menuRX + (int)menuW, (int)(menuRY + maxH));
        // 滚动条可见时，hover 排除滚动条区域 / exclude scrollbar from hover when visible
        float hoverRight = menuRX + menuW - (totalH > maxH ? SCROLLBAR_W + 4 : 2);
        int cy = (int)menuRY + TOP_H - (int)menuScrollOff;

        boolean searching = !menuSearchText.isEmpty();
        if (searching) {
            // D: 搜索模式 — 扁平列表，按匹配度排序 / search mode — flat list, sorted by relevance
            var matches = new java.util.ArrayList<NodeType>();
            for (var cat : CATEGORIES) for (var nt : cat.types) if (combined.test(nt)) matches.add(nt);
            String q = menuSearchText.toLowerCase();
            matches.sort((a, b) -> {
                String na = I18n.get(a.displayName).toLowerCase();
                String nb = I18n.get(b.displayName).toLowerCase();
                int scoreA = na.equals(q) ? 0 : na.startsWith(q) ? 1 : 2;
                int scoreB = nb.equals(q) ? 0 : nb.startsWith(q) ? 1 : 2;
                if (scoreA != scoreB) return Integer.compare(scoreA, scoreB);
                return na.compareTo(nb);
            });
            int cols = 2; // 双列布局 / two-column layout
            int itemsPerCol = (int)Math.ceil((double)matches.size() / cols);
            int idx = 0;
            for (var nt : matches) {
                int col = idx / itemsPerCol, row = idx % itemsPerCol;
                int ix = (int)menuRX + 8 + col * colW;
                int iy = cy + row * ih;
                int itemRight = (int)Math.min(ix + colW - 4, hoverRight);
                boolean h = mx >= ix && mx <= itemRight && my >= iy && my < iy + ih;
                if (h) { g.fill(ix, iy, itemRight, iy + ih, 0xFF3A3428); hovered = nt; }
                drawStr(g, I18n.get(nt.displayName), ix + 4, iy + 2, h ? CMH() : CMN());
                idx++;
            }
        } else {
            for (int ci = 0; ci < CATEGORIES.length; ci++) {
                NodeCategory cat = CATEGORIES[ci];
                int vis = visibleCount(cat, combined);
                if (vis == 0) continue;
                boolean exp = catExpanded.getOrDefault(ci, false);
                int cols = exp ? cat.columns : 1;
                String title = (exp ? "▼ " : "▶ ") + net.minecraft.client.resources.language.I18n.get(cat.langKey);
                boolean titleHover = mx >= menuRX + 2 && mx <= hoverRight && my >= cy && my < cy + ch;
                if (titleHover) g.fill((int)menuRX + 2, cy, (int)(hoverRight), (int)(cy + ch), 0xFF3A3428);
                drawStr(g, title, menuRX + 6, cy + 2, titleHover ? CMH() : CCT());
                cy += ch;
                if (!exp) continue;
                int itemsPerCol = (int)Math.ceil((double)vis / cols);
                int itemIdx = 0;
                for (var nt : cat.types) {
                    if (!combined.test(nt)) continue;
                    int col = itemIdx / itemsPerCol;
                    int row = itemIdx % itemsPerCol;
                    int ix = (int)menuRX + 8 + col * colW;
                    int iy = cy + row * ih;
                    int itemRight = (int)Math.min(ix + colW - 4, hoverRight);
                    boolean h = mx >= ix && mx <= itemRight && my >= iy && my < iy + ih;
                    if (h) { g.fill(ix, iy, itemRight, iy + ih, 0xFF3A3428); hovered = nt; }
                    drawStr(g, I18n.get(nt.displayName), ix + 4, iy + 2, h ? CMH() : CMN());
                    itemIdx++;
                }
                cy += itemsPerCol * ih;
            }
        }
        g.disableScissor();

        // A: 右侧滚动条 / scrollbar
        if (totalH > maxH) {
            int trackH = maxH - TOP_H;
            int thumbH = Math.max(16, (int)((double)maxH / totalH * trackH));
            int thumbY = (int)menuRY + TOP_H + (int)((double)menuScrollOff / (totalH - maxH) * (trackH - thumbH));
            g.fill((int)(menuRX + menuW - SCROLLBAR_W - 2), thumbY,
                   (int)(menuRX + menuW - 2), thumbY + thumbH, 0xFF5A4D3A);
        }
        return hovered;
    }

    /** Handle category expand/collapse click + search box focus. Returns true if consumed. */
    public boolean handleCategoryClick(int mx, int my) {
        // D: 命中搜索框 → 聚焦 / hit search box → focus
        int sbX = (int)menuRX + 6, sbY = (int)menuRY + 18, sbW = (int)menuW - 12, sbH = 12;
        if (mx >= sbX && mx <= sbX + sbW && my >= sbY && my <= sbY + sbH) {
            menuSearchFocused = true; return true;
        }
        // 搜索模式下无分类折叠 / no category toggle in search mode
        if (!menuSearchText.isEmpty()) return false;
        int ih=14, ch=16;
        float clickRight = menuRX + menuW - (menuHasScrollbar() ? SCROLLBAR_W + 4 : 2);
        int cy = (int)menuRY + TOP_H - (int)menuScrollOff;
        for (int ci = 0; ci < CATEGORIES.length; ci++) {
            int vis = visibleCount(CATEGORIES[ci], currentFilter);
            if (vis == 0) continue;
            if (mx >= menuRX + 2 && mx <= clickRight && my >= cy && my < cy + ch) {
                catExpanded.put(ci, !catExpanded.getOrDefault(ci, false));
                return true;
            }
            cy += ch;
            if (!catExpanded.getOrDefault(ci, false)) continue;
            int cols = CATEGORIES[ci].columns;
            cy += (int)Math.ceil((double)vis / cols) * ih;
        }
        return false;
    }

    // ── D: 搜索框辅助方法 / search box helpers ──
    public void scrollMenu(float delta) { setMenuScrollOff((int)(menuScrollOff + delta)); }
    /** 菜单是否有滚动条。 / Whether the menu has a scrollbar. */
    public boolean menuHasScrollbar() { return menuTotalH > menuMaxH && menuMaxH > 0; }
    /** 滚动条轨道区（屏幕坐标）。 / Scrollbar track area (screen coords). */
    public int[] menuScrollbarTrack() {
        return new int[]{(int)(menuRX + menuW - SCROLLBAR_W - 2), (int)(menuRY + TOP_H),
                         SCROLLBAR_W, menuMaxH - TOP_H};
    }
    /** 按当前 scrollOff 计算 thumb Y 与高度。 */
    public int[] menuScrollbarThumb() {
        int trackH = menuMaxH - TOP_H;
        int thumbH = Math.max(16, (int)((double)menuMaxH / menuTotalH * trackH));
        int maxOff = menuTotalH - menuMaxH;
        int thumbY = (int)(menuRY + TOP_H);
        if (maxOff > 0) thumbY += (int)((double)menuScrollOff / maxOff * (trackH - thumbH));
        return new int[]{thumbY, thumbH};
    }
    public int menuScrollOff() { return (int)menuScrollOff; }
    public int menuMaxScrollOff() { return Math.max(0, menuTotalH - menuMaxH); }
    public void setMenuScrollOff(int off) { menuScrollOff = Math.max(0, Math.min(off, menuMaxScrollOff())); }
    public void appendMenuSearch(char c) { menuSearchText += c; }
    public void menuSearchBackspace() {
        if (!menuSearchText.isEmpty()) menuSearchText = menuSearchText.substring(0, menuSearchText.length() - 1);
    }
    public void resetMenuSearch() { menuSearchText = ""; menuSearchFocused = false; menuScrollOff = 0; }
    public boolean isMenuSearchFocused() { return menuSearchFocused; }
    public void setMenuSearchFocused(boolean f) { menuSearchFocused = f; }
    public void setMenuSearchText(String t) { menuSearchText = t == null ? "" : t; }

    public void renderButtons(GuiGraphics g, boolean compiled, boolean running, String cycleWarning,
                               long saveFeedbackUntil, boolean gridSnap, int themeIdx, int width, int height) {
        long now = System.currentTimeMillis();
        boolean fb = now < saveFeedbackUntil;
        int btnH = 18;
        // 工具栏位置：顶部(默认)或底部
        int btnY = toolbarBottom ? height - btnH - 4 : 4;
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
        // 颜色配置按钮
        int cX5 = 196, cW5 = 54;
        g.fill(cX5, btnY, cX5+cW5, btnY+btnH, 0xFF3A3832);
        g.renderOutline(cX5, btnY, cW5, btnH, CSB());
        g.renderOutline(cX5+1, btnY+1, cW5-2, btnH-2, 0xFF2A2822);
        drawStr(g, "§7" + net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.style"), cX5+8, btnY+4, CT);

        // 右下角书签按钮 / bottom-right bookmark button
        int bmX = width - 22, bmY = height - 44, bmW = 18, bmH = 18;
        boolean bmOpen = showBookmarkPanel;
        g.fill(bmX, bmY, bmX+bmW, bmY+bmH, bmOpen ? 0xFF4A4A2A : 0xFF3A3832);
        g.renderOutline(bmX, bmY, bmW, bmH, bmOpen ? 0xFFFFCC44 : CSB());
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

    private void bezier(GuiGraphics g, float x1, float y1, float x2, float y2, int c) {
        float dx = Math.abs(x2-x1)*0.4f;
        float dist = (float)Math.sqrt((x2-x1)*(x2-x1)+(y2-y1)*(y2-y1));
        int steps = Math.max(10, (int)(dist*0.15f));
        float px=x1, py=y1;
        for(int i=1; i<=steps; i++) {
            float t = i/(float)steps, inv = 1-t;
            float nx = inv*inv*inv*x1 + 3*inv*inv*t*(x1+dx) + 3*inv*t*t*(x2-dx) + t*t*t*x2;
            float ny = inv*inv*inv*y1 + 3*inv*inv*t*y1 + 3*inv*t*t*y2 + t*t*t*y2;
            int sx = (int)px, sy = (int)py, ex = (int)nx, ey = (int)ny;
            int sdx = ex - sx, sdy = ey - sy;
            int segLen = Math.max(Math.abs(sdx), Math.abs(sdy));
            if (segLen == 0) {
                g.fill(sx, sy, sx + 1, sy + 1, c);
            } else {
                // batch same-row pixels into horizontal runs → 1-pixel-thick line at any slope
                int runStart = sx, runY = sy;
                for (int j = 1; j <= segLen; j++) {
                    int cx = sx + sdx * j / segLen;
                    int cy = sy + sdy * j / segLen;
                    if (cy != runY || j == segLen) {
                        int endX = (j == segLen) ? ex : (sx + sdx * (j - 1) / segLen);
                        int x1_ = Math.min(runStart, endX), x2_ = Math.max(runStart, endX);
                        g.fill(x1_, runY, x2_ + 1, runY + 1, c);
                        runStart = cx;
                        runY = cy;
                    }
                }
            }
            px=nx; py=ny;
        }
    }
}
