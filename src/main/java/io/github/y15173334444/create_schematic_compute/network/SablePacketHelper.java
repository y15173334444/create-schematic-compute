package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity;
import io.github.y15173334444.create_schematic_compute.compat.SableReflection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Shared Sable sub-level lookup and server-side device scanning.
 * 共享的 Sable 子层级查询与服务端设备扫描工具类。
 *
 * <p>Delegates reflection to the shared {@link io.github.y15173334444.create_schematic_compute.compat.SableReflection}
 * and delegates reachability checks to {@link io.github.y15173334444.create_schematic_compute.compat.SablePoseHelper}.
 * 将反射调用委托给共享的反射工具类与可达性检查助手。
 *
 * <p>This helper is the central entry point for all cross-sub-level operations:
 * finding which sub-level contains a given block position, scanning sub-levels
 * for graph-connected devices, and verifying player-to-block reachability across
 * Sable's coordinate transforms.
 * 本助手是跨子层级操作的统一入口：查找包含指定方块位置的子层级、
 * 扫描子层级中的图连接设备、以及通过 Sable 坐标变换验证玩家到方块的可达性。
 */
public class SablePacketHelper {

    /**
     * Checks whether the Sable mod is loaded in the current environment.
     * 检查当前环境中是否加载了 Sable 模组。
     *
     * <p>Uses {@link net.neoforged.fml.ModList} which is safe to call on both
     * the physical client and the physical server — no side-only class references.
     * 使用 ModList 查询，在物理客户端和物理服务端均可安全调用 —— 不引用任何 side-only 类。
     *
     * @return true if Sable is loaded / 如果 Sable 已加载
     */
    private static boolean sableAvailable() {
        return net.neoforged.fml.ModList.get().isLoaded("sable");
    }

    /**
     * Finds the Sable sub-level that contains a block entity at the given position.
     * 查找在指定坐标处包含方块实体的 Sable 子层级。
     *
     * <p>Iterates over all sub-levels attached to the overworld container and returns
     * the first sub-level whose level has a non-null block entity at {@code pos}.
     * Returns {@code null} if Sable is not loaded, the container is missing, or no
     * sub-level matches.
     * 遍历附着在主世界容器上的所有子层级，返回第一个在 {@code pos} 处存在非空方块实体的子层级。
     * 若 Sable 未加载、容器缺失或无匹配子层级，则返回 {@code null}。
     *
     * @param overworld the overworld level / 主世界 Level
     * @param pos       the block position to look up / 要查找的方块坐标
     * @return the matching sub-level, or {@code null} / 匹配的子层级，或 null
     */
    public static Level findSubLevel(Level overworld, BlockPos pos) {
        if (!sableAvailable()) return null;
        try {
            // Compile-time Sable access (dedicated-server safe). resolveSubLevel uses
            // the ChunkPos→Plot→SubLevel mapping — O(1) vs. the old full iteration.
            // 编译期 Sable 访问（专用服务器安全）。resolveSubLevel 使用
            // ChunkPos→Plot→SubLevel 映射——O(1)，优于旧的完整遍历。
            var sub = io.github.y15173334444.create_schematic_compute.compat.SablePoseHelper
                .resolveSubLevel(overworld, pos);
            if (sub != null) {
                Level sl = sub.getLevel();
                if (sl != null && sl.getBlockEntity(pos) != null) return sl;
            }
        } catch (Exception e) {
            // Log the failure but don't crash — sub-level lookup is best-effort.
            // 记录异常但不崩溃 —— 子层级查询是尽力而为的。
            SchematicCompute.LOGGER.warn("SablePacketHelper.findSubLevel failed: {}", e.toString());
        }
        return null;
    }

    /**
     * A lightweight record holding metadata for a discovered graph-block-entity device
     * inside a Sable sub-level.
     * 记录在 Sable 子层级中发现的图方块实体设备的轻量数据载体。
     *
     * @param localPos   the block position within the sub-level's own coordinate space
     *                   子层级本地坐标空间中的方块位置
     * @param name       the display name of the block (from its BlockState)
     *                   方块的显示名称（来自 BlockState）
     * @param beClassName the simple class name of the BlockEntity
     *                   方块实体的简单类名
     * @param distance   Euclidean distance from the scanning origin (player position)
     *                   距扫描原点（玩家位置）的欧几里得距离
     * @param subLevelId a deterministic ID derived from the sub-level's world-space origin
     *                   由子层级世界空间原点派生的确定性 ID
     */
    public record SableDeviceEntry(BlockPos localPos, String name, String beClassName,
                                   float distance, long subLevelId) {}

