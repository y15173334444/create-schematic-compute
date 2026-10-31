package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.api.CustomNodeBehavior;
import io.github.y15173334444.create_schematic_compute.api.CustomNodeContext;
import io.github.y15173334444.create_schematic_compute.api.CustomNodeResult;
import io.github.y15173334444.create_schematic_compute.api.CustomNodeType;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 自定义节点（{@link NodeType#CUSTOM}）的分类求值处理器（公开 API 波 1 批 B）——
 * GraphEvaluator 按 NodeCategory 分派的 CUSTOM case 落点。职责：
 * <ol>
 *   <li><b>值级交付</b>：输入引脚/参数/状态 blob 打包成 {@code api.CustomNodeContext}
 *       （零图域类型），调用注册表里的 {@code api.CustomNodeBehavior}；</li>
 *   <li><b>可恢复轮转</b>（D2a）：挂起结果计入 {@link CustomNodeCompute} 独立池、
 *       检查点落 RuntimeState.customStates、输出保持 lastGood；</li>
 *   <li><b>fail-closed</b>：提供方抛异常/返回 null/降级缺席 —— 输出保持、不计完成，
 *       绝不打断求值 tick；</li>
 *   <li><b>墙钟埋点 + 处置梯子</b>（普通节点 µs 义务）：连续超自身份额 → 降频
 *       （20 tick 跳过求值，预算调大即解冻——K6 同款）→ 熔断（不再调用，图重编译解除）；
 *       日志带 modid+节点 id。</li>
 * </ol>
 * The CUSTOM category handler (public-API wave 1 batch B) — the landing point of
 * GraphEvaluator's NodeCategory dispatch for {@link NodeType#CUSTOM}. It packs inputs,
 * params and the state blob into the value-level {@code api.CustomNodeContext}, calls the
 * registered {@code api.CustomNodeBehavior}, rotates resumable nodes via the independent
 * {@link CustomNodeCompute} pool with checkpoints in RuntimeState.customStates, fails
 * closed on provider exceptions/nulls/degraded shapes (outputs hold, the eval tick never
 * breaks), and runs the wall-clock escalation ladder for ordinary nodes' µs obligation
 * (consecutive over-slice → throttle → fuse; logs carry modid + node id).
 */
public final class CustomNodeEval {

    /** 独立 logger（headless 安全，E1 纪律同款）。/ standalone logger (headless-safe, same E1 discipline). */
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomNodeEval.class);

    /** 连续超份额多少 tick 进入降频（K6 冻结同值）。/ consecutive over-slice ticks that trigger a throttle (same value as K6's freeze). */
    static final int OVER_SLICE_FREEZE_TICKS = 20;
    /** 多少个降频周期后熔断。/ completed throttle cycles before fusing. */
    static final int FUSE_CYCLES = 5;
    /** 同节点告警节流间隔（game tick）。/ per-node warning throttle interval (game ticks). */
    static final long WARN_INTERVAL_TICKS = 200;
    /** 状态 blob 上限（SNBT 文本长度，与 op stringValue 的 32KB 线缆包封同值口径）——
     *  超限检查点拒收（审查批 (b)3：无上限的检查点会膨胀图 NBT/内存）。
     *  State-blob cap (SNBT text length, the same 32KB figure as the op stringValue wire
     *  envelope) — oversized checkpoints are refused (review (b)3: uncapped checkpoints
     *  would bloat the graph NBT/memory). */
    static final int MAX_STATE_SNBT_LENGTH = 32768;

    private CustomNodeEval() {}

    public static void eval(EvalContext ctx) {
        GraphNode node = ctx.node;
        CustomNodeType spec = CustomNodeRegistry.get(node.customId);
        CustomNodeBehavior behavior = CustomNodeRegistry.behavior(node.customId);
        // 降级空转（schema/行为缺席：提供方卸载或未注册）——输出保持，绝不抛。
        // Degraded idle (schema/behavior absent: provider unloaded or unregistered) —
        // outputs hold, never throws.
        if (spec == null || behavior == null) return;

        CustomNodeRunState run = node.customRun;
        if (node.customRun == null) node.customRun = run = new CustomNodeRunState();
        // 提供方世代检测：注册表已变（重注册/注销重注册）→ 运行态整体重置 ——
        // 熔断/降频随提供方更替一并解除（审查批 (c)6 的实现路径）。
        // Provider-generation check: the registry moved (re-register / unload+register) →
        // reset the run state wholesale — fuses and throttles lift with the provider swap
        // (the implementation path behind review (c)6).
        if (run.providerGeneration != CustomNodeRegistry.providerGeneration()) {
            run = new CustomNodeRunState();
            run.providerGeneration = CustomNodeRegistry.providerGeneration();
            node.customRun = run;
        }

        // 熔断：不再调用提供方，输出保持 lastGood（图重编译重建节点即解除）。
        // Fused: the provider is never called; outputs hold lastGood (a recompile rebuilds
        // the node and lifts it).
        if (run.fused) return;

        // 降频中：预算调大即解冻（K6 同款——冻结与冻结时预算值绑定），否则倒数跳过。
        // Throttled: a raised budget thaws immediately (K6-style — the freeze binds to the
        // budget value it froze at), otherwise count down and skip.
        if (run.throttleTicksLeft > 0) {
            if (CustomNodeCompute.budgetMs() > run.frozenBudgetMs) {
                run.throttleTicksLeft = 0;
                run.overSliceStreak = 0;
            } else {
                run.throttleTicksLeft--;
                return; // 输出保持 / outputs hold
            }
        }

        // ── 值级交付（api.CustomNodeContext）──
        int pinCount = node.inputs();
        float[] in = new float[pinCount];
        for (int i = 0; i < pinCount; i++)
            in[i] = ctx.graph.getInputValue(node.id, i, ctx.outputs);
        CompoundTag stored = ctx.runtimeState != null ? ctx.runtimeState.customStates.get(node.id) : null;
        long t0 = System.nanoTime();
        long slice = CustomNodeCompute.sliceNs();
        long sliceDeadline = t0 + slice;
        Ctx call = new Ctx(ctx, node, spec, in, stored, sliceDeadline);

        CustomNodeResult result;
        try {
            result = behavior.evaluate(call);
        } catch (Exception ex) {
            // 提供方 bug：fail-closed —— 本次空转（输出保持），节流告警，绝不打断求值 tick。
            // Provider bug: fail closed — idle this call (outputs hold), throttled warning,
            // never break the eval tick.
            warnOnce(ctx, run, node, "threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            result = null;
        }
        long elapsed = System.nanoTime() - t0;

        if (result != null && result.isSuspended()) {
            // 可恢复挂起（D2a）：检查点落状态表、计入独立池重节点份额、输出保持
            // （ctx.o 不写 —— eval 尾部 put 的就是上一 tick 的 lastGood）。
            // Resumable suspension (D2a): checkpoint into the state table, feed the
            // independent pool's heavy share, outputs hold (ctx.o untouched — the eval
            // tail's put carries lastGood from the previous tick).
            // 计数口径对齐 FORMULA（审查批 (c)5）：只有超自身份额的挂起才计入池 ——
            // 提前挂起（协作早退）不该压薄全池 slice（K6 公平口径）。
            // Yield accounting aligned with FORMULA (review (c)5): only an over-slice
            // suspension feeds the pool — an early cooperative suspend must not shrink
            // everyone's slice (K6's fair rule).
            if (elapsed > slice) CustomNodeCompute.reportYield();
            if (ctx.runtimeState != null && result.newState() != null) {
                CompoundTag checkpoint = result.newState();
                if (checkpoint.toString().length() <= MAX_STATE_SNBT_LENGTH)
                    ctx.runtimeState.customStates.put(node.id, checkpoint);
                else
                    // 超限检查点拒收（旧保持、新拒收）：既存检查点原样保留，仅本次
                    // 的新检查点不落表 —— 提供方须把检查点压进上限内；节流告警带
                    // modid+id。诚实拒绝，不伪造兜底。
                    // An oversized checkpoint is refused (the old one is kept, only the new
                    // one is dropped): the provider must fit its checkpoint under the cap;
                    // throttled warning with modid+id. Honest refusal, no fabricated fallback.
                    warnOnce(ctx, run, node, "checkpoint rejected: " + checkpoint.toString().length()
                        + " chars exceeds the " + MAX_STATE_SNBT_LENGTH + " cap");
            }
            ladder(ctx, run, node, elapsed, slice);
            return;
        }
        if (result != null) {
            // 完成：输出写入 ctx.o（长度对齐 schema——提供方多/少给按钳制收口）；
            // 状态 = done 清除 / doneWithState 替换 / null 保持。
            // Completion: outputs land in ctx.o (length aligned to the schema — provider
            // over/under-shoot clamps); state = done clears / doneWithState replaces /
            // null keeps.
            float[] outs = result.outputs();
            int want = spec.outputs();
            float[] o = ctx.o;
            if (o == null || o.length != want) {
                // 长度漂移（schema 变更 / 构造期 schema 未解析）→ 重建数组随写回（FORMULA 同款）。
                // Length drift (schema change / schema unresolved at construction) → rebuild
                // the array and write it back (same as FORMULA).
                o = new float[want];
                node.outputValues = o;
                ctx.o = o;
            }
            for (int i = 0; i < want; i++)
                o[i] = i < outs.length ? outs[i] : 0f;
            if (ctx.runtimeState != null) {
                if (result.shouldClearState()) ctx.runtimeState.customStates.remove(node.id);
                else if (result.newState() != null) {
                    if (result.newState().toString().length() <= MAX_STATE_SNBT_LENGTH)
                        ctx.runtimeState.customStates.put(node.id, result.newState());
                    else
                        warnOnce(ctx, run, node, "state rejected: " + result.newState().toString().length()
                            + " chars exceeds the " + MAX_STATE_SNBT_LENGTH + " cap");
                }
            }
            ladder(ctx, run, node, elapsed, slice);
            return;
        }
        // null 结果 = 本次空转：输出保持，仍计墙钟（超份额照常进梯子）。
        // null result = idle: outputs hold, the wall clock still feeds the ladder.
        ladder(ctx, run, node, elapsed, slice);
    }

    /**
     * 处置梯子记账（公平规则同 K6：仅本节点超自身份额计连续超时）。
     *
     * <p><b>破约快车道（审查批 N1 修正后口径）</b>：仅当 {@code prev ≥ 2}
     * （slice &lt; 整池，多重节点分摊中）才存在「吃满整池 = 数倍于自身份额」的破约
     * 形态，此时 ≥ budgetNs 的单次调用跳过宽限立即熔断。<b>prev ≤ 1 域没有快车道</b>：
     * slice 恰等于整池预算，守约节点在过线后退出必然落在 ≈ 整池 —— 此时吃满整池
     * 恰恰是守约（探针实证：预算 1ms 严格按契约轮询退出的合法挂起实测 995µs），
     * 该域退回渐进梯子。</p>
     *
     * <p><b>记账边界带真实幅度余量（审查批 N2）</b>：{@code elapsed ≤ slice × 1.5}
     * 视为检测粒度内的守约（过线退出的 ε 与检查点粒度都不该被记连续超时）——
     * 梯子惩罚的是不诚实，不是检测精度。</p>
     *
     * <p><b>首调豁免</b>：首次测量的调用落在类加载/JIT 预热窗内、墙钟虚高，不记账
     * （N1 附带风险）。</p>
     *
     * <p>Ladder accounting (K6's fair rule: only the node's own over-slice counts
     * consecutively). <b>Fence-breach fast lane (post-N1)</b>: only when {@code prev ≥ 2}
     * (slice &lt; the pool — multiple heavy nodes sharing) does consuming the whole pool
     * mean several times one's own share; there a single call ≥ budgetNs fuses
     * immediately. <b>No fast lane in the prev ≤ 1 domain</b>: the slice equals the whole
     * pool, so a compliant node exiting past the line lands at ≈ the pool — consuming it
     * IS compliance there (probe-verified: a strictly contract-polling legal suspension
     * measured 995µs against a 1ms budget), and the gradual ladder rules that domain.
     * <b>Accounting margin (N2)</b>: {@code elapsed ≤ slice × 1.5} reads as compliant
     * within detection granularity. <b>First-call exemption</b>: the first measured call
     * sits in the classload/JIT warm-up window and is never accounted.</p>
     */
    private static void ladder(EvalContext ctx, CustomNodeRunState run, GraphNode node, long elapsed, long slice) {
        if (run.fused) return; // 已熔断不再重复记账 / already fused — no re-accounting
        if (run.measuredCalls++ == 0) return; // 首调 = 预热窗，豁免 / first call = warm-up, exempt
        long prev = CustomNodeCompute.heavyYieldCountPrev();
        long budget = CustomNodeCompute.budgetNs();
        if (prev >= 2 && budget > 0 && elapsed >= budget) {
            run.fused = true;
            warnOnce(ctx, run, node, "FUSED on a fence breach: one call consumed the whole pool budget ("
                + elapsed / 1_000_000L + "ms vs a " + budget / 1_000_000L + "ms pool at prev=" + prev
                + "); outputs hold lastGood, a graph recompile or a provider re-register lifts this");
            return;
        }
        if (elapsed <= slice + slice / 2) {
            run.overSliceStreak = 0;
            return;
        }
        run.overSliceStreak++;
        if (run.overSliceStreak == 1)
            warnOnce(ctx, run, node, "overran its slice (" + (elapsed / 1_000_000L) + "ms vs "
                + (slice / 1_000_000L) + "ms)");
        if (run.overSliceStreak >= OVER_SLICE_FREEZE_TICKS) {
            run.throttleTicksLeft = OVER_SLICE_FREEZE_TICKS;
            run.frozenBudgetMs = CustomNodeCompute.budgetMs();
            run.overSliceStreak = 0;
            run.throttleCycles++;
            warnOnce(ctx, run, node, "throttled: evaluating every " + OVER_SLICE_FREEZE_TICKS
                + " ticks after " + OVER_SLICE_FREEZE_TICKS + " consecutive over-slice ticks");
            if (run.throttleCycles >= FUSE_CYCLES) {
                run.fused = true;
                warnOnce(ctx, run, node, "FUSED: no longer evaluated (outputs hold lastGood); "
                    + "a graph recompile or a provider re-register lifts this");
            }
        }
    }

    /** 节流告警（同节点 {@link #WARN_INTERVAL_TICKS} 去重；日志带 modid+节点 id——验收口径②）。
     *  Throttled warning (deduped per node by WARN_INTERVAL_TICKS; logs carry modid + node
     *  id — acceptance 2). */
    private static void warnOnce(EvalContext ctx, CustomNodeRunState run, GraphNode node, String what) {
        long now = ctx.audioTickStamp;
        if (now - run.lastWarnTick < WARN_INTERVAL_TICKS) return;
        run.lastWarnTick = now;
        LOGGER.warn("[CustomNode] {} (host {}): {}", node.customId,
            ctx.customHostPos != null ? ctx.customHostPos.toShortString() : "?", what);
    }

    /**
     * {@code api.CustomNodeContext} 的实现侧：一次调用一个实例（轻量值载体，字段即快照）。
     * The impl side of {@code api.CustomNodeContext}: one instance per call (a lightweight
     * value carrier — the fields are the snapshots).
     */
    private static final class Ctx implements CustomNodeContext {
        private final EvalContext ctx;
        private final GraphNode node;
        private final CustomNodeType spec;
        private final float[] in;
        private final CompoundTag state;
        private final long sliceDeadline;

        Ctx(EvalContext ctx, GraphNode node, CustomNodeType spec, float[] in, CompoundTag state, long sliceDeadline) {
            this.ctx = ctx;
            this.node = node;
            this.spec = spec;
            this.in = in;
            this.state = state;
            this.sliceDeadline = sliceDeadline;
        }

        @Override public float[] inputs() { return in.clone(); }

        @Override public float numberParam(String key) {
            int i = paramIndex(key);
            return i >= 0 && i < node.params.length ? node.params[i] : 0f;
        }

        @Override public String textParam(String key) {
            // TEXT 控件的存储面（节点 NBT 文本表 + 编辑器控件 + op 路径）随批 C 编辑器面
            // 落地 —— 本批恒空串（诚实的缺席，不伪造存储位置）。
            // TEXT control storage (a per-node NBT text table + editor control + op path)
            // lands with the editor batch (C) — always "" here (an honest absence, never a
            // fabricated storage location).
            return "";
        }

        @Override public boolean boolParam(String key) {
            return numberParam(key) > 0.5f;
        }

        @Override public net.minecraft.world.item.ItemStack itemParam(String key) {
            // ITEM 控件的存储面随编辑器批（批 C）接线 —— 本批恒 null（headless 安全）。
            // ITEM control storage lands with the editor batch (C) — always null here (headless-safe).
            return null;
        }

        @Override public CompoundTag state() { return state == null ? new CompoundTag() : state.copy(); }

        @Override public boolean sliceExpired() {
            return System.nanoTime() >= sliceDeadline;
        }

        @Override public net.minecraft.server.level.ServerLevel level() { return ctx.customLevel; }

        @Override public net.minecraft.core.BlockPos hostPos() { return ctx.customHostPos; }

        @Override public long tick() { return ctx.audioTickStamp; }

        private int paramIndex(String key) {
            if (key == null) return -1;
            var params = spec.params();
            for (int i = 0; i < params.size(); i++)
                if (params.get(i).key().equals(key)) return i;
            return -1;
        }
    }
}
