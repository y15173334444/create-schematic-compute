package io.github.y15173334444.create_schematic_compute.graph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.FormulaIssue;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.FormulaTooDeepException;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.ScriptParseResult;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.Severity;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.SwizzleToken;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.Token;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.TokType;

import static io.github.y15173334444.create_schematic_compute.graph.FormulaFunctions.VECTOR_FN_NAMES;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaFunctions.rpnYieldsVec3;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaLexer.isValidIdentifier;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaLexer.tokenize;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaParser.MAX_PARSE_DEPTH;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaParser.compileTokens;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaValidator.typeCheckRpn;

/**
 * 公式 AST 前端（刀3 语句解析，拓扑审查 §三 P5 按相拆分，自 FormulaParser 逐字迁出）：
 * 语句树解析（parseScriptAst 与行游标 LineCursor、块递归、@output 解析期 hoist）、保守
 * vec3 类型推断（inferVec3Vars）与变量收集族。递归深度上限 MAX_PARSE_DEPTH（P1-6）与受控
 * 异常 FormulaTooDeepException 的声明留在 FormulaParser（词汇锚），本类按原语义在递归链上
 * 抛出；入口由 FormulaParser.parseScript 的 AST 模式检测调用并兜住降级（输出恒 0 +
 * ERROR issue）。静态函数包，不建继承体系。
 * <p>Formula AST front-end (knife-3 statement parsing; the topology-review §三 P5 phase
 * split, relocated verbatim from FormulaParser): the statement-tree parse (parseScriptAst
 * with the LineCursor line cursor, block recursion and the parse-time @output hoist), the
 * conservative vec3 type inference (inferVec3Vars) and the variable collectors. The
 * recursion cap MAX_PARSE_DEPTH (P1-6) and the controlled FormulaTooDeepException stay
 * declared on FormulaParser (the vocabulary anchor); this class throws them on the
 * recursion chain per the original semantics, and the entry is invoked — with its
 * degradation (pinned-to-0 output + ERROR issue) caught — by FormulaParser.parseScript's
 * AST-mode detection. Static function pack, no inheritance hierarchy.</p>
 */
final class FormulaAstParser {

    private FormulaAstParser() {}

    // ==================== AST 模式解析(刀3) ====================

    /** 是否触发 AST 模式:控制流关键字、大括号、swizzle、vec3/向量函数(这些 RPN 路径无法完整表达)。
     *  Whether this token forces AST mode: control-flow keywords, braces, swizzle, vec3/vector fns. */
    static boolean isAstTrigger(Token t) {
        TokType tt = t.type();
        return tt == TokType.KEYWORD || tt == TokType.LBRACE || tt == TokType.RBRACE || tt == TokType.SWIZZLE
            || (tt == TokType.FUNCTION && (t.text().equals("vec3") || VECTOR_FN_NAMES.contains(t.text())));
    }

    /** 源行:trim 后的文本 + token 列表 + 对应的首个源行号(诊断定位)。
     *  Source line: trimmed text + tokens + first source line index (for diagnostics). */
    private record SrcLine(String raw, List<Token> tokens, int srcLineIdx) {}

    /** 逐行 token 游标:语句按行结束;块可跨行。 / Per-line token cursor: statements end at line end; blocks span lines. */
    private static final class LineCursor {
        final List<SrcLine> lines;
        int li = 0, ci = 0;

        LineCursor(List<SrcLine> lines) { this.lines = lines; }

        Token peek() {
            while (li < lines.size() && ci >= lines.get(li).tokens().size()) { li++; ci = 0; }
            return li < lines.size() ? lines.get(li).tokens().get(ci) : null;
        }

        Token next() { Token t = peek(); if (t != null) ci++; return t; }

        boolean atLineEnd() { return li >= lines.size() || ci >= lines.get(li).tokens().size(); }

        /** 当前行号(源行,诊断用)。 / Current line index (source line, for diagnostics). */
        int currentSrcLine() { return li < lines.size() ? lines.get(li).srcLineIdx() : 0; }

        /** 消费到当前行尾。 / Consume to the end of the current line. */
        List<Token> consumeToLineEnd() {
            var rest = new ArrayList<Token>();
            while (!atLineEnd()) rest.add(next());
            return rest;
        }
    }

