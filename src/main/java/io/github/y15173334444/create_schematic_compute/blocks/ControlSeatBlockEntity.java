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

/**
 * Control Seat Block Entity (控制座椅方块实体).
 * <p>
 * EN: The server-side logic for the Control Seat block. It accepts player input
 * (keyboard, mouse, gamepad) forwarded from the client, executes a compute graph
 * on the server tick, and writes results back to redstone / bus channels.
 * <p>
 * ZH: 控制座椅方块的服务端逻辑。它接收从客户端转发的玩家输入（键盘、鼠标、手柄），
 * 在服务端每 tick 执行计算图，并将结果写回红石/总线通道。
 */
public class ControlSeatBlockEntity extends SyncedGraphBlockEntity {

    // ══ Global input cache (per-player UUID) / 全局输入缓存（按玩家 UUID） ══

    /**
     * EN: Snapshot of a player's input state captured on the client and forwarded to the server.
     * ZH: 在客户端捕获并转发到服务端的玩家输入状态快照。
     *
     * @param keyBits      EN: bitmask of currently held keys / ZH: 当前按下的按键位掩码
     * @param mouseX       EN: mouse X delta (joystick emulation) / ZH: 鼠标 X 增量（模拟摇杆）
     * @param mouseY       EN: mouse Y delta (joystick emulation) / ZH: 鼠标 Y 增量（模拟摇杆）
     * @param yaw          EN: player view yaw / ZH: 玩家视角偏航角
     * @param pitch        EN: player view pitch / ZH: 玩家视角俯仰角
     * @param mode         EN: input mode (0=none, 1=world-relative, etc.) / ZH: 输入模式（0=无，1=世界相对等）
     * @param mouseButtons EN: bitmask of mouse buttons / ZH: 鼠标按键位掩码
     * @param gpadLX       EN: gamepad left stick X / ZH: 手柄左摇杆 X
     * @param gpadLY       EN: gamepad left stick Y / ZH: 手柄左摇杆 Y
     * @param gpadRX       EN: gamepad right stick X / ZH: 手柄右摇杆 X
     * @param gpadRY       EN: gamepad right stick Y / ZH: 手柄右摇杆 Y
     * @param gpadLT       EN: gamepad left trigger / ZH: 手柄左扳机
     * @param gpadRT       EN: gamepad right trigger / ZH: 手柄右扳机
     * @param gpadButtons  EN: bitmask of gamepad buttons / ZH: 手柄按钮位掩码
     */
    public record InputState(long keyBits, float mouseX, float mouseY, float yaw, float pitch, int mode,
        int mouseButtons, float gpadLX, float gpadLY, float gpadRX, float gpadRY, float gpadLT, float gpadRT, long gpadButtons) {}

    /** EN: Global input buffer keyed by player UUID. Shared across all Control Seat BE instances.
     *  ZH: 按玩家 UUID 索引的全局输入缓冲区，所有控制座椅方块实体实例共享。 */
    private static final java.util.Map<UUID, InputState> PLAYER_INPUTS = new java.util.HashMap<>();

    /**
     * EN: Store a player's latest input snapshot (called from the network packet handler on the server thread).
     * Thread-safe via internal synchronization.
     * ZH: 存储玩家最新的输入快照（由网络包处理器在服务端线程调用）。内部通过同步保证线程安全。
     *
     * @param playerUuid  EN: the player's UUID / ZH: 玩家的 UUID
     * @param keyBits     EN: bitmask of held keys / ZH: 按下的按键位掩码
     * @param mouseX      EN: mouse X delta / ZH: 鼠标 X 增量
     * @param mouseY      EN: mouse Y delta / ZH: 鼠标 Y 增量
     * @param yaw         EN: view yaw / ZH: 视角偏航角
     * @param pitch       EN: view pitch / ZH: 视角俯仰角
     * @param mode        EN: input mode / ZH: 输入模式
     * @param mouseButtons EN: mouse button bitmask / ZH: 鼠标按键位掩码
     * @param gpadLX      EN: gamepad left X / ZH: 手柄左摇杆 X
     * @param gpadLY      EN: gamepad left Y / ZH: 手柄左摇杆 Y
     * @param gpadRX      EN: gamepad right X / ZH: 手柄右摇杆 X
     * @param gpadRY      EN: gamepad right Y / ZH: 手柄右摇杆 Y
     * @param gpadLT      EN: gamepad left trigger / ZH: 手柄左扳机
     * @param gpadRT      EN: gamepad right trigger / ZH: 手柄右扳机
     * @param gpadButtons EN: gamepad button bitmask / ZH: 手柄按钮位掩码
     */
    public static void storeInput(UUID playerUuid, long keyBits, float mouseX, float mouseY,
        float yaw, float pitch, int mode, int mouseButtons,
        float gpadLX, float gpadLY, float gpadRX, float gpadRY, float gpadLT, float gpadRT, long gpadButtons) {
        synchronized (PLAYER_INPUTS) { PLAYER_INPUTS.put(playerUuid, new InputState(keyBits, mouseX, mouseY, yaw, pitch, mode, mouseButtons, gpadLX, gpadLY, gpadRX, gpadRY, gpadLT, gpadRT, gpadButtons)); }
    }

