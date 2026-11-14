package io.github.y15173334444.create_schematic_compute.graph;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * R4 性能验收基准（§4.7：弹道 20ms 预算实测 + GC 压力对比，三轮中位）。
 * 四个面对比弹道真脚本的单次求值成本：
 *
 * <ul>
 * <li>{@code java}        —— Java 解释器面（exec + 逐输出 evaluateValue，生产 Java 链全成本）；</li>
 * <li>{@code native-exec} —— R3a 逐节点面（JNI exec + 字节 carrier；execIn 每求值新建 = 生产 resolveNativeExec 形态）；</li>
 * <li>{@code native-b1}   —— R3b 批量面单员（一次 tickBatch；生产段长的退化形态）；</li>
 * <li>{@code native-b8}   —— R3b 批量面 8 员（段均摊形态，逐员输入微扰避免全同输入误导）。</li>
 * </ul>
 *
 * <p>诚实口径：native 两面把生产路径的**每次求值 Java 侧胶水分配**（execIn /
 * 批量偏移与状态数组 / flatIn+flatOut）计入 alloc/eval——这是 R3a/R3b 形态的真实
 * 每次成本；节点持缓冲（carrierOut / progressOut）按生产复用。deadline/slice = 0
 * （不限），单次完整求值；「20ms 预算」读数 = evals/20ms 换算。三轮各自独立测量
 * 窗，报中位。</p>
 *
 * <p>编译/运行（脱离 gradle；graph 纯类 + slf4j + classpath 上的 natives 目录）：</p>
 * <pre>
 * javac -encoding UTF-8 -d out -cp slf4j-api-2.0.9.jar -sourcepath src/main/java bench/java/FormulaFaceBench.java
 * java -Xms1g -cp "out;build/natives;slf4j-api-2.0.9.jar" io.github.y15173334444.create_schematic_compute.graph.FormulaFaceBench 1000 1500
 * </pre>
 *
 * <p>The R4 performance-acceptance bench (§4.7: ballistic under a 20ms tick budget +
 * GC-pressure comparison, three-run median). Four faces on the real ballistic script;
 * native faces include the per-eval Java-side glue allocations their production
 * shapes actually pay. deadline/slice = 0 (unlimited) — one full eval per call,
 * "evals per 20ms" derived. Three independent windows, median reported.</p>
 */
public final class FormulaFaceBench {

    static final com.sun.management.ThreadMXBean TMX =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    record Round(long evals, long ns, long bytes) {}

    /** CBC 金标准同参进料（对拍网 BALLISTIC_CASES[1]）。/ the parity net's mid-range case. */
    static final double[] PINS = {0, 80, 0, 500, 70, -100};
    static final String[] PIN_NAMES = {"mx", "my", "mz", "tx", "ty", "tz"};

    public static void main(String[] args) throws Exception {
        long warmupMs = args.length > 0 ? Long.parseLong(args[0]) : 1000;
        long measureMs = args.length > 1 ? Long.parseLong(args[1]) : 1500;
        System.out.printf("java %s, vm %s%n", System.getProperty("java.version"),
            System.getProperty("java.vm.name"));

        String script = Files.readString(Path.of("docs/examples/ballistic_solver.formula"));
        var parsed = FormulaParser.parseScript(script);
        if (parsed.ast == null || parsed.ast.isEmpty()) {
            System.out.println("PARSE FAILED: " + parsed.issues);
            return;
        }
        int nOut = Math.max(1, parsed.outputRpns.size());

        // ── native 面的 upload 前置（bench 内复制 programFor 的五步，不碰 FormulaEval/
        //    协调器——那些类牵 MC 面；本 bench 只用 graph 纯类）。/ upload here (the five
        //    programFor steps copied in-bench; keeps MC-touching classes out). ──
        if (!FormulaNative.AVAILABLE) {
            System.out.println("formula_native unavailable — java face only");
            javaFace(parsed, nOut, warmupMs, measureMs);
            return;
        }
        long session = FormulaNative.sessionCreate();
        var enc = FormulaTokEncoder.encode(parsed);
        int progId = FormulaNative.programUpload(session, enc.bytes());
        if (progId < 0) {
            System.out.println("upload failed rc=" + progId);
            return;
        }
        int slots = enc.slotNames().size();
        int[] pinSlots = new int[PIN_NAMES.length];
        for (int vi = 0; vi < pinSlots.length; vi++) {
            pinSlots[vi] = enc.slotNames().indexOf(PIN_NAMES[vi]);
        }
        System.out.printf("slots=%d nOut=%d progId=%d%n", slots, nOut, progId);

        javaFace(parsed, nOut, warmupMs, measureMs);
        nativeExecFace(session, progId, slots, pinSlots, nOut, warmupMs, measureMs);
        nativeBatchFace(session, progId, slots, pinSlots, nOut, 1, warmupMs, measureMs);
        nativeBatchFace(session, progId, slots, pinSlots, nOut, 8, warmupMs, measureMs);
        FormulaNative.sessionDestroy(session);
    }

