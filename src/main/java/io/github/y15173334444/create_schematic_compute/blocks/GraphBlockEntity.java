package io.github.y15173334444.create_schematic_compute.blocks;

/** Common interface for all block entities that host a node graph.
 *  Replaces instanceof chains in BlueprintSavePacket and BlueprintTogglePacket. */
import java.util.Map;

public interface GraphBlockEntity {
    void loadGraphFromBytes(byte[] data);
    default boolean isRunning() { return false; }
    default void setRunning(boolean running) {}
    default boolean graphHasCycles() { return false; }
    default void clearPidState() {}
    /** 服务端→客户端：同步 flipflopStates 用于 UI 实时显示 */
    default void syncFlipflopStates(Map<Integer, Boolean> states) {}
    /** 服务端→客户端：同步子图 flipflopStates 用于 UI 实时显示 */
    default void syncSubFlipflopStates(Map<Integer, Map<Integer, Boolean>> subStates) {}
    /** 服务端→客户端：同步 BUS band 列表到本地图节点 */
    default void syncBusBandsFromServer(String busName, java.util.List<String> bands) {}
    /** 暴露节点图供 packet handler 使用 */
    default io.github.y15173334444.create_schematic_compute.graph.NodeGraph getNodeGraph() { return null; }

    // ── 组合式托管（ProgrammableGearbox 等 Kinetic 线 BE）所需的最小成员面。
    //    全部带默认实现，存量 7 个 SyncedGraphBlockEntity 子类不受影响；
    //    抽象基类覆写为字段桥接。 ──

    /** 本地未 ACK 编辑 op 计数（客户端回弹保护）。/ Pending un-ACKed local edit ops (client bounce guard). */
    default int getPendingLocalOps() { return 0; }
    default void setPendingLocalOps(int value) {}

    /** 最近一次服务端求值快照（客户端渲染读取）。/ Latest server eval snapshot (read by client rendering). */
    default io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot getCachedEvalSnapshot() {
        return io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot.EMPTY;
    }
    default void setCachedEvalSnapshot(io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot snapshot) {}

    /** 强制下次 getUpdateTag 携带完整图（编辑会话加入时调用）。/ Force full-graph sync on next update tag. */
    default void flagFullSync() {}
    /** 合并版全量同步请求（高频显示 op 用，按 tick 冲刷）。/ Coalesced full-sync request, flushed on tick. */
    default void requestFullSync() {}

    /** 客户端是否已从服务端收到图数据。/ True once the client received the graph from the server. */
    default boolean isGraphReady() { return true; }

    /** 读取指定封装节点的子图 flipflop 状态（GraphEditor 渲染徽标用）。
     *  Sub-graph flipflop states of an encapsulation node (for GraphEditor badges). */
    default Map<Integer, Boolean> peekSubStateFlipflops(int encapNodeId) { return java.util.Collections.emptyMap(); }
}
