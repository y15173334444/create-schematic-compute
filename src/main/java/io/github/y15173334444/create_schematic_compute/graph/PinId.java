package io.github.y15173334444.create_schematic_compute.graph;

/** pinId 形态助手：{@code 类型码 ":" 参段}（设计文档 docs/pin-type-segment-plan.md §3）。
 *  <p>参段是引脚的稳定标识（变量名/频段名/子节点 id/十进制索引），<b>可含任意字符</b>——
 *  含 {@code ':'}、{@code '>'} 均无碍：解析按<b>第一个</b> {@code ':'} 切分（类型码是闭集
 *  标识符，不含分隔符）。未知类型码 = 解析失败（严格口径——旧格式兼容只走 GraphMigration）。</p>
 *  <p>PinId shape helper: {@code type-code ":" param} (design doc
 *  docs/pin-type-segment-plan.md §3). The param is the pin's stable identity (variable
 *  name / band name / sub-node id / decimal index) and may contain <b>any character</b>
 *  — including {@code ':'} and {@code '>'}: parsing splits at the <b>first</b> {@code ':'}
 *  (type codes are closed-set identifiers and never contain the separator). An unknown
 *  type code means parse failure (strict — legacy-format compatibility lives only in
 *  GraphMigration).</p>
 */
public final class PinId {

    private PinId() {}

    /** 类型段与参段的分隔符 / separator between the type segment and the param. */
    private static final char SEP = ':';

    /** 生成 pinId：{@code 类型码:参段}。 / Build a pinId: {@code type-code:param}. */
    public static String format(PinType type, String param) {
        return type.code + SEP + param;
    }

    /** 取类型段。无段形态（旧格式/畸形）返回 null。 / Read the type segment; untyped
     *  (legacy/malformed) shapes return null. */
    public static PinType typeOf(String pinId) {
        if (pinId == null) return null;
        int at = pinId.indexOf(SEP);
        return at <= 0 ? null : PinType.byCode(pinId.substring(0, at));
    }

    /** 取参段：带段形态剥去类型码；无段形态（旧格式）原样返回（迁移的裸参读取面）。
     *  Read the param: strips the type code from typed shapes; untyped (legacy) shapes
     *  return themselves (the bare-param reading used by migration). */
    public static String paramOf(String pinId) {
        if (pinId == null) return null;
        int at = pinId.indexOf(SEP);
        if (at <= 0) return pinId;
        return PinType.byCode(pinId.substring(0, at)) == null ? pinId : pinId.substring(at + 1);
    }

    /** 是否为合法带段形态（类型码可解析）。 / Whether this is a well-formed typed shape. */
    public static boolean isTyped(String pinId) {
        return typeOf(pinId) != null;
    }
}
