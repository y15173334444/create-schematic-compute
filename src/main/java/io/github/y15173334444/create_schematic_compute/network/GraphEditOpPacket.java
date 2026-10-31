package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.network.EditSessionRegistry;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.OpType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A custom network payload that carries a single {@link GraphOp} from the client to the server.
 * <p>
 * 一个自定义网络负载，将单个 {@link GraphOp} 从客户端传输到服务端。
 * <p>
 * The packet lives entirely on the network thread during serialization and is then enqueued
 * on the server worker thread for authenticated processing.  The embedded {@code GraphOp}
 * represents any mutation the player performed in the graph editor UI -- adding/deleting
 * nodes, wiring pins, editing parameters, changing visuals, etc.
 * <p>
 * 该数据包在序列化期间完全驻留在网络线程上，随后被加入服务端工作线程队列以进行鉴权处理。
 * 内嵌的 {@code GraphOp} 表示玩家在图编辑器 UI 中执行的任何变更——添加/删除节点、连接引脚、
 * 编辑参数、修改视觉样式等。
 *
 * @see GraphOp
 * @see EditSessionRegistry#applyOp(ServerLevel, BlockPos, GraphOp, ServerPlayer)
 */
public record GraphEditOpPacket(GraphOp op, int unknownWireOrdinal) implements CustomPacketPayload {
    /** 独立 logger：不引用 (root) 包的 @Mod 类——其静态初始化依赖 MC 注册表，
     *  而 LoggerFactory 不依赖注册表，headless/JUnit 下也能安全完成类初始化。日志名为本类 FQCN。
     *  Same-name standalone logger: never references the @Mod class — its static init needs
     *  the Minecraft registries, while LoggerFactory does not, so class init stays safe in
     *  plain JUnit too. The log name is this class's FQCN. */
    private static final Logger LOGGER = LoggerFactory.getLogger(GraphEditOpPacket.class);

    /** TEXT 帽违规告警的墙钟节流戳（条目 17②）：60s 一条 WARN 提示「有人在超帽」——
     *  P2-11/K21 纪律下逐包日志会刷盘，逐包细节走既有 DEBUG 通路；仅日志语义，
     *  无行为状态（测试无复位义务）。
     *  Wall-clock throttle stamp for the TEXT-cap violation warning (entry 17②): one
     *  WARN per 60s signals "someone is over the cap" — under the P2-11/K21 discipline
     *  a warn per packet floods the log, while per-attempt detail rides the existing
     *  DEBUG path. Logging-only, no behavioural state (tests owe no reset). */
    private static volatile long lastTextCapWarnMs = Long.MIN_VALUE;
    /** @see #lastTextCapWarnMs / 见 {@link #lastTextCapWarnMs}。 */
    private static final long TEXT_CAP_WARN_INTERVAL_MS = 60_000;

    /**
     * 常规构造（op 类型已知）：未知序号哨兵 = -1。
     * <p>Convenience constructor for ops whose type resolved normally; the
     * unknown-ordinal sentinel is -1.</p>
     */
    public GraphEditOpPacket(GraphOp op) { this(op, -1); }

    /**
     * Packet type identifier registered with NeoForge custom-payload channel.
     * <p>
     * 在 NeoForge 自定义负载通道中注册的数据包类型标识符。
     */
    public static final Type<GraphEditOpPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_edit_op"));

