package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.api.HostAdmission;
import io.github.y15173334444.create_schematic_compute.api.HostCategory;
import io.github.y15173334444.create_schematic_compute.api.HostGraphBehavior;
import io.github.y15173334444.create_schematic_compute.api.HostGraphHandle;
import io.github.y15173334444.create_schematic_compute.api.HostGraphType;
import io.github.y15173334444.create_schematic_compute.graph.EditSessionTargets;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.NodeAllowance;
import io.github.y15173334444.create_schematic_compute.graph.NodeCategory;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 宿主套件的内部适配器（波 2）：把 api 契约（{@link HostGraphType} 声明 +
 * {@link HostGraphBehavior} 缝）适配成完整 {@link GraphBlockEntity}，内嵌真正的
 * {@link GraphHost} 引擎 —— 组合线先例（{@link KineticGaugeBlockEntity} 形态）的
 * 通用化。第三方 BE 永不见图域对象：图的存取/求值/同步/BUS/持久化全部住在引擎里，
 * 经适配器以图域类型应答 network/client.editor 的既有消费面。
 * The host suite's internal adapter (wave 2): adapts the api contract ({@link
 * HostGraphType} declarations + {@link HostGraphBehavior} seams) into a full
 * {@link GraphBlockEntity} around a real {@link GraphHost} engine — the composition-line
 * precedent ({@link KineticGaugeBlockEntity}'s shape) generalized. Third-party BEs never
 * see graph-domain objects: graph access/evaluation/sync/BUS/persistence all live in the
 * engine, answering the existing network/client.editor consumers through the adapter.
 *
 * <p>public 仅为 {@code api.HostGraphs} 的委托边服务（波 1 {@code CustomNodeRoster}
 * 同款护栏）；本模组内部宿主不经本类。</p>
 * <p>Public only to serve the {@code api.HostGraphs} delegate edge (the wave-1
 * {@code CustomNodeRoster} guardrail family); this mod's own hosts never ride this
 * class.</p>
 */
public final class HostGraphAdapter implements GraphBlockEntity, GraphHost.TickHooks {

    private final BlockEntity owner;
    private final HostGraphType spec;
    private final HostGraphBehavior behavior;
    private final GraphHost host;
    private final Handle handle;
    private final NodeAllowance allowance;

    /** public 仅为 api 门面的委托边服务（波 1 {@code CustomNodeRoster} 同款护栏）。
     *  Public only to serve the api facade's delegate edge (the wave-1 {@code
     *  CustomNodeRoster} guardrail family). */
    public HostGraphAdapter(BlockEntity owner, HostGraphType spec, HostGraphBehavior behavior) {
        // 引擎构造必须晚于 owner 赋值：GraphHost 构造期即经 owner.asBlockEntity() 反驱
        // （字段初始化器先于构造体执行，引擎若在初始化器里构造会在 owner 上 NPE）。
        // The engine must be constructed after the owner assignment: GraphHost's
        // constructor drives back through owner.asBlockEntity() immediately (field
        // initializers run before the constructor body, so building the engine there
        // would NPE on owner).
        this.owner = owner;
        this.spec = spec;
        this.behavior = behavior;
        this.allowance = buildAllowance(spec.admission(), spec.hostKey());
        this.host = new GraphHost(this);
        this.handle = new Handle();
        EditSessionTargets.registerAdapter(owner, this);
    }

    /** 引擎柄视图（bind 的返回值）。 / The engine-handle view (bind's return value). */
    public HostGraphHandle handle() {
        return handle;
    }

    // ── 准入偏好换算 / admission conversion ─────────────────────────────

    /** api 准入偏好 → 内部名单（类目穷举换算 + 排除 id fail-fast 解析；bind 期执行）。
     *  api admission → the internal allowance (exhaustive category conversion + fail-fast
     *  exclusion-id resolution; runs at bind time). */
    private static NodeAllowance buildAllowance(HostAdmission admission, String hostKey) {
        EnumSet<NodeCategory> cats = EnumSet.noneOf(NodeCategory.class);
        for (HostCategory hc : admission.categories()) {
            cats.add(switch (hc) {
                case VALUES -> NodeCategory.VALUES;
                case MATH_BASIC -> NodeCategory.MATH_BASIC;
                case MATH_ADVANCED -> NodeCategory.MATH_ADVANCED;
                case TRIG -> NodeCategory.TRIG;
                case LOGIC -> NodeCategory.LOGIC;
                case CONTROL -> NodeCategory.CONTROL;
                case SEQUENTIAL_ACC -> NodeCategory.SEQUENTIAL_ACC;
                case SEQUENTIAL_STATE -> NodeCategory.SEQUENTIAL_STATE;
                case INPUT_CTRL -> NodeCategory.INPUT_CTRL;
                case INPUT_VIEW -> NodeCategory.INPUT_VIEW;
                case INPUT_MOTION -> NodeCategory.INPUT_MOTION;
                case INPUT_POSE -> NodeCategory.INPUT_POSE;
                case GEARBOX -> NodeCategory.GEARBOX;
                case TRANSMISSION -> NodeCategory.TRANSMISSION;
                case SPEED -> NodeCategory.SPEED;
                case KINETIC -> NodeCategory.KINETIC;
                case OUTPUT -> NodeCategory.OUTPUT;
                case AUDIO -> NodeCategory.AUDIO;
                case RADAR -> NodeCategory.RADAR;
                case DISPLAY -> NodeCategory.DISPLAY;
                case STRUCTURE -> NodeCategory.STRUCTURE;
                case DEBUG -> NodeCategory.DEBUG;
                case CUSTOM -> NodeCategory.CUSTOM;
                case VECTOR -> NodeCategory.VECTOR;
            });
        }
        Set<NodeType> exclusions = new HashSet<>();
        for (String id : admission.excludedNodeIds()) {
            NodeType t = NodeType.BY_ID.get(id);
            if (t == null)
                throw new IllegalArgumentException(
                    "unknown excluded node id in host admission (" + hostKey + "): " + id);
            exclusions.add(t);
        }
        return exclusions.isEmpty()
            ? NodeAllowance.of(hostKey, cats)
            : NodeAllowance.of(hostKey, cats, exclusions.toArray(NodeType[]::new));
    }

    // ── 引擎柄 / engine handle ──────────────────────────────────────────

    /** 柄实现：宿主 BE 的驱动面逐行进引擎（组合线 write/read 门控口径在 save 内收口）。
     *  The handle implementation: the host BE's drive surface, one line into the engine
     *  (the composition-line write/read gate closes inside save). */
    private final class Handle implements HostGraphHandle {

        @Override public void tick() {
            host.tickBody(HostGraphAdapter.this);
        }

        @Override public void save(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            // 与 CncGearbox/KineticGauge 同策略：客户端数据包只在显式全量同步挂起时
            // 携带整图，其余只带 running 标志——整图随包会冲掉正在进行的编辑。
            // Same policy as CncGearbox/KineticGauge: client packets carry the full graph
            // only while an explicit full sync is pending, otherwise just the running
            // flag — graph-in-every-packet clobbers edits in flight.
            if (!clientPacket || host.isFullSyncPending()) {
                host.saveHostNBT(tag, registries);
            } else {
                tag.putBoolean("running", host.running);
            }
        }

        @Override public void load(CompoundTag tag, HolderLookup.Provider registries) {
            host.loadHostNBT(tag, registries);
        }

        @Override public void onHostLoad() {
            host.onHostLoad();
        }

        @Override public void onHostChunkUnloaded() {
            host.onHostChunkUnloaded();
        }

        @Override public void onHostRemoved() {
            host.onHostRemoved();
            EditSessionTargets.unregisterAdapter(owner);
        }

        @Override public void stopRunning() {
            host.onStopRunning();
        }
    }

    // ── tick 编排偏差桥（spec 声明 + behavior 缝）/ deviation bridges ──────────

    @Override public GraphHost.EvalVariant evalVariant() {
        return switch (spec.evalVariant()) {
            case FULL_STATE -> GraphHost.EvalVariant.FULL_STATE;
            case BASIC -> GraphHost.EvalVariant.BASIC;
            case EMPTY_TABLE -> GraphHost.EvalVariant.EMPTY_TABLE;
        };
    }

    @Override public GraphHost.StopSlot stopSlot() {
        return switch (spec.stopSlot()) {
            case BEFORE_GRAPH_CHECK -> GraphHost.StopSlot.BEFORE_GRAPH_CHECK;
            case AFTER_GRAPH_CHECK -> GraphHost.StopSlot.AFTER_GRAPH_CHECK;
            case AFTER_RECOMPILE -> GraphHost.StopSlot.AFTER_RECOMPILE;
        };
    }

    @Override public boolean lightRecompile() { return spec.lightRecompile(); }

    @Override public boolean litAbsentAbortsTick() { return spec.litAbsentAbortsTick(); }

    @Override public boolean stopEndsTick() { return spec.stopEndsTick(); }

    @Override public boolean activeInputRefresh() { return spec.activeInputRefresh(); }

    @Override public boolean wantsStep7Recover() { return spec.wantsStep7Recover(); }

    @Override public boolean wantsFlipflopDiff() { return spec.wantsFlipflopDiff(); }

    @Override public net.minecraft.world.level.block.state.properties.BooleanProperty litProperty() {
        return spec.litProperty();
    }

    // seatInput() 不进波 2 契约：适配器继承引擎的零值座位输入默认（BASIC/空表档语义成立）。
    // seatInput() stays out of the wave-2 contract: the adapter inherits the engine's
    // zero-state seat-input default (BASIC/empty-table semantics hold).

    @Override public void afterGuard() { behavior.afterGuard(); }

    @Override public void beforeLit() { behavior.beforeLit(); }

    @Override public void beforeGraphCheck() { behavior.beforeGraphCheck(); }

    @Override public void beforeInputs() { behavior.beforeInputs(); }

    @Override public void afterRecover() { behavior.afterRecover(); }

    @Override public void beforeEval() { behavior.beforeEval(); }

    @Override public void afterEval() { behavior.afterEval(); }

    @Override public void afterWriteOutputs() { behavior.afterWriteOutputs(); }

    @Override public void afterEvalBroadcast() { behavior.afterEvalBroadcast(); }

    @Override public void beforeSetChanged() { behavior.beforeSetChanged(); }

    @Override public void afterSetChanged() { behavior.afterSetChanged(); }

    @Override public void stopBody(GraphHost engine) {
        behavior.stopBody(handle);
    }

    // ── GraphBlockEntity 契约桥（组合线同款转发）/ contract bridges ─────────────

    @Override public NodeGraph getNodeGraph() { return host.graph; }

    @Override public NodeAllowance nodeAllowance() { return allowance; }

    @Override public Set<NodeType> loadTimeNodeTypes() { return host.loadTimeNodeTypes(); }

    @Override public Set<String> loadTimeCustomIds() { return host.loadTimeCustomIds(); }

    @Override public io.github.y15173334444.create_schematic_compute.graph.NodePerfView evaluatorView() {
        return host.evaluator;
    }

    @Override public boolean isRunning() { return host.isRunning(); }

    @Override public void setRunning(boolean r) { host.setRunning(r); }

    @Override public boolean graphHasCycles() { return host.graphHasCycles(); }

    @Override public void clearPidState() { host.clearPidState(); }

    @Override public void syncFlipflopStates(Map<Integer, Boolean> states) { host.syncFlipflopStates(states); }

    @Override public void applyCurrentState(int ownerNodeId, int nodeId) { host.applyCurrentState(ownerNodeId, nodeId); }

    @Override public void syncSubFlipflopStates(Map<Integer, Map<Integer, Boolean>> subStates) {
        host.syncSubFlipflopStates(subStates);
    }

    @Override public void syncBusBandsFromServer(String busName, java.util.List<String> bands) {
        host.syncBusBandsFromServer(busName, bands);
    }

    @Override public EvalSnapshot getCachedEvalSnapshot() { return host.getCachedEvalSnapshot(); }

    @Override public void setCachedEvalSnapshot(EvalSnapshot snapshot) { host.setCachedEvalSnapshot(snapshot); }

    @Override public int getPendingLocalOps() { return host.getPendingLocalOps(); }

    @Override public void setPendingLocalOps(int value) { host.setPendingLocalOps(value); }

    @Override public void flagFullSync() { host.flagFullSync(); }

    @Override public void requestFullSync() { host.requestFullSync(); }

    @Override public boolean isGraphReady() { return host.isGraphReady(); }

    @Override public Map<Integer, Boolean> peekSubStateFlipflops(int encapNodeId) {
        return host.peekSubStateFlipflops(encapNodeId);
    }

    @Override public Map<Integer, Boolean> getFlipflopStates() { return host.getFlipflopStates(); }

    // ── 宿主绑定回调（反驱动 owner BE）/ host-binding callbacks (drive owner back) ──

    @Override public BlockEntity asBlockEntity() { return owner; }

    @Override public Level getLevel() { return owner.getLevel(); }

    @Override public BlockPos getBlockPos() { return owner.getBlockPos(); }

    @Override public void setChanged() { owner.setChanged(); }

    @Override public void sendBlockUpdated() {
        Level lvl = owner.getLevel();
        if (lvl != null)
            lvl.sendBlockUpdated(owner.getBlockPos(), owner.getBlockState(), owner.getBlockState(), 3);
    }
}
