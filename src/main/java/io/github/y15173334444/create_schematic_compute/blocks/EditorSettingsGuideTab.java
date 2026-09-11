package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

/**
 * 节点指南 tab（渲染/命中几何/详情面板），自 {@link EditorSettingsScreen} 拆分。
 * The node-guide tab (render, hit geometry and detail pane), split out of
 * {@link EditorSettingsScreen} (docs/gui-decomposition-plan.md step 3).
 *
 * <p><b>行为零变更</b>：实现逐字搬迁，只有隐式外层访问改为经 {@link EditorSettingsHost} 取得；
 * 本 tab 自己的状态（滚动/选中/滚动条拖拽）随实现搬入，屏幕的输入分发经访问器读写。
 * <b>Behaviour-preserving</b>: the implementation moved verbatim; implicit outer-class accesses now
 * go through {@link EditorSettingsHost}. This tab's own state moved with it and the screen's
 * input dispatch reaches it through accessors.</p>
 */
final class EditorSettingsGuideTab {

    private final EditorSettingsHost h;

    // ── 指南状态（原 EditorSettingsScreen 字段）/ guide state (moved from the screen) ──
    private int scroll = 0;
    private int target = -1;
    private int detailScroll = 0;
    private boolean scrollbarDrag = false;
    private float scrollbarDragStartY = 0f;
    private int scrollbarDragStartOff = 0;

    EditorSettingsGuideTab(EditorSettingsHost h) { this.h = h; }

    // ── 访问器：屏幕输入分发读写 / accessors used by the screen's input dispatch ──
    int scroll() { return scroll; }
    void setScroll(int v) { scroll = v; }
    int target() { return target; }
    void setTarget(int v) { target = v; }
    int detailScroll() { return detailScroll; }
    void setDetailScroll(int v) { detailScroll = v; }
    boolean scrollbarDrag() { return scrollbarDrag; }
    void setScrollbarDrag(boolean v) { scrollbarDrag = v; }
    void setScrollbarDragStart(float y, int off) { scrollbarDragStartY = y; scrollbarDragStartOff = off; }

    /** 指南行高。 / guide row height. */
    /** 指南行高（父类输入命中测试也要用，故为包级）。 / guide row height (package-level: the screen hit-test needs it too). */
    static final int GUIDE_ROW_H = 16;

    /** 列表顶部 y（提示行下方）。 / list top y (below the hint line). */
    int guideListTop() { return h.cy() + 18; }
    /** 列表底部 y。 / list bottom y. */
    int guideListBot() { return h.h() - 8 - 18; }
    int guideVisibleRows() { return Math.max(1, (guideListBot() - guideListTop()) / GUIDE_ROW_H); }
    int guideMaxScroll() { return Math.max(0, NodeType.values().length - guideVisibleRows()); }

    /** 行区右缘：展开态止于详情面板左侧（互不压叠），收起态到内容区右缘（预留滚动条条带）。
     *  Row right edge: expanded stops before the detail pane (no overlap); collapsed
     *  reaches the content right edge (minus the scrollbar strip). */
    int guideRowRight() {
        return h.expanded() ? paneX() - 12 : h.w() - 14 - h.slide() - 12;
    }

    /** 右侧详情面板宽度（随窗口自适应，钳制 300..520）。 / detail-pane width (window-adaptive, clamped 300..520). */
    private int guidePaneW() { return Math.max(300, Math.min(520, h.w() / 3)); }
    /** 面板左缘：屏幕右侧锚定（与调色板 / 虚拟键盘停靠同一约定）。 / pane left edge: right-anchored like the palette/keyboard. */
    int paneX() { return h.w() - 14 - guidePaneW(); }
    /** 面板内说明文本的换行宽度。 / wrapped-text width inside the pane. */
    private int paneTextW() { return guidePaneW() - 30; }
    /** 面板内容顶部 y。 / pane content top y. */
    private static int paneTop() { return 12; }
    /** 面板内容底部 y。 / pane content bottom y. */
    private int paneBot() { return h.h() - 8; }

    /** 指南列表滚动条 thumb {x, y, w, h}（轨道纵跨列表，几何与渲染/命中/拖拽共用）。
     *  Guide-list scrollbar thumb {x, y, w, h} (track spans the list; shared by render,
     *  hit-testing and dragging). */
    int[] guideScrollbarThumb(int rowRight) {
        int trackH = guideListBot() - guideListTop();
        int thumbH = Math.max(12, trackH * guideVisibleRows() / NodeType.values().length);
        int maxScroll = guideMaxScroll();
        int thumbY = guideListTop() + (maxScroll > 0 ? (trackH - thumbH) * scroll / maxScroll : 0);
        return new int[]{rowRight - 8, thumbY, 6, thumbH};
    }

