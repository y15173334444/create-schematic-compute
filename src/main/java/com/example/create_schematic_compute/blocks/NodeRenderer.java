package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.graph.GraphNode;
import com.example.create_schematic_compute.graph.NodeConnection;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Set;

/**
 * 节点图渲染器 — 处理网格、节点、连线的绘制逻辑
 */
public class NodeRenderer {
    // Create 风格颜色常量（暖金属色系：黄铜/铜/钢）
    static final int CG=0xFF1F1E1A, CGL=0xFF2C2A24, CN=0xFF3A3832, CH=0xFF4A3F28;
    static final int CB=0xFF5A4D3A, CPI=0xFFD4A017, CPO=0xFFB87333;
    static final int CW=0xFFC5962B, CWD=0xFFFFDD55, CT=0xFFFFFFFF, CD=0xFF888888;
    static final int CSN=0xFFFFAA00;

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
        new NodeCategory("category.create_schematic_compute.math", new NodeType[]{NodeType.ADD, NodeType.SUB, NodeType.MUL, NodeType.DIV, NodeType.MOD, NodeType.POW, NodeType.ROOT, NodeType.ABS, NodeType.INTERP, NodeType.CEIL, NodeType.FLOOR}),
        new NodeCategory("category.create_schematic_compute.logic", new NodeType[]{NodeType.GT, NodeType.LT, NodeType.EQ, NodeType.BOOL}),
        new NodeCategory("category.create_schematic_compute.control", new NodeType[]{NodeType.PID, NodeType.PID_POWER, NodeType.CLAMP, NodeType.MAP}),
        new NodeCategory("category.create_schematic_compute.output", new NodeType[]{NodeType.REDSTONE_OUT, NodeType.PRIVATE_OUT, NodeType.SPEED_CTRL}),
        new NodeCategory("category.create_schematic_compute.sequential", new NodeType[]{NodeType.DELAY, NodeType.LATCH, NodeType.T_FLIPFLOP, NodeType.PULSE_EXTEND, NodeType.LOOP, NodeType.FUSE}),
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
            float x1 = c2sX.apply(fn.x+NW), y1 = c2sY.apply(fn.y+HH+PH*(fn.type.inputs+c.fromPin)+PH/2f);
            float x2 = c2sX.apply(tn.x), y2 = c2sY.apply(tn.y+HH+PH*c.toPin+PH/2f);
            bezier(g, x1, y1, x2, y2, CW);
        }
    }

    public void renderDraggingWire(GuiGraphics g, NodeGraph graph, int wireFromNode, int wireFromPin,
                                    float wireEndX, float wireEndY, float camX, float camY, float zoom) {
        var fn = graph.findNode(wireFromNode);
        if(fn==null) return;
        float x1 = c2sX.apply(fn.x+NW), y1 = c2sY.apply(fn.y+HH+PH*(fn.type.inputs+wireFromPin)+PH/2f);
        float x2 = c2sX.apply(wireEndX), y2 = c2sY.apply(wireEndY);
        bezier(g, x1, y1, x2, y2, CWD);
    }

    // 编辑区高度（像素，本地坐标空间）
    private int editExtraHeight = 0;
    // 编辑区屏幕边界（供 EditPanel 定位控件）
    public float editScreenX, editScreenY, editScreenW, editScreenH;

    /** 计算节点编辑区高度 */
    public static int calcEditHeight(GraphNode n) {
        if (n == null) return 0;
        int h = 0;
        if (n.type.paramNames.length > 0 && n.type != NodeType.BOOL) h += n.params.length * 18 + 4;
        if (n.type == NodeType.BOOL && n.params.length > 0) h += 20;
        if (n.type == NodeType.REDSTONE_IN || n.type == NodeType.REDSTONE_OUT) h += 46;
        return h > 0 ? h + 10 : 0;
    }

    public void renderNodes(GuiGraphics g, List<GraphNode> nodes, Set<GraphNode> selectedNodes,
                             GraphNode primaryNode, int editExtra,
                             float camX, float camY, float zoom, int mx, int my) {
        editExtraHeight = editExtra;
        int w = screen.width, h = screen.height;
        float margin = 50;
        for(var n : nodes) {
            float sx = c2sX.apply(n.x), sy = c2sY.apply(n.y);
            float sw = NW*zoom, nh = (HH+PH*(n.type.inputs+n.type.outputs))*zoom+4;
            if (sx + sw < -margin || sx > w + margin || sy + nh < -margin || sy > h + margin)
                continue;
            drawNode(g, n, selectedNodes.contains(n), n == primaryNode, camX, camY, zoom, mx, my);
        }
    }

    private void drawNode(GuiGraphics g, GraphNode n, boolean selected, boolean isPrimary,
                           float camX, float camY, float zoom, int mx, int my) {
        float sx = c2sX.apply(n.x), sy = c2sY.apply(n.y);
        float sw = NW*zoom;
        float contentH = (HH+PH*(n.type.inputs+n.type.outputs))*zoom+4;
        // 编辑模式：节点向下展开（与节点共用缩放）
        boolean editing = isPrimary && editExtraHeight > 0;
        float extraH = editing ? editExtraHeight * zoom : 0;
        float nh = contentH + extraH;
        // 保存编辑区屏幕坐标
        if (editing) {
            editScreenX = sx + 2; editScreenY = sy + contentH + 1;
            editScreenW = sw - 4; editScreenH = extraH - 1;
        }
        // 节点体（暖钢色）
        g.fill((int)sx,(int)sy,(int)(sx+sw),(int)(sy+nh),CN);
        // Create 风格双层边框
        int borderColor = isPrimary ? 0xFFFFAA00 : selected ? 0xFFD4A017 : CB;
        g.renderOutline((int)sx,(int)sy,(int)sw,(int)nh, borderColor);
        g.renderOutline((int)sx+1,(int)sy+1,(int)sw-2,(int)nh-2, 0xFF2A2822);
        // 节点头部
        g.fill((int)sx+2,(int)sy+2,(int)(sx+sw-2),(int)(sy+HH*zoom),CH);
        // 编辑区背景 + 分隔线
        if (editing) {
            int sepY = (int)(sy + contentH);
            g.fill((int)sx+2, sepY, (int)(sx+sw-2), sepY + 1, 0xFF5A4D3A);
            g.fill((int)sx+2, sepY + 1, (int)(sx+sw-2), (int)(sy+nh-1), 0xFF2A2822);
        }
        var pose = g.pose();
        pose.pushPose();
        pose.translate(sx,sy,0);
        pose.scale(zoom,zoom,1);
        drawStr(g, "§6" + I18n.get(n.type.getTitle()), 4, 4, CT);
        // 输入端
        for(int i=0; i<n.type.inputs; i++) {
            float py = HH+PH*i+PH/2f;
            int r = PR;
            g.fill(-r - 1, (int)(py - r - 1), r + 1, (int)(py + r + 1), 0xFF8B6914);
            g.fill(-r, (int)(py - r), r, (int)(py + r), CPI);
            drawStr(g, n.type.inputLabel(i), 10, py-3, CD);
        }
        // 输出端
        for(int i=0; i<n.type.outputs && n.type != NodeType.SPEED_CTRL; i++) {
            float py = HH+PH*(n.type.inputs+i)+PH/2f;
            int r = PR;
            g.fill(NW - r - 1, (int)(py - r - 1), NW + r + 1, (int)(py + r + 1), 0xFF8A4A22);
            g.fill(NW - r, (int)(py - r), NW + r, (int)(py + r), CPO);
            drawStr(g, n.type.outputLabel(i), NW-30, py-3, CD);
        }
        // 展开指示器（可编辑节点在标题右侧显示 ▶/▼）
        if (n.type.paramNames.length > 0 || n.type == NodeType.REDSTONE_IN || n.type == NodeType.REDSTONE_OUT
            || n.type == NodeType.PRIVATE_IN || n.type == NodeType.PRIVATE_OUT) {
            drawStr(g, editing ? "§6▼" : "§7▶", NW - 14, 4, CT);
        }
        pose.popPose();
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
        g.renderOutline((int)menuRX,(int)menuRY,mw,totalH,0xFF8B7533);
        g.renderOutline((int)menuRX+1,(int)menuRY+1,mw-2,totalH-2,0xFF1A1814);
        drawStr(g, "§6§lNodes", menuRX+6, menuRY+4, CT);

        NodeType hovered = null;
        int cy = (int)menuRY + 18;
        for (int ci = 0; ci < CATEGORIES.length; ci++) {
            NodeCategory cat = CATEGORIES[ci];
            if (visibleCount(cat, filter) == 0) continue;
            boolean exp = catExpanded.getOrDefault(ci, false);
            // 分类标题
            String title = (exp ? "§6▼ " : "§6▶ ") + net.minecraft.client.resources.language.I18n.get(cat.langKey);
            boolean titleHover = mx>=menuRX+2 && mx<=menuRX+mw-2 && my>=cy && my<cy+ch;
            if (titleHover) g.fill((int)menuRX+2, cy, (int)(menuRX+mw-2), (int)(cy+ch), 0xFF3A3428);
            drawStr(g, title, menuRX+6, cy+2, titleHover ? 0xFFFFDD77 : 0xFFCCCCCC);
            cy += ch;
            if (!exp) continue;
            // 子节点
            for (var nt : cat.types) {
                if (filter != null && !filter.test(nt)) continue;
                boolean h = mx>=menuRX+2 && mx<=menuRX+mw-2 && my>=cy && my<cy+ih;
                if (h) { g.fill((int)menuRX+12, cy, (int)(menuRX+mw-2), (int)(cy+ih), 0xFF3A3428); hovered = nt; }
                drawStr(g, "§7" + I18n.get(nt.displayName), menuRX+16, cy+2, h ? 0xFFFFDD77 : CD);
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
                               long saveFeedbackUntil, int width) {
        long now = System.currentTimeMillis();
        boolean fb = now < saveFeedbackUntil;
        // Create 风格按钮 — 双层边框（青铜外框 + 暗色内框）
        int btnY = 4, btnH = 18;
        // Compile 按钮
        int cX = 4, cW = 52;
        g.fill(cX, btnY, cX+cW, btnY+btnH, fb ? 0xFF3A5A2A : 0xFF3A3832);
        g.renderOutline(cX, btnY, cW, btnH, 0xFF8B7533);
        g.renderOutline(cX+1, btnY+1, cW-2, btnH-2, 0xFF2A2822);
        drawStr(g, fb ? "§aCompiled!" : "§eCompile", cX+4, btnY+4, CT);
        // 关闭按钮
        int cX2 = 60, cW2 = 18;
        g.fill(cX2, btnY, cX2+cW2, btnY+btnH, 0xFF4A3028);
        g.renderOutline(cX2, btnY, cW2, btnH, 0xFF8B5333);
        g.renderOutline(cX2+1, btnY+1, cW2-2, btnH-2, 0xFF2A2822);
        drawStr(g, "§cX", cX2+4, btnY+4, CT);
        // Run/Stop 按钮
        int cX3 = 82, cW3 = 48;
        g.fill(cX3, btnY, cX3+cW3, btnY+btnH, running ? 0xFF3A5A2A : 0xFF3A3832);
        g.renderOutline(cX3, btnY, cW3, btnH, running ? 0xFF5A8A3A : 0xFF8B7533);
        g.renderOutline(cX3+1, btnY+1, cW3-2, btnH-2, 0xFF2A2822);
        drawStr(g, running ? "§a[Stop]" : "§e[Run]", cX3+4, btnY+4, CT);
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
            int minX = (int)Math.min(px,nx), minY = (int)Math.min(py,ny);
            int maxX = (int)Math.max(px,nx), maxY = (int)Math.max(py,ny);
            g.fill(minX,minY,maxX+1,maxY+1,c);
            px=nx; py=ny;
        }
    }
}
