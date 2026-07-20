package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.RuntimeState;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import io.github.y15173334444.create_schematic_compute.network.ClientboundGraphEvalPacket;
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

    /** Server-authoritative evaluation snapshot (Phase MVP — set by ClientboundGraphEvalPacket on client).
     *  服务端权威求值快照（MVP 阶段 —— 由 ClientboundGraphEvalPacket 在客户端设置）。 */
    public volatile EvalSnapshot cachedEvalSnapshot = EvalSnapshot.EMPTY;

    /** True until the first tick registers BUS channels — ensures old saves work without manual intervention.
     *  在第一次 tick 注册 BUS 通道之前为 true —— 确保旧存档无需手动干预即可正常工作。 */
    private boolean busRegistrationPending = true;

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
        if (BusChannelHelper.registerChannels(graph, worldPosition, level))
            needsFullSync = true;
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
        if (lastEvaluatedGraph != null) {
            BusChannelHelper.syncDeletedBusNames(lastEvaluatedGraph, graph, worldPosition, level);
            unregisterBusChannels(lastEvaluatedGraph);
        }
        evaluator = new GraphEvaluator(graph);
        evaluator.restoreSubState(runtimeState);
        lastEvaluatedGraph = graph;
        lastGraphGeneration = graph.graphGeneration;
        runtimeState.pidState.clear();
        registerBusChannels();
    }

    /** Full rebuild that also resets delay queues, flipflop states and pulse timers.
     *  Used by Blueprint and ProgramComputer (which use timing/state nodes).
     *  完全重建，同时重置延迟队列、触发器状态和脉冲计时器。
     *  供 Blueprint 和 ProgramComputer（使用时序/状态节点）使用。 */
    protected void recompileEvaluatorFull() {
        if (lastEvaluatedGraph != null) {
            BusChannelHelper.syncDeletedBusNames(lastEvaluatedGraph, graph, worldPosition, level);
            unregisterBusChannels(lastEvaluatedGraph);
            runtimeState.clear();
        }
        evaluator = new GraphEvaluator(graph);
        evaluator.restoreSubState(runtimeState);
        lastEvaluatedGraph = graph;
        lastGraphGeneration = graph.graphGeneration;
        registerBusChannels();
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
            var snapshot = evaluator.captureSnapshot();
            PacketDistributor.sendToPlayersTrackingChunk(sl, new ChunkPos(worldPosition),
                new ClientboundGraphEvalPacket(worldPosition, snapshot.outputs(), snapshot.debugTimes()));
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
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) {
        if (needsFullSync) {
            needsFullSync = false;
            var t = new CompoundTag();
            saveAdditional(t, r);
            return t;
        }
        var t = new CompoundTag();
        t.putBoolean("running", running);
        return t;
    }
}
