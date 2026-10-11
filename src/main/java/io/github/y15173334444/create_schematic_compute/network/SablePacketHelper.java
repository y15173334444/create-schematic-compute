package io.github.y15173334444.create_schematic_compute.network;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity;
import io.github.y15173334444.create_schematic_compute.compat.SableAccess;
import io.github.y15173334444.create_schematic_compute.compat.SablePoseHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Shared Sable sub-level lookup and server-side device scanning.
 * 共享的 Sable 子层级查询与服务端设备扫描工具类。
 *
 * <p>All Sable access is <b>compile-time direct</b> ({@link SableAccess} container bridge +
 * direct API calls), following the validated dedicated-server-safe pattern: only the
 * {@code getContainer(Level)} overload is ever resolved, never the
 * {@code getContainer(ClientLevel)} one, so NeoForge's RuntimeDistCleaner is never tripped.
 * P2-19: the old 558-line {@code SableReflection} layer is retired — its javadoc premise
 * ("compiles without Sable on the classpath") no longer holds since every other compat
 * class imports {@code dev.ryanhcode.sable.*} directly and all of {@code libs/} rides
 * {@code compileOnly}; its four-phase reflective fallback had no remaining consumer.
 * 将 Sable 访问全部走<b>编译期直连</b>（{@link SableAccess} 容器桥 + 直接 API 调用），
 * 沿用已验证的专用服务器安全模式：只解析实际调用的 {@code getContainer(Level)} 重载，
 * 绝不触碰 {@code getContainer(ClientLevel)}，故不会触发 NeoForge RuntimeDistCleaner。
 * P2-19：旧 558 行 {@code SableReflection} 反射层整体退役——其 javadoc 前提
 * （"编译期 classpath 无 Sable 也能编译"）在其他 compat 类全部直接 import
 * {@code dev.ryanhcode.sable.*}、libs/ 全挂 {@code compileOnly} 后已不成立；
 * 四阶段反射降级语义也已无消费者。
 *
 * <p>Guard discipline (fail-soft without Sable): every public entry point checks the
 * cheap null args first, then {@code ModList.isLoaded("sable")}, and only then touches
 * any Sable class. NoClassDefFoundError is an {@link Error} — {@code catch(Exception)}
 * cannot intercept it, so the guards must <i>prevent</i> Sable class-loading rather than
 * catch it (see SableAbsenceTest, which loads these classes under a Sable-less loader).
 * 守卫纪律（无 Sable 时 fail-soft）：每个公共入口先做空参检查，再查
 * {@code ModList.isLoaded("sable")}，最后才触碰任何 Sable 类。NoClassDefFoundError 是
 * {@link Error}——{@code catch(Exception)} 拦不住，所以守卫必须<b>预防</b>Sable 类加载
 * 而非事后捕获（见 SableAbsenceTest：在屏蔽 Sable 的类加载器下加载本类并调用守卫路径）。
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
        // 守卫顺序 = compat 家族统一模式（SableAccess/SablePoseHelper 同款）：
        // 先廉价空参检查，再 ModList 查询，最后才可能触碰 Sable 类。
        // Guard order = the compat-family pattern (as in SableAccess/SablePoseHelper):
        // cheap null checks first, then the ModList query, and only then any Sable class.
        if (overworld == null) return null;
        if (!sableAvailable()) return null;
        try {
            // Compile-time Sable access (dedicated-server safe). resolveSubLevel uses
            // the ChunkPos→Plot→SubLevel mapping — O(1) vs. the old full iteration.
            // 编译期 Sable 访问（专用服务器安全）。resolveSubLevel 使用
            // ChunkPos→Plot→SubLevel 映射——O(1)，优于旧的完整遍历。
            var sub = SablePoseHelper.resolveSubLevel(overworld, pos);
            if (sub != null) {
                // 存在性检查改用 findSubLevelBlockEntity（含 chunk 枚举回退，回归审计 #7）
                // Existence check via findSubLevelBlockEntity (with chunk fallback, audit #7)
                if (SablePoseHelper.findSubLevelBlockEntity(sub, pos) != null) {
                    return sub.getLevel();
                }
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
     *   <li>Iterate every sub-level; for each, read its world-space
     *       transform (origin + quaternion rotation + optional pivot point) live.</li>
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
        // 守卫顺序同 findSubLevel：空参 → ModList → 才触碰 Sable 类（无 Sable 时空列表，不崩）。
        // Guard order as in findSubLevel: nulls → ModList → only then Sable classes
        // (empty list without Sable, never a crash).
        if (overworld == null || playerPos == null) return Collections.emptyList();
        if (!sableAvailable()) return Collections.emptyList();

        List<SableDeviceEntry> results = new ArrayList<>();

        // Pre-compute squared range to avoid sqrt in the hot loop.
        // 预计算平方距离，避免热循环中反复开方。
        int rangeSq = scanRange * scanRange;
        // Sub-level internal scan radius — generous to cover edge cases.
        // 子层级内部扫描半径 —— 设置较大值以覆盖边界情况。
        int subScanR = 128;
        try {
            // 编译期直连（专用服务器安全）：SableAccess 只解析 getContainer(Level) 重载。
            // Compile-time direct access (dedicated-server safe): SableAccess only ever
            // resolves the getContainer(Level) overload.
            var container = SableAccess.getContainer(overworld);
            if (container == null) return results;
            var all = SableAccess.getAllSubLevels(container);
            if (all.isEmpty()) return results;

            for (SubLevel s : all) {
                // 每次扫描实时读取变换（P2-20）：旧的位置键缓存会在结构原地旋转后给出陈旧
                // 朝向且条目永不回收；扫描为玩家触发（ScanSablePacket）、提取仅为四次直接
                // getter，缓存早已不值得。
                // Live transform per scan (P2-20): the old position-keyed cache served a
                // stale orientation after in-place rotations and never evicted; scans are
                // player-triggered (ScanSablePacket) and extraction is four direct getters,
                // so caching no longer pays for itself.
                double[] t = extractSubTransform(s);
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

                LevelPlot plot = s.getPlot();
                if (plot == null) continue;
                BlockPos centerBlock = plot.getCenterBlock();
                int cbx = centerBlock != null ? centerBlock.getX() : 0;
                int cby = centerBlock != null ? centerBlock.getY() : 0;
                int cbz = centerBlock != null ? centerBlock.getZ() : 0;

                Collection<PlotChunkHolder> loaded = plot.getLoadedChunks();
                if (loaded == null) continue;

                // Build quaternion once per sub-level — reused for every BE position transform.
                // 每个子层级构建一次四元数 —— 所有方块实体位置变换复用。
                org.joml.Quaterniond q = new org.joml.Quaterniond(qx, qy, qz, qw);
                int found = 0, scannedChunks = 0, scannedBEs = 0;
                for (PlotChunkHolder holder : loaded) {
                    if (holder == null) continue;
                    scannedChunks++;
                    // PlotChunkHolder.getChunk() 协变返回 LevelChunk（未就绪时可为 null）。
                    // PlotChunkHolder.getChunk() covariantly returns LevelChunk (null while not ready).
                    net.minecraft.world.level.chunk.LevelChunk chunk = holder.getChunk();
                    if (chunk == null) continue;
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
                            deviceDisplayName(be),
                            be.getClass().getSimpleName(), (float)Math.sqrt(ds), subLevelId));
                        found++;
                    }
                }
                // Diagnostic log — helps debug scan coverage in production.
                // P1-3：每包两条 INFO 会随反复扫描刷盘，降 DEBUG（诊断价值保留）。
                // Diagnostic log — helps debug scan coverage in production.
                // P1-3: two INFOs per packet flood the log under repeated scans; downgraded to
                // DEBUG (diagnostic value kept).
                SchematicCompute.LOGGER.debug("SablePacketHelper: sub-level at {} plotCtr=({},{},{}) chunks={} BEs={} graph={}",
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
        // P1-3：同上，每次扫描一条 INFO → DEBUG。/ P1-3: same as above, one INFO per scan → DEBUG.
        SchematicCompute.LOGGER.debug("SablePacketHelper.scanDevices: {} total device(s)", results.size());
        return results;
    }

    /** 设备显示名的拼装规则（两条取名路径共用，保持格式一致）：
     *  类型名始终可见 —— 其他玩家据此知道设备原本是什么方块；有自定义名时跟在后面。
     *  Display-name assembly shared by BOTH naming paths so the format stays
     *  consistent: the block type name stays visible (other players can tell what
     *  the device originally was), with the custom name following it when present. */
    public static String formatDeviceDisplay(String typeName, String customName) {
        if (customName == null || customName.isEmpty()) return typeName;
        return typeName + " · " + customName;
    }

    /** 设备显示名：类型名恒显示，自定义名（graph.customName，编辑器顶栏设置）跟在后面。
     *  必须与客户端本地扫描（PortableTerminalScreen.scanNearbyBlocks）的取名逻辑一致，
     *  否则两条路径产出的列表对不上、搜索行为不一致。
     *  Device display name: the block type name always shows, followed by the custom
     *  name (graph.customName, set in the editor top bar) when present. Must mirror
     *  the client-side local scan (PortableTerminalScreen.scanNearbyBlocks), or the
     *  two paths produce lists that disagree and search behaves inconsistently. */
    private static String deviceDisplayName(BlockEntity be) {
        String type = be.getBlockState().getBlock().getName().getString();
        return formatDeviceDisplay(type, resolveCustomName(be));
    }

    /** 跨类加载器安全的自定义名读取（详见 {@link #findGraphInterface(Class)} 的背景）。
     *  快路径：同加载器 instanceof（本地扫描 / 普通方块）。
     *  慢路径：Sable 拷贝 —— 接口同 FQN 不同 Class，instanceof 恒 false，此处按
     *  {@link #isGraphBlockEntity} 相同的 FQN 遍历定位接口后反射调用 getCustomName；
     *  默认方法执行在拷贝自己的加载器里，返回的 String 全局共享，值可正常互通。
     *  Cross-classloader-safe custom-name read. Fast path: same-loader instanceof
     *  (local scan / ordinary blocks). Slow path: Sable copies — the interface has the
     *  same FQN but is a different Class, so instanceof is always false; locate the
     *  interface via the same FQN walk {@link #isGraphBlockEntity} uses and invoke
     *  getCustomName reflectively. The default method runs in the copy's own loader
     *  and the returned String is globally shared, so the value crosses safely. */
    static String resolveCustomName(Object be) {
        if (be instanceof GraphBlockEntity gbe) {
            String custom = gbe.getCustomName();
            return custom != null ? custom : "";
        }
        Class<?> iface = findGraphInterface(be.getClass());
        if (iface != null) {
            try {
                Object r = iface.getMethod("getCustomName").invoke(be);
                return r != null ? r.toString() : "";
            } catch (Exception e) {
                // 反射失败 → 视为无名，调用方回退到类型名（与旧行为一致，不中断扫描）。
                // Reflection failure → treat as unnamed; the caller falls back to the
                // type name (old behaviour), the scan is never interrupted.
                SchematicCompute.LOGGER.warn("SablePacketHelper.resolveCustomName reflective read failed: {}", e.toString());
            }
        }
        return "";
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
        return findGraphInterface(be.getClass()) != null;
    }

    /** GraphBlockEntity 接口的 FQN（跨类加载器匹配只认字符串，Class 对象不可比）。 */
    private static final String GRAPH_BE_FQN =
        "io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity";

    /**
     * 在类自身接口与父类链上定位 FQN 匹配的 GraphBlockEntity 接口（跨类加载器场景下
     * 返回的是<b>另一份</b> Class 对象），未找到返回 null。遍历顺序与包前缀剪枝与
     * 原有慢速路径逐行一致。
     * Locates the FQN-matching GraphBlockEntity interface across the class's own
     * interfaces and the superclass chain (under cross-classloading the returned
     * Class is the <b>other</b> copy), or null. Walk order and package-prefix pruning
     * are identical to the original slow path.
     */
    private static Class<?> findGraphInterface(Class<?> cls) {
        for (Class<?> iface : cls.getInterfaces()) {
            if (iface.getName().equals(GRAPH_BE_FQN)) return iface;
        }
        // Also check superclass chain — the BE could extend a class that implements the interface.
        // 同时检查父类链 —— BE 可能继承了实现该接口的父类。
        for (Class<?> c = cls.getSuperclass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(GRAPH_BE_FQN)) return c;
            for (Class<?> iface : c.getInterfaces()) {
                if (iface.getName().equals(GRAPH_BE_FQN)) return iface;
            }
            // Stop traversing once we leave the mod's package — no point scanning to java.lang.Object.
            // 离开本模组包名前缀后停止向上遍历 —— 没必要扫描到 java.lang.Object。
            if (!c.getName().startsWith("io.github.y15173334444.create_schematic_compute")) break;
        }
        return null;
    }

    /**
     * Reads a sub-level's world-space transform from its logical pose — live, no cache.
     * 从子关卡的 logicalPose 实时读取世界空间变换 —— 不做缓存。
     *
     * <p>P2-20: the previous {@code subTransformCache} keyed entries by the sub-level's
     * <i>origin coordinates</i> and never invalidated. A structure rotating in place keeps
     * its origin while its quaternion changes, so terminal scans kept using the stale
     * orientation, and entries accumulated forever (no remove/clear anywhere). Caching
     * existed to dodge ~20 reflective invokes per sub-level; with compile-time direct
     * access the extraction is four plain getters and scans are player-triggered
     * (ScanSablePacket) — the cache cost more than it saved, so it is gone. Failures
     * degrade per sub-level (null → skipped), matching the old reflective granularity.
     * P2-20：旧 {@code subTransformCache} 以子关卡<b>原点坐标</b>为键且永不失效。结构原地
     * 旋转时原点不变、四元数已变 —— 终端扫描持续拿到陈旧朝向，且条目全仓无 remove/clear
     * 永久累积。缓存的本意是省去每子关卡约 20 次反射 invoke；改编译期直连后提取只是四个
     * 普通 getter，且扫描由玩家触发（ScanSablePacket）—— 缓存得不偿失，故删除。失败按
     * 子关卡粒度降级（null → 跳过该子关卡），与旧反射路径的粒度一致。
     *
     * @param subLevel the Sable sub-level / Sable 子关卡
     * @return {@code [ox,oy,oz,rpx,rpy,rpz,qx,qy,qz,qw]}，不可用时返回 {@code null}
     *         the 10-element transform array, or {@code null} if unavailable
     * @see #extractPoseTransform(Pose3dc) 数组布局说明 / array layout
     */
    private static double[] extractSubTransform(SubLevel subLevel) {
        if (subLevel == null) return null;
        try {
            // 逐子关卡 try/catch：单个子关卡姿态异常只跳过它自己，不中断整轮扫描
            // （与旧反射访问器逐调用 catch 的降级粒度一致）。
            // Per-sub-level try/catch: one bad pose skips only that sub-level, never the
            // whole scan (same degradation granularity as the old per-call reflective accessors).
            return extractPoseTransform(subLevel.logicalPose());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Pure transform extraction from a {@link Pose3dc} — the testable core of
     * {@link #extractSubTransform(SubLevel)}.
     * 从 {@link Pose3dc} 提取变换的纯逻辑部分 —— {@link #extractSubTransform(SubLevel)} 的可测内核。
     *
     * <p>Return layout: {@code [ox,oy,oz,rpx,rpy,rpz,qx,qy,qz,qw]}; a null rotation
     * point falls back to {0,0,0} (older Sable versions had no pivot — parity with the
     * retired {@code SableReflection.extractFullTransform}); null position or orientation
     * yields null.
     * 返回布局：{@code [ox,oy,oz,rpx,rpy,rpz,qx,qy,qz,qw]}；旋转中心为 null 时回退
     * {0,0,0}（旧版 Sable 无旋转中心 —— 与已退役的 {@code SableReflection.extractFullTransform}
     * 语义一致）；position/orientation 为 null 则整体返回 null。
     *
     * @param pose the logical pose / 逻辑位姿
     * @return the 10-element transform array, or {@code null} / 10 元素变换数组，或 null
     */
    static double[] extractPoseTransform(Pose3dc pose) {
        if (pose == null) return null;
        try {
            Vector3dc pos = pose.position();
            if (pos == null) return null;
            Quaterniondc orient = pose.orientation();
            if (orient == null) return null;
            // 旋转中心可能缺失（旧版 Sable）——回退零向量，保证变换仍可用
            // The rotation point may be absent (older Sable) — fall back to zeros so the
            // transform remains usable.
            Vector3dc rp = pose.rotationPoint();
            return new double[]{
                pos.x(), pos.y(), pos.z(),
                rp != null ? rp.x() : 0, rp != null ? rp.y() : 0, rp != null ? rp.z() : 0,
                orient.x(), orient.y(), orient.z(), orient.w()
            };
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Checks if a player can reach a block at the given position, accounting for
     * Sable sub-level coordinate mapping when the Sable mod is loaded.
     * 检查玩家能否到达指定位置的方块，当 Sable 加载时考虑子层级坐标映射。
     *
     * <p>When Sable is available, delegates to {@link SablePoseHelper}
     * which reads sub-level transforms via the compile-time direct API.
     * 当 Sable 可用时，委托给 SablePoseHelper —— 经编译期直连 API 读取子层级变换。
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
        // 守卫顺序同上：空参 → ModList → 才触碰 Sable 类。
        // Guard order as above: nulls → ModList → only then Sable classes.
        if (sp == null || pos == null) return false;
        if (sableAvailable()) {
            return SablePoseHelper.isWithinReachableRange(sp, pos, maxDistSq);
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
     * to distribute the hash uniformly. Used as the grouping identifier in scan results
     * （P2-20 后不再是变换缓存键 —— 缓存已删）.
     * 每个坐标乘以 100 后截断为 long 以保留厘米级精度，然后以交错位移（21 位和 42 位）
     * 进行异或以均匀分布哈希值。用作扫描结果中的分组标识符（P2-20 后不再是变换缓存键
     * —— 缓存已删）。
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
