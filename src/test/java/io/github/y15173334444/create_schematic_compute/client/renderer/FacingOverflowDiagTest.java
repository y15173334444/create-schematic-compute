package io.github.y15173334444.create_schematic_compute.client.renderer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试（2026-08-24）：FACING 朝东西（EAST/WEST）时 HUD 虚像溢出的根因与修复。
 * BER 的 poseStack 只含相机平移（相机旋转在 RenderSystem 全局 modelView），因此
 * renderHud 里 poseStack 变换后的顶点 z 分量是**世界 Z 分量**而非视线深度。修复前
 * emitAnchored 直接用它做透视除法 s=zAnchor/fz：玩家面朝东西（视线沿 X 轴）时
 * 玻璃/画布的世界 Z 分量 ≈ 玩家 Z → fz≈0 → s 爆炸/翻转 → 虚像溢出（实测 EAST
 * s=0.9/-0.018、WEST s=-Infinity）。修复后顶点先经相机旋转 viewRot 到真正的
 * 相机空间（fz = 视线深度）再锚定。本测试对 4 个 FACING 断言：相机空间 fz 必有
 * 量级、锚定比例 s 必在 (0, 0.1]（南北东西一致）。
 * <p>
 * Regression (2026-08-24): the EAST/WEST HUD virtual-image overflow root cause &
 * fix. The BER poseStack carries only the camera translation (the camera rotation
 * lives in the RenderSystem modelView stack), so a vertex z after the poseStack is
 * a world-Z component, not the view depth. Pre-fix emitAnchored used it directly in
 * the perspective division s=zAnchor/fz: facing EAST/WEST (view along X) the
 * glass/canvas world-Z ≈ the player Z → fz≈0 → s blows up/flips → overflow (measured
 * EAST s=0.9/-0.018, WEST s=-Infinity). Post-fix the vertex is rotated by viewRot
 * into true camera space (fz = view depth) before anchoring. For all 4 facings this
 * test asserts: camera-space fz keeps a real magnitude and the anchor ratio s stays
 * in (0, 0.1] (identical across N/S/E/W).
 */
class FacingOverflowDiagTest {

    private static final float D = 100f;      // VIRTUAL_IMAGE_D
    private static final float PANEL_DIST = 0.05f;

