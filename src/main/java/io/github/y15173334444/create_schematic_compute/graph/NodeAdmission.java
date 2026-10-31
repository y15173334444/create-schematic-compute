package io.github.y15173334444.create_schematic_compute.graph;

import java.util.EnumSet;
import java.util.Set;

/**
 * 服务端 op 链的节点准入门（P2-7 准入服务端化 + K10 数量上限服务端化，2026-10-31 拍板）。
 * {@code BlockNodeAllowances} 的名单纪律此前只有客户端 Screen 引用（菜单过滤），改包客户端
 * 可经 ADD_NODE op 把任意类型塞进任意宿主；本门在服务端创建节点前做同一判定。
 * The server-side node admission gate (P2-7 admission server-side + K10 node-count cap
 * server-side, 2026-10-31 ruling). The {@code BlockNodeAllowances} discipline used to be
 * referenced only by the client Screens (menu filtering) — a modified client could push any
 * type into any host via an ADD_NODE op; this gate applies the same judgement on the server
 * before a node is created.
 *
 * <p><b>旧档兼容口径（拍板约束：装载时遗留集）</b>：各版本整理的各宿主节点筛选有差异，直接
 * 静态校验会让旧版本的图坏掉。放行集合 = <b>现行名单 ∪ 装载普查</b>——宿主图装载（NBT 加载 /
 * /data merge / Sable 复制，全部经 {@code GraphHost.loadHostNBT} 的图替换点）时对图树做一次
 * 类型普查，装载时已在场的类型本会话内永远可再进来。由此撤销恢复（undo of REMOVE_NODE 的
 * ADD_NODE + NBT 快照）必然放行：其类型要么在现行名单、要么在装载普查、要么是本会话已通过
 * 本门加入的类型。普查由引擎（{@code GraphHost}）在装载时记录，门只做集合算术——引擎管事实，
 * 门管政策；公开 API 节点波（D5 名单制）在本门上扩展字符串 id 名单。
 * <b>Legacy-save tolerance (ruled: load-time census)</b>: per-host node selections differ
 * across versions, and strictly checking against the current table would brick older saves.
 * The admitted set is <b>current allowlist ∪ load-time census</b> — when the host graph loads
 * (NBT load / /data merge / Sable copies, all through the graph-replacement site in
 * {@code GraphHost.loadHostNBT}) the graph tree is censused once, and types present at load
 * stay admissible for the session. Undo-restore (the ADD_NODE + NBT snapshot of a
 * REMOVE_NODE undo) therefore always passes: its type is in the current allowlist, in the
 * load census, or was itself admitted by this gate earlier in the session. The census is
 * recorded by the engine ({@code GraphHost}) at load; the gate only does set arithmetic —
 * the engine owns facts, the gate owns policy; the public-API node wave (D5 roster
 * admission) extends this gate with string-id rosters.</p>
 */
public final class NodeAdmission {

    private NodeAdmission() {}

    /** 每图节点数上限（主图与每个封装子图各自计数，K10 上限的权威值）。
     *  <p>Node cap per graph (the main graph and each encapsulated sub-graph count
     *  separately) — the authoritative K10 value; the client editor's MAX_NODES delegates
     *  here so both sides can never drift.</p> */
    public static final int MAX_NODES = 1024;

    /**
     * 装载时类型普查：主图与全部封装子图（递归）中实际出现的节点类型集合。
     * 在图替换点（{@code GraphHost.loadHostNBT}）调用一次并随引擎保存。
     * Load-time type census: the node types actually present across the main graph and
     * every encapsulated sub-graph (recursive). Called once at the graph-replacement site
     * ({@code GraphHost.loadHostNBT}) and kept by the engine.
     */
    public static EnumSet<NodeType> census(NodeGraph graph) {
        EnumSet<NodeType> out = EnumSet.noneOf(NodeType.class);
        collect(graph, out);
        return out;
    }

    private static void collect(NodeGraph g, EnumSet<NodeType> out) {
        if (g == null) return;
        for (GraphNode n : g.nodes) {
            if (n.type != null) out.add(n.type);
            collect(n.subGraph, out);
        }
    }

