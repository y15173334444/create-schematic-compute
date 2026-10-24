package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * Server-to-client acknowledgment packet for a graph edit operation.
 * 服务端到客户端的图编辑操作确认包。
 *
 * <p>This packet is sent after the server processes a {@link GraphEditRequestPacket}.
 * It carries the server-assigned node ID (for ADD_NODE_REQUEST edits) and the
 * updated graph editVersion so the client can reconcile its local graph state
 * with the authoritative server state without a full re-sync.</p>
 *
 * <p>此数据包在服务端处理完 {@link GraphEditRequestPacket} 后发送。
 * 它携带服务端分配的节点 ID（针对 ADD_NODE_REQUEST 类型的编辑）以及
 * 更新后的图 editVersion，以便客户端在无需全量重新同步的情况下，
 * 将本地图状态与服务端的权威状态对齐。</p>
 *
 * <p><b>E2 解耦 / E2 decoupling</b>：本类曾直接 import {@code blocks/GraphEditor}
 * （网络层伸进客户端 UI）。现在网络层只定义 {@link EditAckSink} 接口并保存一个静态
 * 汇点引用——唯一的客户端实现 {@code client/GraphAckRouter} 在 ClientSetup 初始化时注册，
 * import 方向反转为 client → network。无人注册（专用服务器、客户端初始化窗口）时
 * 静默丢弃，与旧的「编辑器已关则丢」语义一致。</p>
 *
 * <p>The class used to import {@code blocks/GraphEditor} directly (the network layer
 * reaching into client UI). The network layer now only defines the {@link EditAckSink}
 * interface and holds one static sink reference — the single client implementation
 * {@code client/GraphAckRouter} registers itself during ClientSetup init, reversing the
 * import direction to client → network. With nobody registered (dedicated server, the
 * client-init window) the ack is silently dropped, matching the old "editor closed" drop
 * semantics.</p>
 *
 * @param pos         The block position of the graph editor tile entity
 *                    图编辑器方块实体的坐标位置
 * @param tempId      The client-assigned temporary node ID (used to match the pending request)
 *                    客户端分配的临时节点 ID（用于匹配待处理请求）
 * @param assignedId  The server-assigned permanent node ID (negative if the edit was rejected or is not an ADD)
 *                    服务端分配的永久节点 ID（若编辑被拒绝或非 ADD 操作则为负数）
 * @param editVersion The monotonic edit counter after this edit was applied
 *                    此编辑应用后的单调递增编辑计数器
 */
public record GraphEditAckPacket(BlockPos pos, int tempId, int assignedId, long editVersion)
    implements CustomPacketPayload {

    /**
     * Packet type identifier registered with NeoForge's network registry.
     * 在 NeoForge 网络注册表中注册的数据包类型标识符。
     */
    public static final Type<GraphEditAckPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_edit_ack"));

    /**
     * Stream codec for serializing/deserializing this packet over the network.
     * Uses composite encoding: position (BlockPos), tempId (VAR_INT), assignedId (VAR_INT), editVersion (VAR_LONG).
     * 用于在网络中序列化/反序列化此数据包的流编解码器。
     * 采用组合编码：位置 (BlockPos)、tempId (VAR_INT)、assignedId (VAR_INT)、editVersion (VAR_LONG)。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, GraphEditAckPacket> CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, GraphEditAckPacket::pos,
            ByteBufCodecs.VAR_INT, GraphEditAckPacket::tempId,
            ByteBufCodecs.VAR_INT, GraphEditAckPacket::assignedId,
            ByteBufCodecs.VAR_LONG, GraphEditAckPacket::editVersion,
            GraphEditAckPacket::new
        );

    /**
     * {@return the packet type} / 返回数据包类型。
     */
    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * Client-side ACK consumer, implemented by the UI layer (see
     * {@code client/GraphAckRouter}). The handler hands the whole packet over on the
     * main thread; the implementation owns both consumption steps the old inline handler
     * did: releasing the BE's pending-op counter (acks may arrive after the editor
     * closed) and routing to the open editor screen (mismatched position → intentional
     * drop, matching the old "editor closed" semantics).
     * 客户端 ACK 消费方，由 UI 层实现（见 {@code client/GraphAckRouter}）。handler 在
     * 主线程把整包交出；实现方承担旧内联 handler 的两步消费逻辑：归还 BE 的待 op 计数
     * （ACK 可能晚于编辑器关闭到达）与路由给打开的编辑器屏（坐标不匹配 → 有意丢弃，
     * 与旧的「编辑器已关则丢」语义一致）。
     */
    public interface EditAckSink {
        /**
         * An edit ACK arrived on the main/render thread.
         * 一条编辑 ACK 已在主线程/渲染线程到达。
         *
         * @param pkt the received acknowledgment packet / 接收到的确认数据包
         */
        void onAck(GraphEditAckPacket pkt);
    }

    /** Registered client sink; null on dedicated servers and before client init. 已注册的客户端汇点；专用服务器与客户端初始化前为 null。 */
    private static volatile EditAckSink clientSink;

    /**
     * Register the client sink (client init only — see {@code ClientSetup.clientSetup}).
     * 注册客户端汇点（仅客户端初始化调用 —— 见 {@code ClientSetup.clientSetup}）。
     *
     * @param sink the sink implementation, or null to unregister / 汇点实现，null 为注销
     */
    public static void setClientSink(@org.jetbrains.annotations.Nullable EditAckSink sink) { clientSink = sink; }

    /**
     * Client-side handler: hands the ack to the registered {@link EditAckSink} on the
     * main thread; dropped silently when nobody is registered.
     * 客户端处理函数：在主线程把确认交给已注册的 {@link EditAckSink}；无人注册时静默丢弃。
     *
     * @param pkt The received acknowledgment packet / 接收到的确认数据包
     * @param ctx The network payload context / 网络载荷上下文
     */
    public static void handle(GraphEditAckPacket pkt, IPayloadContext ctx) {
        // Enqueue on the main/render thread because all GUI interactions must happen there.
        // 将任务加入主线程/渲染线程队列，因为所有 GUI 交互必须在主线程执行。
        ctx.enqueueWork(() -> dispatch(pkt));
    }

    /**
     * Dispatch to the registered sink (package-private: the headless routing test calls
     * this synchronously instead of stubbing {@code IPayloadContext}).
     * 分发到已注册汇点（包私有：headless 路由测试同步调用它，无需伪造
     * {@code IPayloadContext}）。
     */
    static void dispatch(GraphEditAckPacket pkt) {
        var sink = clientSink;
        if (sink != null) sink.onAck(pkt);
    }
}
