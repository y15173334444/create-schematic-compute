package io.github.y15173334444.create_schematic_compute.compat;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.ControlSeatBlock;
import io.github.y15173334444.create_schematic_compute.blocks.ControlSeatBlockEntity;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sable (physics mod) compatibility layer for ControlSeatBlockEntity.
 * <p>
 * When the Sable physics mod is present, this subclass replaces the vanilla
 * {@link ControlSeatBlockEntity} to integrate with Sable's sub-level physics
 * system. It receives per-tick physics callbacks ({@link #sable$physicsTick}),
 * consumes player input, updates entity yaw, and caches the sub-world pose
 * for downstream consumers (attitude/forward nodes, world-position queries).
 * <p>
 * 当存在 Sable 物理模组时，此子类替代原版 {@link ControlSeatBlockEntity}，
 * 与 Sable 的子世界物理系统集成。它接收每物理帧回调（{@link #sable$physicsTick}），
 * 消费玩家输入、更新实体偏航角，并缓存子世界姿态供下游使用
 * （姿态/前方朝向节点、世界坐标查询）。
 *
 * <p>The rider entity reference is injected via
 * {@link ControlSeatBlock#setSeatEntity} to avoid full-world scans.
 * 骑手实体引用通过 {@link ControlSeatBlock#setSeatEntity} 注入，避免全范围搜索。
 *
 * @author y15173334444
 * @see ControlSeatBlockEntity
 * @see BlockEntitySubLevelActor
 */
public class ControlSeatBlockEntitySable extends ControlSeatBlockEntity implements BlockEntitySubLevelActor {

    /**
     * Saved reference to the containing world, used as a fallback when
     * {@code this.level} becomes null during sub-level transitions.
     * 保存对所在世界的引用，当子世界切换导致 {@code this.level} 变 null 时作为回退。
     */
    private Level savedLevel;

    /**
     * UUID of the player currently riding this seat.
     * Populated during physics tick by scanning the seat entity's passengers.
     * 当前骑乘此座位的玩家 UUID，在物理帧遍历座位实体乘客时填充。
     */
    private java.util.UUID riderUUID = null;

    /**
     * Cached sub-world euler angles (degrees), updated each physics tick
     * from the sub-level's current pose. These drive attitude/forward
     * node outputs in {@link #updateAttitude()}.
     * 缓存的子世界欧拉角（度），每物理帧从子世界当前姿态更新，
     * 驱动 {@link #updateAttitude()} 中的姿态/前方朝向节点输出。
     */
    private volatile float cachedSubYaw = 0, cachedSubPitch = 0, cachedSubRoll = 0;

    /**
     * Cached horizontal rotation of the block's FACING direction (degrees).
     * Read from the block state each physics tick.
     * 缓存方块 FACING 朝向的水平旋转角（度），每物理帧从方块状态读取。
     */
    private volatile float cachedBlockFacingYaw = 0;

    /**
     * 子世界初始 yaw（用于计算相对旋转，消除初始偏移）。
     * Sub-world initial yaw, recorded on the first physics tick.
     * Used as the reference point for computing relative rotation,
     * so that attitude outputs are zero when the sub-world hasn't rotated.
     */
    private volatile float initialSubYaw = Float.NaN;

    /**
     * Whether we have received at least one valid sub-world pose snapshot.
     * Guards {@link #updateAttitude()} against using stale/uninitialized data.
     * 是否已收到至少一次有效的子世界姿态快照，用于防止姿态计算使用未初始化数据。
     */
    private volatile boolean hasSubPose = false;

    // 原始本地速度由基类字段 rawVelX/Y/Z 存储，tick() 差分为加速度。
    // Raw local velocity is stored in superclass fields rawVelX/Y/Z;
    // tick() differentiates them into acceleration.

    /**
     * Cached sub-level instance, resolved lazily on first dependency query
     * and reused thereafter to avoid repeated spatial lookups.
     * 缓存的子世界实例，首次依赖查询时惰性解析，后续复用避免重复空间查找。
     */
    private volatile SubLevel cachedSubLevel;

    /**
     * Constructs the Sable-compatible block entity at the given position.
     * 在指定位置构造 Sable 兼容的方块实体。
     *
     * @param pos   block position in the world / 世界中的方块坐标
     * @param state block state for this position / 此位置的方块状态
     */
    public ControlSeatBlockEntitySable(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    /**
     * Called when the block entity is added to a world.
     * Captures the level reference for later fallback use.
     * 方块实体被加入世界时调用，捕获 level 引用供后续回退使用。
     */
    @Override
    public void onLoad() {
        super.onLoad();
        savedLevel = level;
        // 重置缓存的子关卡引用——reload 后子关卡可能重新注册，缓存可能指向已移除的旧实例。
        // 与 RadarBlockEntitySable/SensorBlockEntitySable 一致（回归审计 #8）。
        // Reset the cached sub-level ref — after a reload the sub-level may re-register
        // and the cache could point to a removed instance. Matches the sibling classes.
        cachedSubLevel = null;
    }

    /**
     * Sets the world reference and updates the saved fallback.
     * 设置世界引用并更新保存的回退引用。
     *
     * @param level the new world / 新的世界实例
     */
    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        savedLevel = level;
        // 重置缓存的子关卡引用（见 onLoad 注释，回归审计 #8）。
        // Reset the cached sub-level ref (see onLoad comment, regression audit #8).
        cachedSubLevel = null;
    }

    /**
     * Physics tick callback from the Sable engine.
     * <p>
     * This is the main integration point. Each call performs:
     * <ol>
     * <li>Cache the sub-world's current pose and world position</li>
     * <li>Transform world-space linear velocity into local-space raw velocity,
     *     then further rotate it into block-local coordinates</li>
     * <li>Detect the riding player via the seat entity's passenger list</li>
     * <li>Consume player input and update attitude/forward outputs</li>
     * <li>Update the seat entity's yaw so {@code getYRot()} reflects the
     *     correct world orientation</li>
     * </ol>
     *
     * Sable 引擎的物理帧回调。
     * <p>
     * 这是主要的集成入口，每次调用执行：
     * <ol>
     * <li>缓存子世界当前姿态和世界坐标</li>
     * <li>将世界空间线速度变换为本地空间原始速度，再旋转到方块本地坐标系</li>
     * <li>通过座位实体的乘客列表检测骑乘玩家</li>
     * <li>消费玩家输入并更新姿态/前方朝向输出</li>
     * <li>更新座位实体偏航角，使 {@code getYRot()} 反映正确的世界朝向</li>
     * </ol>
     *
     * @param subLevel  the sub-level this block entity belongs to / 此方块实体所属的子世界
     * @param handle    the rigid-body handle for physics interaction / 物理交互的刚体句柄
     * @param deltaTime time delta since last physics tick (seconds) / 自上次物理帧的时间增量（秒）
     */
    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle handle, double deltaTime) {
        if (this.level == null) this.level = savedLevel;
        if (this.level == null || this.level.isClientSide()) return;

        // ── 先缓存子世界 pose 和位置（无论是否有骑手）──
        // ── Cache sub-world pose and position first (regardless of rider presence) ──
        float[] pose = SablePoseHelper.getSubPose(subLevel);
        cachedSubYaw = pose[0];
        cachedSubPitch = pose[1];
        cachedSubRoll = pose[2];
        try {
            var logicalPose = subLevel.logicalPose();
            var pos = logicalPose.position();
            var orient = logicalPose.orientation();
            var rp = logicalPose.rotationPoint();
            if (pos != null) {
                // 计算方块自身世界坐标（而非子世界轴心坐标）。
                // Compute the block's own world position (not the sub-level's pivot),
                // by applying the sub-level's rotation around its pivot to the block's
                // offset from that pivot.
                double lx = worldPosition.getX() + 0.5;
                double ly = worldPosition.getY() + 0.5;
                double lz = worldPosition.getZ() + 0.5;
                if (rp != null) {
                    // 方块相对于旋转轴心的本地偏移，经子世界四元数旋转后加到轴心世界坐标上。
                    // Block-local offset from the rotation pivot, rotated by the sub-level's
                    // orientation quaternion, then added to the pivot's world position.
                    var localOffset = new org.joml.Vector3d(lx - rp.x(), ly - rp.y(), lz - rp.z());
                    var q = new org.joml.Quaterniond(orient.x(), orient.y(), orient.z(), orient.w());
                    q.transform(localOffset);
                    cachedSubWorldX = (float) (pos.x() + localOffset.x);
                    cachedSubWorldY = (float) (pos.y() + localOffset.y);
                    cachedSubWorldZ = (float) (pos.z() + localOffset.z);
                } else {
                    // 无旋转轴心时，直接使用子世界逻辑坐标作为世界位置。
                    // No rotation pivot — use the sub-level's logical position directly.
                    cachedSubWorldX = (float) pos.x();
                    cachedSubWorldY = (float) pos.y();
                    cachedSubWorldZ = (float) pos.z();
                }
            }
        } catch (Exception e) {
            SchematicCompute.LOGGER.warn("ControlSeat Sable world-pos compute failed at {}: {}",
                worldPosition, e.toString());
        }
        // 首次有效数据到达时记录初始 yaw，作为相对旋转的零参考点。
        // Record initial yaw on first valid data as the zero-reference for relative rotation.
        if (Float.isNaN(initialSubYaw)) initialSubYaw = cachedSubYaw;
        if (getBlockState().hasProperty(ControlSeatBlock.FACING))
            cachedBlockFacingYaw = getBlockState().getValue(ControlSeatBlock.FACING).toYRot();
        hasSubPose = true;

        // ── 原始本地速度（世界→本地旋转，纯浮点运算无 GC）──
        // ── Raw local velocity (world→local rotation, pure float math, no allocations) ──
        double wx = subLevel.latestLinearVelocity.x();
        double wy = subLevel.latestLinearVelocity.y();
        double wz = subLevel.latestLinearVelocity.z();
        // Precompute trig values once to avoid repeated Math.sin/cos calls.
        // 预计算三角函数值，避免重复调用 Math.sin/cos。
        double cy = Math.cos(Math.toRadians(cachedSubYaw)), sy = Math.sin(Math.toRadians(cachedSubYaw));
        double cp = Math.cos(Math.toRadians(cachedSubPitch)), sp = Math.sin(Math.toRadians(cachedSubPitch));
        double cr = Math.cos(Math.toRadians(cachedSubRoll)), sr = Math.sin(Math.toRadians(cachedSubRoll));
        // R^T = Rz^T * Rx^T * Ry^T, apply to world vector.
        // Apply inverse rotation: rotate world velocity into sub-level local space.
        // 应用逆旋转：将世界速度旋转到子世界本地空间。
        // Step 1: undo yaw (Y-axis rotation) / 第一步：撤销偏航角（绕Y轴旋转）
        double v1x = cy * wx - sy * wz;
        double v1y = wy;
        double v1z = sy * wx + cy * wz;
        // Step 2: undo pitch (X-axis rotation) / 第二步：撤销俯仰角（绕X轴旋转）
        double v2x = v1x;
        double v2y = cp * v1y + sp * v1z;
        double v2z = -sp * v1y + cp * v1z;
        // Step 3: undo roll (Z-axis rotation) / 第三步：撤销滚转角（绕Z轴旋转）
        rawVelX = cr * v2x + sr * v2y;  // 结构前后 / structure front-back
        rawVelY = -sr * v2x + cr * v2y; // 结构上下 / structure up-down
        rawVelZ = v2z;                   // 结构左右 / structure left-right

        // 从结构局部旋转到方块自身朝向（X=方块前后, Z=方块左右）。
        // Rotate from structure-local space into block-local space,
        // where X = block forward/back, Z = block left/right.
        double blockAngle = Math.toRadians(-cachedBlockFacingYaw);
        double cb = Math.cos(blockAngle), sb = Math.sin(blockAngle);
        double bvx = sb * rawVelX + cb * rawVelZ;
        double bvz = cb * rawVelX - sb * rawVelZ;
        rawVelX = bvx;
        rawVelZ = bvz;

        // ── 检测骑手（setSeatEntity 在 useWithoutItem 中调用）──
        // ── Detect rider (setSeatEntity is called in useWithoutItem) ──
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
            // 即使无骑手也要更新姿态（让 ATTITUDE/FORWARD 节点持续输出）。
            // Update attitude even without a rider so ATTITUDE/FORWARD nodes
            // continue to produce output.
            updateAttitude();
            return;
        }

        // 消费输入 — 将寄存器中的按键/摇杆值传递给输入处理管线。
        // Consume input — pass registered key/joystick values to the input pipeline.
        if (riderUUID != null) consumeInputByPlayer(riderUUID);

        // 更新姿态/前方朝向（tick() 中会调用 updateAttitude()）。
        // Update attitude/forward outputs (tick() calls updateAttitude() elsewhere).
        updateAttitude();

        // 更新实体 yaw（当前值+插值缓存），使 getYRot() 返回正确的世界朝向。
        // Update entity yaw (current + interpolation cache) so getYRot() returns correct world yaw.
        if (entity != null) {
            // 相对 yaw：子世界旋转量减去初始参考值，得到增量。
            // Relative yaw: sub-world rotation minus initial reference = net change.
            float relativeYaw = cachedSubYaw - initialSubYaw;
            // 方块朝向减去子世界相对旋转 = 实体在世界中的实际朝向。
            // Block facing minus sub-world relative rotation = entity's actual world heading.
            float newYaw = cachedBlockFacingYaw - relativeYaw;
            entity.setYRot(newYaw);      // current yaw / 当前偏航
            entity.yRotO = newYaw;       // previous yaw (for interpolation) / 上一帧偏航（插值用）
            entity.setYHeadRot(newYaw);
        }
    }

    /**
     * Overrides view-angle adjustment from the base class.
     * <p>
     * The client sends the delta between player yaw and vehicle yaw, so no
     * additional adjustment is applied here — the physics tick already
     * handles orientation updates directly.
     * 覆盖基类的视角调整。客户端发送的是 playerYaw - vehicleYaw 差值，
     * 因此此处无需额外调整 —— 物理帧已直接处理朝向更新。
     */
    @Override
    protected void adjustViewAngle() {
        // 客户端发送的是 playerYaw - vehicleYaw 差值，无需额外处理。
        // The client sends the delta between player yaw and vehicle yaw;
        // no additional adjustment is needed here.
    }

    /**
     * Saves type-specific data to NBT, including the initial sub-world yaw.
     * 将类型特定数据（包括初始子世界偏航角）保存到 NBT。
     *
     * @param tag        the NBT compound to write into / 要写入的 NBT 复合标签
     * @param registries holder lookup provider for data-fixers / Holder 查找提供器
     */
    @Override
    protected void saveTypeSpecific(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveTypeSpecific(tag, registries);
        if (!Float.isNaN(initialSubYaw)) tag.putFloat("initialSubYaw", initialSubYaw);
    }

    /**
     * Loads type-specific data from NBT, restoring the initial sub-world yaw.
     * 从 NBT 加载类型特定数据，恢复初始子世界偏航角。
     *
     * @param tag        the NBT compound to read from / 要读取的 NBT 复合标签
     * @param registries holder lookup provider for data-fixers / Holder 查找提供器
     */
    @Override
    protected void loadTypeSpecific(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadTypeSpecific(tag, registries);
        if (tag.contains("initialSubYaw")) initialSubYaw = tag.getFloat("initialSubYaw");
    }

    /**
     * 从缓存的子世界姿态计算 attitude / forward，供 tick() 使用。
     * Computes attitude and forward outputs from the cached sub-world pose,
     * for use by {@code tick()} and downstream node consumers.
     * <p>
     * <b>Attitude</b> (yaw/pitch/roll): the sub-world's own orientation
     * relative to its initial state, with yaw negated to match Minecraft's
     * clockwise-positive convention.
     * <p>
     * <b>Forward</b>: the direction the seat faces in world space — block
     * facing rotated by the sub-world's relative yaw, with sub-world pitch
     * applied directly.
     * <p>
     * <b>姿态</b>（偏航/俯仰/滚转）：子世界自身相对初始状态的朝向，
     * 偏航角取反以匹配 Minecraft 顺时针为正的约定。
     * <p>
     * <b>前方朝向</b>：座位在世界空间中的朝向 —— 方块朝向经子世界相对偏航旋转，
     * 俯仰角直接使用子世界俯仰。
     */
    @Override
    protected void updateAttitude() {
        // 方块世界朝向作为基准。
        // Block world-facing direction serves as the baseline.
        blockYaw = cachedBlockFacingYaw;
        if (!hasSubPose) {
            // 尚未收到子世界姿态数据，回退到基类默认计算。
            // No sub-world pose data yet; fall back to base-class default computation.
            super.updateAttitude();
            return;
        }

        // ── 姿态（yaw 用相对旋转并取反以匹配 Minecraft 顺时针为正的约定）──
        // ── Attitude: yaw is relative rotation negated to match Minecraft's
        //     clockwise-positive convention ──
        float relativeYaw = cachedSubYaw - initialSubYaw;
        attitudeYaw = -relativeYaw;
        attitudePitch = cachedSubPitch;
        attitudeRoll = cachedSubRoll;

        // ── 前方朝向：方块朝向经子世界相对旋转 ──
        // ── Forward: block facing rotated by sub-world relative yaw ──
        forwardYaw = cachedBlockFacingYaw - relativeYaw;
        forwardPitch = cachedSubPitch; // 直接用子世界俯仰 / use sub-world pitch directly
        // 将偏航角归一化到 [-180, 180] 范围，避免角度溢出。
        // Normalize yaw to [-180, 180] to prevent angle overflow.
        while (forwardYaw > 180) forwardYaw -= 360;
        while (forwardYaw < -180) forwardYaw += 360;
    }

    // ── BlockEntitySubLevelActor implementation / 子世界角色接口实现 ──

    /**
     * Returns the sub-levels that must be loaded before this block entity
     * can tick. Delegates to {@link #resolveSubLevel()} for lazy resolution.
     * 返回此方块实体 tick 前必须加载的子世界，
     * 委托给 {@link #resolveSubLevel()} 进行惰性解析。
     *
     * @return an iterable of dependency sub-levels / 依赖子世界的可迭代集合
     */
    @Override
    public Iterable<SubLevel> sable$getLoadingDependencies() { return resolveSubLevel(); }

    /**
     * Returns the sub-levels this block entity maintains a connection to.
     * Delegates to {@link #resolveSubLevel()} for lazy resolution.
     * 返回此方块实体保持连接的子世界，
     * 委托给 {@link #resolveSubLevel()} 进行惰性解析。
     *
     * @return an iterable of connection sub-levels / 连接子世界的可迭代集合
     */
    @Override
    public Iterable<SubLevel> sable$getConnectionDependencies() { return resolveSubLevel(); }

    /**
     * Resolves the sub-level containing this block entity, with caching.
     * <p>
     * The sub-level is resolved once by spatial lookup at the block's
     * world position, then cached for all subsequent calls. This avoids
     * repeated spatial queries during dependency resolution and physics ticks.
     * 解析包含此方块实体的子世界，带缓存。
     * <p>
     * 子世界在方块所在世界坐标通过空间查找解析一次，后续调用直接返回缓存值，
     * 避免在依赖解析和物理帧期间重复空间查询。
     *
     * @return singleton list of the resolved sub-level, or empty list if not found
     *         已解析子世界的单元素列表，未找到则返回空列表
     */
    private Iterable<SubLevel> resolveSubLevel() {
        if (cachedSubLevel != null) return java.util.List.of(cachedSubLevel);
        cachedSubLevel = SablePoseHelper.resolveSubLevel(level, worldPosition);
        if (cachedSubLevel != null) return java.util.List.of(cachedSubLevel);
        return java.util.Collections.emptyList();
    }
}