    /**
     * 准入违规描述（可读日志用），合法返回 null。仅审会创建节点的 ADD_NODE / ADD_NODE_REQUEST
     * （落类型的 op 就这两个）；其余 op 一律放行（null）。
     * <p>Admission violation description (for readable logs), null when legal. Only the
     * node-creating ADD_NODE / ADD_NODE_REQUEST are examined (the only type-landing ops);
     * everything else passes (null).</p>
     *
     * <p>三条判定，按序：</p>
     * <ol>
     *   <li><b>类型可解码</b>：线上 NodeType 按字符串 id 编码，未知 id 解码为 null——
     *       带着不可解码类型请求建点即拒（P2-9 未知 op 序号的同族口径）。</li>
     *   <li><b>类型获准入</b>：现行名单放行，或装载普查在场（旧档兼容口径，见类注）。
     *       名单缺席（{@code allowance == null}）按拒绝处理（fail-closed）——普查在场仍放行。</li>
     *   <li><b>容量</b>：目标图（主图或 ownerNodeId 定位的封装子图）节点数已达
     *       {@link #MAX_NODES} 即拒（K10 服务端化；客户端编辑器同值同口径）。</li>
     * </ol>
     * <p>Three checks, in order: (1) the type must decode (wire NodeTypes are string ids;
     * an unknown id decodes to null — refusing an undecodable add request is the same
     * family as P2-9's unknown op ordinals); (2) the type must be admitted by the current
     * allowlist or be present in the load census (legacy tolerance, see class doc) — a
     * missing allowlist ({@code allowance == null}) fails closed, the census still admits;
     * (3) capacity — the target graph (main or the encapsulation sub-graph located by
     * ownerNodeId) at {@link #MAX_NODES} refuses further adds (K10 server-side; the client
     * editor enforces the same value).</p>
     */
    public static String violation(GraphOp op, NodeAllowance allowance,
                                   Set<NodeType> loadTimeTypes, NodeGraph targetGraph) {
        // customNodeId 协议卫生（公开 API 波 1 批 B 审查 (b)2）：该字段只允许与
        // CUSTOM 哨兵同现 —— 其余 op 携带即拒（合法客户端从不发出）。先于「仅审建点」
        // 闸门：字段走私不限于建点 op。
        // customNodeId wire hygiene (public-API wave 1 batch B review (b)2): the field may
        // only ride the CUSTOM sentinel — its presence on any other op is refused (legit
        // clients never send it). Ahead of the add-only gate: field smuggling is not
        // limited to node-creating ops.
        boolean hasCustomId = op.customNodeId() != null && !op.customNodeId().isEmpty();
        if (hasCustomId && op.type() != OpType.ADD_NODE && op.type() != OpType.ADD_NODE_REQUEST)
            return "customNodeId on op " + op.type();
        if (op.type() != OpType.ADD_NODE && op.type() != OpType.ADD_NODE_REQUEST) return null;
        NodeType t = op.nodeType();
        if (t == null) return "nodeType=unknown";
        // CUSTOM 哨兵必须携带已注册 id（fail-closed）：未注册 id 即便靠装载普查混过
        // 名单关，也只会在执行器落地无人认领的占位壳 + 垃圾 cid 进档 —— 在门上拒。
        // The CUSTOM sentinel must carry a registered id (fail-closed): an unregistered id
        // would slip past the roster via the load census and land an unclaimed placeholder
        // shell plus a junk cid key in the save — refused at the gate.
        if (t == NodeType.CUSTOM) {
            if (!hasCustomId) return "customNodeId absent on CUSTOM";
            if (!CustomNodeRegistry.isRegistered(op.customNodeId()))
                return "customNodeId=" + op.customNodeId() + " not registered";
        } else if (hasCustomId) {
            // 建点 op 上非 CUSTOM 类型携带 customNodeId 同样拒（id 只属于哨兵）。
            // A customNodeId on a non-CUSTOM node-creating op is refused too (the id
            // belongs to the sentinel alone).
            return "customNodeId on non-CUSTOM nodeType " + t.id;
        }
        boolean admitted = (allowance != null && allowance.allows(t))
            || (loadTimeTypes != null && loadTimeTypes.contains(t));
        if (!admitted) return "nodeType=" + t.id + " not admitted";
        if (targetGraph != null && targetGraph.nodes.size() >= MAX_NODES)
            return "nodeCount=" + targetGraph.nodes.size() + " at cap " + MAX_NODES;
        return null;
    }
}
