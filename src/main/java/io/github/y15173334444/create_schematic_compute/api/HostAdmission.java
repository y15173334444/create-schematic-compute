package io.github.y15173334444.create_schematic_compute.api;

import java.util.Set;

/**
 * 第三方宿主的准入偏好声明（宿主套件波 2）：类目白名单 + 类内排除。默认拒绝、
 * 显式声明 —— 声明为空的宿主不放任何内置节点进图；第三方自定义节点走
 * {@link HostCategory#CUSTOM} 类目 + 名单制（{@code CustomNodes.admitToHost}，在其下按字符串 id
 * 细分）。排除项按节点 id 字符串声明（内置节点为裸 id，如 {@code "add"}；自定义节点为
 * {@code "modid:node_id"}）；id 在 bind 时解析，未知 id 即抛（fail-fast，启动即炸口径）。
 * A third-party host's admission preference (host suite, wave 2): a category
 * allowlist plus in-category exclusions. Default-deny, explicit declaration — a host
 * with an empty declaration admits no built-in nodes into its graphs; third-party
 * custom nodes ride the {@link HostCategory#CUSTOM} category + the roster (subdivided below the
 * category by string id via {@code CustomNodes.admitToHost}). Exclusions are declared
 * as built-in node-id strings (e.g. {@code "csc:add"}); ids resolve at bind time and
 * an unknown id throws (fail fast at construction).
 *
 * <p>不可变值类型：构造时防御性拷贝，两个集合不可为 {@code null}（缺省即空集 =
 * 全拒）。</p>
 * <p>An immutable value type: defensively copied at construction; neither set may be
 * {@code null} (absence means the empty set = admit nothing).</p>
 *
 * @param categories       类目白名单（整类放行）/ the category allowlist (whole categories)
 * @param excludedNodeIds  类内排除（节点 id 字符串）/ the in-category exclusions (node-id strings)
 */
public record HostAdmission(Set<HostCategory> categories, Set<String> excludedNodeIds) {

    public HostAdmission {
        categories = categories == null ? Set.of() : Set.copyOf(categories);
        excludedNodeIds = excludedNodeIds == null ? Set.of() : Set.copyOf(excludedNodeIds);
    }

    /** 全拒声明（占位宿主/渐进接入用）。 / An admit-nothing declaration (placeholder
     *  hosts / progressive bring-up). */
    public static HostAdmission none() {
        return new HostAdmission(Set.of(), Set.of());
    }

    /** 构造：类目若干、无排除。 / Categories only, no exclusions. */
    public static HostAdmission of(HostCategory first, HostCategory... rest) {
        return new HostAdmission(java.util.EnumSet.of(first, rest), Set.of());
    }

    /** 构造：类目若干 + 类内排除（内置节点 id 字符串，bind 时解析校验）。
     *  / Categories plus in-category exclusions (node-id strings — built-ins are bare ids
     *  like {@code "add"}, custom nodes are {@code "modid:node_id"} — resolved and
     *  validated at bind time). */
    public static HostAdmission ofExcluding(Set<HostCategory> categories,
                                            String firstExcluded, String... moreExcluded) {
        var ids = new java.util.LinkedHashSet<String>();
        ids.add(firstExcluded);
        java.util.Collections.addAll(ids, moreExcluded);
        return new HostAdmission(categories, ids);
    }
}
