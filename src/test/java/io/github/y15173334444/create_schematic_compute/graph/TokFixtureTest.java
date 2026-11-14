package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tok 夹具的**唯一权威生成/校验面**（契约 v2 起）：夹具脚本住在本类（唯一真相源），
 * 编码器确定性 ⇒ 同脚本字节恒等。
 *
 * <ul>
 * <li>默认（无系统属性）＝<b>校验</b>：重编码四份脚本，与入库 .tokbin 逐字节比对；
 *     golden 终值对 Java StrictMath 链逐位复核。夹具漂移/契约改动未重生成 = 此处红。</li>
 * <li>环境变量 {@code CSC_REGENERATE_TOK_FIXTURES=1} ＝<b>重生成</b>：覆写 .tokbin 与
 *     .golden.txt（契约变更后的显式动作，生成后入库）。用环境变量而非系统属性——
 *     gradle fork 的测试 JVM 不继承命令行 -D，但继承父进程环境。</li>
 * </ul>
 *
 * <p>夹具语义（bridge_cross.rs 的输入口径必须与本表一致）：scalarLoop 无输入、
 * vec3Loop 无输入、closedForm 输入 {@code x=0.5}、ballistic 输入 500 格中程六钉。
 * golden 形态 = 槽位表（name=十进制槽位）+ finals 段（name=hex 位模式），消费方 =
 * bridge_cross.rs。</p>
 *
 * <p>The single authoritative generate/verify face for the tok fixtures (since
 * contract v2): fixture scripts live here (the one source of truth), the encoder is
 * deterministic, so the same script is byte-identical. Default (no environment
 * variable) = verify: re-encode all four scripts and compare byte-for-byte with the
 * checked-in fixtures, golden finals re-checked bit-exact against the Java
 * StrictMath chain. Environment variable {@code CSC_REGENERATE_TOK_FIXTURES=1} =
 * regenerate (the explicit action after a contract change) — an environment variable
 * rather than a system property, because the gradle-forked test JVM does not inherit
 * command-line -D flags but does inherit the parent environment.</p>
 */
class TokFixtureTest {

    private static final Path FIXTURE_DIR =
        Path.of("native/formula-core/tests/fixtures");

    /** 夹具脚本（唯一真相源；脚本变更 ⇒ 显式重生成并入库）。 / fixture scripts (the one source of truth). */
    private static final Map<String, String> SCRIPTS = new LinkedHashMap<>();

    /** 夹具输入（bridge_cross.rs 的口径锚）。 / fixture inputs (bridge_cross.rs's anchors). */
    private static final Map<String, Map<String, Double>> INPUTS = new LinkedHashMap<>();

    static {
        SCRIPTS.put("scalarLoop", """
            i = 0
            acc = 0
            repeat 100000 {
                acc = acc + i * 0.001
                i = i + 1
            }
            acc""");
        INPUTS.put("scalarLoop", Map.of());
        SCRIPTS.put("vec3Loop", """
            p = vec3(0, 0, 0)
            v = vec3(1, 2, 3)
            repeat 1000 {
                p = p + v * 0.001
                v = v + vec3(0.001, -0.002, 0.0005)
            }
            p.x""");
        INPUTS.put("vec3Loop", Map.of());
        SCRIPTS.put("closedForm", """
            y = 2.0
            n = min(1, 2) + floor(2.5)
            m = sqrt(2.0) ^ 2.0
            k = abs(-3.5)
            y + n + m + k + x""");
        INPUTS.put("closedForm", Map.of("x", 0.5));
        // 弹道六钉 = 500 格中程（与 FormulaNativeTest.ballisticPins / bridge_cross.rs 同参）。
        // The ballistic six pins = the 500-block mid-range (same params everywhere).
        INPUTS.put("ballistic", new LinkedHashMap<>(Map.of(
            "mx", 0.0, "my", 80.0, "mz", 0.0, "tx", 500.0, "ty", 70.0, "tz", -100.0)));
    }

    /** 弹道真脚本（docs 侧权威，非本类内联）。 / the ballistic real script (docs-side authority). */
    private static String ballisticScript() {
        try {
            return Files.readString(Path.of("docs/examples/ballistic_solver.formula"));
        } catch (Exception e) {
            throw new IllegalStateException("cannot read docs/examples/ballistic_solver.formula", e);
        }
    }

