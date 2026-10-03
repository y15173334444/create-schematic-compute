package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 声道布局预设测试（R3）：布局表、旧数据迁移、各声道拆分权重曲线。
 * Channel-layout preset tests (R3): layout tables, legacy migration, per-channel split laws.
 */
class ChannelLayoutTest {

    private static final float EPS = 1e-6f;

    private static NoteEvent note(int instrument, int key, int panning) {
        return new NoteEvent(instrument, key, 100, panning, 0, 1f);
    }

    private static float weight(int instrument, int key, int panning, String channel, boolean hasCenter) {
        AudioRef in = new AudioRef(List.of(note(instrument, key, panning)), 1f);
        AudioRef out = ChannelLayout.channelRef(in, channel, hasCenter);
        return out.isEmpty() ? 0f : out.events().get(0).gain();
    }

    // ── 布局表 / layout tables ──

    @Test
    @DisplayName("布局表：引脚序与中置标志 / layout tables: pin sets and center flag")
    void layoutTables() {
        assertEquals(5, ChannelLayout.layoutCount());
        assertArrayEquals(new String[]{"mix"}, ChannelLayout.pins(ChannelLayout.MIX));
        assertArrayEquals(new String[]{"l", "r"}, ChannelLayout.pins(ChannelLayout.STEREO));
        assertArrayEquals(new String[]{"l", "r", "ls", "rs"}, ChannelLayout.pins(ChannelLayout.QUAD));
        assertArrayEquals(new String[]{"l", "r", "c", "sub", "ls", "rs"},
            ChannelLayout.pins(ChannelLayout.SURROUND_5_1));
        assertArrayEquals(new String[]{"l", "r", "c", "sub", "ls", "rs", "sl", "sr"},
            ChannelLayout.pins(ChannelLayout.SURROUND_7_1));
        assertFalse(ChannelLayout.hasCenter(ChannelLayout.MIX));
        assertFalse(ChannelLayout.hasCenter(ChannelLayout.STEREO));
        assertFalse(ChannelLayout.hasCenter(ChannelLayout.QUAD));
        assertTrue(ChannelLayout.hasCenter(ChannelLayout.SURROUND_5_1));
        assertTrue(ChannelLayout.hasCenter(ChannelLayout.SURROUND_7_1));
    }

    @Test
    @DisplayName("越界布局钳制 / out-of-range layout clamps")
    void layoutClamp() {
        assertArrayEquals(ChannelLayout.pins(ChannelLayout.MIX), ChannelLayout.pins(-3));
        assertArrayEquals(ChannelLayout.pins(ChannelLayout.SURROUND_7_1), ChannelLayout.pins(99));
    }

    // ── 旧数据迁移 / legacy migration ──

    @Test
    @DisplayName("旧声道数迁移表 / legacy count migration table")
    void legacyMigration() {
        assertEquals(ChannelLayout.MIX, ChannelLayout.fromLegacyCount(1));
        assertEquals(ChannelLayout.STEREO, ChannelLayout.fromLegacyCount(2));
        assertEquals(ChannelLayout.STEREO, ChannelLayout.fromLegacyCount(3));
        assertEquals(ChannelLayout.SURROUND_5_1, ChannelLayout.fromLegacyCount(4));
        assertEquals(ChannelLayout.SURROUND_5_1, ChannelLayout.fromLegacyCount(5));
        assertEquals(ChannelLayout.SURROUND_5_1, ChannelLayout.fromLegacyCount(6));
        assertEquals(ChannelLayout.SURROUND_5_1, ChannelLayout.fromLegacyCount(7));
    }

    @Test
    @DisplayName("旧前缀模式识别 / legacy prefix detection")
    void legacyPrefixDetection() {
        assertTrue(ChannelLayout.isLegacyPrefix(List.of("mix")));
        assertTrue(ChannelLayout.isLegacyPrefix(List.of("mix", "l", "r")));
        assertTrue(ChannelLayout.isLegacyPrefix(List.of("mix", "l", "r", "c", "ls", "rs", "sub")));
        assertFalse(ChannelLayout.isLegacyPrefix(List.of("l", "r")), "new STEREO pins do not start with mix");
        assertFalse(ChannelLayout.isLegacyPrefix(List.of("l", "r", "c", "sub", "ls", "rs")));
        assertFalse(ChannelLayout.isLegacyPrefix(List.of()));
        assertFalse(ChannelLayout.isLegacyPrefix(null));
    }

    // ── 拆分权重 / split weights ──

