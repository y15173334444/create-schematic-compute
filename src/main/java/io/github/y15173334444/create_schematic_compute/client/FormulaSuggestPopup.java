package io.github.y15173334444.create_schematic_compute.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * A lightweight suggestion dropdown rendered as an overlay near the caret
 * in {@link MultiLineEditBox}.  Owned by the edit box, rendered by the
 * parent {@code NodeRenderer} at z-layer C=5.5 (after all pins).
 *
 * <p>Each candidate carries a display name, an optional grey signature hint,
 * and the text that should be inserted upon acceptance.</p>
 *
 * 轻量级建议下拉框，作为覆盖层渲染在 {@link MultiLineEditBox} 的光标附近。
 * 由编辑框持有，由父级 {@code NodeRenderer} 在 z 层 C=5.5（所有引脚之后）渲染。
 *
 * <p>每个候选项携带显示名称、可选灰色签名提示以及接受后应插入的文本。</p>
 */
public class FormulaSuggestPopup {

    /** A single autocomplete entry / 单个自动补全条目 */
    public record Candidate(String name, String signature, String insertText) {}

    private final List<Candidate> candidates = new ArrayList<>();
    private int selected = 0;
    private boolean visible = false;

    /** Anchor position (in the edit box's local coordinate space).
     *  Updated every frame by renderWidget and triggerCompletion.
     *  锚点位置（编辑框局部坐标空间），每帧由 renderWidget 和 triggerCompletion 更新。 */
    public int anchorX, anchorY;
    /** Last rendered bounds (for click detection) / 上次渲染边界（用于点击检测） */
    public int renderedX, renderedY, renderedW, renderedH;

    // ── State / 状态 ──

    public boolean isVisible() { return visible && !candidates.isEmpty(); }

    public List<Candidate> getCandidates() { return candidates; }
    public int getSelectedIndex() { return selected; }
    public Candidate getSelected() {
        return (selected >= 0 && selected < candidates.size()) ? candidates.get(selected) : null;
    }

    /** Open the popup with a pre-filtered list. / 用预过滤的列表打开弹出框。 */
    public void open(int anchorX, int anchorY, List<Candidate> list) {
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.candidates.clear();
        if (list != null) this.candidates.addAll(list);
        this.selected = 0;
        this.visible = !this.candidates.isEmpty();
    }

    /** Close the popup and clear state. / 关闭弹出框并清除状态。 */
    public void close() { visible = false; candidates.clear(); selected = 0; }

    // ── Keyboard / 键盘操作 ──

    /** Move selection up / 向上移动选择 */
    public void moveUp()   { if (isVisible()) selected = Math.max(0, selected - 1); }
    /** Move selection down / 向下移动选择 */
    public void moveDown() { if (isVisible()) selected = Math.min(candidates.size() - 1, selected + 1); }

    /** Accept the currently-selected candidate and return its insert text, closing the popup.
     *  接受当前选中的候选项并返回其插入文本，同时关闭弹出框。 */
    public String acceptSelected() {
        Candidate c = getSelected();
        String text = c != null ? c.insertText() : null;
        close();
        return text;
    }

    // ── Mouse click / 鼠标点击 ──

    /**
     * Handle a mouse click.  If the click falls on a popup item, select it,
     * accept it, and return the insert text.  Returns null if the click is
     * outside the popup or the popup is not visible.
     *
     * 处理鼠标点击。如果点击落在弹出框条目上，选中它、接受它并返回插入文本。
     * 如果点击在弹出框外或弹出框不可见，返回 null。
     */
    public String mouseClicked(int mx, int my) {
        if (!isVisible()) return null;
        int x = renderedX, y = renderedY;
        if (mx < x || mx > x + renderedW || my < y || my > y + renderedH) return null;
        int relY = my - y - PAD_Y;
        int idx = relY / ITEM_H;
        if (idx >= 0 && idx < candidates.size()) {
            selected = idx;
            return acceptSelected();
        }
        return null;
    }

    // ── Filtering (static helper) / 过滤（静态辅助方法） ──

