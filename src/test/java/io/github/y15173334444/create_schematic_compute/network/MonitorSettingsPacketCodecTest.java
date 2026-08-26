package io.github.y15173334444.create_schematic_compute.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MonitorSettingsPacket 编解码往返测试：覆盖设置合并后的数据契约（merge-plan）——
 * HUD 玻璃面板参数（panelSizeX/Y、panelOffsetX/Y、panelDistance）已删除，玻璃与 3D
 * 悬浮屏幕共用 screen* 参数，包内仅剩 10 字段：8 3D + hudMode + virtualImageScale。
 * 往返必须逐字段一致；字节流顺序断言（vis 位于 hudMode 之后）。
 * CODEC round-trip: covers the post-merge data contract — the HUD glass-panel params
 * (panelSizeX/Y, panelOffsetX/Y, panelDistance) are gone; the glass shares the 3D
 * screen's screen* params, leaving 10 fields: 8 3D + hudMode + virtualImageScale.
 * Round-trip must match field-by-field; the byte-order assertion checks vis after hudMode.
 */
class MonitorSettingsPacketCodecTest {

    private static MonitorSettingsPacket sample() {
        return new MonitorSettingsPacket(
            new BlockPos(12, -34, 56),
            1.5f, 1.2f, 0.25f, 2.0f, -0.5f, 10f, 20f, 30f,
            true,
            2.5f // virtualImageScale
        );
    }

    @Test
    @DisplayName("round-trip preserves all 10 fields incl. virtualImageScale")
    void roundTrip() {
        var pkt = sample();
        ByteBuf buf = Unpooled.buffer();
        try {
            MonitorSettingsPacket.CODEC.encode(buf, pkt);
            var out = MonitorSettingsPacket.CODEC.decode(buf);

            assertEquals(pkt.pos(), out.pos());
            assertEquals(pkt.screenWidth(), out.screenWidth());
            assertEquals(pkt.screenLength(), out.screenLength());
            assertEquals(pkt.screenX(), out.screenX());
            assertEquals(pkt.screenY(), out.screenY());
            assertEquals(pkt.screenZ(), out.screenZ());
            assertEquals(pkt.screenRoll(), out.screenRoll());
            assertEquals(pkt.screenPitch(), out.screenPitch());
            assertEquals(pkt.screenYaw(), out.screenYaw());
            assertEquals(pkt.hudMode(), out.hudMode());
            // 唯一保留的 HUD 字段 / the only remaining HUD field
            assertEquals(2.5f, out.virtualImageScale());
        } finally {
            buf.release();
        }
    }

    @Test
    @DisplayName("virtualImageScale sits right after hudMode in the byte stream")
    void fieldOrder() {
        var pkt = sample();
        ByteBuf buf = Unpooled.buffer();
        try {
            MonitorSettingsPacket.CODEC.encode(buf, pkt);
            // pos(long) + 8 个 3D float + hudMode 1 bool + virtualImageScale 1 float
            // pos(long) + 8 3D floats + hudMode bool + virtualImageScale float
            assertEquals(8 + 8 * 4 + 1 + 4, buf.readableBytes());
            buf.skipBytes(8);      // BlockPos → long
            for (int i = 0; i < 8; i++) buf.skipBytes(4); // 3D floats
            buf.skipBytes(1);      // hudMode
            assertEquals(2.5f, buf.readFloat(), 0.0f);    // virtualImageScale
        } finally {
            buf.release();
        }
    }
}
