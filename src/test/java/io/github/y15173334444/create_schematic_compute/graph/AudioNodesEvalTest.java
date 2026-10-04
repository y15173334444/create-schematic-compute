package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.network.AudioBands;
import io.github.y15173334444.create_schematic_compute.network.SignalBus;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 音频节点求值测试（R1 单引脚多声道）：MUSIC→AMP→AUDIO_OUT 发到频段、SPEAKER_PLAY 选声道播放。
 * Audio-node evaluation tests (R1 single-pin multi-channel): MUSIC→AMP→AUDIO_OUT publishes
 * to a band; SPEAKER_PLAY selects a channel and hands it to the sink.
 */
class AudioNodesEvalTest {

    private static final float DT = 0.1f; // tempo 1000 (10 t/s) ⇒ delta 1.0 NBS tick

    @AfterEach void cleanup() { AudioBands.clear(); SignalBus.clear(); }

    private record Fixture(NodeGraph graph, GraphEvaluator ev, GraphNode music, GraphNode amp,
                           GraphNode audioOut, Map<Integer, MusicTransport> transports) {}

    /** CONST(1)→MUSIC.play，MUSIC.audio→AMP.audio→AUDIO_OUT(band B)。 */
    private Fixture build(NbsSong song, float gain) {
        NodeGraph g = new NodeGraph();
        GraphNode constPlay = g.addNode(NodeType.CONST, 0, 0);
        constPlay.params[0] = 1f;
        GraphNode music = g.addNode(NodeType.MUSIC, 100, 0);
        music.song = song;
        GraphNode amp = g.addNode(NodeType.AMP, 200, 0);
        amp.params[0] = gain;
        GraphNode audioOut = g.addNode(NodeType.AUDIO_OUT, 300, 0);
        audioOut.signalName = "B";

        assertTrue(g.addConnection(constPlay.id, 0, music.id, 0));
        assertTrue(g.addConnection(music.id, 0, amp.id, 0));
        assertTrue(g.addConnection(amp.id, 0, audioOut.id, 0));

        Map<Integer, MusicTransport> transports = new HashMap<>();
        GraphEvaluator ev = new GraphEvaluator(g);
        ev.restoreSubState(new RuntimeState());
        ev.setAudioTransports(transports);
        ev.setAudioHostPos(new BlockPos(0, 0, 0));
        return new Fixture(g, ev, music, amp, audioOut, transports);
    }

    private static NbsSong song(int... keys) {
        NbsSong s = new NbsSong();
        s.tempo = 1000;
        int tick = 0;
        for (int k : keys) s.putNote(tick++, 0, 2, k, 100, 100, 0);
        return s;
    }

    @Test
    @DisplayName("链路发布：MUSIC 展开 → AMP 增益 → 频段音源 / chain publishes a gain-scaled source")
    void chainPublishes() {
        Fixture f = build(song(45), 2f);
        f.ev().evaluate(List.of(), Map.of(), DT, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        var events = AudioBands.get(AudioBands.bandKey("B", "B"), 0L).events();
        assertEquals(1, events.size());
        assertEquals(45, events.get(0).key());
        assertEquals(2f, AudioBands.get(AudioBands.bandKey("B", "B"), 0L).gain(), 1e-6, "AMP gain applied to the source chain");
    }

    @Test
    @DisplayName("边沿去重：play 保持高电平不重播 / a held-high play does not restart each tick")
    void edgeDedupNoRestart() {
        Fixture f = build(song(40, 50), 1f);
        var seat = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0);
        f.ev().evaluate(List.of(), Map.of(), DT, seat);
        var evs = AudioBands.get(AudioBands.bandKey("B", "B"), 0L).events();
        assertEquals(2, evs.size(), "ticks 0-1 both fire within the pre-roll window");
        assertEquals(40, evs.get(0).key(), "tick 0 first");
        assertEquals(50, evs.get(1).key(), "tick 1 pre-rolled with its target time");
        f.ev().evaluate(List.of(), Map.of(), DT, seat);
        assertTrue(AudioBands.get(AudioBands.bandKey("B", "B"), 0L).events().isEmpty(),
            "held-high play neither restarts nor re-fires pre-rolled ticks");
    }

