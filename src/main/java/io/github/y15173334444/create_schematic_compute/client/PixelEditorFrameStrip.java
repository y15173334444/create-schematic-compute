package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

/**
 * 序列帧条：缩略图条 + 按钮行（◀/▶/新建/删除）、滚动与拖拽重排（自 {@link PixelEditorScreen}
 * 拆分，docs/gui-decomposition-plan.md 步骤 4 第二刀）。
 * The sequence frame strip: thumbnail strip + button row (◀/▶/+New/Delete), scrolling and
 * drag-reorder, split out of {@link PixelEditorScreen} (roadmap step 4, second cut).
 *
 * <p><b>行为零变更</b>：实现逐字搬迁，隐式外层访问改为经 {@link Host} / 构造注入的
 * {@link PixelEditorKernel} 取得。帧状态（当前帧号 / 滚动 / 拖拽 / 新建菜单）随实现搬入；
 * 条带几何（y 位置 / 左右缘 / 起始 x）属屏幕布局，经宿主获取；落库与同步（sendOp /
 * sendFrameSync / bump）也归屏幕。
 * <b>Behaviour-preserving</b>: bodies moved verbatim, implicit outer-class accesses now go
 * through {@link Host} / the constructor-injected {@link PixelEditorKernel}. The frame state
 * (current index / scroll / drag / the +New menu) moved with it; the strip geometry (y
 * positions / edges / start x) belongs to the screen layout and is reached via the host, as
 * are persistence and sync (sendOp / sendFrameSync / bump).</p>
 */
final class PixelEditorFrameStrip {

    /** 宿主：屏幕侧提供的最小接缝 / Host: the minimal screen-side surface. */
    interface Host {
        int height();
        /** 取色器面板展开时的左缘（条带右缘）。/ palette left edge = strip right edge. */
        int paletteLeft();
        /** 序列按钮行矩形 [x, y, w, h]（屏幕布局）。/ button-row rect (screen layout). */
        int[] frameBtnRect();
        /** 序列按钮行高（布局常量）。/ button-row height (layout constant). */
        int frameBtnH();
        /** 序列缩略图条顶部 y。/ thumbnail-strip top y. */
        int frameStripY();
        /** 首个缩略图 x（紧贴左工具列右缘）。/ first-thumbnail x. */
        int thumbStartX();
        GraphNode node();
        BlockPos blockPos();
        UUID playerUUID();
        /** 定向同步当前帧（关闭/切帧前调用）。/ targeted current-frame sync. */
        void sendFrameSync();
        void sendOp(GraphOp op);
        void bump();
    }

    private final Host host;
    private final PixelEditorKernel kernel;

    // ── 帧条状态（原 PixelEditorScreen 字段）/ frame-strip state (moved from the screen) ──
    private int frameIndex = 0;
    private int frameScroll = 0;
    private boolean frameMenuOpen = false;
    private enum FrameDrag { IDLE, PRESSED, DRAGGING }
    private FrameDrag frameDrag = FrameDrag.IDLE;
    private int frameDragIndex = -1, frameDropIndex = -1;
    private double frameDragStartX = 0;
    private long frameDragPressTime = 0;
    private boolean frameScrollbarDragging = false;
    private double frameSbDragStartX = 0;
    private int frameSbDragStartOff = 0;
    private long lastFrameAutoScroll = 0;

    /** 序列按钮高（紧凑）。/ sequence button height (compact). */
    private static final int FS_BTN = 16;
    /** 缩略图高 / 间距（宽按宽高比动态、贴底紧凑）。/ thumbnail height & gap (dynamic width, flush bottom). */
    private static final int THUMB = 36, THUMB_GAP = 6;

    PixelEditorFrameStrip(Host host, PixelEditorKernel kernel) {
        this.host = host;
        this.kernel = kernel;
    }

    // ── 访问器 / accessors ──
    int frameIndex() { return frameIndex; }
    void setFrameIndex(int v) { frameIndex = v; }
    /** 拖拽重排进行中（mouseMoved 短路悬停用）。/ a drag-reorder is in progress (short-circuits hover). */
    boolean isDragging() { return frameDrag == FrameDrag.DRAGGING; }

    // ── 几何（依赖节点宽高比）/ geometry (node aspect-driven) ──
    private int thumbW() {
        GraphNode node = host.node();
        return Math.max(2, Math.round(THUMB * (float) Math.max(1, node.imageWidth) / Math.max(1, node.imageHeight)));
    }
    private int thumbPitch() { return thumbW() + THUMB_GAP; }
    private int maxVisibleThumbs() {
        int avail = host.paletteLeft() - host.thumbStartX();
        return Math.max(1, avail / thumbPitch());
    }

