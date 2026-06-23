package com.example.create_schematic_compute.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 公式解析器 — 将数学表达式编译为可执行的后缀表达式（RPN）
 * 支持：多字母变量名（连续字母如 ABD）、+ - * / % ^ ( )、数字、一元负号、
 *       三角函数：sin(x) cos(x) tan(x) asin(x) acos(x) atan2(y,x) sinh(x) cosh(x)
 */
public class FormulaParser {

    /** RPN token representing a function call. */
    private record FunctionToken(String name, int arity) {}

    private static final Map<String, Integer> FUNCTIONS;
    private static final Set<String> FUNCTION_NAMES;
    static {
        var m = new java.util.LinkedHashMap<String, Integer>();
        m.put("sin", 1); m.put("cos", 1); m.put("tan", 1);
        m.put("asin", 1); m.put("acos", 1); m.put("atan2", 2);
        m.put("sinh", 1); m.put("cosh", 1);
        FUNCTIONS = Collections.unmodifiableMap(m);
        FUNCTION_NAMES = FUNCTIONS.keySet();
    }

    /** 解析 formula 返回所有变量名（按出现顺序，跳过函数名） */
    public static List<String> extractVariables(String formula) {
        var vars = new LinkedHashSet<String>();
        int i = 0;
        while (i < formula.length()) {
            char c = formula.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                int j = i;
                while (j < formula.length() && ((formula.charAt(j) >= 'A' && formula.charAt(j) <= 'Z')
                    || (formula.charAt(j) >= 'a' && formula.charAt(j) <= 'z'))) j++;
                String name = formula.substring(i, j);
                if (!FUNCTION_NAMES.contains(name)) {
                    vars.add(name);
                }
                i = j;
            } else { i++; }
        }
        return new ArrayList<>(vars);
    }

    /** 编译表达式为 RPN token 列表（String=变量名, Double=数字, Character=运算符, FunctionToken=函数调用） */
    public static List<Object> compile(String formula) {
        var output = new ArrayList<Object>();
        var ops = new ArrayDeque<Object>();
        boolean expectUnary = true;
        int i = 0;
        while (i < formula.length()) {
            char c = formula.charAt(i);
            if (c == ' ') { i++; continue; }
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                int j = i;
                while (j < formula.length() && ((formula.charAt(j) >= 'A' && formula.charAt(j) <= 'Z')
                    || (formula.charAt(j) >= 'a' && formula.charAt(j) <= 'z'))) j++;
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
            default -> 0;
        };
    }

    private static int precedence(char op) {
        return switch(op) { case '+','-' -> 1; case '*','/','%' -> 2; case '^' -> 3; default -> 0; };
    }

    /** 编译并求值一步完成 */
    public static float eval(String formula, Map<String, Double> vars) {
        try { return (float)evaluate(compile(formula), vars); }
        catch (Exception e) { return 0; }
    }

    /** 带缓存的求值：formula变化频率低，但对同一个公式每tick都会被求值 */
    public static float evalCached(String formula, Map<String, Double> vars, Map<String, java.util.List<Object>> cache) {
        try {
            var rpn = cache.get(formula);
            if (rpn == null) {
                rpn = compile(formula);
                cache.put(formula, rpn);
            }
            return (float)evaluate(rpn, vars);
        } catch (Exception e) { return 0; }
    }
}
