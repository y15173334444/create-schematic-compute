package io.github.y15173334444.create_schematic_compute.client.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

/**
 * 图编辑器右缘侧边栏：收起态图标条（常驻）+ 板块面板（节点库 / 工具）。
 * 书签与设置是单动作图标，不占板块位。设计与拍板见 docs/graph-editor-sidebar-plan.md：
 * 除 [X][编译][运行] 三键外的工具全部收进本栏；设置入口进栏、四 tab 面板留整屏。
 * The graph editor's right-edge sidebar: a permanent collapsed icon rail plus a
 * section panel (node library / tools). Bookmarks and settings are one-shot icon
 * actions, not sections. See docs/graph-editor-sidebar-plan.md: everything except
 * the [X][compile][run] buttons tucks into this rail; the settings entry moves in
 * while the four-tab panel screen stays full-screen.
 *
 * <p>图标条与面板矩形在本类单源计算，渲染与命中同用（命中几何漂移教训）。
 * The rail and panel rects are computed in one place here and shared by both render
 * and hit-testing (the wire-hit geometry-drift lesson).</p>
 */
final class GraphSidebar {

    /** 图标条宽（贴右缘）/ rail width (flush to the right edge) */
    static final int RAIL_W = 22;
    /** 工具面板宽 / tools panel width */
    private static final int PANEL_W = 200;
    /** 图标条项目高 / rail item height */
    private static final int ITEM_H = 24;

    /** 板块：NONE=收起。书签/设置是单动作，不入板块枚举。
     *  Sections: NONE = collapsed. Bookmarks/settings are actions, not sections. */
    enum Section { NONE, NODES, TOOLS }

    private final GraphEditor ed;
    private Section open = Section.NONE;
    /** 最近一次画布悬停点（钉住菜单建点落位用；NaN=尚未悬停过画布）。
     *  Last canvas hover point (pinned-menu node placement; NaN = never hovered). */
    private float aimX = Float.NaN, aimY = Float.NaN;

    GraphSidebar(GraphEditor ed) { this.ed = ed; }

    Section openSection() { return open; }
    boolean isNodesOpen() { return open == Section.NODES; }
    void close() { open = Section.NONE; }

    // ── 几何单源 / single-source geometry ──

    private int railX() { return ed.host.asScreen().width - RAIL_W; }

    /** 图标条项目矩形：0=节点库，1=工具，2=书签，3=设置（沉底）。
     *  Rail item rect: 0=node library, 1=tools, 2=bookmarks, 3=settings (docked bottom). */
    private int[] itemRect(int i) {
        int h = ed.host.asScreen().height;
        int y = (i == 3) ? h - ITEM_H - 2 : 2 + i * ITEM_H;
        return new int[]{railX(), y, RAIL_W, ITEM_H};
    }

    private int[] panelRect() {
        return new int[]{railX() - PANEL_W, 0, PANEL_W, ed.host.asScreen().height};
    }

    /** 工具面板行矩形（渲染与命中同用）。/ Tools panel row rect (shared by render + hit). */
    private int[] toolsRowRect(int row) {
        int[] p = panelRect();
        int y = 26 + row * 24;
        return new int[]{p[0] + 6, y, PANEL_W - 12, 20};
    }

    /** 指针是否在侧边栏（含钉住菜单）之上——画布瞄准点跟踪用。
     *  Whether the pointer is over the sidebar (pinned menu included) — canvas-aim tracking. */
    boolean overChrome(double mx, double my) {
        if (mx >= railX()) return true;
        if (my < GraphEditor.TOP_BAR_H) return true;
        if (open == Section.TOOLS) { int[] p = panelRect(); if (mx >= p[0] && my >= p[1] && my <= p[1] + p[3]) return true; }
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
        int w = ed.host.asScreen().width, h = ed.host.asScreen().height;
        int rx = railX();
        if (open == Section.TOOLS) renderToolsPanel(g, mx, my);
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
        drawItem(g, mx, my, 0, "节点", I18n.get("gui.create_schematic_compute.sidebar.nodes"), open == Section.NODES);
        drawItem(g, mx, my, 1, "工具", I18n.get("gui.create_schematic_compute.sidebar.tools"), open == Section.TOOLS);
        drawItem(g, mx, my, 2, "书签", I18n.get("gui.create_schematic_compute.sidebar.bookmark"), ed.viewBookmarks.panelVisible());
        drawItem(g, mx, my, 3, "设置", I18n.get("gui.create_schematic_compute.topbar.settings"), false);
    }

