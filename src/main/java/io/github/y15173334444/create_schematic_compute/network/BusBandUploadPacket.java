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

    /** 频段数上限（P2-8）：与 op 路径 bandCount 的 64 钳制同口径（GraphEditOpPacket decode），
     *  64 已远超正常用量；超限 = 协议错误而非静默截断 —— 本路径直入 reconcileBands 并随图 NBT
     *  持久化，无界列表会把数万条频段名写进权威图落盘（存档膨胀）。
     *  Band-count ceiling (P2-8): the same 64 discipline as the op path's bandCount clamp
     *  (GraphEditOpPacket decode); 64 far exceeds normal use. Over-limit is a protocol error,
     *  never a silent truncation — this path feeds reconcileBands and persists with the graph
     *  NBT, so an unbounded list lets a session member write tens of thousands of band names
     *  into the authoritative graph (save bloat). */
    public static final int MAX_BANDS = 64;

    public static final StreamCodec<ByteBuf, BusBandUploadPacket> CODEC = new StreamCodec<>() {
        @Override public BusBandUploadPacket decode(ByteBuf buf) {
            var b = new FriendlyByteBuf(buf);
            var pos = b.readBlockPos();
            var busName = b.readUtf();
            // P2-8 安全钳制：先校验后读取（对齐 W1A 的"超限=协议错误"解码器口径 —— 不静默
            // 截断、不按声明值分配）。负数与超 64 的声明一律 DecoderException（NeoForge 断开
            // 该连接）。
            // P2-8 safety clamp: validate before reading (the W1A "over-limit = protocol error"
            // decoder discipline — never silently truncated, never allocating at the declared
            // value). A negative or over-64 declared count throws DecoderException (NeoForge
            // drops the connection).
            int bandCount = b.readVarInt();
            if (bandCount < 0 || bandCount > MAX_BANDS)
                throw new io.netty.handler.codec.DecoderException(
                    "BusBandUploadPacket: band count out of bounds: " + bandCount
                        + " (limit " + MAX_BANDS + ")");
            var bands = new ArrayList<String>(bandCount);
            for (int i = 0; i < bandCount; i++) bands.add(b.readUtf());
            return new BusBandUploadPacket(pos, busName, bands, b.readVarInt());
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
            if (!io.github.y15173334444.create_schematic_compute.network.EditSessionRegistry.getEditors(serverLevel, pos).contains(sp.getUUID()))
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
