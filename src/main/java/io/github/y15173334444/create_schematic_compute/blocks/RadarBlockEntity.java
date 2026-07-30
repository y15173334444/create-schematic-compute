package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.*;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import io.github.y15173334444.create_schematic_compute.radar.TargetAssignment;
import io.github.y15173334444.create_schematic_compute.radar.TargetRecord;
import com.simibubi.create.foundation.blockEntity.IMergeableBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.util.*;

/**
 * Server-side radar block entity that scans for entities and Sable structures,
 * assigns detected targets to graph-based TARGET_OUT nodes, and evaluates the
 * node graph to produce computed output signals. Supports multi/single-target
 * scan modes, manual target locking, display offset/scale configuration, and
 * coordinate-space transformations when placed on a Sable sub-world structure.
 * Also syncs scan results to clients for the holographic blip renderer.
 *
 * 服务端雷达方块实体，扫描实体和 Sable 结构，将检测到的目标分配给基于图的
 * TARGET_OUT 节点，并评估节点图产生计算后的输出信号。支持多/单目标扫描模式、
 * 手动目标锁定、显示偏移/缩放配置，以及放置在 Sable 子世界结构上时的坐标空间变换。
 * 同时将扫描结果同步到客户端供全息 blip 渲染器使用。
 */
public class RadarBlockEntity extends SyncedGraphBlockEntity {

    // Scan settings / 扫描设置
    /** Scan range in blocks. 扫描范围（格）。 */
    public int scanRange = 32;
    /** Scan mode: 0 = multi-target, 1 = single target. 扫描模式：0=多目标, 1=单目标。 */
    public int scanMode = 0;
    /** Tri-axis display size in blocks. 三轴显示大小（格）。 */
    public int displayScale = 4;
    /** Whether to scan and display players. 是否扫描并显示玩家。 */
    public boolean showPlayers = true;
    /** Whether to scan and display mobs. 是否扫描并显示生物。 */
    public boolean showMobs = true;
    /** Whether to scan and display Sable sub-world structures. 是否扫描并显示 Sable 子世界结构。 */
    public boolean showSable = true;
    /** Lock mode: 0 = automatic assignment, 1 = manual locking. 锁定模式：0=自动分配, 1=手动锁定。 */
    public int lockMode = 0;
    /** XYZ display offset in blocks, shifts the holographic blip display relative to the radar block. XYZ 显示偏移（格），将全息 blip 显示相对于雷达方块偏移。 */
    public float displayX = 0, displayY = 0, displayZ = 0;
    /** When true, the host Sable structure (the one this radar sits on) is excluded from scanning and locking. 为 true 时不扫描/锁定所在 Sable 结构。 */
    public boolean excludeHost = true;
    /** Display style: 0 = classic XYZ axes, 1 = holographic plane. 显示风格：0=经典XYZ轴, 1=全息平面。 */
    public int displayStyle = 0;
    /** Minimum lock distance in meters — targets closer than this distance are not locked, preventing self-lock. 最近锁定距离（米），距离小于此值的目标不被锁定，防止自锁。 */
    public float lockDistance = 0f;
    /** Manually locked target entity IDs, preserved across ticks as long as the target stays in scan range. 手动锁定的目标 entityId，只要目标保持在扫描范围内则跨 tick 保持。 */
    public final java.util.LinkedHashSet<Integer> lockedTargets = new java.util.LinkedHashSet<>();
    /** Currently assigned target entity IDs in auto mode — read by the client-side renderer for highlighting active targets. 自动模式下当前分配的目标 entityId（渲染器用于高亮显示）。 */
    public final java.util.Set<Integer> activeTargets = new java.util.HashSet<>();

    // Sable sub-world position cache — NaN means "not on a Sable structure, use worldPosition instead".
    // Sable 子世界位置缓存 — NaN 表示"不在 Sable 上，使用 worldPosition"。
    /** Cached world-space X position of this radar within the Sable sub-world. 缓存的 Sable 子世界中此雷达的世界坐标 X。 */
    public volatile float cachedSubWorldX = Float.NaN;
    /** Cached world-space Y position of this radar within the Sable sub-world. 缓存的 Sable 子世界中此雷达的世界坐标 Y。 */
    public volatile float cachedSubWorldY = Float.NaN;
    /** Cached world-space Z position of this radar within the Sable sub-world. 缓存的 Sable 子世界中此雷达的世界坐标 Z。 */
    public volatile float cachedSubWorldZ = Float.NaN;
    /** Cached yaw angle (degrees) of the Sable sub-world orientation. 缓存的 Sable 子世界朝向 Yaw 角（度）。 */
    public volatile float cachedSubYaw = Float.NaN;
    /** Cached pitch angle (degrees) of the Sable sub-world orientation. 缓存的 Sable 子世界朝向 Pitch 角（度）。 */
    public volatile float cachedSubPitch = Float.NaN;
    /** Cached roll angle (degrees) of the Sable sub-world orientation. 缓存的 Sable 子世界朝向 Roll 角（度）。 */
    public volatile float cachedSubRoll = Float.NaN;
    /** World-space origin coordinates of the host Sable structure — used for isHost comparison to exclude the radar's own structure. 所在 Sable 结构的子世界原点世界坐标（用于 isHost 比较以排除雷达自身所在结构）。 */
    public volatile float cachedSubOriginX = Float.NaN;
    public volatile float cachedSubOriginY = Float.NaN;
    public volatile float cachedSubOriginZ = Float.NaN;
    /** Sable structure orientation quaternion components — provides exact inverse rotation to avoid Euler angle precision loss during coordinate transforms. Sable 结构朝向四元数分量（精确逆旋转，避免 Euler 角精度丢失）。 */
    public volatile float cachedSubQx = Float.NaN;
    public volatile float cachedSubQy = Float.NaN;
    public volatile float cachedSubQz = Float.NaN;
    public volatile float cachedSubQw = Float.NaN;

    /** Scan result cache — populated each tick on the server and read by the client-side blip renderer. 扫描结果缓存（服务端每 tick 填充，渲染器读取）。 */
    public final List<TargetRecord> targets = new ArrayList<>();

    /** Client-side registry of all loaded radar block entities, used by RadarLockHandler to iterate radars for crosshair-lock UI. 客户端侧所有已加载雷达的注册表，用于 RadarLockHandler 遍历以支持准星锁定 UI。 */
    private static final java.util.Set<RadarBlockEntity> CLIENT_RADARS = new java.util.HashSet<>();
    /** Returns an unmodifiable view of all client-side radar instances. 返回所有客户端雷达实例的只读视图。 */
    public static java.util.Collection<RadarBlockEntity> getClientRadars() { return CLIENT_RADARS; }

