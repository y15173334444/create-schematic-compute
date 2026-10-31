package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.api.CustomNodeBehavior;
import io.github.y15173334444.create_schematic_compute.api.CustomNodeType;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 第三方自定义节点的动态注册表（公开 API 波 1，探索稿 D2 拍板：与 {@link NodeType}
 * 枚举共存）。字符串 id 寻址（{@code "modid:node_id"}）；{@code NodeType.CUSTOM} 是
 * 唯一的枚举哨兵，实际形状由本表的 {@link CustomNodeType} schema 决定。
 *
 * <p><b>注册时机与线程口径</b>：提供方模组在自身构造期（mod init，单线程）注册；
 * 服务端线程/客户端线程此后只读——ConcurrentHashMap 保证发布安全。重复 id 注册
 * 抛 {@link IllegalStateException}（与 {@link NodeType#BY_ID} 静态块同款纪律：
 * id 冲突 = 提供方 bug，启动即炸比静默覆盖诚实）。</p>
 *
 * <p><b>与准入的关系</b>（D5，批 D 落地）：注册 ≠ 准入——名单制默认拒绝，第三方还须
 * 把自己的节点 id 登记进目标宿主的准入名单（自己宿主套件随便登记；本模组方块走本模组
 * 提供的登记口）。本表只回答「形状是否存在」；「能否进图」由 {@link NodeAdmission} 裁定。</p>
 *
 * <p>The dynamic registry for third-party custom nodes (public-API wave 1; ruling D2:
 * coexists with the {@link NodeType} enum). Addressed by string id ("modid:node_id");
 * {@code NodeType.CUSTOM} is the single enum sentinel whose actual shape comes from the
 * {@link CustomNodeType} schema here. Registered at provider mod-init (single-threaded),
 * read-only afterwards on server/client threads (ConcurrentHashMap for safe publication).
 * Registering ≠ admitting (D5 roster discipline lands in batch D): this table answers
 * "does the shape exist"; {@link NodeAdmission} rules whether it may enter a graph.</p>
 */
public final class CustomNodeRegistry {

    private CustomNodeRegistry() {}

    /** id → schema（提供方 init 注册、运行期只读）/ id → schema (registered at provider init, read-only at runtime). */
    private static final Map<String, CustomNodeType> BY_ID = new ConcurrentHashMap<>();

    /** id → 求值行为（批 B）：schema 与行为同批登记，缺一即降级（图形在场、求值空转）。
     *  id → evaluation behavior (batch B): schema and behavior register together; either
     *  missing degrades (the shape exists in the graph, evaluation idles). */
    private static final Map<String, CustomNodeBehavior> BEHAVIORS = new ConcurrentHashMap<>();

    /** modid → 该提供方注册的全部 id（提供方在线校验与卸载清理的底账）/ modid → ids owned by that provider (provider-online checks and unload cleanup). */
    private static final Map<String, Set<String>> BY_PROVIDER = new ConcurrentHashMap<>();

    /** 注册表世代号：每次 register/unregister 递增 —— 自定义节点运行态（处置梯子的
     *  熔断/降频）以它检测「提供方已变」并在下一 tick 整体重置（审查批 (c)6：
     *  「提供方重注册解除熔断」的实现路径）。
     *  Registry generation: bumped on every register/unregister — custom-node run states
     *  watch it to detect "the provider changed" and reset wholesale next tick (review
     *  (c)6: the implementation path behind "a provider re-register lifts a fuse"). */
    private static volatile long generation = 0;

    /** 注册自定义节点 schema；id 重复抛异常（与 NodeType.BY_ID 同款启动即炸纪律）。
     *  <p>Register a custom-node schema; a duplicate id throws (same fail-fast discipline
     *  as NodeType.BY_ID's static block).</p> */
    public static void register(CustomNodeType spec) {
        register(spec, null);
    }

    /** 注册 schema + 求值行为（完整形态）：行为缺省 = 图形可进编辑器但求值空转
     *  （批 A 的 schema-only 形态保留，供形状测试与渐进接入）。
     *  <p>Register a schema with its evaluation behavior (the full form): a null behavior
     *  keeps the shape addable in the editor while evaluation idles (batch A's schema-only
     *  form retained for shape tests and progressive bring-up).</p> */
    public static void register(CustomNodeType spec, CustomNodeBehavior behavior) {
        CustomNodeType prev = BY_ID.putIfAbsent(spec.id(), spec);
        if (prev != null)
            throw new IllegalStateException("Duplicate custom node id: " + spec.id());
        String modid = spec.id().substring(0, spec.id().indexOf(':'));
        BY_PROVIDER.computeIfAbsent(modid, k -> ConcurrentHashMap.newKeySet()).add(spec.id());
        if (behavior != null) BEHAVIORS.put(spec.id(), behavior);
        generation++;
    }

    /** 按 id 查求值行为；未注册返回 null（调用方降级空转）。/ Look up the evaluation
     *  behavior by id; null when unregistered (callers idle). */
    public static CustomNodeBehavior behavior(String customId) {
        return customId == null ? null : BEHAVIORS.get(customId);
    }

    /** 当前注册表世代号（运行态重置检测用）。/ the current registry generation (for run-state reset detection). */
    public static long providerGeneration() {
        return generation;
    }

    /** 按 id 查 schema；未注册（含提供方缺席）返回 null —— 调用方降级占位。
     *  <p>Look up a schema by id; null when unregistered (including a missing provider) —
     *  callers degrade to a placeholder.</p> */
    public static CustomNodeType get(String customId) {
        return customId == null ? null : BY_ID.get(customId);
    }

    /** id 是否已注册 / whether the id is registered. */
    public static boolean isRegistered(String customId) {
        return customId != null && BY_ID.containsKey(customId);
    }

    /** 某提供方（modid）名下的全部节点 id（D5 名单制与提供方在线校验用）。
     *  <p>All node ids owned by a provider modid (for the D5 roster and provider-online checks).</p> */
    public static Set<String> idsByProvider(String modid) {
        return BY_PROVIDER.getOrDefault(modid, Set.of());
    }

    /** 注销某提供方的全部节点（开发热替换/测试隔离用；生产生命周期不调用）。
     *  包级可见 —— 提供方只见 api.CustomNodes.register，无生产注销路径（审查批 (b)4
     *  护栏）；世代号随注销递增，在场的运行态（熔断等）下一 tick 全量重置。
     *  <p>Unregister everything owned by a provider (dev hot-swap / test isolation only;
     *  never called in the production lifecycle). Package-private — providers only ever
     *  see api.CustomNodes.register, so there is no production unregister path (review
     *  (b)4 guardrail); the generation bumps so live run states (fuses etc.) reset
     *  wholesale next tick.</p> */
    static void unregisterProvider(String modid) {
        Set<String> ids = BY_PROVIDER.remove(modid);
        if (ids != null) {
            ids.forEach(BY_ID::remove);
            ids.forEach(BEHAVIORS::remove);
        }
        generation++;
    }

    /** 清空注册表（仅测试隔离用）/ clear everything (test isolation only). */
    public static void clearForTests() {
        BY_ID.clear();
        BEHAVIORS.clear();
        BY_PROVIDER.clear();
        generation++;
    }
}
