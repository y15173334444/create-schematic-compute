package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.AudioCurve;
import io.github.y15173334444.create_schematic_compute.graph.MusicTransport;
import io.github.y15173334444.create_schematic_compute.graph.NoteEvent;
import io.github.y15173334444.create_schematic_compute.graph.SpeakerSink;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import io.github.y15173334444.create_schematic_compute.network.MusicStopPacket;
import io.github.y15173334444.create_schematic_compute.network.NoteEventPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 音响 BE（R1-3）：<b>音频专用图宿主</b>（{@code SyncedGraphBlockEntity}）——图完全由玩家在
 * 编辑器里摆放（典型链路 {@code BUS_IN --音频线--> SPEAKER_PLAY}），BE 不播种任何节点。
 * <p>从音频频段读（band→音频 BUS_IN.signalName，经求值器 BUS_IN 音频分支读 AudioBands），
 * 经 {@code SPEAKER_PLAY} 在自身坐标发声
 * （实现 {@link SpeakerSink}）。增益/半径/静音为播放设置；红石高电平静音兜底。</p>
 * <p>Speaker BE: an audio-only graph host ({@code SyncedGraphBlockEntity}) whose graph is authored
 * entirely by the player in the editor (the usual chain being
 * {@code BUS_IN --audio wire--> SPEAKER_PLAY}) — the BE seeds no nodes. It reads an audio band
 * and plays at its own position via {@link SpeakerSink}.</p>
 */
public class SpeakerBlockEntity extends SyncedGraphBlockEntity implements SpeakerSink {

    /** 播放增益（叠加在音源增益链上）。 */
    public float gain = 1f;
    /** 可听半径（格，1–4096，默认 48）。 / Audible radius in blocks (1-4096, default 48). */
    public int radius = 48;
    /** 静音（用户设置，持久化）。红石高电平只做**运行时**门控，不写回本字段。 */
    public boolean mute;

    /** 红石静音的运行时态（每 tick 采样邻居信号；不持久化、不覆盖用户设置）。 */
    private boolean redstoneMuted;

    private final Map<Integer, MusicTransport> audioTransports = new HashMap<>();

    public SpeakerBlockEntity(BlockPos pos, BlockState s) {
        super(SchematicCompute.SPEAKER_BE.get(), pos, s);
    }

    public void tick() {
        if (level == null || level.isClientSide()) return;
        ensureBusRegistered();
        // 第 ⑮ 步（P1-8 补线）：挂起全量同步按 grace 窗口冲刷；必须在所有早退之前，
        // 停机宿主也要能把图推给新加入的编辑者（Blueprint 同款）。
        // Step ⑮ (P1-8 wiring): flush the pending full sync within the grace window; must
        // sit before every early return so a stopped host still ships its graph to a newly
        // joined editor (same as Blueprint).
        flushPendingFullSync();
        // 红石静音兜底（plan §3.6）：只门控播放，不写回 mute（用户设置不被红石脉冲覆盖）。
        redstoneMuted = level.hasNeighborSignal(worldPosition);
        // 与 ProgramComputer/功放同款门控：运行开关关闭时不求值、不发声。
        if (!isRunning()) { onStopRunning(); return; }
        // 图完全由玩家在编辑器里摆放，BE 不播种任何节点（旧「空图建默认图」是测试期遗留，
        // 2026-10-11 移除）。订阅哪个频段 = 图里那根总线输入的名字。
        // The graph is authored entirely by the player in the editor — the BE seeds no nodes
        // (the old empty-graph default seeding was a test-era leftover, removed 2026-10-11).
        rs().checkGraphChanged(graph());
        if (graphChanged()) recompileEvaluatorFull();
        evaluator().setAudioTransports(audioTransports);
        evaluator().setAudioHostPos(worldPosition);
        evaluator().setSpeakerSink(this);
        evaluator().setAudioTickStamp(level.getGameTime());
        // 第 ⑥ 步（P3 宿主面补线）：音响图的 VALUES 类含 REDSTONE_IN，此前从不刷新链路输入，
        // 图内 REDSTONE_IN 恒读 0（红石面残缺）。混合回退口径与 Sensor/ControlSeat/Monitor 一致
        //（refreshInputs：扫描非零信号，保留 push 送达值作回退）。
        // Step ⑥ (P3 host-face wiring): the speaker's VALUES category includes REDSTONE_IN,
        // but link inputs were never refreshed — every REDSTONE_IN read 0 (a crippled
        // redstone face). Hybrid-fallback semantics match Sensor/ControlSeat/Monitor
        // (refreshInputs: scan non-zero signals, keep push-delivered values as fallback).
        rs().refreshInputs();
        var in = rs().buildInputs(graph());
        var results = evaluator().evaluate(in, runtimeState().pidState, 0.05f,
                runtimeState().delayQueues, runtimeState().flipflopStates, runtimeState().pulseTimers);
        // 第 ⑩ 步（P3 宿主面补线）：写出红石输出。准入名单现未给音响 OUTPUT 类（REDSTONE_OUT
        // 暂不可从菜单添加），但 op 通道不校验准入、旧档也可能携带此类节点——写出口径与其他
        // 宿主一致；图内无 REDSTONE_OUT 时 results 为空表，零副作用（lastOutputs 清空即无发射）。
        // Step ⑩ (P3 host-face wiring): write redstone outputs back. The speaker's allowance
        // has no OUTPUT category yet (REDSTONE_OUT is not menu-addable), but the op channel
        // does not check allowances and legacy saves may carry such nodes — same write
        // discipline as every other host; with no REDSTONE_OUT in the graph, results is empty
        // and this is a no-op (lastOutputs cleared, nothing transmitted).
        rs().writeOutputs(results);
        broadcastEvalSnapshot();
        // 若 band 通道映射发生变化则同步到客户端（第 ⑫ 步，与其余宿主同位）
        // Sync band channel mappings to clients if they changed (step 12, same slot as
        // every other host).
        BusChannelHelper.syncIfBandsChanged(graph(), worldPosition, lastBusHashMap(), level);
        broadcastFlipflopDiff();
        setChanged();
    }

