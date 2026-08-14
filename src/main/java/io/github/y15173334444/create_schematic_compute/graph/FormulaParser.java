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
                          COMMENT, AT_OUTPUT, ASSIGN, UNKNOWN,
                          LBRACE, RBRACE, KEYWORD, SWIZZLE, SEMICOLON }

    /** A lexical token with [start, end) character offsets in the source string.
     *  词法单元，携带源字符串中的 [start, end) 字符偏移。 */
    public record Token(int start, int end, TokType type, String text) {}

    /** Pattern for valid identifiers: a-z, A-Z, 0-9, underscore, starting with letter or underscore */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final Logger LOGGER = LoggerFactory.getLogger(FormulaParser.class);

    /** RPN token representing a function call. */
    private record FunctionToken(String name, int arity) {}

    /** 双字符/逻辑运算符的 RPN token(==、!=、<=、>=、&&、||、一元 !)。
     *  RPN token for two-char / logical operators (==, !=, <=, >=, &&, ||, unary !). */
    public record OpToken(String op) {}

    /** 分量访问 RPN token(v.x → 弹出向量压入分量)。 / Swizzle RPN token (v.x → pop vector, push component). */
    public record SwizzleToken(String comps) {}

    /** 控制流关键字(repeat/while/if/else/break/continue)。 / Control-flow keywords. */
    public static final Set<String> KEYWORDS = Set.of("repeat", "while", "if", "else", "break", "continue");

    /** `==`/`!=` 浮点容差(决策文档 §五:1e-6;`< > <= >=` 保持精确)。
     *  Float tolerance for ==/!= (decisions doc §五: 1e-6; < > <= >= stay exact). */
    public static final double EQ_TOLERANCE = 1e-6;

    /** 一条赋值语句：变量名 + 编译好的 RPN 表达式 */
    public record Assignment(String varName, List<Object> rpn) {}

    /** parseScript() 的结果 — 包含解析后的结构化信息，可供评估器和 UI 使用 */
    public static class ScriptParseResult {
        public final List<String> inputVars;       // 有序、去重的外部输入变量名
        public final List<String> outputLabels;    // @output 声明的输出名（空字符串 = 默认输出）
        public final List<List<Object>> outputRpns;// 每个输出对应的编译后 RPN 表达式
        public final List<Assignment> assignments; // 顺序的赋值语句列表
        public final boolean isLegacy;             // true = 旧版单行表达式模式
        public final String sourceFormula;         // 解析来源字符串，用于检测陈旧缓存
        public final List<FormulaAst.Stmt> ast;    // 刀3:语句树;null = 旧 RPN 模式(向后兼容)/ knife 3: statement tree; null = legacy RPN mode
        public final Set<String> vec3Vars;         // 刀3:保守类型推断——任何处赋值为向量的变量名(刀4 引脚展开用)
        public final List<FormulaIssue> issues;    // 刀3:AST 解析期的类型感知问题(validate() 合并展示)

        public ScriptParseResult(List<String> inputVars, List<String> outputLabels,
                                 List<List<Object>> outputRpns, List<Assignment> assignments,
                                 boolean isLegacy, String sourceFormula,
                                 List<FormulaAst.Stmt> ast, Set<String> vec3Vars, List<FormulaIssue> issues) {
            this.inputVars = inputVars;
            this.outputLabels = outputLabels;
            this.outputRpns = outputRpns;
            this.assignments = assignments;
            this.isLegacy = isLegacy;
            this.sourceFormula = sourceFormula;
            this.ast = ast;
            this.vec3Vars = vec3Vars;
            this.issues = issues;
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
        m.put("vec3", 3);       // 向量构造 / vector constructor
        m.put("length", 1);     // 模长 / magnitude
        m.put("normalize", 1);  // 单位化 / normalize
        m.put("dot", 2);        // 点积 / dot product
        m.put("cross", 2);      // 叉积 / cross product
        m.put("dist", 2);       // 两点距 / distance
        m.put("yaw", 1);        // 偏航角(度)/ yaw angle (degrees)
        m.put("pitch", 1);      // 俯仰角(度)/ pitch angle (degrees)
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
                // Control-flow keyword — before function/variable checks
                if (KEYWORDS.contains(name)) {
                    tokens.add(new Token(i, j, TokType.KEYWORD, name));
                    i = j; continue;
                }
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
            // Two-char operators: == != <= >=
            if ((c == '=' || c == '!' || c == '<' || c == '>') && i + 1 < len && s.charAt(i + 1) == '=') {
                tokens.add(new Token(i, i + 2, TokType.OPERATOR, s.substring(i, i + 2)));
                i += 2; continue;
            }
            // Two-char logical operators: && ||
            if (c == '&' && i + 1 < len && s.charAt(i + 1) == '&') {
                tokens.add(new Token(i, i + 2, TokType.OPERATOR, "&&"));
                i += 2; continue;
            }
            if (c == '|' && i + 1 < len && s.charAt(i + 1) == '|') {
                tokens.add(new Token(i, i + 2, TokType.OPERATOR, "||"));
                i += 2; continue;
            }
            // Assignment
            if (c == '=') { tokens.add(new Token(i, i + 1, TokType.ASSIGN, "=")); i++; continue; }
            // Comparison / logical singles: < > !
            if (c == '<' || c == '>' || c == '!') {
                tokens.add(new Token(i, i + 1, TokType.OPERATOR, String.valueOf(c)));
                i++; continue;
            }
            // Braces (control-flow blocks)
            if (c == '{') { tokens.add(new Token(i, i + 1, TokType.LBRACE, "{")); i++; continue; }
            if (c == '}') { tokens.add(new Token(i, i + 1, TokType.RBRACE, "}")); i++; continue; }
            // Swizzle: '.' followed by identifier → member access (v.x / v.xy)
            if (c == '.' && i + 1 < len && isIdentStart(s.charAt(i + 1))) {
                int j = i + 1;
                while (j < len && isIdentPart(s.charAt(j))) j++;
                tokens.add(new Token(i, j, TokType.SWIZZLE, s.substring(i + 1, j)));
                i = j; continue;
            }
            // Semicolon (statement separator, knife 3)
            if (c == ';') { tokens.add(new Token(i, i + 1, TokType.SEMICOLON, ";")); i++; continue; }
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
                if ("()=,+\\-*/%^{}!.&|<>".indexOf(nc) >= 0) break;
                if (nc == '@' || nc == '#' || nc == '?' || nc == ';' || nc == ':' || nc == '\'' || nc == '"') break;
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
        try {
            var parsed = parseScript(s);
            if (parsed.ast != null) {
                issues.addAll(parsed.issues);
            }
        } catch (Exception e) {
            LOGGER.debug("AST type-check parse failed", e);
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
        return compileTokens(tokenize(formula));
    }

    /** 从 token 列表编译 RPN(表达式片段用,如循环条件/行内表达式)。
     *  Compile RPN from a token list (for expression fragments, e.g. loop conditions). */
    public static List<Object> compileTokens(List<Token> tokens) {
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
                case SWIZZLE -> {
                    // 后缀分量访问:直接进输出 / postfix member access: straight to output
                    output.add(new SwizzleToken(t.text()));
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
                    } else if (expectUnary && opText.equals("!")) {
                        // 一元逻辑非:求值时只弹一个操作数 / unary logical not: pops a single operand at eval
                        ops.push(new OpToken("!"));
                        expectUnary = true;
                    } else {
                        char op = opText.charAt(0);
                        if (expectUnary && (op == '+' || op == '-')) {
                            output.add(0.0);
                            ops.push('-');
                        } else if (isBinaryOperator(opText)) {
                            Object opObj = opText.length() == 1 ? (Object)op : (Object)new OpToken(opText);
                            int prec = precedenceOf(opObj);
                            while (!ops.isEmpty() && !(ops.peek() instanceof FunctionToken)
                                   && !ops.peek().equals('(')
                                   && precedenceOf(ops.peek()) >= prec)
                                output.add(ops.pop());
                            ops.push(opObj);
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

    /** 计算 RPN 表达式(标量适配层,旧 API 零回归)。内部走统一 Value 栈机,纯标量 RPN 结果逐位不变。
     *  Evaluate an RPN expression (scalar adapter, legacy API zero regression). Internally the unified
     *  Value stack machine — pure scalar RPNs stay bit-identical. */
    public static double evaluate(List<Object> rpn, Map<String, Double> vars) {
        var env = new java.util.HashMap<String, Value>(Math.max(4, vars.size() * 2));
        for (var e : vars.entrySet()) env.put(e.getKey(), new Value.Scalar(e.getValue()));
        return asScalar(evaluateValue(rpn, env));
    }

    /** 取标量分量;向量入参 lenient 归 0(刀 3 在解析/校验层拦形态错误)。
     *  Extract the scalar component; vectors are leniently 0 (knife 3 rejects shape errors at parse/validate). */
    public static double asScalar(Value v) {
        return v instanceof Value.Scalar s ? s.v() : 0.0;
    }

    /** 真值判定:!= 0(比较/逻辑运算符共用,决策文档 §五)。 / Truthiness: != 0 (shared by comparison/logical operators). */
    private static boolean truthy(double v) { return v != 0.0; }

    /**
     * 统一 Value 栈机(单一求值引擎,决策文档 §五)。旧标量 RPN 与刀 3 的向量/控制流共用同一引擎。
     * Unified Value stack machine (single evaluation engine, decisions doc §五). Legacy scalar RPN
     * and knife-3 vectors share this one engine.
     */
    public static Value evaluateValue(List<Object> rpn, Map<String, Value> env) {
        var stack = new ArrayDeque<Value>();
        for (var tok : rpn) {
            if (tok instanceof Double d) { stack.push(new Value.Scalar(d)); continue; }
            if (tok instanceof String varName) { stack.push(env.getOrDefault(varName, Value.Scalar.ZERO)); continue; }
            if (tok instanceof FunctionToken ft) {
                if (ft.name().equals("vec3") || VECTOR_FN_NAMES.contains(ft.name())) {
                    // 向量函数:按参数形态分派(决策 §五)/ vector functions: dispatch by arg shape
                    stack.push(applyVectorFunction(ft, stack));
                } else {
                    // 标量函数 / scalar functions
                    int arity = ft.arity();
                    double[] args = new double[arity];
                    for (int a = arity - 1; a >= 0; a--) args[a] = asScalar(stack.pop());
                    stack.push(new Value.Scalar(applyFunction(ft.name(), args)));
                }
                continue;
            }
            if (tok instanceof SwizzleToken st) {
                // 分量访问:弹出基值取分量 / member access: pop base, push component
                Value base = stack.pop();
                if (base instanceof Value.Vec3Val v && st.comps().length() == 1) {
                    stack.push(new Value.Scalar(switch (st.comps()) {
                        case "x" -> v.x(); case "y" -> v.y(); default -> v.z();
                    }));
                } else {
                    stack.push(Value.Scalar.ZERO); // 组合分量暂缓/标量取分量 → lenient 零
                }
                continue;
            }
            if (tok instanceof OpToken ot) {
                if (ot.op().equals("!")) {
                    Value v = stack.pop();
                    stack.push(new Value.Scalar(truthy(asScalar(v)) ? 0.0 : 1.0));
                    continue;
                }
                Value bv = stack.pop(), av = stack.pop();
                if (av instanceof Value.Vec3Val || bv instanceof Value.Vec3Val) {
                    stack.push(Value.Scalar.ZERO); // 向量不可比较(校验期报错,运行时 lenient 0)
                    continue;
                }
                double b = asScalar(bv), a = asScalar(av);
                double r = switch (ot.op()) {
                    case "==" -> Math.abs(a - b) < EQ_TOLERANCE ? 1.0 : 0.0; // 1e-6 容差 / tolerance
                    case "!=" -> Math.abs(a - b) < EQ_TOLERANCE ? 0.0 : 1.0;
                    case "<=" -> a <= b ? 1.0 : 0.0;
                    case ">=" -> a >= b ? 1.0 : 0.0;
                    case "&&" -> (truthy(a) && truthy(b)) ? 1.0 : 0.0;
                    case "||" -> (truthy(a) || truthy(b)) ? 1.0 : 0.0;
                    default -> 0.0;
                };
                stack.push(new Value.Scalar(r));
                continue;
            }
            char op = (Character)tok;
            Value bv = stack.pop(), av = stack.pop();
            if (av instanceof Value.Vec3Val || bv instanceof Value.Vec3Val) {
                stack.push(applyVectorOp(op, av, bv));
                continue;
            }
            double b = asScalar(bv), a = asScalar(av);
            stack.push(new Value.Scalar(switch(op){
                case '+' -> a + b; case '-' -> a - b; case '*' -> a * b;
                case '/' -> b != 0 ? a / b : 0; case '%' -> b != 0 ? a % b : 0;
                case '^' -> Math.pow(a, b);
                case '<' -> a < b ? 1.0 : 0.0;
                case '>' -> a > b ? 1.0 : 0.0;
                default -> 0.0;
            }));
        }
        return stack.isEmpty() ? Value.Scalar.ZERO : stack.pop();
    }

    /** 向量函数名集合。 / Vector function names. */
    private static final Set<String> VECTOR_FN_NAMES =
        Set.of("length", "normalize", "dot", "cross", "dist", "yaw", "pitch");

    /** 取向量形态;标量 lenient 广播为 (s, 0, 0)(校验期会拦形态错误)。
     *  Extract vector shape; scalars broadcast leniently to (s, 0, 0) (validate flags shape errors). */
    public static Value.Vec3Val asVec(Value v) {
        return v instanceof Value.Vec3Val vv ? vv : new Value.Vec3Val(asScalar(v), 0.0, 0.0);
    }

    /** 向量函数求值。yaw/pitch 逐字对齐 DIRECTION 节点(GraphEvaluator.java:577-579)。
     *  Vector function evaluation. yaw/pitch mirror the DIRECTION node exactly. */
    private static Value applyVectorFunction(FunctionToken ft, ArrayDeque<Value> stack) {
        return switch (ft.name()) {
            case "vec3" -> {
                double z = asScalar(stack.pop()), y = asScalar(stack.pop()), x = asScalar(stack.pop());
                yield new Value.Vec3Val(x, y, z);
            }
            case "length" -> {
                var v = asVec(stack.pop());
                yield new Value.Scalar(Math.sqrt(v.x()*v.x() + v.y()*v.y() + v.z()*v.z()));
            }
            case "normalize" -> {
                var v = asVec(stack.pop());
                double l = Math.sqrt(v.x()*v.x() + v.y()*v.y() + v.z()*v.z());
                yield l > 1e-12 ? new Value.Vec3Val(v.x()/l, v.y()/l, v.z()/l) : Value.Vec3Val.ZERO;
            }
            case "dot" -> {
                var b = asVec(stack.pop());
                var a = asVec(stack.pop());
                yield new Value.Scalar(a.x()*b.x() + a.y()*b.y() + a.z()*b.z());
            }
            case "cross" -> {
                var b = asVec(stack.pop());
                var a = asVec(stack.pop());
                yield new Value.Vec3Val(
                    a.y()*b.z() - a.z()*b.y(),
                    a.z()*b.x() - a.x()*b.z(),
                    a.x()*b.y() - a.y()*b.x());
            }
            case "dist" -> {
                var b = asVec(stack.pop());
                var a = asVec(stack.pop());
                double dx = a.x()-b.x(), dy = a.y()-b.y(), dz = a.z()-b.z();
                yield new Value.Scalar(Math.sqrt(dx*dx + dy*dy + dz*dz));
            }
            case "yaw" -> {
                var v = asVec(stack.pop());
                double yaw = Math.toDegrees(Math.atan2(v.x(), -v.z()));
                yield new Value.Scalar((yaw + 360.0) % 360.0);
            }
            case "pitch" -> {
                var v = asVec(stack.pop());
                double h = Math.sqrt(v.x()*v.x() + v.z()*v.z());
                yield new Value.Scalar(Math.toDegrees(Math.atan2(-v.y(), h)));
            }
            default -> Value.Scalar.ZERO;
        };
    }

    /** 向量参与的逐分量运算(决策 §五):标量广播;v×v、v÷v、标量÷v、% ^ 无良定义 → lenient 零(校验期报错)。
     *  Componentwise ops with a vector operand (decisions §五): scalar broadcast; vec*vec, vec/vec,
     *  scalar/vec, % and ^ are undefined → lenient zero (flagged at validate). */
    private static Value applyVectorOp(char op, Value av, Value bv) {
        boolean aVec = av instanceof Value.Vec3Val, bVec = bv instanceof Value.Vec3Val;
        var a = asVec(av);
        var b = asVec(bv);
        double sa = asScalar(av), sb = asScalar(bv);
        return switch (op) {
            case '+' -> aVec && bVec ? new Value.Vec3Val(a.x()+b.x(), a.y()+b.y(), a.z()+b.z())
                     : aVec ? new Value.Vec3Val(a.x()+sb, a.y()+sb, a.z()+sb)
                     : new Value.Vec3Val(b.x()+sa, b.y()+sa, b.z()+sa);
            case '-' -> aVec && bVec ? new Value.Vec3Val(a.x()-b.x(), a.y()-b.y(), a.z()-b.z())
                     : aVec ? new Value.Vec3Val(a.x()-sb, a.y()-sb, a.z()-sb)
                     : new Value.Vec3Val(sa-b.x(), sa-b.y(), sa-b.z());
            case '*' -> !bVec ? new Value.Vec3Val(a.x()*sb, a.y()*sb, a.z()*sb)
                     : !aVec ? new Value.Vec3Val(sa*b.x(), sa*b.y(), sa*b.z())
                     : Value.Vec3Val.ZERO; // v×v 无良定义 / undefined
            case '/' -> aVec && !bVec && sb != 0 ? new Value.Vec3Val(a.x()/sb, a.y()/sb, a.z()/sb)
                     : Value.Vec3Val.ZERO;  // v÷v、标量÷v 无良定义 / undefined
            default -> Value.Vec3Val.ZERO;  // % ^ 对向量无良定义 / undefined
        };
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

    /** 是否为二元运算符(含双字符比较/逻辑)。 / Is a binary operator (incl. two-char comparison/logical). */
    private static boolean isBinaryOperator(String text) {
        if (text.length() == 1 && "+-*/%^<>".indexOf(text.charAt(0)) >= 0) return true;
        return text.equals("==") || text.equals("!=") || text.equals("<=") || text.equals(">=")
            || text.equals("&&") || text.equals("||");
    }

    /** 统一优先级(决策文档 §五):|| < && < 比较 < +- < * / % < ^ < 一元 !。
     *  Unified precedence (decisions §五): || < && < comparison < +- < * / % < ^ < unary !. */
    private static int precedenceOf(Object op) {
        if (op instanceof Character c) {
            return switch (c) { case '+','-' -> 4; case '*','/','%' -> 5; case '^' -> 6; case '<','>' -> 3; default -> 0; };
        }
        if (op instanceof OpToken ot) {
            return switch (ot.op()) {
                case "||" -> 1;
                case "&&" -> 2;
                case "==", "!=", "<=", ">=" -> 3;
                case "!" -> 7;
                default -> 0;
            };
        }
        return 0;
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
        final String originalFormula = formula;
        if (formula == null) {
            return new ScriptParseResult(
                List.of(), List.of(""), List.of(), List.of(), true, "",
                null, Set.of(), List.of());
        }
        // Normalize line endings: \r\n → \n, strip standalone \r
        formula = formula.replace("\r\n", "\n").replace("\r", "");
        if (formula.isEmpty()) {
            return new ScriptParseResult(
                List.of(), List.of(""), List.of(), List.of(), true, originalFormula,
                null, Set.of(), List.of());
        }
        // 向下兼容检测：无换行、无 =、无 @output、无控制流语法 → 旧版单行表达式
        boolean hasNewline = formula.indexOf('\n') >= 0;
        boolean hasAssignment = formula.indexOf('=') >= 0;
        boolean hasOutputMarker = formula.contains("@output");
        boolean hasAstSyntax = false;
        for (Token t : tokenize(formula)) {
            if (isAstTrigger(t)) { hasAstSyntax = true; break; }
        }
        if (!hasNewline && !hasAssignment && !hasOutputMarker && !hasAstSyntax) {
            // Legacy single-expression mode
            List<String> vars = extractVariables(formula);
            List<Object> rpn;
            try { rpn = compile(formula); } catch (Exception e) { LOGGER.debug("Legacy expression compile failed", e); rpn = List.of(0.0); }
            return new ScriptParseResult(
                vars, List.of(""), List.of(rpn), List.of(), true, originalFormula,
                null, Set.of(), List.of());
        }

        // 新脚本模式：逐行解析
        String[] rawLines = formula.split("\n", -1);
        // Join line continuations: lines ending with \ merge with the next line
        // joinedSrc 记录每个合并行对应的首个源行号(诊断定位用)
        var joined = new java.util.ArrayList<String>();
        var joinedSrc = new java.util.ArrayList<Integer>();
        StringBuilder continuation = new StringBuilder();
        int contSrc = -1;
        for (int li = 0; li < rawLines.length; li++) {
            String l = rawLines[li];
            String trimmed = l.trim();
            if (trimmed.endsWith("\\")) {
                // Line continuation: strip trailing \ and append to buffer
                if (contSrc < 0) contSrc = li;
                continuation.append(trimmed.substring(0, trimmed.length() - 1).trim());
                continuation.append(' ');
            } else if (continuation.length() > 0) {
                // Previous line had continuation: merge
                continuation.append(trimmed);
                joined.add(continuation.toString());
                joinedSrc.add(Math.max(0, contSrc));
                continuation.setLength(0);
                contSrc = -1;
            } else {
                joined.add(trimmed);
                joinedSrc.add(li);
            }
        }
        if (continuation.length() > 0) {
            // Last line had continuation but no follow-up
            joined.add(continuation.toString().trim());
            joinedSrc.add(Math.max(0, contSrc));
        }
        // Filter: skip empty and comment-only lines
        var lines = new java.util.ArrayList<String>();
        var lineSources = new java.util.ArrayList<Integer>();
        for (int li = 0; li < joined.size(); li++) {
            String trimmed = joined.get(li).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) continue;
            lines.add(trimmed);
            lineSources.add(joinedSrc.get(li));
        }

        // ── AST 模式检测:控制流关键字/大括号/swizzle/向量函数 → 语句解析器(刀3)
        // AST mode detection: control-flow keywords / braces / swizzle / vector fns → statement parser (knife 3)
        for (String l : lines) {
            for (Token t : tokenize(l)) {
                if (isAstTrigger(t)) {
                    return parseScriptAst(lines, lineSources, originalFormula);
                }
            }
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
                } else if (!rest.isEmpty()) {
                    // @output 表达式(刀3,如 @output yaw(aim)):编译为 RPN,标签 = 表达式文本
                    // @output expression (knife 3): compile to RPN, label = expression text
                    try {
                        List<Object> rpn = compile(rest);
                        if (!outputLabels.contains(rest)) {
                            outputLabels.add(rest);
                            outputRpns.add(rpn);
                        }
                    } catch (Exception e) {
                        LOGGER.debug("Output expression compile failed", e);
                    }
                }
                continue;
            }
            // Assignment: varName = expression(跳过 == 的两个 =,避免比较被误判为赋值)
            // Assignment: varName = expression (skip both '=' of == so comparisons aren't mistaken for assignments)
            int eqIdx = -1;
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == '='
                    && (i + 1 >= line.length() || line.charAt(i + 1) != '=')
                    && (i == 0 || line.charAt(i - 1) != '=')) {
                    eqIdx = i; break;
                }
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
            new ArrayList<>(inputVars), outputLabels, outputRpns, assignments, false, originalFormula,
            null, Set.of(), List.of());
    }

    // ==================== AST 模式解析(刀3) ====================

    /** 是否触发 AST 模式:控制流关键字、大括号、swizzle、vec3/向量函数(这些 RPN 路径无法完整表达)。
     *  Whether this token forces AST mode: control-flow keywords, braces, swizzle, vec3/vector fns. */
    private static boolean isAstTrigger(Token t) {
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
    private static ScriptParseResult parseScriptAst(List<String> lines, List<Integer> lineSources, String originalFormula) {
        var srcLines = new ArrayList<SrcLine>();
        for (int i = 0; i < lines.size(); i++) srcLines.add(new SrcLine(lines.get(i), tokenize(lines.get(i)), lineSources.get(i)));
        var out = new AstParseOut();
        parseBlockStatements(new LineCursor(srcLines), out, false);

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

        return new ScriptParseResult(
            new ArrayList<>(inputVars), out.outputLabels, out.outputRpns, List.of(), false, originalFormula,
            out.stmts, vec3Vars, issues);
    }

    /** 解析语句块,直到 RBRACE 或输入结束。topLevel=true 时末行独立表达式计入默认输出候选。
     *  Parse statements until RBRACE or end of input. Only top-level standalone exprs count as default output. */
    private static void parseBlockStatements(LineCursor cur, AstParseOut out, boolean topLevel) {
        while (true) {
            Token t = cur.peek();
            if (t == null || t.type() == TokType.RBRACE) return;
            parseStatement(cur, out, topLevel);
        }
    }

    private static void parseStatement(LineCursor cur, AstParseOut out, boolean topLevel) {
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
        if (t.type() == TokType.KEYWORD) { parseKeywordStmt(cur, out, t); consumeSemicolon(cur); return; }
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

    private static void parseKeywordStmt(LineCursor cur, AstParseOut out, Token kw) {
        switch (kw.text()) {
            case "repeat" -> {
                Token num = cur.next();
                long count = (num != null && num.type() == TokType.NUMBER)
                    ? (long) Double.parseDouble(num.text()) : 1;
                var body = parseBody(cur, out);
                out.stmts.add(new FormulaAst.RepeatStmt(Math.max(0, count), body));
            }
            case "while" -> {
                int srcLine = cur.currentSrcLine();
                var condTokens = parseCond(cur);
                int col = condTokens.isEmpty() ? kw.start() : condTokens.get(0).start();
                var condRpn = compileTokens(condTokens);
                out.rpnSites.add(new AstParseOut.RpnSite(condRpn, srcLine, col));
                var body = parseBody(cur, out);
                out.stmts.add(new FormulaAst.WhileStmt(condRpn, body));
            }
            case "if" -> {
                int srcLine = cur.currentSrcLine();
                var condTokens = parseCond(cur);
                int col = condTokens.isEmpty() ? kw.start() : condTokens.get(0).start();
                var condRpn = compileTokens(condTokens);
                out.rpnSites.add(new AstParseOut.RpnSite(condRpn, srcLine, col));
                var body = parseBody(cur, out);
                List<FormulaAst.Stmt> elseBody = List.of();
                // else 紧跟同一行 / else must follow on the same line
                Token t = cur.peek();
                if (t != null && t.type() == TokType.KEYWORD && t.text().equals("else")) {
                    cur.next();
                    elseBody = parseBody(cur, out);
                }
                out.stmts.add(new FormulaAst.IfStmt(condRpn, body, elseBody));
            }
            case "break" -> out.stmts.add(new FormulaAst.BreakStmt());
            case "continue" -> out.stmts.add(new FormulaAst.ContinueStmt());
            default -> consumeToSemicolon(cur); // 未知关键字 lenient 跳过
        }
    }

    /** 解析循环/分支体:带大括号的块,或无大括号的单语句体(如 if (c) break)。
     *  Parse a loop/branch body: a braced block, or a brace-less single statement (e.g. if (c) break). */
    private static List<FormulaAst.Stmt> parseBody(LineCursor cur, AstParseOut out) {
        Token t = cur.peek();
        if (t == null) return List.of();
        if (t.type() == TokType.LBRACE) {
            cur.next();
            var body = new ArrayList<FormulaAst.Stmt>();
            var nested = new AstParseOut();
            parseBlockStatements(cur, nested, false);
            body.addAll(nested.stmts);
            hoistNested(out, nested);
            Token close = cur.peek();
            if (close != null && close.type() == TokType.RBRACE) cur.next();
            return body;
        }
        // 无大括号:单语句体 / brace-less single-statement body
        var nested = new AstParseOut();
        parseStatement(cur, nested, false);
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

    /** RPN 类型模拟:表达式结果是否为 vec3(保守)。 / RPN type simulation: does the expr yield a vector (conservative). */
    private static boolean rpnYieldsVec3(List<Object> rpn, Set<String> vec3Vars) {
        var stack = new ArrayDeque<Boolean>(); // true = vec3
        for (var tok : rpn) {
            if (tok instanceof Double) { stack.push(false); continue; }
            if (tok instanceof String v) { stack.push(vec3Vars.contains(v)); continue; }
            if (tok instanceof SwizzleToken) { stack.pop(); stack.push(false); continue; } // 分量 = 标量
            if (tok instanceof OpToken ot) {
                if (ot.op().equals("!")) { stack.pop(); stack.push(false); }
                else { stack.pop(); stack.pop(); stack.push(false); }
                continue;
            }
            if (tok instanceof FunctionToken ft) {
                boolean[] args = new boolean[ft.arity()];
                for (int i = ft.arity() - 1; i >= 0; i--) args[i] = stack.pop();
                stack.push(switch (ft.name()) {
                    case "vec3", "normalize", "cross" -> true;
                    default -> false; // length/dot/dist/yaw/pitch 与标量函数 → 标量
                });
                continue;
            }
            char op = (Character) tok;
            boolean b = stack.pop(), a = stack.pop();
            stack.push((a || b) && "+-*/".indexOf(op) >= 0);
        }
        return !stack.isEmpty() && stack.peek();
    }

    // ── 类型感知检查(校验期 ERROR,运行时仍 lenient)/ type-aware checks (editor ERROR, runtime stays lenient) ──

    /** 按决策 §五 检查一个 RPN 的类型歧义:向量对向量、向量比较、向量函数形态。
     *  Checks one RPN per decisions §五: vector-vs-vector ops, vector comparisons, vector fn arg shapes. */
    private static void typeCheckRpn(List<Object> rpn, Set<String> vec3Vars, List<FormulaIssue> issues, int line, int col) {
        var stack = new ArrayDeque<Boolean>(); // true = vec3
        for (var tok : rpn) {
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
        }
    }
}
