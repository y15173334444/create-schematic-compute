package io.github.y15173334444.create_schematic_compute.compat;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.RadarBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Radar 的 Sable 兼容层 — 通过 sable$physicsTick 直接获取子世界位置。
 * <p>
 * Sable compatibility layer for Radar — directly captures sub-level position via
 * {@code sable$physicsTick} instead of relying on the main-world Level reference.
 * <p>
 * This subclass overrides {@link RadarBlockEntity#getEffectiveLevel()} to always
 * return the sub-level's {@code Level} when the radar is hosted on a Sable structure,
 * because Sable may set {@code this.level} to {@code null} or point it at the
 * main overworld, which would break world-space coordinate calculations.
 *
 * @see RadarBlockEntity
 * @see BlockEntitySubLevelActor
 */
public class RadarBlockEntitySable extends RadarBlockEntity implements BlockEntitySubLevelActor {

    /**
     * 缓存子世界的 Level 引用，防止 Sable 将 {@code this.level} 置空后丢失世界上下文。
     * <p>
     * Cached reference to the sub-level's {@code Level}, guarding against Sable
     * nulling out {@code this.level} and causing loss of world context.
     * Declared {@code volatile} because it may be written from the physics thread
     * and read from the render/main thread.
     */
    private volatile Level savedLevel;

    /**
     * 构造一个在 Sable 子世界中运行的雷达方块实体。
     * <p>
     * Constructs a radar block entity that operates within a Sable sub-level.
     *
     * @param pos   方块在世界（子世界本地）中的坐标 / block position in the sub-level's local coordinate space
     * @param state 方块状态 / block state
     */
    public RadarBlockEntitySable(BlockPos pos, BlockState state) { super(pos, state); }

    /**
     * 返回当前有效的 {@link Level}：优先使用缓存的子世界 Level，避免 Sable 将
     * {@code level} 置空后坐标计算基于错误的（主世界）Level。
     * <p>
     * Returns the effective {@code Level}: prefers the cached sub-level reference
     * so that coordinate calculations do not silently fall back to the main overworld
     * when Sable nulls out {@code this.level}.
     *
     * @return 当前有效的世界实例 / the effective world instance
     */
    @Override
    protected Level getEffectiveLevel() {
        // 在 Sable 结构上时，始终使用子世界的 Level（savedLevel）
        // 因为 level 可能被设为 null 或指向主世界
        // When hosted on a Sable structure, always use the sub-level (savedLevel)
        // because `level` may be nulled or redirected to the overworld
        if (!Float.isNaN(cachedSubYaw) && savedLevel != null) return savedLevel;
        return level != null ? level : savedLevel;
    }

    /**
     * Sable 物理每帧回调：从子世界的逻辑姿态直接计算雷达的世界坐标与朝向。
     * <p>
     * Per-physics-frame callback from Sable: directly computes the radar's
     * world-space position and orientation from the sub-level's logical pose.
     * <p>
     * This is the core of the Sable compat layer — instead of polling or
     * reverse-engineering the sub-level transform, we receive it directly
     * from the physics engine each tick, guaranteeing accuracy.
     *
     * @param subLevel  雷达所在的服务器子世界 / the server-side sub-level hosting this radar
     * @param handle    关联的物理刚体句柄 / the physics rigid-body handle tied to this entity
     * @param deltaTime 自上一物理帧以来的时间增量（秒）/ time delta since last physics frame, in seconds
     */
    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double deltaTime) {
        // 始终持有有效的 Level 引用：以 subLevel.getLevel() 作为兜底
        // Always have a valid level reference: use subLevel.getLevel() as fallback
        if (savedLevel == null) savedLevel = subLevel.getLevel();
        if (this.level == null) this.level = savedLevel;

        // 客户端侧无需计算物理姿态，直接返回以避免不必要的运算
        // Skip computation on the client side — physics pose is server-authoritative
        if (this.level == null || this.level.isClientSide()) return;

        try {
            var pose = subLevel.logicalPose();
            var pos = pose.position();
            var orient = pose.orientation();
            // 旋转轴心在子世界本地坐标的位置
            // The rotation pivot point in the sub-level's local coordinate space
            var rp = pose.rotationPoint();

            // 缓存所在子世界的世界原点（供 scanSableStructures 的 isHost 比较）
            // Cache the sub-level's world-space origin (used by scanSableStructures
            // to determine host ownership via isHost comparison)
            if (pos != null) {
                cachedSubOriginX = (float) pos.x();
                cachedSubOriginY = (float) pos.y();
                cachedSubOriginZ = (float) pos.z();
            }

            // 计算雷达在子世界本地坐标中的位置（BlockPos 中心 + 0.5）
            // Compute the radar's position in sub-level local coordinates (block-center + 0.5)
            double localX = worldPosition.getX() + 0.5;
            double localY = worldPosition.getY() + 0.5;
            double localZ = worldPosition.getZ() + 0.5;

            // 雷达世界坐标 = 子世界位置 + 旋转 * (雷达本地坐标 - 旋转轴心)
            // Radar world position = sub-level origin + rotation * (radar local pos - rotation pivot)
            if (pos != null && rp != null) {
                // 计算本地偏移向量（雷达位置 - 旋转轴心）
                // Compute the local offset vector (radar position - rotation pivot)
                var localOffset = new org.joml.Vector3d(localX - rp.x(), localY - rp.y(), localZ - rp.z());
                var q = new org.joml.Quaterniond(orient.x(), orient.y(), orient.z(), orient.w());
                // 本地偏移 → 世界偏移（应用子世界旋转）
                // Transform local offset → world offset by applying the sub-level's rotation quaternion
                q.transform(localOffset);
                cachedSubWorldX = (float) (pos.x() + localOffset.x);
                cachedSubWorldY = (float) (pos.y() + localOffset.y);
                cachedSubWorldZ = (float) (pos.z() + localOffset.z);
            } else if (pos != null) {
                // 无 rotationPoint 时回退到子世界原点
                // Fall back to the sub-level origin when no rotationPoint is available
                cachedSubWorldX = (float) pos.x();
                cachedSubWorldY = (float) pos.y();
                cachedSubWorldZ = (float) pos.z();
            }

            // 缓存四元数分量（供渲染器/射线检测做精确逆旋转，避免 Euler 角精度丢失）
            // Cache quaternion components for the renderer and ray-intersection code
            // to perform exact inverse rotation, avoiding Euler-angle precision loss
            cachedSubQx = (float) orient.x();
            cachedSubQy = (float) orient.y();
            cachedSubQz = (float) orient.z();
            cachedSubQw = (float) orient.w();

            // Euler 角已由四元数取代，仅保留兼容（渲染器/锁定已迁移到四元数）
            // Euler angles are superseded by quaternions; kept only for backwards
            // compatibility (the renderer and lock logic have been migrated to quaternions)
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

    /**
     * 方块实体加载时初始化世界引用并重置子世界缓存。
     * <p>
     * Called when the block entity is loaded; initializes the world reference
     * and resets the cached sub-level so it will be re-resolved on next access.
     */
    @Override
    public void onLoad() {
        super.onLoad();
        // 记录当前 Level 并清空子世界缓存，确保下次访问时重新解析
        // Record the current Level and clear the sub-level cache so it is
        // re-resolved on the next dependency query
        savedLevel = level;
        cachedSubLevel = null;
    }

    /**
     * 方块实体的 Level 被外部修改时同步更新缓存引用。
     * <p>
     * Called when the block entity's {@code Level} is changed externally;
     * synchronizes the cached reference and resets the sub-level cache.
     *
     * @param l 新的世界实例 / the new world instance
     */
    @Override
    public void setLevel(Level l) {
        super.setLevel(l);
        // 同步更新缓存的 Level 引用并清空子世界缓存
        // Synchronize the cached Level reference and invalidate the sub-level cache
        savedLevel = l;
        cachedSubLevel = null;
    }

    /**
     * 缓存的子世界引用，避免每次查询依赖时重复解析 Sable 结构。
     * <p>
     * Cached sub-level reference to avoid re-resolving the containing Sable
     * structure on every dependency query. Declared {@code volatile} because
     * it may be read by Sable's loading/connection threads and written by
     * the main server thread.
     */
    private volatile SubLevel cachedSubLevel;

    /**
     * 返回此方块实体加载前必须就绪的子世界依赖（加载顺序依赖）。
     * <p>
     * Returns the sub-levels that must be loaded before this block entity can
     * be ticked (loading-order dependencies).
     *
     * @return 包含所在子世界的可迭代集合 / an iterable containing the hosting sub-level, or empty if none
     */
    @Override
    public Iterable<SubLevel> sable$getLoadingDependencies() { return resolveSubLevel(); }

    /**
     * 返回此方块实体需要保持连接的子世界依赖（连接依赖）。
     * <p>
     * Returns the sub-levels this block entity must maintain a connection to
     * (connection dependencies).
     *
     * @return 包含所在子世界的可迭代集合 / an iterable containing the hosting sub-level, or empty if none
     */
    @Override
    public Iterable<SubLevel> sable$getConnectionDependencies() { return resolveSubLevel(); }

    /**
     * 解析当前方块实体所在的 Sable 子世界，结果会被缓存以降低重复查询开销。
     * <p>
     * Resolves the Sable sub-level that contains this block entity. The result
     * is cached to amortize the cost of repeated dependency queries from
     * Sable's internal loading and connection systems.
     *
     * @return 包含所在子世界的列表（若在子世界中），否则返回空列表 /
     *         a list containing the hosting sub-level if found, or an empty list otherwise
     */
    private Iterable<SubLevel> resolveSubLevel() {
        if (cachedSubLevel != null) return java.util.List.of(cachedSubLevel);
        cachedSubLevel = SablePoseHelper.resolveSubLevel(level, worldPosition);
        if (cachedSubLevel != null) return java.util.List.of(cachedSubLevel);
        return java.util.Collections.emptyList();
    }
}
