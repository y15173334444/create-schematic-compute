package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record BlueprintSavePacket(BlockPos pos, byte[] nbtData) implements CustomPacketPayload {

    public static final Type<BlueprintSavePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "blueprint_save"));

    public static final StreamCodec<ByteBuf, BlueprintSavePacket> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BlueprintSavePacket::pos,
            ByteBufCodecs.BYTE_ARRAY, BlueprintSavePacket::nbtData,
            BlueprintSavePacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // 限制 NBT 数据大小防止 OOM
            if (nbtData.length > 256 * 1024) {
                SchematicCompute.LOGGER.warn("Rejected oversized blueprint data ({} bytes) from {}", nbtData.length, ctx.player().getName().getString());
                return;
            }
            // 安全校验：距离检查 + 编辑会话成员检查
            if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            if (!(sp.level() instanceof net.minecraft.server.level.ServerLevel sl)) return;
            if (!io.github.y15173334444.create_schematic_compute.network.SablePacketHelper.isWithinReachableRange(sp, pos, 16384.0)) return;
            if (!io.github.y15173334444.create_schematic_compute.blocks.EditSessionRegistry.getEditors(sl, pos).contains(sp.getUUID()))
                return;
            if (ctx.player().level().getBlockEntity(pos) instanceof GraphBlockEntity gbe) {
                gbe.loadGraphFromBytes(nbtData);
                if (gbe.graphHasCycles() && gbe.isRunning()) {
                    gbe.setRunning(false);
                    ctx.player().sendSystemMessage(
                            net.minecraft.network.chat.Component.literal("§e⚠ Cycle detected! Graph stopped. Remove the loop and try again."));
                }
            }
        });
    }
}
