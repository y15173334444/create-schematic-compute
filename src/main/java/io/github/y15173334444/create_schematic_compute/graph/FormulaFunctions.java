package io.github.y15173334444.create_schematic_compute.graph;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.FunctionToken;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.OpToken;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser.SwizzleToken;

import static io.github.y15173334444.create_schematic_compute.graph.FormulaParser.asScalar;
import static io.github.y15173334444.create_schematic_compute.graph.FormulaParser.asVec;

/**
 * 公式内建函数与命名常量表（拓扑审查 §三 P5「函数表」相位，自 FormulaParser 逐字迁出）：
 * FUNCTIONS 元数据表、CONSTANTS 值表、向量函数名集合与标量/向量求值体（applyFunction /
 * applyVectorFunction），外加 RPN 类型模拟 rpnYieldsVec3（保守 vec3 推断与校验共用）。
 * 表与求值同居一文件——K14（扩充内建函数集）的扩容落点。FormulaParser.FUNCTIONS 是同一张
 * 不可变表在家族门面上的稳定别名。静态函数包，不建继承体系。
 * <p>Formula builtin-function and named-constant tables (the topology-review §三 P5
 * "function table" phase, relocated verbatim from FormulaParser): the FUNCTIONS metadata
 * table, the CONSTANTS value table, the vector-function name set with their scalar/vector
 * evaluation bodies (applyFunction / applyVectorFunction), plus the RPN type simulation
 * rpnYieldsVec3 (shared by the conservative vec3 inference and validation). Tables and
 * evaluation share one file — the K14 expansion point. FormulaParser.FUNCTIONS is the
 * facade's stable alias to the same unmodifiable map. Static function pack, no
 * inheritance hierarchy.</p>
 */
final class FormulaFunctions {

    private FormulaFunctions() {}

    static final Map<String, Integer> FUNCTIONS;
    static final Set<String> FUNCTION_NAMES;
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
    static final Map<String, Double> CONSTANTS;
    static final Set<String> CONSTANT_NAMES;
    static {
        var cm = new java.util.LinkedHashMap<String, Double>();
        cm.put("PI", Math.PI);
        cm.put("E", Math.E);
        CONSTANTS = Collections.unmodifiableMap(cm);
        CONSTANT_NAMES = CONSTANTS.keySet();
    }

    /** Returns the set of constant names (for autocomplete / validation to enumerate). */
    static Set<String> constantNames() { return CONSTANT_NAMES; }

    /** Returns true if the name is a known constant name. */
    static boolean isConstantName(String name) { return CONSTANT_NAMES.contains(name); }

    /** 向量函数名集合。 / Vector function names. */
    static final Set<String> VECTOR_FN_NAMES =
        Set.of("length", "normalize", "dot", "cross", "dist", "yaw", "pitch");

    /** 向量函数求值。yaw/pitch 逐字对齐 DIRECTION 节点(GraphEvaluator.java:577-579)。
     *  Vector function evaluation. yaw/pitch mirror the DIRECTION node exactly. */
    static Value applyVectorFunction(FunctionToken ft, ArrayDeque<Value> stack) {
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

    /** 执行单个函数调用。角度约定与 GraphEvaluator 中 trig 节点保持一致：
     *  sin/cos/tan 输入为度（内部转弧度）；asin/acos/atan2 输出为度（内部转弧度计算再转回）；
     *  sinh/cosh 直接使用原值。 */
    static double applyFunction(String name, double[] args) {
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

    /** RPN 类型模拟:表达式结果是否为 vec3(保守)。 / RPN type simulation: does the expr yield a vector (conservative). */
    static boolean rpnYieldsVec3(List<Object> rpn, Set<String> vec3Vars) {
        var stack = new ArrayDeque<Boolean>(); // true = vec3
        for (var tok : rpn) {
            // 栈下溢 = 表达式未输完(编辑框逐击键实时校验的中途状态)→ 保守按标量处理,绝不抛异常
            // Underflow = incomplete expression (mid-keystroke validation) — treat conservatively as scalar, never throw
            try {
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
            } catch (java.util.NoSuchElementException underflow) {
                return false;
            }
        }
        return !stack.isEmpty() && stack.peek();
    }
}
