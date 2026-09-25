package io.github.y15173334444.create_schematic_compute.graph;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 方块的节点准入：分类白名单 + 类内黑名单。
 * A block's node allowance: category allowlist plus in-category exclusions.
 *
 * <p>新增节点只要落进已允许的分类即自动可用，不必再改 10 处名单
 * （见 docs/node-category-allowance-plan.md）。</p>
 * <p>New node types land in an already-allowed category and become available
 * without editing ten lists (see docs/node-category-allowance-plan.md).</p>
 */
public record NodeAllowance(Set<NodeCategory> categories, Set<NodeType> exclusions) {

    public NodeAllowance {
        categories = Collections.unmodifiableSet(EnumSet.copyOf(categories));
        exclusions = exclusions.isEmpty()
            ? Set.of()
            : Collections.unmodifiableSet(EnumSet.copyOf(exclusions));
    }

    /** 构造：分类若干 + 可选类内排除。 / categories plus optional exclusions. */
    public static NodeAllowance of(Set<NodeCategory> categories, NodeType... exclusions) {
        return new NodeAllowance(categories, exclusions.length == 0
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
