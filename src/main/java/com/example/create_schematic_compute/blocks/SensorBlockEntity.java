package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.ModUtils;
import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.graph.GraphEvaluator;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import com.simibubi.create.foundation.blockEntity.IMergeableBE;
import net.createmod.catnip.data.Couple;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.util.*;

public class SensorBlockEntity extends BlockEntity implements MenuProvider, IMergeableBE, GraphBlockEntity {
    public NodeGraph graph = new NodeGraph();
    public boolean running = false;
    public final Map<Integer, Float> pidState = new HashMap<>();
    public float attitudeYaw = 0, attitudePitch = 0, attitudeRoll = 0;
    public float forwardYaw = 0, forwardPitch = 0;
    private GraphEvaluator evaluator = null;
    private NodeGraph lastEvaluatedGraph = null;
    private final List<FreqLink> freqLinks = new ArrayList<>();
    private final Map<Long, Integer> lastInputs = new HashMap<>();
    private final Map<Long, Integer> lastOutputs = new HashMap<>();
    private record FreqLink(long freqKey, IRedstoneLinkable linkable) {}
    public SensorBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.SENSOR_BE.get(), pos, s); }
    @Override public boolean isRunning() { return running; }
    @Override public void setRunning(boolean r) { running = r; setChanged(); if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }
    @Override public boolean graphHasCycles() { return graph.hasCycles(); }
    @Override public void clearPidState() { pidState.clear(); }

    /** 工厂方法：Sable 存在时创建兼容子类，否则创建基础类 */
    public static SensorBlockEntity create(BlockPos pos, BlockState state) {
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
                Class<?> cls = Class.forName("com.example.create_schematic_compute.compat.SensorBlockEntitySable");
                return (SensorBlockEntity) cls.getConstructor(BlockPos.class, BlockState.class).newInstance(pos, state);
            }
        } catch (Exception ignored) {}
        return new SensorBlockEntity(pos, state);
    }
    @Override public void accept(BlockEntity other) {
        if(other instanceof SensorBlockEntity src) { this.graph = src.graph; this.running = src.running; setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }
    }
    @Override public void onLoad() { super.onLoad(); registerLinks(); }
    @Override public void onChunkUnloaded() { super.onChunkUnloaded(); unregisterLinks(); }
    @Override public void setRemoved() { unregisterLinks(); super.setRemoved(); }
    private void registerLinks() {
        if(level==null||level.isClientSide()) return;
        unregisterLinks();
        var EMPTY = RedstoneLinkNetworkHandler.Frequency.EMPTY;
        for(var n : graph.nodes) {
            if(n.type==NodeType.REDSTONE_IN||n.type==NodeType.REDSTONE_OUT) {
                var item1 = n.itemParams!=null&&n.itemParams.length>0 ? n.itemParams[0] : ItemStack.EMPTY;
                var item2 = n.itemParams!=null&&n.itemParams.length>1 ? n.itemParams[1] : ItemStack.EMPTY;
                var f1 = !item1.isEmpty() ? RedstoneLinkNetworkHandler.Frequency.of(item1) : EMPTY;
                var f2 = !item2.isEmpty() ? RedstoneLinkNetworkHandler.Frequency.of(item2) : EMPTY;
                var freqKey = ModUtils.freqKey(item1, item2);
                var isIn = n.type==NodeType.REDSTONE_IN;
                var link = new IRedstoneLinkable() {
                    public int getTransmittedStrength() { return isIn?0:lastOutputs.getOrDefault(freqKey,0); }
                    public void setReceivedStrength(int s) { if(isIn) lastInputs.put(freqKey,s); }
                    public boolean isListening() { return isIn; }
                    public boolean isAlive() { return true; }
                    public Couple<RedstoneLinkNetworkHandler.Frequency> getNetworkKey() { return Couple.create(f1,f2); }
                    public BlockPos getLocation() { return worldPosition; }
                };
                Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, link);
                freqLinks.add(new FreqLink(freqKey, link));
            }
        }
        // 第二轮询：从已有网络中读取初始信号
        for(var fl : freqLinks) {
            var net = Create.REDSTONE_LINK_NETWORK_HANDLER.getNetworkOf(level, fl.linkable);
            if(net!=null) for(var l : net) if(l!=fl.linkable&&l.isAlive()) {
                int sig = l.getTransmittedStrength();
                if(sig>0) { lastInputs.put(fl.freqKey, sig); break; }
            }
        }
    }
    private void unregisterLinks() { for(var fl : freqLinks) Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, fl.linkable); freqLinks.clear(); }

    protected void updateAttitude() {
        float bYaw = 0;
        if (getBlockState().hasProperty(SensorBlock.FACING)) bYaw = getBlockState().getValue(SensorBlock.FACING).toYRot();
        org.joml.Quaterniond subQ = new org.joml.Quaterniond();
        // 读取 sable 子世界旋转
        if (level != null && net.neoforged.fml.ModList.get().isLoaded("sable")) {
            var subQ2 = getSublevelOrientation(level);
            if (subQ2 != null) subQ = subQ2;
        }

        // ── 姿态：YXZ 欧拉角（先偏航Y，再俯仰X，再滚转Z） ──
        var euler = new org.joml.Vector3d();
        subQ.getEulerAnglesYXZ(euler);
        attitudePitch = (float)Math.toDegrees(euler.x); // 绕 X 轴
        attitudeRoll = (float)Math.toDegrees(euler.z);  // 绕 Z 轴
        while (attitudePitch > 90) attitudePitch -= 180; while (attitudePitch < -90) attitudePitch += 180;
        while (attitudeRoll > 180) attitudeRoll -= 360; while (attitudeRoll < -180) attitudeRoll += 360;

        // ── 前方朝向：方块前方向量经子世界旋转 ──
        org.joml.Vector3d worldFwd = new org.joml.Vector3d(0, 0, 1);
        worldFwd.rotateY(Math.toRadians(-bYaw));
        subQ.transform(worldFwd);
        forwardYaw = (float)-Math.toDegrees(Math.atan2(worldFwd.x, worldFwd.z));
        forwardPitch = (float)Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, worldFwd.y / worldFwd.length()))));
        while (forwardYaw > 180) forwardYaw -= 360; while (forwardYaw < -180) forwardYaw += 360;
    }

    /** 反射获取子世界四元数 — 方法引用缓存 */
    private static Class<?> subLevelContainerClass;
    private static java.lang.reflect.Method getContainerMethod, getAllSubLevelsMethod, logicalPoseMethod;
    private static volatile boolean sableReflectionInit;

    private static void initSableReflection() {
        if (sableReflectionInit) return;
        try {
            subLevelContainerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            getContainerMethod = subLevelContainerClass.getMethod("getContainer", net.minecraft.world.level.Level.class);
            // The container class — found from the return type of getContainer
            getAllSubLevelsMethod = getContainerMethod.getReturnType().getMethod("getAllSubLevels");
            // The sub level class — found from the list element type
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
                    if (pose != null) {
                        var oq = pose.orientation();
                        return new org.joml.Quaterniond(oq.x(), oq.y(), oq.z(), oq.w());
                    }
                }
            }
        } catch(Exception ignored) {}
        return null;
    }

    public void tick() {
        if(level==null||level.isClientSide()) return;
        updateAttitude();
        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        var s = getBlockState();
        if (!s.hasProperty(SensorBlock.LIT)) return;
        if(s.getValue(SensorBlock.LIT)!=shouldBeLit) level.setBlock(worldPosition, s.setValue(SensorBlock.LIT, shouldBeLit), 3);
        if(!running) return;
        if(evaluator==null||lastEvaluatedGraph!=graph){ evaluator=new GraphEvaluator(graph); lastEvaluatedGraph=graph; pidState.clear(); }
        var in = new ArrayList<GraphEvaluator.InputSource>();
        for(var n : graph.nodes) if(n.type==NodeType.REDSTONE_IN) { long fk=ModUtils.freqKey(n.itemParams); in.add(new GraphEvaluator.InputSource(fk, lastInputs.getOrDefault(fk,0))); }
        var si = new GraphEvaluator.SeatInputState(0,0,0,0,0,0,0,0,0,0,0,0,0,0,attitudeYaw,attitudePitch,attitudeRoll,forwardYaw,forwardPitch);
        var results = evaluator.evaluate(in, pidState, 0.05f, si);
        lastOutputs.clear();
        for(var r : results) {
            long fk = ModUtils.freqKey(r.freq1(),r.freq2());
            lastOutputs.put(fk, r.signal());
            for(var fl : freqLinks) if(fl.freqKey()==fk) Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(level, fl.linkable);
        }
        setChanged();
    }
    public void loadGraphFromBytes(byte[] data) {
        if (level == null) return;
        try { var t=NbtIo.readCompressed(new ByteArrayInputStream(data),NbtAccounter.create(2*1024*1024));
            if(t!=null&&t.contains("graph")){ graph=NodeGraph.load(t.getCompound("graph"),level.registryAccess()); registerLinks(); setChanged(); if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }
            setChanged();
        } catch(Exception e){ SchematicCompute.LOGGER.error("Failed to load sensor graph",e); graph=new NodeGraph(); setChanged(); }
    }
    @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) { super.saveAdditional(t,r); t.put("graph",graph.save(r)); t.putBoolean("running",running); }
    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t,r);
        if(t.contains("graph")){ graph=NodeGraph.load(t.getCompound("graph"),r); registerLinks(); setChanged(); if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }
        if(t.contains("running")) running=t.getBoolean("running");
        setChanged(); if(level!=null) level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);
    }
    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) { var t=new CompoundTag(); saveAdditional(t,r); return t; }
    @Override public Component getDisplayName() { return Component.translatable("container."+SchematicCompute.MOD_ID+".sensor"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new SensorMenu(id, this); }
}
