package io.github.y15173334444.create_schematic_compute.graph;

import java.util.List;
import java.util.Map;

/**
 * 音频域处理器（决策文档 §四）：AUDIO —— MUSIC、AMP、WSHAPE、CHANNEL、AUDIO_OUT、
 * SPEAKER_PLAY。case 体从 {@link GraphEvaluator} 的 eval() switch 逐字搬迁
 * （§三-2：方法体不改写，只改接收者/归属）；本类是静态函数包，不建继承体系（§三-3）。
 * <p>Audio-domain handler (decision doc §四): AUDIO — MUSIC, AMP, WSHAPE, CHANNEL,
 * AUDIO_OUT, SPEAKER_PLAY. Case bodies are relocated verbatim from
 * {@link GraphEvaluator}'s eval() switch (§三-2: bodies are never rewritten, only
 * their owner changes); this class is a static function pack, no inheritance
 * hierarchy (§三-3).</p>
 */
public final class AudioEval {

    private AudioEval() {}

    /** 求值音频域节点（case 体逐字搬迁，见类注释）。方法开头放局部别名块（接收者改写），
     *  让 case 体一个字都不用改；结尾 {@code ctx.o = o} 回写输出数组（本域不重建数组，
     *  回写为通用契约）。
     *  Evaluate an audio-domain node (case bodies relocated verbatim, see the class
     *  doc). The method opens with local aliases (receiver rewrite) so the case bodies
     *  stay untouched; it ends with a {@code ctx.o = o} write-back of the output array
     *  (this domain never rebuilds it; the write-back is the general contract).
     *
     * @param ctx 求值上下文（每调用/每节点槽已由调用方填好）/ evaluation context
     *            (per-call / per-node slots already filled by the caller)
     */
    static void eval(EvalContext ctx) {
        // 方法内局部别名（接收者改写）：case 体逐字保留 / local aliases (receiver rewrite):
        // the case bodies stay verbatim
        GraphNode node = ctx.node;
        float[] o = ctx.o;
        List<GraphEvaluator.InputSource> inputs = ctx.inputs;
        Map<Integer, Float> pidState = ctx.pidState;
        float dt = ctx.dt;
        GraphEvaluator.SeatInputState seat = ctx.seat;
        NodeGraph graph = ctx.graph;
        Map<Integer, float[]> outputs = ctx.outputs;
        Map<Long, AudioRef> audioRefs = ctx.audioRefs;
        AudioBandsPort audioBands = ctx.audioBands;
        SpeakerSink speakerSink = ctx.speakerSink;
        java.util.Map<Integer, MusicTransport> audioTransports = ctx.audioTransports;
        net.minecraft.core.BlockPos audioHostPos = ctx.audioHostPos;
        RuntimeState runtimeState = ctx.runtimeState;
        long audioTickStamp = ctx.audioTickStamp;
        switch (node.type) {
            // ── 音频（功放电脑）/ Audio (amplifier computer) ──
            case MUSIC -> {
                // 输入：play/stop 触点（上升沿）、seek 数值（值变化当帧快照跳转，含回 0）。
                // 输出：audio(handle)、playing、tick、seconds、done。
                MusicTransport tr;
                if (audioTransports != null) {
                    tr = audioTransports.get(node.id);
                    if (tr == null) { tr = new MusicTransport(); audioTransports.put(node.id, tr); }
                } else {
                    tr = new MusicTransport();
                }
                float playIn = graph.getInputValue(node.id, 0, outputs);
                float stopIn = graph.getInputValue(node.id, 1, outputs);
                float seekIn = graph.getInputValueOrDefault(node.id, 2, outputs, 0f);
                NbsSong song = node.song;
                // seek：值变化即当帧快照跳转；上次值记 pidState 辅助槽位 id+300000（随运行时状态
                // 持久化，重载/重编译不误跳；剪除由 aliveStateKeys 的同款辅助槽位覆盖）。
                float seekLast = (runtimeState != null)
                    ? runtimeState.pidState.getOrDefault(node.id + 300000, 0f) : 0f;
                if (runtimeState != null) runtimeState.pidState.put(node.id + 300000, seekIn);
                if (Math.round(seekIn) != Math.round(seekLast)) tr.seek(Math.round(seekIn));
                boolean playCur = playIn > 0.5f, stopCur = stopIn > 0.5f;
                boolean playPrev = runtimeState != null && runtimeState.nodeEdge.getOrDefault(node.id, false);
                boolean stopPrev = runtimeState != null && runtimeState.nodeEdge.getOrDefault(node.id + 100000, false);
                if (playCur && !playPrev) tr.playFromHead(song); // seek 已先行；头在曲尾则回头重播
                if (stopCur && !stopPrev) tr.stop();
                if (runtimeState != null) {
                    runtimeState.nodeEdge.put(node.id, playCur);
                    runtimeState.nodeEdge.put(node.id + 100000, stopCur);
                }
                // 预播提前量：提前 PREROLL_SECONDS 展开、事件带目标时刻（delaySeconds）
                List<NoteEvent> events = (song != null)
                    ? tr.advance(song, dt, MusicTransport.PREROLL_SECONDS) : List.of();
                boolean done = tr.consumeFinishedPulse();
                // 停止/跳转沿（且未同 tick 续播）→ 一次性停止标记穿透管线到 sink：
                // 客户端清除该音响预播窗口内已下发未播的声部，加宽窗口才不会留尾巴。
                // Stop/seek edge (no same-tick resume) → a one-shot stop marker rides the
                // pipeline to the sink: the client cancels this speaker's dispatched-but-
                // unplayed voices, so a widened pre-roll leaves no tail after a stop.
                boolean flush = tr.consumeFlushPulse() && !tr.isPlaying();
                audioRefs.put(EvalContext.audioKey(node.id, 0), new AudioRef(events, 1f, flush));
                o[0] = 0; // 音频引脚经 audioRefs（类型化，非浮点）
                o[1] = tr.isPlaying() ? 1f : 0f;
                o[2] = tr.headTick();
                o[3] = (song != null) ? tr.headSeconds(song) : 0f;
                o[4] = done ? 1f : 0f;
            }
            case AMP -> {
                AudioRef in = graph.getAudioInputRef(node.id, 0, audioRefs);
                float gainIn = graph.getInputValueOrDefault(node.id, 1, outputs,
                    node.params.length > 0 ? node.params[0] : 1f);
                float gain = Math.max(0f, Math.min(4f, gainIn));
                if (in == null) {
                    audioRefs.put(EvalContext.audioKey(node.id, 0), AudioRef.EMPTY);
                } else {
                    // 力度曲线（方案一，2026-10-05 作者拍板）：x = 音符自身力度（速度×层音量 =
                    // 实例音量，0..1），y = 输出力度（写回实例音量）——曲线管形状、链上标量（含引脚）
                    // 在曲线后线性叠加管电平，两者正交；恒等曲线 = 原行为。曲线点间线性插值，端点钳制。
                    // x 不是 gain 字段：运输层把速度×层音量烘焙进 velocity、gain 恒 1，
                    // 响度住在 instanceVolume()——用 gain 当 x 会让曲线只见常数、塑不到力度。
                    // Dynamics curve (option 1, author call): x = the note's own loudness
                    // (velocity × layer = the instance volume, 0..1), y = output level written
                    // back as the instance volume — the curve shapes, the chain scalar (incl.
                    // the pin) scales afterwards; the two are orthogonal. Identity curve = old
                    // behaviour. Linear interpolation between points, clamped ends. Note x is
                    // NOT the gain field: the transport bakes velocity × layer into velocity and
                    // fixes gain at 1, so loudness lives in instanceVolume() — using gain as x
                    // would show the curve a constant and shape no dynamics at all.
                    // 恒等曲线 = 真旁路（不参与，与没动过逐位一致）——「默认」随时可达，
                    // 不是一次性状态。Identity curve = true bypass (bit-identical to
                    // untouched), so the default is always reachable, not one-shot.
                    if (node.isCurveNode() && node.curveX != null && node.curveY != null
                        && !AudioCurve.isIdentity(node.curveX, node.curveY)
                        && in.events() != null && !in.events().isEmpty()) {
                        var shaped = new java.util.ArrayList<NoteEvent>(in.events().size());
                        for (NoteEvent e : in.events())
                            shaped.add(e.withInstanceVolume(
                                AudioCurve.eval(node.curveX, node.curveY, e.instanceVolume())));
                        in = new AudioRef(shaped, in.gain(), in.stopSignal(), in.waveCurves());
                    }
                    audioRefs.put(EvalContext.audioKey(node.id, 0), in.withGain(in.gain() * gain));
                }
            }
            case WSHAPE -> {
                // 波形整形：把自身曲线（控制点对）追加进引用的整形链（应用序；客户端烘 LUT 时
                // 按序合成 g∘f），随 AudioRef → 频段表 → NoteEventPacket 送达客户端——起音时
                // 保号整形固化进采样（整形先于重采样插值，混音内环零附加成本）；控制点直达，
                // 无烘焙量化失真。恒等曲线 = 真旁路（不入链、不进包，与没动过逐位一致）。
                // 事件与标量原样——服务端不做任何采样级处理（功放图与音响图均可放）。
                // Wave shaper: appends this node's curve (control-point pairs) to the ref's
                // shaping chain (application order; the client composes g∘f when baking) —
                // it rides the AudioRef → band table → note packet to the client, which bakes
                // the sign-preserving shape into the sample at voice start (shaping precedes
                // resampling interpolation; zero cost in the mix inner loop), points
                // delivered raw (no bake-quantization distortion). An identity curve is a
                // true bypass (never enters the chain or the wire — bit-identical to
                // untouched). Events and the scalar pass untouched (no server-side sample
                // processing); works in amplifier and speaker graphs alike.
                AudioRef in = graph.getAudioInputRef(node.id, 0, audioRefs);
                if (in == null) {
                    audioRefs.put(EvalContext.audioKey(node.id, 0), AudioRef.EMPTY);
                } else if (AudioCurve.isIdentity(node.curveX, node.curveY)) {
                    audioRefs.put(EvalContext.audioKey(node.id, 0), in);
                } else {
                    audioRefs.put(EvalContext.audioKey(node.id, 0),
                        in.withWaveCurve(new AudioCurve.Curve(node.curveX, node.curveY)));
                }
            }
            case CHANNEL -> {
                // 声道拆分：audio 入 → 各声道出（引脚集合 = 布局表；拆分口径见 ChannelLayout）。
                AudioRef in = graph.getAudioInputRef(node.id, 0, audioRefs);
                int chCount = node.signalBands != null ? node.signalBands.size() : 0;
                boolean hasCenter = ChannelLayout.hasCenter(
                    node.params.length > 0 ? (int) node.params[0] : ChannelLayout.STEREO);
                for (int ci = 0; ci < chCount; ci++)
                    audioRefs.put(EvalContext.audioKey(node.id, ci),
                        ChannelLayout.channelRef(in, node.signalBands.get(ci), hasCenter));
            }
            case AUDIO_OUT -> {
                // 单引脚多声道：audio 入（多声道 AudioRef）→ 发布到音频频段（band=signalName，带发布时刻）。
                // owner = 宿主坐标+节点 id（R1-2 冲突纪律）：同名双发布方 → 双方旗标、整体静默；
                // 空名无可发布对象，不算冲突。
                AudioRef in = graph.getAudioInputRef(node.id, 0, audioRefs);
                if (audioHostPos != null) {
                    node.audioConflict = !node.signalName.isEmpty()
                        && !audioBands
                            .publish(audioBands
                                    .bandKey(node.signalName, node.signalName),
                                in != null ? in : AudioRef.EMPTY, audioTickStamp, ctx.consumerKey(node));
                }
            }
            case SPEAKER_PLAY -> {
                // 播放 sink：把音源交给宿主音响 BE，在其坐标发声（R1-3/R1-D）。
                // 单引脚多声道、**恒等直通**（2026-10-04 作者口径：声道拆分归上游 CHANNEL——
                // 每路一个频道、音响是哑终端；单音响放不了聚合以上的多声道摆放，
                // 消费端二次选路是职责错位）。低音炮等单路需求 = 上游 CHANNEL 拆分后发布成频道。
                // Playback sink: hand the source to the host speaker BE (R1-3/R1-D).
                // Single-pin multi-channel, **identity passthrough** (author call 2026-10-04:
                // splitting belongs to the upstream CHANNEL - one band per channel, the speaker
                // is a dumb terminal; a single physical speaker cannot place multi-channel
                // audio, so a consumer-side re-pick is a responsibility leak).
                AudioRef in = graph.getAudioInputRef(node.id, 0, audioRefs);
                if (in != null && speakerSink != null) {
                    if (in.stopSignal()) speakerSink.stopPlayback();
                    else speakerSink.play(in.events(), in.gain(), in.waveCurves());
                }
            }
        }
        ctx.o = o;
    }
}
