package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.AudioCurve;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.MusicTransport;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.NoteEvent;
import io.github.y15173334444.create_schematic_compute.graph.SpeakerSink;
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
 * 音响 BE（R1-3）：<b>音频专用图宿主</b>（{@code SyncedGraphBlockEntity}），图 = {@code BUS_IN --音频线--> SPEAKER_PLAY}。
 * <p>从音频频段读（band→音频 BUS_IN.signalName，经求值器 BUS_IN 音频分支读 AudioBands），
 * 经 {@code SPEAKER_PLAY} 在自身坐标发声
 * （实现 {@link SpeakerSink}）。增益/半径/静音为播放设置；红石高电平静音兜底。</p>
 * <p>Speaker BE: an audio-only graph host ({@code BUS_IN --audio wire--> SPEAKER_PLAY}) that reads an
 * audio band and plays at its own position via {@link SpeakerSink}.</p>
 */
public class SpeakerBlockEntity extends SyncedGraphBlockEntity implements SpeakerSink, GraphHost.TickHooks {

    /** 默认图初始频段名。运行时以图内 BUS_IN 的名字为准（编辑器可改、随节点 NBT 持久化）。
     *  Initial band name for the default graph only — the graph's BUS_IN name rules at runtime. */
    public String channelBand = "speaker";
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

    /** tick 体已收敛进 GraphHost.tickBody（K23）：15 步共同编排住骨架，音响的偏差化为
     *  声明与钩子——矩阵独有缺口「停止早退早于默认图建立」由 stopSlot=BEFORE_GRAPH_CHECK
     *  承载，红石静音采样在缝 1、默认图在缝 2、音频注入在缝 3；⑥⑩ 已在骨架（P3 补线），
     *  ⑬ 在位、⑫ 缺保持缺失（批 2 补步）。
     *  The tick body converged into GraphHost.tickBody (K23); the matrix's unique
     *  "stop early-return precedes the default graph" gap rides stopSlot=BEFORE_GRAPH_CHECK,
     *  with the redstone-mute sample at seam 1, the default graph at seam 2 and the audio
     *  injection at seam 3. */
    public void tick() {
        tickBody(this);
    }

    // ── tick 编排偏差声明（K23 骨架钩子）/ tick-orchestration deviations (K23 hooks) ──

    /** 缝 1（⑮ 后 ② 前）：红石静音兜底（plan §3.6）——只门控播放，不写回 mute
     *  （用户设置不被红石脉冲覆盖）。
     *  Seam 1 (after ⑮, before ②): redstone-mute sampling (plan §3.6) — gates playback
     *  only, never writes back the user's mute setting. */
    @Override public void beforeLit() {
        redstoneMuted = level.hasNeighborSignal(worldPosition);
    }

    /** ⑤ 停机检查先于 ③（矩阵独有缺口「停止早退早于默认图建立」原样保留）。
     *  Step ⑤ stop check before ③ (the matrix's unique gap kept as-is). */
    @Override public GraphHost.StopSlot stopSlot() { return GraphHost.StopSlot.BEFORE_GRAPH_CHECK; }

    /** 缝 2（⑤ 早退后 ③ 前）：默认图建立。
     *  Seam 2 (after the ⑤ early-return, before ③): default-graph bootstrap. */
    @Override public void beforeGraphCheck() {
        // 默认图：BUS_IN(band) --音频线--> SPEAKER_PLAY(声道)（仅空图时建立；频段读取走
        // BUS_IN 音频分支——输出接音频线即读 AudioBands，2026-10-03 起 AUDIO_IN 节点已移除）
        if (graph().nodes.isEmpty()) createDefaultGraph();
        // BUS_IN 的频段名归图所有（用户在编辑器里改、随节点 NBT 持久化）——旧版此处每 tick
        // 把音频 BUS_IN 强制写回 channelBand 字段（默认图时代遗留），用户改名字总被重置为
        // "speaker"。订阅哪个频段 = 图里那根总线输入的名字，BE 不再覆盖。
        // BUS_IN band names belong to the graph (edited in the editor, persisted in node
        // NBT) — the old per-tick re-point to the channelBand field was a default-graph-era
        // leftover that kept resetting user edits.
    }