    @Test
    void diagFacingDepth() {
        System.out.println("=== HUD depth-anchor regression (fixed: camera-space fz) ===");
        // FACING -> toYRot, FACING world dir (MC: NORTH=-Z, SOUTH=+Z, EAST=+X, WEST=-X)
        String[] names = {"NORTH", "SOUTH", "EAST", "WEST"};
        float[] yaws = {180f, 0f, -90f, 90f};
        float[][] dirs = {{0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}};
        // 玩家视线 = -FACING（面向玻璃）。相机空间前方 = -Z（OpenGL 约定）。
        // viewRot：把视线方向旋转到相机 -Z 轴（绕 Y 的水平旋转）。
        // Player view = -FACING (facing the glass). Camera forward = -Z.
        // viewRot: rotate the view direction onto the camera -Z axis (horizontal Ry).
        float[] viewYaws = {180f, 0f, -90f, 90f}; // 视线方向：NORTH→+Z、SOUTH→-Z、EAST→-X、WEST→+X

        for (int i = 0; i < 4; i++) {
            float facingYDeg = yaws[i];
            float gx = -3f * dirs[i][0], gy = -3f * dirs[i][1], gz = -3f * dirs[i][2];
            Matrix4f rot = new Matrix4f().rotateY((float) Math.toRadians(-facingYDeg));
            Vector3f localCenter = new Vector3f(0.5f, 0.5f, -0.5f - PANEL_DIST);
            rot.transformPosition(localCenter);
            float px = gx - localCenter.x, py = gy - localCenter.y, pz = gz - localCenter.z;
            Matrix4f m = new Matrix4f()
                .translate(px, py, pz)
                .translate(0.5f, 0.5f, 0.5f)
                .rotateY((float) Math.toRadians(-facingYDeg))
                .translate(0f, 0f, -(0.5f + PANEL_DIST));
            // 修复后：相机旋转（世界 → 相机空间）
            Matrix4f viewRot = new Matrix4f().rotateY((float) Math.toRadians(viewYaws[i]));
            Matrix4f viewRotInv = new Matrix4f(viewRot).invert();
            // glassZ：玻璃中心相机空间深度（视线深度）
            Vector3f glass = new Vector3f(0, 0, 0);
            m.transformPosition(glass);
            viewRot.transformPosition(glass);
            float glassZ = glass.z;
            // 内容顶点画布 (0,0) 与 (0.5,0)：先世界再相机空间（修复后的 fz 语义）
            Matrix4f m2 = new Matrix4f(m).translate(0f, 0f, -D).scale(D, D, -D);
            Vector3f c0 = new Vector3f(0f, 0f, 0f);
            m2.transformPosition(c0);
            viewRot.transformPosition(c0);
            Vector3f c1 = new Vector3f(0.5f, 0f, 0f);
            m2.transformPosition(c1);
            viewRot.transformPosition(c1);
            float zAnchor = glassZ - D * 0.001f;
            float s0 = zAnchor / c0.z, s1 = zAnchor / c1.z;
            System.out.printf("%-6s glassZ_cam=%.3f | fz_cam=(%.3f, %.3f) zAnchor=%.3f s=(%.3f, %.3f)%n",
                names[i], glassZ, c0.z, c1.z, zAnchor, s0, s1);

            // 断言：相机空间深度必有量级（视线前方 ≈ -103，不依赖世界 Z）
            assertTrue(Math.abs(c0.z) > 50f, names[i] + " fz must have magnitude, got " + c0.z);
            assertTrue(Math.abs(c1.z) > 50f, names[i] + " fz must have magnitude, got " + c1.z);
            // 断言：锚定比例 s 正常且为负（前方深度 zAnchor<0、fz<0 → s>0）；修复前 EAST/WEST 为 ±inf/翻转
            assertTrue(s0 > 0.001f && s0 <= 0.1f,
                names[i] + " s0 must be in (0.001, 0.1], got " + s0);
            assertTrue(s1 > 0.001f && s1 <= 0.1f,
                names[i] + " s1 must be in (0.001, 0.1], got " + s1);
            // 玻璃深度必须为负（玻璃在相机前方）
            assertTrue(glassZ < -1f, names[i] + " glassZ must be negative (in front), got " + glassZ);
        }
    }

