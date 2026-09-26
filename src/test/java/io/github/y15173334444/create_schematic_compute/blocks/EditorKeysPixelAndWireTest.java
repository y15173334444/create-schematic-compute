package io.github.y15173334444.create_schematic_compute.blocks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 键位系统扩容回归测试：「删除连线」动作与像素编辑器动作（工具/网格/笔刷大小）
 * 进出厂默认键与旧硬编码逐项一致；feedKey 正确派发；冲突规则覆盖新动作；
 * 重绑后派发跟随新键、重置后回到出厂。
 * Regression: the Delete-Wire action and the pixel-editor actions enter the binding
 * system with factory defaults identical to the old hardcodes.
 */
class EditorKeysPixelAndWireTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void resetToDefaults() {
        EditorKeys.setConfigPathForTest(tempDir.resolve("config.properties").toString());
        EditorKeys.reloadForTest();
    }

    private static final long T = 1000L;

    @Test
    @DisplayName("factory defaults replicate the old hardcodes / 出厂默认键与旧硬编码一致")
    void factoryDefaultsMatchOldHardcodes() {
        assertEquals(EditorKeys.Action.DELETE_WIRE, EditorKeys.feedKey(87, 0, T));          // W
        assertEquals(EditorKeys.Action.PIXEL_BRUSH, EditorKeys.feedKey(66, 0, T));          // B
        assertEquals(EditorKeys.Action.PIXEL_ERASER, EditorKeys.feedKey(69, 0, T));         // E
        assertEquals(EditorKeys.Action.PIXEL_FILL, EditorKeys.feedKey(70, 0, T));           // F
        assertEquals(EditorKeys.Action.PIXEL_EYEDROPPER, EditorKeys.feedKey(73, 0, T));     // I
        assertEquals(EditorKeys.Action.PIXEL_LINE, EditorKeys.feedKey(76, 0, T));           // L
        assertEquals(EditorKeys.Action.PIXEL_RECT, EditorKeys.feedKey(82, 0, T));           // R
        assertEquals(EditorKeys.Action.PIXEL_HAND, EditorKeys.feedKey(72, 0, T));           // H
        assertEquals(EditorKeys.Action.PIXEL_GRID, EditorKeys.feedKey(71, 0, T));           // G
        assertEquals(EditorKeys.Action.PIXEL_BRUSH_SMALLER, EditorKeys.feedKey(91, 0, T));  // [ (GLFW 91, not the old broken VK 219)
        assertEquals(EditorKeys.Action.PIXEL_BRUSH_BIGGER, EditorKeys.feedKey(93, 0, T));  // ] (GLFW 93, not the old broken VK 221)
        assertFalse(EditorKeys.bufferActive());
    }

    @Test
    @DisplayName("conflict rules cover the new actions / 冲突规则覆盖新动作")
    void conflictRulesCoverNewActions() {
        // X 已被 DELETE_NODE 占用 → 像素画笔不得绑到 X
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.PIXEL_BRUSH,
            List.of(new EditorKeys.Step(88, 0))), "X is taken by DELETE_NODE");
        // E 已被 PIXEL_ERASER 占用 → 同样拒绝
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.PIXEL_BRUSH,
            List.of(new EditorKeys.Step(69, 0))), "E is taken by PIXEL_ERASER");
        // 空闲键 V 可绑定，派发随新键走
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.PIXEL_BRUSH,
            List.of(new EditorKeys.Step(86, 0))), "V is free");
        assertEquals(EditorKeys.Action.PIXEL_BRUSH, EditorKeys.feedKey(86, 0, T));
        assertNull(EditorKeys.feedKey(66, 0, T), "the old B binding is gone after rebinding");
        // 重置回出厂 → B 恢复
        EditorKeys.resetToDefault(EditorKeys.Action.PIXEL_BRUSH);
        assertEquals(EditorKeys.Action.PIXEL_BRUSH, EditorKeys.feedKey(66, 0, T));
    }

    @Test
    @DisplayName("delete-wire binding survives a config roundtrip / 删除连线绑定随配置往返")
    void deleteWireBindingPersists() {
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.DELETE_WIRE,
            List.of(new EditorKeys.Step(79, 0)))); // O
        EditorKeys.reloadForTest(); // 从已保存的配置文件重读 / re-read the saved config
        assertEquals(EditorKeys.Action.DELETE_WIRE, EditorKeys.feedKey(79, 0, T));
        assertNull(EditorKeys.feedKey(87, 0, T), "the default W binding is replaced");
    }
}
