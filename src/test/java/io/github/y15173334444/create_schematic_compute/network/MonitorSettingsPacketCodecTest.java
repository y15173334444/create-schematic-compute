package io.github.y15173334444.create_schematic_compute.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MonitorSettingsPacket 编解码往返测试：覆盖 merge-plan §3.4 的数据契约扩展 ——
 * 新增的 virtualImageScale（第 15 字段）编解码顺序追加在 HUD 组（panelDistance）
 * 之后，往返必须逐字段一致（旧客户端无该字段 → 读流偏移错位会在此暴露）。
 * CODEC round-trip: covers the merge-plan §3.4 data-contract extension — the new
 * virtualImageScale (15th field) is appended after the HUD group (panelDistance);
 * round-trip must match field-by-field (a legacy client lacking the field would
 * misalign the stream offset and show up here).
 */
class MonitorSettingsPacketCodecTest {

    private static MonitorSettingsPacket sample() {
        return new MonitorSettingsPacket(
            new BlockPos(12, -34, 56),
            1.5f, 1.2f, 0.25f, 2.0f, -0.5f, 10f, 20f, 30f,
            true,
            2.0f, 1.2f, 0.1f, -0.2f, 0.05f,
            2.5f // virtualImageScale
        );
    }

    @Test
    @DisplayName("round-trip preserves all 15 fields incl. virtualImageScale")
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
            assertEquals(pkt.panelSizeX(), out.panelSizeX());
            assertEquals(pkt.panelSizeY(), out.panelSizeY());
            assertEquals(pkt.panelOffsetX(), out.panelOffsetX());
            assertEquals(pkt.panelOffsetY(), out.panelOffsetY());
            assertEquals(pkt.panelDistance(), out.panelDistance());
            // §3.4 新增字段 / the newly added field
            assertEquals(2.5f, out.virtualImageScale());
        } finally {
            buf.release();
        }
    }

    @Test
    @DisplayName("virtualImageScale sits AFTER the HUD group in the byte stream")
    void fieldOrder() {
        var pkt = sample();
        ByteBuf buf = Unpooled.buffer();
        try {
            MonitorSettingsPacket.CODEC.encode(buf, pkt);
            // 3D 组 8 float + hudMode 1 bool + HUD 组 5 float → 之后才是 virtualImageScale
            // 8 floats + bool + 5 floats → then virtualImageScale
            int posBytes = 8; // BlockPos.STREAM_CODEC → long
            int floats = 8 + 5; // 3D + HUD（不含 vis）
            assertEquals(posBytes + floats * 4 + 1 + 4, buf.readableBytes());
            buf.skipBytes(posBytes);
            for (int i = 0; i < floats; i++) buf.skipBytes(4);
            buf.skipBytes(1); // hudMode
            // 第 15 字段 = 虚像缩放 / the 15th field is the virtual-image scale
            assertEquals(2.5f, buf.readFloat(), 0.0f);
        } finally {
            buf.release();
        }
    }
}
