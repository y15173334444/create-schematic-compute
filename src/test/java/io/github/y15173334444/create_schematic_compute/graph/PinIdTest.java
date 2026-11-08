package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * pinId 类型段助手测试：生成/解析往返、参段任意字符（含 ':'、'>'）、严格解析。
 * PinId type-segment helper: generate/parse round trips, arbitrary param characters
 * (incl. ':' and '>'), strict parsing.
 */
class PinIdTest {

    @Test
    @DisplayName("生成/解析往返：类型段+参段 / format-parse round trip")
    void roundTrip() {
        assertEquals("float:0", PinId.format(PinType.FLOAT, "0"));
        assertEquals("audio:band_0", PinId.format(PinType.AUDIO, "band_0"));
        assertEquals("vec3:12", PinId.format(PinType.VEC3, "12"));

        assertEquals(PinType.FLOAT, PinId.typeOf("float:0"));
        assertEquals("0", PinId.paramOf("float:0"));
        assertEquals(PinType.AUDIO, PinId.typeOf("audio:band_0"));
        assertEquals("band_0", PinId.paramOf("audio:band_0"));
    }

    @Test
    @DisplayName("参段可含 ':' 与 '>'——按第一个冒号切分 / params may contain ':' and '>' — split at the first colon")
    void paramMayContainSeparator() {
        String pid = PinId.format(PinType.FLOAT, "out>a:b");
        assertEquals("float:out>a:b", pid);
        assertEquals(PinType.FLOAT, PinId.typeOf(pid));
        assertEquals("out>a:b", PinId.paramOf(pid));

        String bandNamed = PinId.format(PinType.AUDIO, "float:x");
        assertEquals("audio:float:x", bandNamed);
        assertEquals(PinType.AUDIO, PinId.typeOf(bandNamed));
        assertEquals("float:x", PinId.paramOf(bandNamed));
    }

    @Test
    @DisplayName("严格解析：未知类型码 / strict parsing: unknown type codes")
    void unknownCodeIsUntyped() {
        assertNull(PinId.typeOf("foo:x"), "unknown code must not parse as typed");
        assertFalse(PinId.isTyped("foo:x"));
        // 裸参读取：无段形态整串即参段（旧档频段名可含 ':'，不能被误剥）
        // Bare-param reading: an untyped shape IS its own param (legacy band names may
        // contain ':' — they must never be mis-stripped).
        assertEquals("foo:x", PinId.paramOf("foo:x"));
    }

    @Test
    @DisplayName("旧格式（无段形态）裸参读取 / legacy untyped shapes read as bare params")
    void bareLegacyShapes() {
        assertNull(PinId.typeOf("band_0"));
        assertEquals("band_0", PinId.paramOf("band_0"));
        assertEquals("A", PinId.paramOf("A"));
        assertEquals("2", PinId.paramOf("2"));
        assertFalse(PinId.isTyped("2"));
    }
}
