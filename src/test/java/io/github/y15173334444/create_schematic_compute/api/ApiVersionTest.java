package io.github.y15173334444.create_schematic_compute.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApiVersion} 的回归锚：CURRENT 单源形态自检（升版不把形态改坏）、x.y.z
 * 解析契约（合法形态与拒绝面）、档位判别 {@code isAtLeast} 的边界矩阵、字典序
 * 全序与 {@code equals} 的一致性。
 * Regression anchors for {@link ApiVersion}: the CURRENT self-check on the x.y.z
 * shape (so a version bump can't silently break the format), the parse contract
 * (accepted and rejected shapes), the isAtLeast tier-gate boundary matrix, and the
 * lexicographic total order's consistency with equals.
 */
class ApiVersionTest {

    @Test
    @DisplayName("CURRENT 保持 x.y.z 形态且与自身档位持平 / CURRENT stays x.y.z-shaped and matches its own tier")
    void currentStaysWellFormed() {
        assertTrue(ApiVersion.CURRENT.toString().matches("\\d+\\.\\d+\\.\\d+"),
            "CURRENT must render as x.y.z: " + ApiVersion.CURRENT);
        assertEquals(ApiVersion.CURRENT, ApiVersion.parse(ApiVersion.CURRENT.toString()));
        assertTrue(ApiVersion.CURRENT.isAtLeast(ApiVersion.CURRENT.major(), ApiVersion.CURRENT.minor(), ApiVersion.CURRENT.patch()));
        assertFalse(ApiVersion.CURRENT.isAtLeast(ApiVersion.CURRENT.major(), ApiVersion.CURRENT.minor(), ApiVersion.CURRENT.patch() + 1));
    }

    @Test
    @DisplayName("解析接受三段非负整数 / parse accepts three non-negative segments")
    void parseAcceptsWellFormed() {
        assertEquals(new ApiVersion(0, 0, 0), ApiVersion.parse("0.0.0"));
        assertEquals(new ApiVersion(1, 0, 0), ApiVersion.parse("1.0.0"));
        assertEquals(new ApiVersion(12, 345, 6789), ApiVersion.parse("12.345.6789"));
    }

    @Test
    @DisplayName("解析拒绝形态不符（段数/非数字/负数/前后缀） / parse rejects malformed shapes (segment count, non-numeric, negatives, affixes)")
    void parseRejectsMalformed() {
        String[] bad = {"0.1", "0.1.0.0", "", "1", "a.b.c", "1.2.x", "1.-2.3",
            "1.2.3-beta", " 1.2.3", "1.2.3 ", "1.+2.3", "1..3", ".1.3"};
        for (String s : bad) {
            assertThrows(IllegalArgumentException.class, () -> ApiVersion.parse(s),
                "must reject: " + s);
        }
    }

    @Test
    @DisplayName("null 版本串抛 NPE / a null version string throws NPE")
    void parseRejectsNull() {
        assertThrows(NullPointerException.class, () -> ApiVersion.parse(null));
    }

    @Test
    @DisplayName("isAtLeast 档位判别按 x→y→z 边界收敛 / isAtLeast gates on the x→y→z boundary")
    void isAtLeastMatrix() {
        ApiVersion v = new ApiVersion(1, 0, 0);
        assertTrue(v.isAtLeast(1, 0, 0));
        assertTrue(v.isAtLeast(0, 9, 9));
        assertTrue(v.isAtLeast(0, 0, 0));
        assertFalse(v.isAtLeast(1, 0, 1));
        assertFalse(v.isAtLeast(1, 1, 0));
        assertFalse(v.isAtLeast(2, 0, 0));
    }

    @Test
    @DisplayName("compareTo 字典序全序且与 equals 一致 / compareTo is a lexicographic total order consistent with equals")
    void compareTotallyOrdered() {
        ApiVersion[] asc = {new ApiVersion(0, 0, 1), new ApiVersion(0, 1, 0), new ApiVersion(0, 2, 0),
            new ApiVersion(1, 0, 0), new ApiVersion(1, 0, 1), new ApiVersion(1, 1, 0), new ApiVersion(2, 0, 0)};
        for (int i = 0; i < asc.length; i++) {
            for (int j = 0; j < asc.length; j++) {
                int c = asc[i].compareTo(asc[j]);
                assertEquals(Integer.compare(i, j), c, asc[i] + " vs " + asc[j]);
                assertEquals(c == 0, asc[i].equals(asc[j]), asc[i] + " vs " + asc[j]);
            }
        }
    }
}
