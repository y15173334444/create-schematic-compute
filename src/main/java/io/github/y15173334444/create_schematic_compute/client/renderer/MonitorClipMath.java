package io.github.y15173334444.create_schematic_compute.client.renderer;

/**
 * 显示器 HUD 的纯几何 / 裁剪数学 / Monitor HUD pure geometry & clipping math.
 *
 * <p>自 {@link MonitorBlockEntityRenderer} 拆出（docs/gui-decomposition-plan.md 步骤 1）：
 * 这些函数只做矩阵变换、多边形裁剪与投影，**不碰渲染状态、不持实例字段**，
 * 因而可脱离渲染器单独单测（{@code ConformalProjectionTest} / {@code FacingOverflowDiagTest}）。</p>
 * <p>Extracted from {@link MonitorBlockEntityRenderer} (roadmap step 1): these functions do
 * matrix transforms, polygon clipping and projection only — no render state, no instance
 * fields — so they are unit-testable without a GPU context.</p>
 *
 * <p><b>行为零变更</b>：本次拆分是纯搬迁，方法体逐字未改（对照 docs/gui-decomposition-plan.md §2 原则 1）；
 * 唯一的机械改动是跨类使用所需的可见性提升（package-private → public）。
 * <b>Behaviour-preserving</b>: a pure move; no method body was modified. The only mechanical
 * change is the visibility bump (package-private → public) needed for cross-class use.</p>
 */
public final class MonitorClipMath {

    private MonitorClipMath() {}

    /** 锚定比例 s 的量级上界：掠射时 {@code fz→0⁻} → {@code s→∞} → 顶点 Inf/NaN 撕裂。
     *  钳到有限值后坐标有界（float 精确）、NDC 视锥外由 GPU 干净裁剪。
     *  Magnitude clamp for the anchor ratio s: at grazing {@code fz→0⁻} gives {@code s→∞}
     *  and Inf/NaN vertices; a finite bound keeps coords float-exact and out of the frustum.
     *  由 {@link MonitorBlockEntityRenderer} 经静态导入引用（单测亦直接引用）。 */
    public static final float MAX_ANCHOR_S = 1e4f;
    // ── 画布姿态仪（俯仰梯）：tan 透视刻度 + pitch 平移 + roll 旋转（纯函数，供单元测试） ──
    // Canvas attitude indicator (pitch ladder): tan-perspective ticks + pitch shift +
    // roll rotation (pure functions, unit-testable). No player-camera projection —
    // everything is drawn on the panel canvas, so Sable structures work natively.

    /** 画布刻度纵向尺度：1 弧度 ≈ 此比例 × 画布半高（tan 透视，近地平线密、远处疏）。
     *  Canvas tick scale: 1 radian ≈ this ratio × panel half-height (tan perspective). */
    private static final float LADDER_CANVAS_SCALE = 0.9f;

    /** 刻度角度（度）→ 画布 y 偏移（面板局部 y-up，相对画布中心）。
     *  y = -K·tan(pitch−θ)：θ 是刻度代表的世界俯仰角（+ 在上方），pitch 抬头时
     *  地平线（θ=0）下移、+10° 刻度在中心上方（2026-08-24 修复：原为 pitch+θ
     *  「同向叠加」，导致平飞时 +10° 刻度跑到中心下方、上方显示负角——不符合
     *  HUD 惯例「向上为正」）。
     *  注：tan 周期 180° 使 +90° 与 −90° 刻度 y 恒相同（度数相同重叠）——重叠由
     *  drawPitchLadder 的**档线分侧**解决（正角度画左段、负角度画右段），纯函数
     *  保留 tan 透视原样（2026-08-24）。
     *  Pure function on doubles.
     *  Tick angle (deg) → canvas y offset (panel-local y-up, relative to canvas
     *  center). y = -K·tan(pitch−θ): θ is the world pitch this tick labels (+ above);
     *  nose-up pitch moves the horizon (θ=0) down, and the +10° tick sits above the
     *  center (2026-08-24 fix: the old pitch+θ "same-direction sum" put the +10° tick
     *  below center at level flight — negative angles above — against the HUD
     *  convention "up is positive").
     *  Note: tan's 180° period gives the +90° and −90° ticks the same y (same
     *  magnitude overlap) — resolved by drawPitchLadder's side-split bars (positive
     *  angles left, negative right); the pure function keeps tan as-is. */
    public static double ladderCanvasY(double pitchDeg, double thetaDeg, double halfH) {
        return -LADDER_CANVAS_SCALE * halfH * Math.tan(Math.toRadians(pitchDeg - thetaDeg));
    }
    public static final float[] EMPTY_POLY = new float[0];