    /** 缝 3（④ 后 ⑥ 前）：音频宿主状态注入（仿雷达位置注入范式）。
     *  Seam 3 (after ④, before ⑥): audio-host state injection (the radar-style
     *  positional injection). */
    @Override public void beforeInputs() {
        evaluator().setAudioTransports(audioTransports);
        evaluator().setAudioHostPos(worldPosition);
        evaluator().setSpeakerSink(this);
        evaluator().setAudioTickStamp(level.getGameTime());
    }

    /** ⑬ 触发器差分在位（矩阵 ⑬ 列 ✓）。 / Step ⑬ present (matrix ✓). */
    @Override public boolean wantsFlipflopDiff() { return true; }

    /** ⑨ 全状态档（6 参）。 / Step ⑨ full-state (6-arg) form. */
    @Override public GraphHost.EvalVariant evalVariant() { return GraphHost.EvalVariant.FULL_STATE; }

    /** 默认音频图：BUS_IN(band) --音频线--> SPEAKER_PLAY（恒等直通，2026-10-04 起无声道选择——
     *  拆分归上游 CHANNEL，音响是哑终端）。单引脚多声道；频段读取由 BUS_IN 音频分支承担
     * （signalBands ≥1 条保 bandCount≥1，音频引用才落到引脚 0）。 */
    private void createDefaultGraph() {
        GraphNode in = graph().addNode(NodeType.BUS_IN, 0, 0);
        in.signalName = channelBand;
        in.signalBands = new ArrayList<>(List.of(channelBand));
        GraphNode play = graph().addNode(NodeType.SPEAKER_PLAY, 220, 0);
        graph().addConnection(in.id, 0, play.id, 0); // pinId 自动派生（BUS 出 = 频段名）
        graph().bumpGeneration();
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

    /** 应用设置（C2S 设置包）：gain/radius/mute 是 BE 播放设置；band 仅作空图默认图的初始
     *  频段名（运行时频段名归图节点，编辑器里改），此处不再重写图。SPEAKER_PLAY 恒等直通
     * （2026-10-04 起无声道参数——拆分归上游 CHANNEL）。
     *  Apply settings (C2S packet): gain/radius/mute are BE playback settings; band only seeds
     *  an empty graph's BUS_IN name (the runtime band name belongs to the graph node) — no
     *  graph rewrite here. SPEAKER_PLAY passes through identically (no channel param since
     *  2026-10-04 — splitting belongs to the upstream CHANNEL). */
    public void applySettings(String band, float g, int rad, boolean m) {
        this.channelBand = band == null ? "" : band;
        this.gain = Math.max(0f, Math.min(4f, g));
        this.radius = Math.max(1, rad);
        this.mute = m;
        if (level != null && !level.isClientSide() && graph().nodes.isEmpty()) {
            createDefaultGraph();
        }
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void saveTypeSpecific(CompoundTag t, HolderLookup.Provider r) {
        t.putString("band", channelBand);
        t.putFloat("gain", gain);
        t.putInt("radius", radius);
        t.putBoolean("mute", mute);
    }

    @Override
    protected void loadTypeSpecific(CompoundTag t, HolderLookup.Provider r) {
        if (t.contains("band")) channelBand = t.getString("band");
        if (t.contains("gain")) gain = t.getFloat("gain");
        if (t.contains("radius")) radius = t.getInt("radius");
        if (t.contains("mute")) mute = t.getBoolean("mute");
    }

    @Override
    protected void acceptTypeSpecific(SyncedGraphBlockEntity src) {
        if (!(src instanceof SpeakerBlockEntity s)) return;
        this.channelBand = s.channelBand;
        this.gain = s.gain;
        this.radius = s.radius;
        this.mute = s.mute;
    }
}
