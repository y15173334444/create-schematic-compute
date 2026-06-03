package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.graph.GraphNode;
import com.example.create_schematic_compute.graph.NodeConnection;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
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
    }

    private final Host host;
    public final NodeRenderer renderer;
    public final EditPanel editPanel;
    private Predicate<NodeType> nodeFilter;

    // 编辑状态
    public float camX=0, camY=0, zoom=1f;
    public GraphNode draggingNode=null, selectedNode=null;
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

    public GraphEditor(Host host, Screen screen) {
        this.host = host;
        this.renderer = new NodeRenderer(this::c2sX, this::c2sY, screen);
        this.editPanel = new EditPanel(screen);
    }

    public void setNodeFilter(Predicate<NodeType> filter) { this.nodeFilter = filter; }

    // 坐标转换
    public float c2sX(float cx) { Screen s = host.asScreen(); return s.width/2f+(cx+camX)*zoom; }
    public float c2sY(float cy) { Screen s = host.asScreen(); return s.height/2f+(cy+camY)*zoom; }
    public float s2cX(double sx) { Screen s = host.asScreen(); return(float)((sx-s.width/2f)/zoom-camX); }
    public float s2cY(double sy) { Screen s = host.asScreen(); return(float)((sy-s.height/2f)/zoom-camY); }

    public void renderBg(GuiGraphics g, int mx, int my) {
        var graph = host.getGraph();
        renderer.renderGrid(g, camX, camY, zoom, host.asScreen().width, host.asScreen().height);
        renderer.renderConnections(g, graph, camX, camY, zoom);
        if(draggingWire) renderer.renderDraggingWire(g, graph, wireFromNode, wireFromPin, wireEndX, wireEndY, camX, camY, zoom);
        renderer.renderNodes(g, graph.nodes, selectedNode, camX, camY, zoom, mx, my);
        renderer.renderButtons(g, true, host.isRunning(), cycleWarning, saveFeedbackUntil, host.asScreen().width);
        if(showMenu) { selectedMenuType = renderer.renderAddNodeMenu(g, menuX, menuY, mx, my, nodeFilter); }
        if(editPanel.isOpen()) editPanel.render(g, host.asScreen().width, host.asScreen().height, mx, my);
    }

    public boolean mouseClicked(double mx, double my, int btn) {
        var graph = host.getGraph();
        if(btn==0){
            if(mx>=4&&mx<=56&&my>=4&&my<=20){recompile(graph);return true;}
            if(mx>=60&&mx<=78&&my>=4&&my<=20){host.asScreen().onClose();return true;}
            if(mx>=82&&mx<=130&&my>=4&&my<=20){
                boolean ws=!host.isRunning();
                if(ws && graph.hasCycles()){cycleWarning="! Cycle detected!";return true;}
                cycleWarning=null;
                host.saveGraph();
                host.toggleRunning(ws);
                return true;
            }
        }
        if(editPanel.handleFreqSlotClick(mx, my)) return true;
        if(editPanel.handleHotbarClick(mx, my)) return true;
        if(editPanel.mouseClicked(mx, my, btn)) return true;
        if(showMenu&&btn==0){
            if(renderer.handleCategoryClick((int)mx, (int)my)) return true;
            if(selectedMenuType!=null)graph.addNode(selectedMenuType,s2cX(mx),s2cY(my));showMenu=false;return true;}
        if(btn==1){
            var h=hitNode(mx,my);if(h!=null){graph.removeNode(h.id);selectedNode=null;editPanel.close();return true;}
            var hc=hitConn(mx,my);if(hc!=null){graph.removeConnection(hc.fromId,hc.fromPin,hc.toId,hc.toPin);return true;}
            menuX=(float)mx; menuY=(float)my; showMenu=true; return true;
        }
        if(btn==0){
            showMenu=false;
            for(var node:graph.nodes){if(node.type==NodeType.SPEED_CTRL)continue;float sx=c2sX(node.x),sy=c2sY(node.y);for(int i=0;i<node.type.outputs;i++){float py=sy+HH*zoom+PH*zoom*(node.type.inputs+i)+PH*zoom/2f;if(Math.abs(mx-(sx+NW*zoom))<8&&Math.abs(my-py)<PH*zoom/2f+2){draggingWire=true;wireFromNode=node.id;wireFromPin=i;wireEndX=s2cX(mx);wireEndY=s2cY(my);return true;}}}
            var hit=hitNode(mx,my);if(hit!=null){float sy=c2sY(hit.y);if(my>=sy&&my<=sy+HH*zoom+4){draggingNode=hit;dragOffX=hit.x-s2cX(mx);dragOffY=hit.y-s2cY(my);}selectedNode=hit;if(shouldOpenPanel(hit))editPanel.open(hit);return true;}
            selectedNode=null; editPanel.close(); panning=true; panLastX=(float)mx; panLastY=(float)my;
        }
        return false;
    }

    /** 子类可重写定义哪些节点左键打开编辑面板 */
    protected boolean shouldOpenPanel(GraphNode node) {
        return node.type.paramNames.length > 0 || node.type == NodeType.REDSTONE_IN
            || node.type == NodeType.REDSTONE_OUT || node.type == NodeType.PRIVATE_IN
            || node.type == NodeType.PRIVATE_OUT || node.type == NodeType.PID_POWER;
    }

    public void mouseReleased(double mx, double my, int btn) {
        var graph = host.getGraph();
        if(btn==0&&draggingWire){for(var node:graph.nodes){float sx=c2sX(node.x),sy=c2sY(node.y);for(int i=0;i<node.type.inputs;i++){float py=sy+HH*zoom+PH*zoom*i+PH*zoom/2f;if(Math.abs(mx-sx)<8&&Math.abs(my-py)<PH*zoom/2f+2&&wireFromNode!=node.id)graph.addConnection(wireFromNode,wireFromPin,node.id,i);}}draggingWire=false;}
        if(btn==0&&draggingNode!=null)draggingNode=null;if(btn==0&&panning)panning=false;
    }
    public void mouseMoved(double mx, double my) {
        if(panning){camX+=(float)(mx-panLastX)/zoom;camY+=(float)(my-panLastY)/zoom;panLastX=(float)mx;panLastY=(float)my;}
        if(draggingNode!=null){draggingNode.x=s2cX(mx)+dragOffX;draggingNode.y=s2cY(my)+dragOffY;}if(draggingWire){wireEndX=s2cX(mx);wireEndY=s2cY(my);}
    }
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        float oz=zoom; zoom*=(sy>0)?1.12f:(1f/1.12f); zoom=Math.max(0.25f,Math.min(4f,zoom));
        camX+=(mx-host.asScreen().width/2f)*(1f/zoom-1f/oz); camY+=(my-host.asScreen().height/2f)*(1f/zoom-1f/oz); return true;
    }
    public boolean keyPressed(int key, int sc, int mod) {
        var graph = host.getGraph();
        if(editPanel.keyPressed(key,sc,mod)) return true;
        if(key==68&&net.minecraft.client.gui.screens.Screen.hasControlDown()&&selectedNode!=null){
            var dup=graph.addNode(selectedNode.type,selectedNode.x+30,selectedNode.y+30);
            System.arraycopy(selectedNode.params,0,dup.params,0,Math.min(selectedNode.params.length,dup.params.length));
            if(selectedNode.itemParams!=null)dup.itemParams=selectedNode.itemParams.clone();
            if(selectedNode.signalName!=null)dup.signalName=selectedNode.signalName;
            return true;
        }
        return false;
    }
    public boolean charTyped(char ch, int mod) { return editPanel.charTyped(ch, mod); }

    private void recompile(NodeGraph graph) {
        cycleWarning=null; host.saveGraph();
        host.toggleRunning(false);
    }

    private GraphNode hitNode(double mx, double my) {
        var graph = host.getGraph();
        for(int i=graph.nodes.size()-1;i>=0;i--){
            var n=graph.nodes.get(i);
            float sx=c2sX(n.x), sy=c2sY(n.y), sw=NW*zoom, nh=(HH+PH*(n.type.inputs+n.type.outputs))*zoom+4;
            if(mx>=sx&&mx<=sx+sw&&my>=sy&&my<=sy+nh) return n;
        }
        return null;
    }
    private NodeConnection hitConn(double mx, double my) {
        var graph = host.getGraph();
        for(NodeConnection c:graph.connections){
            GraphNode fn=graph.findNode(c.fromId), tn=graph.findNode(c.toId);
            if(fn==null||tn==null)continue;
            float fx=c2sX(fn.x+NW), fy=c2sY(fn.y+HH+PH*(fn.type.inputs+c.fromPin)+PH/2f);
            float tx=c2sX(tn.x), ty=c2sY(tn.y+HH+PH*c.toPin+PH/2f);
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
            if(minDist<12) return c;
        }
        return null;
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
