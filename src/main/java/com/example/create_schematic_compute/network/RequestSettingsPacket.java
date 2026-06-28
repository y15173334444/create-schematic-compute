package com.example.create_schematic_compute.network;

import com.example.create_schematic_compute.SchematicCompute;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** C→S: request full BE settings NBT for remote editing. */
public record RequestSettingsPacket(BlockPos targetPos, boolean isSable) implements CustomPacketPayload {
    public static final Type<RequestSettingsPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "req_settings"));
    public static final StreamCodec<ByteBuf, RequestSettingsPacket> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, RequestSettingsPacket::targetPos,
        ByteBufCodecs.BOOL, RequestSettingsPacket::isSable,
        RequestSettingsPacket::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Level level = ctx.player().level();
            if (isSable) {
                Level sl = SablePacketHelper.findSubLevel(level, targetPos);
                if (sl != null) level = sl; else return;
            }
            BlockEntity be = level.getBlockEntity(targetPos);
            if (be == null) return;
            CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
            var baos = new ByteArrayOutputStream();
            try { NbtIo.writeCompressed(tag, baos); } catch (Exception e) { return; }
            PacketDistributor.sendToPlayer((ServerPlayer) ctx.player(),
                new ResponseSettingsPacket(targetPos, baos.toByteArray()));
        });
    }
}
