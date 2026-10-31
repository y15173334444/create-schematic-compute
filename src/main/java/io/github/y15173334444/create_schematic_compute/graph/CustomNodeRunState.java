package io.github.y15173334444.create_schematic_compute.graph;

/**
 * 自定义节点的求值运行态（公开 API 波 1 批 B）——全部 <b>transient</b>（不持久化：
 * 处置梯子的记账是运行期安全态，存档恢复后从干净态重新观察）。挂在
 * {@link GraphNode#customRun}（懒创建）。
 * A custom node's evaluation run-state (public-API wave 1 batch B) — all <b>transient</b>
 * (never persisted: the escalation ladder's accounting is runtime safety state that
 * re-observes from a clean slate after a save restore). Hangs off
 * {@link GraphNode#customRun}, created lazily.
 */
final class CustomNodeRunState {

    /** 连续超自身份额的 tick 数（公平规则同 K6：仅本节点超份额计，干净 tick 归零）。
     *  Consecutive over-own-slice ticks (K6's fair rule: only the node's own overstay
     *  counts; a clean tick resets). */
    int overSliceStreak;

    /** 降频剩余 tick 数（&gt;0 = 本 tick 跳过求值、输出保持）。/ remaining throttle ticks
     *  (&gt;0 = skip evaluation this tick, outputs hold). */
    int throttleTicksLeft;

    /** 降频冻结绑定的预算值（K6 同款解冻：预算调大下一 tick 自动解冻）。
     *  The budget value the throttle froze at (K6-style thaw: raising the budget thaws
     *  the next tick). */
    double frozenBudgetMs;

    /** 已完成的降频周期数（≥ {@code CustomNodeEval.FUSE_CYCLES} → 熔断）。
     *  Completed throttle cycles (≥ CustomNodeEval.FUSE_CYCLES → fuse). */
    int throttleCycles;

    /** 熔断：不再调用提供方，输出保持 lastGood；图重编译（节点重建）或提供方重注册解除。
     *  Fused: the provider is never called, outputs hold lastGood; lifted by a graph
     *  recompile (node rebuild) or a provider re-register. */
    boolean fused;

    /** 上次告警的 game tick（告警节流去重用）。/ the game tick of the last warning (throttled logging). */
    long lastWarnTick;
}
