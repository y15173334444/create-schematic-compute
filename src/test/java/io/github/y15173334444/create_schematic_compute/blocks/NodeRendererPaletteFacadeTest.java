package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.client.EditorTheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NodeRenderer 调色板门面冻结核查：全部 26 个静态色访问器（公共 + 包级）逐槽等于
 * {@link EditorTheme} 的活动值，且运行时换色后门面**立即**反映新色（P2-23 回归锚——
 * 门面必须是活值委托，绝不允许任何 static final 捕获回潮）。
 * NodeRenderer palette facade freeze check: all 26 static colour accessors (public and
 * package-private) must equal the corresponding live {@link EditorTheme} slot, and a runtime
 * recolour must show through the facade **immediately** (the P2-23 regression anchor — the
 * facade must stay live delegation, never a static-final capture again).
 */
class NodeRendererPaletteFacadeTest {

    @BeforeEach
    @AfterEach
    void resetToDefaultPreset() {
        // 与 EditorThemeTest 同款复位：测试与真实配置文件状态解耦。
        // Same reset as EditorThemeTest: decouple the tests from the real config file.
        EditorTheme.get().applyPreset(0);
    }

    @Test
    @DisplayName("26 个门面访问器逐槽等于 EditorTheme 活动值 / all 26 facade accessors equal the live EditorTheme slots")
    void facadeAccessorsDelegateToTheLiveTheme() {
        EditorTheme t = EditorTheme.get();
        assertEquals(t.bg(), NodeRenderer.CG());
        assertEquals(t.grid(), NodeRenderer.CGL());
        assertEquals(t.node(), NodeRenderer.CN());
        assertEquals(t.header(), NodeRenderer.CH());
        assertEquals(t.border(), NodeRenderer.CB());
        assertEquals(t.inputPin(), NodeRenderer.CPI());
        assertEquals(t.outputPin(), NodeRenderer.CPO());
        assertEquals(t.wire(), NodeRenderer.CW());
        assertEquals(t.wireAudio(), NodeRenderer.CWA());
        assertEquals(t.pinAudio(), NodeRenderer.CAP());
        assertEquals(t.pinAudioBorder(), NodeRenderer.CAPB());
        assertEquals(t.drag(), NodeRenderer.CWD());
        assertEquals(t.menuText(), NodeRenderer.CMN());
        assertEquals(t.menuHover(), NodeRenderer.CMH());
        assertEquals(t.nodeTitle(), NodeRenderer.CNT());
        assertEquals(t.catText(), NodeRenderer.CCT());
        assertEquals(t.sysBorder(), NodeRenderer.CSB());
        assertEquals(t.inputPinBorder(), NodeRenderer.CPIB());
        assertEquals(t.outputPinBorder(), NodeRenderer.CPOB());
        assertEquals(t.panelBg(), NodeRenderer.PBG());
        assertEquals(t.panelHeader(), NodeRenderer.PHT());
        assertEquals(t.panelBorder(), NodeRenderer.PBR());
        assertEquals(t.insetBg(), NodeRenderer.PINS());
        assertEquals(t.accent(), NodeRenderer.ACC());
        assertEquals(t.error(), NodeRenderer.ERR());
        assertEquals(t.hover(), NodeRenderer.HOV());
    }

    @Test
    @DisplayName("运行时换色穿透门面立即生效（P2-23 回归锚）/ a runtime recolour shows through the facade immediately (P2-23 anchor)")
    void facadeTracksRuntimeRecolor() {
        int[] custom = EditorTheme.DEFAULT_COLORS.clone();
        custom[19] = 0xFF123456; // panel_bg —— Pixel/NBS 屏的底色槽 / the panel-background slot
        EditorTheme.get().setColors(custom);
        assertEquals(0xFF123456, NodeRenderer.PBG());
        assertEquals(EditorTheme.get().panelBg(), NodeRenderer.PBG());
        // 预设换装同样穿透 / preset swaps show through too
        EditorTheme.get().applyPreset(3);
        assertEquals(EditorTheme.THEMES[3][19], NodeRenderer.PBG());
    }

    @Test
    @DisplayName("withAlpha 委托一致 / withAlpha delegates consistently")
    void withAlphaDelegates() {
        assertEquals(EditorTheme.withAlpha(0xFF2A2822, 0xEE), NodeRenderer.withAlpha(0xFF2A2822, 0xEE));
        assertEquals(0xEE2A2822, NodeRenderer.withAlpha(0xFF2A2822, 0xEE));
    }

    @Test
    @DisplayName("暂存工作台初始化 = 当前色板快照 / staging initialises to a snapshot of the live palette")
    void stagingInitialisesFromLivePalette() {
        int[] custom = EditorTheme.DEFAULT_COLORS.clone();
        custom[23] = 0xFF00BEEF; // accent
        EditorTheme.get().setColors(custom);
        NodeRenderer.initStaging();
        assertEquals(0xFF00BEEF, NodeRenderer.stagingColors[23]);
        assertEquals(26, NodeRenderer.stagingColors.length);
        assertEquals(26, NodeRenderer._NUM_COLORS);
        // 改快照不影响主题（隔离不变式）/ mutating the staging copy must not touch the theme
        NodeRenderer.stagingColors[23] = 0xFF010101;
        assertEquals(0xFF00BEEF, EditorTheme.get().accent());
    }
}
