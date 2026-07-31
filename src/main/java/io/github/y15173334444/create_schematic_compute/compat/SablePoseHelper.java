package io.github.y15173334444.create_schematic_compute.compat;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Sable 子世界姿态提取工具 / Sable sub-level pose extraction helper.
 * <p>
 * 提供 Sable 模组子世界的姿态解析、子世界定位、方块实体查找和可达性检测等通用方法，
 * 避免在多个 BlockEntity 中重复实现相同逻辑。
 * <p>
 * Provides common utilities for Sable sub-level pose extraction, sub-level
 * resolution, BlockEntity lookup, and reachability checks, so that the same
 * logic does not need to be duplicated across multiple BlockEntities.
 */
public class SablePoseHelper {

    /**
     * 从 ServerSubLevel 的 logicalPose 中提取 YXZ 欧拉角（度）。
     * Extract YXZ Euler angles (in degrees) from the sub-level's logicalPose.
     *
     * @param subLevel Sable 服务端子世界 / the Sable server sub-level
     * @return float[3] = { yaw, pitch, roll }，均为角度制 / all in degrees；
     *         若姿势无效或解析失败则返回全零 / returns all zeros if pose is invalid or parsing fails
     */
    public static float[] getSubPose(ServerSubLevel subLevel) {
        float[] r = new float[3];
        try {
            var pose = subLevel.logicalPose();
            if (pose != null) {
                var oq = pose.orientation();
                if (oq != null) {
                    // Sable stores orientation as a quaternion; convert to YXZ Euler angles
                    // Sable 以四元数存储朝向，此处转换为 YXZ 欧拉角便于下游使用
                    org.joml.Quaterniond q = new org.joml.Quaterniond(oq.x(), oq.y(), oq.z(), oq.w());
                    org.joml.Vector3d euler = new org.joml.Vector3d();
                    q.getEulerAnglesYXZ(euler);
                    // JOML getEulerAnglesYXZ stores: x=pitch, y=yaw, z=roll
                    // JOML getEulerAnglesYXZ 的存储顺序为: x=pitch, y=yaw, z=roll
                    r[0] = (float) Math.toDegrees(euler.y); // yaw / 偏航
                    r[1] = (float) Math.toDegrees(euler.x); // pitch / 俯仰
                    r[2] = (float) Math.toDegrees(euler.z); // roll / 翻滚
                }
            }
        } catch (Exception ignored) {} // 防御性静默：pose 数据可能因模组状态不一致而缺失 / silently tolerate missing pose data from inconsistent mod state
        return r;
    }

    /**
     * 通过 chunk 查找定位 BE 所在的 Sable SubLevel。
     * Resolve the Sable SubLevel that owns the given block position.
     * <p>
     * 避免遍历所有子世界 —— 直接使用 ChunkPos → Plot → SubLevel 映射，效率更高。
     * Avoids iterating all sub-levels by using the ChunkPos → Plot → SubLevel
     * mapping, which is more efficient.
     *
     * @param level         目标世界 / the target level (must be server-side / 必须是服务端)
     * @param worldPosition 方块坐标 / the block position to look up
     * @return 该方块所在的 SubLevel，若不在 Sable 子层级中则返回 null
     *         the SubLevel containing the block, or null if not in a Sable sub-level
     */
    public static SubLevel resolveSubLevel(Level level, BlockPos worldPosition) {
        // 客户端世界没有 Sable 子层级数据，直接返回 / client-side levels have no Sable sub-level data, bail early
        if (level == null || level.isClientSide()) return null;
        // 内部守卫：NoClassDefFoundError 是 Error 而非 Exception，catch(Exception) 抓不住；
        // 必须在触碰任何 Sable 类前检查。防御未来未加守卫的调用方。
        // Internal guard: NoClassDefFoundError is an Error, not caught by catch(Exception);
        // check before touching any Sable class. Defense against future unguarded callers.
        if (!net.neoforged.fml.ModList.get().isLoaded("sable")) return null;
        try {
            var container = SubLevelContainer.getContainer(level);
            if (container == null) return null;
            // ChunkPos → Plot 映射是 Sable 内部最直接的定位方式，无需遍历所有子层级
            // ChunkPos → Plot is the most direct lookup path inside Sable; no need to iterate all sub-levels
            var cp = new net.minecraft.world.level.ChunkPos(worldPosition);
            var plot = container.getPlot(cp);
            return plot != null ? plot.getSubLevel() : null;
        } catch (Exception ignored) { return null; }
    }

