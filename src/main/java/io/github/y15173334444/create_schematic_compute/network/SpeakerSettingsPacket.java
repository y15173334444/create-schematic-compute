package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.SpeakerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 音响设置（C→S）：频道名/增益/半径/静音。应用到 {@link SpeakerBlockEntity}。
 * <p>Speaker settings (client→server): channel name / gain / radius / mute.</p>
 */
public record SpeakerSettingsPacket(BlockPos pos, String band, String channel, float gain, int radius, boolean mute)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SpeakerSettingsPacket> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "speaker_settings"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpeakerSettingsPacket> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, SpeakerSettingsPacket::pos,
        ByteBufCodecs.STRING_UTF8, SpeakerSettingsPacket::band,
        ByteBufCodecs.STRING_UTF8, SpeakerSettingsPacket::channel,
        ByteBufCodecs.FLOAT, SpeakerSettingsPacket::gain,
        ByteBufCodecs.VAR_INT, SpeakerSettingsPacket::radius,
        ByteBufCodecs.BOOL, SpeakerSettingsPacket::mute,
        SpeakerSettingsPacket::new);

    @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel sl)) return;
            if (!SablePacketHelper.isWithinReachableRange(sp, pos, 16384.0)) return;
            if (sl.getBlockEntity(pos) instanceof SpeakerBlockEntity be) {
                be.applySettings(band, channel, gain, radius, mute);
            }
        });
    }
}
