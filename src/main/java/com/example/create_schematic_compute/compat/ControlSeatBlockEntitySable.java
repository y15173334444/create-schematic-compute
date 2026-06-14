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

        // ── 先缓存子世界 pose（无论是否有骑手） ──
        float[] pose = SablePoseHelper.getSubPose(subLevel);
        cachedSubYaw = pose[0];
        cachedSubPitch = pose[1];
        cachedSubRoll = pose[2];
        if (Float.isNaN(initialSubYaw)) initialSubYaw = cachedSubYaw;
        if (getBlockState().hasProperty(ControlSeatBlock.FACING))
            cachedBlockFacingYaw = getBlockState().getValue(ControlSeatBlock.FACING).toYRot();
        hasSubPose = true;

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
        // 仅在摇杆模式下跟随sable结构旋转；视角差模式下玩家视角独立于结构（类似坦克炮塔）
        if (entity != null && inputMode == 0) {
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
