package io.github.y15173334444.create_schematic_compute.client.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编辑器性能设置的持久化往返（路径注入形态，无头可测）：三项开关的默认值、save→load
 * 往返保真、缺键回落默认；与既有属性文件共存（grid_snap 等键必须保留——读取-合并-写回纪律）。
 * Persistence round-trip for the editor perf settings (path-injected form, headless-testable):
 * the three switches' defaults, save→load fidelity, missing-key fallback to defaults, and
 * coexistence with an existing properties file (grid_snap and friends must survive — the
 * read-merge-write discipline).
 */
class EditorPerfSettingsTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("缺键回落默认值 / missing keys fall back to the defaults")
    void missingKeysFallBackToDefaults() {
        Path p = dir.resolve("absent.properties");
        EditorPerfSettings.load(p);
        assertTrue(EditorPerfSettings.textLod());
        assertFalse(EditorPerfSettings.cullWorld());
        assertFalse(EditorPerfSettings.cullFp());
    }

    @Test
    @DisplayName("save→load 往返保真 / save→load round-trips faithfully")
    void roundTripPreservesAllThree() throws Exception {
        Path p = dir.resolve("perf.properties");
        EditorPerfSettings.load(p); // 先对齐默认态 / align to defaults first
        // 直接写包可见字段（绕开落真实路径的 setter）。 / write the package-visible fields
        // directly (bypassing the setters, which persist to the real config path).
        EditorPerfSettings.textLod = false;
        EditorPerfSettings.cullWorld = true;
        EditorPerfSettings.cullFp = true;
        EditorPerfSettings.save(p);
        EditorPerfSettings.load(p); // 重读（模拟重启）/ re-read (simulates a restart)
        assertFalse(EditorPerfSettings.textLod());
        assertTrue(EditorPerfSettings.cullWorld());
        assertTrue(EditorPerfSettings.cullFp());
        // 还原默认态，避免污染同 JVM 后续测试 / restore defaults for later tests in this JVM
        EditorPerfSettings.textLod = true;
        EditorPerfSettings.cullWorld = false;
        EditorPerfSettings.cullFp = false;
        assertTrue(Files.exists(p));
    }

    @Test
    @DisplayName("读取-合并-写回保留既有键 / read-merge-write preserves existing keys")
    void existingKeysSurviveTheMerge() throws Exception {
        Path p = dir.resolve("mixed.properties");
        Files.writeString(p, "grid_snap=false\neditorKeys.format=2\n");
        EditorPerfSettings.load(p);
        EditorPerfSettings.cullWorld = true; // 直接改字段 / mutate the field directly
        EditorPerfSettings.save(p);          // 触发一次保存 / triggers one save
        var props = new java.util.Properties();
        try (var is = Files.newInputStream(p)) { props.load(is); }
        assertEquals("false", props.getProperty("grid_snap"));
        assertEquals("2", props.getProperty("editorKeys.format"));
        assertEquals("true", props.getProperty("perf.cull_world"));
    }
}
