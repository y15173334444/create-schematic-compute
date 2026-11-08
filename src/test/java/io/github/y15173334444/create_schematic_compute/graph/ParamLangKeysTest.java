package io.github.y15173334444.create_schematic_compute.graph;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 参数标签语言键完整性（防复发锚）：NodeType 的每个参数名 + 编辑区特用键（DSG 条件对
 * speed/amplitude、TEXT/DATA 的 text/color）在 zh_cn 与 en_us 两个语言文件里都必须有
 * {@code param.create_schematic_compute.<name>} 键且取值非空——缺键会在编辑区显示裸
 * i18n 键（2026-11-08 实测截图：vec3_const 的 x/y/z 等 19 键缺失）。
 * <p>
 * Param-label lang-key completeness (the anti-recurrence anchor): every NodeType param
 * name plus the edit-panel special keys (the DSG conditional pair speed/amplitude, the
 * TEXT/DATA text/color) must exist as {@code param.create_schematic_compute.<name>} with
 * a non-blank value in BOTH the zh_cn and en_us lang files — a missing key renders the
 * raw i18n key in the edit panel (the 2026-11-08 screenshot: 19 keys missing, including
 * vec3_const's x/y/z).
 */
class ParamLangKeysTest {

    private static JsonObject load(String lang) throws Exception {
        try (InputStream in = ParamLangKeysTest.class.getResourceAsStream(
                "/assets/create_schematic_compute/lang/" + lang + ".json")) {
            assertNotNull(in, "lang file missing: " + lang);
            return new Gson().fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class);
        }
    }

    private static void assertKey(JsonObject json, String lang, String key) {
        assertTrue(json.has(key), lang + " missing " + key);
        assertFalse(json.get(key).getAsString().isBlank(), lang + " blank value for " + key);
    }

    @Test
    @DisplayName("每个参数名在两种语言里都有非空标签键 / every param name has a non-blank label key in both languages")
    void everyParamNameHasLangKeys() throws Exception {
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            JsonObject json = load(lang);
            for (NodeType t : NodeType.values()) {
                for (String p : t.paramNames) {
                    assertKey(json, lang, "param.create_schematic_compute." + p);
                }
            }
            for (String p : new String[]{"speed", "amplitude", "text", "color"}) {
                assertKey(json, lang, "param.create_schematic_compute." + p);
            }
        }
    }
}
