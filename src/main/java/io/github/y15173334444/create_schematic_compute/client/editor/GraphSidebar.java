package io.github.y15173334444.create_schematic_compute.client.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * 图编辑器右缘侧边栏：常驻图标条 + 板块面板（图 / 节点库）。
 * 图标条用**自制像素图标**（16×16 程序化绘制 + 悬停提示，不用字形/符号）；最上方是
 * 展开/收起按钮；网格吸附、工具栏置底、封装导入导出是图标条上的直控（原「工具」板块
 * 解散）。书签与设置是单动作图标。设计与拍板见 docs/graph-editor-sidebar-plan.md。
 * The graph editor's right-edge sidebar: a permanent icon rail plus a section panel
 * (graph / node library). The rail uses **self-drawn pixel icons** (procedural 16×16
 * with hover tips; no glyphs or symbols); the top item is the expand/collapse button;
 * grid snap, bottom toolbar and encap IO are direct rail toggles (the old "Tools"
 * section is dissolved). Bookmarks and settings are one-shot icons. See
 * docs/graph-editor-sidebar-plan.md.
 *
 * <p>图标条与面板矩形在本类单源计算，渲染与命中同用（命中几何漂移教训）。
 * The rail and panel rects are computed in one place here and shared by both render
 * and hit-testing (the wire-hit geometry-drift lesson).</p>
 */
final class GraphSidebar {

    /** 图标条宽（贴右缘）/ rail width (flush to the right edge) */
    static final int RAIL_W = 22;
    /** 板块面板宽 / section panel width */
    private static final int PANEL_W = 200;
    /** 图标条项目高 / rail item height */
    private static final int ITEM_H = 24;

    /** 板块：NONE=面板收起（图标条仍在）。书签/设置/直控不入板块枚举。
     *  Sections: NONE = panel collapsed (the rail stays). Bookmarks/settings/toggles
     *  are not sections. */
    enum Section { NONE, GRAPH, NODES }

    /** 图标条项目（自上而下；设置沉底）。
     *  Rail items (top to bottom; settings docked at the bottom). */
    private enum RailItem { EXPAND, NODES, GRAPH, BOOKMARKS, SNAP, TBAR, ENCAP, SETTINGS }

    private final GraphEditor ed;
    private Section open = Section.NONE;
    /** 展开按钮的回落板块（收起后重新展开回到上次板块）。
     *  The expand button's fallback section (re-expanding returns to the last one). */
    private Section lastSection = Section.NODES;
    /** 上一帧是否在子图（进子图自动展开「图」板块，返回键始终可达）。
     *  Whether the previous frame was inside a sub-graph (auto-open "Graph" on entry
     *  so the Back button stays reachable). */
    private boolean wasInSubGraph = false;
    /** 最近一次画布悬停点（钉住菜单建点落位用；NaN=尚未悬停过画布）。
     *  Last canvas hover point (pinned-menu node placement; NaN = never hovered). */
    private float aimX = Float.NaN, aimY = Float.NaN;

    GraphSidebar(GraphEditor ed) { this.ed = ed; }

    Section openSection() { return open; }
    boolean isNodesOpen() { return open == Section.NODES; }
    void close() { setSection(Section.NONE); }

    private void setSection(Section s) {
        if (open == Section.GRAPH && s != Section.GRAPH) ed.unfocusNameEdit();
        if (s != Section.NONE) lastSection = s;
        open = s;
    }

    // ── 几何单源 / single-source geometry ──

    private int railX() { return ed.host.asScreen().width - RAIL_W; }

    /** 图标条排布（沉底的设置除外）：展开、节点库、图、书签、网格、置底、封装（宿主支持时）。
     *  Rail order (settings docked separately): expand, nodes, graph, bookmarks,
     *  snap, bottom-toolbar, encap (when hosted). */
    private List<RailItem> railItems() {
        List<RailItem> l = new ArrayList<>(List.of(
            RailItem.EXPAND, RailItem.NODES, RailItem.GRAPH, RailItem.BOOKMARKS, RailItem.SNAP, RailItem.TBAR));
        if (ed.host.supportsEncapImportExport()) l.add(RailItem.ENCAP);
        return l;
    }

