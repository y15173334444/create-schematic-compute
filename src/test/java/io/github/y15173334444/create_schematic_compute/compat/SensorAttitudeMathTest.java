package io.github.y15173334444.create_schematic_compute.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 姿态传感器 ATTITUDE 节点数学的回归测试。
 * Regression tests for the attitude sensor ATTITUDE-node math.
 * <p>
 * 真实 bug 模式（2026-08-23 实测复现）：同一 Sable 结构上不同朝向的姿态传感器，
 * ATTITUDE 节点输出完全相同（旧实现只取结构级 pitch/roll，忽略方块朝向）。
 * 修复后 pitch/roll 由「方块朝向 × 结构姿态」的局部基向量推导，不同朝向输出不同。
 * <p>
 * The real bug pattern (reproduced in-game 2026-08-23): sensors with different
 * facings on the same Sable structure reported identical ATTITUDE outputs (the
 * old implementation used structure-level pitch/roll only, ignoring the block
 * facing). After the fix, pitch/roll derive from the block basis rotated by
 * facing × sub-world pose, so different facings yield different outputs.
 */
class SensorAttitudeMathTest {

    private static final double TOL = 0.6; // 度 / degrees

    private static float[] att(float fy, float sy, float sp, float sr) {
        return SensorAttitudeMath.blockAttitude(fy, sy, sp, sr);
    }

    @Test
    @DisplayName("单位姿态 → pitch/roll 均为 0 / identity pose → pitch/roll zero")
    void identityPoseProducesZero() {
        float[] a = att(0, 0, 0, 0);
        assertEquals(0, a[0], TOL);
        assertEquals(0, a[1], TOL);
    }

    @Test
    @DisplayName("无结构旋转时，朝向只影响 yaw，不影响 pitch/roll")
    void horizontalFacingsOnLevelStructureShareZeroAttitude() {
        for (float fy : new float[]{0, 90, 180, -90}) {
            float[] a = att(fy, 0, 0, 0);
            assertEquals(0, a[0], TOL, "pitch for facing " + fy);
            assertEquals(0, a[1], TOL, "roll for facing " + fy);
        }
    }

    @Test
    @DisplayName("核心修复：同一结构姿态下，不同方块朝向产生不同 ATTITUDE 输出")
    void differentFacingProducesDifferentAttitude() {
        // 实测结构姿态：yaw=6.37°, pitch=19.03°, roll=-0.28°（2026-08-23 日志）
        // Real structure pose captured in-game.
        float[] west = att(90, 6.37f, 19.03f, -0.28f);   // 面 WEST
        float[] north = att(180, 6.37f, 19.03f, -0.28f); // 面 NORTH
        // 两个朝向的 pitch/roll 必须显著不同（修复前完全相同）
        assertTrue(Math.abs(west[0] - north[0]) > 10, "pitch must differ per facing");
        assertTrue(Math.abs(west[1] - north[1]) > 10, "roll must differ per facing");
    }

    @Test
    @DisplayName("侧向方块：结构俯仰体现为方块的横滚（pitch≈0, |roll|≈结构俯仰）")
    void sidewaysBlockSeesStructurePitchAsRoll() {
        float[] west = att(90, 6.37f, 19.03f, -0.28f);
        // 量级：结构俯仰 19.03° 在侧向方块上表现为横滚；符号由 JOML 旋转约定决定，
        // 与已验证正确的 FORWARD 节点同一套约定（正前方方块 roll 与结构 roll 同号）。
        assertEquals(19.03, Math.abs(west[1]), TOL, "west block |roll| ≈ structure pitch");
        assertTrue(Math.abs(west[0]) < 1, "west block pitch ≈ 0");
    }

    @Test
    @DisplayName("正向方块：pitch 与 FORWARD.pitch 一致（前向仰角）")
    void forwardBlockPitchMatchesForwardElevation() {
        // 面 NORTH（-Z）时结构俯仰体现为前向仰角 +19.03（与 FORWARD 节点实测一致）
        float[] north = att(180, 0, 19.03f, 0);
        assertEquals(19.03, north[0], TOL, "north block pitch ≈ structure pitch");
    }

    @Test
    @DisplayName("锁定实测行为：实测结构姿态下各朝向的具体输出值（2026-08-23 探针验证）")
    void locksMeasuredBehavior() {
        // 实测结构姿态 yaw=6.37° pitch=19.03° roll=-0.28°，探针实测输出（真实 JOML）：
        // facing=180 (NORTH) -> pitch=19.030 roll=+0.280
        float[] north = att(180, 6.37f, 19.03f, -0.28f);
        assertEquals(19.03, north[0], 0.2, "north pitch");
        assertEquals(0.28, north[1], 0.2, "north roll (backward-facing mirror of structure roll)");
        // facing=90 (WEST) -> pitch=0.265 roll=-19.030（结构俯仰表现为侧向横滚）
        float[] west = att(90, 6.37f, 19.03f, -0.28f);
        assertEquals(0.265, west[0], 0.3, "west pitch ≈ 0");
        assertEquals(-19.03, west[1], 0.2, "west roll = -structure pitch");
        // facing=0 (SOUTH) -> pitch=-19.030（与 FORWARD 同约定：前向 +Z 被上仰结构压下）
        float[] south = att(0, 6.37f, 19.03f, -0.28f);
        assertEquals(-19.03, south[0], 0.2, "south pitch (forward-elevation convention)");
        assertEquals(-0.28, south[1], 0.2, "south roll");
    }

    @Test
    @DisplayName("前向垂直（±90° 俯仰）时 roll 无定义 → 0，不产生 NaN")
    void verticalForwardHasZeroRoll() {
        float[] a = att(0, 0, 89.99f, 0);
        assertEquals(0, a[1], TOL);
        assertTrue(Float.isFinite(a[0]) && Float.isFinite(a[1]));
    }
}
