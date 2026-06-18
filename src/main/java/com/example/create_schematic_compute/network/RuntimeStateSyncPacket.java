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

import java.util.HashMap;
import java.util.Map;

/** 服务端→客户端：同步运行时 flipflopStates 用于编辑区当前状态实时显示 */
public record RuntimeStateSyncPacket(BlockPos pos, Map<Integer, Boolean> flipflopStates) implements CustomPacketPayload {
    public static final Type<RuntimeStateSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "runtime_state_sync"));

    public static final StreamCodec<ByteBuf, RuntimeStateSyncPacket> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, RuntimeStateSyncPacket::pos,
        ByteBufCodecs.map(HashMap::new, ByteBufCodecs.VAR_INT, ByteBufCodecs.BOOL),
            RuntimeStateSyncPacket::flipflopStates,
        RuntimeStateSyncPacket::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var be = ctx.player().level().getBlockEntity(pos);
            if (be instanceof GraphBlockEntity gbe && flipflopStates != null) {
                gbe.syncFlipflopStates(flipflopStates);
            }
        });
    }
}
