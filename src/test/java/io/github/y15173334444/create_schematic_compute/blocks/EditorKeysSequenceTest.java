package io.github.y15173334444.create_schematic_compute.blocks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 键盘动作序列绑定引擎的单测（脱离 MC 环境 —— EditorKeys 为纯类）。
 * <p>
 * 覆盖：单步触发（与旧单键行为一致）、修饰位包含语义、多步序列缓冲推进、
 * 超时作废、vim 式重开（无关键以新首步重评估）、前缀歧义冲突拒绝、
 * 持久化新格式往返与旧格式回退迁移、步数上限。
 * Unit tests for the keyboard sequence-binding engine (MC-free — EditorKeys is a
 * plain class). Covers single-step firing, modifier containment, multi-step
 * buffering, timeout, vim-style restart, prefix-ambiguity rejection, persistence
 * roundtrip + legacy fallback migration, and the step cap.
 */
class EditorKeysSequenceTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void resetToDefaults() {
        EditorKeys.setConfigPathForTest(tempDir.resolve("config.properties").toString());
        EditorKeys.reloadForTest();
    }

    private static final long T = 1000L;

    private static EditorKeys.Step step(int key, int mods) { return new EditorKeys.Step(key, mods); }

    @Test
    @DisplayName("单步序列触发 —— 与旧单键绑定行为一致")
    void singleStepFires() {
        assertEquals(EditorKeys.Action.DELETE_NODE, EditorKeys.feedKey(88, 0, T, EditorKeys.Scope.GRAPH));       // X
        assertEquals(EditorKeys.Action.RESET_VIEW, EditorKeys.feedKey(268, 0, T, EditorKeys.Scope.GRAPH));       // Home
        assertEquals(EditorKeys.Action.DELETE_SELECTED, EditorKeys.feedKey(261, 0, T, EditorKeys.Scope.GRAPH)); // Delete
        assertEquals(EditorKeys.Action.BOX_SELECT, EditorKeys.feedKey(258, 0, T, EditorKeys.Scope.GRAPH));       // Tab
        assertFalse(EditorKeys.bufferActive());
    }

    @Test
    @DisplayName("修饰位包含语义 —— Ctrl+Z 在 Ctrl+Shift+Z 下同样命中，裸 Z 不命中")
    void modsContainment() {
        assertEquals(EditorKeys.Action.UNDO, EditorKeys.feedKey(90, EditorKeys.MOD_CTRL, T, EditorKeys.Scope.GRAPH));
        assertEquals(EditorKeys.Action.UNDO, EditorKeys.feedKey(90, EditorKeys.MOD_CTRL | EditorKeys.MOD_SHIFT, T, EditorKeys.Scope.GRAPH));
        assertNull(EditorKeys.feedKey(90, 0, T, EditorKeys.Scope.GRAPH));
        assertFalse(EditorKeys.bufferActive()); // 裸 Z 不构成 Ctrl+Z 的前缀（修饰不含）
    }

    @Test
    @DisplayName("多步序列 —— 第一步缓冲等待，第二步触发")
    void multiStepSequence() {
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.DELETE_NODE,
                List.of(step(75, 0), step(68, 0)))); // K → D
        assertNull(EditorKeys.feedKey(75, 0, T, EditorKeys.Scope.GRAPH));
        assertTrue(EditorKeys.bufferActive()); // 前缀等待，按键已被消费
        assertEquals(EditorKeys.Action.DELETE_NODE, EditorKeys.feedKey(68, 0, T, EditorKeys.Scope.GRAPH));
        assertFalse(EditorKeys.bufferActive());
    }

    @Test
    @DisplayName("缓冲超时 —— 间隔超过 1.5s 缓冲作废，第二步不再触发")
    void timeoutDiscardsBuffer() {
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.DELETE_NODE,
                List.of(step(75, 0), step(68, 0))));
        assertNull(EditorKeys.feedKey(75, 0, T, EditorKeys.Scope.GRAPH));
        assertNull(EditorKeys.feedKey(68, 0, T + EditorKeys.SEQUENCE_TIMEOUT_MS + 1, EditorKeys.Scope.GRAPH));
        assertFalse(EditorKeys.bufferActive());
    }

    @Test
    @DisplayName("vim 式重开 —— 缓冲中按了无关键，以该键为新首步重评估（可立即触发单步）")
    void restartRule() {
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.DELETE_NODE,
                List.of(step(75, 0), step(68, 0)))); // K → D（X 已不再绑定 DELETE）
        assertNull(EditorKeys.feedKey(75, 0, T, EditorKeys.Scope.GRAPH));
        // Home（未被重绑的 RESET_VIEW 单步）不是 K→D 的延续 —— 重开后立即触发
        assertEquals(EditorKeys.Action.RESET_VIEW, EditorKeys.feedKey(268, 0, T, EditorKeys.Scope.GRAPH));
        assertFalse(EditorKeys.bufferActive());
    }

    @Test
    @DisplayName("前缀歧义冲突 —— [K] 与 [K,D]、[K,D] 与 [K,D] 拒绝，[K,E] 与 [D,K] 允许")
    void prefixConflictRejected() {
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.DELETE_NODE,
                List.of(step(75, 0), step(68, 0))));
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.UNDO, List.of(step(75, 0))));
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.UNDO, List.of(step(75, 0), step(68, 0))));
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.UNDO, List.of(step(75, 0), step(69, 0))));  // K → E
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.UNDO, List.of(step(68, 0), step(75, 0))));  // D → K（顺序不同不算前缀）
    }

    @Test
    @DisplayName("修饰子集前缀冲突 —— 步骤修饰互为子集即歧义（K→D 与 K→Ctrl+D 在裸 K,D 按序时会双触发）")
    void modsSubsetPrefixConflict() {
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.DELETE_NODE,
                List.of(step(75, 0), step(68, 0))));                                          // K → D
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.UNDO,
                List.of(step(75, 0), step(68, EditorKeys.MOD_CTRL))));                        // K → Ctrl+D（步 2 修饰 0 ⊂ Ctrl → 歧义）
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.UNDO,
                List.of(step(75, 0), step(68, EditorKeys.MOD_CTRL | EditorKeys.MOD_SHIFT)))); // K → Ctrl+Shift+D 同理
    }

    @Test
    @DisplayName("持久化往返 —— 新格式 seq 保存后重载一致")
    void persistenceRoundtrip() throws Exception {
        var seq = List.of(step(75, EditorKeys.MOD_CTRL), step(68, 0), step(70, 0));
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.UNDO, seq));
        EditorKeys.reloadForTest();
        assertEquals(seq, EditorKeys.sequence(EditorKeys.Action.UNDO));
        // 配置文件里是新格式编码 / the config file holds the new encoding
        String content = Files.readString(tempDir.resolve("config.properties"));
        assertTrue(content.contains("editorKeys.UNDO.seq=75,1;68,0;70,0"));
    }

    @Test
    @DisplayName("旧格式回退迁移 —— key/mods 单键配置读取为单步序列")
    void legacyConfigMigration() throws Exception {
        Path cfg = tempDir.resolve("config.properties");
        Files.writeString(cfg, "editorKeys.UNDO.key=90\neditorKeys.UNDO.mods=1\n");
        EditorKeys.reloadForTest();
        assertEquals(List.of(step(90, EditorKeys.MOD_CTRL)), EditorKeys.sequence(EditorKeys.Action.UNDO));
    }

    @Test
    @DisplayName("步数上限 —— 5 步拒绝且数据不动，4 步允许；空序列拒绝")
    void stepCapEnforced() {
        var five = List.of(step(65, 0), step(66, 0), step(67, 0), step(68, 0), step(69, 0));
        var four = List.of(step(65, 0), step(66, 0), step(67, 0), step(68, 0));
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.UNDO, five));
        assertEquals(List.of(step(90, EditorKeys.MOD_CTRL)), EditorKeys.sequence(EditorKeys.Action.UNDO)); // 未被破坏
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.UNDO, four));
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.UNDO, List.of()));
    }

    @Test
    @DisplayName("PAN 按住组合键 —— 单步可绑、press 引擎永不触发、多步拒绝；CONTEXT_MENU 键盘与鼠标槽照常")
    void panHoldCombo() {
        // PAN 绑定单步按住键（A）——按下 A 不触发动作（由手势拦截启动平移，feedKey 返回 null）。
        // PAN binds a single hold key (A) - pressing it fires nothing (the gesture
        // intercepts it to start panning; feedKey returns null).
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.PAN, List.of(step(65, 0))));
        assertNull(EditorKeys.feedKey(65, 0, T, EditorKeys.Scope.GRAPH));
        assertFalse(EditorKeys.bufferActive());
        assertFalse(EditorKeys.mixedBufferActive());
        assertTrue(EditorKeys.matchesPanHold(65, 0));
        assertTrue(EditorKeys.matchesPanHold(65, EditorKeys.MOD_SHIFT), "修饰包含语义 / mods containment");
        assertFalse(EditorKeys.matchesPanHold(66, 0));
        assertTrue(EditorKeys.isPanHoldKey(65));
        assertFalse(EditorKeys.isPanHoldKey(66));
        // 按住语义无连招：多步拒绝且数据不动。 / A hold has no combos: multi-step refused, data untouched.
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.PAN, List.of(step(75, 0), step(68, 0))));
        assertEquals(List.of(step(65, 0)), EditorKeys.sequence(EditorKeys.Action.PAN));
        // 菜单恢复键序触发（按绑定键在光标处打开）。 / The menu regains its key trigger (opens at the cursor on the bound key).
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.CONTEXT_MENU, List.of(step(75, 0))));
        assertEquals(EditorKeys.Action.CONTEXT_MENU, EditorKeys.feedKey(75, 0, T, EditorKeys.Scope.GRAPH));
        // 鼠标步形态 = 按钮拖动（panMouseButton 落位、panKeyBound 复位）。
        // The mouse-step shape = button-drag (panMouseButton lands, panKeyBound resets).
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.PAN,
            List.of(new EditorKeys.Step(EditorKeys.mouseStepKey(2), 0))));
        assertFalse(EditorKeys.panKeyBound());
        assertEquals(2, EditorKeys.panMouseButton());
    }

    // ══════════ 作用域模型：同键三屏各绑各的 / scope model: one key, three screens ══════════

    @Test
    @DisplayName("same key binds in all three scopes; matching is scope-local / 同键三作用域各绑各的、匹配只在本域")
    void sameKeyAcrossScopes() {
        // Ctrl+Z 出厂即在图域(NBS 域)各有撤销——再绑进像素域也合法
        // Ctrl+Z ships as undo in both the GRAPH and NBS scopes; binding it in PIXEL is legal too.
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.PIXEL_BRUSH,
            List.of(new EditorKeys.Step(90, EditorKeys.MOD_CTRL))));
        // 同域仍查冲突：NBS 域内 Ctrl+Z 已被 NBS_UNDO 出厂占用
        // Same-scope clashes still refuse: NBS_UNDO holds Ctrl+Z in the NBS scope.
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.NBS_SAVE,
            List.of(new EditorKeys.Step(90, EditorKeys.MOD_CTRL))));
        // 各域命中各自动作 / each scope fires its own action
        assertEquals(EditorKeys.Action.UNDO, EditorKeys.feedKey(90, EditorKeys.MOD_CTRL, T, EditorKeys.Scope.GRAPH));
        assertEquals(EditorKeys.Action.PIXEL_BRUSH, EditorKeys.feedKey(90, EditorKeys.MOD_CTRL, T, EditorKeys.Scope.PIXEL));
        assertEquals(EditorKeys.Action.NBS_UNDO, EditorKeys.feedKey(90, EditorKeys.MOD_CTRL, T, EditorKeys.Scope.NBS));
        // NBS 出厂默认 = 旧硬编码（Space / Ctrl+Z / Ctrl+Y）/ NBS factory keys = the old hardcodes
        assertEquals(EditorKeys.Action.NBS_PLAY_PAUSE, EditorKeys.feedKey(32, 0, T, EditorKeys.Scope.NBS));
        assertEquals(EditorKeys.Action.NBS_REDO, EditorKeys.feedKey(89, EditorKeys.MOD_CTRL, T, EditorKeys.Scope.NBS));
        assertNull(EditorKeys.feedKey(32, 0, T, EditorKeys.Scope.GRAPH), "Space means nothing in the graph editor");
    }

    @Test
    @DisplayName("buffers clear on scope change - no cross-screen combo chaining / 换域清缓冲、跨屏不接龙")
    void scopeChangeClearsBuffers() {
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.NBS_SAVE,
            List.of(new EditorKeys.Step(75, 0), new EditorKeys.Step(83, 0))));   // K → S
        assertNull(EditorKeys.feedKey(75, 0, T, EditorKeys.Scope.NBS), "K arms the NBS combo");
        // 换屏（图域）喂 K：图域 K 无绑定；不得替 NBS 组合续步
        // Switching to GRAPH mid-combo: the NBS half-combo must die, never chain.
        assertNull(EditorKeys.feedKey(75, 0, T + 10, EditorKeys.Scope.GRAPH));
        assertNull(EditorKeys.feedKey(83, 0, T + 20, EditorKeys.Scope.GRAPH));
        assertNull(EditorKeys.feedKey(83, 0, T + 30, EditorKeys.Scope.NBS), "the NBS combo died with the scope switch");
    }

    @Test
    @DisplayName("empty-key binding: clear unbinds, persists and stays rebindable / 绑定空键：清绑持久化、可再绑")
    void clearSequenceUnbindsAndPersists() {
        // NBS_UNDO 出厂 Ctrl+Z → 清除 = 未绑（「绑定空键」）
        assertEquals(List.of(new EditorKeys.Step(90, EditorKeys.MOD_CTRL)), EditorKeys.sequence(EditorKeys.Action.NBS_UNDO));
        EditorKeys.clearSequence(EditorKeys.Action.NBS_UNDO);
        assertTrue(EditorKeys.sequence(EditorKeys.Action.NBS_UNDO).isEmpty(), "cleared = unbound");
        assertNull(EditorKeys.feedKey(90, EditorKeys.MOD_CTRL, T, EditorKeys.Scope.NBS), "the key no longer fires");
        // 显式空值持久化：重载后仍为未绑，不回落出厂 Ctrl+Z
        // The explicit empty value persists: still unbound after reload, no factory fallback.
        EditorKeys.reloadForTest();
        assertTrue(EditorKeys.sequence(EditorKeys.Action.NBS_UNDO).isEmpty(), "unbound survives the roundtrip");
        assertNull(EditorKeys.feedKey(90, EditorKeys.MOD_CTRL, T, EditorKeys.Scope.NBS));
        // 出厂未绑动作的「默认」仍是未绑 / factory-unbound actions stay unbound on reset
        EditorKeys.resetToDefault(EditorKeys.Action.NBS_STOP);
        assertTrue(EditorKeys.sequence(EditorKeys.Action.NBS_STOP).isEmpty());
        // 清绑后可再绑 / rebindable after clearing
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.NBS_UNDO, List.of(new EditorKeys.Step(81, 0))));
        assertEquals(EditorKeys.Action.NBS_UNDO, EditorKeys.feedKey(81, 0, T, EditorKeys.Scope.NBS));
    }
}