    /** SPEAKER_PLAY 播放下沉：在自身坐标发声（套用音响增益/静音）。 */
    @Override
    public void play(List<NoteEvent> events, float gain) {
        play(events, gain, List.of());
    }

    /** 带波形整形曲线链的播放：控制点对随 {@link NoteEventPacket} 原样下发（无量化——
     *  客户端以 float 精度烘 LUT 逐样本应用），空链 = 无整形。
     *  Playback with a waveshaper curve chain: control-point pairs ship inside the note
     *  packet raw (no quantization — the client bakes the LUT at float precision and
     *  applies it per sample); an empty chain means no shaping. */
    @Override
    public void play(List<NoteEvent> events, float gain, List<AudioCurve.Curve> waveCurves) {
        if (level == null || level.isClientSide() || events == null || events.isEmpty()) return;
        if (mute) return;
        if (redstoneMuted) return;
        float sgain = gain * this.gain;
        List<NoteEvent> evs = new ArrayList<>(events.size());
        for (var e : events) evs.add(e.withGain(e.gain() * sgain));
        PacketDistributor.sendToPlayersTrackingChunk((ServerLevel) level,
            new ChunkPos(worldPosition),
            new NoteEventPacket(worldPosition, radius, level.getGameTime(), evs,
                waveCurves == null ? List.of() : waveCurves));
    }

    /** 停止标记（一次性，MUSIC 停止/跳转沿随音频引用到达）：通知追踪玩家清除本音响
     *  排队的未播声部——预播窗口加宽后没有它，传输停止会拖一个窗口长的尾巴。
     *  与 play 不同，静音/红石静音<b>不拦截</b>停止标记：静音期间本无排程音符，
     *  多发一条空清除无害；漏发则恢复播放前的旧尾巴会残留。
     *  Stop marker (one-shot, arrives with the audio ref on a MUSIC stop/seek edge): tell
     *  tracking players to cancel this speaker's queued unplayed voices — without it a
     *  widened pre-roll drags a one-window tail after a transport stop. Unlike play, mute /
     *  redstone-mute do NOT gate the marker: a muted speaker has nothing queued (an extra
     *  empty cancel is harmless), while a suppressed one would leave a stale tail. */
    @Override
    public void stopPlayback() {
        if (level == null || level.isClientSide()) return;
        PacketDistributor.sendToPlayersTrackingChunk((ServerLevel) level,
            new ChunkPos(worldPosition), new MusicStopPacket(worldPosition));
    }

    /** 应用设置（C2S 设置包）：gain/radius/mute 是 BE 播放设置。图不在这里动——节点由玩家在
     *  编辑器里摆放（旧「空图种默认图」是测试期遗留，2026-10-11 移除）。SPEAKER_PLAY 恒等直通
     *  （2026-10-04 起无声道参数——拆分归上游 CHANNEL）。
     *  Apply settings (C2S packet): gain/radius/mute are BE playback settings. The graph is left
     *  alone here — nodes are authored by the player in the editor (the old empty-graph default
     *  seeding was a test-era leftover, removed 2026-10-11). SPEAKER_PLAY passes through
     *  identically (no channel param since 2026-10-04 — splitting belongs to the upstream
     *  CHANNEL). */
    public void applySettings(float g, int rad, boolean m) {
        this.gain = Math.max(0f, Math.min(4f, g));
        this.radius = Math.max(1, rad);
        this.mute = m;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void saveTypeSpecific(CompoundTag t, HolderLookup.Provider r) {
        t.putFloat("gain", gain);
        t.putInt("radius", radius);
        t.putBoolean("mute", mute);
    }

    @Override
    protected void loadTypeSpecific(CompoundTag t, HolderLookup.Provider r) {
        if (t.contains("gain")) gain = t.getFloat("gain");
        if (t.contains("radius")) radius = t.getInt("radius");
        if (t.contains("mute")) mute = t.getBoolean("mute");
    }

    @Override
    protected void acceptTypeSpecific(SyncedGraphBlockEntity src) {
        if (!(src instanceof SpeakerBlockEntity s)) return;
        this.gain = s.gain;
        this.radius = s.radius;
        this.mute = s.mute;
    }
}