    /**
     * Scans all Sable sub-levels for graph-block-entity devices within a given range
     * of the player position.
     * 在玩家位置指定范围内扫描所有 Sable 子层级中的图方块实体设备。
     *
     * <h3>Algorithm overview / 算法概述</h3>
     * <ol>
     *   <li>Get the Sable container from the overworld level.</li>
     *   <li>Iterate every sub-level; for each, compute or retrieve its cached world-space
     *       transform (origin + quaternion rotation + optional pivot point).</li>
     *   <li>Skip sub-levels whose origin is too far from the player (coarse rejection).</li>
     *   <li>Iterate every loaded chunk in the sub-level; for each block entity inside,
     *       check whether it implements {@link GraphBlockEntity}.</li>
     *   <li>Convert the local block position to world-space coordinates using the
     *       sub-level's rotation quaternion and origin, then test distance against
     *       the scan range.</li>
     *   <li>Sort results by distance ascending and cap at 50 entries.</li>
     * </ol>
     *
     * @param overworld  the server-side overworld level / 服务端主世界
     * @param playerPos  the player's block position (scanning origin) / 玩家的方块位置（扫描原点）
     * @param scanRange  maximum Euclidean distance in blocks / 最大欧几里得距离（方块数）
     * @return a list of discovered devices, sorted by distance; never null
     *         按距离排序的已发现设备列表，不会为 null
     */
    public static List<SableDeviceEntry> scanDevices(ServerLevel overworld, BlockPos playerPos, int scanRange) {
        if (!sableAvailable()) return Collections.emptyList();

        List<SableDeviceEntry> results = new ArrayList<>();

        // Pre-compute squared range to avoid sqrt in the hot loop.
        // 预计算平方距离，避免热循环中反复开方。
        int rangeSq = scanRange * scanRange;
        // Sub-level internal scan radius — generous to cover edge cases.
        // 子层级内部扫描半径 —— 设置较大值以覆盖边界情况。
        int subScanR = 128;
        try {
            var cnt = SableReflection.getContainer((Level) overworld);
            if (cnt == null) return results;
            var all = SableReflection.getAllSubLevels(cnt);
            if (all.isEmpty()) return results;

            for (var s : all) {
                // Use cached sub-level transform data (avoids ~20 reflection calls after first scan)
                // 使用缓存的子层级变换数据（首次扫描后避免约 20 次反射调用）
                double[] t = getOrComputeSubTransform(s);
                if (t == null) continue;
                // t[0-2]: world-space origin offset; t[3-5]: rotation pivot (optional); t[6-9]: quaternion (x,y,z,w)
                // t[0-2]: 世界空间原点偏移; t[3-5]: 旋转中心点(可选); t[6-9]: 四元数(x,y,z,w)
                double ox = t[0], oy = t[1], oz = t[2], rpx = t[3], rpy = t[4], rpz = t[5];
                double qx = t[6], qy = t[7], qz = t[8], qw = t[9];

                // Coarse rejection: skip sub-levels whose origin is clearly out of range.
                // 粗粒度剔除：跳过原点明显超出范围的子层级。
                BlockPos origin = BlockPos.containing(ox, oy, oz);
                int margin = subScanR + 8;
                if (origin.distSqr(playerPos) > (long)(scanRange + margin) * (scanRange + margin)) continue;

                // Derive a stable ID from the origin — used to group results per sub-level.
                // 从原点派生稳定 ID —— 用于按子层级分组结果。
                long subLevelId = subLevelId(ox, oy, oz);
                // Only compute pivot subtraction if a non-zero pivot is present (minor optimization).
                // 仅当旋转中心非零时才做减法（微优化）。
                boolean hasRp = (rpx != 0 || rpy != 0 || rpz != 0);

                var plot = SableReflection.getPlot(s);
                if (plot == null) continue;
                var centerBlock = SableReflection.getPlotCenterBlock(plot);
                int cbx = centerBlock != null ? centerBlock.getX() : 0;
                int cby = centerBlock != null ? centerBlock.getY() : 0;
                int cbz = centerBlock != null ? centerBlock.getZ() : 0;

                // getPlotLoadedChunks returns a raw Collection<?>; we adapt it generically.
                // getPlotLoadedChunks 返回原始 Collection<?>；我们通过泛型适配使用。
                @SuppressWarnings("unchecked")
                var loaded = (Collection<?>) (Object) SableReflection.getPlotLoadedChunks(plot);
                if (loaded == null) continue;

                // Build quaternion once per sub-level — reused for every BE position transform.
                // 每个子层级构建一次四元数 —— 所有方块实体位置变换复用。
                org.joml.Quaterniond q = new org.joml.Quaterniond(qx, qy, qz, qw);
                int found = 0, scannedChunks = 0, scannedBEs = 0;
                for (var holder : loaded) {
                    if (holder == null) continue;
                    scannedChunks++;
                    Object chunkObj = SableReflection.getChunkFromHolder(holder);
                    if (!(chunkObj instanceof net.minecraft.world.level.chunk.LevelChunk chunk)) continue;
                    for (var e : chunk.getBlockEntities().entrySet()) {
                        scannedBEs++;
                        BlockEntity be = e.getValue();
                        // Only interested in graph-connected block entities.
                        // 只关注图连接的方块实体。
                        if (!isGraphBlockEntity(be)) continue;

                        // Local position: center of the block for accurate distance.
                        // 本地坐标：使用方块中心点以获得精确距离。
                        BlockPos owPos = e.getKey();
                        double lx = owPos.getX() + 0.5;
                        double ly = owPos.getY() + 0.5;
                        double lz = owPos.getZ() + 0.5;

                        // Apply rotation pivot: subtract pivot, rotate, then the origin offset
                        // is added after the quaternion transform (done with `ox + lo.x` below).
                        // 应用旋转中心：先减去旋转中心，旋转变换后再叠加世界空间原点偏移。
                        org.joml.Vector3d lo = hasRp
                            ? new org.joml.Vector3d(lx - rpx, ly - rpy, lz - rpz)
                            : new org.joml.Vector3d(lx, ly, lz);
                        q.transform(lo);

                        // Convert to world-space coordinates by adding the sub-level origin.
                        // 加上子层级原点得到世界空间坐标。
                        double wx = ox + lo.x, wy = oy + lo.y, wz = oz + lo.z;
                        // Euclidean distance squared — avoid Math.sqrt in the loop.
                        // 欧几里得距离平方 —— 循环中避免 Math.sqrt。
                        double ds = (wx - playerPos.getX() - 0.5) * (wx - playerPos.getX() - 0.5)
                                  + (wy - playerPos.getY() - 0.5) * (wy - playerPos.getY() - 0.5)
                                  + (wz - playerPos.getZ() - 0.5) * (wz - playerPos.getZ() - 0.5);
                        if (ds > rangeSq) continue;

                        results.add(new SableDeviceEntry(owPos.immutable(),
                            be.getBlockState().getBlock().getName().getString(),
                            be.getClass().getSimpleName(), (float)Math.sqrt(ds), subLevelId));
                        found++;
                    }
                }
                // Diagnostic log — helps debug scan coverage in production.
                // 诊断日志 —— 有助于在生产环境中调试扫描覆盖情况。
                SchematicCompute.LOGGER.info("SablePacketHelper: sub-level at {} plotCtr=({},{},{}) chunks={} BEs={} graph={}",
                    origin, cbx, cby, cbz, scannedChunks, scannedBEs, found);
            }
        } catch (Exception e) {
            // Catch-all: scanning is best-effort; never let an exception escape.
            // 兜底捕获：扫描是尽力而为的，不允许异常向外传播。
            SchematicCompute.LOGGER.warn("SablePacketHelper.scanDevices(Sable) failed: {}", e.toString());
        }

        // Sort by distance ascending; cap results to avoid oversized packets.
        // 按距离升序排序；限制结果数量以避免过大的数据包。
        results.sort(Comparator.comparingDouble(SableDeviceEntry::distance));
        if (results.size() > 50) results = new ArrayList<>(results.subList(0, 50));
        SchematicCompute.LOGGER.info("SablePacketHelper.scanDevices: {} total device(s)", results.size());
        return results;
    }

