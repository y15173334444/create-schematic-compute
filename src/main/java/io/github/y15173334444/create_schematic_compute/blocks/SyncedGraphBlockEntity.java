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
import com.simibubi.create.foundation.blockEntity.IMergeableBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Common base class for all graph-hosting block entities.
 * 所有托管图节点的方块实体的通用基类。
 *
 * <p>Consolidates the duplicated fields and lifecycle methods that were
 * copy-pasted across all 7 BE types. Subclasses implement their own
 * {@link #tick()} logic and call the protected helpers as needed.</p>
 * <p>整合了原本在全部 7 种 BE 类型中重复复制粘贴的字段与生命周期方法。
 * 子类实现各自的 {@link #tick()} 逻辑，并按需调用 protected 辅助方法。</p>
 *
 * <p>Provides:</p>
 * <p>提供：</p>
 * <ul>
 *   <li>Common fields: graph, running, runtimeState, evaluator, etc.</li>
 *   <li>通用字段：graph、running、runtimeState、evaluator 等。</li>
 *   <li>BUS channel lifecycle (register/cleanup/unregister) — no-op safe for BEs without BUS</li>
 *   <li>BUS 通道生命周期（注册/清理/注销）—— 对无 BUS 的 BE 安全空操作。</li>
 *   <li>RedstoneLinkHelper — available to all subclasses</li>
 *   <li>RedstoneLinkHelper —— 可供所有子类使用。</li>
 *   <li>Standard NBT save/load with type-specific hooks</li>
 *   <li>标准 NBT 保存/加载，带类型特定钩子。</li>
 *   <li>Standard getUpdateTag / flagFullSync with optional override</li>
 *   <li>标准的 getUpdateTag / flagFullSync，可按需覆写。</li>
 *   <li>EvalSnapshot broadcast (post-evaluation)</li>
 *   <li>EvalSnapshot 广播（求值后）。</li>
 * </ul>
 */
public abstract class SyncedGraphBlockEntity extends BlockEntity
        implements MenuProvider, IMergeableBE, GraphBlockEntity {

    // ── Common fields / 通用字段 ──

    /** The node graph hosted by this block entity — the core data model.
     *  此方块实体托管的节点图 —— 核心数据模型。 */
    public NodeGraph graph = new NodeGraph();

    /** Whether this block entity is currently evaluating its graph each tick.
     *  此方块实体当前是否每 tick 都在求值其图。 */
    public boolean running = false;

    /** Per-entity runtime state holding PID accumulators, flipflop states,
     *  sub-graph states, and debug timing data.
     *  每个实体的运行时状态，保存 PID 累加器、触发器状态、子图状态和调试计时数据。 */
    public final RuntimeState runtimeState = new RuntimeState();

    /** Helper for managing Create redstone link I/O bound to this block entity.
     *  管理绑定到此方块实体的 Create 红石链接输入/输出的辅助器。 */
    protected final RedstoneLinkHelper rs = new RedstoneLinkHelper(this);

    /** The evaluator that executes the graph each tick. Rebuilt on graph change.
     *  每 tick 执行图求值的求值器。图变更时重建。 */
    protected GraphEvaluator evaluator = null;

    /** Reference to the graph for which the current evaluator was built.
     *  Used to detect in-place mutations by comparing generations.
     *  指向当前求值器所基于的图的引用。用于通过比较代数来检测就地变更。 */
    protected NodeGraph lastEvaluatedGraph = null;

    /** Graph generation at the time of the last evaluator build.
     *  Compared against {@link NodeGraph#graphGeneration} to detect changes.
     *  上次构建求值器时的图代数。与 graphGeneration 比较以检测变更。 */
    protected int lastGraphGeneration = -1;

    /** Cached hash map of BUS channel states at the last evaluation.
     *  Key: node ID, Value: hashed bus state. Used to detect BUS value changes
     *  and trigger channel broadcasts only when values actually differ.
     *  上次求值时 BUS 通道状态的缓存哈希映射。
     *  Key: 节点 ID, Value: 哈希化的总线状态。用于检测 BUS 值变更，
     *  仅在值实际不同时才触发通道广播。 */
    protected final HashMap<Integer, Integer> lastBusHashMap = new HashMap<>();

    /** When true, the next {@link #getUpdateTag} call will include the full graph NBT.
     *  Set on graph mutation, BUS re-registration, or manual {@link #flagFullSync}.
     *  为 true 时，下一次 getUpdateTag 调用将包含完整图 NBT。
     *  在图变更、BUS 重新注册或手动调用 flagFullSync 时设置。 */
    protected boolean needsFullSync = true;

    /** Game time at which the last full sync was requested.
     *  Used together with {@link #FULL_SYNC_GRACE_TICKS} to throttle sync frequency.
     *  上次请求完整同步时的游戏时间。与 FULL_SYNC_GRACE_TICKS 配合使用以限制同步频率。 */
    private long lastFullSyncGameTime = 0;

    /** Minimum ticks between two consecutive full graph syncs.
     *  Prevents network spam when many graph mutations happen in quick succession.
     *  两次连续完整图同步之间的最小 tick 数。防止快速连续发生大量图变更时网络刷屏。 */
    private static final int FULL_SYNC_GRACE_TICKS = 40;

    /** Snapshot of BUS_OUT (signalName + "@" + nodeId) keys from the last recompile.
     *  Because {@link #lastEvaluatedGraph} is a reference (not a copy), it cannot
     *  detect node removals when the graph is mutated in-place. This set tracks
     *  the BUS_OUT key set at the last recompile so that removed nodes can be
     *  explicitly unregistered before {@link BusChannelHelper#reRegisterChannels}
     *  processes the new graph.
     *  上次重编译时 BUS_OUT (signalName + "@" + nodeId) 键的快照。
     *  因为 lastEvaluatedGraph 是引用（非副本），无法在就地修改图时检测到节点删除。
     *  此集合追踪上次重编译时的 BUS_OUT 键集，以便在 reRegisterChannels 处理新图之前
     *  显式取消注册已删除的节点。 */
    private final Set<String> lastBusOutKeys = new HashSet<>();

    /** Server-authoritative evaluation snapshot (Phase MVP — set by ClientboundGraphEvalPacket on client).
     *  服务端权威求值快照（MVP 阶段 —— 由 ClientboundGraphEvalPacket 在客户端设置）。 */
    public volatile EvalSnapshot cachedEvalSnapshot = EvalSnapshot.EMPTY;

    /** True until the first tick registers BUS channels — ensures old saves work without manual intervention.
     *  在第一次 tick 注册 BUS 通道之前为 true —— 确保旧存档无需手动干预即可正常工作。 */
    private boolean busRegistrationPending = true;

    /** Set to true on the client once the graph NBT has been loaded from the server.
     *  Allows the client UI (e.g. GraphEditor) to check whether the graph is ready for rendering.
     *  客户端在从服务端加载图 NBT 后设置为 true。允许客户端 UI（如 GraphEditor）检查图是否准备好渲染。 */
    public transient boolean graphReady = false;

    // ── BUS registration guard / BUS 注册守卫 ──

    /** Call at the start of each tick to guarantee BUS channels are registered at least once.
     *  This is a lazy one-shot guard: after the first call, busRegistrationPending flips to
     *  false and becomes a no-op. Designed so that old world saves (where BUS channels were
     *  never explicitly registered) get auto-repaired on first load.
     *  在每个 tick 开始时调用，以确保 BUS 通道至少被注册一次。
     *  这是一个惰性一次性守卫：首次调用后 busRegistrationPending 翻转为 false 并变为空操作。
     *  设计用于旧世界存档（BUS 通道从未显式注册过）在首次加载时自动修复。 */
    protected void ensureBusRegistered() {
        if (busRegistrationPending) {
            busRegistrationPending = false;
            registerBusChannels();
        }
    }

    // ── RedstoneLinkHelper accessors / RedstoneLinkHelper 访问器 ──

    /** Store a redstone input signal value for a given frequency key.
     *  存储给定频率键的红石输入信号值。
     *  @param freqKey the Create redstone link frequency key / Create 红石链接频率键
     *  @param signal  the redstone signal strength (0-15) / 红石信号强度（0-15） */
    public void putRedstoneInput(long freqKey, int signal) { rs.putInput(freqKey, signal); }

    /** Retrieve the last-known redstone input signal for a given frequency key.
     *  获取给定频率键的最后已知红石输入信号。
     *  @param freqKey the Create redstone link frequency key / Create 红石链接频率键
     *  @return the cached signal strength, or 0 if never set / 缓存的信号强度，从未设置则返回 0 */
    public int getRedstoneInput(long freqKey) { return rs.getInput(freqKey); }

    /**
     * Construct a new graph-hosting block entity.
     * 构造一个新的托管图的方块实体。
     *
     * @param type  the registered block entity type / 已注册的方块实体类型
     * @param pos   the block position in the world / 世界中的方块坐标
     * @param state the block state at this position / 此位置的方块状态
     */
    protected SyncedGraphBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ── GraphBlockEntity interface / GraphBlockEntity 接口 ──

    /** @return the node graph hosted by this BE / 此 BE 托管的节点图 */
    @Override public NodeGraph getNodeGraph() { return graph; }

    /** @return whether the graph evaluator is currently running / 图求值器当前是否在运行 */
    @Override public boolean isRunning() { return running; }

    /** Set the running state and mark the BE as changed for saving.
     *  设置运行状态并标记 BE 已变更以便保存。
     *  @param r true to start evaluation, false to stop / true 开始求值，false 停止 */
    @Override public void setRunning(boolean r) { running = r; setChanged(); }

    /** @return true if the graph contains directed cycles / 如果图包含有向环则返回 true */
    @Override public boolean graphHasCycles() { return graph.hasCycles(); }

    /** Clear all PID controller accumulators in the runtime state.
     *  清除运行时状态中的所有 PID 控制器累加器。 */
    @Override public void clearPidState() { runtimeState.pidState.clear(); }

    /** Replace flipflop states with the given map (used for client-server sync).
     *  用给定映射替换触发器状态（用于客户端-服务端同步）。
     *  @param states map of node ID to flipflop output value / 节点 ID 到触发器输出值的映射 */
    @Override public void syncFlipflopStates(java.util.Map<Integer, Boolean> states) {
        runtimeState.flipflopStates.clear();
        if (states != null) runtimeState.flipflopStates.putAll(states);
    }

    /** Replace sub-graph flipflop states from the server (used for ENCAPSULATION nodes).
     *  从服务端替换子图触发器状态（用于 ENCAPSULATION 节点）。
     *  @param subStates map of sub-graph ID to (node ID → flipflop value) / 子图 ID 到（节点 ID → 触发器值）的映射 */
    @Override public void syncSubFlipflopStates(java.util.Map<Integer, java.util.Map<Integer, Boolean>> subStates) {
        runtimeState.subStates.clear();
        if (subStates != null) {
            for (var entry : subStates.entrySet()) {
                var ss = runtimeState.getOrCreateSubState(entry.getKey());
                ss.flipflopStates.putAll(entry.getValue());
            }
        }
    }

    /** Apply BUS band list from the server to the client-side graph.
     *  将服务端的 BUS 频段列表应用到客户端图。
     *  @param busName the BUS signal name / BUS 信号名称
     *  @param bands   the list of band identifiers / 频段标识符列表 */
    @Override public void syncBusBandsFromServer(String busName, java.util.List<String> bands) {
        BusChannelHelper.syncBandsFromServer(busName, bands, graph);
    }

    // ── Redstone links lifecycle / 红石链接生命周期 ──

    /** Called when the block entity is added to the world.
     *  Loads redstone link associations and bumps graph generation to force a full
     *  recompile on the first tick (ensures BUS channels and evaluator caches are rebuilt).
     *  方块实体被添加到世界时调用。加载红石链接关联并递增图代数，
     *  以在首次 tick 时强制完全重编译（确保 BUS 通道和求值器缓存被重建）。 */
    @Override public void onLoad() {
        super.onLoad();
        rs.onLoad(graph);
        if (level != null && !level.isClientSide()) {
            // EN: Bump generation to force a full recompile on the first tick.
            // This ensures BUS channels, sub-graph state, and evaluator
            // caches are all rebuilt from the freshly loaded NBT.
            // 中文: 递增代数，以在第一次 tick 时强制完全重新编译。
            // 这确保了 BUS 通道、子图状态和求值器缓存都从新加载的 NBT 中重新构建。
            graph.bumpGeneration();
        }
    }

    /** Called when the chunk containing this BE is unloaded.
     *  Cleans up BUS channels on both client and server before the BE goes dormant.
     *  包含此 BE 的区块被卸载时调用。在 BE 休眠之前清理客户端和服务端的 BUS 通道。 */
    @Override public void onChunkUnloaded() { cleanupBusChannels(graph); unregisterBusChannels(graph); super.onChunkUnloaded(); rs.onChunkUnloaded(); }

    /** Called when the block is removed from the world.
     *  Performs full BUS channel teardown: client cleanup + server unregistration.
     *  方块从世界中移除时调用。执行完整的 BUS 通道拆除：客户端清理 + 服务端注销。 */
    @Override public void setRemoved() { cleanupBusChannels(graph); unregisterBusChannels(graph); rs.setRemoved(); super.setRemoved(); }

    // ── BUS channel lifecycle (safe no-ops — BEs without BUS just inherit these)
    //      BUS 通道生命周期（安全空操作 —— 无 BUS 的 BE 直接继承即可） ──

    /** Register all BUS_IN and BUS_OUT nodes in the current graph with the server's
     *  SignalBus. Called once on first tick (via {@link #ensureBusRegistered}) and
     *  again on each recompile. Triggers a block update if any channels were registered
     *  so that tracking clients receive the updated graph NBT including busConflict flags.
     *  向服务端 SignalBus 注册当前图中所有 BUS_IN 和 BUS_OUT 节点。
     *  首次 tick 时调用一次（通过 ensureBusRegistered），每次重编译时再次调用。
     *  如果有任何通道被注册，触发放块更新，使追踪客户端接收包含 busConflict 标志的更新图 NBT。 */
    protected void registerBusChannels() {
        if (BusChannelHelper.registerChannels(graph, worldPosition, level)) {
            needsFullSync = true;
            // Trigger a block update so tracking clients receive the full graph NBT
            // (including busConflict flags) via getUpdateTag(). Without this, clients
            // would not see busConflict changes until the next chunk re-send.
            // 触发放块更新，使追踪客户端通过 getUpdateTag() 接收完整图 NBT
            //（包含 busConflict 标志）。否则客户端在下次区块重新发送前无法看到 busConflict 变更。
            setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /** Clear client-side BUS band caches for all BUS_IN/BUS_OUT nodes in the graph.
     *  This broadcasts empty band lists; should only be called when the BE is being
     *  unloaded or removed — not during a graph reload.
     *  清除图中所有 BUS_IN/BUS_OUT 节点的客户端 BUS 频段缓存。
     *  这会广播空频段列表；应仅在 BE 被卸载或移除时调用 —— 而不是在图重载期间。
     *  @param g the graph whose BUS nodes to clean up / 要清理其 BUS 节点的图 */
    protected void cleanupBusChannels(NodeGraph g) {
        BusChannelHelper.cleanupClientBands(g, worldPosition, level);
    }

    /** Unregister all BUS_IN and BUS_OUT channels from the server's SignalBus.
     *  从服务端 SignalBus 注销所有 BUS_IN 和 BUS_OUT 通道。
     *  @param g the graph whose BUS nodes to unregister / 要注销其 BUS 节点的图 */
    protected void unregisterBusChannels(NodeGraph g) {
        BusChannelHelper.unregisterChannels(g, worldPosition, level);
    }

    // ── Graph change detection / 图变更检测 ──

    /** True when the evaluator needs rebuilding (graph changed since last check).
     *  Compares {@link #lastGraphGeneration} against the graph's current generation;
     *  also true when no evaluator has been built yet (evaluator == null).
     *  当求值器需要重建时为 true（自上次检查以来图结构已更改）。
     *  比较 lastGraphGeneration 与图的当前代数；尚未构建求值器时（evaluator == null）也为 true。 */
    protected boolean graphChanged() {
        return evaluator == null || lastGraphGeneration != graph.graphGeneration;
    }

    /** Rebuild evaluator and re-register BUS channels after a graph change.
     *  Only clears pidState — subStates (ENCAPSULATION sub-graph state) is preserved
     *  so that nested timing nodes retain their state across recompiles.
     *  Uses diff-based BUS re-registration ({@link BusChannelHelper#reRegisterChannels})
     *  to prevent newly-added BUS_OUT nodes from stealing channels from existing owners.
     *  在图结构变更后重建求值器并重新注册 BUS 通道。
     *  仅清除 pidState —— subStates（ENCAPSULATION 子图状态）会被保留，
     *  使嵌套时序节点在重编译期间保持其状态。
     *  使用基于差异的 BUS 重新注册（reRegisterChannels），防止新添加的 BUS_OUT 节点
     *  从现有所有者窃取频道。 */
    protected void recompileEvaluator() {
        NodeGraph oldGraph = lastEvaluatedGraph;
        if (oldGraph != null) {
            BusChannelHelper.syncDeletedBusNames(oldGraph, graph, worldPosition, level);
        }
        // Explicitly unregister BUS_OUT nodes that were removed since the last recompile.
        // Because lastEvaluatedGraph is a reference (not a copy), reRegisterChannels
        // alone cannot detect removals when the graph is mutated in-place.
        // 显式取消注册自上次重编译以来已删除的 BUS_OUT 节点。
        // 因为 lastEvaluatedGraph 是引用（而非副本），仅靠 reRegisterChannels 无法在
        // 就地修改图时检测到删除。
        unregisterRemovedBusOutNodes();
        evaluator = new GraphEvaluator(graph);
        evaluator.restoreSubState(runtimeState);
        lastEvaluatedGraph = graph;
        lastGraphGeneration = graph.graphGeneration;
        runtimeState.pidState.clear();
        // Use diff-based re-registration to preserve channel ownership.
        // This prevents a newly-added BUS_OUT with the same signalName from
        // stealing the channel from the existing owner during the recompile window.
        // 使用基于差异的重新注册以保留频道所有权。
        // 防止新添加的同名 BUS_OUT 在重编译窗口期间从现有所有者窃取频道。
        if (BusChannelHelper.reRegisterChannels(graph, oldGraph, worldPosition, level)) {
            needsFullSync = true;
            setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        // Snapshot current BUS_OUT keys for the next recompile's removal detection.
        // 快照当前 BUS_OUT 键，供下次重编译的删除检测使用。
        snapshotBusOutKeys();
    }

    /** Full rebuild that also resets delay queues, flipflop states and pulse timers.
     *  Used by Blueprint and ProgramComputer (which use timing/state nodes).
     *  Before clearing, saves and restores sub-graph state (DELAY queues, flipflops,
     *  pulse timers, PID state) so that encapsulation timing nodes survive the rebuild.
     *  Also restores debugTime so that frequency-generate mode phase persists.
     *  完全重建，同时重置延迟队列、触发器状态和脉冲计时器。
     *  供 Blueprint 和 ProgramComputer（使用时序/状态节点）使用。
     *  清除前保存并恢复子图状态（DELAY 队列、触发器、脉冲计时器、PID 状态），
     *  使封装内的时序节点在重建后仍然存活。同时恢复 debugTime 使频率发生模式相位保持。 */
    protected void recompileEvaluatorFull() {
        Map<Integer, Float> savedDebugTime = null;
        Map<Integer, RuntimeState.SubState> savedSubStates = null;
        NodeGraph oldGraph = lastEvaluatedGraph;
        if (oldGraph != null) {
            BusChannelHelper.syncDeletedBusNames(oldGraph, graph, worldPosition, level);
            savedDebugTime = new HashMap<>(runtimeState.debugTime);
            // Save sub-graph state before clearing — preserves DELAY queues,
            // flipflop states, pulse timers, and PID state for encapsulation
            // timing nodes across recompiles.
            // 清除前保存子图状态——保留封装内 DELAY 队列、触发器、脉冲计时器
            // 和 PID 状态，跨重编译保持时序节点连续性。
            if (!runtimeState.subStates.isEmpty()) {
                savedSubStates = new HashMap<>(runtimeState.subStates);
            }
            runtimeState.clear();
        }
        unregisterRemovedBusOutNodes();
        evaluator = new GraphEvaluator(graph);
        // Restore sub-graph state to runtimeState so the new evaluator picks it up,
        // but only for ENCAPSULATION nodes still present in the current graph —
        // prunes state for deleted encaps so stale DELAY/flipflop/PID no longer
        // leaks into NBT or keeps the flipflop diff perpetually dirty.
        // 将子图状态恢复到 runtimeState，使新评估器能获取；但只恢复当前图中
        // 仍存在的 ENCAPSULATION 节点——剪除已删除封装的状态，避免陈旧
        // DELAY/flipflop/PID 泄漏入 NBT 或让 flipflop diff 永远处于变更态。
        if (savedSubStates != null) {
            var aliveIds = new java.util.HashSet<Integer>();
            for (var n : graph.nodes)
                if (n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.ENCAPSULATION)
                    aliveIds.add(n.id);
            for (var entry : savedSubStates.entrySet()) {
                if (aliveIds.contains(entry.getKey()))
                    runtimeState.subStates.put(entry.getKey(), entry.getValue());
            }
        }
        evaluator.restoreSubState(runtimeState);
        if (savedDebugTime != null && !savedDebugTime.isEmpty()) {
            evaluator.restoreDebugTimes(savedDebugTime);
        }
        lastEvaluatedGraph = graph;
        lastGraphGeneration = graph.graphGeneration;
        // Use diff-based re-registration to preserve channel ownership.
        // 使用基于差异的重新注册以保留频道所有权。
        if (BusChannelHelper.reRegisterChannels(graph, oldGraph, worldPosition, level)) {
            needsFullSync = true;
            setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        // Snapshot current BUS_OUT keys for the next recompile's removal detection.
        // 快照当前 BUS_OUT 键，供下次重编译的删除检测使用。
        snapshotBusOutKeys();
    }

    /** Minimal rebuild (no BUS lifecycle). Used by Monitor and SpeedProxy.
     *  最小化重建（无 BUS 生命周期操作）。供 Monitor 和 SpeedProxy 使用。
     *  Preserves debugTime (DEBUG_SIGNAL_GEN phase accumulator) across rebuilds so that
     *  frequency-generate mode continues smoothly after graph edits.
     *  Also detects and unregisters removed BUS_OUT nodes so that deleted channels
     *  don't leak in SignalBus.CHANNELS.
     *  跨重建保留 debugTime（信号发生器相位累加器），使频率发生模式在图编辑后平滑继续。
     *  同时检测并注销已删除的 BUS_OUT 节点，防止已删除频道在 SignalBus.CHANNELS 中泄漏。 */
    protected void recompileEvaluatorLight() {
        // Save debugTime before destroying the old evaluator / 销毁旧求值器前保存 debugTime
        if (evaluator != null) evaluator.saveDebugTimes(runtimeState);
        NodeGraph oldGraph = lastEvaluatedGraph;
        if (oldGraph != null) {
            BusChannelHelper.syncDeletedBusNames(oldGraph, graph, worldPosition, level);
        }
        // Unregister BUS_OUT nodes that were removed since last recompile / 注销自上次重编译以来已删除的 BUS_OUT 节点
        unregisterRemovedBusOutNodes();
        evaluator = new GraphEvaluator(graph);
        // Restore debugTime from RuntimeState so frequency mode phase persists
        // 从 RuntimeState 恢复 debugTime，使频率模式相位保持
        if (!runtimeState.debugTime.isEmpty()) evaluator.restoreDebugTimes(runtimeState.debugTime);
        lastEvaluatedGraph = graph;
        lastGraphGeneration = graph.graphGeneration;
        runtimeState.pidState.clear();
        // Use diff-based re-registration (same as recompileEvaluator) to preserve channel
        // ownership across recompiles. 使用基于差异的重新注册（与 recompileEvaluator 相同），
        // 在重编译期间保留频道所有权。
        if (BusChannelHelper.reRegisterChannels(graph, oldGraph, worldPosition, level)) {
            needsFullSync = true;
            setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        // Snapshot current BUS_OUT keys for next recompile's removal detection / 快照当前 BUS_OUT 键供下次重编译检测删除
        snapshotBusOutKeys();
    }

    // ── BUS_OUT removal detection (works around lastEvaluatedGraph reference sharing) ──
    //     BUS_OUT 删除检测（解决 lastEvaluatedGraph 引用共享问题）

    /** Unregister BUS_OUT channels for nodes that were present in the last recompile
     *  snapshot ({@link #lastBusOutKeys}) but are no longer in the current {@link #graph}.
     *  Called before {@link BusChannelHelper#reRegisterChannels} so that removed nodes
     *  are properly cleaned up even when the graph was mutated in-place.
     *  取消注册在上次重编译快照 (lastBusOutKeys) 中存在但当前 graph 中已不存在的
     *  BUS_OUT 节点频道。在 reRegisterChannels 之前调用，确保即使在就地修改图时，
     *  已删除的节点也能被正确清理。 */
    private void unregisterRemovedBusOutNodes() {
        if (level == null || level.isClientSide() || lastBusOutKeys.isEmpty()) return;
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
                    SignalBus.unregisterChannel(name, new ChannelOwner(worldPosition, nodeId));
                }
            }
        }
    }

    /** Snapshot the current BUS_OUT (signalName, nodeId) key set into
     *  {@link #lastBusOutKeys} for the next recompile's removal detection.
     *  将当前 BUS_OUT (signalName, nodeId) 键集快照到 lastBusOutKeys 中，
     *  供下次重编译的删除检测使用。 */
    private void snapshotBusOutKeys() {
        lastBusOutKeys.clear();
        for (var n : graph.nodes)
            if (n.type == NodeType.BUS_OUT && !n.signalName.isEmpty())
                lastBusOutKeys.add(n.signalName + "@" + n.id);
    }

    // ── Not-running helper / 停止运行辅助方法 ──

    /** Clear BUS_OUT maps and write empty redstone outputs when stopped.
     *  Called by subclasses when the running state transitions to false.
     *  Prevents stale output values from persisting after the graph stops.
     *  停止时清除 BUS_OUT 映射并写入空的红石输出。
     *  由子类在运行状态转换为 false 时调用。防止图停止后残留过时的输出值。 */
    protected void onStopRunning() {
        for (var n : graph.nodes) {
            if (n.type == NodeType.BUS_OUT && n.busInternalMap != null)
                n.busInternalMap.clear();
        }
        rs.writeOutputs(java.util.Collections.emptyList());
    }

    // ── EvalSnapshot broadcast / EvalSnapshot 广播 ──

    /** Broadcast eval snapshot to tracking clients after evaluation completes.
     *  Saves debugTime to RuntimeState before capturing the snapshot so that
     *  frequency-generate phase data is included in the packet payload.
     *  The packet is sent via {@link PacketDistributor#sendToPlayersTrackingChunk}
     *  so only clients with this chunk loaded receive it.
     *  求值完成后向追踪客户端广播求值快照。
     *  捕获快照前将 debugTime 保存到 RuntimeState，使频率发生相位数据包含在数据包中。
     *  数据包通过 PacketDistributor.sendToPlayersTrackingChunk 发送，
     *  仅加载了此区块的客户端会收到。 */
    protected void broadcastEvalSnapshot() {
        if (level instanceof ServerLevel sl) {
            // 在快照前保存 debugTime 到 RuntimeState（用于 NBT 持久化）/ save debugTime before snapshot for NBT persistence
            evaluator.saveDebugTimes(runtimeState);
            var snapshot = evaluator.captureSnapshot();
            PacketDistributor.sendToPlayersTrackingChunk(sl, new ChunkPos(worldPosition),
                new ClientboundGraphEvalPacket(worldPosition, snapshot.outputs(), snapshot.debugTimes(),
                    snapshot.subOutputs(), snapshot.subDebugTimes()));
        }
    }

    // ── loadGraphFromBytes (from network packet) / 从网络包加载图 ──

    /** Deserialize and replace the current graph from compressed NBT bytes received
     *  over the network (typically from a client-side editor save).
     *  从通过网络接收的压缩 NBT 字节（通常来自客户端编辑器保存）反序列化并替换当前图。
     *
     *  <p>Before replacing the graph, old BUS channels are unregistered from the
     *  server's SignalBus. {@link #cleanupBusChannels} is deliberately NOT called
     *  here because it broadcasts empty BusBandSyncPackets to clients, which would
     *  clear signalBands and permanently delete all BUS connections. The next tick's
     *  recompile will re-register channels from the new graph and broadcast the
     *  correct band lists.</p>
     *  <p>替换图之前，旧 BUS 通道从服务端 SignalBus 注销。此处故意不调用
     *  cleanupBusChannels，因为它会向客户端广播空的 BusBandSyncPacket，
     *  清空 signalBands 并永久删除所有 BUS 连线。下一次 tick 的 recompile
     *  将从新图重新注册通道并广播正确的频段列表。</p>
     *
     *  @param data the compressed NBT bytes containing the serialized graph / 包含序列化图的压缩 NBT 字节 */
    public void loadGraphFromBytes(byte[] data) {
        if (level == null) return;
        try {
            var t = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(2 * 1024 * 1024));
            if (t != null && t.contains("graph")) {
                // Unregister old BUS channels from the server's SignalBus before
                // replacing the graph. Do NOT call cleanupBusChannels here — it
                // broadcasts empty BusBandSyncPackets to clients, which would
                // clear signalBands and permanently delete all BUS connections.
                // The next tick's recompile will re-register channels from the
                // new graph and broadcast the correct band lists.
                // 替换图前从服务端 SignalBus 注销旧 BUS 频道。
                // 不调用 cleanupBusChannels —— 它会广播空 BusBandSyncPacket 给客户端，
                // 清空 signalBands 并永久删除所有 BUS 连线。
                // 下一次 tick 的 recompile 会从新图重新注册频道并广播正确的频段列表。
                unregisterBusChannels(graph);
                graph = NodeGraph.load(t.getCompound("graph"), level.registryAccess());
                // Force recompile on the next tick: bump the fresh graph's generation AND
                // reset lastGraphGeneration to -1. NodeGraph.load produces a fresh graph
                // with generation 0; bumping once to 1 can COLLIDE with lastGraphGeneration
                // left at 1 by the previous compile's recompile (regression audit: repeated
                // Compile+Run made graphChanged() false, so the unregistered BUS_OUT channel
                // was never re-registered and BUS_IN read 0). Resetting lastGraphGeneration
                // guarantees the next graphChanged() is true regardless of the new value.
                // 强制下一 tick 重编译：bump 新图代数，并把 lastGraphGeneration 重置为 -1。
                // NodeGraph.load 产生 generation=0 的新图；bump 一次到 1 可能与上次重编译
                // 留下的 lastGraphGeneration=1 冲突（回归审计：反复编译+运行使 graphChanged()
                // 为 false，被注销的 BUS_OUT 频道永不重注册，BUS_IN 读 0）。重置
                // lastGraphGeneration 保证无论新值如何 graphChanged() 都为 true。
                graph.bumpGeneration();
                lastGraphGeneration = -1;
                // The graph was fully replaced — clear sub-graph runtime state so the
                // next recompile starts from a clean slate. Old-graph ENCAPSULATION
                // node IDs have no meaning in the new graph; preserving them lets
                // stale DELAY/flipflop/PID leak across loads.
                // 图被整体替换——清空子图运行时状态，使下次重编译从干净状态开始。
                // 旧图的 ENCAPSULATION 节点 ID 在新图中无意义，保留会让陈旧状态泄漏。
                runtimeState.subStates.clear();
                runtimeState.flipflopStates.clear();
                rs.onLoad(graph);
            }
            needsFullSync = true;
            setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Failed to load graph for {} at {}, resetting",
                getClass().getSimpleName(), worldPosition, e);
            graph = new NodeGraph();
            rs.onLoad(graph);
            setChanged();
        }
    }

    // ── NBT save/load / NBT 保存/加载 ──

    /** Save the graph, running state, runtime state, and any type-specific data to NBT.
     *  Called by Minecraft when the chunk is saved or when {@link #getUpdateTag} needs
     *  the full block entity data for network sync.
     *  将图、运行状态、运行时状态以及任何类型特定数据保存到 NBT。
     *  由 Minecraft 在区块保存时或 getUpdateTag 需要完整方块实体数据进行网络同步时调用。
     *  @param t the compound tag to write into / 要写入的复合标签
     *  @param r the holder lookup provider for registry access / 用于注册表访问的 HolderLookup.Provider */
    @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.saveAdditional(t, r);
        t.put("graph", graph.save(r));
        t.putBoolean("running", running);
        t.put("runtime", runtimeState.save());
        saveTypeSpecific(t, r);
    }

    /** Load the graph, running state, runtime state, and any type-specific data from NBT.
     *  On the client side, if a GraphEditor is currently open for this BE, the graph
     *  replacement is skipped to avoid overwriting in-progress edits with server data
     *  (which would cause visible value bounce-back in the editor UI).
     *  BUS channels are NOT registered here — they are lazily registered on the first
     *  tick via {@link #ensureBusRegistered} to avoid double-registration with the
     *  generation bump in {@link #onLoad}.
     *  从 NBT 加载图、运行状态、运行时状态以及任何类型特定数据。
     *  在客户端，如果当前正为此 BE 打开 GraphEditor，则跳过图替换，以避免用服务端数据
     *  覆盖正在进行的编辑（这会在编辑器 UI 中导致可见的数值回弹）。
     *  BUS 通道不在此处注册 —— 它们在首次 tick 时通过 ensureBusRegistered 惰性注册，
     *  以避免与 onLoad 中的代数递增形成双重注册。
     *  @param t the compound tag to read from / 要读取的复合标签
     *  @param r the holder lookup provider for registry access / 用于注册表访问的 HolderLookup.Provider */
    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t, r);
        if (t.contains("graph")) {
            // If the editor UI is open, the editor holds live references into the current
            // graph (nodeEditStatesById, responder closures, etc.). Replacing the graph
            // here from a server NBT sync would orphan those references and cause the
            // next renderBg to recreate EditStates from potentially stale server data →
            // visible value bounce-back. Skip the replacement; the editor's graph is
            // always more current for params the local player is editing.
            // 如果编辑器 UI 处于打开状态，编辑器中持有对当前图的活跃引用
            //（nodeEditStatesById、responder 闭包等）。在此替换图会导致这些引用失效，
            // 下一次 renderBg 重建 EditState 时可能读取服务端的过时数据 → 数值回弹。
            // 跳过替换；对于本地玩家正在编辑的参数，编辑器中的图始终是最新的。
            // 例外：graphReady == false 时（中途加入的玩家首次同步，本地图还是空的/旧的），
            // 即使编辑器已打开也必须加载服务端最新图，否则永远拿不到权威图数据
            // （回归审计：中途加入玩家无法获取最新图）。
            boolean editorOpen = false;
            if (level != null && level.isClientSide()) {
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.screen instanceof GraphEditor.Host host
                    && host.getBlockPos().equals(worldPosition)) {
                    editorOpen = true;
                }
            }
            if (!editorOpen || !graphReady) {
                graph = NodeGraph.load(t.getCompound("graph"), r);
                rs.onLoad(graph);
                this.graphReady = true;
            }
        }
        if (t.contains("running")) running = t.getBoolean("running");
        if (t.contains("runtime")) {
            RuntimeState loaded = RuntimeState.load(t.getCompound("runtime"));
            runtimeState.pidState.putAll(loaded.pidState);
        }
        loadTypeSpecific(t, r);
        setChanged();
        // BUS channels are registered on first tick via ensureBusRegistered() —
        // no need to eagerly register here (avoids double-registration with onLoad bump).
        // BUS 通道在首次 tick 时通过 ensureBusRegistered() 注册 — 无需在此过早注册
        //（避免与 onLoad 的 bumpGeneration 形成双重注册）。
    }

    /** Override to save BE-type-specific NBT (e.g. Monitor screen settings).
     *  Called from {@link #saveAdditional} after the common fields have been written.
     *  覆写以保存 BE 类型特定的 NBT（例如 Monitor 屏幕设置）。
     *  在通用字段写入后由 saveAdditional 调用。
     *  @param t the compound tag to write into / 要写入的复合标签
     *  @param r the holder lookup provider / HolderLookup.Provider */
    protected void saveTypeSpecific(CompoundTag t, HolderLookup.Provider r) {}

    /** Override to load BE-type-specific NBT.
     *  Called from {@link #loadAdditional} after the common fields have been read.
     *  覆写以加载 BE 类型特定的 NBT。
     *  在通用字段读取后由 loadAdditional 调用。
     *  @param t the compound tag to read from / 要读取的复合标签
     *  @param r the holder lookup provider / HolderLookup.Provider */
    protected void loadTypeSpecific(CompoundTag t, HolderLookup.Provider r) {}

    // ── Network sync / 网络同步 ──

    /** Create the packet that is sent to clients when this block entity is first loaded
     *  or when {@code sendBlockUpdated} is called. Delegates to Minecraft's standard
     *  {@link ClientboundBlockEntityDataPacket} which calls {@link #getUpdateTag}.
     *  创建当此方块实体首次加载或调用 sendBlockUpdated 时发送给客户端的数据包。
     *  委托给 Minecraft 标准的 ClientboundBlockEntityDataPacket，后者调用 getUpdateTag。
     *  @return the update packet, or null if not applicable / 更新数据包，如不适用则返回 null */
    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Force a full graph sync to all tracking clients (called when a new editor joins).
     *  Sets {@link #needsFullSync} to true so that the next {@link #getUpdateTag} call
     *  includes the complete graph NBT. Also records the current game time for
     *  throttling and triggers an immediate block update.
     *  强制向所有追踪客户端进行完整图同步（当新编辑器加入时调用）。
     *  将 needsFullSync 设为 true，使下一次 getUpdateTag 调用包含完整的图 NBT。
     *  同时记录当前游戏时间用于限流，并触发即时方块更新。 */
    public void flagFullSync() {
        needsFullSync = true;
        lastFullSyncGameTime = (level != null) ? level.getGameTime() : 0;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /** Always send the full graph so that new clients tracking this chunk receive
     *  the authoritative graph data regardless of whether a prior full sync has
     *  already been consumed by another client. Without this, a client loading a
     *  chunk after {@link #needsFullSync} was cleared would never receive the graph
     *  NBT, leaving {@link #graphReady} permanently false.
     *  始终发送完整图数据，以确保新追踪此区块的客户端无论先前是否有其他客户端
     *  消费了完整同步，都能收到权威的图数据。否则在 needsFullSync 被清除后
     *  加载区块的客户端将永远收不到图 NBT，导致 graphReady 永久为 false。
     *  @param r the holder lookup provider / HolderLookup.Provider
     *  @return a compound tag containing all block entity data / 包含所有方块实体数据的复合标签 */
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) {
        var t = new CompoundTag();
        saveAdditional(t, r);
        return t;
    }
}
