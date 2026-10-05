package io.github.y15173334444.create_schematic_compute.graph;

/**
 * 一条待下发的音符事件：服务端展开后携带的最终 NBS 参数 + 累计增益 + 子 tick 目标时刻。
 * **纯数据 + 纯映射，无 MC 依赖**。
 * <p>A scheduled note event: final NBS params, accumulated gain, and the sub-tick target
 * offset after server-side expansion. Pure data + pure mapping, no Minecraft deps.</p>
 * <p>播放映射（Eval §四.1 / F1 / F3 / G3）逐条：</p>
 * <ul>
 *   <li>乐器：0–15 原版一一对应音符盒 16 音色；≥16 自定义乐器 MVP 降级为 0（eval §四.2）。</li>
 *   <li>键：精确窗口 33–57 → 音符盒 0..24；窗口外**钳制**到边缘（不回写破坏曲目，本事件是播放副本）。</li>
 *   <li>音高倍率 = 2^((n−12)/12 + cents/1200)（F1）；引擎 calculatePitch 再钳 [0.5, 2.0]。</li>
 *   <li>实例音量 = 力度/100 × 增益；F2 可听响度钳 1，F3 音量 &gt;1 只延长衰减距离。</li>
 *   <li>声像：展开时已按 G2 烘焙层声像（层居中取音符、否则取平均，见
 *   {@link MusicTransport}）；播放端由听者相对音响的世界几何位置取代（数据保留但播放不用，
 *   eval §四.3）。</li>
 *   <li>delaySeconds：自下发时刻到音符目标时刻的秒数（预播提前量下发，服务端提前
 *   {@link MusicTransport#PREROLL_SECONDS} 展开）——客户端按目标时刻定点播，到达抖动
 *   （服务端 tick 过载时的「晚点/成批」）被提前量吸收。</li>
 * </ul>
 */
public record NoteEvent(int instrument, int key, int velocity, int panning, int pitch, float gain, float delaySeconds) {

    /** 便捷构造：无目标偏移（delaySeconds=0，到达即播）。 */
    public NoteEvent(int instrument, int key, int velocity, int panning, int pitch, float gain) {
        this(instrument, key, velocity, panning, pitch, gain, 0f);
    }

    /** 返回替换增益后的副本（保留 delaySeconds）。 */
    public NoteEvent withGain(float newGain) {
        return new NoteEvent(instrument, key, velocity, panning, pitch, newGain, delaySeconds);
    }

    /** 返回替换实例音量后的副本（实例音量 = 力度/100 × 增益，反解增益写回；力度为 0 的音符
     *  本就无声、增益不动）。 / a copy with the instance volume replaced (volume =
     *  velocity/100 × gain, solved back into the gain; a zero-velocity note is silent
     *  regardless, so its gain stays). */
    public NoteEvent withInstanceVolume(float volume) {
        if (velocity <= 0) return this;
        return withGain(volume * 100f / velocity);
    }

    /** 原版乐器（0–15）；自定义乐器（≥16）或越界降级为 0（harp）。 */
    public int mappedInstrument() {
        return (instrument >= 0 && instrument < 16) ? instrument : 0;
    }

    /** 音符号 n = key − 33（键 33 → 0，A0..C8 = −33..54）。引擎重采样全音域播放，
     *  不再钳制窗口（NBS 同款公式：基准键 45；窗口 33–57 仍是「零失真」精确区间）。
     *  Note number n = key − 33 (key 33 → 0, A0..C8 = −33..54). The engine resamples the
     *  full range (the NBS formula, base key 45); 33–57 stays the exact/no-stretch window. */
    public int noteNum() {
        return key - 33;
    }

    /** 音高倍率 = 2^((n−12)/12 + cents/1200)（= NBS 的 0.5×2^((key−33)/12) 同式）。
     *  Pitch multiplier, same formula as NBS's {@code 0.5×2^((key−33)/12)}. */
    public float pitchMultiplier() {
        return (float) Math.pow(2.0, (noteNum() - 12) / 12.0 + pitch / 1200.0);
    }

    /** 实例音量 = 力度/100 × 增益（F2/F3）。 */
    public float instanceVolume() {
        return (velocity / 100f) * gain;
    }
}
