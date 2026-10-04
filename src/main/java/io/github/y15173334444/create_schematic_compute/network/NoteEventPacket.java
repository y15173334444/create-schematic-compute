package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.client.audio.CscAudioEngine;
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
 * 音符事件下发（S→C）：某只音响坐标 + 衰减半径 + 本 tick 事件。客户端经
 * {@link CscAudioEngine}（自管混音引擎）在世界位置播出。
 * <p>Note-event dispatch (server→client): a speaker position + attenuation radius + this tick's
 * events. The client plays them through {@link CscAudioEngine} (the self-mixed engine) at the
 * speaker's world position (plan §3.5).</p>
 * <p>精度口径：±1 游戏刻 + 子 tick 偏移尽力（到达即播 + 引擎输出预填 ≈200 ms，预播提前量后续）。</p>
 * <p>音色：原版音符盒 16 音色采样（运行时引用原版资源，D13 口径）；自定义乐器降级为 harp。</p>
 */
public record NoteEventPacket(BlockPos speakerPos, float radius, long dispatchGameTick,
                              List<NoteEvent> events) implements CustomPacketPayload {

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
            return new NoteEventPacket(pos, radius, dispatchGameTick, list);
        });

    @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(this::playClient);
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
        for (NoteEvent e : events) playAt(e, x, y, z, radius, lateTicks * 0.05f, speakerPos.asLong());
    }

    /**
     * 在世界坐标播一条音符事件。客户端发声的唯一入口：服务端下发与 NBS 编辑器试听
     * （{@link CscAudioEngine#playAtListener}）共用同款播放路径（plan §3.2）——
     * 委托 {@link CscAudioEngine}（自管混音，声部消耗与音符密度无关）。
     * {@code speakerTag} = 音响坐标 asLong，停止标记按它清除该音响未播声部。
     * <p>Play one note event at a world position. The single client playback entry: server
     * dispatch and the NBS editor's audition share this same path (plan §3.2) — delegated to
     * {@link CscAudioEngine} (self-mixed; voice usage is independent of note density).
     * {@code speakerTag} = the speaker position's asLong, the key stop markers cancel by.</p>
     */
    @OnlyIn(Dist.CLIENT)
    public static void playAt(NoteEvent e, double x, double y, double z, double radius,
                              float lateSeconds, long speakerTag) {
        CscAudioEngine.play(e, x, y, z, radius, lateSeconds, speakerTag);
    }
}
