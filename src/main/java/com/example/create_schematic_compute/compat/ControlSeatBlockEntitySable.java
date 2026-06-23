package com.example.create_schematic_compute.compat;

import com.example.create_schematic_compute.blocks.ControlSeatBlock;
import com.example.create_schematic_compute.blocks.ControlSeatBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * sable$physicsTick: 消费输入 + 更新实体 yaw + 缓存子世界 pose
 *
 * 实体引用通过 ControlSeatBlock.setSeatEntity() 注入，避免全范围搜索。
 */
public class ControlSeatBlockEntitySable extends ControlSeatBlockEntity implements BlockEntitySubLevelActor {

    private Level savedLevel;
    private java.util.UUID riderUUID = null;
    private volatile float cachedSubYaw = 0, cachedSubPitch = 0, cachedSubRoll = 0;
    private volatile float cachedBlockFacingYaw = 0;
    /** 子世界初始 yaw（用于计算相对旋转，消除初始偏移） */
    private volatile float initialSubYaw = Float.NaN;
    private volatile boolean hasSubPose = false;
    // 原始本地速度由基类字段 rawVelX/Y/Z 存储，tick() 差分为加速度

    public ControlSeatBlockEntitySable(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        savedLevel = level;
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        savedLevel = level;
    }


    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double deltaTime) {
        if (this.level == null) this.level = savedLevel;
        if (this.level == null || this.level.isClientSide()) return;

        // ── 先缓存子世界 pose 和位置（无论是否有骑手） ──
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
        if (Float.isNaN(initialSubYaw)) initialSubYaw = cachedSubYaw;
        if (getBlockState().hasProperty(ControlSeatBlock.FACING))
            cachedBlockFacingYaw = getBlockState().getValue(ControlSeatBlock.FACING).toYRot();
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

        // ── 检测骑手（setSeatEntity 在 useWithoutItem 中调用） ──
        boolean hasRider = false;
        var entity = mySeatEntity;
        if (entity != null) {
            for (var p : entity.getPassengers()) {
                if (p instanceof Player pl) {
                    riderUUID = pl.getUUID();
                    hasRider = true;
                    break;
                }
            }
        }

        if (!hasRider) {
            riderUUID = null;
            keyBits = 0; mouseJoystickX = 0; mouseJoystickY = 0;
            // 即使无骑手也要更新姿态（让 ATTITUDE/FORWARD 节点持续输出）
            updateAttitude();
            return;
        }

        // 消费输入
        if (riderUUID != null) consumeInputByPlayer(riderUUID);

        // 更新姿态/前方朝向（tick() 中会调用 updateAttitude()）
        updateAttitude();

        // 更新实体 yaw → 使用相对旋转（减去初始偏移），保持 getYRot() 与初始朝向一致
        if (entity != null) {
            float relativeYaw = cachedSubYaw - initialSubYaw;
            entity.yRotO = cachedBlockFacingYaw - relativeYaw;
            entity.setYHeadRot(cachedBlockFacingYaw - relativeYaw);
        }
    }

    @Override
    protected void adjustViewAngle() {
        // 客户端发送的是 playerYaw - vehicleYaw 差值
    }

    /** 从缓存的子世界姿态计算 attitude / forward，供 tick() 使用 */
    @Override
    protected void updateAttitude() {
        blockYaw = cachedBlockFacingYaw;
        if (!hasSubPose) {
            super.updateAttitude();
            return;
        }

        // ── 姿态（yaw 用相对旋转并取反以匹配 Minecraft 顺时针为正的约定）──
        float relativeYaw = cachedSubYaw - initialSubYaw;
        attitudeYaw = -relativeYaw;
        attitudePitch = cachedSubPitch;
        attitudeRoll = cachedSubRoll;

        // ── 前方朝向：方块朝向经子世界相对旋转 ──
        forwardYaw = cachedBlockFacingYaw - relativeYaw;
        forwardPitch = cachedSubPitch; // 直接用子世界俯仰
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
