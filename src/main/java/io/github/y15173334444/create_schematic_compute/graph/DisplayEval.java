package io.github.y15173334444.create_schematic_compute.graph;

import java.util.Map;

/**
 * 显示域处理器（决策文档 §四）：DISPLAY —— HUD_PITCH_LADDER 透传 + TEXT/DATA/IMAGE/
 * IMAGE_SEQUENCE 空体（无浮点输出，数据由渲染器从 GraphNode 字段读取）。case 体从
 * {@link GraphEvaluator} 的 eval() switch 逐字搬迁（§三-2）；本类是静态函数包，不建继承
 * 体系（§三-3）。
 * <p>Display-domain handler (decision doc §四): DISPLAY — the HUD_PITCH_LADDER
 * passthrough plus the empty TEXT/DATA/IMAGE/IMAGE_SEQUENCE bodies (no float output;
 * the renderer reads GraphNode fields). Case bodies are relocated verbatim from
 * {@link GraphEvaluator}'s eval() switch (§三-2); this class is a static function pack,
 * no inheritance hierarchy (§三-3).</p>
 */
public final class DisplayEval {

    private DisplayEval() {}

    /** 求值显示域节点（case 体逐字搬迁，见类注释）。结尾 {@code ctx.o = o} 回写输出数组。
     *  Evaluate a display-domain node (case bodies relocated verbatim, see the class
     *  doc). Ends with the {@code ctx.o = o} write-back.
     *
     * @param ctx 求值上下文（每调用/每节点槽已由调用方填好）/ evaluation context
     */
    static void eval(EvalContext ctx) {
        // 方法内局部别名（接收者改写）：case 体逐字保留 / local aliases (receiver rewrite):
        // the case bodies stay verbatim
        GraphNode node = ctx.node;
        float[] o = ctx.o;
        NodeGraph graph = ctx.graph;
        Map<Integer, float[]> outputs = ctx.outputs;
        switch (node.type) {
            case HUD_PITCH_LADDER -> {
                // 透传姿态输入（pitch/roll），符号形态由渲染解释 / passthrough attitude
                // inputs (pitch/roll); the symbol shape is interpreted by the renderer
                o[0] = graph.getInputValue(node.id, 0, outputs);
                o[1] = graph.getInputValue(node.id, 1, outputs);
            }
            // 显示节点 — 无浮点输出；数据由渲染器从 GraphNode 字段读取
            // Display nodes — no float output; data read from GraphNode fields by renderer
            case TEXT, DATA, IMAGE, IMAGE_SEQUENCE -> {}
        }
        ctx.o = o;
    }
}