    /**
     * Checks if a BlockEntity implements {@link GraphBlockEntity}, handling
     * cross-classloader scenarios (e.g. when Sable copies block entities into
     * its assembly, the classloader differs from the mod's own classloader).
     * 检查方块实体是否实现了 GraphBlockEntity 接口，兼容跨类加载器场景
     * （例如 Sable 将方块实体拷贝到其 assembly 中时，类加载器与模组自身的类加载器不同）。
     *
     * <p>Strategy:
     * <ol>
     *   <li><b>Fast path:</b> direct {@code instanceof} for same-classloader BEs.</li>
     *   <li><b>Slow path:</b> walk the interface list by FQN string comparison.</li>
     *   <li><b>Fallback:</b> walk the superclass chain (and each superclass's interfaces)
     *       stopping at the first non-mod package to avoid unnecessary deep traversal.</li>
     * </ol>
     *
     * @param be the block entity to test / 要检查的方块实体
     * @return true if it is / implements GraphBlockEntity / 如果是 GraphBlockEntity 则返回 true
     */
    private static boolean isGraphBlockEntity(BlockEntity be) {
        // Fast path: direct instanceof works for same-classloader BEs
        // 快速路径：同 ClassLoader 下的 BE 直接 instanceof 即可
        if (be instanceof GraphBlockEntity) return true;
        // Slow path: check by interface name for cross-classloader BEs
        // 慢速路径：通过接口全限定名比较来匹配跨 ClassLoader 的 BE
        for (Class<?> iface : be.getClass().getInterfaces()) {
            if (iface.getName().equals("io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity"))
                return true;
        }
        // Also check superclass chain — the BE could extend a class that implements the interface.
        // 同时检查父类链 —— BE 可能继承了实现该接口的父类。
        for (Class<?> c = be.getClass().getSuperclass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity"))
                return true;
            for (Class<?> iface : c.getInterfaces()) {
                if (iface.getName().equals("io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity"))
                    return true;
            }
            // Stop traversing once we leave the mod's package — no point scanning java.lang.Object.
            // 离开本模组包名前缀后停止向上遍历 —— 没必要扫描到 java.lang.Object。
            if (!c.getName().startsWith("io.github.y15173334444.create_schematic_compute")) break;
        }
        return false;
    }

