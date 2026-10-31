package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.client.EditorTheme;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeConnection;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.SpatialIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 节点图渲染器 — 处理网格、节点、连线的绘制逻辑
 */
public class NodeRenderer {
    /** 独立 logger：不引用 (root) 包的 @Mod 类——其静态初始化依赖 MC 注册表，
     *  而 LoggerFactory 不依赖注册表，headless/JUnit 下也能安全完成类初始化。日志名为本类 FQCN。
     *  Same-name standalone logger: never references the @Mod class — its static init needs
     *  the Minecraft registries, while LoggerFactory does not, so class init stays safe in
     *  plain JUnit too. The log name is this class's FQCN. */
    private static final Logger LOGGER = LoggerFactory.getLogger(NodeRenderer.class);

    // ── 调色板门面（状态已抽至 client.EditorTheme —— 见 docs/file-topology-split-review-2026-10-11.md §三 P2）──
    //    门面冻结：下列全部静态签名（公共 + 包级）与原版逐字一致，存量引用零改动；实现改为
    //    逐个委托到活动主题实例，颜色**每次实时取值**——修复复审 P2-23 的 static final 固化。
    // ── Palette facade (state extracted to client.EditorTheme — topology review §三 P2) ──
    //    Facade frozen: every static signature below (public + package-private) is verbatim
    //    identical to the original, so existing references stay untouched; each one now delegates
    //    to the active theme instance and colours are fetched **live** — fixing the P2-23
    //    static-final capture.

    /** 槽位总数别名（EditorSettings 屏使用）/ slot-count alias (used by the editor-settings screens). */
    static final int _NUM_COLORS = EditorTheme.NUM_COLORS;

    // Text and dim colors are constant across all themes（跨包共用：client.* 的 NBS/像素编辑器）
    public static final int CT=0xFFFFFFFF, CD=0xFF888888;

    // Inline accessors — 委托到 EditorTheme（语义名映射见 EditorTheme 类头）/ delegate to
    // EditorTheme (the semantic-name mapping table is in EditorTheme's class header)
    public static int CG() { return EditorTheme.get().bg(); }
    public static int CGL() { return EditorTheme.get().grid(); }
    static int CN() { return EditorTheme.get().node(); }
    static int CH() { return EditorTheme.get().header(); }
    public static int CB() { return EditorTheme.get().border(); }   // 包归位放宽：AmplifierComputerScreen 跨包用 / widened after re-homing
    static int CPI() { return EditorTheme.get().inputPin(); }
    static int CPO() { return EditorTheme.get().outputPin(); }
    static int CW() { return EditorTheme.get().wire(); }
    static int CWA() { return EditorTheme.get().wireAudio(); }
    static int CAP() { return EditorTheme.get().pinAudio(); }
    static int CAPB() { return EditorTheme.get().pinAudioBorder(); }
    static int CWD() { return EditorTheme.get().drag(); }
    static int CMN() { return EditorTheme.get().menuText(); }
    static int CMH() { return EditorTheme.get().menuHover(); }
    static int CNT() { return EditorTheme.get().nodeTitle(); }
    static int CCT() { return EditorTheme.get().catText(); }
    static int CPIB() { return EditorTheme.get().inputPinBorder(); }
    static int CPOB() { return EditorTheme.get().outputPinBorder(); }
    // GUI-chrome colors (used across editor and peripheral screens; public for
    // cross-package consumers in client.*). CG/CGL join them for the NBS editor's
    // canvas backdrop and grid lines / CG/CGL 同放行：NBS 编辑器画布底与网格线要用。
    public static int CSB() { return EditorTheme.get().sysBorder(); }
    public static int PBG() { return EditorTheme.get().panelBg(); }    // panel_bg    面板底
    public static int PHT() { return EditorTheme.get().panelHeader(); }// panel_header 面板标题带
    public static int PBR() { return EditorTheme.get().panelBorder(); }// panel_border 面板边框
    public static int PINS() { return EditorTheme.get().insetBg(); }   // inset_bg    内凹井底
    public static int ACC() { return EditorTheme.get().accent(); }     // accent      强调色
    public static int ERR() { return EditorTheme.get().error(); }      // error       警示/错误
    public static int HOV() { return EditorTheme.get().hover(); }      // hover       悬停高亮

