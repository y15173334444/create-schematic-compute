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

    /**
     * Constructs a Control Seat entity in the given level.
     * 在指定世界中构造控制座椅实体。
     *
     * @param type  entity type / 实体类型
     * @param level the world level this entity belongs to / 该实体所属的世界
     */
    public ControlSeatEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true; // 无物理 / no collision or gravity
    }

    /**
     * Returns the offset vector where a passenger should be visually attached
     * relative to this seat entity's position. The Y value (5/16) aligns with
     * the top face of the seat block so the player appears to be sitting on it.
     * <p>
     * 返回乘客相对于此座椅实体位置的视觉挂载偏移向量。Y 值 (5/16) 与座椅方块顶面
     * 对齐，使玩家看起来正坐在上面。
     *
     * @param passenger  the entity riding this seat / 骑乘此座椅的实体
     * @param dimensions the dimensions of the passenger entity / 乘客实体的尺寸
     * @param partialTick partial tick for interpolation / 用于插值的部分 tick
     * @return attachment-point offset relative to this entity / 相对此实体的挂载点偏移
     */
    @Override
    protected Vec3 getPassengerAttachmentPoint(Entity passenger,
            net.minecraft.world.entity.EntityDimensions dimensions, float partialTick) {
        return new Vec3(0.0, 0.3125, 0.0); // 5/16 — top face of the seat block / 座椅方块顶面
    }

    /**
     * Defines this entity's synched data entries for network replication.
     * This entity has no dynamic synchronized data to register.
     * <p>
     * 定义此实体的网络同步数据条目。此实体没有需要注册的动态同步数据。
     *
     * @param builder the builder for synched data entries / 同步数据条目的构建器
     */
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    /**
     * Reads additional save data from NBT when the entity is loaded.
     * This entity has no custom persistent data to restore.
     * <p>
     * 加载实体时从 NBT 读取额外的持久化数据。此实体没有需要恢复的自定义持久化数据。
     *
     * @param tag the NBT compound tag to read from / 要读取的 NBT 复合标签
     */
    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    /**
     * Writes additional save data to NBT when the entity is saved.
     * This entity has no custom persistent data to store.
     * <p>
     * 保存实体时将额外的持久化数据写入 NBT。此实体没有需要存储的自定义持久化数据。
     *
     * @param tag the NBT compound tag to write to / 要写入的 NBT 复合标签
     */
    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}

    /**
     * Returns the world-space position where a dismounting passenger should
     * be placed: one block above the seat, centered horizontally.
     * <p>
     * 返回乘客下马时应被放置的世界空间位置：座椅上方一格，水平居中。
     *
     * @param passenger the entity dismounting this seat / 正在下马的实体
     * @return dismount destination position / 下马目标位置
     */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        BlockPos pos = blockPosition();
        return new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
    }

    /**
     * Per-tick update. On the server side, auto-removes this entity when it has
     * no passenger. When Sable is not managing this entity (i.e. outside any
     * Sable sub-level), the seat manually follows the block it belongs to.
     * <p>
     * 每 tick 更新。在服务端，当没有乘客时自动移除自身。当 Sable 未管理此实体时
     * （即不在任何 Sable 子关卡内），座椅手动跟随其所属的方块位置。
     */
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
            if (!net.neoforged.fml.ModList.get().isLoaded("sable")
                || Sable.HELPER.getContaining(this) == null) {
                BlockPos pos = blockPosition();
                setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            }
        }
    }

    /**
     * Returns whether this entity can be pushed by other entities.
     * Always false — the seat is immovable by external forces.
     * <p>
     * 返回此实体是否可被其他实体推动。始终为 false —— 座椅不可被外力移动。
     *
     * @return always false / 始终为 false
     */
    @Override
    public boolean isPushable() { return false; }
}