    /**
     * 掠射回归（2026-08-24，s 钳制方案）：玩家视线与玻璃法线夹角 θ 增大时，画布
     * （玻璃后方 VIRTUAL_IMAGE_D 格）靠视线反侧的顶点视线深度 fz→0⁻ → 锚定比例
     * s=zAnchor/fz→∞ → 顶点坐标溢出为 Inf/NaN → 虚像撕裂/颜色溢出（用户实测视角差
     * ≥80° 必然触发）。修复：emitAnchored 把 s 钳制到 ±MAX_ANCHOR_S——坐标有界
     * （float 精确）、NDC 落视锥外 → GPU 视锥裁剪干净切掉；fz 正常（|s|≈0.03）时
     * 完全不受影响。**不用** 8-22 的 clipPolyToHalfPlane 几何裁剪（画布巨大 ×D，
     * 正常斜视 30-50° 时画布边缘 fz 也接近 0 → 误切正常可见内容 → 显示不全）。
     * <p>
     * Grazing regression (s-clamp approach): as θ between the player view and the
     * glass normal grows, far-canvas vertices on the far side of the view direction
     * go to view depth fz→0⁻ → the anchor ratio s=zAnchor/fz→∞ → vertex coords
     * overflow to Inf/NaN → the image tears/bleeds (user-measured, guaranteed at
     * view difference ≥80°). Fix: emitAnchored clamps s to ±MAX_ANCHOR_S — coords
     * stay finite (float-exact) with NDC outside the frustum → the GPU frustum clip
     * cuts them cleanly; normal |s|≈0.03 is untouched. The 8-22 clipPolyToHalfPlane
     * geometric clip is NOT used (the canvas is huge ×D, so ordinary oblique views
     * put canvas-edge fz near 0 → it wrongly cuts visible content → incomplete).
     */
    @Test
    void grazingSClamp() {
        System.out.println("=== grazing s-clamp regression ===");
        float facingYDeg = 180f; // NORTH FACING：玻璃法线朝 +Z（-FACING），玩家面朝 +Z
        Matrix4f rot = new Matrix4f().rotateY((float) Math.toRadians(-facingYDeg));
        Vector3f localCenter = new Vector3f(0.5f, 0.5f, -0.5f - PANEL_DIST);
        rot.transformPosition(localCenter);
        float px = -localCenter.x, py = -localCenter.y, pz = 3f - localCenter.z;
        Matrix4f m = new Matrix4f()
            .translate(px, py, pz)
            .translate(0.5f, 0.5f, 0.5f)
            .rotateY((float) Math.toRadians(-facingYDeg))
            .translate(0f, 0f, -(0.5f + PANEL_DIST));
        Matrix4f m2 = new Matrix4f(m).translate(0f, 0f, -D).scale(D, D, -D);
        // 画布 4 角（内容局部坐标：面板半宽 1.0、半高 0.6）+ 画布中心
        float[] canvas = {-1f, -0.6f, 1f, -0.6f, 1f, 0.6f, -1f, 0.6f, 0f, 0f};
        float k = 1.428f; // 1/tan(fov/2)，fov 70°（NDC 量级估算）

        for (float theta : new float[]{0f, 30f, 60f, 70f, 80f, 85f, 89f, 89.9f}) {
            // 玩家视线 = (sinθ, 0, cosθ)。viewRot 把视线旋转到相机 -Z：φ = 180°-θ
            Matrix4f viewRot = new Matrix4f().rotateY((float) Math.toRadians(180f - theta));
            // zAnchor：玻璃中心相机深度（m·0 → viewRot → z）减图层偏移
            Vector3f glass = new Vector3f(0f, 0f, 0f);
            m.transformPosition(glass);
            viewRot.transformPosition(glass);
            float zAnchor = glass.z - D * 0.001f;
            int clampedCount = 0, offscreenCount = 0;
            for (int kk = 0; kk < canvas.length / 2; kk++) {
                float x = canvas[kk * 2], y = canvas[kk * 2 + 1];
                // 与 emitAnchored 相同的数学：内容 → m2 → 世界 → viewRot → 相机空间
                Vector3f c = new Vector3f(x, y, 0f);
                m2.transformPosition(c);
                viewRot.transformPosition(c);
                float fx = c.x, fy = c.y, fz = c.z;
                float sRaw = zAnchor / fz;
                float s = Math.max(-MonitorBlockEntityRenderer.MAX_ANCHOR_S,
                    Math.min(MonitorBlockEntityRenderer.MAX_ANCHOR_S, sRaw));
                float vx = fx * s, vy = fy * s;
                // 锚定顶点坐标与比例必须有限（修复前掠射时 Inf/NaN → 撕裂）
                assertTrue(Float.isFinite(s) && Float.isFinite(vx) && Float.isFinite(vy),
                    "anchor must stay finite at " + theta + "°, corner " + kk);
                if (sRaw != s) clampedCount++;
                // NDC 估算：x_ndc = vx·k/(-zAnchor)
                float ndcX = vx * k / (-zAnchor);
                if (Math.abs(ndcX) > 1f) offscreenCount++;
                System.out.printf("theta=%.1f° corner(%+.1f,%+.1f) fz=%+.4f sRaw=%+.4e s=%+.4e ndcX=%+.2f%n",
                    theta, x, y, fz, sRaw, s, ndcX);
            }
            // 正常视角（θ≤30°）：s 一律不钳（|s|≈0.03，无任何钳制介入）
            if (theta <= 30f) {
                assertEquals(0, clampedCount, "normal view must never clamp at " + theta + "°");
            }
            // 掠射（θ≥80°）：画布角点 fz 变正（相机后方）→ s 小负（镜像）——s 钳制
            // 无法处理（|s| 小不触发），这部分内容依赖 mask 排除或相机平面裁剪
            // （见诊断输出；grazingSClamp 只验证 s 钳制自身行为，不覆盖镜像剔除）。
            if (theta >= 80f) {
                System.out.printf("  [info] theta=%.1f°: canvas corners crossing camera plane (fz>0 → mirrored s)%n", theta);
            }
        }
    }

