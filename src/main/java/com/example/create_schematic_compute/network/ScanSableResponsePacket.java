package com.example.create_schematic_compute.network;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.client.PortableTerminalScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Server→Client: results of a Sable sub-level device scan. */
public record ScanSableResponsePacket(List<SablePacketHelper.SableDeviceEntry> devices)
        implements CustomPacketPayload {
    public static final Type<ScanSableResponsePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "scan_sable_resp"));

    public static final StreamCodec<ByteBuf, ScanSableResponsePacket> CODEC =
        StreamCodec.composite(entryListCodec(), ScanSableResponsePacket::devices, ScanSableResponsePacket::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> PortableTerminalScreen.onSableScanResult(devices));
    }

    // ── Custom list codec ──

    private static StreamCodec<ByteBuf, List<SablePacketHelper.SableDeviceEntry>> entryListCodec() {
        return new StreamCodec<>() {
            @Override
            public void encode(ByteBuf buf, List<SablePacketHelper.SableDeviceEntry> list) {
                ByteBufCodecs.VAR_INT.encode(buf, list.size());
                for (var e : list) {
                    BlockPos.STREAM_CODEC.encode(buf, e.localPos());
                    ByteBufCodecs.STRING_UTF8.encode(buf, e.name());
                    ByteBufCodecs.STRING_UTF8.encode(buf, e.beClassName());
                    buf.writeFloat(e.distance());
                    buf.writeLong(e.subLevelId());
                }
            }

            @Override
            public List<SablePacketHelper.SableDeviceEntry> decode(ByteBuf buf) {
                int count = ByteBufCodecs.VAR_INT.decode(buf);
                List<SablePacketHelper.SableDeviceEntry> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
                    String name = ByteBufCodecs.STRING_UTF8.decode(buf);
                    String cls = ByteBufCodecs.STRING_UTF8.decode(buf);
                    float dist = buf.readFloat();
                    long sid = buf.readLong();
                    list.add(new SablePacketHelper.SableDeviceEntry(pos, name, cls, dist, sid));
                }
                return list;
            }
        };
    }
}
