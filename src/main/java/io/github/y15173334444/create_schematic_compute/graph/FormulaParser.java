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

    /** Pattern for valid identifiers: a-z, A-Z, 0-9, underscore, starting with letter or underscore */
    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

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

    private static final Map<String, Integer> FUNCTIONS;
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

    /** 解析 formula 返回所有变量名（按出现顺序，跳过函数名）。
     *  变量名支持字母、数字、下划线，首字符必须是字母或下划线。 */
    public static List<String> extractVariables(String formula) {
        var vars = new LinkedHashSet<String>();
        int i = 0;
        while (i < formula.length()) {
            char c = formula.charAt(i);
            if (isIdentStart(c)) {
                int j = i + 1;
                while (j < formula.length() && isIdentPart(formula.charAt(j))) j++;
                String name = formula.substring(i, j);
                if (!FUNCTION_NAMES.contains(name)) {
                    vars.add(name);
                }
                i = j;
            } else { i++; }
        }
        return new ArrayList<>(vars);
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }
    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }

    /** 编译表达式为 RPN token 列表（String=变量名, Double=数字, Character=运算符, FunctionToken=函数调用） */
    public static List<Object> compile(String formula) {
        // 自动将全角括号转为半角 / auto-convert full-width parens to half-width
        formula = formula.replace('（', '(').replace('）', ')');
        var output = new ArrayList<Object>();
        var ops = new ArrayDeque<Object>();
        boolean expectUnary = true;
        int i = 0;
        while (i < formula.length()) {
            char c = formula.charAt(i);
            if (c == ' ') { i++; continue; }
            if (isIdentStart(c)) {
                int j = i + 1;
                while (j < formula.length() && isIdentPart(formula.charAt(j))) j++;
                String name = formula.substring(i, j);
                // 向前跳过空白，检查是否是函数调用
                int k = j;
                while (k < formula.length() && formula.charAt(k) == ' ') k++;
                Integer arity = FUNCTIONS.get(name);
                if (arity != null && k < formula.length() && formula.charAt(k) == '(') {
                    ops.push(new FunctionToken(name, arity));
                    i = k + 1; // 跳过 '('
                    expectUnary = true;
                    continue;
                }
                output.add(name);
                i = j; expectUnary = false; continue;
            }
            if (c >= '0' && c <= '9') {
                int j = i + 1;
                while (j < formula.length() && ((formula.charAt(j) >= '0' && formula.charAt(j) <= '9') || formula.charAt(j) == '.')) j++;
                output.add(Double.parseDouble(formula.substring(i, j)));
                i = j; expectUnary = false; continue;
            }
            if (c == '(') { ops.push('('); i++; expectUnary = true; continue; }
            if (c == ',') {
                while (!ops.isEmpty() && !(ops.peek() instanceof FunctionToken))
                    output.add(ops.pop());
                i++; expectUnary = true; continue;
            }
            if (c == ')') {
                while (!ops.isEmpty() && !(ops.peek() instanceof FunctionToken) && !ops.peek().equals('('))
                    output.add(ops.pop());
                if (!ops.isEmpty()) {
                    Object top = ops.pop();
                    if (top instanceof FunctionToken ft) output.add(ft);
                }
                i++; expectUnary = false; continue;
            }
            if ("+-*/%^".indexOf(c) >= 0) {
                if (expectUnary && (c == '+' || c == '-')) {
                    output.add(0.0); ops.push('-');
                } else {
                    int prec = precedence(c);
                    while (!ops.isEmpty() && ops.peek() instanceof Character op
                           && !op.equals('(') && precedence(op) >= prec)
                        output.add(ops.pop());
                    ops.push(c);
                }
                i++; expectUnary = true; continue;
            }
            i++; // skip unknown chars
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
            try { rpn = compile(formula); } catch (Exception e) { rpn = List.of(0.0); }
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
