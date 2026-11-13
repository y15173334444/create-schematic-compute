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
 * <p><b>数据面纪律</b>：本 handler 骨架<b>只产文本行</b>（help 表 / 未接线提示），不读
 * 任何总线或求值状态；后续接线批的 {@code stale} 等数据命令必须走服务端求值快照口径
 * （规格 §3.3 警示：探针「当前值」读数是客户端本地求值，不是真值）。</p>
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
 * <p><b>Data-plane discipline</b>: this handler skeleton produces <b>text lines only</b>
 * (help table / not-wired hints) and reads no bus or evaluation state; the wiring batch's
 * {@code stale}-style data commands must come from the server eval-snapshot plane (spec
 * §3.3 warning: the probe's "current value" readout is client-local evaluation, not truth).</p>
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
            // 文法与响应上界都在服务端（§4.4：原始字符串进、文本行出、单次成本有上界）
            // Grammar and response caps are both server-side (§4.4: raw string in, text
            // lines out, per-request cost bounded).
            List<String> lines = ConsoleProtocol.clampResponse(ConsoleProtocol.respond(command));
            PacketDistributor.sendToPlayer(sp, new ConsoleResponsePacket(lines));
        });
    }
}
