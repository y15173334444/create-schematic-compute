package io.github.y15173334444.create_schematic_compute.network;

import java.util.Map;

/**
 * BUS 频道条目 — 由 SignalBus.CHANNELS 管理。
 * internalMap 是 BUS_OUT 节点 busInternalMap 的引用，求值时直接写入，BUS_IN 实时可见。
 */
public class ChannelEntry {
    /** BUS_OUT 的 busInternalMap 引用（写入立即可见） */
    public final Map<String, Float> internalMap;
    final ChannelOwner owner;
    private int refCount = 1;

    public ChannelEntry(Map<String, Float> internalMap, ChannelOwner owner) {
        this.internalMap = internalMap;
        this.owner = owner;
    }

    /** 返回当前引用计数 */
    public int refCount() { return refCount; }

    void incrementRef() { refCount++; }

    /** @return 递减后的引用计数 */
    int decrementRef() { return --refCount; }
}
