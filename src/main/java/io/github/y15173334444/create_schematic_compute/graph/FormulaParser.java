package io.github.y15173334444.create_schematic_compute.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 公式解析器 — 将数学表达式编译为可执行的后缀表达式（RPN）及轻量级脚本解析。
 * 数学表达式支持：多字母变量名（连续字母如 ABD）、+ - * / % ^ ( )、数字、一元负号、
 *       三角函数：sin(x) cos(x) tan(x) asin(x) acos(x) atan2(y,x) sinh(x) cosh(x)
 *
 * 脚本模式（包含换行符或赋值语句或 @output 标记时自动启用）：
 *   -- 注释行        : 以 "--" 开头的行
 *   赋值语句         : varName = expression
 *   输出声明         : @output varName
 *   默认回退输出     : 若未声明 @output，最后一行独立表达式为输出
 */
public class FormulaParser {

    // ─────────────────── Token types for syntax highlighting / validation ───────────────────
    // 词法单元类型 — 用于语法高亮和校验

    public enum TokType { NUMBER, IDENT, FUNCTION, CONSTANT, OPERATOR, LPAREN, RPAREN,
                          COMMENT, AT_OUTPUT, ASSIGN, UNKNOWN }

    /** A lexical token with [start, end) character offsets in the source string.
     *  词法单元，携带源字符串中的 [start, end) 字符偏移。 */
    public record Token(int start, int end, TokType type, String text) {}

    /** Pattern for valid identifiers: a-z, A-Z, 0-9, underscore, starting with letter or underscore */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final Logger LOGGER = LoggerFactory.getLogger(FormulaParser.class);

    /** RPN token representing a function call. */
    private record FunctionToken(String name, int arity) {}

    /** 一条赋值语句：变量名 + 编译好的 RPN 表达式 */
    public record Assignment(String varName, List<Object> rpn) {}

    /** parseScript() 的结果 — 包含解析后的结构化信息，可供评估器和 UI 使用 */
    public static class ScriptParseResult {
        public final List<String> inputVars;       // 有序、去重的外部输入变量名
        public final List<String> outputLabels;    // @output 声明的输出名（空字符串 = 默认输出）
        public final List<List<Object>> outputRpns;// 每个输出对应的编译后 RPN 表达式
        public final List<Assignment> assignments; // 顺序的赋值语句列表
        public final boolean isLegacy;             // true = 旧版单行表达式模式

        public ScriptParseResult(List<String> inputVars, List<String> outputLabels,
                                 List<List<Object>> outputRpns, List<Assignment> assignments,
                                 boolean isLegacy) {
            this.inputVars = inputVars;
            this.outputLabels = outputLabels;
            this.outputRpns = outputRpns;
            this.assignments = assignments;
            this.isLegacy = isLegacy;
        }
    }

    public static final Map<String, Integer> FUNCTIONS;
    private static final Set<String> FUNCTION_NAMES;
    static {
        var m = new java.util.LinkedHashMap<String, Integer>();
        m.put("sin", 1); m.put("cos", 1); m.put("tan", 1);
        m.put("asin", 1); m.put("acos", 1); m.put("atan2", 2);
        m.put("sinh", 1); m.put("cosh", 1);
        m.put("sqrt", 1); m.put("ln", 1); m.put("log", 1); m.put("exp", 1);
        m.put("sec", 1); m.put("csc", 1); m.put("cot", 1);
        FUNCTIONS = Collections.unmodifiableMap(m);
        FUNCTION_NAMES = FUNCTIONS.keySet();
    }

    /** Named constants requiring parenthesis disambiguation: bare PI/E are variables,
     *  only (PI)/(E) wrapped directly by a grouping '(' are constant literals. */
    private static final Map<String, Double> CONSTANTS;
    private static final Set<String> CONSTANT_NAMES;
    static {
        var cm = new java.util.LinkedHashMap<String, Double>();
        cm.put("PI", Math.PI);
        cm.put("E", Math.E);
        CONSTANTS = Collections.unmodifiableMap(cm);
        CONSTANT_NAMES = CONSTANTS.keySet();
    }

    /** Returns the set of constant names (for autocomplete / validation to enumerate). */
    public static Set<String> constantNames() { return CONSTANT_NAMES; }