    /** clipPolyByDepth 纯函数单测（2026-08-24，逐顶点 fz 相机平面裁剪）：
     *  全在相机前方（fz≤0）→ 不变；全在后方（fz>0）→ 空；跨越 → 精确裁剪。 */
    @Test
    void clipPolyByDepthUnit() {
        float[] sq = {0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f};
        // 全在相机前方（fz 全 ≤ 0）→ 原样保留
        float[] r1 = MonitorBlockEntityRenderer.clipPolyByDepth(sq, new float[]{-5f, -5f, -5f, -5f}, 0f);
        assertEquals(8, r1.length, "fully-in-front must keep all vertices");
        // 全在相机后方（fz 全 > 0）→ 空
        float[] r2 = MonitorBlockEntityRenderer.clipPolyByDepth(sq, new float[]{5f, 5f, 5f, 5f}, 0f);
        assertEquals(0, r2.length, "fully-behind must clip to empty");
        // 跨越：fz = x - 0.5（左半负、右半正）→ 保留 x ≤ 0.5 部分且非空
        float[] r3 = MonitorBlockEntityRenderer.clipPolyByDepth(
            sq, new float[]{-0.5f, 0.5f, 0.5f, -0.5f}, 0f);
        assertTrue(r3.length / 2 >= 3, "crossing must keep content, verts=" + r3.length / 2);
        for (int k = 0; k < r3.length / 2; k++) {
            assertTrue(r3[k * 2] <= 0.5f + 1e-4f,
                "clipped vertex must stay in front (x ≤ 0.5), got " + r3[k * 2]);
        }
    }

