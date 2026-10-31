package io.github.y15173334444.create_schematic_compute;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置(ModConfigSpec,SERVER 类型,自动同步客户端)。
 * Mod config (ModConfigSpec, SERVER type, auto-synced to clients).
 *
 * 设计约束(见 docs/formula-budget-syntax-decisions.md §三):
 * 只放「时序/资源」旋钮——不同值不影响计算结果,只影响计算节奏;
 * 语义常数(== 容差、MAX_ITER、角度约定)保持硬编码,避免跨服蓝图结果漂移。
 */
public final class Config {

    /** 配置规格 / config spec */
    public static final ModConfigSpec SPEC;

    /** 每服务端 tick 的 FORMULA 预算池总量(毫秒)。 / Total FORMULA budget pool per server tick (ms). */
    public static final ModConfigSpec.DoubleValue FORMULA_BUDGET_MS;
    public static final ModConfigSpec.DoubleValue CUSTOM_NODE_BUDGET_MS;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("formula");
        FORMULA_BUDGET_MS = builder
            .comment("每服务端 tick 的 FORMULA 预算池总量(毫秒),仅影响计算节奏、不影响计算结果。",
                     "Total FORMULA budget pool per server tick (ms); affects timing only, never results.")
            .defineInRange("formulaBudgetMs", 3.0, 0.5, 20.0);
        builder.pop();
        builder.push("custom");
        CUSTOM_NODE_BUDGET_MS = builder
            .comment("每服务端 tick 的自定义节点(第三方)独立预算池总量(毫秒),与 FORMULA 池互不挤压;仅影响计算节奏、不影响计算结果。",
                     "Total independent budget pool per server tick (ms) for third-party custom nodes, separate from the FORMULA pool; affects timing only, never results.")
            .defineInRange("customNodeBudgetMs", 3.0, 0.5, 20.0);
        builder.pop();
        SPEC = builder.build();
    }

    private Config() {}

    /**
     * formulaBudgetMs 的安全读取：配置未装载窗口（注册后、SERVER 配置装载前）
     * {@code get()} 抛 IllegalStateException，回退默认 3.0ms（与根包预算接线同口径）。
     * Safe formulaBudgetMs read: in the pre-load window (registered but the SERVER config
     * not yet loaded) {@code get()} throws IllegalStateException — fall back to the 3.0ms
     * default (same shape as the root's budget wiring).
     */
    public static double formulaBudgetMsSafe() {
        try {
            return FORMULA_BUDGET_MS.get();
        } catch (IllegalStateException e) {
            return 3.0;
        }
    }

    /**
     * customNodeBudgetMs 的安全读取（与 formulaBudgetMsSafe 同口径：配置未装载窗口回退
     * 默认 3.0ms）。/ Safe customNodeBudgetMs read (same shape as formulaBudgetMsSafe:
     * the pre-load window falls back to the 3.0ms default).
     */
    public static double customNodeBudgetMsSafe() {
        try {
            return CUSTOM_NODE_BUDGET_MS.get();
        } catch (IllegalStateException e) {
            return 3.0;
        }
    }
}
