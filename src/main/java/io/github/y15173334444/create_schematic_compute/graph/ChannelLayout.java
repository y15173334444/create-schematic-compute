package io.github.y15173334444.create_schematic_compute.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>声道拆分标准布局</b>（plan R3）——CHANNEL 节点 {@code params[0]} 的规范语义与各声道的
 * 拆分口径。<b>纯 Java，无 MC 依赖</b>（可在无 bootstrap 的 JUnit 里测试）。
 * <p><b>Standard channel layouts</b> for the CHANNEL node: the layout preset stored in
 * {@code params[0]} decides the output pin set, and every channel's split law lives here
 * as a pure function.</p>
 *
 * <p>布局与引脚序 / Layouts &amp; pin order：</p>
 * <ul>
 *   <li>{@link #MIX}（聚合）：mix —— 恒等。</li>
 *   <li>{@link #STEREO}（2.0）：l, r —— 等功率声像拆分（NBS pan 0–200 → 归一 p，全带）。</li>
 *   <li>{@link #QUAD}（4.0）：l, r, ls, rs —— l/r 同上；ls/rs 收极左/极右溢出带。</li>
 *   <li>{@link #SURROUND_5_1}：l, r, c, sub, ls, rs —— l/c/r 改 LCR 三角权重（能量守恒）。</li>
 *   <li>{@link #SURROUND_7_1}:在 5.1 基础上加侧环绕 sl, sr（与 ls/rs 同口径，见下）。</li>
 * </ul>
 *
 * <p>拆分口径（全部只依赖音符自身的 pan / 乐器 / 键位，布局间正交）：</p>
 * <ul>
 *   <li><b>l / r</b>：布局不含 c 时为等功率对（cos/sin(p·π/2)，立体声正统口径）；含 c 时改
 *       LCR 三角（p&lt;0.5：L=cos(pπ)、C=sin(pπ)；p≥0.5：C=cos((p−0.5)π)、R=sin((p−0.5)π)），
 *       任意 p 处 L²+C²+R²=1，中心能量归 c 而不是三份重叠。</li>
 *   <li><b>c</b>：LCR 的中心分量（中心提取）。NBS 声像是一维的，这是它唯一自然的中心语义。</li>
 *   <li><b>sub</b>：低音乐器（bass / basedrum）或极低键位（key &lt; 24）的音符全增益进入，
 *       其余不进——NBS 没有频谱信息，乐器/音域是唯一可靠的「低音」判据。</li>
 *   <li><b>ls / rs</b>：极左/极右溢出带（p&lt;0.25 余弦渐入至 1，其余不进，右对称）——作曲者把
 *       音符打到极左/极右即为环绕意图。</li>
 *   <li><b>sl / sr</b>：与 ls/rs 同口径。NBS 声像无深度信息，侧/后环放在声学上无法区分，
 *       空间感靠对应音响的摆放位置实现。</li>
 *   <li>未知名兜底全量（与旧 MVP 行为一致，向前兼容）。</li>
 * </ul>
 */
public final class ChannelLayout {

    /** 布局序号 = CHANNEL params[0] 语义。Layout ordinal, stored in CHANNEL params[0]. */
    public static final int MIX = 0;
    public static final int STEREO = 1;
    public static final int QUAD = 2;
    public static final int SURROUND_5_1 = 3;
    public static final int SURROUND_7_1 = 4;

    /** 每布局的输出引脚序（成员名与 {@link NodeType#CHANNEL_PIN_IDS} 一致）。
     *  Output pin ids per layout (member names match {@link NodeType#CHANNEL_PIN_IDS}). */
    private static final String[][] LAYOUT_PINS = {
        {"mix"},
        {"l", "r"},
        {"l", "r", "ls", "rs"},
        {"l", "r", "c", "sub", "ls", "rs"},
        {"l", "r", "c", "sub", "ls", "rs", "sl", "sr"},
    };

    private ChannelLayout() {}

    /** 布局总数。Number of layouts. */
    public static int layoutCount() { return LAYOUT_PINS.length; }

    /** 布局的输出声道 pinId 序（越界钳到有效范围）。Pin ids of a layout (out-of-range clamped). */
    public static String[] pins(int layout) {
        return LAYOUT_PINS[Math.max(0, Math.min(LAYOUT_PINS.length - 1, layout))];
    }

    /** 布局是否含中置声道（决定 l/r 用等功率对还是 LCR）。Whether the layout has a center channel. */
    public static boolean hasCenter(int layout) { return layout == SURROUND_5_1 || layout == SURROUND_7_1; }

    /**
     * 声道拆分：把多声道音源按声道名拆出单个声道（mix 恒等；权重口径见类注）。
     * 权重低于 −80 dB（数值噪声级，如 cos(π/2) 的 6e-17）的音符不进入结果（筛选语义，
     * 下游包更小）；未知名兜底全量。
     * Split the source into one channel by name; notes below the −80 dB threshold
     * (float noise like cos(π/2)) are dropped.
     */
    public static AudioRef channelRef(AudioRef in, String channel, boolean hasCenter) {
        if (in == null) return AudioRef.EMPTY;
        if (in.stopSignal()) return in; // 停止标记原样穿透（事件恒空）/ the stop marker passes unchanged
        if (in.isEmpty() || channel == null) return AudioRef.EMPTY;
        if ("mix".equals(channel)) return in;
        if (!isKnownChannel(channel)) return in;
        List<NoteEvent> out = new ArrayList<>(in.events().size());
        for (NoteEvent e : in.events()) {
            float w = weight(e, channel, hasCenter);
            if (w > WEIGHT_FLOOR) out.add(e.withGain(e.gain() * w));
        }
        return new AudioRef(List.copyOf(out), in.gain(), in.stopSignal());
    }

    /** 权重地板：低于此值视为数值噪声（−80 dB），不进该声道。
     *  Weight floor: below this a note is numeric noise (−80 dB), not routed. */
    private static final float WEIGHT_FLOOR = 1e-4f;

    private static boolean isKnownChannel(String channel) {
        for (String id : NodeType.CHANNEL_PIN_IDS) if (id.equals(channel)) return true;
        return false;
    }

    /** 单音符在某声道的权重（0 = 不进该声道）。Per-note weight in a channel (0 = excluded). */
    private static float weight(NoteEvent e, String channel, boolean hasCenter) {
        float p = e.panning() / 200f; // 0=左 .. 0.5=中 .. 1=右 / 0=left .. 0.5=center .. 1=right
        return switch (channel) {
            case "l" -> hasCenter ? lcrLeft(p) : equalPowerLeft(p);
            case "r" -> hasCenter ? lcrRight(p) : equalPowerRight(p);
            case "c" -> lcrCenter(p);
            case "sub" -> isBass(e) ? 1f : 0f;
            case "ls", "sl" -> sideLeft(p);
            case "rs", "sr" -> sideRight(p);
            default -> 1f;
        };
    }

    /** 低音判据：bass / basedrum 乐器，或极低键位（key &lt; 24）。Bass test: instrument or very low key. */
    private static boolean isBass(NoteEvent e) {
        return e.instrument() == 1 || e.instrument() == 2 || e.key() < 24;
    }

    /** 等功率立体声对（布局不含 c 时的 l/r，NBS 同款）。Equal-power stereo pair (no-center layouts). */
    private static float equalPowerLeft(float p)  { return (float) Math.cos(p * Math.PI / 2); }
    private static float equalPowerRight(float p) { return (float) Math.sin(p * Math.PI / 2); }

    /** LCR 三角权重（含 c 布局）：L/C/R 能量守恒，中心能量归 c。LCR triangle law (center layouts). */
    private static float lcrLeft(float p)   { return p < 0.5f ? (float) Math.cos(p * Math.PI) : 0f; }
    private static float lcrCenter(float p) { return p < 0.5f ? (float) Math.sin(p * Math.PI)
                                                            : (float) Math.cos((p - 0.5f) * Math.PI); }
    private static float lcrRight(float p)  { return p >= 0.5f ? (float) Math.sin((p - 0.5f) * Math.PI) : 0f; }

    /** 极左/极右溢出带（p&lt;0.25 余弦渐入；sl/sr 同口径，区分靠音响摆放）。
     *  Hard-left/right overflow bands; sl/sr share the law (placement decides acoustics). */
    private static float sideLeft(float p)  { return p < 0.25f ? (float) Math.cos(p / 0.25f * Math.PI / 2) : 0f; }
    private static float sideRight(float p) { return p > 0.75f ? (float) Math.cos((1f - p) / 0.25f * Math.PI / 2) : 0f; }
}