    /**
     * 相机后方顶点导致**镜像**的回归（2026-08-30 修复）。
     * Regression for the **mirroring** caused by behind-camera vertices (fixed 2026-08-30).
     *
     * <p>{@code emitAnchored} 只按量级钳制 s（±MAX_ANCHOR_S=1e4），从不看 fz 的符号。
     * 顶点跑到相机后方时 fz>0 → s=zAnchor/fz 变负 → 锚定顶点 (fx·s, fy·s) 被镜像到
     * 屏幕对侧。实测（θ=60°，远未到掠射）：远端两角 fz=+33.99、s=-7.99e-02、
     * ndcX=-5.85 —— |s| 只有 0.08，离 1e4 差五个数量级，s 钳制完全不介入。
     * 于是 quad 的一条边从 ndcX +0.40 被拉到 -5.85，横扫整个屏幕 = 用户报的
     * "部分视角渲染溢出"。
     * emitAnchored clamps s by magnitude only (±MAX_ANCHOR_S=1e4) and never looks at
     * the sign of fz. Behind the camera fz>0 → s=zAnchor/fz goes negative → the
     * anchored vertex (fx·s, fy·s) is mirrored across the screen. Measured at θ=60°
     * (nowhere near grazing): the two far corners sit at fz=+33.99, s=-7.99e-02,
     * ndcX=-5.85 — |s| is 0.08, five orders of magnitude below 1e4, so the s clamp
     * never engages. One quad edge is then stretched from ndcX +0.40 to -5.85,
     * sweeping across the whole screen: the reported "overflow at some angles".
     *
     * <p>修复：渲染前用 {@code clipPolyToCameraPlane} 把几何裁到相机平面（只留 fz≤0），
     * 镜像顶点根本不会进入 emitAnchored。本测试先证明镜像确实发生，再证明裁剪后
     * 所有保留顶点的 s 都为正（无镜像）。
     * Fix: geometry is clipped to the camera plane before emission, so mirrored
     * vertices never reach emitAnchored. This test first proves the mirroring happens,
     * then proves every surviving vertex keeps a positive s.
     */
    @Test
    void cameraPlaneClipRemovesMirroredVertices() {
        System.out.println("=== behind-camera mirror regression (fixed: camera-plane clip) ===");
        float theta = 60f;                 // 远未到掠射，常见斜视就会触发 / ordinary oblique view
        float facingYDeg = 180f;
        Matrix4f rot = new Matrix4f().rotateY((float) Math.toRadians(-facingYDeg));
        Vector3f localCenter = new Vector3f(0.5f, 0.5f, -0.5f - PANEL_DIST);
        rot.transformPosition(localCenter);
        float px = -localCenter.x, py = -localCenter.y, pz = 3f - localCenter.z;
        Matrix4f m = new Matrix4f()
            .translate(px, py, pz)
            .translate(0.5f, 0.5f, 0.5f)
            .rotateY((float) Math.toRadians(-facingYDeg))
            .translate(0f, 0f, -(0.5f + PANEL_DIST));
        Matrix4f m2 = new Matrix4f(m).translate(0f, 0f, -D).scale(D, D, -D);
        Matrix4f viewRot = new Matrix4f().rotateY((float) Math.toRadians(180f - theta));
        Vector3f glass = new Vector3f(0f, 0f, 0f);
        m.transformPosition(glass);
        viewRot.transformPosition(glass);
        float zAnchor = glass.z - D * 0.001f;
        float[] canvas = {-1f, -0.6f, 1f, -0.6f, 1f, 0.6f, -1f, 0.6f};

        // (1) 未裁剪时确有角点在相机后方，且每个后方顶点的 s 都是负的（镜像）
        //     Unclipped: some corners are behind the camera and every one mirrors (s<0).
        int behind = 0, negativeS = 0;
        for (int i = 0; i < 4; i++) {
            Vector3f c = new Vector3f(canvas[i * 2], canvas[i * 2 + 1], 0f);
            m2.transformPosition(c);
            viewRot.transformPosition(c);
            if (c.z > 0f) behind++;
            if (zAnchor / c.z < 0f) negativeS++;
            System.out.printf("raw corner(%+.1f,%+.1f) fz=%+.3f s=%+.4e%n",
                canvas[i * 2], canvas[i * 2 + 1], c.z, zAnchor / c.z);
        }
        assertTrue(behind > 0, "at " + theta + "° some canvas corners must fall behind the camera");
        assertEquals(behind, negativeS, "every behind-camera vertex must yield a mirrored (negative) s");

        // (2) 状态判定必须是"跨越" / the state must read as crossing
        assertEquals(MonitorBlockEntityRenderer.CAM_CROSSING,
            MonitorBlockEntityRenderer.cameraPlaneState(canvas, 0f, m2, viewRot),
            "canvas straddles the camera plane at " + theta + "°");

        // (3) 裁剪后：保留顶点全部 fz<=0 且 s>0（无镜像），并且仍有可见内容
        //     Clipped: every surviving vertex stays in front with a positive s.
        float[] clipped = MonitorBlockEntityRenderer.clipPolyToCameraPlane(canvas, 0f, m2, viewRot);
        assertTrue(clipped.length / 2 >= 3,
            "crossing canvas must keep visible content, verts=" + clipped.length / 2);
        for (int i = 0; i < clipped.length / 2; i++) {
            Vector3f c = new Vector3f(clipped[i * 2], clipped[i * 2 + 1], 0f);
            m2.transformPosition(c);
            viewRot.transformPosition(c);
            assertTrue(c.z <= 1e-3f, "clipped vertex must stay in front (fz<=0), got " + c.z);
            assertTrue(zAnchor / c.z > 0f,
                "clipped vertex must not mirror (s>0), got " + zAnchor / c.z);
        }
    }
}