    /** 图标条项目矩形（渲染与命中同用）。/ Rail item rect (shared by render + hit). */
    private int[] itemRect(RailItem k) {
        int w = ed.host.asScreen().width, h = ed.host.asScreen().height;
        int y;
        if (k == RailItem.SETTINGS) {
            y = h - ITEM_H - 2;
        } else {
            int idx = railItems().indexOf(k);
            y = 2 + Math.max(0, idx) * ITEM_H;
        }
        return new int[]{w - RAIL_W, y, RAIL_W, ITEM_H};
    }

    private int[] panelRect() {
        return new int[]{railX() - PANEL_W, 0, PANEL_W, ed.host.asScreen().height};
    }

    /** 「图」板块图名行矩形（渲染与命中同用）。
     *  The "Graph" section's name-row rect (shared by render + hit). */
    int[] nameRowRect() {
        int[] p = panelRect();
        return new int[]{p[0] + 8, 26, PANEL_W - 16, 20};
    }

    /** 「图」板块子图返回按钮矩形（渲染与命中同用）。
     *  The "Graph" section's sub-graph Back button rect (shared by render + hit). */
    int[] backRect() {
        int[] p = panelRect();
        return new int[]{p[0] + 8, 50, PANEL_W - 16, 20};
    }

    /** 指针是否在侧边栏（含钉住菜单）之上——画布瞄准点跟踪用。
     *  Whether the pointer is over the sidebar (pinned menu included) — canvas-aim tracking. */
    boolean overChrome(double mx, double my) {
        if (mx >= railX()) return true;
        int btnY = NodeRenderer.toolbarBtnY(ed.host.asScreen().height);
        if (my >= btnY && my < btnY + 18 && mx < 140) return true; // [X][编译][运行] 键簇 / button cluster
        if (open == Section.GRAPH) {
            int[] p = panelRect();
            if (mx >= p[0] && my >= p[1] && my <= p[1] + p[3]) return true;
        }
        return open == Section.NODES && ed.renderer.isMenuHit(mx, my);
    }

    /** 建点落位：最近画布瞄准点；从未悬停则退到自由画布中心。
     *  Placement point: the last canvas aim; falls back to the free-canvas centre. */
    double[] canvasAim() {
        if (Float.isNaN(aimX))
            return new double[]{(ed.host.asScreen().width - RAIL_W) * 0.4, ed.host.asScreen().height * 0.5};
        return new double[]{aimX, aimY};
    }

    void trackAim(double mx, double my) {
        if (!overChrome(mx, my)) { aimX = (float) mx; aimY = (float) my; }
    }

    // ── 渲染 / render ──