    @Test
    @DisplayName("seek 值变化即当帧快照跳转（含首次给值）/ a seek value change snapshots a jump that frame")
    void seekValueChangeJumps() {
        Fixture f = build(song(40, 50), 1f);
        f.music().song = new NbsSong();
        f.music().song.tempo = 1000;
        f.music().song.putNote(0, 0, 2, 40, 100, 100, 0);
        f.music().song.putNote(5, 0, 2, 50, 100, 100, 0);
        GraphNode seek = f.graph().addNode(NodeType.CONST, -100, 100);
        seek.params[0] = 5f;
        assertTrue(f.graph().addConnection(seek.id, 0, f.music().id, 2)); // seek 引脚

        var seat = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0);
        f.ev().evaluate(List.of(), Map.of(), DT, seat);
        var events = AudioBands.get(AudioBands.bandKey("B", "B"), 0L).events();
        assertEquals(1, events.size(), "only the tick-5 note fires (tick 0 skipped by the seek)");
        assertEquals(50, events.get(0).key());
        // seek 值保持不变 → 不再跳转，正常前进到 tick 6（无音符 → 空事件）
        f.ev().evaluate(List.of(), Map.of(), DT, seat);
        assertTrue(AudioBands.get(AudioBands.bandKey("B", "B"), 0L).events().isEmpty(), "held seek value must not re-jump");
    }

    @Test
    @DisplayName("BUS_IN 音频分支门控：只有挂音频线才读频段 / the BUS_IN audio branch requires an audio wire")
    void busInAudioBranchGating() {
        NodeGraph g = new NodeGraph();
        GraphNode busIn = g.addNode(NodeType.BUS_IN, 0, 0);
        busIn.signalName = "B";
        GraphNode amp = g.addNode(NodeType.AMP, 100, 0);       // AMP 音频入 = AUDIO 域
        GraphNode add = g.addNode(NodeType.ADD, 200, 0);       // ADD 输入 = FLOAT 域
        assertTrue(g.addConnection(busIn.id, 0, amp.id, 0));
        assertTrue(g.hasAudioSinkConnection(busIn.id), "wired to an audio pin → audio branch");

        GraphNode busIn2 = g.addNode(NodeType.BUS_IN, 0, 100);
        busIn2.signalName = "B";
        assertTrue(g.addConnection(busIn2.id, 0, add.id, 0));
        assertFalse(g.hasAudioSinkConnection(busIn2.id), "wired to a float pin → float branch (no hijack)");
    }

    @Test
    @DisplayName("增益钳 0..4 / gain is clamped to 0..4")
    void gainClamped() {
        Fixture f = build(song(45), 10f);
        f.ev().evaluate(List.of(), Map.of(), DT, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(4f, AudioBands.get(AudioBands.bandKey("B", "B"), 0L).gain(), 1e-6);
    }

    @Test
    @DisplayName("MUSIC 输出：playing/tick/done / MUSIC outputs playing/tick/done")
    void musicOutputs() {
        Fixture f = build(song(40, 50, 60), 1f);
        f.ev().evaluate(List.of(), Map.of(), DT, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(1f, f.ev().getNodeOutput(f.music().id, 1), 0.001f, "playing=1 (mid-song)");
        assertEquals(0f, f.ev().getNodeOutput(f.music().id, 4), 0.001f, "no done pulse mid-song");
    }

    @Test
    @DisplayName("SPEAKER_PLAY 选声道（左/右按声像）播放 / SPEAKER_PLAY selects a channel by panning")
    void speakerPlayChannelSelect() {
        NbsSong s = new NbsSong();
        s.tempo = 1000;
        s.putNote(0, 0, 2, 40, 100, 0, 0);     // 全左
        s.putNote(0, 1, 2, 50, 100, 150, 0);   // 偏右
        Fixture f = build(s, 1f);
        GraphNode play = f.graph().addNode(NodeType.SPEAKER_PLAY, 400, 0);
        play.params[0] = 1f;                   // 选「左」
        assertTrue(f.graph().addConnection(f.amp().id, 0, play.id, 0));

        List<NoteEvent> got = new ArrayList<>();
        f.ev().setSpeakerSink((events, gain) -> got.addAll(events));
        f.ev().evaluate(List.of(), Map.of(), DT, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(2, got.size());
        assertEquals(40, got.get(0).key()); assertEquals(1f, got.get(0).gain(), 1e-6);
        assertEquals(50, got.get(1).key());
        assertEquals((float) Math.cos(0.75 * Math.PI / 2), got.get(1).gain(), 1e-6, "pan 150 keeps its equal-power L weight");
    }

    @Test
    @DisplayName("SPEAKER_PLAY 全声道序号：sub 按低音路由 / SPEAKER_PLAY full channel ordinals: sub routes bass")
    void speakerPlaySubChannel() {
        NbsSong s = new NbsSong();
        s.tempo = 1000;
        s.putNote(0, 0, 1, 45, 100, 100, 0);   // bass 乐器（pan 中心）
        s.putNote(0, 1, 0, 60, 100, 100, 0);   // harp 中键（不进 sub）
        Fixture f = build(s, 1f);
        GraphNode play = f.graph().addNode(NodeType.SPEAKER_PLAY, 400, 0);
        play.params[0] = (float) ChannelLayout.channelIndex("sub"); // 4
        assertTrue(f.graph().addConnection(f.amp().id, 0, play.id, 0));

        List<NoteEvent> got = new ArrayList<>();
        f.ev().setSpeakerSink((events, gain) -> got.addAll(events));
        f.ev().evaluate(List.of(), Map.of(), DT, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(1, got.size(), "only the bass instrument reaches sub");
        assertEquals(45, got.get(0).key());
        assertEquals(1f, got.get(0).gain(), 1e-6);
    }

    @Test
    @DisplayName("私有频段音频：PRIVATE_OUT 发布 → PRIVATE_IN 读取 / private band audio: publish then read")
    void privateBandAudio() {
        // 图 1：MUSIC→AMP→PRIVATE_OUT("px")——音频线接私有频段引脚（域特判放行）
        NodeGraph g1 = new NodeGraph();
        GraphNode constPlay1 = g1.addNode(NodeType.CONST, 0, 0);
        constPlay1.params[0] = 1f;
        GraphNode music = g1.addNode(NodeType.MUSIC, 100, 0);
        music.song = song(45);
        GraphNode amp = g1.addNode(NodeType.AMP, 200, 0);
        amp.params[0] = 1f;
        GraphNode pout = g1.addNode(NodeType.PRIVATE_OUT, 300, 0);
        pout.signalName = "px";
        assertTrue(g1.addConnection(constPlay1.id, 0, music.id, 0));
        assertTrue(g1.addConnection(music.id, 0, amp.id, 0));
        assertTrue(g1.addConnection(amp.id, 0, pout.id, 0), "audio wire into PRIVATE_OUT accepted (band-pin domain rule)");

        GraphEvaluator ev1 = new GraphEvaluator(g1);
        ev1.restoreSubState(new RuntimeState());
        ev1.setAudioTransports(new HashMap<>());
        ev1.setAudioHostPos(new BlockPos(0, 0, 0));
        ev1.evaluate(List.of(), Map.of(), DT, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        // 图 2：PRIVATE_IN("px")→AUDIO_OUT("rx")——私有读取走 exactly-once 游标，再发布到 rx
        NodeGraph g2 = new NodeGraph();
        GraphNode pin = g2.addNode(NodeType.PRIVATE_IN, 0, 0);
        pin.signalName = "px";
        GraphNode out = g2.addNode(NodeType.AUDIO_OUT, 100, 0);
        out.signalName = "rx";
        assertTrue(g2.addConnection(pin.id, 0, out.id, 0), "PRIVATE_OUT wire into an audio pin accepted");

        GraphEvaluator ev2 = new GraphEvaluator(g2);
        ev2.restoreSubState(new RuntimeState());
        ev2.setAudioTransports(new HashMap<>());
        ev2.setAudioHostPos(new BlockPos(0, 0, 0));
        ev2.evaluate(List.of(), Map.of(), DT, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        var events = AudioBands.get(AudioBands.bandKey("rx", "rx"), 0L).events();
        assertEquals(1, events.size(), "the note crosses the private band end to end");
        assertEquals(45, events.get(0).key());
    }

    @Test
    @DisplayName("频段音频标志：BUS_OUT 按引脚对端域写入 SignalBus / band audio flags: BUS_OUT derives them from pin peers")
    void bandAudioFlagsPublished() {
        NodeGraph g = new NodeGraph();
        GraphNode constPlay = g.addNode(NodeType.CONST, 0, 0);
        constPlay.params[0] = 1f;
        GraphNode music = g.addNode(NodeType.MUSIC, 100, 0);
        music.song = song(45);
        GraphNode busOut = g.addNode(NodeType.BUS_OUT, 200, 0);
        busOut.signalName = "fb";
        busOut.signalBands = new ArrayList<>(List.of("ba", "bb"));
        assertTrue(g.addConnection(constPlay.id, 0, music.id, 0));
        assertTrue(g.addConnection(music.id, 0, busOut.id, 0), "MUSIC feeds band ba (audio)");

        GraphEvaluator ev = new GraphEvaluator(g);
        ev.restoreSubState(new RuntimeState());
        ev.setAudioTransports(new HashMap<>());
        ev.setAudioHostPos(new BlockPos(0, 0, 0));
        ev.evaluate(List.of(), Map.of(), DT, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertTrue(io.github.y15173334444.create_schematic_compute.network.SignalBus.isAudioBand("fb", "ba"),
            "band ba has an audio peer");
        assertFalse(io.github.y15173334444.create_schematic_compute.network.SignalBus.isAudioBand("fb", "bb"),
            "band bb has no wire");
        int stamp = io.github.y15173334444.create_schematic_compute.network.SignalBus.audioStamp("fb");

        // 拆掉音频连线后再求值：标志翻 false、版本自增（宿主推送据此重发）
        g.removeConnection(music.id, 0, busOut.id, 0);
        ev.evaluate(List.of(), Map.of(), DT, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertFalse(io.github.y15173334444.create_schematic_compute.network.SignalBus.isAudioBand("fb", "ba"));
        assertTrue(io.github.y15173334444.create_schematic_compute.network.SignalBus.audioStamp("fb") > stamp,
            "the flag version moved so hosts push the new definition");
    }

    @Test
    @DisplayName("频段隔离：BUS_IN 按频段读各自的音频键 / per-band isolation: BUS_IN reads each band's own key")
    void busInPerBandIsolation() {
        // 发布方：MUSIC→BUS_OUT("fb") 的 ba（音频）；bb 无音频输入（浮点）
        NodeGraph g1 = new NodeGraph();
        GraphNode constPlay = g1.addNode(NodeType.CONST, 0, 0);
        constPlay.params[0] = 1f;
        GraphNode music = g1.addNode(NodeType.MUSIC, 100, 0);
        music.song = song(45);
        GraphNode busOut = g1.addNode(NodeType.BUS_OUT, 200, 0);
        busOut.signalName = "fb";
        busOut.signalBands = new ArrayList<>(List.of("ba", "bb"));
        assertTrue(g1.addConnection(constPlay.id, 0, music.id, 0));
        assertTrue(g1.addConnection(music.id, 0, busOut.id, 0));

        GraphEvaluator ev1 = new GraphEvaluator(g1);
        ev1.restoreSubState(new RuntimeState());
        ev1.setAudioTransports(new HashMap<>());
        ev1.setAudioHostPos(new BlockPos(0, 0, 0));
        ev1.evaluate(List.of(), Map.of(), DT, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        // 订阅方：BUS_IN("fb") 的 ba → AUDIO_OUT；bb 不接
        NodeGraph g2 = new NodeGraph();
        GraphNode busIn = g2.addNode(NodeType.BUS_IN, 0, 0);
        busIn.signalName = "fb";
        busIn.signalBands = new ArrayList<>(List.of("ba", "bb"));
        GraphNode rx = g2.addNode(NodeType.AUDIO_OUT, 100, 0);
        rx.signalName = "rx";
        assertTrue(g2.addConnection(busIn.id, 0, rx.id, 0), "band ba wired to an audio consumer");

        GraphEvaluator ev2 = new GraphEvaluator(g2);
        ev2.restoreSubState(new RuntimeState());
        ev2.setAudioTransports(new HashMap<>());
        ev2.setAudioHostPos(new BlockPos(0, 0, 0));
        ev2.evaluate(List.of(), Map.of(), DT, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        var events = AudioBands.get(AudioBands.bandKey("rx", "rx"), 0L).events();
        assertEquals(1, events.size(), "band ba's note arrives through the subscriber");
        assertEquals(45, events.get(0).key());
    }

    @Test
    @DisplayName("5.1 布局求值：六个声道各走各的频段 / 5.1 evaluation: six channels route to their own bands")
    void surround51Routing() {
        NbsSong s = new NbsSong();
        s.tempo = 1000;
        s.putNote(0, 0, 0, 45, 100, 0, 0);     // 极左 harp
        s.putNote(0, 1, 0, 46, 100, 100, 0);   // 中心 harp
        s.putNote(0, 2, 0, 47, 100, 200, 0);   // 极右 harp
        s.putNote(0, 3, 1, 48, 100, 100, 0);   // bass 乐器
        s.putNote(0, 4, 0, 20, 100, 100, 0);   // 低键 harp（key<24）

        NodeGraph g = new NodeGraph();
        GraphNode constPlay = g.addNode(NodeType.CONST, 0, 0);
        constPlay.params[0] = 1f;
        GraphNode music = g.addNode(NodeType.MUSIC, 100, 0);
        music.song = s;
        GraphNode ch = g.addNode(NodeType.CHANNEL, 200, 0);
        ch.params[0] = (float) ChannelLayout.SURROUND_5_1;
        ch.ensureChannelBands();
        String[] pinNames = ch.signalBands.toArray(new String[0]);
        assertArrayEquals(new String[]{"l", "r", "c", "sub", "ls", "rs"}, pinNames);

        GraphNode[] outs = new GraphNode[pinNames.length];
        assertTrue(g.addConnection(constPlay.id, 0, music.id, 0));
        assertTrue(g.addConnection(music.id, 0, ch.id, 0));
        for (int i = 0; i < pinNames.length; i++) {
            outs[i] = g.addNode(NodeType.AUDIO_OUT, 300 + i * 100, 0);
            outs[i].signalName = "ch_" + pinNames[i];
            assertTrue(g.addConnection(ch.id, i, outs[i].id, 0));
        }

        GraphEvaluator ev = new GraphEvaluator(g);
        ev.restoreSubState(new RuntimeState());
        ev.setAudioTransports(new HashMap<>());
        ev.setAudioHostPos(new BlockPos(0, 0, 0));
        ev.evaluate(List.of(), Map.of(), DT, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        // l：极左全增益；中心/右侧在 LCR 下权重 0 不进
        var l = AudioBands.get(AudioBands.bandKey("ch_l", "ch_l"), 0L).events();
        assertEquals(1, l.size()); assertEquals(45, l.get(0).key()); assertEquals(1f, l.get(0).gain(), 1e-6);
        // r：极右全增益
        var r = AudioBands.get(AudioBands.bandKey("ch_r", "ch_r"), 0L).events();
        assertEquals(1, r.size()); assertEquals(47, r.get(0).key()); assertEquals(1f, r.get(0).gain(), 1e-6);
        // c：中心 pan 的三个非低音音符（中心 harp、bass、低键）
        var c = AudioBands.get(AudioBands.bandKey("ch_c", "ch_c"), 0L).events();
        assertEquals(3, c.size());
        // sub：bass 乐器 + 低键，与 pan 无关
        var sub = AudioBands.get(AudioBands.bandKey("ch_sub", "ch_sub"), 0L).events();
        assertEquals(2, sub.size());
        // ls/rs：极左/极右溢出带各收一枚
        assertEquals(1, AudioBands.get(AudioBands.bandKey("ch_ls", "ch_ls"), 0L).events().size());
        assertEquals(1, AudioBands.get(AudioBands.bandKey("ch_rs", "ch_rs"), 0L).events().size());
    }

    @Test
    @DisplayName("私有频道定义：音频输入定义为音频、浮点输入定义为浮点（引脚类型变换的属性源）/ the channel definition follows the input pin's peer domain")
    void privateChannelDefinitionFollowsPeerDomain() {
        // 音频输入：MUSIC→AMP→PRIVATE_OUT("pd") → 定义为音频
        NodeGraph g1 = new NodeGraph();
        GraphNode constPlay = g1.addNode(NodeType.CONST, 0, 0);
        constPlay.params[0] = 1f;
        GraphNode music = g1.addNode(NodeType.MUSIC, 100, 0);
        music.song = song(45);
        GraphNode amp = g1.addNode(NodeType.AMP, 200, 0);
        amp.params[0] = 1f;
        GraphNode pout = g1.addNode(NodeType.PRIVATE_OUT, 300, 0);
        pout.signalName = "pd";
        assertTrue(g1.addConnection(constPlay.id, 0, music.id, 0));
        assertTrue(g1.addConnection(music.id, 0, amp.id, 0));
        assertTrue(g1.addConnection(amp.id, 0, pout.id, 0));
        GraphEvaluator ev1 = new GraphEvaluator(g1);
        ev1.restoreSubState(new RuntimeState());
        ev1.setAudioTransports(new HashMap<>());
        ev1.setAudioHostPos(new BlockPos(0, 0, 0));
        ev1.evaluate(List.of(), Map.of(), DT, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertTrue(SignalBus.isPrivateAudio("pd"), "an audio pin peer defines the channel as audio");

        // 浮点输入：CONST→PRIVATE_OUT("pf") → 定义为浮点
        NodeGraph g2 = new NodeGraph();
        GraphNode c = g2.addNode(NodeType.CONST, 0, 0);
        c.params[0] = 5f;
        GraphNode pout2 = g2.addNode(NodeType.PRIVATE_OUT, 100, 0);
        pout2.signalName = "pf";
        assertTrue(g2.addConnection(c.id, 0, pout2.id, 0));
        GraphEvaluator ev2 = new GraphEvaluator(g2);
        ev2.restoreSubState(new RuntimeState());
        ev2.setAudioTransports(new HashMap<>());
        ev2.setAudioHostPos(new BlockPos(0, 0, 0));
        ev2.evaluate(List.of(), Map.of(), DT, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertFalse(SignalBus.isPrivateAudio("pf"), "a float pin peer defines the channel as float");
        assertEquals(5f, SignalBus.get("pf"), 1e-6);
    }

    @Test
    @DisplayName("冲突门控：冲突的 PRIVATE_OUT 不写值、不算定义 / a conflicted PRIVATE_OUT writes nothing and defines nothing")
    void conflictedPrivateOutIsInert() {
        NodeGraph g = new NodeGraph();
        GraphNode c = g.addNode(NodeType.CONST, 0, 0);
        c.params[0] = 5f;
        GraphNode pout = g.addNode(NodeType.PRIVATE_OUT, 100, 0);
        pout.signalName = "pc";
        assertTrue(g.addConnection(c.id, 0, pout.id, 0));
        GraphEvaluator ev = new GraphEvaluator(g);
        ev.restoreSubState(new RuntimeState());
        ev.setAudioTransports(new HashMap<>());
        ev.setAudioHostPos(new BlockPos(0, 0, 0));
        pout.busConflict = true; // 名被其他 owner 占用（注册路径置位）/ the name is held by another owner
        ev.evaluate(List.of(), Map.of(), DT, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(0f, SignalBus.get("pc"), "a conflicted writer never lands");
        assertFalse(SignalBus.isPrivateAudio("pc"), "a conflicted node defines nothing");
    }

    @Test
    @DisplayName("停止沿：一次性停止标记随音频引用到达 sink / a stop edge ships a one-shot stop marker to the sink")
    void stopEdgeShipsOneShotMarker() {
        // 自建图：CONST.play / CONST.stop → MUSIC → AMP → AUDIO_OUT(B) + SPEAKER_PLAY → sink
        NodeGraph g = new NodeGraph();
        GraphNode constPlay = g.addNode(NodeType.CONST, 0, 0);
        GraphNode constStop = g.addNode(NodeType.CONST, 30, 0);
        GraphNode music = g.addNode(NodeType.MUSIC, 100, 0);
        music.song = song(45);
        GraphNode amp = g.addNode(NodeType.AMP, 200, 0);
        GraphNode audioOut = g.addNode(NodeType.AUDIO_OUT, 300, 0);
        audioOut.signalName = "B";
        GraphNode play = g.addNode(NodeType.SPEAKER_PLAY, 400, 0);
        assertTrue(g.addConnection(constPlay.id, 0, music.id, 0));
        assertTrue(g.addConnection(constStop.id, 0, music.id, 1));
        assertTrue(g.addConnection(music.id, 0, amp.id, 0));
        assertTrue(g.addConnection(amp.id, 0, audioOut.id, 0));
        assertTrue(g.addConnection(amp.id, 0, play.id, 0));

        Map<Integer, MusicTransport> transports = new HashMap<>();
        GraphEvaluator ev = new GraphEvaluator(g);
        ev.restoreSubState(new RuntimeState());
        ev.setAudioTransports(transports);
        ev.setAudioHostPos(new BlockPos(0, 0, 0));
        List<NoteEvent> played = new ArrayList<>();
        List<Boolean> stops = new ArrayList<>();
        ev.setSpeakerSink(new SpeakerSink() {
            @Override public void play(List<NoteEvent> events, float gain) { played.addAll(events); }
            @Override public void stopPlayback() { stops.add(Boolean.TRUE); }
        });
        var seat = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0);

        constPlay.params[0] = 1f; constStop.params[0] = 0f;
        ev.evaluate(List.of(), Map.of(), DT, seat);
        assertTrue(stops.isEmpty(), "playing: no stop marker");
        assertEquals(1, played.size());

        // play 撤 + stop 升 → 停止沿：恰好一条标记到达 sink，本 tick 不再发音符
        constPlay.params[0] = 0f; constStop.params[0] = 1f;
        ev.evaluate(List.of(), Map.of(), DT, seat);
        assertEquals(1, stops.size(), "the stop edge ships exactly one stop marker");

        // 标记一次性：电平保持/撤除都不再发
        constStop.params[0] = 0f;
        ev.evaluate(List.of(), Map.of(), DT, seat);
        assertEquals(1, stops.size(), "the marker is one-shot, not level-held");

        // 同 tick play+stop 双沿：既有代码序 = play 先、stop 后 → stop 赢，标记照发
        //（传输保持停止、无新音符）。吞标记的活路径是 seek/stop 后未续播。
        constPlay.params[0] = 1f; constStop.params[0] = 1f;
        ev.evaluate(List.of(), Map.of(), DT, seat);
        assertEquals(2, stops.size(), "stop wins the same-tick play+stop race (existing edge order)");
        assertEquals(1, played.size(), "the transport ends up stopped: no new notes");
    }
}
