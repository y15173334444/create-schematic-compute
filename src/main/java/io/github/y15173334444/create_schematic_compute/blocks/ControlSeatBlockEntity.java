package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.ModUtils;
import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import javax.annotation.Nullable;
import java.util.UUID;

public class ControlSeatBlockEntity extends SyncedGraphBlockEntity {

    // ══ 全局输入缓存（按玩家 UUID） ══
    public record InputState(long keyBits, float mouseX, float mouseY, float yaw, float pitch, int mode,
        int mouseButtons, float gpadLX, float gpadLY, float gpadRX, float gpadRY, float gpadLT, float gpadRT, long gpadButtons) {}
    private static final java.util.Map<UUID, InputState> PLAYER_INPUTS = new java.util.HashMap<>();

    public static void storeInput(UUID playerUuid, long keyBits, float mouseX, float mouseY,
        float yaw, float pitch, int mode, int mouseButtons,
        float gpadLX, float gpadLY, float gpadRX, float gpadRY, float gpadLT, float gpadRT, long gpadButtons) {
        synchronized (PLAYER_INPUTS) { PLAYER_INPUTS.put(playerUuid, new InputState(keyBits, mouseX, mouseY, yaw, pitch, mode, mouseButtons, gpadLX, gpadLY, gpadRX, gpadRY, gpadLT, gpadRT, gpadButtons)); }
    }
    public static int pendingInputSize() { synchronized (PLAYER_INPUTS) { return PLAYER_INPUTS.size(); } }
    public static void clearAllInputs() { synchronized (PLAYER_INPUTS) { PLAYER_INPUTS.clear(); } }
    public static void clearPlayerInput(UUID uuid) { synchronized (PLAYER_INPUTS) { PLAYER_INPUTS.remove(uuid); } }

    // 控制座椅输入状态
    public long keyBits = 0; public float mouseJoystickX = 0, mouseJoystickY = 0;
    public float viewYaw = 0, viewPitch = 0; public int inputMode = 0, mouseButtons = 0;
    public float gpadLX = 0, gpadLY = 0, gpadRX = 0, gpadRY = 0, gpadLT = 0, gpadRT = 0;
    public long gpadButtons = 0;
    private float savedWorldYaw = 0, savedWorldPitch = 0;

    protected float attitudeYaw = 0, attitudePitch = 0, attitudeRoll = 0;
    protected float forwardYaw = 0, forwardPitch = 0, blockYaw = 0;
    protected float accelX = 0, accelY = 0, accelZ = 0;
    protected double rawVelX, rawVelY, rawVelZ;
    protected volatile float cachedSubWorldX = Float.NaN, cachedSubWorldY = Float.NaN, cachedSubWorldZ = Float.NaN;
    private double prevRawVelX, prevRawVelY, prevRawVelZ;
    private boolean firstAccel = true;

    protected volatile io.github.y15173334444.create_schematic_compute.entity.ControlSeatEntity mySeatEntity = null;
    public void setSeatEntity(io.github.y15173334444.create_schematic_compute.entity.ControlSeatEntity e) { mySeatEntity = e; }

