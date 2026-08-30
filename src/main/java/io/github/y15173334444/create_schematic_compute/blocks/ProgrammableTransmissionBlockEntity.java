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

import java.util.ArrayDeque;
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

    /**
     * 孤儿动力态预检（必须在 super.tick() 之前跑）：官方 validateKinetics 的清理顺序是
     * removeSource → detachKinetics —— 先把自己的速度清 0，detach 再走 handleRemoved 时
     * 因 getTheoreticalSpeed()==0 短路早退，**以我们为源的下游树不会被清洗**（下游残留
     * 旧源+旧转速）。之后任何一次 attach 传播里，下游轴会经无源自驱引导把本变速器
     * 反向收编（source 指向输出侧），随后 TX→上游边把绝对目标推向异号的上游速度，
     * 触发官方 incompatible 守卫 destroyBlock（炸方块根因，2026-08-31 RCON 探针定位）。
     * 趁自身速度仍非 0 先 detach，handleRemoved 正常清洗下游；此后的收编顺序
     * （上游/下游谁先）都无害 —— 零速邻居被官方 fromSpeed==0 守卫跳过。
     * Orphaned-kinetic-state pre-check (must run BEFORE super.tick()): official
     * validateKinetics removes the source (zeroing our speed) before detaching, so
     * handleRemoved early-returns on speed==0 and the downstream tree stays sourced
     * to us and spinning. The next attach pass then lets the downstream claim us
     * backwards (source on the output side) and the TX->upstream edge pushes the
     * absolute target into an opposite-sign upstream speed — official incompatible
     * guard destroys the block (root cause pinned via RCON probe, 2026-08-31).
     * Detaching while our own speed is still non-zero lets handleRemoved clean the
     * tree; afterwards the claim order (upstream vs downstream first) is harmless —
     * zero-speed neighbours are skipped by the official fromSpeed==0 guard.
     */
    private void cleanOrphanedKineticState() {
        if (level == null || level.isClientSide)
            return;
        if (hasSource()) {
            if (level.getBlockEntity(source) instanceof KineticBlockEntity sourceBE
                    && sourceBE.getSpeed() != 0)
                return;   // 源健在且在转：无需处理 / healthy source, nothing to do
            detachKinetics();   // 源已失速/丢失：趁速度非 0 清洗下游树 / source dead: clean tree now
        } else if (getSpeed() != 0) {
            detachKinetics();   // 幻影态（无源带速，如区块重载丢 source）：先清洗 / phantom speed: clean first
        } else {
            // 盲区：无源且速度 0，但下游树可能仍以我们为源挂在网上（NBT 重载/官方
            // validate 顺序漏洞的终态）。attach 前不清掉，下游会在传播里触发官方
            // epsilon-cycle（同网压制）或经依赖守卫外的路径炸方块。
            // Blind spot: sourceless AND zero speed, yet the downstream tree may still
            // be sourced to us (terminal state of the NBT reload / official validate
            // order hole). Clean it before any attach pass or the propagation hits the
            // official same-network epsilon-cycle guard.
            if (hasDependentNeighbours())
                forceCleanDependentTree();
        }
    }

    /** 是否存在仍以我们为源的邻居 / whether any neighbour is still sourced to us. */
    private boolean hasDependentNeighbours() {
        for (Direction d : Direction.values()) {
            if (level.getBlockEntity(worldPosition.relative(d)) instanceof KineticBlockEntity nb
                    && nb.hasSource() && nb.source.equals(worldPosition))
                return true;
        }
        return false;
    }

    /**
     * 强制清洗仍以本方块为源的下游子树（官方 handleRemoved 在 getTheoreticalSpeed()==0
     * 时短路早退，此状态下只能自walk）。逐节点调用官方 removeSource()（speed=0 +
     * source=null + setNetwork(null)），与 propagateMissingSource 的清理语义一致；
     * 不做潜在新源回搜 —— 随后的 attach 传播会按官方合并分支重建整棵树。
     * Forcibly clean the downstream subtree still sourced to us (official handleRemoved
     * early-returns at getTheoreticalSpeed()==0, so in that state we must walk
     * ourselves). Each node gets the official removeSource() (speed=0 + source=null +
     * setNetwork(null)) — same mutation semantics as propagateMissingSource, minus its
     * potential-new-source search; the attach pass right after rebuilds the tree
     * through the official merge branches anyway.
     */
    private void forceCleanDependentTree() {
        ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
        java.util.HashSet<BlockPos> visited = new java.util.HashSet<>();
        frontier.add(worldPosition);
        visited.add(worldPosition);
        while (!frontier.isEmpty()) {
            BlockPos pos = frontier.poll();
            for (Direction d : Direction.values()) {
                BlockPos np = pos.relative(d);
                if (!visited.add(np))
                    continue;
                if (!(level.getBlockEntity(np) instanceof KineticBlockEntity nb))
                    continue;
                if (nb.hasSource() && nb.source.equals(pos)) {
                    nb.removeSource();
                    frontier.add(np);
                }
            }
        }
    }

    @Override
    public void tick() {
        cleanOrphanedKineticState();
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

    /**
     * 官方 getDesiredOutputSpeed 逐行复刻（含无源自驱引导路径）。
     * Line-by-line replica of the official getDesiredOutputSpeed (including the
     * sourceless self-drive bootstrap).
     *
     * <p><b>依赖方守卫</b>：source 指向本变速器的邻居是<b>下游依赖方</b>，永远无权
     * 经无源自驱引导反向收编我们（自环）。没有这条守卫时：本变速器一旦静默进入
     * 无源态（官方 validateKinetics 的 removeSource→detach 顺序漏洞 / NBT 重载丢
     * source），下游轴会在下一次 attach 传播里把本变速器收编（source 指到输出侧），
     * 随后 TX→上游边把绝对目标推向异号的上游速度 → 官方 incompatible 守卫
     * destroyBlock 炸方块（2026-08-31 RCON 探针定位，RCON 台架 T2=64→128 必炸）。</p>
     * <p><b>Dependent guard</b>: a neighbour whose source points at us is a DOWNSTREAM
     * DEPENDENT and may never claim us backwards via the sourceless bootstrap (that is
     * a cycle). Without this, any silent sourceless state (official validateKinetics
     * removeSource->detach order hole / NBT reload losing source) lets the spinning
     * downstream shaft claim the TX on the next attach pass; the TX->upstream edge then
     * pushes the absolute target into an opposite-sign upstream speed and the official
     * incompatible guard destroys the block (pinned via RCON probe, 2026-08-31; the
     * bench T2=64->128 used to detonate deterministically).</p>
     */
    public static float getDesiredOutputSpeed(KineticBlockEntity from, ProgrammableTransmissionBlockEntity tx,
                                              boolean targetingController) {
        float targetSpeed = tx.appliedTarget;
        float speed = tx.getTheoreticalSpeed();
        float fromSpeed = from.getTheoreticalSpeed();

        // 依赖方（source 指向我们）边必须完美 no-op：返回我们自己的当前转速，
        // 使 newSpeed==邻速 → 官方 |差|≤1e-4 → continue。不能返回 0 —— 官方传播的
        // 兜底重挂载块会在「无分支命中」时把邻居 setSpeed(newSpeed)+setSource(我们)，
        // 返回 0 会让依赖方把我们静默改挂到自己名下（速度 0），随后在上游边触发
        // epsilon/incompatible destroyBlock（2026-08-31 RCON 台架 T4 跨号实测）。
        // A dependent (source pointing at us) edge must be a PERFECT no-op: return our
        // own current speed so newSpeed==neighbour speed -> official |diff|<=1e-4 ->
        // continue. Returning 0 is NOT safe: the official fall-through re-parent block
        // sets neighbour.setSpeed(newSpeed)+setSource(us) when no branch hits, which
        // silently re-parents us under the dependent at speed 0 and detonates the
        // upstream edge (bench T4 sign-cross, 2026-08-31).
        if (targetingController && from.hasSource() && from.source.equals(tx.getBlockPos()))
            return speed;

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
