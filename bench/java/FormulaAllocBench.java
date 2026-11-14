import io.github.y15173334444.create_schematic_compute.graph.FormulaInterpreter;
import io.github.y15173334444.create_schematic_compute.graph.FormulaParser;
import io.github.y15173334444.create_schematic_compute.graph.Value;

import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;

/**
 * 现行 Java 解释器（FormulaInterpreter + FormulaParser.evaluateValue 栈机）的
 * 分配率基准。目的：给「公式节点预算池 20ms/tick → 1GB/s 分配」的实测基线，
 * 供 Rust 后端原型对照。脱离模组启动、javac 直跑（公式链是纯 Java，仅依赖 slf4j）。
 *
 * 测量口径：com.sun.management.ThreadMXBean#getThreadAllocatedBytes —— 线程级
 * 分配计数器，不受 GC 影响（计分配总量而非存活量）。
 *
 * 脚本集：
 *  - scalarLoop：标量热循环（弹道模拟类重节点的典型形态）
 *  - vec3Loop ：向量热循环（刀 3 向量语义接入后的形态）
 *  - closed   ：闭式长表达式（无控制流，单次求值）
 *
 * 运行：<java> FormulaAllocBench [warmupMs] [measureMs]
 */
public final class FormulaAllocBench {

    static final com.sun.management.ThreadMXBean TMX =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    record Result(long iterations, long ns, long bytes) {}

    public static void main(String[] args) {
        long warmupMs = args.length > 0 ? Long.parseLong(args[0]) : 1000;
        long measureMs = args.length > 1 ? Long.parseLong(args[1]) : 2000;

        System.out.printf("java version: %s, vm: %s%n",
            System.getProperty("java.version"), System.getProperty("java.vm.name"));

        bench("scalarLoop", """
                i = 0
                acc = 0
                repeat 100000 {
                    acc = acc + i * 0.001
                    i = i + 1
                }
                """, warmupMs, measureMs);
        bench("vec3Loop", """
                p = vec3(0, 0, 0)
                v = vec3(1, 2, 3)
                repeat 100000 {
                    p = p + v * 0.01
                    v = v - vec3(0, 0.001, 0)
                }
                """, warmupMs, measureMs);
        bench("closedForm", """
                y = sin(x) * cos(2*x) + sqrt(abs(x - 0.5)) + log(1 + x*x) ^ 2 \
                    + sin(x*3) * 0.5 + floor(x) + min(x, 1) + max(x, 2)
                """, warmupMs, measureMs);
    }

    static void bench(String name, String script, long warmupMs, long measureMs) {
        var parsed = FormulaParser.parseScript(script);
        if (parsed.ast == null || parsed.ast.isEmpty()) {
            System.out.printf("%-10s PARSE FAILED: %s%n", name, parsed.issues);
            return;
        }
        Map<String, Value> env = new HashMap<>();
        env.put("x", new Value.Scalar(0.5));

        // 预热（JIT 稳态）
        runFor(parsed, env, warmupMs);
        // 测量
        long tid = Thread.currentThread().threadId();
        long b0 = TMX.getThreadAllocatedBytes(tid);
        Result r = runFor(parsed, env, measureMs);
        long b1 = TMX.getThreadAllocatedBytes(tid);
        long bytes = b1 - b0;

        double s = r.ns / 1e9;
        System.out.printf("%-10s iter/s=%,12.0f  ns/iter=%8.1f  alloc/s=%8.1f MB  alloc/iter=%7.1f B%n",
            name, r.iterations / s, (double) r.ns / r.iterations,
            bytes / s / 1048576.0, (double) bytes / r.iterations);
    }

    /** 反复 exec 直至墙钟到期；iterations 按 100k 迭代的整轮 exec 计（scalarLoop/vec3Loop），
     *  闭式脚本按 exec 次数计。返回迭代总数与累计耗时。 */
    static Result runFor(FormulaParser.ScriptParseResult parsed, Map<String, Value> env, long ms) {
        long end = System.nanoTime() + ms * 1_000_000L;
        long iters = 0, ns = 0;
        while (System.nanoTime() < end) {
            long t0 = System.nanoTime();
            FormulaInterpreter.exec(parsed.ast, env);
            long t1 = System.nanoTime();
            ns += t1 - t0;
            iters += parsed == null ? 1 : execIterations(parsed);
        }
        return new Result(iters, ns, 0);
    }

    /** 每次 exec 的「迭代当量」：循环脚本 = repeat 次数；闭式 = 1。
     *  从脚本字面量约定读取（bench 专用，不进主代码）。 */
    static long execIterations(FormulaParser.ScriptParseResult parsed) {
        // 简化：以源码中 repeat 常量为准；无 repeat 视为 1 次
        for (var stmt : parsed.ast) {
            if (stmt instanceof io.github.y15173334444.create_schematic_compute.graph.FormulaAst.RepeatStmt r)
                return r.count();
        }
        return 1;
    }
}
