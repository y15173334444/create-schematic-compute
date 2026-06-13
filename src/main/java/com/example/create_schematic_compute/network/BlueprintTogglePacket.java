package com.example.create_schematic_compute.network;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.blocks.GraphBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record BlueprintTogglePacket(BlockPos pos, boolean start) implements CustomPacketPayload {
    public static final Type<BlueprintTogglePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "blueprint_tog"));
    public static final StreamCodec<ByteBuf, BlueprintTogglePacket> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BlueprintTogglePacket::pos,
            ByteBufCodecs.BOOL, BlueprintTogglePacket::start,
            BlueprintTogglePacket::new);
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player().level().getBlockEntity(pos) instanceof GraphBlockEntity gbe) {
                if (start && gbe.graphHasCycles()) {
                    ctx.player().sendSystemMessage(
                            net.minecraft.network.chat.Component.literal("§c⚠ Cycle detected! Cannot start: the graph contains a feedback loop."));
                    return;
                }
                if (!start) gbe.clearPidState();
                gbe.setRunning(start);
            }
        });
    }
}
