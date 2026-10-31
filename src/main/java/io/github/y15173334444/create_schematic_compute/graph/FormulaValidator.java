package io.github.y15173334444.create_schematic_compute.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.FormulaIssue;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.FunctionToken;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.OpToken;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.Severity;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.SwizzleToken;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.ScriptParseResult;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.Token;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.TokType;

import static io.github.y15173334444.create_schematic_compute.graph.FormulaFunctions.CONSTANT_NAMES;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaFunctions.FUNCTIONS;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaFunctions.VECTOR_FN_NAMES;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaLexer.isValidIdentifier;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaLexer.tokenize;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaParser.parseScript;

/**
 * 公式校验相位（拓扑审查 §三 P5，自 FormulaParser 逐字迁出）：validate 的逐组启发式检查
 * （括号/大括号配对、未知字符、未知函数、元数、赋值左值、@output、关键字语法、空块、
 * while 恒真、swizzle 分量、AST 类型感知、输出引脚上限——纯 UI 辅助，不影响求值）与
 * typeCheckRpn 类型检查。§13 的双份解析已随 2026-10-31 拍板批去重：调用方传入同文本的
 * 预解析产物时组 13 直接复用（编辑器解析+校验共享一份产物），缺席/不匹配回退内部解析，
 * 行为等价。静态函数包，不建继承体系；公开入口经 FormulaParser 冻结门面。
 * <p>Formula validation phase (topology review §三 P5, relocated verbatim from
 * FormulaParser): validate's heuristic check groups (bracket/brace matching, unknown
 * characters, unknown functions, arity, assignment left side, @output, keyword syntax,
 * empty blocks, always-true while, swizzle components, AST type-awareness, output pin
 * cap — purely a UI aid, never affecting evaluation) and the typeCheckRpn type checks.
 * §13's double parse was de-duplicated in the 2026-10-31 ruling batch: a matching
 * pre-parsed artifact from the caller is reused by group 13 (the editor's parse and
 * validation share one artifact); absence or mismatch falls back to the internal parse,
 * behaviourally identical. Static function pack, no inheritance hierarchy; the public
 * entry goes through FormulaParser's frozen facade.</p>
 */
final class FormulaValidator {

    private FormulaValidator() {}

    private static final Logger LOGGER = LoggerFactory.getLogger(FormulaValidator.class);

    /**
     * Validate a formula string, returning a list of issues found.
     * This is purely a UI aid — it does not affect evaluation (which is lenient and defaults to 0).
     *
     * 校验公式字符串，返回发现的问题列表。
     * 纯 UI 辅助功能——不影响求值（求值宽容，默认返回 0）。
     */
    static List<FormulaIssue> validate(String src) {
        return validate(src, null);
    }

