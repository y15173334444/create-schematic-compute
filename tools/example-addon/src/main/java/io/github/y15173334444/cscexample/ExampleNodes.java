package io.github.y15173334444.cscexample;

import io.github.y15173334444.create_schematic_compute.api.CustomNodeBehavior;
import io.github.y15173334444.create_schematic_compute.api.CustomNodeContext;
import io.github.y15173334444.create_schematic_compute.api.CustomNodeResult;
import io.github.y15173334444.create_schematic_compute.api.CustomNodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * 两个示例节点：{@code cscexample:signal}（提供方直连读自己的方块）与
 * {@code cscexample:heavy}（可恢复重节点，演示 sliceExpired 检查点契约）。
 * Two sample nodes: cscexample:signal (provider-direct block read) and
 * cscexample:heavy (a resumable heavy node demonstrating the sliceExpired contract).
 */
final class ExampleNodes {

    private ExampleNodes() {}

    // ── 节点一：提供方直连 ─────────────────────────────────────────────

    /**
     * schema：0 入 1 出，一个 TEXT 参数 {@code pos}（目标方块坐标 "x y z"，宿主维度内
     * 绝对坐标）。「参数即寻址」约定：本模组不提供通用绑定 UI，提供方在参数里携带
     * 寻址信息、求值回调里自行解析（更聪明的做法——机器名注册表——由提供方自建）。
     * The schema: 0 in / 1 out, one TEXT param "pos" (the target block's "x y z" absolute
     * in the host's dimension). Per the "params are addressing" convention this mod ships
     * no generic binding UI — the provider carries addressing in its params and resolves
     * it in the callback (a smarter pattern — a machine-name registry — is the provider's
     * to build).
     */
    static CustomNodeType signalSchema() {
        return new CustomNodeType("cscexample:signal", "Example: Signal Read",
            List.of(), List.of("out"),
            List.of(new CustomNodeType.CustomParam("pos", CustomNodeType.CustomParam.ParamKind.TEXT)));
    }

    /** 求值行为：level/hostPos 空检（降级环境为 null → 输出 0），解析坐标读
     *  {@link SignalProviderBlockEntity#getSignal()}，读不到（未加载/不是提供方）→ 0。
     *  The behavior: null-check level/hostPos (degraded environments → output 0), parse
     *  the coordinates, read the provider's signal; unreadable (unloaded / not a
     *  provider) → 0. */
    static CustomNodeBehavior signalBehavior() {
        return ctx -> {
            ServerLevel level = ctx.level();
            BlockPos providerPos = parsePos(ctx.textParam("pos"));
            float value = 0f;
            // 只读直连示例：这里也可以写回（同 tick 双向），保持只读以突出「读」。
            // A read-only direct example: writing back works too (same tick both ways);
            // read-only keeps the sample focused.
            if (level != null && providerPos != null) {
                var be = level.getBlockEntity(providerPos);
                if (be instanceof SignalProviderBlockEntity provider)
                    value = provider.getSignal();
            }
            return CustomNodeResult.done(value);
        };
    }

    /** 解析 "x y z"；任何畸形输入 → null（节点输出 0，不抛异常——fail-closed 的提供方侧
     *  对应物：输入卫生自己负责）。/ Parses "x y z"; any malformed input → null (the node
     *  outputs 0 instead of throwing — the provider-side twin of fail-closed: input
     *  hygiene is the provider's own duty). */
    private static BlockPos parsePos(String text) {
        if (text == null || text.isBlank()) return null;
        String[] parts = text.trim().split("[ ,]+");
        if (parts.length != 3) return null;
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── 节点二：可恢复重节点 ───────────────────────────────────────────

    /**
     * schema：0 入 1 出，一个 NUMBER 参数 {@code iterations}（伪工作总量；求值回调里
     * 每次推进一段，跨 tick 续算）。把 iterations 调大（如 500 万）即可在游戏里观察
     * {@code /cscperf} 下本节点的耗时与挂起轮转。
     * The schema: 0 in / 1 out, one NUMBER param "iterations" (the pseudo-work total;
     * the callback advances a chunk per call and resumes across ticks). Crank iterations
     * up (e.g. 5,000,000) to watch this node in /cscperf rotating through its slices.
     */
    static CustomNodeType heavySchema() {
        return new CustomNodeType("cscexample:heavy", "Example: Heavy (Resumable)",
            List.of(), List.of("out"),
            List.of(new CustomNodeType.CustomParam("iterations", CustomNodeType.CustomParam.ParamKind.NUMBER)));
    }

    /**
     * 可恢复求值：进度（已算次数 + 累加值）打包进检查点；时间片到期在循环边界返回
     * {@link CustomNodeResult#suspended}；总量算完返回
     * {@link CustomNodeResult#doneWithState}（保状态——把最终值留给下一 tick 展示用，
     * 也可以改用 {@link CustomNodeResult#done} 清检查点）。每 tick 至少推进
     * {@code CHUNK} 次，实际推进量受 slice 预算约束。
     * The resumable evaluation: progress (iterations done + the accumulator) packs into
     * the checkpoint; slice expiry returns suspended at the loop boundary; finishing
     * returns doneWithState (the state kept — the final value stays readable next tick;
     * switch to done() to clear the checkpoint instead). At least CHUNK iterations run
     * per call; the actual amount respects the slice budget.
     */
    static CustomNodeBehavior heavyBehavior() {
        return ctx -> {
            long total = Math.max(1, (long) ctx.numberParam("iterations"));
            CompoundTag state = ctx.state();
            long done = state.contains("done") ? state.getLong("done") : 0;
            double acc = state.contains("acc") ? state.getDouble("acc") : 0.0;
            if (done >= total) return CustomNodeResult.doneWithState(new float[]{(float) acc}, state);

            long chunk = Math.max(50_000L, total / 100L);
            long end = Math.min(total, done + chunk);
            for (long i = done; i < end; i++) {
                acc += Math.sin(i * 0.001) * Math.cos(i * 0.002);
                if (ctx.sliceExpired()) {
                    CompoundTag checkpoint = new CompoundTag();
                    checkpoint.putLong("done", i + 1);
                    checkpoint.putDouble("acc", acc);
                    return CustomNodeResult.suspended(checkpoint);
                }
            }
            CompoundTag next = new CompoundTag();
            next.putLong("done", end);
            next.putDouble("acc", acc);
            if (end >= total) return CustomNodeResult.doneWithState(new float[]{(float) acc}, next);
            return CustomNodeResult.suspended(next);
        };
    }
}
