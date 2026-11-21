package io.github.y15173334444.create_schematic_compute.graph;

/**
 * 求值器耗时表的真只读窄视图（条目 19① 收敛面）：只暴露 per-node EMA 纳秒读数与
 * 节点 id 清单，不交出求值器本体 —— 消费方（{@code /cscperf}）经图自行反查节点
 * 类型/自定义 id。签名全为原始类型与 int 数组，无任何可改写求值状态的通道；
 * 旧口径「返回活 {@link GraphEvaluator}、只读靠消费约定」的门就此关死。
 * A genuinely read-only narrow view of the evaluator cost table (the entry-19 ①
 * convergence): only per-node EMA nanosecond readings and the node-id list are
 * exposed, never the evaluator itself — consumers ({@code /cscperf}) resolve node
 * type / custom id through the graph. Signatures are primitives and int arrays
 * only; there is no channel that could mutate evaluation state. This closes the
 * old hole of handing out a live {@link GraphEvaluator} whose read-only nature was
 * merely a consumer convention.
 *
 * <p>实例随求值器生命周期存亡（图编辑重建即整表换血），视图不持独立状态——
 * 调用方在 tick 边界外读即可，无快照义务。</p>
 * <p>The view lives and dies with the evaluator (a graph edit rebuilds the table
 * wholesale); it holds no state of its own — read it outside tick boundaries, no
 * snapshot duty.</p>
 */
public interface NodePerfView {

    /** 表里是否已有任何记录（空图未跑 tick 时为 false）。
     *  Whether any record exists (false for an idle graph that never ticked). */
    boolean hasCosts();

    /** 已有记录的节点 id 清单（乱序、拷贝出新数组，调用方可自由改写副本）。
     *  The recorded node ids (unordered, freshly copied — the caller may mutate the
     *  copy freely). */
    int[] nodeIds();

    /** 节点的耗时 EMA（纳秒）；未记录的节点返回 0（正值 = 有记录，0 = 无数据）。
     *  The node's EMA evaluation cost in nanoseconds; unrecorded nodes return 0
     *  (positive = recorded, 0 = no data). */
    long nodeCostNs(int nodeId);
}
