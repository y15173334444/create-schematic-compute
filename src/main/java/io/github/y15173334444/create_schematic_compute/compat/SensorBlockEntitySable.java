package io.github.y15173334444.create_schematic_compute.compat;

import io.github.y15173334444.create_schematic_compute.blocks.SensorBlock;
import io.github.y15173334444.create_schematic_compute.blocks.SensorBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sable-compatible Sensor Block Entity (BE).
 * <p>
 * Implements {@link BlockEntitySubLevelActor} to receive {@code sable$physicsTick}
 * callbacks inside the sub-world, directly reading {@code subLevel.logicalPose()}
 * to obtain the structure's real-world pose. This avoids the reflection-based
 * fallback path in the parent class and provides more accurate physics data.
 * </p>
 * <p>
 * sable 兼容的姿态传感器方块实体（BE）：
 * 实现 {@link BlockEntitySubLevelActor} 接口，从而在子世界中接收 {@code sable$physicsTick}
 * 回调，直接通过 {@code subLevel.logicalPose()} 读取结构在真实世界中的姿态。
 * 该方法避免了父类中基于反射的回退路径，并提供更精确的物理数据。
 * </p>
 */
public class SensorBlockEntitySable extends SensorBlockEntity implements BlockEntitySubLevelActor {

    // ─────────────────────────────────────────────────────────────
    //  Cached sub-world state / 缓存的子世界状态
    // ─────────────────────────────────────────────────────────────

    /**
     * Cached sub-world yaw angle (degrees), extracted from the sub-world's logical pose.
     * 缓存的子世界偏航角（度），从子世界的 logicalPose 中提取。
     */
    private volatile float cachedSubYaw = 0, cachedSubPitch = 0, cachedSubRoll = 0;

    /**
     * Cached yaw angle of the block's FACING property (degrees).
     * Used to rotate velocity vectors from structure-local space into block-local space.
     * 缓存方块 FACING 属性的偏航角（度），用于将速度矢量从结构局部坐标系旋转到方块自身坐标系。
     */
    private volatile float cachedBlockFacingYaw = 0;

    /**
     * Whether the sub-world pose has been computed at least once.
     * When {@code false}, {@link #updateAttitude()} falls back to the parent path.
     * 子世界姿态是否至少计算过一次。为 {@code false} 时，{@link #updateAttitude()} 回退到父类路径。
     */
    private volatile boolean hasSubPose = false;

    /**
     * Backup reference to the {@link Level} this BE belongs to.
     * Stored because {@code level} can be null during certain lifecycle phases
     * (e.g. early {@code sable$physicsTick} before onLoad completion).
     * 所属 {@link Level} 的备份引用。由于在特定生命周期阶段（如 onLoad 完成前的早期
     * sable$physicsTick 回调中），{@code level} 可能为 null，因此需要保存此引用。
     */
    private volatile Level savedLevel;

    // 原始本地速度由基类字段 rawVelX/Y/Z 存储，tick() 差分为加速度
    // Raw local velocities are stored in parent fields rawVelX/Y/Z;
    // tick() differentiates them into acceleration.

    // ─────────────────────────────────────────────────────────────
    //  Sable SubLevel cache / Sable 子层级缓存
    // ─────────────────────────────────────────────────────────────

    /**
     * Cached (lightweight) snapshot of the Sable {@link SubLevel} this BE lives in.
     * Resolved once via chunk-based lookup, then reused across dependency queries.
     * 当前 BE 所在 Sable {@link SubLevel} 的缓存快照。通过基于 chunk 的查找解析一次后，
     * 在后续依赖查询中重复使用。
     */
    private volatile SubLevel cachedSubLevel;

    // ─────────────────────────────────────────────────────────────
    //  Constructor / 构造函数
    // ─────────────────────────────────────────────────────────────

