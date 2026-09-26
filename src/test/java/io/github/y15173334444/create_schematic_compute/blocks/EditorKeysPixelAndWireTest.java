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
 * 键位系统回归测试：「删除连线」动作与像素编辑器动作进出厂默认与旧硬编码逐项一致；
 * 统一序列引擎（键步/鼠标步交错）正确派发；冲突规则覆盖新动作；重绑后派发跟随、
 * 重置后回出厂。
 * Regression: the Delete-Wire action and the pixel-editor actions enter the binding
 * system with factory defaults identical to the old hardcodes; the unified sequence
 * engine (interleaved key/mouse steps) dispatches correctly.
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

    /** 鼠标步构造（按钮 → 负键码）。 / A mouse step (button → the negative keycode). */
    private static EditorKeys.Step mouse(int button) {
        return new EditorKeys.Step(EditorKeys.mouseStepKey(button), 0);
    }

    @Test
    @DisplayName("factory defaults match the old hardcodes; delete-wire ships as the Tab → left-click chord / 出厂默认与旧硬编码一致；删除连线出厂即 Tab → 左键老组合")
    void factoryDefaultsMatchOldHardcodes() {
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
        // 删除连线出厂 = Tab → 左键 老组合：W 退役；Tab 按键触发框选（纯键域）的同时
        // 为组合入混合缓冲，随后的左键完成删线（混合域）——两域独立互不清除。
        // Delete-wire ships as the legacy Tab → left-click chord: W retired; the Tab
        // press fires box-select (key domain) while arming the chord in the mixed
        // domain, and the following left-click completes it — independent domains.
        assertNull(EditorKeys.feedKey(87, 0, T), "W is retired");
        assertEquals(List.of(new EditorKeys.Step(258, 0), mouse(0)), EditorKeys.sequence(EditorKeys.Action.DELETE_WIRE));
        assertEquals(EditorKeys.Action.BOX_SELECT, EditorKeys.feedKey(258, 0, T + 1), "the Tab press still fires box-select");
        assertEquals(EditorKeys.Action.DELETE_WIRE, EditorKeys.feedClick(0, 0, T + 2), "the left-click completes the chord");
        // 平移 = 左键拖动（单鼠标步，拖拽管线消费、引擎不派发）；菜单 = 右键。
        // Pan = left-drag (a single mouse step consumed by the drag pipeline, never
        // dispatched by the engine); menu = right-click.
        assertEquals(List.of(mouse(0)), EditorKeys.sequence(EditorKeys.Action.PAN));
        assertEquals(List.of(mouse(1)), EditorKeys.sequence(EditorKeys.Action.CONTEXT_MENU));
        assertNull(EditorKeys.feedClick(0, 0, T + 3), "PAN's button never engine-fires");
        assertEquals(EditorKeys.Action.CONTEXT_MENU, EditorKeys.feedClick(1, 0, T + 4));
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
    @DisplayName("delete-wire key binding replaces the chord and survives a config roundtrip / 删除连线改键序触发替换组合并随配置往返")
    void deleteWireBindingPersists() {
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.DELETE_WIRE,
            List.of(new EditorKeys.Step(79, 0)))); // O
        // 纯键序整体替换组合：序列不再含鼠标步。 / The pure key sequence replaces the chord wholesale: no mouse steps remain.
        assertFalse(EditorKeys.hasMouseStep(EditorKeys.sequence(EditorKeys.Action.DELETE_WIRE)));
        EditorKeys.reloadForTest(); // 从已保存的配置文件重读 / re-read the saved config
        assertEquals(EditorKeys.Action.DELETE_WIRE, EditorKeys.feedKey(79, 0, T));
        assertNull(EditorKeys.feedKey(87, 0, T), "the default W binding is replaced");
        assertFalse(EditorKeys.hasMouseStep(EditorKeys.sequence(EditorKeys.Action.DELETE_WIRE)), "the key trigger survives the roundtrip");
    }

    // ══════════ 统一序列模型：键鼠交错 / unified sequence model: interleaved key+mouse ══════════

    @Test
    @DisplayName("interleaved key+mouse queues fire per event / 键鼠交错队列逐事件推进触发")
    void interleavedSequences() {
        // K → 左键 → A → 右键：键步/鼠标步任意交错、逐事件推进、末事件完成触发。
        // （Tab → 左键 开头会与出厂删线组合构成同域前缀歧义而被拒——短者先行触发。）
        // K → left-click → A → right-click: key/mouse steps interleave freely, advanced
        // per event and fired by the last one. (Leading with Tab → left-click would
        // prefix-ambiguate the factory wire chord in the same domain — the shorter
        // fires first — and is rightly refused.)
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.UNDO, List.of(
            new EditorKeys.Step(75, 0), mouse(0), new EditorKeys.Step(65, 0), mouse(1))));
        assertNull(EditorKeys.feedKey(75, 0, T), "K advances the queue");
        assertNull(EditorKeys.feedClick(0, 0, T), "the left-click advances the queue");
        assertNull(EditorKeys.feedKey(65, 0, T), "A advances the queue");
        assertEquals(EditorKeys.Action.UNDO, EditorKeys.feedClick(1, 0, T), "the right-click completes it");
        // 混合序列可在任意事件类型上完成：中键 → Y（点中键、按 Y 触发）。
        // A mixed queue may complete on either event type: middle → Y (click, then press).
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.REDO,
            List.of(mouse(2), new EditorKeys.Step(89, 0))));
        assertNull(EditorKeys.feedClick(2, 0, T));
        assertEquals(EditorKeys.Action.REDO, EditorKeys.feedKey(89, 0, T));
    }

    @Test
    @DisplayName("single mouse steps carry the plain bindings; the menu regains its key trigger / 单鼠标步承接原鼠标派发；菜单恢复键序触发")
    void singleMouseSteps() {
        // 中键绑定撤销：裸中键点击即触发。 / Middle bound to undo: a bare middle click fires.
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.UNDO, List.of(mouse(2))));
        assertEquals(EditorKeys.Action.UNDO, EditorKeys.feedClick(2, 0, T));
        // 菜单恢复键序触发（按绑定键在光标处打开），整条替换右键。 / The menu regains its key trigger (opens at the cursor), replacing right-click wholesale.
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.CONTEXT_MENU, List.of(new EditorKeys.Step(79, 0))));
        assertEquals(List.of(new EditorKeys.Step(79, 0)), EditorKeys.sequence(EditorKeys.Action.CONTEXT_MENU));
        assertEquals(EditorKeys.Action.CONTEXT_MENU, EditorKeys.feedKey(79, 0, T));
        // 重置回出厂 → 右键恢复。 / Factory reset → right-click restored.
        EditorKeys.resetToDefault(EditorKeys.Action.CONTEXT_MENU);
        assertEquals(EditorKeys.Action.CONTEXT_MENU, EditorKeys.feedClick(1, 0, T));
    }

    @Test
    @DisplayName("capability + same-domain clash rules / 能力与同域冲突规则")
    void capabilityAndClashes() {
        // 像素动作与框选独占鼠标：鼠标步拒绝。 / Pixel and box-select own the mouse: mouse steps refused.
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.PIXEL_BRUSH, List.of(mouse(0))));
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.BOX_SELECT,
            List.of(new EditorKeys.Step(258, 0), mouse(0))));
        // 同域一钮一动作：裸左键点击已被平移占用，其它动作不得以左键鼠标步开头。
        // Same-domain one-button-one-action: the bare left click is pan's; no other
        // action may lead with a left-click mouse step.
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.UNDO, List.of(mouse(0))), "the bare left click is pan's");
        // 跨域共存：删线 Tab → 左键（混合域）与框选 Tab（纯键域）出厂并存。 / Cross-domain coexistence: the wire chord (mixed) and box-select (key domain) ship together.
        assertTrue(EditorKeys.hasMouseStep(EditorKeys.sequence(EditorKeys.Action.DELETE_WIRE)));
        assertEquals(EditorKeys.Action.BOX_SELECT, EditorKeys.feedKey(258, 0, T));
        assertEquals(EditorKeys.Action.DELETE_WIRE, EditorKeys.feedClick(0, 0, T + 1));
    }

    @Test
    @DisplayName("PAN grab/drag switch, exclusivity and persistence / PAN 抓图/拖动切换、独占与持久化")
    void panHoldConflictsAndPersistence() {
        // 绑 A = 按住抓图（引擎永不派发，手势拦截）。 / Bind A = the hold-grab (never engine-fired, intercepted by the gesture).
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.PAN, List.of(new EditorKeys.Step(65, 0))));
        assertTrue(EditorKeys.panKeyBound());
        assertEquals(-1, EditorKeys.panMouseButton());
        assertNull(EditorKeys.feedKey(65, 0, T));
        assertTrue(EditorKeys.matchesPanHold(65, 0));
        // 改绑鼠标步 = 按钮拖动。 / A mouse step = button-drag.
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.PAN, List.of(mouse(2))));
        assertFalse(EditorKeys.panKeyBound());
        assertEquals(2, EditorKeys.panMouseButton());
        // 抓图键独占（双向）：其它序列任何键步不得用 A；PAN 不得占用其它序列已用的键。
        // Grab-key exclusivity (both ways): no other sequence's key steps may use A;
        // PAN may not take a key another sequence uses.
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.PAN, List.of(new EditorKeys.Step(65, 0))));
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.DELETE_NODE, List.of(new EditorKeys.Step(65, 0))), "A is PAN's grab key");
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.DELETE_NODE,
            List.of(new EditorKeys.Step(75, 0), new EditorKeys.Step(65, 0), mouse(1))), "A dead-ends mid-queue");
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.PAN, List.of(new EditorKeys.Step(88, 0))), "X is DELETE_NODE's key");
        // 持久化：负键码与键步原样往返。 / Persistence: negative keycodes and key steps roundtrip verbatim.
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.PAN, List.of(mouse(2))));
        EditorKeys.reloadForTest();
        assertEquals(List.of(mouse(2)), EditorKeys.sequence(EditorKeys.Action.PAN));
        assertEquals(2, EditorKeys.panMouseButton());
        // 重置回出厂：左键拖动恢复。 / Factory reset: left-drag restored.
        EditorKeys.resetToDefault(EditorKeys.Action.PAN);
        assertEquals(List.of(mouse(0)), EditorKeys.sequence(EditorKeys.Action.PAN));
        assertEquals(0, EditorKeys.panMouseButton());
        assertFalse(EditorKeys.panKeyBound());
    }

    @Test
    @DisplayName("legacy marker-less configs reset to factory / 无格式标记的旧配置整表回出厂")
    void legacyConfigHeals() throws Exception {
        // 历史构造漏洞产物：未初始化鼠标槽默认 0=左键 并写盘（截图里一堆「/ 左键」的来源）。
        // The historical construction-bug product: uninitialized mouse slots defaulted
        // to 0 = left button and leaked into configs (the source of the screenshot's
        // pile of "/ left-click" rows).
        java.nio.file.Files.writeString(tempDir.resolve("config.properties"),
            "editorKeys.UNDO.mouse=0\neditorKeys.UNDO.seq=90,1\n"
            + "editorKeys.DELETE_WIRE.mouse=-1\neditorKeys.DELETE_WIRE.seq=87,0\n"
            + "editorKeys.PIXEL_BRUSH.mouse=0\n");
        EditorKeys.reloadForTest();
        assertEquals(List.of(new EditorKeys.Step(90, EditorKeys.MOD_CTRL)), EditorKeys.sequence(EditorKeys.Action.UNDO), "factory undo restored");
        assertEquals(List.of(new EditorKeys.Step(258, 0), mouse(0)), EditorKeys.sequence(EditorKeys.Action.DELETE_WIRE), "the Tab → left factory chord is restored");
        // 重载后的首次保存写回标记，新绑定自此可信持久。 / The first save after the reload writes the marker; new bindings persist trusted.
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.UNDO, List.of(new EditorKeys.Step(90, EditorKeys.MOD_CTRL))));
        EditorKeys.reloadForTest();
        assertEquals(List.of(new EditorKeys.Step(90, EditorKeys.MOD_CTRL)), EditorKeys.sequence(EditorKeys.Action.UNDO));
    }
}
