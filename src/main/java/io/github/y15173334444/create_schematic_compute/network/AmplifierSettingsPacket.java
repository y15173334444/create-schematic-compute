package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.AmplifierComputerBlockEntity;
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
 * 功放电脑监听设置（C→S）：盖子频谱的监听目标。{@code nodeId} 为
 * {@code AmplifierComputerBlockEntity.AUTO_MONITOR}（-1 = 自动·图最上方）或指定的 MUSIC
 * 节点编号——合法性由 {@code applySettings} 校验，无效编号拒收。
 * <p>Amplifier computer monitor setting (client→server): the lid spectrum's monitored target.
 * {@code nodeId} is {@code AmplifierComputerBlockEntity.AUTO_MONITOR} (-1 = auto, the graph's
 * topmost MUSIC node) or a pinned MUSIC node id — {@code applySettings} validates it and
 * rejects invalid ids.</p>
 */
public record AmplifierSettingsPacket(BlockPos pos, int nodeId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AmplifierSettingsPacket> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "amplifier_settings"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AmplifierSettingsPacket> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, AmplifierSettingsPacket::pos,
        ByteBufCodecs.VAR_INT, AmplifierSettingsPacket::nodeId,
        AmplifierSettingsPacket::new);

    @Override public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel sl)) return;
            if (!SablePacketHelper.isWithinReachableRange(sp, pos, 16384.0)) return;
            if (sl.getBlockEntity(pos) instanceof AmplifierComputerBlockEntity be) {
                be.applySettings(nodeId);
            }
        });
    }
}
