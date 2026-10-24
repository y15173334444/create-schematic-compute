package io.github.y15173334444.create_schematic_compute.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AST 语句解释器(刀3+刀5)。控制流由语句级解释执行;表达式走统一 Value 栈机(evaluateValue)。
 * 刀5:循环边界协作超时——每 16 迭代测一次墙钟,超过 slice 即挂起(保存 carrier);
 * 下 tick 恢复 Env 快照、从循环栈计数续算。续算采用**寻径执行**:跳过已快照化的前缀语句,
 * 直达挂起的循环(避免前缀赋值覆盖快照中的循环变量)。MAX_ITER 按 spread 累计,超限硬 shed。
 * AST statement interpreter (knife 3 + 5). Control flow runs statement-level; expressions go
 * through the unified Value stack machine. Knife 5: cooperative timeout at loop boundaries —
 * wall clock checked every 16 iterations; exceeding the slice suspends (saves a carrier);
 * next tick restores the Env snapshot and resumes from the loop-stack counters via **seek
 * execution**: snapshotted prefix statements are skipped so they can't clobber loop-carried
 * variables in the snapshot. MAX_ITER accumulates spread-wide; exceeding it hard-sheds.
 */
public final class FormulaInterpreter {

    /** 每 16 迭代测一次墙钟(决策 §五:墙钟检查、每 16 迭代)。 / Wall clock checked every 16 iterations. */
    static final int CHECK_EVERY = 16;
    /** 病态循环硬上限(决策 §五:MAX_ITER=1M,按 spread 累计)。 / Pathological-loop hard cap (spread-wide). */
    static final long MAX_ITER = 1_000_000L;

    /**
     * 语句嵌套递归深度上限（P1-6 解释侧兜底）。AST 由解析器产生、深度已受
     * {@link FormulaParser#MAX_PARSE_DEPTH} 约束，本上限是第二道防线：防未来出现
     * 绕过解析器构造深 AST 的路径（或上限口径漂移）。计数随每次 {@link #exec} 调用
     * 从 0 重新开始 —— 挂起/续算跨 tick 复用 carrier，但深度计数不跨 tick 累计。
     * Statement-nesting recursion limit (P1-6 interpreter backstop). ASTs come from the
     * parser and are already bounded by {@link FormulaParser#MAX_PARSE_DEPTH}; this limit
     * is the second line of defence against any path that builds a deep AST without the
     * parser (or a limit-drift regression). The count restarts from 0 on every
     * {@link #exec} call — suspend/resume reuses the carrier across ticks, but the depth
     * count never accumulates across ticks.
     */
    static final int MAX_EXEC_DEPTH = 256;

    /** break 信号:最内层循环捕获并退出。 / Break signal: caught by the innermost loop, which exits. */
    static final class BreakSignal extends RuntimeException {}
    /** continue 信号:最内层循环捕获并跳下一轮;顶层由 {@link #exec} lenient 吞掉(F14)。
     *  Continue signal: caught by the innermost loop; at top level leniently swallowed by
     *  {@link #exec} (F14). */
    static final class ContinueSignal extends RuntimeException {}

    /**
     * 受控超深异常（Exception 族，P1-6）：语句递归超过 {@link #MAX_EXEC_DEPTH} 时抛出。
     * eval 分支的 {@code catch (Exception)} 捕获 → 输出归零，绝不以 StackOverflowError 逃逸。
     * Controlled too-deep exception (Exception family, P1-6): thrown when the statement
     * recursion exceeds {@link #MAX_EXEC_DEPTH}. The eval branch's {@code catch (Exception)}
     * intercepts it → outputs pinned to 0; never escapes as a StackOverflowError.
     */
    public static final class ExecTooDeepException extends RuntimeException {
        public ExecTooDeepException(int depth) {
            super("statement nesting exceeds " + MAX_EXEC_DEPTH + " levels (depth " + depth + ")");
        }
    }

    /** 挂起信号:循环边界 slice 耗尽,携带 carrier 抛出。 / Suspend signal: slice exhausted at a loop boundary, carries the carrier. */
    public static final class SuspendSignal extends RuntimeException {
        public final Carrier carrier;
        SuspendSignal(Carrier c) { super(null, null, false, false); this.carrier = c; }
    }

