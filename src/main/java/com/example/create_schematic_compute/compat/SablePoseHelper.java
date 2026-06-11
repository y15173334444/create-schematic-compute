package com.example.create_schematic_compute.compat;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;

/**
 * sable 子世界姿态提取工具 — 避免在多个 BE 中重复实现。
 */
public class SablePoseHelper {

    /**
     * 从子世界的 logicalPose 中提取 YXZ 欧拉角（度）。
     * @return float[3] = { yaw, pitch, roll }
     */
    public static float[] getSubPose(ServerSubLevel subLevel) {
        float[] r = new float[3];
        try {
            var pose = subLevel.logicalPose();
            if (pose != null) {
                var oq = pose.orientation();
                if (oq != null) {
                    org.joml.Quaterniond q = new org.joml.Quaterniond(oq.x(), oq.y(), oq.z(), oq.w());
                    org.joml.Vector3d euler = new org.joml.Vector3d();
                    q.getEulerAnglesYXZ(euler);
                    r[0] = (float) Math.toDegrees(euler.y); // yaw
                    r[1] = (float) Math.toDegrees(euler.x); // pitch
                    r[2] = (float) Math.toDegrees(euler.z); // roll
                }
            }
        } catch (Exception ignored) {}
        return r;
    }
}
