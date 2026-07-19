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

/** Client→Server: player closed the edit UI for a graph. / 客户端→服务端：玩家关闭了图的编辑界面。 */
public record GraphLeavePacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<GraphLeavePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_leave"));

    public static final StreamCodec<RegistryFriendlyByteBuf, GraphLeavePacket> CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, GraphLeavePacket::pos,
            GraphLeavePacket::new
        );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(GraphLeavePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                var level = sp.serverLevel();
                var editors = io.github.y15173334444.create_schematic_compute.blocks.EditSessionRegistry.getEditors(level, pkt.pos);
                // Remove self / 移除自己
                EditSessionRegistry.leave(level, pkt.pos, sp.getUUID());
                // Broadcast "left" notification to remaining editors so they remove this player immediately / 向其余编辑器广播"离开"通知，使其立即移除该玩家
                var leftPkt = new GraphPresencePacket(pkt.pos, sp.getUUID(), "", 0, 0f, 0f, -1, -1, -1, 0, 0f, 0f);
                for (var editorId : editors) {
                    if (editorId.equals(sp.getUUID())) continue;
                    var ep = level.getServer().getPlayerList().getPlayer(editorId);
                    if (ep != null)
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(ep,
                            new GraphPresenceSyncPacket(leftPkt));
                }
            }
        });
    }
}
