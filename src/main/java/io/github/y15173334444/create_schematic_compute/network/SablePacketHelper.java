package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Shared Sable sub-level lookup and server-side device scanning. */
public class SablePacketHelper {

    private static Method sableGetContainerMethod, sableGetAllSubLevelsMethod,
                          sableLogicalPoseMethod, sableSubLevelGetLevelMethod,
                          sableGetPlotMethod;
    private static Method plotGetCenterBlockMethod, plotGetLoadedChunksMethod;
    private static Method plotChunkHolderGetChunkMethod;
    private static Method posePositionMethod, poseOrientationMethod, poseRotationPointMethod;
    private static Method vecXMethod, vecYMethod, vecZMethod;
    private static Method quatXMethod, quatYMethod, quatZMethod, quatWMethod;
    private static boolean initialized, sableAvailable;

    private static void init() {
        if (initialized) return;
        initialized = true;
        try {
            var c = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            sableGetContainerMethod = c.getMethod("getContainer", Level.class);
            sableGetAllSubLevelsMethod = c.getMethod("getAllSubLevels");
            var sl = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
            sableLogicalPoseMethod = sl.getMethod("logicalPose");
            sableSubLevelGetLevelMethod = sl.getMethod("getLevel");
            sableGetPlotMethod = sl.getMethod("getPlot");

            var plotClass = sableGetPlotMethod.getReturnType();
            plotGetCenterBlockMethod = plotClass.getMethod("getCenterBlock");
            plotGetLoadedChunksMethod = plotClass.getMethod("getLoadedChunks");

            var holderClass = Class.forName("dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder");
            plotChunkHolderGetChunkMethod = holderClass.getMethod("getChunk");

            var poseClass = sableLogicalPoseMethod.getReturnType();
            posePositionMethod = poseClass.getMethod("position");
            poseOrientationMethod = poseClass.getMethod("orientation");
            try { poseRotationPointMethod = poseClass.getMethod("rotationPoint"); }
            catch (NoSuchMethodException e) { poseRotationPointMethod = null; }

            var vecClass = posePositionMethod.getReturnType();
            vecXMethod = vecClass.getMethod("x");
            vecYMethod = vecClass.getMethod("y");
            vecZMethod = vecClass.getMethod("z");

            var quatClass = poseOrientationMethod.getReturnType();
            quatXMethod = quatClass.getMethod("x");
            quatYMethod = quatClass.getMethod("y");
            quatZMethod = quatClass.getMethod("z");
            quatWMethod = quatClass.getMethod("w");

            sableAvailable = true;
            SchematicCompute.LOGGER.info("SablePacketHelper: reflection OK");
        } catch (Exception e) {
            SchematicCompute.LOGGER.warn("SablePacketHelper: reflection init FAILED — {}", e.toString());
        }
    }

    public static Level findSubLevel(Level overworld, BlockPos pos) {
        init();
        if (!sableAvailable) return null;
        try {
            var cnt = sableGetContainerMethod.invoke(null, overworld);
            if (cnt == null) return null;
            var all = (List<?>) sableGetAllSubLevelsMethod.invoke(cnt);
            if (all == null) return null;
            for (var s : all) {
                Level sl = (Level) sableSubLevelGetLevelMethod.invoke(s);
                if (sl != null && sl.getBlockEntity(pos) != null) return sl;
            }
        } catch (Exception e) {
            SchematicCompute.LOGGER.warn("SablePacketHelper.findSubLevel failed: {}", e.toString());
        }
        return null;
    }

    public record SableDeviceEntry(BlockPos localPos, String name, String beClassName,
                                   float distance, long subLevelId) {}

