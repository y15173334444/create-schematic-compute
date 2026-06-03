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

/**
 * 节点图渲染器 — 处理网格、节点、连线的绘制逻辑
 */
public class NodeRenderer {
    // 颜色常量
    static final int CG=0xFF1E1E24, CGL=0xFF282830, CN=0xFF3C3C44, CH=0xFF4A4A54;
    static final int CB=0xFF5A5A64, CPI=0xFF4499FF, CPO=0xFFFF8844;
    static final int CW=0xFFAAAAAA, CWD=0xFFFFFF88, CT=0xFFFFFFFF, CD=0xFF888888;
    static final int CSN=0xFFFF6600;

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
        new NodeCategory("category.create_schematic_compute.math", new NodeType[]{NodeType.ADD, NodeType.SUB, NodeType.MUL, NodeType.DIV, NodeType.MOD}),
        new NodeCategory("category.create_schematic_compute.logic", new NodeType[]{NodeType.GT, NodeType.LT, NodeType.EQ}),
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

    public void renderNodes(GuiGraphics g, List<GraphNode> nodes, GraphNode selectedNode,
                             float camX, float camY, float zoom, int mx, int my) {
        int w = screen.width, h = screen.height;
        float margin = 50; // 视口外扩余量
        for(var n : nodes) {
            float sx = c2sX.apply(n.x), sy = c2sY.apply(n.y);
            float sw = NW*zoom, nh = (HH+PH*(n.type.inputs+n.type.outputs))*zoom+4;
            // 跳过屏幕外的节点（带余量避免连线突然消失）
            if (sx + sw < -margin || sx > w + margin || sy + nh < -margin || sy > h + margin)
                continue;
            drawNode(g, n, n==selectedNode, camX, camY, zoom, mx, my);
        }
    }

