package io.github.y15173334444.create_schematic_compute.blocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 私有系统引脚文本回归：PRIVATE_IN 输出 / PRIVATE_OUT 输入的引脚文本随频道承载类型切换——
 * 承载音频显「音频」，浮点维持「值」；判定（outAudio / inAudio）与引脚着色同源（NodeRenderer
 * 引脚循环），纯显示层不碰求值。
 * Private-system pin text regression: PRIVATE_IN's output / PRIVATE_OUT's input pin text
 * switches with the carried type — audio shows Audio, float keeps Val; the predicate shares
 * its source with the pin tint. Display-only, evaluation untouched.
 */
class NodeRendererPrivateInPinTextTest {

    private static final String AUDIO_KEY = "pin.create_schematic_compute.audio";
    private static final String VAL_KEY = "pin.create_schematic_compute.val";

    @Test
    @DisplayName("承载音频 → 「音频」键；浮点 → 维持节点自带标签 / audio-carrying → the Audio key; float → the node's own label")
    void pinTextSwitchesWithChannelType() {
        assertEquals(AUDIO_KEY, NodeRenderer.privateBandPinLabel(VAL_KEY, true),
            "承载音频显「音频」/ audio-carrying shows Audio");
        assertEquals(VAL_KEY, NodeRenderer.privateBandPinLabel(VAL_KEY, false),
            "浮点维持「值」/ float keeps Val");
    }

    @Test
    @DisplayName("两个 lang 文件里「音频」与「值」键都存在（防拼写漂移）/ both lang files carry the Audio and Val keys")
    void langKeysExistInBothLanguages() throws Exception {
        for (String f : new String[]{"en_us", "zh_cn"}) {
            String json = Files.readString(Path.of(
                "src/main/resources/assets/create_schematic_compute/lang/" + f + ".json"));
            assertTrue(hasStringKey(json, AUDIO_KEY), f + " 缺 " + AUDIO_KEY + " / missing " + AUDIO_KEY);
            assertTrue(hasStringKey(json, VAL_KEY), f + " 缺 " + VAL_KEY + " / missing " + VAL_KEY);
        }
    }

    /** JSON 里的精确键存在判定（键名后必须紧跟引号，防前缀误配）。 / exact key presence (the key must be quote-terminated). */
    private static boolean hasStringKey(String json, String key) {
        Matcher m = Pattern.compile(Pattern.quote("\"" + key + "\":")).matcher(json);
        return m.find();
    }
}