    // ── Java 面：exec + 逐输出 evaluateValue（生产 Java 链全成本；env 每求值重建 = 真实节点形态） ──

    static void javaFace(FormulaParser.ScriptParseResult parsed, int nOut, long warmupMs, long measureMs) {
        Map<String, Value> template = new HashMap<>();
        for (int i = 0; i < PIN_NAMES.length; i++) {
            template.put(PIN_NAMES[i], new Value.Scalar(PINS[i]));
        }
        runJava(parsed, template, nOut, warmupMs);
        Round[] rounds = new Round[3];
        for (int r = 0; r < 3; r++) {
            rounds[r] = runJava(parsed, template, nOut, measureMs);
        }
        report("java", rounds);
    }

    static Round runJava(FormulaParser.ScriptParseResult parsed, Map<String, Value> template,
                         int nOut, long ms) {
        long tid = Thread.currentThread().threadId();
        long b0 = TMX.getThreadAllocatedBytes(tid);
        long end = System.nanoTime() + ms * 1_000_000L;
        long evals = 0, ns = 0;
        while (System.nanoTime() < end) {
            long t0 = System.nanoTime();
            Map<String, Value> env = new HashMap<>(template);
            FormulaInterpreter.exec(parsed.ast, env);
            for (int oi = 0; oi < nOut; oi++) {
                if (oi < parsed.outputRpns.size()) {
                    FormulaParser.asScalar(
                        FormulaParser.evaluateValue(parsed.outputRpns.get(oi), env));
                }
            }
            long t1 = System.nanoTime();
            ns += t1 - t0;
            evals++;
        }
        return new Round(evals, ns, TMX.getThreadAllocatedBytes(tid) - b0);
    }

    // ── R3a 逐节点面：JNI exec；execIn 每求值新建（生产 resolveNativeExec 形态） ──

    static void nativeExecFace(long session, int progId, int slots, int[] pinSlots, int nOut,
                               long warmupMs, long measureMs) {
        double[] out = new double[nOut];
        byte[] carrierOut = new byte[FormulaNative.carrierBufferFor(slots)];
        float[] progress = new float[1];

        runExec(session, progId, slots, pinSlots, out, carrierOut, progress, warmupMs);
        Round[] rounds = new Round[3];
        for (int r = 0; r < 3; r++) {
            rounds[r] = runExec(session, progId, slots, pinSlots, out, carrierOut, progress, measureMs);
        }
        report("native-exec", rounds);
    }

    static Round runExec(long session, int progId, int slots, int[] pinSlots, double[] out,
                         byte[] carrierOut, float[] progress, long ms) {
        long tid = Thread.currentThread().threadId();
        long b0 = TMX.getThreadAllocatedBytes(tid);
        long end = System.nanoTime() + ms * 1_000_000L;
        long evals = 0, ns = 0;
        while (System.nanoTime() < end) {
            long t0 = System.nanoTime();
            double[] execIn = new double[slots]; // 生产形态：resolveNativeExec 每求值新建
            applyPins(execIn, pinSlots, PINS);
            FormulaNative.exec(session, progId, execIn, null, 0, 0, false,
                out, carrierOut, progress);
            long t1 = System.nanoTime();
            ns += t1 - t0;
            evals++;
        }
        return new Round(evals, ns, TMX.getThreadAllocatedBytes(tid) - b0);
    }

    // ── R3b 批量面：一次 tickBatch（count=1 退化形态 / count=8 段均摊形态） ──