    /**
     * Cache: sub-level world-space origin ID → full transform array.
     * 缓存：子层级世界空间原点 ID → 完整变换数组。
     *
     * <p>Transform array layout: [ox, oy, oz, rpx, rpy, rpz, qx, qy, qz, qw]
     * where ox/oy/oz are world-space origin offsets, rpx/rpy/rpz are the rotation
     * pivot point (may be all zero), and qx/qy/qz/qw form the rotation quaternion.
     * 变换数组布局：[ox, oy, oz, rpx, rpy, rpz, qx, qy, qz, qw]
     * ox/oy/oz 是世界空间原点偏移量，rpx/rpy/rpz 是旋转中心点（可能全为零），
     * qx/qy/qz/qw 构成旋转四元数。
     *
     * <p>Thread-safe: multiple scan callers may read/write concurrently.
     * 线程安全：多个扫描调用方可并发读写。
     */
    private static final java.util.Map<Long, double[]> subTransformCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Retrieves or lazily computes the world-space transform for a given sub-level object.
     * 获取或延迟计算指定子层级对象的世界空间变换。
     *
     * <p>The transform is keyed by a stable ID derived from the sub-level's logical-pose
     * position, avoiding collisions that could occur with {@code System.identityHashCode}
     * on reflective proxy objects. Once computed, subsequent calls are O(1) cache hits.
     * 变换以从子层级 logical-pose 位置派生的稳定 ID 为键，避免在反射代理对象上使用
     * identityHashCode 可能产生的冲突。首次计算后，后续调用均为 O(1) 缓存命中。
     *
     * @param subLevelObj the Sable sub-level object (obtained via reflection)
     *                    Sable 子层级对象（通过反射获取）
     * @return the full transform array, or {@code null} if unavailable
     *         完整变换数组，若不可用则返回 null
     */
    private static double[] getOrComputeSubTransform(Object subLevelObj) {
        if (subLevelObj == null) return null;
        // Use origin position as stable cache key (avoids identityHashCode collisions)
        // 使用原点坐标作为稳定缓存键（避免 identityHashCode 冲突）
        var pose = SableReflection.getLogicalPose(subLevelObj);
        if (pose == null) return null;
        double[] pos = SableReflection.extractPosition(pose);
        if (pos == null) return null;
        long posKey = subLevelId(pos[0], pos[1], pos[2]);
        // computeIfAbsent is atomic per key — safe for concurrent scanners.
        // computeIfAbsent 对每个键是原子的 —— 并发扫描安全。
        return subTransformCache.computeIfAbsent(posKey, k ->
            SableReflection.extractFullTransform(pose));
    }

