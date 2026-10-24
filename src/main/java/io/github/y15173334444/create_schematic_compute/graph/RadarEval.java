package io.github.y15173334444.create_schematic_compute.graph;


/**
 * 雷达域处理器（决策文档 §四）：RADAR —— TARGET_OUT（目标查询经宿主注入的
 * radarPos / targetLookup）。case 体从 {@link GraphEvaluator} 的 eval() switch 逐字搬迁
 * （§三-2）；本类是静态函数包，不建继承体系（§三-3）。
 * <p>Radar-domain handler (decision doc §四): RADAR — TARGET_OUT (target lookup rides
 * the host-injected radarPos / targetLookup). Case bodies are relocated verbatim from
 * {@link GraphEvaluator}'s eval() switch (§三-2); this class is a static function pack,
 * no inheritance hierarchy (§三-3).</p>
 */
public final class RadarEval {

    private RadarEval() {}

    /** 求值雷达域节点（case 体逐字搬迁，见类注释）。结尾 {@code ctx.o = o} 回写输出数组。
     *  Evaluate a radar-domain node (case bodies relocated verbatim, see the class doc).
     *  Ends with the {@code ctx.o = o} write-back.
     *
     * @param ctx 求值上下文（每调用/每节点槽已由调用方填好）/ evaluation context
     */
    static void eval(EvalContext ctx) {
        // 方法内局部别名（接收者改写）：case 体逐字保留 / local aliases (receiver rewrite):
        // the case bodies stay verbatim
        GraphNode node = ctx.node;
        float[] o = ctx.o;
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
