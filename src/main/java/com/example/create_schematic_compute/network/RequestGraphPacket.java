package com.example.create_schematic_compute.network;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.blocks.GraphBlockEntity;
import com.example.create_schematic_compute.graph.NodeGraph;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public record RequestGraphPacket(BlockPos targetPos, boolean isSable) implements CustomPacketPayload {
    public static final Type<RequestGraphPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "request_graph"));
    public static final StreamCodec<ByteBuf, RequestGraphPacket> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, RequestGraphPacket::targetPos,
        ByteBufCodecs.BOOL, RequestGraphPacket::isSable,
        RequestGraphPacket::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Level level = ctx.player().level();
            if (isSable) {
                Level sl = SablePacketHelper.findSubLevel(level, targetPos);
                if (sl != null) level = sl;
                else return;
            }
            var be = level.getBlockEntity(targetPos);
            if (be instanceof GraphBlockEntity) {
                NodeGraph graph = null;
                try { graph = (NodeGraph) be.getClass().getField("graph").get(be); }
                catch (Exception e) { return; }
                if (graph == null) return;
                var tag = graph.save(level.registryAccess());
                var baos = new ByteArrayOutputStream();
                try { net.minecraft.nbt.NbtIo.writeCompressed(tag, baos); } catch (IOException e) { return; }
                int version = graph.graphGeneration;
                PacketDistributor.sendToPlayer((net.minecraft.server.level.ServerPlayer) ctx.player(),
                    new ResponseGraphPacket(targetPos, baos.toByteArray(), version));
            }
        });
    }
}
