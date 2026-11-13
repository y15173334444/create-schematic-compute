package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * Client→Server：图内控制台查询（C 阶段步骤 5，规格 §4.4 推荐协议）。
 *
 * <p>载荷 = 目标 host 的 {@link BlockPos} + <b>原始命令字符串</b>——客户端不解析命令名，
 * 文法唯一真相源在服务端（{@link ConsoleProtocol}），命令集演进不需要改客户端。注册面经
 * {@link AllPackets#rateLimited} 包装（预算 {@link C2SPacketRateGuard#CONSOLE_QUERY}：
 * {@code help} 是低频按需查询，O(1) 构表、响应只发请求者、无广播放大，给小预算）。</p>
 *
 * <p><b>服务端守卫</b>：距离校验沿用惯例（§4.4 分层缓解第 4 层——128 格可达，与
 * {@code GraphSaveRequestPacket} 同款 {@link SablePacketHelper#isWithinReachableRange}），
 * 挡住远程探测任意方块。命令长度上限在 {@link ConsoleProtocol#respond} 复核（不信任
 * 客户端；codec 层字符串本身受 vanilla 32KB 上界钳制，无「先分配后校验」口子）。</p>
 *
 * <p><b>数据面纪律</b>：本 handler 只产文本行，数据真值口径走服务端采集快照与按 tick 缓存的
 * 依赖图（{@code ConsoleDataPlane} 渲染；规格 §3.3 警示：探针「当前值」读数是客户端本地
 * 求值，不是真值——绝不复用显示通道）；{@code bus} 的总线表读取由分发处以宿主维度作用域
 * 包裹（issue #29 硬口径）。目标宿主从全局注册表解析（{@link ConsoleDataPlane#resolveHost}），
 * 查询点不是登记宿主时数据命令回可诊断回执。</p>
 *
 * <h2>Client→Server: the in-editor console query (Phase-C step 5, spec §4.4's recommended
 * protocol).</h2>
 *
 * <p>Payload = the target host's {@link BlockPos} + the <b>raw command string</b> — the
 * client never parses command names; the grammar's single source of truth is server-side
 * ({@link ConsoleProtocol}), so the command set evolves without client changes. The
 * registration face is wrapped by {@code AllPackets.rateLimited} (budget
 * {@code CONSOLE_QUERY}: {@code help} is a low-frequency on-demand query, O(1) table build,
 * requester-only response, no broadcast amplification — a small budget). Server guards: the
 * conventional 128-block reach check (§4.4 layer 4, same as GraphSaveRequestPacket) blocks
 * remote probing of arbitrary blocks; the command length is re-checked in
 * {@code ConsoleProtocol.respond} (the client is never trusted; the codec string is already
 * vanilla-clamped at 32KB — no allocate-before-validate seam).</p>
 *
 * <p><b>Data-plane discipline</b>: this handler produces <b>text lines only</b>; the data-truth
 * plane rides the server's collection snapshot and the per-tick cached dependency graph
 * (rendered by {@code ConsoleDataPlane}; spec §3.3's warning — the probe's "current value"
 * readout is client-local evaluation, not truth, and the display channel is never reused);
 * {@code bus}'s bus-table reads are wrapped in the host's dimension scope at the dispatch (the
 * issue #29 hard rule). The target host is resolved from the global registry
 * ({@link ConsoleDataPlane#resolveHost}); when the query point holds no registered host, the
 * data commands answer diagnosably.</p>
 */
public record ConsoleQueryPacket(BlockPos pos, String command) implements CustomPacketPayload {

    public static final Type<ConsoleQueryPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "console_query"));

    public static final StreamCodec<ByteBuf, ConsoleQueryPacket> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC,      ConsoleQueryPacket::pos,
        ByteBufCodecs.STRING_UTF8,  ConsoleQueryPacket::command,
        ConsoleQueryPacket::new
    );

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** 客户端发送口（GraphConsole.Core 的 querySender 接线用；命令长度已在客户端
     *  {@code Core.MAX_INPUT} 处受限，服务端仍复核）。 / The client send face (wired as
     *  GraphConsole.Core's querySender; the length is already capped client-side at
     *  {@code Core.MAX_INPUT}, and the server re-checks anyway). */
    public static void send(BlockPos pos, String command) {
        PacketDistributor.sendToServer(new ConsoleQueryPacket(pos, command));
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // 守卫链照 GraphSaveRequestPacket：服务端玩家 / 服务端世界 / 距离可达
            // Guard chain per GraphSaveRequestPacket: server player / server level / within reach
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel sl)) return;
            if (!SablePacketHelper.isWithinReachableRange(sp, pos, 16384.0)) return;
            // 目标宿主解析（§4.4：查询落的是宿主 BE 的维度——bus 等命令的维度作用域读取与
            // 「本 host」视角都从这里出发）；未解析到 = 数据命令回可诊断回执。
            // Target-host resolution (§4.4: the query lands on the host BE's dimension — the
            // dimension-scoped reads of bus and the "this host" views all start here); no
            // resolution = the data commands answer diagnosably.
            var target = ConsoleDataPlane.resolveHost(sl.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ());
            // 文法与响应上界都在服务端（§4.4：原始字符串进、文本行出、单次成本有上界）
            // Grammar and response caps are both server-side (§4.4: raw string in, text
            // lines out, per-request cost bounded).
            List<String> lines = ConsoleProtocol.clampResponse(ConsoleProtocol.respond(command, target));
            PacketDistributor.sendToPlayer(sp, new ConsoleResponsePacket(lines));
        });
    }
}