    /** AST 解析中间产物:@output hoist 收集 + 末行表达式 + 各 RPN 的定位(类型检查用)。
     *  AST parse intermediate: hoisted @outputs + last standalone expr + RPN sites (for type checks). */
    private static final class AstParseOut {
        final List<FormulaAst.Stmt> stmts = new ArrayList<>();
        final List<String> outputLabels = new ArrayList<>();
        final List<List<Object>> outputRpns = new ArrayList<>();
        final List<RpnSite> rpnSites = new ArrayList<>();
        List<Object> lastStandaloneRpn = null;

        record RpnSite(List<Object> rpn, int line, int col) {}
    }

    /** AST 模式解析(刀3):语句树 + RPN 叶子;@output 解析期 hoist;保守类型推断 + 类型感知问题。
     *  AST-mode parse (knife 3): statement tree + RPN leaves; parse-time @output hoist;
     *  conservative type inference + type-aware issues. */
    static ScriptParseResult parseScriptAst(List<String> lines, List<Integer> lineSources, String originalFormula) {
        var srcLines = new ArrayList<SrcLine>();
        for (int i = 0; i < lines.size(); i++) srcLines.add(new SrcLine(lines.get(i), tokenize(lines.get(i)), lineSources.get(i)));
        var out = new AstParseOut();
        parseBlockStatements(new LineCursor(srcLines), out, true, 0);

        // 输出:无 @output → 顶层末行独立表达式为默认输出(与旧模式一致)
        if (out.outputLabels.isEmpty()) {
            if (out.lastStandaloneRpn != null) {
                out.outputLabels.add("");
                out.outputRpns.add(out.lastStandaloneRpn);
            } else {
                out.outputLabels.add("");
                out.outputRpns.add(List.of(0.0));
            }
        }

        // inputVars:所有 RPN 的变量引用 − 任何处被赋值的名字(决策 §五,与旧规则一致)
        Set<String> assignedNames = new LinkedHashSet<>();
        for (var s : out.stmts) collectAssigns(s, assignedNames);
        var inputVars = new LinkedHashSet<String>();
        for (var rpn : out.outputRpns) collectVars(inputVars, assignedNames, rpn);
        for (var s : out.stmts) collectStmtVars(s, inputVars, assignedNames);

        // 保守类型推断:任何处赋值为向量的变量即 vec3(刀4 引脚展开用;引脚数解析后固定)
        var vec3Vars = inferVec3Vars(out.stmts);

        // 类型感知检查(向量对向量、向量比较、向量函数形态 → 编辑器 ERROR)
        var issues = new ArrayList<FormulaIssue>();
        for (var site : out.rpnSites) {
            typeCheckRpn(site.rpn(), vec3Vars, issues, site.line(), site.col());
        }

        // ── vec3 输出展开(刀4,决策 §五):@output v(vec3)→ v.x/v.y/v.z 三个标量引脚,解析期静态展开。
        //    outputRpns 追加 SwizzleToken;引脚数解析后固定不变;outputPinIndex 由展开后的标签解析。
        // ── vec3 output expansion (knife 4, decisions §五): @output v (vec3) → three scalar pins v.x/v.y/v.z.
        var expandedLabels = new ArrayList<String>();
        var expandedRpns = new ArrayList<List<Object>>();
        for (int i = 0; i < out.outputLabels.size(); i++) {
            String label = out.outputLabels.get(i);
            List<Object> rpn = out.outputRpns.get(i);
            if (rpnYieldsVec3(rpn, vec3Vars)) {
                String prefix = label.isEmpty() ? "" : label + ".";
                for (char comp : new char[]{'x', 'y', 'z'}) {
                    var r = new ArrayList<Object>(rpn);
                    r.add(new SwizzleToken(String.valueOf(comp)));
                    expandedLabels.add(prefix + comp);
                    expandedRpns.add(r);
                }
            } else {
                expandedLabels.add(label);
                expandedRpns.add(rpn);
            }
        }

        return new ScriptParseResult(
            new ArrayList<>(inputVars), expandedLabels, expandedRpns, List.of(), false, originalFormula,
            out.stmts, vec3Vars, issues);
    }