    /** 拖拽推进指南列表：thumb 相对增量换算为行偏移（颜色/键位列表同款）。 / Advance the guide list by the dragged thumb delta. */
    void applyGuideScrollbarDrag(double my) {
        int maxScroll = guideMaxScroll();
        if (maxScroll <= 0) return;
        int trackH = guideListBot() - guideListTop();
        int thumbH = Math.max(12, trackH * guideVisibleRows() / NodeType.values().length);
        if (trackH - thumbH <= 0) return;
        float delta = (float) (my - scrollbarDragStartY) / (trackH - thumbH);
        scroll = Math.max(0, Math.min(maxScroll, scrollbarDragStartOff + Math.round(delta * maxScroll)));
    }

    /** 收起指南详情：退出展开态并清空选中/说明滚动。 / Collapse the guide detail: leave the expanded form, clear selection and description scroll. */
    void guideCollapse() {
        h.setExpanded(false);
        target = -1;
        detailScroll = 0;
        scrollbarDrag = false;
    }

    /** 选中节点的说明文本逐行换行行。 / the selected node's description wrapped into lines. */
    private java.util.List<String> guideDescLines(NodeType t) {
        String descKey = "gui.create_schematic_compute.guide." + t.name();
        String raw = I18n.exists(descKey) ? I18n.get(descKey)
            : I18n.get("gui.create_schematic_compute.settings.guide_missing");
        var out = new java.util.ArrayList<String>();
        String remaining = raw;
        int w = paneTextW();
        while (!remaining.isEmpty()) {
            String chunk = h.font().plainSubstrByWidth(remaining, w);
            if (chunk.isEmpty()) break;
            out.add(chunk);
            if (chunk.length() >= remaining.length()) break;
            remaining = remaining.substring(chunk.length());
        }
        return out;
    }

    /** 指南 tab 渲染：收起态 = 全宽节点列表，悬停行在底部显示一行截断说明；
     *  点击行左滑展开 —— 左侧节点栏 + 可拖拽滚动条，右侧详情面板（元信息 + 换行说明）。
     *  文案走 guide.<TYPE> lang 键；缺键时显示占位文案。
     *  Guide tab: collapsed = full-width node list (hover shows a truncated one-line
     *  description); a row click slides the UI left — node bar with a draggable
     *  scrollbar left, a detail pane right (metadata + wrapped description). Copy from
     *  guide.<TYPE> lang keys, with a placeholder when a key is missing. */
    void renderGuideTab(GuiGraphics g, int mx, int my, int cx, int contentBottom) {
        var types = NodeType.values();
        int rowH2 = GUIDE_ROW_H;
        int listTop = guideListTop(), listBot = guideListBot();
        int rowRight = guideRowRight();
        int maxScroll = guideMaxScroll();
        if (scroll < 0) scroll = 0;
        if (scroll > maxScroll) scroll = maxScroll;
        g.drawString(h.font(), "§7" + I18n.get("gui.create_schematic_compute.settings.guide_hint"), cx, h.cy(), 0xFFCCCCCC, false);

        NodeType hoveredGuide = null;
        g.enableScissor(cx, listTop, rowRight, listBot);
        for (int i = scroll; i < types.length; i++) {
            int ry = listTop + (i - scroll) * rowH2;
            if (ry + rowH2 > listBot) break;
            var t = types[i];
            boolean selected = h.expanded() && target == i;
            if (selected) g.fill(cx, ry, rowRight, ry + rowH2, NodeRenderer.HOV()); // 选中行 = 悬停高亮 / selected row = hover highlight
            else if (i % 2 == 0) g.fill(cx, ry, rowRight, ry + rowH2, NodeRenderer.PINS());
            if (mx >= cx && mx <= rowRight && my >= ry && my <= ry + rowH2) hoveredGuide = t;
            String name = I18n.get(t.displayName);
            if (h.expanded()) {
                // 展开态只显示名称（右侧面板承载其余信息），超宽截断。
                // Expanded rows show only the name, truncated; the pane carries the rest.
                g.drawString(h.font(), "§e" + name, cx + 4, ry + 4, selected ? NodeRenderer.ACC() : 0xFFCCCCCC, false);
                continue;
            }
            g.drawString(h.font(), "§e" + name, cx + 4, ry + 4, 0xFFCCCCCC, false);
            String pins = I18n.get("gui.create_schematic_compute.guide.inputs") + t.inputs
                + " → " + I18n.get("gui.create_schematic_compute.guide.outputs") + t.outputs;
            g.drawString(h.font(), "§7" + pins, cx + 170, ry + 4, 0xFF999999, false);
            if (t.paramNames.length > 0 && cx + 260 < rowRight - 12) {
                String params = String.join(", ", t.paramNames);
                params = h.font().plainSubstrByWidth("§8" + params, rowRight - (cx + 260) - 8);
                g.drawString(h.font(), params, cx + 260, ry + 4, 0xFF888888, false);
            }
        }
        g.disableScissor();

        // 收起态：悬停行在底部给一行截断说明；展开态由右侧面板呈现完整文案。
        // Collapsed: hovering shows a truncated one-line description at the bottom;
        // expanded shows the full copy in the detail pane instead.
        if (!h.expanded() && hoveredGuide != null) {
            String descKey = "gui.create_schematic_compute.guide." + hoveredGuide.name();
            if (I18n.exists(descKey)) {
                String desc = h.font().plainSubstrByWidth(I18n.get(descKey), rowRight - cx);
                g.drawString(h.font(), "§7" + desc, cx, contentBottom - 12, 0xFFCCCCCC, false);
            }
        }
        // 滚动条（thumb 可拖拽）/ scrollbar (draggable thumb)
        if (maxScroll > 0) {
            int[] sb = guideScrollbarThumb(rowRight);
            g.fill(sb[0], listTop, sb[0] + sb[2], listBot, NodeRenderer.PINS()); // 滚动条轨道 = 内凹井 / track = inset well
            g.fill(sb[0] + 1, sb[1], sb[0] + sb[2] - 1, sb[1] + sb[3], NodeRenderer.CSB());
        }
        // 展开态：列表下方收起按钮 + 右侧详情面板 / expanded: collapse button under the list + the detail pane
        if (h.expanded()) {
            int clY = guideListBot() + 2;
            boolean clHov = mx >= cx && mx <= cx + 64 && my >= clY && my <= clY + 16;
            g.fill(cx, clY, cx + 64, clY + 16, clHov ? NodeRenderer.HOV() : NodeRenderer.PBG());
            g.renderOutline(cx, clY, 64, 16, NodeRenderer.CSB());
            g.drawString(h.font(), "§f" + I18n.get("gui.create_schematic_compute.settings.collapse"), cx + 16, clY + 4, 0xFFFFFFFF, false);
            renderGuidePane(g, types);
        }
    }

