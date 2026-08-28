package io.github.y15173334444.create_schematic_compute.blocks;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.GearboxCommandSink;
import io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator;
import io.github.y15173334444.create_schematic_compute.graph.KineticEncoderView;
import io.github.y15173334444.create_schematic_compute.graph.MotionCommand;
import io.github.y15173334444.create_schematic_compute.graph.MotionQuota;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Map;

/**
 * 数控齿轮箱方块实体：从动件 + 离合器 + 指令栈 + 图托管。
 * CNC gearbox block entity: driven member + clutch + command stack + graph hosting.
 *
 * <p><b>运动模型</b>：转速完全跟随网络（本方块不设速）；指令执行期间离合接合，
 * 位置/行程按官方换算开环记账（{@link MotionQuota}：度 = speed×0.3/tick、
 * 米 = speed/512×dt），配额耗尽即完成并打完成脉冲。这一模型在数学上不可能出现
 * "窗口跳过 → 指令永不完成 → 栈卡死"（前代位置采样法的事故根因）。</p>
 * <p><b>Motion model</b>: speed follows the network (this block never sets speed);
 * while a command executes the clutch engages and travel is booked open-loop via the
 * official conversions ({@link MotionQuota}). The quota completes at zero — the
 * window-skip wedge ("command sent but output never stops") is mathematically
 * impossible.</p>
 *
 * <p><b>离合</b>：指令执行中或 CLUTCH 节点意图为真 → 接合；空闲 → 分离。
 * 接合/分离走官方合并/失源路径（见 {@link CncGearboxBlock}）。</p>
 */
