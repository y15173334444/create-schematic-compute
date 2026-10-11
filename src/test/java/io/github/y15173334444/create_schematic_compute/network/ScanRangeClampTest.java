package io.github.y15173334444.create_schematic_compute.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-3 回归：Sable 终端扫描的服务端半径钳制。
 * <p>P1-3 regressions: the server-side scan-radius clamp for the Sable terminal.</p>
 *
 * <p>安全语义：scanRange 原样可信 —— 超大值经 {@code rangeSq = scanRange * scanRange}
 * 的 int 溢出反而"全通过"，任意玩家可反复触发子级 BE 全量遍历。钳制与终端 UI 的
 * 输入范围（1..128）一致；扫描原点改用服务端玩家位置（处理器内，headless 不可直测，
 * 由代码审查覆盖 —— 客户端 pos 不再参与扫描）。</p>
 * <p>Security semantics: scanRange used to be trusted as-is — an oversized value sailed
 * through the {@code rangeSq = scanRange * scanRange} int overflow, letting any player
 * repeatedly drive full sub-level BE traversals. The clamp matches the terminal UI's input
 * range (1..128); the scan origin now comes from the server player position (inside the
 * handler, not directly testable headless — covered by review: the client pos no longer
 * participates in scanning).</p>
 */
class ScanRangeClampTest {

    @Test
    @DisplayName("半径钳制到 [1, 128]：负数/0 抬到 1，超大压到 128，界内原样")
    void radiusClampedToOneTwentyEight() {
        assertEquals(1, ScanSablePacket.clampScanRange(0));
        assertEquals(1, ScanSablePacket.clampScanRange(-5000));
        assertEquals(64, ScanSablePacket.clampScanRange(64));
        assertEquals(ScanSablePacket.MAX_SCAN_RANGE, ScanSablePacket.clampScanRange(128));
        assertEquals(ScanSablePacket.MAX_SCAN_RANGE, ScanSablePacket.clampScanRange(Integer.MAX_VALUE),
            "溢出面（2^31-1 平方）必须被上限压掉");
        assertEquals(ScanSablePacket.MAX_SCAN_RANGE, ScanSablePacket.clampScanRange(1 << 30));
    }
}