    // ══════════════ 渲染 / render ══════════════

    void render(GuiGraphics g, int mx, int my) {
        int[] br = host.frameBtnRect();                   // 按钮行 / button row
        int fy = host.frameStripY();                      // 缩略图条顶 / thumbnail-strip top
        int fl = br[0], frr = br[0] + br[2];
        int btnY = br[1];
        // 整个序列区背景（按钮行 + 缩略图条），底部紧贴屏幕底 / whole frame-area bg (button row + strip), flush to the bottom.
        g.fill(fl, btnY, frr, host.height(), C_BG);
        // 按钮行顶部与画布的分隔线；按钮行与缩略图条之间的分隔线 / border above the row and between the row & strip.
        g.fill(fl, btnY, frr, btnY + 1, C_BORDER);
        g.fill(fl, fy, frr, fy + 1, C_BORDER);
        var f = Minecraft.getInstance().font;
        List<int[]> frames = host.node().imageSequenceFrames;
        if (frames == null || frames.isEmpty()) return;
        int n = frames.size();
        int by = btnY + (host.frameBtnH() - FS_BTN) / 2;   // 按钮行内垂直居中 / centred in the row
        // 导航 / nav (relative to fl)
        boolean pH = hit(mx, my, fl + 8, by, 18, FS_BTN);
        g.fill(fl + 8, by, fl + 26, by + FS_BTN, pH ? C_HOVER : C_BTN);
        g.renderOutline(fl + 8, by, 18, FS_BTN, C_BORDER);
        g.drawString(f, "§7◀", fl + 13, by + 3, C_TXT_DIM, false);
        boolean nH = hit(mx, my, fl + 30, by, 18, FS_BTN);
        g.fill(fl + 30, by, fl + 48, by + FS_BTN, nH ? C_HOVER : C_BTN);
        g.renderOutline(fl + 30, by, 18, FS_BTN, C_BORDER);
        g.drawString(f, "§7▶", fl + 35, by + 3, C_TXT_DIM, false);
        g.drawString(f, "§7" + (frameIndex + 1) + "/" + n, fl + 54, by + 3, C_TXT_DIM, false);
        // 新建 / +New
        boolean newH = hit(mx, my, fl + 94, by, 44, FS_BTN);
        g.fill(fl + 94, by, fl + 138, by + FS_BTN, newH ? C_HOVER : C_BTN);
        g.renderOutline(fl + 94, by, 44, FS_BTN, C_BORDER);
        g.drawString(f, "§a" + I18n.get("gui.create_schematic_compute.monitor.pixel_new"), fl + 100, by + 3, C_TXT_DIM, false);
        // 删除 / delete
        boolean delH = hit(mx, my, fl + 146, by, 48, FS_BTN);
        g.fill(fl + 146, by, fl + 194, by + FS_BTN, delH ? C_DEL : C_BTN);
        g.renderOutline(fl + 146, by, 48, FS_BTN, 0xFF8A5A4A);
        g.drawString(f, "§c" + I18n.get("gui.create_schematic_compute.monitor.pixel_delete"), fl + 150, by + 3, C_TXT_DIM, false);
        // 缩略图 / thumbnails（高度固定、宽度随宽高比动态；去边框，选中帧用底色高亮）
        int tx = host.thumbStartX();
        int tw = thumbW();
        int pitch = thumbPitch();
        int maxScroll = Math.max(0, n - maxVisibleThumbs());
        int scroll = Math.max(0, Math.min(maxScroll, frameScroll));
        frameScroll = scroll;
        for (int i = 0; i < n; i++) {
            int x = tx + i * pitch - scroll * pitch;
            if (x + tw < fl || x > frr) continue;
            int y = fy + 2;
            if (i == frameIndex) g.fill(x - 1, y - 1, x + tw + 1, y + THUMB + 1, 0xFF3A5A2A);
            else if (frameDrag == FrameDrag.DRAGGING && frameDragIndex == i) g.fill(x - 1, y - 1, x + tw + 1, y + THUMB + 1, 0xFF7A4A3A);
            g.fill(x, y, x + tw, y + THUMB, NodeRenderer.PINS());
            renderThumb(g, frames.get(i), x, y, tw, THUMB);
        }
        // 拖拽落点指示 / drop indicator
        if (frameDrag == FrameDrag.DRAGGING && frameDropIndex >= 0 && frameDropIndex <= n) {
            int dx = tx + frameDropIndex * pitch - scroll * pitch - 2;
            g.fill(dx, fy + 2, dx + 2, fy + THUMB + 2, NodeRenderer.ACC());
        }
        // 滚动条 / scrollbar
        if (maxScroll > 0) {
            int sbW = frr - tx;
            int sbX = tx;
            float sbThumbW = Math.max(18, (float) maxVisibleThumbs() / n * sbW);
            float thumbX = sbX + (float) scroll / maxScroll * (sbW - sbThumbW);
            g.fill(sbX, host.height() - 5, sbX + sbW, host.height() - 3, C_BTN);
            g.fill((int) thumbX, host.height() - 5, (int) (thumbX + sbThumbW), host.height() - 3, 0xFF8A7A5A);
        }
        // 新建菜单（Blank / From current）最后画，浮在缩略图条上方 / draw the "+New" menu last so it floats above the strip.
        if (frameMenuOpen) {
            g.fill(fl + 94, by + 18, fl + 184, by + 40, C_BG);
            g.renderOutline(fl + 94, by + 18, 90, 22, C_BORDER);
            g.drawString(f, "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_blank"), fl + 100, by + 20, C_TXT_DIM, false);
            g.drawString(f, "§7" + I18n.get("gui.create_schematic_compute.monitor.pixel_from_current"), fl + 100, by + 30, C_TXT_DIM, false);
        }
    }

    private void renderThumb(GuiGraphics g, int[] frame, int x, int y, int w, int h) {
        if (frame == null || frame.length == 0) return;
        GraphNode node = host.node();
        int imgW = node.imageWidth, imgH = node.imageHeight;
        if (imgW <= 0 || imgH <= 0) return;
        // 缩放到填满 w×h（保持宽高比），采用像素级整数矩形（支持放大/缩小）/ scale to fill w×h preserving aspect via per-pixel integer rects.
        float cf = Math.min((float) h / imgH, (float) w / imgW);
        float offX = x + (w - imgW * cf) / 2f, offY = y + (h - imgH * cf) / 2f;
        for (int py = 0; py < imgH; py++)
            for (int px = 0; px < imgW; px++) {
                int idx = py * imgW + px;
                int c = (idx < frame.length) ? frame[idx] : 0;
                int x1 = Math.round(offX + px * cf), y1 = Math.round(offY + py * cf);
                int x2 = Math.max(x1 + 1, Math.round(offX + (px + 1) * cf));
                int y2 = Math.max(y1 + 1, Math.round(offY + (py + 1) * cf));
                if ((c & 0xFF000000) == 0) {
                    int ck = ((px + py) & 1) * 0x222222;
                    g.fill(x1, y1, x2, y2, 0xFF333333 + ck);
                } else {
                    g.fill(x1, y1, x2, y2, c);
                }
            }
    }

    // ══════════════ 输入 / input ══════════════

    boolean handleClick(double mx, double my, int btn) {
        int fl = host.thumbStartX() - 8;                    // 与渲染的按钮行锚点一致（br[0]=LEFT_W）/ match the render anchor (br[0]=LEFT_W)
        int fy = host.frameStripY();
        int by = host.frameBtnRect()[1] + (host.frameBtnH() - FS_BTN) / 2;   // 按钮行内垂直居中 / centred in the row
        List<int[]> frames = host.node().imageSequenceFrames;
        if (frames == null || frames.isEmpty()) return false;
        int n = frames.size();
        if (btn == 0) {
            if (hit(mx, my, fl + 8, by, 18, FS_BTN)) { switchFrame(frameIndex - 1); return true; }
            if (hit(mx, my, fl + 30, by, 18, FS_BTN)) { switchFrame(frameIndex + 1); return true; }
            if (hit(mx, my, fl + 94, by, 44, FS_BTN)) { frameMenuOpen = !frameMenuOpen; return true; }
            if (frameMenuOpen && mx >= fl + 94 && mx <= fl + 184 && my >= by + 18 && my <= by + 40) {
                boolean blank = my <= by + 28;
                addFrame(blank);
                frameMenuOpen = false;
                return true;
            }
            if (hit(mx, my, fl + 146, by, 48, FS_BTN)) { deleteFrame(); return true; }
            // 缩略图点击 / thumbnail clicks
            int tx = host.thumbStartX();
            int frr = host.paletteLeft();
            for (int i = 0; i < n; i++) {
                int x = tx + i * thumbPitch() - frameScroll * thumbPitch();
                if (x + thumbW() < fl || x > frr) continue;
                if (hit(mx, my, x, fy + 2, thumbW(), THUMB)) {
                    if (frameDrag != FrameDrag.DRAGGING) {
                        frameDrag = FrameDrag.PRESSED;
                        frameDragIndex = i;
                        frameDropIndex = i;
                        frameDragStartX = mx;
                        frameDragPressTime = System.currentTimeMillis();
                    }
                    return true;
                }
            }
            // 滚动条 / scrollbar
            int maxScroll = Math.max(0, n - maxVisibleThumbs());
            if (maxScroll > 0 && my >= host.height() - 5 && my <= host.height() - 3) {
                int sbW = frr - tx;
                float sbThumbW = Math.max(18, (float) maxVisibleThumbs() / n * sbW);
                float thumbX = tx + (float) frameScroll / maxScroll * (sbW - sbThumbW);
                if (mx >= thumbX && mx <= thumbX + sbThumbW) {
                    frameScrollbarDragging = true;
                    frameSbDragStartX = mx;
                    frameSbDragStartOff = frameScroll;
                    return true;
                }
            }
        } else if (btn == 1) {
            return false;
        }
        return false;
    }

    /** 滚动条拖拽推进（mouseDragged 中滚动条块，位于平移判断之前）。返回 true = 已消费。
     *  Scrollbar drag advancement (the scrollbar block in mouseDragged, which sits before the
     *  pan check). true = consumed. */
    boolean handleScrollbarDragged(double mx) {
        if (!frameScrollbarDragging) return false;
        int n = host.node().imageSequenceFrames.size();
        int maxScroll = Math.max(0, n - maxVisibleThumbs());
        int tx = host.thumbStartX();
        int sbW = host.paletteLeft() - tx;
        float sbThumbW = Math.max(18, (float) maxVisibleThumbs() / n * sbW);
        float delta = (float) (mx - frameSbDragStartX) / (sbW - sbThumbW);
        frameScroll = Math.max(0, Math.min(maxScroll, frameSbDragStartOff + Math.round(delta * maxScroll)));
        return true;
    }

    /** 拖拽推进（mouseDragged 的帧拖拽块，位于平移判断之后）：按下→拖拽升级 / 拖拽更新。
     *  返回 true = 已消费。
     *  Drag advancement (the frame-drag blocks in mouseDragged, after the pan check): the
     *  pressed→dragging upgrade and the drag update. true = consumed. */
    boolean handleDragged(double mx, double my) {
        if (frameDrag == FrameDrag.PRESSED) {
            if (Math.abs(mx - frameDragStartX) > 5 || System.currentTimeMillis() - frameDragPressTime > 200) {
                frameDrag = FrameDrag.DRAGGING;
            }
        }
        if (frameDrag == FrameDrag.DRAGGING && frameDragIndex >= 0) {
            updateFrameDropIndex(mx);
            frameAutoScroll(mx);
            return true;
        }
        return false;
    }

    /** 滚动条释放（mouseReleased 的滚动条块，位于平移判断之前）。返回 true = 已消费。
     *  Scrollbar release (the scrollbar block in mouseReleased, before the pan check).
     *  true = consumed. */
    boolean releaseScrollbar() {
        if (!frameScrollbarDragging) return false;
        frameScrollbarDragging = false;
        return true;
    }

    /** 拖拽释放（mouseReleased 的帧拖拽块，位于平移判断之后）：拖拽落定 / 按下切帧。
     *  返回 true = 已消费；按下切帧不消费（原实现继续落到形状/笔刷释放逻辑）。
     *  Drag release (the frame-drag blocks in mouseReleased, after the pan check): drop or
     *  press-to-switch. true = consumed; a press-to-switch is NOT consumed (the original fell
     *  through to the shape/brush release logic). */
    boolean releaseDrag() {
        if (frameDrag == FrameDrag.DRAGGING && frameDragIndex >= 0) {
            applyFrameReorder();
            resetFrameDrag();
            return true;
        }
        if (frameDrag == FrameDrag.PRESSED) {
            switchFrame(frameDragIndex);
            resetFrameDrag();
        }
        return false;
    }

    /** 滚轮滚动帧条。 / wheel-scroll the strip. */
    boolean handleScrolled(double sy) {
        int n = host.node().imageSequenceFrames.size();
        int maxScroll = Math.max(0, n - maxVisibleThumbs());
        frameScroll = Math.max(0, Math.min(maxScroll, frameScroll + (sy > 0 ? -1 : 1)));
        return true;
    }

    // ══════════════ 帧操作 / frame ops ══════════════

    private void switchFrame(int newIndex) {
        List<int[]> frames = host.node().imageSequenceFrames;
        if (frames == null || frames.isEmpty()) return;
        if (newIndex < 0 || newIndex >= frames.size()) return;
        host.sendFrameSync();
        frameIndex = newIndex;
        host.node().imagePixels = frames.get(frameIndex);
        int maxScroll = Math.max(0, frames.size() - maxVisibleThumbs());
        if (frameIndex < frameScroll) frameScroll = frameIndex;
        else if (frameIndex > frameScroll + maxVisibleThumbs() - 1) frameScroll = frameIndex - maxVisibleThumbs() + 1;
        frameScroll = Math.max(0, Math.min(maxScroll, frameScroll));
        frameMenuOpen = false;
    }

    private void addFrame(boolean blank) {
        GraphNode node = host.node();
        List<int[]> frames = PixelEditorKernel.ensureFrames(node);
        kernel.pushFramesUndo(node);
        int[] f;
        if (blank) {
            f = new int[node.imageWidth * node.imageHeight];
            java.util.Arrays.fill(f, 0x00000000);
        } else {
            f = node.imagePixels.clone();
        }
        frames.add(f);
        frameIndex = frames.size() - 1;
        node.imagePixels = f;
        frameMenuOpen = false;
        host.sendFrameSync();
        host.bump();
    }

    private void deleteFrame() {
        GraphNode node = host.node();
        List<int[]> frames = PixelEditorKernel.ensureFrames(node);
        if (frames.isEmpty()) return;
        kernel.pushFramesUndo(node);
        int removed = frameIndex;
        if (frames.size() > 1) {
            frames.remove(removed);
        } else {
            int[] blank = new int[node.imageWidth * node.imageHeight];
            java.util.Arrays.fill(blank, 0x00000000);
            frames.set(0, blank);
        }
        if (frameIndex >= frames.size()) frameIndex = frames.size() - 1;
        node.imagePixels = frames.get(Math.max(0, Math.min(frameIndex, frames.size() - 1)));
        host.sendOp(GraphOp.removeImageFrame(host.blockPos(), -1, node.id, removed, host.playerUUID()));
        host.bump();
    }

    private void updateFrameDropIndex(double mx) {
        List<int[]> frames = host.node().imageSequenceFrames;
        if (frames == null || frames.isEmpty()) return;
        int tx = host.thumbStartX();
        int target = (int) Math.floor((mx - tx + frameScroll * thumbPitch()) / thumbPitch());
        target = Math.max(0, Math.min(frames.size(), target));
        frameDropIndex = target;
    }

    private void frameAutoScroll(double mx) {
        List<int[]> frames = host.node().imageSequenceFrames;
        if (frames == null) return;
        int maxScroll = Math.max(0, frames.size() - maxVisibleThumbs());
        long now = System.currentTimeMillis();
        if (now - lastFrameAutoScroll < 100) return;
        if (mx < host.thumbStartX() + thumbW() + 4 && frameScroll > 0) {
            frameScroll = Math.max(0, frameScroll - 1);
            lastFrameAutoScroll = now;
        } else if (mx > host.paletteLeft() - thumbW() - 20 && frameScroll < maxScroll) {
            frameScroll = Math.min(maxScroll, frameScroll + 1);
            lastFrameAutoScroll = now;
        }
    }

    private void applyFrameReorder() {
        GraphNode node = host.node();
        List<int[]> frames = node.imageSequenceFrames;
        if (frames == null || frameDragIndex < 0) return;
        int from = frameDragIndex;
        int to = frameDropIndex;
        if (to > from) to--;
        if (from == to) return;
        kernel.pushFramesUndo(node);
        int[] f = frames.remove(from);
        if (to < 0) to = 0;
        if (to > frames.size()) to = frames.size();
        frames.add(to, f);
        frameIndex = to;
        node.imagePixels = frames.get(to);
        host.sendOp(GraphOp.moveImageFrame(host.blockPos(), -1, node.id, from, to, host.playerUUID()));
        host.bump();
    }

    private void resetFrameDrag() {
        frameDrag = FrameDrag.IDLE;
        frameDragIndex = -1;
        frameDropIndex = -1;
        frameDragStartX = 0;
        frameDragPressTime = 0;
    }

    // ── 主题色与命中助手（原屏幕常量，随实现搬入）/ theme colours & hit helper (moved) ──
    private static final int C_BG = NodeRenderer.PBG();          // 面板底 / panel bg
    private static final int C_BORDER = NodeRenderer.PBR();      // 面板描边 / panel border
    private static final int C_BTN = 0xFF3A3428;                 // 按钮底 / button bg
    private static final int C_HOVER = 0xFF5A4A3A;               // 悬停 / hover
    private static final int C_DEL = 0xFF7A4A3A;                 // 删除危险 / destructive
    private static final int C_TXT_DIM = 0xFFCCCCCC;

    private static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
