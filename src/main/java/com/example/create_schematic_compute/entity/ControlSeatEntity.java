package com.example.create_schematic_compute.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class ControlSeatEntity extends Entity {
    private static final EntityDataAccessor<Float> DATA_SABLE_RELATIVE_YAW =
        SynchedEntityData.defineId(ControlSeatEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_SABLE_RELATIVE_PITCH =
        SynchedEntityData.defineId(ControlSeatEntity.class, EntityDataSerializers.FLOAT);

    public ControlSeatEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public float getSableRelativeYaw() { return entityData.get(DATA_SABLE_RELATIVE_YAW); }
    public void setSableRelativeYaw(float v) { entityData.set(DATA_SABLE_RELATIVE_YAW, v); }
    public float getSableRelativePitch() { return entityData.get(DATA_SABLE_RELATIVE_PITCH); }
    public void setSableRelativePitch(float v) { entityData.set(DATA_SABLE_RELATIVE_PITCH, v); }

    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger, net.minecraft.world.entity.EntityDimensions dimensions, float partialTick) {
        return new Vec3(0.0, 0.3125, 0.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_SABLE_RELATIVE_YAW, 0f);
        builder.define(DATA_SABLE_RELATIVE_PITCH, 0f);
    }

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
                remove(RemovalReason.DISCARDED);
                return;
            }
            BlockPos pos = blockPosition();
            setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        }
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction callback) {
        float sy = passenger.getYRot();
        super.positionRider(passenger, callback);
        passenger.setYRot(sy);
    }

    @Override
    public boolean isPushable() { return false; }
}
