package io.github.y15173334444.create_schematic_compute.client.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.y15173334444.create_schematic_compute.client.editor.NodeRenderer;
import io.github.y15173334444.create_schematic_compute.graph.PinType;

/**
 * 私有系统引脚文本回归：PRIVATE_IN 输出 / PRIVATE_OUT 输入的引脚文本随声明类型切换——
 * 音频显「音频」、向量显「向量」、浮点维持「值」；判定与引脚着色同源（NodeRenderer
 * 引脚循环），纯显示层不碰求值。
 * Private-system pin text regression: PRIVATE_IN's output / PRIVATE_OUT's input pin text
 * switches with the declared type — audio shows Audio, vector shows Vec, float keeps Val;
 * the predicate shares its source with the pin tint. Display-only, evaluation untouched.
 */
class NodeRendererPrivateInPinTextTest {

    private static final String AUDIO_KEY = "pin.create_schematic_compute.audio";
    private static final String VAL_KEY = "pin.create_schematic_compute.val";
    private static final String VEC_KEY = "pin.create_schematic_compute.vec";

    @Test
    @DisplayName("声明类型切文本：音频/向量/浮点 / declared type switches the text: audio, vector, float")
    void pinTextSwitchesWithChannelType() {
        assertEquals(AUDIO_KEY, NodeRenderer.privateBandPinLabel(VAL_KEY, PinType.AUDIO),
            "承载音频显「音频」/ audio-carrying shows Audio");
        assertEquals(VEC_KEY, NodeRenderer.privateBandPinLabel(VAL_KEY, PinType.VEC3),
            "承载向量显「向量」/ vector-carrying shows Vec");
        assertEquals(VAL_KEY, NodeRenderer.privateBandPinLabel(VAL_KEY, PinType.FLOAT),
            "浮点维持「值」/ float keeps Val");
    }

    @Test
    @DisplayName("两个 lang 文件里「音频」「向量」「值」键都存在（防拼写漂移）/ both lang files carry the Audio, Vec and Val keys")
    void langKeysExistInBothLanguages() throws Exception {
        for (String f : new String[]{"en_us", "zh_cn"}) {
            String json = Files.readString(Path.of(
                "src/main/resources/assets/create_schematic_compute/lang/" + f + ".json"));
            assertTrue(hasStringKey(json, AUDIO_KEY), f + " 缺 " + AUDIO_KEY + " / missing " + AUDIO_KEY);
            assertTrue(hasStringKey(json, VAL_KEY), f + " 缺 " + VAL_KEY + " / missing " + VAL_KEY);
            assertTrue(hasStringKey(json, VEC_KEY), f + " 缺 " + VEC_KEY + " / missing " + VEC_KEY);
        }
    }

    /** JSON 里的精确键存在判定（键名后必须紧跟引号，防前缀误配）。 / exact key presence (the key must be quote-terminated). */
    private static boolean hasStringKey(String json, String key) {
        Matcher m = Pattern.compile(Pattern.quote("\"" + key + "\":")).matcher(json);
        return m.find();
    }
}