    /**
     * 带**预解析产物**的校验（§13 去重，2026-10-31 拍板批）：{@code pre} 非空且其
     * {@code sourceFormula} 与对源做括号 sanitize 后的文本一致时，组 13 直接复用该产物、
     * 不再完整重跑 {@code parseScript}——校验路径与解析路径共享同一份产物（编辑器每次
     * 击键由两次完整解析降为一次）；不一致或缺席回退内部解析，行为与单参形等价。
     * Validation with a **pre-parsed artifact** (the §13 dedup, 2026-10-31 ruling batch):
     * when {@code pre} matches the sanitized source, group 13 reuses it instead of
     * re-running {@code parseScript} in full — the validation and parse paths share one
     * artifact (each editor keystroke drops from two full parses to one); a mismatch or
     * absence falls back to the internal parse, behaviourally identical to the one-arg form.
     */
    static List<FormulaIssue> validate(String src, ScriptParseResult pre) {
        var issues = new ArrayList<FormulaIssue>();
        if (src == null || src.isEmpty()) return issues;
        String s = src.replace('（', '(').replace('）', ')');
        var tokens = tokenize(s);
        if (tokens.isEmpty()) return issues;

        // ── 1) Bracket matching ──
        // FUNCTION tokens implicitly open a call context (tokenize() consumes
        // their '(' and never emits a standalone LPAREN), so both LPAREN and
        // FUNCTION are pushed onto the stack.  RPAREN closes whichever is on top.
        var parenStack = new ArrayDeque<Integer>(); // token indices
        for (int ti = 0; ti < tokens.size(); ti++) {
            Token t = tokens.get(ti);
            if (t.type() == TokType.LPAREN || t.type() == TokType.FUNCTION) {
                parenStack.push(ti);
            } else if (t.type() == TokType.RPAREN) {
                if (parenStack.isEmpty()) {
                    int[] lc = lineCol(s, t.start());
                    issues.add(new FormulaIssue(lc[0], lc[1], t.end() - t.start(), Severity.ERROR,
                        "多余的右括号"));
                } else { parenStack.pop(); }
            }
        }
        for (int ti : parenStack) {
            Token t = tokens.get(ti);
            int[] lc = lineCol(s, t.start());
            if (t.type() == TokType.FUNCTION) {
                issues.add(new FormulaIssue(lc[0], lc[1], t.end() - t.start(), Severity.ERROR,
                    "未闭合的函数调用 '" + t.text() + "()'"));
            } else {
                issues.add(new FormulaIssue(lc[0], lc[1], t.end() - t.start(), Severity.ERROR,
                    "未闭合的左括号"));
            }
        }

        // ── 2) UNKNOWN tokens ──
        for (Token t : tokens) {
            if (t.type() == TokType.UNKNOWN) {
                int[] lc = lineCol(s, t.start());
                String text = t.text();
                if (text.equals(".")) {
                    issues.add(new FormulaIssue(lc[0], lc[1], 1, Severity.ERROR, "无法识别的字符 '.'"));
                } else if (!text.isEmpty() && (text.charAt(0) >= '0' && text.charAt(0) <= '9')) {
                    issues.add(new FormulaIssue(lc[0], lc[1], text.length(), Severity.ERROR,
                        "无效的数字格式 '" + text + "'"));
                } else {
                    issues.add(new FormulaIssue(lc[0], lc[1], text.length(), Severity.ERROR,
                        "无法识别的字符 '" + text + "'"));
                }
            }
        }

        // ── 4) Unknown function: IDENT followed by LPAREN but not in FUNCTIONS ──
        for (int ti = 0; ti < tokens.size() - 1; ti++) {
            Token t = tokens.get(ti);
            if (t.type() == TokType.IDENT && tokens.get(ti + 1).type() == TokType.LPAREN) {
                // CONSTANT followed by LPAREN is fine (e.g., (PI) is constant, not a call)
                // But IDENT followed by LPAREN means user tried to call something like foo(...)
                int[] lc = lineCol(s, t.start());
                issues.add(new FormulaIssue(lc[0], lc[1], t.end() - t.start(), Severity.ERROR,
                    "未知函数 '" + t.text() + "()'"));
            }
        }

        // ── 5) Function arity ──
        for (int ti = 0; ti < tokens.size(); ti++) {
            Token t = tokens.get(ti);
            if (t.type() == TokType.FUNCTION) {
                int arity = FUNCTIONS.getOrDefault(t.text(), 0);
                int argCount = countFunctionArgs(tokens, ti);
                if (argCount != arity) {
                    int[] lc = lineCol(s, t.start());
                    issues.add(new FormulaIssue(lc[0], lc[1], t.end() - t.start(), Severity.ERROR,
                        "函数 '" + t.text() + "()' 期望 " + arity + " 个参数，但传入了 " + argCount + " 个"));
                }
            }
        }

        // ── 6) Assignment: ASSIGN left side must be a valid identifier ──
        for (int ti = 0; ti < tokens.size(); ti++) {
            if (tokens.get(ti).type() == TokType.ASSIGN) {
                boolean validLeft = ti > 0 && tokens.get(ti - 1).type() == TokType.IDENT
                    && isValidIdentifier(tokens.get(ti - 1).text());
                if (!validLeft) {
                    int[] lc = lineCol(s, tokens.get(ti).start());
                    issues.add(new FormulaIssue(lc[0], lc[1], 1, Severity.ERROR,
                        "赋值左侧必须是变量名"));
                }
            }
        }

        // ── 7) @output checks ──
        var outputNames = new ArrayList<String>();
        for (int ti = 0; ti < tokens.size(); ti++) {
            if (tokens.get(ti).type() == TokType.AT_OUTPUT) {
                Token next = ti + 1 < tokens.size() ? tokens.get(ti + 1) : null;
                // 刀 3/4 起 @output 支持表达式(如 @output length(v))——表达式起始 token 均合法,
                // 仅对明确的非法起始(空/右括号/逗号/运算符等)给 WARN
                // Since knife 3/4 @output accepts expressions (e.g. @output length(v)) — any expression
                // start token is legal; WARN only on clearly invalid starts (empty / rparen / comma / op...)
                boolean exprStart = next != null && switch (next.type()) {
                    case IDENT, FUNCTION, LPAREN, NUMBER, CONSTANT -> true;
                    case OPERATOR -> next.text().equals("-") || next.text().equals("+") || next.text().equals("!");
                    default -> false;
                };
                if (!exprStart) {
                    int[] lc = lineCol(s, tokens.get(ti).start());
                    issues.add(new FormulaIssue(lc[0], lc[1], 7, Severity.WARN,
                        "@output 后面需要合法的变量名或表达式"));
                } else if (next.type() == TokType.IDENT && isValidIdentifier(next.text())) {
                    // 重名检查仅对纯变量名输出有意义 / duplicate-name check only meaningful for plain-name outputs
                    String outName = next.text();
                    if (CONSTANT_NAMES.contains(outName)) {
                        // @output PI / @output E is technically fine for bare variable PI/E
                        // (only (PI)/(E) is constant; bare PI/E is a variable)
                    }
                    if (outputNames.contains(outName)) {
                        int[] lc = lineCol(s, next.start());
                        issues.add(new FormulaIssue(lc[0], lc[1], next.end() - next.start(), Severity.WARN,
                            "重复的输出名 '" + outName + "'"));
                    } else {
                        outputNames.add(outName);
                    }
                }
            }
        }

        // ── 8) Brace matching (control-flow blocks) ──
        var braceStack = new ArrayDeque<Integer>();
        for (int ti = 0; ti < tokens.size(); ti++) {
            Token t = tokens.get(ti);
            if (t.type() == TokType.LBRACE) {
                braceStack.push(ti);
            } else if (t.type() == TokType.RBRACE) {
                if (braceStack.isEmpty()) {
                    int[] lc = lineCol(s, t.start());
                    issues.add(new FormulaIssue(lc[0], lc[1], 1, Severity.ERROR, "多余的右大括号 '}'"));
                } else { braceStack.pop(); }
            }
        }
        for (int ti : braceStack) {
            Token t = tokens.get(ti);
            int[] lc = lineCol(s, t.start());
            issues.add(new FormulaIssue(lc[0], lc[1], 1, Severity.ERROR, "未闭合的左大括号 '{'"));
        }

        // ── 9) Keyword syntax ──
        for (int ti = 0; ti < tokens.size(); ti++) {
            Token t = tokens.get(ti);
            if (t.type() != TokType.KEYWORD) continue;
            int[] lc = lineCol(s, t.start());
            String kw = t.text();
            if (kw.equals("repeat")) {
                if (ti + 2 >= tokens.size() || tokens.get(ti + 1).type() != TokType.NUMBER
                    || tokens.get(ti + 2).type() != TokType.LBRACE) {
                    issues.add(new FormulaIssue(lc[0], lc[1], kw.length(), Severity.ERROR,
                        "'repeat' 后应为次数和 '{',如 repeat 10 {"));
                }
            } else if (kw.equals("if") || kw.equals("while")) {
                if (ti + 1 >= tokens.size() || tokens.get(ti + 1).type() != TokType.LPAREN) {
                    issues.add(new FormulaIssue(lc[0], lc[1], kw.length(), Severity.ERROR,
                        "'" + kw + "' 后应为条件,如 " + kw + " (x > 0) {"));
                }
            } else if (kw.equals("else")) {
                if (ti + 1 >= tokens.size() || tokens.get(ti + 1).type() != TokType.LBRACE) {
                    issues.add(new FormulaIssue(lc[0], lc[1], kw.length(), Severity.ERROR,
                        "'else' 后应为 '{'"));
                }
            }
        }

        // ── 10) Empty block WARN ──
        for (int ti = 0; ti + 1 < tokens.size(); ti++) {
            if (tokens.get(ti).type() == TokType.LBRACE && tokens.get(ti + 1).type() == TokType.RBRACE) {
                int[] lc = lineCol(s, tokens.get(ti).start());
                issues.add(new FormulaIssue(lc[0], lc[1], 1, Severity.WARN, "空块不会产生任何效果"));
            }
        }

        // ── 11) while(true) WARN ──
        for (int ti = 0; ti + 3 < tokens.size(); ti++) {
            if (tokens.get(ti).type() == TokType.KEYWORD && tokens.get(ti).text().equals("while")
                && tokens.get(ti + 1).type() == TokType.LPAREN
                && tokens.get(ti + 2).type() == TokType.NUMBER
                && tokens.get(ti + 3).type() == TokType.RPAREN) {
                double v = Double.parseDouble(tokens.get(ti + 2).text());
                if (v != 0) {
                    int[] lc = lineCol(s, tokens.get(ti).start());
                    issues.add(new FormulaIssue(lc[0], lc[1], 5, Severity.WARN,
                        "while 条件恒真且无退出条件,将依赖预算超时兜底"));
                }
            }
        }

        // ── 12) Swizzle components ──
        for (Token t : tokens) {
            if (t.type() == TokType.SWIZZLE && !t.text().matches("[xyz]+")) {
                int[] lc = lineCol(s, t.start());
                issues.add(new FormulaIssue(lc[0], lc[1], t.end() - t.start(), Severity.ERROR,
                    "无效的分量访问 '.'" + t.text() + "(仅支持 x/y/z)"));
            }
        }

        // ── 13) 类型感知检查(AST 模式,复用 parseScript 的类型检查结果)/ type-aware checks (AST mode) ──
        // §13 去重（2026-10-31 拍板批）：调用方已持有同文本的解析产物时直接复用，不再完整
        // 重跑 parseScript；不匹配/缺席回退内部解析（两形行为等价，parseScript 确定性）。
        // §13 dedup (2026-10-31 ruling batch): when the caller already holds a parse of the
        // same text, reuse it instead of re-running parseScript in full; a mismatch or
        // absence falls back to the internal parse (behaviourally identical either way).
        try {
            ScriptParseResult parsed = (pre != null && pre.sourceFormula.equals(s)) ? pre : parseScript(s);
            // P1-6:嵌套超限的降级结果 ast==null 但携带 ERROR issue —— 同样要透出给徽章。
            // P1-6: a depth-degraded parse has ast==null but carries ERROR issues — surface them too.
            if (parsed.ast != null || !parsed.issues.isEmpty()) {
                issues.addAll(parsed.issues);
                // ── 14) 输出引脚 16 上限(vec3 展开后,节点 UI 上限)/ pin cap 16 (post vec3 expansion) ──
                if (parsed.outputLabels.size() > 16) {
                    issues.add(new FormulaIssue(0, 0, 0, Severity.WARN,
                        "输出引脚共 " + parsed.outputLabels.size() + " 个,超过节点显示上限 16,超出部分不在节点上显示"));
                }
            }
        } catch (Exception e) {
            LOGGER.debug("AST type-check parse failed", e);
        }

        return issues;
    }

