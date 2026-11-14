package io.github.y15173334444.create_schematic_compute.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 批量段协调器（R3b，§4.7-6）——把 FORMULA 节点的逐节点 exec 收敛为「每连续段一次
 * tickBatch」。每次 {@code GraphEvaluator.evaluate} 调用一个实例（服务端单线程）。
 *
 * <p><b>切分规则（精确等价的唯一来源，§4.7-6 证明概要）</b>：批量段 = 拓扑序中连续
 * 的可入段 FORMULA 节点，且新成员与段内任一现有成员之间<b>不存在任何图内连线</b>
 * （双向皆查）。段在首个不可入段节点处断开并于其求值<b>之前</b>冲刷。等价性：段
 * 区间内无外部节点（连续性）⇒ 成员的同 tick 输入写者恒在段首之前（拓扑序 + 无边）、
 * 成员输出的外部读者恒在段尾之后 ⇒ 段首一次 gather+exec+write 与逐位置求值逐位
 * 等价；被切边读跨 tick 快照，不受影响。跳过型节点（冻结/done-skip/去重命中/编码
 * 失败）不产读写、不破坏区间闭合，可留在段内（中性）。</p>
 *
 * <p><b>去重骑行</b>：批内同脚本 + 同输入（Arrays.equals 按位）的后到者不进批——
 * 冲刷后取首员的存储结果（= FORMULA case 去重命中分支的镜像：输出拷缓存、槽位态
 * 清空、spread 归零）；首员未缓存（OutputShed/ExecError）→ 骑手退化为独立单员批
 * （与今天逐位置行为一致）。</p>
 *
 * <p><b>冲刷执行</b>：一次 {@link FormulaNative#tickBatch}——挂起态会话内自持
 * （carrier 不跨界）、逐员状态/进度/耗时回传；冲刷后逐员落账（dedup 存储、yield
 * 记账、spread、streak）+ {@code ctx.outputs.put}（成员的 outputs 落表统一在段首
 * 时刻——eval() 尾部的落表对被段节点跳过）。</p>
 *
 * <p>The batch-segment coordinator (R3b, §4.7-6) — converges per-node FORMULA execs
 * into one tickBatch per contiguous segment. One instance per evaluate() call.
 * The split rule: a segment is a maximal run of consecutive joinable FORMULA nodes
 * in the topo order where a candidate shares no in-graph wiring with any pending
 * member (both directions); the segment flushes before the first non-joinable node
 * evaluates. The exactness proof lives in §4.7-6. Dedup riders mirror the FORMULA
 * case's cache-hit branch after the flush.</p>
 */
final class FormulaBatchCoordinator {

    /** 一个批成员：解析完成、待冲刷。 / one resolved member awaiting the flush. */
    private static final class Member {
        final GraphNode node;
        final float[] o;
        final FormulaParser.ScriptParseResult script;
        final int programId;
        final float[] pinInputs;
        final double[] execIn;
        final boolean refresh;
        final float[] frozenRef;

        Member(GraphNode node, float[] o, FormulaParser.ScriptParseResult script, int programId,
               float[] pinInputs, double[] execIn, boolean refresh, float[] frozenRef) {
            this.node = node;
            this.o = o;
            this.script = script;
            this.programId = programId;
            this.pinInputs = pinInputs;
            this.execIn = execIn;
            this.refresh = refresh;
            this.frozenRef = frozenRef;
        }
    }

    /** 去重骑手：批内同键的后到者，冲刷后落缓存命中分支或退化独立求值。 */
    private record Rider(GraphNode node, float[] o, FormulaParser.ScriptParseResult script, int programId,
                         float[] pinInputs, double[] execIn, boolean refresh, float[] frozenRef) {}

    private final EvalContext ctx;
    private final NodeGraph graph;
    private final ArrayList<Member> members = new ArrayList<>();
    private final ArrayList<Integer> memberIds = new ArrayList<>();
    private final ArrayList<Rider> riders = new ArrayList<>();

    FormulaBatchCoordinator(EvalContext ctx) {
        this.ctx = ctx;
        this.graph = ctx.graph;
    }

    /**
     * FORMULA case 的入段口（R3a 逐节点态机的解析前半共用
     * {@link FormulaEval#resolveNativeExec}）；解析判「跳过」（冻结/done-skip/编码
     * 失败）= 中性节点，不进段也不破段。
     * The FORMULA case's entry: resolve then join (or ride); a skip resolution is a
     * neutral node that neither joins nor breaks the segment.
     */
    /** 入段口：true = 本节点归冲刷管（成员或骑手，outputs 落表在冲刷侧）；
     *  false = 中性节点（冻结/done-skip/编码失败），outputs 走 eval() 尾部常规落表。 */
    boolean stage(GraphNode node, float[] o, FormulaParser.ScriptParseResult script, float[] pinInputs, int nOut) {
        var prog = FormulaNativeSession.programFor(script);
        if (prog == null) {
            return false; // 编码/上传失败(契约外):输出保持,中性 / contract-impossible: hold, neutral
        }
        FormulaEval.NativeResolution res = FormulaEval.resolveNativeExec(node, prog, pinInputs);
        if (res == null) {
            return false; // 冻结保持 / done 且输入未变:跳过,中性 / frozen or done-skip: neutral
        }
        // 去重骑行:批内同脚本 + 同输入(按位)的后到者不进批。
        // Dedup riding: a later same-key member rides instead of re-evaluating.
        for (Member m : members) {
            if (m.script.sourceFormula.equals(script.sourceFormula) && Arrays.equals(m.pinInputs, pinInputs)) {
                riders.add(new Rider(node, o, script, prog.programId(), pinInputs,
                    res.execIn(), res.refresh(), res.frozenRef()));
                return true;
            }
        }
        members.add(new Member(node, o, script, prog.programId(), pinInputs,
            res.execIn(), res.refresh(), res.frozenRef()));
        memberIds.add(node.id);
        return true;
    }

    /**
     * 判定下一节点能否加入当前段（FORMULA 且与段内任一成员无图内连线——双向皆查）。
     * Whether the next topo node may join the pending segment (FORMULA, no wiring
     * between it and any pending member, either direction).
     */
    boolean canJoin(GraphNode next) {
        if (next.type != NodeType.FORMULA) {
            return false;
        }
        for (NodeConnection c : graph.connections) {
            if ((c.fromId == next.id && memberIds.contains(c.toId))
                || (c.toId == next.id && memberIds.contains(c.fromId))) {
                return false;
            }
        }
        return true;
    }

    /** 当前段成员数（测试/观测视图）。 / pending member count (test/observability view). */
    int pendingMembers() {
        return members.size();
    }

    /** 拓扑遍每步的冲刷闸：不可入段节点求值前冲刷当前段。 / the per-step flush gate. */
    void flushBefore(GraphNode next) {
        if ((members.isEmpty() && riders.isEmpty()) || canJoin(next)) {
            return;
        }
        flush();
    }

    /** 遍终点冲刷（尾段）。 / the trailing flush. */
    void flushAll() {
        if (!members.isEmpty() || !riders.isEmpty()) {
            flush();
        }
    }

    /** 冲刷当前段（快照-清空-执行,骑手处理可再产单员批——局部快照防递归污染）。 */
    private void flush() {
        var batch = new ArrayList<>(members);
        var batchRiders = new ArrayList<>(riders);
        members.clear();
        memberIds.clear();
        riders.clear();
        if (!batch.isEmpty()) {
            executeBatch(batch);
        }
        for (Rider r : batchRiders) {
            float[] cached = FormulaCompute.lookupDedup(r.script().sourceFormula, r.pinInputs());
            if (cached != null) {
                // 骑手缓存命中 = FORMULA case 去重命中分支的镜像:输出拷缓存、槽位态清、spread 归零。
                // Rider cache-hit = the FORMULA case's dedup-hit branch mirrored.
                FormulaNativeSession.clearNodeSlot(r.node());
                Arrays.fill(r.o(), 0f);
                System.arraycopy(cached, 0, r.o(), 0, Math.min(cached.length, r.o().length));
                r.node().formulaSpreadProgress = 0f;
                // 骑手的 outputs 落表也在冲刷侧（eval() 尾部对被段/骑手节点均已跳过）。
                // The rider's outputs land on the flush side too (both tail puts skipped).
                ctx.outputs.put(r.node().id, r.o().clone());
            } else {
                // 首员未缓存(OutputShed/ExecError 不进 dedup)→ 骑手独立求值:单员批,与逐位置行为一致。
                // An uncached leader sheds -> the rider evaluates independently as a one-member batch.
                var solo = List.of(new Member(r.node(), r.o(), r.script(), r.programId(),
                    r.pinInputs(), r.execIn(), r.refresh(), r.frozenRef()));
                executeBatch(solo);
            }
        }
    }

    /** 执行一批:一次 tickBatch + 逐员状态映射（与 R3a catch 族镜像逐字对齐）。 */
    private void executeBatch(List<Member> batch) {
        int count = batch.size();
        int[] slots = new int[count];
        int[] programIds = new int[count];
        int[] refreshFlags = new int[count];
        int[] inOffsets = new int[count + 1];
        int[] outOffsets = new int[count + 1];
        int inTotal = 0;
        int outTotal = 0;
        for (int i = 0; i < count; i++) {
            Member m = batch.get(i);
            slots[i] = FormulaNativeSession.slotFor(m.node);
            programIds[i] = m.programId;
            refreshFlags[i] = m.refresh ? 1 : 0;
            inTotal += m.execIn.length;
            inOffsets[i + 1] = inTotal;
            outTotal += m.o.length;
            outOffsets[i + 1] = outTotal;
        }
        double[] flatIn = new double[inTotal];
        double[] flatOut = new double[outTotal];
        for (int i = 0; i < count; i++) {
            Member m = batch.get(i);
            System.arraycopy(m.execIn, 0, flatIn, inOffsets[i], m.execIn.length);
            // 双向 seed:OutputShed 的「已完成位新鲜、余位保持」底座(float→double 逐元素)。
            for (int k = 0; k < m.o.length; k++) {
                flatOut[outOffsets[i] + k] = m.o[k];
            }
        }
        int[] statuses = new int[count];
        float[] progress = new float[count];
        long[] elapsedNs = new long[count];
        int rc = FormulaNative.tickBatch(FormulaNativeSession.handle(), count,
            slots, programIds, refreshFlags,
            flatIn, inOffsets, FormulaCompute.sliceNs(), FormulaCompute.deadlineRemainingNs(),
            flatOut, outOffsets, statuses, progress, elapsedNs);
        if (rc != 0) {
            // 批级契约错误(会话/数组校验)——理论不可达:输出保持、无状态迁移、告警。
            FormulaEval.LOGGER.error("FORMULA tickBatch batch-level error rc={} — outputs hold", rc);
            return;
        }
        for (int i = 0; i < count; i++) {
            mapMemberStatus(batch.get(i), statuses[i], progress[i], elapsedNs[i], flatOut, outOffsets[i]);
        }
    }

    /** 逐员状态映射（R3a 状态码迁移的镜像,carrier 字节面换成槽位态）。 */
    private void mapMemberStatus(Member m, int rcStatus, float progress, long elapsedNs,
                                 double[] flatOut, int outOffset) {
        GraphNode node = m.node;
        float[] o = m.o;
        boolean dedupCacheable = true;
        switch (rcStatus) {
            case 0 -> { // Done
                node.formulaNativeStatus = 0;
                node.formulaNativeDone = true;
                node.formulaNativeFrozen = m.frozenRef;
                node.formulaSpreadProgress = 0f;
                node.formulaDeadlineStreak = 0;
                for (int i = 0; i < o.length; i++) {
                    o[i] = (float) flatOut[outOffset + i];
                }
            }
            case 1 -> { // Suspended
                node.formulaNativeStatus = 1;
                node.formulaNativeDone = false;
                node.formulaNativeFrozen = m.frozenRef;
                node.formulaSpreadProgress = progress;
                FormulaCompute.reportYield();
                node.formulaDeadlineStreak = 0;
            }
            case 2 -> { // Shed (MAX_ITER)
                node.formulaNativeStatus = 2;
                node.formulaNativeDone = false;
                node.formulaSpreadProgress = 0f;
                if (!node.formulaShedWarned) {
                    node.formulaShedWarned = true;
                    node.formulaFrozenBudgetMs = 0.0;
                    FormulaEval.LOGGER.warn("FORMULA node {} exceeded MAX_ITER — pathological script, shed to lastGood", node.id);
                }
            }
            case 3 -> { // OutputShed（输出阶段撞 deadline）
                node.formulaNativeStatus = 3;
                node.formulaNativeDone = false;
                dedupCacheable = false;
                for (int i = 0; i < o.length; i++) {
                    o[i] = (float) flatOut[outOffset + i]; // 已完成位新鲜 + 余位保持(seed)
                }
                FormulaEval.LOGGER.debug("FORMULA batch output idx suspended by budget deadline (node {})", node.id);
                FormulaEval.noteDeadlineShed(node, elapsedNs);
            }
            case 4 -> { // DeadlineSuspended（fromDeadline:免计 yield,走公平规则）
                node.formulaNativeStatus = 4;
                node.formulaNativeDone = false;
                node.formulaNativeFrozen = m.frozenRef;
                node.formulaSpreadProgress = progress;
                FormulaEval.noteDeadlineShed(node, elapsedNs);
            }
            case 5 -> { // ExecError(栈下溢/超深——Java catch(Exception) 同型:输出归零)
                node.formulaNativeStatus = 5;
                node.formulaNativeDone = false;
                node.formulaSpreadProgress = 0f;
                Arrays.fill(o, 0f);
                dedupCacheable = false;
            }
            default -> {
                // 逐员契约错误(未知槽位/程序/缓冲)——理论不可达:输出保持、无迁移、不进 dedup。
                node.formulaNativeStatus = rcStatus;
                FormulaEval.LOGGER.error("FORMULA tickBatch member error rc={} (node {}) — outputs hold", rcStatus, node.id);
                dedupCacheable = false;
            }
        }
        if (dedupCacheable) {
            FormulaCompute.storeDedup(m.script.sourceFormula, m.pinInputs, o);
        }
        // 段首统一落表(精确等价证明的写侧;eval() 尾部对被段节点跳过)。
        ctx.outputs.put(node.id, o.clone());
    }
}