    /**
     * Enumerate the world-space origin [x,y,z] of every loaded Sable sub-level.
     * Used by {@code RadarBlockEntity} structure scanning. Compile-time Sable
     * access — dedicated-server safe (only the Level overload of getContainer is
     * resolved, never the ClientLevel one). Returns empty list when Sable absent.
     * <p>
     * 枚举所有已加载 Sable 子关卡的世界空间原点 [x,y,z]。
     * 供 RadarBlockEntity 结构扫描使用。编译期 Sable 访问——专用服务器安全
     * （只解析 getContainer 的 Level 重载，绝不触碰 ClientLevel 重载）。
     * Sable 未安装时返回空列表。
     *
     * @param level 目标世界（服务端）/ the target level (server-side)
     * @return 世界空间原点列表，空当 Sable 未加载 / world-space origins, empty if Sable absent
     */
    public static java.util.List<double[]> getAllSubLevelOrigins(Level level) {
        if (level == null || level.isClientSide()) return java.util.List.of();
        if (!net.neoforged.fml.ModList.get().isLoaded("sable")) return java.util.List.of();
        try {
            var container = SubLevelContainer.getContainer(level);
            if (container == null) return java.util.List.of();
            var out = new java.util.ArrayList<double[]>();
            for (var subLevel : container.getAllSubLevels()) {
                var pose = subLevel.logicalPose();
                if (pose == null) continue;
                var pos = pose.position();
                if (pos == null) continue;
                out.add(new double[]{pos.x(), pos.y(), pos.z()});
            }
            return out;
        } catch (Exception ignored) { return java.util.List.of(); }
    }

    /**
     * Extract {@code [ox,oy,oz,qx,qy,qz,qw]} of the sub-level containing {@code pos},
     * or {@code null} if the block is not inside a Sable sub-level. Compile-time
     * Sable access — dedicated-server safe. Returns null when Sable absent.
     * <p>
     * 提取包含 {@code pos} 的子关卡的世界原点 + 朝向四元数
     * {@code [ox,oy,oz,qx,qy,qz,qw]}，方块不在 Sable 子关卡内或 Sable 未加载
     * 时返回 {@code null}。编译期 Sable 访问——专用服务器安全。
     *
     * @param level         目标世界（服务端）/ the target level (server-side)
     * @param worldPosition 方块坐标 / the block position to look up
     * @return 7 元素数组或 null / 7-element array or null
     */
    public static double[] getContainingSubLevelTransform(Level level, BlockPos worldPosition) {
        if (level == null || level.isClientSide()) return null;
        if (!net.neoforged.fml.ModList.get().isLoaded("sable")) return null;
        try {
            var subLevel = resolveSubLevel(level, worldPosition);
            if (subLevel == null) return null;
            var pose = subLevel.logicalPose();
            if (pose == null) return null;
            var p = pose.position();
            if (p == null) return null;
            var oq = pose.orientation();
            return new double[]{
                p.x(), p.y(), p.z(),
                oq != null ? oq.x() : 0, oq != null ? oq.y() : 0,
                oq != null ? oq.z() : 0, oq != null ? oq.w() : 1
            };
        } catch (Exception ignored) { return null; }
    }

