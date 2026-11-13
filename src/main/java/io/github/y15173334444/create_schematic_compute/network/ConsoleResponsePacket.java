package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Server→Client：图内控制台查询的响应（C 阶段步骤 5，规格 §4.4）。
 *
 * <p>载荷 = 文本行列表——行内容<b>原样展示</b>，客户端不解析（文法唯一真相源在服务端
 * {@link ConsoleProtocol}）。行数 / 字节上界由服务端 {@link ConsoleProtocol#clampResponse}
 * 在发送前钳制（§4.4 分层缓解第 3 层：单次请求成本有上界）；解码侧另做行数钳制——
 * 不按线上声明的数量直接分配（§4.4 第 0 步「先分配后校验」教训的同款防线，S→C 侧便宜
 * 就一并做）。响应只发给请求者（无广播放大）。</p>
 *
 * <p>客户端路由走静态汇点（照 {@code ScanSableResponsePacket} 的 clientHandler 手法），
 * 在 {@code ClientSetup} 接到 {@code GraphConsole::deliverToOpenEditor}——网络层不 import
 * 编辑器（E2 汇点纪律）。限流拒绝时请求根本不会到达 handler，因此「无响应」只在极端
 * 场景出现；客户端的等待标记是外观性的（超时提示为暂缓项）。</p>
 *
 * <h2>Server→Client: the response to an in-editor console query (Phase-C step 5, spec
 * §4.4).</h2>
 *
 * <p>Payload = a list of text lines — displayed <b>verbatim</b>, never parsed client-side
 * (the grammar's single source of truth is server-side {@link ConsoleProtocol}). The line /
 * byte caps are clamped server-side by {@code ConsoleProtocol.clampResponse} before sending
 * (§4.4 layer 3); the decoder additionally clamps the line count — never allocating by the
 * wire-declared count (the §4.4 layer-0 allocate-before-validate lesson, done here too
 * because it is cheap on the S→C side). Responses go only to the requester (no broadcast
 * amplification).</p>
 *
 * <p>Client routing uses the static-sink pattern (the {@code ScanSableResponsePacket}
 * clientHandler playbook), wired in {@code ClientSetup} to
 * {@code GraphConsole::deliverToOpenEditor} — the network layer never imports the editor
 * (the E2 sink discipline). A rate-limited request never reaches its handler, so "no
 * response" only occurs in extreme scenarios; the client's awaiting marker is cosmetic
 * (a timeout hint is deferred).</p>
 */
public record ConsoleResponsePacket(List<String> lines) implements CustomPacketPayload {

    public static final Type<ConsoleResponsePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "console_resp"));

    /** 解码行数硬上界（合法响应 ≤ 200 行 + 截断标记；余量按钳制后最大形态给足，防线上
     *  声明的超大计数直接进 ArrayList 分配——P0-1「先分配后校验」同款防线）。
     *  Hard decode line cap (a legitimate response is ≤ 200 lines + the marker; the slack
     *  covers the clamped maximum form so a wire-declared oversized count can never reach
     *  the ArrayList allocation — the P0-1 allocate-before-validate defense). */
    static final int MAX_DECODE_LINES = 1024;

    public static final StreamCodec<ByteBuf, ConsoleResponsePacket> CODEC = new StreamCodec<>() {
        @Override
        public void encode(ByteBuf buf, ConsoleResponsePacket pkt) {
            List<String> lines = pkt.lines;
            ByteBufCodecs.VAR_INT.encode(buf, lines.size());
            for (String line : lines) ByteBufCodecs.STRING_UTF8.encode(buf, line);
        }

        @Override
        public ConsoleResponsePacket decode(ByteBuf buf) {
            int count = Math.min(ByteBufCodecs.VAR_INT.decode(buf), MAX_DECODE_LINES);
            List<String> lines = new ArrayList<>(count);
            for (int i = 0; i < count; i++) lines.add(ByteBufCodecs.STRING_UTF8.decode(buf));
            return new ConsoleResponsePacket(List.copyOf(lines));
        }
    };

    /** 客户端汇点（ClientSetup 接 {@code GraphConsole::deliverToOpenEditor}；服务端侧为
     *  空 no-op）。 / The client sink (ClientSetup wires {@code GraphConsole::deliverToOpenEditor};
     *  a no-op on the server side). */
    public static Consumer<List<String>> clientHandler = lines -> { };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> clientHandler.accept(lines));
    }
}