    /** 解析语句块,直到 RBRACE 或输入结束。topLevel=true 时末行独立表达式计入默认输出候选。
     *  depth = 语句嵌套深度（P1-6 守卫,超 {@link FormulaParser#MAX_PARSE_DEPTH} 抛受控异常）。
     *  Parse statements until RBRACE or end of input. Only top-level standalone exprs count
     *  as default output. depth = statement nesting depth (P1-6 guard; over
     *  {@link FormulaParser#MAX_PARSE_DEPTH} throws the controlled exception). */
    private static void parseBlockStatements(LineCursor cur, AstParseOut out, boolean topLevel, int depth) {
        while (true) {
            Token t = cur.peek();
            if (t == null || t.type() == TokType.RBRACE) return;
            parseStatement(cur, out, topLevel, depth);
        }
    }

    private static void parseStatement(LineCursor cur, AstParseOut out, boolean topLevel, int depth) {
        // P1-6:递归入口统一守卫 —— 语句每深一层 depth + 1（parseBody 进体时 +1）。
        // P1-6: single guard at the recursion entry — each nesting level passes depth + 1
        // (parseBody increments when entering a body).
        if (depth > MAX_PARSE_DEPTH) throw new FormulaTooDeepException(depth);
        Token t = cur.next();
        if (t.type() == TokType.AT_OUTPUT) {
            // 解析期 hoist:@output <标识符|表达式>(决策 §3.6:块内同样 hoist)
            int srcLine = cur.currentSrcLine();
            var rest = consumeToSemicolon(cur);
            if (rest.isEmpty()) { consumeSemicolon(cur); return; }
            int col = rest.get(0).start();
            String text = tokensText(cur, rest);
            if (rest.size() == 1 && rest.get(0).type() == TokType.IDENT && isValidIdentifier(text)) {
                if (!out.outputLabels.contains(text)) {
                    out.outputLabels.add(text);
                    out.outputRpns.add(List.of((Object) text)); // 变量终值查找 / read final value
                }
            } else {
                // @output 表达式(如 @output yaw(aim))
                var rpn = compileTokens(rest);
                out.rpnSites.add(new AstParseOut.RpnSite(rpn, srcLine, col));
                if (!out.outputLabels.contains(text)) {
                    out.outputLabels.add(text);
                    out.outputRpns.add(rpn);
                }
            }
            consumeSemicolon(cur);
            return;
        }
        if (t.type() == TokType.KEYWORD) { parseKeywordStmt(cur, out, t, depth); consumeSemicolon(cur); return; }
        // 赋值或独立表达式
        int srcLine = cur.currentSrcLine();
        var rest = consumeToSemicolon(cur);
        int col = t.start();
        if (t.type() == TokType.IDENT && !rest.isEmpty() && rest.get(0).type() == TokType.ASSIGN) {
            var exprTokens = rest.subList(1, rest.size());
            var rpn = compileTokens(exprTokens);
            out.rpnSites.add(new AstParseOut.RpnSite(rpn, srcLine, col));
            out.stmts.add(new FormulaAst.AssignStmt(t.text(), rpn));
        } else {
            var full = new ArrayList<Token>();
            full.add(t);
            full.addAll(rest);
            var rpn = compileTokens(full);
            out.rpnSites.add(new AstParseOut.RpnSite(rpn, srcLine, col));
            out.stmts.add(new FormulaAst.ExprStmt(rpn));
            if (topLevel) out.lastStandaloneRpn = rpn; // 仅顶层末行表达式 = 默认输出候选
        }
        consumeSemicolon(cur);
    }

    /** 消费到行尾、分号或右大括号(不含)。语句以 ';' 或块结束 '}' 为界。
     *  Consume to line end, ';' or '}' (exclusive). Statements end at ';' or at the block-closing '}'. */
    private static List<Token> consumeToSemicolon(LineCursor cur) {
        var rest = new ArrayList<Token>();
        while (!cur.atLineEnd()) {
            Token t = cur.peek();
            if (t.type() == TokType.SEMICOLON || t.type() == TokType.RBRACE) break;
            rest.add(cur.next());
        }
        return rest;
    }

    /** 消费可选的语句分隔符 ';'。 / Consume the optional statement separator ';'. */
    private static void consumeSemicolon(LineCursor cur) {
        Token t = cur.peek();
        if (t != null && t.type() == TokType.SEMICOLON) cur.next();
    }

