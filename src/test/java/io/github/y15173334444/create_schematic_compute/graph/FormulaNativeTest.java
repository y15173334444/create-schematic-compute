package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Rust 后端端到端测试（R2）：Java 编码器产字节码 → native 上传/求值，走真 JNI 面
 * （sessionCreate/programUpload/exec/programRelease）。对拍口径与 R1 桥测一致：
 * **逐位**。native 缺席（陌生平台/无 cargo 构建/加载失败/abi 不匹配）时
 * {@link FormulaNative#AVAILABLE} 为 false → assumeTrue 整类跳过，套件保持绿——
 * Java 解释器兜底路径由其他对拍测试网覆盖，本类只锁 native 面。
 * Rust-backend end-to-end tests (R2): the Java encoder produces bytecode, the
 * native side uploads/evaluates over the real JNI surface. All comparisons are
 * bit-exact; the whole class skips via assumeTrue when no native library is
 * available, keeping the suite green everywhere else.
 */
class FormulaNativeTest {

    private static String loadScript() {
        try {
            return Files.readString(Path.of("docs/examples/ballistic_solver.formula"));
        } catch (Exception e) {
            fail("cannot read docs/examples/ballistic_solver.formula: " + e);
            return null;
        }
    }

    /** native 缺席 → 跳过（每测一次闸,与 @BeforeAll 口径双保险）。 / skip gate when no native lib. */
    private static void assumeNative() {
        assumeTrue(FormulaNative.AVAILABLE,
            "formula_native cdylib unavailable — skipping native e2e (Java fallback unaffected)");
    }

    /** 弹道 500 格中程输入（与 R1 桥夹具同参）。 / ballistic mid-range input pins. */
    private static Map<String, Double> ballisticPins() {
        Map<String, Double> pins = new HashMap<>();
        pins.put("mx", 0.0);
        pins.put("my", 80.0);
        pins.put("mz", 0.0);
        pins.put("tx", 500.0);
        pins.put("ty", 70.0);
        pins.put("tz", -100.0);
        return pins;
    }

    /** 输入按名查槽（槽位序 = 源码首现序 ≠ 引脚序——契约的坑点即此）。 / map pins into slots by name. */
    private static double[] inputsBySlot(List<String> slots, Map<String, Double> pins) {
        double[] inputs = new double[slots.size()];
        for (Map.Entry<String, Double> e : pins.entrySet()) {
            int i = slots.indexOf(e.getKey());
            assertTrue(i >= 0, "input pin " + e.getKey() + " missing from slot table");
            inputs[i] = e.getValue();
        }
        return inputs;
    }

    @Test
    @DisplayName("scalarLoop: 100k 迭代终值与 Java 链逐位一致(acc=4999950.0, i=100000.0)")
    void scalarLoopNativeBitExact() {
        assumeNative();
        String src = "i = 0\nacc = 0\nrepeat 100000 {\n    acc = acc + i * 0.001\n    i = i + 1\n}";
        var parsed = FormulaParser.parseScript(src);
        var enc = FormulaTokEncoder.encode(parsed);
        long session = FormulaNative.sessionCreate();
        try {
            int pid = FormulaNative.programUpload(session, enc.bytes());
            assertTrue(pid >= 0, "programUpload failed: " + pid);
            double[] outputs = new double[enc.slotNames().size()];
            int rc = FormulaNative.exec(session, pid, new double[0], null, 0L, outputs, null);
            assertEquals(0, rc, "unbudgeted exec must be Done");
            int acc = enc.slotNames().indexOf("acc");
            int i = enc.slotNames().indexOf("i");
            assertEquals(4999950.0, outputs[acc], "acc final");
            assertEquals(100000.0, outputs[i], "i final");
            // 位模式复核(逐位口径的显式形态)。 / bit-pattern re-check (the explicit bit-exact form).
            assertEquals(Double.doubleToLongBits(4999950.0), Double.doubleToLongBits(outputs[acc]), "acc bits");
            assertEquals(Double.doubleToLongBits(100000.0), Double.doubleToLongBits(outputs[i]), "i bits");
        } finally {
            FormulaNative.sessionDestroy(session);
        }
    }

    @Test
    @DisplayName("ballistic: 弹道真脚本全槽位终值与 Java FormulaInterpreter.exec 逐位一致")
    void ballisticNativeMatchesJavaChain() {
        assumeNative();
        var parsed = FormulaParser.parseScript(loadScript());
        assertNotNull(parsed.ast, "script must parse to AST mode; issues: " + parsed.issues);
        assertTrue(parsed.issues.isEmpty(), "script issues: " + parsed.issues);
        var enc = FormulaTokEncoder.encode(parsed);
        // 期望值 = 同一次 parse 的 AST 直接跑 Java 解释器(不限预算),不硬编码。
        // Expected values come from running the same parsed AST through the Java
        // interpreter unbudgeted — nothing hard-coded.
        var env = new HashMap<String, Value>();
        ballisticPins().forEach((name, v) -> env.put(name, new Value.Scalar(v)));
        FormulaInterpreter.exec(parsed.ast, env);

        long session = FormulaNative.sessionCreate();
        try {
            int pid = FormulaNative.programUpload(session, enc.bytes());
            assertTrue(pid >= 0, "programUpload failed: " + pid);
            List<String> slots = enc.slotNames();
            double[] outputs = new double[slots.size()];
            int rc = FormulaNative.exec(session, pid, inputsBySlot(slots, ballisticPins()), null, 0L, outputs, null);
            assertEquals(0, rc, "unbudgeted exec must be Done");
            for (int s = 0; s < slots.size(); s++) {
                // 未赋值/未运行的槽:env 缺项 asScalar(null)=0.0,与 native lenient 0 同口径。
                double want = FormulaParser.asScalar(env.get(slots.get(s)));
                assertEquals(Double.doubleToLongBits(want), Double.doubleToLongBits(outputs[s]),
                    "slot " + s + " (" + slots.get(s) + ") bit-exact vs Java chain");
            }
        } finally {
            FormulaNative.sessionDestroy(session);
        }
    }

    @Test
    @DisplayName("ballistic: 2ms slice 反复挂起续算(carrier 跨界)终值与不限预算逐位一致")
    void ballisticSlicedResumeBitExact() {
        assumeNative();
        var parsed = FormulaParser.parseScript(loadScript());
        assertNotNull(parsed.ast, "script must parse to AST mode; issues: " + parsed.issues);
        var enc = FormulaTokEncoder.encode(parsed);
        List<String> slots = enc.slotNames();
        double[] inputs = inputsBySlot(slots, ballisticPins());
        long session = FormulaNative.sessionCreate();
        try {
            int pid = FormulaNative.programUpload(session, enc.bytes());
            assertTrue(pid >= 0, "programUpload failed: " + pid);
            // 不限预算基准。 / unbudgeted baseline.
            double[] base = new double[slots.size()];
            assertEquals(0, FormulaNative.exec(session, pid, inputs, null, 0L, base, null), "baseline exec");
            // 2ms slice 反复:每次把 carrierOut 头部读出的有效字节传回 carrierIn 直到 Done。
            // exec 只回状态码,写入长度自描述于 CSCC 头(carrierLength);缓冲按最坏形态给足。
            double[] sliced = new double[slots.size()];
            byte[] carrierBuf = new byte[slots.size() * 32 + 4096];
            byte[] carrierIn = null;
            int rounds = 0;
            int rc;
            do {
                rounds++;
                assertTrue(rounds < 100_000, "resume not converging");
                rc = FormulaNative.exec(session, pid, inputs, carrierIn, 2_000_000L, sliced, carrierBuf);
                if (rc == 1) {
                    int len = FormulaNative.carrierLength(carrierBuf);
                    assertTrue(len > 0, "suspended carrier header must decode");
                    carrierIn = Arrays.copyOf(carrierBuf, len);
                } else {
                    carrierIn = null;
                }
            } while (rc == 1);
            assertEquals(0, rc, "sliced exec must eventually be Done");
            assertTrue(rounds > 1, "ballistic under 2ms slices must suspend at least once");
            for (int s = 0; s < slots.size(); s++) {
                assertEquals(Double.doubleToLongBits(base[s]), Double.doubleToLongBits(sliced[s]),
                    "slot " + s + " (" + slots.get(s) + ") converges bit-exact through resume");
            }
        } finally {
            FormulaNative.sessionDestroy(session);
        }
    }

    @Test
    @DisplayName("contract: abiVersion=1;programRelease 后 exec 返回负错误码;非法会话/未知 id 均负码")
    void nativeContractNegativePaths() {
        assumeNative();
        assertEquals(FormulaNative.ABI_VERSION, FormulaNative.abiVersion(), "abi lockstep");
        var parsed = FormulaParser.parseScript("i = 0\nacc = 0\nrepeat 100000 {\n    acc = acc + i * 0.001\n    i = i + 1\n}");
        var enc = FormulaTokEncoder.encode(parsed);
        double[] outputs = new double[enc.slotNames().size()];
        // 会话无效 = -1(头文件契约)。 / invalid session = -1.
        assertEquals(-1, FormulaNative.exec(0L, 0, new double[0], null, 0L, outputs, null), "exec session 0");
        assertEquals(-1, FormulaNative.programUpload(0L, enc.bytes()), "upload session 0");
        assertEquals(-1, FormulaNative.programRelease(0L, 0), "release session 0");
        // sessionDestroy(0) = 无操作不炸。 / destroy of a null session is a no-op.
        assertDoesNotThrow(() -> FormulaNative.sessionDestroy(0L));

        long session = FormulaNative.sessionCreate();
        try {
            int pid = FormulaNative.programUpload(session, enc.bytes());
            assertTrue(pid >= 0, "programUpload failed: " + pid);
            // 丢弃路径:release 后 exec 必须负错误码(-2 程序 id 无效)。 / released → negative rc.
            assertEquals(0, FormulaNative.programRelease(session, pid), "release must succeed");
            int rc = FormulaNative.exec(session, pid, new double[0], null, 0L, outputs, null);
            assertEquals(-2, rc, "exec after programRelease must be -2");
            // 未知 id 同口径。 / unknown id, same contract.
            assertEquals(-2, FormulaNative.exec(session, 999, new double[0], null, 0L, outputs, null), "exec unknown id");
        } finally {
            FormulaNative.sessionDestroy(session);
        }
    }
}
