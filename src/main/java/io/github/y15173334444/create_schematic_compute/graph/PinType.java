package io.github.y15173334444.create_schematic_compute.graph;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** 引脚载荷类型（pinId 类型段的取值域）：FLOAT（普通浮点）/ AUDIO（音源句柄）/ VEC3（三分量向量），
 *  外加声明表专用的 EMPTY（空/未声明，不参与载荷分派）。
 *  连线合法性、传输载体选择、节点消费三处按它统一分派。追加型枚举——新载荷类型只加值，
 *  稳定码（NBT/网络/pinId 类型段共用）永不变。
 *  <p>Pin payload type (the pinId type segment's value domain): FLOAT (plain float) /
 *  AUDIO (source handle) / VEC3 (three-component vector), plus the declaration-table-only
 *  EMPTY (empty/undeclared, never dispatched as payload). Connection legality, carrier
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
    VEC3("vec3"),
    /** 空（未声明）态：新频段的初始声明；连接时按对端引脚类型自动声明（SET_PIN_TYPE 拒收回空，
     *  声明只许 空→实）。只存在于声明表槽位，不参与载荷分派——读取侧一律按 FLOAT 行为
     *  （求值不断档），展示面显灰色。
     *  Empty (undeclared) state: a fresh band's initial declaration, auto-declared from the
     *  peer pin's type at connect time (SET_PIN_TYPE refuses to set it back — declarations
     *  only ever go empty→real). Lives only in the declaration table and never in payload
     *  dispatch — the read side always behaves as FLOAT (evaluation never breaks) and the
     *  display face shows grey. */
    EMPTY("empty");

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

    /** 读取侧引脚的**生效类型**（着色/文本的合并口径，单一源）：定义同步值 ∨ 本地声明，
     *  择强序 AUDIO > VEC3 > 显式本地声明 > 定义同步值——「属性跟频道走，不跟本地接线走」的
     *  展示面。空（{@link #EMPTY}）是最弱态：本地空声明让位给频道定义，两侧皆空才显灰。
     *  {@code synced} 为 null = 无定义同步值（发布侧）。
     *  Effective type of a read-side pin (the single source for tint/text merging):
     *  the definition-synced value ∨ the local declaration, taking the stronger under
     *  AUDIO > VEC3 > explicit local > synced — the display face of "the property rides
     *  the channel, not local wiring". {@link #EMPTY} is the weakest state: an empty local
     *  declaration yields to the channel definition, and grey shows only when both sides
     *  are empty. A null {@code synced} means no definition value (publish side). */
    public static PinType effective(PinType local, PinType synced) {
        if (local == AUDIO || synced == AUDIO) return AUDIO;
        if (local == VEC3 || synced == VEC3) return VEC3;
        if (local != null && local != EMPTY) return local;
        return synced != null && synced != EMPTY ? synced : EMPTY;
    }

    /** 连接时声明的**纯判定**（全切换，单一真相源）：对端有显式类型且与槽位现声明不同 →
     *  返回应声明的类型（空槽补声明、显式声明随连接改写）；对端为空/无类型或同型 → null
     *  （不动、免发空 op）。手势、撤销重接、粘贴、远端 ADD_CONN 全部经此判定。
     *  The pure connect-declaration decision (full switch, single source): returns the type
     *  to declare when the peer carries an explicit type differing from the slot's current
     *  declaration (empty slots fill, explicit declarations rewrite); null when the peer is
     *  empty/typeless or already matching (no change, no pointless op). Gestures, undo
     *  rewires, pastes and remote ADD_CONNs all decide here. */
    public static PinType autoDeclareFrom(PinType declared, PinType peer) {
        if (peer != null && peer != EMPTY && peer != declared) return peer;
        return null;
    }
}
