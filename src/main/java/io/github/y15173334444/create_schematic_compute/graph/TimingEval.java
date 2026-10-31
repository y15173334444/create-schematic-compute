package io.github.y15173334444.create_schematic_compute.graph;

import java.util.ArrayDeque;
import java.util.Map;

/**
 * 时序域处理器（决策文档 §四）：SEQUENTIAL_ACC / SEQUENTIAL_STATE。
 * {@code eval()} 档只实现 ACCUMULATOR / INTEGRATOR（状态量走 pidState，路径无关）；
 * {@code evalState()} 承接七类（DELAY/LATCH/GATE/T_FLIPFLOP/PULSE_EXTEND/LOOP/FUSE）
 * ——档位合一（2026-10-31 拍板）后七类一律经 evalExt 入口落这里，4 参档静默空转怪癖
 * 已熄灭（§三-4，登记册条目『时序/逻辑节点在 SeatInput 档宿主静默空转』）。
 * case 体从 {@link GraphEvaluator} 逐字搬迁（§三-2）；本类是静态函数包，不建继承体系（§三-3）。
 * <p>Timing-domain handler (decision doc §四): SEQUENTIAL_ACC / SEQUENTIAL_STATE.
 * {@code eval()} implements only ACCUMULATOR / INTEGRATOR (their state rides pidState,
 * path-independent); {@code evalState()} takes the seven types
 * (DELAY/LATCH/GATE/T_FLIPFLOP/PULSE_EXTEND/LOOP/FUSE) — after the tier unification
 * (2026-10-31 ruling) those seven always land here via the evalExt entry, and the
 * 4-arg silently-idling quirk is gone (§三-4, the open-issues register). Case bodies
 * are relocated verbatim from {@link GraphEvaluator} (§三-2); this class is a static
 * function pack, no inheritance hierarchy (§三-3).</p>
 */
public final class TimingEval {

    private TimingEval() {}

    /** 求值累加/积分节点（eval() 档；case 体逐字搬迁，见类注释）。
     *  Evaluate an accumulate / integrate node (the eval() tier; case bodies relocated
     *  verbatim, see the class doc).
     *
     * @param ctx 求值上下文（每调用/每节点槽已由调用方填好）/ evaluation context
     */
    static void eval(EvalContext ctx) {
        // 方法内局部别名（接收者改写）：case 体逐字保留 / local aliases (receiver rewrite):
        // the case bodies stay verbatim
        GraphNode node = ctx.node;
        float[] o = ctx.o;
        Map<Integer, Float> pidState = ctx.pidState;
        NodeGraph graph = ctx.graph;
        Map<Integer, float[]> outputs = ctx.outputs;
        switch (node.type) {
            case ACCUMULATOR -> {
                float inPlus = graph.getInputValue(node.id, 0, outputs);
                float inMinus = graph.getInputValue(node.id, 1, outputs);
                float step = node.params.length > 0 ? node.params[0] : 1f;
                int prevPKey = node.id + 100000, prevMKey = node.id + 200000;
                float cur = pidState.getOrDefault(node.id, 0f);
                float prevP = pidState.getOrDefault(prevPKey, 0f);
                float prevM = pidState.getOrDefault(prevMKey, 0f);
                if (inPlus > 0.5f && prevP <= 0.5f) cur += step;   // + 上升沿  /  + rising edge
                if (inMinus > 0.5f && prevM <= 0.5f) cur -= step;  // - 上升沿  /  - rising edge
                pidState.put(node.id, cur);
                pidState.put(prevPKey, inPlus);
                pidState.put(prevMKey, inMinus);
                o[0] = cur;
            }
            case INTEGRATOR -> {
                float inPlus = graph.getInputValue(node.id, 0, outputs);
                float inMinus = graph.getInputValue(node.id, 1, outputs);
                float inClear = graph.getInputValue(node.id, 2, outputs);
                float step = node.params.length > 0 ? node.params[0] : 1f;
                int interval = node.params.length > 1 ? Math.max(1, (int)node.params[1]) : 1;
                float limit = node.params.length > 2 ? node.params[2] : 1000f;
                int tickKey = node.id + 100000;
                float cur = pidState.getOrDefault(node.id, 0f);
                int tick = pidState.getOrDefault(tickKey, 0f).intValue();
                tick++;
                if (inClear > 0.5f) { cur = 0f; tick = 0; }
                else if (tick >= interval) {
                    tick = 0;
                    if (inPlus > 0.5f && inMinus <= 0.5f) cur += step;
                    else if (inMinus > 0.5f && inPlus <= 0.5f) cur -= step;
                    // 两者都激活 → 保持（不变）  /  both active → hold (no change)
                }
                cur = Math.max(0, Math.min(cur, limit));
                pidState.put(node.id, cur);
                pidState.put(tickKey, (float)tick);
                o[0] = cur;
            }
        }
        ctx.o = o;
    }

