package com.example.create_schematic_compute.compat;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.blocks.RadarBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Radar 的 Sable 兼容层 — 通过 sable$physicsTick 直接获取子世界位置。
 */
public class RadarBlockEntitySable extends RadarBlockEntity implements BlockEntitySubLevelActor {

    private volatile Level savedLevel;

    public RadarBlockEntitySable(BlockPos pos, BlockState state) { super(pos, state); }

    @Override
    protected Level getEffectiveLevel() {
        // 在 Sable 结构上时，始终使用子世界的 Level（savedLevel）
        // 因为 level 可能被设为 null 或指向主世界
        if (!Float.isNaN(cachedSubYaw) && savedLevel != null) return savedLevel;
        return level != null ? level : savedLevel;
    }

    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double deltaTime) {
        if (this.level == null) this.level = savedLevel;
        if (this.level == null || this.level.isClientSide()) return;
        if (savedLevel == null) savedLevel = this.level;
        try {
            var pose = subLevel.logicalPose();
            var pos = pose.position();
            var orient = pose.orientation();
            var rp = pose.rotationPoint(); // 旋转轴心在子世界本地坐标的位置

            // 缓存所在子世界的世界原点（供 scanSableStructures 的 isHost 比较）
            if (pos != null) {
                cachedSubOriginX = (float) pos.x();
                cachedSubOriginY = (float) pos.y();
                cachedSubOriginZ = (float) pos.z();
            }

            // 计算雷达在子世界本地坐标中的位置（BlockPos 中心 + 0.5）
            double localX = worldPosition.getX() + 0.5;
            double localY = worldPosition.getY() + 0.5;
            double localZ = worldPosition.getZ() + 0.5;

            // 雷达世界坐标 = 子世界位置 + 旋转 * (雷达本地坐标 - 旋转轴心)
            if (pos != null && rp != null) {
                var localOffset = new org.joml.Vector3d(localX - rp.x(), localY - rp.y(), localZ - rp.z());
                var q = new org.joml.Quaterniond(orient.x(), orient.y(), orient.z(), orient.w());
                q.transform(localOffset); // 本地偏移 → 世界偏移（应用子世界旋转）
                cachedSubWorldX = (float) (pos.x() + localOffset.x);
                cachedSubWorldY = (float) (pos.y() + localOffset.y);
                cachedSubWorldZ = (float) (pos.z() + localOffset.z);
            } else if (pos != null) {
                // 无 rotationPoint 时回退到子世界原点
                cachedSubWorldX = (float) pos.x();
                cachedSubWorldY = (float) pos.y();
                cachedSubWorldZ = (float) pos.z();
            }

            // 缓存四元数分量（供渲染器/射线检测做精确逆旋转，避免 Euler 角精度丢失）
            cachedSubQx = (float) orient.x();
            cachedSubQy = (float) orient.y();
            cachedSubQz = (float) orient.z();
            cachedSubQw = (float) orient.w();

            // Euler 角已由四元数取代，仅保留兼容（渲染器/锁定已迁移到四元数）
            var q = new org.joml.Quaterniond(orient.x(), orient.y(), orient.z(), orient.w());
            var euler = new org.joml.Vector3d();
            q.getEulerAnglesYXZ(euler);
            cachedSubYaw = (float) Math.toDegrees(euler.y);
            cachedSubPitch = (float) Math.toDegrees(euler.x);
            cachedSubRoll = (float) Math.toDegrees(euler.z);
        } catch (Exception e) {
            SchematicCompute.LOGGER.warn("Radar sable$physicsTick failed at {}: {}",
                worldPosition, e.toString());
        }
    }

    @Override public void onLoad() { super.onLoad(); savedLevel = level; }
    @Override public void setLevel(Level l) { super.setLevel(l); savedLevel = l; }

    @Override public Iterable<dev.ryanhcode.sable.sublevel.SubLevel> sable$getLoadingDependencies() { return java.util.Collections.emptyList(); }
    @Override public Iterable<dev.ryanhcode.sable.sublevel.SubLevel> sable$getConnectionDependencies() { return java.util.Collections.emptyList(); }
}
