package io.github.y15173334444.create_schematic_compute.compat;

/**
 * 姿态传感器 ATTITUDE 节点的纯数学工具 — 方块自身世界姿态推导。
 * Pure math for the attitude sensor's ATTITUDE node — block world-space attitude.
 * <p>
 * 姿态（pitch/roll）从「方块朝向 × 子世界姿态」合成后的局部基向量推导：
 * 前向向量决定俯仰（与 FORWARD 节点同公式），上向量绕前向轴的倾斜决定横滚。
 * 因此同一结构上朝向不同的传感器块，其 ATTITUDE 输出不同
 * （修复前只取结构级 pitch/roll，与方块朝向无关）。pitch 的符号约定
 * 与 FORWARD 节点一致（前向仰角），非旧的欧拉角约定。
 * <p>
 * Attitude (pitch/roll) is derived from the block's local basis vectors after
 * composing the block FACING with the sub-world pose: the forward vector yields
 * pitch (same formula as the FORWARD node) and the up vector's tilt around the
 * forward axis yields roll. Blocks with different facings on the same structure
 * therefore report different ATTITUDE values. The pitch sign convention matches
 * the FORWARD node (forward elevation), not the legacy Euler-angle convention.
 * <p>
 * 纯函数、零 Minecraft/Sable 依赖，可单测。
 * Pure function with no Minecraft/Sable dependencies — unit-testable.
 */
public final class SensorAttitudeMath {

    private SensorAttitudeMath() {}

    /**
     * 由方块朝向与子世界姿态计算方块自身世界空间的 [pitch, roll]（度）。
     * Compute the block's world-space [pitch, roll] (degrees) from its facing
     * and the sub-world pose.
     *
     * @param facingYaw 方块 FACING 的偏航角（{@code Direction.toYRot()}，度）
     *                   the block FACING yaw (Direction.toYRot(), degrees)
     * @param subYaw    子世界姿态偏航（度） / sub-world pose yaw (degrees)
     * @param subPitch  子世界姿态俯仰（度） / sub-world pose pitch (degrees)
     * @param subRoll   子世界姿态横滚（度） / sub-world pose roll (degrees)
     * @return float[2] = { pitch, roll }，均为角度制 / both in degrees
     */
    public static float[] blockAttitude(float facingYaw, float subYaw, float subPitch, float subRoll) {
        // ── 方块本地基向量：先绕 Y 旋转 -facingYaw（方块朝向），再经子世界四元数 ──
        // Local basis: rotate by -facingYaw around Y (block facing), then apply the sub-world quaternion.
        org.joml.Vector3d fwd = new org.joml.Vector3d(0, 0, 1);
        fwd.rotateY(Math.toRadians(-facingYaw));
        org.joml.Vector3d up = new org.joml.Vector3d(0, 1, 0);
        up.rotateY(Math.toRadians(-facingYaw));
        org.joml.Quaterniond subQ = new org.joml.Quaterniond()
            .rotateY(Math.toRadians(subYaw))
            .rotateX(Math.toRadians(subPitch))
            .rotateZ(Math.toRadians(subRoll));
        subQ.transform(fwd);
        subQ.transform(up);

        // ── 俯仰：前向向量仰角（与 FORWARD 节点同公式）──
        // Pitch: forward-vector elevation (same formula as the FORWARD node).
        double pitchDeg = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, fwd.y / fwd.length()))));

        // ── 横滚：上向量绕前向轴的倾斜（bank angle）──
        // Roll: the up vector's tilt around the forward axis (bank angle).
        double rollDeg;
        org.joml.Vector3d nf = new org.joml.Vector3d(fwd).normalize();
        // 与 fwd 垂直的“上参考”方向（世界 +Y 去掉沿前向的分量）
        // World-up reference projected onto the plane perpendicular to fwd.
        org.joml.Vector3d refUp = new org.joml.Vector3d(0, 1, 0);
        refUp.fma(-nf.y, nf);
        if (refUp.lengthSquared() > 1e-12) {
            refUp.normalize();
            // 横滚 = atan2(up·侧向轴, up·参考上向)；侧向轴 = fwd × refUp
            // Roll = atan2(up·lateral, up·refUp); lateral = fwd × refUp
            org.joml.Vector3d lateral = new org.joml.Vector3d(nf).cross(refUp);
            rollDeg = Math.toDegrees(Math.atan2(up.dot(lateral), up.dot(refUp)));
        } else {
            // 前向接近垂直（±90° 俯仰），横滚无定义 → 0
            // Forward nearly vertical (±90° pitch): roll undefined → 0
            rollDeg = 0;
        }
        while (rollDeg > 180) rollDeg -= 360;
        while (rollDeg < -180) rollDeg += 360;
        return new float[]{(float) pitchDeg, (float) rollDeg};
    }
}
