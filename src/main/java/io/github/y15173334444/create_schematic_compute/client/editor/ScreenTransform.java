package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;

/**
 * 屏幕变换与像素对齐的唯一入口（抖动方案 JIT-1 / JIT-2，见
 * docs/graph-editor-canvas-jitter-plan.md）。两条规则：
 * <ol>
 * <li><b>节点原点每帧吸附一次</b>：框、内容 pose、引脚、连线根、命中换算全部从
 *     {@link #nodeX}/{@link #nodeY} 的同一个整数原点派生——拖动时各部件锁死同一步进，
 *     不再互相爬（此前框走 {@code (int)} 截断跳格、内容走 float pose 平滑滑动、连线根
 *     走裸浮点，三套取整各玩各的）；</li>
 * <li><b>网格整数格</b>：原点吸附一次、各线由 {@code 原点 + round(k·GS·zoom)} 推出，
 *     平移时整格刚性平移（无逐线呼吸），每条线与真实世界线偏差 ≤1px。</li>
 * </ol>
 * Screen transform and pixel alignment, the single entry (jitter plan JIT-1 / JIT-2, see
 * docs/graph-editor-canvas-jitter-plan.md). Two rules:
 * <ol>
 * <li><b>One snapped node origin per frame</b>: the frame, the content pose, the pins, the
 *     wire roots and the paired hit conversions all derive from the same integer origin
 *     returned by {@link #nodeX}/{@link #nodeY} — during a drag every part steps in lockstep
 *     instead of crawling against each other (the frame used to truncate with {@code (int)},
 *     the content pose glided in float, and the wire roots rode raw floats: three rounding
 *     regimes doing their own thing);</li>
 * <li><b>Integer grid lattice</b>: the origin snaps once and each line is
 *     {@code origin + round(k·GS·zoom)}, so the whole lattice translates rigidly during pans
 *     (no per-line breathing) while every line stays within 1px of its true world position.</li>
 * </ol>
 */
final class ScreenTransform {

    private ScreenTransform() {}

    /** 唯一像素对齐入口：四舍五入。render / hit 路径禁止裸 {@code (int)} 截断
     *  （负数侧不对称，同一元素的不同部件会边界互爬）。
     *  The single pixel-align entry: round-half-up. No bare {@code (int)} truncation on the
     *  render / hit paths (it is asymmetric for negatives, so parts of one element creep
     *  across each other's boundaries). */
    static int px(float v) {
        return Math.round(v);
    }

    /** 节点屏幕原点 X（每帧吸附一次）。 / Node screen origin X, snapped once per frame. */
    static int nodeX(NodeRenderer.CoordMapper c2sX, GraphNode n) {
        return px(c2sX.apply(n.x));
    }

    /** 缩放范围（2026-11-21 实测拍板 2~0.125）：放大上限 2（原 4 深于任何阅读所需），
     *  缩小下限 0.125（原 0.25 装不下大图）——滚轮/捏合/书签恢复共用本钳制（单源）。
     *  Zoom range (live-ruled 2026-11-21, 2..0.125): zoom-in cap 2 (4 was deeper than any
     *  reading gain needs), zoom-out floor 0.125 (0.25 could not fit large graphs) — wheel,
     *  pinch and bookmark restores all share this clamp (single source). */
    static final float MIN_ZOOM = 0.125f, MAX_ZOOM = 2f;

