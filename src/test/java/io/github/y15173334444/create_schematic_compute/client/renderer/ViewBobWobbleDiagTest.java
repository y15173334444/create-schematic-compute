package io.github.y15173334444.create_schematic_compute.client.renderer;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.y15173334444.create_schematic_compute.client.renderer.MonitorClipMath.anchoredEmit;
import static io.github.y15173334444.create_schematic_compute.client.renderer.MonitorClipMath.projectGlassCornersToCanvas;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：行走 bob 下 HUD 虚像的行为（2026-09-19 修订，用户选定的方案 C）。
 * Regression: HUD virtual-image behaviour under walk bob (2026-09-19 revision,
 * option C chosen by the user).
 *
 * <p>数值复现 renderHud 的整条管线（相机旋转取单位阵——面板正对相机的规范场景，
 * 各配置差异与朝向无关）：画布点 → 内容矩阵 → bob 后相机空间 → 沿 bob 后射线
 * 锚定（产线路径走**真实生产函数** {@link MonitorClipMath#anchoredEmit}）→ GPU 侧
 * 再乘 bob（bob 在投影矩阵里，GameRenderer.renderLevel）→ NDC。对 bob 相位采样
 * 一圈，量两件事：
 * Numerically replays the renderHud pipeline (unit camera rotation — a canonical
 * head-on panel): canvas point → content matrix → post-bob camera space → anchor
 * along the post-bob ray (the production path calls the **real** production function
 * {@link MonitorClipMath#anchoredEmit}) → GPU-side bob (bob lives in the projection
 * matrix) → NDC. Sampling a full bob phase cycle measures:
 * <ol>
 *   <li>内容点 NDC 摆幅 —— 用户报告的"晃动"。产线要求**共形**：只剩与全世界
 *       一致的旋转分量（远物视差被消掉），Bob 平移分量必须消失。</li>
 *       <i>the NDC sway of a content point — the reported wobble. Production must be
 *       **conformal**: only the world-rotation component remains (far-object
 *       translation parallax is cancelled).</i></li>
 *   <li>遮罩角点（按内容管线渲染）与玻璃角点的 NDC 落差在一圈里的最大值 ——
 *       裁剪窗口必须贴住玻璃（v1.2.5.2 的 +B·0 眼把它放大到 0.046 NDC）。</li>
 *       <i>the max NDC gap between the mask corner (rendered through the content
 *       pipeline) and the glass corner — the clip window must track the glass.</i></li>
 * </ol>
 *
 * <p>矩阵里同时保留两个**反事实**配置作对照（公式内联复刻，仅用于对比打印/断言，
 * 不再是产线路径）：
 * The matrix also keeps two **counterfactual** configurations for comparison
 * (formulas inlined; comparison only — no longer the production path):
 * <ul>
 *   <li>PRE（v1.2.5.2 及更早）：预 bob 坐标锚定 —— bob 的视空间平移按**玻璃深度**
 *       （近）做透视除 → 内容按近物视差随玻璃晃（0.038 NDC @amp0.1，用户最初报告
 *       的"晃动"）。与其配对的遮罩眼是原点（窗口落差恰好 0）。</li>
 *       <i>PRE (≤ v1.2.5.2): anchor in pre-bob coords — bob's translation divides at
 *       the near glass depth → the content sways glued to the glass. Pairs with the
 *       origin mask eye (gap exactly 0).</i></li>
 *   <li>COMP：发射 B⁻¹·A(c)，屏幕位置完全 bob 免疫 —— 与产线只差旋转分量是否
 *       保留；遮罩眼同样必须是视觉眼。</li>
 *       <i>COMP: emit B⁻¹·A(c), fully bob-immune screen position — differs from
 *       production only in keeping the world-rotation sway; same visual-eye mask.</i></li>
 * </ul>
 * 配对法则（数值实证）：锚定方式决定遮罩眼——PRE↔原点、POST/COMP↔视觉眼 B⁻¹·0；
 * v1.2.5.2 的 +B·0（"原点被 bob 搬到哪"）与任何锚定都不封闭。
 * Pairing rule (measured): the anchoring dictates the mask eye — PRE↔origin,
 * POST/COMP↔the visual eye B⁻¹·0; v1.2.5.2's +B·0 closes with nothing.
 */
class ViewBobWobbleDiagTest {

    private static final float D = 100f;           // VIRTUAL_IMAGE_D
    private static final float GLASS_Z = -3f;      // 玻璃深度（相机前方）/ glass depth (in front)
    private static final float HW = 1f, HH = 0.6f; // 2.0×1.2 面板 / default panel
    private static final float K = (float) (1.0 / Math.tan(Math.toRadians(35))); // fov 70°

    private static final int ANCHOR_PRE = 0, ANCHOR_COMP = 1, ANCHOR_POST = 2;
    private static final int EYE_UNBOBBED = 0, EYE_CURRENT = 1, EYE_TRUE = 2;

    /** 产线配置（2026-09-19 修订）：沿 bob 后射线锚定 + 视觉眼遮罩。
     *  Production config (2026-09-19 revision): post-bob-ray anchoring + the visual-eye mask. */
    private static final int PRODUCTION_ANCHOR = ANCHOR_POST;
    private static final int PRODUCTION_EYE = EYE_TRUE;

    /** 用户可感知阈值：0.002 NDC ≈ 0.07° @fov70（窗口落差须低于此）。
     *  Visible threshold: 0.002 NDC ≈ 0.07° at fov 70 (the window gap must stay below). */
    private static final float VISIBLE_NDC = 0.002f;

    private static float ndcX(Vector3f v) { return v.x * K / (-v.z); }
    private static float ndcY(Vector3f v) { return v.y * K / (-v.z); }

    /** 面板矩阵 m：面板局部 → 视图（相机旋转取单位阵）。 panel-local → view. */
    private static Matrix4f panelMatrix() { return new Matrix4f().translate(0f, 0f, GLASS_Z); }

    /** 画布矩阵 m2 = m · T(0,0,-D) · S(D,D,-D)：画布局部 → 视图，1:1 复刻
     *  renderHud 的 poseStack 链。 1:1 with the renderHud poseStack chain. */
    private static Matrix4f canvasMatrix() {
        return new Matrix4f(panelMatrix()).translate(0f, 0f, -D).scale(D, D, -D);
    }

    /** 内容画布点的**最终视图坐标**（GPU 乘完 bob 之后）。POST 走真实生产函数
     *  {@link MonitorClipMath#anchoredEmit}；PRE/COMP 为内联复刻的反事实对照。
     *  Final view position of a canvas point (after the GPU bob). POST goes through
     *  the real production function; PRE/COMP are inlined counterfactual replicas. */
    private static Vector3f finalView(Matrix4f m2, Vector2f c, float zAnchor, Matrix4f bob, int anchor) {
        switch (anchor) {
            case ANCHOR_POST: {
                // 产线路径：anchoredEmit 的发射坐标经 GPU（viewRot=I → 直接乘 bob）
                // 落到最终视图位置。 Production path: the emitted coords go through
                // the GPU (viewRot = I → multiply bob directly) to the final position.
                var bobInv = bob.invert(new Matrix4f());       // emitMat = viewRotInv·B⁻¹ = B⁻¹
                var emit = anchoredEmit(m2, bob, bobInv, c.x, c.y, 0f, zAnchor);
                return bob.transformPosition(new Vector3f(emit));
            }
            case ANCHOR_PRE: {
                // 反事实（≤v1.2.5.2）：预 bob 坐标锚定，GPU 的 bob 按玻璃深度除。
                var v = new Vector3f(c.x, c.y, 0f);
                m2.transformPosition(v);
                float s = zAnchor / v.z;
                return bob.transformPosition(new Vector3f(v.x * s, v.y * s, zAnchor));
            }
            default: {
                // 反事实 COMP：发射 B⁻¹·A(c)，GPU 乘回后恰为 A(c)，与 bob 无关。
                var v = new Vector3f(c.x, c.y, 0f);
                m2.transformPosition(v);
                float s = zAnchor / v.z;
                return new Vector3f(v.x * s, v.y * s, zAnchor);
            }
        }
    }

    /** renderHud 遮罩眼 → 面板局部（1:1 复刻）。 Mask eye → panel-local (1:1). */
    private static Vector3f eyeLocal(Matrix4f bob, int eyeMode) {
        var eye = new Vector4f(0f, 0f, 0f, 1f);
        if (eyeMode == EYE_CURRENT) bob.transform(eye);
        else if (eyeMode == EYE_TRUE) bob.invert(new Matrix4f()).transform(eye);
        var eyeLocal = new Vector4f(eye.x, eye.y, eye.z, 1f); // viewRotInv = I
        panelMatrix().invert().transform(eyeLocal);
        return new Vector3f(eyeLocal.x, eyeLocal.y, eyeLocal.z);
    }

    private static final int PHASES = 16;

    /** 内容画布中心点一圈 bob 相位的 NDC 摆幅（x/y 分开）。 NDC sway of the canvas
     *  center across a full bob cycle (x/y separately). */
    private static float[] contentSway(float amp, int anchor) {
        var m2 = canvasMatrix();
        float minX = 1e9f, maxX = -1e9f, minY = 1e9f, maxY = -1e9f;
        for (int i = 0; i < PHASES; i++) {
            var bob = MonitorBlockEntityRenderer.bobTransform(i / (float) PHASES, amp);
            var v = finalView(m2, new Vector2f(0f, 0f), GLASS_Z, bob, anchor);
            minX = Math.min(minX, ndcX(v)); maxX = Math.max(maxX, ndcX(v));
            minY = Math.min(minY, ndcY(v)); maxY = Math.max(maxY, ndcY(v));
        }
        return new float[]{maxX - minX, maxY - minY};
    }

    /** 远处世界点（-1000 格，旋转视差参照）一圈相位的 NDC 摆幅 —— 全世界共形的
     *  下限参照（平移视差 ≈ 0，只剩 bob 旋转分量）。
     *  NDC sway of a far world point (-1000 blocks, the rotation-parallax reference)
     *  — the conformal floor every world object shares (translation ≈ 0, only the
     *  bob rotation remains). */
    private static float[] farWorldSway(float amp) {
        float minX = 1e9f, maxX = -1e9f, minY = 1e9f, maxY = -1e9f;
        for (int i = 0; i < PHASES; i++) {
            var bob = MonitorBlockEntityRenderer.bobTransform(i / (float) PHASES, amp);
            var v = bob.transformPosition(new Vector3f(0f, 0f, -1000f));
            minX = Math.min(minX, ndcX(v)); maxX = Math.max(maxX, ndcX(v));
            minY = Math.min(minY, ndcY(v)); maxY = Math.max(maxY, ndcY(v));
        }
        return new float[]{maxX - minX, maxY - minY};
    }

    /** 玻璃右上角点一圈相位的 NDC 摆幅 —— 近处世界物参照（含平移视差）。
     *  NDC sway of the glass's top-right corner — the near world-object reference
     *  (translation parallax included). */
    private static float[] glassSway(float amp) {
        float minX = 1e9f, maxX = -1e9f, minY = 1e9f, maxY = -1e9f;
        for (int i = 0; i < PHASES; i++) {
            var bob = MonitorBlockEntityRenderer.bobTransform(i / (float) PHASES, amp);
            var g = bob.transformPosition(panelMatrix().transformPosition(new Vector3f(HW, HH, 0f)));
            minX = Math.min(minX, ndcX(g)); maxX = Math.max(maxX, ndcX(g));
            minY = Math.min(minY, ndcY(g)); maxY = Math.max(maxY, ndcY(g));
        }
        return new float[]{maxX - minX, maxY - minY};
    }

    /** 遮罩右上角点（按内容管线渲染）与玻璃右上角点的 NDC 落差在一圈相位里的
     *  最大值 —— 裁剪窗口是否贴住玻璃。 Max NDC gap between the mask's top-right
     *  corner (rendered through the content pipeline) and the glass corner across
     *  the cycle — does the clip window track the glass. */
    private static float[] maskGlassGap(float amp, int anchor, int eyeMode) {
        var m2 = canvasMatrix();
        float gx = 0f, gy = 0f;
        for (int i = 0; i < PHASES; i++) {
            var bob = MonitorBlockEntityRenderer.bobTransform(i / (float) PHASES, amp);
            var glassBob = bob.transformPosition(panelMatrix().transformPosition(new Vector3f(HW, HH, 0f)));
            var eye = eyeLocal(bob, eyeMode);
            float[] maskQuad = projectGlassCornersToCanvas(eye.x, eye.y, eye.z, HW, HH, D);
            var maskCorner = finalView(m2, new Vector2f(maskQuad[4], maskQuad[5]), GLASS_Z, bob, anchor);
            gx = Math.max(gx, Math.abs(ndcX(maskCorner) - ndcX(glassBob)));
            gy = Math.max(gy, Math.abs(ndcY(maskCorner) - ndcY(glassBob)));
        }
        return new float[]{gx, gy};
    }

    private static String f(float[] v) { return String.format("(%.4f, %.4f)", v[0], v[1]); }
    private static String a(int anchor) {
        return anchor == ANCHOR_PRE ? "PRE " : anchor == ANCHOR_COMP ? "COMP" : "POST";
    }
    private static String e(int eye) {
        return eye == EYE_UNBOBBED ? "UNBOB" : eye == EYE_CURRENT ? "+t  " : "TRUE";
    }

    @Test
    @DisplayName("Sway matrix — anchoring × mask eye, realistic walking amp")
    void swayMatrix() {
        float amp = 0.1f; // 近似正常步行 / approximate normal walking
        System.out.printf("[diag] amp=%.2f  glassSway=%s (near world)  farWorldSway=%s (rotation floor)%n",
            amp, f(glassSway(amp)), f(farWorldSway(amp)));
        for (int anchor = 0; anchor <= 2; anchor++) {
            for (int eye = 0; eye <= 2; eye++) {
                System.out.printf("[diag] anchor=%s eye=%s  contentSway=%s  maskGlassGap=%s%n",
                    a(anchor), e(eye), f(contentSway(amp, anchor)), f(maskGlassGap(amp, anchor, eye)));
            }
        }
        // 控制组：amp=0（站定）一切归零 —— 环本身不引入摆动。
        // Control: amp=0 (standing) must be all zeros — the harness adds no sway.
        assertTrue(contentSway(0f, ANCHOR_PRE)[0] < 1e-6f
                && contentSway(0f, ANCHOR_COMP)[1] < 1e-6f
                && contentSway(0f, ANCHOR_POST)[0] < 1e-6f,
            "amp=0 must give zero sway in every configuration");
    }

    @Test
    @DisplayName("Production (POST+TRUE): conformal sway, window glued to the glass")
    void productionIsConformalAndGlued() {
        float amp = 0.1f;
        float[] prod = contentSway(amp, PRODUCTION_ANCHOR);
        float prodSway = Math.max(prod[0], prod[1]);
        float[] pre = contentSway(amp, ANCHOR_PRE);
        float preSway = Math.max(pre[0], pre[1]);
        float[] floor = farWorldSway(amp);
        float floorSway = Math.max(floor[0], floor[1]);
        float[] gap = maskGlassGap(amp, PRODUCTION_ANCHOR, PRODUCTION_EYE);
        System.out.printf("[diag] production sway=%.4f (conformal floor %.4f, pre-fix %.4f)  window gap=%s%n",
            prodSway, floorSway, preSway, f(gap));
        // 1) 共形：内容摆幅必须贴着"全世界一起转"的下限（远物参照）——bob 平移
        //    分量被消掉（PRE 的 Y 摆 0.038 → 0.011）。给参照留 20% 余量吸收采样差。
        //    Conformal: the content sway must hug the "whole world rotates together"
        //    floor (the far-object reference) — bob's translation component is gone.
        assertTrue(prodSway < floorSway * 1.2f + 1e-4f,
            "production sway " + prodSway + " must be conformal with the far-world floor "
                + floorSway + " (bob translation still leaking?)");
        // 2) 严格更好：必须明显低于贴玻璃摆法（PRE）。
        assertTrue(prodSway < preSway * 0.5f,
            "production sway " + prodSway + " must be well under the glued-to-glass sway " + preSway);
        // 3) 窗口贴玻璃：裁剪边界与玻璃角的落差低于可感知阈值。
        assertTrue(Math.max(gap[0], gap[1]) < VISIBLE_NDC,
            "the clip window must track the glass, got gap " + f(gap));
    }

    @Test
    @DisplayName("Pairing pins: PRE↔origin closes exactly; the other eyes do not")
    void pairingPins() {
        float amp = 0.1f;
        // PRE 配原点：锚定沿预 bob 射线时，遮罩眼必须是原点（窗口落差恰为 0）。
        // PRE with the origin eye: the window gap is exactly zero.
        float[] preUnbob = maskGlassGap(amp, ANCHOR_PRE, EYE_UNBOBBED);
        assertTrue(Math.max(preUnbob[0], preUnbob[1]) < 1e-4f,
            "PRE+origin must glue the window to the glass exactly, got " + f(preUnbob));
        // PRE 配视觉眼/+t：窗口滑差显著（93e3530 的错配就是这一类）。
        // PRE with the visual eye / +t: a visible window slide (the 93e3530 mismatch).
        float[] preTrue = maskGlassGap(amp, ANCHOR_PRE, EYE_TRUE);
        assertTrue(Math.max(preTrue[0], preTrue[1]) > 0.01f,
            "PRE+visual-eye must NOT close, got " + f(preTrue));
        // POST 配视觉眼：窗口精确贴玻璃（产线配置）。
        float[] postTrue = maskGlassGap(amp, ANCHOR_POST, EYE_TRUE);
        assertTrue(Math.max(postTrue[0], postTrue[1]) < 1e-4f,
            "POST+visual-eye must glue the window exactly, got " + f(postTrue));
    }
}
