package io.github.y15173334444.create_schematic_compute;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import io.github.y15173334444.create_schematic_compute.network.BudgetSetPacket;
import io.github.y15173334444.create_schematic_compute.network.BudgetSyncPacket;
import io.github.y15173334444.create_schematic_compute.network.OpenSettingsPacket;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 模组指令（K6 交互面，本模组首批指令）。两条均需 OP（拍板原话「两条指令…均需 OP」）：
 * <ul>
 *   <li>{@code /cscsettings} —— 打开设置面板（S2C 空载荷通知客户端，直达「公式预算」tab）；</li>
 *   <li>{@code /cscbudget &lt;ms&gt;} —— 直接调整公式预算（钳 0.5–20，写当前世界的
 *       SERVER 配置 = 世界作用域，广播权威值同步）。</li>
 * </ul>
 * The mod's commands (the K6 interaction surface; the mod's first commands). Both require
 * OP (the ruling's "both commands require OP"):
 * <ul>
 *   <li>{@code /cscsettings} — opens the settings screen (an S2C empty payload; lands on
 *       the formula-budget tab);</li>
 *   <li>{@code /cscbudget &lt;ms&gt;} — adjusts the formula budget directly (clamped to
 *       0.5–20, written to the current world's SERVER config = world-scoped, with the
 *       authoritative value broadcast).</li>
 * </ul>
 */
public final class SchematicComputeCommands {

    private SchematicComputeCommands() {}

    /** 注册两条指令（游戏总线 RegisterCommandsEvent 回调）。 / register both commands (the game-bus RegisterCommandsEvent callback). */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cscsettings")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> {
                ServerPlayer sp = ctx.getSource().getPlayerOrException();
                PacketDistributor.sendToPlayer(sp, OpenSettingsPacket.INSTANCE);
                return 1;
            }));
        dispatcher.register(Commands.literal("cscbudget")
            .requires(src -> src.hasPermission(2))
            .then(Commands.argument("ms", DoubleArgumentType.doubleArg(0.5, 20.0))
                .executes(ctx -> {
                    ServerPlayer sp = ctx.getSource().getPlayerOrException();
                    double ms = Mth.clamp(DoubleArgumentType.getDouble(ctx, "ms"), 0.5, 20.0);
                    // 世界作用域:SERVER 型配置写当前世界 serverconfig,NeoForge 自动存盘;
                    // 预算源每 tick 实时读取,下一 tick 即生效。
                    // World-scoped: the SERVER config writes the current world's serverconfig
                    // (saved automatically); the budget source reads live, effective next tick.
                    Config.FORMULA_BUDGET_MS.set(ms);
                    PacketDistributor.sendToAllPlayers(new BudgetSyncPacket(ms, true));
                    double v = ms;
                    ctx.getSource().sendSuccess(() -> Component.translatable(
                        "commands.create_schematic_compute.cscbudget.set", String.format("%.1f", v)), true);
                    return 1;
                })));
    }
}