public class CncGearboxBlockEntity extends KineticBlockEntity
        implements GearboxCommandSink, GraphBlockEntity, GraphHostOwner, KineticEncoderView {

    /** 组合式图托管核心。 Composition-based graph hosting core. */
    public final GraphHost host;

    // ── 指令栈与执行态 / command stack & execution state ──

    /** FIFO 指令栈（队首执行、队尾压入）。 FIFO stack: head executes, tail enqueues. */
    public final ArrayDeque<MotionCommand> commandStack = new ArrayDeque<>();
    /** 当前执行中的指令；null = 空闲。 Current command; null = idle. */
    private MotionCommand currentCommand;
    /** 当前指令剩余配额（度或米）。 Remaining quota of the current command (deg/m). */
    private MotionQuota quota;
    /** WAIT 计时器（tick）。 WAIT timer in ticks. */
    private int waitTimer;
    private Status status = Status.IDLE;

    public enum Status { IDLE, RUNNING }

    // ── 编码器 / encoder ──

    /** 旋转累计（度，0-360 归一）。 Rotary integral (degrees). */
    private float positionDeg = 0.0f;
    /** 线性累计（米）。 Linear integral (meters). */
    private float positionMeters = 0.0f;

    public CncGearboxBlockEntity(BlockPos pos, BlockState state) {
        super(SchematicCompute.CNC_GEARBOX_BE.get(), pos, state);
        this.host = new GraphHost(this);
        this.host.setEvaluatorCustomizer(ev -> ev.setCommandSink(this));
    }

    // ── 每 tick / per tick ──

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide)
            return;
        host.ensureBusRegistered();
        host.flushPendingFullSync();

        // 编码器积分跟随实际转速（过载自动冻结，官方语义）
        // Encoder integral follows the actual speed; overstress freezes automatically.
        positionDeg += getSpeed() * MotionQuota.DEG_PER_RPM_TICK;
        positionDeg -= (float) Math.floor(positionDeg / 360f) * 360f;
        positionMeters += getSpeed() * MotionQuota.METERS_PER_RPM_TICK;

        host.rs.checkGraphChanged(host.graph);
        if (host.graphChanged())
            host.recompileEvaluatorFull();

        boolean clutchIntent = false;
        if (!host.running) {
            host.onStopRunning();
            commandStack.clear();
            currentCommand = null;
            status = Status.IDLE;
        } else {
            host.rs.refreshInputsActive();
            var in = host.rs.buildInputs(host.graph);
            var results = host.evaluator.evaluate(in, host.runtimeState.pidState, 0.05f,
                    host.runtimeState.delayQueues, host.runtimeState.flipflopStates, host.runtimeState.pulseTimers);
            host.rs.writeOutputs(results);
            host.broadcastEvalSnapshot();
            host.evaluator.clearCompletedNodeId();
            clutchIntent = runMotionControl();
        }

        updateClutchState(clutchIntent);
        setChanged();
    }

    /**
     * 运动控制步：指令栈消费 + CLUTCH 常接合意图。返回本 tick 的离合意图。
     * Control step: command stack consumption + standing CLUTCH intent.
     * Returns this tick's clutch intent.
     */
    private boolean runMotionControl() {
        boolean clutchIntent = false;

        // —— CLUTCH 常接合意图（图中任意 CLUTCH 节点输出 >0.5 即接合）——
        // Standing CLUTCH intent (any CLUTCH node output >0.5 engages).
        for (var n : host.graph.nodes) {
            if (n.type == NodeType.CLUTCH) {
                float v = host.evaluator.getNodeOutput(n.id, 0);
                if (Float.isFinite(v) && v > 0.5f) { clutchIntent = true; break; }
            }
        }

        // —— 指令栈消费（优先级最高）：配额记账 + 完成脉冲 ——
        // Command stack consumption (highest priority): quota booking + done pulse.
        if (currentCommand == null && !commandStack.isEmpty()) {
            currentCommand = commandStack.pollFirst();
            waitTimer = 0;
            if (currentCommand.kind() == NodeType.ROTATE)
                quota = MotionQuota.of(currentCommand.value());
            else if (currentCommand.kind() == NodeType.MOVE)
                quota = MotionQuota.of(currentCommand.value());
            else
                quota = null;   // WAIT 用计时器 / WAIT uses the timer
            status = Status.RUNNING;
        }
        if (currentCommand != null) {
            clutchIntent = true;   // 指令执行期间保持接合 / stay engaged while executing
            switch (currentCommand.kind()) {
                case ROTATE -> {
                    // 助手内部取绝对值：负方向网络（-RPM）同样记账 —— 传原始带符号
                    // 值会让配额永不消耗，离合永远接合（"输入指令后一直在转"事故根因）。
                    // The helper takes |speed| internally: a negative-direction network
                    // must book travel too — raw signed values wedge the quota.
                    quota.consumeAbs(MotionQuota.degreesPerTick(getSpeed()));
                    if (quota.done()) completeCommand();
                }
                case MOVE -> {
                    quota.consumeAbs(MotionQuota.metersPerTick(getSpeed()));
                    if (quota.done()) completeCommand();
                }
                case WAIT -> {
                    waitTimer++;
                    if (waitTimer >= Math.max(0, Math.round(currentCommand.value())))
                        completeCommand();
                }
                default -> completeCommand();
            }
        }

        if (currentCommand == null && !clutchIntent)
            status = Status.IDLE;
        return clutchIntent;
    }

    private void completeCommand() {
        MotionCommand done = currentCommand;
        currentCommand = null;
        quota = null;
        if (done != null)
            host.evaluator.setCompletedNodeId(done.sourceNodeId());   // 下一 tick 该节点输出一帧 1
        status = commandStack.isEmpty() ? Status.IDLE : Status.RUNNING;
    }

    // ── 离合状态机 / clutch state machine ──

    /**
     * 接合/分离（官方路径）：分离 = detachKinetics（以下游失源收尾，自身仍被输入侧
     * 驱动）；接合 = 翻面后 attachKinetics（下游经合并分支并入本网络）。
     * Engage/disengage via official paths: disengage = detachKinetics (downstream
     * loses us as source; we stay driven by the input side); engage = flip then
     * attachKinetics (downstream merges into our network).
     */
    private void updateClutchState(boolean want) {
        BlockState st = getBlockState();
        if (!st.hasProperty(CncGearboxBlock.ENGAGED) || st.getValue(CncGearboxBlock.ENGAGED) == want)
            return;
        if (!want)
            detachKinetics();   // 通知以我们为源的下游失源重灌 / let the branch re-source
        level.setBlock(worldPosition, st.setValue(CncGearboxBlock.ENGAGED, want), 3);
        if (want)
            attachKinetics();   // 主动重探：下游并入本网络 / re-probe: branch merges in
    }

    /** 扳手翻面前的切除钩子：接合状态下先分离（保留指令栈与镜像）。
     *  Pre-flip sever hook: disengage first when engaged (stack preserved). */
    public void disengageForFlip() {
        BlockState st = getBlockState();
        if (level == null || level.isClientSide || !st.hasProperty(CncGearboxBlock.ENGAGED)
                || !st.getValue(CncGearboxBlock.ENGAGED))
            return;
        detachKinetics();
        level.setBlock(worldPosition, st.setValue(CncGearboxBlock.ENGAGED, false), 3);
    }

    // ── GearboxCommandSink：指令栈入队 / 急停 ──

    @Override
    public void enqueue(MotionCommand command) {
        if (commandStack.size() >= MotionCommand.MAX_STACK)
            return;   // 满则拒收（防高频触发积压）/ drop when full (anti-flood)
        commandStack.addLast(command);
        setChanged();
    }

    @Override
    public void emergencyStop() {
        commandStack.clear();
        currentCommand = null;
        quota = null;
        status = Status.IDLE;
        host.evaluator.setCompletedNodeId(null);
        setChanged();
    }

    // ── KineticEncoderView（ENCODER 节点宿主视图）/ encoder host view ──

    @Override public float encoderPosition() { return positionDeg; }

    @Override public float encoderPositionMeters() { return positionMeters; }

    @Override public float encoderVelocity() { return getSpeed(); }

    /** 复位引脚（电平触发）：角度与线性累计同时清零。
     *  Reset pin (level-triggered): zero both accumulators. */
    @Override
    public void resetEncoder() {
        positionDeg = 0f;
        positionMeters = 0f;
        setChanged();
    }

    // ── goggle 面板 / goggle overlay ──

    @Override
    public boolean addToGoggleTooltip(java.util.List<net.minecraft.network.chat.Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(net.minecraft.network.chat.Component.literal("│ CNC Gearbox")
                .withStyle(net.minecraft.ChatFormatting.GOLD));
        tooltip.add(net.minecraft.network.chat.Component.literal("│ status: " + status
                + "  |  clutch: " + (getBlockState().getValue(CncGearboxBlock.ENGAGED) ? "engaged" : "open")
                + "  |  queue: " + (commandStack.size() + (currentCommand != null ? 1 : 0)))
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        tooltip.add(net.minecraft.network.chat.Component.literal(String.format(java.util.Locale.ROOT,
                "│ speed: %d rpm  |  pos: %.1f° / %.3fm",
                (int) getSpeed(), positionDeg, positionMeters))
                .withStyle(net.minecraft.ChatFormatting.AQUA));
        return true;
    }

    // ── 生命周期 / lifecycle ──

    @Override public void onLoad() { super.onLoad(); host.onHostLoad(); }

    @Override public void onChunkUnloaded() { host.onHostChunkUnloaded(); super.onChunkUnloaded(); }

    @Override public void remove() { host.onHostRemoved(); super.remove(); }

    // ── NBT（Create 的 saveAdditional/read 为 final，钩子在 write/read）──

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        host.saveHostNBT(tag, registries);
        tag.put("CmdStack", MotionCommand.saveStack(commandStack));
        tag.putFloat("CscPosDeg", positionDeg);
        tag.putFloat("CscPosM", positionMeters);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        host.loadHostNBT(tag, registries);
        if (tag.contains("CmdStack"))
            MotionCommand.loadStack(tag.getList("CmdStack", Tag.TAG_COMPOUND), commandStack);
        else
            commandStack.clear();
        if (tag.contains("CscPosDeg")) positionDeg = tag.getFloat("CscPosDeg");
        if (tag.contains("CscPosM")) positionMeters = tag.getFloat("CscPosM");
    }

    // ── GraphBlockEntity 桥接 / interface bridges ──

    @Override public void loadGraphFromBytes(byte[] data) { host.loadGraphFromBytes(data); }

    @Override public NodeGraph getNodeGraph() { return host.graph; }

    @Override public boolean isRunning() { return host.isRunning(); }

    @Override public void setRunning(boolean r) { host.setRunning(r); }

    @Override public boolean graphHasCycles() { return host.graphHasCycles(); }

    @Override public void clearPidState() { host.clearPidState(); }

    @Override public void syncFlipflopStates(Map<Integer, Boolean> states) { host.syncFlipflopStates(states); }

    @Override public void syncSubFlipflopStates(Map<Integer, Map<Integer, Boolean>> subStates) { host.syncSubFlipflopStates(subStates); }

    @Override public void syncBusBandsFromServer(String busName, java.util.List<String> bands) { host.syncBusBandsFromServer(busName, bands); }

    @Override public EvalSnapshot getCachedEvalSnapshot() { return host.getCachedEvalSnapshot(); }

    @Override public void setCachedEvalSnapshot(EvalSnapshot snapshot) { host.setCachedEvalSnapshot(snapshot); }

    @Override public int getPendingLocalOps() { return host.getPendingLocalOps(); }

    @Override public void setPendingLocalOps(int value) { host.setPendingLocalOps(value); }

    @Override public void flagFullSync() { host.flagFullSync(); }

    @Override public void requestFullSync() { host.requestFullSync(); }

    @Override public boolean isGraphReady() { return host.isGraphReady(); }

    @Override public Map<Integer, Boolean> peekSubStateFlipflops(int encapNodeId) { return host.peekSubStateFlipflops(encapNodeId); }

    // ── GraphHostOwner 回调 / owner callbacks ──

    @Override public BlockEntity asBlockEntity() { return this; }

    @Override public net.minecraft.world.level.Level getLevel() { return level; }

    @Override public BlockPos getBlockPos() { return worldPosition; }

    @Override public void setChanged() { super.setChanged(); }

    @Override public void sendBlockUpdated() {
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
}