    /**
     * Checks if a player can reach a block at the given position, accounting for
     * Sable sub-level coordinate mapping when the Sable mod is loaded.
     * 检查玩家能否到达指定位置的方块，当 Sable 加载时考虑子层级坐标映射。
     *
     * <p>When Sable is available, delegates to {@link io.github.y15173334444.create_schematic_compute.compat.SablePoseHelper}
     * which uses cached sub-level transforms — only iterates sub-levels on first
     * lookup per sub-level, then uses O(1) cached data.
     * 当 Sable 可用时，委托给 SablePoseHelper —— 仅在首次查找时遍历子层级，
     * 之后使用 O(1) 缓存数据。
     *
     * <p>When Sable is not loaded, falls back to a simple 2D (XZ) distance check
     * against the player's world-space coordinates.
     * 当 Sable 未加载时，回退到基于玩家世界空间坐标的简单二维 (XZ) 距离检查。
     *
     * @param sp        the server-side player / 服务端玩家
     * @param pos       the target block position / 目标方块位置
     * @param maxDistSq the maximum squared distance allowed / 允许的最大平方距离
     * @return true if within reachable range / 在可达范围内返回 true
     */
    public static boolean isWithinReachableRange(net.minecraft.server.level.ServerPlayer sp,
                                                  net.minecraft.core.BlockPos pos,
                                                  double maxDistSq) {
        // Use reflection-free helper when Sable is loaded (safe on server dist)
        // Sable 加载时使用免反射助手（在服务端分发环境安全）
        if (sableAvailable()) {
            return io.github.y15173334444.create_schematic_compute.compat.SablePoseHelper
                .isWithinReachableRange(sp, pos, maxDistSq);
        }
        // Fallback: plain world-coordinate check — fast, no Sable dependency.
        // 回退：纯世界坐标检查 —— 快速，不依赖 Sable。
        double dx = sp.getX() - pos.getX();
        double dz = sp.getZ() - pos.getZ();
        return dx * dx + dz * dz <= maxDistSq;
    }

    /**
     * Derives a deterministic long ID from a sub-level's world-space origin coordinates.
     * 从子层级的世界空间原点坐标派生一个确定性的长整型 ID。
     *
     * <p>Each coordinate is multiplied by 100 and truncated to long to preserve
     * centimeter-level precision, then XOR'd at staggered bit offsets (21 and 42 bits)
     * to distribute the hash uniformly. This is used as the cache key for sub-level
     * transforms and as a grouping identifier in scan results.
     * 每个坐标乘以 100 后截断为 long 以保留厘米级精度，然后以交错位移（21 位和 42 位）
     * 进行异或以均匀分布哈希值。此 ID 用作子层级变换的缓存键和扫描结果中的分组标识符。
     *
     * @param ox world-space origin X / 世界空间原点 X
     * @param oy world-space origin Y / 世界空间原点 Y
     * @param oz world-space origin Z / 世界空间原点 Z
     * @return a deterministic sub-level ID / 确定性子层级 ID
     */
    private static long subLevelId(double ox, double oy, double oz) {
        // Multiply to preserve sub-block precision; bit-shift XOR distributes entropy.
        // 乘 100 保留亚方块精度；位移异或分布熵值。
        long xi = (long) (ox * 100);
        long yi = (long) (oy * 100);
        long zi = (long) (oz * 100);
        return xi ^ (yi << 21) ^ (zi << 42);
    }
}
