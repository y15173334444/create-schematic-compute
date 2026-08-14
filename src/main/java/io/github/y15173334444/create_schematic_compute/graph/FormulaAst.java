package io.github.y15173334444.create_schematic_compute.graph;

import java.util.List;

/**
 * FORMULA 脚本 AST(决策文档 docs/formula-budget-syntax-decisions.md §五):
 * 语句树 + RPN 叶子——表达式不建树,直接携带编译后的 RPN(单一栈机)。
 * @output 是解析期声明,不是 AST 节点(hoist 语义,防止运行时动态引脚)。
 * FORMULA script AST (decisions doc §五): statement tree + RPN leaves — expressions
 * carry compiled RPN directly (single stack machine). @output is a parse-time
 * declaration, never an AST node (hoist semantics, prevents runtime-dynamic pins).
 */
public final class FormulaAst {

    /** 语句 / statement */
    public sealed interface Stmt permits AssignStmt, ExprStmt, RepeatStmt, WhileStmt,
            IfStmt, BreakStmt, ContinueStmt {}

    /** 赋值:var = expr(RPN)。 / Assignment: var = expr (RPN). */
    public record AssignStmt(String var, List<Object> rpn) implements Stmt {}

    /** 独立表达式语句(末行表达式 = 默认输出)。 / Standalone expression (last line = default output). */
    public record ExprStmt(List<Object> rpn) implements Stmt {}

    /** 定长循环 repeat N { ... }。 / Fixed-count loop. */
    public record RepeatStmt(long count, List<Stmt> body) implements Stmt {}

    /** 条件循环 while (cond) { ... }。 / Conditional loop. */
    public record WhileStmt(List<Object> condRpn, List<Stmt> body) implements Stmt {}

    /** 条件分支 if (cond) { ... } else { ... }(else 可为空)。 / Conditional branch (elseBody may be empty). */
    public record IfStmt(List<Object> condRpn, List<Stmt> body, List<Stmt> elseBody) implements Stmt {}

    /** 退出最内层循环。 / Exit the innermost loop. */
    public record BreakStmt() implements Stmt {}

    /** 跳到最内层循环下一轮。 / Skip to the next iteration of the innermost loop. */
    public record ContinueStmt() implements Stmt {}

    private FormulaAst() {}
}
