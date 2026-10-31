package io.github.y15173334444.create_schematic_compute.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.github.y15173334444.create_schematic_compute.graph.FormulaAstParser.parseScriptAst;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaFunctions.CONSTANTS;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaFunctions.VECTOR_FN_NAMES;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaFunctions.applyFunction;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaFunctions.applyVectorFunction;

/**
 * 公式解析器 — 家族门面 + 表达式栈机锚类（拓扑审查 §三 P5 按相拆分后的锚类；旧扁平 RPN
 * 前端已随 2026-10-31 双前端退役删除）。
 * 数学表达式支持：多字母变量名（连续字母如 ABD）、+ - * / % ^ ( )、数字、一元负号、
 *       三角函数：sin(x) cos(x) tan(x) asin(x) acos(x) atan2(y,x) sinh(x) cosh(x)
 *
 * 脚本语法（全部脚本统一走 AST 前端；无控制流脚本随双前端退役升格）：
 *   -- 注释行        : 以 "--" 开头的行
 *   赋值语句         : varName = expression
 *   输出声明         : @output varName
 *   默认回退输出     : 若未声明 @output，最后一行独立表达式为输出
 *
 * 相位分家（拓扑 P5，物理拆分）：词法在 FormulaLexer（tokenize /
 * sanitizeFullwidth / isValidIdentifier）；AST 前端在 FormulaAstParser
 * （刀3 语句树解析）；内建函数与常量表在 FormulaFunctions（K14 扩容落点，FUNCTIONS 字段
 * 是本类上的稳定别名）；校验在 FormulaValidator。本类留守：词汇类型（TokType / Token /
 * ScriptParseResult / FormulaIssue / RPN token 族 / MAX_PARSE_DEPTH 族）、表达式编译与
 * Value 栈机（compile / compileTokens / evaluateValue——AST 表达式叶子与 DEBUG 信号共用）
 * 与 parseScript 的降级兜底，以及全部公开签名的冻结门面（逐个委托，存量引用零改动）。
 * 双前端退役（2026-10-31）：旧单行表达式模式 + 逐行赋值/输出解析 + 模式路由已删除，
 * 全部脚本升格 AST（见登记册『AST 与 RPN 双前端并行』条目）。
 * <p>Formula parser — family facade plus the expression-stack-machine anchor (the
 * topology-review §三 P5 phase split anchor; the legacy flat-RPN front-end retired
 * with the 2026-10-31 dual-frontend retirement). The phases now live next door:
 * lexing in FormulaLexer (tokenize / sanitizeFullwidth / isValidIdentifier),
 * the AST front-end in FormulaAstParser (knife-3 statement parsing), the builtin
 * function &amp; constant tables in FormulaFunctions (the K14 expansion point; the
 * FUNCTIONS field here is a stable alias to the same table), and validation in
 * FormulaValidator. This class keeps the vocabulary types (TokType / Token /
 * ScriptParseResult / FormulaIssue / the RPN token family / the MAX_PARSE_DEPTH
 * family), the expression compiler and Value stack machine (compile / compileTokens /
 * evaluateValue — shared by AST expression leaves and the DEBUG signal path) plus
 * parseScript's degradation backstop, and frozen facades for every public signature
 * (item-by-item delegation, existing references untouched). Dual-frontend retirement
 * (2026-10-31): the legacy single-expression mode, per-line assignment/output parsing
 * and mode routing are gone — every script is promoted to AST (open-issues entry
 * "AST and RPN dual frontends").</p>
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

    private static final Logger LOGGER = LoggerFactory.getLogger(FormulaParser.class);

    /** RPN token representing a function call. */
    record FunctionToken(String name, int arity) {}

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

    /** parseScript() 的结果 — 包含解析后的结构化信息，可供评估器和 UI 使用。
     *  双前端退役（2026-10-31 拍板）：旧扁平 RPN 前端（逐行赋值/输出解析）随批删除，
     *  {@code Assignment}/{@code assignments}/{@code isLegacy} 一并退场——脚本统一走
     *  AST 前端（表达式叶子仍是共享 Value 栈机的 RPN）。
     *  The dual-frontend retirement (2026-10-31 ruling): the flat-RPN per-line frontend
     *  is gone with its Assignment/assignments/isLegacy shape — every script parses
     *  through the AST frontend (expression leaves stay RPN on the shared stack machine). */
    public static class ScriptParseResult {
        public final List<String> inputVars;       // 有序、去重的外部输入变量名
        public final List<String> outputLabels;    // @output 声明的输出名（空字符串 = 默认输出）
        public final List<List<Object>> outputRpns;// 每个输出对应的编译后 RPN 表达式
        public final String sourceFormula;         // 解析来源字符串，用于检测陈旧缓存
        public final List<FormulaAst.Stmt> ast;    // 语句树；null = 深度守卫降级/空脚本形态（输出恒 0）
        public final Set<String> vec3Vars;         // 保守类型推断——任何处赋值为向量的变量名(刀4 引脚展开用)
        public final List<FormulaIssue> issues;    // AST 解析期的类型感知问题(validate() 合并展示)

        public ScriptParseResult(List<String> inputVars, List<String> outputLabels,
                                 List<List<Object>> outputRpns, String sourceFormula,
                                 List<FormulaAst.Stmt> ast, Set<String> vec3Vars, List<FormulaIssue> issues) {
            this.inputVars = inputVars;
            this.outputLabels = outputLabels;
            this.outputRpns = outputRpns;
            this.sourceFormula = sourceFormula;
            this.ast = ast;
            this.vec3Vars = vec3Vars;
            this.issues = issues;
        }
    }

    // ── 冻结门面（拓扑审查 §三 P5 按相拆分）：下列公开签名的实现已迁至相位类（词法 →
    //    FormulaLexer、函数/常量表 → FormulaFunctions、校验 → FormulaValidator、AST 前端 →
    //    FormulaAstParser），签名与语义逐字不变，存量引用零改动。
    // ── Frozen facades (topology-review §三 P5 phase split): the implementations behind
    //    the public signatures below moved to the phase classes (lexer → FormulaLexer,
    //    function & constant tables → FormulaFunctions, validation → FormulaValidator,
    //    AST front-end → FormulaAstParser); signatures and semantics are verbatim-identical,
    //    existing references untouched. ──

    /** 内建函数名 → 元数表。存储在 FormulaFunctions（拓扑 P5 函数表相位，K14 扩容落点）；
     *  本字段是同一张不可变表在家族门面上的稳定别名。/ Builtin function name → arity.
     *  Storage lives in FormulaFunctions (the topology-P5 function-table phase, the K14
     *  expansion point); this field is the facade's stable alias to the same unmodifiable map. */
    public static final Map<String, Integer> FUNCTIONS = FormulaFunctions.FUNCTIONS;

    /** 常量名集合（委托，表在 FormulaFunctions）/ constant-name set (delegates; table in FormulaFunctions). */
    public static Set<String> constantNames() { return FormulaFunctions.constantNames(); }

    /** 是否命名常量（委托，表在 FormulaFunctions）/ known-constant test (delegates; table in FormulaFunctions). */
    public static boolean isConstantName(String name) { return FormulaFunctions.isConstantName(name); }

    /** 全角→半角归一（委托，正文在 FormulaLexer）/ full-width → half-width normalisation (delegates; body in FormulaLexer). */
    public static String sanitizeFullwidth(String s) { return FormulaLexer.sanitizeFullwidth(s); }

    /** 词法扫描（委托，正文在 FormulaLexer）/ lexical scan (delegates; body in FormulaLexer). */
    public static List<Token> tokenize(String src) { return FormulaLexer.tokenize(src); }

    // ──────────────────────────── Validation ────────────────────────────

    /** Issue severity / 问题严重程度 */
    public enum Severity { ERROR, WARN }

    /** A validation issue with source location and message.
     *  校验问题，包含源位置和消息。 */
    public record FormulaIssue(int line, int col, int length, Severity severity, String message) {}

    /** 公式校验（委托，正文在 FormulaValidator）/ formula validation (delegates; body in FormulaValidator). */
    public static List<FormulaIssue> validate(String src) { return FormulaValidator.validate(src); }

    /** 公式校验（复用预解析产物，§13 去重增量重载）：{@code pre} 非空且其
     *  {@code sourceFormula} 与本方法对源做括号 sanitize 后的文本一致时，跳过内部
     *  完整重解析、直接复用该产物（校验路径与解析路径共享同一份产物）；不一致或缺席
     *  回退内部解析——两种形态行为等价（parseScript 确定性，产物相同则徽章面相同）。
     *  Formula validation reusing a pre-parsed artifact (the §13 dedup overload): when
     *  {@code pre} is non-null and its {@code sourceFormula} equals the sanitized source,
     *  the internal full re-parse is skipped and the artifact is shared between the
     *  validation and parse paths; a mismatch or absence falls back to the internal parse.
     *  Both forms are behaviourally identical (parseScript is deterministic, so identical
     *  artifacts yield identical badge surfaces). */
    public static List<FormulaIssue> validate(String src, ScriptParseResult pre) {
        return FormulaValidator.validate(src, pre);
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
     * 预算兜底 shed 信号(K6,2026-10-31):表达式栈机采样到本 tick 预算 deadline 已过
     * ({@code FormulaCompute.opsDeadlineExhausted()}),唯一抛点在 {@link #evaluateValue}。
     * Exception 族——各求值入口按「输出保持、瞬态重试」口径捕获,绝不以 Error 逃逸:
     * 解释器转 fromDeadline 挂起(carrier 保住),FORMULA 输出阶段与 DEBUG 曲线/试算按
     * 瞬态豁免(输出保持/不误判坏公式)。
     * Budget-backstop shed signal (K6, 2026-10-31): the expression machine sampled the tick's
     * budget deadline as passed ({@code FormulaCompute.opsDeadlineExhausted()}); the only throw
     * site is {@link #evaluateValue}. Exception family — every eval entry catches it as "hold
     * outputs, transient retry", it never escapes as an Error: the interpreter converts it to a
     * fromDeadline suspend (carrier preserved), while the FORMULA output phase and the DEBUG
     * curve/probe take the transient exemption (hold outputs / never misread as a broken formula).
     */
    public static final class DeadlineShedException extends RuntimeException {
        public DeadlineShedException() { super(null, null, false, false); }
    }

    /**
     * 统一 Value 栈机(单一求值引擎,决策文档 §五)。旧标量 RPN 与刀 3 的向量/控制流共用同一引擎。
     * K6:本地 op 计数器每 256 token 查一次 tick deadline({@link FormulaCompute#deadlineExhausted()})
     * ——计数器是**调用内局部**的:微小表达式(≤256 ops)永不采样、零共享状态,巨表达式
     * (闭式巨脚本)在首个采样点即被 shed。共享计数器形态曾让测试类间静态状态泄漏误伤
     * (BallisticSolverTest/FormulaParserSyntaxTest 首爆),故取局部口径。
     * Unified Value stack machine (single evaluation engine, decisions doc §五). Legacy scalar RPN
     * and knife-3 vectors share this one engine.
     * K6: a call-local op counter samples the tick deadline
     * ({@link FormulaCompute#deadlineExhausted()}) every 256 tokens — the counter is **local to
     * the call**: tiny expressions (≤256 ops) are never sampled with zero shared state, while a
     * monster expression (the closed-form giant) is shed at its first sample. A shared-counter
     * shape leaked static state across test classes (BallisticSolverTest /
     * FormulaParserSyntaxTest exploded first), hence the call-local design.
     */
    public static Value evaluateValue(List<Object> rpn, Map<String, Value> env) {
        var stack = new ArrayDeque<Value>();
        int ops = 0;
        for (var tok : rpn) {
            if ((++ops & 0xFF) == 0 && FormulaCompute.deadlineExhausted()) throw new DeadlineShedException();
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

    /** 取向量形态;标量 lenient 广播为 (s, 0, 0)(校验期会拦形态错误)。
     *  Extract vector shape; scalars broadcast leniently to (s, 0, 0) (validate flags shape errors). */
    public static Value.Vec3Val asVec(Value v) {
        return v instanceof Value.Vec3Val vv ? vv : new Value.Vec3Val(asScalar(v), 0.0, 0.0);
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

    /** 标识符合法性（委托，正文在 FormulaLexer）/ identifier validity (delegates; body in FormulaLexer). */
    public static boolean isValidIdentifier(String s) { return FormulaLexer.isValidIdentifier(s); }

    /**
     * AST 语句嵌套深度上限（P1-6）。parseStatement/parseKeywordStmt/parseBody/
     * parseBlockStatements 的互相递归以往无守卫：恶意/坏档脚本能以千层嵌套 if 打出
     * StackOverflowError —— Error 不被 eval 分支的 {@code catch (Exception)} 拦截，
     * 且 SO 发生在 {@code cachedScript} 赋值前 → 下个 tick 重解析再 SO，崩溃循环。
     * 该上限同时约束解释器递归（AST 只能由本解析器产生，AST 深度 ≤ 此上限）。
     * AST statement nesting limit (P1-6). The mutual recursion among parseStatement/
     * parseKeywordStmt/parseBody/parseBlockStatements used to be unguarded: a malicious
     * or corrupt-save script with thousands of nested ifs produced a StackOverflowError —
     * an Error the eval branch's {@code catch (Exception)} cannot intercept, and since the
     * SO fired before {@code cachedScript} was assigned, the next tick re-parsed and SO'd
     * again (crash loop). This limit also bounds the interpreter's recursion (ASTs are only
     * produced by this parser, so AST depth ≤ this limit).
     */
    public static final int MAX_PARSE_DEPTH = 128;

    /**
     * 受控深度超限异常（Exception 族，P1-6）：解析嵌套超过 {@link #MAX_PARSE_DEPTH}
     * 时由语句递归链抛出。{@link #parseScript} 在 AST 模式入口捕获并降级为
     * 「输出恒 0 + ERROR issue」的解析结果 —— 求值、SET_FORMULA op、GraphMigration
     * 世界加载三条暴露路径都由此收敛为公式错误，绝不以 StackOverflowError 逃逸。
     * Controlled depth-exceeded exception (Exception family, P1-6): thrown by the
     * statement recursion chain when nesting exceeds {@link #MAX_PARSE_DEPTH}.
     * {@link #parseScript} catches it at the AST-mode entry and degrades to a
     * "pinned-to-0 output + ERROR issue" parse result — the three exposure paths
     * (server eval, SET_FORMULA op, GraphMigration world load) all converge into a
     * formula error, never escaping as a StackOverflowError.
     */
    public static final class FormulaTooDeepException extends RuntimeException {
        public FormulaTooDeepException(int depth) {
            super("script nesting exceeds " + MAX_PARSE_DEPTH + " levels (depth " + depth + ")");
        }
    }

    /**
     * 解析 formula 文本，返回结构化的脚本解析结果。
     * 统一前端（双前端退役 2026-10-31）：全部脚本走 AST 语句解析器（表达式叶子仍是共享
     * Value 栈机的 RPN）；旧版单行表达式与逐行 RPN 前端已随批删除，无控制流脚本升格 AST。
     * <p>嵌套超过 {@link #MAX_PARSE_DEPTH} 时不抛出：降级为输出恒 0 的结果并附
     * ERROR issue（编辑器徽章可见）—— 解析层兜住求值/op/世界加载三条路径（P1-6）。
     * Parses formula text into a structured result. Unified frontend (dual-frontend
     * retirement, 2026-10-31): every script parses through the AST statement parser
     * (expression leaves stay RPN on the shared stack machine); the legacy
     * single-expression and per-line RPN frontends are gone — non-control-flow scripts
     * are promoted to AST.
     * <p>Does not throw on nesting beyond {@link #MAX_PARSE_DEPTH}: degrades to a
     * pinned-to-0 result with an ERROR issue (visible as the editor badge) — the parse
     * layer backstops all three paths: eval / op / world load (P1-6).
     */
    public static ScriptParseResult parseScript(String formula) {
        final String originalFormula = formula;
        if (formula == null) {
            return new ScriptParseResult(
                List.of(), List.of(""), List.of(), "",
                null, Set.of(), List.of());
        }
        // Normalize line endings: \r\n → \n, strip standalone \r
        formula = formula.replace("\r\n", "\n").replace("\r", "");
        if (formula.isEmpty()) {
            return new ScriptParseResult(
                List.of(), List.of(""), List.of(), originalFormula,
                null, Set.of(), List.of());
        }
        // 逐行预处理：续行合并 + 注释过滤（供统一 AST 前端消费）。
        // Line preprocessing: continuation joining + comment filtering (feeds the unified
        // AST frontend).
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

        // ── 统一前端（双前端退役 2026-10-31）：全部脚本走 AST 语句解析器——无控制流脚本
        //    随批升格（表达式叶子仍是共享 Value 栈机的 RPN），旧版单行/逐行 RPN 前端已删除。
        // ── Unified frontend (dual-frontend retirement, 2026-10-31): every script goes
        //    through the AST statement parser — non-control-flow scripts are promoted
        //    (expression leaves stay RPN on the shared stack machine); the legacy
        //    single-expression / per-line RPN frontends are gone.
        // P1-6:嵌套超限 → 受控异常 → 降级为「输出恒 0 + ERROR issue」。
        // 三条暴露路径（求值/SET_FORMULA op/GraphMigration 世界加载）都只经
        // 本方法拿解析结果，降级即全部兜住，绝不让 StackOverflowError 逃逸。
        // P1-6: nesting over the limit → controlled exception → degrade to a
        // pinned-to-0 result with an ERROR issue. All three exposure paths (eval /
        // SET_FORMULA op / GraphMigration world load) obtain parse results only
        // through this method, so the degradation backstops them all; a
        // StackOverflowError never escapes.
        try {
            return parseScriptAst(lines, lineSources, originalFormula);
        } catch (FormulaTooDeepException e) {
            LOGGER.debug("AST parse abandoned: {}", e.getMessage());
            return new ScriptParseResult(
                List.of(), List.of(""), List.of(java.util.List.of(0.0)),
                originalFormula, null, Set.of(),
                List.of(new FormulaIssue(0, 0, 0, Severity.ERROR,
                    "脚本嵌套超过 " + MAX_PARSE_DEPTH + " 层，已放弃解析，输出恒 0"
                    + " / script nesting exceeds the " + MAX_PARSE_DEPTH
                    + "-level limit; parse abandoned, outputs pinned to 0")));
        }
    }

}