    static float clampZoom(float zoom) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom));
    }

    /** 连续缩放映射（GZ-1，docs/graph-editor-gesture-zoom-plan.md）：每 1.0 增量 = 12%
     *  （经典滚轮 ±1 刻度手感不变），高精度触摸板/旋钮/数位板/高分辨率滚轮的分数增量连续
     *  跟手、不再被量化成整刻度；{@link #clampZoom} 钳制（0.125–2），零增量 no-op。
     *  Continuous zoom mapping (GZ-1): one delta unit = 12% (classic wheel notches
     *  unchanged), fractional deltas from high-precision trackpads/dials/tablets/high-res
     *  wheels stay continuous instead of being quantized to whole notches; clamped by
     *  {@link #clampZoom} (0.125–2), zero delta is a no-op. */
    static float zoomStep(float zoom, double delta) {
        float nz = zoom * (float) Math.pow(1.12, delta);
        return clampZoom(nz);
    }

    /** 缩放锚点 X（GZ-1）：增量式平移修正，保持屏幕点 (mx,·) 下的图点不动（连续缩放序列下
     *  逐次成立）。 / Zoom anchor X (GZ-1): the incremental pan correction holding the graph
     *  point under the screen point (mx,·) fixed (valid step by step along any continuous
     *  zoom sequence). */
    static float anchorPanX(float camX, double mx, float screenWidth, float oldZoom, float newZoom) {
        return camX + (float) ((mx - screenWidth / 2f) * (1.0 / newZoom - 1.0 / oldZoom));
    }

    /** 缩放锚点 Y（GZ-1，口径同 {@link #anchorPanX}）。 / Zoom anchor Y (GZ-1), same rule. */
    static float anchorPanY(float camY, double my, float screenHeight, float oldZoom, float newZoom) {
        return camY + (float) ((my - screenHeight / 2f) * (1.0 / newZoom - 1.0 / oldZoom));
    }

    /** 捏合缩放（GZ-2，docs/graph-editor-gesture-zoom-plan.md）：手指间距比**直驱**（1:1 跟手）
     *  ——与滚轮的指数映射语义不同，捏合给出的本来就是比例。{@link #clampZoom} 钳制（0.125–2）。
     *  Pinch zoom (GZ-2): the finger-distance ratio drives zoom directly (1:1 with the
     *  fingers) — unlike the wheel's exponential map, a pinch already IS a ratio.
     *  Clamped by {@link #clampZoom} (0.125–2). */
    static float pinchStep(float zoom, double scale) {
        return clampZoom(zoom * (float) scale);
    }

    /** 捏合合成滚轮 → 缩放因子（GZ-2 回退通道）：每 120 滚轮单位（一整刻度）= notchBase 倍，
     *  分数增量连续。 / Synthesized pinch wheel → zoom factor (GZ-2 fallback channel): 120
     *  wheel units (one whole notch) = one notchBase step, fractional deltas stay
     *  continuous. */
    static double wheelNotchFactor(double delta, double notchBase) {
        return Math.pow(notchBase, delta / 120.0);
    }

    /** 节点屏幕原点 Y（每帧吸附一次）。 / Node screen origin Y, snapped once per frame. */
    static int nodeY(NodeRenderer.CoordMapper c2sY, GraphNode n) {
        return px(c2sY.apply(n.y));
    }

    /** 图空间锚点 → 屏幕 X：吸附原点 + 局部偏移×zoom。连线根与引脚圆同点，
     *  拖动时锁死不爬；与 {@link WireGeometry} 的锚点公式配套（命中曲线仍走图空间
     *  浮点锚点，12px 命中阈值吸收 ≤0.5px 偏差，亚像素并列语义不动）。
     *  Graph anchor → screen X: snapped origin + local offset×zoom. The wire root lands on
     *  the same point as the pin circle and cannot crawl during drags; pairs with
     *  {@link WireGeometry}'s anchor formula (the hit curve keeps its graph-space float
     *  anchors — the 12px hit threshold absorbs the ≤0.5px deviation, and the sub-pixel
     *  tie semantics stay untouched). */
    static float anchorX(NodeRenderer.CoordMapper c2sX, GraphNode n, WireGeometry.Pt anchor, float zoom) {
        return nodeX(c2sX, n) + (anchor.x() - n.x) * zoom;
    }

    /** 图空间锚点 → 屏幕 Y（口径同 {@link #anchorX}）。 / Graph anchor → screen Y, same rule as {@link #anchorX}. */
    static float anchorY(NodeRenderer.CoordMapper c2sY, GraphNode n, WireGeometry.Pt anchor, float zoom) {
        return nodeY(c2sY, n) + (anchor.y() - n.y) * zoom;
    }

    /** 网格线位置（JIT-2）：{@code round(origin) + round(k·spacing)}，落在 [0, span] 内的
     *  全部线（升序）。origin 平移时整数步进 → 整格刚性平移；单线与真实位置
     *  {@code origin + k·spacing} 的偏差 ≤1px（两次取整各 ≤0.5px）。
     *  Grid line positions (JIT-2): {@code round(origin) + round(k·spacing)}, every line
     *  inside [0, span], ascending. A panning origin steps in integers, so the lattice
     *  translates rigidly; each line stays within 1px of its true {@code origin + k·spacing}
     *  (two roundings, ≤0.5px each). */
    static int[] gridLattice(float origin, float spacing, int span) {
        if (!(spacing > 0f) || span < 0) return new int[0];
        int base = px(origin);
        int kMin = (int) Math.floor((0 - base) / spacing) - 1;
        int kMax = (int) Math.ceil((span - base) / spacing) + 1;
        int[] out = new int[Math.max(0, kMax - kMin + 1)];
        int count = 0;
        for (int k = kMin; k <= kMax; k++) {
            int x = base + px(k * spacing);
            if (x >= 0 && x <= span) out[count++] = x;
        }
        return java.util.Arrays.copyOf(out, count);
    }
}