    /**
     * Extract the orientation quaternion of the sub-level containing {@code pos},
     * or {@code null} if the block is not inside a Sable sub-level (or Sable absent).
     * Compile-time Sable access — dedicated-server safe.
     * <p>
     * 提取包含 {@code pos} 的子关卡朝向四元数。方块不在 Sable 子关卡内或 Sable
     * 未加载时返回 {@code null}。编译期 Sable 访问——专用服务器安全。
     *
     * @param level         目标世界（服务端）/ the target level (server-side)
     * @param worldPosition 方块坐标 / the block position to look up
     * @return 朝向四元数或 null / orientation quaternion or null
     */
    public static org.joml.Quaterniond getSubLevelOrientationQuaternion(Level level, BlockPos worldPosition) {
        if (level == null || level.isClientSide()) return null;
        if (!net.neoforged.fml.ModList.get().isLoaded("sable")) return null;
        try {
            var subLevel = resolveSubLevel(level, worldPosition);
            if (subLevel == null) return null;
            // 成员检查：ChunkPos→Plot 映射只保证坐标落在 plot 的 chunk 覆盖内，不代表该
            // 方块确实属于这个结构（如大型结构下方/内部的地面方块）。仅当该子关卡的
            // level 中确实存在此方块实体时，才应用其姿态——与旧反射路径的
            // sl.getBlockEntity(worldPosition) != null 语义一致。
            // Membership check: ChunkPos→Plot only means the block is inside the plot's
            // chunk footprint, not that it's part of the structure (e.g. overworld terrain
            // under/inside a large structure). Only apply the pose when the sub-level's
            // level actually holds a block entity at worldPosition — matches the old
            // reflection path's sl.getBlockEntity(worldPosition) != null semantics.
            Level sl = subLevel.getLevel();
            if (sl == null || sl.getBlockEntity(worldPosition) == null) return null;
            var pose = subLevel.logicalPose();
            if (pose == null) return null;
            var oq = pose.orientation();
            if (oq == null) return null;
            return new org.joml.Quaterniond(oq.x(), oq.y(), oq.z(), oq.w());
        } catch (Exception ignored) { return null; }
    }

