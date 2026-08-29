package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.RuntimeState;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import io.github.y15173334444.create_schematic_compute.network.ChannelOwner;
import io.github.y15173334444.create_schematic_compute.network.ClientboundGraphEvalPacket;
import io.github.y15173334444.create_schematic_compute.network.SignalBus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 组合式图托管核心 —— 让挂在 Create {@code KineticBlockEntity} 继承线上的方块实体
 * （如 ProgrammableGearbox）无需继承 {@link SyncedGraphBlockEntity}（原生 BlockEntity
 * 线，Java 单继承冲突）即可获得完整的图托管能力。
 * Composition-based graph-hosting core — lets block entities on Create's
 * {@code KineticBlockEntity} inheritance line (e.g. ProgrammableGearbox) get full
 * graph-hosting capability without extending {@link SyncedGraphBlockEntity} (vanilla
 * BlockEntity line — Java single-inheritance conflict).
 *
 * <p>逻辑自 {@link SyncedGraphBlockEntity} 移植（Blueprint 风格的 recompileEvaluatorFull
 * 路径 + BUS 生命周期 + NBT 全套 + 编辑回弹保护），宿主 BE 持有一个本对象并在各
 * 生命周期钩子处转发。收敛重构（两线共同委托本类）按计划押后。</p>
 * <p>Logic ported from {@link SyncedGraphBlockEntity} (Blueprint-style
 * recompileEvaluatorFull path + BUS lifecycle + full NBT handling + editor bounce-back
 * protection). The hosting BE owns one instance and forwards lifecycle hooks. The
 * convergence refactor (both lines delegating to this class) is deferred by plan.</p>
 */
public class GraphHost {

    /** 最小全量同步间隔 tick。 / Min ticks between consecutive full syncs. */
    private static final int FULL_SYNC_GRACE_TICKS = 40;

    /** 宿主回调视图（图宿主契约）。 / Host callback view (the graph-host contract). */
    private final GraphBlockEntity owner;

    /** 托管节点图 —— 核心数据模型。 / Hosted node graph — core data model. */
    public NodeGraph graph = new NodeGraph();
    /** 是否每 tick 求值图。 / Whether to evaluate the graph every tick. */
    public boolean running = false;
    /** 运行时状态（PID 积分、触发器、延时队列等）。 / Runtime state (PID integrals, flipflops, delay queues...). */
    public final RuntimeState runtimeState = new RuntimeState();
    /** 红石链接 I/O 辅助器。 / Redstone link I/O helper. */
    protected final RedstoneLinkHelper rs;
    /** 每 tick 求值器；图变更时重建。 / Per-tick evaluator; rebuilt on graph change. */
    public GraphEvaluator evaluator = null;
    /** 当前求值器所基于的图引用（代数比对用）。 / Graph ref backing current evaluator (generation compare). */
    protected NodeGraph lastEvaluatedGraph = null;
    /** 上次构建求值器时的图代数。 / Graph generation at last evaluator build. */
    protected int lastGraphGeneration = -1;
    /** 上次求值的 BUS 哈希缓存。 / BUS hash cache from last evaluation. */
    protected final HashMap<Integer, Integer> lastBusHashMap = new HashMap<>();
    /** 待全量同步标志。 / Pending full-sync flag. */
    protected boolean needsFullSync = true;
    /** 上次全量同步时间。 / Last full-sync game time. */
    private long lastFullSyncGameTime = 0;
    /** 上次重编译的 BUS_OUT 键快照（删除检测）。 / BUS_OUT key snapshot from last recompile (removal detection). */
    private final Set<String> lastBusOutKeys = new HashSet<>();
    /** 服务端权威求值快照（客户端渲染读取）。 / Server-authoritative eval snapshot (client rendering). */
    public volatile EvalSnapshot cachedEvalSnapshot = EvalSnapshot.EMPTY;
    /** 首次 tick 注册 BUS 守卫。 / First-tick BUS registration guard. */
    private boolean busRegistrationPending = true;
    /** 客户端：图已从服务端加载。 / Client: graph loaded from server. */
    public transient boolean graphReady = false;
    /** 本地未 ACK op 计数（回弹保护）。 / Un-ACKed local ops (bounce guard). */
    public transient int pendingLocalOps = 0;

