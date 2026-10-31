package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C：公式预算权威值同步（K6 交互面）。SERVER 型配置登录时由 NeoForge 自动同步，
 * 但运行期修改（指令/设置面板）不会重推——本包是运行期的权威刷新通道：调整成功广播
 * 全体玩家，被拒者单独收到「现值 + 无权限」以校正面板显示。
 * S2C: authoritative formula-budget sync (the K6 interaction surface). The SERVER config
 * syncs on login via NeoForge, but runtime changes (command / settings panel) are not
 * re-pushed — this packet is the runtime refresh channel: a successful adjustment
 * broadcasts to everyone; a denied request gets "current value + no permission" back so
 * the panel corrects itself.
 */
public record BudgetSyncPacket(double ms, boolean canAdjust) implements CustomPacketPayload {

    public static final Type<BudgetSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "budget_sync"));

    public static final StreamCodec<ByteBuf, BudgetSyncPacket> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.DOUBLE, BudgetSyncPacket::ms,
            ByteBufCodecs.BOOL, BudgetSyncPacket::canAdjust,
            BudgetSyncPacket::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** 网络线程直处理：纯 volatile 静态字段写（预算 tab 的显示态），主线程无关。
     *  Handled directly on the network thread: plain volatile static writes (the budget
     *  tab's display state), nothing main-thread. */
    public void handle(IPayloadContext ctx) {
        io.github.y15173334444.create_schematic_compute.client.BudgetClientState.onSync(ms, canAdjust);
    }
}