    @Test
    @DisplayName("mix 恒等 / mix passes through")
    void mixIdentity() {
        AudioRef in = new AudioRef(List.of(note(0, 45, 100), note(0, 60, 0)), 1f);
        AudioRef out = ChannelLayout.channelRef(in, "mix", true);
        assertSame(in.events(), out.events(), "mix returns the same immutable source");
    }

    @Test
    @DisplayName("立体声等功率 l/r（无中置布局）/ equal-power stereo l/r (no-center layout)")
    void stereoEqualPower() {
        assertEquals(1f, weight(0, 45, 0, "l", false), EPS);
        assertEquals((float) Math.cos(Math.PI / 4), weight(0, 45, 100, "l", false), EPS);
        assertEquals(0f, weight(0, 45, 200, "l", false), EPS);
        assertEquals(0f, weight(0, 45, 0, "r", false), EPS);
        assertEquals((float) Math.sin(Math.PI / 4), weight(0, 45, 100, "r", false), EPS);
        assertEquals(1f, weight(0, 45, 200, "r", false), EPS);
    }

    @Test
    @DisplayName("5.1 的 LCR 三角：能量守恒、中心归 c / LCR triangle in 5.1: energy conservation, center to c")
    void lcrWeights() {
        // p=0：全左；p=0.25：L=C=√2/2；p=0.5：全中置；p=0.75：C=R=√2/2；p=1：全右
        assertEquals(1f, weight(0, 45, 0, "l", true), EPS);
        assertEquals((float) Math.cos(Math.PI / 4), weight(0, 45, 50, "l", true), EPS);
        assertEquals(0f, weight(0, 45, 100, "l", true), EPS, "center panning carries zero L under LCR");
        assertEquals(0f, weight(0, 45, 0, "c", true), EPS);
        assertEquals((float) Math.sin(Math.PI / 4), weight(0, 45, 50, "c", true), EPS);
        assertEquals(1f, weight(0, 45, 100, "c", true), EPS);
        assertEquals((float) Math.cos(Math.PI / 4), weight(0, 45, 150, "c", true), EPS);
        assertEquals(0f, weight(0, 45, 200, "c", true), EPS);
        assertEquals(0f, weight(0, 45, 100, "r", true), EPS);
        assertEquals((float) Math.sin(Math.PI / 4), weight(0, 45, 150, "r", true), EPS);
        assertEquals(1f, weight(0, 45, 200, "r", true), EPS);
        // 能量守恒抽查：p=0.25 处 L²+C²+R² = 1
        float l = weight(0, 45, 50, "l", true), c = weight(0, 45, 50, "c", true),
              r = weight(0, 45, 50, "r", true);
        assertEquals(1f, l * l + c * c + r * r, 1e-4);
    }

    @Test
    @DisplayName("sub：低音乐器 + 极低键位 / sub: bass instruments + very low keys")
    void subRouting() {
        assertEquals(1f, weight(1, 45, 100, "sub", true), EPS, "bass instrument");
        assertEquals(1f, weight(2, 45, 100, "sub", true), EPS, "basedrum instrument");
        assertEquals(1f, weight(0, 23, 100, "sub", true), EPS, "key 23 < 24");
        assertEquals(0f, weight(0, 24, 100, "sub", true), EPS, "key 24 stays out");
        assertEquals(0f, weight(0, 45, 100, "sub", true), EPS, "harp at mid key stays out");
        assertEquals(0f, weight(3, 45, 100, "sub", true), EPS, "snare stays out");
    }

    @Test
    @DisplayName("环绕溢出带：ls/rs 全增益极点、余弦渐出；sl/sr 同口径 / surround overflow bands")
    void sideRouting() {
        assertEquals(1f, weight(0, 45, 0, "ls", true), EPS);
        assertEquals((float) Math.cos(Math.PI / 4), weight(0, 45, 25, "ls", true), EPS);
        assertEquals(0f, weight(0, 45, 50, "ls", true), EPS, "quarter-left and inward stay out");
        assertEquals(1f, weight(0, 45, 200, "rs", true), EPS);
        assertEquals(0f, weight(0, 45, 150, "rs", true), EPS);
        assertEquals(1f, weight(0, 45, 0, "sl", true), EPS, "side-L shares the rear-L law");
        assertEquals(1f, weight(0, 45, 200, "sr", true), EPS, "side-R shares the rear-R law");
        assertEquals(0f, weight(0, 45, 100, "sl", true), EPS);
    }

    @Test
    @DisplayName("未知声道名兜底全量 / unknown channel names fall back to full")
    void unknownChannelFallback() {
        AudioRef in = new AudioRef(List.of(note(0, 45, 100)), 1f);
        assertSame(in.events(), ChannelLayout.channelRef(in, "??", true).events());
    }
}
