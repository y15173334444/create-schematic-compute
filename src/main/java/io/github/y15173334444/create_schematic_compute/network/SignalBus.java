package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 全局信号总线 — 通过字符串名称传输浮点数。
 *  <ul>
 *  <li>{@link #SIGNALS} — PRIVATE_IN/OUT 使用的扁平键值存储</li>
 *  <li>{@link #CHANNELS} — BUS_IN/OUT 使用的频道注册表，持有 BUS_OUT 的 internalMap 引用 + 引用计数</li>
 *  <li>{@link #BAND_REGISTRY} — BUS 频段名列表注册表（跨计算机共享频段定义，用于编辑器 UI）</li>
 *  </ul>
 */
public class SignalBus {
    private static final ConcurrentHashMap<String, Float> SIGNALS = new ConcurrentHashMap<>();

    /** BUS 频道注册表：bus名 → ChannelEntry（持有 BUS_OUT 的 busInternalMap 引用） */
    private static final ConcurrentHashMap<String, ChannelEntry> CHANNELS = new ConcurrentHashMap<>();

    /** BUS 频段注册表：bus名 → band名列表（跨计算机共享频段定义） */
    private static final ConcurrentHashMap<String, List<String>> BAND_REGISTRY = new ConcurrentHashMap<>();

    // ── PRIVATE_IN/OUT API（不变） ──────────────────────

    public static void put(String channel, float value) {
        SIGNALS.put(channel, value);
    }

    public static float get(String channel) {
        return SIGNALS.getOrDefault(channel, 0f);
    }

    /** 清理指定信号名（PRIVATE_OUT 节点销毁时调用，防止 SIGNALS map 泄漏） */
    public static void clearSignal(String channel) {
        SIGNALS.remove(channel);
    }

    // ── BUS 频段名同步 API（不变） ──────────────────────

    /** 注册 BUS 频段（BUS_OUT 编辑时调用） */
    public static void registerBands(String busName, List<String> bands) {
        if (bands != null && !bands.isEmpty())
            BAND_REGISTRY.put(busName, new ArrayList<>(bands));
        else
            BAND_REGISTRY.remove(busName);
    }

    /** 获取 BUS 频段列表 */
    public static List<String> getBands(String busName) {
        return BAND_REGISTRY.get(busName);
    }

    // ── BUS 频道注册 API（新增） ──────────────────────

    /**
     * 注册一个 BUS_OUT 频道到全局表。
     * <p>同一 owner 可重复注册（如 tick 重入）：更新 internalMap 引用并递增引用计数。
     * <p>不同 owner 使用相同频道名 → 冲突，打印 WARN 并返回 false。
     *
     * @param channelName 频段名（signalName）
     * @param internalMap BUS_OUT 节点的 busInternalMap 引用
     * @param owner       频道所有者标识
     * @return true 注册成功，false 被其他 owner 占用
     */
    public static boolean registerChannel(String channelName, Map<String, Float> internalMap, ChannelOwner owner) {
        ChannelEntry existing = CHANNELS.get(channelName);
        if (existing == null) {
            // 首次注册 — 使用 putIfAbsent 防竞态
            ChannelEntry created = new ChannelEntry(internalMap, owner);
            ChannelEntry raced = CHANNELS.putIfAbsent(channelName, created);
            if (raced == null) {
                SchematicCompute.LOGGER.debug("[SignalBus] Channel '{}' registered by {}", channelName, owner);
                return true;
            }
            existing = raced; // 竞态失败，按已有条目处理
        }
        // 同一 owner → 更新引用并增加计数
        if (existing.owner.equals(owner)) {
            existing.incrementRef();
            if (existing.internalMap != internalMap) {
                SchematicCompute.LOGGER.debug("[SignalBus] Channel '{}' map reference updated by {}", channelName, owner);
                // 将旧值迁移到新 Map（保留已写入的频段值）
                internalMap.putAll(existing.internalMap);
                CHANNELS.put(channelName, new ChannelEntry(internalMap, owner));
            }
            return true;
        }
        // 不同 owner → 冲突
        SchematicCompute.LOGGER.warn("[SignalBus] Channel '{}' already owned by {} — rejected registration by {}",
            channelName, existing.owner, owner);
        return false;
    }

    /**
     * 取消注册一个 BUS_OUT 频道。递减引用计数，归零时自动移除。
     *
     * @param channelName 频段名
     * @param owner       频道所有者标识（必须匹配才能取消注册）
     * @return true 取消注册成功或频道不存在，false owner 不匹配
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
        }
        return true;
    }

    /** 获取频道条目（供 BUS_IN 读取）。返回 null 表示没有活跃的 BUS_OUT。 */
    public static ChannelEntry getChannel(String channelName) {
        return CHANNELS.get(channelName);
    }

    // ── 清理 API ──────────────────────────────────────

    /** 清除指定总线名的信号和频段注册（改名/删除时调用）。
     *  <p>注意：<b>不</b>操作 CHANNELS 注册表 — 频道生命周期由 {@link #registerChannel}/{@link #unregisterChannel} 通过引用计数管理。 */
    public static void clearBus(String busName) {
        String prefix = busName + "\0";
        SIGNALS.keySet().removeIf(k -> k.startsWith(prefix));
        BAND_REGISTRY.remove(busName);
    }

    /** 清除所有信号、频道注册和频段注册表（服务器关闭时调用） */
    public static void clear() {
        SIGNALS.clear();
        BAND_REGISTRY.clear();
        CHANNELS.clear();
    }
}
