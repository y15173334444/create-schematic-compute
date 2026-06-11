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

    public SensorBlockEntitySable(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double deltaTime) {
        if (level == null || level.isClientSide()) return;

        // ── 读取子世界姿态 ──
        float[] pose = SablePoseHelper.getSubPose(subLevel);
        cachedSubYaw = pose[0];
        cachedSubPitch = pose[1];
        cachedSubRoll = pose[2];
        if (getBlockState().hasProperty(SensorBlock.FACING))
            cachedBlockFacingYaw = getBlockState().getValue(SensorBlock.FACING).toYRot();
        hasSubPose = true;

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
