package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.Config;
import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C2S：设置面板「公式预算」选项卡的调整请求（K6）。服务端权威：OP 校验
 * （{@code hasPermissions(2)}，与拍板原话「调整均需 OP」一致）→ 钳制到配置区间
 * → 写 SERVER 型配置（作用域 = 当前世界的 serverconfig，NeoForge 自动存盘）→
 * 广播权威值；被拒者回「现值 + 无权限」校正面板。
 * C2S: the settings panel's formula-budget adjustment request (K6). Server-authoritative:
 * OP check ({@code hasPermissions(2)}, matching the ruling "adjustments require OP") →
 * clamp into the config range → write the SERVER-type config (scoped to the current
 * world's serverconfig, saved by NeoForge automatically) → broadcast the authoritative
 * value; a denied request gets "current value + no permission" back.
 */
public record BudgetSetPacket(double ms) implements CustomPacketPayload {

    public static final Type<BudgetSetPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "budget_set"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BudgetSetPacket> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.DOUBLE, BudgetSetPacket::ms,
            BudgetSetPacket::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(BudgetSetPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            // 信任边界:面板只是输入面,权限以服务端裁定为准(改包客户端无门可钻)。
            // Trust boundary: the panel is an input surface only; the server decides permission
            // (a modified client has no way through).
            if (!sp.hasPermissions(2)) {
                PacketDistributor.sendToPlayer(sp,
                    new BudgetSyncPacket(Config.formulaBudgetMsSafe(), false));
                sp.sendSystemMessage(Component.translatable(
                    "commands.create_schematic_compute.cscbudget.denied"));
                return;
            }
            double clamped = Math.round(net.minecraft.util.Mth.clamp(pkt.ms, 0.5, 20.0) * 10.0) / 10.0;
            Config.FORMULA_BUDGET_MS.set(clamped);
            // 持久化:ConfigValue.set() 只改内存(NeoForge 21.1 不落盘),显式 save() 写当前
            // 世界的 serverconfig——重启后仍生效(实测发现:不 save 则值随会话丢失)。
            // Persistence: ConfigValue.set() only mutates memory (it does not save on NeoForge
            // 21.1); the explicit save() writes this world's serverconfig — the value survives
            // a restart (verified empirically: without it the value was lost with the session).
            Config.SPEC.save();
            PacketDistributor.sendToAllPlayers(new BudgetSyncPacket(clamped, true));
            sp.sendSystemMessage(Component.translatable(
                "commands.create_schematic_compute.cscbudget.set", String.format("%.1f", clamped)));
        });
    }
}
