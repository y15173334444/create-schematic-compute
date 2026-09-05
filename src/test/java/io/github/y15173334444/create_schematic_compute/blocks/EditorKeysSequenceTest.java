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
        assertEquals(EditorKeys.Action.DELETE_NODE, EditorKeys.feedKey(88, 0, T));       // X
        assertEquals(EditorKeys.Action.RESET_VIEW, EditorKeys.feedKey(268, 0, T));       // Home
        assertEquals(EditorKeys.Action.DELETE_SELECTED, EditorKeys.feedKey(261, 0, T)); // Delete
        assertEquals(EditorKeys.Action.BOX_SELECT, EditorKeys.feedKey(258, 0, T));       // Tab
        assertFalse(EditorKeys.bufferActive());
    }

    @Test
    @DisplayName("修饰位包含语义 —— Ctrl+Z 在 Ctrl+Shift+Z 下同样命中，裸 Z 不命中")
    void modsContainment() {
        assertEquals(EditorKeys.Action.UNDO, EditorKeys.feedKey(90, EditorKeys.MOD_CTRL, T));
        assertEquals(EditorKeys.Action.UNDO, EditorKeys.feedKey(90, EditorKeys.MOD_CTRL | EditorKeys.MOD_SHIFT, T));
        assertNull(EditorKeys.feedKey(90, 0, T));
        assertFalse(EditorKeys.bufferActive()); // 裸 Z 不构成 Ctrl+Z 的前缀（修饰不含）
    }

    @Test
    @DisplayName("多步序列 —— 第一步缓冲等待，第二步触发")
    void multiStepSequence() {
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.DELETE_NODE,
                List.of(step(75, 0), step(68, 0)))); // K → D
        assertNull(EditorKeys.feedKey(75, 0, T));
        assertTrue(EditorKeys.bufferActive()); // 前缀等待，按键已被消费
        assertEquals(EditorKeys.Action.DELETE_NODE, EditorKeys.feedKey(68, 0, T));
        assertFalse(EditorKeys.bufferActive());
    }

    @Test
    @DisplayName("缓冲超时 —— 间隔超过 1.5s 缓冲作废，第二步不再触发")
    void timeoutDiscardsBuffer() {
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.DELETE_NODE,
                List.of(step(75, 0), step(68, 0))));
        assertNull(EditorKeys.feedKey(75, 0, T));
        assertNull(EditorKeys.feedKey(68, 0, T + EditorKeys.SEQUENCE_TIMEOUT_MS + 1));
        assertFalse(EditorKeys.bufferActive());
    }

    @Test
    @DisplayName("vim 式重开 —— 缓冲中按了无关键，以该键为新首步重评估（可立即触发单步）")
    void restartRule() {
        assertTrue(EditorKeys.setSequence(EditorKeys.Action.DELETE_NODE,
                List.of(step(75, 0), step(68, 0)))); // K → D（X 已不再绑定 DELETE）
        assertNull(EditorKeys.feedKey(75, 0, T));
        // Home（未被重绑的 RESET_VIEW 单步）不是 K→D 的延续 —— 重开后立即触发
        assertEquals(EditorKeys.Action.RESET_VIEW, EditorKeys.feedKey(268, 0, T));
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
    @DisplayName("鼠标动作不参与序列 —— setSequence 拒绝，单键绑定照常")
    void mouseActionsExcluded() {
        assertFalse(EditorKeys.setSequence(EditorKeys.Action.PAN, List.of(step(75, 0))));
        assertTrue(EditorKeys.setMouseBinding(EditorKeys.Action.PAN, 2));
        assertEquals(2, EditorKeys.mouseButton(EditorKeys.Action.PAN));
        assertNull(EditorKeys.feedKey(75, 0, T)); // 键序引擎不理会鼠标动作
    }
}
