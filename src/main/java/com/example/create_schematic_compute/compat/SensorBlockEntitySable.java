package com.example.create_schematic_compute.compat;

import com.example.create_schematic_compute.blocks.SensorBlock;
import com.example.create_schematic_compute.blocks.SensorBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * sable 兼容的姿态传感器 BE：
 * 实现 BlockEntitySubLevelActor 从而在子世界中接收 physicsTick，
 * 直接读取 subLevel.logicalPose() 获取结构姿态。
 */
public class SensorBlockEntitySable extends SensorBlockEntity implements BlockEntitySubLevelActor {

    private volatile float cachedSubYaw = 0, cachedSubPitch = 0, cachedSubRoll = 0;
    private volatile float cachedBlockFacingYaw = 0;
    private volatile boolean hasSubPose = false;
    // 原始本地速度由基类字段 rawVelX/Y/Z 存储，tick() 差分为加速度

    public SensorBlockEntitySable(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double deltaTime) {
        if (level == null || level.isClientSide()) return;

        // ── 读取子世界姿态和位置 ──
        float[] pose = SablePoseHelper.getSubPose(subLevel);
        cachedSubYaw = pose[0];
        cachedSubPitch = pose[1];
        cachedSubRoll = pose[2];
        try {
            var pos = subLevel.logicalPose().position();
            if (pos != null) {
                cachedSubWorldX = (float) pos.x();
                cachedSubWorldY = (float) pos.y();
                cachedSubWorldZ = (float) pos.z();
            }
        } catch (Exception ignored) {}
        if (getBlockState().hasProperty(SensorBlock.FACING))
            cachedBlockFacingYaw = getBlockState().getValue(SensorBlock.FACING).toYRot();
        hasSubPose = true;

        // ── 原始本地速度（世界→本地旋转，纯浮点运算无 GC）──
        double wx = subLevel.latestLinearVelocity.x();
        double wy = subLevel.latestLinearVelocity.y();
        double wz = subLevel.latestLinearVelocity.z();
        double cy = Math.cos(Math.toRadians(cachedSubYaw)), sy = Math.sin(Math.toRadians(cachedSubYaw));
        double cp = Math.cos(Math.toRadians(cachedSubPitch)), sp = Math.sin(Math.toRadians(cachedSubPitch));
        double cr = Math.cos(Math.toRadians(cachedSubRoll)), sr = Math.sin(Math.toRadians(cachedSubRoll));
        // R^T = Rz^T * Rx^T * Ry^T, apply to world vector
        double v1x = cy * wx - sy * wz;
        double v1y = wy;
        double v1z = sy * wx + cy * wz;
        double v2x = v1x;
        double v2y = cp * v1y + sp * v1z;
        double v2z = -sp * v1y + cp * v1z;
        rawVelX = cr * v2x + sr * v2y;  // 结构前后
        rawVelY = -sr * v2x + cr * v2y; // 结构上下
        rawVelZ = v2z;                   // 结构左右

        // 从结构局部旋转到方块自身朝向（X=方块前后, Z=方块左右）
        double blockAngle = Math.toRadians(-cachedBlockFacingYaw);
        double cb = Math.cos(blockAngle), sb = Math.sin(blockAngle);
        double bvx = sb * rawVelX + cb * rawVelZ;
        double bvz = cb * rawVelX - sb * rawVelZ;
        rawVelX = bvx;
        rawVelZ = bvz;

        // 更新 attitude/forward（tick() 会调用 updateAttitude() 转到此方法）
        updateAttitude();
    }

    /**
     * 覆盖父类的 updateAttitude()：
     * 使用 sable$physicsTick 中缓存的子世界姿态，不再用反射找子世界。
     */
    @Override
    protected void updateAttitude() {
        if (!hasSubPose) {
            // 没有子世界姿态时调用父类方法（走反射/默认值）
            super.updateAttitude();
            return;
        }

        // ── 姿态：子世界旋转的 pitch 和 roll ──
        attitudePitch = cachedSubPitch;
        attitudeRoll = cachedSubRoll;

        // ── 前方朝向：方块前方向量经子世界旋转 ──
        org.joml.Vector3d worldFwd = new org.joml.Vector3d(0, 0, 1);
        worldFwd.rotateY(Math.toRadians(-cachedBlockFacingYaw));

        org.joml.Quaterniond subQ = new org.joml.Quaterniond()
            .rotateY(Math.toRadians(cachedSubYaw))
            .rotateX(Math.toRadians(cachedSubPitch))
            .rotateZ(Math.toRadians(cachedSubRoll));
        subQ.transform(worldFwd);

        forwardYaw = (float)-Math.toDegrees(Math.atan2(worldFwd.x, worldFwd.z));
        forwardPitch = (float)Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, worldFwd.y / worldFwd.length()))));
        while (forwardYaw > 180) forwardYaw -= 360;
        while (forwardYaw < -180) forwardYaw += 360;
    }


    @Override
    public Iterable<dev.ryanhcode.sable.sublevel.SubLevel> sable$getLoadingDependencies() {
        return java.util.Collections.emptyList();
    }

    @Override
    public Iterable<dev.ryanhcode.sable.sublevel.SubLevel> sable$getConnectionDependencies() {
        return java.util.Collections.emptyList();
    }
}
