package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.AudioRef;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>音频频段层</b>：音频经「总线/私有频段」路由（R1-2）。<b>单引脚多声道</b>——每个频段承载
 * 一个多声道 {@link AudioRef}（聚合/左/右打在一个引用里），声道选择在下游（如 SPEAKER_PLAY 选声道）。
 * <p>Audio-band layer: audio routes via bus/private bands. <b>Single-pin multi-channel</b> —
 * each band carries one multi-channel {@link AudioRef} (mix/left/right in one reference);
 * channel selection happens downstream (e.g. {@code SPEAKER_PLAY} picks the channel).</p>
 * <ul>
 *   <li><b>band</b>（频段名）= 路由（如「舞池」）。全局静态与 {@link SignalBus} 同款惯例
 *       （跨维度共享是既有信号设计的性质）；发布即覆盖写。</li>
 *   <li><b>新鲜度门控</b>：每条引用带发布时的 game-time 戳，读取方只接受
 *       {@code 戳 ≥ 当前刻 − 1} 的引用（容忍跨 BE 的先后 tick 顺序，即 1 刻跨方块延迟口径）。
 *       发布方停机/卸载后陈旧引用自然失效——消费端自愈，不依赖任何人清理（旧「机关枪卡音」根因）。</li>
 *   <li>发布（AUDIO_OUT）写本 tick 音源；读取（AUDIO_IN）取之；音响经 SPEAKER_PLAY 选声道播放。</li>
 * </ul>
 */
public final class AudioBands {

    /** band → 最近发布的音源（覆盖写）。 */
    private static final ConcurrentHashMap<String, AudioRef> BANDS = new ConcurrentHashMap<>();
    /** band → 发布时的 game time（新鲜度门控，见类注）。 */
    private static final ConcurrentHashMap<String, Long> STAMPS = new ConcurrentHashMap<>();
    /** (consumer, band) → 该消费者已读的最新 stamp（exactly-once 游标，见带 consumer 的 get）。 */
    private static final ConcurrentHashMap<String, Long> CONSUMED = new ConcurrentHashMap<>();
    /** 独立 logger（勿引模组主类——测试环境不可初始化）。 */
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AudioBands.class);
    /** 重播取证限频（毫秒）。 */
    private static long lastReplayLogMs;

    /** 容忍的陈旧宽度（刻）：1 = 只认本刻与上一刻发布的引用（跨 BE tick 顺序口径）。 */
    private static final long FRESH_WINDOW_TICKS = 1;

    private AudioBands() {}

    // ── 发布（AUDIO_OUT / BUS_OUT 音频分支）/ publish ─────────────────────

    /** 写某频段的本 tick 音源（覆盖写，带上发布时刻）。 */
    public static void publish(String band, AudioRef ref, long gameTick) {
        if (band == null || band.isEmpty()) return;
        AudioRef r = ref == null ? AudioRef.EMPTY : ref;
        BANDS.put(band, r);
        STAMPS.put(band, gameTick);
    }

    // ── 读取（AUDIO_IN 等）/ read ────────────────────────────────────────

    /**
     * 取某频段音源；仅当发布时刻在本刻或上一刻（{@code nowTick − FRESH_WINDOW_TICKS}）才有效，
     * 否则返回 EMPTY——发布方停机/卸载/换世界后陈旧事件自动失声。
     */
    public static AudioRef get(String band, long nowTick) {
        if (band == null || band.isEmpty()) return AudioRef.EMPTY;
        AudioRef ref = BANDS.get(band);
        Long stamp = STAMPS.get(band);
        if (ref == null || stamp == null || stamp < nowTick - FRESH_WINDOW_TICKS) return AudioRef.EMPTY;
        return ref;
    }

    /**
     * 按<b>消费者游标</b>去重读取：每个 (consumer, band) 只认比上次更新的 stamp——
     * <b>exactly-once</b>，既不重播也不丢音。
     * <p>背景（cross-BE tick 顺序依赖）：BE tick 顺序随区块重载翻转；顺序翻转时同一批发布
     * 会被连续两次读到（stamp 仍在新鲜窗内）——同批音符相隔 1 tick 响两遍 = 听感「糊/挤
     * 在一起，然后恢复」。新鲜窗本身无法区分「迟到的首读」与「重播」，游标可以。
     * <p>Consumer-cursor read: each (consumer, band) accepts only stamps newer than its last
     * read — exactly-once, no replay and no drop. The freshness window alone cannot tell a
     * late first read from a replay; the cursor can.</p>
     */
    public static AudioRef get(String consumer, String band, long nowTick) {
        if (band == null || band.isEmpty() || consumer == null) return AudioRef.EMPTY;
        Long stamp = STAMPS.get(band);
        if (stamp == null) return AudioRef.EMPTY;
        String key = consumer + "\u0000" + band;
        Long last = CONSUMED.get(key);
        if (last != null && stamp <= last) {
            long now = System.currentTimeMillis();
            if (now - lastReplayLogMs > 2000) {
                lastReplayLogMs = now;
                LOG.warn("[AudioBands] REPLAY-Blocked consumer={} band={} stamp={} (tick-order replay refused)",
                    consumer, band, stamp);
            }
            return AudioRef.EMPTY;
        }
        AudioRef ref = get(band, nowTick);
        if (ref != AudioRef.EMPTY) CONSUMED.put(key, stamp);
        return ref;
    }

    // ── 生命周期 / lifecycle ────────────────────────────────────────────

    /** 清空本 tick 音源（测试隔离用；线上路径靠新鲜度门控自愈）。 */
    public static void clearTickEvents() {
        BANDS.replaceAll((k, v) -> AudioRef.EMPTY);
        STAMPS.clear();
    }

    /** 服务器停止/世界卸载清理（挂在 server-stopping 钩子上，与 SignalBus.clear 同点）。 */
    public static void clear() {
        BANDS.clear();
        STAMPS.clear();
        CONSUMED.clear();
    }
}
