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
     * Register a BUS_OUT node as a participant of a shared channel.
     * <p>注册一个 BUS_OUT 节点为共享频道的参与者。</p>
     * <p>Shared model (回归审计：跨方块同名 BUS_OUT 共享频道而非独占):
     * every same-name BUS_OUT across blocks joins the SAME channel entry and
     * writes into the shared map. Always returns true — there is no ownership
     * conflict; same-graph duplicates are flagged separately as local conflicts.
     * 共享模型：所有同名 BUS_OUT（可跨方块）加入同一频道条目并写入共享 map。
     * 恒返回 true——无所有权冲突；同图内重名由本地冲突标记单独处理。</p>
     *
     * @param channelName bus name (signalName) / 总线名（signalName）
     * @param owner       participant identifier (pos, nodeId) / 参与者标识
     * @return true（恒真）/ always true
     */
    public static boolean registerChannel(String channelName, ChannelOwner owner) {
        ChannelEntry entry = CHANNELS.computeIfAbsent(channelName, k -> new ChannelEntry());
        entry.addParticipant(owner);
        SchematicCompute.LOGGER.debug("[SignalBus] Channel '{}' participant added: {}", channelName, owner);
        return true;
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
        // 共享模型：移除参与者；最后一个参与者离开时清理整个频道
        // Shared model: remove the participant; tear down the channel when the last leaves
        if (!existing.removeParticipant(owner, null)) {
            SchematicCompute.LOGGER.debug("[SignalBus] Channel '{}' unregistration by {} — not a participant", channelName, owner);
            return false;
        }
        if (existing.isEmpty()) {
            SchematicCompute.LOGGER.debug("[SignalBus] Channel '{}' removed (last participant left)", channelName);
            CHANNELS.remove(channelName, existing);
            // Clear residual signal data so the channel doesn't pollute the next registrant
            // 清除残留信号数据，防止频道污染下一个注册者
            clearBus(channelName);
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