    /** Count arguments inside a function call.  Since {@link FormulaLexer#tokenize(String)}
     *  consumes the {@code (} that follows a function name (it is never emitted as
     *  a standalone LPAREN token), the arguments start at {@code funcIdx+1}.
     *  Both LPAREN (grouping) and FUNCTION (nested call) increase depth;
     *  only depth==0 RPAREN closes the outer call whose args we are counting. */
    private static int countFunctionArgs(List<Token> tokens, int funcIdx) {
        if (funcIdx + 1 >= tokens.size()) return 0;
        int depth = 0, commas = 0;
        boolean hasContent = false;
        for (int i = funcIdx + 1; i < tokens.size(); i++) {
            TokType tt = tokens.get(i).type();
            if (tt == TokType.LPAREN || tt == TokType.FUNCTION) {
                depth++; hasContent = true;
            } else if (tt == TokType.RPAREN) {
                if (depth == 0) {
                    // Matching close of the outer function's argument list
                    return hasContent ? commas + 1 : 0;
                }
                depth--;
            } else if (tt == TokType.OPERATOR && tokens.get(i).text().equals(",") && depth == 0) {
                commas++;
            } else if (tt != TokType.UNKNOWN) {
                hasContent = true;
            }
        }
        return hasContent ? commas + 1 : 0;
    }