    /** 硬 shed 信号:MAX_ITER 兜底(病态脚本)。 / Hard-shed signal: MAX_ITER backstop (pathological scripts). */
    public static final class ShedSignal extends RuntimeException {
        ShedSignal() { super(null, null, false, false); }
    }

    /** 挂起-lite 收敛态(节点 transient,存盘丢弃)。 / Suspend-lite convergence state (node transient, dropped on save). */
    public static final class Carrier {
        /** 挂起时未完成的循环(外层→内层,内层在尾部)。 / Unfinished loops at suspension time (outer→inner). */
        public List<LoopState> loopStack = new ArrayList<>();
        /** 挂起时的 Env 快照(续算恢复用)。 / Env snapshot at suspension (restored on resume). */
        public Map<String, Value> envSnapshot = new HashMap<>();
        /** spread 期间冻结的输入快照(变更检测 1e-3 容差)。 / Frozen input snapshot during the spread. */
        public float[] frozenInputs = null;
        /** 是否已完成(emit-on-done:done 后输入未变则整节点跳过)。 / Done flag (emit-on-done: skip while inputs unchanged). */
        public boolean done = false;
        /** 渲染态进度:0..1,-1 = 不定(while 循环)。 / Render progress: 0..1, -1 = indeterminate (while loops). */
        public float progress = 0f;
        /** spread 累计迭代数(MAX_ITER 跨 tick 累积,防永不收敛)/ cumulative iterations across the spread. */
        public long totalIterations = 0;
    }

    /** 循环栈条目:循环 AST 实例(同一次 parse 内身份稳定)+ 下一轮序号。 / Loop-stack entry: loop AST identity + next iteration index. */
    public static final class LoopState {
        public final FormulaAst.Stmt loop;
        public long k;
        LoopState(FormulaAst.Stmt loop, long k) { this.loop = loop; this.k = k; }
    }

    /** 执行上下文:预算(slice)、检查计数器与挂起状态。 / Execution context: budget (slice), check counters, suspension state. */
    public static final class Ctx {
        final long sliceNs;
        final long nodeStartNs;
        final Map<String, Value> env;
        final List<LoopState> loopStack;
        int iterations;
        long totalIterations;

        Ctx(long sliceNs, Map<String, Value> env, List<LoopState> resumeStack, long initTotal) {
            this.sliceNs = sliceNs;
            this.nodeStartNs = System.nanoTime();
            this.env = env;
            this.loopStack = resumeStack != null ? new ArrayList<>(resumeStack) : new ArrayList<>();
            this.totalIterations = initTotal;
        }

        /** 循环边界检查:每 CHECK_EVERY 迭代测墙钟,超 slice → 挂起;超 MAX_ITER → 硬 shed。
         *  Boundary check: wall clock every CHECK_EVERY iterations; over slice → suspend; over MAX_ITER → shed. */
        void boundaryCheck() {
            if (++iterations >= CHECK_EVERY) {
                iterations = 0;
                if (System.nanoTime() - nodeStartNs > sliceNs) {
                    // 进度以**最外层 repeat** 为主、内层份额平滑叠加:嵌套循环(如弹道脚本的
                    // 扫描×模拟)下内层循环反复 0→1 会造成进度条横跳;最外层是 while 时退回
                    // 最内层 repeat,都没有则不定(-1)。
                    // Progress is driven by the **outermost repeat** with the innermost share added for
                    // smoothness: under nested loops (e.g. the ballistic scan×simulation) the inner loop
                    // repeatedly sweeping 0→1 makes the bar jump around; when the outermost is a while,
                    // fall back to the innermost repeat, else indeterminate (-1).
                    float progress = 0f;
                    if (!loopStack.isEmpty()) {
                        LoopState outer = loopStack.get(0);
                        if (outer.loop instanceof FormulaAst.RepeatStmt ro && ro.count() > 0) {
                            double p = outer.k / (double) ro.count();
                            LoopState inner = loopStack.get(loopStack.size() - 1);
                            if (inner != outer && inner.loop instanceof FormulaAst.RepeatStmt ri && ri.count() > 0)
                                p += (inner.k / (double) ri.count()) / ro.count();
                            progress = (float) Math.min(1.0, Math.max(0.0, p));
                        } else {
                            LoopState inner = loopStack.get(loopStack.size() - 1);
                            progress = inner.loop instanceof FormulaAst.RepeatStmt ri && ri.count() > 0
                                ? (float) Math.min(1.0, Math.max(0.0, (double) inner.k / ri.count()))
                                : -1f; // 不定 / indeterminate
                        }
                    }
                    var c = new Carrier();
                    c.loopStack = new ArrayList<>(loopStack);
                    c.envSnapshot = new HashMap<>(env);
                    c.progress = progress;
                    c.totalIterations = totalIterations; // MAX_ITER 跨 tick 累积 / spread-wide
                    throw new SuspendSignal(c);
                }
            }
            if (++totalIterations > MAX_ITER) throw new ShedSignal();
        }

