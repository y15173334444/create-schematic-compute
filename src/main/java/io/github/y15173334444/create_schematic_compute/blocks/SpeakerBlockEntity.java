package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.MusicTransport;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.NoteEvent;
import io.github.y15173334444.create_schematic_compute.graph.SpeakerSink;
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
 * 音响 BE（R1-3）：<b>音频专用图宿主</b>（{@code SyncedGraphBlockEntity}），图 = {@code AUDIO_IN → SPEAKER_PLAY}。
 * <p>从音频频段读（band→AUDIO_IN.signalName，引脚自动收敛），经 {@code SPEAKER_PLAY} 在自身坐标发声
 * （实现 {@link SpeakerSink}）。增益/半径/静音为播放设置；红石高电平静音兜底。</p>
 * <p>Speaker BE: an audio-only graph host ({@code AUDIO_IN → SPEAKER_PLAY}) that reads an
 * audio band and plays at its own position via {@link SpeakerSink}.</p>
 */
public class SpeakerBlockEntity extends SyncedGraphBlockEntity implements SpeakerSink {

    /** 音频频段名（band，路由）→ AUDIO_IN.signalName。 */
    public String channelBand = "speaker";
    /** 播放声道（channel pinId：聚合/左/右…）→ 默认图里 AUDIO_IN.该声道 → SPEAKER_PLAY。 */
    public String channelName = "mix";
    /** 播放增益（叠加在音源增益链上）。 */
    public float gain = 1f;
    /** 半径覆盖（0 = 跟随声音定义）。 */
    public int radius = 0;
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
        // 红石静音兜底（plan §3.6）：只门控播放，不写回 mute（用户设置不被红石脉冲覆盖）。
        redstoneMuted = level.hasNeighborSignal(worldPosition);
        // 与 ProgramComputer/功放同款门控：运行开关关闭时不求值、不发声。
        if (!isRunning()) { onStopRunning(); return; }
        // 默认图：AUDIO_IN(band) → SPEAKER_PLAY(声道)（仅空图时建立）
        if (graph().nodes.isEmpty()) createDefaultGraph();
        for (GraphNode n : graph().nodes) if (n.type == NodeType.AUDIO_IN) n.signalName = channelBand;
        rs().checkGraphChanged(graph());
        if (graphChanged()) recompileEvaluatorFull();
        evaluator().setAudioTransports(audioTransports);
        evaluator().setAudioHostPos(worldPosition);
        evaluator().setSpeakerSink(this);
        evaluator().setAudioTickStamp(level.getGameTime());
        var in = rs().buildInputs(graph());
        evaluator().evaluate(in, runtimeState().pidState, 0.05f,
                runtimeState().delayQueues, runtimeState().flipflopStates, runtimeState().pulseTimers);
        broadcastEvalSnapshot();
        broadcastFlipflopDiff();
        setChanged();
    }

    /** 默认音频图：AUDIO_IN(band) → SPEAKER_PLAY(选声道)。单引脚多声道。 */
    private void createDefaultGraph() {
        GraphNode in = graph().addNode(NodeType.AUDIO_IN, 0, 0);
        in.signalName = channelBand;
        GraphNode play = graph().addNode(NodeType.SPEAKER_PLAY, 220, 0);
        play.params[0] = channelParam(channelName);
        graph().addConnectionWithPinIds(in.id, "0", play.id, "0");
        graph().bumpGeneration();
        setChanged();
    }

    /** 声道名 → SPEAKER_PLAY 声道参数（聚合 0 / 左 1 / 右 2）。 */
    private static float channelParam(String channel) {
        return "l".equals(channel) ? 1f : "r".equals(channel) ? 2f : 0f;
    }

    /** SPEAKER_PLAY 播放下沉：在自身坐标发声（套用音响增益/静音）。 */
    @Override
    public void play(List<NoteEvent> events, float gain) {
        if (level == null || level.isClientSide() || events == null || events.isEmpty()) return;
        if (mute) return;
        if (redstoneMuted) return;
        float sgain = gain * this.gain;
        List<NoteEvent> evs = new ArrayList<>(events.size());
        for (var e : events) evs.add(e.withGain(e.gain() * sgain));
        PacketDistributor.sendToPlayersTrackingChunk((ServerLevel) level,
            new ChunkPos(worldPosition), new NoteEventPacket(worldPosition, radius, level.getGameTime(), evs));
    }

    /** 应用设置（C2S 设置包）。频段/声道改动**原位**更新既有图节点，不清空用户布线。 */
    public void applySettings(String band, String channel, float g, int rad, boolean m) {
        this.channelBand = band == null ? "" : band;
        this.channelName = channel == null ? "" : channel;
        this.gain = Math.max(0f, Math.min(4f, g));
        this.radius = Math.max(0, rad);
        this.mute = m;
        if (level != null && !level.isClientSide()) {
            if (graph().nodes.isEmpty()) {
                createDefaultGraph();
            } else {
                // 原位改：AUDIO_IN 换频段、SPEAKER_PLAY 换声道；用户加的节点/连线全保留。
                for (GraphNode n : graph().nodes) {
                    if (n.type == NodeType.AUDIO_IN) n.signalName = channelBand;
                    else if (n.type == NodeType.SPEAKER_PLAY) n.params[0] = channelParam(channelName);
                }
                graph().bumpGeneration();
            }
        }
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void saveTypeSpecific(CompoundTag t, HolderLookup.Provider r) {
        t.putString("band", channelBand);
        t.putString("channel", channelName);
        t.putFloat("gain", gain);
        t.putInt("radius", radius);
        t.putBoolean("mute", mute);
    }

    @Override
    protected void loadTypeSpecific(CompoundTag t, HolderLookup.Provider r) {
        if (t.contains("band")) channelBand = t.getString("band");
        if (t.contains("channel")) channelName = t.getString("channel");
        if (t.contains("gain")) gain = t.getFloat("gain");
        if (t.contains("radius")) radius = t.getInt("radius");
        if (t.contains("mute")) mute = t.getBoolean("mute");
    }

    @Override
    protected void acceptTypeSpecific(SyncedGraphBlockEntity src) {
        if (!(src instanceof SpeakerBlockEntity s)) return;
        this.channelBand = s.channelBand;
        this.channelName = s.channelName;
        this.gain = s.gain;
        this.radius = s.radius;
        this.mute = s.mute;
    }
}
