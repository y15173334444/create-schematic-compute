package io.github.y15173334444.create_schematic_compute.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编辑器主题（调色板单一真相源）的契约：实例态、运行时改色即时生效（P2-23 的核心回归锚）、
 * 预设换装与越界回落、快照隔离、配置读写往返。
 * The editor-theme (palette single-source-of-truth) contract: instance state, runtime recolour
 * taking effect immediately (the core P2-23 regression anchor), preset swaps with out-of-range
 * fallback, snapshot isolation, and the config save/load round-trip.
 */
class EditorThemeTest {

    @BeforeEach
    @AfterEach
    void resetToDefaultPreset() {
        // 用例前后回到预设 0：类初始化可能已从真实配置文件加载用户配色，测试必须与
        // 仓库/机器状态解耦。Reset to preset 0 around each test: class init may already have
        // loaded a user palette from the real config file, so tests must not depend on it.
        EditorTheme.get().applyPreset(0);
    }

    @Test
    @DisplayName("默认色板锚点与旧 NodeRenderer 出厂值逐槽一致 / default palette anchors match the original factory values")
    void defaultsMatchTheOriginalPalette() {
        assertEquals(26, EditorTheme.NUM_COLORS);
        assertEquals(26, EditorTheme.COLOR_KEYS.length);
        assertEquals(26, EditorTheme.DEFAULT_COLORS.length);
        assertEquals(4, EditorTheme.THEMES.length);
        // 槽位锚点（取自原 NodeRenderer DEFAULT_COLORS）/ slot anchors (from the original NodeRenderer table)
        assertEquals(0xFF1F1E1A, EditorTheme.get().bg());
        assertEquals(0xFFD4A017, EditorTheme.get().inputPin());
        assertEquals(0xFF3FB8A8, EditorTheme.get().wireAudio());
        assertEquals(0xFF5ED0BC, EditorTheme.get().pinAudio());
        assertEquals(0xFF888888, EditorTheme.get().menuText());
        assertEquals(0xFF8B7533, EditorTheme.get().sysBorder());
        assertEquals(0xFF2A2822, EditorTheme.get().panelBg());
        assertEquals(0xFF4A3F28, EditorTheme.get().panelHeader());
        assertEquals(0xFF5A4D3A, EditorTheme.get().panelBorder());
        assertEquals(0xFF1A1814, EditorTheme.get().insetBg());
        assertEquals(0xFFFFAA00, EditorTheme.get().accent());
        assertEquals(0xFFFF4444, EditorTheme.get().error());
        assertEquals(0xFF3A3428, EditorTheme.get().hover());
        // 预设 0 == 出厂默认 / preset 0 is the factory default
        assertArrayEquals(EditorTheme.DEFAULT_COLORS, EditorTheme.THEMES[0]);
    }

    @Test
    @DisplayName("运行时换色后新取值立即生效（P2-23 核心）/ a runtime recolour is visible to the very next fetch (P2-23 core)")
    void runtimeRecolorTakesEffectImmediately() {
        int[] custom = EditorTheme.DEFAULT_COLORS.clone();
        custom[19] = 0xFF112233; // panel_bg
        custom[23] = 0xFF445566; // accent
        EditorTheme.get().setColors(custom);
        // 下一次取值就是新色——不存在任何 static final 捕获 / the next fetch already sees the
        // new colours — no static-final capture anywhere
        assertEquals(0xFF112233, EditorTheme.get().panelBg());
        assertEquals(0xFF445566, EditorTheme.get().accent());
    }

    @Test
    @DisplayName("预设换装 + 越界回落 0 / preset swap and out-of-range fallback to 0")
    void applyPresetSwitchesAndClamps() {
        EditorTheme.get().applyPreset(1);
        assertEquals(1, EditorTheme.get().themeIndex());
        assertEquals(0xFF141C2A, EditorTheme.get().panelBg()); // THEMES[1][19]
        EditorTheme.get().applyPreset(99);
        assertEquals(0, EditorTheme.get().themeIndex());
        assertEquals(0xFF2A2822, EditorTheme.get().panelBg());
        EditorTheme.get().applyPreset(-1);
        assertEquals(0, EditorTheme.get().themeIndex());
    }

    @Test
    @DisplayName("快照是副本——改快照不影响主题 / the snapshot is a copy — mutating it leaves the theme untouched")
    void snapshotIsACopy() {
        int[] snap = EditorTheme.get().snapshot();
        snap[23] = 0xFFDEAD00;
        assertEquals(0xFFFFAA00, EditorTheme.get().accent());
    }

    @Test
    @DisplayName("setColors 忽略短数组（旧口径不变）/ setColors ignores short arrays (old contract kept)")
    void setColorsIgnoresShortArrays() {
        int before = EditorTheme.get().panelBg();
        EditorTheme.get().setColors(new int[3]);
        assertEquals(before, EditorTheme.get().panelBg());
    }

    @Test
    @DisplayName("withAlpha 保留 RGB、替换 alpha / withAlpha keeps RGB, replaces alpha")
    void withAlphaKeepsRgbReplacesAlpha() {
        assertEquals(0x33FFAA00, EditorTheme.withAlpha(0xFFFFAA00, 0x33));
        assertEquals(0x33FFAA00, EditorTheme.withAlpha(0x00FFAA00, 0x33));
        // alpha 超出 8 位被掩到 0xFF（旧口径）/ an alpha wider than 8 bits masks to 0xFF (old contract)
        assertEquals(0xFF345678, EditorTheme.withAlpha(0x12345678, 0x1FF));
    }

    @Test
    @DisplayName("配置保存/加载往返恢复自定义配色 / the config save/load round-trip restores a custom palette")
    void configRoundTrip(@TempDir Path tmp) throws Exception {
        int[] custom = EditorTheme.DEFAULT_COLORS.clone();
        custom[23] = 0xFF00BEEF; // accent
        EditorTheme.get().setColors(custom);
        Path file = tmp.resolve("client.properties");
        EditorTheme.saveColorConfig(file);
        assertTrue(Files.exists(file));
        assertTrue(Files.readString(file).contains("color.accent=FF00BEEF"));
        // 换走再加载：自定义色完整恢复 / switch away, then load: the custom palette returns intact
        EditorTheme.get().applyPreset(1);
        assertEquals(0xFF141C2A, EditorTheme.get().panelBg());
        EditorTheme.loadColorConfig(file);
        assertEquals(0xFF00BEEF, EditorTheme.get().accent());
        assertEquals(0xFF1F1E1A, EditorTheme.get().bg()); // 未改的槽位 = 默认 / untouched slots stay default
    }

    @Test
    @DisplayName("加载不存在的配置 = 保持现状 / loading a missing config keeps the current palette")
    void loadMissingConfigKeepsCurrent(@TempDir Path tmp) {
        EditorTheme.get().applyPreset(2);
        int before = EditorTheme.get().panelBg();
        EditorTheme.loadColorConfig(tmp.resolve("nope.properties"));
        assertEquals(before, EditorTheme.get().panelBg());
    }
}