    void render(GuiGraphics g, int mx, int my) {
        // 进子图自动展开「图」板块（返回键一击可达）/ auto-open "Graph" on sub-graph entry
        boolean sub = ed.isInSubGraph();
        if (sub && !wasInSubGraph && open == Section.NONE) setSection(Section.GRAPH);
        wasInSubGraph = sub;

        int w = ed.host.asScreen().width, h = ed.host.asScreen().height;
        int rx = railX();
        if (open == Section.GRAPH) renderGraphPanel(g, mx, my);
        // 钉住的节点库：菜单自带框体，锚在图标条左侧（渲染后菜单自存矩形，命中同源）。
        // Pinned node library: the menu draws its own frame, anchored left of the rail
        // (it stores its rect after render — hits reuse that same state).
        if (open == Section.NODES) {
            float ax = rx - 2 - Math.max(ed.renderer.menuWidth(), 161);
            ed.renderPinnedNodeMenu(g, ax, 4, mx, my);
        }
        // 图标条 / rail
        g.fill(rx, 0, w, h, NodeRenderer.withAlpha(NodeRenderer.PBG(), 0xEE));
        g.fill(rx, 0, rx + 1, h, NodeRenderer.CSB());
        boolean encapExport = isEncapExportMode();
        drawRailButton(g, mx, my, RailItem.EXPAND, open != Section.NONE,
            I18n.get(open != Section.NONE ? "gui.create_schematic_compute.sidebar.collapse"
                                          : "gui.create_schematic_compute.sidebar.expand"));
        drawRailButton(g, mx, my, RailItem.NODES, open == Section.NODES,
            I18n.get("gui.create_schematic_compute.sidebar.nodes"));
        drawRailButton(g, mx, my, RailItem.GRAPH, open == Section.GRAPH,
            I18n.get("gui.create_schematic_compute.sidebar.graph"));
        drawRailButton(g, mx, my, RailItem.BOOKMARKS, ed.viewBookmarks.panelVisible(),
            I18n.get("gui.create_schematic_compute.sidebar.bookmark"));
        drawRailButton(g, mx, my, RailItem.SNAP, ed.gridSnapEnabled,
            I18n.get("gui.create_schematic_compute.grid"));
        drawRailButton(g, mx, my, RailItem.TBAR, NodeRenderer.isToolbarBottom(),
            I18n.get("gui.create_schematic_compute.sidebar.toolbar_bottom"));
        if (ed.host.supportsEncapImportExport())
            drawRailButton(g, mx, my, RailItem.ENCAP, encapExport,
                I18n.get(encapExport ? "gui.create_schematic_compute.encap_export"
                                     : "gui.create_schematic_compute.encap_import"));
        drawRailButton(g, mx, my, RailItem.SETTINGS, false,
            I18n.get("gui.create_schematic_compute.topbar.settings"));
    }

    /** 封装导入导出图标是否处于「导出」态（单选封装节点）。
     *  Whether the encap IO icon is in "export" mode (single encapsulation node selected). */
    private boolean isEncapExportMode() {
        return ed.selectedNode != null
            && ed.selectedNode.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.ENCAPSULATION
            && ed.selectedNodes.size() == 1;
    }

