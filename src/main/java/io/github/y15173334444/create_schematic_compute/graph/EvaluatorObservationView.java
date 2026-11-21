package io.github.y15173334444.create_schematic_compute.graph;

import java.util.Map;

/**
 * 求值器观测面的真只读视图（known-open-issues 条目『观测面 evaluatorView 交出活求值器 +
 * 探针每 tick 装箱』①）。{@code GraphBlockEntity.evaluatorView()} 的静态返回类型收敛为
 * 本接口——{@link GraphEvaluator} 的 public 面含 {@code evaluate()} 重入与
 * {@code setSignalBus}/{@code setCustomLevel} 等端口注入改写口，「只读」靠约定不足以把守；
 * 宿主套件波（第三方宿主接口）落地后第三方拿到的是本窄口，不是求值器本体。
 * <p>The true read-only view of the evaluator's observation face (register entry
 * "evaluatorView hands out the live evaluator + per-tick probe boxing", part 1).
 * {@code GraphBlockEntity.evaluatorView()} narrows to this interface —
 * {@link GraphEvaluator}'s public face carries {@code evaluate()} re-entry and
 * {@code setSignalBus}/{@code setCustomLevel} port-injection mutators, which a
 * "read-only" convention cannot guard; once the host-suite wave lands third parties
 * receive this narrow port, not the evaluator itself.</p>
 *
 * <p>窄口住 {@code graph/} 复制域（条目 11 跨类加载器教训：观测面签名属于图域类型，
 * 与 {@code blocks.GraphBlockEntity} 同处复制域）。/ The port lives in the
 * {@code graph/} duplication domain (the cross-loader finding of register entry 11:
 * observation-face signatures are graph-domain types, sharing GraphBlockEntity's
 * duplication domain).</p>
 */
public interface EvaluatorObservationView {

    /** 每节点求值耗时 EMA 只读快照：键 = 节点 id，值 = 指数滑动平均 ns（首样播种，
     *  之后 7/8 旧 + 1/8 新）；随求值器存亡，图编辑整表换血（含已删节点的陈旧键）。
     *  <p>Read-only snapshot of the per-node evaluation-cost EMA: keyed by node id,
     *  values are exponential moving averages in ns (first sample seeds; afterwards
     *  7/8 old + 1/8 new); lives and dies with the evaluator — a graph edit rebuilds
     *  the table wholesale (stale keys of deleted nodes go with it).</p>
     *
     * <p>物化时点 = 本方法调用（指令/UI 频率），不在每 tick 热路径——热路径写侧是
     * 原始类型开放寻址表（同条目 ②）。/ Materialization happens at this call
     * (command/UI frequency), never on the per-tick hot path — the hot-path write side
     * is a primitive open-addressed table (part 2 of the same entry).</p> */
    Map<Integer, Long> nodeCostNsView();

    /** 节点标识解析（/cscperf 行标签）：CUSTOM 节点 = customId（modid:节点 id），
     *  内建节点 = 类型 id；解析不到（图里已删的陈旧键）= {@code "?"}。
     *  <p>Node identity resolution (the /cscperf row label): CUSTOM nodes carry their
     *  customId (modid:node id), built-ins carry the type id; unresolvable ids (stale
     *  keys of deleted nodes) resolve to {@code "?"}.</p> */
    String nodeLabel(int nodeId);
}
