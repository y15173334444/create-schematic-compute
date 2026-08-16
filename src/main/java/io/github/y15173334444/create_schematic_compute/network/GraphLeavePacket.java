package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.EditSessionRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * Client→Server: player closed the edit UI for a graph.
 * 客户端→服务端：玩家关闭了图的编辑界面。
 *
 * <p>Sent when a player closes the schematic-graph editing screen. The server
 * removes the player from the co-editing session and notifies all remaining
 * editors so they can reflect the departure in their local UI immediately.</p>
 *
 * <p>当玩家关闭原理图编辑器界面时发送。服务端将该玩家从协同编辑会话中移除，
 * 并通知所有剩余编辑者，使其本地界面立即反映该玩家的离开。</p>
 *
 * @param pos the block position of the edit session / 编辑会话所在的方块坐标
 */
public record GraphLeavePacket(BlockPos pos) implements CustomPacketPayload {

    /**
     * Packet type identifier registered with the NeoForge network channel.
     * 向 NeoForge 网络通道注册的数据包类型标识符。
     */
    public static final Type<GraphLeavePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_leave"));

    /**
     * Stream codec: serializes/deserializes this packet from a network buffer.
     * Uses {@link BlockPos#STREAM_CODEC} to encode/decode the single {@code pos} field.
     *
     * 流编解码器：从网络缓冲区序列化/反序列化此数据包。
     * 使用 {@link BlockPos#STREAM_CODEC} 编解码唯一的 {@code pos} 字段。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, GraphLeavePacket> CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, GraphLeavePacket::pos,
            GraphLeavePacket::new
        );

    /**
     * Returns the packet type. Required by {@link CustomPacketPayload}.
     * 返回数据包类型。{@link CustomPacketPayload} 接口要求实现。
     *
     * @return {@link #TYPE}
     */
    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * Server-side handler: removes the player from the edit session and
     * broadcasts a "left" presence update to all remaining editors.
     *
     * 服务端处理：将玩家从编辑会话中移除，并向所有剩余编辑者广播"离开"的在线状态更新。
     *
     * @param pkt the received {@code GraphLeavePacket}
     *            接收到的 {@code GraphLeavePacket}
     * @param ctx the payload context providing access to the sending player
     *            提供发送方玩家访问入口的载荷上下文
     */
    public static void handle(GraphLeavePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // Only process on the server side / 仅在服务端处理
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                var level = sp.serverLevel();

                // Snapshot current editor set BEFORE removal, so we know who to broadcast to.
                // 在移除前先获取当前编辑者集合的快照，以确定需要向谁广播。
                var editors = io.github.y15173334444.create_schematic_compute.blocks.EditSessionRegistry.getEditors(level, pkt.pos);

                // Remove self from the co-editing session / 从协同编辑会话中移除自己
                EditSessionRegistry.leave(level, pkt.pos, sp.getUUID());

                // Build a "left" presence packet — all fields zeroed/empty to signal departure.
                // The -1 cursor values are the sentinel that tells the client this user has left.
                // 构建"离开"的在线状态数据包 — 所有字段归零/置空以表示离开。
                // -1 的光标值是通知客户端该用户已离开的特殊标记。
                var leftPkt = new GraphPresencePacket(pkt.pos, sp.getUUID(), "", 0, 0f, 0f, -1, -1, -1, 0, 0f, 0f, new int[0], (byte)0, -1);

                // Broadcast "left" notification to remaining editors so they remove this player immediately.
                // 向其余编辑者广播"离开"通知，使其立即移除该玩家。
                for (var editorId : editors) {
                    // Skip self — we already left the session above / 跳过自身 — 已在上述步骤离开会话
                    if (editorId.equals(sp.getUUID())) continue;

                    var ep = level.getServer().getPlayerList().getPlayer(editorId);
                    if (ep != null)
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(ep,
                            // Wrap in a sync packet so all presence updates arrive in a consistent bundle.
                            // 包装为同步数据包，确保所有在线状态更新以一致的批次到达。
                            new GraphPresenceSyncPacket(leftPkt));
                }
            }
        });
    }
}
