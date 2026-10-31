package io.github.y15173334444.create_schematic_compute.graph;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宿主级自定义节点准入名单（公开 API 波 1 批 D，探索稿 D5 拍板）：宿主键 → 已登记节点 id。
 * 默认拒绝、显式登记——第三方经 {@code api.CustomNodes.admitToHost(nodeId, hostKey)} 把自己的
 * 节点 id 登记进目标宿主（自己的宿主套件随便登记；本模组方块走本模组提供的登记口，键见
 * {@link NodeAllowance#hostKey()}）。注册表只回答「该宿主名单里有没有这个 id」；
 * 「id 形状是否存在」归 {@link CustomNodeRegistry}，「能否进图」由 {@link NodeAdmission}
 * 裁定（名单 ∪ 装载普查 cid）。
 *
 * <p>The per-host custom-node admission roster (public-API wave 1 batch D; exploration
 * ruling D5): host key → admitted node ids. Default-deny, explicit enrolment — providers
 * roster their node ids into a target host via {@code api.CustomNodes.admitToHost}
 * (freely into their own host-suite hosts; into this mod's blocks via the registration
 * point this mod provides, keyed by {@link NodeAllowance#hostKey()}). The roster only
 * answers "is this id on this host's list"; whether the shape exists is
 * {@link CustomNodeRegistry}'s question and whether it may enter a graph is ruled by
 * {@link NodeAdmission} (roster ∪ load-time census cids).</p>
 *
 * <p><b>登记时机与线程口径</b>：与 {@link CustomNodeRegistry} 同款——提供方 mod 构造期
 * （单线程）登记，此后服务端/客户端线程只读（ConcurrentHashMap 发布安全）。重复登记幂等
 * （名单语义：在不在，不关次数）。</p>
 *
 * <p><b>Enrolment timing & threads</b>: same as {@link CustomNodeRegistry} — registered at
 * provider mod construction (single-threaded), read-only afterwards (ConcurrentHashMap
 * publication). Duplicate enrolment is idempotent (roster semantics: membership, not
 * count).</p>
 */
public final class CustomNodeRoster {

    private CustomNodeRoster() {}

    /** hostKey → 该宿主已登记的自定义节点 id / host key → custom node ids admitted for that host. */
    private static final Map<String, Set<String>> BY_HOST = new ConcurrentHashMap<>();

    /** 第三方可登记的宿主键白名单（批 D 审查拍板）：归属校验的口径 = 显式白名单，键
     *  随持有 CUSTOM 类目的本模组宿主逐个开放（现 = 蓝图机，验收①宿主；波 2 宿主套件
     *  落地时由宿主自己声明可登记键后在此扩行）。引用名单常量的 hostKey（单一来源，
     *  禁字面量副本）。
     *  The allowlist of host keys third parties may enrol into (batch D review ruling):
     *  ownership = an explicit allowlist, growing per this mod's CUSTOM-bearing hosts
     *  (currently the blueprint computer, acceptance-1's host; wave-2 host-suite hosts
     *  declare their own enrolable keys and extend this line). References the allowance
     *  constant's hostKey (single source, no literal copies). */
    private static final Set<String> ENROLABLE_HOST_KEYS = Set.of(
        BlockNodeAllowances.BLUEPRINT.hostKey());

    /**
     * 把节点 id 登记进宿主名单（幂等）。生产路径 = {@code api.CustomNodes.admitToHost}
     * 门面；本方法公开仅为门面委托边服务（批 B {@code unregisterProvider} 包级化同族
     * 护栏：第三方只见 api 包）。
     * <p>Enrol a node id into a host roster (idempotent). The production path is the
     * {@code api.CustomNodes.admitToHost} facade; this stays public only to serve the
     * facade's delegate edge (the same guardrail family as batch B's package-private
     * {@code unregisterProvider}: providers see the api package only).</p>
     *
     * @throws IllegalArgumentException id 未注册（防手误：登记一个不存在的形状是提供方
     *                                  bug，启动即炸比静默无效名单诚实），或 hostKey 不在
     *                                  第三方可登记白名单内（归属校验，批 D 审查拍板）
     *                                  / the id is not registered (a typo'd enrolment is a
     *                                  provider bug — fail fast rather than a silently dead
     *                                  roster entry), or the host key is not on the
     *                                  third-party enrolable allowlist (ownership check,
     *                                  batch D review ruling)
     */
    public static void admit(String hostKey, String nodeId) {
        if (hostKey == null || hostKey.isBlank())
            throw new IllegalArgumentException("roster hostKey must be non-blank");
        // 归属校验（批 D 审查拍板）：第三方只许登记进白名单宿主键——任意模组把节点
        // 登记进任意键（含他人键）的旁路在此收口。键族随宿主套件波扩展。
        // Ownership check (batch D review ruling): third parties enrol only into
        // allowlisted host keys — the bypass of enrolling into any key (including other
        // mods' keys) closes here. The key family grows with the host-suite wave.
        if (!ENROLABLE_HOST_KEYS.contains(hostKey))
            throw new IllegalArgumentException("host key is not enrolable by third parties: " + hostKey);
        if (!CustomNodeRegistry.isRegistered(nodeId))
            throw new IllegalArgumentException("roster enrolment of unregistered custom node id: " + nodeId);
        BY_HOST.computeIfAbsent(hostKey, k -> ConcurrentHashMap.newKeySet()).add(nodeId);
    }

    /** 某宿主名单的只读快照；键未登记过（或 null）返回空集 —— fail-closed。
     *  <p>A read-only snapshot of one host's roster; empty for an unknown (or null) key —
     *  fail-closed.</p> */
    public static Set<String> forHost(String hostKey) {
        if (hostKey == null) return Set.of();
        Set<String> ids = BY_HOST.get(hostKey);
        return ids == null ? Set.of() : Set.copyOf(ids);
    }

    /** 已有名单的宿主键快照（观测面用）。/ snapshot of host keys that have rosters (observability). */
    public static Set<String> hostKeys() {
        return Set.copyOf(BY_HOST.keySet());
    }

    /** 清空名单（仅测试隔离用）。/ clear everything (test isolation only). */
    public static void clearForTests() {
        BY_HOST.clear();
    }
}
