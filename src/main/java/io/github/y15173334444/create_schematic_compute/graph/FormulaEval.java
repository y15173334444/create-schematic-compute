package io.github.y15173334444.create_schematic_compute.graph;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数学/三角/逻辑/控制域处理器（决策文档 §四）：MATH_BASIC / MATH_ADVANCED / TRIG /
 * LOGIC / CONTROL —— 算术、FORMULA、插值、三角、比较、继电器、PID/CLAMP/MAP。
 * <b>不含 GATE</b>（状态体在 evalExt 档，见 TimingEval；档位合一 2026-10-31 后 GATE
 * 不再走本处理器，决策 §三-4 怪癖随之熄灭）。case 体从 {@link GraphEvaluator} 的 eval()
 * switch 逐字搬迁（§三-2：
 * 方法体不改写，只改接收者/归属）；本类是静态函数包，不建继承体系（§三-3）。
 * <p>Math / trig / logic / control handler (decision doc §四): MATH_BASIC /
 * MATH_ADVANCED / TRIG / LOGIC / CONTROL — arithmetic, FORMULA, interpolation,
 * trigonometry, comparisons, relays, PID/CLAMP/MAP. <b>GATE is absent</b> (its state
 * body lives in the evalExt tier, see TimingEval; after the tier unification
 * (2026-10-31) GATE no longer routes here and the decision §三-4 quirk is gone).
 * Case bodies are relocated verbatim from
 * {@link GraphEvaluator}'s eval() switch (§三-2: bodies are never rewritten, only their
 * owner changes); this class is a static function pack, no inheritance hierarchy (§三-3).</p>
 */
public final class FormulaEval {

    private FormulaEval() {}

