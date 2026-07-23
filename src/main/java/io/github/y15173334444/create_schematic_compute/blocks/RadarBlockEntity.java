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

public class RadarBlockEntity extends SyncedGraphBlockEntity {

    // 扫描设置
    public int scanRange = 32;
    public int scanMode = 0; // 0=多目标, 1=单目标
    public int displayScale = 4; // 三轴显示大小(格)
    public boolean showPlayers = true;
    public boolean showMobs = true;
    public boolean showSable = true;
    public int lockMode = 0; // 0=自动, 1=手动
    public float displayX = 0, displayY = 0, displayZ = 0; // XYZ 显示偏移（格）
    public boolean excludeHost = true; // 不扫描/锁定所在 Sable 结构
    public int displayStyle = 0; // 0=经典XYZ轴, 1=全息平面
    public float lockDistance = 0f; // 最近锁定距离(m)，范围内的目标不被锁定
    public final java.util.LinkedHashSet<Integer> lockedTargets = new java.util.LinkedHashSet<>();
    /** 自动模式下当前分配的目标 entityId（渲染器用于高亮显示） */
    public final java.util.Set<Integer> activeTargets = new java.util.HashSet<>();

    // Sable 子世界位置缓存（NaN = 不在 Sable 上，使用 worldPosition）
    public volatile float cachedSubWorldX = Float.NaN;
    public volatile float cachedSubWorldY = Float.NaN;
    public volatile float cachedSubWorldZ = Float.NaN;
    public volatile float cachedSubYaw = Float.NaN;
    public volatile float cachedSubPitch = Float.NaN;
    public volatile float cachedSubRoll = Float.NaN;
    /** 所在 Sable 结构的子世界原点世界坐标（用于 isHost 比较） */
    public volatile float cachedSubOriginX = Float.NaN;
    public volatile float cachedSubOriginY = Float.NaN;
    public volatile float cachedSubOriginZ = Float.NaN;
    /** Sable 结构朝向四元数分量（精确逆旋转，避免 Euler 角精度丢失） */
    public volatile float cachedSubQx = Float.NaN;
    public volatile float cachedSubQy = Float.NaN;
    public volatile float cachedSubQz = Float.NaN;
    public volatile float cachedSubQw = Float.NaN;

    // 扫描结果缓存（渲染器读取）
    public final List<TargetRecord> targets = new ArrayList<>();

    /** 客户端侧所有已加载雷达的注册表，用于 RadarLockHandler 遍历 */
    private static final java.util.Set<RadarBlockEntity> CLIENT_RADARS = new java.util.HashSet<>();
    public static java.util.Collection<RadarBlockEntity> getClientRadars() { return CLIENT_RADARS; }

    /** Sable 兼容工厂 */
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