    private void drawNode(GuiGraphics g, GraphNode n, boolean selected,
                           float camX, float camY, float zoom, int mx, int my) {
        float sx = c2sX.apply(n.x), sy = c2sY.apply(n.y);
        float sw = NW*zoom, nh = (HH+PH*(n.type.inputs+n.type.outputs))*zoom+4;
        g.fill((int)sx,(int)sy,(int)(sx+sw),(int)(sy+nh),CN);
        g.renderOutline((int)sx,(int)sy,(int)sw,(int)nh, selected ? CSN : CB);
        g.fill((int)sx+1,(int)sy+1,(int)(sx+sw-1),(int)(sy+HH*zoom),CH);
        var pose = g.pose();
        pose.pushPose();
        pose.translate(sx,sy,0);
        pose.scale(zoom,zoom,1);
        drw(g, I18n.get(n.type.getTitle()), 4, 4, CT);
        // 输入端
        for(int i=0; i<n.type.inputs; i++) {
            float py = HH+PH*i+PH/2f;
            g.fill(-PR,(int)(py-PR),PR,(int)(py+PR),CPI);
            drw(g, n.type.inputLabel(i), 10, py-3, CD);
        }
        // 输出端（SPEED_CTRL 内部使用，不显示引脚）
        for(int i=0; i<n.type.outputs && n.type != NodeType.SPEED_CTRL; i++) {
            float py = HH+PH*(n.type.inputs+i)+PH/2f;
            g.fill((int)(NW-PR),(int)(py-PR),(int)(NW+PR),(int)(py+PR),CPO);
            drw(g, n.type.outputLabel(i), NW-30, py-3, CD);
        }
        // 参数
        for(int pi=0; pi<n.type.paramNames.length; pi++) {
            float pv = pi < n.params.length ? n.params[pi] : 0;
            drw(g, "§7"+n.type.paramNames[pi]+"="+String.format("%.2f",pv),
                4, HH+PH*(n.type.inputs+n.type.outputs)+4+pi*10, CD);
        }
        // 信号名
        if(n.type==NodeType.PRIVATE_IN||n.type==NodeType.PRIVATE_OUT) {
            String label = "§7channel=" + (n.signalName.isEmpty() ? "§o<set>" : n.signalName);
            drw(g, label, 4, HH+PH*(n.type.inputs+n.type.outputs)+4, CD);
        }
        // 频率槽位
        if(n.type==NodeType.REDSTONE_IN||n.type==NodeType.REDSTONE_OUT) {
            float fy = HH+PH*(n.type.inputs+n.type.outputs)+4;
            if(n.type.paramNames.length>0) fy += 10*n.type.paramNames.length;
            for(int fi=0; fi<2; fi++) {
                String t = fi==0 ? "§7#1:" : "§7#2:";
                if(n.itemParams!=null && fi<n.itemParams.length && !n.itemParams[fi].isEmpty())
                    t += n.itemParams[fi].getHoverName().getString();
                else t += "§o<empty>";
                drw(g, t, 4, fy+fi*10, CD);
            }
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
        g.fill((int)menuRX,(int)menuRY,(int)(menuRX+mw),(int)(menuRY+totalH),0xFF2A2A30);
        g.renderOutline((int)menuRX,(int)menuRY,mw,totalH,0xFF666666);
        drw(g, "§lNodes", menuRX+4, menuRY+4, CT);

        NodeType hovered = null;
        int cy = (int)menuRY + 18;
        for (int ci = 0; ci < CATEGORIES.length; ci++) {
            NodeCategory cat = CATEGORIES[ci];
            if (visibleCount(cat, filter) == 0) continue;
            boolean exp = catExpanded.getOrDefault(ci, false);
            // 分类标题
            String title = (exp ? "§7▼ " : "§7▶ ") + net.minecraft.client.resources.language.I18n.get(cat.langKey);
            boolean titleHover = mx>=menuRX+2 && mx<=menuRX+mw-2 && my>=cy && my<cy+ch;
            if (titleHover) g.fill((int)menuRX+2, cy, (int)(menuRX+mw-2), (int)(cy+ch), 0xFF3A3A44);
            drw(g, title, menuRX+6, cy+2, titleHover ? CT : 0xFFCCCCCC);
            cy += ch;
            if (!exp) continue;
            // 子节点
            for (var nt : cat.types) {
                if (filter != null && !filter.test(nt)) continue;
                boolean h = mx>=menuRX+2 && mx<=menuRX+mw-2 && my>=cy && my<cy+ih;
                if (h) { g.fill((int)menuRX+12, cy, (int)(menuRX+mw-2), (int)(cy+ih), 0xFF3A3A44); hovered = nt; }
                drw(g, I18n.get(nt.displayName), menuRX+16, cy+2, h ? CT : CD);
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
        // Compile 按钮
        g.fill(4,4,56,20, fb ? 0xFF44AA44 : 0xFF3A3A44);
        g.renderOutline(4,4,52,16,0xFF666666);
        drw(g, fb ? "Compiled!" : "Compile", 8, 6, CT);
        // 关闭按钮
        g.fill(60,4,78,20,0xFF4A3030);
        g.renderOutline(60,4,18,16,0xFF666666);
        drw(g, "X", 64, 6, CT);
        // Run/Stop 按钮
        g.fill(82,4,130,20, running ? 0xFF44AA44 : 0xFF444444);
        g.renderOutline(82,4,48,16,0xFF666666);
        drw(g, running ? "[Stop]" : "[Run]", 84, 6, CT);
        // 环警告
        if(cycleWarning != null) {
            int ww = Minecraft.getInstance().font.width(cycleWarning)+20;
            int cx = width/2;
            g.fill(cx-ww/2,28,cx+ww/2,50,0xCC442222);
            g.renderOutline(cx-ww/2,28,ww,22,0xFFFF4444);
            drw(g, cycleWarning, cx-ww/2+10, 33, 0xFFFFFFFF);
        }
    }

    void drw(GuiGraphics g, String t, float x, float y, int c) {
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
