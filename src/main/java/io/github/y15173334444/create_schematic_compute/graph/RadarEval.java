package io.github.y15173334444.create_schematic_compute.graph;

import java.util.List;
import java.util.Map;

/**
 * 雷达域处理器（决策文档 §四）：RADAR —— TARGET_OUT。case 体从 {@link GraphEvaluator}
 * 的 eval() switch 逐字搬迁（§三-2：方法体不改写，只改接收者/归属）；本类是静态函数包，
 * 不建继承体系（§三-3）。
 * <p>Radar-domain handler (decision doc §四): RADAR — TARGET_OUT. Case bodies are
 * relocated verbatim from {@link GraphEvaluator}'s eval() switch (§三-2: bodies are
 * never rewritten, only their owner changes); this class is a static function pack,
 * no inheritance hierarchy (§三-3).</p>
 */
public final class RadarEval {

    private RadarEval() {}

    /** 求值雷达域节点（case 体逐字搬迁，见类注释）。方法开头放局部别名块（接收者改写），
     *  让 case 体一个字都不用改；结尾 {@code ctx.o = o} 回写输出数组（通用契约）。
     *  Evaluate a radar-domain node (case bodies relocated verbatim, see the class
     *  doc). The method opens with local aliases (receiver rewrite) so the case bodies
     *  stay untouched; it ends with a {@code ctx.o = o} write-back of the output array
     *  (the general contract).
     *
     * @param ctx 求值上下文（每调用/每节点槽已由调用方填好）/ evaluation context
     *            (per-call / per-node slots already filled by the caller)
     */
    static void eval(EvalContext ctx) {
        // 方法内局部别名（接收者改写）：case 体逐字保留 / local aliases (receiver rewrite):
        // the case bodies stay verbatim
        GraphNode node = ctx.node;
        float[] o = ctx.o;
        List<GraphEvaluator.InputSource> inputs = ctx.inputs;
        Map<Integer, Float> pidState = ctx.pidState;
        float dt = ctx.dt;
        GraphEvaluator.SeatInputState seat = ctx.seat;
        net.minecraft.core.BlockPos radarPos = ctx.radarPos;
        TargetLookup targetLookup = ctx.targetLookup;
        switch (node.type) {
            case TARGET_OUT -> {
                var t = radarPos != null ? targetLookup.get(radarPos, node.id) : null;
                if (t != null) { o[0] = (float) t.x(); o[1] = (float) t.y(); o[2] = (float) t.z(); o[3] = t.entityId(); o[4] = t.distance(); }
            }
        }
        ctx.o = o;
    }
}