    /** 右侧详情面板：选中节点名 + 元信息（入/出/参数）+ 换行详述（超长可滚）。
     *  Detail pane: node name + metadata (in/out/params) + wrapped description (scrollable). */
    private void renderGuidePane(GuiGraphics g, NodeType[] types) {
        if (target < 0 || target >= types.length) return;
        NodeType t = types[target];
        int px = paneX(), pw = guidePaneW(), pb = paneBot();
        int top = paneTop();
        g.fill(px, top - 4, px + pw, pb, NodeRenderer.PINS()); // 详情面板底 = 内凹井 / detail pane bg = inset well
        g.renderOutline(px, top - 4, pw, pb - (top - 4), NodeRenderer.CSB());
        int tx = px + 10;
        g.drawString(h.font(), "§6" + I18n.get(t.displayName), tx, top, 0xFFFFFFFF, false);
        g.drawString(h.font(), "§8" + t.name(), px + pw - 10 - h.font().width(t.name()), top, 0xFF888888, false);
        int y = top + 12;
        String meta = I18n.get("gui.create_schematic_compute.guide.inputs") + " " + t.inputs
            + "    " + I18n.get("gui.create_schematic_compute.guide.outputs") + " " + t.outputs;
        g.drawString(h.font(), "§7" + meta, tx, y, 0xFFCCCCCC, false);
        y += 12;
        if (t.paramNames.length > 0) {
            String params = I18n.get("gui.create_schematic_compute.settings.guide_params") + " "
                + h.font().plainSubstrByWidth(String.join(", ", t.paramNames), pw - 36);
            g.drawString(h.font(), "§7" + params, tx, y, 0xFF999999, false);
            y += 12;
        }
        g.fill(px + 6, y, px + pw - 6, y + 1, NodeRenderer.PBG()); // 分区细线 = 面板底高光细线 / section rule = panel-bg hairline
        y += 6;
        var lines = guideDescLines(t);
        int innerRight = px + pw - 10;
        int innerBot = pb - 4;
        int visible = Math.max(1, (innerBot - y) / 11);
        int maxScroll = Math.max(0, lines.size() - visible);
        if (detailScroll < 0) detailScroll = 0;
        if (detailScroll > maxScroll) detailScroll = maxScroll;
        g.enableScissor(tx, y, innerRight, innerBot);
        int ly = y - detailScroll * 11;
        for (String ln : lines) {
            g.drawString(h.font(), "§7" + ln, tx, ly, 0xFFCCCCCC, false);
            ly += 11;
            if (ly > innerBot + 11) break;
        }
        g.disableScissor();
        // 说明文本滚动指示条（滚轮滚动）/ description scroll indicator (wheel-scrolled)
        if (maxScroll > 0) {
            int trackH = innerBot - y;
            float thumbH = Math.max(10, trackH * (float) visible / lines.size());
            float thumbY = y + (trackH - thumbH) * detailScroll / maxScroll;
            g.fill(px + pw - 8, y, px + pw - 4, innerBot, NodeRenderer.PINS()); // 滚动条轨道 = 内凹井 / track = inset well
            g.fill(px + pw - 7, (int) thumbY, px + pw - 5, (int) (thumbY + thumbH), NodeRenderer.CSB());
        }
    }
}