    /**
     * EN: Returns the number of players with pending input. Useful for diagnostics.
     * ZH: 返回有待处理输入的玩家数量，用于诊断。
     *
     * @return EN: count of buffered player inputs / ZH: 已缓冲的玩家输入数量
     */
    public static int pendingInputSize() { synchronized (PLAYER_INPUTS) { return PLAYER_INPUTS.size(); } }

    /**
     * EN: Discard all buffered player inputs. Called when the server resets or on cleanup.
     * ZH: 丢弃所有已缓冲的玩家输入。在服务端重置或清理时调用。
     */
    public static void clearAllInputs() { synchronized (PLAYER_INPUTS) { PLAYER_INPUTS.clear(); } }

    /**
     * EN: Remove buffered input for a specific player (e.g. on disconnect).
     * ZH: 移除指定玩家的缓冲输入（例如玩家断开连接时）。
     *
     * @param uuid EN: the player's UUID / ZH: 玩家的 UUID
     */
    public static void clearPlayerInput(UUID uuid) { synchronized (PLAYER_INPUTS) { PLAYER_INPUTS.remove(uuid); } }

    // ══ Control Seat input state / 控制座椅输入状态 ══

    /** EN: Bitmask of keys currently held by the seated player. / ZH: 座位上玩家当前按下的按键位掩码。 */
    public long keyBits = 0;
    /** EN: Mouse delta X used as a virtual joystick. / ZH: 用作虚拟摇杆的鼠标 X 增量。 */
    public float mouseJoystickX = 0, mouseJoystickY = 0;
    /** EN: Player's view yaw (degrees). / ZH: 玩家视角偏航角（度）。 */
    public float viewYaw = 0, viewPitch = 0;
    /** EN: Input mode (0=none, 1=world-relative). / ZH: 输入模式（0=无，1=世界相对）。 */
    public int inputMode = 0, mouseButtons = 0;
    /** EN: Gamepad left stick X/Y. / ZH: 手柄左摇杆 X/Y。 */
    public float gpadLX = 0, gpadLY = 0, gpadRX = 0, gpadRY = 0, gpadLT = 0, gpadRT = 0;
    /** EN: Gamepad button bitmask. / ZH: 手柄按钮位掩码。 */
    public long gpadButtons = 0;
    /** EN: Saved world-relative yaw/pitch for mode 1 (world-relative control).
     *  ZH: 为模式 1（世界相对控制）保存的世界相对偏航/俯仰角。 */
    private float savedWorldYaw = 0, savedWorldPitch = 0;

