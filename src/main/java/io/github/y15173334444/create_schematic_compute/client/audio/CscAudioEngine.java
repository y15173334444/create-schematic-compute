package io.github.y15173334444.create_schematic_compute.client.audio;

import com.mojang.blaze3d.audio.Library;
import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.NoteEvent;
import io.github.y15173334444.create_schematic_compute.mixin.SoundEngineAccessor;
import io.github.y15173334444.create_schematic_compute.mixin.SoundManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * CSC 音频引擎（客户端）：世界发声与编辑器试听的**唯一播放入口**。
 * <p>CSC audio engine (client): the <b>single playback entry</b> for world playback and the
 * editor's audition.</p>
 *
 * <p>机制（eval §三 B 路线「自管混音层」）：原版播放一音符一声部、打满 247 声部池即静默丢音
 * （plan 风险 1 / F6，高密度曲目普遍复现）。本引擎把音符交 {@link AudioMixer} 合成进
 * <b>一条 PCM 流</b>，经 {@code Channel.attachBufferStream}（STREAMING 池）输出——
 * 声部消耗恒为 1，与曲目密度无关。
 * <p>Mechanism (eval §三 route B, mixing tier): vanilla playback burns one pool voice per note
 * and silently drops beyond the 247-voice pool (plan risk 1 / F6, common on dense songs). This
 * engine folds notes into <b>one PCM stream</b> through {@code Channel.attachBufferStream}
 * (the STREAMING pool) — voice usage is constantly 1, independent of note density.</p>
 *
 * <p>衰减/声像（F7 + D13 口径）：{@code gain = 1 − dist/radius} 线性衰减，radius 取音响
 * {@code radius} 覆盖（&gt;0）或默认 {@link AudioMixer#DEFAULT_RADIUS}（48 格）——
 * {@code radius} 字段自此有了消费者；声像 = 听者右手系横向分量的等功率声像。音量 =
 * 主音量 × 唱片类音量（与原版唱片/音符盒同滑条，plan 风险 7）。
 * <p>Attenuation/panning (F7 + D13): linear {@code gain = 1 − dist/radius}, radius from the
 * speaker's {@code radius} override (&gt;0) or {@link AudioMixer#DEFAULT_RADIUS} (48 blocks) —
 * the {@code radius} field finally has a consumer; equal-power pan from the listener-right
 * component. Volume = master × records (shared slider with vanilla record players, plan risk 7).</p>
 *
 * <p>生命周期：通道惰性挂接、自愈重建（资源重载/设备切换/欠载停播后 {@code isStopped} → 下次
 * 播放重建）；AL 调用全部经 {@code ChannelHandle.execute} 落在声音引擎线程（与原版同契约）。
 * <p>Lifecycle: the channel is attached lazily and self-heals (resource reload / device switch /
 * buffer underrun → {@code isStopped} → re-created on next play); all AL calls run on the sound
 * engine thread via {@code ChannelHandle.execute}, same contract as vanilla.</p>
 */
@OnlyIn(Dist.CLIENT)
@net.neoforged.fml.common.EventBusSubscriber(modid = SchematicCompute.MOD_ID, value = Dist.CLIENT)
public final class CscAudioEngine {

    private static AudioMixer mixer;
    private static ChannelAccess.ChannelHandle handle;
    private static boolean attaching;
    private static boolean attachedOnce;
    /** 时间线诊断（「推迟/挤堆」定量定位，plan R2 排查工具）。 */
    private static final AudioTimelineDiag DIAG = new AudioTimelineDiag();
    /** 墙钟锚点（帧 + 时刻成对更新/成对读取，见 targetFrameFor）：
     *  最近一次渲染完成时的渲染游标帧 + 对应墙钟。 */
    private static final Object ANCHOR_LOCK = new Object();
    private static long anchorFrames = 0;
    private static long anchorWallNanos = System.nanoTime();
    /**
     * 实测消费速率（帧/纳秒，EMA 平滑）。墙钟 → 输出帧的换算**必须**用实测速率而非标称
     * 44100：Windows 共享音频引擎会把 44.1k 重采样到设备速率，DAC 与墙钟存在 ±ppm 级比率
     * 误差——纯标称换算会让落点目标相对游标慢慢漂移，累积到超过预播余量时触发整批钳制前拉
     * （听感「挤在一起然后恢复」，与视频播放器音画漂移-重同步同机制）。实测速率使换算
     * 跟随真实消费时钟，比率误差被构造性消除。
     * <p>Measured consumption rate (frames/ns, EMA-smoothed). The wall→frame conversion must
     * use the <b>observed</b> rate, not the nominal 44100: Windows shared audio resamples 44.1k
     * to the device rate, giving the DAC-vs-wall ratio a ±ppm error — nominal conversion drifts
     * the targets against the cursor until a whole batch clamps forward ("bunch then recover",
     * the video-player A/V-desync mechanism). The observed rate follows the real clock.</p>
     */
    private static double framesPerNano = AudioMixer.SAMPLE_RATE / 1e9;
    /** EMA 系数（≈200 个渲染样本收敛，抹平按缓冲块采样的量化噪声）。 */
    private static final double RATE_EMA = 0.005;
    /** 实测速率钳制区间（防欠载重挂等异常间隔毒化 EMA）：标称的 0.8–1.2 倍。 */
    private static final double RATE_MIN = 0.8 * AudioMixer.SAMPLE_RATE / 1e9;
    private static final double RATE_MAX = 1.2 * AudioMixer.SAMPLE_RATE / 1e9;

    private CscAudioEngine() {}

    // ══════════════ 播放入口 / playback entries ══════════════

    /**
     * 世界发声：在 (x,y,z) 播一条音符事件（衰减/声像按听者几何）。
     * radius ≤ 0 取默认 48 格。来自 {@code NoteEventPacket}（服务端下发）。
     */
    public static void play(NoteEvent e, double x, double y, double z, double radius, float lateSeconds) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || e == null) return;
        float vol = masterRecordsVolume();
        if (vol <= 0f) return;
        var lt = mc.getSoundManager().getListenerTransform();
        double dx = x - lt.position().x, dy = y - lt.position().y, dz = z - lt.position().z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float distGain = AudioMixer.distanceGain(dist, radius);
        if (distGain <= 0f) return;
        float pan = 0f;
        if (dist > 1.0e-3) {
            var right = lt.right();
            pan = (float) ((dx * right.x + dy * right.y + dz * right.z) / dist);
        }
        schedule(e, pan, distGain, vol, lateSeconds);
    }

    /**
     * 试听（编辑器）：按听者位置发声（距离 0 → 全增益、居中声像），同款播放路径。
     * Audition (editor): played at the listener (distance 0 → full gain, centred), same path.
     */
    public static void playAtListener(NoteEvent e) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || e == null) return;
        float vol = masterRecordsVolume();
        if (vol <= 0f) return;
        schedule(e, 0f, 1f, vol, 0f);   // 本地试听无网络迟到 / audition is local
    }

    private static void schedule(NoteEvent e, float pan, float distGain, float vol, float lateSeconds) {
        float[] sample = SampleBank.sample(e.mappedInstrument());
        if (sample == null) return;
        int rate = SampleBank.rate(e.mappedInstrument());
        float gain = e.instanceVolume() * distGain * vol;
        if (gain <= 0f) return;
        float[] lr = new float[2];
        AudioMixer.panGains(pan, lr);
        ensureChannel();
        long target = targetFrameFor(e, lateSeconds);
        long placed = mixer.scheduleAtFrame(target, sample, rate, e.pitchMultiplier(), gain * lr[0], gain * lr[1]);
        long now = System.nanoTime();
        DIAG.onSchedule(target, placed, now);
        DIAG.onBatch(e.delaySeconds(), now);
        // 钳制发作即时取证（限频）：落点迟放 >5 ms 时打一条，带上 delaySeconds 区分
        // 「起播/回绕预播不足」与「时钟漂移回拉」
        long drift = placed - target;
        if (drift > ms(5)) {
            String ep = DIAG.episode(drift, e.delaySeconds(), mixer.activeVoices(), now);
            if (ep != null) SchematicCompute.LOGGER.warn(ep);
        }
    }

    /** 限幅增益诊断回调（声音引擎线程）。 */
    static void onLimiterDiag(float gain) {
        DIAG.onLimiterGain(gain);
    }

    private static long ms(double v) { return (long) (v * 1_000_000); }

    /**
     * 目标绝对输出帧 = 锚点帧 + 距锚点墙钟增量 + （目标时刻 − 预播）。
     * 锚点对（帧, 时刻）必须**成对**取用：混音渲染与调度共锁，若基准帧取「当前游标」而墙钟取
     * 「锚点时刻」，等待渲染锁的批次会吃进已完成渲染的步进（每批整体后移 1 块、块数随撞锁次数
     * 漂移）——「密集段落推迟/挤堆」的根因。绝对帧坐标与锁获取时刻彻底解耦。
     * <p>Target absolute frame = anchor frames + wall delta + (target − pre-roll). The anchor
     * pair (frames, wall) must be read <b>atomically</b>: mixing renders and scheduling share a
     * lock, and a base frame read after lock acquisition (with a wall time from before) absorbs
     * completed renders — shifting whole batches late by a buffer, drifting with lock collisions.
     * Absolute-frame placement is fully decoupled from lock timing.</p>
     */
    private static long targetFrameFor(NoteEvent e, float lateSeconds) {
        synchronized (ANCHOR_LOCK) {
            long wallAdvance = Math.max(0, System.nanoTime() - anchorWallNanos);
            return AudioMixer.placementFrames(anchorFrames,
                Math.round(wallAdvance * framesPerNano),
                e.delaySeconds(), lateSeconds,
                io.github.y15173334444.create_schematic_compute.graph.MusicTransport.PREROLL_SECONDS);
        }
    }

    /** 渲染完成回调（声音引擎线程）：成对刷新墙钟锚点 + 实测速率 EMA。 */
    static void onFramesRendered(long framesTotal) {
        synchronized (ANCHOR_LOCK) {
            long now = System.nanoTime();
            long df = framesTotal - anchorFrames;
            long dt = now - anchorWallNanos;
            if (df > 0 && dt > 0) {
                double observed = (double) df / dt;
                if (observed > RATE_MIN && observed < RATE_MAX) {
                    framesPerNano += (observed - framesPerNano) * RATE_EMA;
                }
            }
            anchorFrames = framesTotal;
            anchorWallNanos = now;
        }
    }

    /** 渲染诊断回调（声音引擎线程）。 */
    static void onRenderDiag(long nanosTaken, int voices, long nowNanos) {
        DIAG.onRender(nanosTaken, voices, nowNanos);
    }

    private static float masterRecordsVolume() {
        var mc = Minecraft.getInstance();
        return mc.options.getSoundSourceVolume(SoundSource.MASTER)
            * mc.options.getSoundSourceVolume(SoundSource.RECORDS);
    }

    // ══════════════ 通道生命周期 / channel lifecycle ══════════════

    /** 惰性挂接；已停的通道重建（资源重载/设备切换/欠载自愈）。
     *  每次挂接用新流实例（旧通道若残余 pump 不会双速抽干混音器），挂接中不重入。 */
    private static synchronized void ensureChannel() {
        if (mixer == null) mixer = new AudioMixer();
        if (handle != null && !handle.isStopped()) return;
        if (attaching) return;
        attaching = true;
        if (attachedOnce) DIAG.onReattach(System.nanoTime());   // 重建 = 欠载/重载证据
        attachedOnce = true;
        try {
            SoundManager sm = Minecraft.getInstance().getSoundManager();
            SoundEngine se = ((SoundManagerAccessor) sm).csc$getSoundEngine();
            ChannelAccess ca = ((SoundEngineAccessor) se).csc$getChannelAccess();
            handle = null;                       // 在挂接完成前先失效旧柄
            CscAudioStream fresh = new CscAudioStream(mixer);
            ca.createHandle(Library.Pool.STREAMING).thenAccept(h -> {
                synchronized (CscAudioEngine.class) {
                    attaching = false;
                    handle = h;
                }
                h.execute(ch -> {
                    ch.setVolume(1f);            // 音量已在混音里烘焙 / volume is baked into the mix
                    ch.disableAttenuation();     // 衰减已在混音里烘焙 / attenuation is baked too
                    ch.attachBufferStream(fresh);
                    ch.play();
                });
            }).exceptionally(t -> {
                synchronized (CscAudioEngine.class) {
                    attaching = false;
                }
                SchematicCompute.LOGGER.warn("CscAudioEngine: channel attach failed: {}", t.toString());
                return null;
            });
        } catch (Throwable t) {
            attaching = false;
            SchematicCompute.LOGGER.warn("CscAudioEngine: channel attach failed: {}", t.toString());
        }
    }

    /** 诊断：当前混音声部数（0 = 引擎未活动）。 */
    public static int activeVoices() {
        return mixer == null ? 0 : mixer.activeVoices();
    }

    /**
     * 每渲染帧补泵输出队列。原生泵送（{@code ChannelAccess.scheduleTick}）只有 20 Hz，
     * 与 50 ms 消费刚好打平——任何一次卡顿都会把 4 块队列抽干 → 声道停播 → 重挂多出
     * ≈200 ms 间隙（超多音符下「偶尔延迟/丢音」的根因）。按帧泵送把队列持续顶满。
     * <p>Per-render-frame pump. The vanilla pump cadence is 20 Hz, exactly matching the 50 ms
     * consumption — any single hitch drains the 4-buffer queue → the source stops → re-attach
     * adds a ≈200 ms gap (the "occasional latency / drop" under extreme density). Per-frame
     * pumping keeps the queue topped up.</p>
     */
    @net.neoforged.bus.api.SubscribeEvent
    public static void onRenderFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) {
        pump();
        // 时间线诊断：每 5 秒一条聚合报告（有活动才打）
        String report = DIAG.reportIfDue(System.nanoTime());
        if (report != null) SchematicCompute.LOGGER.info(report);
    }

    /** 补泵一次输出队列（幂等；通道空闲/已释放时无操作）。 */
    public static synchronized void pump() {
        if (handle != null && !handle.isStopped()) handle.execute(com.mojang.blaze3d.audio.Channel::updateStream);
    }
}
