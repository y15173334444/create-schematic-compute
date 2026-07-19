package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C wrapper for {@link BlobDataPacket}. Needed because NeoForge doesn't allow
 * the same TYPE to be registered for both playToServer and playToClient.
 * <p>{@link BlobDataPacket} 的 S→C 包装器。必需，因为 NeoForge 不允许同一 TYPE 同时注册为 playToServer 和 playToClient。</p>
 */
public record BlobDataSyncPacket(BlobDataPacket inner) implements CustomPacketPayload {

    public static final Type<BlobDataSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "blob_data_sync"));

    public static final StreamCodec<ByteBuf, BlobDataSyncPacket> CODEC =
        BlobDataPacket.CODEC.map(BlobDataSyncPacket::new, BlobDataSyncPacket::inner);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
