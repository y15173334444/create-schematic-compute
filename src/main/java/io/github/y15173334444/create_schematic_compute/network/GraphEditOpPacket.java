package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.EditSessionRegistry;
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
public record GraphEditOpPacket(GraphOp op) implements CustomPacketPayload {

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
                OpType type = OpType.values()[b.readVarInt()];
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
                    if (bandCount < 0 || bandCount > 64) bandCount = 0;
                    bands = new ArrayList<>(bandCount);
                    for (int i = 0; i < bandCount; i++) bands.add(b.readUtf());
                }
                int keyIndex = b.readVarInt();
                int imageFrameIndex = b.readVarInt();
                int hotbarSlot = b.readVarInt();
                // ItemStack: only present for SET_HOTBAR_ITEM -- skips heavy NBT parse on unrelated ops / 仅在 SET_HOTBAR_ITEM 时存在——无关操作上跳过重量级 NBT 解析
                ItemStack itemStack = ItemStack.EMPTY;
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
                int[] imageData = null;
                int imgLen = VarInt.read(buf);
                if (imgLen > 0) {
                    imageData = new int[imgLen];
                    for (int i = 0; i < imgLen; i++) imageData[i] = buf.readInt();
                }
                // Construct the immutable record wrapping the reconstructed GraphOp / 构造包装了重构 GraphOp 的不可变记录
                return new GraphEditOpPacket(new GraphOp(
                    type, graphPos, ownerNodeId, targetNodeId,
                    tempId, nodeType, x, y,
                    fromId, fromPin, toId, toPin,
                    paramIndex, paramValue, stringValue,
                    colorBg, colorBorder, colorText,
                    sortB, bands, keyIndex, imageFrameIndex,
                    hotbarSlot, itemStack, editVersion, actor,
                    blobRefId, imageData
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
     * The handler performs three security checks before applying the operation:
     * <ol>
     *   <li>The sender must be a valid {@link ServerPlayer} on a {@link ServerLevel}.</li>
     *   <li>The sender must be within {@link #MAX_EDIT_DIST_SQ} of the target block.</li>
     *   <li>The sender must be a registered editor of the target edit session.</li>
     * </ol>
     * <p>
     * 处理器在应用操作之前执行三项安全检查：
     * <ol>
     *   <li>发送者必须是 {@link ServerLevel} 上的有效 {@link ServerPlayer}。</li>
     *   <li>发送者必须在目标方块的 {@link #MAX_EDIT_DIST_SQ} 距离内。</li>
     *   <li>发送者必须是目标编辑会话的已注册编辑者。</li>
     * </ol>
     * <p>
     * On success, the player UUID is stamped into a new {@code GraphOp} (overwriting
     * whatever UUID the client claimed) so the server is the authoritative source of
     * actor identity.
     * <p>
     * 成功时，玩家的 UUID 被写入新的 {@code GraphOp}（覆盖客户端声明的任何 UUID），
     * 使服务端成为操作者身份的权威来源。
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
            var pos = pkt.op.graphPos();
            // Security: reject if the player is too far from the edit target / 安全检查：玩家距离编辑目标过远则拒绝
            if (!io.github.y15173334444.create_schematic_compute.network.SablePacketHelper.isWithinReachableRange(sp, pos, MAX_EDIT_DIST_SQ)) return;
            // Security: reject if the player is not a registered editor of this session / 安全检查：玩家不是此编辑会话的已注册编辑者则拒绝
            if (!EditSessionRegistry.getEditors(sl, pos).contains(sp.getUUID())) return;
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
                pkt.op.blobRefId(), pkt.op.imageData());
            // Apply the authenticated operation to the edit session / 将已鉴权的操作应用到编辑会话
            EditSessionRegistry.applyOp(sl, pos, authenticatedOp, sp);
        });
    }

}
