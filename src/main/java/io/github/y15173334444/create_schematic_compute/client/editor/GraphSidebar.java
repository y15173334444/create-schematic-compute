package io.github.y15173334444.create_schematic_compute.client.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 图编辑器右缘侧边栏：常驻图标条 + 板块面板（图 / 节点库）。
 * 图标条用**自制像素图标**（16×16 手绘位图 + 悬停提示，不用字形/符号）；最上方那行是
 * **图标条宽度开关**：收起态 22px 竖排「展开侧栏」，展开态向左延伸、每个图标右侧露出文字
 * （CJK 竖排，其他语言整行转 90°）；网格吸附、
 * 工具栏置底、封装导入导出是图标条上的直控（原「工具」板块解散）。书签与设置是单动作
 * 图标；性能监视器是只读浮窗（帧率/内存/图规模），终端控制台是聊天/系统消息镜像 +
 * 命令输入行。设计与拍板见 docs/graph-editor-sidebar-plan.md。
 * The graph editor's right-edge sidebar: a permanent icon rail plus a section panel
 * (graph / node library). The rail uses **self-drawn pixel icons** (hand-authored
 * 16×16 bitmaps with hover tips; no glyphs or symbols); the top row is the rail's
 * **width toggle**: collapsed it is a 22px stacked "展开侧栏", expanded it widens
 * leftwards so every label shows beside its icon (CJK stacks per character, other
 * locales rotate the line 90°); grid snap, bottom toolbar and encap IO are direct rail
 * toggles (the old "Tools" section is dissolved). Bookmarks and settings are
 * one-shot icons. See docs/graph-editor-sidebar-plan.md.
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
    /** 终端面板宽（输出行要宽度 / the console wants width） */
    private static final int TERM_W = 300;
    /** 图标条项目高 / rail item height */
    private static final int ITEM_H = 24;

    /** 板块：NONE=面板收起（图标条仍在）。书签/设置/直控不入板块枚举。
     *  Sections: NONE = panel collapsed (the rail stays). Bookmarks/settings/toggles
     *  are not sections. */
    enum Section { NONE, GRAPH, NODES, FILES, MONITOR, TERMINAL }

    /** 图标条项目（自上而下；设置沉底）。
     *  Rail items (top to bottom; settings docked at the bottom). */
    private enum RailItem { EXPAND, NODES, GRAPH, BOOKMARKS, SNAP, TBAR, ENCAP, MONITOR, TERMINAL, SETTINGS }

    private final GraphEditor ed;
    private Section open = Section.NONE;
    /** 上一帧是否在子图（进子图自动展开「图」板块，返回键始终可达）。
     *  Whether the previous frame was inside a sub-graph (auto-open "Graph" on entry
     *  so the Back button stays reachable). */
    private boolean wasInSubGraph = false;
    /** 按压回弹（弹出类点击反馈）：时长与弹出距离。
     *  Press bounce (pop-style click feedback): duration and outward travel. */
    private static final long PRESS_MS = 170;
    private static final float POP_PX = 4f;
    /** 最近被按下的图标条项目与时刻。/ The last pressed rail item and its timestamp. */
    private RailItem pressedItem;
    private long pressedAt;
    /** 浮窗弹出动画：面板从图标条底下滑出到位（170ms ease-out，与按压回弹同口径）。
     *  Flyout pop animation: the panel slides out from under the rail (170ms ease-out,
     *  same duration as the press bounce). */
    private static final long FLY_MS = 170;
    /** 最近一次浮窗打开的时刻（图/文件/节点库/书签共用一口钟）。
     *  When the current flyout opened (one clock shared by graph/files/nodes/bookmarks). */
    private long flyoutOpenedAt;

    GraphSidebar(GraphEditor ed) { this.ed = ed; }

    Section openSection() { return open; }
    boolean isNodesOpen() { return open == Section.NODES; }
    void close() { setSection(Section.NONE); }

    private void setSection(Section s) {
        if (open == Section.GRAPH && s != Section.GRAPH) ed.unfocusNameEdit();
        if (open == Section.TERMINAL && s != Section.TERMINAL) unfocusConsoleInput();
        open = s;
        if (s != Section.NONE) flyoutOpenedAt = System.currentTimeMillis(); // 弹出动画 / pop animation
    }

    // ── 几何单源 / single-source geometry ──

    /** 图标条是否展开：展开 = 向左延伸一点，让每个图标右侧露出文字。
     *  Whether the rail is expanded — widened leftwards so every label shows beside
     *  its icon. */
    private boolean railExpanded = false;

    /** 图标条当前宽度（收起 = 22px 纯图标；展开 = 图标 + 右侧文字）。
     *  Current rail width (collapsed = 22px icons only; expanded = icon + label). */
    int width() { return railExpanded ? expandedRailWidth() : RAIL_W; }

    private int railX() { return ed.host.asScreen().width - width(); }

    /** 展开态宽度：图标 16 + 间距 4 + 最长标签 + 两侧留白（按当前语言实测）。
     *  Expanded width: icon 16 + gap 4 + the widest label + padding (measured live). */
    private int expandedRailWidth() {
        var mc = Minecraft.getInstance();
        int widest = 0;
        for (RailItem k : railItems()) widest = Math.max(widest, mc.font.width(railLabel(k)));
        widest = Math.max(widest, mc.font.width(expandLabel()));
        return 3 + 16 + 4 + widest + 6;
    }

    /** 图标条项目的文字（收起态作悬停提示，展开态作图标右侧的行内标签）。
     *  An item's label (hover tip when collapsed, inline label beside the icon when
     *  expanded). */
    private String railLabel(RailItem k) {
        return switch (k) {
            case EXPAND -> expandLabel();
            case NODES -> I18n.get("gui.create_schematic_compute.sidebar.nodes");
            case GRAPH -> I18n.get("gui.create_schematic_compute.sidebar.graph");
            case BOOKMARKS -> I18n.get("gui.create_schematic_compute.sidebar.bookmark");
            case SNAP -> I18n.get("gui.create_schematic_compute.grid");
            case TBAR -> I18n.get(NodeRenderer.isToolbarBottom()
                ? "gui.create_schematic_compute.sidebar.toolbar_bottom"
                : "gui.create_schematic_compute.sidebar.toolbar_top");
            case ENCAP -> I18n.get("gui.create_schematic_compute.sidebar.files_panel");
            case MONITOR -> I18n.get("gui.create_schematic_compute.sidebar.monitor");
            case TERMINAL -> I18n.get("gui.create_schematic_compute.sidebar.terminal");
            case SETTINGS -> I18n.get("gui.create_schematic_compute.topbar.settings");
        };
    }

    /** 展开/收起签的文字（按当前状态取相反动作）。/ Expand/collapse label for the current state. */
    private String expandLabel() {
        return I18n.get(railExpanded ? "gui.create_schematic_compute.sidebar.collapse"
                                     : "gui.create_schematic_compute.sidebar.expand");
    }

    /** 图标条排布（沉底的设置除外）：展开、节点库、图、书签、网格、置底、封装（宿主支持时）。
     *  Rail order (settings docked separately): expand, nodes, graph, bookmarks,
     *  snap, bottom-toolbar, encap (when hosted). */
    private List<RailItem> railItems() {
        List<RailItem> l = new ArrayList<>(List.of(
            RailItem.EXPAND, RailItem.NODES, RailItem.GRAPH, RailItem.BOOKMARKS, RailItem.SNAP, RailItem.TBAR));
        if (ed.host.supportsEncapImportExport()) l.add(RailItem.ENCAP);
        l.add(RailItem.MONITOR);
        l.add(RailItem.TERMINAL);
        return l;
    }

    /** 展开/收起文字签的高度：取两个标签里更长的那个（切换时不跳动）。
     *  Height of the expand/collapse text tab: the longer of the two labels
     *  (no jump when toggling). */
    private int expandTabHeight() {
        String a = I18n.get("gui.create_schematic_compute.sidebar.expand");
        String b = I18n.get("gui.create_schematic_compute.sidebar.collapse");
        var mc = Minecraft.getInstance();
        int unit = isCjk(a) || isCjk(b) ? Math.max(a.length(), b.length()) * 10
                                        : Math.max(mc.font.width(a), mc.font.width(b));
        return unit + 8;
    }

    /** 含 CJK 字符（汉字/全角）→ 竖排逐字；纯拉丁 → 整行转 90°。
     *  Contains CJK (Han / full-width) characters → stacked per character;
     *  pure Latin → the whole line rotated 90°. */
    private static boolean isCjk(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= 0x2E80) return true;
        return false;
    }

    /** 各图标条项目高度（文字签比图标高）。/ Per-item height (the text tab is taller). */
    /** 各行高：收起态的展开签是竖排文字（比图标高），展开态统一行高。
     *  Row heights: the collapsed expand tab is stacked text (taller); expanded rows are uniform. */
    private int itemHeight(RailItem k) {
        return k == RailItem.EXPAND && !railExpanded ? expandTabHeight() : ITEM_H;
    }

    /** 图标条项目矩形（渲染与命中同用）。/ Rail item rect (shared by render + hit). */
    private int[] itemRect(RailItem k) {
        int w = ed.host.asScreen().width, h = ed.host.asScreen().height;
        int y;
        if (k == RailItem.SETTINGS) {
            y = h - ITEM_H - 2;
        } else {
            y = 2;
            for (RailItem it : railItems()) {
                if (it == k) break;
                y += itemHeight(it);
            }
        }
        return new int[]{railX(), y, width(), itemHeight(k)};
    }

    private int[] panelRect() {
        // 弹出动画：面板从图标条底下滑出；偏移进几何单源，渲染与命中一起动。
        // Pop animation: the panel slides out from under the rail; the offset lives in
        // this single-source rect so render and hit-testing move together.
        // （图/文件/监视器共用 PANEL_W，终端更宽 / graph/files/monitor share PANEL_W, the
        // terminal is wider.）
        int pw = open == Section.TERMINAL ? TERM_W : PANEL_W;
        return new int[]{railX() - pw + flyoutDx(pw), 0, pw, ed.host.asScreen().height};
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

    /** 建点落位：钉住菜单添加的节点统一落在**可见画布中心**——浮窗左侧空档的中点、
     *  高度取一半。不再追随最近瞄准点：那个点常常正好在被浮窗盖住的区域，节点加完看不见。
     *  Placement point for pinned-menu adds: the centre of the visible canvas (midway of
     *  the free gap left of any open flyout, half height). It no longer follows the last
     *  aim point — that point often sits under the flyout and the new node lands invisible. */
    double[] canvasAim() {
        return new double[]{flyoutLeft() * 0.5, ed.host.asScreen().height * 0.5};
    }

    /** 当前浮窗（板块面板 / 钉住节点库）的左缘；没有浮窗时就是图标条左缘。
     *  Left edge of the open flyout (section panel / pinned node library); the rail's own
     *  left edge when none is open. */
    private int flyoutLeft() {
        int rx = railX();
        if (open == Section.GRAPH || open == Section.FILES || open == Section.MONITOR) return rx - PANEL_W;
        if (open == Section.TERMINAL) return rx - TERM_W;
        if (open == Section.NODES) return (int) (rx - 2 - Math.max(ed.renderer.menuWidth(), 161));
        return rx;
    }

    /** 按压曲线：1（刚按下）→ 0（回弹完），不是被按下的项目则为 0。
     *  Press curve: 1 at the click falling to 0 (0 for any other item). */
    private float pressAmount(RailItem k) {
        if (pressedItem != k) return 0f;
        long dt = System.currentTimeMillis() - pressedAt;
        if (dt < 0 || dt >= PRESS_MS) { pressedItem = null; return 0f; }
        return (float) Math.sin(Math.PI * dt / PRESS_MS);
    }

    /** 浮窗滑出进度：0（刚打开，全藏在图标条后）→ 1（就位）；ease-out 三次曲线。
     *  Flyout slide progress: 0 (just opened, fully hidden behind the rail) → 1 (in
     *  place); ease-out cubic. */
    static float slideProgress(long openedAt) {
        long dt = System.currentTimeMillis() - openedAt;
        if (dt < 0 || dt >= FLY_MS) return 1f;
        float t = (float) dt / FLY_MS;
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    /** 浮窗本帧的弹出偏移（加到 x 上：正值朝图标条一侧，动画结束后为 0）。
     *  This frame's flyout offset (added to x: positive toward the rail, 0 once done). */
    int flyoutDx(int dist) {
        return Math.round((1f - slideProgress(flyoutOpenedAt)) * dist);
    }

    /** 向白色提亮（0=原色，1=纯白）。/ Blend toward white (0 = as-is, 1 = pure white). */
    private static int brighten(int c, float t) {
        if (t <= 0f) return c;
        if (t > 1f) t = 1f;
        int a = (c >>> 24) & 0xFF, r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        r = (int) (r + (255 - r) * t);
        g = (int) (g + (255 - g) * t);
        b = (int) (b + (255 - b) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** 单浮窗口径：打开新的板块/面板前收起书签浮窗（同屏只留一个侧栏浮窗）。
     *  Single-flyout discipline: close the bookmark flyout before opening another
     *  section/panel (only one sidebar flyout on screen at a time). */
    private void closeBookmarksFlyout() {
        if (ed.viewBookmarks.panelVisible()) ed.viewBookmarks.closePanel();
    }

    // ── 渲染 / render ──

    void render(GuiGraphics g, int mx, int my) {
        // 进子图自动展开「图」板块（返回键一击可达）/ auto-open "Graph" on sub-graph entry
        boolean sub = ed.isInSubGraph();
        if (sub && !wasInSubGraph && open == Section.NONE) { closeBookmarksFlyout(); setSection(Section.GRAPH); }
        wasInSubGraph = sub;

        int w = ed.host.asScreen().width, h = ed.host.asScreen().height;
        int rx = railX();
        if (open == Section.GRAPH) renderGraphPanel(g, mx, my);
        if (open == Section.FILES) renderFilesPanel(g, mx, my);
        if (open == Section.MONITOR) renderMonitorPanel(g, mx, my);
        if (open == Section.TERMINAL) renderTerminalPanel(g, mx, my);
        // 钉住的节点库：菜单自带框体，锚在图标条左侧（渲染后菜单自存矩形，命中同源）。
        // Pinned node library: the menu draws its own frame, anchored left of the rail
        // (it stores its rect after render — hits reuse that same state).
        if (open == Section.NODES) {
            int mw = (int) Math.max(ed.renderer.menuWidth(), 161);
            // 菜单自带屏幕钳制，滑出偏移走独立参数（钳制之后施加，菜单才能从条后滑出）。
            // The menu screen-clamps its anchor, so the slide offset travels as its own
            // parameter (applied after the clamp — the menu slides out from under the rail).
            ed.renderPinnedNodeMenu(g, rx - 2 - mw, 4, mx, my, flyoutDx(mw + 2));
        }
        // 图标条 / rail
        g.fill(rx, 0, w, h, NodeRenderer.withAlpha(NodeRenderer.PBG(), 0xEE));
        g.fill(rx, 0, rx + 1, h, NodeRenderer.CSB());
        drawRailTextTab(g, mx, my);
        drawRailButton(g, mx, my, RailItem.NODES, open == Section.NODES);
        drawRailButton(g, mx, my, RailItem.GRAPH, open == Section.GRAPH);
        drawRailButton(g, mx, my, RailItem.BOOKMARKS, ed.viewBookmarks.panelVisible());
        drawRailButton(g, mx, my, RailItem.SNAP, ed.gridSnapEnabled);
        drawRailButton(g, mx, my, RailItem.TBAR, false); // 位置由图标表达，不留常亮 / icon reports position, no steady lit
        if (ed.host.supportsEncapImportExport())
            drawRailButton(g, mx, my, RailItem.ENCAP, open == Section.FILES);
        drawRailButton(g, mx, my, RailItem.MONITOR, open == Section.MONITOR);
        drawRailButton(g, mx, my, RailItem.TERMINAL, open == Section.TERMINAL);
        drawRailButton(g, mx, my, RailItem.SETTINGS, false);
    }

    /** 图标条按钮：背景 + 点亮态 + 自绘像素图标（展开态再加图标右侧的文字）+ 悬停提示；
     *  按下时朝画布弹出一下再回位。
     *  Rail button: background + lit state + self-drawn pixel icon (plus the label right of
     *  it when expanded) + hover tip; a press pops it out toward the canvas and settles back. */
    private void drawRailButton(GuiGraphics g, int mx, int my, RailItem k, boolean lit) {
        var mc = Minecraft.getInstance();
        int[] r = itemRect(k);
        boolean hover = mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
        float pop = pressAmount(k);
        int dx = Math.round(POP_PX * pop);
        if (hover || lit)
            g.fill(r[0] - dx, r[1], r[0] + r[2], r[1] + r[3], lit ? NodeRenderer.withAlpha(NodeRenderer.ACC(), 0x55) : NodeRenderer.HOV());
        if (lit) g.fill(r[0] - dx, r[1], r[0] + 2, r[1] + r[3], NodeRenderer.ACC());
        int iconX = r[0] + 3 - dx, iconY = r[1] + (r[3] - 16) / 2;
        drawIcon(g, iconX, iconY, k, lit, hover, pop);
        String label = railLabel(k);
        if (railExpanded) {
            g.drawString(mc.font, label, iconX + 16 + 4, r[1] + (r[3] - 8) / 2,
                brighten(lit ? 0xFFFFCC66 : (hover ? 0xFFFFFFFF : 0xFFCCCCCC), 0.55f * pop), false);
        } else if (hover) {
            int tw = mc.font.width(label) + 8;
            int tx = r[0] - tw - 2 - dx, ty = r[1];
            g.fill(tx, ty, tx + tw, ty + 12, 0xF01A1814);
            g.renderOutline(tx, ty, tw, 12, NodeRenderer.CSB());
            g.drawString(mc.font, label, tx + 4, ty + 2, 0xFFFFFFFF, false);
        }
    }

    /** 展开/收起行：收起态是竖排文字（CJK 逐字；其他语言整行转 90°，图标条只有 22px 宽），
     *  展开态是「chevron + 收起侧栏」普通一行。点击只切换图标条宽度，板块仍由各自图标开合。
     *  Expand/collapse row: stacked text when collapsed (CJK per character; other locales
     *  rotated 90° — the rail is only 22px wide) and a normal "chevron + collapse" row when
     *  expanded. Clicking only toggles the rail width; sections still open from their icons. */
    private void drawRailTextTab(GuiGraphics g, int mx, int my) {
        var mc = Minecraft.getInstance();
        int[] r = itemRect(RailItem.EXPAND);
        boolean hover = mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
        float pop = pressAmount(RailItem.EXPAND);
        int dx = Math.round(POP_PX * pop);
        if (hover) g.fill(r[0] - dx, r[1], r[0] + r[2], r[1] + r[3], NodeRenderer.HOV());
        int c = brighten(hover ? 0xFFFFFFFF : 0xFFCCCCCC, 0.55f * pop);
        String label = expandLabel();
        if (railExpanded) {
            int iconX = r[0] + 3 - dx, iconY = r[1] + (r[3] - 16) / 2;
            drawIcon(g, iconX, iconY, RailItem.EXPAND, false, hover, pop);
            g.drawString(mc.font, label, iconX + 16 + 4, r[1] + (r[3] - 8) / 2, c, false);
            return;
        }
        int cx = r[0] - dx + r[2] / 2;
        if (isCjk(label)) {
            int ty = r[1] + (r[3] - label.length() * 10) / 2;
            for (int i = 0; i < label.length(); i++) {
                String ch = String.valueOf(label.charAt(i));
                g.drawString(mc.font, ch, cx - mc.font.width(ch) / 2, ty + i * 10 + 1, c, false);
            }
        } else {
            int tw = mc.font.width(label);
            g.pose().pushPose();
            g.pose().translate(cx - 4.5f, r[1] + r[3] / 2.0f + tw / 2.0f, 0f);
            g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(-90f));
            g.drawString(mc.font, label, 0, 0, c, false);
            g.pose().popPose();
        }
    }

    // ── 自绘像素图标（16×16 手绘位图，不用字形/符号）──
    // ── Self-drawn pixel icons (hand-authored 16×16 bitmaps; no glyphs or symbols) ──

    /** 展开/收起行：右向 chevron（收起方向）。/ Expand row: right-pointing chevron (collapse). */
    private static final String[] ICON_CHEVRON_RIGHT = {
        "................",
        "................",
        "................",
        "...##...........",
        "....##..........",
        ".....##.........",
        "......##........",
        ".......##.......",
        "......##........",
        ".....##.........",
        "....##..........",
        "...##...........",
        "................",
        "................",
        "................",
        "................",
    };

    /** 节点库：方框 + 加号。/ Node library: framed box + plus. */
    private static final String[] ICON_NODES = {
        "................",
        "................",
        "................",
        "..##########....",
        "..#........#....",
        "..#...##...#....",
        "..#...##...#....",
        "..#.######.#....",
        "..#.######.#....",
        "..#...##...#....",
        "..#...##...#....",
        "..#........#....",
        "..##########....",
        "................",
        "................",
        "................",
    };

    /** 图：两个节点 + 连线。/ Graph: two nodes joined by an edge. */
    private static final String[] ICON_GRAPH = {
        "................",
        "................",
        "..#####.........",
        "..#...#.........",
        "..#...#.........",
        "..#...#.........",
        "..#####.........",
        ".......#........",
        "........#.......",
        ".........#####..",
        ".........#...#..",
        ".........#...#..",
        ".........#...#..",
        ".........#####..",
        "................",
        "................",
    };

    /** 书签：绸带 + 底部 V 缺口。/ Bookmark: ribbon with a bottom V notch. */
    private static final String[] ICON_BOOKMARK = {
        "................",
        "................",
        "....########....",
        "....#......#....",
        "....#......#....",
        "....#......#....",
        "....#......#....",
        "....#......#....",
        "....#......#....",
        "....#......#....",
        "....#......#....",
        "....###..###....",
        "....##....##....",
        "....#......#....",
        "................",
        "................",
    };

    /** 网格吸附：三纵三横网格。/ Grid snap: a three-by-three grid. */
    private static final String[] ICON_SNAP = {
        "................",
        "................",
        "..############..",
        "..#....#....#...",
        "..#....#....#...",
        "..#....#....#...",
        "..#....#....#...",
        "..############..",
        "..#....#....#...",
        "..#....#....#...",
        "..#....#....#...",
        "..#....#....#...",
        "..############..",
        "..#....#....#...",
        "................",
        "................",
    };

    /** 工具栏在顶：顶部横条 + 上箭头（与置底态成对，图标本身反馈当前位置）。
     *  Toolbar on top: top bar + up arrow (pairs with the bottom variant — the icon itself
     *  reports where the three-button cluster currently sits). */
    private static final String[] ICON_BAR_TOP = {
        "................",
        "..############..",
        "..############..",
        "................",
        "................",
        ".......##.......",
        "......####......",
        ".....######.....",
        "....########....",
        ".......##.......",
        ".......##.......",
        ".......##.......",
        ".......##.......",
        ".......##.......",
        "................",
        "................",
    };

    /** 工具栏置底：下箭头 + 底部横条。/ Bottom toolbar: down arrow above a bottom bar. */
    private static final String[] ICON_BAR_BOTTOM = {
        "................",
        "................",
        ".......##.......",
        ".......##.......",
        ".......##.......",
        ".......##.......",
        ".......##.......",
        "....########....",
        ".....######.....",
        "......####......",
        ".......##.......",
        "................",
        "................",
        "..############..",
        "..############..",
        "................",
    };

    /** 封装导入导出：文件夹（导入导出走文件系统）。/ Encap IO: folder (import/export is file-system based). */
    private static final String[] ICON_FOLDER = {
        "................",
        "................",
        "................",
        ".####...........",
        ".#...#..........",
        ".#############..",
        ".#...........#..",
        ".#...........#..",
        ".#...........#..",
        ".#...........#..",
        ".#...........#..",
        ".#...........#..",
        ".#############..",
        "................",
        "................",
        "................",
    };

    /** 性能监视器：心电脉冲线（基线 + 上冲 + 下探）。/ Performance monitor: an ECG pulse
     *  line (baseline + spike + dip). */
    private static final String[] ICON_MONITOR = {
        "................",
        "................",
        "................",
        "......##........",
        "......##........",
        "......##........",
        "......##........",
        "......##........",
        "......##........",
        ".##############.",
        "........#.......",
        "........#.......",
        "........#.......",
        "........#.......",
        "................",
        "................",
    };

    /** 终端：窗框 + 提示符「>_」。/ Terminal: a window frame with the ">_" prompt. */
    private static final String[] ICON_TERMINAL = {
        "................",
        "................",
        ".##############.",
        ".#............#.",
        ".#............#.",
        ".#.##.........#.",
        ".#..##........#.",
        ".#...##.......#.",
        ".#..##........#.",
        ".#.#....#####.#.",
        ".#............#.",
        ".##############.",
        "................",
        "................",
        "................",
        "................",
    };

    /** 设置：八齿齿轮（环 + 八向齿，中心留孔）。/ Settings: eight-tooth gear (ring + teeth, open hub). */
    private static final String[] ICON_GEAR = {
        "................",
        ".......##.......",
        ".......##.......",
        "...##.####.##...",
        "...##########...",
        "....########....",
        "...###....###...",
        ".#####....#####.",
        ".#####....#####.",
        "...###....###...",
        "....########....",
        "...##########...",
        "...##.####.##...",
        ".......##.......",
        ".......##.......",
        "................",
    };

    private static void px(GuiGraphics g, int x, int y, int c) { g.fill(x, y, x + 1, y + 1, c); }

    /** 16×16 字符位图 → 像素（'#' 点亮，其余透明）。
     *  16×16 character bitmap → pixels ('#' lit, everything else transparent). */
    private static void blit(GuiGraphics g, int x, int y, String[] bmp, int c) {
        for (int r = 0; r < bmp.length; r++) {
            String row = bmp[r];
            for (int i = 0; i < row.length(); i++)
                if (row.charAt(i) == '#') px(g, x + i, y + r, c);
        }
    }

    private void drawIcon(GuiGraphics g, int x, int y, RailItem k, boolean lit, boolean hover, float pop) {
        int c = lit ? 0xFFFFCC66 : (hover ? 0xFFFFFFFF : 0xFFCCCCCC);
        c = brighten(c, 0.55f * pop);
        String[] bmp = switch (k) {
            case EXPAND -> ICON_CHEVRON_RIGHT; // 只有展开态画 chevron / only when expanded
            case NODES -> ICON_NODES;
            case GRAPH -> ICON_GRAPH;
            case BOOKMARKS -> ICON_BOOKMARK;
            case SNAP -> ICON_SNAP;
            case TBAR -> NodeRenderer.isToolbarBottom() ? ICON_BAR_BOTTOM : ICON_BAR_TOP;
            case ENCAP -> ICON_FOLDER;
            case MONITOR -> ICON_MONITOR;
            case TERMINAL -> ICON_TERMINAL;
            case SETTINGS -> ICON_GEAR;
        };
        if (bmp != null) blit(g, x, y, bmp, c);
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

    // ── 「文件」面板（占位：五个按钮暂不接行为）/ placeholder "Files" panel ──

    private static final int FILE_BTN_H = 20;
    private static final int FILE_BTN_GAP = 24;
    private static final int FILE_BTN_Y0 = 30;

    /** 面板按钮序号：0 新建图、1 导入、2 导出、3 撤销、4 重做（后两个并排）。
     *  Button indices: 0 new graph, 1 import, 2 export, 3 undo, 4 redo (last two share a row).
     *  矩形单源，渲染与命中同用。/ Rects are single-source (render + hit). */
    private int[] fileBtnRect(int i) {
        int[] p = panelRect();
        int x = p[0] + 8, w = PANEL_W - 16;
        if (i < 3) return new int[]{x, FILE_BTN_Y0 + i * FILE_BTN_GAP, w, FILE_BTN_H};
        int half = (w - 4) / 2;
        return new int[]{(i == 3) ? x : x + half + 4, FILE_BTN_Y0 + 3 * FILE_BTN_GAP, half, FILE_BTN_H};
    }

    private static String fileBtnLabel(int i) {
        return I18n.get(switch (i) {
            case 0 -> "gui.create_schematic_compute.sidebar.new_graph";
            case 1 -> "gui.create_schematic_compute.sidebar.import_graph";
            case 2 -> "gui.create_schematic_compute.sidebar.export_graph";
            case 3 -> "gui.create_schematic_compute.editorkeys.undo";
            default -> "gui.create_schematic_compute.editorkeys.redo";
        });
    }

    /** 「文件」面板：新建图 / 导入 / 导出 / 撤销 / 重做。按钮暂不接行为（占位）。
     *  "Files" panel: new graph / import / export / undo / redo. Buttons are inert
     *  for now (placeholder). */
    private void renderFilesPanel(GuiGraphics g, int mx, int my) {
        var mc = Minecraft.getInstance();
        int[] p = panelRect();
        g.fill(p[0], p[1], p[0] + p[2], p[1] + p[3], NodeRenderer.withAlpha(NodeRenderer.PBG(), 0xEE));
        g.renderOutline(p[0], p[1], p[2], p[3], NodeRenderer.CSB());
        g.drawString(mc.font, "§l" + I18n.get("gui.create_schematic_compute.sidebar.files_panel"),
            p[0] + 8, p[1] + 8, NodeRenderer.CCT(), false);
        for (int i = 0; i < 5; i++) {
            int[] r = fileBtnRect(i);
            boolean hover = mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3],
                hover ? NodeRenderer.HOV() : NodeRenderer.withAlpha(NodeRenderer.ACC(), 0x1C));
            g.renderOutline(r[0], r[1], r[2], r[3], NodeRenderer.CSB());
            String label = fileBtnLabel(i);
            g.drawString(mc.font, label, r[0] + (r[2] - mc.font.width(label)) / 2,
                r[1] + (r[3] - 8) / 2, hover ? 0xFFFFFFFF : 0xFFDDDDDD, false);
        }
    }

    // ── 性能监视器面板 / performance monitor panel ──

    /** 帧率历史：250ms 采一格，46 格 ≈ 11.5 秒走势（4px 条铺满面板内容宽）。
     *  FPS history: one 250ms bucket each, 46 buckets ≈ 11.5s of trend (4px bars fill
     *  the panel's content width). */
    private static final int HIST_SLOTS = 46;
    private static final long SAMPLE_MS = 250;
    private final float[] fpsHist = new float[HIST_SLOTS];
    private int fpsHistLen;
    private long lastSampleAt;

    /** 走势满格的动态封顶：随窗口内峰值上浮，向上取到常见刷新率阶梯（数值始终可读，
     *  单尖峰也会把满格抬到自己之上，不压扁其余条）。
     *  Dynamic full-scale cap of the trend: floats with the window's peak, rounded up to
     *  the common refresh-rate ladder (numbers stay readable; a lone spike lifts the
     *  scale above itself instead of squashing every other bar). */
    private static final int[] FPS_LADDER = {30, 60, 75, 90, 120, 144, 165, 240, 320, 480, 640, 960};

    private int fpsScale() {
        float mx = 0;
        for (int i = 0; i < fpsHistLen; i++) mx = Math.max(mx, fpsHist[i]);
        if (mx <= 0) return 60; // 还没采样时按最常见刷新率起步 / 60 until the first sample lands
        for (int r : FPS_LADDER) if (mx <= r) return r;
        return (int) ((mx + 29) / 30) * 30; // 超出阶梯按 30 向上取整 / beyond the ladder: round up by 30s
    }

    /** 监视器一行：左标签灰、右值右对齐白。返回下一行的 y。
     *  One monitor row: gray label left, white right-aligned value. Returns next row y. */
    private static int monRow(GuiGraphics g, Font f, int x, int w, int y, String label, String value) {
        g.drawString(f, label, x, y, 0xFF888888, false);
        g.drawString(f, value, x + w - f.width(value), y, 0xFFEEEEEE, false);
        return y + 14;
    }

    /** 「性能监视器」面板：帧率 + 走势条、内存（数值 + 条）、节点/连线/缩放。只读。
     *  "Performance" panel: FPS + trend bars, memory (value + bar), nodes/wires/zoom.
     *  Read-only. */
    private void renderMonitorPanel(GuiGraphics g, int mx, int my) {
        var mc = Minecraft.getInstance();
        int[] p = panelRect();
        g.fill(p[0], p[1], p[0] + p[2], p[1] + p[3], NodeRenderer.withAlpha(NodeRenderer.PBG(), 0xEE));
        g.renderOutline(p[0], p[1], p[2], p[3], NodeRenderer.CSB());
        g.drawString(mc.font, "§l" + I18n.get("gui.create_schematic_compute.sidebar.monitor"),
            p[0] + 8, p[1] + 8, NodeRenderer.CCT(), false);

        // 采样（面板打开期间累积；250ms 一格）/ sample (accrues while open; 250ms a bucket)
        long now = System.currentTimeMillis();
        if (now - lastSampleAt >= SAMPLE_MS) {
            lastSampleAt = now;
            if (fpsHistLen == HIST_SLOTS) {
                System.arraycopy(fpsHist, 1, fpsHist, 0, HIST_SLOTS - 1);
                fpsHistLen--;
            }
            fpsHist[fpsHistLen++] = mc.getFps();
        }

        int x0 = p[0] + 8, w = PANEL_W - 16;
        int fps = Math.max(1, mc.getFps());
        int y = monRow(g, mc.font, x0, w, p[1] + 28, I18n.get("gui.create_schematic_compute.perfmon.fps"),
            fps + " FPS · " + String.format("%.1f", 1000f / fps) + " ms");

        // 帧率走势（满格随峰值动态上浮）/ FPS trend (full scale floats with the peak)
        g.drawString(mc.font, I18n.get("gui.create_schematic_compute.perfmon.trend"), x0, y + 4, 0xFF888888, false);
        int scale = fpsScale();
        String cap = I18n.get("gui.create_schematic_compute.perfmon.trend_cap", scale);
        g.drawString(mc.font, cap, x0 + w - mc.font.width(cap), y + 4, 0xFF666666, false);
        int areaH = 56;
        int baseY = y + 18 + areaH;
        g.fill(x0, baseY, x0 + w, baseY + 1, NodeRenderer.PBR()); // 基线 / baseline
        float barW = (float) w / HIST_SLOTS;
        for (int i = 0; i < fpsHistLen; i++) {
            float f = fpsHist[i];
            int bh = f <= 0 ? 0 : Math.round(Math.min(f, scale) / (float) scale * areaH);
            if (bh > 0) {
                int bx = x0 + Math.round(i * barW);
                int bw = Math.max(1, Math.round(barW) - 1);
                // 历史格暗一点、最新一格全亮 / dim history, the latest bucket lit
                g.fill(bx, baseY - bh, bx + bw, baseY,
                    i == fpsHistLen - 1 ? NodeRenderer.ACC() : NodeRenderer.withAlpha(NodeRenderer.ACC(), 0x77));
            }
        }
        y = baseY + 10;

        // 内存（数值 + 占用条）/ memory (value + usage bar)
        long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
        long maxMb = Math.max(1, Runtime.getRuntime().maxMemory() >> 20);
        y = monRow(g, mc.font, x0, w, y, I18n.get("gui.create_schematic_compute.perfmon.memory"),
            usedMb + " / " + maxMb + " MB");
        int frac = (int) Math.min(w - 2, (double) usedMb / maxMb * (w - 2));
        g.renderOutline(x0, y, w, 8, NodeRenderer.CSB());
        if (frac > 0) g.fill(x0 + 1, y + 1, x0 + 1 + frac, y + 7, NodeRenderer.ACC());
        y += 16;

        // 图规模与视角 / graph size and view
        var graph = ed.getGraph();
        y = monRow(g, mc.font, x0, w, y, I18n.get("gui.create_schematic_compute.perfmon.nodes"),
            String.valueOf(graph.nodes.size()));
        y = monRow(g, mc.font, x0, w, y, I18n.get("gui.create_schematic_compute.perfmon.wires"),
            String.valueOf(graph.connections.size()));
        monRow(g, mc.font, x0, w, y, I18n.get("gui.create_schematic_compute.perfmon.zoom"),
            Math.round(ed.zoom * 100) + "%");
    }

    // ── 终端控制台面板 / terminal console panel ──

    /** 终端输入框（懒建；位置每帧从 inputRowRect 取）+ 输出区滚动偏移（0 = 贴底跟新行）。
     *  The console input (lazy; positioned per frame from inputRowRect) + the output
     *  scroll offset (0 = pinned to the bottom, following new lines). */
    private EditBox consoleInput;
    private int consoleScroll = 0;

    /** 终端输入行矩形（渲染与命中同源，挂在面板底部）。/ The input-row rect (render + hit
     *  single source, docked at the panel's bottom). */
    private int[] inputRowRect() {
        int[] p = panelRect();
        return new int[]{p[0] + 8, p[1] + p[3] - 22, p[2] - 16, 16};
    }

    boolean consoleInputFocused() { return consoleInput != null && consoleInput.isFocused(); }

    void unfocusConsoleInput() { if (consoleInput != null) consoleInput.setFocused(false); }

    /** 终端键盘：Esc 失焦、Enter 提交（发送 + 回显），其余交给 EditBox。
     *  Console keyboard: Esc blurs, Enter submits (send + echo), the rest to the EditBox. */
    boolean consoleKey(int key, int sc, int mod) {
        if (key == 256) { unfocusConsoleInput(); return true; }
        if (key == 257 || key == 335) { submitConsole(); return true; }
        return consoleInput.keyPressed(key, sc, mod);
    }

    boolean consoleChar(char ch, int mod) { return consoleInput.charTyped(ch, mod); }

    /** 提交输入行：命令（/ 开头）本地回显后 sendChat（原版语义：无 / 的文本作为聊天发出；
     *  命令反馈经系统消息回流到输出区）。/ Submit the input: commands ("/"-prefixed) echo
     *  locally then go out via sendChat (vanilla semantics: text without "/" is sent as
     *  chat; command feedback flows back as system messages). */
    private void submitConsole() {
        String text = consoleInput.getValue().strip();
        if (text.isEmpty()) return;
        var mc = Minecraft.getInstance();
        if (text.startsWith("/")) SidebarConsoleFeed.append("§8> §7" + text);
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendChat(text);
        } else {
            SidebarConsoleFeed.append(I18n.get("gui.create_schematic_compute.terminal.disconnected"));
        }
        consoleInput.setValue("");
    }

    /** 「终端」面板：标题 + 输出区（新行沉底、滚轮翻历史、超宽截断）+ 底部输入行。
     *  "Terminal" panel: title + output area (newest at bottom, wheel through history,
     *  overlong lines truncated) + the input row at the bottom. */
    private void renderTerminalPanel(GuiGraphics g, int mx, int my) {
        var mc = Minecraft.getInstance();
        int[] p = panelRect();
        g.fill(p[0], p[1], p[0] + p[2], p[1] + p[3], NodeRenderer.withAlpha(NodeRenderer.PBG(), 0xEE));
        g.renderOutline(p[0], p[1], p[2], p[3], NodeRenderer.CSB());
        g.drawString(mc.font, "§l" + I18n.get("gui.create_schematic_compute.sidebar.terminal"),
            p[0] + 8, p[1] + 8, NodeRenderer.CCT(), false);

        int[] ir = inputRowRect();
        int top = p[1] + 26, bottom = ir[1] - 6, rowH = 10;
        int rows = Math.max(0, (bottom - top) / rowH);
        var lines = SidebarConsoleFeed.snapshot();
        int maxScroll = Math.max(0, lines.size() - rows);
        if (consoleScroll > maxScroll) consoleScroll = maxScroll;
        if (consoleScroll < 0) consoleScroll = 0;
        for (int i = 0; i < rows; i++) {
            int idx = lines.size() - 1 - consoleScroll - i;
            if (idx < 0) break;
            String s = lines.get(idx);
            g.drawString(mc.font, mc.font.plainSubstrByWidth(s, ir[2]), p[0] + 8, top + i * rowH, 0xFFCCCCCC, false);
        }
        g.fill(ir[0], ir[1], ir[0] + ir[2], ir[1] + ir[3], 0xFF1A1814);
        g.renderOutline(ir[0], ir[1], ir[2], ir[3], consoleInputFocused() ? NodeRenderer.ACC() : 0xFF5A4D3A);
        if (consoleInput == null) {
            consoleInput = new EditBox(mc.font, 0, 0, ir[2] - 8, 12, Component.literal(""));
            consoleInput.setMaxLength(256);
            consoleInput.setBordered(false);
        }
        consoleInput.setX(ir[0] + 4);
        consoleInput.setY(ir[1] + 3);
        consoleInput.setWidth(ir[2] - 8);
        consoleInput.render(g, 0, 0, 0);
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
                pressedItem = k; pressedAt = System.currentTimeMillis(); // 弹出回弹 / pop feedback
                switch (k) {
                    case EXPAND -> railExpanded = !railExpanded; // 只调图标条宽度 / width toggle only
                    case NODES -> {
                        closeBookmarksFlyout();
                        setSection(open == Section.NODES ? Section.NONE : Section.NODES);
                    }
                    case GRAPH -> {
                        closeBookmarksFlyout();
                        setSection(open == Section.GRAPH ? Section.NONE : Section.GRAPH);
                    }
                    case BOOKMARKS -> {
                        boolean opening = !ed.viewBookmarks.panelVisible();
                        if (opening) setSection(Section.NONE); // 单浮窗：开书签先收板块
                        if (opening) flyoutOpenedAt = System.currentTimeMillis(); // 弹出动画 / pop animation
                        ed.viewBookmarks.togglePanel();
                    }
                    case SNAP -> {
                        ed.gridSnapEnabled = !ed.gridSnapEnabled;
                        NodeRenderer.saveGridSnap(ed.gridSnapEnabled);
                    }
                    case TBAR -> NodeRenderer.toggleToolbarBottom();
                    case ENCAP -> {
                        closeBookmarksFlyout();
                        setSection(open == Section.FILES ? Section.NONE : Section.FILES);
                    }
                    case MONITOR -> {
                        closeBookmarksFlyout();
                        setSection(open == Section.MONITOR ? Section.NONE : Section.MONITOR);
                    }
                    case TERMINAL -> {
                        closeBookmarksFlyout();
                        setSection(open == Section.TERMINAL ? Section.NONE : Section.TERMINAL);
                    }
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
        if (open == Section.FILES) {
            // 占位面板：只吞点击，按钮暂不接行为。/ Placeholder: swallow clicks; buttons inert.
            int[] p = panelRect();
            if (mx >= p[0] && mx <= p[0] + p[2] && my >= p[1] && my <= p[1] + p[3]) return true;
        }
        if (open == Section.MONITOR) {
            // 只读面板：吞掉面板上的点击（防穿透画布）。/ Read-only panel: swallow clicks
            // inside the panel (no canvas click-through).
            int[] p = panelRect();
            if (mx >= p[0] && mx <= p[0] + p[2] && my >= p[1] && my <= p[1] + p[3]) return true;
        }
        if (open == Section.TERMINAL) {
            int[] p = panelRect();
            if (mx >= p[0] && mx <= p[0] + p[2] && my >= p[1] && my <= p[1] + p[3]) {
                int[] ir = inputRowRect();
                if (consoleInput != null && mx >= ir[0] && mx <= ir[0] + ir[2] && my >= ir[1] && my <= ir[1] + ir[3]) {
                    ed.unfocusAllEdits(); // 夺焦：其余编辑框让位 / take focus; other boxes yield
                    consoleInput.setFocused(true);
                    consoleInput.mouseClicked(mx, my, btn);
                }
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
        if (open == Section.TERMINAL) {
            // 终端输出区滚轮翻历史（精确钳制在渲染帧做）/ wheel over the console reviews
            // history (exact clamping happens on the render frame)
            int[] p = panelRect();
            if (mx >= p[0] && mx <= p[0] + p[2] && my >= p[1] && my <= p[1] + p[3]) {
                consoleScroll += sy > 0 ? 3 : -3;
                return true;
            }
        }
        return false;
    }
}
