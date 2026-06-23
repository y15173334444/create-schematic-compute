package com.example.create_schematic_compute.network;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.blocks.GraphBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** 客户端→服务端：上传 BUS 频段变更（不触发编译） */
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
            var be = ctx.player().level().getBlockEntity(pos);
            if (be instanceof GraphBlockEntity gbe) {
                // 更新服务端图中 BUS_OUT/BUS_IN 的 signalBands
                var graph = gbe.getNodeGraph();
                if (graph != null) {
                    for (var n : graph.nodes) {
                        if ((n.type == com.example.create_schematic_compute.graph.NodeType.BUS_OUT
                            || n.type == com.example.create_schematic_compute.graph.NodeType.BUS_IN)
                            && n.signalName.equals(busName)) {
                            n.signalBands = bands != null ? new ArrayList<>(bands) : new ArrayList<>();
                            n.bandsDirty = true;
                        }
                    }
                }
                // 注册到全局表；空频段时通过引用计数取消注册
                if (bands != null && !bands.isEmpty()) {
                    SignalBus.registerBands(busName, bands);
                } else {
                    SignalBus.clearBus(busName);
                    // 取消注册此 BUS_OUT 的频道（通过引用计数清理，不影响其他同名 BUS_OUT）
                    for (var n : graph.nodes) {
                        if (n.type == com.example.create_schematic_compute.graph.NodeType.BUS_OUT
                            && n.signalName.equals(busName)) {
                            SignalBus.unregisterChannel(busName,
                                new com.example.create_schematic_compute.network.ChannelOwner(pos, n.id));
                        }
                    }
                }
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