    static void nativeBatchFace(long session, int progId, int slots, int[] pinSlots, int nOut,
                                int count, long warmupMs, long measureMs) {
        int[] nodeSlots = new int[count];
        for (int i = 0; i < count; i++) {
            nodeSlots[i] = FormulaNative.allocNodeSlot(session);
        }
        // 8 员形态逐员输入微扰（生产段 = 同脚本不同输入的常见形态；全同输入会误导为纯数组搬运）。
        double[][] pinSets = new double[count][];
        for (int i = 0; i < count; i++) {
            pinSets[i] = PINS.clone();
            pinSets[i][5] = PINS[5] + i * 0.5;
        }

        runBatch(session, progId, slots, pinSlots, nOut, nodeSlots, pinSets, warmupMs);
        Round[] rounds = new Round[3];
        for (int r = 0; r < 3; r++) {
            rounds[r] = runBatch(session, progId, slots, pinSlots, nOut, nodeSlots, pinSets, measureMs);
        }
        report("native-b" + count, rounds);
    }

    static Round runBatch(long session, int progId, int slots, int[] pinSlots, int nOut,
                          int[] nodeSlots, double[][] pinSets, long ms) {
        int count = nodeSlots.length;
        long tid = Thread.currentThread().threadId();
        long b0 = TMX.getThreadAllocatedBytes(tid);
        long end = System.nanoTime() + ms * 1_000_000L;
        long evals = 0, ns = 0;
        while (System.nanoTime() < end) {
            long t0 = System.nanoTime();
            // 生产 executeBatch 的每次冲刷分配形态（数组逐次新建）逐字计入。
            int[] programIds = new int[count];
            int[] refreshFlags = new int[count];
            int[] inOffsets = new int[count + 1];
            int[] outOffsets = new int[count + 1];
            int inTotal = 0, outTotal = 0;
            for (int i = 0; i < count; i++) {
                programIds[i] = progId;
                inTotal += slots;
                inOffsets[i + 1] = inTotal;
                outTotal += nOut;
                outOffsets[i + 1] = outTotal;
            }
            double[] flatIn = new double[inTotal];
            double[] flatOut = new double[outTotal];
            for (int i = 0; i < count; i++) {
                int base = inOffsets[i];
                for (int vi = 0; vi < pinSlots.length; vi++) {
                    int s = pinSlots[vi];
                    if (s >= 0 && s < slots) {
                        flatIn[base + s] = pinSets[i][vi];
                    }
                }
            }
            int[] statuses = new int[count];
            float[] progress = new float[count];
            long[] elapsedNs = new long[count];
            FormulaNative.tickBatch(session, count, nodeSlots, programIds, refreshFlags,
                flatIn, inOffsets, 0, 0, flatOut, outOffsets, statuses, progress, elapsedNs);
            long t1 = System.nanoTime();
            ns += t1 - t0;
            evals += count;
        }
        return new Round(evals, ns, TMX.getThreadAllocatedBytes(tid) - b0);
    }

    static void applyPins(double[] execIn, int[] pinSlots, double[] pins) {
        for (int vi = 0; vi < pinSlots.length && vi < pins.length; vi++) {
            int s = pinSlots[vi];
            if (s >= 0 && s < execIn.length) {
                execIn[s] = pins[vi];
            }
        }
    }

    /** 三轮中位报告：ns/eval、alloc B/eval、evals/20ms（tick 预算口径换算）。 */
    static void report(String name, Round[] rounds) {
        Round[] sorted = rounds.clone();
        java.util.Arrays.sort(sorted,
            java.util.Comparator.comparingLong(r -> r.ns * 1_000_000L / Math.max(1, r.evals)));
        Round med = sorted[1];
        double perEvalNs = (double) med.ns / Math.max(1, med.evals);
        double perEvalB = (double) med.bytes / Math.max(1, med.evals);
        System.out.printf("%-12s ns/eval=%9.1f  alloc/eval=%8.1f B  evals/20ms=%7.1f  rounds=[%s]%n",
            name, perEvalNs, perEvalB, 20_000_000.0 / perEvalNs,
            rounds[0].evals + "/" + rounds[1].evals + "/" + rounds[2].evals);
    }
}
