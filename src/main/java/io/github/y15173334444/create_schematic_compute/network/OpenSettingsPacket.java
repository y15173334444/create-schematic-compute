package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：`/cscsettings` 指令的空载荷——服务端校验 OP 后通知客户端打开设置面板
 * （直达「公式预算」选项卡）。屏幕切换必须回主线程，故走 enqueueWork。
 * S2C: the empty payload behind `/cscsettings` — after the server's OP check the client
 * opens the settings screen (straight to the formula-budget tab). Screen switches must
 * happen on the main thread, hence enqueueWork.
 */
public record OpenSettingsPacket() implements CustomPacketPayload {

    public static final Type<OpenSettingsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "open_settings"));

    /** 空载荷:单例 unit 编解码。 / Empty payload: a singleton unit codec. */
    public static final OpenSettingsPacket INSTANCE = new OpenSettingsPacket();

    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, OpenSettingsPacket> CODEC =
        StreamCodec.unit(INSTANCE);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(io.github.y15173334444.create_schematic_compute.client.editor.EditorSettingsScreen
            ::openFromCommand);
    }
}
