package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 音符事件播放映射测试（P0 键位映射验证项 / Eval §四.1）：
 * 键 33/45/57 与音符盒对应键同高；全音域 0–87 不钳制（引擎重采样，NBS 同款公式）；
 * 自定义乐器降级；增益入音量。
 * NoteEvent playback-mapping tests (the P0 key-mapping verification item).
 */
class NoteEventTest {

    @Test
    @DisplayName("键位映射：33/45/57 → 音符盒 0/12/24 / keys 33/45/57 map to note-block 0/12/24")
    void keyMappingWindow() {
        assertEquals(0, new NoteEvent(0, 33, 100, 100, 0, 1f).noteNum());
        assertEquals(12, new NoteEvent(0, 45, 100, 100, 0, 1f).noteNum());
        assertEquals(24, new NoteEvent(0, 57, 100, 100, 0, 1f).noteNum());
    }

    @Test
    @DisplayName("全音域不钳制：键 20→−13、键 70→37 / full range, no clamping")
    void keyFullRange() {
        assertEquals(-13, new NoteEvent(0, 20, 100, 100, 0, 1f).noteNum(), "key 20 plays below the window (engine resamples)");
        assertEquals(37, new NoteEvent(0, 70, 100, 100, 0, 1f).noteNum(), "key 70 plays above the window");
        assertEquals(-33, new NoteEvent(0, 0, 100, 100, 0, 1f).noteNum(), "A0 (key 0) reaches the engine unclamped");
        assertEquals(54, new NoteEvent(0, 87, 100, 100, 0, 1f).noteNum(), "C8 (key 87) reaches the engine unclamped");
    }

    @Test
    @DisplayName("音高倍率 = 2^((n-12)/12)：0.5/1/2 / pitch multiplier matches note block")
    void pitchMultiplier() {
        assertEquals(0.5f, new NoteEvent(0, 33, 100, 100, 0, 1f).pitchMultiplier(), 1e-6);
        assertEquals(1.0f, new NoteEvent(0, 45, 100, 100, 0, 1f).pitchMultiplier(), 1e-6);
        assertEquals(2.0f, new NoteEvent(0, 57, 100, 100, 0, 1f).pitchMultiplier(), 1e-6);
        // cents: +1200 cents = +1 octave → key 45 (n=12) → 2.0
        assertEquals(2.0f, new NoteEvent(0, 45, 100, 100, 1200, 1f).pitchMultiplier(), 1e-6);
    }

    @Test
    @DisplayName("自定义乐器降级为 0 / custom instrument downgrades to 0")
    void customInstrumentDowngrades() {
        assertEquals(5, new NoteEvent(5, 45, 100, 100, 0, 1f).mappedInstrument());
        assertEquals(0, new NoteEvent(20, 45, 100, 100, 0, 1f).mappedInstrument(), "custom (≥16) downgrades to harp");
    }

    @Test
    @DisplayName("实例音量 = 力度/100 × 增益 / instance volume = velocity/100 × gain")
    void instanceVolume() {
        assertEquals(1.0f, new NoteEvent(0, 45, 100, 100, 0, 1f).instanceVolume(), 1e-6);
        assertEquals(0.4f, new NoteEvent(0, 45, 80, 100, 0, 0.5f).instanceVolume(), 1e-6);
        assertEquals(1.0f, new NoteEvent(0, 45, 50, 100, 0, 2f).instanceVolume(), 1e-6);
    }
}