    /**
     * Constructs a Sable-compatible SensorBlockEntity.
     *
     * @param pos   the block position in the world / 方块在世界中的位置
     * @param state the block state / 方块状态
     */
    public SensorBlockEntitySable(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    // ─────────────────────────────────────────────────────────────
    //  Lifecycle / 生命周期
    // ─────────────────────────────────────────────────────────────

    /**
     * Called when the BE is loaded into the world.
     * Saves the level reference and invalidates the sub-level cache,
     * because the association may change after a reload.
     *
     * BE 被加载到世界时调用。保存 level 引用并使子层级缓存失效，
     * 因为重新加载后关联关系可能发生变化。
     */
    @Override public void onLoad() { super.onLoad(); savedLevel = level; cachedSubLevel = null; }

    /**
     * Called when the BE's level reference changes (e.g., dimension change).
     * Updates the saved level and clears the sub-level cache so it is re-resolved.
     *
     * BE 的 level 引用变更时调用（例如维度切换）。更新保存的 level 并清除子层级缓存，
     * 使其在下次查询时重新解析。
     *
     * @param l the new level / 新的 Level 实例
     */
    @Override public void setLevel(Level l) { super.setLevel(l); savedLevel = l; cachedSubLevel = null; }

    // ─────────────────────────────────────────────────────────────
    //  Physics tick / 物理更新
    // ─────────────────────────────────────────────────────────────

    /**
     * Core Sable physics tick callback, invoked each simulation step within the sub-world.
     * <p>
     * This method performs three tasks in order:
     * <ol>
     *   <li>Read the sub-world's real-world pose (position + orientation from logicalPose)
     *       and cache the decomposed Euler angles and world-space coordinates.</li>
     *   <li>Transform the sub-world's linear velocity from world space into
     *       block-local space using cached orientation angles, so the parent
     *       {@code tick()} can differentiate into acceleration along each local axis.</li>
     *   <li>Update {@code attitude} and {@code forward} vector via
     *       {@link #updateAttitude()} so the sensor graph sees the latest pose.</li>
     * </ol>
     * </p>
     *
     * sable 物理更新核心回调，在子世界中每个仿真步调用一次。
     * <p>
     * 该方法按顺序执行三项工作：
     * <ol>
     *   <li>读取子世界的真实世界姿态（通过 logicalPose 获取位置+朝向），
     *       缓存分解后的欧拉角及世界空间坐标。</li>
     *   <li>利用缓存的朝向角，将子世界的线速度从世界坐标系转换到方块局部坐标系，
     *       以便父类 {@code tick()} 沿各局部轴差分得到加速度。</li>
     *   <li>通过 {@link #updateAttitude()} 更新 attitude 和 forward 矢量，
     *       使传感器图形能感知到最新姿态。</li>
     * </ol>
     * </p>
     *
     * @param subLevel  the Sable server sub-level driving this tick / 驱动本次 tick 的 Sable 服务端子层级
     * @param handle    the rigid-body handle for this BE in the sub-world / 此 BE 在子世界中的刚体句柄
     * @param deltaTime simulation delta time in seconds / 仿真时间步长（秒）
     */
    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double deltaTime) {
        // Restore level reference if it was cleared (e.g. during chunk unload/reload)
        // 如果 level 被清除则恢复引用（例如 chunk 卸载/重载期间）
        if (this.level == null) this.level = savedLevel;
        if (level == null || level.isClientSide()) return;

        // ── 读取子世界姿态和位置 ──
        // Read sub-world pose and position
        float[] pose = SablePoseHelper.getSubPose(subLevel);
        cachedSubYaw = pose[0];
        cachedSubPitch = pose[1];
        cachedSubRoll = pose[2];
        try {
            // Attempt to compute the block's real-world position via the sub-level's logical pose.
            // This accounts for both the sub-level's origin shift AND its rotation point offset,
            // so the sensor outputs coordinates meaningful in the overworld, not the sub-world.
            // 尝试通过子层级的 logicalPose 计算方块的真实世界坐标。
            // 此方法同时考虑了子层级的原点偏移和旋转中心偏移，
            // 因此传感器输出的是对主世界有意义的坐标，而非子世界内部坐标。
            var logicalPose = subLevel.logicalPose();
            var pos = logicalPose.position();
            var orient = logicalPose.orientation();
            var rp = logicalPose.rotationPoint();
            if (pos != null) {
                // 计算方块自身世界坐标（而非子世界轴心坐标）
                // Compute the block's own world coordinates (not the sub-world pivot coordinates)
                double lx = worldPosition.getX() + 0.5;
                double ly = worldPosition.getY() + 0.5;
                double lz = worldPosition.getZ() + 0.5;
                if (rp != null) {
                    // Rotation point exists: rotate local offset by sub-world orientation,
                    // then add to sub-world origin to get final world-space coordinates.
                    // 存在旋转中心：计算本地偏移量，经子世界朝向旋转变换后，
                    // 加上子世界原点得到最终世界空间坐标。
                    var localOffset = new org.joml.Vector3d(lx - rp.x(), ly - rp.y(), lz - rp.z());
                    var q = new org.joml.Quaterniond(orient.x(), orient.y(), orient.z(), orient.w());
                    q.transform(localOffset);
                    cachedSubWorldX = (float) (pos.x() + localOffset.x);
                    cachedSubWorldY = (float) (pos.y() + localOffset.y);
                    cachedSubWorldZ = (float) (pos.z() + localOffset.z);
                } else {
                    // No rotation point: use sub-world origin directly.
                    // 无旋转中心：直接使用子世界原点。
                    cachedSubWorldX = (float) pos.x();
                    cachedSubWorldY = (float) pos.y();
                    cachedSubWorldZ = (float) pos.z();
                }
            }
        } catch (Exception e) {
            io.github.y15173334444.create_schematic_compute.SchematicCompute.LOGGER.warn(
                "Sensor Sable world-pos compute failed at {}: {}", worldPosition, e.toString());
        }

        // Cache the block's FACING yaw for velocity rotation computations below.
        // 缓存方块的 FACING 偏航角，供下方速度旋转计算使用。
        if (getBlockState().hasProperty(SensorBlock.FACING))
            cachedBlockFacingYaw = getBlockState().getValue(SensorBlock.FACING).toYRot();
        hasSubPose = true;

        // ── 原始本地速度（世界→本地旋转，纯浮点运算无 GC）──
        // Transform raw local velocity from world space to structure-local space.
        // Uses only primitive math (no object allocations) to avoid GC pressure during physics ticks.
        // 将原始本地速度从世界坐标系转换到结构局部坐标系。
        // 仅使用原始数学运算（无对象分配），避免物理 tick 期间产生 GC 压力。

        // Read sub-world's latest linear velocity in world space.
        // 读取子世界在世界空间中的最新线速度。
        double wx = subLevel.latestLinearVelocity.x();
        double wy = subLevel.latestLinearVelocity.y();
        double wz = subLevel.latestLinearVelocity.z();

        // Pre-compute trig values for the rotation chain (yaw-pitch-roll in YXZ order).
        // 预计算旋转链的三角函数值（YXZ 顺序：偏航-俯仰-翻滚）。
        double cy = Math.cos(Math.toRadians(cachedSubYaw)), sy = Math.sin(Math.toRadians(cachedSubYaw));
        double cp = Math.cos(Math.toRadians(cachedSubPitch)), sp = Math.sin(Math.toRadians(cachedSubPitch));
        double cr = Math.cos(Math.toRadians(cachedSubRoll)), sr = Math.sin(Math.toRadians(cachedSubRoll));

        // R^T = Rz^T * Rx^T * Ry^T, apply to world vector.
        // The transpose-of-rotation-chain transforms world-frame vectors INTO the structure's
        // local frame, which is what we need for sensor axis-aligned velocity/acceleration.
        // 旋转链的转置将世界坐标系中的矢量变换到结构局部坐标系，
        // 这正是传感器轴对齐速度/加速度所需要的。

        // Step 1: inverse yaw (Rz^T / rotation around Y axis inverted)
        // 第一步：逆偏航（绕 Y 轴旋转的逆）
        double v1x = cy * wx - sy * wz;
        double v1y = wy;
        double v1z = sy * wx + cy * wz;

        // Step 2: inverse pitch (Rx^T / rotation around X axis inverted)
        // 第二步：逆俯仰（绕 X 轴旋转的逆）
        double v2x = v1x;
        double v2y = cp * v1y + sp * v1z;
        double v2z = -sp * v1y + cp * v1z;

        // Step 3: inverse roll (Ry^T / rotation around Z axis inverted)
        // 第三步：逆翻滚（绕 Z 轴旋转的逆）
        rawVelX = cr * v2x + sr * v2y;  // 结构前后 / structure forward-back
        rawVelY = -sr * v2x + cr * v2y; // 结构上下 / structure up-down
        rawVelZ = v2z;                   // 结构左右 / structure left-right

        // ── 从结构局部旋转到方块自身朝向（X=方块前后, Z=方块左右） ──
        // Rotate velocity from structure-local axes into block-local axes.
        // The block's FACING may differ from the structure's orientation,
        // so we apply an additional yaw rotation to align velocity axes with the block.
        // 将速度从结构局部坐标系旋转到方块自身朝向坐标系。
        // 方块的 FACING 可能与结构朝向不同，因此额外施加偏航旋转以对齐速度轴。
        double blockAngle = Math.toRadians(-cachedBlockFacingYaw);
        double cb = Math.cos(blockAngle), sb = Math.sin(blockAngle);
        double bvx = sb * rawVelX + cb * rawVelZ;
        double bvz = cb * rawVelX - sb * rawVelZ;
        rawVelX = bvx;
        rawVelZ = bvz;

        // 更新 attitude/forward（tick() 会调用 updateAttitude() 转到此方法）
        // Update attitude and forward vectors.
        // The parent tick() calls updateAttitude(), which is overridden below
        // to consume the cached sub-world pose rather than using reflection.
        updateAttitude();
    }