    /** 连接态暗化系数：编辑区频段引脚连接后填充变暗的明暗变换。
     *  Connected-state darkening factor for edit-panel band-pin fills. */
    static final float CONNECTED_DIM = 0.55f;

    /** 连接态暗化：对配置色做明暗变换（RGB × {@link #CONNECTED_DIM}，alpha 与色相不动）——
     *  主题自适应；旧硬编码暗橄榄（0xFF666644）退役，自定义配色不再脱节。
     *  Connected-state darken: scale the configured colour's brightness (RGB only), hue and
     *  alpha kept — theme-adaptive; the old hardcoded khaki is retired. */
    static int dimColor(int argb) {
        int r = (int) (((argb >> 16) & 0xFF) * CONNECTED_DIM);
        int g = (int) (((argb >> 8) & 0xFF) * CONNECTED_DIM);
        int b = (int) ((argb & 0xFF) * CONNECTED_DIM);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** 主题色的半透明变体（实现移至 {@link EditorTheme#withAlpha}，签名冻结）。
     *  Theme colour with a given alpha (implementation moved to {@link EditorTheme#withAlpha};
     *  signature frozen). */
    public static int withAlpha(int color, int alpha) {
        return EditorTheme.withAlpha(color, alpha);
    }

    /** 出厂默认色板别名（EditorSettings 屏使用）/ factory-default palette alias (used by the editor-settings screens). */
    static final int[] DEFAULT_COLORS = EditorTheme.DEFAULT_COLORS;

    /** 槽位语义名别名（颜色设置页 i18n 键用）/ slot-key alias (used by the colours settings tab). */
    static final String[] COLOR_KEYS = EditorTheme.COLOR_KEYS;

    // 暂存色（编辑器设置「颜色」页的工作台副本）留在门面：EditorSettingsScreen 直接对它赋值，
    // 它是设置对话框的会话态而非主题权威态——权威活色板在 EditorTheme。
    // Staging palette (the colours-tab workbench copy) stays on the facade: EditorSettingsScreen
    // assigns this field directly; it is dialog-session state, not the authoritative theme —
    // the authoritative live palette lives in EditorTheme.
    static int[] stagingColors = DEFAULT_COLORS.clone();
    static void initStaging() { stagingColors = currentColors(); }

    static void setColors(int[] c) {
        EditorTheme.get().setColors(c);
    }

    /** 当前色板快照（委托）/ snapshot of the live palette (delegates). */
    static int[] currentColors() { return EditorTheme.get().snapshot(); }

    /** 应用预设主题（委托）/ apply a preset theme (delegates). */
    static void applyTheme(int index) { EditorTheme.get().applyPreset(index); }

    /** 从配置文件加载颜色（委托）/ load colours from the config file (delegates). */
    static void loadColorConfig() { EditorTheme.loadColorConfig(); }

    /** 保存颜色到配置文件（委托）/ save colours to the config file (delegates). */
    static void saveColorConfig() { EditorTheme.saveColorConfig(); }

    /** 保存网格吸附状态（保留已有颜色属性） */
    static void saveGridSnap(boolean on) {
        try {
            var path = java.nio.file.Path.of("config", "create_schematic_compute-client.properties");
            var props = new java.util.Properties();
            if (java.nio.file.Files.exists(path))
                try (var is = java.nio.file.Files.newInputStream(path)) { props.load(is); }
            props.setProperty("grid_snap", String.valueOf(on));
            try (var os = java.nio.file.Files.newOutputStream(path)) { props.store(os, null); }
        } catch (Exception e) { LOGGER.error("Failed to save grid snap setting", e); }
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
        } catch (Exception e) { LOGGER.error("Failed to load grid snap setting", e); }
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
        } catch (Exception e) { LOGGER.error("Failed to save toolbar position", e); }
    }

    static boolean loadToolbarBottom() {
        try {
            var path = java.nio.file.Path.of("config", "create_schematic_compute-client.properties");
            if (java.nio.file.Files.exists(path)) {
                var props = new java.util.Properties();
                try (var is = java.nio.file.Files.newInputStream(path)) { props.load(is); }
                return Boolean.parseBoolean(props.getProperty("toolbar_bottom", "false"));
            }
        } catch (Exception e) { LOGGER.error("Failed to load toolbar position", e); }
        return false;
    }

    // 尺寸常量
    public static final int NW=140, WIDE_NW=240, HH=18, PH=16, PR=4, GS=30;
    /** Dynamic node width: FORMULA gets 240px for long expressions, COMMENT uses its own width */
    public static int nw(GraphNode n) {
        if (n == null) return NW;
        // 委托到 SpatialIndex.nwStatic（节点几何唯一权威）——渲染与命中索引天然同步。
        // Delegate to SpatialIndex.nwStatic (the single geometry source) so rendering and
        // the hit index can never drift apart.
        return Math.round(SpatialIndex.nwStatic(n));
    }
    /** Node body height in graph-space pixels (excluding edit panel expansion). */
    public static float nh(GraphNode n) {
        if (n == null) return HH + PH * 2;
        return SpatialIndex.nhStatic(n);
    }

    // 坐标转换接口
    public interface CoordMapper { float apply(float coord); }
    private final CoordMapper c2sX, c2sY;
    private final net.minecraft.client.gui.screens.Screen screen;


    /** 添加节点菜单（已拆至 NodeAddMenu，docs/gui-decomposition-plan.md 步骤 5；本类保留门面）。
     *  Add-node menu (extracted to NodeAddMenu, roadmap step 5; this class keeps the facade). */
    private final NodeAddMenu addMenu;

    /** 门面：本帧添加菜单悬停的自定义节点 id（CUSTOM 哨兵选中时非 null）。
     *  Facade: this frame's add-menu hovered custom node id (non-null when the CUSTOM
     *  sentinel is the selection). */
    public String lastCustomMenuId() { return addMenu.lastCustomMenuId(); }

    /** D5 名单宿主键下发（菜单 CUSTOM 段按宿主名单过滤；见 NodeAddMenu.setRosterHost）。
     *  Hand the D5 roster host key down (the menu's CUSTOM section filters by the host
     *  roster; see NodeAddMenu.setRosterHost). */
    public void setRosterHost(String hostKey) { addMenu.setRosterHost(hostKey); }
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
                                    float wireEndX, float wireEndY, float camX, float camY, float zoom,
                                    boolean targetAudio) {
        wireRenderer.renderDraggingWire(g, graph, wireFromNode, wireFromPin, wireEndX, wireEndY, camX, camY, zoom, targetAudio);
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
    public java.util.Map<Integer, io.github.y15173334444.create_schematic_compute.client.editor.GraphEditor.EditState> nodeEditStatesById = java.util.Collections.emptyMap();
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
                                    java.util.Map<Integer, io.github.y15173334444.create_schematic_compute.client.editor.GraphEditor.EditState> editStates,
                                    float camX, float camY, float zoom, int mx, int my,
                                    Map<Integer, Boolean> flipflopStates,
                                    Map<Integer, String> lockedNodes) {
        commentRenderer.renderCommentNodes(g, nodes, selectedNodes, primaryNode, editNodeIds, editStates,
            camX, camY, zoom, mx, my, flipflopStates, lockedNodes);
    }

