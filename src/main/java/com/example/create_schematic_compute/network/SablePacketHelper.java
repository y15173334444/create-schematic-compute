package com.example.create_schematic_compute.network;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.blocks.GraphBlockEntity;
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
                var pose = sableLogicalPoseMethod.invoke(s);
                if (pose == null) continue;
                var originVec = posePositionMethod.invoke(pose);
                double ox = (double) vecXMethod.invoke(originVec);
                double oy = (double) vecYMethod.invoke(originVec);
                double oz = (double) vecZMethod.invoke(originVec);
                BlockPos origin = BlockPos.containing(ox, oy, oz);

                int margin = subScanR + 8;
                if (origin.distSqr(playerPos) > (long)(scanRange + margin) * (scanRange + margin)) continue;

                var orientQuat = poseOrientationMethod.invoke(pose);
                double qx = (double) quatXMethod.invoke(orientQuat);
                double qy = (double) quatYMethod.invoke(orientQuat);
                double qz = (double) quatZMethod.invoke(orientQuat);
                double qw = (double) quatWMethod.invoke(orientQuat);

                double rpx = 0, rpy = 0, rpz = 0;
                boolean hasRp = false;
                if (poseRotationPointMethod != null) {
                    var rpVec = poseRotationPointMethod.invoke(pose);
                    if (rpVec != null) {
                        rpx = (double) vecXMethod.invoke(rpVec);
                        rpy = (double) vecYMethod.invoke(rpVec);
                        rpz = (double) vecZMethod.invoke(rpVec);
                        hasRp = true;
                    }
                }

                long subLevelId = subLevelId(ox, oy, oz);

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
                        // Keep in overworld coords to match rp (rotation point is in overworld coords)
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
            if (iface.getName().equals("com.example.create_schematic_compute.blocks.GraphBlockEntity"))
                return true;
        }
        // Also check superclass chain
        for (Class<?> c = be.getClass().getSuperclass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals("com.example.create_schematic_compute.blocks.GraphBlockEntity"))
                return true;
            for (Class<?> iface : c.getInterfaces()) {
                if (iface.getName().equals("com.example.create_schematic_compute.blocks.GraphBlockEntity"))
                    return true;
            }
            if (!c.getName().startsWith("com.example.create_schematic_compute")) break;
        }
        return false;
    }

    private static long subLevelId(double ox, double oy, double oz) {
        long xi = (long) (ox * 100);
        long yi = (long) (oy * 100);
        long zi = (long) (oz * 100);
        return xi ^ (yi << 21) ^ (zi << 42);
    }
}
