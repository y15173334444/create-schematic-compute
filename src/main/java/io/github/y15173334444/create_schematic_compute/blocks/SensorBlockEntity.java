package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Sensor Block Entity — 传感器方块实体
 * <p>
 * A block entity that acts as a sensor node in the schematic-compute graph.
 * It captures real-time physical state of the block: attitude (yaw/pitch/roll),
 * forward direction, acceleration, velocity, and sub-world spatial position.
 * This data is fed into the graph evaluator each tick so that schematic programs
 * can react to the block's motion and orientation in world space.
 * <p>
 * 作为 schematic-compute 计算图中的传感器节点，每个 tick 采集方块自身的实时物理状态：
 * 姿态角（偏航/俯仰/横滚）、朝向、加速度、速度以及子世界空间坐标，
 * 供 schematic 程序根据方块的位姿和运动做出响应。
 */
public class SensorBlockEntity extends SyncedGraphBlockEntity {

    // ── Attitude / 姿态角 ──
    /** Yaw angle (degrees) of the sensor's attitude in sub-world space. / 传感器在子世界空间中的姿态偏航角（度）。 */
    public float attitudeYaw = 0, attitudePitch = 0, attitudeRoll = 0;

    // ── Forward direction / 前向方向 ──
    /** Yaw angle (degrees) of the forward direction in sub-world space. / 传感器前向方向在子世界空间中的偏航角（度）。 */
    public float forwardYaw = 0, forwardPitch = 0;

    // ── Acceleration / 加速度 ──
    /** Acceleration (m/s² per tick) computed by finite-differencing velocity. / 通过速度差分计算出的加速度分量。 */
    public float accelX = 0, accelY = 0, accelZ = 0;

    // ── Raw velocity / 原始速度（由外部线程写入，volatile 保证可见性） ──
    /** Raw velocity (blocks/tick) — written externally (e.g. by physics thread), declared volatile for cross-thread visibility. / 原始速度（方块/tick），由外部（如物理线程）写入，volatile 保证跨线程可见性。 */
    public volatile double rawVelX, rawVelY, rawVelZ;

    // ── Sub-world spatial caching / 子世界空间坐标缓存 ──
    /**
     * Cached sub-world position of the sensor, used when the block resides inside
     * a sub-level (e.g. Sable ship). {@code Float.NaN} means "not in a sub-world,
     * use the real block position instead."
     * <p>
     * 传感器在子世界中的缓存坐标。当方块位于子关卡（如 Sable 舰船）内部时使用。
     * {@code Float.NaN} 表示不在子世界中，回退到真实方块位置。
     */
    public volatile float cachedSubWorldX = Float.NaN, cachedSubWorldY = Float.NaN, cachedSubWorldZ = Float.NaN;

    // ── Internal state for acceleration computation / 加速度计算所需的内部状态 ──
    /** Previous-frame raw velocity, used to compute acceleration via finite difference. / 上一帧的原始速度，用于差分计算加速度。 */
    private double prevRawVelX, prevRawVelY, prevRawVelZ;
    /** Flag: true on the first tick, so acceleration cannot yet be computed (need two samples). / 首次 tick 标记：加速度需要两个采样点，首帧无法计算。 */
    private boolean firstAccel = true;

