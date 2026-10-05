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

    /** 频段音频标志：bus名 → 承载音频的频段名集合（发布方按引脚对端域判定，随频段定义同步到
     *  订阅方，编辑器着色用）/ Per-band audio flags: busName → band names carrying audio
     *  (decided by the publisher's pin peers, synced with the band definition for editor tinting). */
    private static final ConcurrentHashMap<String, java.util.Set<String>> AUDIO_BANDS = new ConcurrentHashMap<>();
    /** 每频道的标志版本号（变更即自增）——宿主推送缓存据此判断要不要重发 / Per-channel flag version
     *  (bumped on every change) — host push caches compare this to decide on a resend. */
    private static final ConcurrentHashMap<String, Integer> AUDIO_STAMPS = new ConcurrentHashMap<>();

    /** 私有频道占用表：name → owner（与 BUS {@link #CHANNELS} 同款占用语义——首个注册者获胜，
     *  同名不同 owner 注册失败、由调用方标冲突旗标；写入权属于 owner）。
     *  Private channel occupancy: name → owner — the same first-registrant-wins discipline as
     *  the BUS channel registry; a different owner's registration fails and the caller flags it. */
    private static final ConcurrentHashMap<String, ChannelOwner> PRIVATE_OWNERS = new ConcurrentHashMap<>();

    /** 私有频道定义标志：name → 该频道当前是否承载音频（发布方按输入引脚对端域的拓扑判定，
     *  随版本戳同步给订阅方做引脚类型变换——BUS 频段音频标志同款，属性跟频道走）。
     *  Private channel definition flag: whether the channel carries audio (topology-derived by
     *  the publisher's pin peer, version-stamped and synced to subscribers for pin typing). */
    private static final ConcurrentHashMap<String, Boolean> PRIVATE_AUDIO = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> PRIVATE_AUDIO_STAMPS = new ConcurrentHashMap<>();

    // ── PRIVATE_IN/OUT API (unchanged) / PRIVATE_IN/OUT API（不变） ──────────────────────

    public static void put(String channel, float value) {
        SIGNALS.put(channel, value);
    }

    public static float get(String channel) {
        return SIGNALS.getOrDefault(channel, 0f);
    }

    /** 清理指定信号名（值 + 占用 + 定义一起撤）：PRIVATE_OUT 节点销毁/改名/宿主卸载的统一释放，
     *  防止 SIGNALS/PRIVATE_OWNERS 泄漏与陈旧 owner 永久占名。
     *  Clear a signal name (value + occupancy + definition together) — the unified release for
     *  PRIVATE_OUT destroy / rename / host unload, so maps never leak and a stale owner can't
     *  hold a name forever. */
    public static void clearSignal(String channel) {
        SIGNALS.remove(channel);
        PRIVATE_OWNERS.remove(channel);
        if (PRIVATE_AUDIO.remove(channel) != null) PRIVATE_AUDIO_STAMPS.merge(channel, 1, Integer::sum);
    }

    // ── PRIVATE channel occupancy API / 私有频道占用 API ──────────────────────

    /**
     * 注册私有频道占用（BUS {@link #registerChannel} 占用语义同款）：首个注册者获胜；
     *  同一 owner 可重复注册；不同 owner 使用同名 → 冲突，返回 false 且不动现有占用。
     *  Register a private channel's occupancy (same discipline as the BUS registry):
     *  first registrant wins, the same owner may re-register, a different owner is refused.
     *  @return true 注册成功（或本就是该 owner），false 被其他 owner 占用
     */
    public static boolean registerPrivateChannel(String channel, ChannelOwner owner) {
        ChannelOwner prev = PRIVATE_OWNERS.putIfAbsent(channel, owner);
        return prev == null || prev.equals(owner);
    }

    /**
     * 释放私有频道占用（owner 必须匹配才动）：占用、值与定义一并撤——发布方离去即撤频道，
     *  同名竞争者随后可经 {@link #registerPrivateChannel} 接管（自愈同 BUS）。
     *  Release a private channel (owner must match): occupancy, value and definition go together
     *  — the channel dies with its publisher and a contender takes over (BUS-style self-heal).
     *  @return true 已释放（含本就无占用的空名），false 被其他 owner 占用、未动
     */
    public static boolean unregisterPrivateChannel(String channel, ChannelOwner owner) {
        ChannelOwner cur = PRIVATE_OWNERS.get(channel);
        if (cur != null && !cur.equals(owner)) return false;
        clearSignal(channel);
        return true;
    }

    /** 当前占用者（无占用返回 null；冲突恢复的存活判定用）/ The current owner (null when free). */
    public static ChannelOwner getPrivateOwner(String channel) {
        return PRIVATE_OWNERS.get(channel);
    }

    // ── Private channel definition API / 私有频道定义 API ──────────────────────

    /** 更新私有频道定义标志（内容变化才写 + 自增版本）。返回是否发生变化。
     *  Update the private channel's definition flag (write + version bump only on change). */
    public static boolean setPrivateAudio(String channel, boolean audio) {
        Boolean cur = PRIVATE_AUDIO.get(channel);
        if (cur != null && cur == audio) return false;
        PRIVATE_AUDIO.put(channel, audio);
        PRIVATE_AUDIO_STAMPS.merge(channel, 1, Integer::sum);
        return true;
    }

    /** 私有频道当前是否承载音频（订阅方引脚类型变换用）/ Whether the private channel carries audio. */
    public static boolean isPrivateAudio(String channel) {
        return PRIVATE_AUDIO.getOrDefault(channel, false);
    }

    /** 私有频道定义版本号（无定义 0）/ The private channel's definition version stamp (0 when none). */
    public static int privateAudioStamp(String channel) {
        return PRIVATE_AUDIO_STAMPS.getOrDefault(channel, 0);
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

    /** 更新频段音频标志（内容变化才写 + 自增版本）。返回是否发生变化。
     *  Update the per-band audio flags (write + version bump only on change); returns whether it changed. */
    public static boolean setAudioBands(String busName, java.util.Set<String> audioBandNames) {
        var current = AUDIO_BANDS.get(busName);
        if (audioBandNames == null || audioBandNames.isEmpty()) {
            if (current == null) return false;
            AUDIO_BANDS.remove(busName);
            AUDIO_STAMPS.merge(busName, 1, Integer::sum);
            return true;
        }
        if (current != null && current.equals(audioBandNames)) return false;
        AUDIO_BANDS.put(busName, new java.util.HashSet<>(audioBandNames));
        AUDIO_STAMPS.merge(busName, 1, Integer::sum);
        return true;
    }

    /** 频段音频标志（只读；无定义返回空集）/ The channel's audio band flags (empty when undefined). */
    public static java.util.Set<String> getAudioBands(String busName) {
        return AUDIO_BANDS.getOrDefault(busName, java.util.Set.of());
    }

    /** 某频段当前是否音频（订阅方着色用）/ Whether a specific band carries audio (subscriber tinting). */
    public static boolean isAudioBand(String busName, String bandName) {
        return AUDIO_BANDS.getOrDefault(busName, java.util.Set.of()).contains(bandName);
    }

    /** 标志版本号（无定义 0）/ The channel's flag version stamp (0 when undefined). */
    public static int audioStamp(String busName) {
        return AUDIO_STAMPS.getOrDefault(busName, 0);
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
        // EN: Same owner → update reference; preserve ref-count across internalMap replacement.
        // 同一 owner → 更新引用；替换 internalMap 时保留引用计数。
        if (existing.owner.equals(owner)) {
            if (existing.internalMap != internalMap) {
                SchematicCompute.LOGGER.debug("[SignalBus] Channel '{}' map reference updated by {}", channelName, owner);
                // Preserve ref-count when replacing the entry with a new map reference.
                // Old values belong to the previous graph state; each BUS_OUT starts fresh.
                // 替换条目时保留引用计数。旧值属于之前的图状态，每个 BUS_OUT 重新开始。
                ChannelEntry updated = new ChannelEntry(internalMap, owner);
                // Carry forward the old ref-count (don't reset to 1) / 沿用旧引用计数（不重置为1）
                while (updated.refCount() < existing.refCount()) updated.incrementRef();
                CHANNELS.put(channelName, updated);
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
            // Preserve ref-count across map reference update / 更新 map 引用时保留引用计数
            ChannelEntry updated = new ChannelEntry(internalMap, owner);
            while (updated.refCount() < existing.refCount()) updated.incrementRef();
            CHANNELS.put(channelName, updated);
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
        AUDIO_BANDS.remove(busName);
        AUDIO_STAMPS.remove(busName);
    }

    /** Clear all signals, channel registrations, and band registries (called on server shutdown) / 清除所有信号、频道注册和频段注册表（服务器关闭时调用） */
    public static void clear() {
        SIGNALS.clear();
        BAND_REGISTRY.clear();
        AUDIO_BANDS.clear();
        AUDIO_STAMPS.clear();
        CHANNELS.clear();
        PRIVATE_OWNERS.clear();
        PRIVATE_AUDIO.clear();
        PRIVATE_AUDIO_STAMPS.clear();
    }

    /** graph 端口实现（接口下沉）：求值器经 {@code SignalBusPort} 走本表，graph 不直连
     *  网络包。 / the graph-side port (interface sinking): the evaluator reaches this
     *  table through {@code SignalBusPort}, never through the network package directly. */
    public static final io.github.y15173334444.create_schematic_compute.graph.SignalBusPort PORT =
        new io.github.y15173334444.create_schematic_compute.graph.SignalBusPort() {
            @Override public float get(String channel) { return SignalBus.get(channel); }
            @Override public void put(String channel, float value) { SignalBus.put(channel, value); }
            @Override public void setPrivateAudio(String channel, boolean audio) { SignalBus.setPrivateAudio(channel, audio); }
            @Override public void setAudioBands(String busName, java.util.Set<String> audioBandNames) { SignalBus.setAudioBands(busName, audioBandNames); }
            @Override public void registerBands(String busName, java.util.List<String> bands) { SignalBus.registerBands(busName, bands); }
            @Override public java.util.List<String> getBands(String busName) { return SignalBus.getBands(busName); }
            @Override public java.util.Map<String, Float> channelMap(String channel) {
                ChannelEntry e = SignalBus.getChannel(channel);
                return e != null ? e.internalMap : null;
            }
        };
}