    /**
     * 相机平面裁剪的**边距**（单位：格）：只保留 {@code fz <= -CAM_PLANE_MARGIN} 的几何。
     * Camera-plane clip **margin** (blocks): only geometry at {@code fz <= -CAM_PLANE_MARGIN} survives.
     *
     * <p>不能裁到 fz=0：边界顶点会正好落在相机平面上，fz=0 → s=zAnchor/0 = ±Infinity，
     * 其中 zAnchor<0 给出 **-Infinity** —— 又被镜像了（单元测试 cameraPlaneClipRemoves
     * MirroredVertices 实测到这一点）。留 1 格边距后 |s| ≤ |zAnchor|/1，量级有限且
     * **符号为正**：顶点落在正确一侧的远处，NDC 在视锥外由 GPU 干净裁剪，不会横跨屏幕。
     * Clipping to fz=0 does not work: the boundary vertex lands exactly on the camera
     * plane, fz=0 → s=zAnchor/0 = ±Infinity, and with zAnchor<0 that is **-Infinity** —
     * mirrored again (caught by the cameraPlaneClipRemovesMirroredVertices unit test).
     * With a 1-block margin, |s| ≤ |zAnchor| and, crucially, the **sign stays positive**:
     * the vertex sits far out on the correct side, its NDC leaves the frustum, and the
     * GPU clips it cleanly instead of stretching it across the screen.
     */
    public static final float CAM_PLANE_MARGIN = 1.0f;

    /**
     * 把多边形裁剪到相机平面（只保留 {@code fz <= threshold} 的部分）。
     * Clip a polygon to the camera plane, keeping only {@code fz <= threshold}.
     *
     * <p>为什么必须有这一步：{@link #emitAnchored} 只钳制锚定比例 s 的**量级**，
     * 从不看 fz 的**符号**。顶点跑到相机后方时 fz>0 → s=zAnchor/fz 变成**负数**
     * → 锚定顶点 (fx·s, fy·s) 被镜像到屏幕对侧，画出一个鬼影（"部分视角渲染溢出"
     * 的根因）。s 钳制拦不住它：fz 是正常量级的正值时 s 只是个小负数，远不到
     * ±MAX_ANCHOR_S。{@link FacingOverflowDiagTest} 的 grazingSClamp 也明确写着
     * "不覆盖镜像剔除"。
     * Why this must exist: {@link #emitAnchored} clamps the anchor ratio s by
     * *magnitude* only and never looks at the *sign* of fz. A vertex behind the camera
     * has fz>0 → s=zAnchor/fz goes **negative** → the anchored vertex (fx·s, fy·s) is
     * mirrored to the opposite side of the screen, drawing a ghost image (the root
     * cause of the "overflow at some viewing angles" report). The s clamp cannot stop
     * it: with an ordinary-magnitude positive fz, s is just a small negative number,
     * nowhere near ±MAX_ANCHOR_S.
     *
     * <p>fz 必须走与 emitAnchored **完全相同的路径**（m 变换 → viewRot 变换），
     * 否则会算出与锚定不一致的深度（手算矩阵元素在此处给过假值，见 942 行注释）。
     * fz must be computed over the **exact same path** as emitAnchored (m transform →
     * viewRot transform), or it disagrees with the depth actually used for anchoring.
     *
     * @param poly      画布局部坐标的凸多边形（x,y 交替） / convex polygon in canvas-local coords (x,y interleaved)
     * @param zLocal    图层的画布局部 z / the layer's canvas-local z
     * @return 裁剪后的多边形；长度 0 表示全部在相机后方 / clipped polygon; length 0 means fully behind the camera
     */
    public static float[] clipPolyToCameraPlane(float[] poly, float zLocal,
            org.joml.Matrix4f m, org.joml.Matrix4f viewRot) {
        int n = poly.length / 2;
        if (n < 3) return EMPTY_POLY;
        float[] fz = new float[n];
        float fMin = Float.MAX_VALUE, fMax = -Float.MAX_VALUE;
        var v = new org.joml.Vector3f();
        for (int i = 0; i < n; i++) {
            v.set(poly[i * 2], poly[i * 2 + 1], zLocal);
            m.transformPosition(v);
            viewRot.transformPosition(v);
            fz[i] = v.z;
            if (v.z < fMin) fMin = v.z;
            if (v.z > fMax) fMax = v.z;
        }
        if (fMin > 0f) return EMPTY_POLY;          // 整体在相机后方 → 丢弃
        if (fMax <= -CAM_PLANE_MARGIN) return poly; // 整体离相机平面足够远 → 原样返回（零开销）
        // 有顶点贴到/越过相机平面 → 裁到留边距的平面（不能裁到 0，见 CAM_PLANE_MARGIN 注释）
        return clipPolyByDepth(poly, fz, -CAM_PLANE_MARGIN);
    }