        /** 找到或压入当前循环的栈条目(resume 时命中已有条目,取其 k)。 / Find or push this loop's stack entry (resume hits the existing entry). */
        LoopState findOrPush(FormulaAst.Stmt loop) {
            for (var e : loopStack) if (e.loop == loop) return e;
            var e = new LoopState(loop, 0);
            loopStack.add(e);
            return e;
        }

        /** 循环完成/退出后弹出栈条目。 / Pop the stack entry after the loop completes or breaks. */
        void popLoop(FormulaAst.Stmt loop) {
            if (!loopStack.isEmpty() && loopStack.get(loopStack.size() - 1).loop == loop)
                loopStack.remove(loopStack.size() - 1);
        }
    }

    private FormulaInterpreter() {}

    /** 单趟执行(无预算限制;测试与刀3 兼容入口)。 / Single-pass execution (no budget; knife-3 compatible entry). */
    public static void exec(List<FormulaAst.Stmt> stmts, Map<String, Value> env) {
        exec(stmts, env, Long.MAX_VALUE / 2, null, 0);
    }

    /** 执行语句列表;结束后 env 持有变量终值。sliceNs = 单节点配额(FormulaCompute.sliceNs())。
     *  Execute statements; env holds final values. sliceNs = per-node quota (FormulaCompute.sliceNs()). */
    public static void exec(List<FormulaAst.Stmt> stmts, Map<String, Value> env, long sliceNs) {
        exec(stmts, env, sliceNs, null, 0);
    }

    /** 带 resume 栈与累计迭代数的续算入口(resumeStack = carrier.loopStack)。
     *  深度计数从 0 开始 —— 每次 exec 调用独立,续算不继承上一次的深度（P1-6）。
     *  Resume entry point with the carrier's loop stack and spread-wide iteration count.
     *  The depth count starts at 0 — each exec call is independent; resuming never
     *  inherits the previous call's depth (P1-6). */
    public static void exec(List<FormulaAst.Stmt> stmts, Map<String, Value> env, long sliceNs,
                            List<LoopState> resumeStack, long initTotal) {
        var ctx = new Ctx(sliceNs, env, resumeStack, initTotal);
        try {
            runBody(stmts, env, ctx, 0, 0);
        } catch (BreakSignal b) {
            // 顶层 break lenient 忽略(校验期已提示)/ top-level break is leniently ignored
        } catch (ContinueSignal c) {
            // F14: 顶层 continue 与 break 同口径 lenient 忽略——ContinueSignal 是 RuntimeException,
            // 若不在此拦截会逃逸到 eval 分支的 catch(Exception),被当成异常把整节点输出清零。
            // 语义:continue 之后的顶层语句跳过,已完成的赋值保留(与顶层 break 一致)。
            // F14: top-level continue is leniently ignored with the same contract as break —
            // ContinueSignal is a RuntimeException; left uncaught here it would escape into the
            // eval branch's catch(Exception) and zero the whole node's outputs as if it were an
            // error. Semantics: top-level statements after the continue are skipped, completed
            // assignments are kept (identical to top-level break).
        }
    }

