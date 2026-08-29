package io.github.y15173334444.create_schematic_compute.blocks;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.KineticScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.infrastructure.config.AllConfigs;
import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

/**
 * 可编程变速器方块实体：官方 SpeedController 语义复刻的从动件。
 * Programmable transmission block entity: a driven member replicating the official
 * SpeedController semantics.
 *
 * <p><b>传播语义</b>（{@code RotationPropagatorMixin} 注入
 * {@code getConveyedSpeed}，四方向全覆盖，见
 * {@link #getConveyedSpeed}/{@link #getDesiredOutputSpeed}）：
 * 输入侧驱动本方块；输出侧按 {@code appliedTarget}（绝对值）向外传播；
 * 官方 max/min 符号钳制保证传播过程中不跨零。</p>
 *
 * <p><b>目标变更</b>：官方 {@code SpeedControllerBlockEntity.updateTargetRotation}
 * 同款拆建序列 —— network.remove → handleRemoved → removeSource → attachKinetics
 * （桌面源码核对，2026-08-28）。带冷却限频保护 flickerScore（官方 128 上限炸方块）。</p>
 *
 * <p><b>目标来源</b>：图运行且存在 {@code TX_OUT} 节点时取节点输出；否则回落
 * 滚轮设定值（官方 SC 同款 ValueBox）。</p>
 */