    /**
     * 求值器重建后的定制回调（宿主用它注入 encoderView 等）。
     * Post-rebuild evaluator customizer (host injects encoderView etc. through it).
     */
    @Nullable
    private Consumer<GraphEvaluator> evaluatorCustomizer;

    public GraphHost(GraphBlockEntity owner) {
        this.owner = owner;
        this.rs = new RedstoneLinkHelper(owner.asBlockEntity());
    }

    /** 设置求值器定制回调，并立即应用到现有求值器（若已存在）。
     *  Set the evaluator customizer; applies to an existing evaluator immediately if present. */
    public void setEvaluatorCustomizer(@Nullable Consumer<GraphEvaluator> c) {
        this.evaluatorCustomizer = c;
        if (evaluator != null && c != null) c.accept(evaluator);
    }

    // ── 宿主访问 / host accessors ──

    private BlockPos pos() { return owner.getBlockPos(); }
    @Nullable private net.minecraft.world.level.Level lvl() { return owner.getLevel(); }
    private void markDirty() { owner.setChanged(); }

    /** 触发放块更新使追踪客户端收到新 getUpdateTag。
     *  Trigger a block update so tracking clients receive a fresh getUpdateTag. */
    private void pushBlockUpdate() {
        owner.sendBlockUpdated();
    }

    // ── BUS 生命周期 / BUS lifecycle ──

    /** 每个 tick 开始调用：保证 BUS 通道至少注册一次（旧存档自动修复）。
     *  Call at tick start: guarantees channels register at least once (auto-repairs old saves). */
    public void ensureBusRegistered() {
        if (busRegistrationPending) {
            busRegistrationPending = false;
            registerBusChannels();
        }
    }

    /** 向服务端 SignalBus 注册图中全部 BUS_IN/BUS_OUT。
     *  Register all BUS_IN/BUS_OUT in the graph with the server SignalBus. */
    public void registerBusChannels() {
        if (BusChannelHelper.registerChannels(graph, pos(), lvl())) {
            needsFullSync = true;
            markDirty();
            pushBlockUpdate();
        }
    }

    /** 清理客户端 BUS band 缓存（卸载/移除时）。 / Clear client BUS band caches (unload/remove). */
    public void cleanupBusChannels(NodeGraph g) { BusChannelHelper.cleanupClientBands(g, pos(), lvl()); }

    /** 从服务端 SignalBus 注销 BUS_IN/BUS_OUT 通道。 / Unregister channels from the server SignalBus. */
    public void unregisterBusChannels(NodeGraph g) { BusChannelHelper.unregisterChannels(g, pos(), lvl()); }

    // ── 图变更检测与重编译 / change detection & recompile ──

    /** 求值器需要重建时为 true。 / True when the evaluator needs rebuilding. */
    public boolean graphChanged() {
        return evaluator == null || lastGraphGeneration != graph.graphGeneration;
    }

    /**
     * 强制下一 tick 重编译：把"上次构建代数"重置为 -1。
     * Force a recompile next tick by resetting the last-built generation to -1.
     *
     * <p>替换图的调用点（loadGraphFromBytes / 客户端包加载）在 {@code graph.bumpGeneration()}
     * 之后必须调用本方法：NodeGraph.load 产生 generation=0 的新图，bump 一次到 1 可能与
     * 上次重编译留下的 lastGraphGeneration=1 冲突，graphChanged() 为 false → 重编译（及
     * BUS 重注册）被跳过 → BUS_IN 读 0（回归审计：反复编译+运行失效）。
     * Call sites that replace the graph (loadGraphFromBytes / client packet loads) must
     * call this after {@code graph.bumpGeneration()}: a fresh loaded graph starts at
     * generation 0, and bumping once to 1 can collide with the prior compile's recorded
     * 1, making graphChanged() false — the recompile (and BUS re-registration) is skipped
     * and BUS_IN reads 0 (regression audit: repeated compile+run broke).
     * <p><b>不要</b>直接写 {@code lastGraphGeneration} 字段 —— 用本方法表达意图。
     * Do <b>not</b> poke the field directly; use this method to state the intent. */
    public void invalidateEvaluator() {
        lastGraphGeneration = -1;
    }

