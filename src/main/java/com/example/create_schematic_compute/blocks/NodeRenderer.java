package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.graph.GraphNode;
import com.example.create_schematic_compute.graph.NodeConnection;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Set;

/**
 * 节点图渲染器 — 处理网格、节点、连线的绘制逻辑
 */
public class NodeRenderer {
    // 颜色常量（可配置，通过主题切换）
    static int CG=0xFF1F1E1A, CGL=0xFF2C2A24, CN=0xFF3A3832, CH=0xFF4A3F28;
    static int CB=0xFF5A4D3A, CPI=0xFFD4A017, CPO=0xFFB87333;
    static int CW=0xFFC5962B, CWD=0xFFFFDD55, CT=0xFFFFFFFF, CD=0xFF888888;
    static int CMN=0xFF888888, CMH=0xFFFFDD77;
    static int CNT=0xFFFFAA00, CCT=0xFFFFAA00, CSB=0xFF8B7533;
    static int CPIB=0xFF8B6914, CPOB=0xFF8A4A22; // 引脚边框

    static final int _NUM_COLORS = 16;

    static final int[][] THEMES = {
        // 16色: CG,CGL,CN,CH,CB,CPI,CPO,CW,CWD,CMN,CMH,CNT,CCT,CSB,CPIB,CPOB
        {0xFF1F1E1A,0xFF2C2A24,0xFF3A3832,0xFF4A3F28,0xFF5A4D3A,0xFFD4A017,0xFFB87333,0xFFC5962B,0xFFFFDD55,0xFF888888,0xFFFFDD77,0xFFFFAA00,0xFFFFAA00,CSB,0xFF8B6914,0xFF8A4A22},
        {0xFF0A1020,0xFF152040,0xFF1A2A4A,0xFF2A3A5A,0xFF3A5A7A,0xFF44AAFF,0xFFFFAA44,0xFF66BBFF,0xFFFFFF88,0xFF88AACC,0xFFFFDD77,0xFFFFDD77,0xFF88AACC,0xFF6688AA,0xFF2266AA,0xFFAA6622},
        {0xFF0A0A0A,0xFF1A1A1A,0xFF2A2A2A,0xFF3A3A3A,0xFF555555,0xFF00FF88,0xFFFF4466,0xFF888888,0xFFFFFF88,0xFFAAAAAA,0xFFCCCCCC,0xFFCCCCCC,0xFF888888,0xFF666666,0xFF444444,0xFF444444},
        {0xFF1E1410,0xFF2A1C14,0xFF3A2820,0xFF4A3428,0xFF5A4438,0xFFFF8844,0xFFAA6633,0xFFDD8844,0xFFFFCC66,0xFFAA8866,0xFFFFCC77,0xFFFFCC77,0xFFAA8866,0xFF8B6B53,0xFF6B4A33,0xFF6B3A23},
    };
    static int currentTheme = 0;

    static void applyTheme(int index) {
        if (index < 0 || index >= THEMES.length) index = 0;
        currentTheme = index;
        int[] t = THEMES[index];
        CG=t[0];CGL=t[1];CN=t[2];CH=t[3];CB=t[4];CPI=t[5];CPO=t[6];
        CW=t[7];CWD=t[8];CMN=t[9];CMH=t[10];CNT=t[11];CCT=t[12];CSB=t[13];CPIB=t[14];CPOB=t[15];
    }

    static int[] currentColors() { return new int[]{CG,CGL,CN,CH,CB,CPI,CPO,CW,CWD,CMN,CMH,CNT,CCT,CSB,CPIB,CPOB}; }
    static final int[] DEFAULT_COLORS = {0xFF1F1E1A,0xFF2C2A24,0xFF3A3832,0xFF4A3F28,0xFF5A4D3A,0xFFD4A017,0xFFB87333,0xFFC5962B,0xFFFFDD55,0xFF888888,0xFFFFDD77,0xFFFFAA00,0xFFFFAA00,CSB,0xFF8B6914,0xFF8A4A22};
    static final String[] COLOR_KEYS = {"bg","grid","node","header","border","input","output","wire","drag","menu_text","menu_hover","node_title","cat_text","sys_border","input_border","output_border"};
    static final String[] COLOR_NAMES = {"Background","Grid","Node Body","Node Title BG","Border","Input Pin","Output Pin","Wire","Drag Wire","Menu Text","Menu Hover","Node Title","Category Text","System Border","Input Pin Border","Output Pin Border"};