    /** EN: Attitude angles (yaw/pitch/roll) of the controlled entity, set by the compute graph.
     *  ZH: 被控实体的姿态角（偏航/俯仰/翻滚），由计算图设定。 */
    protected float attitudeYaw = 0, attitudePitch = 0, attitudeRoll = 0;
    /** EN: Forward direction yaw/pitch, and block facing yaw.
     *  ZH: 前进方向的偏航/俯仰角，以及方块朝向的偏航角。 */
    protected float forwardYaw = 0, forwardPitch = 0, blockYaw = 0;
    /** EN: Linear acceleration (blocks/tick²) computed from velocity changes.
     *  ZH: 根据速度变化计算出的线性加速度（方块/tick²）。 */
    protected float accelX = 0, accelY = 0, accelZ = 0;
    /** EN: Raw velocity components (blocks/tick) written by the compute graph.
     *  ZH: 由计算图写入的原始速度分量（方块/tick）。 */
    protected volatile double rawVelX, rawVelY, rawVelZ;
    /** EN: Cached sub-world position; NaN means "use block position".
     *  ZH: 缓存的子世界坐标；NaN 表示"使用方块坐标"。 */
    protected volatile float cachedSubWorldX = Float.NaN, cachedSubWorldY = Float.NaN, cachedSubWorldZ = Float.NaN;
    /** EN: Previous tick's raw velocity, used for acceleration calculation.
     *  ZH: 上一 tick 的原始速度，用于加速度计算。 */
    private double prevRawVelX, prevRawVelY, prevRawVelZ;
    /** EN: True until the first acceleration sample is computed (skip first tick).
     *  ZH: 在首个加速度采样计算完成前为 true（跳过首个 tick）。 */
    private boolean firstAccel = true;

    /** EN: Reference to the seat entity that the player sits in; set externally.
     *  ZH: 玩家乘坐的座椅实体引用，由外部设置。 */
    protected volatile io.github.y15173334444.create_schematic_compute.entity.ControlSeatEntity mySeatEntity = null;
    /** EN: Set the seat entity reference. / ZH: 设置座椅实体引用。 */
    public void setSeatEntity(io.github.y15173334444.create_schematic_compute.entity.ControlSeatEntity e) { mySeatEntity = e; }

