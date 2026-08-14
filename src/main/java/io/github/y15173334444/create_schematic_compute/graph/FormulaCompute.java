package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.Config;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * FORMULA 求值预算门面(刀 1)—— 服务端每 tick 单点复位的预算状态 + 纯函数去重缓存。
 * FORMULA evaluation budget facade (knife 1) — per-tick budget state with a single reset
 * point + pure-function dedup cache.
 *
 * 预算语义(权威决策见 docs/formula-budget-syntax-decisions.md §三):
 * - 节点入口永远准入(保底),不查 deadline;循环边界的协作超时(slice yield)在刀 3/5 接入;
 * - slice = 预算 / max(1, N_heavy_prev),N_heavy = 上一 tick 实际 yield 的重节点数;
 * - deadline 仅作兜底(闭式巨脚本的紧急 shed)。
 * 刀 1 中本类对现有 RPN 脚本即刻生效的是:去重缓存;其余状态为刀 3/5 的挂载点。
 */
public final class FormulaCompute {

    /** 本 tick 预算硬 deadline(纳秒,墙钟)。兜底用途。 / Hard budget deadline for this tick (ns, wall clock). Backstop only. */
    private static long budgetDeadlineNs = 0;
    /** 本 tick 内发生 yield 的重节点计数(刀 3 解释器接入后非零)。 / Heavy-node yield count within this tick (non-zero once knife 3 lands). */
    private static int heavyYieldCount = 0;
    /** 上一 tick 的 yield 计数 → slice = 预算 / max(1, prev)。 / Last tick's yield count → slice = budget / max(1, prev). */
    private static int heavyYieldCountPrev = 0;
    /** tick 级去重缓存(纯函数安全),beginTick 清空。 / Per-tick dedup cache (safe for pure functions), cleared by beginTick. */
    private static final Map<DedupKey, float[]> DEDUP_CACHE = new HashMap<>();
    /** 本 tick 去重命中/未命中计数(观测/调试面板用)。 / This tick's dedup hit/miss counts (observability / debug panel). */
    private static int dedupHits = 0;
    private static int dedupMisses = 0;

    private FormulaCompute() {}

    /**
     * 每个服务端 tick 开始调用一次(ServerTickEvent.Pre):
     * 复位 deadline、轮转 yield 计数、清空去重表。
     * Called once at the start of each server tick (ServerTickEvent.Pre):
     * resets the deadline, rotates the yield counter, clears the dedup cache.
     */
    public static void beginTick() {
        budgetDeadlineNs = System.nanoTime() + budgetNs();
        heavyYieldCountPrev = heavyYieldCount;
        heavyYieldCount = 0;
        dedupHits = 0;
        dedupMisses = 0;
        DEDUP_CACHE.clear();
    }

    /** 服务端停止时清理(防跨世界污染,与 SignalBus 等既有清理同模式)。 / Cleanup on server stop (prevent cross-world pollution). */
    public static void clearAll() {
        budgetDeadlineNs = 0;
        heavyYieldCount = 0;
        heavyYieldCountPrev = 0;
        dedupHits = 0;
        dedupMisses = 0;
        DEDUP_CACHE.clear();
    }

    /**
     * 预算总量(纳秒),来自 Config.formulaBudgetMs。
     * Total budget (ns), from Config.formulaBudgetMs.
     * 配置未加载时(单元测试环境)回退默认 3.0ms;游戏运行时配置在服务器启动时已加载。
     * Falls back to the 3.0ms default when config isn't loaded (unit tests); in-game the config is loaded at server start.
     */
    public static long budgetNs() {
        double ms;
        try {
            ms = Config.FORMULA_BUDGET_MS.get();
        } catch (IllegalStateException e) {
            ms = 3.0; // ModConfigSpec 未加载时 get() 抛 IllegalStateException / ModConfigSpec throws before load
        }
        return (long) (ms * 1_000_000L);
    }

    /** 单节点 slice(纳秒)= 预算 / max(1, 上一 tick yield 的重节点数)。 / Per-node slice (ns) = budget / max(1, last tick's heavy yield count). */
    public static long sliceNs() {
        return budgetNs() / Math.max(1, heavyYieldCountPrev);
    }

    /** 解释器循环边界 yield 时上报(刀 3 接入)。 / Reported by the interpreter when yielding at a loop boundary (knife 3). */
    public static void reportYield() {
        heavyYieldCount++;
    }

    /** 上一 tick 实际 yield 的重节点数(调试面板/观测用)。 / Last tick's heavy yield count (for debug panels / observability). */
    public static int heavyYieldCountPrev() {
        return heavyYieldCountPrev;
    }

    /** 预算兜底 deadline 是否已过(刀 3 接入;刀 1 无循环、仅兜底)。 / Whether the backstop deadline has passed (knife 3; backstop only in knife 1). */
    public static boolean deadlineExhausted() {
        return System.nanoTime() >= budgetDeadlineNs;
    }

    // ─────────────────── 去重缓存 / Dedup cache ───────────────────

    /**
     * 查去重缓存:同脚本 + 同输入本 tick 内命中返回已算结果。
     * 返回的是缓存内部数组,只读——调用方必须自行复制,不得原地修改。
     * Lookup: same script + same inputs within this tick returns the cached result.
     * The returned array is the cache-internal one and read-only — callers must copy it, never mutate in place.
     */
    public static float[] lookupDedup(String formula, float[] inputs) {
        float[] hit = DEDUP_CACHE.get(new DedupKey(formula, inputs));
        if (hit != null) dedupHits++; else dedupMisses++;
        return hit;
    }

    /** 本 tick 去重命中次数(观测/调试面板用)。 / This tick's dedup hit count (observability / debug panel). */
    public static int dedupHits() { return dedupHits; }

    /** 本 tick 去重未命中次数(观测/调试面板用)。 / This tick's dedup miss count (observability / debug panel). */
    public static int dedupMisses() { return dedupMisses; }

    /**
     * 存入去重缓存。缓存内部再克隆一次,与调用方后续的数组修改完全隔离。
     * Store into the dedup cache. The cache clones internally — fully isolated from later caller-side mutation.
     */
    public static void storeDedup(String formula, float[] inputs, float[] result) {
        DEDUP_CACHE.put(new DedupKey(formula, inputs), result.clone());
    }

    /** 去重键:脚本源码 + 输入值内容(按值比较,非数组引用)。 / Dedup key: script source + input values (by value, not by array identity). */
    private record DedupKey(String formula, float[] inputs) {
        @Override
        public int hashCode() {
            return 31 * formula.hashCode() + Arrays.hashCode(inputs);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof DedupKey k
                && formula.equals(k.formula)
                && Arrays.equals(inputs, k.inputs);
        }
    }
}
