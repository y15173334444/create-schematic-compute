package io.github.y15173334444.create_schematic_compute.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Global signal bus — transports float values by string name.
 *  <p>全局信号总线 — 通过字符串名称传输浮点数。</p>
 *  <ul>
 *  <li>{@link #SIGNALS} — flat key-value store used by PRIVATE_IN/OUT / PRIVATE_IN/OUT 使用的扁平键值存储</li>
 *  <li>{@link #CHANNELS} — channel registry for BUS_IN/OUT, holds BUS_OUT internalMap references + ref-counts / BUS_IN/OUT 使用的频道注册表，持有 BUS_OUT 的 internalMap 引用 + 引用计数</li>
 *  <li>{@link #BAND_REGISTRY} — BUS band-name list registry (cross-computer band definitions, for editor UI) / BUS 频段名列表注册表（跨计算机共享频段定义，用于编辑器 UI）</li>
 *  </ul>
 */
public class SignalBus {
    /** 独立 logger：**不引用** @Mod 类 —— 其静态初始化需要 MC 注册表，纯 JUnit 起不来
     *  （BusInBandConvergenceTest 为此只能反射种条目）。日志名为本类 FQCN。
     *  Same-name standalone logger: never references the @Mod class — its static init needs
     *  the Minecraft registries and cannot boot in plain JUnit (which is why
     *  BusInBandConvergenceTest had to plant entries reflectively). The log name is this class's FQCN. */
    private static final Logger LOGGER = LoggerFactory.getLogger(SignalBus.class);

    private static final ConcurrentHashMap<String, Float> SIGNALS = new ConcurrentHashMap<>();

    /** BUS channel registry: busName → ChannelEntry (holding BUS_OUT busInternalMap reference) / BUS 频道注册表：bus名 → ChannelEntry（持有 BUS_OUT 的 busInternalMap 引用） */
    private static final ConcurrentHashMap<String, ChannelEntry> CHANNELS = new ConcurrentHashMap<>();

    /** BUS band registry: busName → band name list (cross-computer shared band definitions) / BUS 频段注册表：bus名 → band名列表（跨计算机共享频段定义） */
    private static final ConcurrentHashMap<String, List<String>> BAND_REGISTRY = new ConcurrentHashMap<>();

    /** 频段声明载荷类型：bus名 → 频段名 → {@code PinType}（发布方逐频段声明，随频段定义同步
     *  给订阅方收敛/着色；缺省 FLOAT）/ Per-band declared payload types: busName → band name
     *  → PinType (declared by the publisher, synced with the band definition for subscriber
     *  convergence and tinting; FLOAT by default). */
    private static final ConcurrentHashMap<String, java.util.Map<String, io.github.y15173334444.create_schematic_compute.graph.PinType>> BAND_TYPES = new ConcurrentHashMap<>();
    /** 每频道的定义版本号（变更即自增）——宿主推送缓存据此判断要不要重发 / Per-channel
     *  definition version (bumped on every change) — host push caches compare this to
     *  decide on a resend. */
    private static final ConcurrentHashMap<String, Integer> BAND_TYPE_STAMPS = new ConcurrentHashMap<>();

    /** 向量值表（状态语义 last-write-wins）：键 = 私有名或频段键 {@code channel\0band}。
     *  Vector values (state semantics, last write wins): key = private name or the band
     *  key {@code channel\0band}. */
    private static final ConcurrentHashMap<String, io.github.y15173334444.create_schematic_compute.graph.Vec3f> VEC3_VALUES = new ConcurrentHashMap<>();

    /** 私有频道占用表：name → owner（与 BUS {@link #CHANNELS} 同款占用语义——首个注册者获胜，
     *  同名不同 owner 注册失败、由调用方标冲突旗标；写入权属于 owner）。
     *  Private channel occupancy: name → owner — the same first-registrant-wins discipline as
     *  the BUS channel registry; a different owner's registration fails and the caller flags it. */
    private static final ConcurrentHashMap<String, ChannelOwner> PRIVATE_OWNERS = new ConcurrentHashMap<>();

    /** BUS 频道的**活候选**占用表：name → 注册被拒过的 owner 集合（首个注册者获胜语义下的排队者）。
     *  <p>持有者**确证离开**时，若名下还有活候选，频段定义**不**退役成「已定义为空」——降级为
     *  「未定义」（条目缺席），让「缺席不是定义」守卫跳过收敛、保住订阅者连线；候选经
     *  {@code recoverConflictedChannels} 接管后定义随注册内核回来。没有候选才做死名退役
     *  （issue #11 的死名收敛照旧）。用户症状：注册序倒置下 B 持有频道、A 撞名，B 改名走人
     *  把名字退役成「已定义为空」，订阅侧每 tick 收敛把 BUS_IN 的频段图剪光，A 接管后线也回不来。</p>
     *  Live <b>claimants</b> per BUS channel: owners whose registration was refused (the queue
     *  behind first-registrant-wins). When the holder provably leaves and claimants remain, the
     *  band definition is <b>not</b> retired to "defined empty" — it degrades to "undefined"
     *  (absent entry) so the "absence is not a definition" guard skips convergence and keeps
     *  subscribers' wires; the claimant's takeover brings the definition back with its
     *  registration. Only an uncontested departure retires the name (issue #11's dead-name
     *  cleanup, unchanged). User symptom: under a registration-order inversion B holds the
     *  channel and A conflicts; renaming B away retired the name to defined-empty and the
     *  per-tick convergence pruned every BUS_IN's band graph — A's later takeover could not
     *  bring the wires back. */
    private static final ConcurrentHashMap<String, java.util.Set<ChannelOwner>> CLAIMANTS = new ConcurrentHashMap<>();

    /** 私有频道声明载荷类型：name → {@code PinType}（发布方声明，随版本戳同步给订阅方做
     *  引脚类型变换——BUS 频段类型表同款，属性跟频道走）。
     *  Private channel declared payload type: name → PinType (declared by the publisher,
     *  version-stamped and synced to subscribers for pin typing; the BUS discipline). */
    private static final ConcurrentHashMap<String, io.github.y15173334444.create_schematic_compute.graph.PinType> PRIVATE_TYPES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> PRIVATE_TYPE_STAMPS = new ConcurrentHashMap<>();

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
        VEC3_VALUES.remove(channel);
        PRIVATE_OWNERS.remove(channel);
        if (PRIVATE_TYPES.remove(channel) != null) PRIVATE_TYPE_STAMPS.merge(channel, 1, Integer::sum);
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

    /** 更新私有频道声明载荷类型（内容变化才写 + 自增版本）。返回是否发生变化。
     *  Update the private channel's declared payload type (write + version bump only on
     *  change); returns whether it changed. */
    public static boolean setPrivateType(String channel, io.github.y15173334444.create_schematic_compute.graph.PinType type) {
        var cur = PRIVATE_TYPES.get(channel);
        if (cur == type) return false;
        PRIVATE_TYPES.put(channel, type);
        PRIVATE_TYPE_STAMPS.merge(channel, 1, Integer::sum);
        return true;
    }

    /** 私有频道声明载荷类型（无定义 = FLOAT）/ The private channel's declared payload type
     *  (FLOAT when undefined). */
    public static io.github.y15173334444.create_schematic_compute.graph.PinType privateType(String channel) {
        return PRIVATE_TYPES.getOrDefault(channel, io.github.y15173334444.create_schematic_compute.graph.PinType.FLOAT);
    }

    /** 私有频道定义版本号（无定义 0）/ The private channel's definition version stamp (0 when none). */
    public static int privateTypeStamp(String channel) {
        return PRIVATE_TYPE_STAMPS.getOrDefault(channel, 0);
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

    /**
     * 把频段定义标记为「已定义为空」——发布方**确证离开**（改名走人 / 显式清空频道）时清理死名用。
     * <p>与「未定义」（条目缺席，见 {@link #clearBus}）语义不同：收敛方会把已定义为空收敛成空列表
     * （issue #11 的死名清理），而条目缺席被「缺席不是定义」守卫跳过（发布方区块卸载等瞬时缺席
     * 不得剪掉 BUS_IN 的连线）。</p>
     * Mark the band definition as <b>defined empty</b> — used to clean up a dead name when its
     * publisher has provably left (renamed away / explicitly retired the channel).
     * <p>Distinct from <b>undefined</b> (absent entry, see {@link #clearBus}): convergence turns a
     * defined-empty channel into an empty list (issue #11's dead-name cleanup), while an absent
     * entry is skipped by the "absence is not a definition" guard (transient absence such as the
     * publisher's chunk unloading must never prune a BUS_IN's wires).</p>
     */
    public static void retireBands(String busName) {
        BAND_REGISTRY.put(busName, new ArrayList<>());
    }

    /**
     * 发布方**确证离开**（改名走人 / 节点删除 / 显式清空频道）的统一释放：按 owner 撤频道
     * 条目，并在名下无主时退役频段定义（{@link #retireBands}）。只动属于该 owner 的数据 ——
     * 他人条目与定义不碰（跨 owner 破坏防线）。**瞬时缺席**（宿主卸载 / 区块 unloaded）
     * 不走此路：那要保持「未定义」，由「缺席不是定义」守卫保订阅者连线。
     * <p>统一规则（一处写清）：确证离开且**名下无活候选** → retire（已定义为空，死名收敛清图）；
     * 确证离开但**还有活候选**（{@link #CLAIMANTS}） → 降级为「未定义」——候选接管前收敛跳过、
     * 保订阅者连线，接管后定义随注册内核回来；瞬时缺席 → 保持未定义（跳过收敛，保线）。</p>
     * Unified release for a **provable** departure (rename-away / node delete / explicit
     * channel retirement): drops the channel entry by owner and, when the name is left
     * unowned, retires the band definition (defined-empty). Only that owner's data is touched
     * — a foreign entry or definition is never nulled (the cross-owner damage guard).
     * **Transient absence** (host unload / chunk unloaded) does not go through here: it must
     * stay "undefined" so the "absence is not a definition" guard preserves subscribers' wires.
     * <p>The rule in one place: provable departure with <b>no live claimants</b> → retire
     * (defined-empty, dead-name convergence prunes); provable departure <b>with claimants</b>
     * → degrade to "undefined" — convergence skips until the claimant takes over, then the
     * definition returns with its registration; transient absence → stays undefined.</p>
     *
     * @return true if this owner's entry was released
     */
    public static boolean releaseChannel(String busName, ChannelOwner owner) {
        boolean released = unregisterChannel(busName, owner);
        // 离开者不再是候选（改名/删除/显式清空的统一出口都在这）。
        // The leaver stops being a claimant — this is the unified exit for rename/delete/retire.
        dropClaim(busName, owner);
        if (released || getChannel(busName) == null) {
            if (!released) clearBus(busName);
            // 活候选还在 → 名字是「有争议」而非「已死」：不退役成「已定义为空」（clearBus
            // 已把条目摘成缺席），缺席守卫跳过收敛、保住订阅者连线；候选接管后定义回来。
            // A live claimant means the name is contested, not dead: do not retire it to
            // "defined empty" (clearBus already left it absent) — the absence guard keeps
            // subscribers' wires until the claimant's takeover restores the definition.
            if (claimantsOf(busName).isEmpty()) retireBands(busName);
        }
        return released;
    }

    /** 当前活候选（只读；无候选返回空集）/ The current claimants (empty when none). */
    private static java.util.Set<ChannelOwner> claimantsOf(String busName) {
        var c = CLAIMANTS.get(busName);
        return c != null ? c : java.util.Set.of();
    }

    /** 摘掉一个候选（注册成功或确证离开时）；集空连键一起收，防表泄漏。
     *  Drop a claimant (on successful registration or provable departure); the emptied
     *  key goes too, so the table never leaks. */
    private static void dropClaim(String busName, ChannelOwner owner) {
        CLAIMANTS.computeIfPresent(busName, (k, set) -> {
            set.remove(owner);
            return set.isEmpty() ? null : set;
        });
    }

    /**
     * 非发布方编辑器的无主残留清理（P1-9，{@link #releaseChannel}「确证离开 → 退役」的
     * Case-C 口径）：仅当 {@code CHANNELS} 无该名条目（频道确证已死）时清全局残留
     * （{@link #clearBus} + {@link #retireBands}，死名收敛清图）；任何存活 owner 在场时
     * 一律不动 —— BUS_IN 之类的订阅方不持有频道（只有发布方注册），它能证明的只有
     * 「没有活的发布方」，绝不凭本地推断抹掉存活 owner 的定义与值（单人/局域网下客户端
     * 编辑器与集成服务端共享同一 JVM 静态表，裸写会穿到权威侧）。
     * Unowned-residue cleanup for non-publisher editors (P1-9; the case-C shape of
     * {@link #releaseChannel}'s "provable departure → retire" rule): clear the global residue
     * ({@link #clearBus} + {@link #retireBands}, the dead-name convergence) only when CHANNELS
     * holds no entry for the name (the channel is provably dead); a live owner of any kind
     * keeps everything — a subscriber such as a BUS_IN never owns the channel (only publishers
     * register), so all it can prove is "no live publisher exists"; it must never wipe a live
     * owner's definition and values on a local guess (in single-player/LAN the client editor
     * shares the integrated server's static tables in one JVM, so a raw write reaches the
     * authoritative side).
     *
     * @return true 已清理；false 该名仍有存活 owner、未动
     * @return true cleaned; false a live owner holds the name, nothing touched
     */
    public static boolean clearBusIfUnowned(String busName) {
        if (getChannel(busName) != null) return false;
        clearBus(busName);
        retireBands(busName);
        return true;
    }

    /**
     * 客户端编辑器门控的频段注册（P1-9，对照 {@link #releaseChannel} 的 owner 纪律）：仅当
     * 该名**未被其他 owner 占用**时才写本地定义 —— 他人持有的名绝不覆盖（跨 owner 破坏防线，
     * 冲突旗标同步竞态窗口内尤其如此）；无主（无 CHANNELS 条目）或本 owner 持有时放行 ——
     * 无主时的写入是编辑器的乐观本地值，权威值随后由服务端的频段广播收敛。
     * Editor-gated band registration (P1-9, mirroring {@link #releaseChannel}'s owner
     * discipline): write the local definition only when the name is **not held by a foreign
     * owner** — a foreign-owned name is never overwritten (the cross-owner damage guard,
     * especially inside the conflict-flag sync race window); unowned (no CHANNELS entry) or
     * self-owned names pass — an unowned write is the editor's optimistic local value, converged
     * later by the server's authoritative band broadcasts.
     *
     * @return true 已写入；false 被其他 owner 占用、未动
     * @return true written; false held by another owner, nothing touched
     */
    public static boolean registerBandsIfNotForeign(String busName, List<String> bands, ChannelOwner self) {
        ChannelEntry entry = CHANNELS.get(busName);
        if (entry != null && !entry.owner.equals(self)) {
            LOGGER.warn("[SignalBus] Band registration for '{}' by {} rejected — owned by {}",
                busName, self, entry.owner);
            return false;
        }
        registerBands(busName, bands);
        return true;
    }

    /** 更新逐频段声明载荷类型（内容变化才写 + 自增版本）。返回是否发生变化。
     *  Update the per-band declared payload types (write + version bump only on change);
     *  returns whether they changed. */
    public static boolean setBandTypes(String busName, java.util.Map<String, io.github.y15173334444.create_schematic_compute.graph.PinType> bandTypes) {
        var current = BAND_TYPES.get(busName);
        if (bandTypes == null || bandTypes.isEmpty()) {
            if (current == null) return false;
            BAND_TYPES.remove(busName);
            BAND_TYPE_STAMPS.merge(busName, 1, Integer::sum);
            return true;
        }
        if (current != null && current.equals(bandTypes)) return false;
        BAND_TYPES.put(busName, new java.util.HashMap<>(bandTypes));
        BAND_TYPE_STAMPS.merge(busName, 1, Integer::sum);
        return true;
    }

    /** 频道的逐频段声明载荷类型（只读；无定义返回空表）/ The channel's per-band declared
     *  payload types (empty when undefined). */
    public static java.util.Map<String, io.github.y15173334444.create_schematic_compute.graph.PinType> getBandTypes(String busName) {
        return BAND_TYPES.getOrDefault(busName, java.util.Map.of());
    }

    /** 某频段的声明载荷类型（无声明 = FLOAT；订阅方收敛/着色用）/ A band's declared payload
     *  type (FLOAT when undeclared; subscriber convergence and tinting). */
    public static io.github.y15173334444.create_schematic_compute.graph.PinType bandType(String busName, String bandName) {
        return BAND_TYPES.getOrDefault(busName, java.util.Map.of())
            .getOrDefault(bandName, io.github.y15173334444.create_schematic_compute.graph.PinType.FLOAT);
    }

    /** 定义版本号（无定义 0）/ The channel's definition version stamp (0 when undefined). */
    public static int bandTypeStamp(String busName) {
        return BAND_TYPE_STAMPS.getOrDefault(busName, 0);
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
                LOGGER.debug("[SignalBus] Channel '{}' registered by {}", channelName, owner);
                dropClaim(channelName, owner); // 注册成功 = 不再是候选 / success = no longer a claimant
                return true;
            }
            existing = raced; // EN: Lost the race, process according to existing entry / 竞态失败，按已有条目处理
        }
        // EN: Same owner → update reference; preserve ref-count across internalMap replacement.
        // 同一 owner → 更新引用；替换 internalMap 时保留引用计数。
        if (existing.owner.equals(owner)) {
            if (existing.internalMap != internalMap) {
                LOGGER.debug("[SignalBus] Channel '{}' map reference updated by {}", channelName, owner);
                // Preserve ref-count when replacing the entry with a new map reference.
                // Old values belong to the previous graph state; each BUS_OUT starts fresh.
                // 替换条目时保留引用计数。旧值属于之前的图状态，每个 BUS_OUT 重新开始。
                ChannelEntry updated = new ChannelEntry(internalMap, owner);
                // Carry forward the old ref-count (don't reset to 1) / 沿用旧引用计数（不重置为1）
                while (updated.refCount() < existing.refCount()) updated.incrementRef();
                CHANNELS.put(channelName, updated);
            }
            dropClaim(channelName, owner);
            return true;
        }
        // EN: Different owner → conflict
        // 不同 owner → 冲突（登记为活候选：持有者离开时名下并非无主，见 CLAIMANTS）
        // conflict (register a live claimant: the name is not dead when the holder leaves)
        LOGGER.warn("[SignalBus] Channel '{}' already owned by {} — rejected registration by {}",
            channelName, existing.owner, owner);
        CLAIMANTS.computeIfAbsent(channelName, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(owner);
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
            LOGGER.debug("[SignalBus] Channel '{}' not found for unregistration by {}", channelName, owner);
            return false;
        }
        if (!existing.owner.equals(owner)) {
            LOGGER.warn("[SignalBus] Channel '{}' unregistration by {} rejected — owned by {}",
                channelName, owner, existing.owner);
            return false;
        }
        int remaining = existing.decrementRef();
        if (remaining <= 0) {
            LOGGER.debug("[SignalBus] Channel '{}' removed (refCount reached 0)", channelName);
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
        VEC3_VALUES.keySet().removeIf(k -> k.startsWith(prefix));
        BAND_REGISTRY.remove(busName);
        BAND_TYPES.remove(busName);
        BAND_TYPE_STAMPS.remove(busName);
    }

    // ── Vector value API / 向量值 API ──────────────────────

    /** 写向量值（状态语义 last-write-wins）：键 = 私有名或频段键 {@code channel\0band}。
     *  Write a vector value (state semantics, last write wins). */
    public static void putVec3(String key, io.github.y15173334444.create_schematic_compute.graph.Vec3f value) {
        if (value == null) VEC3_VALUES.remove(key);
        else VEC3_VALUES.put(key, value);
    }

    /** 读向量值（缺席 = null）/ Read a vector value (absent = null). */
    public static io.github.y15173334444.create_schematic_compute.graph.Vec3f getVec3(String key) {
        return VEC3_VALUES.get(key);
    }

    /** Clear all signals, channel registrations, and band registries (called on server shutdown) / 清除所有信号、频道注册和频段注册表（服务器关闭时调用） */
    public static void clear() {
        SIGNALS.clear();
        VEC3_VALUES.clear();
        BAND_REGISTRY.clear();
        BAND_TYPES.clear();
        BAND_TYPE_STAMPS.clear();
        CHANNELS.clear();
        CLAIMANTS.clear();
        PRIVATE_OWNERS.clear();
        PRIVATE_TYPES.clear();
        PRIVATE_TYPE_STAMPS.clear();
    }

    /** graph 端口实现（接口下沉）：求值器经 {@code SignalBusPort} 走本表，graph 不直连
     *  网络包。 / the graph-side port (interface sinking): the evaluator reaches this
     *  table through {@code SignalBusPort}, never through the network package directly. */
    public static final io.github.y15173334444.create_schematic_compute.graph.SignalBusPort PORT =
        new io.github.y15173334444.create_schematic_compute.graph.SignalBusPort() {
            @Override public float get(String channel) { return SignalBus.get(channel); }
            @Override public void put(String channel, float value) { SignalBus.put(channel, value); }
            @Override public void setPrivateType(String channel, io.github.y15173334444.create_schematic_compute.graph.PinType type) { SignalBus.setPrivateType(channel, type); }
            @Override public void setBandTypes(String busName, java.util.Map<String, io.github.y15173334444.create_schematic_compute.graph.PinType> bandTypes) { SignalBus.setBandTypes(busName, bandTypes); }
            @Override public void registerBands(String busName, java.util.List<String> bands) { SignalBus.registerBands(busName, bands); }
            @Override public java.util.List<String> getBands(String busName) { return SignalBus.getBands(busName); }
            @Override public java.util.Map<String, Float> channelMap(String channel) {
                ChannelEntry e = SignalBus.getChannel(channel);
                return e != null ? e.internalMap : null;
            }
            @Override public void putVec3(String key, io.github.y15173334444.create_schematic_compute.graph.Vec3f value) { SignalBus.putVec3(key, value); }
            @Override public io.github.y15173334444.create_schematic_compute.graph.Vec3f getVec3(String key) { return SignalBus.getVec3(key); }
        };
}
