package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/** Client→Server: request a scan of Sable sub-levels for nearby programmable blocks.
 *  <p>客户端→服务端：请求扫描 Sable 子层级附近的可编程方块。</p>
 *
 *  <p>P1-3 服务端权威：扫描半径经 {@link #clampScanRange} 钳制（终端 UI 实际范围 1..128，
 *  且超大值原本会经 {@code rangeSq = scanRange * scanRange} 的 int 溢出反而全通过）；扫描
 *  原点取**服务端玩家位置**而非包内坐标 —— 客户端 pos 不再参与扫描（唯一信任客户端坐标
 *  做原点的包，任意坐标反复触发子级 BE 全量遍历的攻击面被消除）。原点即玩家自身，
 *  {@code isWithinReachableRange} 的距离校验被原点派生吸收（玩家 vs 自身位置恒在可达范围）。
 *  <p>P1-3 server authority: the scan radius is clamped via {@link #clampScanRange} (the
 *  terminal UI's actual range is 1..128, and an oversized value previously sailed through the
 *  {@code rangeSq = scanRange * scanRange} int overflow); the scan origin is the <b>server
 *  player position</b>, not the packet's coordinate — the client pos no longer participates in
 *  scanning (this was the only packet trusting a client coordinate as scan origin; repeated
 *  arbitrary-coordinate scans driving full sub-level BE traversals are eliminated). The origin
 *  is the player itself, so the {@code isWithinReachableRange} distance check is subsumed by
 *  the origin derivation (player vs own position is always within reach).</p>
 */
public record ScanSablePacket(BlockPos playerPos, int scanRange) implements CustomPacketPayload {
    public static final Type<ScanSablePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "scan_sable"));

    /** 扫描半径服务端上限（与终端 UI 的输入上限一致 / PortableTerminalScreen 1..128）。
     *  Server-side scan-radius ceiling (matches the terminal UI's input cap, 1..128). */
    public static final int MAX_SCAN_RANGE = 128;

    /** 半径钳制到 [1, MAX_SCAN_RANGE]（服务端权威；客户端 UI 同款范围）。
     *  Clamp the radius into [1, MAX_SCAN_RANGE] (server authority; same range as the UI). */
    public static int clampScanRange(int scanRange) {
        return Math.max(1, Math.min(MAX_SCAN_RANGE, scanRange));
    }

    public static final StreamCodec<ByteBuf, ScanSablePacket> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, ScanSablePacket::playerPos,
        ByteBufCodecs.VAR_INT,  ScanSablePacket::scanRange,
        ScanSablePacket::new
    );
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // 守卫：必须为服务端世界上的服务端玩家（原先的强转潜在 CCE）
            // Guard: must be a server player on a server level (the old cast risked a CCE)
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!(sp.level() instanceof ServerLevel sw)) return;
            // 原点服务端权威：包内 playerPos 仅作日志参考，不参与扫描
            // Server-authoritative origin: the packet's playerPos is log-only, never scanned
            BlockPos origin = sp.blockPosition();
            // 半径服务端钳制（int 溢出面随 128 上限一并消除）
            // Radius clamped server-side (the int-overflow surface dies with the 128 cap)
            int range = clampScanRange(scanRange);
            List<SablePacketHelper.SableDeviceEntry> devs =
                SablePacketHelper.scanDevices(sw, origin, range);
            PacketDistributor.sendToPlayer(sp,
                new ScanSableResponsePacket(devs));
        });
    }
}
