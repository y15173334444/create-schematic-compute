package io.github.y15173334444.create_schematic_compute.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 公开 API 的独立版本号（本模组公开 API 的组成部分）：x.y.z 语义版本 —— x=大改
 * （破坏性变更）、y=新功能（向后兼容的新面）、z=hotfix（向后兼容的修复）。版本号
 * 与模组版本（{@code mod_version}，描述发行节奏）解耦、互不联动：本类型是唯一
 * 权威单源（{@link #CURRENT}），附属模组经 {@link #CURRENT}、{@link #parse}、
 * {@link #isAtLeast} 读取判别，不以模组版本区间推断 API 兼容性。阶段口径：0.x =
 * experimental（接口签名、语义可变）；首次破坏性变更即升 1.0.0，那一刻就是首个
 * 稳定面的冻结点。各档位的承诺见 docs/1.3.0-public-api-exploration.md §6.2。
 *
 * <p>The public API's own version number (part of this mod's public API): an x.y.z
 * semantic version — x = major (breaking changes), y = minor (backward-compatible
 * additions), z = patch (backward-compatible fixes). It is decoupled from the mod
 * version ({@code mod_version}, which tracks release cadence) and the two never move
 * together: this type is the single source of truth ({@link #CURRENT}); addons read
 * and compare it via {@link #CURRENT}, {@link #parse} and {@link #isAtLeast} instead
 * of inferring API compatibility from mod-version ranges. Phase policy: 0.x =
 * experimental (signatures/semantics may change); the first breaking change bumps to
 * 1.0.0 — the freeze point of the first stable face. Per-tier commitments live in
 * docs/1.3.0-public-api-exploration.md §6.2.</p>
 *
 * @param major  大改档位（破坏性变更升位）/ the major tier (bumped by breaking changes)
 * @param minor  新功能档位（向后兼容的新面）/ the minor tier (backward-compatible additions)
 * @param patch  hotfix 档位（向后兼容的修复）/ the patch tier (backward-compatible fixes)
 */
public record ApiVersion(int major, int minor, int patch) implements Comparable<ApiVersion> {

    private static final Pattern SEGMENT = Pattern.compile("\\d+");

    /** 当前 API 版本（唯一权威单源：升版只改此处，CHANGELOG 同批明示）。
     *  The current API version (the single source of truth: bump only here, and
     *  call it out in the CHANGELOG in the same change). */
    public static final ApiVersion CURRENT = new ApiVersion(0, 2, 0);

    /**
     * 解析 x.y.z 形态的版本串（三段、每段为非负十进制整数，如 {@code "0.1.0"}）。
     * <p>Parses an x.y.z version string (three segments, each a non-negative decimal
     * integer, e.g. {@code "0.1.0"}).</p>
     *
     * @param s 版本串 / the version string
     * @return 解析出的版本 / the parsed version
     * @throws NullPointerException {@code s} 为 {@code null} / if {@code s} is {@code null}
     * @throws IllegalArgumentException 形态不符（段数不对、含非数字段或数值越界）/ if the shape
     *                                  is wrong (wrong segment count, a non-numeric
     *                                  segment, or a value out of range)
     */
    public static ApiVersion parse(String s) {
        Objects.requireNonNull(s, "version string");
        String[] parts = s.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("API version must be x.y.z (three segments): " + s);
        }
        return new ApiVersion(parseSegment(parts[0], s), parseSegment(parts[1], s), parseSegment(parts[2], s));
    }

    private static int parseSegment(String segment, String whole) {
        if (!SEGMENT.matcher(segment).matches()) {
            throw new IllegalArgumentException(
                "API version segment must be a non-negative decimal integer: " + whole);
        }
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("API version segment out of range: " + whole, e);
        }
    }

    /**
     * 判断本版本是否不低于给定档位（语义即 {@code compareTo(new ApiVersion(...)) >= 0}）：
     * 附属模组用它守门 —— 声称需要的 API 档位高于本模组提供的版本时自行降级或明确报错。
     * <p>Whether this version is at least the given tier ({@code compareTo(new
     * ApiVersion(...)) >= 0}): addons gate on it — degrade or fail with a clear
     * message when the tier they require exceeds the version this mod provides.</p>
     */
    public boolean isAtLeast(int major, int minor, int patch) {
        return compareTo(new ApiVersion(major, minor, patch)) >= 0;
    }

    /**
     * 按大改 → 新功能 → hotfix 的字典序全序比较（与 {@link #equals} 一致）。
     * <p>Compares lexicographically by major, then minor, then patch; consistent
     * with {@link #equals}.</p>
     */
    @Override
    public int compareTo(ApiVersion o) {
        int c = Integer.compare(major, o.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, o.minor);
        if (c != 0) {
            return c;
        }
        return Integer.compare(patch, o.patch);
    }

    /**
     * 版本串形态 {@code "x.y.z"}（如 {@code "0.1.0"}；{@link #parse} 的可回读形态）。
     * <p>The version string {@code "x.y.z"} (e.g. {@code "0.1.0"}; the form
     * {@link #parse} reads back).</p>
     */
    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