    private static void parseKeywordStmt(LineCursor cur, AstParseOut out, Token kw, int depth) {
        switch (kw.text()) {
            case "repeat" -> {
                Token num = cur.next();
                long count = (num != null && num.type() == TokType.NUMBER)
                    ? (long) Double.parseDouble(num.text()) : 1;
                var body = parseBody(cur, out, depth);
                out.stmts.add(new FormulaAst.RepeatStmt(Math.max(0, count), body));
            }
            case "while" -> {
                int srcLine = cur.currentSrcLine();
                var condTokens = parseCond(cur);
                int col = condTokens.isEmpty() ? kw.start() : condTokens.get(0).start();
                var condRpn = compileTokens(condTokens);
                out.rpnSites.add(new AstParseOut.RpnSite(condRpn, srcLine, col));
                var body = parseBody(cur, out, depth);
                out.stmts.add(new FormulaAst.WhileStmt(condRpn, body));
            }
            case "if" -> {
                int srcLine = cur.currentSrcLine();
                var condTokens = parseCond(cur);
                int col = condTokens.isEmpty() ? kw.start() : condTokens.get(0).start();
                var condRpn = compileTokens(condTokens);
                out.rpnSites.add(new AstParseOut.RpnSite(condRpn, srcLine, col));
                var body = parseBody(cur, out, depth);
                List<FormulaAst.Stmt> elseBody = List.of();
                // else 紧跟同一行 / else must follow on the same line
                Token t = cur.peek();
                if (t != null && t.type() == TokType.KEYWORD && t.text().equals("else")) {
                    cur.next();
                    elseBody = parseBody(cur, out, depth);
                }
                out.stmts.add(new FormulaAst.IfStmt(condRpn, body, elseBody));
            }
            case "break" -> out.stmts.add(new FormulaAst.BreakStmt());
            case "continue" -> out.stmts.add(new FormulaAst.ContinueStmt());
            default -> consumeToSemicolon(cur); // 未知关键字 lenient 跳过
        }
    }

    /** 解析循环/分支体:带大括号的块,或无大括号的单语句体(如 if (c) break)。
     *  depth+1 = 进入一层体（P1-6 深度计数）。
     *  Parse a loop/branch body: a braced block, or a brace-less single statement (e.g.
     *  if (c) break). depth+1 = one body level deeper (P1-6 depth counting). */
    private static List<FormulaAst.Stmt> parseBody(LineCursor cur, AstParseOut out, int depth) {
        Token t = cur.peek();
        if (t == null) return List.of();
        if (t.type() == TokType.LBRACE) {
            cur.next();
            var body = new ArrayList<FormulaAst.Stmt>();
            var nested = new AstParseOut();
            parseBlockStatements(cur, nested, false, depth + 1);
            body.addAll(nested.stmts);
            hoistNested(out, nested);
            Token close = cur.peek();
            if (close != null && close.type() == TokType.RBRACE) cur.next();
            return body;
        }
        // 无大括号:单语句体 / brace-less single-statement body
        var nested = new AstParseOut();
        parseStatement(cur, nested, false, depth + 1);
        hoistNested(out, nested);
        return nested.stmts;
    }

    /** 嵌套块内 @output/RPN 定位 hoist 到外层收集器。 / Hoist nested @outputs and RPN sites to the outer collector. */
    private static void hoistNested(AstParseOut out, AstParseOut nested) {
        for (int i = 0; i < nested.outputLabels.size(); i++) {
            if (!out.outputLabels.contains(nested.outputLabels.get(i))) {
                out.outputLabels.add(nested.outputLabels.get(i));
                out.outputRpns.add(nested.outputRpns.get(i));
            }
        }
        out.rpnSites.addAll(nested.rpnSites);
    }

