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

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("formula");
        FORMULA_BUDGET_MS = builder
            .comment("每服务端 tick 的 FORMULA 预算池总量(毫秒),仅影响计算节奏、不影响计算结果。",
                     "Total FORMULA budget pool per server tick (ms); affects timing only, never results.")
            .defineInRange("formulaBudgetMs", 3.0, 0.5, 20.0);
        builder.pop();
        SPEC = builder.build();
    }

    private Config() {}
}
