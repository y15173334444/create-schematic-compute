package io.github.y15173334444.create_schematic_compute.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 设备显示名拼装规则的回归测试：类型名恒显示（其他玩家据此知道设备原本是什么方块），
 * 自定义名非空时跟在后面。服务端 Sable 扫描与客户端本地扫描两条路径共用此规则
 * （{@code SablePacketHelper.formatDeviceDisplay}），格式漂移会让两条路径的列表
 * 对不上、搜索行为不一致。
 * <p>
 * Regression tests for the device display-name assembly: the block type name always
 * shows (so other players can tell what the device originally was), with the custom
 * name following when present. Both naming paths (server Sable scan and client local
 * scan) share {@code SablePacketHelper.formatDeviceDisplay}; format drift would make
 * the two paths' lists disagree and search behave inconsistently.
 */
class DeviceDisplayFormatTest {

    @Test
    @DisplayName("无自定义名 → 只显示类型名")
    void typeNameOnlyWhenNoCustomName() {
        assertEquals("数控齿轮箱", SablePacketHelper.formatDeviceDisplay("数控齿轮箱", ""));
        assertEquals("可编程变速器", SablePacketHelper.formatDeviceDisplay("可编程变速器", null));
    }

    @Test
    @DisplayName("有自定义名 → 类型名 · 自定义名")
    void customNameFollowsTypeName() {
        assertEquals("数控齿轮箱 · 结构上的那台",
            SablePacketHelper.formatDeviceDisplay("数控齿轮箱", "结构上的那台"));
    }

    @Test
    @DisplayName("自定义名保留原文（含中文与空格，不做裁剪）")
    void customNameKeptVerbatim() {
        assertEquals("Monitor · 我的 显示器",
            SablePacketHelper.formatDeviceDisplay("Monitor", "我的 显示器"));
    }
}