    /**
     * EN: Construct a new Control Seat block entity.
     * ZH: 构造一个新的控制座椅方块实体。
     *
     * @param pos EN: block position in the world / ZH: 方块在世界中的位置
     * @param s   EN: the block state / ZH: 方块状态
     */
    public ControlSeatBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.CONTROL_SEAT_BE.get(), pos, s); }

    /**
     * EN: Factory method that creates the appropriate ControlSeatBlockEntity subclass.
     * If the Sable mod is loaded, uses a compatibility subclass that integrates with
     * Sable's rendering pipeline. Otherwise returns the base implementation.
     * ZH: 工厂方法，创建合适的 ControlSeatBlockEntity 子类。如果加载了 Sable 模组，
     * 则使用集成了 Sable 渲染管线的兼容子类，否则返回基础实现。
     *
     * @param pos   EN: block position / ZH: 方块位置
     * @param state EN: block state / ZH: 方块状态
     * @return EN: a ControlSeatBlockEntity instance (possibly a Sable-compat subclass) /
     *         ZH: ControlSeatBlockEntity 实例（可能是 Sable 兼容子类）
     */
    public static ControlSeatBlockEntity create(BlockPos pos, BlockState state) {
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
                Class<?> cls = Class.forName("io.github.y15173334444.create_schematic_compute.compat.ControlSeatBlockEntitySable");
                return (ControlSeatBlockEntity) cls.getConstructor(BlockPos.class, BlockState.class).newInstance(pos, state);
            }
        } catch (Exception ignored) {}
        return new ControlSeatBlockEntity(pos, state);
    }

    /**
     * EN: Called when a contraption is assembled and this BE is copied from another.
     * Transfers graph state and bus channel registrations, then triggers a block update.
     * ZH: 在机械装置组装、从另一个方块实体复制此 BE 时调用。
     * 转移图状态和总线通道注册，然后触发方块更新。
     *
     * @param other EN: the source block entity being copied from / ZH: 被复制的源方块实体
     */
    @Override public void accept(BlockEntity other) {
        if(other instanceof ControlSeatBlockEntity src) {
            unregisterBusChannels(graph);
            this.graph = src.graph; this.running = src.running; runtimeState.clear();
            setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * EN: Scan for nearby ControlSeatEntity and consume the first seated player's buffered input.
     * If no player is found, reset all input fields to zero.
     * ZH: 扫描附近的 ControlSeatEntity，消费第一个座位上玩家缓冲的输入。
     * 如果没有找到玩家，将所有输入字段重置为零。
     */
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
        // EN: No seated player found — zero out all inputs so the graph sees neutral state.
        // ZH: 未找到座位上的玩家——将所有输入归零，使计算图视为空输入状态。
        keyBits = 0; inputMode = 0; mouseJoystickX = 0; mouseJoystickY = 0;
    }

    /**
     * EN: Atomically retrieve and consume the buffered input for the given player UUID.
     * The input is removed from the global buffer so each snapshot is consumed exactly once.
     * ZH: 原子地获取并消费指定玩家 UUID 的缓冲输入。输入从全局缓冲区中移除，
     * 确保每个快照只被消费一次。
     *
     * @param playerUuid EN: the player's UUID / ZH: 玩家的 UUID
     */
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

    /**
     * EN: Adjust view angle before feeding into the graph. Base implementation is a no-op
     * because the client already sends the delta; subclasses may override for custom logic.
     * ZH: 将视角角度输入计算图前进行调整。基础实现为空，因为客户端已发送差值；
     * 子类可重写以实现自定义逻辑。
     */
    protected void adjustViewAngle() { /* EN: Client already sends delta; no extra adjustment needed. /
                                           ZH: 客户端已发差值，不做额外调整。 */ }

    /**
     * EN: Update attitude and forward direction based on the block's facing.
     * Called every tick before the graph is evaluated.
     * ZH: 根据方块朝向更新姿态和前进方向。每 tick 在图计算前调用。
     */
    protected void updateAttitude() {
        if (getBlockState().hasProperty(ControlSeatBlock.FACING)) {
            blockYaw = getBlockState().getValue(ControlSeatBlock.FACING).toYRot();
            attitudeYaw = blockYaw; forwardYaw = blockYaw;
        }
    }

    /**
     * EN: Main server-tick logic for the Control Seat.
     * <ol>
     * <li>Register bus channels if needed</li>
     * <li>Consume player input</li>
     * <li>Update the LIT block state to reflect run status</li>
     * <li>Recompile the evaluator if the graph changed</li>
     * <li>Recover conflicted bus channels</li>
     * <li>Build inputs, compute acceleration, evaluate the graph, write outputs</li>
     * <li>Broadcast evaluation snapshot to clients (for DEBUG_PROBE)</li>
     * <li>Sync bus channel bands if they changed</li>
     * </ol>
     * ZH: 控制座椅的主服务端 tick 逻辑。
     * <ol>
     * <li>必要时注册总线通道</li>
     * <li>消费玩家输入</li>
     * <li>更新 LIT 方块状态以反映运行状态</li>
     * <li>如果计算图发生变化，重新编译计算器</li>
     * <li>恢复冲突的总线通道</li>
     * <li>构建输入、计算加速度、执行图计算、写入输出</li>
     * <li>向客户端广播计算快照（供 DEBUG_PROBE 采样）</li>
     * <li>如果总线通道频段发生变化，进行同步</li>
     * </ol>
     */
    public void tick() {
        if(level==null||level.isClientSide()) return;
        ensureBusRegistered();
        consumeInput(); adjustViewAngle();
        // EN: Only light the block when a graph is loaded AND running, so the player has visual feedback.
        // ZH: 仅在已加载计算图并运行时点亮方块，给予玩家视觉反馈。
        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        var currentState = getBlockState();
        if (!currentState.hasProperty(ControlSeatBlock.LIT)) return;
        if(currentState.getValue(ControlSeatBlock.LIT)!=shouldBeLit)
            level.setBlock(worldPosition, currentState.setValue(ControlSeatBlock.LIT, shouldBeLit), 3);
        rs.checkGraphChanged(graph);
        if(graphChanged()) recompileEvaluator();
        if(!running) { onStopRunning(); return; }

        rs.refreshInputs();
        // EN: If bus channels were in conflict, force a full sync so all peers see the resolution.
        // ZH: 如果总线通道存在冲突，强制执行全量同步，使所有对等端获知解决结果。
        if (BusChannelHelper.recoverConflictedChannels(graph, worldPosition, level)) {
            needsFullSync = true; setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        var in = rs.buildInputs(graph);

        // EN: Mode 1 = world-relative input. Convert view-relative yaw/pitch into world-relative
        // by adding the seat entity's rotation so downstream nodes see absolute direction.
        // ZH: 模式 1 = 世界相对输入。通过加上座椅实体的旋转角，将视角相对偏航/俯仰
        // 转换为世界相对值，使下游节点看到绝对方向。
        if (inputMode == 1) {
            var seatEntities = level.getEntitiesOfClass(
                io.github.y15173334444.create_schematic_compute.entity.ControlSeatEntity.class,
                new net.minecraft.world.phys.AABB(worldPosition).inflate(50));
            if (!seatEntities.isEmpty()) {
                float entityYaw = seatEntities.get(0).getYRot();
                savedWorldYaw = viewYaw + entityYaw;
                // EN: Normalize to [-180, 180] so downstream math stays predictable.
                // ZH: 归一化到 [-180, 180]，保证下游计算可预测。
                while (savedWorldYaw > 180) savedWorldYaw -= 360; while (savedWorldYaw < -180) savedWorldYaw += 360;
                savedWorldPitch = viewPitch;
            }
        }
        updateAttitude();
        // EN: Compute acceleration as the discrete derivative of velocity (Δv / Δt).
        // Skip the first tick because there is no previous velocity to diff against.
        // ZH: 将加速度计算为速度的离散导数（Δv / Δt）。
        // 跳过首个 tick，因为没有上一帧的速度可做差分。
        if (firstAccel) { prevRawVelX = rawVelX; prevRawVelY = rawVelY; prevRawVelZ = rawVelZ; firstAccel = false; }
        else {
            accelX = (float)((rawVelX - prevRawVelX) / 0.05); accelY = (float)((rawVelY - prevRawVelY) / 0.05); accelZ = (float)((rawVelZ - prevRawVelZ) / 0.05);
            prevRawVelX = rawVelX; prevRawVelY = rawVelY; prevRawVelZ = rawVelZ;
        }
        // EN: Pack all input state into the evaluator's SeatInputState. Position defaults to block center
        // unless a sub-world override has been set (NaN check).
        // ZH: 将所有输入状态打包为计算器的 SeatInputState。位置默认为方块中心，
        // 除非已设置了子世界覆盖（NaN 检查）。
        var seatInput = new GraphEvaluator.SeatInputState(keyBits, mouseJoystickX, mouseJoystickY, viewYaw, viewPitch,
            savedWorldYaw, savedWorldPitch, mouseButtons, gpadLX, gpadLY, gpadRX, gpadRY, gpadLT, gpadRT, gpadButtons,
            blockYaw, attitudeYaw, attitudePitch, attitudeRoll, forwardYaw, forwardPitch,
            accelX, accelY, accelZ, (float)rawVelX, (float)rawVelY, (float)rawVelZ,
            Float.isNaN(cachedSubWorldX) ? worldPosition.getX()+0.5f : cachedSubWorldX,
            Float.isNaN(cachedSubWorldY) ? worldPosition.getY()+0.5f : cachedSubWorldY,
            Float.isNaN(cachedSubWorldZ) ? worldPosition.getZ()+0.5f : cachedSubWorldZ);

        var results = evaluator.evaluate(in, runtimeState.pidState, 0.05f, seatInput);
        rs.writeOutputs(results);
        // EN: Broadcast EvalSnapshot to clients so DEBUG_PROBE nodes can sample node outputs.
        // ZH: 广播 EvalSnapshot 给客户端，供 DEBUG_PROBE 节点采样节点输出。
        broadcastEvalSnapshot();
        BusChannelHelper.syncIfBandsChanged(graph, worldPosition, lastBusHashMap, level);
        setChanged();
    }

    /**
     * EN: Localized display name for the container UI.
     * ZH: 容器界面的本地化显示名称。
     *
     * @return EN: translated component / ZH: 翻译后的文本组件
     */
    @Override public Component getDisplayName() { return Component.translatable("container."+SchematicCompute.MOD_ID+".control_seat"); }

    /**
     * EN: Create the menu (server-side container) for this block entity.
     * ZH: 为此方块实体创建菜单（服务端容器）。
     *
     * @param id  EN: container sync ID / ZH: 容器同步 ID
     * @param inv EN: player inventory / ZH: 玩家物品栏
     * @param p   EN: the player opening the menu / ZH: 打开菜单的玩家
     * @return EN: a new ControlSeatMenu instance / ZH: 新的 ControlSeatMenu 实例
     */
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new ControlSeatMenu(id, this); }
}
