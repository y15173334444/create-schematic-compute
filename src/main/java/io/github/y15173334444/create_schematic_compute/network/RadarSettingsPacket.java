package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.RadarBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RadarSettingsPacket(BlockPos pos, int scanRange, int scanMode, int displayScale,
                                  boolean showPlayers, boolean showMobs, boolean showSable,
                                  int lockMode, float displayX, float displayY, float displayZ,
                                  boolean excludeHost, int displayStyle, float lockDistance)
        implements CustomPacketPayload {

    public static final Type<RadarSettingsPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "radar_settings"));

    public static final StreamCodec<ByteBuf, RadarSettingsPacket> CODEC = new StreamCodec<>() {
        @Override public RadarSettingsPacket decode(ByteBuf buf) {
            return new RadarSettingsPacket(BlockPos.STREAM_CODEC.decode(buf), buf.readInt(), buf.readInt(), buf.readInt(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readBoolean(), buf.readInt(), buf.readFloat());
        }
        @Override public void encode(ByteBuf buf, RadarSettingsPacket p) {
            BlockPos.STREAM_CODEC.encode(buf, p.pos);
            buf.writeInt(p.scanRange); buf.writeInt(p.scanMode); buf.writeInt(p.displayScale);
            buf.writeBoolean(p.showPlayers); buf.writeBoolean(p.showMobs); buf.writeBoolean(p.showSable);
            buf.writeInt(p.lockMode);
            buf.writeFloat(p.displayX); buf.writeFloat(p.displayY); buf.writeFloat(p.displayZ);
            buf.writeBoolean(p.excludeHost);
            buf.writeInt(p.displayStyle);
            buf.writeFloat(p.lockDistance);
        }
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // 安全校验：距离检查 + 编辑会话成员检查
            if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            if (!(sp.level() instanceof net.minecraft.server.level.ServerLevel sl)) return;
            double dx = sp.getX() - pos.getX();
            double dz = sp.getZ() - pos.getZ();
            if (dx * dx + dz * dz > 16384.0) return;
            if (!io.github.y15173334444.create_schematic_compute.blocks.EditSessionRegistry.getEditors(sl, pos).contains(sp.getUUID()))
                return;
            if (ctx.player().level().getBlockEntity(pos) instanceof RadarBlockEntity be) {
                be.scanRange = Math.max(1, Math.min(128, scanRange));
                be.scanMode = scanMode;
                be.displayScale = Math.max(1, Math.min(32, displayScale));
                be.showPlayers = showPlayers;
                be.showMobs = showMobs;
                be.showSable = showSable;
                be.lockMode = lockMode;
                be.displayX = displayX; be.displayY = displayY; be.displayZ = displayZ;
                be.excludeHost = excludeHost;
                be.displayStyle = Math.max(0, Math.min(1, displayStyle));
                be.lockMode = Math.max(0, Math.min(1, lockMode));
                be.lockDistance = Math.max(0f, lockDistance);
                be.setChanged();
            }
        });
    }
}
