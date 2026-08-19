package io.github.y15173334444.create_schematic_compute.compat;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.MonitorBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sable-compatible Monitor Block Entity (BE).
 * <p>
 * Implements {@link BlockEntitySubLevelActor} to receive {@code sable$physicsTick}
 * callbacks inside the sub-world, directly reading {@code subLevel.logicalPose()}
 * to cache the BE's real-world position and the structure's orientation quaternion.
 * The client-side HUD conformal projection ({@code hudPanelFrame}) uses these cached
 * values — on a Sable structure {@code getBlockPos()} returns sub-world local
 * coordinates, which do not match the player camera's world-space frame, so the
 * pitch-ladder projection failed entirely (all rays t≤0 / off-panel → nothing drawn).
 * <p>
 * sable 兼容的全息显示器方块实体（BE）：
 * 实现 {@link BlockEntitySubLevelActor} 接口，在子世界中接收 {@code sable$physicsTick}
 * 回调，通过 {@code subLevel.logicalPose()} 缓存方块的真实世界坐标与结构朝向四元数。
 * 客户端 HUD 共形投影（{@code hudPanelFrame}）使用这些缓存值——Sable 结构上的
 * {@code getBlockPos()} 返回子世界本地坐标，与玩家相机的世界坐标系不匹配，
 * 导致俯仰梯投影完全失败（射线 t≤0 / 出界 → 一条都不画）。
 */
public class MonitorBlockEntitySable extends MonitorBlockEntity implements BlockEntitySubLevelActor {

    /** Backup Level reference — {@code level} can be null during early lifecycle
     *  phases (e.g. before onLoad completes in sable$physicsTick).
     *  {@code level} 备份引用——生命周期早期（如 sable$physicsTick 在 onLoad 完成前）可能为 null。 */
    private volatile Level savedLevel;

    public MonitorBlockEntitySable(BlockPos pos, BlockState s) { super(pos, s); }

    /**
     * Sable per-physics-frame callback: cache the BE's world-space position and the
     * structure's orientation quaternion from the sub-level's logical pose.
     * <p>
     * Sable 物理帧回调：从子层级的 logicalPose 缓存方块世界坐标与结构朝向四元数。
     * <p>
     * World position = sub-level origin + rotation × (local pos − rotation pivot),
     * matching {@link RadarBlockEntitySable} / {@link SensorBlockEntitySable}.
     * The quaternion rotates structure-local vectors (FACING normal / right / up,
     * panel offset, panel distance) into world space on the renderer side.
     */
    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double deltaTime) {
        if (savedLevel == null) savedLevel = subLevel.getLevel();
        if (this.level == null) this.level = savedLevel;
        if (this.level == null || this.level.isClientSide()) return;

        try {
            var pose = subLevel.logicalPose();
            if (pose == null) return;
            var pos = pose.position();
            var orient = pose.orientation();
            var rp = pose.rotationPoint();
            if (pos == null) return;

            // 方块中心（子世界本地坐标）+ 0.5 / block center (sub-world local) + 0.5
            double lx = worldPosition.getX() + 0.5;
            double ly = worldPosition.getY() + 0.5;
            double lz = worldPosition.getZ() + 0.5;

            if (rp != null && orient != null) {
                // 世界坐标 = 子世界原点 + 旋转 × (本地坐标 − 旋转中心)
                // World position = sub-level origin + rotation × (local pos − pivot)
                var localOffset = new org.joml.Vector3d(lx - rp.x(), ly - rp.y(), lz - rp.z());
                var q = new org.joml.Quaterniond(orient.x(), orient.y(), orient.z(), orient.w());
                q.transform(localOffset);
                cachedSubWorldX = (float) (pos.x() + localOffset.x);
                cachedSubWorldY = (float) (pos.y() + localOffset.y);
                cachedSubWorldZ = (float) (pos.z() + localOffset.z);
            } else {
                // 无旋转中心/朝向：回退到子世界原点 / no pivot/orientation: fall back to origin
                cachedSubWorldX = (float) pos.x();
                cachedSubWorldY = (float) pos.y();
                cachedSubWorldZ = (float) pos.z();
            }

            if (orient != null) {
                cachedSubQx = (float) orient.x();
                cachedSubQy = (float) orient.y();
                cachedSubQz = (float) orient.z();
                cachedSubQw = (float) orient.w();
            }
        } catch (Exception e) {
            SchematicCompute.LOGGER.warn("Monitor sable$physicsTick failed at {}: {}", worldPosition, e.toString());
        }
    }
}
