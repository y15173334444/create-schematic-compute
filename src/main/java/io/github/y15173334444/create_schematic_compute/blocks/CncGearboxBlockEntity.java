package io.github.y15173334444.create_schematic_compute.blocks;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity;
import net.minecraft.core.Direction;
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
import net.minecraft.core.Direction;
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
 * 接合/分离走官方合并/失源路径（见 {@link CncGearboxBlock}）。两端轴面恒在
 * （放置吸附始终有效）；输出面转速修饰 {@link #getRotationSpeedModifier}：
 * 分离 0 / 负行程指令 -1（反转）/ 否则 1（官方 Clutch/SplitShaft 同款），不再抽掉
 * 输出轴面。</p>
 * <p><b>Clutch</b>: command executing or CLUTCH node intent → engage; idle →
 * disengage, via the official merge / missing-source paths. Both shaft faces stay
 * present (placement snap always works); the output face's
 * {@link #getRotationSpeedModifier} is 0 disengaged / -1 on a negative-travel
 * command (reverse) / 1 otherwise (official Clutch/SplitShaft) instead of
 * removing the output shaft face.</p>
 */
public class CncGearboxBlockEntity extends SplitShaftBlockEntity
        implements GearboxCommandSink, GraphBlockEntity, KineticEncoderView, io.github.y15173334444.create_schematic_compute.graph.KineticNetworkView {


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
        // 注入两种求值器定制：指令栈 sink 与编码器视图（GraphHost 每次重建求值器都会重放此回调）。
        // Inject both evaluator customizations: the command-stack sink and the encoder
        // view (GraphHost replays this callback on every evaluator rebuild).
        this.host.setEvaluatorCustomizer(ev -> {
            ev.setCommandSink(this);
            ev.setEncoderView(this);
            ev.setKineticNetworkView(this);
        });
    }

    /**
     * 官方 SplitShaft 转速修饰：输入面恒 1，输出面接合 1 / 分离 0。
     * 两端轴面已恒在（见 {@link CncGearboxBlock#hasShaftTowards}），分离时靠这里
     * 把输出面的传动比打成 0——{@code RotationPropagator.getAxisModifier} 只对
     * {@code SplitShaftBlockEntity} 调本方法，邻居再贴上来也不会把输出并进输入网。
     * Official SplitShaft speed modifier: always 1 on the input face; 1 engaged /
     * 0 disengaged on the output face. Both shaft faces stay present, so isolation
     * is this zero — {@code RotationPropagator.getAxisModifier} only calls this for
     * {@code SplitShaftBlockEntity}, and a shaft placed against the output will not
     * merge into the input network while disengaged.
     */
    /**
     * 官方 SplitShaft 转速修饰：输入面恒 1；输出面分离 0、接合且指令为负行程 -1、否则 1。
     * 负 ROTATE/MOVE = 输出相对输入反转（官方 Gearshift 的 -1 语义）；符号在入栈当帧
     * 快照，执行期间不跟引脚变（见 MotionCommand）。
     * Official SplitShaft speed modifier: always 1 on the input face; on the output
     * face — 0 disengaged, -1 while executing a NEGATIVE travel command (official
     * Gearshift reverse), 1 otherwise. The sign is snapshotted at enqueue and does
     * not follow the pin mid-command (see MotionCommand).
     */
    @Override
    public float getRotationSpeedModifier(Direction face) {
        BlockState st = getBlockState();
        if (face == CncGearboxBlock.inputFace(st, worldPosition))
            return 1f;
        if (face == CncGearboxBlock.outputFace(st, worldPosition)) {
            if (!st.getValue(CncGearboxBlock.ENGAGED))
                return 0f;
            return isOutputReversed() ? -1f : 1f;
        }
        return 1f;
    }

    /**
     * 当前执行中的 ROTATE/MOVE 是否为负行程（反转）。WAIT / 空闲 / CLUTCH 常接合
     * 不反转 —— 反转跟着「正在做的动作」的入栈符号走。
     * Whether the executing ROTATE/MOVE books a negative travel (reverse). WAIT /
     * idle / standing CLUTCH never reverse — the sign follows the enqueued action.
     */
    private boolean isOutputReversed() {
        return currentCommand != null
            && (currentCommand.kind() == NodeType.ROTATE || currentCommand.kind() == NodeType.MOVE)
            && currentCommand.value() < 0f;
    }

    // ── 每 tick / per tick ──

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide)
            return;
        autoSenseInputFace();
        host.ensureBusRegistered();
        host.flushPendingFullSync();

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

        // 编码器积分必须与配额记账**同一 tick 对齐**：runMotionControl 里配额刚按
        // |getSpeed()| 消耗过，这里以本 tick 的接合决策（clutchIntent）作门控积分——
        // 分离/空闲时位置保持、不计（「没有指令也在动」的修复），指令执行期间逐 tick
        // 与配额同量。此前门控读的是积分时刻的方块状态：ENGAGED 翻转（状态刷新）晚于
        // 配额消费一个 tick 时序，指令起止边界各丢一个 tick 的行程——256rpm 下一个
        // tick 就是 76.8°，ROTATE 90° 会只读出 ~13°（「状态刷新导致编码器输出不正确」）。
        // The encoder integral must be tick-aligned with the quota bookkeeping:
        // runMotionControl just burned |getSpeed()| against the quota, so integrate here
        // gated on THIS tick's engagement decision (clutchIntent) — hold when
        // disengaged/idle (the "moves without commands" fix), tick-for-tick equal to the
        // quota while executing. Gating on the blockstate instead lags the quota by one
        // tick across every ENGAGED flip (state refresh): at 256 rpm one tick is 76.8°,
        // so ROTATE 90° read back only ~13°.
        if (clutchIntent) {
            // 反转指令期间输出轴与网络反向：编码器按输出轴符号累计（与 modifier=-1 一致）。
            // While a reverse command runs the output shaft is opposite the network —
            // the encoder books the OUTPUT sign (matches modifier=-1).
            float signedSpeed = isOutputReversed() ? -getSpeed() : getSpeed();
            positionDeg += signedSpeed * MotionQuota.DEG_PER_RPM_TICK;
            positionDeg -= (float) Math.floor(positionDeg / 360f) * 360f;
            positionMeters += signedSpeed * MotionQuota.METERS_PER_RPM_TICK;
        }

        updateClutchState(clutchIntent);
        updateRunState();
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
     * 输入面自动跟随（仅空闲态）：分离 + 无源 + 停转、本侧邻轴不转而对侧邻轴在转时，
     * 自动把输入面翻向驱动侧并主动重探连接。用户「把动力换到另一侧」时方块会跟着
     * 换输入端——旧逻辑里输入面只在放置时感知一次，动力换边后台块死锁在旧端
     * （用户报的「内置轴输入/输出动画位置固定不随输入端变」）。
     * 输入面有源或本方块在转时不翻（运行中/有驱动的方块不自动换向）；两侧都在转时
     * 不翻（真歧义，保持现状）。状态翻转后必须显式 attachKinetics——仅 setBlock
     * 不会触发传播。
     * Input-face auto-follow (idle only): disengaged + sourceless + stopped, current
     * input-side neighbour not spinning while the OPPOSITE side spins -> flip the
     * input face toward the drive and re-probe the connection. Moving the power to
     * the other end now drags the input face with it — previously the face was only
     * sensed at placement, so re-routing the chain left the block dead-ended on its
     * old side (the reported "built-in shaft animation positions never follow the
     * switched input end"). Never flips while sourced/spinning/engaged, nor when
     * both sides spin (genuine ambiguity — stay put). The flip must call
     * attachKinetics explicitly: a bare setBlock triggers no propagation.
     */
    private void autoSenseInputFace() {
        BlockState st = getBlockState();
        if (!st.hasProperty(CncGearboxBlock.INPUT_NEGATIVE) || !st.hasProperty(CncGearboxBlock.ENGAGED))
            return;
        if (hasSource() || getSpeed() != 0 || st.getValue(CncGearboxBlock.ENGAGED))
            return;
        Direction in = CncGearboxBlock.inputFace(st, worldPosition);
        Direction out = in.getOpposite();
        boolean inSpins = neighbourSpins(in);
        boolean outSpins = neighbourSpins(out);
        if (!outSpins || inSpins)
            return;
        level.setBlock(worldPosition, st.cycle(CncGearboxBlock.INPUT_NEGATIVE), 3);
        attachKinetics();
    }

    /** 邻接方块实体是否在转 / whether the neighbour kinetic BE is spinning. */
    private boolean neighbourSpins(Direction d) {
        return level.getBlockEntity(worldPosition.relative(d)) instanceof KineticBlockEntity kbe
                && kbe.getSpeed() != 0;
    }

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

    /**
     * 运行状态灯同步：图未运行 → IDLE；图运行且无指令 → RUN；图运行且指令执行中 → COMMAND。
     * 仅在变化时 setBlock（图启停/指令边界，低频），驱动 blockstate 的 RUN_STATE 切换贴图
     * （cnc0 初始 / cnc1 运行 / cnc2 运行+指令）。由图运行态驱动（而非离合接合）。
     * Run-state lamp sync: graph not running → IDLE; running w/o command → RUN; running
     * with a command executing → COMMAND. setBlock only on change (graph start/stop and
     * command boundaries, low frequency) so the blockstate's RUN_STATE switches the
     * texture (cnc0 idle / cnc1 run / cnc2 command). Driven by graph running state,
     * not by clutch engagement.
     */
    private void updateRunState() {
        BlockState st = getBlockState();
        if (!st.hasProperty(CncGearboxBlock.RUN_STATE))
            return;
        CncGearboxBlock.RunState desired = !host.running
            ? CncGearboxBlock.RunState.IDLE
            : (currentCommand != null
                ? CncGearboxBlock.RunState.COMMAND
                : CncGearboxBlock.RunState.RUN);
        if (st.getValue(CncGearboxBlock.RUN_STATE) != desired)
            level.setBlock(worldPosition, st.setValue(CncGearboxBlock.RUN_STATE, desired), 3);
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

    @Override public float encoderVelocity() {
        // 分离时输出侧并未转动 —— 速度读 0；反转指令期间输出轴与网络反向，读负号。
        // While disengaged the output side is not turning — report 0; during a reverse
        // command the output shaft is opposite the network — report the flipped sign.
        if (!getBlockState().getValue(CncGearboxBlock.ENGAGED))
            return 0f;
        return isOutputReversed() ? -getSpeed() : getSpeed();
    }

    /** 复位引脚（电平触发）：角度与线性累计同时清零。
     *  Reset pin (level-triggered): zero both accumulators. */
    @Override
    public void resetEncoder() {
        positionDeg = 0f;
        positionMeters = 0f;
        setChanged();
    }

    // ── KineticNetworkView（STRESS / RPM 节点宿主视图）/ host view for STRESS / RPM ──
    // 读数 = 本方块所在网络的实时状态（不区分离合 —— 网络读数与输出侧是否传轴无关）。
    // Readings = the live state of OUR network (clutch-agnostic — network readings
    // are independent of whether the output side carries the shaft).

    @Override public float kineticSpeed() { return getSpeed(); }

    @Override public float kineticStress() { return stress; }

    @Override public float kineticCapacity() { return capacity; }

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
        // 客户端数据包只在**显式全量同步挂起**时携带整图（join / flagFullSync）：Create
        // 在每次转速变化/状态翻转都会 sendData，整图随包曾让打开编辑器的客户端反复整图
        // 重建（「断动力还在刷新图」）。图变化本就走 op 通道（编辑者）/ NBT 落盘（存档）；
        // running 等轻量字段照常随包同步。
        // Client packets ship the full graph only while an explicit full sync is pending
        // (join / flagFullSync): Create fires sendData on every speed change / state flip,
        // and graph-in-every-packet made open editors rebuild wholesale ("the graph
        // refreshes when power stops"). Graph changes travel as ops (editors) / NBT
        // (saves); light fields keep flowing in routine packets.
        if (!clientPacket || host.isFullSyncPending()) {
            host.saveHostNBT(tag, registries);
        } else {
            tag.putBoolean("running", host.running);
        }
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
    @Override public Map<Integer, Boolean> getFlipflopStates() { return host.getFlipflopStates(); }

    // ── GraphBlockEntity 宿主绑定回调 / contract host-binding callbacks ──

    @Override public BlockEntity asBlockEntity() { return this; }

    @Override public net.minecraft.world.level.Level getLevel() { return level; }

    @Override public BlockPos getBlockPos() { return worldPosition; }

    @Override public void setChanged() { super.setChanged(); }

    @Override public void sendBlockUpdated() {
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
}
