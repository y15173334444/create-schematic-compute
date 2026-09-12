package io.github.y15173334444.create_schematic_compute.blocks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

/**
 * 视角书签面板 + 相机过渡（自 {@link GraphEditor} 拆分，docs/gui-decomposition-plan.md 步骤 6e）：
 * 书签列表面板（右下角，滚动条 + 行按钮 ✎/→/× + 拖拽排序幽灵行）、书签命名对话框、
 * 相机过渡动画（200ms ease-in-out，书签跳转/重置视角共用）、临时视角（按方块位置跨编辑器
 * 实例恢复）、右下角书签开关按钮。
 * The view-bookmark panel + camera transitions (split out of {@link GraphEditor}, roadmap step 6e):
 * the bookmark list panel (bottom-right; scrollbar, per-row ✎/→/× buttons, drag-reorder ghost),
 * the bookmark name dialog, the camera transition animation (200 ms ease-in-out, shared by jumps
 * and reset-view), the temporary view (keyed by block position, restored across editor instances)
 * and the bottom-right bookmark toggle button.
 *
 * <p><b>行为零变更</b>：方法体逐字搬迁。输入处理块改为返回"是否消费"的布尔方法，调用点在
 * {@code GraphEditor} 各输入方法中以 {@code if (viewBookmarks.handleX(...)) return;} 保持原有
 * 控制流与先后顺序（书签拖拽 → 菜单滚动条 → 书签滚动条等）。编辑器状态经传入的 {@code ed}
 * 引用访问（同包，仅 {@code editingCommentColorNode}、{@code scrollDragStartY/Off} 放宽到包级——
 * 后者是与注释/导入滚动条共享的拖拽状态，留在编辑器）。
 * <b>Behaviour-preserving</b>: bodies moved verbatim. Input blocks become boolean "consumed"
 * methods; {@code GraphEditor} keeps the original control flow and ordering via
 * {@code if (viewBookmarks.handleX(...)) return;} (bookmark drag → menu scrollbar → bookmark
 * scrollbar, etc.). Editor state is reached through the passed-in {@code ed} reference (same
 * package; only {@code editingCommentColorNode} and {@code scrollDragStartY/Off} widened to
 * package level — the latter is drag state shared with the comment/import scrollbars and stays
 * on the editor).</p>
 *
 * <p>外部契约不变：{@code GraphEditor.clearTempView()}（主类初始化调用）保留为静态委托。
 * External contracts unchanged: {@code GraphEditor.clearTempView()} (called from the mod class)
 * remains as a static delegate.</p>
 */
final class GraphViewBookmarks {

    private final GraphEditor ed;

    GraphViewBookmarks(GraphEditor ed) {
        this.ed = ed;
    }

    // ── 相机过渡动画 / Camera transition animation ──
    /** 过渡起始相机状态 / transition start camera state */
    private float transFromX, transFromY, transFromZoom;
    /** 过渡目标相机状态 / transition target camera state */
    private float transToX, transToY, transToZoom;
    /** 过渡开始时间戳 / transition start timestamp (ms) */
    private long transStartMs = 0;
    /** 视角过渡持续时间（毫秒）/ camera transition duration in milliseconds */
    private static final long TRANSITION_MS = 200;

    /** 启动视角过渡动画。 / Start a camera transition animation. */
    void startTransition(float toX, float toY, float toZoom) {
        transFromX = ed.camX; transFromY = ed.camY; transFromZoom = ed.zoom;
        transToX = toX; transToY = toY; transToZoom = toZoom;
        transStartMs = System.currentTimeMillis();
    }

