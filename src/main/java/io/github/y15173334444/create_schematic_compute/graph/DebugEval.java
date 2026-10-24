package io.github.y15173334444.create_schematic_compute.graph;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调试域处理器（决策文档 §四）：DEBUG —— DEBUG_SIGNAL_GEN / DEBUG_PROBE + COMMENT 空体。
 * case 体从 {@link GraphEvaluator} 的 eval() switch 逐字搬迁（§三-2）；本类是静态函数包，
 * 不建继承体系（§三-3）。
 * <p>Debug-domain handler (decision doc §四): DEBUG — DEBUG_SIGNAL_GEN / DEBUG_PROBE
 * plus the empty COMMENT body. Case bodies are relocated verbatim from
 * {@link GraphEvaluator}'s eval() switch (§三-2); this class is a static function pack,
 * no inheritance hierarchy (§三-3).</p>
 */
public final class DebugEval {

    private DebugEval() {}

    private static final Logger LOGGER = LoggerFactory.getLogger(DebugEval.class);

    /** 求值调试域节点（case 体逐字搬迁，见类注释）。结尾 {@code ctx.o = o} 回写输出数组。
     *  Evaluate a debug-domain node (case bodies relocated verbatim, see the class doc).
     *  Ends with the {@code ctx.o = o} write-back.
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
        Map<Integer, Float> debugTime = ctx.debugTime;
        switch (node.type) {
            case DEBUG_SIGNAL_GEN -> {
                int setMode = node.params.length > 0 ? (int) node.params[0] : 0;
                int outMode = node.params.length > 1 ? (int) node.params[1] : 0;
                float inputX = node.params.length > 4 ? node.params[4] : 0.5f;
                // speed/amp 仅手动模式生效；公式模式用默认值
                // speed/amp only effective in manual mode; formula mode uses defaults
                float speed = (setMode == DebugSignals.SET_MANUAL && node.params.length > 2) ? node.params[2] : (1f / 20f);
                float amp = (setMode == DebugSignals.SET_MANUAL && node.params.length > 3) ? node.params[3] : 1f;
                float x;
                if (outMode == DebugSignals.OUT_FREQ) {
                    // 频率发生：x 自动 0→1 循环推进 / frequency generate: x auto-advances 0→1
                    x = debugTime.getOrDefault(node.id, 0f);
                    x += speed;
                    x -= (float) Math.floor(x);
                    debugTime.put(node.id, x);
                } else {
                    // 指定模式：x 来自节点参数 / input-driven: x from node param
                    x = inputX;
                }
                // 编译缓存（避免每 tick 重新编译公式） / compile cache
                // F13: 编译器对残缺公式是 lenient 的（如 "x +" 能编出坏 RPN），真正的炸点在求值期
                // （栈机 pop 空栈）——以往每 tick 抛一次、evalFormula 每 tick 告警一次。改为：编译时
                // 试算一次探测坏公式，失败态按节点缓存（失效键=脚本文本），只告警一次；后续 tick
                // 复用失败态短路（不再重编译/重试算），输出保持归零口径；脚本被编辑（缓存失效 op
                // 置空 debugFormulaRpn）后自动重新编译，修好即恢复。
                // F13: the compiler is lenient toward malformed formulas ("x +" still yields a broken
                // RPN); the real crash point is evaluation time (stack-machine pop on an empty stack) —
                // which used to throw per tick and warn per tick from evalFormula. Now: a trial
                // evaluation at compile time probes for broken formulas; the failure state is cached
                // per node (invalidation key = script text), warns exactly once, and later ticks
                // reuse the failure state as a short circuit (no recompile/re-probe) with the output
                // pinned to 0. Editing the script (the invalidation op clears debugFormulaRpn)
                // recompiles automatically, so a fixed formula recovers on its own.
                boolean formulaDead = false;
                if (setMode == DebugSignals.SET_FORMULA) {
                    if (node.debugFormulaCompileWarned && node.formula.equals(node.debugFormulaWarnedScript)) {
                        // 已知坏脚本（旗标亮 + 脚本文本未变）：直接复用失败态 / known-bad script (flag up,
                        // text unchanged): reuse the failure state directly
                        formulaDead = true;
                    } else if (node.debugFormulaRpn == null && node.formula != null && !node.formula.isBlank()) {
                        node.debugFormulaRpn = DebugSignals.compileFormula(node.formula);
                        if (node.debugFormulaRpn != null) {
                            try {
                                // 试算用任意 x 均可——栈机的抛错只取决于 RPN 结构，与数值无关
                                // Any x works for the probe — the stack machine throws on RPN
                                // structure alone, never on values
                                FormulaParser.evaluate(node.debugFormulaRpn, Map.of("x", 0.0));
                            } catch (Exception e) {
                                node.debugFormulaRpn = null; // 坏 RPN 不入缓存 / broken RPN is not cached
                            }
                        }
                        if (node.debugFormulaRpn == null) {
                            node.debugFormulaCompileWarned = true;
                            node.debugFormulaWarnedScript = node.formula;
                            LOGGER.warn("DEBUG_SIGNAL_GEN node {}: formula '{}' failed to compile/evaluate — output pinned to 0 (warn once per script)",
                                node.id, node.formula);
                            formulaDead = true;
                        } else {
                            // 编译恢复：复位告警旗标（脚本可能曾被编辑过）/ recovered: reset the warn flag
                            node.debugFormulaCompileWarned = false;
                            node.debugFormulaWarnedScript = null;
                        }
                    }
                    // 失败态以 null 公式短路 computeCurve：evalFormula 的 null 分支直接归 0，
                    // 不再触发其内部每 tick 的 WARN 路径。
                    // The failure state short-circuits computeCurve via a null formula: evalFormula's
                    // null branch pins 0 without ever reaching its per-tick WARN path.
                    o[0] = amp * DebugSignals.computeCurve(setMode, x, node.curveX, node.curveY,
                        formulaDead ? null : node.formula, node.debugFormulaRpn);
                } else {
                    o[0] = amp * DebugSignals.computeCurve(setMode, x, node.curveX, node.curveY, node.formula, node.debugFormulaRpn);
                }
            }
            case DEBUG_PROBE -> {
                // pass-through：输出 = 输入，供客户端从 EvalSnapshot 读取
                o[0] = graph.getInputValue(node.id, 0, outputs);
            }
            case COMMENT -> {}
        }
        ctx.o = o;
    }
}