    /**
     * Constructs a sensor block entity at the given position with the given block state.
     * <p>
     * 在指定位置以指定方块状态构造传感器方块实体。
     *
     * @param pos block position in the level / 方块在世界中的坐标
     * @param s   block state / 方块状态
     */
    public SensorBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.SENSOR_BE.get(), pos, s); }

    /**
     * Factory method that returns a sensor block entity, transparently providing
     * a Sable-compatible subclass when the Sable mod is loaded.
     * <p>
     * 工厂方法：当 Sable 模组已加载时，透明地返回 Sable 兼容子类实例。
     *
     * @param pos   block position / 方块坐标
     * @param state block state / 方块状态
     * @return a {@code SensorBlockEntity} (possibly a Sable-aware subclass) / 传感器方块实体（可能是 Sable 兼容子类）
     */
    public static SensorBlockEntity create(BlockPos pos, BlockState state) {
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
                Class<?> cls = Class.forName("io.github.y15173334444.create_schematic_compute.compat.SensorBlockEntitySable");
                return (SensorBlockEntity) cls.getConstructor(BlockPos.class, BlockState.class).newInstance(pos, state);
            }
        } catch (Exception ignored) {}
        return new SensorBlockEntity(pos, state);
    }

    /**
     * Copies graph state and runtime configuration from another sensor block entity.
     * Used when a sensor block is replaced or its data needs to be transferred.
     * <p>
     * 从另一个传感器方块实体复制计算图和运行时配置，用于方块替换或数据迁移场景。
     *
     * @param other the source block entity to copy from / 源方块实体
     */
    @Override public void accept(BlockEntity other) {
        if(other instanceof SensorBlockEntity src) {
            // 先注销旧的 bus 通道，避免残留引用导致数据泄漏
            // Unregister old bus channels first to avoid stale references.
            unregisterBusChannels(graph);
            this.graph = src.graph; this.running = src.running; runtimeState.clear(); setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * Updates the sensor's attitude (yaw/pitch/roll) and forward-direction angles
     * by composing the block's facing rotation with the sub-level's orientation quaternion.
     * <p>
     * Normalises pitch to (-90, 90] and roll/yaw to (-180, 180].
     * <p>
     * 将方块朝向旋转与子关卡朝向四元数合成，更新传感器的姿态角和前向方向角，
     * 并将俯仰角归一到 (-90, 90]，横滚角和偏航角归一到 (-180, 180]。
     */
    protected void updateAttitude() {
        // 方块朝向的基础偏航角（以度为单位的 Y 轴旋转角）
        // Base yaw from the block's FACING property (Y-axis rotation in degrees).
        float bYaw = 0;
        if (getBlockState().hasProperty(SensorBlock.FACING)) bYaw = getBlockState().getValue(SensorBlock.FACING).toYRot();

        // 获取子关卡的朝向四元数（如果没有子关卡，则使用单位四元数）
        // Get the sub-level's orientation quaternion. Defaults to identity if no sub-level exists.
        org.joml.Quaterniond subQ = new org.joml.Quaterniond();
        if (level != null && net.neoforged.fml.ModList.get().isLoaded("sable")) {
            var subQ2 = getSublevelOrientation(level, worldPosition);
            if (subQ2 != null) subQ = subQ2;
        }

        // 从四元数提取欧拉角：x=pitch, y=yaw, z=roll (YXZ 顺序)
        // Extract Euler angles from the quaternion: x=pitch, y=yaw, z=roll (YXZ order).
        var euler = new org.joml.Vector3d();
        subQ.getEulerAnglesYXZ(euler);
        attitudePitch = (float)Math.toDegrees(euler.x);
        attitudeRoll = (float)Math.toDegrees(euler.z);
        // 归一化俯仰角和横滚角到合理范围
        // Normalise pitch and roll to bounded ranges.
        while (attitudePitch > 90) attitudePitch -= 180; while (attitudePitch < -90) attitudePitch += 180;
        while (attitudeRoll > 180) attitudeRoll -= 360; while (attitudeRoll < -180) attitudeRoll += 360;

        // 计算前向方向向量：(0,0,1) 先绕 Y 轴旋转方块朝向角，再经子关卡四元数变换
        // Compute forward direction: start from (0,0,1), rotate by block yaw, then transform by sub-level quaternion.
        org.joml.Vector3d worldFwd = new org.joml.Vector3d(0, 0, 1);
        worldFwd.rotateY(Math.toRadians(-bYaw));
        subQ.transform(worldFwd);
        // 从方向向量反算偏航角和俯仰角
        // Derive yaw and pitch from the transformed direction vector.
        forwardYaw = (float)-Math.toDegrees(Math.atan2(worldFwd.x, worldFwd.z));
        forwardPitch = (float)Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, worldFwd.y / worldFwd.length()))));
        // 归一化前向偏航角
        // Normalise forward yaw.
        while (forwardYaw > 180) forwardYaw -= 360; while (forwardYaw < -180) forwardYaw += 360;
    }

    // ── Sable reflection — delegated to shared SableReflection helper ──
    // ── Sable 反射 — 委托给共享的 SableReflection 工具类 ──

    /**
     * Retrieves the orientation quaternion of the sub-level (e.g. Sable ship)
     * that contains this block entity, via reflection-based Sable API access.
     * <p>
     * Returns {@code null} if the block is not inside a sub-level, Sable is not
     * loaded, or any reflection step fails.
     * <p>
     * 通过反射访问 Sable API 获取包含本方块的子关卡（如 Sable 舰船）的朝向四元数。
     * 如果方块不在子关卡中、Sable 未加载或反射调用失败，返回 {@code null}。
     *
     * @param level         the world level / 世界关卡
     * @param worldPosition the block position of this sensor / 本传感器方块坐标
     * @return orientation quaternion, or {@code null} / 朝向四元数，失败时为 {@code null}
     */
    private static org.joml.Quaterniond getSublevelOrientation(net.minecraft.world.level.Level level,
                                                               net.minecraft.core.BlockPos worldPosition) {
        // Compile-time Sable access (dedicated-server safe — no Class.forName on
        // SubLevelContainer). SablePoseHelper.resolveSubLevel already performs the
        // membership check: it only returns a sub-level that contains worldPosition.
        // 编译期 Sable 访问（专用服务器安全——不对 SubLevelContainer 做 Class.forName）。
        // SablePoseHelper.resolveSubLevel 已做成员检查：只返回包含 worldPosition 的子关卡。
        try {
            return io.github.y15173334444.create_schematic_compute.compat.SablePoseHelper
                .getSubLevelOrientationQuaternion(level, worldPosition);
        } catch (Exception ignored) { return null; }
    }

    /**
     * Performs one tick of the sensor block entity.
     * <p>
     * Each tick the sensor:
     * <ol>
     *   <li>Ensures it is registered on the bus channel.</li>
     *   <li>Updates the LIT block state to reflect whether the graph is running.</li>
     *   <li>Recompiles the evaluator if the graph structure changed.</li>
     *   <li>Computes attitude and forward-direction angles.</li>
     *   <li>Computes acceleration from velocity via finite differencing.</li>
     *   <li>Resolves bus-channel conflicts.</li>
     *   <li>Constructs a {@link GraphEvaluator.SeatInputState} from all sensor data and evaluates the graph.</li>
     *   <li>Writes outputs, broadcasts snapshots, and syncs band changes.</li>
     * </ol>
     * <p>
     * 每个 tick 执行以下步骤：
     * <ol>
     *   <li>确保已在 bus 通道上注册。</li>
     *   <li>根据计算图是否运行更新 LIT 方块状态。</li>
     *   <li>若图结构变化则重新编译求值器。</li>
     *   <li>计算姿态角和前向方向角。</li>
     *   <li>通过速度差分计算加速度。</li>
     *   <li>解决 bus 通道冲突。</li>
     *   <li>从所有传感器数据构造 {@link GraphEvaluator.SeatInputState} 并执行图求值。</li>
     *   <li>写出结果、广播快照并同步 band 变化。</li>
     * </ol>
     */
    public void tick() {
        // 仅服务端执行；客户端不自行 tick
        // Server-side only; the client does not tick independently.
        if(level==null||level.isClientSide()) return;

        ensureBusRegistered();

        // 同步 LIT 状态：计算图运行且有节点时点亮
        // Sync LIT state: lit when the graph is running and has nodes.
        var state = getBlockState();
        if (!state.hasProperty(SensorBlock.LIT)) return;
        boolean lit = running && !graph.nodes.isEmpty();
        if(state.getValue(SensorBlock.LIT)!=lit) level.setBlock(worldPosition, state.setValue(SensorBlock.LIT, lit), 3);

        // 检测图结构变化，必要时重新编译求值器
        // Check for graph structural changes; recompile the evaluator if needed.
        rs.checkGraphChanged(graph);
        if(graphChanged()) recompileEvaluatorFull();

        // 若未运行则执行停止回调并返回
        // If the graph is not running, invoke the stop callback and bail out.
        if(!running) { onStopRunning(); return; }

        updateAttitude();

        // 加速度计算：使用有限差分法，每 tick 对速度做一阶差分
        // Acceleration via finite differencing: first-order difference of velocity per tick.
        // 第一帧无历史速度可差分，仅记录初值
        // First frame has no history to diff against; just record the initial value.
        if (firstAccel) { prevRawVelX = rawVelX; prevRawVelY = rawVelY; prevRawVelZ = rawVelZ; firstAccel = false; }
        else {
            // Δv / Δt，Δt = 0.05s (即 1 tick)
            // Δv / Δt, where Δt = 0.05 s (1 tick).
            accelX = (float)((rawVelX - prevRawVelX) / 0.05);
            accelY = (float)((rawVelY - prevRawVelY) / 0.05);
            accelZ = (float)((rawVelZ - prevRawVelZ) / 0.05);
            prevRawVelX = rawVelX; prevRawVelY = rawVelY; prevRawVelZ = rawVelZ;
        }

        rs.refreshInputs();

        // 检查并修复 bus 通道冲突：若存在冲突通道则强制全量同步
        // Resolve bus channel conflicts; if any exist, force a full sync.
        if (BusChannelHelper.recoverConflictedChannels(graph, worldPosition, level)) {
            needsFullSync = true; setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }

        // 构建图求值器的输入
        // Build inputs for the graph evaluator.
        var in = rs.buildInputs(graph);

        // 方块世界朝向偏航角
        // Block's world-space facing yaw.
        float blockYaw = getBlockState().hasProperty(SensorBlock.FACING)
            ? getBlockState().getValue(SensorBlock.FACING).toYRot() : 0;

        // 构造 SeatInputState：将方块的位姿、运动、子世界坐标打包传入求值器
        // Build the SeatInputState: pack pose, motion, and sub-world position for the evaluator.
        // 若缓存的子世界坐标为 NaN，则回退到方块物理位置（+0.5 取方块中心）
        // If cached sub-world coords are NaN, fall back to the block's physical position (+0.5 for block center).
        var si = new GraphEvaluator.SeatInputState(0,0,0,0,0, 0,0, 0,0,0,0,0,0,0,0, blockYaw,attitudeYaw,attitudePitch,attitudeRoll,forwardYaw,forwardPitch, accelX,accelY,accelZ, (float)rawVelX,(float)rawVelY,(float)rawVelZ,
            Float.isNaN(cachedSubWorldX) ? worldPosition.getX()+0.5f : cachedSubWorldX,
            Float.isNaN(cachedSubWorldY) ? worldPosition.getY()+0.5f : cachedSubWorldY,
            Float.isNaN(cachedSubWorldZ) ? worldPosition.getZ()+0.5f : cachedSubWorldZ);

        var results = evaluator.evaluate(in, runtimeState.pidState, 0.05f, si);
        rs.writeOutputs(results);

        // 广播 EvalSnapshot 给客户端（供 DEBUG_PROBE 采样）
        // Broadcast EvalSnapshot to clients (used by DEBUG_PROBE for sampling).
        broadcastEvalSnapshot();

        // 若 band 通道映射发生变化则同步到客户端
        // Sync band channel mappings to clients if they changed.
        BusChannelHelper.syncIfBandsChanged(graph, worldPosition, lastBusHashMap, level);
        setChanged();
    }
}