    static int[] stagingColors = DEFAULT_COLORS.clone();
    static void initStaging() { stagingColors = currentColors(); }

    static void setColors(int[] c) {
        if (c.length < _NUM_COLORS) return;
        CG=c[0];CGL=c[1];CN=c[2];CH=c[3];CB=c[4];CPI=c[5];CPO=c[6];
        CW=c[7];CWD=c[8];CMN=c[9];CMH=c[10];CNT=c[11];CCT=c[12];CSB=c[13];CPIB=c[14];CPOB=c[15];
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

    // 尺寸常量
    static final int NW=140, HH=18, PH=16, PR=4, GS=30;

    // 坐标转换接口
    public interface CoordMapper { float apply(float coord); }
    private final CoordMapper c2sX, c2sY;
    private final net.minecraft.client.gui.screens.Screen screen;

    // 分类菜单
    private record NodeCategory(String langKey, NodeType[] types) {}
    private static final NodeCategory[] CATEGORIES = {
        new NodeCategory("category.create_schematic_compute.values", new NodeType[]{NodeType.CONST, NodeType.REDSTONE_IN, NodeType.PRIVATE_IN}),
        new NodeCategory("category.create_schematic_compute.math_basic", new NodeType[]{NodeType.ADD, NodeType.SUB, NodeType.MUL, NodeType.DIV, NodeType.MOD, NodeType.POW, NodeType.ROOT, NodeType.ABS, NodeType.CEIL, NodeType.FLOOR}),
        new NodeCategory("category.create_schematic_compute.math_advanced", new NodeType[]{NodeType.FORMULA, NodeType.POSE_CONVERT, NodeType.SPLIT, NodeType.INTERP}),
        new NodeCategory("category.create_schematic_compute.logic", new NodeType[]{NodeType.GT, NodeType.LT, NodeType.EQ, NodeType.BOOL}),
        new NodeCategory("category.create_schematic_compute.control", new NodeType[]{NodeType.PID, NodeType.PID_POWER, NodeType.CLAMP, NodeType.MAP}),
        new NodeCategory("category.create_schematic_compute.output", new NodeType[]{NodeType.REDSTONE_OUT, NodeType.PRIVATE_OUT, NodeType.SPEED_CTRL}),
        new NodeCategory("category.create_schematic_compute.sequential", new NodeType[]{NodeType.DELAY, NodeType.LATCH, NodeType.T_FLIPFLOP, NodeType.PULSE_EXTEND, NodeType.LOOP, NodeType.FUSE, NodeType.ACCUMULATOR}),
        new NodeCategory("category.create_schematic_compute.input_ctrl", new NodeType[]{NodeType.KEYBOARD, NodeType.MOUSE_BUTTON, NodeType.MOUSE_JOYSTICK, NodeType.GAMEPAD_JOYSTICK, NodeType.GAMEPAD_BUTTON}),
        new NodeCategory("category.create_schematic_compute.input_sensor", new NodeType[]{NodeType.VIEW_ANGLE, NodeType.WORLD_VIEW, NodeType.ATTITUDE, NodeType.FORWARD}),
        new NodeCategory("category.create_schematic_compute.display", new NodeType[]{NodeType.TEXT, NodeType.DATA, NodeType.IMAGE, NodeType.IMAGE_SEQUENCE}),
    };
    private final java.util.Map<Integer, Boolean> catExpanded = new java.util.HashMap<>();
    private float menuRX, menuRY;
    private java.util.function.Predicate<NodeType> currentFilter = null;

    public NodeRenderer(CoordMapper c2sX, CoordMapper c2sY, net.minecraft.client.gui.screens.Screen screen) {
        this.c2sX = c2sX; this.c2sY = c2sY;
        this.screen = screen;
    }

    public void renderGrid(GuiGraphics g, float camX, float camY, float zoom, int width, int height) {
        g.fill(-10,-10,width+10,height+10,CG);
        float ox=(camX*zoom)%(GS*zoom), oy=(camY*zoom)%(GS*zoom);
        for(float x=width/2f+ox; x<width; x+=GS*zoom) g.fill((int)x,0,(int)x+1,height,CGL);
        for(float y=height/2f+oy; y<height; y+=GS*zoom) g.fill(0,(int)y,width,(int)y+1,CGL);
        for(float x=width/2f+ox; x>=0; x-=GS*zoom) g.fill((int)x,0,(int)x+1,height,CGL);
        for(float y=height/2f+oy; y>=0; y-=GS*zoom) g.fill(0,(int)y,width,(int)y+1,CGL);
    }

    public void renderConnections(GuiGraphics g, NodeGraph graph, float camX, float camY, float zoom) {
        for(NodeConnection c : graph.connections) {
            GraphNode fn = graph.findNode(c.fromId);
            GraphNode tn = graph.findNode(c.toId);
            if(fn==null||tn==null) continue;
            float x1 = c2sX.apply(fn.x+NW), y1 = c2sY.apply(fn.y+HH+PH*(fn.inputs()+c.fromPin)+PH/2f);
            float x2 = c2sX.apply(tn.x), y2 = c2sY.apply(tn.y+HH+PH*c.toPin+PH/2f);
            bezier(g, x1, y1, x2, y2, CW);
        }
    }

    public void renderDraggingWire(GuiGraphics g, NodeGraph graph, int wireFromNode, int wireFromPin,
                                    float wireEndX, float wireEndY, float camX, float camY, float zoom) {
        var fn = graph.findNode(wireFromNode);
        if(fn==null) return;
        float x1 = c2sX.apply(fn.x+NW), y1 = c2sY.apply(fn.y+HH+PH*(fn.inputs()+wireFromPin)+PH/2f);
        float x2 = c2sX.apply(wireEndX), y2 = c2sY.apply(wireEndY);
        bezier(g, x1, y1, x2, y2, CWD);
    }

    // 编辑区高度（像素，本地坐标空间）
    public java.util.Set<Integer> expandedNodeIds = java.util.Collections.emptySet();
    public java.util.Map<Integer, com.example.create_schematic_compute.blocks.GraphEditor.EditState> nodeEditStatesById = java.util.Collections.emptyMap();
    public boolean suppressControls = false; // 覆盖层打开时禁止渲染编辑控件（仅保留背景）

    public void renderNodes(GuiGraphics g, List<GraphNode> nodes, Set<GraphNode> selectedNodes,
                             GraphNode primaryNode, java.util.Set<Integer> editNodeIds,
                             java.util.Map<Integer, com.example.create_schematic_compute.blocks.GraphEditor.EditState> editStates,
                             float camX, float camY, float zoom, int mx, int my) {
        expandedNodeIds = editNodeIds != null ? editNodeIds : java.util.Collections.emptySet();
        nodeEditStatesById = editStates != null ? editStates : java.util.Collections.emptyMap();
        int w = screen.width, h = screen.height;
        float margin = 50;
        for(var n : nodes) {
            float sx = c2sX.apply(n.x), sy = c2sY.apply(n.y);
            float sw = NW*zoom, nh = (HH+PH*(n.inputs()+n.outputs()))*zoom+4;
            if (sx + sw < -margin || sx > w + margin || sy + nh < -margin || sy > h + margin)
                continue;
            drawNode(g, n, selectedNodes.contains(n), n == primaryNode, expandedNodeIds.contains(n.id), camX, camY, zoom, mx, my);
        }
    }

    private void drawNode(GuiGraphics g, GraphNode n, boolean selected, boolean isPrimary, boolean editing,
                           float camX, float camY, float zoom, int mx, int my) {
        float sx = c2sX.apply(n.x), sy = c2sY.apply(n.y);
        float sw = NW*zoom;
        float contentH = (HH+PH*(n.inputs()+n.outputs()))*zoom+4;
        // 编辑模式：各节点独立计算高度
        float extraH = editing ? com.example.create_schematic_compute.blocks.EditPanel.calcRenderHeight(n, zoom) * zoom : 0;
        float nh = contentH + extraH;
        // 节点体（暖钢色）
        // 节点体在编辑区背景
        g.fill((int)sx,(int)sy,(int)(sx+sw),(int)(sy+nh),CN);
        int borderColor = isPrimary ? 0xFFFFAA00 : selected ? 0xFFD4A017 : CB;
        // 第一遍边框
        g.renderOutline((int)sx,(int)sy,(int)sw,(int)nh, borderColor);
        g.renderOutline((int)sx+1,(int)sy+1,(int)sw-2,(int)nh-2, 0xFF2A2822);
        // 节点头部
        g.fill((int)sx+2,(int)sy+2,(int)(sx+sw-2),(int)(sy+HH*zoom),CH);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(sx,sy,0);
        pose.scale(zoom,zoom,1);
        drawStr(g, I18n.get(n.type.getTitle()), 4, 4, CNT);
        // 输入端
        for(int i=0; i<n.inputs(); i++) {
            float py = HH+PH*i+PH/2f;
            int r = PR;
            g.fill(-r - 1, (int)(py - r - 1), r + 1, (int)(py + r + 1), CPIB);
            g.fill(-r, (int)(py - r), r, (int)(py + r), CPI);
            drawStr(g, n.inputLabel(i), 10, py-3, CD);
        }
        // 输出端
        for(int i=0; i<n.outputs() && n.type != NodeType.SPEED_CTRL; i++) {
            float py = HH+PH*(n.inputs()+i)+PH/2f;
            int r = PR;
            g.fill(NW - r - 1, (int)(py - r - 1), NW + r + 1, (int)(py + r + 1), CPOB);
            g.fill(NW - r, (int)(py - r), NW + r, (int)(py + r), CPO);
            drawStr(g, n.type.outputLabel(i), NW-30, py-3, CD);
        }
        // 公式文本显示（FORMULA 节点）
        if (n.type == NodeType.FORMULA) {
            drawStr(g, "§7" + (n.formula.isEmpty() ? "A+B" : n.formula), 4, HH+PH*(n.inputs()+n.outputs())+4, CD);
        }
        // 展开指示器（可编辑节点在标题右侧显示 ▶/▼）
        if (n.type == NodeType.FORMULA || n.type.paramNames.length > 0
            || n.type == NodeType.REDSTONE_IN || n.type == NodeType.REDSTONE_OUT
            || n.type == NodeType.PRIVATE_IN || n.type == NodeType.PRIVATE_OUT || n.type == NodeType.IMAGE || n.type == NodeType.IMAGE_SEQUENCE || n.type == NodeType.TEXT || n.type == NodeType.DATA) {
            drawStr(g, editing ? "§6▼" : "§7▶", NW - 14, 4, CT);
        }
        // 编辑区（pose 内渲染保证缩放同步，覆盖层在 renderBg 中更高优先级）
        if (editing) {
            int editLocalY = (int)(HH + PH*(n.inputs() + n.outputs()) + 4/zoom);
            int editLocalH = com.example.create_schematic_compute.blocks.EditPanel.calcRenderHeight(n, zoom);
            g.fill(2, editLocalY - 2, NW - 2, editLocalY, 0xFF5A4D3A);
            g.fill(2, editLocalY, NW - 2, editLocalY + editLocalH, 0xFF2A2822);
            var editSt = nodeEditStatesById.get(n.id);
            if (editSt != null && !suppressControls) {
                com.example.create_schematic_compute.blocks.EditPanel.renderAt(g, 0, editLocalY, NW, n, editSt, zoom, mx, my);
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
        int mw=160, ih=14, ch=16;
        // 计算总高度（跳过无可见节点的分类）
        int totalH = 20;
        for (int ci = 0; ci < CATEGORIES.length; ci++) {
            if (visibleCount(CATEGORIES[ci], filter) == 0) continue;
            totalH += ch;
            if (catExpanded.getOrDefault(ci, false)) {
                totalH += visibleCount(CATEGORIES[ci], filter) * ih;
            }
        }
        menuRX = Math.max(0, Math.min(menuX, screen.width-mw));
        menuRY = Math.max(0, Math.min(menuY, screen.height-totalH));
        g.fill((int)menuRX,(int)menuRY,(int)(menuRX+mw),(int)(menuRY+totalH),0xFF2A2822);
        g.renderOutline((int)menuRX,(int)menuRY,mw,totalH,CSB);
        g.renderOutline((int)menuRX+1,(int)menuRY+1,mw-2,totalH-2,0xFF1A1814);
        drawStr(g, "§lNodes", menuRX+6, menuRY+4, CCT);

        NodeType hovered = null;
        int cy = (int)menuRY + 18;
        for (int ci = 0; ci < CATEGORIES.length; ci++) {
            NodeCategory cat = CATEGORIES[ci];
            if (visibleCount(cat, filter) == 0) continue;
            boolean exp = catExpanded.getOrDefault(ci, false);
            // 分类标题
            String title = (exp ? "▼ " : "▶ ") + net.minecraft.client.resources.language.I18n.get(cat.langKey);
            boolean titleHover = mx>=menuRX+2 && mx<=menuRX+mw-2 && my>=cy && my<cy+ch;
            if (titleHover) g.fill((int)menuRX+2, cy, (int)(menuRX+mw-2), (int)(cy+ch), 0xFF3A3428);
            drawStr(g, title, menuRX+6, cy+2, titleHover ? CMH : CCT);
            cy += ch;
            if (!exp) continue;
            // 子节点
            for (var nt : cat.types) {
                if (filter != null && !filter.test(nt)) continue;
                boolean h = mx>=menuRX+2 && mx<=menuRX+mw-2 && my>=cy && my<cy+ih;
                if (h) { g.fill((int)menuRX+12, cy, (int)(menuRX+mw-2), (int)(cy+ih), 0xFF3A3428); hovered = nt; }
                drawStr(g, I18n.get(nt.displayName), menuRX+16, cy+2, h ? CMH : CMN);
                cy += ih;
            }
        }
        return hovered;
    }

    /** 处理分类折叠点击，返回 true 表示点击被消费 */
    public boolean handleCategoryClick(int mx, int my) {
        int mw=160, ih=14, ch=16;
        int cy = (int)menuRY + 18;
        for (int ci = 0; ci < CATEGORIES.length; ci++) {
            if (visibleCount(CATEGORIES[ci], currentFilter) == 0) continue;
            if (mx>=menuRX+2 && mx<=menuRX+mw-2 && my>=cy && my<cy+ch) {
                catExpanded.put(ci, !catExpanded.getOrDefault(ci, false));
                return true;
            }
            cy += ch;
            if (!catExpanded.getOrDefault(ci, false)) continue;
            cy += visibleCount(CATEGORIES[ci], currentFilter) * ih;
        }
        return false;
    }

    public void renderButtons(GuiGraphics g, boolean compiled, boolean running, String cycleWarning,
                               long saveFeedbackUntil, boolean gridSnap, int themeIdx, int width) {
        long now = System.currentTimeMillis();
        boolean fb = now < saveFeedbackUntil;
        int btnY = 4, btnH = 18;
        // 关闭按钮（最左）
        int cX = 4, cW = 18;
        g.fill(cX, btnY, cX+cW, btnY+btnH, 0xFF4A3028);
        g.renderOutline(cX, btnY, cW, btnH, 0xFF8B5333);
        g.renderOutline(cX+1, btnY+1, cW-2, btnH-2, 0xFF2A2822);
        drawStr(g, "§cX", cX+4, btnY+4, CT);
        // Compile 按钮
        int cX2 = 26, cW2 = 52;
        g.fill(cX2, btnY, cX2+cW2, btnY+btnH, fb ? 0xFF3A5A2A : 0xFF3A3832);
        g.renderOutline(cX2, btnY, cW2, btnH, CSB);
        g.renderOutline(cX2+1, btnY+1, cW2-2, btnH-2, 0xFF2A2822);
        drawStr(g, fb ? "§aCompiled!" : "§eCompile", cX2+4, btnY+4, CT);
        // Run/Stop 按钮
        int cX3 = 82, cW3 = 48;
        g.fill(cX3, btnY, cX3+cW3, btnY+btnH, running ? 0xFF3A5A2A : 0xFF3A3832);
        g.renderOutline(cX3, btnY, cW3, btnH, CSB);
        g.renderOutline(cX3+1, btnY+1, cW3-2, btnH-2, 0xFF2A2822);
        drawStr(g, running ? "§a[Stop]" : "§e[Run]", cX3+4, btnY+4, CT);
        // 网格吸附按钮
        int cX4 = 134, cW4 = 58;
        g.fill(cX4, btnY, cX4+cW4, btnY+btnH, gridSnap ? 0xFF3A5A2A : 0xFF3A3428);
        g.renderOutline(cX4, btnY, cW4, btnH, CSB);
        g.renderOutline(cX4+1, btnY+1, cW4-2, btnH-2, 0xFF2A2822);
        drawStr(g, (gridSnap ? "§a" : "§7") + net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.grid"), cX4+6, btnY+4, CT);
        // 颜色配置按钮
        int cX5 = 196, cW5 = 54;
        g.fill(cX5, btnY, cX5+cW5, btnY+btnH, 0xFF3A3832);
        g.renderOutline(cX5, btnY, cW5, btnH, CSB);
        g.renderOutline(cX5+1, btnY+1, cW5-2, btnH-2, 0xFF2A2822);
        drawStr(g, "§7" + net.minecraft.client.resources.language.I18n.get("gui.create_schematic_compute.style"), cX5+8, btnY+4, CT);
        // 环警告
        if(cycleWarning != null) {
            int ww = Minecraft.getInstance().font.width(cycleWarning)+20;
            int cx = width/2;
            g.fill(cx-ww/2,28,cx+ww/2,50,0xCC4A2820);
            g.renderOutline(cx-ww/2,28,ww,22,0xFFFF5533);
            drawStr(g, "§c" + cycleWarning, cx-ww/2+10, 33, 0xFFFFFFFF);
        }
    }

    void drawStr(GuiGraphics g, String t, float x, float y, int c) {
        g.drawString(Minecraft.getInstance().font, t, (int)x, (int)y, c, false);
    }

    private void bezier(GuiGraphics g, float x1, float y1, float x2, float y2, int c) {
        float dx = Math.abs(x2-x1)*0.4f;
        float dist = (float)Math.sqrt((x2-x1)*(x2-x1)+(y2-y1)*(y2-y1));
        int steps = Math.max(20, (int)(dist*0.5f));
        float px=x1, py=y1;
        for(int i=1; i<=steps; i++) {
            float t = i/(float)steps, inv = 1-t;
            float nx = inv*inv*inv*x1 + 3*inv*inv*t*(x1+dx) + 3*inv*t*t*(x2-dx) + t*t*t*x2;
            float ny = inv*inv*inv*y1 + 3*inv*inv*t*y1 + 3*inv*t*t*y2 + t*t*t*y2;
            int sx = (int)px, sy = (int)py, ex = (int)nx, ey = (int)ny;
            int segSteps = Math.max(Math.abs(ex-sx), Math.abs(ey-sy));
            for (int j = 0; j <= segSteps; j++) {
                int x = sx + (ex - sx) * j / Math.max(segSteps, 1);
                int y = sy + (ey - sy) * j / Math.max(segSteps, 1);
                g.fill(x, y, x+1, y+1, c);
            }
            px=nx; py=ny;
        }
    }
}
