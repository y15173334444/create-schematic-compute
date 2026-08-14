package io.github.y15173334444.create_schematic_compute.graph;

/**
 * 公式求值的统一值类型(决策文档 docs/formula-budget-syntax-decisions.md §五):
 * 标量/向量双形态贯穿 Env 与求值栈。单一 Value 栈机是旧 RPN 栈机的原地升级,
 * 旧标量脚本结果逐位不变。
 * Unified value type for formula evaluation (decisions doc §五): scalar/vector dual
 * shape through Env and the eval stack. The single Value stack machine is an in-place
 * upgrade of the legacy RPN machine — legacy scalar scripts stay bit-identical.
 */
public sealed interface Value {

    /** 标量(旧 double 世界)。 / Scalar (the legacy double world). */
    record Scalar(double v) implements Value {
        /** 共享零值,避免每 tick 重复分配。 / Shared zero, avoids per-tick reallocation. */
        public static final Scalar ZERO = new Scalar(0.0);
    }

    /** 三维向量(vec3)。刀 2 仅类型就位,向量运算在刀 3 接入。
     *  3D vector (vec3). Knife 2 only introduces the type; vector ops land in knife 3. */
    record Vec3Val(double x, double y, double z) implements Value {
        public static final Vec3Val ZERO = new Vec3Val(0.0, 0.0, 0.0);
    }
}
