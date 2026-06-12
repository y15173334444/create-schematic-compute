package com.example.create_schematic_compute.network;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.blocks.BlueprintBlockEntity;
import com.example.create_schematic_compute.blocks.ControlSeatBlockEntity;
import com.example.create_schematic_compute.blocks.ProgramComputerBlockEntity;
import com.example.create_schematic_compute.blocks.SensorBlockEntity;
import com.example.create_schematic_compute.blocks.SpeedProxyBlockEntity;
import com.example.create_schematic_compute.blocks.MonitorBlockEntity;
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
            var be = ctx.player().level().getBlockEntity(pos);
            if (be instanceof BlueprintBlockEntity bbe) {
                bbe.loadGraphFromBytes(nbtData);
                if (bbe.graph.hasCycles() && bbe.running) {
                    bbe.running = false;
                    ctx.player().sendSystemMessage(
                            net.minecraft.network.chat.Component.literal("§e⚠ Cycle detected! Blueprint stopped. Remove the loop and try again."));
                }
            } else if (be instanceof SpeedProxyBlockEntity spe) {
                spe.loadGraphFromBytes(nbtData);
            } else if (be instanceof ProgramComputerBlockEntity pbe) {
                pbe.loadGraphFromBytes(nbtData);
            } else if (be instanceof ControlSeatBlockEntity cbe) {
                cbe.loadGraphFromBytes(nbtData);
            } else if (be instanceof SensorBlockEntity sbe) {
                sbe.loadGraphFromBytes(nbtData);
            } else if (be instanceof MonitorBlockEntity mbe) {
                mbe.loadGraphFromBytes(nbtData);
            }
        });
    }
}
