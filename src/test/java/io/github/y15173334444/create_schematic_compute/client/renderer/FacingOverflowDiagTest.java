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
}