    public static List<SableDeviceEntry> scanDevices(ServerLevel overworld, BlockPos playerPos, int scanRange) {
        init();
        if (!sableAvailable) return Collections.emptyList();

        List<SableDeviceEntry> results = new ArrayList<>();

        // ── Scan Sable sub-levels via LevelPlot.getLoadedChunks() ──
        int rangeSq = scanRange * scanRange;
        int subScanR = 128;
        try {
            var cnt = sableGetContainerMethod.invoke(null, (Level) overworld);
            if (cnt == null) return results;
            var all = (List<?>) sableGetAllSubLevelsMethod.invoke(cnt);
            if (all == null || all.isEmpty()) return results;

            for (var s : all) {
                // Use cached sub-level transform data (avoids ~20 reflection calls after first scan)
                // 使用缓存的子层级变换数据（首次扫描后避免约 20 次反射调用）
                double[] t = getOrComputeSubTransform(s);
                if (t == null) continue;
                double ox = t[0], oy = t[1], oz = t[2], rpx = t[3], rpy = t[4], rpz = t[5];
                double qx = t[6], qy = t[7], qz = t[8], qw = t[9];

                BlockPos origin = BlockPos.containing(ox, oy, oz);
                int margin = subScanR + 8;
                if (origin.distSqr(playerPos) > (long)(scanRange + margin) * (scanRange + margin)) continue;

                long subLevelId = subLevelId(ox, oy, oz);
                boolean hasRp = (rpx != 0 || rpy != 0 || rpz != 0);

                var plot = sableGetPlotMethod.invoke(s);
                if (plot == null) continue;
                var centerBlock = (BlockPos) plotGetCenterBlockMethod.invoke(plot);
                int cbx = centerBlock.getX(), cby = centerBlock.getY(), cbz = centerBlock.getZ();

                @SuppressWarnings("unchecked")
                var loaded = (Collection<?>) plotGetLoadedChunksMethod.invoke(plot);
                if (loaded == null) continue;

                org.joml.Quaterniond q = new org.joml.Quaterniond(qx, qy, qz, qw);
                int found = 0, scannedChunks = 0, scannedBEs = 0;
                for (var holder : loaded) {
                    if (holder == null) continue;
                    scannedChunks++;
                    Object chunkObj = plotChunkHolderGetChunkMethod.invoke(holder);
                    if (!(chunkObj instanceof net.minecraft.world.level.chunk.LevelChunk chunk)) continue;
                    for (var e : chunk.getBlockEntities().entrySet()) {
                        scannedBEs++;
                        BlockEntity be = e.getValue();
                        if (!isGraphBlockEntity(be)) continue;

                        BlockPos owPos = e.getKey();
                        double lx = owPos.getX() + 0.5;
                        double ly = owPos.getY() + 0.5;
                        double lz = owPos.getZ() + 0.5;

                        org.joml.Vector3d lo = hasRp
                            ? new org.joml.Vector3d(lx - rpx, ly - rpy, lz - rpz)
                            : new org.joml.Vector3d(lx, ly, lz);
                        q.transform(lo);

                        double wx = ox + lo.x, wy = oy + lo.y, wz = oz + lo.z;
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
                SchematicCompute.LOGGER.info("SablePacketHelper: sub-level at {} plotCtr=({},{},{}) chunks={} BEs={} graph={}",
                    origin, cbx, cby, cbz, scannedChunks, scannedBEs, found);
            }
        } catch (Exception e) {
            SchematicCompute.LOGGER.warn("SablePacketHelper.scanDevices(Sable) failed: {}", e.toString());
        }

        results.sort(Comparator.comparingDouble(SableDeviceEntry::distance));
        if (results.size() > 50) results = new ArrayList<>(results.subList(0, 50));
        SchematicCompute.LOGGER.info("SablePacketHelper.scanDevices: {} total device(s)", results.size());
        return results;
    }

    /** Check if a BE implements GraphBlockEntity using interface name comparison
     *  to avoid classloader issues when Sable's assembly copies BEs. */
    private static boolean isGraphBlockEntity(BlockEntity be) {
        // Fast path: direct instanceof works for same-classloader BEs
        if (be instanceof GraphBlockEntity) return true;
        // Slow path: check by interface name for cross-classloader BEs
        for (Class<?> iface : be.getClass().getInterfaces()) {
            if (iface.getName().equals("io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity"))
                return true;
        }
        // Also check superclass chain
        for (Class<?> c = be.getClass().getSuperclass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity"))
                return true;
            for (Class<?> iface : c.getInterfaces()) {
                if (iface.getName().equals("io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity"))
                    return true;
            }
            if (!c.getName().startsWith("io.github.y15173334444.create_schematic_compute")) break;
        }
        return false;
    }

    // ── Sub-level transform cache (subLevelId → cached world-space origin + quaternion) ──
    private static final java.util.Map<Long, double[]> subTransformCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static double[] getOrComputeSubTransform(Object subLevelObj) {
        if (subLevelObj == null) return null;
        long id = System.identityHashCode(subLevelObj);
        return subTransformCache.computeIfAbsent(id, k -> {
            try {
                var pose = sableLogicalPoseMethod.invoke(subLevelObj);
                if (pose == null) return null;
                var ov = posePositionMethod.invoke(pose);
                double ox = (double) vecXMethod.invoke(ov);
                double oy = (double) vecYMethod.invoke(ov);
                double oz = (double) vecZMethod.invoke(ov);
                double rpx = 0, rpy = 0, rpz = 0;
                if (poseRotationPointMethod != null) {
                    var rpVec = poseRotationPointMethod.invoke(pose);
                    if (rpVec != null) {
                        rpx = (double) vecXMethod.invoke(rpVec);
                        rpy = (double) vecYMethod.invoke(rpVec);
                        rpz = (double) vecZMethod.invoke(rpVec);
                    }
                }
                var oq = poseOrientationMethod.invoke(pose);
                return new double[]{ox, oy, oz, rpx, rpy, rpz,
                    (double) quatXMethod.invoke(oq), (double) quatYMethod.invoke(oq),
                    (double) quatZMethod.invoke(oq), (double) quatWMethod.invoke(oq)};
            } catch (Exception ignored) { return null; }
        });
    }

    /** Check if a player can reach a block at pos, accounting for Sable sub-level
     *  coordinate mapping. Uses cached sub-level transforms — only iterates sub-levels
     *  on first lookup per sub-level, then uses O(1) cached data.
     *  检查玩家能否到达指定坐标的方块。使用缓存的子层级变换 ——
     *  仅在首次查找时遍历子层级，之后使用O(1)缓存数据。
     *  @return true if within range / 在范围内返回true */
    public static boolean isWithinReachableRange(net.minecraft.server.level.ServerPlayer sp,
                                                  net.minecraft.core.BlockPos pos,
                                                  double maxDistSq) {
        // Plain world-coordinate check first (fast path, works for non-Sable)
        double dx = sp.getX() - pos.getX();
        double dz = sp.getZ() - pos.getZ();
        if (dx * dx + dz * dz <= maxDistSq) return true;

        // Sable sub-level coordinate mapping (with cached transforms)
        init();
        if (!sableAvailable) return false;
        try {
            var cnt = sableGetContainerMethod.invoke(null, (Level) sp.serverLevel());
            if (cnt == null) return false;
            var all = (List<?>) sableGetAllSubLevelsMethod.invoke(cnt);
            if (all == null) return false;
            for (var s : all) {
                Level sl = (Level) sableSubLevelGetLevelMethod.invoke(s);
                if (sl == null) continue;
                // Try direct lookup first (works for non-Sable and some Sable sub-level setups)
                // 先尝试直接查找（适用于非Sable和部分Sable子层级设置）
                if (sl.getBlockEntity(pos) != null) {
                    double[] t = getOrComputeSubTransform(s);
                    if (t != null && checkSableDistance(t, pos, sp, maxDistSq)) return true;
                    continue;
                }
                // Fallback: iterate loaded chunks to find BE by worldPosition match.
                // Sable sub-levels store BEs at overworld-local positions in the chunk map;
                // the BE's worldPosition (pos) may differ from the chunk key.
                // 回退：遍历已加载的chunk，通过 worldPosition 匹配查找 BE。
                // Sable 子层级将 BE 存储在 chunk 映射中的 overworld 本地坐标；
                // BE 的 worldPosition (pos) 可能与 chunk key 不同。
                var plot = sableGetPlotMethod.invoke(s);
                if (plot == null) continue;
                @SuppressWarnings("unchecked")
                var loaded = (Collection<?>) plotGetLoadedChunksMethod.invoke(plot);
                if (loaded == null) continue;
                boolean found = false;
                chunkLoop:
                for (var holder : loaded) {
                    if (holder == null) continue;
                    Object chunkObj = plotChunkHolderGetChunkMethod.invoke(holder);
                    if (!(chunkObj instanceof net.minecraft.world.level.chunk.LevelChunk chunk)) continue;
                    for (var e : chunk.getBlockEntities().entrySet()) {
                        if (e.getKey().equals(pos)) {
                            found = true; break chunkLoop;
                        }
                    }
                }
                if (found) {
                    double[] t = getOrComputeSubTransform(s);
                    if (t != null && checkSableDistance(t, pos, sp, maxDistSq)) return true;
                }
            }
        } catch (Exception e) {
            SchematicCompute.LOGGER.warn("SablePacketHelper.isWithinReachableRange failed: {}", e.toString());
        }
        return false;
    }

    /** Compute world-space distance from player to a Sable sub-level block and check range.
     *  t[0..9] = ox, oy, oz, rpx, rpy, rpz, qx, qy, qz, qw */
    private static boolean checkSableDistance(double[] t, net.minecraft.core.BlockPos pos,
                                               net.minecraft.server.level.ServerPlayer sp,
                                               double maxDistSq) {
        double ox = t[0], oy = t[1], oz = t[2], rpx = t[3], rpy = t[4], rpz = t[5];
        var q = new org.joml.Quaterniond(t[6], t[7], t[8], t[9]);
        double bx = pos.getX() + 0.5, by = pos.getY() + 0.5, bz = pos.getZ() + 0.5;
        org.joml.Vector3d lo = (rpx != 0 || rpy != 0 || rpz != 0)
            ? new org.joml.Vector3d(bx - rpx, by - rpy, bz - rpz)
            : new org.joml.Vector3d(bx, by, bz);
        q.transform(lo);
        double sdx = sp.getX() - (ox + lo.x);
        double sdz = sp.getZ() - (oz + lo.z);
        return sdx * sdx + sdz * sdz <= maxDistSq;
    }

    private static long subLevelId(double ox, double oy, double oz) {
        long xi = (long) (ox * 100);
        long yi = (long) (oy * 100);
        long zi = (long) (oz * 100);
        return xi ^ (yi << 21) ^ (zi << 42);
    }
}
