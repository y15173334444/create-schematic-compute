package io.github.y15173334444.create_schematic_compute.blocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编辑区引脚连接暗化的契约：暗态由**配置色推导**（RGB×0.55，alpha/色相不动），
 * 不再是硬编码暗色——自定义主题下连接态跟随主题色。
 * The connected-dim contract for edit-panel band pins: the dim state is derived from the
 * configured colour (RGB×0.55, hue/alpha kept), never a hardcoded dark constant.
 */
class PinConnectedDimTest {

    @Test
    @DisplayName("暗化 = 配置色明暗变换（RGB×0.55、alpha 不动）/ dim scales the configured colour, alpha kept")
    void dimScalesConfiguredColorKeepingAlpha() {
        // 默认 input 金 0xFFD4A017：212/160/23 × 0.55 = 116/88/12
        assertEquals(0xFF74580C, NodeRenderer.dimColor(0xFFD4A017));
        assertEquals(0x8074580C, NodeRenderer.dimColor(0x80D4A017), "alpha passes through untouched");
    }

    @Test
    @DisplayName("暗化恒不亮于原色；纯黑不变 / dim never brightens; black stays black")
    void dimNeverBrightens() {
        int src = 0xFF3FB8A8; // 音频青 / audio teal
        int dim = NodeRenderer.dimColor(src);
        assertTrue(((dim >> 16) & 0xFF) <= ((src >> 16) & 0xFF));
        assertTrue(((dim >> 8) & 0xFF) <= ((src >> 8) & 0xFF));
        assertTrue((dim & 0xFF) <= (src & 0xFF));
        assertEquals(0xFF000000, NodeRenderer.dimColor(0xFF000000));
    }
}
