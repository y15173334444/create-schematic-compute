package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AudioRef 值语义测试：整形曲线链随引用走值相等（{@link AudioCurve.Curve} 自带内容相等），
 * 追加与增益替换都不丢链。 / AudioRef value semantics: the shaping chain compares by content
 * ({@link AudioCurve.Curve} carries value equality) and survives appends and gain copies.
 */
class AudioRefTest {

    @Test
    @DisplayName("曲线链按内容相等 / the curve chain compares by content")
    void waveCurvesValueEquality() {
        AudioRef a = new AudioRef(List.of(), 1f)
            .withWaveCurve(new AudioCurve.Curve(new float[]{0f, 1f}, new float[]{0f, 2f}));
        AudioRef b = new AudioRef(List.of(), 1f)
            .withWaveCurve(new AudioCurve.Curve(new float[]{0f, 1f}, new float[]{0f, 2f}));
        assertEquals(a, b, "distinct arrays with equal content are equal refs");
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, a.withWaveCurve(new AudioCurve.Curve(new float[]{0f, 1f}, new float[]{0f, 3f})),
            "different content is not equal");
    }

    @Test
    @DisplayName("空链与有链不等价 / an empty chain is not a shaped ref")
    void emptyChainDistinct() {
        AudioRef plain = new AudioRef(List.of(), 1f);
        AudioRef shaped = plain.withWaveCurve(new AudioCurve.Curve(new float[]{0f, 1f}, new float[]{0f, 1f}));
        assertNotEquals(plain, shaped);
    }

    @Test
    @DisplayName("追加入链尾、withGain 保留链 / appends land at the tail; withGain keeps the chain")
    void appendAndGain() {
        AudioCurve.Curve first = new AudioCurve.Curve(new float[]{0f, 1f}, new float[]{0f, 2f});
        AudioCurve.Curve second = new AudioCurve.Curve(new float[]{0f, 1f}, new float[]{0f, 0.5f});
        AudioRef a = new AudioRef(List.of(), 1f).withWaveCurve(first).withWaveCurve(second);
        assertEquals(List.of(first, second), a.waveCurves(), "application order is append order");
        assertEquals(a.withGain(2f), new AudioRef(List.of(), 1f)
            .withWaveCurve(first).withWaveCurve(second).withGain(2f));
        assertNotEquals(a.withGain(2f), a.withGain(3f));
    }
}