    /**
     * 执行语句列表。续算模式(depth < loopStack.size()):寻径执行——跳过已快照化的前缀语句,
     * 直达深度 depth 处的挂起循环,其后语句在循环完成后正常执行。
     * recDepth = 语句体嵌套深度（P1-6 守卫,与 depth 的"循环栈索引"语义正交）。
     * Run a statement list. Resume mode (depth < stack size): seek execution — skip the
     * snapshotted prefix, jump to the suspended loop at stack depth `depth`; statements after
     * it run normally once the loop completes.
     * recDepth = body-nesting depth (P1-6 guard, orthogonal to `depth`'s loop-stack-index
     * meaning).
     */
    private static void runBody(List<FormulaAst.Stmt> stmts, Map<String, Value> env, Ctx ctx,
                                int depth, int recDepth) {
        if (recDepth > MAX_EXEC_DEPTH) throw new ExecTooDeepException(recDepth);
        FormulaAst.Stmt target = depth < ctx.loopStack.size() ? ctx.loopStack.get(depth).loop : null;
        if (target == null) {
            for (var s : stmts) execStmt(s, env, ctx, depth, recDepth);
            return;
        }
        int i = 0;
        for (; i < stmts.size(); i++) {
            if (containsLoop(stmts.get(i), target)) { execStmt(stmts.get(i), env, ctx, depth, recDepth); i++; break; }
        }
        for (; i < stmts.size(); i++) execStmt(stmts.get(i), env, ctx, depth, recDepth);
    }

    /** 该语句(或其嵌套体)是否包含目标循环。 / Does this statement (or its nested bodies) contain the target loop. */
    private static boolean containsLoop(FormulaAst.Stmt s, FormulaAst.Stmt target) {
        if (s == target) return true;
        return switch (s) {
            case FormulaAst.RepeatStmt r -> r.body().stream().anyMatch(b -> containsLoop(b, target));
            case FormulaAst.WhileStmt w -> w.body().stream().anyMatch(b -> containsLoop(b, target));
            case FormulaAst.IfStmt i -> i.body().stream().anyMatch(b -> containsLoop(b, target))
                || i.elseBody().stream().anyMatch(b -> containsLoop(b, target));
            default -> false;
        };
    }

    private static void execStmt(FormulaAst.Stmt s, Map<String, Value> env, Ctx ctx, int depth, int recDepth) {
        switch (s) {
            case FormulaAst.AssignStmt a -> env.put(a.var(), FormulaParser.evaluateValue(a.rpn(), env));
            case FormulaAst.ExprStmt e -> FormulaParser.evaluateValue(e.rpn(), env); // 结果丢弃 / discard
            case FormulaAst.RepeatStmt r -> {
                LoopState st = ctx.findOrPush(r);
                int stDepth = ctx.loopStack.indexOf(st);
                for (long k = st.k; k < r.count(); k++) {
                    ctx.boundaryCheck();
                    try {
                        runBody(r.body(), env, ctx, stDepth + 1, recDepth + 1);
                    } catch (ContinueSignal c) {
                        // 下一轮 / next iteration
                    } catch (BreakSignal b) {
                        break;
                    }
                    // 本轮 body 完成后才推进 k:挂起时 carrier 保留当前轮序号,续算重入本轮不丢迭代;
                    // 外层循环同理——body 中途挂起不会把外层 k 提前推进到下一轮。
                    // Advance k only after the body completes: on suspend the carrier keeps the current
                    // iteration, so resume re-enters it without losing iterations; outer loops likewise
                    // don't skip their unfinished body on resume.
                    st.k = k + 1;
                }
                ctx.popLoop(r);
            }
            case FormulaAst.WhileStmt w -> {
                LoopState st = ctx.findOrPush(w);
                int stDepth = ctx.loopStack.indexOf(st);
                while (truthy(FormulaParser.evaluateValue(w.condRpn(), env))) {
                    ctx.boundaryCheck();
                    try {
                        runBody(w.body(), env, ctx, stDepth + 1, recDepth + 1);
                    } catch (ContinueSignal c) {
                        // 下一轮 / next iteration
                    } catch (BreakSignal b) {
                        break;
                    }
                }
                ctx.popLoop(w);
            }
            case FormulaAst.IfStmt i -> {
                boolean take = truthy(FormulaParser.evaluateValue(i.condRpn(), env));
                runBody(take ? i.body() : i.elseBody(), env, ctx, depth, recDepth + 1);
            }
            case FormulaAst.BreakStmt b -> throw new BreakSignal();
            case FormulaAst.ContinueStmt c -> throw new ContinueSignal();
        }
    }

    /** 条件真值:标量 != 0;向量条件 lenient 假(校验期报错)。 / Condition truthiness: scalar != 0; vectors are leniently false. */
    private static boolean truthy(Value v) {
        return v instanceof Value.Scalar s && s.v() != 0.0;
    }
}