    /** Returns true if the name is a known constant name. */
    public static boolean isConstantName(String name) { return CONSTANT_NAMES.contains(name); }

    /** 解析 formula 返回所有变量名（按出现顺序，跳过函数名）。
     *  变量名支持字母、数字、下划线，首字符必须是字母或下划线。
     *  命名常量 (PI)/(E) 仅在其紧邻前驱为分组 '(' 时跳过（视为字面量），
     *  函数调用内的裸 PI/E（如 sin(PI)）仍作为变量。
     *  Uses {@link #tokenize(String)} so that CONSTANT vs IDENT distinction
     *  is consistent with {@link #compile(String)}. */
    public static List<String> extractVariables(String formula) {
        var vars = new LinkedHashSet<String>();
        if (formula == null || formula.isEmpty()) return new ArrayList<>(vars);
        // Normalize full-width parens to match tokenize() behaviour
        String s = formula.replace('（', '(').replace('）', ')');
        var tokens = tokenize(s);
        for (Token t : tokens) {
            if (t.type() == TokType.IDENT && !FUNCTION_NAMES.contains(t.text())) {
                vars.add(t.text());
            }
            // CONSTANT tokens represent literal PI/E inside grouping (…) and are
            // deliberately skipped — they are not variables.  PI/E tokens inside
            // function calls (e.g. sin(PI)) are marked IDENT by tokenize() because
            // the function's LPAREN is consumed by the FUNCTION token and never
            // emitted as a standalone LPAREN.
        }
        return new ArrayList<>(vars);
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }
    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }

    /**
     * Lexically tokenize a formula string for syntax highlighting, autocomplete, and validation.
     * Returns a flat list of tokens with [start, end) character offsets.
     * Whitespace is skipped (no tokens emitted). Unknown single characters that aren't
     * part of any valid token are emitted as {@code UNKNOWN}.
     *
     * 对公式字符串进行词法分析，用于语法高亮、自动补全和校验。
     * 返回带 [start, end) 字符偏移的 token 列表。空白字符被跳过（不生成 token）。
     * 不属于任何有效 token 的未知字符被标记为 {@code UNKNOWN}。
     */
    public static List<Token> tokenize(String src) {
        var tokens = new ArrayList<Token>();
        if (src == null || src.isEmpty()) return tokens;
        // Normalize full-width parens
        String s = src.replace('（', '(').replace('）', ')');
        int i = 0;
        int len = s.length();
        while (i < len) {
            char c = s.charAt(i);
            // Whitespace — skip
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') { i++; continue; }
            // Line continuation: backslash (treated as operator for coloring)
            if (c == '\\') { tokens.add(new Token(i, i + 1, TokType.OPERATOR, "\\")); i++; continue; }
            // Comment: -- to end of line
            if (c == '-' && i + 1 < len && s.charAt(i + 1) == '-') {
                int j = i + 2;
                while (j < len && s.charAt(j) != '\n') j++;
                tokens.add(new Token(i, j, TokType.COMMENT, s.substring(i, j)));
                i = j; continue;
            }
            // @output marker
            if (c == '@' && i + 6 <= len && s.startsWith("output", i + 1)) {
                int j = i + 7; // "@output".length()
                // only match if followed by whitespace, end, or non-ident char
                if (j >= len || !isIdentPart(s.charAt(j))) {
                    tokens.add(new Token(i, i + 7, TokType.AT_OUTPUT, "@output"));
                    i = i + 7; continue;
                }
            }
            // Identifiers
            if (isIdentStart(c)) {
                int j = i + 1;
                while (j < len && isIdentPart(s.charAt(j))) j++;
                String name = s.substring(i, j);
                // Peek ahead: is it a function call?
                int k = j;
                while (k < len && s.charAt(k) == ' ') k++;
                if (FUNCTION_NAMES.contains(name) && k < len && s.charAt(k) == '(') {
                    tokens.add(new Token(i, j, TokType.FUNCTION, name));
                    i = k + 1; // skip '(' — don't emit as standalone LPAREN,
                               // so that PI in sin(PI) is IDENT (variable),
                               // while PI in (PI) is CONSTANT (literal)
                    continue;
                }
                // Named constant: (PI) / (E) — only when immediately preceded by LPAREN token
                if (CONSTANT_NAMES.contains(name) && !tokens.isEmpty()
                    && tokens.get(tokens.size() - 1).type() == TokType.LPAREN) {
                    tokens.add(new Token(i, j, TokType.CONSTANT, name));
                } else {
                    tokens.add(new Token(i, j, TokType.IDENT, name));
                }
                i = j; continue;
            }
            // Numbers
            if (c >= '0' && c <= '9') {
                int j = i + 1;
                while (j < len && ((s.charAt(j) >= '0' && s.charAt(j) <= '9') || s.charAt(j) == '.')) j++;
                String numStr = s.substring(i, j);
                try {
                    Double.parseDouble(numStr);
                    tokens.add(new Token(i, j, TokType.NUMBER, numStr));
                } catch (NumberFormatException e) {
                    tokens.add(new Token(i, j, TokType.UNKNOWN, numStr));
                }
                i = j; continue;
            }
            // Parens
            if (c == '(') { tokens.add(new Token(i, i + 1, TokType.LPAREN, "(")); i++; continue; }
            if (c == ')') { tokens.add(new Token(i, i + 1, TokType.RPAREN, ")")); i++; continue; }
            // Assignment
            if (c == '=') { tokens.add(new Token(i, i + 1, TokType.ASSIGN, "=")); i++; continue; }
            // Comma (function argument separator)
            if (c == ',') { tokens.add(new Token(i, i + 1, TokType.OPERATOR, ",")); i++; continue; }
            // Arithmetic operators
            if ("+-*/%^".indexOf(c) >= 0) {
                tokens.add(new Token(i, i + 1, TokType.OPERATOR, String.valueOf(c)));
                i++; continue;
            }
            // Unknown character — group consecutive unknown chars
            int j = i + 1;
            while (j < len) {
                char nc = s.charAt(j);
                if (nc == ' ' || nc == '\t' || nc == '\r' || nc == '\n') break;
                if (isIdentStart(nc) || (nc >= '0' && nc <= '9')) break;
                if ("()=,+\\-*/%^".indexOf(nc) >= 0) break;
                if (nc == '@' || nc == '#' || nc == '!' || nc == '?' || nc == '<' || nc == '>'
                    || nc == ';' || nc == ':' || nc == '\'' || nc == '"') break;
                j++;
            }
            tokens.add(new Token(i, j, TokType.UNKNOWN, s.substring(i, j)));
            i = j;
        }
        return tokens;
    }

    // ──────────────────────────── Validation ────────────────────────────

    /** Issue severity / 问题严重程度 */
    public enum Severity { ERROR, WARN }

    /** A validation issue with source location and message.
     *  校验问题，包含源位置和消息。 */
    public record FormulaIssue(int line, int col, int length, Severity severity, String message) {}

    /**
     * Validate a formula string, returning a list of issues found.
     * This is purely a UI aid — it does not affect evaluation (which is lenient and defaults to 0).
     *
     * 校验公式字符串，返回发现的问题列表。
     * 纯 UI 辅助功能——不影响求值（求值宽容，默认返回 0）。
     */
    public static List<FormulaIssue> validate(String src) {
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
                if (next == null || next.type() != TokType.IDENT || !isValidIdentifier(next.text())) {
                    int[] lc = lineCol(s, tokens.get(ti).start());
                    issues.add(new FormulaIssue(lc[0], lc[1], 7, Severity.WARN,
                        "@output 后面需要合法的变量名"));
                } else {
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

        return issues;
    }

    /** Count arguments inside a function call.  Since {@link #tokenize(String)}
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

    /** 编译表达式为 RPN token 列表（String=变量名, Double=数字, Character=运算符, FunctionToken=函数调用）。
     *  Refactored to consume {@link #tokenize(String)} so that tokenization, highlighting, and
     *  validation share a single scan of the source.  The RPN produced is identical to the old
     *  character-at-a-time implementation. */
    public static List<Object> compile(String formula) {
        // 自动将全角括号转为半角 / auto-convert full-width parens to half-width
        formula = formula.replace('（', '(').replace('）', ')');
        var tokens = tokenize(formula);
        var output = new ArrayList<Object>();
        var ops = new ArrayDeque<Object>();
        boolean expectUnary = true;
        for (int ti = 0; ti < tokens.size(); ti++) {
            Token t = tokens.get(ti);
            switch (t.type()) {
                case NUMBER -> {
                    output.add(Double.parseDouble(t.text()));
                    expectUnary = false;
                }
                case IDENT -> {
                    output.add(t.text());
                    expectUnary = false;
                }
                case CONSTANT -> {
                    // Re-check ops stack: only treat as literal when a grouping '(' is on top.
                    // tokenize() may mark PI/E as CONSTANT even inside function arguments
                    // (e.g. sin(PI)) because it only sees the preceding LPAREN token.
                    // The old compile() checked ops.peek()=='(' to distinguish grouping '(' from
                    // function '(' — we must do the same here to keep PI as a variable reference
                    // inside function calls (so it generates an input pin).
                    Double v = CONSTANTS.get(t.text());
                    if (v != null && !ops.isEmpty() && ops.peek().equals('(')) {
                        output.add(v);
                    } else {
                        output.add(t.text()); // variable reference
                    }
                    expectUnary = false;
                }
                case FUNCTION -> {
                    ops.push(new FunctionToken(t.text(), FUNCTIONS.getOrDefault(t.text(), 1)));
                    // Skip the following LPAREN (FUNCTION token already consumed the '(')
                    if (ti + 1 < tokens.size() && tokens.get(ti + 1).type() == TokType.LPAREN) ti++;
                    expectUnary = true;
                }
                case LPAREN -> {
                    ops.push('(');
                    expectUnary = true;
                }
                case RPAREN -> {
                    while (!ops.isEmpty() && !(ops.peek() instanceof FunctionToken) && !ops.peek().equals('('))
                        output.add(ops.pop());
                    if (!ops.isEmpty()) {
                        Object top = ops.pop();
                        if (top instanceof FunctionToken ft) output.add(ft);
                    }
                    expectUnary = false;
                }
                case OPERATOR -> {
                    String opText = t.text();
                    if (opText.equals(",")) {
                        while (!ops.isEmpty() && !(ops.peek() instanceof FunctionToken))
                            output.add(ops.pop());
                        expectUnary = true;
                    } else {
                        char op = opText.charAt(0);
                        if (expectUnary && (op == '+' || op == '-')) {
                            output.add(0.0);
                            ops.push('-');
                        } else if ("+-*/%^".indexOf(op) >= 0) {
                            int prec = precedence(op);
                            while (!ops.isEmpty() && ops.peek() instanceof Character opc
                                   && !opc.equals('(') && precedence(opc) >= prec)
                                output.add(ops.pop());
                            ops.push(op);
                        }
                        // else: \ (line continuation) or other non-arithmetic operator — skip
                        expectUnary = true;
                    }
                }
                // ASSIGN, COMMENT, AT_OUTPUT, UNKNOWN — lenient skip
                default -> { /* skip */ }
            }
        }
        while (!ops.isEmpty()) output.add(ops.pop());
        return output;
    }

    /** 计算 RPN 表达式 */
    public static double evaluate(List<Object> rpn, Map<String, Double> vars) {
        var stack = new ArrayDeque<Double>();
        for (var tok : rpn) {
            if (tok instanceof Double d) { stack.push(d); continue; }
            if (tok instanceof String varName) { stack.push(vars.getOrDefault(varName, 0.0)); continue; }
            if (tok instanceof FunctionToken ft) {
                int arity = ft.arity();
                double[] args = new double[arity];
                for (int a = arity - 1; a >= 0; a--) args[a] = stack.pop();
                stack.push(applyFunction(ft.name(), args));
                continue;
            }
            char op = (Character)tok;
            double b = stack.pop(), a = stack.pop();
            stack.push(switch(op){
                case '+' -> a + b; case '-' -> a - b; case '*' -> a * b;
                case '/' -> b != 0 ? a / b : 0; case '%' -> b != 0 ? a % b : 0;
                case '^' -> Math.pow(a, b);
                default -> 0.0;
            });
        }
        return stack.isEmpty() ? 0 : stack.pop();
    }

    /** 执行单个函数调用。角度约定与 GraphEvaluator 中 trig 节点保持一致：
     *  sin/cos/tan 输入为度（内部转弧度）；asin/acos/atan2 输出为度（内部转弧度计算再转回）；
     *  sinh/cosh 直接使用原值。 */
    private static double applyFunction(String name, double[] args) {
        return switch (name) {
            case "sin" -> {
                double v = args[0];
                yield Double.isFinite(v) ? Math.sin(Math.toRadians(v)) : 0;
            }
            case "cos" -> {
                double v = args[0];
                yield Double.isFinite(v) ? Math.cos(Math.toRadians(v)) : 0;
            }
            case "tan" -> {
                double v = args[0];
                yield Double.isFinite(v) ? Math.tan(Math.toRadians(v)) : 0;
            }
            case "asin" -> {
                double v = args[0];
                yield (Double.isFinite(v) && v >= -1 && v <= 1) ? Math.toDegrees(Math.asin(v)) : 0;
            }
            case "acos" -> {
                double v = args[0];
                yield (Double.isFinite(v) && v >= -1 && v <= 1) ? Math.toDegrees(Math.acos(v)) : 0;
            }
            case "atan2" -> {
                double y = args[0], x = args[1];
                yield (Double.isFinite(y) && Double.isFinite(x)) ? Math.toDegrees(Math.atan2(y, x)) : 0;
            }
            case "sinh" -> {
                double v = args[0];
                yield Double.isFinite(v) ? Math.sinh(v) : 0;
            }
            case "cosh" -> {
                double v = args[0];
                yield Double.isFinite(v) ? Math.cosh(v) : 0;
            }
            case "sqrt" -> {
                double v = args[0];
                yield Double.isFinite(v) && v >= 0 ? Math.sqrt(v) : 0;
            }
            case "ln" -> {
                double v = args[0];
                yield Double.isFinite(v) && v > 0 ? Math.log(v) : 0;
            }
            case "log" -> {
                double v = args[0];
                yield Double.isFinite(v) && v > 0 ? Math.log10(v) : 0;
            }
            case "exp" -> {
                double v = args[0];
                yield Double.isFinite(v) ? Math.exp(v) : 0;
            }
            case "sec" -> {
                double v = args[0];
                yield (Double.isFinite(v) && Math.abs(Math.cos(Math.toRadians(v))) > 1e-12)
                    ? 1.0 / Math.cos(Math.toRadians(v)) : 0;
            }
            case "csc" -> {
                double v = args[0];
                yield (Double.isFinite(v) && Math.abs(Math.sin(Math.toRadians(v))) > 1e-12)
                    ? 1.0 / Math.sin(Math.toRadians(v)) : 0;
            }
            case "cot" -> {
                double v = args[0];
                yield (Double.isFinite(v) && Math.abs(Math.tan(Math.toRadians(v))) > 1e-12)
                    ? 1.0 / Math.tan(Math.toRadians(v)) : 0;
            }
            default -> 0;
        };
    }

    private static int precedence(char op) {
        return switch(op) { case '+','-' -> 1; case '*','/','%' -> 2; case '^' -> 3; default -> 0; };
    }

    // ==================== 脚本解析（v1.2+） ====================

    /** 检查字符串是否为合法标识符 */
    public static boolean isValidIdentifier(String s) {
        return s != null && !s.isEmpty() && IDENTIFIER.matcher(s).matches() && !FUNCTION_NAMES.contains(s);
    }

    /**
     * 解析 formula 文本，返回结构化的脚本解析结果。
     * 自动检测旧版单行表达式模式（无换行、无 =、无 @output）并保持向后兼容。
     */
    public static ScriptParseResult parseScript(String formula) {
        if (formula == null) {
            return new ScriptParseResult(
                List.of(), List.of(""), List.of(), List.of(), true);
        }
        // Normalize line endings: \r\n → \n, strip standalone \r
        formula = formula.replace("\r\n", "\n").replace("\r", "");
        if (formula.isEmpty()) {
            return new ScriptParseResult(
                List.of(), List.of(""), List.of(), List.of(), true);
        }
        // 向下兼容检测：无换行、无 =、无 @output → 旧版单行表达式
        boolean hasNewline = formula.indexOf('\n') >= 0;
        boolean hasAssignment = formula.indexOf('=') >= 0;
        boolean hasOutputMarker = formula.contains("@output");
        if (!hasNewline && !hasAssignment && !hasOutputMarker) {
            // Legacy single-expression mode
            List<String> vars = extractVariables(formula);
            List<Object> rpn;
            try { rpn = compile(formula); } catch (Exception e) { LOGGER.debug("Legacy expression compile failed", e); rpn = List.of(0.0); }
            return new ScriptParseResult(
                vars, List.of(""), List.of(rpn), List.of(), true);
        }

        // 新脚本模式：逐行解析
        String[] rawLines = formula.split("\n", -1);
        // Join line continuations: lines ending with \ merge with the next line
        var joined = new java.util.ArrayList<String>();
        StringBuilder continuation = new StringBuilder();
        for (String l : rawLines) {
            String trimmed = l.trim();
            if (trimmed.endsWith("\\")) {
                // Line continuation: strip trailing \ and append to buffer
                continuation.append(trimmed.substring(0, trimmed.length() - 1).trim());
                continuation.append(' ');
            } else if (continuation.length() > 0) {
                // Previous line had continuation: merge
                continuation.append(trimmed);
                joined.add(continuation.toString());
                continuation.setLength(0);
            } else {
                joined.add(trimmed);
            }
        }
        if (continuation.length() > 0) {
            // Last line had continuation but no follow-up
            joined.add(continuation.toString().trim());
        }
        // Filter: skip empty and comment-only lines
        var lines = new java.util.ArrayList<String>();
        for (String l : joined) {
            String trimmed = l.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
            lines.add(trimmed);
        }

        var outputLabels = new ArrayList<String>();
        var outputRpns = new ArrayList<List<Object>>();
        var assignments = new ArrayList<Assignment>();
        String lastStandaloneExpr = null;
        List<Object> lastStandaloneRpn = null;

        for (String line : lines) {
            // @output marker
            if (line.startsWith("@output")) {
                String rest = line.substring("@output".length()).trim();
                if (isValidIdentifier(rest)) {
                    // 防止重复输出名
                    if (!outputLabels.contains(rest)) {
                        outputLabels.add(rest);
                        // @output 的 RPN 就是变量名本身（运行时从 env 读取）
                        outputRpns.add(List.of((Object)rest));
                    }
                }
                continue;
            }
            // Assignment: varName = expression
            int eqIdx = -1;
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == '=') { eqIdx = i; break; }
            }
            if (eqIdx > 0) {
                String varName = line.substring(0, eqIdx).trim();
                if (isValidIdentifier(varName)) {
                    String expr = line.substring(eqIdx + 1).trim();
                    if (!expr.isEmpty()) {
                        try {
                            List<Object> rpn = compile(expr);
                            assignments.add(new Assignment(varName, rpn));
                        } catch (Exception e) {
                            // 编译失败 → 赋值 0
                            LOGGER.debug("Compile failed for assignment '{}'", varName, e);
                            assignments.add(new Assignment(varName, List.of(0.0)));
                        }
                    }
                }
                continue;
            }
            // Standalone expression (potential default output)
            try {
                lastStandaloneExpr = line;
                lastStandaloneRpn = compile(line);
            } catch (Exception e) {
                LOGGER.debug("Standalone expression compile failed", e);
                lastStandaloneRpn = List.of(0.0);
            }
        }

        // Determine outputs
        if (outputLabels.isEmpty()) {
            // 无 @output：最后一行独立表达式作为默认输出
            if (lastStandaloneRpn != null) {
                outputLabels.add("");
                outputRpns.add(lastStandaloneRpn);
            } else {
                // 全部是赋值无表达式 → 输出 0
                outputLabels.add("");
                outputRpns.add(List.of(0.0));
            }
        }

        // Resolve external input variables:
        // Collect all variable references from all RPNs, then remove those that
        // are assigned before they are first read.
        Set<String> assignedNames = new LinkedHashSet<>();
        for (var a : assignments) assignedNames.add(a.varName());

        var inputVars = new LinkedHashSet<String>();
        // 1) Collect vars from output RPNs
        for (var rpn : outputRpns) {
            for (var tok : rpn) {
                if (tok instanceof String v && isValidIdentifier(v) && !assignedNames.contains(v))
                    inputVars.add(v);
            }
        }
        // 2) Collect vars from assignment RHS expressions
        for (var a : assignments) {
            for (var tok : a.rpn()) {
                if (tok instanceof String v && isValidIdentifier(v) && !assignedNames.contains(v))
                    inputVars.add(v);
            }
        }

        return new ScriptParseResult(
            new ArrayList<>(inputVars), outputLabels, outputRpns, assignments, false);
    }
}