    // ─────────────────────────────────────────────────────────────
    //  Attitude update / 姿态更新
    // ─────────────────────────────────────────────────────────────

    /**
     * 覆盖父类的 updateAttitude()：
     * 使用 sable$physicsTick 中缓存的子世界姿态，不再用反射找子世界。
     *
     * Overrides the parent {@code updateAttitude()} to consume the sub-world
     * pose cached during {@code sable$physicsTick}, instead of searching for
     * the sub-level via reflection (which is slower and less reliable).
     * <p>
     * Computes:
     * <ul>
     *   <li>{@code attitudePitch / attitudeRoll} — the sensor body's pitch and roll
     *       as seen in world space (取自治传感器在世界空间中的俯仰和翻滚角).</li>
     *   <li>{@code forwardYaw / forwardPitch} — the direction the sensor is facing
     *       after applying sub-world rotation to the block's FACING direction
     *       (方块 FACING 方向经子世界旋转后的传感器指向).</li>
     * </ul>
     * </p>
     */
    @Override
    protected void updateAttitude() {
        if (!hasSubPose) {
            // 没有子世界姿态时调用父类方法（走反射/默认值）
            // No sub-world pose available yet — fall back to the parent's
            // reflection-based path (or defaults).
            super.updateAttitude();
            return;
        }

        // ── 姿态：子世界旋转的 pitch 和 roll ──
        // Attitude: pitch and roll from the sub-world's rotation.
        // The yaw is handled separately via the forward vector computation below.
        // 取自子世界旋转的俯仰和翻滚角。偏航角通过下方的 forward 矢量计算单独处理。
        attitudePitch = cachedSubPitch;
        attitudeRoll = cachedSubRoll;

        // ── 前方朝向：方块前方向量经子世界旋转 ──
        // Forward facing: rotate the block's canonical forward vector (0,0,1)
        // first by the block's FACING, then by the sub-world's full orientation.
        // 前方朝向：先将方块的标准前方向量 (0,0,1) 绕方块 FACING 旋转，
        // 再经子世界的完整朝向四元数旋转。
        org.joml.Vector3d worldFwd = new org.joml.Vector3d(0, 0, 1);
        worldFwd.rotateY(Math.toRadians(-cachedBlockFacingYaw));

        // Build the sub-world orientation quaternion in YXZ Euler order.
        // 按 YXZ 欧拉顺序构建子世界朝向四元数。
        org.joml.Quaterniond subQ = new org.joml.Quaterniond()
            .rotateY(Math.toRadians(cachedSubYaw))
            .rotateX(Math.toRadians(cachedSubPitch))
            .rotateZ(Math.toRadians(cachedSubRoll));
        subQ.transform(worldFwd);

        // Extract yaw (horizontal angle) and pitch (elevation angle) from the rotated vector.
        // atan2(x, z) gives the horizontal angle; negative sign matches Minecraft's convention.
        // 从旋转后的矢量中提取偏航（水平角）和俯仰（仰角）。
        // atan2(x, z) 给出水平角；负号匹配 Minecraft 的朝向约定。
        forwardYaw = (float)-Math.toDegrees(Math.atan2(worldFwd.x, worldFwd.z));
        forwardPitch = (float)Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, worldFwd.y / worldFwd.length()))));
        // Normalize to [-180, 180] range / 归一化到 [-180, 180] 范围
        while (forwardYaw > 180) forwardYaw -= 360;
        while (forwardYaw < -180) forwardYaw += 360;
    }

    // ─────────────────────────────────────────────────────────────
    //  Sable dependency interface / Sable 依赖接口
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns the sub-levels that must be loaded before this BE can tick.
     * In Sable, this determines which sub-worlds are loaded together with this BE.
     *
     * 返回当前 BE 开始 tick 前必须加载的子层级列表。
     * 在 Sable 中，这决定了哪些子世界与此 BE 一起加载。
     *
     * @return a singleton list containing the resolved sub-level, or an empty list
     *         包含已解析子层级的单元素列表，或空列表
     */
    @Override
    public Iterable<SubLevel> sable$getLoadingDependencies() { return resolveSubLevel(); }

    /**
     * Returns the sub-levels that must stay loaded while this BE is connected.
     * In Sable, this prevents the sub-world from being unloaded while the BE references it.
     *
     * 返回当前 BE 保持连接期间必须持续加载的子层级列表。
     * 在 Sable 中，这防止 BE 引用子世界时该子世界被卸载。
     *
     * @return a singleton list containing the resolved sub-level, or an empty list
     *         包含已解析子层级的单元素列表，或空列表
     */
    @Override
    public Iterable<SubLevel> sable$getConnectionDependencies() { return resolveSubLevel(); }

    // ─────────────────────────────────────────────────────────────
    //  Sub-level resolution / 子层级解析
    // ─────────────────────────────────────────────────────────────

    /**
     * Find the Sable SubLevel for this BE via shared chunk-based lookup (no block entity access).
     * <p>
     * Uses {@link SablePoseHelper#resolveSubLevel(Level, BlockPos)} which maps
     * {@code ChunkPos → Plot → SubLevel}, avoiding a full iteration over all sub-levels.
     * The result is cached in {@link #cachedSubLevel} so subsequent calls are zero-cost.
     * </p>
     * <p>
     * Safe to call during load/unload — avoids touching chunks or iterating all sub-levels.
     * </p>
     *
     * 通过基于 chunk 的共享查找定位当前 BE 所在的 Sable SubLevel（无需访问方块实体）。
     * <p>
     * 使用 {@link SablePoseHelper#resolveSubLevel(Level, BlockPos)} 方法，
     * 通过 {@code ChunkPos → Plot → SubLevel} 映射快速定位，避免遍历所有子层级。
     * 结果缓存在 {@link #cachedSubLevel} 中，后续调用零开销。
     * </p>
     * <p>
     * 可在加载/卸载期间安全调用——不接触 chunk 内部，也不遍历全部子层级。
     * </p>
     *
     * @return a singleton list containing the resolved sub-level, or an empty list if none found
     *         包含已解析子层级的单元素列表；未找到时返回空列表
     */
    private Iterable<SubLevel> resolveSubLevel() {
        // Return cached sub-level immediately if already resolved.
        // 如果已经解析过，直接返回缓存的子层级。
        if (cachedSubLevel != null) return java.util.List.of(cachedSubLevel);

        // Attempt chunk-based resolution. On success, cache the result.
        // 尝试基于 chunk 的解析。成功时缓存结果。
        cachedSubLevel = SablePoseHelper.resolveSubLevel(level, worldPosition);
        if (cachedSubLevel != null) return java.util.List.of(cachedSubLevel);

        // Not inside any Sable sub-level — return empty.
        // 不在任何 Sable 子层级中——返回空列表。
        return java.util.Collections.emptyList();
    }
}
