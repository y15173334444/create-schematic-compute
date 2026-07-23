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
    public NodeGraph graph = new NodeGraph();
    public boolean running = false;
    public final RuntimeState runtimeState = new RuntimeState();
    protected final RedstoneLinkHelper rs = new RedstoneLinkHelper(this);
    protected GraphEvaluator evaluator = null;
    protected NodeGraph lastEvaluatedGraph = null;
    protected int lastGraphGeneration = -1;
    protected final HashMap<Integer, Integer> lastBusHashMap = new HashMap<>();
    protected boolean needsFullSync = true;
    private long lastFullSyncGameTime = 0;
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
     *  Allows the client UI (e.g. GraphEditor) to check whether the graph is ready for rendering. */
    public transient boolean graphReady = false;

    /** Call at the start of each tick to guarantee BUS channels are registered at least once.
     *  在每个 tick 开始时调用，以确保 BUS 通道至少被注册一次。 */
    protected void ensureBusRegistered() {
        if (busRegistrationPending) {
            busRegistrationPending = false;
            registerBusChannels();
        }
    }

    // ── RedstoneLinkHelper accessors / RedstoneLinkHelper 访问器 ──
    public void putRedstoneInput(long freqKey, int signal) { rs.putInput(freqKey, signal); }
    public int getRedstoneInput(long freqKey) { return rs.getInput(freqKey); }

    protected SyncedGraphBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ── GraphBlockEntity interface / GraphBlockEntity 接口 ──
    @Override public NodeGraph getNodeGraph() { return graph; }
    @Override public boolean isRunning() { return running; }
    @Override public void setRunning(boolean r) { running = r; setChanged(); }
    @Override public boolean graphHasCycles() { return graph.hasCycles(); }
    @Override public void clearPidState() { runtimeState.pidState.clear(); }
    @Override public void syncFlipflopStates(java.util.Map<Integer, Boolean> states) {
        runtimeState.flipflopStates.clear();
        if (states != null) runtimeState.flipflopStates.putAll(states);
    }
    @Override public void syncBusBandsFromServer(String busName, java.util.List<String> bands) {
        BusChannelHelper.syncBandsFromServer(busName, bands, graph);
    }

    // ── Redstone links lifecycle / 红石链接生命周期 ──
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
    @Override public void onChunkUnloaded() { cleanupBusChannels(graph); unregisterBusChannels(graph); super.onChunkUnloaded(); rs.onChunkUnloaded(); }
    @Override public void setRemoved() { cleanupBusChannels(graph); unregisterBusChannels(graph); rs.setRemoved(); super.setRemoved(); }

    // ── BUS channel lifecycle (safe no-ops — BEs without BUS just inherit these)
    //      BUS 通道生命周期（安全空操作 —— 无 BUS 的 BE 直接继承即可） ──
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
    protected void cleanupBusChannels(NodeGraph g) {
        BusChannelHelper.cleanupClientBands(g, worldPosition, level);
    }
    protected void unregisterBusChannels(NodeGraph g) {
        BusChannelHelper.unregisterChannels(g, worldPosition, level);
    }

    // ── Graph change detection / 图变更检测 ──
    /** True when the evaluator needs rebuilding (graph changed since last check).
     *  当求值器需要重建时为 true（自上次检查以来图结构已更改）。 */
    protected boolean graphChanged() {
        return evaluator == null || lastGraphGeneration != graph.graphGeneration;
    }

    /** Rebuild evaluator and re-register BUS channels after a graph change.
     *  Only clears pidState — subStates (ENCAPSULATION sub-graph state) is preserved.
     *  在图结构变更后重建求值器并重新注册 BUS 通道。
     *  仅清除 pidState —— subStates（ENCAPSULATION 子图状态）会被保留。 */
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
     *  完全重建，同时重置延迟队列、触发器状态和脉冲计时器。
     *  供 Blueprint 和 ProgramComputer（使用时序/状态节点）使用。 */
    protected void recompileEvaluatorFull() {
        Map<Integer, Float> savedDebugTime = null;
        NodeGraph oldGraph = lastEvaluatedGraph;
        if (oldGraph != null) {
            BusChannelHelper.syncDeletedBusNames(oldGraph, graph, worldPosition, level);
            savedDebugTime = new HashMap<>(runtimeState.debugTime);
            runtimeState.clear();
        }
        // Explicitly unregister BUS_OUT nodes that were removed since the last recompile.
        // 显式取消注册自上次重编译以来已删除的 BUS_OUT 节点。
        unregisterRemovedBusOutNodes();
        evaluator = new GraphEvaluator(graph);
        evaluator.restoreSubState(runtimeState);
        if (savedDebugTime != null && !savedDebugTime.isEmpty()) {
            evaluator.restoreDebugTimes(savedDebugTime);
        }
        lastEvaluatedGraph = graph;
        lastGraphGeneration = graph.graphGeneration;
        // Use diff-based re-registration to preserve channel ownership.
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
     *  最小化重建（无 BUS 生命周期操作）。供 Monitor 和 SpeedProxy 使用。 */
    protected void recompileEvaluatorLight() {
        evaluator = new GraphEvaluator(graph);
        lastEvaluatedGraph = graph;
        lastGraphGeneration = graph.graphGeneration;
        runtimeState.pidState.clear();
        registerBusChannels(); // EN: ensure BUS channels are registered on first tick 中文: 确保 BUS 通道在首次 tick 时被注册
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
     *  停止时清除 BUS_OUT 映射并写入空的红石输出。 */
    protected void onStopRunning() {
        for (var n : graph.nodes) {
            if (n.type == NodeType.BUS_OUT && n.busInternalMap != null)
                n.busInternalMap.clear();
        }
        rs.writeOutputs(java.util.Collections.emptyList());
    }

    // ── EvalSnapshot broadcast / EvalSnapshot 广播 ──
    /** Broadcast eval snapshot to tracking clients after evaluation completes.
     *  求值完成后向追踪客户端广播求值快照。 */
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
    public void loadGraphFromBytes(byte[] data) {
        if (level == null) return;
        try {
            var t = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(2 * 1024 * 1024));
            if (t != null && t.contains("graph")) {
                graph = NodeGraph.load(t.getCompound("graph"), level.registryAccess());
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
    @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.saveAdditional(t, r);
        t.put("graph", graph.save(r));
        t.putBoolean("running", running);
        t.put("runtime", runtimeState.save());
        saveTypeSpecific(t, r);
    }

    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t, r);
        if (t.contains("graph")) {
            graph = NodeGraph.load(t.getCompound("graph"), r);
            rs.onLoad(graph);
            this.graphReady = true;
        }
        if (t.contains("running")) running = t.getBoolean("running");
        if (t.contains("runtime")) {
            RuntimeState loaded = RuntimeState.load(t.getCompound("runtime"));
            runtimeState.pidState.putAll(loaded.pidState);
        }
        loadTypeSpecific(t, r);
        setChanged();
        // EN: Register BUS channels early if level is already available (belt-and-suspenders with onLoad).
        // 中文: 如果 level 已经可用，尽早注册 BUS 通道（与 onLoad 形成双重保险）。
        if (level != null && !level.isClientSide()) registerBusChannels();
    }

    /** Override to save BE-type-specific NBT (e.g. Monitor screen settings).
     *  覆写以保存 BE 类型特定的 NBT（例如 Monitor 屏幕设置）。 */
    protected void saveTypeSpecific(CompoundTag t, HolderLookup.Provider r) {}
    /** Override to load BE-type-specific NBT.
     *  覆写以加载 BE 类型特定的 NBT。 */
    protected void loadTypeSpecific(CompoundTag t, HolderLookup.Provider r) {}

    // ── Network sync / 网络同步 ──
    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Force a full graph sync to all tracking clients (called when a new editor joins).
     *  强制向所有追踪客户端进行完整图同步（当新编辑器加入时调用）。 */
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
     *  加载区块的客户端将永远收不到图 NBT，导致 graphReady 永久为 false。 */
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) {
        var t = new CompoundTag();
        saveAdditional(t, r);
        return t;
    }
}
