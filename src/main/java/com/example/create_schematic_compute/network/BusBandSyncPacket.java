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

/** 服务端→客户端：同步 BUS 频段列表变化 */
public record BusBandSyncPacket(BlockPos pos, String busName, List<String> bands) implements CustomPacketPayload {

    public static final Type<BusBandSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "bus_band_sync"));

    public static final StreamCodec<ByteBuf, BusBandSyncPacket> CODEC = new StreamCodec<>() {
        @Override public BusBandSyncPacket decode(ByteBuf buf) {
            var b = new FriendlyByteBuf(buf);
            BlockPos p = b.readBlockPos();
            String name = b.readUtf();
            int count = b.readVarInt();
            var list = new ArrayList<String>();
            for (int i = 0; i < count; i++) list.add(b.readUtf());
            return new BusBandSyncPacket(p, name, list);
        }
        @Override public void encode(ByteBuf buf, BusBandSyncPacket pkt) {
            var b = new FriendlyByteBuf(buf);
            b.writeBlockPos(pkt.pos);
            b.writeUtf(pkt.busName);
            var bands = pkt.bands;
            b.writeVarInt(bands != null ? bands.size() : 0);
            if (bands != null) for (String s : bands) b.writeUtf(s);
        }
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var be = ctx.player().level().getBlockEntity(pos);
            if (bands != null && !bands.isEmpty()) {
                SignalBus.registerBands(busName, bands);
            } else {
                SignalBus.clearBus(busName);
            }
            if (be instanceof GraphBlockEntity gbe) {
                var emptyBands = java.util.Collections.<String>emptyList();
                gbe.syncBusBandsFromServer(busName,
                    bands != null && !bands.isEmpty() ? bands : emptyBands);
            }
        });
    }
}