    public RadarBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.RADAR_BE.get(), pos, s); }

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

    @Override public void onLoad() { super.onLoad(); if (level != null && level.isClientSide()) CLIENT_RADARS.add(this);
        else clearCachedSubPose(); // 服务端清除 NBT 带来的旧 Sable 缓存，让 bootstrap 重新初始化
    }
    @Override public void setRemoved() { CLIENT_RADARS.remove(this); TargetAssignment.clear(worldPosition); super.setRemoved(); }

    /** 检查实体是否在扫描范围内（实体与中心已在同一坐标系） */
    private static boolean inScanRange(double ex, double ey, double ez,
                                        AABB scanBox) {
        return scanBox.contains(ex, ey, ez);
    }

    /** 清除过期的 Sable 缓存坐标 */
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

    /** 仅在 sable$physicsTick 从未被调用时兜底填充初始缓存。
     *  sable$physicsTick 每 tick 更新精确世界位置，bootstrap 不应覆盖它。 */
    private void tryBootstrapSableCache() {
        if (!Float.isNaN(cachedSubYaw)) return; // sable$physicsTick 已接管，无需 bootstrap
        try {
            initSableReflection();
            if (sableLogicalPoseMethod == null || sableGetContainerMethod == null) return;
            var scanLevel = getScanLevel();
            if (scanLevel == null) return;
            var cnt = sableGetContainerMethod.invoke(null, scanLevel);
            if (cnt == null) return;
            var all = (List<?>) sableGetAllSubLevelsMethod.invoke(cnt);
            if (all == null || all.isEmpty()) return;
            // Find the sub-level whose global bounding box contains this radar
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
            cachedSubOriginX = (float) px;
            cachedSubOriginY = (float) py;
            cachedSubOriginZ = (float) pz;
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
                    var q = new org.joml.Quaterniond(ox, oy, oz, ow);
                    var euler = new org.joml.Vector3d();
                    q.getEulerAnglesYXZ(euler);
                    cachedSubYaw   = (float) Math.toDegrees(euler.y);
                    cachedSubPitch = (float) Math.toDegrees(euler.x);
                    cachedSubRoll  = (float) Math.toDegrees(euler.z);
                }
            } catch (Exception e) {
                cachedSubYaw = 0; cachedSubPitch = 0; cachedSubRoll = 0;
            }
        } catch (Exception e) {
            SchematicCompute.LOGGER.warn("Radar Sable bootstrap failed: {}", e.toString());
        }
    }

    /** 获取有效的 Level 引用（Sable 子类覆盖以处理 level 为 null 的情况） */
    protected Level getEffectiveLevel() { return level; }

    /** 获取扫描用的 Level（Sable 上实体在主世界，需用 overworld 扫描） */
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

    public void tick() {
        // 修复 Sable 结构上 level 为 null 的问题
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

        // 主动检测 Sable（sable$physicsTick 未调用时的兜底）
        tryBootstrapSableCache();

        // ══ 扫描目标 ══
        targets.clear();

        // 扫描中心
        // - 主世界：BlockPos 是世界坐标
        // - Sable：使用缓存的世界坐标（cachedSubWorld 是子世界的世界原点位置）
        boolean onSable = !Float.isNaN(cachedSubYaw);
        double scx = onSable ? cachedSubWorldX : worldPosition.getX() + 0.5;
        double scy = onSable ? cachedSubWorldY : worldPosition.getY() + 0.5;
        double scz = onSable ? cachedSubWorldZ : worldPosition.getZ() + 0.5;

        AABB scanBox = new AABB(scx - scanRange, scy - scanRange, scz - scanRange,
                                 scx + scanRange, scy + scanRange, scz + scanRange);

        // 诊断日志：无条件打印扫描信息
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
        scanSableStructures(scx, scy, scz, scanBox); // 始终扫描子世界实体，结构 blip 内部判断 showSable
        targets.sort(Comparator.comparingDouble(TargetRecord::distance));
        // 清理不在范围内的过期锁定
        if (!lockedTargets.isEmpty()) {
            var validIds = new java.util.HashSet<Integer>();
            for (var t : targets) validIds.add(t.entityId());
            lockedTargets.removeIf(id -> !validIds.contains(id));
        }
        if (!targets.isEmpty()) {
            SchematicCompute.LOGGER.debug("TICK: {} targets at {}, running={}", targets.size(), worldPosition, running);
        }

        // ══ 分配目标 ══
        var targetOutNodes = new ArrayList<GraphNode>();
        for (var n : graph.nodes) if (n.type == NodeType.TARGET_OUT) targetOutNodes.add(n);
        targetOutNodes.sort(Comparator.comparingInt(n -> n.id));
        if (lockMode == 1) {
            TargetAssignment.assignLocked(worldPosition, targetOutNodes, targets, lockedTargets, scanMode, lockDistance);
        } else {
            TargetAssignment.assign(worldPosition, targetOutNodes, targets, scanMode, lockDistance);
        }
        // 收集当前分配的目标 entityId（渲染器高亮用）
        activeTargets.clear();
        for (var n : targetOutNodes) {
            var t = TargetAssignment.getTarget(worldPosition, n.id);
            if (t != null) activeTargets.add(t.entityId());
        }

        // ══ 图评估 ══
        rs.refreshInputs();
        if (BusChannelHelper.recoverConflictedChannels(graph, worldPosition, level)) {
            needsFullSync = true; setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        var in = rs.buildInputs(graph);
        evaluator.setRadarPos(worldPosition);
        float wx = Float.isNaN(cachedSubWorldX) ? worldPosition.getX() + 0.5f : cachedSubWorldX;
        float wy = Float.isNaN(cachedSubWorldY) ? worldPosition.getY() + 0.5f : cachedSubWorldY;
        float wz = Float.isNaN(cachedSubWorldZ) ? worldPosition.getZ() + 0.5f : cachedSubWorldZ;
        var si = new GraphEvaluator.SeatInputState(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, wx, wy, wz);
        var results = evaluator.evaluate(in, runtimeState.pidState, 0.05f, si,
            new HashMap<>(), new HashMap<>(), new HashMap<>());
        evaluator.setRadarPos(null);
        rs.writeOutputs(results);
        broadcastEvalSnapshot(); // 广播 EvalSnapshot 给客户端（供 DEBUG_PROBE 采样）
        BusChannelHelper.syncIfBandsChanged(graph, worldPosition, lastBusHashMap, level);
        setChanged();
        // 强制同步目标到客户端
        if (level != null && !level.isClientSide())
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    // ── Sable 反射缓存 ──
    private static java.lang.reflect.Method sableGetContainerMethod, sableGetAllSubLevelsMethod,
                                             sableLogicalPoseMethod, sableSubLevelGetLevelMethod;
    private static volatile boolean sableReflectionInit;
    private static void initSableReflection() {
        if (sableReflectionInit) return;
        try {
            var containerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            sableGetContainerMethod = containerClass.getMethod("getContainer", net.minecraft.world.level.Level.class);
            sableGetAllSubLevelsMethod = containerClass.getMethod("getAllSubLevels");
            // SubLevel 在 dev.ryanhcode.sable.sublevel 包（非 api 子包）
            var subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
            sableLogicalPoseMethod = subLevelClass.getMethod("logicalPose");
            sableSubLevelGetLevelMethod = subLevelClass.getMethod("getLevel");
            SchematicCompute.LOGGER.info("Radar Sable reflection initialized OK");
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Radar Sable reflection init FAILED: {}", e.toString());
        }
        sableReflectionInit = true;
    }

    /** Sable 子世界扫描 — 查找所有子世界位置 */
    private void scanSableStructures(double scx, double scy, double scz, AABB scanBox) {
        try {
            initSableReflection();
            if (sableGetContainerMethod == null) return;
            // getContainer 应传 overworld，不是子世界 Level
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
            // 服务端已清除过期 Sable 缓存，客户端同步清除
            clearCachedSubPose();
        }
        if (t.contains("sox")) {
            cachedSubOriginX = t.getFloat("sox");
            cachedSubOriginY = t.getFloat("soy");
            cachedSubOriginZ = t.getFloat("soz");
        } else if (t.contains("swx")) {
            // 旧存档迁移：有 swx 但没有 sox，标记原点为未初始化
            // 下次 sable$physicsTick 会填充，期间 isHost 判断为 false（安全回退）
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

    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    /** Always send the full graph so that new clients tracking this chunk receive
     *  the authoritative graph data. Also includes runtime radar targets for the
     *  client-side blip renderer.
     *  始终发送完整图数据，以确保新追踪此区块的客户端能收到权威图数据。
     *  同时包含运行时雷达目标数据供客户端 blip 渲染器使用。 */
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) {
        var t = new CompoundTag();
        saveAdditional(t, r);
        // Append runtime radar targets for client-side blip renderer
        // 附加运行时雷达目标供客户端 blip 渲染器使用
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

    /** 服务端检测玩家准星下的 blip（使用 blip 3D 显示坐标），返回 entityId，null 表示没对准 */
    @javax.annotation.Nullable
    public Integer findBlipUnderCrosshair(net.minecraft.world.entity.player.Player player) {
        if (targets.isEmpty()) return null;
        var eyePos = player.getEyePosition();
        var lookVec = player.getLookAngle();

        boolean onSable = !Float.isNaN(cachedSubYaw);
        double radarWorldX = onSable ? cachedSubWorldX : getBlockPos().getX() + 0.5;
        double radarWorldY = onSable ? cachedSubWorldY : getBlockPos().getY() + 0.5;
        double radarWorldZ = onSable ? cachedSubWorldZ : getBlockPos().getZ() + 0.5;
        int scanRange = Math.max(1, this.scanRange);
        float axisLen = this.displayScale * 0.5f;

        float facingYDeg = getBlockState().hasProperty(RadarBlock.FACING)
            ? getBlockState().getValue(RadarBlock.FACING).toYRot() : 0;
        var dispOff = new org.joml.Vector3f(displayX, displayY, displayZ);
        dispOff.rotateY((float) Math.toRadians(-facingYDeg));
        if (onSable && !Float.isNaN(cachedSubQw)) {
            var q = new org.joml.Quaternionf(cachedSubQx, cachedSubQy, cachedSubQz, cachedSubQw);
            dispOff.rotate(q);
        }
        radarWorldX += dispOff.x; radarWorldY += dispOff.y; radarWorldZ += dispOff.z;

        org.joml.Quaternionf invQ = null;
        if (onSable && !Float.isNaN(cachedSubQw)) {
            invQ = new org.joml.Quaternionf(cachedSubQx, cachedSubQy, cachedSubQz, cachedSubQw);
            invQ.conjugate();
        }

        Integer best = null;
        double bestDist = 2.0;

        for (var t : targets) {
            float dx = (float)(t.x() - radarWorldX);
            float dy = (float)(t.y() - radarWorldY);
            float dz = (float)(t.z() - radarWorldZ);
            var v = new org.joml.Vector3f(dx, dy, dz);
            if (invQ != null) v.rotate(invQ);
            v.rotateY((float) Math.toRadians(facingYDeg));
            float rx = v.x / scanRange * axisLen;
            float ry = v.y / scanRange * axisLen;
            float rz = v.z / scanRange * axisLen;
            if (Math.abs(rx) > axisLen || Math.abs(ry) > axisLen || Math.abs(rz) > axisLen) continue;

            var worldOffset = new org.joml.Vector3f(rx, ry, rz);
            worldOffset.rotateY((float) Math.toRadians(-facingYDeg));
            if (onSable && !Float.isNaN(cachedSubQw)) {
                var q = new org.joml.Quaternionf(cachedSubQx, cachedSubQy, cachedSubQz, cachedSubQw);
                worldOffset.rotate(q);
            }
            double wx = radarWorldX + worldOffset.x;
            double wy = radarWorldY + worldOffset.y;
            double wz = radarWorldZ + worldOffset.z;

            var tp = new net.minecraft.world.phys.Vec3(wx, wy, wz);
            var toTarget = tp.subtract(eyePos);
            double dot = toTarget.dot(lookVec);
            if (dot <= 0) continue;
            var proj = eyePos.add(lookVec.scale(dot));
            double dist = tp.distanceTo(proj);
            if (dist < bestDist) { bestDist = dist; best = t.entityId(); }
        }
        return best;
    }

    /** 切换手动锁定目标。单目标模式下新锁定替换旧锁定 */
    public boolean toggleLock(int entityId, int maxLocks) {
        if (lockedTargets.contains(entityId)) {
            lockedTargets.remove(entityId);
            setChanged();
            return true;
        }
        // 单目标模式：替换旧锁定
        if (maxLocks == 1 && !lockedTargets.isEmpty()) {
            lockedTargets.clear();
        }
        if (maxLocks > 0 && lockedTargets.size() >= maxLocks) return false;
        lockedTargets.add(entityId);
        setChanged();
        return true;
    }

    /** 根据扫描模式和图中 TARGET_OUT 节点数计算最大锁定数 */
    public int getMaxLocks() {
        if (scanMode == 1) return 1;
        int count = 0;
        for (var n : graph.nodes) if (n.type == NodeType.TARGET_OUT) count++;
        return Math.max(1, count);
    }

    /** 使用 Sable 内置 transformPosition 将子世界实体坐标转换为世界坐标并加入目标列表 */
    private boolean tryAddSubEntity(net.minecraft.world.entity.Entity e, double scx, double scy, double scz,
                                     Object pose, java.lang.reflect.Method transformMethod, AABB scanBox) {
        try {
            double wx, wy, wz;
            if (transformMethod != null) {
                var localPos = new org.joml.Vector3d(e.getX(), e.getY(), e.getZ());
                var worldPos = (org.joml.Vector3d) transformMethod.invoke(pose, localPos);
                wx = worldPos.x; wy = worldPos.y; wz = worldPos.z;
            } else {
                // 回退：简单加法（无旋转结构也能工作）
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

    @Override public Component getDisplayName() { return Component.translatable("container." + SchematicCompute.MOD_ID + ".radar"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new RadarMenu(id, this); }
}
