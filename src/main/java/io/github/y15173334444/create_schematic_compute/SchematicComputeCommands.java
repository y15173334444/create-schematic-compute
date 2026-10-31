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
        dispatcher.register(Commands.literal("cscperf")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> {
                ServerPlayer sp = ctx.getSource().getPlayerOrException();
                // 视线命中的宿主方块（6 格）→ 图 BE → 求值器耗时刻度表。
                // The host block under the crosshair (6 blocks) → graph BE → the
                // evaluator's cost table.
                var hit = sp.pick(6.0, 1.0f, false);
                if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    ctx.getSource().sendFailure(Component.translatable(
                        "commands.create_schematic_compute.cscperf.no_target"));
                    return 0;
                }
                var pos = net.minecraft.core.BlockPos.containing(hit.getLocation());
                if (!(sp.level().getBlockEntity(pos) instanceof io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity gbe)
                    || gbe.evaluatorView() == null || gbe.getNodeGraph() == null) {
                    ctx.getSource().sendFailure(Component.translatable(
                        "commands.create_schematic_compute.cscperf.no_target"));
                    return 0;
                }
                var lines = formatPerfSummary(gbe.evaluatorView(), gbe.getNodeGraph(), 20);
                if (lines.isEmpty()) {
                    ctx.getSource().sendSuccess(() -> Component.translatable(
                        "commands.create_schematic_compute.cscperf.empty"), false);
                    return 1;
                }
                ctx.getSource().sendSuccess(() -> Component.translatable(
                    "commands.create_schematic_compute.cscperf.header", pos.toShortString()), false);
                for (String line : lines)
                    ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                return 1;
            }));
    }

    /** /cscperf 汇总格式化（纯函数，headless 可锚）：per-node EMA 降序 top-N，行式
     *  「#i  node <id> (<type|customId>)  <µs> µs avg」。CUSTOM 节点带 modid+节点 id
     *  （验收②的可观测底座：第三方耗时一眼可辨）。
     *  The /cscperf summary formatter (a pure function, anchorable headless): per-node
     *  EMA descending top-N, lines like "#1 node 12 (cscexample:widget) 123.4 µs avg".
     *  CUSTOM nodes carry modid + node id (the observability base of acceptance 2). */
    public static java.util.List<String> formatPerfSummary(
            io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator ev,
            io.github.y15173334444.create_schematic_compute.graph.NodeGraph graph, int topN) {
        var costs = ev.nodeCostNsView();
        var sorted = new java.util.ArrayList<>(costs.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        var out = new java.util.ArrayList<String>();
        int rank = 0;
        for (var e : sorted) {
            if (rank >= topN) break;
            var node = graph.findNode(e.getKey());
            String label = node == null ? "?"
                : (node.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.CUSTOM
                    ? node.customId : node.type.id);
            out.add(String.format("#%d  node %d (%s)  %.1f µs avg", rank + 1, e.getKey(),
                label, e.getValue() / 1000.0));
            rank++;
        }
        return out;
    }
}
