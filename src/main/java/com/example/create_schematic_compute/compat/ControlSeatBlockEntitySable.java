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

public class ControlSeatBlockEntitySable extends ControlSeatBlockEntity implements BlockEntitySubLevelActor {

    private Level savedLevel;
    private java.util.UUID riderUUID = null;
    private volatile float cachedSubYaw = 0, cachedSubPitch = 0, cachedSubRoll = 0;
    private volatile float cachedBlockFacingYaw = 0;
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

        float[] pose = SablePoseHelper.getSubPose(subLevel);
        cachedSubYaw = pose[0]; cachedSubPitch = pose[1]; cachedSubRoll = pose[2];
        if (Float.isNaN(initialSubYaw)) initialSubYaw = cachedSubYaw;
        if (getBlockState().hasProperty(ControlSeatBlock.FACING))
            cachedBlockFacingYaw = getBlockState().getValue(ControlSeatBlock.FACING).toYRot();
        hasSubPose = true;

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
            updateAttitude();
            return;
        }

        if (riderUUID != null) consumeInputByPlayer(riderUUID);

        updateAttitude();

        // Update entity yaw based on sable rotation
        float relativeYaw = cachedSubYaw - initialSubYaw;
        if (entity != null) {
            // Sync relativeYaw to client via SynchedEntityData (needed for View Angle compensation)
            entity.setSableRelativeYaw(relativeYaw);
            // Entity yaw always follows structure — needed for viewYaw = playerYaw - entityYaw
            // Player's view vector is independently protected by EntitySubLevelUtilMixin
            entity.yRotO = cachedBlockFacingYaw - relativeYaw;
            entity.setYHeadRot(cachedBlockFacingYaw - relativeYaw);
        }
    }

    @Override
    protected void adjustViewAngle() {}

    @Override
    protected void updateAttitude() {
        blockYaw = cachedBlockFacingYaw;
        if (!hasSubPose) {
            super.updateAttitude();
            return;
        }
        float relativeYaw = cachedSubYaw - initialSubYaw;
        attitudeYaw = -relativeYaw;
        attitudePitch = cachedSubPitch;
        attitudeRoll = cachedSubRoll;
        forwardYaw = cachedBlockFacingYaw - relativeYaw;
        forwardPitch = cachedSubPitch;
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
