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
                    if (v != null && v.length() == 8) try { c[i] = (int)(Long.parseLong(v, 16) & 0xFFFFFFFFL); } catch (Exception ignored) {}
                }
                setColors(c);
            } else if (currentTheme > 0) {
                applyTheme(currentTheme);
            }
        } catch (Exception e) { /* 默认 */ }
    }

    /** 保存颜色到配置文件 */
    static void saveColorConfig() {
        try {
            var path = java.nio.file.Path.of("config", "create_schematic_compute-client.properties");
            java.nio.file.Files.createDirectories(path.getParent());
            var props = new java.util.Properties();
            int[] c = currentColors();
            for (int i = 0; i < _NUM_COLORS; i++) props.setProperty("color." + COLOR_KEYS[i], String.format("%08X", c[i]));
            try (var os = java.nio.file.Files.newOutputStream(path)) { props.store(os, "Create: Schematic Compute Theme"); }
        } catch (Exception e) { /* 忽略 */ }
    }

    static { loadColorConfig(); }

    /** 保存网格吸附状态 */
    static void saveGridSnap(boolean on) {
        try {
            var path = java.nio.file.Path.of("config", "create_schematic_compute-client.properties");
            var props = new java.util.Properties();
            if (java.nio.file.Files.exists(path))
                try (var is = java.nio.file.Files.newInputStream(path)) { props.load(is); }
            props.setProperty("grid_snap", String.valueOf(on));
            try (var os = java.nio.file.Files.newOutputStream(path)) { props.store(os, null); }
        } catch (Exception e) { /* 忽略 */ }
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
        } catch (Exception e) { /* 忽略 */ }
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
        } catch (Exception e) { /* 忽略 */ }
    }

    static boolean loadToolbarBottom() {
        try {
            var path = java.nio.file.Path.of("config", "create_schematic_compute-client.properties");
            if (java.nio.file.Files.exists(path)) {
                var props = new java.util.Properties();
                try (var is = java.nio.file.Files.newInputStream(path)) { props.load(is); }
                return Boolean.parseBoolean(props.getProperty("toolbar_bottom", "false"));
            }
        } catch (Exception e) { /* 忽略 */ }
        return false;
    }

    // 尺寸常量
    public static final int NW=140, WIDE_NW=240, HH=18, PH=16, PR=4, GS=30;
    /** Dynamic node width: FORMULA gets 240px for long expressions */
    public static int nw(GraphNode n) { return n != null && n.type == NodeType.FORMULA ? WIDE_NW : NW; }

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
        new NodeCategory("category.create_schematic_compute.input_ctrl", new NodeType[]{NodeType.KEYBOARD, NodeType.MOUSE_BUTTON, NodeType.MOUSE_JOYSTICK, NodeType.GAMEPAD_JOYSTICK, NodeType.GAMEPAD_BUTTON, NodeType.GAMEPAD_TRIGGER}),
        new NodeCategory("category.create_schematic_compute.input_sensor", new NodeType[]{NodeType.VIEW_ANGLE, NodeType.WORLD_VIEW, NodeType.ATTITUDE, NodeType.FORWARD, NodeType.ACCELERATION, NodeType.VELOCITY, NodeType.POSITION, NodeType.TARGET_OUT}),
        new NodeCategory("category.create_schematic_compute.display", new NodeType[]{NodeType.TEXT, NodeType.DATA, NodeType.IMAGE, NodeType.IMAGE_SEQUENCE}),
        new NodeCategory("category.create_schematic_compute.structure", new NodeType[]{NodeType.ENCAPSULATION}),
        new NodeCategory("category.create_schematic_compute.encap_io", new NodeType[]{NodeType.ENCAP_INPUT, NodeType.ENCAP_OUTPUT}),
    };
    private final java.util.Map<Integer, Boolean> catExpanded = new java.util.HashMap<>();
    private float menuRX, menuRY;
    private java.util.function.Predicate<NodeType> currentFilter = null;

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
    public boolean suppressControls = false; // 覆盖层打开时禁止渲染编辑控件（仅保留背景）

    public void renderNodes(GuiGraphics g, List<GraphNode> nodes, Set<GraphNode> selectedNodes,
                             GraphNode primaryNode, java.util.Set<Integer> editNodeIds,
                             java.util.Map<Integer, io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.EditState> editStates,
                             float camX, float camY, float zoom, int mx, int my,
                             Map<Integer, Boolean> flipflopStates) {
        expandedNodeIds = editNodeIds != null ? editNodeIds : java.util.Collections.emptySet();
        nodeEditStatesById = editStates != null ? editStates : java.util.Collections.emptyMap();
        int w = screen.width, h = screen.height;
        float margin = 50;
        for(var n : nodes) {
            float sx = c2sX.apply(n.x), sy = c2sY.apply(n.y);
            float sw = NW*zoom, nh = (HH+PH*(n.functionalInputs() + n.outputs()))*zoom+4;
            if (sx + sw < -margin || sx > w + margin || sy + nh < -margin || sy > h + margin)
                continue;
            drawNode(g, n, selectedNodes.contains(n), n == primaryNode, expandedNodeIds.contains(n.id), camX, camY, zoom, mx, my, flipflopStates);
        }
    }

    private void drawNode(GuiGraphics g, GraphNode n, boolean selected, boolean isPrimary, boolean editing,
                           float camX, float camY, float zoom, int mx, int my,
                           Map<Integer, Boolean> flipflopStates) {
        float sx = c2sX.apply(n.x), sy = c2sY.apply(n.y);
        int nodeW = nw(n);
        float sw = nodeW*zoom;
        float contentH = (HH+PH*(n.functionalInputs() + n.outputs()))*zoom+4;
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
        // 节点体（暖钢色）
        g.fill((int)sx,(int)sy,(int)(sx+sw),(int)(sy+nh),CN());
        int borderColor = isPrimary ? 0xFFFFAA00 : selected ? 0xFFD4A017 : CB();
        // 第一遍边框
        g.renderOutline((int)sx,(int)sy,(int)sw,(int)nh, borderColor);
        g.renderOutline((int)sx+1,(int)sy+1,(int)sw-2,(int)nh-2, 0xFF2A2822);
        // 节点头部
        g.fill((int)sx+2,(int)sy+2,(int)(sx+sw-2),(int)(sy+HH*zoom),CH());
        var pose = g.pose();
        pose.pushPose();
        pose.translate(sx,sy,0);
        pose.scale(zoom,zoom,1);
        drawStr(g, I18n.get(n.type.getTitle()), 4, 4, CNT());
        // 输入端（仅功能引脚，参数引脚在编辑区内）
        int funcInputs = n.functionalInputs();
        for(int i=0; i<funcInputs; i++) {
            float py = HH+PH*i+PH/2f;
            int r = PR;
            g.fill(-r - 1, (int)(py - r - 1), r + 1, (int)(py + r + 1), CPIB());
            g.fill(-r, (int)(py - r), r, (int)(py + r), CPI());
            String inlbl = n.inputLabel(i);
            drawStr(g, (n.type == NodeType.BUS_OUT || n.type == NodeType.FORMULA || n.type == NodeType.ENCAPSULATION) ? inlbl : I18n.get(inlbl), 10, py-3, CD);
        }
        // 输出端
        for(int i=0; i<n.outputs() && n.type != NodeType.SPEED_CTRL; i++) {
            float py = HH+PH*(funcInputs + i)+PH/2f;
            int r = PR;
            g.fill(nodeW - r - 1, (int)(py - r - 1), nodeW + r + 1, (int)(py + r + 1), CPOB());
            g.fill(nodeW - r, (int)(py - r), nodeW + r, (int)(py + r), CPO());
            String rawOutLbl = n.outputLabel(i);
            String outlbl = (n.type == NodeType.BUS_IN || n.type == NodeType.ENCAPSULATION) ? rawOutLbl : I18n.get(rawOutLbl);
            int olw = Minecraft.getInstance().font.width(outlbl);
            drawStr(g, outlbl, nodeW - olw - 6, py-3, CD);
        }
        // FORMULA 节点：不显示内部文本（多行脚本在节点体中不可读），展开后在编辑区查看
        // 展开指示器（可编辑节点在标题右侧显示 ▶/▼）
        if (n.type == NodeType.FORMULA || n.type.paramNames.length > 0
            || n.type == NodeType.REDSTONE_IN || n.type == NodeType.REDSTONE_OUT
            || n.type == NodeType.PRIVATE_IN || n.type == NodeType.PRIVATE_OUT
            || n.type == NodeType.IMAGE || n.type == NodeType.IMAGE_SEQUENCE
            || n.type == NodeType.TEXT || n.type == NodeType.DATA
            || n.type == NodeType.ENCAPSULATION || n.type == NodeType.ENCAP_INPUT || n.type == NodeType.ENCAP_OUTPUT
            || n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT) {
            drawStr(g, editing ? (n.type == NodeType.ENCAPSULATION ? "§b▶▶" : "§6▼") : (n.type == NodeType.ENCAPSULATION ? "§b▶" : "§7▶"), nodeW - 18, 4, CT);
        }
        // 封装节点体部摘要
        if (n.type == NodeType.ENCAPSULATION && !editing) {
            String summary = java.text.MessageFormat.format(I18n.get("gui.create_schematic_compute.encap_summary"), n.functionalInputs(), n.outputs());
            drawStr(g, "§8" + summary, 4, HH + PH * Math.max(n.functionalInputs(), n.outputs()) + 4, CD);
        }
        // 编辑区（pose 内渲染保证缩放同步，覆盖层在 renderBg 中更高优先级）
        if (editing) {
            int editLocalY = (int)(HH + PH*(n.functionalInputs() + n.outputs()) + 4/zoom);
            var editSt = nodeEditStatesById.get(n.id);
            int editLocalH = io.github.y15173334444.create_schematic_compute.blocks.EditPanel.calcRenderHeight(n, zoom, editSt);
            g.fill(2, editLocalY - 2, nodeW - 2, editLocalY, 0xFF5A4D3A);
            g.fill(2, editLocalY, nodeW - 2, editLocalY + editLocalH, 0xFF2A2822);
            if (editSt != null && !suppressControls) {
                io.github.y15173334444.create_schematic_compute.blocks.EditPanel.renderAt(g, 0, editLocalY, nodeW, n, editSt, zoom, mx, my, flipflopStates);
            }
        }
        pose.popPose();
        // 底部边框重绘（仅编辑区所在边，不覆盖左右连接的连线）
        if (editing) {
            g.fill((int)sx, (int)(sy+nh-1), (int)(sx+sw), (int)(sy+nh), borderColor);
            g.fill((int)sx+1, (int)(sy+nh-2), (int)(sx+sw-1), (int)(sy+nh-1), 0xFF2A2822);
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
        currentFilter = filter;
        int ih=14, ch=16;
        // Determine max columns needed (for width calculation)
        int maxCols = 1;
        for (int ci = 0; ci < CATEGORIES.length; ci++) {
            if (visibleCount(CATEGORIES[ci], filter) == 0) continue;
            if (catExpanded.getOrDefault(ci, false))
                maxCols = Math.max(maxCols, CATEGORIES[ci].columns);
        }
        int colW = 144; // width per column
        int mw = 16 + maxCols * colW;
        // Calculate total height (multi-col categories: ceil(items/cols) rows)
        int totalH = 20;
        for (int ci = 0; ci < CATEGORIES.length; ci++) {
            if (visibleCount(CATEGORIES[ci], filter) == 0) continue;
            totalH += ch;
            if (catExpanded.getOrDefault(ci, false)) {
                int cols = CATEGORIES[ci].columns;
                int items = visibleCount(CATEGORIES[ci], filter);
                totalH += (int)Math.ceil((double)items / cols) * ih;
            }
        }
        menuRX = Math.max(0, Math.min(menuX, screen.width-mw));
        menuRY = Math.max(0, Math.min(menuY, screen.height-totalH));
        g.fill((int)menuRX,(int)menuRY,(int)(menuRX+mw),(int)(menuRY+totalH),0xFF2A2822);
        g.renderOutline((int)menuRX,(int)menuRY,mw,totalH,CSB());
        g.renderOutline((int)menuRX+1,(int)menuRY+1,mw-2,totalH-2,0xFF1A1814);
        drawStr(g, "§l" + I18n.get("gui.create_schematic_compute.nodes"), menuRX+6, menuRY+4, CCT());

        NodeType hovered = null;
        int cy = (int)menuRY + 18;
        for (int ci = 0; ci < CATEGORIES.length; ci++) {
            NodeCategory cat = CATEGORIES[ci];
            int vis = visibleCount(cat, filter);
            if (vis == 0) continue;
            boolean exp = catExpanded.getOrDefault(ci, false);
            int cols = exp ? cat.columns : 1;
            // Category header (full width)
            String title = (exp ? "▼ " : "▶ ") + net.minecraft.client.resources.language.I18n.get(cat.langKey);
            boolean titleHover = mx>=menuRX+2 && mx<=menuRX+mw-2 && my>=cy && my<cy+ch;
            if (titleHover) g.fill((int)menuRX+2, cy, (int)(menuRX+mw-2), (int)(cy+ch), 0xFF3A3428);
            drawStr(g, title, menuRX+6, cy+2, titleHover ? CMH() : CCT());
            cy += ch;
            if (!exp) continue;
            // Items in grid layout
            int itemsPerCol = (int)Math.ceil((double)vis / cols);
            int itemIdx = 0;
            for (var nt : cat.types) {
                if (filter != null && !filter.test(nt)) continue;
                int col = itemIdx / itemsPerCol;
                int row = itemIdx % itemsPerCol;
                int ix = (int)menuRX + 8 + col * colW;
                int iy = cy + row * ih;
                boolean h = mx>=ix && mx<=ix+colW-4 && my>=iy && my<iy+ih;
                if (h) { g.fill(ix, iy, ix+colW-4, iy+ih, 0xFF3A3428); hovered = nt; }
                drawStr(g, I18n.get(nt.displayName), ix + 4, iy + 2, h ? CMH() : CMN());
                itemIdx++;
            }
            cy += itemsPerCol * ih;
        }
        return hovered;
    }

    /** Handle category expand/collapse click. Returns true if consumed. */
    public boolean handleCategoryClick(int mx, int my) {
        int ih=14, ch=16;
        int maxCols = 1;
        for (int ci = 0; ci < CATEGORIES.length; ci++) {
            if (visibleCount(CATEGORIES[ci], currentFilter) == 0) continue;
            if (catExpanded.getOrDefault(ci, false))
                maxCols = Math.max(maxCols, CATEGORIES[ci].columns);
        }
        int colW = 144;
        int mw = 16 + maxCols * colW;
        int cy = (int)menuRY + 18;
        for (int ci = 0; ci < CATEGORIES.length; ci++) {
            int vis = visibleCount(CATEGORIES[ci], currentFilter);
            if (vis == 0) continue;
            if (mx>=menuRX+2 && mx<=menuRX+mw-2 && my>=cy && my<cy+ch) {
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
