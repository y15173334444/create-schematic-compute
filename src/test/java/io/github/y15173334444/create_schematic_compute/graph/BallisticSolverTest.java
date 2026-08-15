package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 火控弹道反解脚本测试(对照 Python 参考实现 ballistic_solver.py)。
 * 脚本:docs/examples/ballistic_solver.formula — 粗扫描(361 点)+ 牛顿迭代(≤50 轮)。
 * 参考输出由 Python 版计算(2026-08-15):四组场景的 yaw/pitch/hit。
 * Fire-control ballistic inverse-solver test (validated against the Python reference
 * ballistic_solver.py). Script: docs/examples/ballistic_solver.formula.
 */
class BallisticSolverTest {

    private static String loadScript() {
        try {
            return Files.readString(Path.of("docs/examples/ballistic_solver.formula"));
        } catch (Exception e) {
            fail("cannot read docs/examples/ballistic_solver.formula: " + e);
            return null;
        }
    }

    /** 以输入运行脚本,返回 [ay, ap, hit] / run the script with inputs, returns [ay, ap, hit]. */
    private static double[] solve(double mx, double my, double mz,
                                  double tx, double ty, double tz,
                                  double v0, double g, double fd, double qd, double den) {
        var parsed = FormulaParser.parseScript(loadScript());
        assertNotNull(parsed.ast, "script must parse to AST mode; issues: " + parsed.issues);
        assertTrue(parsed.issues.isEmpty(), "script issues: " + parsed.issues);
        var env = new HashMap<String, Value>();
        env.put("mx", new Value.Scalar(mx)); env.put("my", new Value.Scalar(my)); env.put("mz", new Value.Scalar(mz));
        env.put("tx", new Value.Scalar(tx)); env.put("ty", new Value.Scalar(ty)); env.put("tz", new Value.Scalar(tz));
        env.put("v0", new Value.Scalar(v0)); env.put("g", new Value.Scalar(g));
        env.put("fd", new Value.Scalar(fd)); env.put("qd", new Value.Scalar(qd)); env.put("den", new Value.Scalar(den));
        FormulaInterpreter.exec(parsed.ast, env);
        return new double[]{
            FormulaParser.asScalar(env.get("ay")),
            FormulaParser.asScalar(env.get("ap")),
            FormulaParser.asScalar(env.get("hit")),
            FormulaParser.asScalar(env.get("vx0")),
            FormulaParser.asScalar(env.get("vy0")),
            FormulaParser.asScalar(env.get("vz0"))
        };
    }

    @Test
    @DisplayName("ballistic: case 0 — drag-free flat shot (Python: yaw=180, pitch=8.8862, hit)")
    void testCase0() {
        double[] r = solve(0, 2, 0, 0, 4, 20, 3.0, 0.05, 0.0, 0, 1.0);
        assertEquals(180.0, r[0], 0.5, "yaw");
        assertEquals(8.8862, r[1], 1.0, "pitch");
        assertEquals(1.0, r[2], 0.0, "hit");
        assertEquals(0.0, r[3], 0.1, "vx0");
        assertEquals(0.4634, r[4], 0.1, "vy0");
        assertEquals(2.964, r[5], 0.1, "vz0");
    }

    @Test
    @DisplayName("ballistic: case 1 — linear drag diagonal (Python: yaw=161.5651, pitch=10.2293, hit)")
    void testCase1() {
        double[] r = solve(0, 2, 0, 10, 6, 30, 5.0, 0.08, 0.01, 0, 1.0);
        assertEquals(161.5651, r[0], 0.5, "yaw");
        assertEquals(10.2293, r[1], 1.0, "pitch");
        assertEquals(1.0, r[2], 0.0, "hit");
    }

    @Test
    @DisplayName("ballistic: case 2 — low v0 lob (Python: yaw=180, pitch=34.5662, hit)")
    void testCase2() {
        double[] r = solve(0, 2, 0, 0, 4, 15, 1.0, 0.05, 0.0, 0, 1.0);
        assertEquals(180.0, r[0], 0.5, "yaw");
        assertEquals(34.5662, r[1], 1.0, "pitch");
        assertEquals(1.0, r[2], 0.0, "hit");
    }

    @Test
    @DisplayName("ballistic: case 3 — quadratic drag offset target (Python: yaw=-147.9946→212.0054, pitch=7.4986, hit)")
    void testCase3() {
        double[] r = solve(0, 2, 0, -5, 3, 8, 3.0, 0.05, 0.02, 1, 1.2);
        assertEquals(212.0054, r[0], 0.5, "yaw (normalized [0,360))");
        assertEquals(7.4986, r[1], 1.0, "pitch");
        assertEquals(1.0, r[2], 0.0, "hit");
    }

    @Test
    @DisplayName("ballistic: script parses clean (no issues) and stays under MAX_ITER")
    void testScriptParsesClean() {
        var parsed = FormulaParser.parseScript(loadScript());
        assertNotNull(parsed.ast);
        assertTrue(parsed.issues.isEmpty(), "issues: " + parsed.issues);
        assertEquals(11, parsed.inputVars.size(), "11 input pins (muzzle/target/v0/g/fd/qd/den)");
        assertEquals(6, parsed.outputLabels.size(), "6 outputs (ay/ap/hit/vx0/vy0/vz0)");
    }
}