    /** 完整重建：保留主图全部运行时状态（仅剪除已删除节点）；注入定制回调。
     *  Full rebuild preserving all main-graph runtime state (pruned to alive nodes);
     *  applies the evaluator customizer. */
    public void recompileEvaluatorFull() {
        Map<Integer, Float> savedPid = null;
        Map<Integer, java.util.ArrayDeque<Float>> savedDelay = null;
        Map<Integer, Boolean> savedFf = null;
        Map<Integer, Integer> savedPulse = null;
        Map<Integer, Float> savedDebugTime = null;
        Map<Integer, RuntimeState.SubState> savedSubStates = null;
        // 触发电平记忆必须跨重编译保留：编辑器里每次改动都会重编译，清掉 nodeEdge
        // 会把"常高"触发误判为新上升沿 → 指令栈被反复入队（"输入指令后一直在转"）。
        // Trigger-level memory must survive recompiles: every editor change recompiles,
        // and wiping nodeEdge misjudges a held-high trigger as a NEW rising edge,
        // re-enqueueing commands forever ("command never stops").
        Map<Integer, Boolean> savedNodeEdge = null;
        NodeGraph oldGraph = lastEvaluatedGraph;
        if (oldGraph != null) {
            BusChannelHelper.syncDeletedBusNames(oldGraph, graph, pos(), lvl());
            savedPid = new HashMap<>(runtimeState.pidState);
            savedDelay = new HashMap<>(runtimeState.delayQueues);
            savedFf = new HashMap<>(runtimeState.flipflopStates);
            savedPulse = new HashMap<>(runtimeState.pulseTimers);
            savedDebugTime = new HashMap<>(runtimeState.debugTime);
            if (!runtimeState.subStates.isEmpty()) savedSubStates = new HashMap<>(runtimeState.subStates);
            savedNodeEdge = new HashMap<>(runtimeState.nodeEdge);
            runtimeState.clear();
        }
        unregisterRemovedBusOutNodes();
        evaluator = new GraphEvaluator(graph);
        if (savedSubStates != null) {
            var aliveIds = new HashSet<Integer>();
            for (var n : graph.nodes)
                if (n.type == NodeType.ENCAPSULATION) aliveIds.add(n.id);
            for (var entry : savedSubStates.entrySet())
                if (aliveIds.contains(entry.getKey()))
                    runtimeState.subStates.put(entry.getKey(), entry.getValue());
        }
        evaluator.restoreSubState(runtimeState);
        if (savedPid != null) runtimeState.pidState.putAll(savedPid);
        if (savedNodeEdge != null) runtimeState.nodeEdge.putAll(savedNodeEdge);
        if (savedDelay != null) runtimeState.delayQueues.putAll(savedDelay);
        if (savedFf != null) runtimeState.flipflopStates.putAll(savedFf);
        if (savedPulse != null) runtimeState.pulseTimers.putAll(savedPulse);
        if (savedDebugTime != null && !savedDebugTime.isEmpty()) {
            var alive = new HashSet<Integer>();
            for (var n : graph.nodes) alive.add(n.id);
            savedDebugTime.keySet().removeIf(k -> !RuntimeState.aliveStateKeys(alive).contains(k));
            evaluator.restoreDebugTimes(savedDebugTime);
        }
        pruneRuntimeStateToAlive();
        lastEvaluatedGraph = graph;
        lastGraphGeneration = graph.graphGeneration;
        if (evaluatorCustomizer != null) evaluatorCustomizer.accept(evaluator);
        if (BusChannelHelper.reRegisterChannels(graph, oldGraph, pos(), lvl())) {
            needsFullSync = true;
            markDirty();
            pushBlockUpdate();
        }
        snapshotBusOutKeys();
    }

    /** 按当前图剪除已删除节点的运行时状态。 / Prune runtime state of removed nodes. */
    private void pruneRuntimeStateToAlive() {
        var alive = new HashSet<Integer>();
        for (var n : graph.nodes) alive.add(n.id);
        runtimeState.pruneToAliveIds(alive);
    }

