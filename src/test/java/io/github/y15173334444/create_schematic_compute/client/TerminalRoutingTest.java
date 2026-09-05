package io.github.y15173334444.create_schematic_compute.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 便携终端「编辑」按钮路由表的回归测试（用户症状：变速器/齿轮箱在主世界点击编辑
 * 无反应 —— 变速器被 "Program" 子串抢先命中而错路由到程序计算机界面，其 tick 首帧
 * {@code isBlockEntityValid()} 即 false 而闪退；齿轮箱无任何命中而静默不打开）。
 * <p>
 * 锁定两件事：① 每个已知图设备 BE（含 Sable 变体）必须命中正确的路由键；
 * ② 歧义防护 —— 路由键数组必须「先专后泛」排序（Transmission 先于 Program），
 * 否则 {@code ProgrammableTransmissionBlockEntity} 会再次被错误捕获。新增设备
 * 类型时必须同步扩展 {@code ROUTE_KEYS} 与本表的期望映射。
 * <p>
 * Regression test for the terminal edit-button routing table (user symptom: clicking
 * edit on a transmission/CNC gearbox did nothing in the overworld — the transmission
 * was captured by the "Program" substring and mis-routed to the program-computer
 * screen whose first tick immediately closed it; the gearbox matched nothing and
 * silently failed to open). Locks: ① every known graph-device BE (incl. Sable
 * variants) routes to the right key; ② ambiguity guard — ROUTE_KEYS must stay
 * specific-before-generic (Transmission before Program). New device types must
 * extend ROUTE_KEYS and the expected mapping here together.
 */
class TerminalRoutingTest {

    /** 已知设备 BE 简单类名 → 期望路由键。 / known device BE simple name → expected route key. */
    private static final String[][] EXPECTED = {
        {"MonitorBlockEntity", "Monitor"},
        {"MonitorBlockEntitySable", "Monitor"},
        {"RadarBlockEntity", "Radar"},
        {"RadarBlockEntitySable", "Radar"},
        {"ControlSeatBlockEntity", "ControlSeat"},
        {"ControlSeatBlockEntitySable", "ControlSeat"},
        {"SensorBlockEntity", "Sensor"},
        {"SensorBlockEntitySable", "Sensor"},
        {"BlueprintBlockEntity", "Blueprint"},
        {"ProgramComputerBlockEntity", "Program"},
        {"ProgrammableTransmissionBlockEntity", "Transmission"},
        {"CncGearboxBlockEntity", "CncGearbox"},
        {"SpeedProxyBlockEntity", "SpeedProxy"},
    };

    @Test
    @DisplayName("每个已知设备 BE 都命中正确的路由键")
    void everyKnownDeviceRoutesCorrectly() {
        for (String[] e : EXPECTED) {
            assertEquals(e[1], PortableTerminalScreen.routeKey(e[0]),
                "BE " + e[0] + " must route to " + e[1]);
        }
    }

    @Test
    @DisplayName("变速器不得被 Program 抢先命中（先专后泛的顺序约束）")
    void transmissionMustNotBeCapturedByProgram() {
        // 回归锚点：历史 bug 是 "Program" 先于 "Transmission" 命中
        // Regression anchor: "Program" used to match before "Transmission"
        assertEquals("Transmission", PortableTerminalScreen.routeKey("ProgrammableTransmissionBlockEntity"));
        assertNotEquals("Program", PortableTerminalScreen.routeKey("ProgrammableTransmissionBlockEntity"));
    }

    @Test
    @DisplayName("非设备类名返回 null（不打开任何界面）")
    void nonDeviceNamesYieldNull() {
        assertNull(PortableTerminalScreen.routeKey("ChestBlockEntity"));
        assertNull(PortableTerminalScreen.routeKey("BannerBlockEntity"));
        assertNull(PortableTerminalScreen.routeKey(""));
    }

    // ── Sable 设备类名解析（resolveBeClass）────────────────────────────
    //    历史回归：可编程变速器/数控齿轮箱上线后未补进这张表，Sable 结构列表把它们
    //    静默丢弃了很久（服务端扫描正常、客户端合并时 cls==null 被跳过）。

    @Test
    @DisplayName("resolveBeClass: 每个已知设备类名（含 Sable 变体去后缀）都解析成功")
    void resolveBeClassCoversAllDevices() {
        String[] known = {
            "BlueprintBlockEntity", "ProgramComputerBlockEntity", "SpeedProxyBlockEntity",
            "SensorBlockEntity", "ControlSeatBlockEntity", "MonitorBlockEntity",
            "RadarBlockEntity", "ProgrammableTransmissionBlockEntity", "CncGearboxBlockEntity",
            // Sable 变体：去后缀后必须映射到同一 BE 类
            "MonitorBlockEntitySable", "ControlSeatBlockEntitySable",
            "RadarBlockEntitySable", "SensorBlockEntitySable",
            "ProgrammableTransmissionBlockEntitySable", "CncGearboxBlockEntitySable",
        };
        for (String n : known)
            assertNotNull(PortableTerminalScreen.resolveBeClass(n),
                "Sable 结构设备 '" + n + "' 必须可解析，否则会从结构列表里静默消失");
    }

    @Test
    @DisplayName("resolveBeClass: Sable 变体映射到去后缀的同一 BE 类")
    void sableVariantMapsToSameClass() {
        assertSame(PortableTerminalScreen.resolveBeClass("MonitorBlockEntity"),
            PortableTerminalScreen.resolveBeClass("MonitorBlockEntitySable"));
        assertSame(PortableTerminalScreen.resolveBeClass("CncGearboxBlockEntity"),
            PortableTerminalScreen.resolveBeClass("CncGearboxBlockEntitySable"));
    }

    @Test
    @DisplayName("resolveBeClass: 未知名返回 null")
    void resolveBeClassUnknownYieldsNull() {
        assertNull(PortableTerminalScreen.resolveBeClass("ChestBlockEntity"));
        assertNull(PortableTerminalScreen.resolveBeClass("NotADeviceSable"));
    }
}
