package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.api.CustomNodeBehavior;
import io.github.y15173334444.create_schematic_compute.api.CustomNodeResult;
import io.github.y15173334444.create_schematic_compute.api.CustomNodeType;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 公开 API 波 1 批 B（求值与栅栏）的回归锚：值级分派（done/输入/参数）、可恢复挂起
 * 轮转（检查点经 RuntimeState.customStates + 独立池 yield）、fail-closed（降级/异常/
 * null 空转）、处置梯子（连续超份额 → 降频 → 预算调大解冻 → 熔断）、独立池与 FORMULA
 * 池账本隔离、状态表持久化纪律（save/load/putAllFrom/prune）。
 * Regression anchors for public-API wave 1 batch B (evaluation & fence): the value-level
 * dispatch (done/inputs/params), resumable suspension rotation (checkpoints through
 * RuntimeState.customStates + the independent pool's yield), fail-closed idling
 * (degraded/exception/null), the escalation ladder (consecutive over-slice → throttle →
 * budget-raise thaw → fuse), ledger isolation from the FORMULA pool, and the state
 * table's persistence discipline (save/load/putAllFrom/prune).
 *
 * <p>headless 取型：求值入口 = {@code new GraphEvaluator(graph).evaluate(...)}
 * （GraphEvaluatorTest 同款）；{@code restoreSubState(new RuntimeState())} 兼作
 * runtimeState 注入口（生产重建路径同点注入）。CustomNodeCompute 的预算源/挂起数是
 * 静态账本 —— 每测后复位，防跨测试泄漏（K6 局部计数教训同族）。</p>
 */
class CustomNodeEvalTest {

    private static final String MODID = "testmod";

    private static CustomNodeType spec(String name, int in, int out, int params) {
        return new CustomNodeType(MODID + ":" + name, "T " + name,
            java.util.stream.IntStream.range(0, in).mapToObj(i -> "in" + i).toList(),
            java.util.stream.IntStream.range(0, out).mapToObj(i -> "out" + i).toList(),
            java.util.stream.IntStream.range(0, params)
                .mapToObj(i -> new CustomNodeType.CustomParam("p" + i, CustomNodeType.CustomParam.ParamKind.NUMBER))
                .toList());
    }

    /** 自旋约 200µs（确定性超份额：预算 5ms/prev=100 → slice=50µs；elapsed 落在
     *  slice 与整池之间 —— 渐进梯子路径，不触发破约快车道）。
     *  Spins ~200µs (deterministic overstay: budget 5ms with prev=100 → a 50µs slice;
     *  elapsed lands between slice and the whole pool — the gradual ladder path, never
     *  the breach fast lane). */
    private static void spinOverSlice() {
        long deadline = System.nanoTime() + 200_000L;
        while (System.nanoTime() < deadline) { /* 故意耗时 */ }
    }

    /** 渐进梯子的确定性预算台面：整池 5ms、prev=100 → slice=50µs。
     *  The gradual-ladder budget stage: a 5ms pool with prev=100 → a 50µs slice. */
    private static void gradualLadderStage() {
        CustomNodeCompute.setBudgetMsSource(() -> 5.0);
        CustomNodeCompute.forceHeavyYieldCountPrevForTests(100);
    }

    @AfterEach
    void resetStatics() {
        CustomNodeRegistry.clearForTests();
        CustomNodeCompute.setBudgetMsSource(null);
        CustomNodeCompute.clearAll();
        CustomNodeCompute.forceHeavyYieldCountPrevForTests(0);
    }

    /** 建图 + 求值器 + 注入的 RuntimeState（customStates 落点）。/ graph + evaluator + the wired RuntimeState. */
    private record Rig(NodeGraph graph, GraphEvaluator evaluator, GraphNode node, RuntimeState state) {}

    private Rig rig(CustomNodeBehavior behavior) {
        // 同 id 重注册（unregister→register）：同测多 rig 各带行为；不做预求值 ——
        // 计数/状态账本从零开始，各测自驱动全部 tick。
        // Re-register per rig (unregister→register) so multiple rigs in one test each carry
        // their own behavior; NO warm-up evaluate — counters and state start at zero and
        // every test drives all its own ticks.
        CustomNodeRegistry.unregisterProvider(MODID);
        CustomNodeRegistry.register(spec("counter", 1, 2, 2), behavior);
        NodeGraph g = new NodeGraph();
        GraphNode n = g.addNode(NodeType.CUSTOM, MODID + ":counter", 0f, 0f);
        GraphEvaluator ev = new GraphEvaluator(g);
        RuntimeState rs = new RuntimeState();
        ev.restoreSubState(rs); // runtimeState 注入口（生产重建路径同点）
        return new Rig(g, ev, n, rs);
    }

    // ── 分派与值级交付 ─────────────────────────────────────────────────

    @Test
    @DisplayName("分派 done：输出按 schema 落地；输入/参数值级可读")
    void dispatchDoneWritesOutputsAndReadsValues() {
        Rig rig = rig(ctx -> {
            float[] in = ctx.inputs();
            CustomNodeResult r = CustomNodeResult.done(in[0] + ctx.numberParam("p0"), ctx.numberParam("p1"));
            return r;
        });
        // 装输入：CONST(3) → 自定义节点 in0；params[0]=0.5, params[1]=7
        GraphNode src = rig.graph.addNode(NodeType.CONST, -10f, 0f);
        src.params[0] = 3f;
        rig.graph.addConnection(src.id, 0, rig.node.id, 0);
        rig.node.params[0] = 0.5f;
        rig.node.params[1] = 7f;

        rig.evaluator.evaluate(java.util.List.of(), java.util.Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));

        assertEquals(3.5f, rig.evaluator.getNodeOutput(rig.node.id, 0), 1e-4f, "in0 + p0");
        assertEquals(7f, rig.evaluator.getNodeOutput(rig.node.id, 1), 1e-4f, "p1");
    }

    @Test
    @DisplayName("输出长度漂移守卫：行为给的输出多于 schema 按钳制收口")
    void outputLengthDriftClamps() {
        Rig rig = rig(ctx -> CustomNodeResult.done(1f, 2f, 3f, 4f)); // schema 只声明 2 出
        rig.evaluator.evaluate(java.util.List.of(), java.util.Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertEquals(1f, rig.evaluator.getNodeOutput(rig.node.id, 0), 1e-4f);
        assertEquals(2f, rig.evaluator.getNodeOutput(rig.node.id, 1), 1e-4f);
        assertEquals(2, rig.node.outputValues.length, "node.outputValues 重建为 schema 尺寸");
    }

    @Test
    @DisplayName("fail-closed：提供方抛异常/返回 null/降级缺席 —— 输出保持、tick 不炸")
    void failClosedIdles() {
        Rig rig = rig(ctx -> { throw new RuntimeException("provider bug"); });
        rig.node.outputValues = new float[]{5f, 6f}; // 预置 lastGood
        assertDoesNotThrow(() -> rig.evaluator.evaluate(java.util.List.of(), java.util.Map.of(),
            0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0)));
        assertEquals(5f, rig.node.outputValues[0], 1e-6f, "异常 = 输出保持");

        // null 结果同口径
        Rig rig2 = rig(ctx -> null);
        rig2.node.outputValues = new float[]{5f, 6f};
        assertDoesNotThrow(() -> rig2.evaluator.evaluate(java.util.List.of(), java.util.Map.of(),
            0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0)));
        assertEquals(5f, rig2.node.outputValues[0], 1e-6f);

        // 降级（提供方卸载）：空转不炸
        Rig rig3 = rig(ctx -> CustomNodeResult.done(1f, 2f));
        CustomNodeRegistry.unregisterProvider(MODID);
        assertDoesNotThrow(() -> rig3.evaluator.evaluate(java.util.List.of(), java.util.Map.of(),
            0.05f, new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0)));
    }

    // ── 可恢复轮转与状态 blob ──────────────────────────────────────────

    @Test
    @DisplayName("挂起轮转：检查点落 customStates → 下 tick 以 state 续算 → done 收尾清除")
    void suspensionRotatesThroughStateBlob() {
        Rig rig = rig(ctx -> {
            int seen = ctx.state().contains("n") ? ctx.state().getInt("n") : 0;
            if (seen < 2) {
                CompoundTag next = new CompoundTag();
                next.putInt("n", seen + 1);
                return CustomNodeResult.suspended(next);
            }
            return CustomNodeResult.done((float) seen);
        });
        var ev = rig.evaluator;
        var seat = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0);
        var no = java.util.List.<GraphEvaluator.InputSource>of(); var nom = java.util.Map.<Integer, Float>of();

        ev.evaluate(no, nom, 0.05f, seat);
        assertEquals(0f, ev.getNodeOutput(rig.node.id, 0), 1e-6f, "挂起输出保持（首 tick 无 lastGood = 0）");
        assertEquals(1, rig.state().customStates.get(rig.node.id).getInt("n"), "检查点 n=1 落表");

        ev.evaluate(no, nom, 0.05f, seat);
        assertEquals(2, rig.state().customStates.get(rig.node.id).getInt("n"), "n=2");

        ev.evaluate(no, nom, 0.05f, seat);
        assertEquals(2f, ev.getNodeOutput(rig.node.id, 0), 1e-6f, "done 收尾写输出");
        assertFalse(rig.state().customStates.containsKey(rig.node.id),
            "done（无新状态）清除检查点");
    }

    @Test
    @DisplayName("挂起计入独立池：yield 计数随 beginTick 轮转，slice = 预算/max(1, prev)")
    void suspensionFeedsIndependentPool() {
        Rig rig = rig(ctx -> CustomNodeResult.suspended(new CompoundTag()));
        var seat = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0);
        rig.evaluator.evaluate(java.util.List.of(), java.util.Map.of(), 0.05f, seat);
        CustomNodeCompute.beginTick(); // 轮转：prev = 本 tick 挂起数
        assertEquals(1, CustomNodeCompute.heavyYieldCountPrev(), "挂起计入独立池");
        assertEquals(CustomNodeCompute.budgetNs(), CustomNodeCompute.sliceNs(), "prev=1 → slice=预算");
        CustomNodeCompute.forceHeavyYieldCountPrevForTests(4);
        assertEquals(CustomNodeCompute.budgetNs() / 4, CustomNodeCompute.sliceNs(), "prev=4 → slice=预算/4");
    }

    @Test
    @DisplayName("独立池账本隔离：改自定义池预算源不动 FORMULA 池")
    void poolLedgersAreIndependent() {
        double formulaBefore = FormulaCompute.budgetMs();
        CustomNodeCompute.setBudgetMsSource(() -> 17.5);
        assertEquals(17.5, CustomNodeCompute.budgetMs(), 1e-9);
        assertEquals(formulaBefore, FormulaCompute.budgetMs(), 1e-9, "FORMULA 池不受影响");
    }

    @Test
    @DisplayName("状态表持久化纪律：save/load/putAllFrom round-trip + prune 按存活剪除")
    void stateTablePersistenceDiscipline() {
        Rig rig = rig(ctx -> CustomNodeResult.done(1f, 2f));
        rig.state().customStates.put(rig.node.id, blobOf("k", 9));

        CompoundTag saved = rig.state().save();
        RuntimeState restored = RuntimeState.load(saved);
        RuntimeState target = new RuntimeState();
        target.putAllFrom(restored); // 存档恢复唯一入口
        assertEquals(9, target.customStates.get(rig.node.id).getInt("k"), "round-trip 保真");

        target.pruneToAliveIds(java.util.Set.of(999));
        assertFalse(target.customStates.containsKey(rig.node.id), "死节点状态被剪除");

        RuntimeState remerged = new RuntimeState();
        remerged.putAllFrom(RuntimeState.load(saved));
        remerged.pruneToAliveIds(java.util.Set.of(rig.node.id));
        assertEquals(9, remerged.customStates.get(rig.node.id).getInt("k"), "存活节点状态保留");
    }

    private static CompoundTag blobOf(String k, int v) {
        CompoundTag t = new CompoundTag();
        t.putInt(k, v);
        return t;
    }

    // ── 处置梯子 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("梯子：连续超份额 20 tick → 降频跳过 → 预算调大解冻 → 熔断后永不再调")
    void escalationLadder() {
        AtomicInteger calls = new AtomicInteger();
        Rig rig = rig(ctx -> {
            calls.incrementAndGet();
            spinOverSlice(); // 忽略 sliceExpired，故意越过 slice —— 梯子对象
            return CustomNodeResult.done(1f, 2f);
        });
        gradualLadderStage();
        var seat = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0);
        var no = java.util.List.<GraphEvaluator.InputSource>of(); var nom = java.util.Map.<Integer, Float>of();

        for (int i = 0; i < CustomNodeEval.OVER_SLICE_FREEZE_TICKS; i++)
            rig.evaluator.evaluate(no, nom, 0.05f, seat);
        int afterStreak = calls.get();
        assertEquals(CustomNodeEval.OVER_SLICE_FREEZE_TICKS, afterStreak);

        // 降频：跳过求值（计数冻结）
        rig.evaluator.evaluate(no, nom, 0.05f, seat);
        assertEquals(afterStreak, calls.get(), "降频期不再调用提供方");
        assertEquals(CustomNodeEval.OVER_SLICE_FREEZE_TICKS - 1,
            nodeRun(rig).throttleTicksLeft);

        // 预算调大 → 下一 tick 解冻（K6 同款）
        CustomNodeCompute.setBudgetMsSource(() -> 6.0);
        rig.evaluator.evaluate(no, nom, 0.05f, seat);
        assertEquals(afterStreak + 1, calls.get(), "解冻后恢复调用");
        assertEquals(1, nodeRun(rig).throttleCycles, "第一周期已记账");

        // 回到超份额，再走 4 个完整周期 → 熔断；每周期 = 20 个超份额 tick（再触发降频）
        // + 20 个降频跳过 tick（消耗冻结窗）——两段缺一不可，混排会把周期节奏拖长。
        gradualLadderStage();
        for (int cycle = 0; cycle < CustomNodeEval.FUSE_CYCLES - 1; cycle++) {
            for (int i = 0; i < CustomNodeEval.OVER_SLICE_FREEZE_TICKS; i++)
                rig.evaluator.evaluate(no, nom, 0.05f, seat); // 超份额段 → 第 20 tick 触发降频
            for (int i = 0; i < CustomNodeEval.OVER_SLICE_FREEZE_TICKS; i++)
                rig.evaluator.evaluate(no, nom, 0.05f, seat); // 降频段 → 跳过消耗冻结窗
        }
        assertEquals(CustomNodeEval.FUSE_CYCLES, nodeRun(rig).throttleCycles);
        assertTrue(nodeRun(rig).fused, "5 周期 → 熔断");
        int fusedCalls = calls.get();
        for (int i = 0; i < 10; i++)
            rig.evaluator.evaluate(no, nom, 0.05f, seat);
        assertEquals(fusedCalls, calls.get(), "熔断后永不再调");
        assertEquals(1f, rig.evaluator.getNodeOutput(rig.node.id, 0), 1e-6f, "输出保持 lastGood");
    }

    private static CustomNodeRunState nodeRun(Rig rig) {
        return rig.node.customRun;
    }

    // ── 审查批修复锚（2026-10-31）────────────────────────────────────

    @Test
    @DisplayName("yield 口径：提前挂起（协作早退）不计池；超份额挂起才计——K6 公平口径")
    void yieldOnlyCountsOverSliceSuspensions() {
        AtomicInteger toggles = new AtomicInteger();
        Rig rig = rig(ctx -> {
            // 偶数次 = 提前挂起（瞬间返回，远短于 slice）；奇数次 = 忙等越过 slice 再挂起
            if (toggles.getAndIncrement() % 2 == 0)
                return CustomNodeResult.suspended(new CompoundTag());
            long deadline = System.nanoTime() + CustomNodeCompute.sliceNs() + 200_000L;
            while (System.nanoTime() < deadline) { /* 故意越过 slice */ }
            return CustomNodeResult.suspended(new CompoundTag());
        });
        var seat = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0);
        var no = java.util.List.<GraphEvaluator.InputSource>of();
        var nom = java.util.Map.<Integer, Float>of();

        rig.evaluator.evaluate(no, nom, 0.05f, seat);   // 提前挂起
        CustomNodeCompute.beginTick();
        assertEquals(0, CustomNodeCompute.heavyYieldCountPrev(), "协作早退挂起不计池");

        rig.evaluator.evaluate(no, nom, 0.05f, seat);   // 越过 slice 的挂起
        CustomNodeCompute.beginTick();
        assertEquals(1, CustomNodeCompute.heavyYieldCountPrev(), "超份额挂起计入池");
    }

    @Test
    @DisplayName("状态帽：超 32KB SNBT 的检查点拒收（不落表）；合规检查点照常落")
    void stateCapRefusesOversizedBlob() {
        Rig rig = rig(ctx -> {
            CompoundTag big = new CompoundTag();
            big.putString("pad", "x".repeat(40000)); // 超帽
            return CustomNodeResult.suspended(big);
        });
        rig.evaluator.evaluate(java.util.List.of(), java.util.Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertFalse(rig.state().customStates.containsKey(rig.node.id), "超帽检查点不落表");
    }

    @Test
    @DisplayName("世代重置：提供方重注册 → 熔断解除、运行态归零（审查批 (c)6 实现路径）")
    void providerGenerationResetsFuse() {
        AtomicInteger calls = new AtomicInteger();
        Rig rig = rig(ctx -> {
            calls.incrementAndGet();
            spinOverSlice(); // 超份额对象（瞬时返回永不进梯子）
            return CustomNodeResult.done(1f, 2f);
        });
        // 走完整梯子到熔断（渐进台面：整池 5ms/prev=100 → slice=50µs，行为自旋 200µs）
        gradualLadderStage();
        var seat = new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0);
        var no = java.util.List.<GraphEvaluator.InputSource>of();
        var nom = java.util.Map.<Integer, Float>of();
        for (int cycle = 0; cycle < CustomNodeEval.FUSE_CYCLES; cycle++) {
            for (int i = 0; i < CustomNodeEval.OVER_SLICE_FREEZE_TICKS; i++)
                rig.evaluator.evaluate(no, nom, 0.05f, seat); // 超份额段
            for (int i = 0; i < CustomNodeEval.OVER_SLICE_FREEZE_TICKS; i++)
                rig.evaluator.evaluate(no, nom, 0.05f, seat); // 降频段
        }
        assertTrue(nodeRun(rig).fused, "前置：已熔断");
        int fusedCalls = calls.get();
        rig.evaluator.evaluate(no, nom, 0.05f, seat);
        assertEquals(fusedCalls, calls.get(), "熔断期不调用");

        // 提供方重注册（世代号递增）→ 下一 tick 运行态重置、新行为接管（熔断解除）。
        // calls 只在旧行为里自增 —— 重置后调用的是新行为，断言落在输出口。
        CustomNodeRegistry.unregisterProvider(MODID);
        CustomNodeRegistry.register(spec("counter", 1, 2, 2), ctx -> CustomNodeResult.done(9f, 9f));
        rig.evaluator.evaluate(no, nom, 0.05f, seat);
        assertEquals(fusedCalls, calls.get(), "旧行为不再被调");
        assertEquals(9f, rig.evaluator.getNodeOutput(rig.node.id, 0), 1e-6f, "重注册后新行为接管（熔断解除）");
        assertFalse(nodeRun(rig).fused, "运行态已重置");
    }

    @Test
    @DisplayName("栅栏破约快车道：单次调用吃满整池预算 → 跳过宽限立即熔断")
    void wholePoolBreachFusesImmediately() {
        Rig rig = rig(ctx -> {
            try { Thread.sleep(2); } catch (InterruptedException ignored) { }
            return CustomNodeResult.done(1f, 2f);
        });
        CustomNodeCompute.setBudgetMsSource(() -> 0.01); // 整池 10µs：2ms 调用 = 破约
        CustomNodeCompute.forceHeavyYieldCountPrevForTests(1);
        rig.evaluator.evaluate(java.util.List.of(), java.util.Map.of(), 0.05f,
            new GraphEvaluator.SeatInputState(0, 0, 0, 0, 0));
        assertTrue(nodeRun(rig).fused, "破约 → 立即熔断");
        assertEquals(0, nodeRun(rig).throttleCycles, "未经降频周期（快车道跳过宽限）");
    }
}
