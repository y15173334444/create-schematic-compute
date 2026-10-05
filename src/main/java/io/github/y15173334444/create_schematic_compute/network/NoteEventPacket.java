package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.client.audio.CscAudioEngine;
import io.github.y15173334444.create_schematic_compute.graph.AudioCurve;
import io.github.y15173334444.create_schematic_compute.graph.NoteEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 音符事件下发（S→C）：某只音响坐标 + 衰减半径 + 本 tick 事件 + 波形整形曲线链（可选）。
 * 客户端经 {@link CscAudioEngine}（自管混音引擎）在世界位置播出。
 * <p>Note-event dispatch (server→client): a speaker position + attenuation radius + this tick's
 * events + an optional waveshaper curve chain. The client plays them through {@link CscAudioEngine}
 * (the self-mixed engine) at the speaker's world position (plan §3.5).</p>
 * <p>整形口径：控制点对原样随批下发（X 递增 0..1，float 全精度），客户端按应用序烘 LUT
 * （{@link AudioCurve#bakeLut}，零量化失真）、起音时保号整形固化进采样（整形先于重采样
 * 插值）；空链 = 无整形。每批自包含、无版本协商。/ Shaping contract: control-point pairs
 * ship raw per batch (X ascending 0..1, full float precision); the client bakes the LUT in
 * application order ({@link AudioCurve#bakeLut}, zero quantization) and bakes the
 * sign-preserving shape into the sample at voice start (shaping precedes resampling
 * interpolation); an empty chain means no shaping. Every batch is self-contained — no
 * version negotiation.</p>
 * <p>精度口径：±1 游戏刻 + 子 tick 偏移尽力（到达即播 + 引擎输出预填 ≈200 ms，预播提前量后续）。</p>
 * <p>音色：原版音符盒 16 音色采样（运行时引用原版资源，D13 口径）；自定义乐器降级为 harp。</p>
 */
public record NoteEventPacket(BlockPos speakerPos, float radius, long dispatchGameTick,
                              List<NoteEvent> events,
                              List<AudioCurve.Curve> waveCurves) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<NoteEventPacket> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "note_event"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NoteEventPacket> CODEC = StreamCodec.of(
        (buf, pkt) -> {
            buf.writeBlockPos(pkt.speakerPos);
            buf.writeFloat(pkt.radius);
            buf.writeVarLong(pkt.dispatchGameTick);
            buf.writeVarInt(pkt.events.size());
            for (NoteEvent e : pkt.events) {
                buf.writeByte(e.instrument());
                buf.writeByte(e.key());
                buf.writeByte(e.velocity());
                buf.writeByte(e.panning());
                buf.writeShort(e.pitch());
                buf.writeFloat(e.gain());
                buf.writeFloat(e.delaySeconds());
            }
            // 整形曲线链：逐条写点列（点数 + xs + ys，float 全精度）；空链写 0（无整形）。
            // Shaping chain: per curve a point count + xs + ys (full float precision); an
            // empty chain ships as a zero count.
            buf.writeVarInt(pkt.waveCurves == null ? 0 : pkt.waveCurves.size());
            if (pkt.waveCurves != null) {
                for (AudioCurve.Curve c : pkt.waveCurves) {
                    buf.writeVarInt(c.xs().length);
                    for (float v : c.xs()) buf.writeFloat(v);
                    for (float v : c.ys()) buf.writeFloat(v);
                }
            }
        },
        buf -> {
            BlockPos pos = buf.readBlockPos();
            float radius = buf.readFloat();
            long dispatchGameTick = buf.readVarLong();
            int n = buf.readVarInt();
            List<NoteEvent> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                // 乐器 0–255、声像 0–200 超有符号字节范围，按无符号读回（写侧本就是裸字节）
                // / instrument and panning span past a signed byte — read them unsigned.
                list.add(new NoteEvent(buf.readUnsignedByte(), buf.readByte(), buf.readByte(),
                    buf.readUnsignedByte(), buf.readShort(), buf.readFloat(), buf.readFloat()));
            }
            int curveCount = buf.readVarInt();
            List<AudioCurve.Curve> curves = new ArrayList<>(curveCount);
            for (int i = 0; i < curveCount; i++) {
                int points = buf.readVarInt();
                float[] xs = new float[points], ys = new float[points];
                for (int j = 0; j < points; j++) xs[j] = buf.readFloat();
                for (int j = 0; j < points; j++) ys[j] = buf.readFloat();
                curves.add(new AudioCurve.Curve(xs, ys));
            }
            return new NoteEventPacket(pos, radius, dispatchGameTick, list, curves);
        });

    @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** 网络线程直处理（不再 enqueueWork 跳主线程）：playClient 只读标量/不可变状态（mc.level
     *  游戏刻、监听器变换、音量浮点），引擎自身全锁（mixer/锚点/诊断 synchronized）。客户端
     *  主线程的停顿由此退出音频延迟路径——主线程卡住时音符照常进混音器、由声音引擎线程渲染。
     *  必须与 {@link MusicStopPacket} 同线程同序（两包都在网络线程按到达顺序处理，一个直处理
     *  一个跳主线程会破坏停止标记与音符批次的先后）。
     *  Handle directly on the network thread (no enqueueWork hop to main): playClient reads only
     *  scalars/immutable state (mc.level game time, listener transform, volume floats) and the
     *  engine is self-locked (mixer/anchor/diag synchronized). Client main-thread hitches leave
     *  the audio latency path — notes reach the mixer on time and render on the sound engine
     *  thread while the main thread is stuck. Must stay on the same thread in the same order as
     *  {@link MusicStopPacket} (both arrive sequentially on the network loop; mixing one direct
     *  with one enqueued would reorder stops against note batches). */
    public void handle(IPayloadContext ctx) {
        playClient();
    }

    @OnlyIn(Dist.CLIENT)
    private void playClient() {
        double x = speakerPos.getX() + 0.5, y = speakerPos.getY() + 0.5, z = speakerPos.getZ() + 0.5;
        // 迟到校正：delaySeconds 自下发起算——到达迟到必须从落点扣除，否则迟到 1:1 转成
        // 播出推迟、积压段与后续段重叠（「积压音频挤在一起」）。
        long lateTicks = 0;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null) lateTicks = mc.level.getGameTime() - dispatchGameTick;
        // 批次级探针：区分服务端下发停顿 / 客户端主线程积压 / 迟到校正时钟歪斜
        // (堵塞归因的数据面——[TimelineDiag] DISPATCH-GAP / CLIENT-DELAY / LATE-CORR-SPIKE)
        CscAudioEngine.noteDispatchBatch(dispatchGameTick, events.size(), lateTicks);
        // 波形整形：曲线链按应用序烘成 LUT（float 精度，零量化失真）；空链 = 无整形。
        // Shaping: bake the curve chain into one LUT in application order (float precision,
        // zero quantization); an empty chain means no shaping.
        float[] batchWaveLut = AudioCurve.bakeLut(waveCurves);
        for (NoteEvent e : events) playAt(e, x, y, z, radius, lateTicks * 0.05f, speakerPos.asLong(), batchWaveLut);
    }

    /**
     * 在世界坐标播一条音符事件。客户端发声的唯一入口：服务端下发与 NBS 编辑器试听
     * （{@link CscAudioEngine#playAtListener}）共用同款播放路径（plan §3.2）——
     * 委托 {@link CscAudioEngine}（自管混音，声部消耗与音符密度无关）。
     * {@code speakerTag} = 音响坐标 asLong，停止标记按它清除该音响未播声部；
     * {@code waveLut} = 本批波形整形 LUT（null = 无）。
     * <p>Play one note event at a world position. The single client playback entry: server
     * dispatch and the NBS editor's audition share this same path (plan §3.2) — delegated to
     * {@link CscAudioEngine} (self-mixed; voice usage is independent of note density).
     * {@code speakerTag} = the speaker position's asLong, the key stop markers cancel by;
     * {@code waveLut} = this batch's waveshaper LUT (null = none).</p>
     */
    @OnlyIn(Dist.CLIENT)
    public static void playAt(NoteEvent e, double x, double y, double z, double radius,
                              float lateSeconds, long speakerTag, float[] waveLut) {
        CscAudioEngine.play(e, x, y, z, radius, lateSeconds, speakerTag, waveLut);
    }
}
