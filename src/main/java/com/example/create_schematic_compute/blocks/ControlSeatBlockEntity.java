package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.ModUtils;
import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.graph.GraphEvaluator;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
import com.example.create_schematic_compute.graph.RuntimeState;
import com.example.create_schematic_compute.network.BusChannelHelper;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ControlSeatBlockEntity extends BlockEntity implements MenuProvider, IMergeableBE, GraphBlockEntity {
    public NodeGraph graph = new NodeGraph();

    // ══ 全局输入缓存（按玩家 UUID） ══
    public record InputState(long keyBits, float mouseX, float mouseY, float yaw, float pitch, int mode,
        int mouseButtons, float gpadLX, float gpadLY, float gpadRX, float gpadRY, float gpadLT, float gpadRT, long gpadButtons) {}
    private static final Map<java.util.UUID, InputState> PLAYER_INPUTS = new java.util.HashMap<>();

    public static void storeInput(java.util.UUID playerUuid, long keyBits, float mouseX, float mouseY,
        float yaw, float pitch, int mode, int mouseButtons,
        float gpadLX, float gpadLY, float gpadRX, float gpadRY, float gpadLT, float gpadRT, long gpadButtons) {
        synchronized (PLAYER_INPUTS) {
            PLAYER_INPUTS.put(playerUuid, new InputState(keyBits, mouseX, mouseY, yaw, pitch, mode,
                mouseButtons, gpadLX, gpadLY, gpadRX, gpadRY, gpadLT, gpadRT, gpadButtons));
        }
    }

    public static int pendingInputSize() {
        synchronized (PLAYER_INPUTS) { return PLAYER_INPUTS.size(); }
    }

    /** 清除所有玩家的输入缓存（服务器关闭时调用，防止跨世界污染） */
    public static void clearAllInputs() {
        synchronized (PLAYER_INPUTS) { PLAYER_INPUTS.clear(); }
    }
    public static void clearPlayerInput(java.util.UUID uuid) {
        synchronized (PLAYER_INPUTS) { PLAYER_INPUTS.remove(uuid); }
    }

    /** 查找骑乘本座椅的玩家并消费其输入 */
    protected void consumeInput() {
        if (level == null) return;
        var seats = level.getEntitiesOfClass(
            com.example.create_schematic_compute.entity.ControlSeatEntity.class,
            new net.minecraft.world.phys.AABB(worldPosition).inflate(2)); // seat is always at block pos
        for (var seat : seats) {
            for (var passenger : seat.getPassengers()) {
                if (passenger instanceof Player pl) {
                    consumeInputByPlayer(pl.getUUID());
                    return;
                }
            }
        }
        // 无人骑乘 → 清零输入，保留视角值（世界视角节点继续输出最后朝向）
        keyBits = 0; inputMode = 0;
        mouseJoystickX = 0; mouseJoystickY = 0;
    }

    /** 按玩家 UUID 直接消费输入（不依赖实体位置） */
    protected void consumeInputByPlayer(java.util.UUID playerUuid) {
        InputState s;
        synchronized (PLAYER_INPUTS) {
            s = PLAYER_INPUTS.remove(playerUuid);
        }
        if (s != null) {
            this.keyBits = s.keyBits;
            this.mouseJoystickX = s.mouseX;
            this.mouseJoystickY = s.mouseY;
            this.viewYaw = s.yaw;
            this.viewPitch = s.pitch;
            this.inputMode = s.mode;
            this.mouseButtons = s.mouseButtons;
            this.gpadLX = s.gpadLX; this.gpadLY = s.gpadLY;
            this.gpadRX = s.gpadRX; this.gpadRY = s.gpadRY;
            this.gpadLT = s.gpadLT; this.gpadRT = s.gpadRT;
            this.gpadButtons = s.gpadButtons;
        }
    }

    /** 工厂方法：Sable 存在时创建兼容子类，否则创建基础类 */
    public static ControlSeatBlockEntity create(BlockPos pos, BlockState state) {
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
                Class<?> cls = Class.forName("com.example.create_schematic_compute.compat.ControlSeatBlockEntitySable");
                return (ControlSeatBlockEntity) cls.getConstructor(BlockPos.class, BlockState.class).newInstance(pos, state);
            }
        } catch (Exception ignored) {}
        return new ControlSeatBlockEntity(pos, state);
    }
    public boolean running = false;
    public final RuntimeState runtimeState = new RuntimeState();

    // 控制座椅输入状态（由客户端包更新）
    public long keyBits = 0;          // bit 0-25: A-Z, 26-35: 0-9, 58=LMB, 59=RMB
    public float mouseJoystickX = 0;  // -1 ~ 1
    public float mouseJoystickY = 0;  // -1 ~ 1
    public float viewYaw = 0;         // 视角差YAW（度）
    public float viewPitch = 0;       // 视角差PITCH（度）
    public int inputMode = 0;         // 0=摇杆, 1=视角差
    public int mouseButtons = 0;      // bit 0=LMB, 1=RMB
    public float gpadLX = 0, gpadLY = 0, gpadRX = 0, gpadRY = 0;
    public float gpadLT = 0, gpadRT = 0;
    public long gpadButtons = 0;

    /** 世界视角缓存：仅视角差模式更新，摇杆模式/下马后冻结 */
    private float savedWorldYaw = 0, savedWorldPitch = 0;

    /** 子类（Sable compat）访问 */
    protected GraphEvaluator evaluator = null;
    protected NodeGraph lastEvaluatedGraph = null;
    protected final RedstoneLinkHelper rs = new RedstoneLinkHelper(this);
    private final java.util.HashMap<Integer, Integer> lastBusHashMap = new java.util.HashMap<>();
    /** 姿态/前方朝向（由子类 sable$physicsTick 更新） */
    protected float attitudeYaw = 0, attitudePitch = 0, attitudeRoll = 0;
    protected float forwardYaw = 0, forwardPitch = 0;
    protected float blockYaw = 0;
    protected float accelX = 0, accelY = 0, accelZ = 0;
    protected double rawVelX, rawVelY, rawVelZ;
    /** Sable 子世界缓存世界坐标（非 NaN 时优先于 worldPosition） */
    protected volatile float cachedSubWorldX = Float.NaN, cachedSubWorldY = Float.NaN, cachedSubWorldZ = Float.NaN;
    private double prevRawVelX, prevRawVelY, prevRawVelZ;
    private boolean firstAccel = true;

    public ControlSeatBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.CONTROL_SEAT_BE.get(), pos, s); }
    @Override public boolean isRunning() { return running; }
    @Override public void setRunning(boolean r) { running = r; }
    @Override public boolean graphHasCycles() { return graph.hasCycles(); }
    @Override public void clearPidState() { runtimeState.pidState.clear(); }

    @Override public void accept(net.minecraft.world.level.block.entity.BlockEntity other) {
        if(other instanceof ControlSeatBlockEntity src) {
            unregisterBusChannels(graph);
            this.graph = src.graph;
            this.running = src.running;
            runtimeState.clear();
            setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override public void onLoad() { super.onLoad(); rs.onLoad(graph); }
    @Override public void onChunkUnloaded() { cleanupBusChannels(graph); unregisterBusChannels(graph); super.onChunkUnloaded(); rs.onChunkUnloaded(); }
    @Override public void setRemoved() { cleanupBusChannels(graph); unregisterBusChannels(graph); rs.setRemoved(); super.setRemoved(); }

    @Override public com.example.create_schematic_compute.graph.NodeGraph getNodeGraph() { return graph; }
    @Override public void syncBusBandsFromServer(String busName, java.util.List<String> bands) {
        BusChannelHelper.syncBandsFromServer(busName, bands, graph);
    }

    private void registerBusChannels() {
        if (BusChannelHelper.registerChannels(graph, worldPosition, level))
            needsFullSync = true;
    }

    private void cleanupBusChannels(com.example.create_schematic_compute.graph.NodeGraph g) {
        BusChannelHelper.cleanupClientBands(g, worldPosition, level);
    }

    private void unregisterBusChannels(com.example.create_schematic_compute.graph.NodeGraph g) {
        BusChannelHelper.unregisterChannels(g, worldPosition, level);
    }

    /** 子类（Sable）覆盖使用 */
    protected volatile com.example.create_schematic_compute.entity.ControlSeatEntity mySeatEntity = null;
    public void setSeatEntity(com.example.create_schematic_compute.entity.ControlSeatEntity e) { mySeatEntity = e; }

    /** 子类可重写。客户端发送的是 playerYaw - vehicleYaw，服务端不需额外调整 */
    protected void adjustViewAngle() {
        // 客户端已发差值，不做额外调整
    }

    /** 更新姿态/前方朝向。子类（Sable）可覆盖以从子世界读取。 */
    protected void updateAttitude() {
        if (getBlockState().hasProperty(ControlSeatBlock.FACING)) {
            blockYaw = getBlockState().getValue(ControlSeatBlock.FACING).toYRot();
            attitudeYaw = blockYaw;
            forwardYaw = blockYaw;
        }
        // 无子世界时 attitudePitch/attitudeRoll/forwardPitch 保持 0
    }

    public void tick() {
        if(level==null||level.isClientSide()) return;
        consumeInput();
        adjustViewAngle();
        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        var currentState = getBlockState();
        if (!currentState.hasProperty(ControlSeatBlock.LIT)) return;
        if(currentState.getValue(ControlSeatBlock.LIT)!=shouldBeLit)
            level.setBlock(worldPosition, currentState.setValue(ControlSeatBlock.LIT, shouldBeLit), 3);
        rs.checkGraphChanged(graph);
        if(evaluator==null||lastEvaluatedGraph!=graph) {
            if (lastEvaluatedGraph != null) {
                BusChannelHelper.syncDeletedBusNames(lastEvaluatedGraph, graph, worldPosition, level);
                unregisterBusChannels(lastEvaluatedGraph);
                runtimeState.pidState.clear();
            }
            evaluator = new GraphEvaluator(graph);
            evaluator.restoreSubState(runtimeState);
            lastEvaluatedGraph = graph;
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

        rs.refreshInputs();
        BusChannelHelper.recoverConflictedChannels(graph, worldPosition, level);
        var in = rs.buildInputs(graph);

        // 计算世界视角（仅视角差模式更新，摇杆模式下冻结）
        if (inputMode == 1) {
            var seatEntities = level.getEntitiesOfClass(
                com.example.create_schematic_compute.entity.ControlSeatEntity.class,
                new net.minecraft.world.phys.AABB(worldPosition).inflate(50));
            if (!seatEntities.isEmpty()) {
                float entityYaw = seatEntities.get(0).getYRot();
                savedWorldYaw = viewYaw + entityYaw;
                while (savedWorldYaw > 180) savedWorldYaw -= 360;
                while (savedWorldYaw < -180) savedWorldYaw += 360;
                savedWorldPitch = viewPitch;
            }
        }
        // 更新姿态/前方朝向（子类在 sable$physicsTick 中覆盖）
        updateAttitude();
        // 加速度：在稳定 20Hz 游戏 tick 下差分原始速度（物理 tick 下差分噪声太大）
        if (firstAccel) { prevRawVelX = rawVelX; prevRawVelY = rawVelY; prevRawVelZ = rawVelZ; firstAccel = false; }
        else {
            accelX = (float)((rawVelX - prevRawVelX) / 0.05);
            accelY = (float)((rawVelY - prevRawVelY) / 0.05);
            accelZ = (float)((rawVelZ - prevRawVelZ) / 0.05);
            prevRawVelX = rawVelX; prevRawVelY = rawVelY; prevRawVelZ = rawVelZ;
        }
        var seatInput = new GraphEvaluator.SeatInputState(keyBits, mouseJoystickX, mouseJoystickY, viewYaw, viewPitch,
            savedWorldYaw, savedWorldPitch, mouseButtons, gpadLX, gpadLY, gpadRX, gpadRY, gpadLT, gpadRT, gpadButtons,
            blockYaw, attitudeYaw, attitudePitch, attitudeRoll, forwardYaw, forwardPitch,
            accelX, accelY, accelZ,
            (float) rawVelX, (float) rawVelY, (float) rawVelZ,
            Float.isNaN(cachedSubWorldX) ? worldPosition.getX() + 0.5f : cachedSubWorldX,
            Float.isNaN(cachedSubWorldY) ? worldPosition.getY() + 0.5f : cachedSubWorldY,
            Float.isNaN(cachedSubWorldZ) ? worldPosition.getZ() + 0.5f : cachedSubWorldZ);

        float dt = 0.05f;
        var results = evaluator.evaluate(in, runtimeState.pidState, dt, seatInput);

        rs.writeOutputs(results);
        // BUS 频段变化时发包通知客户端
        BusChannelHelper.syncIfBandsChanged(graph, worldPosition, lastBusHashMap, level);
        setChanged();
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
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Failed to load control seat graph, resetting", e);
            graph = new NodeGraph();
            rs.onLoad(graph);
            setChanged();
        }
    }

    @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.saveAdditional(t,r);
        t.put("graph", graph.save(r));
        t.putBoolean("running", running);
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
    @Override public Component getDisplayName() { return Component.translatable("container."+SchematicCompute.MOD_ID+".control_seat"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new ControlSeatMenu(id, this); }
}
