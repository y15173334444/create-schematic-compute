package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * validate 双份 parseScript 去重批（known-open-issues 条目「validate 双份 parseScript」，
 * 2026-10-31 拍板）的等价锚：{@code validate(src, pre)} 复用同文本预解析产物时，徽章面
 * 必须与单参形**逐条相同**（parseScript 确定性）；产物不匹配时回退内部解析、同样等价。
 * Equivalence anchors for the validate double-parse dedup batch: reusing a matching
 * pre-parsed artifact must yield the exact same issue list as the one-arg form
 * (parseScript is deterministic); a mismatched artifact falls back and stays identical.
 */
class FormulaValidateDedupTest {

    private static final String CLEAN = "x + 1";
    private static final String BROKEN = "sin( PI +";
    private static final String CONTROL_FLOW = "if (x > 0) { @out a = x } else { @out a = 0 - x }";

    @Test
    @DisplayName("复用同文本产物：三种脚本的徽章面与单参形逐条相同 / reuse = identical issues")
    void reuseYieldsIdenticalIssues() {
        for (String src : new String[]{CLEAN, BROKEN, CONTROL_FLOW}) {
            var pre = FormulaParser.parseScript(src);
            List<FormulaParser.FormulaIssue> with = FormulaParser.validate(src, pre);
            List<FormulaParser.FormulaIssue> plain = FormulaParser.validate(src);
            assertEquals(plain, with, "reusing a matching artifact must not change the badge surface: " + src);
        }
    }

    @Test
    @DisplayName("产物不匹配：回退内部解析、徽章面不变 / mismatch falls back, identical issues")
    void mismatchFallsBack() {
        var pre = FormulaParser.parseScript(CLEAN); // 产物来自另一段文本 / artifact of another text
        for (String src : new String[]{BROKEN, CONTROL_FLOW}) {
            assertEquals(FormulaParser.validate(src),
                FormulaParser.validate(src, pre),
                "a stale artifact must fall back to the internal parse: " + src);
        }
    }

    @Test
    @DisplayName("产物携带自身问题（坏脚本）同样被吸收 / the artifact's own issues are absorbed")
    void artifactIssuesAbsorbed() {
        // 坏脚本的解析产物自带 ERROR（如未闭合括号）——复用与重跑都必须把它透出到徽章面。
        // A broken script's parse artifact carries its own ERRORs — both reuse and re-parse
        // must surface them.
        var pre = FormulaParser.parseScript(BROKEN);
        var with = FormulaParser.validate(BROKEN, pre);
        var plain = FormulaParser.validate(BROKEN);
        assertFalse(plain.isEmpty(), "the broken script must surface issues");
        assertEquals(plain, with);
    }

    @Test
    @DisplayName("接线源锚：编辑器解析+校验共享一份产物 / wiring: editor shares one artifact")
    void wiringSharesArtifact() throws Exception {
        String factory = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/NodeEditStateFactory.java"));
        assertTrue(factory.contains("validate(node.formula, initScript)"),
            "the init path must pass the fresh parse to the validator");
        assertTrue(factory.contains("validate(sanitized, res)"),
            "the per-keystroke responder must pass the fresh parse to the validator");
        assertFalse(factory.contains("validate(sanitized)"),
            "the bare double-parse call must be gone");
        String renderer = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/NodeRenderer.java"));
        assertTrue(renderer.contains("validate(n.formula, cached)"),
            "the badge path must reuse the node's cached parse when fresh");
    }
}