    public void renderNodes(GuiGraphics g, NodeGraph graph, List<GraphNode> nodes, Set<GraphNode> selectedNodes,
                             GraphNode primaryNode, java.util.Set<Integer> editNodeIds,
                             java.util.Map<Integer, io.github.y15173334444.create_schematic_compute.client.editor.GraphEditor.EditState> editStates,
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
            drawNode(g, n, graph, isSelectedById(selectedNodes, n), isPrimaryById(primaryNode, n), expanded, camX, camY, zoom, mx, my, flipflopStates, lockedNodes);
        }
    }

    /** 渲染 ▶/▼ 展开指示器的「无参数面板」类型表（拓扑 P2 表驱动化）：这些类型没有
     *  paramNames 却仍有展开面板；与 {@link #hasExpandIndicator(NodeType)} 的 paramNames>0
     *  兜底共同构成**单一来源**——GraphEditor 的同名命中判定委托此处（此前是两份手工同步
     *  的逐字 OR 链）。改动此表即同时改绘制与命中，勿在别处再抄一份。
     *  Types without param panels that still render the ▶/▼ expand indicator (topology-P2
     *  table-driven): they have no paramNames yet own an expand panel; together with the
     *  paramNames>0 catch-all in {@link #hasExpandIndicator(NodeType)} this is the
     *  **single source** — GraphEditor's same-named hit predicate delegates here (it used
     *  to be a hand-synced verbatim copy of the OR chain). Editing the table moves the
     *  drawing and the hit-test together; never duplicate it elsewhere. */
    private static final Set<NodeType> EXPAND_INDICATOR_TYPES = Set.of(
        NodeType.FORMULA, NodeType.REDSTONE_IN, NodeType.REDSTONE_OUT,
        NodeType.PRIVATE_IN, NodeType.PRIVATE_OUT,
        NodeType.IMAGE, NodeType.IMAGE_SEQUENCE,
        NodeType.TEXT, NodeType.DATA,
        NodeType.ENCAPSULATION, NodeType.ENCAP_INPUT, NodeType.ENCAP_OUTPUT,
        NodeType.COMMENT,
        NodeType.BUS_IN, NodeType.BUS_OUT);

    /** 该类型是否渲染 ▶/▼ 展开指示器：paramNames>0 兜底（有参数面板的类型）加上
     *  无参数面板类型表。 / Whether this type renders the ▶/▼ expand indicator:
     *  the paramNames>0 catch-all (types with param panels) plus the table above. */
    public static boolean hasExpandIndicator(NodeType type) {
        return type.paramNames.length > 0 || EXPAND_INDICATOR_TYPES.contains(type);
    }

    private void drawNode(GuiGraphics g, GraphNode n, NodeGraph graph, boolean selected, boolean isPrimary, boolean editing,
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
        float extraH = editing ? io.github.y15173334444.create_schematic_compute.client.editor.EditPanel.calcRenderHeight(n, zoom,
            editing ? nodeEditStatesById.get(n.id) : null) * zoom : 0;
        float nh = contentH + extraH;
        // 发布频道冲突警告（BUS_OUT / PRIVATE_OUT 同徽章）— 在节点上方渲染
        if ((n.type == NodeType.BUS_OUT || n.type == NodeType.PRIVATE_OUT) && n.busConflict) {
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
            // 避开已有的发布频道冲突警告（如果同时存在则移到更上方）
            int yOff = ((n.type == NodeType.BUS_OUT || n.type == NodeType.PRIVATE_OUT) && n.busConflict) ? -(warnH + 6) : 0;
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
        // C=1: 标题文字（CUSTOM：schema 标题直读，注册表未解析回落哨兵 i18n 键）
        // C=1: the title text (CUSTOM: the schema title verbatim, falling back to the
        // sentinel i18n key while the registry lookup fails)
        if (n.type == NodeType.CUSTOM) {
            var spec = io.github.y15173334444.create_schematic_compute.graph.CustomNodeRegistry.get(n.customId);
            drawStr(g, spec != null ? spec.title() : I18n.get(n.type.getTitle()), 4, 4, CNT());
        } else
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
                // §13 去重：节点缓存里的解析产物同文本时直接喂给校验，省一次完整解析。
                // §13 dedup: when the node's cached parse matches the text, feed it to the
                // validator instead of re-parsing in full.
                var cached = n.cachedScript;
                formulaIssuesLive = cached != null && cached.sourceFormula.equals(n.formula)
                    ? io.github.y15173334444.create_schematic_compute.graph.FormulaParser.validate(n.formula, cached)
                    : io.github.y15173334444.create_schematic_compute.graph.FormulaParser.validate(n.formula);
                n.formulaIssues = formulaIssuesLive; // cache until next NBT reload or edit
            }
        }
        boolean formulaHasIssues = formulaIssuesLive != null && !formulaIssuesLive.isEmpty();
        if (formulaHasIssues) {
            drawStr(g, "§c⚠", nodeW - 30, 4, ERR());
        }
        // C=2: 展开指示器
        if (hasExpandIndicator(n.type)) {
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
            int editLocalH = io.github.y15173334444.create_schematic_compute.client.editor.EditPanel.calcRenderHeight(n, zoom, editSt);
            g.fill(2, editLocalY - 2, nodeW - 2, editLocalY, CB());
            g.fill(2, editLocalY, nodeW - 2, editLocalY + editLocalH, 0xFF2A2822);
            if (editSt != null) {
                // 每帧对齐当前图实例：BE 数据同步会整体替换客户端图（音响每 tick setChanged +
                // sendBlockUpdated），EditState 创建时捕获的 st.graph 会变成孤儿快照——编辑区
                // 里依赖图的判定（频段引脚着色/已连接）必须查当前图，否则要重开编辑器才更新。
                // Re-point the edit state at the live graph every frame: BE data syncs replace
                // the client graph wholesale (the speaker ticks setChanged + sendBlockUpdated),
                // so the snapshot captured at EditState creation goes stale and edit-area
                // graph queries (band-pin tint / connected) lag until the editor reopens.
                editSt.graph = graph;
                // 鼠标先换算到节点局部系再传面板：renderAt 的悬停矩形全是局部坐标，与点击侧的
                // (mx-nsx)/zoom 同口径。此前误传屏幕坐标——悬停高亮在「屏幕上数值等于局部矩形」
                // 的空白区点亮（节点不在屏幕左上角时感知位置全错，时序状态批后显形）。
                // Transform the mouse into the node-local frame before handing it to the panel:
                // renderAt's hover rects are all local, the same convention as the click side's
                // (mx-nsx)/zoom. Raw screen coords were passed before — the hover outline lit up
                // wherever the screen position numerically matched the local rect (perceived
                // position wrong for any node not near the screen's top-left; surfaced by the
                // sequential-state batch).
                int lmx = (int) ((mx - sx) / zoom), lmy = (int) ((my - sy) / zoom);
                io.github.y15173334444.create_schematic_compute.client.editor.EditPanel.renderAt(g, 0, editLocalY, nodeW, n, editSt, zoom, lmx, lmy, flipflopStates);
            }
        }
        // C=3.5: 调试节点图表区域（graph space，坐标相对于节点左上角）——实现已拆至 NodeCharts
        // （docs/file-topology-split-review-2026-10-11.md §三 P2），此处直呼转发。
        // C=3.5: debug-node chart area (graph space, coords relative to node top-left) — the
        // implementation lives in NodeCharts (topology review §三 P2); these call straight through.
        java.util.List<float[]> debugCtrlPoints = null;
        if (n.isCurveNode()) {
            debugCtrlPoints = (n.type == NodeType.DEBUG_SIGNAL_GEN)
                ? NodeCharts.renderDebugSignalGenChart(g, n, nodeW, evalSnapshot, currentEncapId)
                : NodeCharts.renderCurveChart(g, n, nodeW);
        } else if (n.type == NodeType.DEBUG_PROBE) {
            NodeCharts.renderDebugProbeChart(g, n, nodeW);
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
        // 频段节点（BUS/PRIVATE）引脚是任意域——承载音频（对端为 AUDIO 引脚）时按音频引脚着色。
        // Band (BUS/PRIVATE) pins are any-domain — they tint as audio pins while carrying audio
        // (peer pin is AUDIO-domain).
        boolean bandIn = n.type == NodeType.BUS_OUT || n.type == NodeType.PRIVATE_OUT;
        boolean bandOut = n.type == NodeType.BUS_IN || n.type == NodeType.PRIVATE_IN;
        for(int i=0; i<funcInputs; i++) {
            float py = HH+PH*i+PH/2f;
            int r = PR;
            // 引脚色只按域（音频域/承载音频的频段引脚 = 青，其余金）——不随连接状态变：
            // 传输灰已退役（作者口径 2026-10-03：连接变色蔓延到普通引脚，读作 bug；音频域
            // 识别由连线青色承担）。
            // Pin colour follows the domain only (AUDIO-domain or audio-carrying band pin =
            // teal, otherwise gold) — never the connection state: the transfer tint is retired
            // (it read as a bug spreading onto ordinary pins; audio domains are told apart by
            // the wire colour).
            boolean inAudio = n.type.inputDomain(i) == NodeType.PinDomain.AUDIO
                || (bandIn && graph != null && graph.isBandPinAudio(n.id, i, false));
            g.fill(-r - 1, (int)(py - r - 1), r + 1, (int)(py + r + 1),
                inAudio ? CAPB() : CPIB());
            g.fill(-r, (int)(py - r), r, (int)(py + r),
                inAudio ? CAP() : CPI());
            String inlbl = n.inputLabel(i);
            // 私有信号输出：输入引脚文本随承载类型切换（判定与上方着色同源 inAudio）——
            // 承载音频显「音频」，浮点维持「值」。BUS_OUT 不经此（其文本 = 频段名）。
            // Private signal output: the input pin text switches with the carried type (same
            // source as the tint above) — audio shows Audio, float keeps Val. BUS_OUT never
            // goes through here (its text = the band name).
            if (n.type == NodeType.PRIVATE_OUT)
                inlbl = privateBandPinLabel(inlbl, inAudio);
            drawStr(g, (n.type == NodeType.BUS_OUT || n.type == NodeType.FORMULA || n.type == NodeType.ENCAPSULATION) ? inlbl : I18n.get(inlbl), 10, py-3, CD);
        }
        for(int i=0; i<n.outputs() && n.type != NodeType.SPEED_CTRL && n.type != NodeType.DEBUG_PROBE; i++) {
            float py = HH+PH*(funcInputs + i)+PH/2f;
            int r = PR;
            // 读取侧频段引脚（BUS_IN/PRIVATE_IN）= 定义同步来的音频标志（跟频道/频段走）
            // ∨ 该引脚本地接了音频线（按频段分流，读取与显示一致）。私有频道的定义标志由
            // 发布方拓扑判定、经 PrivateChannelAudioPacket 同步（BUS 同款，属性跟频道走）。
            // 发布侧（BUS_OUT/PRIVATE_OUT）在输入循环按对端域判定。
            // Read-side band pins (BUS_IN/PRIVATE_IN) = the definition-synced flag (riding the
            // channel/band) ∨ this pin's own local audio wire. A private channel's definition is
            // topology-derived by the publisher and synced via PrivateChannelAudioPacket — the
            // BUS discipline. Publishers (BUS_OUT/PRIVATE_OUT) judge per pin by peer domain.
            boolean syncedBandAudio = n.type == NodeType.BUS_IN && n.signalBands != null
                && i < n.signalBands.size()
                && io.github.y15173334444.create_schematic_compute.network.SignalBus
                    .isAudioBand(n.signalName, n.signalBands.get(i));
            boolean syncedPrivateAudio = n.type == NodeType.PRIVATE_IN
                && io.github.y15173334444.create_schematic_compute.network.SignalBus
                    .isPrivateAudio(n.signalName);
            boolean outAudio = n.type.outputDomain(i) == NodeType.PinDomain.AUDIO
                || (bandOut && graph != null
                    && (syncedBandAudio || syncedPrivateAudio || graph.isBandPinAudio(n.id, i, true)));
            g.fill(nodeW - r - 1, (int)(py - r - 1), nodeW + r + 1, (int)(py + r + 1),
                outAudio ? CAPB() : CPOB());
            g.fill(nodeW - r, (int)(py - r), nodeW + r, (int)(py + r),
                outAudio ? CAP() : CPO());
            String rawOutLbl = n.outputLabel(i);
            // 私有信号输入：引脚文本随频道类型切换（判定与上方着色同源 outAudio）——音频频道
            // 显「音频」，浮点频道维持「值」。BUS_IN 不经此（其文本 = 频段名，有信息量）。
            // Private signal input: the pin text switches with the channel type (same source
            // as the tint above) — audio channels show Audio, float channels keep Val. BUS_IN
            // never goes through here (its text = the band name, which carries information).
            if (n.type == NodeType.PRIVATE_IN)
                rawOutLbl = privateBandPinLabel(rawOutLbl, outAudio);
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
                            int panelBottom = (int)(sy + (editLocalY + io.github.y15173334444.create_schematic_compute.client.editor.EditPanel.calcRenderHeight(n, zoom, editSt)) * zoom);
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

    /** 私有系统引脚文本（PRIVATE_IN 输出 / PRIVATE_OUT 输入共用）：随频道承载类型切换——
     *  承载音频显「音频」，浮点维持节点自带标签（「值」）。判定（outAudio / inAudio）与
     *  引脚着色同源，见引脚循环。
     *  Private-system pin text (shared by PRIVATE_IN's output and PRIVATE_OUT's input):
     *  switches with the carried type — audio shows Audio, float keeps the node's own label
     *  (Val). The predicate (outAudio / inAudio) shares its source with the pin tint; see the
     *  pin loops. */
    static String privateBandPinLabel(String base, boolean audio) {
        return audio ? "pin.create_schematic_compute.audio" : base;
    }


    /** Bresenham 逐像素画线（GuiGraphics 无直接画线 API）。 */


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
    /** 点击是否落在添加节点菜单面板内 / whether the click is on the add-node menu panel */
    public boolean isMenuHit(double mx, double my) { return addMenu.containsClick(mx, my); }
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
