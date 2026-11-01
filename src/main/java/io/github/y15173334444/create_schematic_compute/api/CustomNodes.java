package io.github.y15173334444.create_schematic_compute.api;

import io.github.y15173334444.create_schematic_compute.graph.BlockNodeAllowances;
import io.github.y15173334444.create_schematic_compute.graph.CustomNodeRegistry;
import io.github.y15173334444.create_schematic_compute.graph.CustomNodeRoster;

/**
 * 公开 API 注册门面（本模组公开 API 的组成部分）：第三方模组的唯一登记入口。实现侧
 * （{@link CustomNodeRegistry}、{@link CustomNodeRoster}）住在 graph 包 —— 本类把公开面
 * 收拢进 {@code api}，第三方代码只 import 本包（包结构 = 公开门面 + 实现侧内部包）。
 * 下文的 {@code api → graph} 引用是门面模式的实现委托：结构性约束禁的是 api
 * <b>签名里</b>出现图域类型（提供方因此只对本包编译），而非门面自身的委托边 ——
 * 本类不向提供方暴露任何图域类型。
 * The public-API registration facade (part of this mod's public API): the single entry point for
 * third-party mods. The implementations ({@link CustomNodeRegistry},
 * {@link CustomNodeRoster}) live in the graph package — this class keeps the public face
 * inside {@code api} so third-party code imports only this package (the package layout:
 * api facade + internal impl packages). The {@code api → graph} references below are the
 * facade pattern's implementation delegation: the structural rule bans
 * graph-domain <b>types in api signatures</b> (so providers compile against this package
 * alone), not the facade's own delegate edge — this class exposes no graph type to providers.
 */
public final class CustomNodes {

    private CustomNodes() {}

    /** 蓝图机宿主键（准入名单的登记目标；与 {@code BlockNodeAllowances.BLUEPRINT.hostKey()}
     *  同串同源 —— api 侧别名）。宿主键族随本模组更多宿主开放自定义节点而扩展。
     *  The blueprint host key (the admission-roster enrolment target; the same string as
     *  {@code BlockNodeAllowances.BLUEPRINT.hostKey()} — an api-side alias). More host
     *  keys open as more of this mod's hosts accept custom nodes. */
    public static final String HOST_BLUEPRINT = BlockNodeAllowances.BLUEPRINT.hostKey();

    /**
     * 注册一个自定义节点类型（schema + 求值行为）。在提供方模组构造期调用一次；
     * 重复 id 抛 {@link IllegalStateException}（与内置 NodeType.BY_ID 同款启动即炸）。
     * <p>Register a custom node type (schema + evaluation behavior). Call once at provider
     * mod construction; a duplicate id throws {@link IllegalStateException} (the same
     * fail-fast as the built-in NodeType.BY_ID).</p>
     *
     * <p><b>注册 ≠ 准入</b>（名单制）：注册只让形状/行为存在；能否被放进某个宿主的图
     * 由该宿主的准入名单裁定 —— 第三方经 {@link #admitToHost} 把自己的节点登记进本模组
     * 方块；自有宿主（宿主套件，后续版本提供）随套件自行管理名单。
     * <b>Registering ≠ admitting</b> (the admission roster): registration only brings the shape and
     * behavior into existence; whether it may enter a host's graph is ruled by that host's
     * admission roster — third parties enrol into this mod's blocks via
     * {@link #admitToHost}; their own hosts (the host suite, a later release) manage their
     * rosters with the suite.</p>
     *
     * @param spec     声明式 schema（id/标题/引脚/参数）/ the declarative schema
     * @param behavior 求值行为（{@code null} = 形状可进编辑器但求值空转 —— 渐进接入用）
     *                 / the evaluation behavior ({@code null} = the shape exists in the
     *                 editor while evaluation idles — progressive bring-up)
     */
    public static void register(CustomNodeType spec, CustomNodeBehavior behavior) {
        CustomNodeRegistry.register(spec, behavior);
    }

    /**
     * 把已注册的节点 id 登记进目标宿主的准入名单（名单制：默认拒绝、显式登记）。
     * 在提供方模组构造期、{@link #register} 之后调用；重复登记幂等。id 未注册抛
     * {@link IllegalArgumentException}（防手误：名单里躺一个不存在的形状是提供方 bug）。
     * <p>Enrol a registered node id into a target host's admission roster (default-deny,
     * explicit enrolment). Call at provider mod construction after
     * {@link #register}; duplicate enrolment is idempotent. An unregistered id throws
     * {@link IllegalArgumentException} (a roster entry for a non-existent shape is a
     * provider bug).</p>
     *
     * <p><b>宿主键归属校验</b>：第三方只可登记进<b>显式白名单</b>内的本模组
     * 宿主键（现 = {@link #HOST_BLUEPRINT}；后续版本开放更多宿主时扩展）——
     * 登记进任意其他键（含他人宿主键）抛 {@link IllegalArgumentException}。自有宿主
     * （宿主套件，后续版本提供）不经本口，随套件自行声明名单。
     * <b>Host-key ownership</b>: third parties may enrol only into
     * this mod's host keys on an <b>explicit allowlist</b> (currently
     * {@link #HOST_BLUEPRINT}; it grows as later releases open more hosts) — enrolling
     * into any other key (including another mod's) throws
     * {@link IllegalArgumentException}. Your own hosts (the host suite, a later release)
     * bypass this facade and declare their rosters with the suite.</p>
     *
     * <p>装载兼容不经名单：旧档已在场的 id（无论提供方是否在线）由装载普查放行——
     * 撤销恢复与装载同语义（占位壳等提供方回归）。
     * Load tolerance does not ride the roster: ids already present in an older save pass
     * via the load-time census whether or not the provider is online — undo-restore gets
     * load semantics (a placeholder shell waiting for the provider's return).</p>
     *
     * @param nodeId  已注册的自定义节点 id（{@code "modid:node_id"}）/ the registered id
     * @param hostKey 可登记白名单内的宿主键（现 = {@link #HOST_BLUEPRINT}）/ an enrolable
     *                host key (currently {@link #HOST_BLUEPRINT})
     */
    public static void admitToHost(String nodeId, String hostKey) {
        CustomNodeRoster.admit(hostKey, nodeId);
    }
}
