package io.github.y15173334444.create_schematic_compute.network;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BUS 频道条目 — 由 SignalBus.CHANNELS 管理。
 * 共享模型：所有同名 BUS_OUT（可跨方块）作为 participant 加入同一频道，
 * 共同写入共享的 {@code busMap}（按频段名），BUS_IN 读取共享 map。
 * <p>
 * Shared-model BUS channel entry. All same-name BUS_OUT nodes (possibly across
 * blocks) join as participants writing into one shared {@code busMap} keyed by
 * band name; BUS_IN reads the shared map. refCount equals participant count.
 */
public class ChannelEntry {
    /** 共享频道数据（bandName → 值），所有 participant 共同写入，BUS_IN 读取。 */
    private final Map<String, Float> busMap = new ConcurrentHashMap<>();
    /** 参与者集合（拥有该频道的所有 BUS_OUT 节点）。 */
    private final Set<ChannelOwner> participants = ConcurrentHashMap.newKeySet();

    public ChannelEntry() {}

    /** 共享频道数据映射（bandName → value），BUS_IN 直接读取。 */
    public Map<String, Float> busMap() { return busMap; }

    /** 参与者数量（= 引用计数）。 */
    public int refCount() { return participants.size(); }

    /** 频道是否无参与者（可清理）。 */
    public boolean isEmpty() { return participants.isEmpty(); }

    /** 加入频道（幂等）。 */
    public boolean addParticipant(ChannelOwner owner) { return participants.add(owner); }

    /** 移除参与者；可选地清理该参与者先前写入的频段（当频段定义不再属于它时）。 */
    public boolean removeParticipant(ChannelOwner owner, List<String> bands) {
        boolean removed = participants.remove(owner);
        if (removed && bands != null) {
            // 若该 participant 是唯一拥有这些频段名的写者，移除后清空对应键，
            // 避免残留值干扰其他写者。实际清理由调用方按需处理（保留简单性）。
        }
        return removed;
    }
}