    /** 求值时序状态节点（evalExt 档七类，含 GATE；case 体逐字搬迁，见类注释）。
     *  方法开头放局部别名块（接收者改写），让 case 体一个字都不用改；结尾
     *  {@code ctx.o = o} 回写输出数组（本档七类不重建数组，回写为通用契约）。
     *  Evaluate a timing-state node (the seven evalExt-tier types, GATE included; case
     *  bodies relocated verbatim, see the class doc). The method opens with local aliases
     *  (receiver rewrite) so the case bodies stay untouched; it ends with a
     *  {@code ctx.o = o} write-back of the output array (these seven never rebuild it;
     *  the write-back is the general contract).
     *
     * @param ctx 求值上下文（每调用/每节点槽已由调用方填好，时序三表随行）/ evaluation
     *            context (per-call / per-node slots already filled by the caller, the
     *            three timing tables riding along)
     */
    static void evalState(EvalContext ctx) {
        // 方法内局部别名（接收者改写）：case 体逐字保留 / local aliases (receiver rewrite):
        // the case bodies stay verbatim
        GraphNode node = ctx.node;
        float[] o = ctx.o;
        NodeGraph graph = ctx.graph;
        Map<Integer, float[]> outputs = ctx.outputs;
        Map<Integer, ArrayDeque<Float>> delayQueues = ctx.delayQueues;
        Map<Integer, Boolean> flipflopStates = ctx.flipflopStates;
        Map<Integer, Integer> pulseTimers = ctx.pulseTimers;
        switch (node.type) {
            case DELAY -> {
                float in = graph.getInputValue(node.id, 0, outputs);
                var q = delayQueues.computeIfAbsent(node.id, k -> new java.util.ArrayDeque<>());
                // 输出队列头（(duration) tick 前的输入），再入队今日输入并裁剪。
                // Output the queue head (the input from `duration` ticks ago), then
                // enqueue today's input and trim to the (possibly wire-overridden) duration.
                o[0] = q.isEmpty() ? 0 : q.peekFirst();
                int ticks = Math.max(1, (int)(node.params.length>0?node.params[0]:10));
                q.addLast(in);
                while (q.size() > ticks) q.pollFirst();
            }
            // ── 布尔时序族（LATCH/GATE/T_FLIPFLOP）缺席播种契约 ──
            // 权威态在 flipflopStates（runtimeState 三表之一，NBT 随 runtime 键持久化、跨
            // 重编译 save/restore 保真）；映射缺席时（新建节点首拍 / 已删节点撤销恢复 /
            // 无 runtime 段的旧档）从 **params[1] 状态镜像** 播种——镜像随节点 NBT 走：
            // 撤销恢复的快照里 params[1] = 删除时刻状态（撤回语义保真），旧档 = 存档时
            // 状态（比按初始参数播种更保真），新建节点 = 0（关/复位起步）。
            // params[0]（旧「初始状态」参数）自 1.3.0 起退役：服务端权威架构下编译不再
            // 重置时序态，该参数无存活语义，槽位保留仅为存档形状不变，求值不再读取。
            // ── Boolean-sequential family (LATCH/GATE/T_FLIPFLOP) absent-state seed ──
            // The authoritative state lives in flipflopStates (one of runtimeState's three
            // tables, NBT-persisted under the runtime key and carried across recompiles by
            // save/restore). When the map lacks the id (a fresh node's first tick, the
            // undo-restore of a removed node, or an old save without a runtime section) the
            // seed is the **params[1] state mirror** — it rides the node's NBT, so an
            // undo-restore snapshot carries the state at removal (faithful undo), an old
            // save carries the state at save time (more faithful than the initial param),
            // and a fresh node carries 0 (starts off/reset). params[0], the old "initial
            // state" parameter, is retired as of 1.3.0: server authority means compiles no
            // longer reset sequential state, so it has no living semantics — the slot stays
            // only to keep the save shape unchanged and is never read.
            case LATCH -> {
                float s = graph.getInputValue(node.id, 0, outputs);
                float r = graph.getInputValue(node.id, 1, outputs);
                boolean cur = flipflopStates.getOrDefault(node.id,
                    node.params.length > 1 && node.params[1] > 0.5f);
                if (s > 0.5f) cur = true;
                else if (r > 0.5f) cur = false;
                flipflopStates.put(node.id, cur);
                if (node.params.length > 1) node.params[1] = cur ? 1 : 0;
                o[0] = cur ? 1 : 0;
            }
            case GATE -> {
                boolean cur = flipflopStates.getOrDefault(node.id,
                    node.params.length > 1 && node.params[1] > 0.5f);
                float val = graph.getInputValue(node.id, 0, outputs);
                float open = graph.getInputValue(node.id, 1, outputs);
                float close = graph.getInputValue(node.id, 2, outputs);
                float toggle = graph.getInputValue(node.id, 3, outputs);
                // 上升沿检测用于 toggle  /  Rising-edge detection for toggle
                boolean prevTog = flipflopStates.getOrDefault(-(node.id+1), toggle > 0.5f);
                boolean togEdge = toggle > 0.5f && !prevTog;
                flipflopStates.put(-(node.id+1), toggle > 0.5f);
                // Open 优先，其次 Close，最后 Toggle 边沿  /  Open has priority, then Close, then Toggle edge
                if (open > 0.5f) cur = true;
                else if (close > 0.5f) cur = false;
                else if (togEdge) cur = !cur;
                flipflopStates.put(node.id, cur);
                if (node.params.length > 1) node.params[1] = cur ? 1 : 0;
                o[0] = cur ? val : 0;
            }
            case T_FLIPFLOP -> {
                float in = graph.getInputValue(node.id, 0, outputs);
                boolean cur = flipflopStates.getOrDefault(node.id,
                    node.params.length > 1 && node.params[1] > 0.5f);
                boolean prev = flipflopStates.getOrDefault(-(node.id+1), in > 0.5f);
                if (in > 0.5f && !prev) cur = !cur;
                flipflopStates.put(node.id, cur);
                flipflopStates.put(-(node.id+1), in > 0.5f);
                if (node.params.length > 1) node.params[1] = cur ? 1 : 0;
                o[0] = cur ? 1 : 0;
            }
            case PULSE_EXTEND -> {
                float in = graph.getInputValue(node.id, 0, outputs);
                int timer = pulseTimers.getOrDefault(node.id, 0);
                if (in > 0.5f) timer = Math.max(1, (int)node.params[0]);
                else if (timer > 0) timer--;
                pulseTimers.put(node.id, timer);
                o[0] = timer > 0 ? 1 : 0;
            }
            case LOOP -> {
                float in = graph.getInputValue(node.id, 0, outputs);
                int count = Math.max(1, (int)(node.params.length>0?node.params[0]:5));
                int interval = Math.max(1, (int)(node.params.length>1?node.params[1]:10));
                int remaining = pulseTimers.getOrDefault(node.id, 0);
                int counter = pulseTimers.getOrDefault(-(node.id+1), 0);
                boolean prevTrigger = flipflopStates.getOrDefault(-(node.id+1), false);
                // 上升沿触发启动  /  Rising edge triggers start
                if (in > 0.5f && !prevTrigger && remaining == 0) {
                    remaining = count;
                    counter = interval;
                }
                flipflopStates.put(-(node.id+1), in > 0.5f);
                if (remaining > 0) {
                    counter--;
                    if (counter <= 0) {
                        o[0] = 1;
                        remaining--;
                        if (remaining > 0) counter = interval;
                    } else {
                        o[0] = 0;
                    }
                } else {
                    o[0] = 0;
                }
                pulseTimers.put(node.id, remaining);
                pulseTimers.put(-(node.id+1), counter);
            }
            case FUSE -> {
                float in = graph.getInputValue(node.id, 0, outputs);
                int cd = Math.max(1, (int)(node.params.length>0?node.params[0]:40));
                int timer = pulseTimers.getOrDefault(node.id, 0);
                boolean prev = flipflopStates.getOrDefault(-(node.id+1), false);
                boolean high = in > 0.5f;
                boolean triggered = high && !prev;   // 上升沿 / rising edge
                flipflopStates.put(-(node.id+1), high);
                if (timer > 0) {
                    // 脉冲/冷却阶段：先输出 2 tick 脉冲，其余为冷却
                    // Pulse/cooldown phase: 2-tick pulse first, then cooldown
                    int pulseOut = pulseTimers.getOrDefault(-(node.id+1), 0);
                    if (pulseOut < 2) {
                        o[0] = 1;                    // 2 tick 脉冲 / 2-tick pulse
                        pulseTimers.put(-(node.id+1), pulseOut + 1);
                    } else {
                        o[0] = 0;                    // 脉冲结束，在冷却中 / Pulse ended, in cooldown
                    }
                    timer--;
                    if (timer == 0) pulseTimers.remove(-(node.id+1));
                } else if (triggered || high) {
                    // 上升沿立即触发；持续高电平（长信号）冷却结束后自动再触发 → 脉冲发生器
                    // Rising edge fires immediately; a held-high input (long signal) re-fires
                    // after the cooldown expires — turning FUSE into a pulse generator.
                    timer = cd;                      // 冷却倒计时开始 / Cooldown countdown begins
                    pulseTimers.put(-(node.id+1), 1); // 首个脉冲 tick 已输出 / first pulse tick emitted
                    o[0] = 1;
                } else {
                    o[0] = 0;
                }
                pulseTimers.put(node.id, timer);
            }
        }
        ctx.o = o;
    }
}