    /**
     * Sable-aware BlockEntity 查找 / Sable-aware BlockEntity lookup.
     * <p>
     * 先在给定 level 中查找，再回退到遍历 Sable 子层级。
     * Tries the given level first, then falls back to searching all Sable sub-levels.
     * <p>
     * 两阶段策略：大部分方块在主世界可直接命中，子层级遍历仅在必要时触发。
     * Two-phase strategy: most blocks are in the overworld and hit on the first
     * attempt; sub-level iteration is only triggered when necessary.
     *
     * @param overworld 主世界 level / the overworld (or any server-side) level
     * @param pos       目标方块坐标 / the target block position
     * @return 找到的 BlockEntity，未找到则返回 null
     *         the BlockEntity if found, null otherwise
     */
    public static BlockEntity findBlockEntity(Level overworld, BlockPos pos) {
        // 1. 快速路径：主世界直接查找，多数情况下一步命中
        //    Fast path: direct lookup in the given level — succeeds for most blocks
        var be = overworld.getBlockEntity(pos);
        if (be != null) return be;

        // 2. Sable 子层级回退：仅在 Sable 加载且主世界未命中时执行
        //    Fallback to Sable sub-levels — only when Sable is loaded and the fast path missed
        if (!net.neoforged.fml.ModList.get().isLoaded("sable")) return null;
        try {
            var container = SubLevelContainer.getContainer(overworld);
            if (container == null) return null;
            // 遍历全部子层级查找同一坐标处的 BE
            // Iterate all sub-levels to find a BE at the same coordinates
            for (var subLevel : container.getAllSubLevels()) {
                Level sl = subLevel.getLevel();
                if (sl == null) continue;
                be = sl.getBlockEntity(pos);
                if (be != null) return be;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 检查玩家是否可以到达 Sable 子层级中的方块（直接 API，无反射）。
     * Check whether the player is within reachable range of a block that may
     * reside in a Sable sub-level (uses direct API, no reflection).
     * <p>
     * 先尝试 overworld 直线距离快速判定，失败后遍历子层级，将方块坐标通过
     * 子层级的逻辑姿态变换到世界空间后再比较距离。
     * <p>
     * Fast-path: check straight-line distance in the overworld plane. On miss,
     * iterate sub-levels, transforming the block position into world-space via
     * the sub-level's logical pose before computing the distance.
     *
     * @param sp        目标玩家 / the target server player
     * @param pos       目标方块坐标 / the target block position
     * @param maxDistSq 最大允许的平方距离 / the maximum squared distance allowed
     * @return true 如果玩家在可达范围内 / true if the player is within reachable range
     */
    public static boolean isWithinReachableRange(ServerPlayer sp, BlockPos pos, double maxDistSq) {
        // 快速路径：主世界平面距离检查，省去子层级变换开销
        // Fast path: check distance on the overworld plane to avoid sub-level transform overhead
        double dx = sp.getX() - pos.getX();
        double dz = sp.getZ() - pos.getZ();
        if (dx * dx + dz * dz <= maxDistSq) return true;

        // Sable 路径：方块可能在某个子层级中，需要通过子层级的逻辑姿态
        // （位置 + 朝向 + 旋转中心）将局部坐标变换到世界空间后再计算距离
        // Sable path: the block may be inside a sub-level; transform its local
        // coordinates to world-space via the sub-level's logicalPose
        // (position + orientation + rotation point) before computing distance
        // 内部守卫：NoClassDefFoundError 是 Error 而非 Exception，须在触碰 Sable 类前检查。
        // Internal guard: NoClassDefFoundError is an Error; check before touching Sable classes.
        if (!net.neoforged.fml.ModList.get().isLoaded("sable")) return false;
        try {
            var container = SubLevelContainer.getContainer(sp.serverLevel());
            if (container == null) return false;
            for (var subLevel : container.getAllSubLevels()) {
                Level sl = subLevel.getLevel();
                if (sl == null) continue;
                // 仅处理确实包含该方块坐标的子层级
                // Only process sub-levels that actually contain a BE at this position
                if (sl.getBlockEntity(pos) == null) continue;

                // 获取子层级变换参数：原点、朝向四元数、旋转中心
                // Retrieve sub-level transform parameters: origin, orientation quaternion, rotation point
                var pose = subLevel.logicalPose();
                var origin = pose.position();
                var orient = pose.orientation();
                var rp = pose.rotationPoint();

                // 方块中心坐标（Minecraft 方块坐标 + 0.5 即方块中心）
                // Block center coordinates (Minecraft block coords + 0.5 = block center)
                double bx = pos.getX() + 0.5, by = pos.getY() + 0.5, bz = pos.getZ() + 0.5;
                org.joml.Vector3d localOffset;
                if (rp != null) {
                    // 有旋转中心时，先减去旋转中心得到相对于旋转中心的偏移
                    // When a rotation point is defined, subtract it first to get the offset relative to the rotation pivot
                    localOffset = new org.joml.Vector3d(bx - rp.x(), by - rp.y(), bz - rp.z());
                } else {
                    // 无旋转中心时直接使用方块中心坐标
                    // No rotation point → use block center coordinates directly
                    localOffset = new org.joml.Vector3d(bx, by, bz);
                }
                // 用子层级朝向四元数旋转变换局部偏移量
                // Rotate the local offset by the sub-level's orientation quaternion
                var q = new org.joml.Quaterniond(orient.x(), orient.y(), orient.z(), orient.w());
                q.transform(localOffset);

                // 世界空间坐标 = 子层级原点 + 旋转后的局部偏移
                // World-space position = sub-level origin + rotated local offset
                double wx = origin.x() + localOffset.x;
                double wz = origin.z() + localOffset.z;
                double sdx = sp.getX() - wx;
                double sdz = sp.getZ() - wz;
                if (sdx * sdx + sdz * sdz <= maxDistSq) return true;
            }
        } catch (Exception ignored) {} // 防御性静默：子层级数据在切换维度时可能短暂不可用 / silently tolerate transient sub-level data unavailability during dimension switches
        return false;
    }
}