    /** 多边形相对相机平面的位置。 / Where a polygon sits relative to the camera plane. */
    public static final int CAM_FRONT = 0, CAM_CROSSING = 1, CAM_BEHIND = 2;

    /**
     * 判断多边形是否跨越相机平面——用于给逐像素循环做**零开销短路**：
     * 全部在前方时一个 fz 都不用算。
     * Whether a polygon crosses the camera plane — used to short-circuit per-pixel
     * loops at zero cost when everything is in front.
     *
     * @return {@link #CAM_FRONT} 全在前方 / {@link #CAM_CROSSING} 跨越 / {@link #CAM_BEHIND} 全在后方
     */
    public static int cameraPlaneState(float[] poly, float zLocal,
            org.joml.Matrix4f m, org.joml.Matrix4f viewRot) {
        int n = poly.length / 2;
        float fMin = Float.MAX_VALUE, fMax = -Float.MAX_VALUE;
        var v = new org.joml.Vector3f();
        for (int i = 0; i < n; i++) {
            v.set(poly[i * 2], poly[i * 2 + 1], zLocal);
            m.transformPosition(v);
            viewRot.transformPosition(v);
            if (v.z < fMin) fMin = v.z;
            if (v.z > fMax) fMax = v.z;
        }
        if (fMin > 0f) return CAM_BEHIND;                 // 全在相机后方
        if (fMax > -CAM_PLANE_MARGIN) return CAM_CROSSING; // 有顶点贴到/越过相机平面
        return CAM_FRONT;
    }
    // ── 玩家屏幕定位遮罩（4 边形）：玻璃面板 4 角点从玩家眼睛投影到远处画布平面 ──
    // Player-screen-positioned mask (4-gon): the glass panel's 4 corners projected
    // from the player's eye onto the far canvas plane.

    /** 玻璃面板 4 角点 → 远处画布平面的透视投影（4 边形遮罩，内容局部坐标）。
     *  玩家眼睛在面板局部 (ex,ey,ez)（ez>0：玩家在玻璃前）。每个玻璃角点
     *  (gx,gy,0) 沿视线投影到画布平面（面板局部 z=-canvasD）：t=(canvasD+ez)/ez，
     *  交点内容局部坐标 = 面板局部/D。返回 {x0,y0,x1,y1,x2,y2,x3,y3}（角点顺序
     *  BL→BR→TR→TL）。纯函数——可单测。
     *  Glass panel 4 corners → perspective projection onto the far canvas plane
     *  (the 4-gon display mask, content-local coords). The player eye sits at
     *  panel-local (ex,ey,ez) (ez>0: in front of the glass). Each glass corner
     *  (gx,gy,0) is projected along its view ray onto the canvas plane
     *  (panel-local z=-canvasD): t=(canvasD+ez)/ez, hit = panel-local/D in
     *  content-local coords. Returns {x0,y0,...,x3,y3} (BL→BR→TR→TL). */
    public static float[] projectGlassCornersToCanvas(float ex, float ey, float ez,
            float hw, float hh, float canvasD) {
        float t = (canvasD + ez) / ez;
        return new float[]{
            (ex + t * (-hw - ex)) / canvasD, (ey + t * (-hh - ey)) / canvasD,
            (ex + t * ( hw - ex)) / canvasD, (ey + t * (-hh - ey)) / canvasD,
            (ex + t * ( hw - ex)) / canvasD, (ey + t * ( hh - ey)) / canvasD,
            (ex + t * (-hw - ex)) / canvasD, (ey + t * ( hh - ey)) / canvasD
        };
    }

