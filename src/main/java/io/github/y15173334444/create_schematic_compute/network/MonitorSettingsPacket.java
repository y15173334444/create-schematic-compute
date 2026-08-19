package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.MonitorBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 显示器设置包（C2S）：3D 屏幕 8 参数 + HUD 模式 6 参数（hudMode + 5 面板变换）。
 * HUD 字段按 docs/monitor-hud-mode-design.md §七 扩展自本包，不新建包类。
 * Monitor settings packet (C2S): 8 screen params + HUD mode 6 params
 * (hudMode + 5 panel transforms). Extended per the HUD design doc §七 — no new packet class.
 */
public record MonitorSettingsPacket(BlockPos pos,
    float screenWidth, float screenLength,
    float screenX, float screenY, float screenZ,
    float screenRoll, float screenPitch, float screenYaw,
    boolean hudMode,
    float panelSizeX, float panelSizeY, float panelOffsetX, float panelOffsetY, float panelDistance)
    implements CustomPacketPayload {

    public static final Type<MonitorSettingsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "monitor_settings"));

    public static final StreamCodec<ByteBuf, MonitorSettingsPacket> CODEC = new StreamCodec<>() {
        @Override public MonitorSettingsPacket decode(ByteBuf buf) {
            return new MonitorSettingsPacket(
                BlockPos.STREAM_CODEC.decode(buf),
                buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readBoolean(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()
            );
        }
        @Override public void encode(ByteBuf buf, MonitorSettingsPacket p) {
            BlockPos.STREAM_CODEC.encode(buf, p.pos);
            buf.writeFloat(p.screenWidth); buf.writeFloat(p.screenLength);
            buf.writeFloat(p.screenX); buf.writeFloat(p.screenY); buf.writeFloat(p.screenZ);
            buf.writeFloat(p.screenRoll); buf.writeFloat(p.screenPitch); buf.writeFloat(p.screenYaw);
            buf.writeBoolean(p.hudMode);
            buf.writeFloat(p.panelSizeX); buf.writeFloat(p.panelSizeY);
            buf.writeFloat(p.panelOffsetX); buf.writeFloat(p.panelOffsetY); buf.writeFloat(p.panelDistance);
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
                    screenRoll, screenPitch, screenYaw,
                    hudMode, panelSizeX, panelSizeY, panelOffsetX, panelOffsetY, panelDistance);
            }
        });
    }
}
