package io.github.y15173334444.create_schematic_compute.graph;

/**
 * 自定义节点求值的<b>独立预算池</b>（公开 API 波 1，探索稿 D2a 拍板）——与
 * {@link FormulaCompute} 同构但账本独立：可恢复重节点挂起计入本池，slice =
 * 预算 / max(1, 上 tick 挂起数)，与 FORMULA 池互不挤压（验收口径②）。
 *
 * <p>预算语义（镜像 FormulaCompute 权威决策）：</p>
 * <ul>
 *   <li>节点入口永远准入（保底），不查 deadline；</li>
 *   <li>{@code sliceExpired()}（{@code api.CustomNodeContext} 的检查点）= 调用内墙钟
 *       超过本节点 slice —— 由 {@code CustomNodeEval} 在构建上下文时起算；</li>
 *   <li>挂起（suspended）计入 heavyYieldCount；</li>
 *   <li>普通节点的墙钟超份额不入本池——由处置梯子（CustomNodeEval 内连续超份额记账）
     *   处置。</li>
 * </ul>
 * The <b>independent budget pool</b> for custom-node evaluation (public-API wave 1,
 * ruling D2a) — isomorphic to but separately accounted from {@link FormulaCompute}:
 * resumable heavy nodes' suspensions feed this pool, slice = budget / max(1, last tick's
 * suspensions), and the two pools never squeeze each other (acceptance 2). Budget
 * semantics mirror FormulaCompute's authoritative decisions: node entry is always admitted
 * (no deadline check), the sliceExpired() checkpoint = in-call wall clock past this node's
 * slice (started by CustomNodeEval when building the context), suspensions count as heavy
 * yield, and ordinary nodes' wall-clock overstay is handled by the escalation ladder
 * (consecutive over-slice accounting inside CustomNodeEval), not by this pool.
 */
public final class CustomNodeCompute {

    /** 本 tick 预算硬 deadline（纳秒，墙钟；观测/埋点用，节点准入不查它）。
     *  Hard budget deadline for this tick (ns, wall clock; observability only — node
     *  admission never checks it). */
    private static long budgetDeadlineNs = 0;
    /** 本 tick 挂起计数。/ suspensions within this tick. */
    private static int heavyYieldCount = 0;
    /** 上一 tick 挂起计数 → slice = 预算 / max(1, prev)。/ last tick's suspensions → slice. */
    private static int heavyYieldCountPrev = 0;

    private CustomNodeCompute() {}

    /** 每服务端 tick 开始调用一次（ServerTickEvent.Pre，与 FormulaCompute.beginTick 同点）。
     *  Called once per server tick (ServerTickEvent.Pre, beside FormulaCompute.beginTick). */
    public static void beginTick() {
        budgetDeadlineNs = System.nanoTime() + budgetNs();
        heavyYieldCountPrev = heavyYieldCount;
        heavyYieldCount = 0;
    }

    /** 服务端停止时清理（防跨世界污染）。/ cleanup on server stop (no cross-world pollution). */
    public static void clearAll() {
        budgetDeadlineNs = 0;
        heavyYieldCount = 0;
        heavyYieldCountPrev = 0;
    }

    /**
     * 预算总量（纳秒）。毫秒数由根包注入（{@link #setBudgetMsSource} 接
     * {@code Config.customNodeBudgetMs}）；未注入（单测）回退 3.0ms —— 与 FORMULA 池
     * 默认对称，独立配置键互不影响。
     * Total budget (ns). The ms value arrives via {@link #setBudgetMsSource} (the root
     * wires Config.customNodeBudgetMs); unwired (unit tests) falls back to 3.0ms —
     * symmetric with the FORMULA pool's default, on its own config key.
     */
    private static java.util.function.DoubleSupplier budgetMsSource = () -> 3.0;

    /** 注入预算毫秒来源（根包在配置就绪时接线；读取保持实时）。/ inject the budget-ms source (root wires it; reads stay live). */
    public static void setBudgetMsSource(java.util.function.DoubleSupplier source) {
        budgetMsSource = source != null ? source : () -> 3.0;
    }

    public static long budgetNs() {
        return (long) (budgetMsSource.getAsDouble() * 1_000_000L);
    }

    /** 预算毫秒数（解冻检查用：降频冻结与该值绑定，调大即解冻——K6 同款）。
     *  The budget in ms (thaw checks bind a throttle to this value; raising it thaws — same as K6). */
    public static double budgetMs() {
        return budgetMsSource.getAsDouble();
    }

    /** 单节点 slice（纳秒）= 预算 / max(1, 上一 tick 挂起数)。/ per-node slice = budget / max(1, last tick's suspensions). */
    public static long sliceNs() {
        return budgetNs() / Math.max(1, heavyYieldCountPrev);
    }

    /** 可恢复节点挂起时上报（CustomNodeEval 调用）。/ reported when a resumable node suspends (from CustomNodeEval). */
    public static void reportYield() {
        heavyYieldCount++;
    }

    /** 上一 tick 挂起数（观测/调试面板用）。/ last tick's suspension count (observability). */
    public static int heavyYieldCountPrev() {
        return heavyYieldCountPrev;
    }

    /** 测试缝：直接设定上一 tick 挂起数（slice 数学与 sliceExpired 的确定性验证用；
     *  生产路径只有 beginTick 轮转写它）。/ test seam: set last tick's suspension count
     *  directly (deterministic slice math; production writes it only via beginTick). */
    static void forceHeavyYieldCountPrevForTests(int count) {
        heavyYieldCountPrev = count;
    }
}
