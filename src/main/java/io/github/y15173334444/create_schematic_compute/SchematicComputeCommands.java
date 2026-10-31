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
 * 模组指令（K6 交互面，本模组首批指令）：
 * <ul>
 *   <li>{@code /cscsettings} —— 打开设置面板（**全员可用**——开面板只是查看面，落在
 *       第一选项卡；调整动作在面板内/指令侧各自把关）；</li>
 *   <li>{@code /cscbudget &lt;ms&gt;} —— 直接调整公式预算（**OP**，钳 0.5–20，写当前世界的
 *       SERVER 配置并落盘，广播权威值同步）。</li>
 * </ul>
 * The mod's commands (the K6 interaction surface; the mod's first commands):
 * <ul>
 *   <li>{@code /cscsettings} — opens the settings screen (**everyone** — opening a panel is a
 *       viewing action, landing on the first tab; each adjust path gates itself);</li>
 *   <li>{@code /cscbudget &lt;ms&gt;} — adjusts the formula budget directly (**OP**, clamped
 *       to 0.5–20, written to this world's SERVER config and saved, with the authoritative
 *       value broadcast).</li>
 * </ul>
 */
public final class SchematicComputeCommands {

    private SchematicComputeCommands() {}

    /** 注册两条指令（游戏总线 RegisterCommandsEvent 回调）。 / register both commands (the game-bus RegisterCommandsEvent callback). */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cscsettings")
            .executes(ctx -> {
                ServerPlayer sp = ctx.getSource().getPlayerOrException();
                PacketDistributor.sendToPlayer(sp, OpenSettingsPacket.INSTANCE);
                return 1;
            }));
        dispatcher.register(Commands.literal("cscbudget")
            .requires(src -> src.hasPermission(2))
            .then(Commands.argument("ms", DoubleArgumentType.doubleArg(0.5, 20.0))
                .executes(ctx -> {
                    double ms = Mth.clamp(DoubleArgumentType.getDouble(ctx, "ms"), 0.5, 20.0);
                    // 世界作用域:SERVER 型配置写当前世界 serverconfig;set() 只改内存,
                    // 显式 save() 落盘(重启后仍生效);预算源每 tick 实时读取,下一 tick 生效。
                    // 控制台同样可调(审查修正:拍板只要求 OP,未要求限玩家——requires 已按权限等级把门)。
                    // World-scoped: the SERVER config writes this world's serverconfig; set()
                    // only mutates memory, so the explicit save() persists it across restarts;
                    // the budget source reads live, effective next tick. The console may adjust
                    // too (review fix: the ruling requires OP, not a player — requires() already
                    // gates by permission level).
                    Config.FORMULA_BUDGET_MS.set(ms);
                    Config.SPEC.save();
                    PacketDistributor.sendToAllPlayers(new BudgetSyncPacket(ms, true));
                    double v = ms;
                    ctx.getSource().sendSuccess(() -> Component.translatable(
                        "commands.create_schematic_compute.cscbudget.set", String.format("%.1f", v)), true);
                    return 1;
                })));
    }
}
