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
 * <p><b>Enrolment timing &amp; threads</b>: same as {@link CustomNodeRegistry} — registered at
 * provider mod construction (single-threaded), read-only afterwards (ConcurrentHashMap
 * publication). Duplicate enrolment is idempotent (roster semantics: membership, not
 * count).</p>
 */
public final class CustomNodeRoster {

    private CustomNodeRoster() {}

    /** hostKey → 该宿主已登记的自定义节点 id / host key → custom node ids admitted for that host. */
    private static final Map<String, Set<String>> BY_HOST = new ConcurrentHashMap<>();

    /** CSC 显式白名单（批 D 审查拍板）：第三方可登记进的本模组宿主键，随持有 CUSTOM
     *  类目的本模组宿主逐个开放（现 = 蓝图机，验收①宿主）。引用名单常量的 hostKey
     *  （单一来源，禁字面量副本）。波 2 起归属是两路放行——本白名单之外，第三方
     *  自声明键（见 {@link #DECLARED_HOST_KEYS}）对自己的命名空间同样开放。
     *  The explicit CSC allowlist (batch D review ruling): this mod's host keys third
     *  parties may enrol into, growing per CUSTOM-bearing host (currently the blueprint
     *  computer, acceptance-1's host). References the allowance constant's hostKey
     *  (single source, no literal copies). Since wave 2 ownership is two-path — beyond
     *  this allowlist, third-party self-declared keys (see {@link #DECLARED_HOST_KEYS})
     *  are open to their own namespace. */
    private static final Set<String> CSC_ENROLABLE_KEYS = Set.of(
        BlockNodeAllowances.BLUEPRINT.hostKey());

    /** 第三方宿主经宿主套件 {@code HostGraphs.bind} 自声明的可登记键（波 2）：键强制
     *  {@code modid:name} 形态，归属规则 = 键命名空间等于节点 id 命名空间（声明者收
     *  自己的节点）。会话期静态（与 {@link CustomNodeRegistry} 同口径），模组构造期
     *  写入、此后只读。
     *  Host keys self-declared by third-party hosts via the host suite's {@code
     *  HostGraphs.bind} (wave 2): keys must be {@code modid:name}; ownership = the key's
     *  namespace equals the node id's namespace (the declarer enrols its own nodes).
     *  Session-static (same as {@link CustomNodeRegistry}) — written at mod construction,
     *  read-only afterwards. */
    private static final Set<String> DECLARED_HOST_KEYS = ConcurrentHashMap.newKeySet();

    /**
     * 登记第三方宿主自声明的可登记键（宿主套件 bind 路径，波 2）。键强制
     * {@code modid:name} 形态；重复声明<b>幂等</b>——键属于宿主<b>类型</b>而 bind 属于
     * 宿主<b>实例</b>（同类型多个方块实体各自 bind 一次，同键重登是常态而非 bug）；
     * 与 CSC 显式白名单撞键仍抛 {@link IllegalStateException}（第三方不得伪装本模组
     * 键族）。本方法公开仅为门面委托边服务——第三方只见 api 包。
     * <p>Registers a third-party host's self-declared enrolable key (the host-suite
     * bind path, wave 2). Keys must be {@code modid:name}; duplicate declaration is
     * <b>idempotent</b> — a key belongs to the host <b>type</b> while bind belongs to
     * the host <b>instance</b> (every block entity of the type binds once, so re-declaring
     * the same key is the norm, not a bug); a collision with the CSC explicit allowlist
     * still throws {@link IllegalStateException} (third parties must not pose as this
     * mod's key family). Public only to serve the facade's delegate edge — providers see
     * the api package only.</p>
     *
     * @throws IllegalArgumentException 键空或非 {@code modid:name} 形态 / the key is
     *                                  blank or not {@code modid:name}
     * @throws IllegalStateException    键与 CSC 显式白名单撞键 / the key collides with
     *                                  the CSC explicit allowlist
     */
    public static void declareHostKey(String hostKey) {
        if (hostKey == null || hostKey.isBlank())
            throw new IllegalArgumentException("declared host key must be non-blank");
        int colon = hostKey.indexOf(':');
        if (colon <= 0 || colon == hostKey.length() - 1)
            throw new IllegalArgumentException("declared host key must be modid:name: " + hostKey);
        if (CSC_ENROLABLE_KEYS.contains(hostKey))
            throw new IllegalStateException("host key collides with the CSC allowlist: " + hostKey);
        DECLARED_HOST_KEYS.add(hostKey);
    }

    /** 已声明的第三方宿主键快照（bind 重复校验用）。 / Snapshot of the declared
     *  third-party host keys (for bind's duplicate check). */
    public static Set<String> declaredHostKeys() {
        return Set.copyOf(DECLARED_HOST_KEYS);
    }

    /** 波 2 归属两路放行：① CSC 显式白名单；② 第三方自声明键且键命名空间 == 节点 id
     *  命名空间（声明者收自己的节点）。其余（含他人声明的键）一律拒绝。
     *  The wave-2 two-path ownership: (1) the CSC explicit allowlist; (2) a
     *  third-party self-declared key whose namespace equals the node id's namespace
     *  (the declarer enrols its own nodes). Everything else — including keys declared
     *  by other mods — is refused. */
    private static boolean isEnrolable(String hostKey, String nodeId) {
        if (CSC_ENROLABLE_KEYS.contains(hostKey)) return true;
        if (!DECLARED_HOST_KEYS.contains(hostKey)) return false;
        String keyNs = namespace(hostKey);
        return !keyNs.isEmpty() && keyNs.equals(namespace(nodeId));
    }

    /** {@code modid:name} 的命名空间段；无冒号或空段返回空串（调用方判空拒收）。
     *  The namespace segment of {@code modid:name}; empty string when no colon or an
     *  empty segment (callers check and refuse). */
    private static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon <= 0 ? "" : id.substring(0, colon);
    }

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
     *                                  归属两路放行内（批 D 白名单 / 波 2 自声明键同名
     *                                  命名空间）
     *                                  / the id is not registered (a typo'd enrolment is a
     *                                  provider bug — fail fast rather than a silently dead
     *                                  roster entry), or the host key fails the two-path
     *                                  ownership check (batch-D allowlist / a wave-2
     *                                  self-declared key in the same namespace)
     */
    public static void admit(String hostKey, String nodeId) {
        if (hostKey == null || hostKey.isBlank())
            throw new IllegalArgumentException("roster hostKey must be non-blank");
        // 归属两路放行（批 D 白名单 + 波 2 自声明键）：任意模组把节点登记进任意键
        // （含他人键）的旁路在此收口。
        // Two-path ownership (batch-D allowlist + wave-2 self-declared keys): the bypass
        // of enrolling into any key (including other mods' keys) closes here.
        if (!isEnrolable(hostKey, nodeId))
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

    /** 清空名单与声明键（仅测试隔离用）。/ clear rosters and declared keys (test
     *  isolation only). */
    public static void clearForTests() {
        BY_HOST.clear();
        DECLARED_HOST_KEYS.clear();
    }
}
