package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.EditSessionRegistry;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.OpType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
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

public record GraphEditOpPacket(GraphOp op) implements CustomPacketPayload {

    /** C→S: client emits an edit op to the server. */
    public static final Type<GraphEditOpPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_edit_op"));
    /** S→C: server broadcasts an applied op to other editors. */
    public static final Type<GraphEditOpPacket> TYPE_SYNC =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_edit_op_sync"));

    public static final StreamCodec<ByteBuf, GraphEditOpPacket> CODEC =
        new StreamCodec<>() {
            @Override public GraphEditOpPacket decode(ByteBuf buf) {
                var b = new FriendlyByteBuf(buf);
                OpType type = OpType.values()[b.readVarInt()];
                BlockPos graphPos = b.readBlockPos();
                int ownerNodeId = b.readVarInt();
                int targetNodeId = b.readVarInt();
                int tempId = b.readVarInt();
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
                String stringValue = b.readBoolean() ? b.readUtf() : null;
                int colorBg = b.readInt();
                int colorBorder = b.readInt();
                int colorText = b.readInt();
                int sortB = b.readVarInt();
                java.util.List<String> bands = null;
                if (b.readBoolean()) {
                    int bandCount = b.readVarInt();
                    if (bandCount < 0 || bandCount > 64) // upper bound: prevent OOM
                        bandCount = 0;
                    bands = new ArrayList<>(bandCount);
                    for (int i = 0; i < bandCount; i++) bands.add(b.readUtf());
                }
                int keyIndex = b.readVarInt();
                int imageFrameIndex = b.readVarInt();
                int hotbarSlot = b.readVarInt();
                b.readBoolean(); // skip hasItem (Phase 2)
                long editVersion = b.readVarLong();
                UUID actor = new UUID(b.readLong(), b.readLong());
                return new GraphEditOpPacket(new GraphOp(
                    type, graphPos, ownerNodeId, targetNodeId,
                    tempId, nodeType, x, y,
                    fromId, fromPin, toId, toPin,
                    paramIndex, paramValue, stringValue,
                    colorBg, colorBorder, colorText,
                    sortB, bands, keyIndex, imageFrameIndex,
                    hotbarSlot, ItemStack.EMPTY, editVersion, actor
                ));
            }
            @Override public void encode(ByteBuf buf, GraphEditOpPacket pkt) {
                var b = new FriendlyByteBuf(buf);
                GraphOp o = pkt.op;
                b.writeVarInt(o.type().ordinal());
                b.writeBlockPos(o.graphPos());
                b.writeVarInt(o.ownerNodeId());
                b.writeVarInt(o.targetNodeId());
                b.writeVarInt(o.tempId());
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
                b.writeBoolean(o.stringValue() != null);
                if (o.stringValue() != null) b.writeUtf(o.stringValue());
                b.writeInt(o.colorBg());
                b.writeInt(o.colorBorder());
                b.writeInt(o.colorText());
                b.writeVarInt(o.sortB());
                b.writeBoolean(o.bands() != null);
                if (o.bands() != null) {
                    b.writeVarInt(o.bands().size());
                    for (String band : o.bands()) b.writeUtf(band);
                }
                b.writeVarInt(o.keyIndex());
                b.writeVarInt(o.imageFrameIndex());
                b.writeVarInt(o.hotbarSlot());
                b.writeBoolean(false); // hasItem (Phase 2)
                b.writeVarLong(o.editVersion());
                UUID a = o.actor();
                b.writeLong(a != null ? a.getMostSignificantBits() : 0L);
                b.writeLong(a != null ? a.getLeastSignificantBits() : 0L);
            }
        };

    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** Max edit distance (squared) — ~80 blocks range. */
    private static final double MAX_EDIT_DIST_SQ = 80.0 * 80.0;

    public static void handleServer(GraphEditOpPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel sl)) return;
            var pos = pkt.op.graphPos();
            // 1. Range check — prevent arbitrary chunk loading / remote editing
            double dx = sp.getX() - pos.getX();
            double dz = sp.getZ() - pos.getZ();
            if (dx * dx + dz * dz > MAX_EDIT_DIST_SQ) return;
            // 2. Session membership check (join required before editing)
            if (!EditSessionRegistry.getEditors(sl, pos).contains(sp.getUUID())) return;
            // 3. Overwrite actor UUID with the authenticated sender
            var authenticatedOp = new GraphOp(
                pkt.op.type(), pkt.op.graphPos(), pkt.op.ownerNodeId(), pkt.op.targetNodeId(),
                pkt.op.tempId(), pkt.op.nodeType(), pkt.op.x(), pkt.op.y(),
                pkt.op.fromId(), pkt.op.fromPin(), pkt.op.toId(), pkt.op.toPin(),
                pkt.op.paramIndex(), pkt.op.paramValue(), pkt.op.stringValue(),
                pkt.op.colorBg(), pkt.op.colorBorder(), pkt.op.colorText(),
                pkt.op.sortB(), pkt.op.bands(), pkt.op.keyIndex(), pkt.op.imageFrameIndex(),
                pkt.op.hotbarSlot(), pkt.op.itemStack(),
                pkt.op.editVersion(), sp.getUUID());
            EditSessionRegistry.applyOp(sl, pos, authenticatedOp, sp);
        });
    }

    public static void handleClient(GraphEditOpPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() == null) return;
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.screen instanceof io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.Host host
                && host.getBlockPos().equals(pkt.op.graphPos())) {
                host.onRemoteOp(pkt.op);
            }
        });
    }
}
