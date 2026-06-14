package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.ModUtils;
import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.graph.GraphEvaluator;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
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
        int mouseButtons, float gpadLX, float gpadLY, float gpadRX, float gpadRY, long gpadButtons) {}
    private static final Map<java.util.UUID, InputState> PLAYER_INPUTS = new java.util.HashMap<>();

    public static void storeInput(java.util.UUID playerUuid, long keyBits, float mouseX, float mouseY,
        float yaw, float pitch, int mode, int mouseButtons,
        float gpadLX, float gpadLY, float gpadRX, float gpadRY, long gpadButtons) {
        synchronized (PLAYER_INPUTS) {
            PLAYER_INPUTS.put(playerUuid, new InputState(keyBits, mouseX, mouseY, yaw, pitch, mode,
                mouseButtons, gpadLX, gpadLY, gpadRX, gpadRY, gpadButtons));
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
    public final Map<Integer, Float> pidState = new HashMap<>();

    // 控制座椅输入状态（由客户端包更新） — volatile 确保 sable 物理线程可见
    public volatile long keyBits = 0;
    public volatile float mouseJoystickX = 0, mouseJoystickY = 0;
    public volatile float viewYaw = 0, viewPitch = 0;
    public volatile int inputMode = 0;         // 0=摇杆, 1=视角差
    public volatile int mouseButtons = 0;
    public volatile float gpadLX = 0, gpadLY = 0, gpadRX = 0, gpadRY = 0;
    public volatile long gpadButtons = 0;

    /** 世界视角缓存：仅视角差模式更新，摇杆模式/下马后冻结 */
    private float savedWorldYaw = 0, savedWorldPitch = 0;

    /** 子类（Sable compat）访问 */
    protected GraphEvaluator evaluator = null;
    protected NodeGraph lastEvaluatedGraph = null;
    protected final RedstoneLinkHelper rs = new RedstoneLinkHelper(this);
    /** 姿态/前方朝向（由子类 sable$physicsTick 更新） */
    protected float attitudeYaw = 0, attitudePitch = 0, attitudeRoll = 0;
    protected float forwardYaw = 0, forwardPitch = 0;
    protected float blockYaw = 0;

    public ControlSeatBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.CONTROL_SEAT_BE.get(), pos, s); }
    @Override public boolean isRunning() { return running; }
    @Override public void setRunning(boolean r) { running = r; setChanged(); if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }
    @Override public boolean graphHasCycles() { return graph.hasCycles(); }
    @Override public void clearPidState() { pidState.clear(); }

    @Override public void accept(net.minecraft.world.level.block.entity.BlockEntity other) {
        if(other instanceof ControlSeatBlockEntity src) {
            this.graph = src.graph;
            this.running = src.running;
            setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override public void onLoad() { super.onLoad(); rs.onLoad(graph); }
    @Override public void onChunkUnloaded() { super.onChunkUnloaded(); rs.onChunkUnloaded(); }
    @Override public void setRemoved() { rs.setRemoved(); super.setRemoved(); }

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
        if(!running) return;

        if (evaluator == null || lastEvaluatedGraph != graph) {
            evaluator = new GraphEvaluator(graph);
            lastEvaluatedGraph = graph;
            pidState.clear();
        }

        rs.refreshInputs();
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
        var seatInput = new GraphEvaluator.SeatInputState(keyBits, mouseJoystickX, mouseJoystickY, viewYaw, viewPitch,
            savedWorldYaw, savedWorldPitch, mouseButtons, gpadLX, gpadLY, gpadRX, gpadRY, gpadButtons,
            blockYaw, attitudeYaw, attitudePitch, attitudeRoll, forwardYaw, forwardPitch);

        float dt = 0.05f;
        var results = evaluator.evaluate(in, pidState, dt, seatInput);

        rs.writeOutputs(results);
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
            setChanged();
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
    }
    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t,r);
        if (t.contains("graph")) {
            graph = NodeGraph.load(t.getCompound("graph"), r);
            rs.onLoad(graph);
        }
        if (t.contains("running")) running = t.getBoolean("running");
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) { var t=new CompoundTag(); saveAdditional(t,r); return t; }
    @Override public Component getDisplayName() { return Component.translatable("container."+SchematicCompute.MOD_ID+".control_seat"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new ControlSeatMenu(id, this); }
}