    /** 解析 ( 条件 ) :嵌套括号按深度计数,FUNCTION 隐式开括号。 / Parse ( condition ) with depth counting. */
    private static List<Token> parseCond(LineCursor cur) {
        var cond = new ArrayList<Token>();
        Token t = cur.peek();
        if (t == null || t.type() != TokType.LPAREN) { cur.consumeToLineEnd(); return cond; }
        cur.next(); // consume LPAREN
        int depth = 0;
        while (true) {
            Token tt = cur.next();
            if (tt == null) break;
            if (tt.type() == TokType.LPAREN || tt.type() == TokType.FUNCTION) {
                depth++; cond.add(tt);
            } else if (tt.type() == TokType.RPAREN) {
                if (depth == 0) break;
                depth--; cond.add(tt);
            } else {
                cond.add(tt);
            }
        }
        return cond;
    }

    /** 由 token 偏移重建表达式原文(标签用;行内偏移)。 / Reconstruct expression text from token offsets (for labels). */
    private static String tokensText(LineCursor cur, List<Token> toks) {
        if (toks.isEmpty() || cur.li >= cur.lines.size()) return "";
        SrcLine line = cur.lines.get(cur.li);
        Token first = toks.get(0), last = toks.get(toks.size() - 1);
        int from = Math.max(0, Math.min(first.start(), line.raw().length()));
        int to = Math.max(from, Math.min(last.end(), line.raw().length()));
        return line.raw().substring(from, to).trim();
    }

    // ── AST 辅助遍历 / AST helper traversals ──

    private static void collectAssigns(FormulaAst.Stmt s, Set<String> assignedNames) {
        switch (s) {
            case FormulaAst.AssignStmt a -> assignedNames.add(a.var());
            case FormulaAst.WhileStmt w -> w.body().forEach(b -> collectAssigns(b, assignedNames));
            case FormulaAst.IfStmt i -> { i.body().forEach(b -> collectAssigns(b, assignedNames)); i.elseBody().forEach(b -> collectAssigns(b, assignedNames)); }
            case FormulaAst.RepeatStmt r -> r.body().forEach(b -> collectAssigns(b, assignedNames));
            default -> { }
        }
    }

    private static void collectStmtVars(FormulaAst.Stmt s, Set<String> out, Set<String> assignedNames) {
        switch (s) {
            case FormulaAst.AssignStmt a -> collectVars(out, assignedNames, a.rpn());
            case FormulaAst.ExprStmt e -> collectVars(out, assignedNames, e.rpn());
            case FormulaAst.WhileStmt w -> { collectVars(out, assignedNames, w.condRpn()); w.body().forEach(b -> collectStmtVars(b, out, assignedNames)); }
            case FormulaAst.IfStmt i -> { collectVars(out, assignedNames, i.condRpn()); i.body().forEach(b -> collectStmtVars(b, out, assignedNames)); i.elseBody().forEach(b -> collectStmtVars(b, out, assignedNames)); }
            case FormulaAst.RepeatStmt r -> r.body().forEach(b -> collectStmtVars(b, out, assignedNames));
            default -> { }
        }
    }

    private static void collectVars(Set<String> out, Set<String> assignedNames, List<Object> rpn) {
        for (var tok : rpn) {
            if (tok instanceof String v && isValidIdentifier(v) && !assignedNames.contains(v)) out.add(v);
        }
    }

    // ── 保守类型推断 / conservative type inference ──

    /** 不动点:任何处赋值表达式为向量 → 变量是 vec3(保守,引脚数解析后固定)。 */
    private static Set<String> inferVec3Vars(List<FormulaAst.Stmt> stmts) {
        var vec3 = new LinkedHashSet<String>();
        var assigns = new ArrayList<FormulaAst.AssignStmt>();
        for (var s : stmts) collectAssignList(s, assigns);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (var a : assigns) {
                if (!vec3.contains(a.var()) && rpnYieldsVec3(a.rpn(), vec3)) {
                    vec3.add(a.var());
                    changed = true;
                }
            }
        }
        return vec3;
    }

    private static void collectAssignList(FormulaAst.Stmt s, List<FormulaAst.AssignStmt> out) {
        switch (s) {
            case FormulaAst.AssignStmt a -> out.add(a);
            case FormulaAst.WhileStmt w -> w.body().forEach(b -> collectAssignList(b, out));
            case FormulaAst.IfStmt i -> { i.body().forEach(b -> collectAssignList(b, out)); i.elseBody().forEach(b -> collectAssignList(b, out)); }
            case FormulaAst.RepeatStmt r -> r.body().forEach(b -> collectAssignList(b, out));
            default -> { }
        }
    }
}
