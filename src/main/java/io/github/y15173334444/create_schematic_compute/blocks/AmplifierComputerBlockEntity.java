package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.AudioRef;
import io.github.y15173334444.create_schematic_compute.graph.AudioSpectrum;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.MusicTransport;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.NoteEvent;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import io.github.y15173334444.create_schematic_compute.network.SpectrumSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

/**
 * 功放电脑 BE：图宿主薄壳（ProgramComputer 同款 tick）+ MUSIC 传输状态注入与持久化 +
 * 屏幕频谱取数下发。
 * <p>传输状态（playing/head/nextFire）住 BE 类型段 NBT（plan §3.7），每 tick 注入求值器。</p>
 * <p>Amplifier computer BE: a graph-host shell (ProgramComputer-style tick) plus MUSIC
 * transport injection + persistence (type-specific NBT, plan §3.7) and the on-screen
 * spectrum tap.</p>
 */
public class AmplifierComputerBlockEntity extends SyncedGraphBlockEntity {

    /** 监听编号 = 自动：跟随图最上方的 MUSIC 节点。 / monitor id = auto: the graph's topmost MUSIC node. */
    public static final int AUTO_MONITOR = -1;

    /** MUSIC 节点传输状态（nodeId → 传输），经类型段 NBT 持久化。 */
    private final Map<Integer, MusicTransport> audioTransports = new HashMap<>();

    /** 监听目标编号（类型段 NBT）：{@link #AUTO_MONITOR} = 自动·图最上方，否则为指定的
     *  MUSIC 节点。盖子频谱只反映这个源。悬空编号显示回落最上方，编号保留——撤销重建后自动重挂。
     *  Monitor target id (type-specific NBT): {@link #AUTO_MONITOR} = auto (topmost MUSIC
     *  node), otherwise a pinned MUSIC node. The lid spectrum reflects only this source. A
     *  dangling id falls back to the topmost for display while the id is kept, so undoing the
     *  delete re-attaches the pin automatically. */
    public int monitoredNode = AUTO_MONITOR;

    // ── 屏幕频谱 / on-screen spectrum ──
    /** 服务端取数：排程环 + 柱电平（按预播目标刻点亮）。 / server-side accumulator. */
    private final AudioSpectrum spectrum = new AudioSpectrum();
    /** 上一包字节（变化检测：静默后自然停发）。 / last packed bytes, for change detection. */
    private final byte[] spectrumBytes = new byte[AudioSpectrum.BARS];
    /** 客户端：上一包 / 本包电平（0..1），渲染时按 partialTick 插值（20 Hz → 逐帧）。
     *  Client: previous / current levels (0..1) — interpolated by partialTick between frames. */
    private final float[] prevSpectrum = new float[AudioSpectrum.BARS];
    private final float[] curSpectrum = new float[AudioSpectrum.BARS];
    /** 客户端：最后一包到达的墙钟（纳秒）。断流判定必须用暂停时仍在走的时钟——世界时刻不走。
     *  Client: wall-clock of the last packet (ns). The stale-stream check needs a clock that keeps
     *  running while the game is paused; world time does not. */
    private long lastSpectrumPacketNanos;
    /** 断流阈值（纳秒，3 个发包周期）：超时即视为断流，渲染收敛到本包值。
     *  Stale threshold (ns, three packet periods): past it the stream counts as dead and
     *  rendering settles on the current frame. */
    private static final long SPECTRUM_STALE_NANOS = 150_000_000L;
    /** 客户端：峰顶指示器高度（0..1）——被柱子顶起（即时）、松开后慢落（重力感），纯显示动画。
     *  Client: peak indicator heights (0..1) — pushed up by the bar instantly, sinking slowly
     *  after it lets go; display animation only. */
    private final float[] peakSpectrum = new float[AudioSpectrum.BARS];
    /** 峰顶回落速度（满量程/秒）与上一帧时刻（墙钟；游戏暂停时也在走）。
     *  Peak fall rate (full scale/s) and the last frame's wall time (runs while paused). */
    private static final float PEAK_FALL_PER_SEC = 0.5f;
    private long lastPeakNanos;

    public AmplifierComputerBlockEntity(BlockPos pos, BlockState s) {
        super(SchematicCompute.AMPLIFIER_COMPUTER_BE.get(), pos, s);
    }

