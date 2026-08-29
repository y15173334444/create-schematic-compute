package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.RuntimeState;
import com.simibubi.create.foundation.blockEntity.IMergeableBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Common base class for all graph-hosting block entities — a thin shell over the
 * shared {@link GraphHost} engine.
 * 所有托管图节点的方块实体的通用基类 —— 共享引擎 {@link GraphHost} 之上的薄壳。
 *
 * <p>全部托管状态（graph/running/runtimeState/evaluator/同步协议字段…）与图逻辑
 * （重编译、BUS 生命周期、NBT 公共段、全量同步、快照广播）都住在引擎里；本类只保留：
 * 引擎实例 + 同名访问器桥（graph()/rs()/…，让子类以最小机械改动从字段迁移过来）+
 * GraphBlockEntity/GraphHostOwner 契约委托 + 类型钩子（loadTypeSpecific/saveTypeSpecific/
 * acceptTypeSpecific）。组合线（Kinetic BE 持有 GraphHost）与继承线共用同一个引擎实现 ——
 * 收敛重构的阶段 1 目标。</p>
 * <p>All hosted state (graph/running/runtimeState/evaluator/sync-protocol fields…) and
 * graph logic (recompiles, BUS lifecycle, common NBT sections, full sync, snapshot
 * broadcast) live in the engine; this class keeps only the engine instance + same-name
 * accessor bridges (graph()/rs()/…, so subclasses migrate off fields with minimal
 * mechanical edits) + GraphBlockEntity/GraphHostOwner contract delegation + type hooks
 * (loadTypeSpecific/saveTypeSpecific/acceptTypeSpecific). The composition line (kinetic
 * BEs holding a GraphHost) and this inheritance line share one engine implementation —
 * the phase-1 goal of the convergence refactor.</p>
 */
