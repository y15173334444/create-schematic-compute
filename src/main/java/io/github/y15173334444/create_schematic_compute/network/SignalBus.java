package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Global signal bus — transports float values by string name.
 *  <p>全局信号总线 — 通过字符串名称传输浮点数。</p>
 *  <ul>
 *  <li>{@link #SIGNALS} — flat key-value store used by PRIVATE_IN/OUT / PRIVATE_IN/OUT 使用的扁平键值存储</li>
 *  <li>{@link #CHANNELS} — channel registry for BUS_IN/OUT, holds BUS_OUT internalMap references + ref-counts / BUS_IN/OUT 使用的频道注册表，持有 BUS_OUT 的 internalMap 引用 + 引用计数</li>
 *  <li>{@link #BAND_REGISTRY} — BUS band-name list registry (cross-computer band definitions, for editor UI) / BUS 频段名列表注册表（跨计算机共享频段定义，用于编辑器 UI）</li>
 *  </ul>
 */
public class SignalBus {
    private static final ConcurrentHashMap<String, Float> SIGNALS = new ConcurrentHashMap<>();

    /** BUS channel registry: busName → ChannelEntry (holding BUS_OUT busInternalMap reference) / BUS 频道注册表：bus名 → ChannelEntry（持有 BUS_OUT 的 busInternalMap 引用） */
    private static final ConcurrentHashMap<String, ChannelEntry> CHANNELS = new ConcurrentHashMap<>();

    /** BUS band registry: busName → band name list (cross-computer shared band definitions) / BUS 频段注册表：bus名 → band名列表（跨计算机共享频段定义） */
    private static final ConcurrentHashMap<String, List<String>> BAND_REGISTRY = new ConcurrentHashMap<>();

    // ── PRIVATE_IN/OUT API (unchanged) / PRIVATE_IN/OUT API（不变） ──────────────────────

    public static void put(String channel, float value) {
        SIGNALS.put(channel, value);
    }

    public static float get(String channel) {
        return SIGNALS.getOrDefault(channel, 0f);
    }

    /** Clear a signal name (called when a PRIVATE_OUT node is destroyed, prevents SIGNALS map leak) / 清理指定信号名（PRIVATE_OUT 节点销毁时调用，防止 SIGNALS map 泄漏） */
    public static void clearSignal(String channel) {
        SIGNALS.remove(channel);
    }

    // ── BUS band-name sync API (unchanged) / BUS 频段名同步 API（不变） ──────────────────────

    /** Register BUS bands (called when BUS_OUT is edited) / 注册 BUS 频段（BUS_OUT 编辑时调用） */
    public static void registerBands(String busName, List<String> bands) {
        if (bands != null && !bands.isEmpty())
            BAND_REGISTRY.put(busName, new ArrayList<>(bands));
        else
            BAND_REGISTRY.remove(busName);
    }

    /** Get BUS band list / 获取 BUS 频段列表 */
    public static List<String> getBands(String busName) {
        return BAND_REGISTRY.get(busName);
    }

    // ── BUS channel registration API (new) / BUS 频道注册 API（新增） ──────────────────────

    /**
     * Register a BUS_OUT channel in the global table.
     * <p>注册一个 BUS_OUT 频道到全局表。</p>
     * <p>Same owner may re-register (e.g. tick re-entry): updates the internalMap
     * reference and increments the ref-count.
     * 同一 owner 可重复注册（如 tick 重入）：更新 internalMap 引用并递增引用计数。</p>
     * <p>Different owner with same channel name → conflict; prints WARN and returns false.
     * 不同 owner 使用相同频道名 → 冲突，打印 WARN 并返回 false。</p>
     *
     * @param channelName band name (signalName) / 频段名（signalName）
     * @param internalMap BUS_OUT node's busInternalMap reference / BUS_OUT 节点的 busInternalMap 引用
     * @param owner       channel owner identifier / 频道所有者标识
     * @return true on success, false if occupied by another owner / true 注册成功，false 被其他 owner 占用
     */
    public static boolean registerChannel(String channelName, Map<String, Float> internalMap, ChannelOwner owner) {
        ChannelEntry existing = CHANNELS.get(channelName);
        if (existing == null) {
            // EN: First registration — use putIfAbsent to prevent races
            // 首次注册 — 使用 putIfAbsent 防竞态
            ChannelEntry created = new ChannelEntry(internalMap, owner);
            ChannelEntry raced = CHANNELS.putIfAbsent(channelName, created);
            if (raced == null) {
                SchematicCompute.LOGGER.debug("[SignalBus] Channel '{}' registered by {}", channelName, owner);
                return true;
            }
            existing = raced; // EN: Lost the race, process according to existing entry / 竞态失败，按已有条目处理
        }
        // EN: Same owner → update reference and increment count
        // 同一 owner → 更新引用并增加计数
        if (existing.owner.equals(owner)) {
            existing.incrementRef();
            if (existing.internalMap != internalMap) {
                SchematicCompute.LOGGER.debug("[SignalBus] Channel '{}' map reference updated by {}", channelName, owner);
                // Replace the entry with the new map reference WITHOUT copying old values.
                // Old values belong to the previous graph state; each BUS_OUT starts fresh.
                // 用新 map 引用替换条目，不复制旧值。旧值属于之前的图状态，每个 BUS_OUT 重新开始。
                CHANNELS.put(channelName, new ChannelEntry(internalMap, owner));
            }
            return true;
        }
        // EN: Different owner → conflict
        // 不同 owner → 冲突
        SchematicCompute.LOGGER.warn("[SignalBus] Channel '{}' already owned by {} — rejected registration by {}",
            channelName, existing.owner, owner);
        return false;
    }

    /**
     * Unregister a BUS_OUT channel. Decrements the ref-count; auto-removes when it reaches zero.
     * <p>取消注册一个 BUS_OUT 频道。递减引用计数，归零时自动移除。</p>
     *
     * @param channelName band name / 频段名
     * @param owner       channel owner identifier (must match to unregister) / 频道所有者标识（必须匹配才能取消注册）
     * @return true if unregistered or channel not found, false if owner mismatch / true 取消注册成功或频道不存在，false owner 不匹配
     */
    public static boolean unregisterChannel(String channelName, ChannelOwner owner) {
        ChannelEntry existing = CHANNELS.get(channelName);
        if (existing == null) {
            SchematicCompute.LOGGER.debug("[SignalBus] Channel '{}' not found for unregistration by {}", channelName, owner);
            return false;
        }
        if (!existing.owner.equals(owner)) {
            SchematicCompute.LOGGER.warn("[SignalBus] Channel '{}' unregistration by {} rejected — owned by {}",
                channelName, owner, existing.owner);
            return false;
        }
        int remaining = existing.decrementRef();
        if (remaining <= 0) {
            SchematicCompute.LOGGER.debug("[SignalBus] Channel '{}' removed (refCount reached 0)", channelName);
            CHANNELS.remove(channelName, existing);
            // Clear residual signal data so the channel doesn't pollute the next registrant
            // 清除残留信号数据，防止频道污染下一个注册者
            clearBus(channelName);
        }
        return true;
    }

    /**
     * Update an existing channel's internalMap reference without changing the ref-count.
     * <p>更新现有频道的 internalMap 引用，不改变引用计数。</p>
     * <p>Used during evaluator recompile to refresh the Map reference for BUS_OUT nodes
     * that were already the channel owner, without the unregister/reregister cycle that
     * could allow a competing node to steal the channel.
     * 用于求值器重编译时刷新已是频道所有者的 BUS_OUT 节点的 Map 引用，
     * 避免取消注册/重新注册循环可能让竞争节点窃取频道。</p>
     *
     * @return true if the channel exists and is owned by the specified owner / 频道存在且属于指定所有者时返回 true
     */
    public static boolean updateChannel(String channelName, Map<String, Float> internalMap, ChannelOwner owner) {
        ChannelEntry existing = CHANNELS.get(channelName);
        if (existing == null || !existing.owner.equals(owner)) return false;
        if (existing.internalMap != internalMap) {
            // Replace the entry with the new map reference WITHOUT copying old values.
            // 用新 map 引用替换条目，不复制旧值。
            CHANNELS.put(channelName, new ChannelEntry(internalMap, owner));
        }
        return true;
    }

    /** Get a channel entry (for BUS_IN reading). Returns null if no active BUS_OUT. / 获取频道条目（供 BUS_IN 读取）。返回 null 表示没有活跃的 BUS_OUT。 */
    public static ChannelEntry getChannel(String channelName) {
        return CHANNELS.get(channelName);
    }

    // ── Cleanup API / 清理 API ──────────────────────────────────────

    /** Clear signals and band registrations for a bus name (called on rename/delete).
     *  <p>清除指定总线名的信号和频段注册（改名/删除时调用）。
     *  Note: does <b>not</b> touch the CHANNELS registry — channel lifecycle is managed by
     *  {@link #registerChannel}/{@link #unregisterChannel} via ref-counting.
     *  注意：<b>不</b>操作 CHANNELS 注册表 — 频道生命周期由 registerChannel/unregisterChannel 通过引用计数管理。</p> */
    public static void clearBus(String busName) {
        String prefix = busName + "\0";
        SIGNALS.keySet().removeIf(k -> k.startsWith(prefix));
        BAND_REGISTRY.remove(busName);
    }

    /** Clear all signals, channel registrations, and band registries (called on server shutdown) / 清除所有信号、频道注册和频段注册表（服务器关闭时调用） */
    public static void clear() {
        SIGNALS.clear();
        BAND_REGISTRY.clear();
        CHANNELS.clear();
    }
}