    private static Map<String, FormulaParser.ScriptParseResult> parseAll() {
        Map<String, FormulaParser.ScriptParseResult> parsed = new LinkedHashMap<>();
        for (var e : SCRIPTS.entrySet()) {
            parsed.put(e.getKey(), FormulaParser.parseScript(e.getValue()));
        }
        parsed.put("ballistic", FormulaParser.parseScript(ballisticScript()));
        return parsed;
    }

    @Test
    void fixturesMatchEncoderAndJavaChain() throws Exception {
        boolean regenerate = "1".equals(System.getenv("CSC_REGENERATE_TOK_FIXTURES"));
        Map<String, FormulaParser.ScriptParseResult> parsed = parseAll();
        for (var e : parsed.entrySet()) {
            String name = e.getKey();
            var script = e.getValue();
            assertNotNull(script.ast, name + " must parse to AST mode");
            var enc = FormulaTokEncoder.encode(script);
            Path tokbin = FIXTURE_DIR.resolve(name + ".tokbin");
            Path golden = FIXTURE_DIR.resolve(name + ".golden.txt");
            if (regenerate) {
                Files.createDirectories(FIXTURE_DIR);
                Files.write(tokbin, enc.bytes());
                Files.writeString(golden, renderGolden(enc, script, name), StandardCharsets.UTF_8);
                System.out.println("regenerated fixture: " + name);
                continue;
            }
            // 校验模式：重编码逐字节一致 + golden 终值对 Java 链逐位复核。
            // Verify mode: re-encode byte-identical + golden finals re-checked bit-exact.
            assertTrue(Files.isRegularFile(tokbin), name + ".tokbin must exist");
            assertArrayEquals(enc.bytes(), Files.readAllBytes(tokbin),
                name + ".tokbin drifted from the encoder — regenerate with CSC_REGENERATE_TOK_FIXTURES=1");
            String goldenText = Files.readString(golden, StandardCharsets.UTF_8);
            var env = goldenEnv(script, name);
            FormulaInterpreter.exec(script.ast, env);
            boolean inFinals = false;
            for (String line : goldenText.split("\n")) {
                if (line.startsWith("#")) {
                    inFinals = line.contains("finals");
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }
                String[] kv = line.split("=", 2);
                String varName = kv[0];
                if (!inFinals) {
                    // 槽位表行：名字必须在槽位表内且序号一致。 / slot-table row: name must match its index.
                    int idx = enc.slotNames().indexOf(varName);
                    assertEquals(Integer.parseInt(kv[1]), idx, name + " slot index of " + varName);
                } else {
                    double want = FormulaParser.asScalar(env.get(varName));
                    long wantBits = Double.doubleToLongBits(want);
                    long gotBits = Long.parseUnsignedLong(kv[1], 16);
                    assertEquals(wantBits, gotBits, name + " final of " + varName + " bit-exact vs Java chain");
                }
            }
        }
    }

    /** golden 环境的输入灌入（ INPUTS 或弹道六钉）。 / seed the golden env with inputs. */
    private static Map<String, Value> goldenEnv(FormulaParser.ScriptParseResult script, String name) {
        Map<String, Value> env = new HashMap<>();
        Map<String, Double> pins = INPUTS.getOrDefault(name, Map.of());
        for (String var : script.inputVars) {
            Double v = pins.get(var);
            if (v != null) {
                env.put(var, new Value.Scalar(v));
            }
        }
        return env;
    }

    private static String renderGolden(FormulaTokEncoder.Encoded enc,
                                       FormulaParser.ScriptParseResult script, String name) {
        var env = goldenEnv(script, name);
        FormulaInterpreter.exec(script.ast, env);
        StringBuilder sb = new StringBuilder("# slot table (name -> slot)\n");
        for (int i = 0; i < enc.slotNames().size(); i++) {
            sb.append(enc.slotNames().get(i)).append('=').append(i).append('\n');
        }
        sb.append("# finals (name=hexbits)\n");
        for (String varName : enc.slotNames()) {
            double v = FormulaParser.asScalar(env.get(varName));
            sb.append(varName).append('=')
                .append(Long.toHexString(Double.doubleToLongBits(v))).append('\n');
        }
        return sb.toString();
    }

    private static void assertTrue(boolean cond, String msg) {
        org.junit.jupiter.api.Assertions.assertTrue(cond, msg);
    }
}
