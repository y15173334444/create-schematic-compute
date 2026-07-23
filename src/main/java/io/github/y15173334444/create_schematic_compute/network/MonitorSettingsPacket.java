package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.MonitorBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MonitorSettingsPacket(BlockPos pos,
    float screenWidth, float screenLength,
    float screenX, float screenY, float screenZ,
    float screenRoll, float screenPitch, float screenYaw) implements CustomPacketPayload {

    public static final Type<MonitorSettingsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "monitor_settings"));

    public static final StreamCodec<ByteBuf, MonitorSettingsPacket> CODEC = new StreamCodec<>() {
        @Override public MonitorSettingsPacket decode(ByteBuf buf) {
            return new MonitorSettingsPacket(
                BlockPos.STREAM_CODEC.decode(buf),
                buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat()
            );
        }
        @Override public void encode(ByteBuf buf, MonitorSettingsPacket p) {
            BlockPos.STREAM_CODEC.encode(buf, p.pos);
            buf.writeFloat(p.screenWidth); buf.writeFloat(p.screenLength);
            buf.writeFloat(p.screenX); buf.writeFloat(p.screenY); buf.writeFloat(p.screenZ);
            buf.writeFloat(p.screenRoll); buf.writeFloat(p.screenPitch); buf.writeFloat(p.screenYaw);
        }
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // 安全校验：距离检查 + 编辑会话成员检查
            if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            if (!(sp.level() instanceof net.minecraft.server.level.ServerLevel sl)) return;
            if (!io.github.y15173334444.create_schematic_compute.network.SablePacketHelper.isWithinReachableRange(sp, pos, 16384.0)) return;
            if (!io.github.y15173334444.create_schematic_compute.blocks.EditSessionRegistry.getEditors(sl, pos).contains(sp.getUUID()))
                return;
            if (ctx.player().level().getBlockEntity(pos) instanceof MonitorBlockEntity mbe) {
                mbe.applySettings(screenWidth, screenLength, screenX, screenY, screenZ,
                    screenRoll, screenPitch, screenYaw);
            }
        });
    }
}
