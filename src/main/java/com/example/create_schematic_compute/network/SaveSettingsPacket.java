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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.ByteArrayInputStream;

/** C→S: save block settings (writes NBT back to BE). */
public record SaveSettingsPacket(BlockPos targetPos, byte[] nbt, boolean isSable) implements CustomPacketPayload {
    public static final Type<SaveSettingsPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "save_settings"));
    public static final StreamCodec<ByteBuf, SaveSettingsPacket> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, SaveSettingsPacket::targetPos,
        ByteBufCodecs.BYTE_ARRAY, SaveSettingsPacket::nbt,
        ByteBufCodecs.BOOL, SaveSettingsPacket::isSable,
        SaveSettingsPacket::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (nbt.length > 256 * 1024) return;
            Level level = ctx.player().level();
            if (isSable) {
                Level sl = SablePacketHelper.findSubLevel(level, targetPos);
                if (sl != null) level = sl; else return;
            }
            BlockEntity be = level.getBlockEntity(targetPos);
            if (be == null) return;
            try {
                CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(nbt), NbtAccounter.create(256 * 1024));
                be.loadWithComponents(tag, level.registryAccess());
                be.setChanged();
                level.sendBlockUpdated(targetPos, be.getBlockState(), be.getBlockState(), 3);
            } catch (Exception e) {
                SchematicCompute.LOGGER.warn("SaveSettings failed: {}", e.toString());
            }
        });
    }
}