    public ControlSeatBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.CONTROL_SEAT_BE.get(), pos, s); }

    public static ControlSeatBlockEntity create(BlockPos pos, BlockState state) {
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
                Class<?> cls = Class.forName("io.github.y15173334444.create_schematic_compute.compat.ControlSeatBlockEntitySable");
                return (ControlSeatBlockEntity) cls.getConstructor(BlockPos.class, BlockState.class).newInstance(pos, state);
            }
        } catch (Exception ignored) {}
        return new ControlSeatBlockEntity(pos, state);
    }

    @Override public void accept(BlockEntity other) {
        if(other instanceof ControlSeatBlockEntity src) {
            unregisterBusChannels(graph);
            this.graph = src.graph; this.running = src.running; runtimeState.clear();
            setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    protected void consumeInput() {
        if (level == null) return;
        var seats = level.getEntitiesOfClass(
            io.github.y15173334444.create_schematic_compute.entity.ControlSeatEntity.class,
            new net.minecraft.world.phys.AABB(worldPosition).inflate(2));
        for (var seat : seats) {
            for (var passenger : seat.getPassengers()) {
                if (passenger instanceof Player pl) { consumeInputByPlayer(pl.getUUID()); return; }
            }
        }
        keyBits = 0; inputMode = 0; mouseJoystickX = 0; mouseJoystickY = 0;
    }

    protected void consumeInputByPlayer(UUID playerUuid) {
        InputState s;
        synchronized (PLAYER_INPUTS) { s = PLAYER_INPUTS.remove(playerUuid); }
        if (s != null) {
            this.keyBits = s.keyBits; this.mouseJoystickX = s.mouseX; this.mouseJoystickY = s.mouseY;
            this.viewYaw = s.yaw; this.viewPitch = s.pitch; this.inputMode = s.mode;
            this.mouseButtons = s.mouseButtons;
            this.gpadLX = s.gpadLX; this.gpadLY = s.gpadLY; this.gpadRX = s.gpadRX; this.gpadRY = s.gpadRY;
            this.gpadLT = s.gpadLT; this.gpadRT = s.gpadRT; this.gpadButtons = s.gpadButtons;
        }
    }

    protected void adjustViewAngle() { /* 客户端已发差值，不做额外调整 */ }

    protected void updateAttitude() {
        if (getBlockState().hasProperty(ControlSeatBlock.FACING)) {
            blockYaw = getBlockState().getValue(ControlSeatBlock.FACING).toYRot();
            attitudeYaw = blockYaw; forwardYaw = blockYaw;
        }
    }

    public void tick() {
        if(level==null||level.isClientSide()) return;
        ensureBusRegistered();
        consumeInput(); adjustViewAngle();
        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        var currentState = getBlockState();
        if (!currentState.hasProperty(ControlSeatBlock.LIT)) return;
        if(currentState.getValue(ControlSeatBlock.LIT)!=shouldBeLit)
            level.setBlock(worldPosition, currentState.setValue(ControlSeatBlock.LIT, shouldBeLit), 3);
        rs.checkGraphChanged(graph);
        if(graphChanged()) recompileEvaluator();
        if(!running) { onStopRunning(); return; }

        rs.refreshInputs();
        BusChannelHelper.recoverConflictedChannels(graph, worldPosition, level);
        var in = rs.buildInputs(graph);

        if (inputMode == 1) {
            var seatEntities = level.getEntitiesOfClass(
                io.github.y15173334444.create_schematic_compute.entity.ControlSeatEntity.class,
                new net.minecraft.world.phys.AABB(worldPosition).inflate(50));
            if (!seatEntities.isEmpty()) {
                float entityYaw = seatEntities.get(0).getYRot();
                savedWorldYaw = viewYaw + entityYaw;
                while (savedWorldYaw > 180) savedWorldYaw -= 360; while (savedWorldYaw < -180) savedWorldYaw += 360;
                savedWorldPitch = viewPitch;
            }
        }
        updateAttitude();
        if (firstAccel) { prevRawVelX = rawVelX; prevRawVelY = rawVelY; prevRawVelZ = rawVelZ; firstAccel = false; }
        else {
            accelX = (float)((rawVelX - prevRawVelX) / 0.05); accelY = (float)((rawVelY - prevRawVelY) / 0.05); accelZ = (float)((rawVelZ - prevRawVelZ) / 0.05);
            prevRawVelX = rawVelX; prevRawVelY = rawVelY; prevRawVelZ = rawVelZ;
        }
        var seatInput = new GraphEvaluator.SeatInputState(keyBits, mouseJoystickX, mouseJoystickY, viewYaw, viewPitch,
            savedWorldYaw, savedWorldPitch, mouseButtons, gpadLX, gpadLY, gpadRX, gpadRY, gpadLT, gpadRT, gpadButtons,
            blockYaw, attitudeYaw, attitudePitch, attitudeRoll, forwardYaw, forwardPitch,
            accelX, accelY, accelZ, (float)rawVelX, (float)rawVelY, (float)rawVelZ,
            Float.isNaN(cachedSubWorldX) ? worldPosition.getX()+0.5f : cachedSubWorldX,
            Float.isNaN(cachedSubWorldY) ? worldPosition.getY()+0.5f : cachedSubWorldY,
            Float.isNaN(cachedSubWorldZ) ? worldPosition.getZ()+0.5f : cachedSubWorldZ);

        var results = evaluator.evaluate(in, runtimeState.pidState, 0.05f, seatInput);
        rs.writeOutputs(results);
        BusChannelHelper.syncIfBandsChanged(graph, worldPosition, lastBusHashMap, level);
        setChanged();
    }

    @Override public Component getDisplayName() { return Component.translatable("container."+SchematicCompute.MOD_ID+".control_seat"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new ControlSeatMenu(id, this); }
}
