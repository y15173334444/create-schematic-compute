package com.example.create_schematic_compute.blocks;

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
    /** 服务端→客户端：同步 BUS band 列表到本地图节点 */
    default void syncBusBandsFromServer(String busName, java.util.List<String> bands) {}
    /** 暴露节点图供 packet handler 使用 */
    default com.example.create_schematic_compute.graph.NodeGraph getNodeGraph() { return null; }
}