    /**
     * Netty-aware stream codec that serializes/deserializes every field of a
     * {@link GraphOp} in a fixed order matching both sides.
     * <p>
     * 可感知 Netty 的流编解码器，按照双方一致的固定顺序序列化/反序列化
     * {@link GraphOp} 的每一个字段。
     * <p>
     * The decode/encode methods mirror each other exactly.  Any change to the wire
     * format must be made in both methods simultaneously or the protocol will break.
     * <p>
     * decode/encode 方法严格互为镜像。对线上格式的任何修改都必须同时在这两个方法中进行，
     * 否则协议将损坏。
     */
    public static final StreamCodec<ByteBuf, GraphEditOpPacket> CODEC =
        new StreamCodec<>() {
            /**
             * Reads one {@link GraphEditOpPacket} from the raw network buffer.
             * <p>
             * 从原始网络缓冲区读取一个 {@link GraphEditOpPacket}。
             *
             * @param buf the Netty byte buffer / Netty 字节缓冲区
             * @return a fully-populated packet ready for delivery / 一个已完整填充、可投递的数据包
             */
            @Override public GraphEditOpPacket decode(ByteBuf buf) {
                // Wrap the raw Netty buffer in a Minecraft-friendly helper / 将原始 Netty 缓冲区包装为 Minecraft 友好助手
                var b = new net.minecraft.network.FriendlyByteBuf(buf);
                // Operation type ordinal -- tells us which kind of edit this is / 操作类型序号——标识这是哪种编辑操作
                // P2-9：序号先读入并查表，越界**不断连**——按未知序号处理（合成 REJECT 载体，
                // 后续字段照常按位置读满以保持流一致），handler 层走既有 REJECT 通路回执。
                // P2-9: read the ordinal and look it up first; an out-of-range value must NOT
                // drop the connection — treat it as unknown (synthesise a REJECT carrier, still
                // read the remaining fields positionally to keep the stream aligned), and the
                // handler answers via the existing REJECT path.
                int typeOrd = b.readVarInt();
                OpType[] opTypes = OpType.values();
                boolean unknownType = typeOrd < 0 || typeOrd >= opTypes.length;
                OpType type = unknownType ? OpType.REJECT : opTypes[typeOrd];
                // Position of the schematic-compute block in the world / 世界中 schematic-compute 方块的位置
                BlockPos graphPos = b.readBlockPos();
                int ownerNodeId = b.readVarInt();
                int targetNodeId = b.readVarInt();
                int tempId = b.readVarInt();
                // NodeType is nullable -- a null means "not applicable for this op" / NodeType 可为空——null 表示"此操作不适用"
                NodeType nodeType = null;
                if (b.readBoolean()) nodeType = NodeType.BY_ID.get(b.readUtf());
                float x = b.readFloat();
                float y = b.readFloat();
                int fromId = b.readVarInt();
                int fromPin = b.readVarInt();
                int toId = b.readVarInt();
                int toPin = b.readVarInt();
                int paramIndex = b.readVarInt();
                float paramValue = b.readFloat();
                // stringValue is nullable -- absent for numeric-only params / stringValue 可为空——纯数值参数时不存在
                String stringValue = b.readBoolean() ? b.readUtf() : null;
                int colorBg = b.readInt();
                int colorBorder = b.readInt();
                int colorText = b.readInt();
                int sortB = b.readVarInt();
                // Band list is nullable -- absent when the node has no band assignment / Band 列表可为空——节点无波段分配时不存在
                java.util.List<String> bands = null;
                if (b.readBoolean()) {
                    int bandCount = b.readVarInt();
                    // Sanity bound: a malicious client could send a huge count and OOM the server / 安全上限：恶意客户端可能发送极大计数导致服务端 OOM
                    // K15 同源：上限值收编进 OpExecutor.MAX_BANDS（条目 16 —— ADD_NODE 快照
                    // 恢复路径用同一常量钳频段条数，两侧永不漂移）。
                    // K15 same source: the cap now lives in OpExecutor.MAX_BANDS (entry 16 —
                    // the ADD_NODE snapshot restore path clamps band counts with the same
                    // constant, so the two sides can never drift).
                    if (bandCount < 0 || bandCount > io.github.y15173334444.create_schematic_compute.graph.OpExecutor.MAX_BANDS) bandCount = 0;
                    bands = new ArrayList<>(bandCount);
                    for (int i = 0; i < bandCount; i++) bands.add(b.readUtf());
                }
                int keyIndex = b.readVarInt();
                int imageFrameIndex = b.readVarInt();
                int hotbarSlot = b.readVarInt();
                // ItemStack: only present for SET_HOTBAR_ITEM -- skips heavy NBT parse on unrelated ops / 仅在 SET_HOTBAR_ITEM 时存在——无关操作上跳过重量级 NBT 解析
                // 缺省 null 而非 ItemStack.EMPTY：与 GraphOp 的记录契约一致（"其余 op 传
                // null"），也让解码保持 headless 可测（EMPTY 的 <clinit> 需注册表引导）。
                // 伪造的"SET_HOTBAR_ITEM 但无物品"由 handleServer 的 K15 预检拒绝。
                // Default null rather than ItemStack.EMPTY: matches the GraphOp record contract
                // ("all other ops pass null") and keeps decode headless-testable (EMPTY's
                // <clinit> needs registry bootstrap). A forged "SET_HOTBAR_ITEM without item" is
                // refused by the K15 pre-check in handleServer.
                ItemStack itemStack = null;
                if (b.readBoolean()) {
                    // Must cast to RegistryFriendlyByteBuf so the codec can resolve registry entries / 必须转换为 RegistryFriendlyByteBuf，以便编解码器解析注册表条目
                    itemStack = ItemStack.OPTIONAL_STREAM_CODEC.decode(
                        (RegistryFriendlyByteBuf) buf);
                }
                // editVersion is a monotonically-increasing counter for conflict detection / editVersion 是单调递增计数器，用于冲突检测
                long editVersion = b.readVarLong();
                // The UUID of the player who originated this edit / 发起此编辑的玩家的 UUID
                UUID actor = new UUID(b.readLong(), b.readLong());
                // blobRefId links to a binary blob stored separately (e.g. image attachment) / blobRefId 关联到单独存储的二进制数据（例如图像附件）
                int blobRefId = VarInt.read(buf);
                // Raw image pixel data, length-prefixed; null/empty when no image is attached / 原始图像像素数据，带长度前缀；无图像附件时为空
                // P0-1 安全钳制：先校验后分配。合法负载上界 = 像素画布上限
                //（GraphNode.IMAGE_MAX_SIZE² = 32×32 = 1024 像素，SET_IMAGE_PIXELS 是唯一
                // 携带 imageData 的 op），超限 = 协议错误（DecoderException），不静默截断。
                // P0-1 safety clamp: validate before allocate. The legitimate payload ceiling is
                // the pixel-canvas cap (GraphNode.IMAGE_MAX_SIZE² = 32×32 = 1024 pixels;
                // SET_IMAGE_PIXELS is the only op carrying imageData); over-limit is a protocol
                // error (DecoderException), never silently truncated.
                int[] imageData = null;
                int imgLen = VarInt.read(buf);
                if (imgLen < 0 || imgLen > io.github.y15173334444.create_schematic_compute.graph.GraphNode.IMAGE_MAX_SIZE
                        * io.github.y15173334444.create_schematic_compute.graph.GraphNode.IMAGE_MAX_SIZE)
                    throw new io.netty.handler.codec.DecoderException(
                        "GraphEditOpPacket: imageData length out of bounds: " + imgLen
                            + " (limit " + (io.github.y15173334444.create_schematic_compute.graph.GraphNode.IMAGE_MAX_SIZE
                            * io.github.y15173334444.create_schematic_compute.graph.GraphNode.IMAGE_MAX_SIZE) + ")");
                if (imgLen > 0) {
                    imageData = new int[imgLen];
                    for (int i = 0; i < imgLen; i++) imageData[i] = buf.readInt();
                }
                // 自定义节点 id（公开 API 波 1）：尾部追加字段（bool 哨兵 + utf），与
                // 既有 nullable 字段同款镜像纪律 —— encode/decode 两侧必须同步改。
                // Custom-node id (public-API wave 1): trailing appended field (bool sentinel
                // + utf), same mirror discipline as the existing nullable fields — both
                // encode/decode sides must change together.
                String customNodeId = b.readBoolean() ? b.readUtf() : null;
                // Construct the immutable record wrapping the reconstructed GraphOp / 构造包装了重构 GraphOp 的不可变记录
                // 未知 op 序号（P2-9）：合成 REJECT 载体（paramIndex=线缆原始序号，与
                // GraphOp.reject 的打包惯例一致），unknownWireOrdinal 记录真实序号供
                // handler 层回执与日志 —— 不断连、不应用。
                // Unknown op ordinal (P2-9): synthesize a REJECT carrier (paramIndex = the raw
                // wire ordinal, same packing convention as GraphOp.reject); unknownWireOrdinal
                // keeps the real ordinal for the handler's receipt and logging — no disconnect,
                // no application.
                if (unknownType) {
                    return new GraphEditOpPacket(new GraphOp(
                        type, graphPos, ownerNodeId, targetNodeId,
                        tempId, nodeType, x, y,
                        fromId, fromPin, toId, toPin,
                        typeOrd, paramValue, stringValue,
                        colorBg, colorBorder, colorText,
                        sortB, bands, keyIndex, imageFrameIndex,
                        hotbarSlot, itemStack, editVersion, actor,
                        blobRefId, imageData, customNodeId
                    ), typeOrd);
                }
                return new GraphEditOpPacket(new GraphOp(
                    type, graphPos, ownerNodeId, targetNodeId,
                    tempId, nodeType, x, y,
                    fromId, fromPin, toId, toPin,
                    paramIndex, paramValue, stringValue,
                    colorBg, colorBorder, colorText,
                    sortB, bands, keyIndex, imageFrameIndex,
                    hotbarSlot, itemStack, editVersion, actor,
                    blobRefId, imageData, customNodeId
                ));
            }

            /**
             * Writes one {@link GraphEditOpPacket} into the raw network buffer.
             * <p>
             * 将一个 {@link GraphEditOpPacket} 写入原始网络缓冲区。
             * <p>
             * The write order MUST exactly match {@link #decode(ByteBuf)} or the
             * peer will read garbage.
             * <p>
             * 写入顺序必须与 {@link #decode(ByteBuf)} 完全一致，否则对端将读取到无效数据。
             *
             * @param buf the Netty byte buffer / Netty 字节缓冲区
             * @param pkt the packet to serialize / 待序列化的数据包
             */
            @Override public void encode(ByteBuf buf, GraphEditOpPacket pkt) {
                var b = new net.minecraft.network.FriendlyByteBuf(buf);
                GraphOp o = pkt.op;
                b.writeVarInt(o.type().ordinal());
                b.writeBlockPos(o.graphPos());
                b.writeVarInt(o.ownerNodeId());
                b.writeVarInt(o.targetNodeId());
                b.writeVarInt(o.tempId());
                // Write a boolean sentinel so the decoder knows whether nodeType follows / 写入布尔哨兵值，让解码器知道 nodeType 是否跟随
                b.writeBoolean(o.nodeType() != null);
                if (o.nodeType() != null) b.writeUtf(o.nodeType().id);
                b.writeFloat(o.x());
                b.writeFloat(o.y());
                b.writeVarInt(o.fromId());
                b.writeVarInt(o.fromPin());
                b.writeVarInt(o.toId());
                b.writeVarInt(o.toPin());
                b.writeVarInt(o.paramIndex());
                b.writeFloat(o.paramValue());
                // Write a boolean sentinel so the decoder knows whether stringValue follows / 写入布尔哨兵值，让解码器知道 stringValue 是否跟随
                b.writeBoolean(o.stringValue() != null);
                if (o.stringValue() != null) b.writeUtf(o.stringValue());
                b.writeInt(o.colorBg());
                b.writeInt(o.colorBorder());
                b.writeInt(o.colorText());
                b.writeVarInt(o.sortB());
                // Write a boolean sentinel so the decoder knows whether the band list follows / 写入布尔哨兵值，让解码器知道波段列表是否跟随
                b.writeBoolean(o.bands() != null);
                if (o.bands() != null) {
                    b.writeVarInt(o.bands().size());
                    for (String band : o.bands()) b.writeUtf(band);
                }
                b.writeVarInt(o.keyIndex());
                b.writeVarInt(o.imageFrameIndex());
                b.writeVarInt(o.hotbarSlot());
                // ItemStack: only serialize for SET_HOTBAR_ITEM (bandwidth optimization) / 仅为 SET_HOTBAR_ITEM 序列化（带宽优化）
                // Conditionally including the ItemStack avoids sending heavy NBT data on every unrelated op / 有条件地包含 ItemStack，避免在每条无关操作上发送重量级 NBT 数据
                boolean hasItem = o.type() == OpType.SET_HOTBAR_ITEM && o.itemStack() != null;
                b.writeBoolean(hasItem);
                if (hasItem) {
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(
                        (RegistryFriendlyByteBuf) buf, o.itemStack());
                }
                b.writeVarLong(o.editVersion());
                UUID a = o.actor();
                // Guard against null actor UUID -- write zero bits so decoder produces UUID(0,0) / 防御空 UUID —— 写入零位，让解码器产生 UUID(0,0)
                b.writeLong(a != null ? a.getMostSignificantBits() : 0L);
                b.writeLong(a != null ? a.getLeastSignificantBits() : 0L);
                VarInt.write(buf, o.blobRefId());
                int[] img = o.imageData();
                if (img != null && img.length > 0) {
                    VarInt.write(buf, img.length);
                    for (int v : img) buf.writeInt(v);
                } else {
                    // Write zero-length sentinel so the decoder knows no image data follows / 写入零长度哨兵，让解码器知道无图像数据跟随
                    VarInt.write(buf, 0);
                }
                // 自定义节点 id：nullable 字段同款 bool 哨兵镜像（decode 侧尾部读取）。
                // Custom-node id: the same nullable bool-sentinel mirroring (decoded last).
                b.writeBoolean(o.customNodeId() != null);
                if (o.customNodeId() != null) b.writeUtf(o.customNodeId());
            }
        };

