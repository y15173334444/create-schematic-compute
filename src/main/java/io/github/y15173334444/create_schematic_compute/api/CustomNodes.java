package io.github.y15173334444.create_schematic_compute.api;

import io.github.y15173334444.create_schematic_compute.graph.CustomNodeRegistry;

/**
 * 公开 API 注册门面（公开 API 波 1）：第三方模组的唯一登记入口。实现侧
 * （{@link CustomNodeRegistry}）住在 graph 包 —— 本类把公开面收拢进 {@code api}，
 * 第三方代码只 import 本包（探索稿 §6 包结构：api 门面 + 实现侧内部包）。
 * The public-API registration facade (public-API wave 1): the single entry point for
 * third-party mods. The implementation ({@link CustomNodeRegistry}) lives in the graph
 * package — this class keeps the public face inside {@code api} so third-party code
 * imports only this package (exploration doc §6: api facade + internal impl packages).
 */
public final class CustomNodes {

    private CustomNodes() {}

    /**
     * 注册一个自定义节点类型（schema + 求值行为）。在提供方模组构造期调用一次；
     * 重复 id 抛 {@link IllegalStateException}（与内置 NodeType.BY_ID 同款启动即炸）。
     * <p>Register a custom node type (schema + evaluation behavior). Call once at provider
     * mod construction; a duplicate id throws {@link IllegalStateException} (the same
     * fail-fast as the built-in NodeType.BY_ID).</p>
     *
     * <p><b>注册 ≠ 准入</b>（D5 名单制）：注册只让形状/行为存在；能否被放进某个宿主的图
     * 由该宿主的准入名单裁定 —— 第三方可把自己的节点登记进自己的宿主套件宿主（随便登）
     * 与本模组方块（走本模组提供的登记口，宿主套件波提供）。名单制服务端化随批 D 落地。
     * <b>Registering ≠ admitting</b> (the D5 roster): registration only brings the shape and
     * behavior into existence; whether it may enter a host's graph is ruled by that host's
     * admission roster — third parties roster into their own host-suite hosts freely and
     * into this mod's blocks via the registration口 this mod provides (host-suite wave).
     * The roster's server-side enforcement lands with batch D.</p>
     *
     * @param spec     声明式 schema（id/标题/引脚/参数）/ the declarative schema
     * @param behavior 求值行为（{@code null} = 形状可进编辑器但求值空转 —— 渐进接入用）
     *                 / the evaluation behavior ({@code null} = the shape exists in the
     *                 editor while evaluation idles — progressive bring-up)
     */
    public static void register(CustomNodeType spec, CustomNodeBehavior behavior) {
        CustomNodeRegistry.register(spec, behavior);
    }
}
