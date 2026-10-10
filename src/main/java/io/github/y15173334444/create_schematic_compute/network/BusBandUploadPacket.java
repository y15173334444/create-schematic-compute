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

/** Client→Server: upload BUS band changes (does not trigger compilation) / 客户端→服务端：上传 BUS 频段变更（不触发编译）。
 *  <p>携带来源节点 id：频段是节点的**引脚结构**（结构归节点，冲突者也照常落盘），而频道**定义**
 *  归 owner —— 服务端按两层语义分开处理（{@link BusChannelHelper#applyBandUpload}）。</p>
 *  <p>Carries the source node id: bands are the node's <b>pin structure</b> (structure belongs to
 *  the node — even a conflicted one writes it), while the channel <b>definition</b> belongs to the
 *  owner; the server handles the two layers separately.</p> */
public record BusBandUploadPacket(BlockPos pos, String busName, List<String> bands, int nodeId) implements CustomPacketPayload {

    public static final Type<BusBandUploadPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "bus_band_upload"));

    public static final StreamCodec<ByteBuf, BusBandUploadPacket> CODEC = new StreamCodec<>() {
        @Override public BusBandUploadPacket decode(ByteBuf buf) {
            var b = new FriendlyByteBuf(buf);
            return new BusBandUploadPacket(b.readBlockPos(), b.readUtf(),
                b.readList(FriendlyByteBuf::readUtf), b.readVarInt());
        }
        @Override public void encode(ByteBuf buf, BusBandUploadPacket pkt) {
            var b = new FriendlyByteBuf(buf);
            b.writeBlockPos(pkt.pos);
            b.writeUtf(pkt.busName);
            b.writeCollection(pkt.bands != null ? pkt.bands : List.of(), FriendlyByteBuf::writeUtf);
            b.writeVarInt(pkt.nodeId);
        }
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // 安全校验：距离检查 + 编辑会话成员检查
            if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            if (!(sp.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;
            if (!io.github.y15173334444.create_schematic_compute.network.SablePacketHelper.isWithinReachableRange(sp, pos, 16384.0)) return;
            if (!io.github.y15173334444.create_schematic_compute.blocks.EditSessionRegistry.getEditors(serverLevel, pos).contains(sp.getUUID()))
                return;
            var be = ctx.player().level().getBlockEntity(pos);
            if (be instanceof GraphBlockEntity gbe) {
                var graph = gbe.getNodeGraph();
                if (graph != null) {
                    // 结构/定义两层语义在 applyBandUpload / structure vs definition split there
                    io.github.y15173334444.create_schematic_compute.network.BusChannelHelper
                        .applyBandUpload(graph, pos, nodeId, busName, bands, serverLevel);
                }
            }
        });
    }
}
