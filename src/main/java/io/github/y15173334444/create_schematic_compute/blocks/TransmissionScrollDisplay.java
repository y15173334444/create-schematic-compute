package io.github.y15173334444.create_schematic_compute.blocks;

/**
 * 变速器滚轮值盒的**显示文本规则**（单一真相源，纯类可单测）。
 * Single source of truth for the programmable transmission's scroll value-box text
 * (pure class, unit-testable).
 *
 * <p>值盒那串字完全由我方决定：Create 的 {@code ScrollValueRenderer} 只做
 * {@code Component.literal(behaviour.formatValue())}（桌面源码
 * {@code scrollValue/ScrollValueRenderer.java:92}），所以「显示什么」是本地规则；而
 * 「谁在控制」（节点图 / 滚轮）来自服务端同步的代理态布尔。两者在此汇合。</p>
 * <p>The box text is entirely ours: Create's {@code ScrollValueRenderer} merely wraps
 * {@code behaviour.formatValue()} in {@code Component.literal}, so "what to show" is a local
 * rule, while "who is in control" (graph vs scroll wheel) is the server-synced proxy flag.
 * They meet here.</p>
 *
 * <p><b>符号 / Sign</b>：两种态都取绝对值（官方 SC 口径 {@code Math.abs}，
 * {@code KineticScrollValueBehaviour.java:21}）—— 盒内数字恒为转速<b>大小</b>；
 * 正负号属于「以输出面为参考」的另一套语义（见
 * {@code ProgrammableTransmissionBlockEntity.getDesiredOutputSpeed}），不混进这个格子。
 * Both modes take the absolute value (official SC behaviour): the number is always a
 * magnitude; the output-face-relative sign stays out of this box.</p>
 *
 * <p><b>纯类 / pure class</b>：不引用 Minecraft / Create / 客户端任何类型，只吃
 * {@code int} 与已解析好的后缀字符串，因此可安全用在服务端路径与单元测试里
 * （同 {@code KineticGaugeStates} 的约定）。</p>
 */
public final class TransmissionScrollDisplay {

    private TransmissionScrollDisplay() {}

    /**
     * 值盒文本：{@code |value|}，代理态追加后缀（如 {@code "64 (代理)"}）。
     * Value-box text: {@code |value|}, with the localised suffix appended while proxied.
     *
     * <p>字号提醒：{@code TextValueBox} 会按字符串宽度自动缩字号
     * （{@code ValueBox.java:187-194}，{@code scale = lineHeight / width}），四个字符的后缀
     * 会把字号压到纯数字形态的约 1/3 —— 这是「数字 + (代理)」形态的既定代价。</p>
     * <p>Font-size note: {@code TextValueBox} auto-scales by string width, so a four-character
     * suffix shrinks the glyphs to roughly a third of the number-only form — the accepted cost
     * of the "number + (proxy)" shape.</p>
     *
     * @param value       要显示的生效值：代理态 = 已应用目标，手动态 = 滚轮值
     *                    the effective value: applied target while proxied, scroll value otherwise
     * @param proxied     节点图是否正在代理控制 / whether the graph drives the target
     * @param proxySuffix 已解析的本地化后缀（调用方查语言表，保持本类不碰 i18n）
     *                    resolved localised suffix (the caller owns the lang lookup)
     */
    public static String valueBoxText(int value, boolean proxied, String proxySuffix) {
        // 走 long 取绝对值：int 的 Math.abs(Integer.MIN_VALUE) 仍返回 MIN_VALUE（负），
        // 会把这个盒子唯一的硬规则「符号不外泄」撕开一个口子。当前值被
        // ±maxRotationSpeed 钳住够不着，但规则本身不该有反例。
        // Absorb through long: Math.abs(Integer.MIN_VALUE) still returns MIN_VALUE (negative)
        // and would tear a hole in this box's one hard rule ("never leak a sign"). Reachable
        // values are clamped by ±maxRotationSpeed today, but a rule should have no counterexample.
        long magnitude = Math.abs((long) value);
        return proxied ? magnitude + " " + proxySuffix : Long.toString(magnitude);
    }
}
