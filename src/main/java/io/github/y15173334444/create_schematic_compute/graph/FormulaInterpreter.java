package io.github.y15173334444.create_schematic_compute.graph;

import java.util.List;
import java.util.Map;

/**
 * AST 语句解释器(刀3)。控制流由语句级解释执行;表达式走统一 Value 栈机(evaluateValue)。
 * 刀5 在循环边界接入协作超时(slice yield)与 carrier;本刀单趟执行、无超时。
 * AST statement interpreter (knife 3). Control flow runs statement-level; expressions go
 * through the unified Value stack machine. Knife 5 adds the cooperative timeout (slice
 * yield) at loop boundaries + carrier; this knife runs single-pass with no timeout.
 */
public final class FormulaInterpreter {

    /** break 信号:最内层循环捕获并退出。 / Break signal: caught by the innermost loop, which exits. */
    static final class BreakSignal extends RuntimeException {}
    /** continue 信号:最内层循环捕获并跳下一轮。 / Continue signal: caught by the innermost loop, which skips to the next iteration. */
    static final class ContinueSignal extends RuntimeException {}

    private FormulaInterpreter() {}

    /** 执行语句列表;结束后 env 持有变量终值。 / Execute statements; env holds final variable values afterwards. */
    public static void exec(List<FormulaAst.Stmt> stmts, Map<String, Value> env) {
        try {
            for (var s : stmts) execStmt(s, env);
        } catch (BreakSignal b) {
            // 顶层 break lenient 忽略(校验期已提示)/ top-level break is leniently ignored
        }
    }

    private static void execStmt(FormulaAst.Stmt s, Map<String, Value> env) {
        switch (s) {
            case FormulaAst.AssignStmt a -> env.put(a.var(), FormulaParser.evaluateValue(a.rpn(), env));
            case FormulaAst.ExprStmt e -> FormulaParser.evaluateValue(e.rpn(), env); // 结果丢弃 / discard
            case FormulaAst.RepeatStmt r -> {
                for (long k = 0; k < r.count(); k++) {
                    try {
                        for (var b : r.body()) execStmt(b, env);
                    } catch (ContinueSignal c) {
                        // 下一轮 / next iteration
                    } catch (BreakSignal b) {
                        break;
                    }
                }
            }
            case FormulaAst.WhileStmt w -> {
                while (truthy(FormulaParser.evaluateValue(w.condRpn(), env))) {
                    try {
                        for (var b : w.body()) execStmt(b, env);
                    } catch (ContinueSignal c) {
                        // 下一轮 / next iteration
                    } catch (BreakSignal b) {
                        break;
                    }
                }
            }
            case FormulaAst.IfStmt i -> {
                boolean take = truthy(FormulaParser.evaluateValue(i.condRpn(), env));
                for (var b : take ? i.body() : i.elseBody()) execStmt(b, env);
            }
            case FormulaAst.BreakStmt b -> throw new BreakSignal();
            case FormulaAst.ContinueStmt c -> throw new ContinueSignal();
        }
    }

    /** 条件真值:标量 != 0;向量条件 lenient 假(校验期报错)。 / Condition truthiness: scalar != 0; vectors are leniently false. */
    private static boolean truthy(Value v) {
        return v instanceof Value.Scalar s && s.v() != 0.0;
    }
}
