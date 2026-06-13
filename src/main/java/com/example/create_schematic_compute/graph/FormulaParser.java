package com.example.create_schematic_compute.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 公式解析器 — 将数学表达式编译为可执行的后缀表达式（RPN）
 * 支持：多字母变量名（连续大写字母如 ABD）、+ - * / % ^ ( )、数字、一元负号
 */
public class FormulaParser {

    /** 解析 formula 返回所有变量名（按出现顺序，连续字母为一个变量，大小写均可） */
    public static List<String> extractVariables(String formula) {
        var vars = new LinkedHashSet<String>();
        int i = 0;
        while (i < formula.length()) {
            char c = formula.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                int j = i;
                while (j < formula.length() && ((formula.charAt(j) >= 'A' && formula.charAt(j) <= 'Z')
                    || (formula.charAt(j) >= 'a' && formula.charAt(j) <= 'z'))) j++;
                vars.add(formula.substring(i, j));
                i = j;
            } else { i++; }
        }
        return new ArrayList<>(vars);
    }

    /** 编译表达式为 RPN token 列表（String=变量名, Double=数字, Character=运算符） */
    public static List<Object> compile(String formula) {
        var output = new ArrayList<Object>();
        var ops = new ArrayDeque<Character>();
        boolean expectUnary = true;
        int i = 0;
        while (i < formula.length()) {
            char c = formula.charAt(i);
            if (c == ' ') { i++; continue; }
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                int j = i + 1;
                while (j < formula.length() && ((formula.charAt(j) >= 'A' && formula.charAt(j) <= 'Z')
                    || (formula.charAt(j) >= 'a' && formula.charAt(j) <= 'z'))) j++;
                output.add(formula.substring(i, j));
                i = j; expectUnary = false; continue;
            }
            if (c >= '0' && c <= '9') {
                int j = i + 1;
                while (j < formula.length() && ((formula.charAt(j) >= '0' && formula.charAt(j) <= '9') || formula.charAt(j) == '.')) j++;
                output.add(Double.parseDouble(formula.substring(i, j)));
                i = j; expectUnary = false; continue;
            }
            if (c == '(') { ops.push('('); i++; expectUnary = true; continue; }
            if (c == ')') {
                while (!ops.isEmpty() && ops.peek() != '(') output.add(ops.pop());
                if (!ops.isEmpty() && ops.peek() == '(') ops.pop();
                i++; expectUnary = false; continue;
            }
            if ("+-*/%^".indexOf(c) >= 0) {
                if (expectUnary && (c == '+' || c == '-')) {
                    output.add(0.0); ops.push('-');
                } else {
                    int prec = precedence(c);
                    while (!ops.isEmpty() && ops.peek() != '(' && precedence(ops.peek()) >= prec)
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