    /** 图标条按钮：背景 + 点亮态 + 自绘像素图标 + 悬停提示。
     *  Rail button: background + lit state + self-drawn pixel icon + hover tip. */
    private void drawRailButton(GuiGraphics g, int mx, int my, RailItem k, boolean lit, String tip) {
        var mc = Minecraft.getInstance();
        int[] r = itemRect(k);
        boolean hover = mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
        if (hover || lit)
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], lit ? NodeRenderer.withAlpha(NodeRenderer.ACC(), 0x55) : NodeRenderer.HOV());
        if (lit) g.fill(r[0], r[1], r[0] + 2, r[1] + r[3], NodeRenderer.ACC());
        drawIcon(g, r[0] + 3, r[1] + 4, k, lit, hover);
        if (hover) {
            int tw = mc.font.width(tip) + 8;
            int tx = r[0] - tw - 2, ty = r[1];
            g.fill(tx, ty, tx + tw, ty + 12, 0xF01A1814);
            g.renderOutline(tx, ty, tw, 12, NodeRenderer.CSB());
            g.drawString(mc.font, tip, tx + 4, ty + 2, 0xFFFFFFFF, false);
        }
    }

    // ── 自绘像素图标（16×16 程序化绘制，不用字形/符号）──
    // ── Self-drawn pixel icons (procedural 16×16; no glyphs or symbols) ──

    private static void px(GuiGraphics g, int x, int y, int c) { g.fill(x, y, x + 1, y + 1, c); }

    private static void rectOutline(GuiGraphics g, int x, int y, int w, int h, int c) {
        for (int i = 0; i < w; i++) { px(g, x + i, y, c); px(g, x + i, y + h - 1, c); }
        for (int j = 0; j < h; j++) { px(g, x, y + j, c); px(g, x + w - 1, y + j, c); }
    }

    private void drawIcon(GuiGraphics g, int x, int y, RailItem k, boolean lit, boolean hover) {
        int c = lit ? 0xFFFFCC66 : (hover ? 0xFFFFFFFF : 0xFFCCCCCC);
        switch (k) {
            case EXPAND -> iconChevron(g, x, y, c, open != Section.NONE);
            case NODES -> iconNodePlus(g, x, y, c);
            case GRAPH -> iconNameCard(g, x, y, c);
            case BOOKMARKS -> iconBookmark(g, x, y, c);
            case SNAP -> iconGrid(g, x, y, c);
            case TBAR -> iconBottomBar(g, x, y, c);
            case ENCAP -> iconBoxIO(g, x, y, c);
            case SETTINGS -> iconGear(g, x, y, c);
        }
    }

    /** 展开/收起三角：面板开着 → 指向右（收起方向）；关着 → 指向左（展开方向）。
     *  Expand/collapse triangle: panel open → points right (collapse); closed → left (expand). */
    private static void iconChevron(GuiGraphics g, int x, int y, int c, boolean openNow) {
        for (int r = 0; r < 16; r++) {
            int d = Math.max(Math.abs(r - 7), Math.abs(r - 8));
            int len = 11 - d;
            int x0 = openNow ? 2 : (14 - len);
            for (int i = 0; i < len; i++) px(g, x + x0 + i, y + r, c);
        }
    }

    /** 节点库：方框 + 加号。/ Node library: box + plus. */
    private static void iconNodePlus(GuiGraphics g, int x, int y, int c) {
        rectOutline(g, x + 1, y + 1, 14, 14, c);
        for (int i = 5; i <= 10; i++) {
            px(g, x + i, y + 7, c); px(g, x + i, y + 8, c);
            px(g, x + 7, y + i, c); px(g, x + 8, y + i, c);
        }
    }

    /** 图/名称：名片（头像块 + 两行字）。/ Graph/name: ID card (avatar block + two lines). */
    private static void iconNameCard(GuiGraphics g, int x, int y, int c) {
        rectOutline(g, x + 1, y + 3, 14, 10, c);
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) px(g, x + 3 + i, y + 5 + j, c);
        for (int i = 0; i < 5; i++) { px(g, x + 8 + i, y + 6, c); px(g, x + 8 + i, y + 9, c); }
    }

    /** 书签：矩形 + 底部 V 缺口。/ Bookmark: rectangle with a bottom V notch. */
    private static void iconBookmark(GuiGraphics g, int x, int y, int c) {
        for (int yy = 1; yy <= 13; yy++)
            for (int xx = 3; xx <= 12; xx++) {
                if (yy >= 8 && Math.abs(xx - 7.5) < (yy - 7) * 0.7) continue;
                px(g, x + xx, y + yy, c);
            }
    }

    /** 网格吸附：三纵三横网格线。/ Grid snap: three-by-three grid lines. */
    private static void iconGrid(GuiGraphics g, int x, int y, int c) {
        for (int p : new int[]{2, 7, 12})
            for (int t = 2; t <= 13; t++) { px(g, x + p, y + t, c); px(g, x + t, y + p, c); }
    }

    /** 工具栏置底：下三角 + 底部横条。/ Bottom toolbar: down triangle + bottom bar. */
    private static void iconBottomBar(GuiGraphics g, int x, int y, int c) {
        for (int xx = 1; xx <= 14; xx++) for (int yy = 11; yy <= 13; yy++) px(g, x + xx, y + yy, c);
        for (int yy = 2; yy <= 7; yy++) {
            int half = 8 - yy;
            for (int xx = 8 - half; xx <= 7 + half; xx++) px(g, x + xx, y + yy, c);
        }
    }

    /** 封装导入导出：方框 + 双向箭头。/ Encap import-export: box + double arrow. */
    private static void iconBoxIO(GuiGraphics g, int x, int y, int c) {
        rectOutline(g, x + 3, y + 3, 10, 10, c);
        for (int xx = 0; xx <= 15; xx++) { px(g, x + xx, y + 7, c); px(g, x + xx, y + 8, c); }
        for (int i = 0; i < 4; i++) for (int j = 0; j <= i; j++) {
            px(g, x + 3 - j, y + 4 + i, c); px(g, x + 3 - j, y + 11 - i, c);
            px(g, x + 12 + j, y + 4 + i, c); px(g, x + 12 + j, y + 11 - i, c);
        }
    }

    /** 设置：圆环 + 十字齿。/ Settings: ring + cross teeth. */
    private static void iconGear(GuiGraphics g, int x, int y, int c) {
        for (int yy = 0; yy < 16; yy++) for (int xx = 0; xx < 16; xx++) {
            double dx = xx - 7.5, dy = yy - 7.5, d = Math.sqrt(dx * dx + dy * dy);
            boolean tooth = (Math.abs(dx) < 1.8 || Math.abs(dy) < 1.8) && d < 7.4;
            if ((d > 3.4 && d < 5.8) || tooth) px(g, x + xx, y + yy, c);
        }
    }

    /** 「图」面板：图名编辑（主图）/ 模式标识 + 返回（子图）。体由 GraphEditor 画（生命周期在那边）。
     *  "Graph" panel: name edit (main graph) / mode indicator + Back (sub-graph). The body is
     *  drawn by GraphEditor (the name-edit lifecycle lives there). */
    private void renderGraphPanel(GuiGraphics g, int mx, int my) {
        var mc = Minecraft.getInstance();
        int[] p = panelRect();
        g.fill(p[0], p[1], p[0] + p[2], p[1] + p[3], NodeRenderer.withAlpha(NodeRenderer.PBG(), 0xEE));
        g.renderOutline(p[0], p[1], p[2], p[3], NodeRenderer.CSB());
        g.drawString(mc.font, "§l" + I18n.get("gui.create_schematic_compute.sidebar.graph"), p[0] + 8, p[1] + 8, NodeRenderer.CCT(), false);
        ed.renderGraphPanelBody(g, mx, my);
    }

    // ── 输入 / input ──

    /** 命中侧边栏则消费（图标条 / 图面板 / 钉住菜单）。
     *  Consume when the click lands on the sidebar (rail / graph panel / pinned menu). */
    boolean mouseClicked(double mx, double my, int btn) {
        List<RailItem> hits = railItems();
        hits.add(RailItem.SETTINGS);
        for (RailItem k : hits) {
            int[] r = itemRect(k);
            if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                switch (k) {
                    case EXPAND -> setSection(open == Section.NONE ? lastSection : Section.NONE);
                    case NODES -> setSection(open == Section.NODES ? Section.NONE : Section.NODES);
                    case GRAPH -> setSection(open == Section.GRAPH ? Section.NONE : Section.GRAPH);
                    case BOOKMARKS -> ed.viewBookmarks.togglePanel();
                    case SNAP -> {
                        ed.gridSnapEnabled = !ed.gridSnapEnabled;
                        NodeRenderer.saveGridSnap(ed.gridSnapEnabled);
                    }
                    case TBAR -> NodeRenderer.toggleToolbarBottom();
                    case ENCAP -> ed.openEncapImportExport();
                    case SETTINGS -> ed.openSettingsFromChrome();
                }
                return true;
            }
        }
        if (open == Section.NODES && ed.renderer.isMenuHit(mx, my)) {
            ed.tryAddMenuClick(mx, my, btn, ed.getGraph());
            return true;
        }
        if (open == Section.GRAPH) {
            int[] p = panelRect();
            if (mx >= p[0] && mx <= p[0] + p[2] && my >= p[1] && my <= p[1] + p[3]) {
                if (!ed.clickGraphPanelBody(mx, my, btn)) ed.unfocusNameEdit();
                return true;
            }
        }
        return false;
    }

    /** 钉住菜单上的滚轮 → 菜单滚动（画布缩放不受影响）。
     *  Wheel over the pinned menu scrolls the menu (canvas zoom stays unaffected). */
    boolean mouseScrolled(double mx, double my, double sy) {
        if (open == Section.NODES && ed.renderer.isMenuHit(mx, my)) {
            ed.renderer.scrollMenu((float) (-sy * 14));
            return true;
        }
        return false;
    }
}
