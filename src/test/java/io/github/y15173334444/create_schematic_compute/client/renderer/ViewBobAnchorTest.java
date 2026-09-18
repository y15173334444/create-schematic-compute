package io.github.y15173334444.create_schematic_compute.client.renderer;

import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视角摇晃（view bob）相关的回归约束（2026-09-18）。
 * View-bobbing regression constraints (2026-09-18).
 *
 * <p>背景：Minecraft 把 {@code bobHurt}/{@code bobView} 写进一个 PoseStack 后**乘进投影
 * 矩阵**（GameRenderer.renderLevel），作用于 view space，从不进入 BER 的 poseStack。
 * 玩家行走时因此出现"HUD 虚像晃动"（关掉"视角摇晃"即消失，用户已实测确认）。
 * Background: Minecraft folds bobHurt/bobView into the PROJECTION matrix, so it acts
 * on view space and never reaches the BER poseStack — hence the "HUD virtual image
 * wobbling while walking" report (disabling "View Bobbing" removes it; confirmed).
 *
 * <p>这里锁住两条**实测得出**的结论，防止以后有人凭直觉改回去：
 * These two conclusions were reached by measurement and are pinned so nobody
 * reverses them on intuition:
 * <ol>
 *   <li>深度锚定要**跟随** bob，不要去"补偿"它 —— 补偿后漂移反而大 3.7 倍。</li>
 *   <li>遮罩用的 eye 必须**带上** bob 的平移（相机的视觉位置被 bob 挪了）。</li>
 * </ol>
 */
class ViewBobAnchorTest {

    private static final float VIRTUAL_IMAGE_D = 100f;
    private static final float GLASS_Z = -3f;
    private static final float GLASS_HALF_H = 0.6f;

    /** NDC 的 y（fov 70°）：y/(-z)·k。 / NDC y: y/(-z)·k */
    private static float ndcY(Vector3f v, float k) { return v.y * k / (-v.z); }

    /**
     * 深度锚定必须跟随 bob —— 反向"补偿"会让漂移变大（实测 0.007 → 0.025 NDC）。
     * 直觉上"让虚像不受 bob 影响"似乎更对，但玻璃自身被 bob 搬动的幅度远大于虚像与
     * 玻璃之间的那点差异，所以补偿掉等于把虚像从玻璃上撕开。
     * The depth anchor must FOLLOW bob — "compensating" it makes the drift worse
     * (measured 0.007 → 0.025 NDC). Letting the image ignore bob sounds right, but bob
     * moves the glass itself far more than the image-to-glass difference, so
     * compensating tears the image off the glass.
     */
    @Test
    @DisplayName("The anchor must follow bob — compensating it drifts MORE")
    void followingBobBeatsCompensatingIt() {
        float k = (float) (1.0 / Math.tan(Math.toRadians(35)));
        float canvasY = 0.6f * VIRTUAL_IMAGE_D;
        float canvasZ = GLASS_Z - VIRTUAL_IMAGE_D;
        float s = GLASS_Z / canvasZ;
        var image = new Vector3f(0f, canvasY * s, GLASS_Z);   // 虚像边缘（锚定到玻璃深度）
        var glass = new Vector3f(0f, GLASS_HALF_H, GLASS_Z);

        var bob = MonitorBlockEntityRenderer.bobTransform(0.5f, 1.0f);
        float base = ndcY(image, k) - ndcY(glass, k);
        var glassBob = bob.transformPosition(new Vector3f(glass));
        float follows = ndcY(bob.transformPosition(new Vector3f(image)), k) - ndcY(glassBob, k);
        float compensated = ndcY(image, k) - ndcY(glassBob, k);

        float driftFollows = Math.abs(follows - base);
        float driftCompensated = Math.abs(compensated - base);
        System.out.printf("base=%.4f  follows=%.4f (drift %.4f)  compensated=%.4f (drift %.4f)%n",
            base, follows, driftFollows, compensated, driftCompensated);
        assertTrue(driftFollows < driftCompensated,
            "following bob must stay closer than compensating it: follow " + driftFollows
                + " vs compensate " + driftCompensated);
    }

    /** 遮罩用的 eye 必须随 bob 平移 —— 否则裁剪边界相对内容滑动。
     *  The mask eye must translate with bob, or the clip edge slides over the content. */
    @Test
    @DisplayName("The mask eye carries bob's translation")
    void eyeFollowsBob() {
        var bob = MonitorBlockEntityRenderer.bobTransform(0.5f, 1.0f);
        var eye = new Vector4f(0f, 0f, 0f, 1f);
        bob.transform(eye);
        assertTrue(Math.abs(eye.x) + Math.abs(eye.y) > 1e-6f,
            "bob must move the eye (the camera's visual position), or the mask edge slides");
    }

    /** 站定时（amp=0）一切必须照旧：bob 是单位矩阵、eye 仍在原点。
     *  Standing still (amp=0): bob is the identity and the eye stays at the origin. */
    @Test
    @DisplayName("Standing still (amp=0) changes nothing")
    void standingStillIsIdentity() {
        var bob = MonitorBlockEntityRenderer.bobTransform(0.5f, 0f);
        var v = new Vector3f(1.2f, -0.4f, -3f);
        var out = bob.transformPosition(new Vector3f(v));
        assertTrue(Math.abs(out.x - v.x) < 1e-6f && Math.abs(out.y - v.y) < 1e-6f
                && Math.abs(out.z - v.z) < 1e-6f, "amp=0 must be the identity, got " + out);
        var eye = new Vector4f(0f, 0f, 0f, 1f);
        bob.transform(eye);
        assertTrue(Math.abs(eye.x) < 1e-6f && Math.abs(eye.y) < 1e-6f && Math.abs(eye.z) < 1e-6f,
            "amp=0 must leave the eye at the origin");
    }
}