public abstract class SyncedGraphBlockEntity extends BlockEntity
        implements IMergeableBE, GraphBlockEntity {

    // ── 引擎 / engine ──

    /**
     * 共享图托管引擎 —— 本类的全部托管状态与图逻辑都委托给它。构造时传入 this，
     * 引擎经 GraphHostOwner 回调（getLevel/getBlockPos/setChanged/sendBlockUpdated）
     * 反向驱动方块实体。
     * The shared graph-hosting engine — all hosted state and graph logic is delegated
     * to it. Constructed with this; the engine drives the block entity back through
     * GraphHostOwner callbacks (getLevel/getBlockPos/setChanged/sendBlockUpdated).
     * private：子类与外部一律走下方访问器/契约，不直接耦合引擎字段
     * （阶段 2 的契约收敛依赖这一点）。
     * private: subclasses and outsiders go through the accessors/contract below only —
     * direct engine-field coupling is what the phase-2 contract convergence removes. */
    private final GraphHost host = new GraphHost(this);

    // ── 同名访问器桥（过渡 API，阶段 3 删除）/ same-name accessor bridges (transitional) ──
    // 子类原先直接读写的托管字段，一律改为调用这些同名方法：`graph.nodes` →
    // `graph().nodes`、`rs.foo` → `rs().foo`。引擎字段的可见性不向子类开放。
    // Hosted fields subclasses used to touch directly are now reached through these
    // same-name methods: `graph.nodes` → `graph().nodes`, `rs.foo` → `rs().foo`.
    // Engine field visibility is not opened to subclasses.
    //
    // 【迁移指南 / migration guide】下列 @Deprecated 桥是阶段 1 的机械改写目标，
    // 属过渡 API：阶段 3 薄壳化时随子类一起收缩删除。子类新代码请改走：
    // · 数据读取 → GraphBlockEntity 契约（getNodeGraph/isRunning/getCachedEvalSnapshot/
    //   getFlipflopStates/getPendingLocalOps...），或经引擎宿主回调；
    // · 时序/求值操作（recompile、BUS 生命周期、graphChanged、onStopRunning、
    //   广播/冲刷）→ 收敛进引擎的 tick 驱动（GraphHost），子类只保留类型钩子。
    // These @Deprecated bridges were the phase-1 mechanical-rewrite targets and are
    // transitional: phase-3 thin-shelling shrinks them away with the subclasses. New
    // subclass code should use the GraphBlockEntity contract for data reads, and push
    // timing/evaluation work into the engine's tick driver, keeping only type hooks.

    /** @see GraphHost#graph */
    @Deprecated
    protected NodeGraph graph() { return host.graph; }

    /** 整体替换图（仅限图加载路径：加载前自行注销旧 BUS、加载后自行 bump/invalidate/rs.onLoad）。
     *  Wholesale graph replacement (graph-load paths only: unregister old BUS first,
     *  then bump/invalidate/rs.onLoad yourself after). */
    @Deprecated
    protected void setGraph(NodeGraph g) { host.graph = g; }

    /** @see GraphHost#runtimeState */
    @Deprecated
    protected RuntimeState runtimeState() { return host.runtimeState; }

    /** @see GraphHost#evaluator */
    @Deprecated
    protected GraphEvaluator evaluator() { return host.evaluator; }

    /** @see GraphHost#rs */
    @Deprecated
    protected RedstoneLinkHelper rs() { return host.rs; }

    /** @see GraphHost#lastBusHashMap */
    @Deprecated
    protected HashMap<Integer, Integer> lastBusHashMap() { return host.lastBusHashMap; }

    /** @see GraphHost#invalidateEvaluator */
    @Deprecated
    protected void invalidateEvaluator() { host.invalidateEvaluator(); }

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

    // ── GraphBlockEntity contract → engine / 契约委托 ──
    // GraphBlockEntity 的组合式成员全部转发给引擎的同名桥（引擎是唯一实现，
    // 本类与组合线共用）。/ The composition-oriented GraphBlockEntity members all
    // forward to the engine's same-name bridges (the engine is the single
    // implementation, shared with the composition line).

    /** @return the node graph hosted by this BE / 此 BE 托管的节点图 */
    @Override public NodeGraph getNodeGraph() { return host.graph; }

    /** @see GraphBlockEntity#getPendingLocalOps */
    @Override public int getPendingLocalOps() { return host.getPendingLocalOps(); }

    /** @see GraphBlockEntity#setPendingLocalOps */
    @Override public void setPendingLocalOps(int value) { host.setPendingLocalOps(value); }

    /** @see GraphBlockEntity#getCachedEvalSnapshot */
    @Override public EvalSnapshot getCachedEvalSnapshot() { return host.getCachedEvalSnapshot(); }

    /** @see GraphBlockEntity#setCachedEvalSnapshot */
    @Override public void setCachedEvalSnapshot(EvalSnapshot snapshot) { host.setCachedEvalSnapshot(snapshot); }

    /** @see GraphBlockEntity#isGraphReady */
    @Override public boolean isGraphReady() { return host.isGraphReady(); }

    /** @see GraphBlockEntity#peekSubStateFlipflops */
    @Override public Map<Integer, Boolean> peekSubStateFlipflops(int encapNodeId) {
        return host.peekSubStateFlipflops(encapNodeId);
    }

    /** @see GraphBlockEntity#getFlipflopStates */
    @Override public Map<Integer, Boolean> getFlipflopStates() { return host.getFlipflopStates(); }

    /** @return whether the graph evaluator is currently running / 图求值器当前是否在运行 */
    @Override public boolean isRunning() { return host.isRunning(); }

    /** Set the running state and mark the BE as changed for saving.
     *  设置运行状态并标记 BE 已变更以便保存。
     *  @param r true to start evaluation, false to stop / true 开始求值，false 停止 */
    @Override public void setRunning(boolean r) { host.setRunning(r); }

    /** @return true if the graph contains directed cycles / 如果图包含有向环则返回 true */
    @Override public boolean graphHasCycles() { return host.graphHasCycles(); }

    /** Clear all PID controller accumulators in the runtime state.
     *  清除运行时状态中的所有 PID 控制器累加器。 */
    @Override public void clearPidState() { host.clearPidState(); }

    /** Replace flipflop states with the given map (used for client-server sync).
     *  用给定映射替换触发器状态（用于客户端-服务端同步）。
     *  @param states map of node ID to flipflop output value / 节点 ID 到触发器输出值的映射 */
    @Override public void syncFlipflopStates(Map<Integer, Boolean> states) { host.syncFlipflopStates(states); }

    /** Replace sub-graph flipflop states from the server (used for ENCAPSULATION nodes).
     *  从服务端替换子图触发器状态（用于 ENCAPSULATION 节点）。
     *  @param subStates map of sub-graph ID to (node ID → flipflop value) / 子图 ID 到（节点 ID → 触发器值）的映射 */
    @Override public void syncSubFlipflopStates(Map<Integer, Map<Integer, Boolean>> subStates) {
        host.syncSubFlipflopStates(subStates);
    }

    /** Apply BUS band list from the server to the client-side graph.
     *  将服务端的 BUS 频段列表应用到客户端图。
     *  @param busName the BUS signal name / BUS 信号名称
     *  @param bands   the list of band identifiers / 频段标识符列表 */
    @Override public void syncBusBandsFromServer(String busName, java.util.List<String> bands) {
        host.syncBusBandsFromServer(busName, bands);
    }

    // ── RedstoneLinkHelper accessors / RedstoneLinkHelper 访问器 ──

    /** Store a redstone input signal value for a given frequency key.
     *  存储给定频率键的红石输入信号值。
     *  @param freqKey the Create redstone link frequency key / Create 红石链接频率键
     *  @param signal  the redstone signal strength (0-15) / 红石信号强度（0-15） */
    public void putRedstoneInput(long freqKey, int signal) { rs().putInput(freqKey, signal); }

    /** Retrieve the last-known redstone input signal for a given frequency key.
     *  获取给定频率键的最后已知红石输入信号。
     *  @param freqKey the Create redstone link frequency key / Create 红石链接频率键
     *  @return the cached signal strength, or 0 if never set / 缓存的信号强度，从未设置则返回 0 */
    public int getRedstoneInput(long freqKey) { return rs().getInput(freqKey); }

    // ── GraphBlockEntity 宿主绑定面 ─────────────────────────────────────
    //     契约的宿主绑定面（编辑回弹判定 isGraphReplaceBlocked、NBT 类型段钩子、
    //     asBlockEntity/sendBlockUpdated）已由 GraphBlockEntity 直接承载（阶段 2 并入，
    //     原 GraphHostOwner 接口删除）；本类实现契约即同时满足引擎的宿主回调面。
    //     getLevel / getBlockPos / setChanged 由 BlockEntity 直接提供，签名一致。
    //     The contract's host-binding surface (bounce-back check, NBT hooks,
    //     asBlockEntity/sendBlockUpdated) now lives on GraphBlockEntity itself
    //     (merged in phase 2; the GraphHostOwner interface is gone). Implementing the
    //     contract therefore also satisfies the engine's host-callback surface.
    //     getLevel / getBlockPos / setChanged come from BlockEntity with matching
    //     signatures.

    /** @see GraphBlockEntity#asBlockEntity */
    @Override public BlockEntity asBlockEntity() { return this; }

    /** @see GraphBlockEntity#sendBlockUpdated */
    @Override public void sendBlockUpdated() {
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    // ── Lifecycle glue / 生命周期胶水（顺序保持与引擎拆分前逐条一致）──
    //     Lifecycle order is preserved exactly as before the engine split.

    /** Called when the block entity is added to the world.
     *  Loads redstone link associations and bumps graph generation to force a full
     *  recompile on the first tick (ensures BUS channels and evaluator caches are rebuilt).
     *  方块实体被添加到世界时调用。加载红石链接关联并递增图代数，
     *  以在首次 tick 时强制完全重编译（确保 BUS 通道和求值器缓存被重建）。 */
    @Override public void onLoad() {
        super.onLoad();
        host.onHostLoad();
    }

    /** Called when the chunk containing this BE is unloaded.
     *  Cleans up BUS channels on both client and server before the BE goes dormant.
     *  包含此 BE 的区块被卸载时调用。在 BE 休眠之前清理客户端和服务端的 BUS 通道。 */
    @Override public void onChunkUnloaded() {
        cleanupBusChannels(graph());
        unregisterBusChannels(graph());
        super.onChunkUnloaded();
        rs().onChunkUnloaded();
    }

    /** Called when the block is removed from the world.
     *  Performs full BUS channel teardown: client cleanup + server unregistration.
     *  方块从世界中移除时调用。执行完整的 BUS 通道拆除：客户端清理 + 服务端注销。 */
    @Override public void setRemoved() {
        cleanupBusChannels(graph());
        unregisterBusChannels(graph());
        rs().setRemoved();
        super.setRemoved();
    }

    // ── IMergeableBE 合并（Create contraption / schematic 放置）────────────────
    //     IMergeableBE merge (Create contraption / schematic placement)

    /**
     * 合并另一个同类型 BE 的状态（Create 在 contraption 放置/粘贴时调用）。
     * 七个子类原先各写一份、逻辑几乎逐字相同，现上提到基类，类型特定字段由
     * {@link #acceptTypeSpecific} 钩子承载 —— 与 loadTypeSpecific/saveTypeSpecific 同构。
     * Merge another BE of the same type (called by Create on contraption placement /
     * paste). The seven subclasses used to each carry a near-verbatim copy of this;
     * it now lives in the base class, with type-specific fields handled by the
     * {@link #acceptTypeSpecific} hook — mirroring loadTypeSpecific/saveTypeSpecific.
     *
     * <p><b>类型判定必须允许跨变体合并。</b>原先各子类用 {@code other instanceof
     * XxxBlockEntity} 检查，因此原生基类与其 Sable 兼容变体
     * （{@code compat/ControlSeatBlockEntitySable}、{@code MonitorBlockEntitySable}、
     * {@code RadarBlockEntitySable}、{@code SensorBlockEntitySable} —— 四者均继承对应基类
     * 且都不覆写 accept）之间<em>双向</em>都可合并。整合包中途加装或移除 Sable 时，
     * 新旧两种 BE 会真实共存，合并被静默跳过会丢图。
     * 上提后改用"两个类存在继承关系（任一方向 isInstance）"：
     * 基类↔变体放行、同类型放行、无关类型（Blueprint↔Monitor）跳过，
     * 与旧的 instanceof 语义等价。<b>不要</b>改用 {@code getClass() != getClass()} ——
     * 那会静默断掉全部跨变体合并。
     * The type test must admit cross-variant merges. Each subclass used to check
     * {@code other instanceof XxxBlockEntity}, so a native base BE and its Sable
     * variant ({@code compat/ControlSeatBlockEntitySable}, {@code MonitorBlockEntitySable},
     * {@code RadarBlockEntitySable}, {@code SensorBlockEntitySable} — all four extend the
     * matching base class and none override accept) merged <em>both ways</em>. Installing
     * or removing Sable mid-game leaves both kinds alive in one world, so a silently
     * skipped merge loses the graph. The hoisted check asks whether the two classes are
     * in an inheritance relation (isInstance in either direction): base↔variant passes,
     * identical types pass, unrelated types (Blueprint↔Monitor) are skipped — equivalent
     * to the old instanceof semantics. Do <b>not</b> switch to
     * {@code getClass() != getClass()}; that silently breaks every cross-variant merge.
     *
     * @param other 被吸收的方块实体 / the block entity being absorbed
     */
    @Override public void accept(BlockEntity other) {
        if (!(other instanceof SyncedGraphBlockEntity src)) return;
        if (!isMergeCompatible(getClass(), other.getClass())) return;
        host.adoptFrom(src.host);
        acceptTypeSpecific(src);
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /**
     * 两个 BE 类之间是否允许合并 —— 存在继承关系（任一方向）即可。
     * Whether two BE classes may merge — an inheritance relation in either direction.
     *
     * <p><b>必须放行原生基类与其 Sable 兼容变体。</b>{@code compat/} 下的
     * ControlSeatBlockEntitySable / MonitorBlockEntitySable / RadarBlockEntitySable /
     * SensorBlockEntitySable 四个子类都继承对应基类，且**都不覆写 accept**；旧行为下各子类的
     * {@code other instanceof XxxBlockEntity} 因此双向放行。整合包中途加装或移除 Sable 时，
     * 新旧两种 BE 会在同一个世界共存，合并被静默跳过就是丢图。
     * <b>Must admit a native base class and its Sable variant.</b> The four
     * {@code compat/*BlockEntitySable} subclasses extend the matching base class and none
     * overrides accept, so each subclass's old {@code other instanceof XxxBlockEntity} let
     * them merge both ways. Installing or removing Sable mid-game leaves both kinds alive
     * in one world; a silently skipped merge loses the graph.
     *
     * <p>抽成静态方法是为了让回归测试直接锁住这条语义 ——
     * 用 {@code mine != theirs}（类相等）会静默断掉全部跨变体合并，已踩过一次。
     * Static so a regression test can pin this down: using class equality
     * ({@code mine != theirs}) silently breaks every cross-variant merge, which already
     * happened once.
     *
     * @param mine   本 BE 的类 / this BE's class
     * @param theirs 待合并 BE 的类 / the candidate BE's class
     * @return true 表示允许合并 / true when the merge is allowed
     */
    static boolean isMergeCompatible(Class<?> mine, Class<?> theirs) {
        return mine.isAssignableFrom(theirs) || theirs.isAssignableFrom(mine);
    }

    /**
     * 合并时复制类型特定字段的钩子，由 {@link #accept} 在引擎间接管之后调用。
     * 默认空实现；Monitor 与 Radar 覆写它搬自己的类型字段。
     * Hook for copying type-specific fields during a merge; called by {@link #accept}
     * after the engine adoption. Empty by default; Monitor and Radar override it to
     * carry their own fields across.
     *
     * @param src 同类型的源 BE / the source BE, of the same type
     */
    protected void acceptTypeSpecific(SyncedGraphBlockEntity src) {}

    // ── 引擎逻辑桥 / engine-logic bridges ──
    //     这些逻辑的唯一实现在 GraphHost（与组合线共用）；此处的桥只为保住子类可见
    //     的调用形态。The single implementations live in GraphHost (shared with the
    //     composition line); these bridges only preserve the subclass-visible call shapes.

    /** Call at the start of each tick to guarantee BUS channels are registered at least once.
     *  在每个 tick 开始时调用，以确保 BUS 通道至少被注册一次。 */
    @Deprecated
    protected void ensureBusRegistered() { host.ensureBusRegistered(); }

    /** Register all BUS_IN and BUS_OUT nodes in the current graph with the server's
     *  SignalBus. 向服务端 SignalBus 注册当前图中所有 BUS_IN 和 BUS_OUT 节点。 */
    @Deprecated
    protected void registerBusChannels() { host.registerBusChannels(); }

    /** Clear client-side BUS band caches for all BUS_IN/BUS_OUT nodes in the graph.
     *  清除图中所有 BUS_IN/BUS_OUT 节点的客户端 BUS 频段缓存。
     *  @param g the graph whose BUS nodes to clean up / 要清理其 BUS 节点的图 */
    @Deprecated
    protected void cleanupBusChannels(NodeGraph g) { host.cleanupBusChannels(g); }

    /** Unregister all BUS_IN and BUS_OUT channels from the server's SignalBus.
     *  从服务端 SignalBus 注销所有 BUS_IN 和 BUS_OUT 通道。
     *  @param g the graph whose BUS nodes to unregister / 要注销其 BUS 节点的图 */
    @Deprecated
    protected void unregisterBusChannels(NodeGraph g) { host.unregisterBusChannels(g); }

    /** True when the evaluator needs rebuilding (graph changed since last check).
     *  当求值器需要重建时为 true（自上次检查以来图结构已更改）。 */
    @Deprecated
    protected boolean graphChanged() { return host.graphChanged(); }

    /** Full rebuild that preserves all main-graph runtime state — sequential (DELAY
     *  queues, flipflops, pulse timers) and integral (PID/ACCUMULATOR/INTEGRATOR in
     *  pidState) — pruning only entries whose node was removed.
     *  完全重建，但保留主图全部运行时状态——时序与积分，仅剪除已被删除节点的条目。 */
    @Deprecated
    protected void recompileEvaluatorFull() { host.recompileEvaluatorFull(); }

    /** Minimal rebuild (preserves debugTime only). Used by Monitor and SpeedProxy.
     *  最小化重建（仅保留 debugTime）。供 Monitor 和 SpeedProxy 使用。 */
    @Deprecated
    protected void recompileEvaluatorLight() { host.recompileEvaluatorLight(); }

    /** Clear BUS_OUT maps and write empty redstone outputs when stopped.
     *  停止时清除 BUS_OUT 映射并写入空的红石输出。 */
    @Deprecated
    protected void onStopRunning() { host.onStopRunning(); }

    /** Broadcast eval snapshot to tracking clients after evaluation completes.
     *  求值完成后向追踪客户端广播求值快照。 */
    @Deprecated
    protected void broadcastEvalSnapshot() { host.broadcastEvalSnapshot(); }

    /** Deserialize and replace the current graph from compressed NBT bytes received
     *  over the network (typically from a client-side editor save).
     *  从通过网络接收的压缩 NBT 字节（通常来自客户端编辑器保存）反序列化并替换当前图。 */
    public void loadGraphFromBytes(byte[] data) { host.loadGraphFromBytes(data); }

    /** Apply an already-parsed editor-save tag (graph replacement + forced recompile +
     *  sub-state clear + rs.onLoad + full-sync push). Extension point for hosts whose
     *  editor packet carries a type section alongside the graph (e.g. Monitor screen
     *  settings): parse once, take the type section, then call this — see
     *  {@link GraphHost#loadEditorTag}. Not a transitional bridge; it is the tag-level
     *  entry of the editor-save path.
     *  应用已解析的编辑器保存 NBT。宿主编辑包若在图之外还携带类型段（如 Monitor 屏幕
     *  设置），解析一次、先取类型段再调本方法 —— 见 {@link GraphHost#loadEditorTag}。
     *  非过渡桥：这是编辑器保存路径的 tag 级入口。 */
    protected void loadEditorTag(CompoundTag t) { host.loadEditorTag(t); }

    /** Server-tick flipflop diff broadcast — main- and sub-graph flipflop states are
     *  diffed against baselines and RuntimeStateSyncPacket is sent only on change.
     *  服务端 tick 的触发器差分广播 —— 相对基线 diff，有变化才发 RuntimeStateSyncPacket。 */
    protected void broadcastFlipflopDiff() { host.broadcastFlipflopDiff(); }

    // ── 全量同步 / full sync ──

    /** Force a full graph sync to all tracking clients (called when a new editor joins).
     *  强制向所有追踪客户端进行完整图同步（当新编辑器加入时调用）。 */
    public void flagFullSync() { host.flagFullSync(); }

    /** Coalesced full-graph sync request for high-frequency display ops (layout / pixel
     *  drag streams); never fires immediately — flushed by {@link #flushPendingFullSync()}.
     *  限流版全量同步请求（高频显示 op 用）；从不立即发送 —— 由
     *  {@link #flushPendingFullSync()} 冲刷。 */
    public void requestFullSync() { host.requestFullSync(); }

    /** Call from the BE tick (server side only): flushes a deferred full-sync request at
     *  most once per {@link GraphHost#FULL_SYNC_GRACE_TICKS}-tick grace window.
     *  在 BE tick 中调用（仅服务端）：按 40-tick grace 窗口合并冲刷延迟的全量同步请求。 */
    @Deprecated
    protected void flushPendingFullSync() { host.flushPendingFullSync(); }

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
        host.saveHostNBT(t, r);
        saveTypeSpecific(t, r);
    }

    /** Load the graph, running state, runtime state, and any type-specific data from NBT.
     *  On the client side, if a GraphEditor is currently open for this BE, the graph
     *  replacement is skipped to avoid overwriting in-progress edits with server data
     *  (which would cause visible value bounce-back in the editor UI) — the guard is
     *  {@link GraphBlockEntity#isGraphReplaceBlocked(int)}, shared with the composition line.
     *  BUS channels are NOT registered here — they are lazily registered on the first
     *  tick via {@link #ensureBusRegistered}.
     *  从 NBT 加载图、运行状态、运行时状态以及任何类型特定数据。
     *  在客户端，如果当前正为此 BE 打开 GraphEditor，则跳过图替换（回弹保护）——
     *  判定走与组合线共用的 {@link GraphBlockEntity#isGraphReplaceBlocked(int)}。
     *  BUS 通道不在此处注册 —— 在首次 tick 时通过 ensureBusRegistered 惰性注册。
     *  @param t the compound tag to read from / 要读取的复合标签
     *  @param r the holder lookup provider for registry access / 用于注册表访问的 HolderLookup.Provider */
    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t, r);
        // 公共段（graph/running/runtime + 三道回弹保护 + 客户端 bump / 服务端强制重编译
        // 组合拳）全部在引擎的 loadHostNBT —— 与组合线逐字段同一实现（阶段 1 收敛）。
        // The common section (graph/running/runtime + the three bounce-back guards + the
        // client bump / server forced-recompile combo) lives entirely in the engine's
        // loadHostNBT — field-for-field the same implementation as the composition line
        // (phase 1 convergence).
        host.loadHostNBT(t, r);
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
     *  @param r the holder lookup provider for registry access / 用于注册表访问的 HolderLookup.Provider */
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

    /** Always send the full graph so that new clients tracking this chunk receive
     *  the authoritative graph data regardless of whether a prior full sync has
     *  already been consumed by another client.
     *  始终发送完整图数据，以确保新追踪此区块的客户端无论先前是否有其他客户端
     *  消费了完整同步，都能收到权威的图数据。
     *  @param r the holder lookup provider / HolderLookup.Provider
     *  @return a compound tag containing all block entity data / 包含所有方块实体数据的复合标签 */
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) {
        var t = new CompoundTag();
        saveAdditional(t, r);
        return t;
    }
}