    /**
     * Returns the packet type used by NeoForge channel dispatch.
     * <p>
     * 返回 NeoForge 通道分发所使用的数据包类型。
     *
     * @return the registered {@link Type} constant / 已注册的 {@link Type} 常量
     */
    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * Maximum squared distance (128 blocks) a player may be from the
     * schematic-compute block to be allowed to send edit operations.
     * <p>
     * 玩家与 schematic-compute 方块之间允许发送编辑操作的最大平方距离（128 格）。
     * <p>
     * Squared comparison avoids a {@code Math.sqrt()} call on the hot path.
     * <p>
     * 平方比较避免了热路径上的 {@code Math.sqrt()} 调用。
     */
    private static final double MAX_EDIT_DIST_SQ = 128.0 * 128.0;

    /**
     * Server-side handler invoked when a {@link GraphEditOpPacket} arrives from a client.
     * <p>
     * 当 {@link GraphEditOpPacket} 从客户端到达时调用的服务端处理器。
     * <p>
     * The handler performs these security checks before applying the operation:
     * <ol>
     *   <li>The sender must be a valid {@link ServerPlayer} on a {@link ServerLevel}.</li>
     *   <li>Unknown op ordinals (P2-9) are answered with a REJECT receipt instead of crashing.</li>
     *   <li>Out-of-bounds slot / frame / control-point indices (K15) are rejected.</li>
     *   <li>The sender must be within {@link #MAX_EDIT_DIST_SQ} of the target block.</li>
     *   <li>The sender must be a registered editor of the target edit session.</li>
     * </ol>
     * <p>
     * 处理器在应用操作之前执行以下安全检查：
     * <ol>
     *   <li>发送者必须是 {@link ServerLevel} 上的有效 {@link ServerPlayer}。</li>
     *   <li>未知 op 序号（P2-9）以 REJECT 回执应答而非崩溃断连。</li>
     *   <li>越界的槽位 / 帧 / 控制点索引（K15）一律拒绝。</li>
     *   <li>发送者必须在目标方块的 {@link #MAX_EDIT_DIST_SQ} 距离内。</li>
     *   <li>发送者必须是目标编辑会话的已注册编辑者。</li>
     * </ol>
     * <p>
     * On success, the player UUID is stamped into a new {@code GraphOp} (overwriting
     * whatever UUID the client claimed) so the server is the authoritative source of
     * actor identity, and the blob-ownership scope is bound to the sender so
     * {@code BlobRegistry} lookups only see blobs uploaded by this player.
     * <p>
     * 成功时，玩家的 UUID 被写入新的 {@code GraphOp}（覆盖客户端声明的任何 UUID），
     * 使服务端成为操作者身份的权威来源；同时把 blob 归属作用域绑定到发送者，
     * 使 {@code BlobRegistry} 的取用只能看见该玩家自己上传的 blob。
     *
     * @param pkt the incoming packet from the client / 来自客户端的入站数据包
     * @param ctx the NeoForge payload context providing access to the player / NeoForge 负载上下文，提供对玩家的访问
     */
    public static void handleServer(GraphEditOpPacket pkt, IPayloadContext ctx) {
        // enqueueWork defers to the server worker thread so we do not block the Netty I/O thread / enqueueWork 延迟到服务端工作线程，避免阻塞 Netty I/O 线程
        ctx.enqueueWork(() -> {
            // Guard: must be a real server player / 守卫：必须是真实的服务端玩家
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            // Guard: must be on a server level (not a client-only fake world) / 守卫：必须在服务端世界（而非仅客户端的虚拟世界）
            if (!(sp.level() instanceof ServerLevel sl)) return;
            // P2-6 撤销恢复：为 OpExecutor 注入服务端注册表访问（恢复 ENCAPSULATION 子图
            // 里的物品需要）。每个 op 都以发送时的 level 刷新 —— 维度切换后自动取新值，
            // 恢复在本次调用内同步完成，不存在过期引用。
            // P2-6 undo restore: inject the server registry access into OpExecutor (needed to
            // restore ItemStacks inside a restored ENCAPSULATION sub-graph). Refreshed with the
            // sender's current level on every op, so a dimension switch always provides the
            // fresh value; the restore completes synchronously within this call.
            io.github.y15173334444.create_schematic_compute.graph.OpExecutor
                .setRegistryAccess(sp.level().registryAccess());
            // P2-9：未知 op 序号 = 版本不匹配或改包客户端 —— 走既有 REJECT 通路优雅回执
            //（paramIndex 回显线缆原始序号，较新客户端可按自己的枚举定位被拒类型），
            // 绝不断连、绝不应用。
            // P2-9: an unknown op ordinal means a version mismatch or a modified client —
            // answer gracefully via the existing REJECT path (paramIndex echoes the raw wire
            // ordinal so a newer client can locate the refused type in its own enum). Never
            // disconnect, never apply.
            if (pkt.unknownWireOrdinal() >= 0) {
                LOGGER.debug(
                    "[GraphEditOp] unknown op ordinal {} from {}", pkt.unknownWireOrdinal(),
                    sp.getName().getString());
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                    new GraphEditOpSyncPacket(io.github.y15173334444.create_schematic_compute.graph.GraphOp
                        .rejectUnknownOrdinal(pkt.op(), pkt.unknownWireOrdinal(), sp.getUUID())));
                return;
            }
            var pos = pkt.op.graphPos();
            // K15：越界索引在进入会话层之前拒收 —— 走既有 REJECT 通路（客户端归还待 ACK
            // 计数并按类型回滚本地乐观态），执行器内的对应钳制保留作纵深防御。
            // K15: out-of-bounds indices are refused before the session layer — via the
            // existing REJECT path (the client returns its pending-ACK count and rolls back
            // per type); the matching executor-side caps stay as defense in depth.
            String boundsViolation = opBoundsViolation(pkt.op());
            if (boundsViolation != null) {
                // 条目 17②：TEXT 帽违规在 DEBUG 之上加一条 60s 节流的 WARN——正常玩家
                // 打不出 32KB 文本，触达即「异常流量」信号（节流防刷盘）。
                // Entry 17②: a TEXT-cap violation adds a 60s-throttled WARN above the
                // DEBUG trail — a normal player cannot type 32KB of text, so reaching this
                // is an abnormal-traffic signal (throttled so it cannot flood the log).
                if (boundsViolation.startsWith("textParamLength=")) {
                    long now = System.currentTimeMillis();
                    if (now - lastTextCapWarnMs >= TEXT_CAP_WARN_INTERVAL_MS) {
                        lastTextCapWarnMs = now;
                        LOGGER.warn("[GraphEditOp] TEXT cap violated ({}) op={} from {}",
                            boundsViolation, pos, sp.getName().getString());
                    }
                }
                LOGGER.debug(
                    "[GraphEditOp] rejected ({}: {}) op={} from {}", boundsViolation, pos,
                    pkt.op.type(), sp.getName().getString());
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                    new GraphEditOpSyncPacket(io.github.y15173334444.create_schematic_compute.graph.GraphOp.reject(pkt.op, sp.getUUID())));
                return;
            }
            // Security: reject if the player is too far from the edit target / 安全检查：玩家距离编辑目标过远则拒绝
            // 静默丢弃 = 客户端永远收不到 ACK，pendingLocalOps 计数泄漏、编辑区"上传失败"无迹可查：
            // 回 REJECT 归还计数并打日志（问题：节点编辑区输入数据时上传失败）。
            // A silent drop means no ACK ever arrives: the client's pendingLocalOps counter leaks
            // and the edit panel's "upload failed" leaves no trace. Send REJECT to return the
            // counter and log it (issue: edit-panel input upload fails).
            // P2-11：丢弃路径日志降 DEBUG —— 配合 K21 无限流，INFO 每包一条会刷盘。
            // P2-11: drop-path logs downgraded to DEBUG — with K21's unthrottled streams an
            // INFO-per-packet floods the log/disk.
            if (!io.github.y15173334444.create_schematic_compute.network.SablePacketHelper.isWithinReachableRange(sp, pos, MAX_EDIT_DIST_SQ)) {
                LOGGER.debug(
                    "[GraphEditOp] dropped (out of range) op={} from {}", pkt.op.type(), sp.getName().getString());
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                    new GraphEditOpSyncPacket(io.github.y15173334444.create_schematic_compute.graph.GraphOp.reject(pkt.op, sp.getUUID())));
                return;
            }
            // Security: reject if the player is not a registered editor of this session / 安全检查：玩家不是此编辑会话的已注册编辑者则拒绝
            if (!EditSessionRegistry.getEditors(sl, pos).contains(sp.getUUID())) {
                LOGGER.debug(
                    "[GraphEditOp] dropped (not a registered editor) op={} from {}", pkt.op.type(), sp.getName().getString());
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp,
                    new GraphEditOpSyncPacket(io.github.y15173334444.create_schematic_compute.graph.GraphOp.reject(pkt.op, sp.getUUID())));
                return;
            }
            // Rebuild the GraphOp with the server-authoritative actor UUID -- the client-supplied
            // UUID in the packet is intentionally overwritten to prevent spoofing.
            // 使用服务端权威的操作者 UUID 重建 GraphOp——数据包中客户端提供的 UUID 被有意覆盖以防止伪造。
            var authenticatedOp = new GraphOp(
                pkt.op.type(), pkt.op.graphPos(), pkt.op.ownerNodeId(), pkt.op.targetNodeId(),
                pkt.op.tempId(), pkt.op.nodeType(), pkt.op.x(), pkt.op.y(),
                pkt.op.fromId(), pkt.op.fromPin(), pkt.op.toId(), pkt.op.toPin(),
                pkt.op.paramIndex(), pkt.op.paramValue(), pkt.op.stringValue(),
                pkt.op.colorBg(), pkt.op.colorBorder(), pkt.op.colorText(),
                pkt.op.sortB(), pkt.op.bands(), pkt.op.keyIndex(), pkt.op.imageFrameIndex(),
                pkt.op.hotbarSlot(), pkt.op.itemStack(),
                pkt.op.editVersion(), sp.getUUID(),
                pkt.op.blobRefId(), pkt.op.imageData(),
                pkt.op.customNodeId());
            // Apply the authenticated operation to the edit session / 将已鉴权的操作应用到编辑会话
            // P0-2 blob 归属：把发送者身份绑定为本次会话内 BlobRegistry 取用的请求者 ——
            // poll/peek 只能取到**本玩家**上传的 blob，其他玩家同 id 的预置字节不可见
            //（串包投毒被消除；异常路径 finally 清除作用域，fail-closed）。
            // P0-2 blob ownership: bind the sender as the requester for BlobRegistry lookups
            // within this apply — poll/peek only see blobs uploaded by *this* player; another
            // player's same-id bytes are invisible (cross-player poisoning eliminated; the
            // finally clears the scope so failures fail closed).
            io.github.y15173334444.create_schematic_compute.network.BlobRegistry.beginOwnerScope(sp.getUUID());
            try {
                EditSessionRegistry.applyOp(sl, pos, authenticatedOp, sp);
            } finally {
                io.github.y15173334444.create_schematic_compute.network.BlobRegistry.endOwnerScope();
            }
        });
    }

    /**
     * K15 边界预检：返回违规描述（可读日志用），合法 op 返回 null。
     * 覆盖三类客户端可控索引：热栏槽位（实机 9 格）、像素帧索引（帧列表扩容上限）、
     * 控制点对数（曲线节点实际用量 ≤ 64 点）。
     * <p>K15 bounds pre-check: returns a violation description (for readable logs) or null
     * for a legal op. Covers the three client-controlled indices: hotbar slot (9 on a real
     * player), pixel frame index (frame-list expansion cap), and control-point pairs
     * (curve nodes actually use at most 64 points).</p>
     *
     * @param op the op to check / 待检查的 op
     * @return violation description, or null if in bounds / 违规描述；界内返回 null
     */
    static String opBoundsViolation(io.github.y15173334444.create_schematic_compute.graph.GraphOp op) {
        switch (op.type()) {
            case SET_HOTBAR_ITEM -> {
                // itemStack 缺失（编码旗标为假的伪造 op）= 拒绝：落 null 槽会让 GraphNode.save
                // 的 saveOptional NPE —— 服务端权威路径绝不写入 null 物品。
                // A missing itemStack (forged op with the encode flag false) = refuse: a null
                // slot would NPE GraphNode.save's saveOptional — the authoritative server path
                // never writes a null item.
                // CUSTOM：hotbarSlot 承载 schema ITEM 控件序位——静态上界 = MAX_SCHEMA_PARAMS
                // （精确界 = 节点实际 schema 槽位数，执行器纵深防御裁定）。
                // CUSTOM: hotbarSlot carries the schema ITEM control slot — the static bound
                // is MAX_SCHEMA_PARAMS (the precise bound = the node's real schema slot count,
                // ruled by the executor as defense in depth).
                if (op.nodeType() == io.github.y15173334444.create_schematic_compute.graph.NodeType.CUSTOM) {
                    if (op.hotbarSlot() < 0 || op.hotbarSlot() >= io.github.y15173334444.create_schematic_compute.api.CustomNodeType.MAX_SCHEMA_PARAMS)
                        return "customItemSlot=" + op.hotbarSlot();
                } else if (op.hotbarSlot() < 0 || op.hotbarSlot() >= io.github.y15173334444.create_schematic_compute.graph.OpExecutor.HOTBAR_SLOTS)
                    return "hotbarSlot=" + op.hotbarSlot();
                if (op.itemStack() == null)
                    return "itemStack=null";
            }
            case SET_IMAGE_PIXELS -> {
                // paramIndex 承载帧索引（见 GraphOp.setImagePixels）
                // paramIndex carries the frame index (see GraphOp.setImagePixels)
                if (op.paramIndex() < 0 || op.paramIndex() > io.github.y15173334444.create_schematic_compute.graph.OpExecutor.MAX_SEQUENCE_FRAMES)
                    return "frameIndex=" + op.paramIndex();
            }
            case SET_CUSTOM_TEXT -> {
                // 条目 17②（批 D）：单条 TEXT 超 per-control 帽 = REJECT（客户端回滚），
                // 不再任由线缆 readUtf 静默截断出「写进去了但被剪」的错文本。常量同源
                // api.CustomNodeType.MAX_TEXT_PARAM_LENGTH（执行器纵深防御同值）。
                // Entry 17② (batch D): a TEXT over the per-control cap is REJECTed (the
                // client rolls back) instead of relying on the wire readUtf's silent
                // truncation producing "written but clipped" text. The constant is
                // api.CustomNodeType.MAX_TEXT_PARAM_LENGTH (the executor's defense in
                // depth references the same value).
                if (op.stringValue() != null
                    && op.stringValue().length() > io.github.y15173334444.create_schematic_compute.api.CustomNodeType.MAX_TEXT_PARAM_LENGTH)
                    return "textParamLength=" + op.stringValue().length();
            }
            case SET_CTRL_POINTS -> {
                // 点数上限在 parseCtrlPoints 内也钳（返回 null → 执行器跳过）；
                // 此处以 REJECT 语义前置拒绝，客户端可回滚。
                // The point cap is also clamped in parseCtrlPoints (null → executor skips);
                // here it is rejected up front with REJECT semantics so the client can roll back.
                int points = op.stringValue() == null ? 0 : op.stringValue().split(";").length;
                if (points > io.github.y15173334444.create_schematic_compute.graph.GraphOp.MAX_CTRL_POINTS)
                    return "ctrlPoints=" + points;
            }
            default -> { /* 其余 op 无客户端可控数组索引 / other ops carry no client-sized array index */ }
        }
        return null;
    }

}
