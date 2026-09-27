package io.github.y15173334444.create_schematic_compute.blocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 变速器值盒文本规则的单元测试（代理态显示）。
 * Unit tests for the transmission's value-box text rule (proxy display).
 *
 * <p>纯函数 + 注入后缀：本测试不查语言表、不碰客户端渲染，只钉死"盒里那串字"的构成
 * —— 官方手动态口径（绝对值、无后缀）与代理态（绝对值 + 空格 + 后缀）。</p>
 * <p>Pure function with an injected suffix: this test never touches the lang table or the
 * client renderer, it only pins the shape of the string — the official manual form
 * (magnitude, no suffix) and the proxied form (magnitude + space + suffix).</p>
 */
class TransmissionScrollDisplayTest {

    @Test
    @DisplayName("manual mode keeps the official shape: bare magnitude, never a sign")
    void testManualShape() {
        assertEquals("64", TransmissionScrollDisplay.valueBoxText(64, false, "(proxy)"));
        assertEquals("64", TransmissionScrollDisplay.valueBoxText(-64, false, "(proxy)"),
            "the sign is output-face-relative and must never leak into the box");
        assertEquals("0", TransmissionScrollDisplay.valueBoxText(0, false, "(proxy)"));
    }

    @Test
    @DisplayName("proxied mode appends the localised suffix after the magnitude")
    void testProxiedShape() {
        assertEquals("64 (proxy)", TransmissionScrollDisplay.valueBoxText(64, true, "(proxy)"));
        assertEquals("64 (代理)", TransmissionScrollDisplay.valueBoxText(64, true, "(代理)"));
        assertEquals("64 (代理)", TransmissionScrollDisplay.valueBoxText(-64, true, "(代理)"),
            "proxied mode takes the magnitude too — only the suffix marks the state");
        assertEquals("0 (代理)", TransmissionScrollDisplay.valueBoxText(0, true, "(代理)"),
            "a graph-driven zero is still a proxied reading (e.g. an unwired TX_OUT), not manual");
    }

    @Test
    @DisplayName("the suffix comes from the caller verbatim (no trimming, no case munging)")
    void testSuffixIsVerbatim() {
        assertEquals("128 node-driven", TransmissionScrollDisplay.valueBoxText(128, true, "node-driven"));
        assertEquals("128 ", TransmissionScrollDisplay.valueBoxText(128, true, ""),
            "an empty suffix still yields the separator only — the caller owns the wording");
    }

    @Test
    @DisplayName("Integer.MIN_VALUE is not a counterexample to the no-sign-leak rule")
    void testIntegerMinValueKeepsTheRule() {
        // Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE（负数）——朴素实现会把一个负号
        // 漏进盒子，恰好撕开本类唯一的硬规则。不可达不等于可以没有断言。
        // Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE (still negative): the naive form
        // leaks a minus into the box and tears the class's one hard rule. Unreachable is not a
        // reason to leave it unpinned.
        assertEquals("2147483648", TransmissionScrollDisplay.valueBoxText(Integer.MIN_VALUE, false, "(proxy)"));
        assertEquals("2147483648 (代理)", TransmissionScrollDisplay.valueBoxText(Integer.MIN_VALUE, true, "(代理)"));
        assertEquals("2147483647", TransmissionScrollDisplay.valueBoxText(Integer.MAX_VALUE, false, "(proxy)"),
            "the positive extreme must stay untouched by the long-based abs");
    }
}
