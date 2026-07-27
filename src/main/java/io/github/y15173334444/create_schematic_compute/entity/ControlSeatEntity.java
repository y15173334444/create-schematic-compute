package io.github.y15173334444.create_schematic_compute.entity;

import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Invisible seat entity for the Control Seat block. The player rides this entity;
 * Sable's rider-kick mechanism handles world-space positioning inside sub-levels.
 * <p>
 * 控制座椅方块的不可见座椅实体。玩家骑乘此实体；Sable 的骑手 kick 机制处理
 * 子关卡内的世界空间定位。
 */
public class ControlSeatEntity extends Entity {
    public ControlSeatEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true; // 无物理 / no collision or gravity
    }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger,
            net.minecraft.world.entity.EntityDimensions dimensions, float partialTick) {
        return new Vec3(0.0, 0.3125, 0.0); // 5/16 — top face of the seat block / 座椅方块顶面
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        BlockPos pos = blockPosition();
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide()) {
            if (!isVehicle()) {
                // Auto-remove when no passenger / 无乘客时自动移除
                remove(RemovalReason.DISCARDED);
                return;
            }
            // When inside a Sable sub-level, positioning is handled by Sable's
            // retain_in_sub_level tag + rider kick mechanism. Only manually
            // follow the block position when outside any sub-level.
            // 在 Sable 子关卡内时，位置由 Sable retain 标签 + 骑手 kick 机制处理；
            // 仅在非子关卡环境中手动跟随方块位置。
            if (Sable.HELPER.getContaining(this) == null) {
                BlockPos pos = blockPosition();
                setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            }
        }
    }

    @Override
    public boolean isPushable() { return false; }
}