    /** 点是否在凸四边形内（maskQuad 8 floats，顶点顺序一致）。纯函数——可单测。
     *  Is a point inside the convex quad (maskQuad 8 floats, consistent winding)?
     *  Pure function. */
    public static boolean pointInConvexQuad(float px, float py, float[] q) {
        boolean sign = false;
        for (int i = 0; i < 4; i++) {
            float ax = q[i * 2], ay = q[i * 2 + 1];
            float bx = q[((i + 1) % 4) * 2], by = q[((i + 1) % 4) * 2 + 1];
            float c = (bx - ax) * (py - ay) - (by - ay) * (px - ax);
            if (i == 0) sign = c >= 0;
            else if ((c >= 0) != sign) return false;
        }
        return true;
    }

    /** 叉积（边 AB 与点 P）/ cross product (edge AB vs point P) */
    private static float cross2(float ax, float ay, float bx, float by, float px, float py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    /** 线段 PQ 与直线 AB 的交点（AB 为裁剪边方向）/ segment PQ ∩ line AB */
    private static float[] intersect2(float ax, float ay, float bx, float by,
            float px, float py, float qx, float qy) {
        float dpx = qx - px, dpy = qy - py;
        float dax = bx - ax, day = by - ay;
        float denom = dpx * day - dpy * dax;
        if (Math.abs(denom) < 1e-9f) return new float[]{qx, qy}; // 平行（防御）
        float t = ((ax - px) * day - (ay - py) * dax) / denom;
        return new float[]{px + t * dpx, py + t * dpy};
    }

    /** Sutherland-Hodgman 凸多边形裁剪：subject 多边形（float[]{x,y,...}）被凸
     *  四边形 quad 裁剪 → 裁剪后顶点列表（可为空）。纯函数——可单测。
     *  Sutherland-Hodgman convex-polygon clip: subject polygon clipped by the
     *  convex quad → clipped vertex list (possibly empty). Pure function. */
    public static float[] clipPolyToQuad(float[] poly, float[] quad) {
        float[] out = poly.clone();
        for (int i = 0; i < 4; i++) {
            float ax = quad[i * 2], ay = quad[i * 2 + 1];
            float bx = quad[((i + 1) % 4) * 2], by = quad[((i + 1) % 4) * 2 + 1];
            float[] next = new float[out.length + 8];
            int n = 0;
            int cnt = out.length / 2;
            for (int j = 0; j < cnt; j++) {
                float px = out[j * 2], py = out[j * 2 + 1];
                float qx = out[((j + 1) % cnt) * 2], qy = out[((j + 1) % cnt) * 2 + 1];
                boolean pIn = cross2(ax, ay, bx, by, px, py) >= 0;
                boolean qIn = cross2(ax, ay, bx, by, qx, qy) >= 0;
                if (qIn) {
                    if (!pIn) {
                        float[] ip = intersect2(ax, ay, bx, by, px, py, qx, qy);
                        next[n++] = ip[0]; next[n++] = ip[1];
                    }
                    next[n++] = qx; next[n++] = qy;
                } else if (pIn) {
                    float[] ip = intersect2(ax, ay, bx, by, px, py, qx, qy);
                    next[n++] = ip[0]; next[n++] = ip[1];
                }
            }
            out = new float[n];
            System.arraycopy(next, 0, out, 0, n);
            if (n == 0) return out;
        }
        return out;
    }

    /** 相机平面半平面裁剪（阈值 0，2026-08-24 指示线遮罩用，**逐顶点 fz 版**）：
     *  subject 多边形（画布局部坐标）对「fz ≤ threshold」半平面做 Sutherland-Hodgman
     *  单边裁剪，剔除相机后方（fz>0）的镜像内容。fz 数组是每个顶点经
     *  transformPosition（与 emitAnchored 同路径）算出的视线深度，边交点用 fz
     *  线性插值——不依赖矩阵元素手算（JOML 列主序下手算 m20/m21/m23 与
     *  transformPosition 语义不一致，会算出假 fz 误切）。阈值 0 只切真正越过后方
     *  的内容，fz<0 正常内容不动。纯函数——可单测。
     *  Camera-plane half-plane clip (threshold 0, per-vertex fz): Sutherland-Hodgman
     *  single-edge clip of the subject polygon (canvas-local coords) against
     *  fz ≤ threshold, cutting mirrored behind-camera (fz>0) content. fz[] holds
     *  each vertex's view depth from transformPosition (same path as emitAnchored);
     *  edge hits are linearly interpolated in fz — no hand-derived matrix elements
     *  (JOML column-major m20/m21/m23 disagree with transformPosition, which made
     *  false fz≈-0.1 and wrongly cut content). Pure function — unit-testable. */
    public static float[] clipPolyByDepth(float[] poly, float[] fz, float threshold) {
        int cnt = poly.length / 2;
        if (cnt < 3) return new float[0];
        float[] out = new float[0];
        float[] next = new float[poly.length + 8];
        int n = 0;
        for (int j = 0; j < cnt; j++) {
            float px = poly[j * 2], py = poly[j * 2 + 1];
            float qx = poly[((j + 1) % cnt) * 2], qy = poly[((j + 1) % cnt) * 2 + 1];
            float fp = fz[j], fq = fz[(j + 1) % cnt];
            boolean pIn = fp <= threshold, qIn = fq <= threshold;
            if (qIn) {
                if (!pIn) {
                    float t = (threshold - fp) / (fq - fp);
                    next[n++] = px + t * (qx - px);
                    next[n++] = py + t * (qy - py);
                }
                next[n++] = qx; next[n++] = qy;
            } else if (pIn) {
                float t = (threshold - fp) / (fq - fp);
                next[n++] = px + t * (qx - px);
                next[n++] = py + t * (qy - py);
            }
        }
        out = new float[n];
        System.arraycopy(next, 0, out, 0, n);
        return out;
    }

    /** 裁剪多边形 AABB / AABB of a clipped polygon (float[]{x,y,...}) */
    public static float[] polyAabb(float[] poly) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < poly.length; i += 2) {
            if (poly[i] < minX) minX = poly[i];
            if (poly[i] > maxX) maxX = poly[i];
            if (poly[i + 1] < minY) minY = poly[i + 1];
            if (poly[i + 1] > maxY) maxY = poly[i + 1];
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    /** 矩形绕中心 (cxr,cyr) 旋转 cosR/sinR 后的 AABB（内容局部坐标，字符级遮罩用）。
     *  AABB of a rect rotated by cosR/sinR about (cxr,cyr) (content-local, for the
     *  per-glyph mask). Pure function — unit-testable. */
    public static float[] rotatedAabb(float x0, float y0, float x1, float y1,
            float cxr, float cyr, float cosR, float sinR) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float px = (i & 1) == 0 ? x0 : x1;
            float py = (i & 2) == 0 ? y0 : y1;
            float dx = px - cxr, dy = py - cyr;
            float rx = cxr + dx * cosR - dy * sinR;
            float ry = cyr + dx * sinR + dy * cosR;
            if (rx < minX) minX = rx;
            if (rx > maxX) maxX = rx;
            if (ry < minY) minY = ry;
            if (ry > maxY) maxY = ry;
        }
        return new float[]{minX, minY, maxX, maxY};
    }
}
