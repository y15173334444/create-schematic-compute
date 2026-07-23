package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Client→Server: upload BUS band changes (does not trigger compilation) / 客户端→服务端：上传 BUS 频段变更（不触发编译） */
public record BusBandUploadPacket(BlockPos pos, String busName, List<String> bands) implements CustomPacketPayload {

    public static final Type<BusBandUploadPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "bus_band_upload"));

    public static final StreamCodec<ByteBuf, BusBandUploadPacket> CODEC = new StreamCodec<>() {
        @Override public BusBandUploadPacket decode(ByteBuf buf) {
            var b = new FriendlyByteBuf(buf);
            return new BusBandUploadPacket(b.readBlockPos(), b.readUtf(),
                b.readList(FriendlyByteBuf::readUtf));
        }
        @Override public void encode(ByteBuf buf, BusBandUploadPacket pkt) {
            var b = new FriendlyByteBuf(buf);
            b.writeBlockPos(pkt.pos);
            b.writeUtf(pkt.busName);
            b.writeCollection(pkt.bands != null ? pkt.bands : List.of(), FriendlyByteBuf::writeUtf);
        }
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // 安全校验：距离检查 + 编辑会话成员检查
            if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            if (!(sp.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;
            double dx = sp.getX() - pos.getX();
            double dz = sp.getZ() - pos.getZ();
            if (dx * dx + dz * dz > 16384.0) return;
            if (!io.github.y15173334444.create_schematic_compute.blocks.EditSessionRegistry.getEditors(serverLevel, pos).contains(sp.getUUID()))
                return;
            var be = ctx.player().level().getBlockEntity(pos);
            if (be instanceof GraphBlockEntity gbe) {
                // EN: Update signalBands of BUS_OUT/BUS_IN in the server-side graph
                // 更新服务端图中 BUS_OUT/BUS_IN 的 signalBands
                var graph = gbe.getNodeGraph();
                if (graph != null) {
                    for (var n : graph.nodes) {
                        if ((n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT
                            || n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_IN)
                            && n.signalName.equals(busName)) {
                            n.signalBands = bands != null ? new ArrayList<>(bands) : new ArrayList<>();
                            n.bandsDirty = true;
                        }
                    }
                }
                // EN: Register to global table; unregister via ref-count when bands are empty
                // 注册到全局表；空频段时通过引用计数取消注册
                if (bands != null && !bands.isEmpty()) {
                    SignalBus.registerBands(busName, bands);
                } else {
                    SignalBus.clearBus(busName);
                    // EN: Unregister this BUS_OUT's channel (via ref-count cleanup, doesn't affect other BUS_OUT with the same name)
                    // 取消注册此 BUS_OUT 的频道（通过引用计数清理，不影响其他同名 BUS_OUT）
                    for (var n : graph.nodes) {
                        if (n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT
                            && n.signalName.equals(busName)) {
                            SignalBus.unregisterChannel(busName,
                                new io.github.y15173334444.create_schematic_compute.network.ChannelOwner(pos, n.id));
                        }
                    }
                }
                // EN: Notify all clients (including sender, so BUS_IN nodes in other blocks update)
                // 通知所有客户端（含发送者，以便更新其他方块中的 BUS_IN 节点）
                if (be instanceof net.minecraft.world.level.block.entity.BlockEntity bet) {
                    var level = bet.getLevel();
                    if (level instanceof net.minecraft.server.level.ServerLevel sl) {
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingChunk(sl,
                            new net.minecraft.world.level.ChunkPos(pos),
                            new BusBandSyncPacket(pos, busName, bands));
                    }
                }
            }
        });
    }
}