    /** 最小化重建（保留 debugTime，不搬其余运行时状态）。供 Monitor / SpeedProxy 使用。
     *  跨重建保留 debugTime（DEBUG_SIGNAL_GEN 相位累加器），使频率发生模式在图编辑后
     *  平滑继续；同时检测并注销已删除的 BUS_OUT 节点，防止已删除频道泄漏。
     *  与 Full 的实质差异：不 clear/restore 主图运行时状态（映射原样留存，
     *  末尾按存活节点剪除）。
     *  Minimal rebuild (preserves debugTime, moves no other runtime state); used by
     *  Monitor and SpeedProxy. Keeps the DEBUG_SIGNAL_GEN phase accumulator across
     *  rebuilds so frequency-generate mode continues smoothly after graph edits, and
     *  unregisters removed BUS_OUT nodes so deleted channels don't leak. The material
     *  difference from Full: it never clears/restores the main-graph runtime maps —
     *  they persist as-is and are pruned to alive nodes at the tail.
     *  <p>自 SyncedGraphBlockEntity.recompileEvaluatorLight 移植（阶段 1 收敛）；
     *  无求值器定制回调（该路径的宿主用不到）。
     *  Ported from SyncedGraphBlockEntity.recompileEvaluatorLight (phase 1 convergence);
     *  no evaluator customizer (that path's hosts never used one). */
    public void recompileEvaluatorLight() {
        // 销毁旧求值器前保存 debugTime / save debugTime before destroying the old evaluator
        if (evaluator != null) evaluator.saveDebugTimes(runtimeState);
        NodeGraph oldGraph = lastEvaluatedGraph;
        if (oldGraph != null) {
            BusChannelHelper.syncDeletedBusNames(oldGraph, graph, pos(), lvl());
        }
        // 注销自上次重编译以来已删除的 BUS_OUT 节点 / unregister BUS_OUT nodes removed since last recompile
        unregisterRemovedBusOutNodes();
        evaluator = new GraphEvaluator(graph);
        // 从 RuntimeState 恢复 debugTime，使频率模式相位保持 / restore debugTime so the phase persists
        if (!runtimeState.debugTime.isEmpty()) evaluator.restoreDebugTimes(runtimeState.debugTime);
        lastEvaluatedGraph = graph;
        lastGraphGeneration = graph.graphGeneration;
        // 保留积分状态（PID/ACCUMULATOR/INTEGRATOR），仅剪除已删除节点——编辑不再清空积分
        // Preserve integral state, prune only removed nodes — edits no longer wipe integrals.
        pruneRuntimeStateToAlive();
        // 基于差异的重新注册以保留频道所有权 / diff-based re-registration to keep channel ownership
        if (BusChannelHelper.reRegisterChannels(graph, oldGraph, pos(), lvl())) {
            needsFullSync = true;
            markDirty();
            pushBlockUpdate();
        }
        // 快照当前 BUS_OUT 键供下次重编译检测删除 / snapshot BUS_OUT keys for next recompile's removal detection
        snapshotBusOutKeys();
    }

