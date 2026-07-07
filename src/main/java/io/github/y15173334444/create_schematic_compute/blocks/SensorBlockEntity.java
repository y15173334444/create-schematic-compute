package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.RuntimeState;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SensorBlockEntity extends BlockEntity implements MenuProvider, IMergeableBE, GraphBlockEntity {
    public NodeGraph graph = new NodeGraph();
    public boolean running = false;
    public final RuntimeState runtimeState = new RuntimeState();
    public float attitudeYaw = 0, attitudePitch = 0, attitudeRoll = 0;
    public float forwardYaw = 0, forwardPitch = 0;
    public float accelX = 0, accelY = 0, accelZ = 0;
    public double rawVelX, rawVelY, rawVelZ;
    public volatile float cachedSubWorldX = Float.NaN, cachedSubWorldY = Float.NaN, cachedSubWorldZ = Float.NaN;
    private double prevRawVelX, prevRawVelY, prevRawVelZ;
    private boolean firstAccel = true;
    private GraphEvaluator evaluator = null;
    private NodeGraph lastEvaluatedGraph = null;
    private final RedstoneLinkHelper rs = new RedstoneLinkHelper(this);

    public SensorBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.SENSOR_BE.get(), pos, s); }
    @Override public boolean isRunning() { return running; }
    @Override public void setRunning(boolean r) { running = r; }
    @Override public boolean graphHasCycles() { return graph.hasCycles(); }
    @Override public void clearPidState() { runtimeState.pidState.clear(); }

    public static SensorBlockEntity create(BlockPos pos, BlockState state) {
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
                Class<?> cls = Class.forName("io.github.y15173334444.create_schematic_compute.compat.SensorBlockEntitySable");
                return (SensorBlockEntity) cls.getConstructor(BlockPos.class, BlockState.class).newInstance(pos, state);
            }
        } catch (Exception ignored) {}
        return new SensorBlockEntity(pos, state);
    }
    @Override public void accept(BlockEntity other) {
        if(other instanceof SensorBlockEntity src) {
            unregisterBusChannels(graph);
            this.graph = src.graph; this.running = src.running; runtimeState.clear(); setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
    @Override public void onLoad() { super.onLoad(); rs.onLoad(graph); }
    @Override public void onChunkUnloaded() { cleanupBusChannels(graph); unregisterBusChannels(graph); super.onChunkUnloaded(); rs.onChunkUnloaded(); }
    @Override public void setRemoved() { cleanupBusChannels(graph); unregisterBusChannels(graph); rs.setRemoved(); super.setRemoved(); }

    private final java.util.HashMap<Integer, Integer> lastBusHashMap = new java.util.HashMap<>();
    @Override public io.github.y15173334444.create_schematic_compute.graph.NodeGraph getNodeGraph() { return graph; }
    @Override public void syncBusBandsFromServer(String busName, java.util.List<String> bands) {
        BusChannelHelper.syncBandsFromServer(busName, bands, graph);
    }

    private void registerBusChannels() {
        if (BusChannelHelper.registerChannels(graph, worldPosition, level))
            needsFullSync = true;
    }

    private void cleanupBusChannels(io.github.y15173334444.create_schematic_compute.graph.NodeGraph g) {
        BusChannelHelper.cleanupClientBands(g, worldPosition, level);
    }

    private void unregisterBusChannels(io.github.y15173334444.create_schematic_compute.graph.NodeGraph g) {
        BusChannelHelper.unregisterChannels(g, worldPosition, level);
    }

    protected void updateAttitude() {
        float bYaw = 0;
        if (getBlockState().hasProperty(SensorBlock.FACING)) bYaw = getBlockState().getValue(SensorBlock.FACING).toYRot();
        org.joml.Quaterniond subQ = new org.joml.Quaterniond();
        if (level != null && net.neoforged.fml.ModList.get().isLoaded("sable")) {
            var subQ2 = getSublevelOrientation(level);
            if (subQ2 != null) subQ = subQ2;
        }
        var euler = new org.joml.Vector3d();
        subQ.getEulerAnglesYXZ(euler);
        attitudePitch = (float)Math.toDegrees(euler.x);
        attitudeRoll = (float)Math.toDegrees(euler.z);
        while (attitudePitch > 90) attitudePitch -= 180; while (attitudePitch < -90) attitudePitch += 180;
        while (attitudeRoll > 180) attitudeRoll -= 360; while (attitudeRoll < -180) attitudeRoll += 360;
        org.joml.Vector3d worldFwd = new org.joml.Vector3d(0, 0, 1);
        worldFwd.rotateY(Math.toRadians(-bYaw));
        subQ.transform(worldFwd);
        forwardYaw = (float)-Math.toDegrees(Math.atan2(worldFwd.x, worldFwd.z));
        forwardPitch = (float)Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, worldFwd.y / worldFwd.length()))));
        while (forwardYaw > 180) forwardYaw -= 360; while (forwardYaw < -180) forwardYaw += 360;
    }

    // ── Sable reflection cache ──
    private static Class<?> subLevelContainerClass;
    private static java.lang.reflect.Method getContainerMethod, getAllSubLevelsMethod, logicalPoseMethod;
    private static volatile boolean sableReflectionInit;
    private static void initSableReflection() {
        if (sableReflectionInit) return;
        try {
            subLevelContainerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            getContainerMethod = subLevelContainerClass.getMethod("getContainer", net.minecraft.world.level.Level.class);
            getAllSubLevelsMethod = getContainerMethod.getReturnType().getMethod("getAllSubLevels");
            var subLevelClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevel");
            logicalPoseMethod = subLevelClass.getMethod("logicalPose");
        } catch (Exception ignored) {}
        sableReflectionInit = true;
    }
    private static org.joml.Quaterniond getSublevelOrientation(net.minecraft.world.level.Level level) {
        try {
            initSableReflection();
            if (getContainerMethod == null) return null;
            var cnt = getContainerMethod.invoke(null, level);
            if (cnt != null) {
                var allSubs = (java.util.List<?>)getAllSubLevelsMethod.invoke(cnt);
                if (!allSubs.isEmpty()) {
                    var pose = (dev.ryanhcode.sable.companion.math.Pose3dc)logicalPoseMethod.invoke(allSubs.get(0));
                    if (pose != null) { var oq = pose.orientation(); return new org.joml.Quaterniond(oq.x(), oq.y(), oq.z(), oq.w()); }
                }
            }
        } catch(Exception ignored) {}
        return null;
    }

    public void tick() {
        if(level==null||level.isClientSide()) return;
        var state = getBlockState();
        if (!state.hasProperty(SensorBlock.LIT)) return;
        boolean lit = running && !graph.nodes.isEmpty();
        if(state.getValue(SensorBlock.LIT)!=lit) level.setBlock(worldPosition, state.setValue(SensorBlock.LIT, lit), 3);
        rs.checkGraphChanged(graph);
        if(evaluator==null||lastEvaluatedGraph!=graph) {
            if (lastEvaluatedGraph != null) {
                BusChannelHelper.syncDeletedBusNames(lastEvaluatedGraph, graph, worldPosition, level);
                unregisterBusChannels(lastEvaluatedGraph);
            }
            evaluator = new GraphEvaluator(graph);
            evaluator.restoreSubState(runtimeState);
            lastEvaluatedGraph = graph;
            runtimeState.pidState.clear();
            registerBusChannels();
        }
        if(!running) {
            for (var n : graph.nodes) {
                if (n.type == NodeType.BUS_OUT && n.busInternalMap != null)
                    n.busInternalMap.clear();
            }
            rs.writeOutputs(java.util.Collections.emptyList());
            return;
        }
        updateAttitude();
        if (firstAccel) { prevRawVelX = rawVelX; prevRawVelY = rawVelY; prevRawVelZ = rawVelZ; firstAccel = false; }
        else {
            accelX = (float)((rawVelX - prevRawVelX) / 0.05);
            accelY = (float)((rawVelY - prevRawVelY) / 0.05);
            accelZ = (float)((rawVelZ - prevRawVelZ) / 0.05);
            prevRawVelX = rawVelX; prevRawVelY = rawVelY; prevRawVelZ = rawVelZ;
        }
        if(evaluator==null||lastEvaluatedGraph!=graph){
            evaluator=new GraphEvaluator(graph); lastEvaluatedGraph=graph;
            if (lastEvaluatedGraph != null) runtimeState.pidState.clear();
        }
        rs.refreshInputs();
        BusChannelHelper.recoverConflictedChannels(graph, worldPosition, level);
        var in = rs.buildInputs(graph);
        float blockYaw = getBlockState().hasProperty(SensorBlock.FACING)
            ? getBlockState().getValue(SensorBlock.FACING).toYRot() : 0;
        var si = new GraphEvaluator.SeatInputState(0,0,0,0,0, 0,0, 0,0,0,0,0,0,0,0, blockYaw,attitudeYaw,attitudePitch,attitudeRoll,forwardYaw,forwardPitch, accelX,accelY,accelZ, (float)rawVelX,(float)rawVelY,(float)rawVelZ,
            Float.isNaN(cachedSubWorldX) ? worldPosition.getX()+0.5f : cachedSubWorldX,
            Float.isNaN(cachedSubWorldY) ? worldPosition.getY()+0.5f : cachedSubWorldY,
            Float.isNaN(cachedSubWorldZ) ? worldPosition.getZ()+0.5f : cachedSubWorldZ);
        var results = evaluator.evaluate(in, runtimeState.pidState, 0.05f, si);
        rs.writeOutputs(results);
        BusChannelHelper.syncIfBandsChanged(graph, worldPosition, lastBusHashMap, level);
        setChanged();
    }

    public void loadGraphFromBytes(byte[] data) {
        if (level == null) return;
        try {
            var t = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(2*1024*1024));
            if(t!=null&&t.contains("graph")){ graph=NodeGraph.load(t.getCompound("graph"),level.registryAccess()); rs.onLoad(graph);
            needsFullSync=true; setChanged(); if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }
            setChanged();
        } catch(Exception e) { SchematicCompute.LOGGER.error("Failed to load sensor", e); graph=new NodeGraph(); setChanged(); }
    }

    @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.saveAdditional(t,r); t.put("graph", graph.save(r)); t.putBoolean("running", running);
        t.put("runtime", runtimeState.save());
    }
    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t,r);
        if (t.contains("graph")) {
            var oldExpanded = new java.util.HashMap<Integer, Boolean>();
            for (var n : graph.nodes) if (n.expanded) oldExpanded.put(n.id, true);
            graph = NodeGraph.load(t.getCompound("graph"), r);
            for (var n : graph.nodes) if (oldExpanded.containsKey(n.id)) n.expanded = true;
            rs.onLoad(graph);
        }
        if (t.contains("running")) running = t.getBoolean("running");
        if (t.contains("runtime")) {
            RuntimeState loaded = RuntimeState.load(t.getCompound("runtime"));
            runtimeState.pidState.putAll(loaded.pidState);
            runtimeState.subStates.putAll(loaded.subStates);
        }
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    private boolean needsFullSync = true;
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) {
        if (needsFullSync) { needsFullSync = false; var t=new CompoundTag(); saveAdditional(t,r); return t; }
        var t=new CompoundTag(); t.putBoolean("running", running); return t;
    }
    @Override public Component getDisplayName() { return Component.translatable("container."+SchematicCompute.MOD_ID+".sensor"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new SensorMenu(id, this); }
}