    /** 每帧推进过渡动画（ease-in-out）。 / Advance transition animation per frame (ease-in-out). */
    void advanceCameraTransition() {
        if (transStartMs == 0) return;
        long elapsed = System.currentTimeMillis() - transStartMs;
        float t = Math.min(1f, elapsed / (float) TRANSITION_MS);
        float e = t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2;
        ed.camX = lerp(transFromX, transToX, e);
        ed.camY = lerp(transFromY, transToY, e);
        ed.zoom = lerp(transFromZoom, transToZoom, e);
        if (t >= 1f) transStartMs = 0;
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    // ── 临时视角 / Temporary view (keyed by block position) ──
    private static final java.util.Map<net.minecraft.core.BlockPos, float[]> tempViewByPos = new java.util.HashMap<>();

    /** 清空所有临时视角（session 级）。 / Clear all temp views (session level). */
    public static void clearTempView() { tempViewByPos.clear(); }

    /** 编辑器构造时按方块位置恢复相机。 / Restore camera by block position on editor construction. */
    void restoreTempView() {
        var bp = ed.host.getBlockPos();
        if (bp != null) {
            float[] saved = tempViewByPos.get(bp);
            if (saved != null) { ed.camX = saved[0]; ed.camY = saved[1]; ed.zoom = saved[2]; }
        }
    }

    /** 编辑器关闭时保存当前相机。 / Save current camera when the editor closes. */
    void saveTempView() {
        var bp = ed.host.getBlockPos();
        if (bp != null) tempViewByPos.put(bp, new float[]{ed.camX, ed.camY, ed.zoom});
    }

    // ── 书签面板 UI 状态 / Bookmark panel UI state ──
    /** 书签列表面板是否可见 / whether the bookmark list panel is visible */
    private boolean showBookmarkPanel = false;
    /** 书签名称草稿（新建/重命名时使用）/ draft bookmark name (used when creating/renaming) */
    private String bookmarkNameDraft = "";
    /** 是否正在编辑书签名称 / whether bookmark name editing is active */
    private boolean editingBookmarkName = false;
    private int editingBookmarkIndex = -1; // -1 = 新建, >= 0 = 重命名 / -1 = new, >= 0 = renaming
    private int bookmarkScrollOff = 0; // 书签面板滚动偏移 / bookmark panel scroll offset
    private boolean scrollingBookmark = false;
    private int draggingBookmarkIdx = -1; // 书签拖拽排序 / bookmark drag reorder
    private float bookmarkDragY = 0;       // 拖拽时的鼠标 Y / mouse Y during drag

    /** 面板可见性（渲染镜像到 NodeRenderer 用）。 / Panel visibility (mirrored to NodeRenderer for rendering). */
    boolean panelVisible() { return showBookmarkPanel; }

    /** 渲染书签列表面板与命名对话框（renderBg A=4 层调用）。
     *  Render the bookmark list panel and the name dialog (renderBg layer A=4). */
    void render(GuiGraphics g, int mx, int my) {
        // 书签列表面板（右下角，带滚动条） / bookmark list panel (bottom-right, with scrollbar)
        if (showBookmarkPanel) {
            var mc = Minecraft.getInstance();
            var bks = ed.getGraph().bookmarks;
            int panelW = 180;
            int rowH = 16;
            int maxRows = 5;
            int titleH = 16;
            int btnRowH = 18;
            int totalRows = bks.size();
            int visibleRows = Math.min(totalRows, maxRows);
            int panelH = titleH + btnRowH + 6 + Math.max(visibleRows, 1) * rowH + 10;
            int panelX = ed.host.asScreen().width - panelW - 4;
            int panelY = ed.host.asScreen().height - 44 - panelH; // 在 ★ 按钮上方
            // 限制滚动偏移 / clamp scroll offset
            if (bookmarkScrollOff < 0) bookmarkScrollOff = 0;
            if (bookmarkScrollOff > Math.max(0, totalRows - maxRows)) bookmarkScrollOff = Math.max(0, totalRows - maxRows);
            // 面板背景 / panel background
            g.fill(panelX, panelY, panelX + panelW, panelY + panelH, NodeRenderer.withAlpha(NodeRenderer.PBG(), 0xEE));
            g.renderOutline(panelX, panelY, panelW, panelH, NodeRenderer.CSB());
            // 标题 / title
            g.drawString(mc.font, I18n.get("gui.create_schematic_compute.bookmark.title"), panelX + 6, panelY + 4, 0xFFFFCC88, false);
            // 操作按钮行 / action button row
            int btnY = panelY + titleH + 2;
            // [+ 保存当前] 按钮
            int addBX = panelX + 4, addBW = 72;
            boolean addHover = mx >= addBX && mx < addBX + addBW && my >= btnY && my < btnY + btnRowH;
            g.fill(addBX, btnY, addBX + addBW, btnY + btnRowH, addHover ? 0xFF3A5A3A : 0xFF3A3A2A);
            g.renderOutline(addBX, btnY, addBW, btnRowH, NodeRenderer.CSB());
            g.drawString(mc.font, "+ " + I18n.get("gui.create_schematic_compute.bookmark.add"), addBX + 4, btnY + 4, 0xFFAAFFAA, false);
            // [↺ 重置] 按钮
            int rstBX = panelX + 78, rstBW = 96;
            boolean rstHover = mx >= rstBX && mx < rstBX + rstBW && my >= btnY && my < btnY + btnRowH;
            g.fill(rstBX, btnY, rstBX + rstBW, btnY + btnRowH, rstHover ? 0xFF3A5A3A : 0xFF3A3A2A);
            g.renderOutline(rstBX, btnY, rstBW, btnRowH, NodeRenderer.CSB());
            g.drawString(mc.font, "↺ " + I18n.get("gui.create_schematic_compute.bookmark.reset_view"), rstBX + 4, btnY + 4, 0xFFCCCCCC, false);
            // 分隔线 / separator
            int sepY = btnY + btnRowH + 2;
            g.fill(panelX + 4, sepY, panelX + panelW - 4, sepY + 1, NodeRenderer.PBR());
            // 书签列表 / bookmark list
            int listTopY = sepY + 3;
            for (int i = 0; i < visibleRows; i++) {
                int idx = i + bookmarkScrollOff;
                if (idx >= totalRows) break;
                var bm = bks.get(idx);
                int ry = listTopY + i * rowH;
                // 行背景（悬停高亮） / row hover highlight
                boolean hover = mx >= panelX && mx < panelX + panelW - 10 && my >= ry && my < ry + rowH;
                if (hover) g.fill(panelX + 2, ry, panelX + panelW - 2, ry + rowH, NodeRenderer.HOV());
                // 序号 + 名称 / index + name
                int nameMaxW = panelW - 48;
                String label = (idx < 9 ? (idx + 1) + ". " : "   ") + bm.name();
                if (mc.font.width(label) > nameMaxW) {
                    String trunc = mc.font.plainSubstrByWidth(label, nameMaxW - 8) + "…";
                    g.drawString(mc.font, trunc, panelX + 6, ry + 4, 0xFFCCCCCC, false);
                } else {
                    g.drawString(mc.font, label, panelX + 6, ry + 4, 0xFFCCCCCC, false);
                }
                // 重命名按钮 ✎ / rename button
                boolean renHover = hover && mx >= panelX + panelW - 58 && mx < panelX + panelW - 44;
                g.drawString(mc.font, renHover ? "§e✎" : "§7✎", panelX + panelW - 58, ry + 4, 0xFFFFCC44, false);
                // 跳转按钮 → / jump button
                boolean jmpHover = hover && mx >= panelX + panelW - 42 && mx < panelX + panelW - 28;
                g.drawString(mc.font, jmpHover ? "§a→" : "§7→", panelX + panelW - 42, ry + 4, 0xFF88FF88, false);
                // 删除按钮 × / delete button
                boolean delHover = hover && mx >= panelX + panelW - 26;
                g.drawString(mc.font, delHover ? "§c×" : "§7×", panelX + panelW - 26, ry + 4, 0xFFFF6666, false);
            }
            // 空列表提示 / empty list hint
            if (totalRows == 0) {
                g.drawString(mc.font, "§7(" + I18n.get("gui.create_schematic_compute.bookmark.empty") + ")", panelX + 6, listTopY + 4, 0xFF888888, false);
            }
            // 拖拽幽灵行 / drag ghost row
            if (draggingBookmarkIdx >= 0 && draggingBookmarkIdx < totalRows) {
                int ghostRowH = rowH + 2;
                int ghostY = Math.max(listTopY, Math.min((int)bookmarkDragY - ghostRowH / 2, listTopY + visibleRows * rowH - ghostRowH));
                g.fill(panelX + 2, ghostY, panelX + panelW - 12, ghostY + ghostRowH, 0xBB3A3A38);
                g.renderOutline(panelX + 2, ghostY, panelW - 14, ghostRowH, 0xFFFFCC44);
                var bm = bks.get(draggingBookmarkIdx);
                g.drawString(mc.font, "↕ " + bm.name(), panelX + 8, ghostY + 3, 0xFFFFFF88, false);
            }
            // 滚动条 / scrollbar
            if (totalRows > maxRows) {
                int sbX = panelX + panelW - 8;
                int sbH = visibleRows * rowH;
                int sbY = listTopY;
                g.fill(sbX, sbY, sbX + 6, sbY + sbH, NodeRenderer.PINS());
                int thumbH = Math.max(10, sbH * maxRows / totalRows);
                int maxOff = Math.max(1, totalRows - maxRows);
                int thumbY = sbY + (sbH - thumbH) * bookmarkScrollOff / maxOff;
                g.fill(sbX, thumbY, sbX + 6, thumbY + thumbH, NodeRenderer.CSB());
            }
        }
        // 书签命名对话框（在面板之后渲染，位于上方；底部 确认/取消 按钮 —— 纯鼠标可完成，
        // Enter/Esc 键盘路径保留）/ bookmark name dialog (rendered after panel, on top; the
        // Confirm/Cancel buttons at the bottom make it mouse-only — Enter/Esc keep working)
        if (editingBookmarkName) {
            var mc = Minecraft.getInstance();
            int cx = dlgX(), cy = dlgY();
            g.fill(cx, cy, cx + DLG_W, cy + DLG_H, NodeRenderer.withAlpha(NodeRenderer.PBG(), 0xEE));
            g.renderOutline(cx, cy, DLG_W, DLG_H, NodeRenderer.CSB());
            g.drawString(mc.font, I18n.get("gui.create_schematic_compute.bookmark.name"), cx + 8, cy + 6, 0xFFFFCC88, false);
            g.fill(cx + 8, cy + 26, cx + DLG_W - 8, cy + 46, 0xFF000000);
            g.renderOutline(cx + 8, cy + 26, DLG_W - 16, 20, 0xFF6A6A6A);
            g.drawString(mc.font, bookmarkNameDraft + "_", cx + 12, cy + 31, 0xFFFFFFFF, false);
            int[] cr = confirmRect(), xr = cancelRect();
            boolean cHover = mx >= cr[0] && mx < cr[0] + cr[2] && my >= cr[1] && my < cr[1] + cr[3];
            g.fill(cr[0], cr[1], cr[0] + cr[2], cr[1] + cr[3], cHover ? 0xFF3A6A3A : 0xFF2A4A2A);
            g.renderOutline(cr[0], cr[1], cr[2], cr[3], NodeRenderer.CSB());
            g.drawString(mc.font, I18n.get("gui.create_schematic_compute.bookmark.confirm"), cr[0] + 4, cr[1] + 5, 0xFFAAFFAA, false);
            boolean xHover = mx >= xr[0] && mx < xr[0] + xr[2] && my >= xr[1] && my < xr[1] + xr[3];
            g.fill(xr[0], xr[1], xr[0] + xr[2], xr[1] + xr[3], xHover ? 0xFF6A3A3A : 0xFF4A2A2A);
            g.renderOutline(xr[0], xr[1], xr[2], xr[3], NodeRenderer.CSB());
            g.drawString(mc.font, I18n.get("gui.create_schematic_compute.bookmark.cancel"), xr[0] + 4, xr[1] + 5, 0xFFFFAAAA, false);
            g.drawString(mc.font, "§7Enter §r确认 | §7Esc §r取消", cx + 8, cy + 78, 0xFFAAAAAA, false);
        }
    }

    /** 命名对话框尺寸（渲染与命中共用单一来源）。 / Name-dialog size (one source for render and hit-testing). */
    private static final int DLG_W = 280, DLG_H = 92;
    /** 按钮尺寸（确认/取消共用高度）。 / Button size (Confirm/Cancel share the height). */
    private static final int DLG_BTN_W = (DLG_W - 24) / 2, DLG_BTN_H = 18;

    /** 对话框左上角。 / Dialog top-left corner. */
    private int dlgX() { return (ed.host.asScreen().width - DLG_W) / 2; }
    private int dlgY() { return (ed.host.asScreen().height - DLG_H) / 2; }
    /** 确认/取消按钮矩形 {x,y,w,h}（并排于对话框底部）。 / Confirm/Cancel button rects {x,y,w,h} (side by side at the bottom). */
    private int[] confirmRect() { return new int[]{dlgX() + 8, dlgY() + 54, DLG_BTN_W, DLG_BTN_H}; }
    private int[] cancelRect() { return new int[]{dlgX() + DLG_W - 8 - DLG_BTN_W, dlgY() + 54, DLG_BTN_W, DLG_BTN_H}; }

    /** 提交命名对话框（新建/重命名）——与 Enter 完全同路径：空名仅关闭不保存。
     *  Commit the name dialog (add/rename) — exactly the Enter path: an empty draft just closes. */
    private void confirmNameDialog() {
        if (!bookmarkNameDraft.isEmpty()) {
            var bmGraph = ed.getGraph();
            if (editingBookmarkIndex >= 0) {
                // 重命名：本地先应用 / rename: apply locally first
                var bks = bmGraph.bookmarks;
                if (editingBookmarkIndex >= 0 && editingBookmarkIndex < bks.size()) {
                    var old = bks.get(editingBookmarkIndex);
                    bks.set(editingBookmarkIndex, new io.github.y15173334444.create_schematic_compute.graph.NodeGraph.Bookmark(bookmarkNameDraft, old.camX(), old.camY(), old.zoom()));
                    bmGraph.bumpGeneration();
                }
                ed.host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.renameBookmark(
                    ed.host.getBlockPos(), ed.ownerNodeId(), editingBookmarkIndex, bookmarkNameDraft, ed.host.getPlayerUUID()));
            } else {
                // 新建：本地先应用 / add: apply locally first
                bmGraph.bookmarks.add(new io.github.y15173334444.create_schematic_compute.graph.NodeGraph.Bookmark(bookmarkNameDraft, ed.camX, ed.camY, ed.zoom));
                bmGraph.bumpGeneration();
                ed.host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.addBookmark(
                    ed.host.getBlockPos(), ed.ownerNodeId(), bookmarkNameDraft, ed.camX, ed.camY, ed.zoom, ed.host.getPlayerUUID()));
            }
        }
        editingBookmarkName = false;
        editingBookmarkIndex = -1;
    }

    /** 关闭命名对话框不保存 —— 与 Esc 完全同路径。 / Close the name dialog without saving — exactly the Esc path. */
    private void cancelNameDialog() {
        editingBookmarkName = false;
        editingBookmarkIndex = -1;
    }

    /** 命名对话框点击（mouseClicked 早段）：确认/取消按钮提交或关闭；框内其余点击消费
     *  （模态，不再穿透到画布）；点击外部取消。返回 true 表示已消费。
     *  Name-dialog clicks (early in mouseClicked): the Confirm/Cancel buttons commit or close,
     *  clicks elsewhere inside the dialog are consumed (modal — no fall-through to the canvas),
     *  clicking outside cancels. Returns true if consumed. */
    boolean handleNameDialogClick(double mx, double my) {
        if (!editingBookmarkName) return false;
        int cx = dlgX(), cy = dlgY();
        if (mx < cx || mx > cx + DLG_W || my < cy || my > cy + DLG_H) {
            cancelNameDialog();
            return true;
        }
        int[] cr = confirmRect(), xr = cancelRect();
        if (mx >= cr[0] && mx < cr[0] + cr[2] && my >= cr[1] && my < cr[1] + cr[3]) {
            confirmNameDialog();
            return true;
        }
        if (mx >= xr[0] && mx < xr[0] + xr[2] && my >= xr[1] && my < xr[1] + xr[3]) {
            cancelNameDialog();
            return true;
        }
        return true;
    }

    /** 书签面板交互（仅在面板显示、无弹窗、无命名对话框时）。返回 true 表示已消费。
     *  Bookmark panel interaction (only while the panel is shown, no dialogs open).
     *  Returns true if consumed. */
    boolean handlePanelClick(double mx, double my, int btn) {
        var graph = ed.getGraph();
        if (showBookmarkPanel && !editingBookmarkName && !ed.showExportDialog && !ed.showImportDialog && !ed.colorPicker.isVisible() && ed.editingCommentColorNode == null) {
            int panelW = 180, rowH = 16, maxRows = 5, titleH = 16, btnRowH = 18;
            var bks = graph.bookmarks;
            int totalRows = bks.size();
            int visibleRows = Math.min(totalRows, maxRows);
            int panelX = ed.host.asScreen().width - panelW - 4;
            int panelY = ed.host.asScreen().height - 44 - (titleH + btnRowH + 6 + Math.max(visibleRows, 1) * rowH + 10) - 4;
            int panelH = titleH + btnRowH + 6 + Math.max(visibleRows, 1) * rowH + 10;
            int listTopY = panelY + titleH + btnRowH + 4;
            // [+ 保存] [↺ 重置] 按钮行
            if (btn == 0 && my >= panelY + titleH && my < listTopY) {
                if (mx >= panelX + 4 && mx < panelX + 4 + 70) {
                    // [+ 保存当前]
                    editingBookmarkName = true;
                    editingBookmarkIndex = -1;
                    bookmarkNameDraft = I18n.get("gui.create_schematic_compute.bookmark.new") + " " + (bks.size() + 1);
                    return true;
                }
                if (mx >= panelX + 78 && mx < panelX + 78 + 96) {
                    // [↺ 重置视角]
                    startTransition(0, 0, 1f);
                    return true;
                }
            }
            if (btn == 0 && mx >= panelX && mx < panelX + panelW && my >= listTopY && my < panelY + panelH) {
                // 滚动条拖拽/点击优先（拦截整个滚动条区域）
                if (totalRows > maxRows && mx >= panelX + panelW - 14) {
                    int sbY = listTopY, sbH = visibleRows * rowH;
                    int thumbH = Math.max(10, sbH * maxRows / totalRows);
                    int maxOff = Math.max(1, totalRows - maxRows);
                    int thumbY = sbY + (sbH - thumbH) * bookmarkScrollOff / maxOff;
                    if (my < thumbY) { bookmarkScrollOff = Math.max(0, bookmarkScrollOff - 3); }  // 点上方→上滚
                    else if (my > thumbY + thumbH) { bookmarkScrollOff = Math.min(maxOff, bookmarkScrollOff + 3); } // 点下方→下滚
                    else { scrollingBookmark = true; ed.scrollDragStartY = (float)my; ed.scrollDragStartOff = bookmarkScrollOff; } // 拖拽thumb
                    return true;
                }
                int ry = (int)((my - listTopY) / rowH);
                if (ry >= 0 && ry < visibleRows) {
                    int idx = ry + bookmarkScrollOff;
                    if (idx >= 0 && idx < totalRows) {
                        if (mx >= panelX + panelW - 26) {
                            bks.remove(idx); graph.bumpGeneration();
                            ed.host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.removeBookmark(
                                ed.host.getBlockPos(), ed.ownerNodeId(), idx, ed.host.getPlayerUUID()));
                        } else if (mx >= panelX + panelW - 58 && mx < panelX + panelW - 44) {
                            editingBookmarkName = true; editingBookmarkIndex = idx;
                            bookmarkNameDraft = bks.get(idx).name();
                        } else if (mx >= panelX + panelW - 42 && mx < panelX + panelW - 28) {
                            startTransition(bks.get(idx).camX(), bks.get(idx).camY(), bks.get(idx).zoom());
                        } else {
                            // 点击名称 → 开始拖拽 / name → start drag (release without move = jump)
                            draggingBookmarkIdx = idx;
                            bookmarkDragY = (float)my;
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** 右下角书签按钮（在三角形上方）。返回 true 表示已消费。
     *  Bottom-right bookmark button, above the triangle. Returns true if consumed. */
    boolean handleBookmarkButtonToggle(double mx, double my) {
        { int w = ed.host.asScreen().width, h = ed.host.asScreen().height;
          if(mx>=w-22&&mx<=w-4&&my>=h-44&&my<=h-26){
            showBookmarkPanel = !showBookmarkPanel;
            bookmarkScrollOff = 0;
            if (showBookmarkPanel) { ed.colorPicker.close(); ed.showExportDialog = false; ed.showImportDialog = false; }
            return true;
          } }
        return false;
    }

    /** 书签拖拽排序中的鼠标 Y 更新（mouseDragged 早期检查）。返回 true 表示已消费。
     *  Update drag Y while reordering. Returns true if consumed. */
    boolean handleDragReorder(double my) {
        if (draggingBookmarkIdx >= 0) { bookmarkDragY = (float)my; return true; }
        return false;
    }

    /** 书签滚动条拖拽（mouseDragged，在菜单滚动条之后）。返回 true 表示已消费。
     *  Bookmark scrollbar drag (mouseDragged, after the menu scrollbar). Returns true if consumed. */
    boolean handleScrollbarDrag(double my) {
        if (scrollingBookmark) {
            int panelW = 180, rowH = 16, maxRows = 5;
            var bks = ed.getGraph().bookmarks;
            int totalRows = bks.size();
            if (totalRows > maxRows) {
                int visibleRows = Math.min(totalRows, maxRows);
                int sbH = visibleRows * rowH;
                int thumbH = Math.max(10, sbH * maxRows / totalRows);
                int maxOff = Math.max(1, totalRows - maxRows);
                float delta = (float)(my - ed.scrollDragStartY) / (sbH - thumbH);
                int newOff = ed.scrollDragStartOff + Math.round(delta * maxOff);
                bookmarkScrollOff = Math.max(0, Math.min(maxOff, newOff));
            }
            return true;
        }
        return false;
    }

    /** 滚动条释放 / 书签拖拽落定（mouseReleased）。返回 true 表示已消费。
     *  Scrollbar release / drag-reorder landing (mouseReleased). Returns true if consumed. */
    boolean handleRelease(double my) {
        if (scrollingBookmark) { scrollingBookmark = false; return true; }
        if (draggingBookmarkIdx >= 0) {
            var bks = ed.getGraph().bookmarks;
            if (draggingBookmarkIdx < bks.size() && showBookmarkPanel) {
                int panelW = 180, rowH = 16, maxRows = 5, titleH = 16, btnRowH = 18;
                int panelY = ed.host.asScreen().height - 44 - (titleH + btnRowH + 6 + Math.max(Math.min(bks.size(), maxRows), 1) * rowH + 10) - 4;
                int listTopY = panelY + titleH + btnRowH + 4;
                int toRow = (int)((my - listTopY) / rowH);
                int toIdx = toRow + bookmarkScrollOff;
                if (toIdx >= 0 && toIdx < bks.size() && toIdx != draggingBookmarkIdx) {
                    // 移动书签 / move bookmark
                    var bm = bks.remove(draggingBookmarkIdx);
                    bks.add(toIdx, bm);
                    ed.getGraph().bumpGeneration();
                    ed.host.sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.moveBookmark(
                        ed.host.getBlockPos(), ed.ownerNodeId(), draggingBookmarkIdx, toIdx, ed.host.getPlayerUUID()));
                } else {
                    // 未移动 → 跳转 / not moved → jump
                    var bm = bks.get(draggingBookmarkIdx);
                    startTransition(bm.camX(), bm.camY(), bm.zoom());
                }
            }
            draggingBookmarkIdx = -1;
            return true;
        }
        return false;
    }

    /** 书签面板滚动（mouseScrolled；仅面板显示且指针在面板列上时消费）。
     *  Bookmark panel scroll (mouseScrolled; consumed only when shown and the pointer is over the panel column). */
    boolean handleScroll(double mx, double sy) {
        if (showBookmarkPanel) {
            int panelW = 180, maxRows = 5;
            int panelX = ed.host.asScreen().width - panelW - 4;
            if (mx >= panelX && mx < panelX + panelW) {
                var bks = ed.getGraph().bookmarks;
                int totalRows = bks.size();
                int maxOff = Math.max(0, totalRows - maxRows);
                bookmarkScrollOff += (sy > 0) ? -1 : 1;
                if (bookmarkScrollOff < 0) bookmarkScrollOff = 0;
                if (bookmarkScrollOff > maxOff) bookmarkScrollOff = maxOff;
                return true;
            }
        }
        return false;
    }

    /** 书签命名对话框键盘（Enter 提交 / Backspace 删除 / 其余键消费）。返回 true 表示已消费。
     *  Name-dialog keyboard (Enter submits / Backspace deletes / other keys consumed).
     *  Returns true if consumed. */
    boolean handleKey(int key) {
        if (editingBookmarkName) {
            if (key == 257) { // Enter: 提交（与 确认 按钮同路径）/ submit (same path as the Confirm button)
                confirmNameDialog();
                return true;
            }
            if (key == 259 && !bookmarkNameDraft.isEmpty()) { // Backspace
                bookmarkNameDraft = bookmarkNameDraft.substring(0, bookmarkNameDraft.length() - 1);
                return true;
            }
            return true; // 消费其他键
        }
        return false;
    }

    /** ESC 关闭命名对话框（keyPressed 的 Esc 链）。返回 true 表示已消费。
     *  ESC closes the name dialog (the Esc chain in keyPressed). Returns true if consumed. */
    boolean handleEscClose() {
        if (editingBookmarkName) { cancelNameDialog(); return true; }
        return false;
    }

    /** 快捷键 SAVE_BOOKMARK：打开新建命名对话框。 / SAVE_BOOKMARK keybind: open the new-bookmark dialog. */
    void beginKeybindDraft() {
        editingBookmarkName = true;
        editingBookmarkIndex = -1;
        bookmarkNameDraft = "书签 " + (ed.getGraph().bookmarks.size() + 1);
    }

    /** 命名对话框字符输入。返回 true 表示已消费。 / Name-dialog char input. Returns true if consumed. */
    boolean handleChar(char ch) {
        if (editingBookmarkName) {
            if (ch >= 32 && bookmarkNameDraft.length() < 30) bookmarkNameDraft += ch;
            return true;
        }
        return false;
    }
}
