package com.example.create_schematic_compute.blocks;

/** Common interface for all block entities that host a node graph.
 *  Replaces instanceof chains in BlueprintSavePacket and BlueprintTogglePacket. */
public interface GraphBlockEntity {
    void loadGraphFromBytes(byte[] data);
    default boolean isRunning() { return false; }
    default void setRunning(boolean running) {}
    default boolean graphHasCycles() { return false; }
    default void clearPidState() {}
}