    /**
     * Factory method that creates a Sable-compatible RadarBlockEntity if the Sable mod
     * is loaded, otherwise falls back to a plain RadarBlockEntity. Uses reflection to
     * avoid compile-time dependency on Sable, allowing the mod to work with or without it.
     *
     * 工厂方法：若 Sable mod 已加载则通过反射创建 Sable 兼容的 RadarBlockEntity 子类实例，
     * 否则回退到普通实例。使用反射避免对 Sable 的编译期依赖，使 mod 可在有/无 Sable 环境下运行。
     *
     * @param pos   block position in the world 方块在世界中的位置
     * @param state block state 方块状态
     * @return a RadarBlockEntity instance, potentially with Sable integration 雷达方块实体实例，可能集成 Sable
     */
    public static RadarBlockEntity create(BlockPos pos, BlockState state) {
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
                Class<?> cls = Class.forName("io.github.y15173334444.create_schematic_compute.compat.RadarBlockEntitySable");
                RadarBlockEntity be = (RadarBlockEntity) cls.getConstructor(BlockPos.class, BlockState.class).newInstance(pos, state);
                SchematicCompute.LOGGER.info("Radar: created Sable-compatible instance for {}", pos);
                return be;
            }
        } catch (Exception e) {
            SchematicCompute.LOGGER.warn("Radar: Sable factory failed for {}: {}", pos, e.toString());
        }
        SchematicCompute.LOGGER.info("Radar: created plain instance for {}", pos);
        return new RadarBlockEntity(pos, state);
    }

    /**
     * Constructs a plain RadarBlockEntity with the given position and block state.
     *
     * 使用给定的位置和方块状态构造普通雷达方块实体。
     *
     * @param pos block position 方块位置
     * @param s   block state 方块状态
     */
    public RadarBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.RADAR_BE.get(), pos, s); }

    /**
     * Merges another RadarBlockEntity into this one, as required by {@link IMergeableBE}.
     * Copies the graph, runtime flags, scan settings, and display configuration from the
     * source. Unregisters old bus channels before adopting the new graph to avoid stale
     * channel registrations.
     *
     * 将另一个雷达方块实体合并到当前实体（{@link IMergeableBE} 接口要求）。
     * 从源实体复制图、运行标志、扫描设置和显示配置。
     * 在采用新图之前先注销旧的 bus 通道，以避免过期的通道注册。
     *
     * @param other the source block entity to merge from 要合并的源方块实体
     */
    @Override public void accept(BlockEntity other) {
        if (other instanceof RadarBlockEntity src) {
            unregisterBusChannels(graph);
            this.graph = src.graph; this.running = src.running; runtimeState.clear();
            this.scanRange = src.scanRange; this.scanMode = src.scanMode;
            this.showPlayers = src.showPlayers; this.showMobs = src.showMobs; this.showSable = src.showSable;
            this.excludeHost = src.excludeHost;
            this.displayStyle = src.displayStyle;
            this.lockDistance = src.lockDistance;
            this.displayX = src.displayX; this.displayY = src.displayY; this.displayZ = src.displayZ;
            setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * Called when the block entity is loaded into the world.
     * On the client side, registers this radar in {@link #CLIENT_RADARS} so that
     * {@code RadarLockHandler} can iterate all radars for the crosshair-lock UI.
     * On the server side, clears any stale Sable cache carried over from NBT so
     * that {@code sable$physicsTick} or {@link #tryBootstrapSableCache()} can
     * re-initialize it with fresh data.
     *
     * 方块实体加载到世界时调用。
     * 客户端侧：将此雷达注册到 {@link #CLIENT_RADARS}，以便 {@code RadarLockHandler}
     * 能够遍历所有雷达以支持准星锁定 UI。
     * 服务端侧：清除 NBT 带来的过期 Sable 缓存，让 {@code sable$physicsTick} 或
     * {@link #tryBootstrapSableCache()} 用新鲜数据重新初始化。
     */
    @Override public void onLoad() { super.onLoad(); if (level != null && level.isClientSide()) CLIENT_RADARS.add(this);
        else clearCachedSubPose(); // Server-side: clear stale Sable cache. 服务端：清除 NBT 带来的旧 Sable 缓存，让 bootstrap 重新初始化
    }

    /**
     * Called when the block entity is removed from the world.
     * Cleans up all associated state: removes this radar from the client-side registry,
     * clears the {@link TargetAssignment} map for this position to release assigned targets,
     * then delegates to the superclass.
     *
     * 方块实体从世界移除时调用。
     * 清理所有关联状态：从客户端注册表移除此雷达，清除此位置的
     * {@link TargetAssignment} 映射以释放已分配的目标，然后委托给父类。
     */
    @Override public void setRemoved() { CLIENT_RADARS.remove(this); TargetAssignment.clear(worldPosition); super.setRemoved(); }

    /**
     * Checks whether an entity position falls within the scan bounding box.
     * Both the entity coordinates and the scan box must already be in the same
     * coordinate space (either both in world space or both in sub-world space).
     *
     * 检查实体位置是否在扫描包围盒内。
     * 实体坐标与扫描盒必须已处于同一坐标空间（同在世界空间或同在子世界空间）。
     *
     * @param ex      entity X coordinate 实体 X 坐标
     * @param ey      entity Y coordinate 实体 Y 坐标
     * @param ez      entity Z coordinate 实体 Z 坐标
     * @param scanBox the scan bounding box 扫描包围盒
     * @return {@code true} if the entity is within scan range 实体在扫描范围内返回 {@code true}
     */
    private static boolean inScanRange(double ex, double ey, double ez,
                                        AABB scanBox) {
        return scanBox.contains(ex, ey, ez);
    }

    /**
     * Resets all cached Sable sub-world position, orientation, and origin values to
     * {@link Float#NaN}. Throughout the codebase, NaN serves as the sentinel value
     * meaning "not on a Sable structure" — when any of these values is NaN, the
     * radar falls back to using its {@code worldPosition} (BlockPos) directly.
     *
     * 将所有缓存的 Sable 子世界位置、朝向和原点值重置为 {@link Float#NaN}。
     * 在整个代码库中，NaN 作为哨兵值表示"不在 Sable 结构上"——当这些值中任何一个
     * 为 NaN 时，雷达回退到直接使用其 {@code worldPosition}（BlockPos）。
     */
    private void clearCachedSubPose() {
        cachedSubWorldX = Float.NaN;
        cachedSubWorldY = Float.NaN;
        cachedSubWorldZ = Float.NaN;
        cachedSubOriginX = Float.NaN;
        cachedSubOriginY = Float.NaN;
        cachedSubOriginZ = Float.NaN;
        cachedSubYaw = Float.NaN;
        cachedSubPitch = Float.NaN;
        cachedSubRoll = Float.NaN;
        cachedSubQx = Float.NaN;
        cachedSubQy = Float.NaN;
        cachedSubQz = Float.NaN;
        cachedSubQw = Float.NaN;
    }

    /**
     * One-time fallback to populate the Sable cache when {@code sable$physicsTick}
     * has never been called for this radar. This can happen when the radar is loaded
     * from NBT on a dedicated server before the Sable physics tick fires, or when
     * Sable integration is started after the radar was already placed.
     * <p>
     * Iterates all Sable sub-levels to find the one whose global bounding box contains
     * this radar's position, then extracts world-space coordinates and orientation
     * (Euler angles derived from quaternion) from the sub-level's {@code logicalPose}.
     * <p>
     * Does NOT overwrite the cache if {@code sable$physicsTick} has already set it
     * (checked via {@code cachedSubYaw} not being NaN), because the physics tick
     * provides a more precise per-tick position update.
     *
     * 仅在 {@code sable$physicsTick} 从未被调用时兜底填充初始 Sable 缓存。
     * 可能发生在：专用服务器上从 NBT 加载雷达后、Sable 物理 tick 触发之前；
     * 或雷达已放置后才启动 Sable 集成时。
     * <p>
     * 遍历所有 Sable 子世界，查找全局包围盒包含此雷达位置的子世界，
     * 然后从子世界的 {@code logicalPose} 提取世界空间坐标和朝向（从四元数推导 Euler 角）。
     * <p>
     * {@code sable$physicsTick} 每 tick 更新精确世界位置，bootstrap 不应覆盖它。
     * 通过检查 {@code cachedSubYaw} 非 NaN 来判断是否已被接管。
     */
    private void tryBootstrapSableCache() {
        if (!Float.isNaN(cachedSubYaw)) return; // sable$physicsTick has taken over, no bootstrap needed. sable$physicsTick 已接管，无需 bootstrap
        try {
            initSableReflection();
            if (sableLogicalPoseMethod == null || sableGetContainerMethod == null) return;
            var scanLevel = getScanLevel();
            if (scanLevel == null) return;
            var cnt = sableGetContainerMethod.invoke(null, scanLevel);
            if (cnt == null) return;
            var all = (List<?>) sableGetAllSubLevelsMethod.invoke(cnt);
            if (all == null || all.isEmpty()) return;
            // Find the sub-level whose global bounding box contains this radar.
            // 查找全局包围盒包含此雷达的子世界。
            double rx = worldPosition.getX() + 0.5, ry = worldPosition.getY() + 0.5, rz = worldPosition.getZ() + 0.5;
            Object found = null;
            for (var s : all) {
                var bb = s.getClass().getMethod("boundingBox").invoke(s);
                if (bb == null) continue;
                double mnx = (double) bb.getClass().getMethod("minX").invoke(bb);
                double mxx = (double) bb.getClass().getMethod("maxX").invoke(bb);
                double mny = (double) bb.getClass().getMethod("minY").invoke(bb);
                double mxy = (double) bb.getClass().getMethod("maxY").invoke(bb);
                double mnz = (double) bb.getClass().getMethod("minZ").invoke(bb);
                double mxz = (double) bb.getClass().getMethod("maxZ").invoke(bb);
                if (rx >= mnx && rx <= mxx && ry >= mny && ry <= mxy && rz >= mnz && rz <= mxz) {
                    found = s; break;
                }
            }
            if (found == null) return;
            var bestPose = sableLogicalPoseMethod.invoke(found);
            var pm2 = bestPose.getClass().getMethod("position");
            var pos2 = pm2.invoke(bestPose);
            double px = (double) pos2.getClass().getMethod("x").invoke(pos2);
            double py = (double) pos2.getClass().getMethod("y").invoke(pos2);
            double pz = (double) pos2.getClass().getMethod("z").invoke(pos2);
            // Cache sub-world origin in world space — used later for isHost comparison.
            // 缓存子世界原点世界坐标 — 后续用于 isHost 比较。
            cachedSubOriginX = (float) px;
            cachedSubOriginY = (float) py;
            cachedSubOriginZ = (float) pz;
            // Initial world position equals origin before sable$physicsTick refines it.
            // 初始世界位置等于原点，sable$physicsTick 后续会细化。
            cachedSubWorldX = (float) px;
            cachedSubWorldY = (float) py;
            cachedSubWorldZ = (float) pz;
            try {
                var om = bestPose.getClass().getMethod("orientation");
                var oq = om.invoke(bestPose);
                if (oq != null) {
                    double ox = (double) oq.getClass().getMethod("x").invoke(oq);
                    double oy = (double) oq.getClass().getMethod("y").invoke(oq);
                    double oz = (double) oq.getClass().getMethod("z").invoke(oq);
                    double ow = (double) oq.getClass().getMethod("w").invoke(oq);
                    cachedSubQx = (float) ox; cachedSubQy = (float) oy;
                    cachedSubQz = (float) oz; cachedSubQw = (float) ow;
                    // Derive Euler angles from quaternion for display and simple rotations.
                    // 从四元数推导 Euler 角，用于显示和简单旋转。
                    var q = new org.joml.Quaterniond(ox, oy, oz, ow);
                    var euler = new org.joml.Vector3d();
                    q.getEulerAnglesYXZ(euler);
                    cachedSubYaw   = (float) Math.toDegrees(euler.y);
                    cachedSubPitch = (float) Math.toDegrees(euler.x);
                    cachedSubRoll  = (float) Math.toDegrees(euler.z);
                }
            } catch (Exception e) {
                // Orientation extraction failed — assume zero rotation as safe default.
                // 朝向提取失败 — 默认零旋转作为安全回退。
                cachedSubYaw = 0; cachedSubPitch = 0; cachedSubRoll = 0;
            }
        } catch (Exception e) {
            SchematicCompute.LOGGER.warn("Radar Sable bootstrap failed: {}", e.toString());
        }
    }

    /**
     * Returns a valid {@link Level} reference for this block entity.
     * Overridden by the Sable compatibility subclass to handle the case where
     * {@code level} is {@code null} on Sable structures (because the block entity
     * exists in a sub-world context that does not directly set the vanilla field).
     *
     * 返回此方块实体的有效 {@link Level} 引用。
     * 由 Sable 兼容子类重写，以处理 Sable 结构上 {@code level} 为 {@code null}
     * 的情况（因为方块实体存在于子世界上下文中，不直接设置原版字段）。
     *
     * @return the effective Level, never null in normal operation 有效的 Level，正常运行时不应为 null
     */
    protected Level getEffectiveLevel() { return level; }

    /**
     * Returns the {@link Level} that should be used for entity scanning.
     * <p>
     * When on a Sable structure (detected via {@code cachedSubYaw} not being NaN),
     * entities exist in the overworld even though the radar's block is in a sub-world.
     * Therefore we must scan the server's {@code overworld()} rather than the radar's
     * own level. Otherwise, scan the radar's local level.
     *
     * 返回应用于实体扫描的 {@link Level}。
     * <p>
     * 当在 Sable 结构上时（通过 {@code cachedSubYaw} 非 NaN 检测），
     * 实体存在于主世界而非雷达所在子世界，因此必须扫描服务器的 {@code overworld()}
     * 而非雷达自身的 level。否则扫描雷达本地 level。
     *
     * @return the Level to query for entities 用于查询实体的 Level
     */
    protected Level getScanLevel() {
        if (!Float.isNaN(cachedSubYaw)) {
            Level effective = level;
            if (effective == null) effective = getEffectiveLevel();
            if (effective != null) {
                var srv = effective.getServer();
                if (srv != null) return srv.overworld();
                return effective;
            }
        }
        return level != null ? level : getEffectiveLevel();
    }

    /**
     * Main server-side tick method — called every game tick (20 times per second).
     * <p>
     * Execution order:
     * <ol>
     *   <li>Fix up {@code level} if null (Sable edge case).</li>
     *   <li>Update the LIT block state based on whether the graph has nodes and is running.</li>
     *   <li>Detect graph changes and recompile the evaluator if the graph was modified.</li>
     *   <li>Clear outputs and return early if the graph is not running.</li>
     *   <li>Bootstrap Sable cache as a one-time fallback.</li>
     *   <li>Scan for players and mobs within {@code scanRange}, accounting for Sable coordinate transforms.</li>
     *   <li>Scan Sable sub-world structures (if enabled).</li>
     *   <li>Sort targets by distance, prune expired locks.</li>
     *   <li>Assign targets to TARGET_OUT graph nodes based on scan mode and lock mode.</li>
     *   <li>Evaluate the node graph to produce output signals.</li>
     *   <li>Broadcast results to clients and sync bus channel band changes.</li>
     * </ol>
     *
     * 主服务端 tick 方法 — 每游戏 tick（每秒 20 次）调用。
     * <p>
     * 执行顺序：
     * <ol>
     *   <li>修复 level 为 null 的情况（Sable 边缘情况）。</li>
     *   <li>根据图是否有节点且正在运行来更新 LIT 方块状态。</li>
     *   <li>检测图变化，如果图被修改则重新编译求值器。</li>
     *   <li>如果图未运行则清除输出并提前返回。</li>
     *   <li>一次性兜底引导 Sable 缓存。</li>
     *   <li>在 scanRange 内扫描玩家和生物，考虑 Sable 坐标变换。</li>
     *   <li>扫描 Sable 子世界结构（如果已启用）。</li>
     *   <li>按距离排序目标，清理过期的锁定。</li>
     *   <li>根据扫描模式和锁定模式将目标分配给 TARGET_OUT 图节点。</li>
     *   <li>评估节点图产生输出信号。</li>
     *   <li>向客户端广播结果并同步 bus 通道频段变化。</li>
     * </ol>
     */
    public void tick() {
        // Fix level = null on Sable structures. 修复 Sable 结构上 level 为 null 的问题
        if (level == null) level = getEffectiveLevel();
        if (level == null || level.isClientSide()) return;
        ensureBusRegistered();
        Level scanLevel = getScanLevel();

        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        var currentState = getBlockState();
        if (currentState.hasProperty(RadarBlock.LIT) && currentState.getValue(RadarBlock.LIT) != shouldBeLit)
            level.setBlock(worldPosition, currentState.setValue(RadarBlock.LIT, shouldBeLit), 3);

        rs.checkGraphChanged(graph);
        if (graphChanged()) recompileEvaluator();
        if (!running) {
            for (var n : graph.nodes) {
                if (n.type == NodeType.BUS_OUT && n.busInternalMap != null) n.busInternalMap.clear();
            }
            rs.writeOutputs(Collections.emptyList());
            return;
        }

        // Proactive Sable detection — fallback when sable$physicsTick hasn't been called.
        // 主动检测 Sable（sable$physicsTick 未调用时的兜底）
        tryBootstrapSableCache();

        // ══ Scan targets / 扫描目标 ══
        targets.clear();

        // Determine scan center in the appropriate coordinate space.
        // - Overworld: BlockPos IS the world coordinate.
        // - Sable: use cached world-space coordinates (cachedSubWorld* is the sub-world's world-space origin position).
        // 确定适当坐标空间中的扫描中心。
        // - 主世界：BlockPos 是世界坐标。
        // - Sable：使用缓存的世界坐标（cachedSubWorld 是子世界的世界原点位置）。
        boolean onSable = !Float.isNaN(cachedSubYaw);
        double scx = onSable ? cachedSubWorldX : worldPosition.getX() + 0.5;
        double scy = onSable ? cachedSubWorldY : worldPosition.getY() + 0.5;
        double scz = onSable ? cachedSubWorldZ : worldPosition.getZ() + 0.5;

        AABB scanBox = new AABB(scx - scanRange, scy - scanRange, scz - scanRange,
                                 scx + scanRange, scy + scanRange, scz + scanRange);

        // Diagnostic log: unconditionally log scan state for debugging Sable coordinate issues.
        // 诊断日志：无条件打印扫描信息，用于调试 Sable 坐标问题。
        SchematicCompute.LOGGER.debug("SABLE-SCAN: onSable={} sc=({},{},{}) wp=({},{},{}) subWorld=({},{},{}) subOrigin=({},{},{}) sableYPR=({},{},{}) sqw={} scanLevel={}",
            onSable,
            scx, scy, scz, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
            cachedSubWorldX, cachedSubWorldY, cachedSubWorldZ,
            cachedSubOriginX, cachedSubOriginY, cachedSubOriginZ,
            cachedSubYaw, cachedSubPitch, cachedSubRoll,
            cachedSubQw,
            scanLevel != null ? scanLevel.getClass().getSimpleName() : "null");

        int playerCount = 0, mobCount = 0;
        if (showPlayers) {
            for (var e : scanLevel.players()) {
                boolean inBox = scanBox.contains(e.getX(), e.getY(), e.getZ());
                SchematicCompute.LOGGER.debug("SABLE-SCAN: player at ({},{},{}) sc=({},{},{}) inBox={} dist={}",
                    e.getX(), e.getY(), e.getZ(), scx, scy, scz, inBox,
                    Math.sqrt((e.getX()-scx)*(e.getX()-scx)+(e.getY()-scy)*(e.getY()-scy)+(e.getZ()-scz)*(e.getZ()-scz)));
                if (inBox) {
                    targets.add(TargetRecord.fromEntity(e, scx, scy, scz));
                    playerCount++;
                }
            }
        }
        if (showMobs) {
            for (var e : scanLevel.getEntitiesOfClass(Mob.class, scanBox, e -> true)) {
                boolean inBox = scanBox.contains(e.getX(), e.getY(), e.getZ());
                SchematicCompute.LOGGER.debug("SABLE-SCAN: mob at ({},{},{}) sc=({},{},{}) inBox={} dist={}",
                    e.getX(), e.getY(), e.getZ(), scx, scy, scz, inBox,
                    Math.sqrt((e.getX()-scx)*(e.getX()-scx)+(e.getY()-scy)*(e.getY()-scy)+(e.getZ()-scz)*(e.getZ()-scz)));
                if (inBox) {
                    targets.add(TargetRecord.fromEntity(e, scx, scy, scz));
                    mobCount++;
                }
            }
        }
        SchematicCompute.LOGGER.debug("SABLE-SCAN: main scan found {} players, {} mobs (onSable={})",
            playerCount, mobCount, onSable);
        // Always scan sub-world entities; structure blip insertion checks showSable internally.
        // 始终扫描子世界实体，结构 blip 内部判断 showSable。
        scanSableStructures(scx, scy, scz, scanBox);
        // Sort by distance so closer targets get priority in assignments.
        // 按距离排序，使更近的目标在分配中优先。
        targets.sort(Comparator.comparingDouble(TargetRecord::distance));
        // Prune locks for targets that are no longer in scan range.
        // 清理不在范围内的过期锁定。
        if (!lockedTargets.isEmpty()) {
            var validIds = new java.util.HashSet<Integer>();
            for (var t : targets) validIds.add(t.entityId());
            lockedTargets.removeIf(id -> !validIds.contains(id));
        }
        if (!targets.isEmpty()) {
            SchematicCompute.LOGGER.debug("TICK: {} targets at {}, running={}", targets.size(), worldPosition, running);
        }

        // ══ Assign targets / 分配目标 ══
        var targetOutNodes = new ArrayList<GraphNode>();
        for (var n : graph.nodes) if (n.type == NodeType.TARGET_OUT) targetOutNodes.add(n);
        targetOutNodes.sort(Comparator.comparingInt(n -> n.id));
        if (lockMode == 1) {
            TargetAssignment.assignLocked(worldPosition, targetOutNodes, targets, lockedTargets, scanMode, lockDistance);
        } else {
            TargetAssignment.assign(worldPosition, targetOutNodes, targets, scanMode, lockDistance);
        }
        // Collect currently assigned target entity IDs for the renderer to highlight.
        // 收集当前分配的目标 entityId（渲染器高亮用）。
        activeTargets.clear();
        for (var n : targetOutNodes) {
            var t = TargetAssignment.getTarget(worldPosition, n.id);
            if (t != null) activeTargets.add(t.entityId());
        }

        // ══ Graph evaluation / 图评估 ══
        rs.refreshInputs();
        if (BusChannelHelper.recoverConflictedChannels(graph, worldPosition, level)) {
            needsFullSync = true; setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        var in = rs.buildInputs(graph);
        evaluator.setRadarPos(worldPosition);
        // Use Sable world-space coordinates if available, otherwise fall back to BlockPos center.
        // 如果有 Sable 世界坐标则使用，否则回退到 BlockPos 中心。
        float wx = Float.isNaN(cachedSubWorldX) ? worldPosition.getX() + 0.5f : cachedSubWorldX;
        float wy = Float.isNaN(cachedSubWorldY) ? worldPosition.getY() + 0.5f : cachedSubWorldY;
        float wz = Float.isNaN(cachedSubWorldZ) ? worldPosition.getZ() + 0.5f : cachedSubWorldZ;
        var si = new GraphEvaluator.SeatInputState(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, wx, wy, wz);
        var results = evaluator.evaluate(in, runtimeState.pidState, 0.05f, si,
            new HashMap<>(), new HashMap<>(), new HashMap<>());
        evaluator.setRadarPos(null);
        // Broadcast EvalSnapshot to clients for DEBUG_PROBE sampling.
        // 广播 EvalSnapshot 给客户端（供 DEBUG_PROBE 采样）。
        broadcastEvalSnapshot();
        BusChannelHelper.syncIfBandsChanged(graph, worldPosition, lastBusHashMap, level);
        setChanged();
        // Force-sync targets to clients so the blip renderer stays up to date.
        // 强制同步目标到客户端，确保 blip 渲染器数据实时更新。
        if (level != null && !level.isClientSide())
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    // ── Sable reflection cache / Sable 反射缓存 ──
    /** Cached reflective Method handles for Sable API, avoiding repeated Class.forName and getMethod calls. 缓存的 Sable API 反射 Method 句柄，避免重复 Class.forName 和 getMethod 调用。 */
    private static java.lang.reflect.Method sableGetContainerMethod, sableGetAllSubLevelsMethod,
                                             sableLogicalPoseMethod, sableSubLevelGetLevelMethod;
    /** Thread-safe initialization guard — ensures reflection is set up at most once. 线程安全的初始化守卫 — 确保反射最多初始化一次。 */
    private static volatile boolean sableReflectionInit;

    /**
     * Lazily initializes cached {@link java.lang.reflect.Method} handles for Sable's
     * {@code SubLevelContainer} and {@code SubLevel} APIs. Uses a volatile boolean guard
     * for thread-safe one-shot initialization.
     * <p>
     * Caches:
     * <ul>
     *   <li>{@code SubLevelContainer.getContainer(Level)}</li>
     *   <li>{@code SubLevelContainer.getAllSubLevels()}</li>
     *   <li>{@code SubLevel.logicalPose()}</li>
     *   <li>{@code SubLevel.getLevel()}</li>
     * </ul>
     *
     * 惰性初始化 Sable 的 {@code SubLevelContainer} 和 {@code SubLevel} API 的缓存的
     * {@link java.lang.reflect.Method} 句柄。使用 volatile boolean 守卫实现线程安全的一次性初始化。
     * <p>
     * 缓存内容：
     * <ul>
     *   <li>{@code SubLevelContainer.getContainer(Level)}</li>
     *   <li>{@code SubLevelContainer.getAllSubLevels()}</li>
     *   <li>{@code SubLevel.logicalPose()}</li>
     *   <li>{@code SubLevel.getLevel()}</li>
     * </ul>
     */
    private static void initSableReflection() {
        if (sableReflectionInit) return;
        try {
            var containerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            sableGetContainerMethod = containerClass.getMethod("getContainer", net.minecraft.world.level.Level.class);
            sableGetAllSubLevelsMethod = containerClass.getMethod("getAllSubLevels");
            // SubLevel is in dev.ryanhcode.sable.sublevel (not the api subpackage).
            // SubLevel 在 dev.ryanhcode.sable.sublevel 包（非 api 子包）。
            var subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
            sableLogicalPoseMethod = subLevelClass.getMethod("logicalPose");
            sableSubLevelGetLevelMethod = subLevelClass.getMethod("getLevel");
            SchematicCompute.LOGGER.info("Radar Sable reflection initialized OK");
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Radar Sable reflection init FAILED: {}", e.toString());
        }
        sableReflectionInit = true;
    }

    /**
     * Scans all Sable sub-world structures and adds them as target blips at their
     * world-space origin coordinates. Uses cached reflective Method handles.
     * <p>
     * The host structure (the one this radar is placed on) is identified by comparing
     * each structure's position against the cached sub-world origin. When
     * {@code excludeHost} is true, the host structure is skipped so the radar does
     * not lock onto itself.
     *
     * 扫描所有 Sable 子世界结构，在其世界空间原点坐标处添加为目标 blip。
     * 使用缓存的反射 Method 句柄。
     * <p>
     * 宿主结构（此雷达所在的 Sable 结构）通过将每个结构的位置与缓存的子世界原点
     * 进行比较来识别。当 {@code excludeHost} 为 true 时，跳过宿主结构以避免雷达锁定自身。
     *
     * @param scx scan center X 扫描中心 X
     * @param scy scan center Y 扫描中心 Y
     * @param scz scan center Z 扫描中心 Z
     * @param scanBox scan bounding box 扫描包围盒
     */
    private void scanSableStructures(double scx, double scy, double scz, AABB scanBox) {
        try {
            initSableReflection();
            if (sableGetContainerMethod == null) return;
            // getContainer must receive the overworld Level, not a sub-world Level.
            // getContainer 应传 overworld Level，不是子世界 Level。
            var cnt = sableGetContainerMethod.invoke(null, getScanLevel());
            if (cnt == null) return;
            var all = (List<?>) sableGetAllSubLevelsMethod.invoke(cnt);
            if (all == null || all.isEmpty()) return;

            int structCount = 0;

            for (var s : all) {
                var pose = sableLogicalPoseMethod.invoke(s);
                var pm = pose.getClass().getMethod("position");
                var pos = pm.invoke(pose);
                double sx = (double) pos.getClass().getMethod("x").invoke(pos);
                double sy = (double) pos.getClass().getMethod("y").invoke(pos);
                double sz = (double) pos.getClass().getMethod("z").invoke(pos);

                // Determine if this structure is the one hosting this radar.
                // Tolerance of 0.01 accounts for floating-point drift.
                // 判断此结构是否为承载此雷达的宿主结构。
                // 0.01 的容差用于处理浮点漂移。
                boolean isHost = !Float.isNaN(cachedSubOriginX)
                    && Math.abs(sx - cachedSubOriginX) < 0.01
                    && Math.abs(sy - cachedSubOriginY) < 0.01
                    && Math.abs(sz - cachedSubOriginZ) < 0.01;
                if (!isHost && showSable) {
                    targets.add(TargetRecord.fromSableStructure(scx, scy, scz, sx, sy, sz, "Sable Structure"));
                    structCount++;
                }
            }
            SchematicCompute.LOGGER.info("Radar Sable scan done: {} structures", structCount);
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Radar Sable scan error: {}", e.toString());
        }
    }

    /**
     * Deserializes a node graph from compressed NBT bytes, typically received over
     * the network from a client uploading a schematic or graph definition.
     * On success, replaces the current graph and reinitializes the evaluator.
     * On failure, resets to an empty graph to avoid a corrupted state.
     *
     * 从压缩 NBT 字节反序列化节点图，通常通过网络从客户端上传原理图或图定义时接收。
     * 成功时替换当前图并重新初始化求值器。
     * 失败时重置为空图以避免损坏状态。
     *
     * @param data compressed NBT data 压缩的 NBT 数据
     */
    public void loadGraphFromBytes(byte[] data) {
        if (level == null) return;
        try {
            var t = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(2 * 1024 * 1024));
            if (t != null && t.contains("graph")) {
                graph = NodeGraph.load(t.getCompound("graph"), level.registryAccess());
                rs.onLoad(graph);
            }
            needsFullSync = true; setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Failed to load radar graph, resetting", e);
            graph = new NodeGraph(); rs.onLoad(graph); setChanged();
        }
    }

    /**
     * Saves radar-specific data to NBT for persistence across world reloads.
     * Serializes the node graph, runtime PID/sub states, all scan and display
     * settings, Sable cache coordinates (if present), quaternion orientation,
     * lock state, and active target assignments.
     *
     * 将雷达特定数据保存到 NBT 以便在世界重载时持久化。
     * 序列化节点图、运行时 PID/子状态、所有扫描和显示设置、
     * Sable 缓存坐标（如果存在）、四元数朝向、锁定状态和活跃目标分配。
     *
     * @param t the compound tag to save into 要保存到的复合标签
     * @param r registry access for serialization 用于序列化的注册表访问
     */
    @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.saveAdditional(t, r);
        t.put("graph", graph.save(r));
        t.putBoolean("running", running);
        t.put("runtime", runtimeState.save());
        t.putInt("scanRange", scanRange);
        t.putInt("scanMode", scanMode);
        t.putInt("displayScale", displayScale);
        t.putBoolean("showPlayers", showPlayers);
        t.putBoolean("showMobs", showMobs);
        t.putBoolean("showSable", showSable);
        if (!Float.isNaN(cachedSubWorldX)) {
            t.putFloat("swx", cachedSubWorldX);
            t.putFloat("swy", cachedSubWorldY);
            t.putFloat("swz", cachedSubWorldZ);
            t.putFloat("syaw", cachedSubYaw);
            t.putFloat("spitch", cachedSubPitch);
            t.putFloat("sroll", cachedSubRoll);
            t.putFloat("sqx", cachedSubQx);
            t.putFloat("sqy", cachedSubQy);
            t.putFloat("sqz", cachedSubQz);
            t.putFloat("sqw", cachedSubQw);
        }
        if (!Float.isNaN(cachedSubOriginX)) {
            t.putFloat("sox", cachedSubOriginX);
            t.putFloat("soy", cachedSubOriginY);
            t.putFloat("soz", cachedSubOriginZ);
        }
        t.putInt("lockMode", lockMode);
        t.putFloat("displayX", displayX); t.putFloat("displayY", displayY); t.putFloat("displayZ", displayZ);
        t.putBoolean("excludeHost", excludeHost);
        t.putInt("displayStyle", displayStyle);
        t.putFloat("lockDistance", lockDistance);
        t.putIntArray("lockedTargets", lockedTargets.stream().mapToInt(i->i).toArray());
        t.putIntArray("activeTargets", activeTargets.stream().mapToInt(i->i).toArray());
    }

    /**
     * Loads radar-specific data from NBT after a world reload or chunk load.
     * Handles backward compatibility for old saves that may be missing Sable origin
     * coordinates ({@code sox/soy/soz}) or quaternion data ({@code sqx/sqy/sqz/sqw}).
     * <p>
     * When Sable cache data is absent on the client side, clears the local cache
     * to match the server's state. When {@code swx} exists but {@code sox} doesn't
     * (old save migration), marks the origin as uninitialized so that
     * {@code sable$physicsTick} will populate it; during the gap, {@code isHost}
     * comparisons conservatively return false (safe fallback).
     *
     * 在世界重载或区块加载后从 NBT 加载雷达特定数据。
     * 处理旧存档的向后兼容性：旧存档可能缺少 Sable 原点坐标（{@code sox/soy/soz}）
     * 或四元数数据（{@code sqx/sqy/sqz/sqw}）。
     * <p>
     * 当客户端侧缺少 Sable 缓存数据时，清除本地缓存以匹配服务端状态。
     * 当存在 {@code swx} 但不存在 {@code sox} 时（旧存档迁移），标记原点为未初始化，
     * 让 {@code sable$physicsTick} 后续填充；在此期间 isHost 判断保守返回 false（安全回退）。
     *
     * @param t the compound tag to load from 要加载的复合标签
     * @param r registry access for deserialization 用于反序列化的注册表访问
     */
    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t, r);
        if (t.contains("graph")) { graph = NodeGraph.load(t.getCompound("graph"), r); rs.onLoad(graph); }
        if (t.contains("running")) running = t.getBoolean("running");
        if (t.contains("runtime")) {
            RuntimeState loaded = RuntimeState.load(t.getCompound("runtime"));
            runtimeState.pidState.putAll(loaded.pidState);
            runtimeState.subStates.putAll(loaded.subStates);
        }
        if (t.contains("scanRange")) scanRange = t.getInt("scanRange");
        if (t.contains("scanMode")) scanMode = t.getInt("scanMode");
        if (t.contains("displayScale")) displayScale = t.getInt("displayScale");
        if (t.contains("showPlayers")) showPlayers = t.getBoolean("showPlayers");
        if (t.contains("showMobs")) showMobs = t.getBoolean("showMobs");
        if (t.contains("showSable")) showSable = t.getBoolean("showSable");
        if (t.contains("lockMode")) lockMode = t.getInt("lockMode");
        if (t.contains("displayX")) { displayX = t.getFloat("displayX"); displayY = t.getFloat("displayY"); displayZ = t.getFloat("displayZ"); }
        if (t.contains("excludeHost")) excludeHost = t.getBoolean("excludeHost");
        if (t.contains("displayStyle")) displayStyle = t.getInt("displayStyle");
        if (t.contains("lockDistance")) lockDistance = t.getFloat("lockDistance");
        if (t.contains("lockedTargets")) {
            lockedTargets.clear();
            for (int id : t.getIntArray("lockedTargets")) lockedTargets.add(id);
        }
        if (t.contains("activeTargets")) {
            activeTargets.clear();
            for (int id : t.getIntArray("activeTargets")) activeTargets.add(id);
        }
        if (t.contains("swx")) {
            cachedSubWorldX = t.getFloat("swx");
            cachedSubWorldY = t.getFloat("swy");
            cachedSubWorldZ = t.getFloat("swz");
            cachedSubYaw = t.getFloat("syaw");
            cachedSubPitch = t.getFloat("spitch");
            cachedSubRoll = t.getFloat("sroll");
            if (t.contains("sqx")) {
                cachedSubQx = t.getFloat("sqx");
                cachedSubQy = t.getFloat("sqy");
                cachedSubQz = t.getFloat("sqz");
                cachedSubQw = t.getFloat("sqw");
            }
        } else if (level != null && level.isClientSide()) {
            // Server has cleared stale Sable cache; client should mirror that. 服务端已清除过期 Sable 缓存，客户端同步清除。
            clearCachedSubPose();
        }
        if (t.contains("sox")) {
            cachedSubOriginX = t.getFloat("sox");
            cachedSubOriginY = t.getFloat("soy");
            cachedSubOriginZ = t.getFloat("soz");
        } else if (t.contains("swx")) {
            // Old save migration: has swx but no sox — mark origin as uninitialized.
            // sable$physicsTick will populate it later; isHost returns false in the meantime (safe fallback).
            // 旧存档迁移：有 swx 但没有 sox，标记原点为未初始化。
            // 下次 sable$physicsTick 会填充，期间 isHost 判断为 false（安全回退）。
            cachedSubOriginX = Float.NaN;
            cachedSubOriginY = Float.NaN;
            cachedSubOriginZ = Float.NaN;
        }
        if (t.contains("targets")) {
            targets.clear();
            var list = t.getList("targets", 10);
            for (int i = 0; i < list.size(); i++) {
                var e = list.getCompound(i);
                targets.add(new TargetRecord(e.getDouble("x"), e.getDouble("y"), e.getDouble("z"),
                    e.getInt("id"), e.getFloat("dist"), e.getString("type"), e.getString("name")));
            }
        }
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /**
     * Returns the network packet for syncing block entity data to tracking clients.
     * Uses the vanilla {@link ClientboundBlockEntityDataPacket} mechanism.
     *
     * 返回用于将方块实体数据同步到追踪客户端的网络数据包。
     * 使用原版 {@link ClientboundBlockEntityDataPacket} 机制。
     *
     * @return the update packet, or null 更新数据包，或 null
     */
    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    /** Always send the full graph so that new clients tracking this chunk receive
     *  the authoritative graph data. Also includes runtime radar targets for the
     *  client-side blip renderer.
     *  始终发送完整图数据，以确保新追踪此区块的客户端能收到权威图数据。
     *  同时包含运行时雷达目标数据供客户端 blip 渲染器使用。 */
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) {
        var t = new CompoundTag();
        saveAdditional(t, r);
        // Append runtime radar targets for client-side blip renderer.
        // 附加运行时雷达目标供客户端 blip 渲染器使用。
        var list = new net.minecraft.nbt.ListTag();
        for (var tr : targets) {
            var e = new CompoundTag();
            e.putDouble("x", tr.x()); e.putDouble("y", tr.y()); e.putDouble("z", tr.z());
            e.putInt("id", tr.entityId()); e.putFloat("dist", tr.distance());
            e.putString("type", tr.entityType()); e.putString("name", tr.name());
            list.add(e);
        }
        t.put("targets", list);
        return t;
    }

    /**
     * Server-side raycast to determine which target blip (if any) the player is
     * looking at through the crosshair. The algorithm:
     * <ol>
     *   <li>Converts each target's world-space position to the holographic blip
     *       display coordinates (normalized by scanRange and displayScale).</li>
     *   <li>Applies the inverse of the radar's facing and Sable rotations to
     *       bring the blip display back into world space.</li>
     *   <li>Projects the player's look vector onto the blip position and measures
     *       the point-to-ray distance.</li>
     *   <li>Returns the entityId of the closest blip within the hit threshold (2.0).</li>
     * </ol>
     *
     * 服务端射线检测，判断玩家通过准星正在看向哪个目标 blip。
     * 算法：
     * <ol>
     *   <li>将每个目标的世界空间位置转换为全息 blip 显示坐标（按 scanRange 和 displayScale 归一化）。</li>
     *   <li>应用雷达朝向和 Sable 旋转的逆变换，将 blip 显示位置带回世界空间。</li>
     *   <li>将玩家的视线向量投影到 blip 位置，测量点到射线的距离。</li>
     *   <li>返回命中阈值（2.0）内最近的 blip 的 entityId。</li>
     * </ol>
     *
     * @param player the player performing the look 正在观察的玩家
     * @return entityId of the blip under the crosshair, or {@code null} if none 准星下 blip 的 entityId，无则返回 {@code null}
     */
    @javax.annotation.Nullable
    public Integer findBlipUnderCrosshair(net.minecraft.world.entity.player.Player player) {
        if (targets.isEmpty()) return null;
        var eyePos = player.getEyePosition();
        var lookVec = player.getLookAngle();

        // Determine radar's world-space position for blip display.
        // 确定雷达的世界空间位置用于 blip 显示。
        boolean onSable = !Float.isNaN(cachedSubYaw);
        double radarWorldX = onSable ? cachedSubWorldX : getBlockPos().getX() + 0.5;
        double radarWorldY = onSable ? cachedSubWorldY : getBlockPos().getY() + 0.5;
        double radarWorldZ = onSable ? cachedSubWorldZ : getBlockPos().getZ() + 0.5;
        int scanRange = Math.max(1, this.scanRange);
        float axisLen = this.displayScale * 0.5f;

        float facingYDeg = getBlockState().hasProperty(RadarBlock.FACING)
            ? getBlockState().getValue(RadarBlock.FACING).toYRot() : 0;
        // Apply display offset, rotated into the radar's facing frame.
        // 应用显示偏移，旋转到雷达朝向坐标系。
        var dispOff = new org.joml.Vector3f(displayX, displayY, displayZ);
        dispOff.rotateY((float) Math.toRadians(-facingYDeg));
        if (onSable && !Float.isNaN(cachedSubQw)) {
            var q = new org.joml.Quaternionf(cachedSubQx, cachedSubQy, cachedSubQz, cachedSubQw);
            dispOff.rotate(q);
        }
        radarWorldX += dispOff.x; radarWorldY += dispOff.y; radarWorldZ += dispOff.z;

        // Precompute inverse Sable rotation quaternion for transforming local blip offsets back to world space.
        // 预计算 Sable 旋转的逆四元数，用于将本地 blip 偏移变回世界空间。
        org.joml.Quaternionf invQ = null;
        if (onSable && !Float.isNaN(cachedSubQw)) {
            invQ = new org.joml.Quaternionf(cachedSubQx, cachedSubQy, cachedSubQz, cachedSubQw);
            invQ.conjugate();
        }

        Integer best = null;
        double bestDist = 2.0; // Hit threshold — maximum distance from ray to be considered "on target". 命中阈值 — 到射线的最大距离才算"瞄准中"。

        for (var t : targets) {
            // Step 1: local offset relative to radar center in world space.
            // 第一步：世界空间中相对于雷达中心的本地偏移。
            float dx = (float)(t.x() - radarWorldX);
            float dy = (float)(t.y() - radarWorldY);
            float dz = (float)(t.z() - radarWorldZ);
            var v = new org.joml.Vector3f(dx, dy, dz);
            // Step 2: rotate into radar-local space for normalized blip coordinate computation.
            // 第二步：旋转到雷达本地空间以计算归一化的 blip 坐标。
            if (invQ != null) v.rotate(invQ);
            v.rotateY((float) Math.toRadians(facingYDeg));
            float rx = v.x / scanRange * axisLen;
            float ry = v.y / scanRange * axisLen;
            float rz = v.z / scanRange * axisLen;
            // Skip targets whose blip would fall outside the axis display bounds.
            // 跳过 blip 会超出轴显示范围的目标。
            if (Math.abs(rx) > axisLen || Math.abs(ry) > axisLen || Math.abs(rz) > axisLen) continue;

            // Step 3: convert blip display coordinates back to world space for the ray test.
            // 第三步：将 blip 显示坐标转换回世界空间进行射线检测。
            var worldOffset = new org.joml.Vector3f(rx, ry, rz);
            worldOffset.rotateY((float) Math.toRadians(-facingYDeg));
            if (onSable && !Float.isNaN(cachedSubQw)) {
                var q = new org.joml.Quaternionf(cachedSubQx, cachedSubQy, cachedSubQz, cachedSubQw);
                worldOffset.rotate(q);
            }
            double wx = radarWorldX + worldOffset.x;
            double wy = radarWorldY + worldOffset.y;
            double wz = radarWorldZ + worldOffset.z;

            // Step 4: point-to-ray distance — project eye→target onto look vector.
            // 第四步：点到射线的距离 — 将 eye→target 投影到视线向量上。
            var tp = new net.minecraft.world.phys.Vec3(wx, wy, wz);
            var toTarget = tp.subtract(eyePos);
            double dot = toTarget.dot(lookVec);
            if (dot <= 0) continue; // Target is behind the player. 目标在玩家身后。
            var proj = eyePos.add(lookVec.scale(dot));
            double dist = tp.distanceTo(proj);
            if (dist < bestDist) { bestDist = dist; best = t.entityId(); }
        }
        return best;
    }

    /**
     * Toggles manual lock on a target entity. If the target is already locked, it
     * is unlocked (returns true). Otherwise, attempts to lock it:
     * <ul>
     *   <li>In single-target mode ({@code maxLocks == 1}), any existing lock is
     *       replaced before the new lock is added.</li>
     *   <li>If the lock list is already at capacity ({@code maxLocks}), returns false.</li>
     * </ul>
     *
     * 切换对目标实体的手动锁定。如果目标已锁定则解锁（返回 true）。
     * 否则尝试锁定：
     * <ul>
     *   <li>单目标模式（{@code maxLocks == 1}），先替换已有锁定再添加新锁定。</li>
     *   <li>如果锁定列表已满（达到 {@code maxLocks}），返回 false。</li>
     * </ul>
     *
     * @param entityId the entity to toggle lock on 要切换锁定的实体 ID
     * @param maxLocks maximum allowed locks 允许的最大锁定数
     * @return {@code true} if the lock state changed; {@code false} if the lock list was full and no change occurred 锁定状态发生变化返回 {@code true}；列表已满无变化返回 {@code false}
     */
    public boolean toggleLock(int entityId, int maxLocks) {
        if (lockedTargets.contains(entityId)) {
            lockedTargets.remove(entityId);
            setChanged();
            return true;
        }
        // Single-target mode: replace old lock. 单目标模式：替换旧锁定。
        if (maxLocks == 1 && !lockedTargets.isEmpty()) {
            lockedTargets.clear();
        }
        if (maxLocks > 0 && lockedTargets.size() >= maxLocks) return false;
        lockedTargets.add(entityId);
        setChanged();
        return true;
    }

    /**
     * Calculates the maximum number of manual locks allowed based on scan mode
     * and the number of TARGET_OUT nodes in the graph.
     * <ul>
     *   <li>Single-target scan mode ({@code scanMode == 1}): always returns 1.</li>
     *   <li>Multi-target mode: returns the count of TARGET_OUT nodes, minimum 1.</li>
     * </ul>
     *
     * 根据扫描模式和图中 TARGET_OUT 节点数计算最大手动锁定数。
     * <ul>
     *   <li>单目标扫描模式（{@code scanMode == 1}）：始终返回 1。</li>
     *   <li>多目标模式：返回 TARGET_OUT 节点数，最少 1。</li>
     * </ul>
     *
     * @return maximum number of locks allowed 允许的最大锁定数
     */
    public int getMaxLocks() {
        if (scanMode == 1) return 1;
        int count = 0;
        for (var n : graph.nodes) if (n.type == NodeType.TARGET_OUT) count++;
        return Math.max(1, count);
    }

    /**
     * Transforms a sub-world entity's local coordinates to world space using Sable's
     * {@code logicalPose.transformPosition} (when available via reflection), then adds
     * the entity to the target list if it falls within the scan bounding box.
     * <p>
     * When {@code transformMethod} is null (reflection failed or API unavailable),
     * falls back to simple vector addition of the sub-world origin position — this
     * works for non-rotated structures but will be inaccurate for rotated ones.
     *
     * 使用 Sable 的 {@code logicalPose.transformPosition}（通过反射获取时可用）
     * 将子世界实体的本地坐标变换到世界空间，如果实体落在扫描包围盒内则加入目标列表。
     * <p>
     * 当 {@code transformMethod} 为 null（反射失败或 API 不可用）时，
     * 回退到简单的子世界原点位置向量加法——这对无旋转结构有效，但对旋转结构不精确。
     *
     * @param e               the entity to transform and potentially add 要变换并可能添加的实体
     * @param scx             scan center X 扫描中心 X
     * @param scy             scan center Y 扫描中心 Y
     * @param scz             scan center Z 扫描中心 Z
     * @param pose            Sable logicalPose object for coordinate transform 用于坐标变换的 Sable logicalPose 对象
     * @param transformMethod reflective Method handle for transformPosition (may be null) transformPosition 的反射 Method 句柄（可能为 null）
     * @param scanBox         scan bounding box 扫描包围盒
     * @return {@code true} if the entity was within range and added to the target list 实体在范围内且已添加到目标列表返回 {@code true}
     */
    private boolean tryAddSubEntity(net.minecraft.world.entity.Entity e, double scx, double scy, double scz,
                                     Object pose, java.lang.reflect.Method transformMethod, AABB scanBox) {
        try {
            double wx, wy, wz;
            if (transformMethod != null) {
                // Precise transform: use Sable's built-in transformPosition for correct rotation handling.
                // 精确变换：使用 Sable 内置 transformPosition 正确处理旋转。
                var localPos = new org.joml.Vector3d(e.getX(), e.getY(), e.getZ());
                var worldPos = (org.joml.Vector3d) transformMethod.invoke(pose, localPos);
                wx = worldPos.x; wy = worldPos.y; wz = worldPos.z;
            } else {
                // Fallback: simple vector addition — works for non-rotated structures only.
                // 回退：简单加法（无旋转结构也能工作）。
                var pm = pose.getClass().getMethod("position");
                var pos = pm.invoke(pose);
                double sx = (double) pos.getClass().getMethod("x").invoke(pos);
                double sy = (double) pos.getClass().getMethod("y").invoke(pos);
                double sz = (double) pos.getClass().getMethod("z").invoke(pos);
                wx = e.getX() + sx; wy = e.getY() + sy; wz = e.getZ() + sz;
            }
            if (!inScanRange(wx, wy, wz, scanBox)) return false;
            double dx = wx - scx, dy = wy - scy, dz = wz - scz;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            String type = e instanceof net.minecraft.world.entity.player.Player ? TargetRecord.TYPE_PLAYER
                : e instanceof Mob ? TargetRecord.TYPE_MOB : "other";
            targets.add(new TargetRecord(wx, wy, wz, e.getId(), dist, type, e.getName().getString()));
            return true;
        } catch (Exception ex) {
            SchematicCompute.LOGGER.warn("Radar: failed to add sub-entity {}: {}", e.getName().getString(), ex.toString());
            return false;
        }
    }

    /**
     * Returns the localized display name for the radar container GUI.
     *
     * 返回雷达容器 GUI 的本地化显示名称。
     *
     * @return translated component for the radar screen title 雷达界面标题的翻译后组件
     */
    @Override public Component getDisplayName() { return Component.translatable("container." + SchematicCompute.MOD_ID + ".radar"); }

    /**
     * Creates the radar container menu when a player interacts with the block.
     *
     * 当玩家与方块交互时创建雷达容器菜单。
     *
     * @param id  container window ID 容器窗口 ID
     * @param inv player inventory 玩家背包
     * @param p   the interacting player 交互的玩家
     * @return a new {@link RadarMenu} instance, or {@code null} 新的 {@link RadarMenu} 实例，或 {@code null}
     */
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new RadarMenu(id, this); }
}