    /** 注销上次重编译后已删除节点的 BUS_OUT。 / Unregister removed BUS_OUT since last recompile. */
    private void unregisterRemovedBusOutNodes() {
        if (lvl() == null || lvl().isClientSide() || lastBusOutKeys.isEmpty()) return;
        Set<String> currentKeys = new HashSet<>();
        for (var n : graph.nodes)
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty())
                currentKeys.add(n.signalName + "@" + n.id);
        for (var key : lastBusOutKeys) {
            if (!currentKeys.contains(key)) {
                int at = key.lastIndexOf('@');
                if (at > 0) {
                    String name = key.substring(0, at);
                    int nodeId = Integer.parseInt(key.substring(at + 1));
                    SignalBus.unregisterChannel(name, new ChannelOwner(pos(), nodeId));
                }
            }
        }
    }

    /** 快照当前 BUS_OUT 键集。 / Snapshot current BUS_OUT key set. */
    private void snapshotBusOutKeys() {
        lastBusOutKeys.clear();
        for (var n : graph.nodes)
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty())
                lastBusOutKeys.add(n.signalName + "@" + n.id);
    }

    // ── 引擎间接管（继承线合并用）/ inter-engine adoption (inheritance-line merge) ──

    /**
     * 吸收另一个引擎的状态：注销本图 BUS 通道 → 整体接管 graph/running → 清空运行时状态。
     * 由继承线（SyncedGraphBlockEntity）的 IMergeableBE.accept 在类型判定通过后调用；
     * 类型特定字段由宿主 BE 的 acceptTypeSpecific 钩子另行搬运，不经此方法。
     * Adopt another engine's state: unregister this graph's BUS channels, take over
     * graph/running wholesale, clear runtime state. Called from the inheritance line's
     * IMergeableBE.accept after the type check passes; type-specific fields are carried
     * by the host BE's acceptTypeSpecific hook, not here.
     *
     * @param src 被吸收的引擎 / the engine being absorbed */
    public void adoptFrom(GraphHost src) {
        // 先注销旧图的 BUS 频道——旧节点即将被整体替换，不注销会在 SignalBus 留下残留
        // 引用（泄漏）。Unregister the old graph's BUS channels first: the nodes are
        // about to be replaced wholesale, so skipping this leaks references.
        unregisterBusChannels(graph);
        graph = src.graph;
        running = src.running;
        runtimeState.clear();
    }

    // ── 运行态收尾 / stop-running helper ──

    /** 停止运行：清 BUS_OUT 与红石输出。 / Stop: clear BUS_OUT maps & write empty redstone outputs. */
    public void onStopRunning() {
        for (var n : graph.nodes) {
            if (n.type == NodeType.BUS_OUT && n.busInternalMap != null)
                n.busInternalMap.clear();
        }
        rs.writeOutputs(java.util.Collections.emptyList());
    }

    // ── 快照广播 / snapshot broadcast ──

    /** 向追踪客户端广播求值快照。 / Broadcast eval snapshot to tracking clients. */
    public void broadcastEvalSnapshot() {
        if (lvl() instanceof ServerLevel sl && evaluator != null) {
            evaluator.saveDebugTimes(runtimeState);
            var snapshot = evaluator.captureSnapshot();
            PacketDistributor.sendToPlayersTrackingChunk(sl, new ChunkPos(pos()),
                new ClientboundGraphEvalPacket(pos(), snapshot.outputs(), snapshot.debugTimes(),
                    snapshot.subOutputs(), snapshot.subDebugTimes(), snapshot.formulaSpreads()));
        }
    }

    // ── 全量同步 / full sync ──

    /** 强制下次推送携带完整图（编辑会话加入时）。 / Force full graph in next push (session join). */
    public void flagFullSync() {
        needsFullSync = true;
        lastFullSyncGameTime = (lvl() != null) ? lvl().getGameTime() : 0;
        markDirty();
        pushBlockUpdate();
    }

    /** 合并版全量同步请求（高频路径用，不立即发送）。 / Coalesced full-sync request (no immediate send). */
    public void requestFullSync() {
        if (lvl() == null) return;
        needsFullSync = true;
        markDirty();
    }

    /** 服务端 tick 调用：按 grace 间隔冲刷待同步请求。 / Flush pending request per grace interval (server tick). */
    public void flushPendingFullSync() {
        var l = lvl();
        if (l == null || l.isClientSide() || !needsFullSync) return;
        long now = l.getGameTime();
        if (now - lastFullSyncGameTime < FULL_SYNC_GRACE_TICKS) return;
        lastFullSyncGameTime = now;
        needsFullSync = false;
        pushBlockUpdate();
    }

    // ── 生命周期转发（由宿主 BE 的对应方法调用）/ lifecycle forwarding ──

    /** 宿主 onLoad 时调用。 / Call from host {@code onLoad}. */
    public void onHostLoad() {
        rs.onLoad(graph);
        var l = lvl();
        if (l != null && !l.isClientSide()) graph.bumpGeneration();   // 首 tick 强制重编译 / force first-tick recompile
    }

    /** 宿主 onChunkUnloaded 时调用。 / Call from host {@code onChunkUnloaded}. */
    public void onHostChunkUnloaded() {
        cleanupBusChannels(graph);
        unregisterBusChannels(graph);
        rs.onChunkUnloaded();
    }

    /** 宿主 setRemoved 时调用。 / Call from host {@code setRemoved}. */
    public void onHostRemoved() {
        cleanupBusChannels(graph);
        unregisterBusChannels(graph);
        rs.setRemoved();
    }

    // ── 编辑器保存包（服务端替换图）/ editor save packet ──

    /** 从压缩 NBT 字节整体替换图（多人文档协议入口）。
     *  Replace the graph from compressed NBT bytes (collab protocol entry). */
    public void loadGraphFromBytes(byte[] data) {
        var l = lvl();
        if (l == null) return;
        try {
            var t = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(2 * 1024 * 1024));
            if (t != null && t.contains("graph")) {
                unregisterBusChannels(graph);
                graph = NodeGraph.load(t.getCompound("graph"), l.registryAccess());
                // bump + 重置 lastGraphGeneration 保证下次 graphChanged()==true
                // （回归审计见 SyncedGraphBlockEntity.loadGraphFromBytes）
                // bump + reset guarantee graphChanged()==true next tick (regression audit
                // rationale lives in SyncedGraphBlockEntity.loadGraphFromBytes).
                graph.bumpGeneration();
                lastGraphGeneration = -1;
                runtimeState.subStates.clear();
                runtimeState.flipflopStates.clear();
                rs.onLoad(graph);
            }
            needsFullSync = true;
            markDirty();
            pushBlockUpdate();
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Failed to load graph for {} at {}, resetting",
                owner.asBlockEntity().getClass().getSimpleName(), pos(), e);
            graph = new NodeGraph();
            rs.onLoad(graph);
            markDirty();
        }
    }

    // ── NBT / NBT ──

    /** 写入公共字段（graph/running/runtime）；类型特定数据由宿主另行追加。
     *  Writes common fields (graph/running/runtime); type-specific data appended by host. */
    public void saveHostNBT(CompoundTag t, HolderLookup.Provider r) {
        t.put("graph", graph.save(r));
        t.putBoolean("running", running);
        t.put("runtime", runtimeState.save());
    }

    /**
     * 读取公共字段。客户端编辑保护（编辑器打开且有未 ACK 本地 op / 像素编辑中 /
     * 显示拖拽中时跳过本地图替换）统一走
     * {@link GraphBlockEntity#isGraphReplaceBlocked(int)}。
     * Reads common fields. Client editor-protection (skip local replacement while an
     * editor is open with un-ACKed ops, or during pixel painting / display dragging)
     * now goes through {@link GraphBlockEntity#isGraphReplaceBlocked(int)}.
     */
    public void loadHostNBT(CompoundTag t, HolderLookup.Provider r) {
        if (t.contains("graph")) {
            // 阶段 1：判定收敛到 GraphHostOwner —— 原先此处与 SyncedGraphBlockEntity
            // 各写一份，两线判定漂移会让客户端行为不一致。
            // Phase 1: the check converged into GraphHostOwner — this site and
            // SyncedGraphBlockEntity used to each keep a copy, and drift between them
            // would make the two lines behave differently on the client.
            if (!owner.isGraphReplaceBlocked(pendingLocalOps)) {
                graph = NodeGraph.load(t.getCompound("graph"), r);
                rs.onLoad(graph);
                this.graphReady = true;
                this.pendingLocalOps = 0;
                var client = lvl();
                if (client != null && client.isClientSide()) {
                    graph.bumpGeneration();
                } else {
                    // 服务端路径（NBT 加载 / /data merge）：新实例 gen 与上次重编译记录
                    // 可能同为 0，graphChanged() 将永远为假、求值器冻死在旧图上 —— 与
                    // loadGraphFromBytes 相同的组合拳强制下一 tick 重编译。
                    // Server path (world load / data merge): a fresh instance can collide
                    // with the recorded generation at 0, freezing the old evaluator
                    // forever — same forced-recompile combo as loadGraphFromBytes.
                    graph.bumpGeneration();
                    lastGraphGeneration = -1;
                }
            }
        }
        if (t.contains("running")) running = t.getBoolean("running");
        if (t.contains("runtime")) {
            // 运行时状态完整恢复（pid/延时/触发器/脉冲/调试时间/触发电平/子图）：
            // 方块实体随状态翻转重建（如数控齿轮箱离合 setBlock）或存档重载时，
            // 只恢复 pid 会丢掉其余全部时序与触发电平——触发电平丢失会把"常高"
            // 触发误判为新上升沿，指令栈被无限重新入队。
            // subStates 随阶段 0 一并补上：原生线（Blueprint/ProgramComputer/Radar）
            // 一直恢复子图状态，本线漏掉会让封装内的时序节点跨重载归零。
            // Full runtime-state restore: block entities recreated by state flips
            // (e.g. the CNC clutch setBlock) or world reloads previously lost every
            // map except pid — losing the trigger-level memory re-fired held-high
            // triggers and re-enqueued commands forever.
            // subStates added with phase 0: the native line (Blueprint /
            // ProgramComputer / Radar) has always restored sub-graph state, and missing
            // it here zeroed every timing node inside an encapsulation on reload.
            runtimeState.putAllFrom(RuntimeState.load(t.getCompound("runtime")));
        }
        markDirty();
    }

    // ── 接口桥接（宿主实现 GraphBlockEntity 时转发到这里）──
    //     Interface bridges (host forwards GraphBlockEntity methods here).

    /** @see GraphBlockEntity#isRunning */
    public boolean isRunning() { return running; }

    /** @see GraphBlockEntity#setRunning */
    public void setRunning(boolean r) { running = r; markDirty(); }

    /** @see GraphBlockEntity#graphHasCycles */
    public boolean graphHasCycles() { return graph.hasCycles(); }

    /** @see GraphBlockEntity#clearPidState */
    public void clearPidState() { runtimeState.pidState.clear(); }

    /** @see GraphBlockEntity#syncFlipflopStates */
    public void syncFlipflopStates(Map<Integer, Boolean> states) {
        runtimeState.flipflopStates.clear();
        if (states != null) runtimeState.flipflopStates.putAll(states);
    }

    /** @see GraphBlockEntity#syncSubFlipflopStates */
    public void syncSubFlipflopStates(Map<Integer, Map<Integer, Boolean>> subStates) {
        runtimeState.subStates.clear();
        if (subStates != null) {
            for (var entry : subStates.entrySet()) {
                var ss = runtimeState.getOrCreateSubState(entry.getKey());
                ss.flipflopStates.putAll(entry.getValue());
            }
        }
    }

    /** @see GraphBlockEntity#syncBusBandsFromServer */
    public void syncBusBandsFromServer(String busName, java.util.List<String> bands) {
        BusChannelHelper.syncBandsFromServer(busName, bands, graph);
    }

    /** @see GraphBlockEntity#getCachedEvalSnapshot */
    public EvalSnapshot getCachedEvalSnapshot() { return cachedEvalSnapshot; }

    /** @see GraphBlockEntity#setCachedEvalSnapshot */
    public void setCachedEvalSnapshot(EvalSnapshot snapshot) { if (snapshot != null) cachedEvalSnapshot = snapshot; }

    /** @see GraphBlockEntity#getPendingLocalOps */
    public int getPendingLocalOps() { return pendingLocalOps; }

    /** @see GraphBlockEntity#setPendingLocalOps */
    public void setPendingLocalOps(int value) { pendingLocalOps = Math.max(0, value); }

    /** @see GraphBlockEntity#isGraphReady */
    public boolean isGraphReady() { return graphReady; }

    /** @see GraphBlockEntity#getFlipflopStates */
    public Map<Integer, Boolean> getFlipflopStates() { return runtimeState.flipflopStates; }

    /** @see GraphBlockEntity#peekSubStateFlipflops */
    public Map<Integer, Boolean> peekSubStateFlipflops(int encapNodeId) {
        RuntimeState.SubState ss = runtimeState.subStates.get(encapNodeId);
        return ss != null ? ss.flipflopStates : java.util.Collections.emptyMap();
    }
}
