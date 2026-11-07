package io.github.y15173334444.create_schematic_compute.graph;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** 引脚载荷类型（pinId 类型段的取值域）：FLOAT（普通浮点）/ AUDIO（音源句柄）/ VEC3（三分量向量）。
 *  连线合法性、传输载体选择、节点消费三处按它统一分派。追加型枚举——新载荷类型只加值，
 *  稳定码（NBT/网络/pinId 类型段共用）永不变。
 *  <p>Pin payload type (the pinId type segment's value domain): FLOAT (plain float) /
 *  AUDIO (source handle) / VEC3 (three-component vector). Connection legality, carrier
 *  selection and node consumption all dispatch on it. Append-only — new payload types
 *  just add values; the stable codes (shared by NBT, the wire and the pinId type segment)
 *  never change.</p>
 */
public enum PinType {
    /** 普通浮点（输出数组通道）/ plain float (the output-array channel). */
    FLOAT("float"),
    /** 音源句柄 {@link AudioRef}（类型化载荷旁路）/ audio source handle (typed-payload side channel). */
    AUDIO("audio"),
    /** 三分量向量 {@link Vec3f}（类型化载荷旁路）/ three-component vector (typed-payload side channel). */
    VEC3("vec3");

    /** 稳定类型码——pinId 类型段、NBT、网络同步共用，永不变。 / Stable type code shared by
     *  the pinId type segment, NBT and the wire; never change. */
    public final String code;

    PinType(String code) { this.code = code; }

    private static final Map<String, PinType> BY_CODE;
    static {
        var m = new HashMap<String, PinType>();
        for (PinType t : values()) if (m.put(t.code, t) != null)
            throw new IllegalStateException("Duplicate PinType code: " + t.code);
        BY_CODE = Collections.unmodifiableMap(m);
    }

    /** 稳定码 → 类型；未知码返回 null（严格解析——兼容的唯一入口是迁移）。
     *  Code → type; unknown codes return null (strict parsing — migration is the only
     *  compatibility path). */
    public static PinType byCode(String code) {
        return code == null ? null : BY_CODE.get(code);
    }
}
