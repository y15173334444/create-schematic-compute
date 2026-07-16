package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.EditSessionRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import io.netty.buffer.ByteBuf;

import java.util.UUID;

public record GraphPresencePacket(
    BlockPos pos, UUID player, String playerName,
    int ownerNodeId, float cursorX, float cursorY,
    int selectedNodeId, int editingNodeId,
    int wireFromNode, int wireFromPin, float wireEndX, float wireEndY
) implements CustomPacketPayload {

    public static final Type<GraphPresencePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_presence"));

    public static final StreamCodec<ByteBuf, GraphPresencePacket> CODEC =
        new StreamCodec<>() {
            @Override public GraphPresencePacket decode(ByteBuf buf) {
                var b = new FriendlyByteBuf(buf);
                BlockPos pos = b.readBlockPos();
                UUID player = new UUID(b.readLong(), b.readLong());
                String name = b.readUtf();
                int owner = b.readVarInt();
                float cx = b.readFloat();
                float cy = b.readFloat();
                int sel = b.readVarInt();
                int edit = b.readVarInt();
                int wfn = b.readVarInt();
                int wfp = b.readVarInt();
                float wex = b.readFloat();
                float wey = b.readFloat();
                return new GraphPresencePacket(pos, player, name, owner, cx, cy, sel, edit, wfn, wfp, wex, wey);
            }
            @Override public void encode(ByteBuf buf, GraphPresencePacket p) {
                var b = new FriendlyByteBuf(buf);
                b.writeBlockPos(p.pos);
                b.writeLong(p.player.getMostSignificantBits());
                b.writeLong(p.player.getLeastSignificantBits());
                b.writeUtf(p.playerName);
                b.writeVarInt(p.ownerNodeId);
                b.writeFloat(p.cursorX);
                b.writeFloat(p.cursorY);
                b.writeVarInt(p.selectedNodeId);
                b.writeVarInt(p.editingNodeId);
                b.writeVarInt(p.wireFromNode);
                b.writeVarInt(p.wireFromPin);
                b.writeFloat(p.wireEndX);
                b.writeFloat(p.wireEndY);
            }
        };

    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handleServer(GraphPresencePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            var sync = new GraphPresenceSyncPacket(pkt);
            var editors = EditSessionRegistry.getEditors(sp.serverLevel(), pkt.pos);
            // Use authenticated player UUID, ignore client-supplied UUID
            var senderUUID = sp.getUUID();
            for (var editorId : editors) {
                if (editorId.equals(senderUUID)) continue;
                var ep = sp.getServer().getPlayerList().getPlayer(editorId);
                if (ep != null) PacketDistributor.sendToPlayer(ep, sync);
            }
        });
    }

    public static void handleClient(GraphPresencePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.Host host
                && host.getBlockPos().equals(pkt.pos)) {
                host.getEditor().storeRemotePresence(pkt);
            }
        });
    }
}
