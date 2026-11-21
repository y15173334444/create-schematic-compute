package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.NodeAllowance;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;

import java.util.Map;

/**
 * 泛用屏的宿主窄视图（宿主套件波 2，反向包边纪律同族）：泛用屏与开启器消费宿主
 * 数据面所经的窄口 —— 不回引 blocks（client/editor 包边纪律），签名全为图域类型 +
 * JDK 共享类型（与 {@link EditStateView} 同族，跨加载器安全）。宿主套件适配器实现
 * 本口；解析经 graph 域的 {@code EditSessionTargets.resolve}（窄口与登记同域）。
 * The generic screen's host narrow view (host suite, wave 2, the reverse package-edge
 * discipline family): the port through which the generic screen and opener consume the
 * host's data surface — never reaching back into blocks (the client/editor package-edge
 * rule), with signatures all graph-domain + JDK shared types (the {@link EditStateView}
 * family, cross-loader safe). The host-suite adapter implements this port; resolution
 * rides the graph-domain {@code EditSessionTargets.resolve} (the port and the registry
 * share a domain).
 */
public interface HostGraphView extends EditStateView {

    /** 当前图（可空）/ the current graph (nullable). */
    NodeGraph getNodeGraph();

    /** 求值器是否运行 / whether the evaluator is running. */
    boolean isRunning();

    /** 设置运行态（本地乐观写；权威切换另走 toggle 包）/ set the running state (an
     *  optimistic local write; the authoritative toggle rides the packet). */
    void setRunning(boolean running);

    /** 最近一次服务端求值快照 / the latest server eval snapshot. */
    EvalSnapshot getCachedEvalSnapshot();

    /** 主图 flipflop 状态只读视图 / read-only view of the main-graph flipflop states. */
    Map<Integer, Boolean> getFlipflopStates();

    /** 宿主准入名单（客户端菜单过滤，与服务端准入门同源）/ the host's node allowance
     *  (the client menu filter; same source as the server admission gate). */
    NodeAllowance nodeAllowance();
}
