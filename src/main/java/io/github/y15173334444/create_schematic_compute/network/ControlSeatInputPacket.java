package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.ControlSeatBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ControlSeatInputPacket(
    BlockPos pos,
    long keyBits,
    float mouseX, float mouseY,
    float viewYaw, float viewPitch,
    int inputMode,
    int mouseButtons,
    float gpadLX, float gpadLY, float gpadRX, float gpadRY,
    float gpadLT, float gpadRT,
    long gpadButtons,
    boolean dismount    // ~ 键请求下马
) implements CustomPacketPayload {

    public static final Type<ControlSeatInputPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "seat_input"));

    public static final StreamCodec<ByteBuf, ControlSeatInputPacket> CODEC = new StreamCodec<>() {
        @Override public ControlSeatInputPacket decode(ByteBuf buf) {
            return new ControlSeatInputPacket(
                BlockPos.STREAM_CODEC.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf),
                buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(),
                buf.readInt(),
                buf.readInt(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(),
                buf.readLong(),
                buf.readBoolean()
            );
        }
        @Override public void encode(ByteBuf buf, ControlSeatInputPacket p) {
            BlockPos.STREAM_CODEC.encode(buf, p.pos);
            ByteBufCodecs.VAR_LONG.encode(buf, p.keyBits);
            buf.writeFloat(p.mouseX); buf.writeFloat(p.mouseY);
            buf.writeFloat(p.viewYaw); buf.writeFloat(p.viewPitch);
            buf.writeInt(p.inputMode);
            buf.writeInt(p.mouseButtons);
            buf.writeFloat(p.gpadLX); buf.writeFloat(p.gpadLY);
            buf.writeFloat(p.gpadRX); buf.writeFloat(p.gpadRY);
            buf.writeFloat(p.gpadLT); buf.writeFloat(p.gpadRT);
            buf.writeLong(p.gpadButtons);
            buf.writeBoolean(p.dismount);
        }
    };

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // 安全校验：距离检查（ControlSeat 输入来自骑乘玩家，不做编辑会话检查）
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp) {
                if (!io.github.y15173334444.create_schematic_compute.network.SablePacketHelper.isWithinReachableRange(sp, pos, 16384.0)) return;
            }
            // ~ 键下马
            if (dismount) {
                ctx.player().stopRiding();
            }
            var found = ctx.player().level().getBlockEntity(pos);
            if (found instanceof ControlSeatBlockEntity be) {
                be.keyBits = keyBits; be.mouseJoystickX = mouseX; be.mouseJoystickY = mouseY;
                be.viewYaw = viewYaw; be.viewPitch = viewPitch; be.inputMode = inputMode;
                be.mouseButtons = mouseButtons;
                be.gpadLX = gpadLX; be.gpadLY = gpadLY; be.gpadRX = gpadRX; be.gpadRY = gpadRY;
                be.gpadLT = gpadLT; be.gpadRT = gpadRT;
                be.gpadButtons = gpadButtons;
            } else {
                ControlSeatBlockEntity.storeInput(ctx.player().getUUID(), keyBits, mouseX, mouseY,
                    viewYaw, viewPitch, inputMode, mouseButtons,
                    gpadLX, gpadLY, gpadRX, gpadRY, gpadLT, gpadRT, gpadButtons);
            }
        });
    }
}