    /**
     * Filter candidates whose name contains the prefix (case-insensitive),
     * returning at most {@code max} results.  Candidates that start with the
     * prefix are ordered before those that merely contain it; each group is
     * sorted alphabetically by name.
     *
     * 过滤名称包含前缀（不区分大小写）的候选项，最多返回 {@code max} 个结果。
     * 以前缀开头的候选项排在仅包含前缀的候选项之前；每组按名称字母排序。
     */
    public static List<Candidate> filter(List<Candidate> all, String prefix, int max) {
        if (all == null || prefix == null || prefix.isEmpty()) return List.of();
        String lower = prefix.toLowerCase();
        var starts = new ArrayList<Candidate>();
        var contains = new ArrayList<Candidate>();
        for (var c : all) {
            String n = c.name().toLowerCase();
            if (n.equals(lower)) { starts.add(c); }          // exact match / 精确匹配
            else if (n.startsWith(lower)) starts.add(c);      // prefix match / 前缀匹配
            else if (n.contains(lower)) contains.add(c);       // substring match / 子串匹配
        }
        // Sort each group alphabetically / 每组按字母排序
        starts.sort(java.util.Comparator.comparing(c -> c.name().toLowerCase()));
        contains.sort(java.util.Comparator.comparing(c -> c.name().toLowerCase()));
        var result = new ArrayList<Candidate>(starts);
        result.addAll(contains);
        return result.size() <= max ? result : result.subList(0, max);
    }

    // ── Rendering / 渲染 ──

    // Colours / 颜色
    private static final int BG_COLOR     = 0x881E1A16;  // dark brown / 深棕色半透明
    private static final int BORDER_COLOR = 0x665A5A50;  // muted gold / 暗金色
    private static final int TEXT_COLOR   = 0xFFE0E0E0;  // light grey / 浅灰色
    private static final int SIG_COLOR    = 0xFF909090;  // medium grey (signature hint) / 中灰色（签名提示）
    private static final int SEL_BG       = 0x663A5A8C;  // blue highlight / 蓝色高亮

    // Layout constants / 布局常量
    private static final int ITEM_H = 14;   // item height / 条目高度
    private static final int PAD_X = 6;     // horizontal padding / 水平内边距
    private static final int PAD_Y = 2;     // vertical padding / 垂直内边距

    /**
     * Render the dropdown at the given screen-space position.
     * The caller is responsible for any pose transforms (translate + scale).
     *
     * 在给定的屏幕空间位置渲染下拉框。
     * 调用者负责所有姿态变换（平移 + 缩放）。
     */
    public void render(GuiGraphics g, Font font, int clampedX, int clampedY, int maxWidth) {
        if (!isVisible()) return;
        int n = candidates.size();
        int w = Math.min(maxItemWidth(font, candidates), maxWidth > 0 ? maxWidth : 200);
        int h = n * ITEM_H + PAD_Y * 2;
        int x = clampedX;
        int y = clampedY;

        // Store bounds for click detection / 存储边界用于点击检测
        renderedX = x; renderedY = y; renderedW = w; renderedH = h;

        // Background + border / 背景 + 边框
        g.fill(x, y, x + w, y + h, BG_COLOR);
        g.renderOutline(x, y, w, h, BORDER_COLOR);

        // Items / 条目
        for (int i = 0; i < n; i++) {
            int iy = y + PAD_Y + i * ITEM_H;
            Candidate c = candidates.get(i);
            boolean sel = (i == selected);

            // Selection background / 选中背景
            if (sel) g.fill(x + 1, iy, x + w - 1, iy + ITEM_H, SEL_BG);

            // Item name / 条目名称
            int tx = x + PAD_X;
            g.drawString(font, c.name(), tx, iy + 1, TEXT_COLOR, false);

            // Signature hint in grey / 灰色签名提示
            if (c.signature() != null && !c.signature().isEmpty()) {
                int sigX = tx + font.width(c.name()) + 6;
                g.drawString(font, "§7" + c.signature(), sigX, iy + 1, SIG_COLOR, false);
            }
        }
    }

    /** Compute the maximum pixel width across all candidates. / 计算所有候选项的最大像素宽度。 */
    private static int maxItemWidth(Font font, List<Candidate> list) {
        int max = 60;  // minimum width / 最小宽度
        for (var c : list) {
            int w = font.width(c.name());
            if (c.signature() != null && !c.signature().isEmpty()) w += 6 + font.width(c.signature());
            max = Math.max(max, w);
        }
        return max + PAD_X * 2 + 4;
    }
}