    /** Convert a character offset into (line, col) — both 0-based. */
    private static int[] lineCol(String src, int offset) {
        int line = 0, lineStart = 0;
        for (int i = 0; i < offset && i < src.length(); i++) {
            if (src.charAt(i) == '\n') { line++; lineStart = i + 1; }
        }
        return new int[]{line, offset - lineStart};
    }

    // ── 类型感知检查(校验期 ERROR,运行时仍 lenient)/ type-aware checks (editor ERROR, runtime stays lenient) ──

    /** 按决策 §五 检查一个 RPN 的类型歧义:向量对向量、向量比较、向量函数形态。
     *  Checks one RPN per decisions §五: vector-vs-vector ops, vector comparisons, vector fn arg shapes. */
    static void typeCheckRpn(List<Object> rpn, Set<String> vec3Vars, List<FormulaIssue> issues, int line, int col) {
        var stack = new ArrayDeque<Boolean>(); // true = vec3
        for (var tok : rpn) {
            // 栈下溢 = 表达式未输完(编辑框逐击键实时校验的中途状态)→ 保守放弃本 RPN 的类型检查,绝不抛异常
            // Underflow = incomplete expression (mid-keystroke validation) — abort this RPN's check conservatively, never throw
            try {
                if (tok instanceof Double) { stack.push(false); continue; }
                if (tok instanceof String v) { stack.push(vec3Vars.contains(v)); continue; }
                if (tok instanceof SwizzleToken) { stack.pop(); stack.push(false); continue; }
                if (tok instanceof OpToken ot) {
                    if (ot.op().equals("!")) { stack.pop(); stack.push(false); continue; }
                    boolean b = stack.pop(), a = stack.pop();
                    if (a || b) issues.add(new FormulaIssue(line, col, 1, Severity.ERROR,
                        "向量不可参与比较/逻辑运算('" + ot.op() + "')"));
                    stack.push(false);
                    continue;
                }
                if (tok instanceof FunctionToken ft) {
                    boolean[] args = new boolean[ft.arity()];
                    for (int i = ft.arity() - 1; i >= 0; i--) args[i] = stack.pop();
                    if (VECTOR_FN_NAMES.contains(ft.name())) {
                        // vec3 构造器例外:参数是标量 / vec3 constructor is the exception: args are scalars
                        for (int i = 0; i < args.length; i++) {
                            if (!args[i]) issues.add(new FormulaIssue(line, col, 1, Severity.ERROR,
                                "函数 '" + ft.name() + "' 的参数 " + (i + 1) + " 需要 vec3"));
                        }
                    }
                    stack.push(switch (ft.name()) {
                        case "vec3", "normalize", "cross" -> true;
                        default -> false;
                    });
                    continue;
                }
                char op = (Character) tok;
                boolean b = stack.pop(), a = stack.pop();
                if ((op == '<' || op == '>') && (a || b)) {
                    issues.add(new FormulaIssue(line, col, 1, Severity.ERROR, "向量不可比较"));
                } else if (op == '*' && a && b) {
                    issues.add(new FormulaIssue(line, col, 1, Severity.ERROR, "向量乘向量无良定义,请用 dot()/cross()"));
                } else if (op == '/' && b) {
                    issues.add(new FormulaIssue(line, col, 1, Severity.ERROR, "向量除法无良定义(v÷v / 标量÷v)"));
                } else if ((op == '%' || op == '^') && (a || b)) {
                    issues.add(new FormulaIssue(line, col, 1, Severity.ERROR, "运算符 '" + op + "' 不支持向量"));
                }
                stack.push((a || b) && "+-*/".indexOf(op) >= 0);
            } catch (java.util.NoSuchElementException underflow) {
                return;
            }
        }
    }
}
