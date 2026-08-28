package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.GraphEditor;
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
     * Client-side handler: dispatches the acknowledgment to the open GraphEditor screen.
     * 客户端处理函数：将确认信息分发给当前打开的 GraphEditor 界面。
     *
     * @param pkt The received acknowledgment packet / 接收到的确认数据包
     * @param ctx The network payload context / 网络载荷上下文
     */
    public static void handle(GraphEditAckPacket pkt, IPayloadContext ctx) {
        // Enqueue on the main/render thread because all GUI interactions must happen there.
        // 将任务加入主线程/渲染线程队列，因为所有 GUI 交互必须在主线程执行。
        ctx.enqueueWork(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            // Decrement the pending local-op counter on the target BE regardless of screen
            // state — the ack may arrive after the editor closed. Rejected ops are decremented
            // in GraphEditor.onRemoteOp instead (they never reach here as an ACK).
            // 无论编辑器是否仍打开，都按坐标对 BE 的待 ACK 计数递减（ACK 可能晚于关闭到达）。
            // 被拒 op 在 GraphEditor.onRemoteOp 中递减（不会作为 ACK 到达此处）。
            if (mc.level != null
                && mc.level.getBlockEntity(pkt.pos) instanceof io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity gbe) {
                gbe.setPendingLocalOps(Math.max(0, gbe.getPendingLocalOps() - 1));
            }
            // Only apply if the player still has the same GraphEditor open at the same position.
            // 只有当玩家仍在相同坐标打开着同一个 GraphEditor 时才应用更新。
            if (mc.screen instanceof GraphEditor.Host host && host.getBlockPos().equals(pkt.pos)) {
                host.handleAck(pkt);
            }
            // If the screen is no longer open or the position differs, the ack is intentionally
            // dropped — the next time the graph editor opens it will do a full sync from the server.
            // 如果界面已关闭或坐标不匹配，则有意丢弃此确认——
            // 下次打开图编辑器时会从服务端进行全量同步。
        });
    }
}