    /** 图标条项目：竖排二字标签 + 悬停提示。/ Rail item: a vertical two-char label + hover tip. */
    private void drawItem(GuiGraphics g, int mx, int my, int idx, String label, String tip, boolean active) {
        var mc = Minecraft.getInstance();
        int[] r = itemRect(idx);
        boolean hover = mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
        if (hover || active)
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], active ? NodeRenderer.withAlpha(NodeRenderer.ACC(), 0x55) : NodeRenderer.HOV());
        if (active) g.fill(r[0], r[1], r[0] + 2, r[1] + r[3], NodeRenderer.ACC());
        int cx = r[0] + (RAIL_W - 9) / 2;
        int col = active ? 0xFFFFCC66 : (hover ? 0xFFFFFFFF : 0xFFCCCCCC);
        String c0 = label.length() > 0 ? label.substring(0, 1) : label;
        String c1 = label.length() > 1 ? label.substring(1, 2) : "";
        g.drawString(mc.font, c0, cx, r[1] + 2, col, false);
        if (!c1.isEmpty()) g.drawString(mc.font, c1, cx, r[1] + 12, col, false);
        if (hover) {
            int tw = mc.font.width(tip) + 8;
            int tx = r[0] - tw - 2, ty = r[1];
            g.fill(tx, ty, tx + tw, ty + 12, 0xF01A1814);
            g.renderOutline(tx, ty, tw, 12, NodeRenderer.CSB());
            g.drawString(mc.font, tip, tx + 4, ty + 2, 0xFFFFFFFF, false);
        }
    }

    /** 工具面板：网格吸附 / 工具栏置底 / 封装导入导出（宿主支持时）。
     *  Tools panel: grid snap / bottom toolbar / encapsulation import-export (when hosted). */
    private void renderToolsPanel(GuiGraphics g, int mx, int my) {
        var mc = Minecraft.getInstance();
        int[] p = panelRect();
        g.fill(p[0], p[1], p[0] + p[2], p[1] + p[3], NodeRenderer.withAlpha(NodeRenderer.PBG(), 0xEE));
        g.renderOutline(p[0], p[1], p[2], p[3], NodeRenderer.CSB());
        g.drawString(mc.font, "§l" + I18n.get("gui.create_schematic_compute.sidebar.tools"), p[0] + 8, p[1] + 8, NodeRenderer.CCT(), false);
        drawRow(g, mx, my, 0, I18n.get("gui.create_schematic_compute.grid"), ed.gridSnapEnabled);
        drawRow(g, mx, my, 1, I18n.get("gui.create_schematic_compute.sidebar.toolbar_bottom"), NodeRenderer.isToolbarBottom());
        if (ed.host.supportsEncapImportExport()) {
            boolean hasSingleEncap = ed.selectedNode != null
                && ed.selectedNode.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.ENCAPSULATION
                && ed.selectedNodes.size() == 1;
            drawRow(g, mx, my, 2, hasSingleEncap
                ? I18n.get("gui.create_schematic_compute.encap_export")
                : I18n.get("gui.create_schematic_compute.encap_import"), hasSingleEncap);
        }
    }

    private void drawRow(GuiGraphics g, int mx, int my, int row, String text, boolean on) {
        var mc = Minecraft.getInstance();
        int[] r = toolsRowRect(row);
        boolean hover = mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], on ? 0xFF3A5A2A : (hover ? 0xFF3A3A32 : 0xFF2A2822));
        g.renderOutline(r[0], r[1], r[2], r[3], NodeRenderer.CSB());
        g.drawString(mc.font, (on ? "§a" : "§7") + text, r[0] + 6, r[1] + 6, 0xFFFFFFFF, false);
    }

    // ── 输入 / input ──

    /** 命中侧边栏则消费（图标条 / 钉住菜单 / 工具面板）。
     *  Consume when the click lands on the sidebar (rail / pinned menu / tools panel). */
    boolean mouseClicked(double mx, double my, int btn) {
        for (int i = 0; i < 4; i++) {
            int[] r = itemRect(i);
            if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                switch (i) {
                    case 0 -> open = (open == Section.NODES) ? Section.NONE : Section.NODES;
                    case 1 -> open = (open == Section.TOOLS) ? Section.NONE : Section.TOOLS;
                    case 2 -> ed.viewBookmarks.togglePanel();
                    case 3 -> ed.openSettingsFromChrome();
                }
                return true;
            }
        }
        if (open == Section.NODES && ed.renderer.isMenuHit(mx, my)) {
            ed.tryAddMenuClick(mx, my, btn, ed.getGraph());
            return true;
        }
        if (open == Section.TOOLS) {
            int[] p = panelRect();
            if (mx >= p[0] && mx <= p[0] + p[2] && my >= p[1] && my <= p[1] + p[3]) {
                if (btn == 0) {
                    for (int row = 0; row < 3; row++) {
                        int[] r = toolsRowRect(row);
                        if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3]) {
                            if (row == 0) {
                                ed.gridSnapEnabled = !ed.gridSnapEnabled;
                                NodeRenderer.saveGridSnap(ed.gridSnapEnabled);
                            } else if (row == 1) {
                                NodeRenderer.toggleToolbarBottom();
                            } else {
                                ed.openEncapImportExport();
                            }
                            return true;
                        }
                    }
                }
                return true; // 面板内空白也吞掉，不透传画布 / blank panel space is eaten, never leaks to canvas
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
