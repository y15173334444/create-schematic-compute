package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeAdmission;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.RuntimeState;
import io.github.y15173334444.create_schematic_compute.network.BusBandSyncPacket;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import io.github.y15173334444.create_schematic_compute.network.ChannelOwner;
import io.github.y15173334444.create_schematic_compute.network.ClientboundGraphEvalPacket;
import io.github.y15173334444.create_schematic_compute.network.PrivateChannelAudioPacket;
import io.github.y15173334444.create_schematic_compute.network.RuntimeStateSyncPacket;
import io.github.y15173334444.create_schematic_compute.network.SignalBus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 组合式图托管核心 —— 让挂在 Create {@code KineticBlockEntity} 继承线上的方块实体
 * （如 ProgrammableTransmission / CncGearbox）无需继承 {@link SyncedGraphBlockEntity}（原生 BlockEntity
 * 线，Java 单继承冲突）即可获得完整的图托管能力。
 * Composition-based graph-hosting core — lets block entities on Create's
 * {@code KineticBlockEntity} inheritance line (e.g. ProgrammableTransmission / CncGearbox) get full
 * graph-hosting capability without extending {@link SyncedGraphBlockEntity} (vanilla
 * BlockEntity line — Java single-inheritance conflict).
 *
 * <p>逻辑自 {@link SyncedGraphBlockEntity} 移植（Blueprint 风格的 recompileEvaluatorFull
 * 路径 + BUS 生命周期 + NBT 全套 + 编辑回弹保护），宿主 BE 持有一个本对象并在各
 * 生命周期钩子处转发。tick 编排骨架已两线共用（K23：{@link #tickBody} + {@link TickHooks}）；
 * 其余生命周期面仍由宿主转发。</p>
 * <p>Logic ported from {@link SyncedGraphBlockEntity} (Blueprint-style
 * recompileEvaluatorFull path + BUS lifecycle + full NBT handling + editor bounce-back
 * protection). The hosting BE owns one instance and forwards lifecycle hooks. The tick
 * orchestration is now shared by both lines (K23: {@link #tickBody} + {@link TickHooks});
 * the remaining lifecycle surfaces stay host-forwarded.</p>
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
    /** 上次下发的求值快照（「变化才发」门控基线，服务端宿主生命周期）。
     *  Last shipped eval snapshot (the send-on-change baseline; server host lifetime). */
    private EvalSnapshot lastSentEvalSnapshot;
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

    // ── tick 编排骨架（K23 收敛）/ tick orchestration skeleton (K23) ──

    /** ⑤ 停机检查的执行位（偏差矩阵「停止路径」列的三种口径）。
     *  Where the stopped-host check runs (the three shapes of the matrix's stop-path column). */
    public enum StopSlot {
        /** ③ 之前（Speaker：停止早退先于图检查）。 / before ③ (Speaker: the stop early-return precedes the graph check). */
        BEFORE_GRAPH_CHECK,
        /** ③ 与 ④ 之间（Monitor / SpeedProxy：停机不重编译）。 / between ③ and ④ (Monitor / SpeedProxy: no recompile while stopped). */
        AFTER_GRAPH_CHECK,
        /** ④ 之后（Blueprint 基准口径）。 / after ④ (the Blueprint baseline). */
        AFTER_RECOMPILE,
    }

    /** ⑨ 求值档（偏差矩阵「evaluate」列的三档）。档位合一（2026-10-31 拍板）：原 4 参
     *  SeatInput 无状态档并入全状态档——七类时序/状态节点在 Sensor/ControlSeat 上随
     *  runtimeState 三表真正工作，静默空转怪癖熄灭。
     *  The evaluate variant (the matrix's three forms). Tier unification (2026-10-31
     *  ruling): the old 4-arg SeatInput stateless form merged into the full-state
     *  form — the seven timing/state types actually run on Sensor/ControlSeat via
     *  runtimeState's tables, the silently-idling quirk extinguished. */
    public enum EvalVariant {
        /** 全状态档：delay/flipflop/pulse 走宿主权威状态表（runtimeState 三表），seat 随行
         *  （Sensor / ControlSeat 的座椅输入；其余宿主零值）。八宿主。
         *  The full-state form: delay/flipflop/pulse ride the host's authoritative maps
         *  (runtimeState's three tables), seat riding along (the Sensor / ControlSeat
         *  seat input; zero state on the other hosts). Eight hosts. */
        FULL_STATE,
        /** 3 参基础档（Monitor / KineticGauge / SpeedProxy；内部按空状态表走全状态路径）。
         *  The 3-arg basic form (internally routes the full-state path with empty maps). */
        BASIC,
        /** 空表档：SeatInput + 每 tick 丢弃的空状态表（Radar —— 时序节点不跨 tick 演进）。
         *  Empty-table form: SeatInput + throwaway per-tick state maps (Radar — timing nodes
         *  never evolve across ticks). */
        EMPTY_TABLE,
    }

    /**
     * tick 骨架的宿主钩子（K23）：模板步序住骨架，宿主特有行为经本接口的缝与策略保留。
     * 默认值 = Blueprint 基准口径；各宿主只覆写自己的偏差列。**组合不建继承层次**——
     * 宿主 BE 直接实现本接口并把 {@code this} 传给 {@link #tickBody(TickHooks)}。
     * Host hooks for the tick skeleton (K23): the template step order lives in the
     * skeleton, host-specific behaviour rides the seams and policies below. Defaults =
     * the Blueprint baseline; each host overrides only its own deviation columns.
     * **Composition, no inheritance hierarchy** — the host BE implements this interface
     * and hands {@code this} to {@link #tickBody(TickHooks)}.
     */
    public interface TickHooks {

        // ── 步序策略（偏差矩阵各列的变体）/ step policy (the matrix's column variants) ──

        /** ② LIT 属性；null = 本宿主无 ②（KineticGauge / CncGearbox / PT / Speaker）。
         *  Step ② LIT property; null = this host has no step ②. */
        @Nullable
        default BooleanProperty litProperty() { return null; }

        /** ② 属性缺席时中止本 tick（蓝图机等六宿主口径）；false = 跳过 ② 继续（功放电脑 / 雷达）。
         *  两形在 12 块方块均注册 LIT 的前提下逐调用等价（hasProperty 恒真）。
         *  Step ②: abort the tick when the property is absent (six hosts); false = skip
         *  step ② and continue (AmplifierComputer / Radar). Both shapes are
         *  call-for-call equivalent since every block registers LIT. */
        default boolean litAbsentAbortsTick() { return true; }

        /** ④ 重编译档：true = Light（Monitor / KineticGauge / SpeedProxy），false = Full。
         *  Step ④ recompile variant: true = Light, false = Full. */
        default boolean lightRecompile() { return false; }

        /** ⑤ 停机检查的执行位。 / Step ⑤ stop-check slot. */
        default StopSlot stopSlot() { return StopSlot.AFTER_RECOMPILE; }

        /** ⑤ 停机后是否立即结束本 tick；false = 落入共用尾段（CncGearbox / PT 的 if/else 口径）。
         *  Step ⑤: end the tick on stop; false = fall into the shared tail (the
         *  CncGearbox / PT if-else shape). */
        default boolean stopEndsTick() { return true; }

        /** ⑥ 刷新档：true = refreshInputsActive（A 档），false = refreshInputs（H 档，混合回退）。
         *  Step ⑥ refresh variant: true = refreshInputsActive (A), false = refreshInputs (H). */
        default boolean activeInputRefresh() { return false; }

        /** ⑨ 求值档（逐宿主必填——矩阵 ⑨ 列即此声明）。 / Step ⑨ evaluate variant —
         *  required per host (this declaration IS the matrix's ⑨ column). */
        EvalVariant evalVariant();

        /** ⑨ 全状态档/空表档的座位输入构造（Sensor / ControlSeat / Radar；其余宿主零值）。
         *  Step ⑨ seat input for the full-state/empty-table forms (zero state elsewhere). */
        default GraphEvaluator.SeatInputState seatInput() {
            return new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0);
        }

        /** ⑦ 冲突自愈的**遗留第二挂点**在位（六宿主）。注意：自愈的**主**挂点在 ①
         *  {@link #ensureBusRegistered()} 内（92d0dbf：每 tick 开头、图停止也跑、先于频段
         *  收敛）——其余六宿主**不**声明本步，避免同一 tick 双跑自愈（宿主编排批迁移账）。
         *  Step ⑦: the legacy second self-heal hook is present (six hosts). The PRIMARY
         *  heal already runs inside ① {@link #ensureBusRegistered()} (92d0dbf: every tick
         *  start, stopped or running, before the band convergence) — the other six hosts
         *  do NOT declare this step, avoiding a double heal per tick (host-orchestration
         *  migration account). */
        default boolean wantsStep7Recover() { return false; }

        /** ⑬ 触发器差分广播在位（全状态档八宿主；BASIC / EMPTY_TABLE 档宿主无此步——
         *  矩阵 ⑬ 列口径原样）。
         *  Step ⑬ flipflop-diff broadcast present (the eight full-state hosts; the
         *  BASIC / EMPTY_TABLE hosts carry no such step). */
        default boolean wantsFlipflopDiff() { return false; }

        // ── hook 缝（宿主特有行为按模板位置插入）/ seams (host behaviour at template slots) ──

        /** 缝 0：守卫后、① 前（KineticGauge 读数推送 / CncGearbox autoSenseInputFace）。
         *  Seam 0: after the guard, before ①. */
        default void afterGuard() {}

        /** 缝 1：⑮ 后、② 前（ControlSeat 消费输入 / Speaker 红石静音采样 / Radar 扫描关卡捕获）。
         *  Seam 1: after ⑮, before ②. */
        default void beforeLit() {}

        /** 缝 2：⑤ 早退后、③ 前（CncGearbox 离合意图复位 / PT 分叉前快照）。
         *  Seam 2: after the ⑤ early-return, before ③. */
        default void beforeGraphCheck() {}

        /** 缝 3：④/⑤ 后、⑥ 前（Sensor 姿态/加速度、Radar 扫描段、Speaker 音频注入）。
         *  Seam 3: after ④/⑤, before ⑥. */
        default void beforeInputs() {}

        /** 缝 4：⑦ 后、⑧ 前（AmplifierComputer 传输表剪枝 + 注入）。 / Seam 4: after ⑦, before ⑧. */
        default void afterRecover() {}

        /** 缝 5：⑧ 后、⑨ 前（ControlSeat mode-1 inflate + 姿态/加速度、Radar 注入）。
         *  Seam 5: after ⑧, before ⑨. */
        default void beforeEval() {}

        /** 缝 6：⑨ 后、⑩ 前（Radar setRadarPos(null) 清理）。 / Seam 6: after ⑨, before ⑩. */
        default void afterEval() {}

        /** 缝 7：⑩ 后、⑪ 前（Monitor 红石差分推送）。 / Seam 7: after ⑩, before ⑪. */
        default void afterWriteOutputs() {}

        /** 缝 8：⑫⑬ 后、共用尾段前（CncGearbox 指令推进 / PT 控制源判定 / SpeedProxy 转速落盘 /
         *  AmplifierComputer 频谱推进）。 Seam 8: after ⑫⑬, before the shared tail. */
        default void afterEvalBroadcast() {}

        /** 缝 9：⑭ 前（CncGearbox 编码器/离合/运行态、PT 目标回填/对账/差分发包）。
         *  Seam 9: before ⑭. */
        default void beforeSetChanged() {}

        /** 缝 10：⑭ 后（Radar 两平面同步尾段）。 / Seam 10: after ⑭. */
        default void afterSetChanged() {}

        /** ⑤ 停机路径本体。默认 = onStopRunning()；Radar 保留内联形态、AmplifierComputer /
         *  CncGearbox / PT 追加各自复位。 Step ⑤ stop-path body. Default = onStopRunning();
         *  Radar keeps its inlined shape, AmplifierComputer / CncGearbox / PT append resets. */
        default void stopBody(GraphHost engine) { engine.onStopRunning(); }
    }

    /**
     * 规范 15 步 tick 编排骨架（K23 收敛）——12 份宿主 tick 体的共同编排收敛于此，
     * 宿主特有行为经 {@link TickHooks} 缝/策略逐宿主保留（含全部偏差与缺失，行为零变更）。
     * 步序 = Blueprint 基准口径（矩阵编号 ≠ 执行位：⑮ 紧随 ①，见 P1-8）：
     * ①ensureBusRegistered → ⑮flushPendingFullSync → ②LIT → ③checkGraphChanged →
     * ④重编译(F/L) → ⑤停止路径 → ⑥refreshInputs(A/H) → ⑦recoverConflictedChannels(遗留挂点) →
     * ⑧buildInputs → ⑨evaluate(6/4/3/空表) → ⑩writeOutputs → ⑪broadcastEvalSnapshot →
     * ⑫syncIfBandsChanged → ⑬broadcastFlipflopDiff → ⑭setChanged。
     * Canonical 15-step tick orchestration (K23 convergence) — the common orchestration
     * of the twelve host tick bodies lives here; host-specific behaviour rides the
     * {@link TickHooks} seams/policies with every deviation and gap preserved verbatim
     * (zero behaviour change). Step order = the Blueprint baseline (the matrix numbers
     * are labels, not execution order: ⑮ runs right after ①, see P1-8).
     *
     * <p>① 语义钉死（92d0dbf，v1.2.6.1 冲突自愈）：ensureBusRegistered 在每个 tick 开头、
     * **先于一切早退**执行——图停止也跑（内含冲突自愈 + issue #15 频段收敛 + 音频标志
     * 推送），自愈先于收敛。⑤ 的执行位/形态、④ F/L、⑥ A/H、⑨ 三档、⑦ 遗留挂点与 ⑬ 有无
     * 均按宿主声明保留；位置语义不得动：⑮ 在停机早退之前（P1-8）、⑦ 在 ⑥ 与 ⑧ 之间、
     * ⑬ 在 ⑨ 之后。⑫ 已为 12 宿主统一在位（275ec03 直呼形态收编，wantsBandSync 开关退役）。
     * The ① semantics are frozen (92d0dbf, the v1.2.6.1 conflict self-heal):
     * ensureBusRegistered runs at the top of every tick **before every early return** —
     * stopped graphs included (it carries the conflict heal + the issue-15 band
     * convergence + the audio-flag push), the heal ahead of the convergence. The ⑤
     * slot/shape, ④ F/L, ⑥ A/H, the three ⑨ forms, the ⑦ legacy hook and ⑬ presence
     * follow the host declarations; the position semantics are frozen: ⑮ before every
     * stop early-return (P1-8), ⑦ between ⑥ and ⑧, ⑬ after ⑨. ⑫ is wired for all
     * twelve hosts (the 275ec03 direct-call form absorbed; the wantsBandSync switch retired). */
    public void tickBody(TickHooks h) {
        // ── 守卫 / guard ──
        if (lvl() == null || lvl().isClientSide()) return;
        h.afterGuard();

        // ① BUS 注册（92d0dbf 语义：内含冲突自愈 + issue #15 频段收敛 + 音频标志推送；
        //    图停止也跑、自愈先于收敛——见 ensureBusRegistered）。
        // ① BUS registration (92d0dbf semantics: conflict heal + issue-15 band convergence
        //    + audio-flag push inside; runs stopped, heal before convergence).
        ensureBusRegistered();
        // ⑮ 挂起全量同步冲刷（P1-8）：必须位于**一切**早退之前——停机宿主也要能把图
        //    推给新加入的编辑者。矩阵编号⑮列在末位，执行位在①之后（Blueprint 基准）。
        // ⑮ pending-full-sync flush (P1-8): before EVERY early return so a stopped host
        //    still ships its graph to a newly joined editor. The matrix numbers it last;
        //    it executes right after ① (Blueprint baseline).
        flushPendingFullSync();
        h.beforeLit();

        // ② LIT 指示同步（8 宿主有、4 宿主无）。形态归一：原「缺席即中止 / 缺席即跳过」
        //    两形在全部方块均注册 LIT（hasProperty 恒真）下逐调用等价，缺席分支语义仍按
        //    宿主保留（litAbsentAbortsTick）。
        // ② LIT indicator sync (8 hosts, 4 without). Shape unified: the old
        //    "abort-on-absent / skip-on-absent" pair is call-for-call equivalent since
        //    every block registers LIT (hasProperty always true); the absent-branch
        //    semantics still follow the host (litAbsentAbortsTick).
        BooleanProperty lit = h.litProperty();
        if (lit != null) {
            boolean shouldBeLit = running && !graph.nodes.isEmpty();
            var st = owner.asBlockEntity().getBlockState();
            if (st.hasProperty(lit)) {
                if (st.getValue(lit) != shouldBeLit)
                    lvl().setBlock(pos(), st.setValue(lit, shouldBeLit), 3);
            } else if (h.litAbsentAbortsTick()) {
                return;
            }
        }

        // ⑤ 停机路径——执行位按宿主（StopSlot），形态按 stopEndsTick：
        //    早退口径（十宿主）返回；CncGearbox / PT 的 if/else 口径落共用尾段。
        // ⑤ stop path — slot per host (StopSlot), shape per stopEndsTick: the
        //    early-return shape (ten hosts) bails; the CncGearbox / PT if-else shape
        //    falls into the shared tail.
        if (h.stopSlot() == StopSlot.BEFORE_GRAPH_CHECK && !running) {
            h.stopBody(this);
            return;
        }
        h.beforeGraphCheck();

        // ③ 图变更检测（红石链路注册的唯一自愈口，P2-18）
        // ③ graph-change detection (the only self-heal for link registration, P2-18)
        rs.checkGraphChanged(graph);

        if (h.stopSlot() == StopSlot.AFTER_GRAPH_CHECK && !running) {
            h.stopBody(this);
            return;
        }

        // ④ 重编译：F = 全状态保留 / L = 轻量（debugTime）
        // ④ recompile: F = state-preserving / L = light (debugTime)
        if (graphChanged()) {
            if (h.lightRecompile()) recompileEvaluatorLight();
            else recompileEvaluatorFull();
        }

        if (h.stopSlot() == StopSlot.AFTER_RECOMPILE && !running) {
            h.stopBody(this);
            if (h.stopEndsTick()) return;
        }

        if (running) {
            h.beforeInputs();
            // ⑥ 刷新红石链路输入（A = Active / H = Hybrid 混合回退）
            // ⑥ refresh redstone-link inputs (A = Active / H = Hybrid fallback)
            if (h.activeInputRefresh()) rs.refreshInputsActive();
            else rs.refreshInputs();
            // ⑦ 冲突频道自愈的**遗留第二挂点**（六宿主声明在位；主挂点已在 ① 内先行，
            //    Blueprint 样板：接管 + 超时回收；有恢复才全量同步 + 立即发包）。
            // ⑦ the legacy second conflicted-channel self-heal hook (declared by six
            //    hosts; the primary heal already ran inside ① — Blueprint style:
            //    takeover + stale-owner reclaim; full sync + immediate push only on recovery)
            if (h.wantsStep7Recover() && BusChannelHelper.recoverConflictedChannels(graph, pos(), lvl())) {
                requestFullSync();
                pushBlockUpdate();
            }
            h.afterRecover();
            // ⑧ 按图构建求值输入 / build evaluation inputs from the graph
            var in = rs.buildInputs(graph);
            h.beforeEval();
            // ⑨ 求值——档位合一（2026-10-31 拍板）：全状态档 seat + runtimeState 三表，
            // 七类时序/状态节点真正工作；BASIC 弃置三表、EMPTY_TABLE seat + 弃置三表。
            // ⑨ evaluate — tier unification (2026-10-31 ruling): the full-state form
            //    passes seat + runtimeState's three tables so the seven timing/state
            //    types actually run; BASIC uses throwaway tables, EMPTY_TABLE seat +
            //    throwaway tables.
            float dt = 0.05f;
            var results = switch (h.evalVariant()) {
                case FULL_STATE -> evaluator.evaluate(in, runtimeState.pidState, dt, h.seatInput(),
                    runtimeState.delayQueues, runtimeState.flipflopStates, runtimeState.pulseTimers);
                case BASIC -> evaluator.evaluate(in, runtimeState.pidState, dt);
                case EMPTY_TABLE -> evaluator.evaluate(in, runtimeState.pidState, dt, h.seatInput(),
                    new HashMap<>(), new HashMap<>(), new HashMap<>());
            };
            h.afterEval();
            // ⑩ 写出红石输出 / write redstone outputs
            rs.writeOutputs(results);
            h.afterWriteOutputs();
            // ⑪ 求值快照广播（DEBUG_PROBE 采样）/ broadcast eval snapshot (DEBUG_PROBE)
            broadcastEvalSnapshot();
            // ⑫ 频段变更同步（12 宿主统一在位：275ec03 直呼形态收编进骨架，开关退役）
            // ⑫ band-change sync (wired for all twelve: the 275ec03 direct-call form
            //    absorbed into the skeleton, the switch retired)
            BusChannelHelper.syncIfBandsChanged(graph, pos(), lastBusHashMap, lvl());
            // ⑬ 触发器差分广播（基线去重，无变化零发包；BASIC / EMPTY_TABLE 档宿主无此步）
            // ⑬ flipflop-diff broadcast (baseline-diffed, silent while unchanged; the
            //    BASIC / EMPTY_TABLE hosts carry no such step)
            if (h.wantsFlipflopDiff()) broadcastFlipflopDiff();
            h.afterEvalBroadcast();
        }
        h.beforeSetChanged();
        // ⑭ 运行态落盘标记 / running-state persist marker
        markDirty();
        h.afterSetChanged();
    }

    // ── BUS 生命周期 / BUS lifecycle ──

    /** 每个 tick 开始调用：保证 BUS 通道至少注册一次（旧存档自动修复），并维护
     *  「BUS_IN 的频段列表 = 频道定义」这条不变量（issue #15）。
     *  Call at tick start: guarantees channels register at least once (auto-repairs old saves) and
     *  maintains the invariant "a BUS_IN's band list equals the channel definition" (issue #15). */
    public void ensureBusRegistered() {
        if (busRegistrationPending) {
            busRegistrationPending = false;
            registerBusChannels();
        }
        // 冲突自愈先行（全宿主统一挂点，图停止运行也跑）：候选必须在**同一 tick 内**先接管、
        // 把频段定义带回来，再跑下面的收敛 —— 否则持有者改名走人后定义缺失，收敛与 BUS_IN
        // 只能看到空（用户症状：B 改名走人后 BUS_IN 频段被清，要手动去刷 BUS_OUT 才发得回来）。
        // 旧挂点散在各宿主 tick 且排在 isRunning() 早退之后：图一停就没人自愈。
        // Recovery first (one hook for every host, stopped or running): a claimant must take
        // over and bring the band definition back within the same tick, before the convergence
        // below runs — otherwise a departed holder leaves the definition missing and the
        // convergence/BUS_INs see only emptiness (user symptom: after B renamed away the BUS_IN
        // lost its bands and only a manual BUS_OUT refresh brought them back). The old per-host
        // hooks sat behind the isRunning() early return, so a stopped graph never self-healed.
        if (BusChannelHelper.recoverConflictedChannels(graph, pos(), lvl())) {
            requestFullSync();
            markDirty();
            pushBlockUpdate();
        }
        // issue #15：把本图 BUS_IN 的频段列表收敛到频道定义，**只有真的变了才发**节点数据。
        // 频道**无已加载发布方**时整轮跳过 —— 缺席不是定义，见 convergeBusInBands。
        // 走 **BusBandSyncPacket**（按方块 + 频道名推，客户端只改匹配节点的频段列表）——
        // **绝不能**改用整图 NBT 推送（flagFullSync），那会冲掉正在进行的编辑。
        // 挂在这里是因为**每个**图宿主每 tick 都会调用本方法（9 个宿主统一），无需在各宿主里
        // 重复挂接；只挂在 syncIfBandsChanged 上会漏掉 Monitor / 变速箱 / 可编程变速箱 / SpeedProxy。
        // issue #15: converge this graph's BUS_IN bands to the channel definition and send node
        // data only when that actually changed something. Channels with **no loaded publisher**
        // are skipped for the pass — absence is not a definition; see convergeBusInBands.
        // It travels as a **BusBandSyncPacket**
        // (per block + channel name; the client only rewrites the matching nodes' band lists) —
        // never as a whole-graph NBT push (flagFullSync), which would clobber edits in flight.
        // This hook lives here because **every** graph host calls this method each tick (all nine
        // of them); hanging it only off syncIfBandsChanged would miss Monitor, the gearbox, the
        // programmable transmission and SpeedProxy.
        if (lvl() instanceof ServerLevel sl) {
            var changedChannels = BusChannelHelper.convergeBusInBands(graph);
            if (!changedChannels.isEmpty()) {
                markDirty();   // 收敛结果要落盘 / persist the converged lists
                for (var e : changedChannels.entrySet()) {
                    PacketDistributor.sendToPlayersTrackingChunk(sl, new ChunkPos(pos()),
                        new BusBandSyncPacket(pos(), e.getKey(), e.getValue()));
                }
            }
            pushAudioFlagChanges(sl);
        }
    }

    /** 每频道上次推送的标志版本（pushAudioFlagChanges 的去重缓存；宿主实例生命周期）。
     *  Per-channel last-pushed flag version (pushAudioFlagChanges dedup cache; host lifetime). */
    private final java.util.Map<String, Integer> pushedAudioFlags = new java.util.HashMap<>();

    /** 频段音频标志推送：本图引用的每个频道，标志版本与上次推送不同就重发频段定义 + 标志。
     *  发布方求值器负责把标志写入 SignalBus（拓扑判定），这里只管变化检测与投递。
     *  Push per-band audio flags: for every channel this graph references, resend the band
     *  definition + flags when the flag version moved past the last push. The publisher's
     *  evaluator writes the flags into SignalBus (topology-derived); this only detects and ships.
     *  <p>私有频道同款（引脚类型变换的同步面）：本图引用的私有名，定义版本变了就推
     *  {@link PrivateChannelAudioPacket}。缓存键带表前缀——BUS 与私有分表，同名各自计版。</p> */
    private void pushAudioFlagChanges(ServerLevel sl) {
        var busNames = new java.util.HashSet<String>();
        var privateNames = new java.util.HashSet<String>();
        for (var n : graph.nodes) {
            if (n.signalName.isEmpty()) continue;
            if (n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT) busNames.add(n.signalName);
            else if (n.type == NodeType.PRIVATE_IN || n.type == NodeType.PRIVATE_OUT) privateNames.add(n.signalName);
        }
        for (String busName : busNames) {
            int stamp = io.github.y15173334444.create_schematic_compute.network.SignalBus.audioStamp(busName);
            Integer sent = pushedAudioFlags.get("B:" + busName);
            if (sent != null && sent == stamp) continue;
            var bands = io.github.y15173334444.create_schematic_compute.network.SignalBus.getBands(busName);
            if (bands == null || bands.isEmpty()) { pushedAudioFlags.put("B:" + busName, stamp); continue; }
            var audio = io.github.y15173334444.create_schematic_compute.network.SignalBus.getAudioBands(busName);
            var flags = new java.util.ArrayList<Boolean>(bands.size());
            for (String band : bands) flags.add(audio.contains(band));
            PacketDistributor.sendToPlayersTrackingChunk(sl, new ChunkPos(pos()),
                new BusBandSyncPacket(pos(), busName, bands, flags));
            pushedAudioFlags.put("B:" + busName, stamp);
        }
        for (String name : privateNames) {
            int stamp = io.github.y15173334444.create_schematic_compute.network.SignalBus.privateAudioStamp(name);
            Integer sent = pushedAudioFlags.get("P:" + name);
            if (sent != null && sent == stamp) continue;
            PacketDistributor.sendToPlayersTrackingChunk(sl, new ChunkPos(pos()),
                new PrivateChannelAudioPacket(name,
                    io.github.y15173334444.create_schematic_compute.network.SignalBus.isPrivateAudio(name)));
            pushedAudioFlags.put("P:" + name, stamp);
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
     * <p>替换图的调用点（{@link #loadHostNBT} / 客户端包加载）在 {@code graph.bumpGeneration()}
     * 之后必须调用本方法：NodeGraph.load 产生 generation=0 的新图，bump 一次到 1 可能与
     * 上次重编译留下的 lastGraphGeneration=1 冲突，graphChanged() 为 false → 重编译（及
     * BUS 重注册）被跳过 → BUS_IN 读 0（回归审计：反复编译+运行失效）。
     * Call sites that replace the graph (loadHostNBT / client packet loads) must
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
        evaluator.setSignalBus(io.github.y15173334444.create_schematic_compute.network.SignalBus.PORT);
        evaluator.setAudioBands(io.github.y15173334444.create_schematic_compute.network.AudioBands.PORT);
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
        evaluator.setSignalBus(io.github.y15173334444.create_schematic_compute.network.SignalBus.PORT);
        evaluator.setAudioBands(io.github.y15173334444.create_schematic_compute.network.AudioBands.PORT);
        // 定制回调（encoderView / commandSink 等）必须在每条重建路径上重放——与 Full 一致；
        // 只挂 Full 时，未来用 Light 的宿主会重演「ENCODER 恒 0」。
        // The evaluator customizer must replay on EVERY rebuild path — same as Full;
        // hanging it off Full only would re-run the "ENCODER pinned at 0" bug for any
        // future Light host that carries a customizer.
        if (evaluatorCustomizer != null) evaluatorCustomizer.accept(evaluator);
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

    /** 注销上次重编译后已删除节点的 BUS_OUT / 私有发布节点（改名 = 旧名移除，占用与值随之释放）。
     *  Unregister BUS_OUT / private publishers removed since last recompile (a rename counts
     *  as removing the old name, releasing its occupancy and value). */
    private void unregisterRemovedBusOutNodes() {
        if (lvl() == null || lvl().isClientSide() || lastBusOutKeys.isEmpty()) return;
        Set<String> currentKeys = new HashSet<>();
        for (var n : graph.nodes)
            if ((n.type == NodeType.BUS_OUT || n.type == NodeType.PRIVATE_OUT) && !n.signalName.isEmpty())
                currentKeys.add(snapshotKey(n));
        for (var key : lastBusOutKeys) {
            if (!currentKeys.contains(key)) {
                boolean priv = key.startsWith("P:");
                String rest = key.substring(2);
                int at = rest.lastIndexOf('@');
                if (at > 0) {
                    String name = rest.substring(0, at);
                    int nodeId = Integer.parseInt(rest.substring(at + 1));
                    var owner = new ChannelOwner(pos(), nodeId);
                    if (priv) SignalBus.unregisterPrivateChannel(name, owner);
                    // 删除 = 确证离开：统一释放（owner 门控 + 死名退役），旧名 BUS_IN 得以清旧图
                    // Delete = provable departure: unified release (owner-gated + dead-name
                    // retirement) so old-name BUS_INs drop their stale lists.
                    else SignalBus.releaseChannel(name, owner);
                }
            }
        }
    }

    /** 发布节点快照键：类型前缀 + 名@id——节点移除后类型无从查起，键集必须自带表别
     * （BUS 与私有分表，同名互不冲突）。/ Publisher snapshot key: type tag + name@id —
     * the node type is unrecoverable once the node is gone, so the key carries the table. */
    private static String snapshotKey(GraphNode n) {
        return (n.type == NodeType.PRIVATE_OUT ? "P:" : "B:") + n.signalName + "@" + n.id;
    }

    /** 快照当前发布节点（BUS_OUT / 私有）键集。 / Snapshot current publisher key set. */
    private void snapshotBusOutKeys() {
        lastBusOutKeys.clear();
        for (var n : graph.nodes)
            if ((n.type == NodeType.BUS_OUT || n.type == NodeType.PRIVATE_OUT) && !n.signalName.isEmpty())
                lastBusOutKeys.add(snapshotKey(n));
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

    /** 向追踪客户端广播求值快照——<b>内容变化才发</b>（flipflop 差分同款）：求值快照是节点值
     *  数据面，每刻重发不变内容正是「暂停时总线数据面反复刷新」的来源。新追踪玩家的首帧由
     *  ChunkWatch 补发（SchematicCompute），不由每刻广播兜底。
     *  Broadcast the eval snapshot to tracking players — <b>only when its content changed</b>
     *  (same discipline as the flipflop diff). Reshipping unchanged values every tick is what
     *  kept the bus data plane "refreshing" while playback was paused; a newly tracking player
     *  gets their first frame from the ChunkWatch backfill (SchematicCompute) instead of riding
     *  a per-tick broadcast. */
    public void broadcastEvalSnapshot() {
        if (lvl() instanceof ServerLevel sl && evaluator != null) {
            evaluator.saveDebugTimes(runtimeState);
            var snapshot = evaluator.captureSnapshot();
            cachedEvalSnapshot = snapshot;
            if (snapshot.sameContent(lastSentEvalSnapshot)) return;
            lastSentEvalSnapshot = snapshot;
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

    /** 全量同步是否仍处于挂起状态（BE 的 clientPacket 写入据此决定是否携带整图）。
     *  Whether a full sync is still pending (the BE's clientPacket write uses this to decide
     *  whether to ship the graph at all). */
    public boolean isFullSyncPending() { return needsFullSync; }

    /** 服务端 tick 调用：按 grace 间隔冲刷待同步请求。 / Flush pending request per grace interval (server tick). */
    public void flushPendingFullSync() {
        var l = lvl();
        if (l == null || l.isClientSide() || !needsFullSync) return;
        long now = l.getGameTime();
        if (now - lastFullSyncGameTime < FULL_SYNC_GRACE_TICKS) return;
        lastFullSyncGameTime = now;
        // 先推送再清标志：clientPacket 写入在 pushBlockUpdated 内同步序列化，
        // 必须仍能看到挂起标志才会在数据包里携带整图。
        // Push first, clear after: the clientPacket write serializes synchronously inside
        // pushBlockUpdated and must still see the pending flag to ship the graph.
        pushBlockUpdate();
        needsFullSync = false;
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

    // ── flipflop 差分广播 / flipflop diff broadcast ──

    /** 已同步的主图触发器基线（差分避免每 tick 广播）。/ Last-synced main flipflop baseline. */
    private Map<Integer, Boolean> lastSyncedFlipflopStates = null;
    /** 已同步的子图触发器基线（encapNodeId → sub-node flipflop）。/ Last-synced sub-graph baseline. */
    private Map<Integer, Map<Integer, Boolean>> lastSyncedSubFlipflopStates = null;

    /**
     * 服务端 tick 调用：主图与子图触发器状态相对基线做差分，有变化才广播
     * RuntimeStateSyncPacket（子图按自己的基线差分，而不是"存在即变更"，
     * 避免每 tick 刷包）。
     * Call from the server tick: diff main- and sub-graph flipflop states against the
     * baselines and broadcast RuntimeStateSyncPacket only on change (sub-graphs diff
     * against their own baseline instead of treating presence as change — prevents
     * per-tick packet spam). */
    public void broadcastFlipflopDiff() {
        var l = lvl();
        if (!(l instanceof ServerLevel sl)) return;
        var currentFf = runtimeState.flipflopStates;
        boolean changed = !currentFf.equals(lastSyncedFlipflopStates);
        java.util.Map<Integer, Map<Integer, Boolean>> subFf = java.util.Collections.emptyMap();
        if (!runtimeState.subStates.isEmpty()) {
            var curSub = new HashMap<Integer, Map<Integer, Boolean>>();
            for (var se : runtimeState.subStates.entrySet()) {
                if (!se.getValue().flipflopStates.isEmpty())
                    curSub.put(se.getKey(), new HashMap<>(se.getValue().flipflopStates));
            }
            if (!curSub.equals(lastSyncedSubFlipflopStates)) {
                changed = true;
                subFf = curSub;
            }
        } else if (lastSyncedSubFlipflopStates != null && !lastSyncedSubFlipflopStates.isEmpty()) {
            // 子图全部消失（封装被删除等）也视为变更，通知客户端清空
            // All sub-states disappeared (e.g. encap removed) — also a change.
            changed = true;
        }
        if (changed) {
            lastSyncedFlipflopStates = new HashMap<>(currentFf);
            lastSyncedSubFlipflopStates = subFf.isEmpty() ? null : subFf;
            PacketDistributor.sendToPlayersTrackingChunk(sl, new ChunkPos(pos()),
                new RuntimeStateSyncPacket(pos(), lastSyncedFlipflopStates, subFf));
        }
    }

    // ── NBT / NBT ──

    /** 写入公共字段（graph/running/runtime）；类型特定数据由宿主另行追加。
     *  Writes common fields (graph/running/runtime); type-specific data appended by host. */
    public void saveHostNBT(CompoundTag t, HolderLookup.Provider r) {
        t.put("graph", graph.save(r));
        // 图内容指纹 = 图代际（实例内单调计数，只随图变更增长，同代际即同内容）——
        // loadHostNBT 据此跳过「内容未变」的重复重载（雷达目标同步的每 tick 推送
        // 不再砸本地图）。/ Graph content fingerprint = the graph generation (a
        // per-instance monotonic counter that grows only on graph changes — same
        // generation means same content). loadHostNBT uses it to skip reloading
        // unchanged content (the radar's per-tick target pushes stop clobbering the
        // local graph).
        t.putInt("hostGraphGen", graph.graphGeneration);
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
    /** 上次成功装入的 hostGraphGen（图内容指纹）；MIN_VALUE = 尚未装入。
     *  The hostGraphGen (graph content fingerprint) of the last successful load;
     *  MIN_VALUE = nothing loaded yet. */
    private int lastLoadedGraphGen = Integer.MIN_VALUE;

    /** 装载时图树类型普查（P2-7 旧档兼容口径）：每次图替换点重算一次，本会话内不随节点
     *  增删变化——服务端准入门（`graph.NodeAdmission`）据此放行旧档已携带的类型。volatile：
     *  op 应用线程与装载线程可能不同步发布。
     *  Load-time graph-tree type census (the P2-7 legacy tolerance): recomputed once per
     *  graph replacement, unchanged by later node add/remove within the session — the
     *  server admission gate (`graph.NodeAdmission`) admits legacy-carried types from it.
     *  volatile: the op-application and load threads may publish unsynchronized. */
    private volatile java.util.Set<NodeType> loadTimeNodeTypes = java.util.Collections.emptySet();

    public void loadHostNBT(CompoundTag t, HolderLookup.Provider r) {
        if (t.contains("graph")) {
            int tagGen = t.contains("hostGraphGen") ? t.getInt("hostGraphGen") : -1;
            // 图内容与已装入的完全一致（同代际）：跳过替换、不动本地图代际——目标/设置/
            // 运行段照常刷新。雷达目标同步的每 tick 推送正是这种包，重载它就是
            // 「图每次重建 / 拖拽卡顿」的直接来源。无指纹的旧数据（tagGen=-1）一律重载。
            // The tag's graph matches what is already loaded (same generation): skip the
            // replacement, leave the local graph and its generation alone — the
            // targets/settings/runtime sections still refresh. The radar's per-tick
            // target push is exactly this kind of packet; loading it is the direct source
            // of "the graph rebuilds every time" / drag stutter. Tags without the
            // fingerprint (tagGen=-1) always reload.
            if (tagGen != -1 && tagGen == lastLoadedGraphGen && graph != null && graphReady) {
                // skip graph replacement / 跳过图替换
            } else if (!owner.isGraphReplaceBlocked(pendingLocalOps)) {
                // 阶段 1：判定收敛到 GraphHostOwner —— 原先此处与 SyncedGraphBlockEntity
                // 各写一份，两线判定漂移会让客户端行为不一致。
                // Phase 1: the check converged into GraphHostOwner — this site and
                // SyncedGraphBlockEntity used to each keep a copy, and drift between them
                // would make the two lines behave differently on the client.
                graph = NodeGraph.load(t.getCompound("graph"), r);
                // 装载时类型普查（P2-7 旧档兼容口径）：在图替换点记一次，准入门据此放行
                // 旧档已在场的类型。同代际跳过路径不重算——内容未变，普查仍然准确。
                // Load-time type census (the P2-7 legacy tolerance): recorded once at the
                // graph-replacement site; the admission gate admits legacy-present types
                // from it. The same-generation skip path keeps the previous census — the
                // content is unchanged, so it stays accurate.
                this.loadTimeNodeTypes = NodeAdmission.census(graph);
                rs.onLoad(graph);
                this.graphReady = true;
                this.pendingLocalOps = 0;
                this.lastLoadedGraphGen = tagGen;
                var client = lvl();
                if (client != null && client.isClientSide()) {
                    graph.bumpGeneration();
                } else {
                    // 服务端路径（NBT 加载 / /data merge）：新实例 gen 与上次重编译记录
                    // 可能同为 0，graphChanged() 将永远为假、求值器冻死在旧图上 ——
                    // bump + 置 lastGraphGeneration = -1 强制下一 tick 重编译。
                    // Server path (world load / data merge): a fresh instance can collide
                    // with the recorded generation at 0, freezing the old evaluator
                    // forever — bump + lastGraphGeneration = -1 forces next-tick recompile.
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

    /** 装载时图树类型普查（P2-7 旧档兼容口径，`EditSessionTarget.loadTimeNodeTypes` 的引擎侧
     *  事实源；服务端准入门据此放行旧档已携带的类型）。
     *  Load-time graph-tree type census (the P2-7 legacy tolerance; the engine-side fact
     *  source behind `EditSessionTarget.loadTimeNodeTypes` — the server admission gate
     *  admits legacy-carried types from it). */
    public java.util.Set<NodeType> loadTimeNodeTypes() { return loadTimeNodeTypes; }

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

    /**
     * @see GraphBlockEntity#applyCurrentState
     *
     * <p>入口约定：{@code OpExecutor.apply} 已翻转 {@code params[1]}，这里把镜像值采纳进
     * 运行时映射（含子图条目随写随建），随后立即差分广播。Entry convention: {@code OpExecutor.apply}
     * has already flipped {@code params[1]}; this adopts the mirror value into the runtime
     * map (creating the sub-graph entry on demand), then broadcasts the diff at once. */
    public void applyCurrentState(int ownerNodeId, int nodeId) {
        NodeGraph scope = graph;
        if (ownerNodeId >= 0) {
            var encap = graph.findNode(ownerNodeId);
            scope = encap != null ? encap.subGraph : null;
        }
        var n = scope != null ? scope.findNode(nodeId) : null;
        if (n == null || n.params.length < 2) return;
        runtimeState.putFlipflop(ownerNodeId, nodeId, n.params[1] > 0.5f);
        broadcastFlipflopDiff();
    }
}