    /**
     * K6 deadline 兜底的公平规则：deadline 是 tick 级共享资源，被动方（其它源把 tick 拖过
     * deadline、本节点自身开销在份额内）只保持输出、不惩罚；主动方（节点自身执行墙钟超过
     * 自身 slice）计连续超时，满 {@link #DEADLINE_FREEZE_TICKS} 走冻结。done 与常规 slice
     * 挂起都会清零连续计数（在推进 = 有救，无需升级）。
     * K6 fairness rule for the deadline backstop: the deadline is a shared per-tick resource —
     * a victim (some other source pushed the tick past the deadline while this node stayed
     * within its own slice) only holds its outputs, never punished; a culprit (its own exec
     * wall clock exceeded its slice) counts toward the consecutive streak and freezes at
     * {@link #DEADLINE_FREEZE_TICKS}. Completion and regular slice suspends reset the streak
     * (making progress means there is still hope — no escalation needed).
     */
    private static void noteDeadlineShed(GraphNode node, long elapsedNs) {
        if (elapsedNs > FormulaCompute.sliceNs()) {
            if (++node.formulaDeadlineStreak >= DEADLINE_FREEZE_TICKS && !node.formulaShedWarned) {
                node.formulaShedWarned = true;
                node.formulaCarrier = null;
                node.formulaSpreadProgress = 0f;
                LOGGER.warn("FORMULA node {} outlived the budget deadline {} ticks in a row — "
                    + "pathological closed-form script, shed to lastGood until edited",
                    node.id, node.formulaDeadlineStreak);
            }
        } else {
            node.formulaDeadlineStreak = 0;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(FormulaEval.class);

    /**
     * K6 deadline 兜底冻结阈值：节点**自身**超份额（本次执行墙钟 > slice）连续达到该 tick 数
     * 即判「永完不成的闭式巨脚本」，走 MAX_ITER 同款冻结（告警一次、清 carrier、不再重试直到
     * 公式被编辑）。20 tick ≈ 1 秒（20 Hz）。
     * K6 deadline-backstop freeze threshold: once a node that **itself** outlived its own slice
     * (exec wall clock > slice) accumulates this many consecutive ticks, it is judged a
     * never-finishing closed-form monster and takes the MAX_ITER freeze (warn once, clear the
     * carrier, no retries until the formula is edited). 20 ticks ≈ 1 s at 20 Hz.
     */
    private static final int DEADLINE_FREEZE_TICKS = 20;

    /** 求值数学/三角/逻辑/控制域节点（case 体逐字搬迁，见类注释）。方法开头放局部别名块
     *  （接收者改写），让 case 体一个字都不用改；结尾 {@code ctx.o = o} 回写输出数组（通用契约）。
     *  Evaluate a math / trig / logic / control node (case bodies relocated verbatim, see
     *  the class doc). The method opens with local aliases (receiver rewrite) so the case
     *  bodies stay untouched; it ends with a {@code ctx.o = o} write-back (the general contract).
     *
     * @param ctx 求值上下文（每调用/每节点槽已由调用方填好）/ evaluation context
     *            (per-call / per-node slots already filled by the caller)
     */
    static void eval(EvalContext ctx) {
        // 方法内局部别名（接收者改写）：case 体逐字保留 / local aliases (receiver rewrite):
        // the case bodies stay verbatim
        GraphNode node = ctx.node;
        float[] o = ctx.o;
        Map<Integer, Float> pidState = ctx.pidState;
        float dt = ctx.dt;
        NodeGraph graph = ctx.graph;
        Map<Integer, float[]> outputs = ctx.outputs;
        switch (node.type) {
            case RELAY_A -> {
                float a = graph.getInputValue(node.id, 0, outputs);
                float b = graph.getInputValue(node.id, 1, outputs);
                boolean c = graph.getInputValue(node.id, 2, outputs) > 0.5f;
                // SPDT: contact false → A throw connected; contact true → B throw connected; disconnected side = 0
                o[0] = c ? 0f : a;  // A输出
                o[1] = c ? b : 0f;  // B输出
            }
            case RELAY_B -> {
                float a = graph.getInputValue(node.id, 0, outputs);
                float b = graph.getInputValue(node.id, 1, outputs);
                boolean c = graph.getInputValue(node.id, 2, outputs) > 0.5f;
                o[0] = c ? b : a;   // 输出 = selected source
            }
            case ADD -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = Float.isFinite(a) && Float.isFinite(b) ? a + b : 0; }
            case SUB -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = Float.isFinite(a) && Float.isFinite(b) ? a - b : 0; }
            case MUL -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = Float.isFinite(a) && Float.isFinite(b) ? a * b : 0; }
            case DIV -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = (b != 0 && Float.isFinite(a) && Float.isFinite(b)) ? a / b : 0; }
            case MOD -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = (b != 0 && Float.isFinite(a) && Float.isFinite(b)) ? a % b : 0; }
            case POW -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); float r = (float) Math.pow(Math.abs(a), b); o[0] = Float.isFinite(r) ? r : 0; }
            case ROOT -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = (b != 0 && a >= 0) ? (float) Math.pow(a, 1.0 / b) : 0; }
            case ABS -> o[0] = Math.abs(graph.getInputValue(node.id, 0, outputs));
            case ROUND -> {
                float val = graph.getInputValue(node.id, 0, outputs);
                int decimals = node.params.length > 0 ? Math.max(0, (int)node.params[0]) : 2;
                double pow10 = Math.pow(10, decimals);
                o[0] = (float)(Math.round(val * pow10) / pow10);
            }
            case SIN -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) ? (float) Math.sin(Math.toRadians(v)) : 0;
            }
            case COS -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) ? (float) Math.cos(Math.toRadians(v)) : 0;
            }
            case TAN -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) ? (float) Math.tan(Math.toRadians(v)) : 0;
            }
            case ASIN -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = (Float.isFinite(v) && v >= -1 && v <= 1) ? (float) Math.toDegrees(Math.asin(v)) : 0;
            }
            case ACOS -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = (Float.isFinite(v) && v >= -1 && v <= 1) ? (float) Math.toDegrees(Math.acos(v)) : 0;
            }
            case ATAN2 -> {
                float y = graph.getInputValue(node.id, 0, outputs);
                float x = graph.getInputValue(node.id, 1, outputs);
                o[0] = (Float.isFinite(y) && Float.isFinite(x)) ? (float) Math.toDegrees(Math.atan2(y, x)) : 0;
            }
            case SINH -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) ? (float) Math.sinh(v) : 0;
            }
            case COSH -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) ? (float) Math.cosh(v) : 0;
            }
            case SQRT -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) && v >= 0 ? (float) Math.sqrt(v) : 0;
            }
            case LN -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) && v > 0 ? (float) Math.log(v) : 0;
            }
            case LOG -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) && v > 0 ? (float) Math.log10(v) : 0;
            }
            case EXP -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) ? (float) Math.exp(v) : 0;
            }
            case SEC -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = (Float.isFinite(v) && Math.abs(Math.cos(Math.toRadians(v))) > 1e-12f)
                    ? (float)(1.0 / Math.cos(Math.toRadians(v))) : 0;
            }
            case CSC -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = (Float.isFinite(v) && Math.abs(Math.sin(Math.toRadians(v))) > 1e-12f)
                    ? (float)(1.0 / Math.sin(Math.toRadians(v))) : 0;
            }
            case COT -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = (Float.isFinite(v) && Math.abs(Math.tan(Math.toRadians(v))) > 1e-12f)
                    ? (float)(1.0 / Math.tan(Math.toRadians(v))) : 0;
            }
            case ANGLE_UNWRAP -> {
                // 双角度比对（无状态）：输出 = a-b 差角折进所选区间——mode 0 折入
                // (-180,180]（双向有号差角），mode 1 折入 [0,360)（0~360 正向角）。
                // 不带逐 tick 累计（旧累计输出会一路涨到几千度，比对不稳定）。
                // Two-angle compare (stateless): the output is the a-b delta folded into
                // the picked range — mode 0 into (-180,180] (signed shortest delta),
                // mode 1 into [0,360) (0~360 forward delta). No per-tick accumulation
                // (the old accumulator ran away into the thousands and compared unstably).
                float a = graph.getInputValue(node.id, 0, outputs);
                float b = graph.getInputValue(node.id, 1, outputs);
                float mode = node.params.length > 0 ? node.params[0] : 0f;
                float diff = a - b;
                if (mode > 0.5f) {
                    while (diff < 0f) diff += 360f;
                    while (diff >= 360f) diff -= 360f;
                } else {
                    while (diff > 180f) diff -= 360f;
                    while (diff < -180f) diff += 360f;
                }
                o[0] = diff;
            }
            case DIRECTION -> {
                float ax = node.params.length > 0 ? node.params[0] : 0;
                float ay = node.params.length > 1 ? node.params[1] : 0;
                float az = node.params.length > 2 ? node.params[2] : 0;
                float bx = node.params.length > 3 ? node.params[3] : 0;
                float by = node.params.length > 4 ? node.params[4] : 0;
                float bz = node.params.length > 5 ? node.params[5] : 0;
                double dx = bx - ax, dy = by - ay, dz = bz - az;
                if (dx == 0 && dy == 0 && dz == 0) { o[0] = 0; o[1] = 0; o[2] = 0; break; }
                double h = Math.sqrt(dx * dx + dz * dz);
                double yaw = Math.toDegrees(Math.atan2(dx, -dz));
                yaw = (yaw + 360) % 360;
                double pitch = Math.toDegrees(Math.atan2(-dy, h));
                o[0] = (float) yaw;
                o[1] = (float) pitch;
                o[2] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            case INTERP -> {
                float a = graph.getInputValue(node.id, 0, outputs);
                float b = graph.getInputValue(node.id, 1, outputs);
                if (!Float.isFinite(a) || !Float.isFinite(b)) { o[0] = 0; o[1] = 0; }
                else if (a >= b) { o[0] = a - b; o[1] = 0; }
                else { o[0] = 0; o[1] = Math.abs(b - a); }
            }
            case CEIL -> o[0] = (float) Math.ceil(graph.getInputValue(node.id, 0, outputs));
            case FLOOR -> o[0] = (float) Math.floor(graph.getInputValue(node.id, 0, outputs));
            case OR -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = (a > 0.5f || b > 0.5f) ? 1 : 0; }
            case AND -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = (a > 0.5f && b > 0.5f) ? 1 : 0; }
            case BOOL -> {
                float vin = graph.getInputValue(node.id, 0, outputs);
                o[0] = vin > 0 ? 1 : 0;
                if (node.params.length > 0 && node.params[0] > 0.5f) o[0] = 1 - o[0];
            }
            case GT -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a > b ? 1 : 0; }
            case LT -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a < b ? 1 : 0; }
            case GE -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a >= b ? 1 : 0; }
            case LE -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a <= b ? 1 : 0; }
            case EQ -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = Math.abs(a - b) < 0.01f ? 1 : 0; }
            case PID -> {
                float sp = graph.getInputValue(node.id, 0, outputs);
                float pv = graph.getInputValue(node.id, 1, outputs);
                if (Float.isNaN(sp) || !Float.isFinite(sp)) sp = 0;
                if (Float.isNaN(pv) || !Float.isFinite(pv)) pv = 0;
                float kp = node.params.length > 0 ? node.params[0] : 1.0f;
                float ki = node.params.length > 1 ? node.params[1] : 0.1f;
                float kd = node.params.length > 2 ? node.params[2] : 0.05f;
                float s = node.params.length > 3 ? node.params[3] : 1.0f;
                float ilimit = node.params.length > 4 ? node.params[4] : 3.0f;
                // 积分死区：|err|≤deadband 时积分**保持**（不增长也不清除）。
                // 默认 0.001 —— 取代旧的「|err|≤0.001 清零」。
                // Integral deadband: when |err| ≤ deadband the integral **holds**
                // (neither grows nor clears). Default 0.001 — replaces the old
                // "clear when |err| ≤ 0.001".
                float deadband = node.params.length > 5 ? node.params[5] : 0.001f;
                if (!Float.isFinite(deadband) || deadband < 0) deadband = 0;
                float err = sp - pv;
                int ik = node.id;
                float integral = pidState.getOrDefault(ik, 0f);
                if (Math.abs(err) > deadband) integral += err * dt;
                // else: hold — 不增长也不清除 / neither grow nor clear
                // ilimit 直接限制 I 项输出贡献  /  ilimit directly clamps I-term output contribution
                float iContrib = ki * integral;
                if (iContrib > ilimit) iContrib = ilimit;
                if (iContrib < -ilimit) iContrib = -ilimit;
                // 抗饱和：同时限制原始积分  /  Anti-windup: also clamp the raw integral
                float iCap = ilimit / Math.max(ki, 0.001f);
                if (integral > iCap) integral = iCap;
                if (integral < -iCap) integral = -iCap;
                pidState.put(ik, integral);
                // D 项：误差变化率  /  D term: rate of error change
                int prevErrKey = node.id + 300000;
                float prevErr = pidState.getOrDefault(prevErrKey, 0f);
                float derivative = dt > 0 ? (err - prevErr) / dt : 0;
                pidState.put(prevErrKey, err);
                o[0] = (kp * err + iContrib + kd * derivative) * s;
            }
            case PID_POWER -> {
                float sp = graph.getInputValue(node.id, 0, outputs);
                float pv = graph.getInputValue(node.id, 1, outputs);
                float base = graph.getInputValue(node.id, 2, outputs);
                if (Float.isNaN(sp) || !Float.isFinite(sp)) sp = 0;
                if (Float.isNaN(pv) || !Float.isFinite(pv)) pv = 0;
                if (Float.isNaN(base) || !Float.isFinite(base)) base = 0;
                float kp = node.params.length > 0 ? node.params[0] : 1.0f;
                float ki = node.params.length > 1 ? node.params[1] : 0.1f;
                float kd = node.params.length > 2 ? node.params[2] : 0.05f;
                float ilimit = node.params.length > 3 ? node.params[3] : 3.0f;
                // 同 PID：死区内积分保持 / same as PID: integral holds inside the deadband
                float deadband = node.params.length > 4 ? node.params[4] : 0.001f;
                if (!Float.isFinite(deadband) || deadband < 0) deadband = 0;
                float err = sp - pv;
                int ik = node.id;
                float integral = pidState.getOrDefault(ik, 0f);
                if (Math.abs(err) > deadband) integral += err * dt;
                // ilimit 直接限制 I 项输出贡献  /  ilimit directly clamps I-term output contribution
                float iContrib = ki * integral;
                if (iContrib > ilimit) iContrib = ilimit;
                if (iContrib < -ilimit) iContrib = -ilimit;
                float iCap = ilimit / Math.max(ki, 0.001f);
                if (integral > iCap) integral = iCap;
                if (integral < -iCap) integral = -iCap;
                pidState.put(ik, integral);
                // D 项  /  D term
                int prevErrKey = node.id + 300000;
                float prevErr = pidState.getOrDefault(prevErrKey, 0f);
                float derivative = dt > 0 ? (err - prevErr) / dt : 0;
                pidState.put(prevErrKey, err);
                o[0] = base + kp * err + iContrib + kd * derivative;
            }
            case CLAMP -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                if (Float.isNaN(v) || !Float.isFinite(v)) v = 0;
                float mn = graph.getInputValueOrDefault(node.id, 1, outputs,
                    node.params.length > 0 ? node.params[0] : 0);
                float mx = graph.getInputValueOrDefault(node.id, 2, outputs,
                    node.params.length > 1 ? node.params[1] : 0);
                o[0] = Math.max(mn, Math.min(mx, v));
            }
            case MAP -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                float imn = graph.getInputValueOrDefault(node.id, 1, outputs,
                    node.params.length > 0 ? node.params[0] : 0);
                float imx = graph.getInputValueOrDefault(node.id, 2, outputs,
                    node.params.length > 1 ? node.params[1] : 0);
                float omn = graph.getInputValueOrDefault(node.id, 3, outputs,
                    node.params.length > 2 ? node.params[2] : 0);
                float omx = graph.getInputValueOrDefault(node.id, 4, outputs,
                    node.params.length > 3 ? node.params[3] : 0);
                if (!Float.isFinite(v) || !Float.isFinite(imn) || !Float.isFinite(imx) || !Float.isFinite(omn) || !Float.isFinite(omx)) { o[0] = 0; }
                else { float r = imx - imn; o[0] = r == 0 ? omn : omn + (v - imn) / r * (omx - omn); }
            }
            case FORMULA -> {
                if (node.formula.isEmpty()) {
                    int nOut = Math.max(1, node.dynamicOutputCount);
                    if (o.length != nOut) { o = new float[nOut]; node.outputValues = o; }
                    for (int i = 0; i < nOut; i++) o[i] = 0;
                    break;
                }
                // Single source of truth: node.cachedScript, freshness-guarded by
                // ensureScriptParsed (方案 A). This is the same cache used by
                // inputPinIndex / outputPinIndex for connection resolution —
                // no more dual-cache drift.
                // 唯一真相源：node.cachedScript，由 ensureScriptParsed（方案 A）保护新鲜度。
                // 与连线解析 inputPinIndex/outputPinIndex 使用同一缓存 —— 不再有双缓存漂移。
                node.ensureScriptParsed();
                var script = node.cachedScript;
                // 更新节点状态 / Update node state
                int nOut = Math.max(1, node.dynamicOutputCount);
                node.outputLabels = node.outputLabels; // already set by ensureScriptParsed
                // 动态调整输出数组大小（与 ENCAPSULATION / BUS_IN 相同的模式）
                // Size output array dynamically (same pattern as ENCAPSULATION / BUS_IN)
                if (o.length != nOut) { o = new float[nOut]; node.outputValues = o; }
                // 输入快照 — 去重键按值比较 / Input snapshot — dedup key compares by value
                float[] pinInputs = new float[script.inputVars.size()];
                for (int vi = 0; vi < script.inputVars.size(); vi++) {
                    pinInputs[vi] = graph.getInputValue(node.id, vi, outputs);
                }
                // 去重:同脚本 + 同输入本 tick 内复用结果(FORMULA 为纯函数,安全)。
                // Dedup: same script + same inputs reuses this tick's result (FORMULA is a pure function, safe).
                float[] cached = FormulaCompute.lookupDedup(script.sourceFormula, pinInputs);
                if (cached != null) {
                    // 缓存结果复制出(缓存内部数组只读);长度不匹配的余项归零(legacy dout 差异)
                    // Copy out the cached result (cache array is read-only); zero any remainder on length mismatch (legacy dout)
                    java.util.Arrays.fill(o, 0f);
                    System.arraycopy(cached, 0, o, 0, Math.min(cached.length, o.length));
                    // 去重命中:本节点不推进自己的 spread,清 carrier 与渲染态(值由缓存保证一致)
                    // Dedup hit: this node doesn't advance its own spread — clear carrier + render state (values stay consistent via cache)
                    node.formulaCarrier = null;
                    node.formulaSpreadProgress = 0f;
                    break;
                }
                // 统一 AST 路径（双前端退役 2026-10-31）：carrier 挂起-lite + emit-on-done；
                // 无控制流脚本随批升格，旧扁平 RPN 求值循环已删除。
                // Unified AST path (dual-frontend retirement, 2026-10-31): carrier
                // suspend-lite + emit-on-done; non-control-flow scripts promoted along,
                // the flat-RPN eval loop is gone.
                if (script.ast != null) {
                    if (node.formulaShedWarned) {
                        // MAX_ITER shed 冻结:病态脚本不再重试,输出保持 lastGood,直到公式被编辑
                        // MAX_ITER shed freeze: pathological script retries no more; outputs hold lastGood until the formula is edited
                        node.formulaSpreadProgress = 0f;
                        break;
                    }
                    var env = new java.util.HashMap<String, Value>(Math.max(4, script.inputVars.size() * 2));
                    boolean warm = node.params.length > 0 && node.params[0] > 0.5f; // 温启动节点参数 / warm-restart node param
                    var car = node.formulaCarrier;
                    float[] usedFrozen = null;

                    if (car != null && car.done) {
                        if (EvalContext.inputsUnchanged(car.frozenInputs, pinInputs)) {
                            // done 且输入未变:整节点跳过(输出保持)/ done & inputs unchanged: skip, outputs hold
                            node.formulaSpreadProgress = 0f;
                            break;
                        }
                        node.formulaCarrier = null; // done 冷复位(决策 §五)/ done → cold reset
                        car = null;
                    }
                    if (car != null) {
                        // spread 中 / mid-spread
                        if (EvalContext.inputsUnchanged(car.frozenInputs, pinInputs)) {
                            env.putAll(car.envSnapshot); // 续算 / resume
                            usedFrozen = car.frozenInputs;
                        } else if (warm) {
                            // 温启动 = **继续迭代**(2026-08-15 联调决策,修订决策账 §五「k=0 重跑」):
                            // 保留当前循环进度 k,刷新输入变量与冻结快照,求解器继续朝新目标迭代——
                            // 进度条不重置、定长 repeat 完成剩余迭代即输出;信号发生器等持续变化输入
                            // 不再每个 tick 重启。
                            // Warm = **continue iterating** (session decision 2026-08-15, revising §五 "k=0 restart"):
                            // keep the current loop progress k, refresh the input vars and the freeze snapshot —
                            // the solver keeps iterating toward the new target; the bar never resets, fixed-count
                            // repeats finish their remaining iterations, and continuously-changing inputs
                            // (signal generator etc.) no longer restart every tick.
                            env.putAll(car.envSnapshot);
                            for (int vi = 0; vi < script.inputVars.size(); vi++)
                                env.put(script.inputVars.get(vi), new Value.Scalar(pinInputs[vi]));
                            usedFrozen = pinInputs.clone();
                        } else {
                            // 严格冻结:继续旧输入快照(默认)/ strict freeze: continue the old snapshot (default)
                            env.putAll(car.envSnapshot);
                            usedFrozen = car.frozenInputs;
                        }
                    }
                    if (car == null) {
                        // 全新执行(冷):env 来自引脚 / fresh (cold): env from pins
                        for (int vi = 0; vi < script.inputVars.size(); vi++)
                            env.put(script.inputVars.get(vi), new Value.Scalar(pinInputs[vi]));
                        usedFrozen = pinInputs.clone();
                    }
                    final float[] frozenRef = usedFrozen;
                    // K7：去重缓存只收「可信」结果 —— 成功与挂起（冻结）照常缓存，异常
                    // （catch）路径绝不写缓存：错误结果的全零数组会把同脚本同输入的兄弟
                    // 节点判成"本 tick 已算"，让它免算吃到零值。
                    // K7: the dedup cache only takes trustworthy results — success and
                    // suspend (frozen) are cached as before, the exception (catch) path never
                    // writes: an all-zero error result would mark a sibling node with the
                    // same script+inputs as "already computed this tick" and feed it zeros.
                    boolean dedupCacheable = true;
                    final long execStartNs = System.nanoTime(); // K6 公平规则计时 / K6 fairness-rule clock
                    try {
                        FormulaInterpreter.exec(script.ast, env, FormulaCompute.sliceNs(),
                            car != null ? car.loopStack : null, car != null ? car.totalIterations : 0);
                        // 完成:done + 输出新鲜值 / done: fresh outputs
                        var done = new FormulaInterpreter.Carrier();
                        done.done = true;
                        done.frozenInputs = frozenRef;
                        node.formulaCarrier = done;
                        node.formulaSpreadProgress = 0f;
                        node.formulaDeadlineStreak = 0; // 完成即有救,清连续超时 / completing means recoverable, reset the streak
                        for (int oi = 0; oi < nOut; oi++) {
                            try {
                                var rpn = script.outputRpns.get(oi);
                                o[oi] = (float)FormulaParser.asScalar(FormulaParser.evaluateValue(
                                    oi < script.outputRpns.size() ? rpn : java.util.List.of(0.0), env));
                            } catch (FormulaParser.DeadlineShedException d) {
                                // K6:输出阶段撞 deadline——该输出保持(不写 0),done 态整体作废
                                //(下 tick 全量重试,闲时补全),结果不进 dedup(部分输出不毒化同脚本兄弟);
                                // 处置同样走公平规则。
                                // K6: the output phase hit the deadline — this output holds (never
                                // zeroed), the done state is discarded wholesale (full retry next
                                // tick, finishes on a quieter one) and the result never enters the
                                // dedup cache (a partial output must not poison same-script
                                // siblings); the fairness rule applies all the same.
                                node.formulaCarrier = null;
                                dedupCacheable = false;
                                LOGGER.debug("FORMULA AST output idx={} suspended by budget deadline (node {})", oi, node.id);
                                noteDeadlineShed(node, System.nanoTime() - execStartNs);
                            } catch (Exception e) {
                                LOGGER.debug("FORMULA AST output error idx={}: {}", oi, e.getMessage());
                                o[oi] = 0;
                            }
                        }
                    } catch (FormulaInterpreter.SuspendSignal sus) {
                        if (sus.fromDeadline) {
                            // K6:deadline 挂起——carrier 续存(下 tick 续算)、输出保持、**不计 yield**
                            //(闭式表达式无法协作让路,计入只会挤压循环份额);处置走公平规则。
                            // K6: deadline suspend — carrier kept (resume next tick), outputs hold,
                            // and it does NOT count as a yield (a closed-form expression cannot
                            // cooperatively yield; counting it would only starve the loops); the
                            // fairness rule disposes.
                            sus.carrier.frozenInputs = frozenRef;
                            node.formulaCarrier = sus.carrier;
                            node.formulaSpreadProgress = sus.carrier.progress;
                            noteDeadlineShed(node, System.nanoTime() - execStartNs);
                        } else {
                            // 挂起:carrier 续存,输出冻结(emit-on-done)/ suspend: keep carrier, outputs frozen
                            sus.carrier.frozenInputs = frozenRef;
                            node.formulaCarrier = sus.carrier;
                            node.formulaSpreadProgress = sus.carrier.progress;
                            FormulaCompute.reportYield();
                            node.formulaDeadlineStreak = 0; // 正常推进,清连续超时 / regular progress, reset the streak
                        }
                    } catch (FormulaInterpreter.ShedSignal sh) {
                        // MAX_ITER 兜底:清 carrier、输出冻结、告警一次 / MAX_ITER backstop
                        node.formulaCarrier = null;
                        node.formulaSpreadProgress = 0f;
                        if (!node.formulaShedWarned) {
                            node.formulaShedWarned = true;
                            LOGGER.warn("FORMULA node {} exceeded MAX_ITER — pathological script, shed to lastGood", node.id);
                        }
                    } catch (Exception e) {
                        LOGGER.debug("FORMULA AST exec error: {}", e.getMessage());
                        java.util.Arrays.fill(o, 0f);
                        node.formulaCarrier = null;
                        node.formulaSpreadProgress = 0f;
                        dedupCacheable = false; // 异常结果不进缓存 / error results never enter the cache
                    }
                    if (dedupCacheable)
                        FormulaCompute.storeDedup(script.sourceFormula, pinInputs, o);
                    break;
                }
                // 降级形态（深度守卫放弃）：输出恒 0 + 去重照记（双前端退役后 ast==null
                // 仅存此形；空公式已在上方早退）。
                // Degraded shape (depth-guard abandonment): outputs pinned to 0 with the
                // dedup cache still recording (after the dual-frontend retirement ast==null
                // is only this shape; empty formulas early out above).
                java.util.Arrays.fill(o, 0f);
                FormulaCompute.storeDedup(script.sourceFormula, pinInputs, o);
            }
        }
        ctx.o = o;
    }
}
