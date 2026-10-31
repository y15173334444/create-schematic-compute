package io.github.y15173334444.create_schematic_compute.graph;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 方块的节点准入：宿主键 + 分类白名单 + 类内黑名单。
 * A block's node allowance: host key plus category allowlist plus in-category exclusions.
 *
 * <p>新增节点只要落进已允许的分类即自动可用，不必再改 10 处名单
 * （见 docs/node-category-allowance-plan.md）。</p>
 * <p>New node types land in an already-allowed category and become available
 * without editing ten lists (see docs/node-category-allowance-plan.md).</p>
 *
 * <p><b>宿主键（公开 API 波 1 批 D，D5 名单制）</b>：{@code hostKey} 是该宿主在
 * {@link CustomNodeRoster} 里的字符串键（{@code "csc:" + 方块 id}）——服务端准入门
 * 与客户端添加菜单都从名单常量自带这一键派生自定义节点名单（单一来源，零漂移）；
 * 键与 {@code api.CustomNodes.HOST_BLUEPRINT} 同源（api 侧为别名）。
 * <b>Host key (public-API wave 1 batch D, the D5 roster)</b>: {@code hostKey} is this
 * host's string key into {@link CustomNodeRoster} ({@code "csc:" + block id}) — the
 * server admission gate and the client add-menu both derive the custom-node roster from
 * this field carried by the allowance constant itself (single source, no drift); the key
 * is the same string aliased by {@code api.CustomNodes.HOST_BLUEPRINT}.</p>
 */
public record NodeAllowance(String hostKey, Set<NodeCategory> categories, Set<NodeType> exclusions) {

    public NodeAllowance {
        if (hostKey == null || hostKey.isBlank())
            throw new IllegalArgumentException("NodeAllowance hostKey must be non-blank (D5 roster key)");
        categories = Collections.unmodifiableSet(EnumSet.copyOf(categories));
        exclusions = exclusions.isEmpty()
            ? Set.of()
            : Collections.unmodifiableSet(EnumSet.copyOf(exclusions));
    }

    /** 构造：宿主键 + 分类若干 + 可选类内排除。 / host key, categories, optional exclusions. */
    public static NodeAllowance of(String hostKey, Set<NodeCategory> categories, NodeType... exclusions) {
        return new NodeAllowance(hostKey, categories, exclusions.length == 0
            ? Set.of()
            : EnumSet.copyOf(java.util.Arrays.asList(exclusions)));
    }

    /** 是否允许新建该类型节点。 / whether the type may be added. */
    public boolean allows(NodeType t) {
        if (exclusions.contains(t)) return false;
        NodeCategory c = NodeCategory.of(t);
        return c != null && categories.contains(c);
    }

    /** 展开为实际允许的节点集合（测试 / 文档对齐用）。
     *  Expand to the concrete allowed set (tests / doc alignment). */
    public EnumSet<NodeType> allowedTypes() {
        EnumSet<NodeType> out = EnumSet.noneOf(NodeType.class);
        for (NodeType t : NodeType.values()) if (allows(t)) out.add(t);
        return out;
    }
}