public class ProgrammableTransmissionBlockEntity extends KineticBlockEntity
        implements GraphBlockEntity {

    /** 组合式图托管核心。 Composition-based graph hosting core. */
    public final GraphHost host;

    /** 已应用目标转速（conveyed 取值、NBT 持久化）。
     *  Applied target speed (conveyed value, persisted). */
    private int appliedTarget = 0;
    /** 滚轮设定目标转速；图运行且有 TX_OUT 时被图覆盖。
     *  Scroll-set target; overridden by the graph while running with a TX_OUT node. */
    private int scrollTarget = 0;
    /** 期望目标（每 tick 对账）。 Desired target (reconciled per tick). */
    private int desiredTarget = 0;

    /** 滚轮行为（官方 SC 同款；behaviour 自带 NBT 持久化）。
     *  Scroll behaviour (official SC-style; self-persisted by the behaviour). */
    public KineticScrollValueBehaviour scrollBehaviour;

    /** 拆建冷却（tick）：限频保护官方 flickerScore（>128 炸方块）。
     *  Teardown cooldown (ticks): rate-limits to protect the official flickerScore
     *  (destroy above 128). */
    private static final int TEARDOWN_COOLDOWN_TICKS = 4;
    private long lastTeardownTime = -100000;

    public ProgrammableTransmissionBlockEntity(BlockPos pos, BlockState state) {
        super(SchematicCompute.TRANSMISSION_BE.get(), pos, state);
        this.host = new GraphHost(this);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        int max = AllConfigs.server().kinetics.maxRotationSpeed.get();
        scrollBehaviour = new KineticScrollValueBehaviour(
                net.minecraft.network.chat.Component.translatable(
                        "container.create_schematic_compute.transmission.scroll"),
                this, new TransmissionValueBoxTransform());
        scrollBehaviour.between(-max, max);
        scrollBehaviour.value = 0;
        scrollBehaviour.withCallback(i -> this.updateTargetRotation());
        behaviours.add(scrollBehaviour);
    }

    // ── 目标应用 / target application ──

    /** 每 tick 对账：期望目标变化且冷却已过 → 官方拆建序列。
     *  Per-tick reconciliation: desired changed & cooldown elapsed → official teardown. */
    private void reconcileTarget() {
        if (level == null || level.isClientSide)
            return;
        if (desiredTarget == appliedTarget)
            return;
        long now = level.getGameTime();
        if (now - lastTeardownTime < TEARDOWN_COOLDOWN_TICKS)
            return;
        appliedTarget = desiredTarget;
        lastTeardownTime = now;
        updateTargetRotation();
    }

    /**
     * 官方 SpeedController.updateTargetRotation 同款拆建序列（桌面源码逐行核对）：
     * 先整体脱网 + 通知下游失源重灌 + 清自身源，再重新 attach —— 重建路径全部走
     * 官方合并/失源分支，规避同网 epsilon 加速摧毁与跨号摧毁。
     * The official SpeedController.updateTargetRotation teardown sequence (verified
     * line-by-line against Desktop source): detach from network, let downstream
     * re-source, clear own source, re-attach — the rebuild runs through official
     * merge/missing-source branches only.
     */
    public void updateTargetRotation() {
        if (level == null || level.isClientSide)
            return;
        if (hasNetwork())
            getOrCreateNetwork().remove(this);
        detachKinetics();
        removeSource();
        attachKinetics();
        setChanged();
    }

    // ── 每 tick / per tick ──

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide)
            return;
        host.ensureBusRegistered();
        host.flushPendingFullSync();

        host.rs.checkGraphChanged(host.graph);
        if (host.graphChanged())
            host.recompileEvaluatorFull();

        int target;
        if (!host.running) {
            host.onStopRunning();
            target = scrollTarget();   // 停机回落滚轮值 / stopped: fall back to the scroll value
        } else {
            host.rs.refreshInputsActive();
            var in = host.rs.buildInputs(host.graph);
            var results = host.evaluator.evaluate(in, host.runtimeState.pidState, 0.05f,
                    host.runtimeState.delayQueues, host.runtimeState.flipflopStates, host.runtimeState.pulseTimers);
            host.rs.writeOutputs(results);
            host.broadcastEvalSnapshot();

            float max = AllConfigs.server().kinetics.maxRotationSpeed.get();
            target = scrollTarget();
            for (var n : host.graph.nodes) {
                if (n.type == NodeType.TX_OUT) {
                    float raw = host.evaluator.getNodeOutput(n.id, 0);
                    raw = Float.isFinite(raw) ? raw : 0;
                    target = Mth.clamp(Math.round(raw), (int) -max, (int) max);
                    break;   // 拓扑序首个 TX_OUT / first TX_OUT in topo order
                }
            }
        }
        desiredTarget = target;
        reconcileTarget();
        setChanged();
    }

    /** 滚轮当前值（behaviour 可能尚未注册——首 tick 前）。
     *  Current scroll value (behaviour may not be registered yet — pre first tick). */
    private int scrollTarget() {
        return scrollBehaviour != null ? scrollBehaviour.getValue() : scrollTarget;
    }

    // ── 传播语义（官方 SC 复刻，mixin 入口）/ propagation semantics (official SC replica, mixin entry) ──

    /**
     * 官方 SC conveyed 复刻（含 max/min 符号钳制：传播过程中不跨零）。
     * Official SC conveyed replica (with the max/min sign clamp: never crosses zero
     * mid-propagation).
     *
     * @param from                对端（输入侧邻居或输出侧下游）/ the other end
     * @param tx                  本变速器 / this transmission
     * @param targetingController true = 对端朝向本方块传播（驱动我们）；false = 本方块向外传播
     */
    public static float getConveyedSpeed(KineticBlockEntity from, ProgrammableTransmissionBlockEntity tx,
                                         boolean targetingController) {
        float targetSpeed = tx.appliedTarget;
        float speed = tx.getTheoreticalSpeed();
        float fromSpeed = from.getTheoreticalSpeed();
        float desiredOutputSpeed = getDesiredOutputSpeed(from, tx, targetingController);

        float compareSpeed = targetingController ? speed : fromSpeed;
        if (desiredOutputSpeed >= 0 && compareSpeed >= 0)
            return Math.max(desiredOutputSpeed, compareSpeed);
        if (desiredOutputSpeed < 0 && compareSpeed < 0)
            return Math.min(desiredOutputSpeed, compareSpeed);
        return desiredOutputSpeed;
    }

    /** 官方 getDesiredOutputSpeed 逐行复刻（含无源自驱引导路径）。
     *  Line-by-line replica of the official getDesiredOutputSpeed (including the
     *  sourceless self-drive bootstrap). */
    public static float getDesiredOutputSpeed(KineticBlockEntity from, ProgrammableTransmissionBlockEntity tx,
                                              boolean targetingController) {
        float targetSpeed = tx.appliedTarget;
        float speed = tx.getTheoreticalSpeed();
        float fromSpeed = from.getTheoreticalSpeed();

        if (targetSpeed == 0)
            return 0;
        if (targetingController && fromSpeed == 0)
            return 0;
        if (!tx.hasSource()) {
            // 官方语义保留：无源时 targeting 方向自驱 targetSpeed —— 这正是放置时的
            // 引导路径（否则 conveyed=0 → 没人认领我们 → 永远无源，鸡生蛋死锁）。
            // 无发电机的网络由官方过载保护兜底（容量 0 → overStressed → 停转）。
            // Official semantics kept: sourceless self-drive bootstraps the initial
            // drive-up (otherwise conveyed=0 → nobody claims us → sourceless forever).
            // Networks without a generator are caught by the official over-stress guard.
            if (targetingController)
                return targetSpeed;
            return 0;
        }

        boolean fromPowersTx = tx.source != null && tx.source.equals(from.getBlockPos());

        if (fromPowersTx) {
            if (targetingController)
                return targetSpeed;
            return fromSpeed;
        }

        if (targetingController)
            return speed;
        return targetSpeed;
    }

    // ── 滚轮值盒变换（官方 ControllerValueBoxTransform 同款）──
    //     Value box transform (official ControllerValueBoxTransform-style).

    private class TransmissionValueBoxTransform extends ValueBoxTransform.Sided {
        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(8, 11f, 15.5f);
        }

        @Override
        protected boolean isSideActive(BlockState state, Direction direction) {
            if (direction.getAxis().isVertical())
                return false;
            return state.getValue(ProgrammableTransmissionBlock.HORIZONTAL_AXIS) != direction.getAxis();
        }

        @Override
        public float getScale() {
            return 0.5f;
        }
    }

    // ── goggle 面板 / goggle overlay ──

    @Override
    public boolean addToGoggleTooltip(java.util.List<net.minecraft.network.chat.Component> tooltip, boolean isPlayerSneaking) {
        tooltip.add(net.minecraft.network.chat.Component.literal("│ Programmable Transmission")
                .withStyle(net.minecraft.ChatFormatting.GOLD));
        tooltip.add(net.minecraft.network.chat.Component.literal(String.format(java.util.Locale.ROOT,
                        "│ target: %d rpm  |  input: %s rpm",
                        appliedTarget,
                        hasSource() ? String.valueOf((int) getTheoreticalSpeed()) : "-"))
                .withStyle(net.minecraft.ChatFormatting.AQUA));
        tooltip.add(net.minecraft.network.chat.Component.literal("│ mode: " + (host.running ? "program" : "scroll"))
                .withStyle(net.minecraft.ChatFormatting.GRAY));
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
        tag.putInt("CscTxApplied", appliedTarget);
        tag.putInt("CscTxScroll", scrollTarget());
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        host.loadHostNBT(tag, registries);
        appliedTarget = tag.contains("CscTxApplied") ? tag.getInt("CscTxApplied") : 0;
        if (tag.contains("CscTxScroll")) scrollTarget = tag.getInt("CscTxScroll");
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