    public void tick() {
        if (level == null || level.isClientSide()) return;
        ensureBusRegistered();
        var state = getBlockState();
        if (state.hasProperty(AmplifierComputerBlock.LIT)) {
            boolean shouldBeLit = isRunning() && !graph().nodes.isEmpty();
            if (state.getValue(AmplifierComputerBlock.LIT) != shouldBeLit)
                level.setBlock(worldPosition, state.setValue(AmplifierComputerBlock.LIT, shouldBeLit), 3);
        }
        rs().checkGraphChanged(graph());
        if (graphChanged()) recompileEvaluatorFull();
        if (!isRunning()) {
            // 停机：排程与电平一起清（音乐停了柱子不该继续抖），清零沿下发一次——
            // 「合盖不画」只在 LIT 由 isRunning 派生的今天才成立，显示路径不该依赖它；
            // flushSpectrumPacket 的变化检测保证只在沿上发一包，之后自然停发。
            // Stopped: drop the schedule and the levels together and ship the clear once. "A
            // closed lid draws nothing" only holds while LIT derives from isRunning — the
            // display path must not lean on that; the change gate sends one packet on the edge
            // and silence takes over from there.
            spectrum.clear();
            flushSpectrumPacket();
            onStopRunning();
            return;
        }
        rs().refreshInputsActive();
        if (BusChannelHelper.recoverConflictedChannels(graph(), worldPosition, level)) {
            requestFullSync();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        // 传输表只保留仍存活的 MUSIC 节点（删节点后条目随剪，防类型段 NBT 慢性膨胀）
        audioTransports.keySet().removeIf(id -> {
            GraphNode n = graph().findNode(id);
            return n == null || n.type != NodeType.MUSIC;
        });
        // 注入音频宿主状态（传输表 + 方块坐标 + 本 tick 时刻戳），仿雷达位置注入范式；
        // 时刻戳供 AudioBands 新鲜度门控（发布方停机/卸载后陈旧音源自愈失效）。
        evaluator().setAudioTransports(this.audioTransports);
        evaluator().setAudioHostPos(this.worldPosition);
        evaluator().setAudioTickStamp(level.getGameTime());
        var in = rs().buildInputs(graph());
        float dt = 0.05f;
        var results = evaluator().evaluate(in, runtimeState().pidState, dt,
                runtimeState().delayQueues, runtimeState().flipflopStates, runtimeState().pulseTimers);
        rs().writeOutputs(results);
        broadcastEvalSnapshot();
        BusChannelHelper.syncIfBandsChanged(graph(), worldPosition, lastBusHashMap(), level);
        broadcastFlipflopDiff();
        updateSpectrum(level.getGameTime());
        setChanged();
    }

    // ── 屏幕频谱（服务端取数 → 追踪玩家 → 客户端插值显示）/ on-screen spectrum ──

    /** 监听目标解析（纯函数，UI 高亮与频谱取数共用）：指定编号有效且为 MUSIC → 该节点；
     *  否则图最上方的 MUSIC 节点——排序与封装 IO 同口径（y 升序、id 决胜，
     *  {@code GraphNode.getSubNodes} 同款）。悬空指定回落最上方；图里没有 MUSIC 节点 → null。
     *  Monitor resolution (pure, shared by UI highlight and the spectrum tap): a valid pinned
     *  MUSIC node wins; otherwise the graph's topmost MUSIC node — the same ordering as the
     *  encapsulation IO ({@code GraphNode.getSubNodes}: y ascending, id tie-break). A dangling
     *  pin falls back to the topmost; no MUSIC node → null. */
    static GraphNode resolveMonitoredNode(NodeGraph g, int pinned) {
        if (pinned != AUTO_MONITOR) {
            GraphNode n = g.findNode(pinned);
            if (n != null && n.type == NodeType.MUSIC) return n;
        }
        GraphNode top = null;
        for (var n : g.nodes)
            if (n.type == NodeType.MUSIC
                    && (top == null || n.y < top.y || (n.y == top.y && n.id < top.id)))
                top = n;
        return top;
    }

    /** 监听设置的纯校验核：只接受 AUTO 或「存在且为 MUSIC」的编号，否则维持原值。
     *  Pure validation core for the monitor setting: only AUTO or an existing MUSIC node id is
     *  accepted; anything else keeps the current value. */
    static int sanitizedMonitor(int current, int requested, NodeGraph g) {
        if (requested == AUTO_MONITOR) return AUTO_MONITOR;
        GraphNode n = g.findNode(requested);
        return (n != null && n.type == NodeType.MUSIC) ? requested : current;
    }

    /** 监听设置（C2S 包落地，Speaker.applySettings 同款收口）：无效编号拒收（值不变），
     *  变更才 setChanged + sendBlockUpdated（客户端经 acceptTypeSpecific 回读）。
     *  Monitor setting (C2S packet landing, Speaker.applySettings-style): invalid ids are
     *  rejected (value unchanged); setChanged + sendBlockUpdated only on change — clients read
     *  it back via acceptTypeSpecific. */
    public void applySettings(int nodeId) {
        int v = sanitizedMonitor(this.monitoredNode, nodeId, graph());
        if (v == this.monitoredNode) return;
        this.monitoredNode = v;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    /**
     * 频谱取数：只拉取<b>监听目标</b>（默认图最上方 MUSIC 节点，或设置面板指定的那个）的
     * 音频引用，按<b>目标刻</b>排程、推进电平，变化才下发给追踪玩家。
     * <p>取数是纯观察（{@code evaluator().audioOut} 只读引用表），不介入音频通路；事件带
     * {@code delaySeconds}（最多 2 s 预播），所以点亮时刻用「本刻 + delaySeconds/0.05」换算，
     * 直接按本刻累加会让柱子比声音早最多 2 秒。监听目标的停止/跳转沿（{@code stopSignal}）
     * 清空——非监听节点的停止沿不进显示（单源后不再有「map 迭代序决定谁被清」的顺序缺口，
     * 旧的全清语义随多源聚合一并退役）。</p>
     * <p>Spectrum tap: pull the note events out of the <b>monitored</b> source's audio refs
     * only (the graph's topmost MUSIC node by default, or the one pinned in the settings
     * panel), schedule them at their <b>target tick</b>, advance the levels and ship them to
     * tracking players only when they change. The read is pure observation (it never touches
     * the audio path); events carry {@code delaySeconds} (up to 2 s of pre-roll), so the
     * light-up tick is {@code now + delaySeconds/0.05} — accumulating at the current tick
     * would run the bars up to 2 s ahead of the sound. The monitored source's stop/seek edge
     * ({@code stopSignal}) clears everything — other nodes' stop edges never reach the display
     * (single-source, so the old "map iteration order decides who gets wiped" gap is gone with
     * the multi-source aggregation).</p>
     */
    private void updateSpectrum(long nowTick) {
        GraphNode src = resolveMonitoredNode(graph(), monitoredNode);
        AudioRef ref = src == null ? null : evaluator().audioOut(src.id, 0);
        if (ref != null && ref.stopSignal()) spectrum.clear();
        if (ref != null) {
            for (NoteEvent e : ref.events()) {
                long at = nowTick + Math.round(e.delaySeconds() / 0.05f);
                spectrum.schedule(at, e.key(), e.pitch(), e.velocity(), e.gain());
            }
        }
        spectrum.advance(nowTick);
        flushSpectrumPacket();
    }

    /** 频谱下发收口（服务端）：变化才发，静默后自然停发。
     *  Server-side spectrum send: on change only — a silent stream stops the traffic on its own. */
    private void flushSpectrumPacket() {
        if (spectrum.writeBytes(spectrumBytes)) {
            PacketDistributor.sendToPlayersTrackingChunk((ServerLevel) level,
                new ChunkPos(worldPosition), new SpectrumSyncPacket(worldPosition, spectrumBytes.clone()));
        }
    }

    /** 收到服务端频谱包（客户端显示数据）：本包转上一包、新值入本包。全零包 = 停止/跳转沿的
     *  清空包——两帧同值直接落定（干净切断），不让「末刻电平 ↔ 0」的插值锯齿拖到下次播放。
     *  Client-side packet intake: the current frame becomes the previous one and the new values
     *  enter the current frame. An all-zero packet is the stop/seek clear — snap both frames to
     *  it (a clean cut) so the last-level ↔ 0 interpolation sawtooth never runs on into the
     *  pause. */
    public void acceptSpectrum(byte[] levels) {
        if (levels == null) return;
        lastSpectrumPacketNanos = System.nanoTime();
        int n = Math.min(levels.length, AudioSpectrum.BARS);
        System.arraycopy(curSpectrum, 0, prevSpectrum, 0, AudioSpectrum.BARS);
        for (int i = 0; i < n; i++) curSpectrum[i] = (levels[i] & 0xFF) / 255f;
        for (int i = n; i < AudioSpectrum.BARS; i++) curSpectrum[i] = 0f;
        boolean allZero = true;
        for (float v : curSpectrum) if (v > 0f) { allZero = false; break; }
        if (allZero) System.arraycopy(curSpectrum, 0, prevSpectrum, 0, AudioSpectrum.BARS);
    }

    /** 渲染用柱电平（0..1）：20 Hz 数据 → 帧间插值（partialTick）；断流后收敛到本包值——
     *  partialTick 每刻循环 0→1，两帧冻结后插值会永远锯齿（暂停时屏幕「反复刷新」的根因）。
     *  Render-side bar level (0..1): frame interpolation (partialTick) across the 20 Hz stream;
     *  once the stream goes stale it settles on the current frame — partialTick cycles 0→1 every
     *  tick, so interpolating between two frozen frames sawtooths forever (the paused-screen
     *  "keeps refreshing" symptom). */
    public float spectrumLevel(int bar, float partialTick) {
        if (System.nanoTime() - lastSpectrumPacketNanos > SPECTRUM_STALE_NANOS)
            return curSpectrum[bar];
        return net.minecraft.util.Mth.lerp(partialTick, prevSpectrum[bar], curSpectrum[bar]);
    }

    /** 峰顶指示器推进（渲染器每帧一次）：柱子顶起（即时）、否则按墙钟慢落。
     *  Advance the peak indicators (once per frame, from the renderer): pushed up by the bar
     *  instantly, otherwise sinking on the wall clock. */
    public void tickSpectrumPeaks(long nowNanos, float partialTick) {
        long dtNanos = nowNanos - lastPeakNanos;
        lastPeakNanos = nowNanos;
        float dt = Math.min(0.25f, dtNanos / 1_000_000_000f);
        for (int i = 0; i < AudioSpectrum.BARS; i++) {
            float lvl = spectrumLevel(i, partialTick);
            float falling = Math.max(0f, peakSpectrum[i] - PEAK_FALL_PER_SEC * dt);
            peakSpectrum[i] = Math.max(lvl, falling);
        }
    }

    /** 峰顶指示器高度（0..1）。 / peak indicator height (0..1). */
    public float spectrumPeak(int bar) {
        return peakSpectrum[bar];
    }

    // ── 类型段 NBT：传输状态持久化 / type-specific NBT: transport persistence ──

    @Override
    protected void saveTypeSpecific(CompoundTag t, HolderLookup.Provider r) {
        ListTag list = new ListTag();
        for (var e : audioTransports.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putInt("id", e.getKey());
            MusicTransport tr = e.getValue();
            c.putBoolean("playing", tr.playing);
            c.putFloat("head", tr.head);
            c.putInt("nextFire", tr.nextFire);
            list.add(c);
        }
        t.put("audioTransports", list);
        // 自动（默认值）不写键：旧档加载即自动，无需迁移。 / AUTO (the default) writes no key:
        // legacy saves load as auto — no migration.
        if (monitoredNode != AUTO_MONITOR) t.putInt("monitoredNode", monitoredNode);
    }

    @Override
    protected void loadTypeSpecific(CompoundTag t, HolderLookup.Provider r) {
        audioTransports.clear();
        monitoredNode = t.contains("monitoredNode") ? t.getInt("monitoredNode") : AUTO_MONITOR;
        if (!t.contains("audioTransports", Tag.TAG_LIST)) return;
        ListTag list = t.getList("audioTransports", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            MusicTransport tr = new MusicTransport();
            tr.playing = c.getBoolean("playing");
            tr.head = c.getFloat("head");
            tr.nextFire = c.getInt("nextFire");
            audioTransports.put(c.getInt("id"), tr);
        }
    }

    @Override
    protected void acceptTypeSpecific(SyncedGraphBlockEntity src) {
        if (!(src instanceof AmplifierComputerBlockEntity a)) return;
        this.audioTransports.clear();
        this.audioTransports.putAll(a.audioTransports);
        this.monitoredNode = a.monitoredNode;
    }
}
