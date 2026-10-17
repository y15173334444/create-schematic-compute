package io.github.y15173334444.create_schematic_compute.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 滚动条（社区 Note Block Studio 的横竖双滚动条同款职责）：滑块比例 = 可见/总量，
 * 可拖拽定位、点轨道翻页——解决「只靠滚轮、不知道翻到哪了」的迷路感。
 * 几何与映射是纯函数（{@link #thumbRect}/{@link #posFromThumbDrag}/{@link #posFromTrackClick}，
 * 渲染与命中共用）；状态（位置/总量）由调用方持有。
 * <p>Scrollbars (the community Note Block Studio's horizontal + vertical pair): the thumb is
 * proportional to visible/total, draggable to seek and click-the-track to page — solving the
 * "wheel-only, no idea where I am" confusion. Geometry and mapping are pure functions shared
 * by rendering and hit-testing; the caller owns the position/total state.</p>
 */
public final class NbsScrollBar {

    /** 轨道粗细（横=高、竖=宽）。 / track thickness (height for horizontal, width for vertical). */
    public static final int THICK = 10;
    /** 滑块最小尺寸（保证可抓）。 / minimum thumb size (always grabbable). */
    public static final int MIN_THUMB = 12;

    private NbsScrollBar() { }

    /**
     * 滑块矩形（纯函数）：轨道 (x,y,w,h)、总量 total、可见量 visible、位置 pos → {x,y,w,h}。
     * 横向按 w ≥ h 判定；比例 = visible/total（最小 {@link #MIN_THUMB}，不超过轨道）；
     * pos 钳在 [0, total−visible]。<b>渲染与命中共用此表。</b>
     * Thumb rect (pure): track (x,y,w,h), total, visible, pos → {x,y,w,h}. Horizontal when
     * w ≥ h; size = visible/total (floored at {@link #MIN_THUMB}, never over the track); pos
     * clamps to [0, total−visible]. Shared by rendering and hit-testing.
     */
    static int[] thumbRect(int x, int y, int w, int h, int total, int visible, int pos) {
        boolean horiz = w >= h;
        int track = horiz ? w : h;
        total = Math.max(1, total);
        visible = Math.max(1, Math.min(visible, total));
        int thumb = Math.min(track, Math.max(MIN_THUMB, (int) Math.round((double) track * visible / total)));
        int maxPos = total - visible;
        int p = Math.max(0, Math.min(pos, maxPos));
        int travel = track - thumb;
        int tpos = maxPos <= 0 ? 0 : (int) Math.round((double) p * travel / maxPos);
        return horiz ? new int[]{x + tpos, y, thumb, h} : new int[]{x, y + tpos, w, thumb};
    }

    /**
     * 拖动滑块 → 新位置（纯函数）：按抓点在滑块内的相对位置连续映射。
     * Drag the thumb → new position (pure): continuous mapping from the grab point along the track.
     */
    static int posFromThumbDrag(int trackLen, int thumbLen, int clickAlong, int total, int visible) {
        total = Math.max(1, total);
        visible = Math.max(1, Math.min(visible, total));
        int maxPos = total - visible;
        int travel = trackLen - thumbLen;
        if (travel <= 0 || maxPos <= 0) return 0;
        double rel = (clickAlong - thumbLen / 2.0) / travel;
        return Math.max(0, Math.min(maxPos, (int) Math.round(rel * maxPos)));
    }

    /**
     * 点轨道（滑块外）→ 翻页朝点击侧（纯函数）：前侧退一页、后侧进一页，钳位。
     * Track click outside the thumb → page toward it (pure): one viewport back before the
     * thumb, one viewport forward after it, clamped.
     *
     * @param clickAlong 点击处的沿轴偏移 / the click offset along the track
     * @param thumbStart 滑块起点沿轴偏移 / the thumb's start offset along the track
     * @param thumbLen   滑块长 / the thumb length
     */
    static int posFromTrackClick(int clickAlong, int thumbStart, int thumbLen, int pos, int total, int visible) {
        total = Math.max(1, total);
        visible = Math.max(1, Math.min(visible, total));
        int maxPos = total - visible;
        int p = Math.max(0, Math.min(pos, maxPos));
        if (clickAlong < thumbStart) p -= visible;
        else if (clickAlong > thumbStart + thumbLen) p += visible;
        return Math.max(0, Math.min(maxPos, p));
    }

    /**
     * 画轨道 + 滑块（悬停亮边）。track (x,y,w,h)；total/visible/pos 同 {@link #thumbRect}。
     * Draw the track and thumb (hover-lit outline).
     */
    public static void render(GuiGraphics g, int x, int y, int w, int h,
                              int total, int visible, int pos, boolean hover) {
        g.fill(x, y, x + w, y + h, 0xFF1A1814);
        g.renderOutline(x, y, w, h, NbsPianoRoll.C_GRID_BAR);
        int[] t = thumbRect(x, y, w, h, total, visible, pos);
        g.fill(t[0], t[1], t[0] + t[2], t[1] + t[3], hover ? NbsPianoRoll.C_TEXT_DIM : 0xFF4A4438);
        g.renderOutline(t[0], t[1], t[2], t[3], hover ? NbsPianoRoll.C_TEXT : NbsPianoRoll.C_GRID_BAR);
    }
}
