package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.RadarBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 雷达锁定/解锁数据包 —— 客户端发送此包来切换雷达对某个实体的锁定状态。
 * Radar lock/unlock packet —— sent by the client to toggle the radar's lock
 * state for a specific entity.
 *
 * <p>负载字段：
 * <ul>
 *   <li>{@code pos} —— 雷达方块坐标 / radar block position</li>
 *   <li>{@code entityId} —— 要锁定/解锁的实体网络 ID / network ID of the entity to lock or unlock</li>
 *   <li>{@code lock} —— {@code true} 表示锁定，{@code false} 表示解锁 / {@code true} to lock, {@code false} to unlock</li>
 * </ul>
 *
 * <p>服务端处理时会做距离校验和编辑会话成员校验，防止恶意修改远程雷达。
 * Server-side handling includes distance and edit-session membership checks to
 * prevent unauthorized modification of remote radars.
 */
public record RadarLockPacket(BlockPos pos, int entityId, boolean lock) implements CustomPacketPayload {

    /**
     * 数据包类型标识符，注册到 NeoForge 网络通道。
     * Packet type identifier registered with the NeoForge network channel.
     */
    public static final Type<RadarLockPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "radar_lock"));

    /**
     * 流编解码器：按字段顺序序列化/反序列化 —— 坐标、实体 ID、锁定标志。
     * Stream codec: serializes/deserializes in field order —— position, entity ID, lock flag.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, RadarLockPacket> CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, RadarLockPacket::pos,
            ByteBufCodecs.VAR_INT, RadarLockPacket::entityId,
            ByteBufCodecs.BOOL, RadarLockPacket::lock,
            RadarLockPacket::new);

    /**
     * 返回此数据包的 {@link Type}，供 NeoForge 网络层路由。
     * Returns the {@link Type} of this payload for NeoForge network-layer routing.
     */
    @Override public Type<RadarLockPacket> type() { return TYPE; }

    /**
     * 服务端处理入口：校验权限后执行锁定/解锁逻辑。
     * Server-side handler: validates permissions then applies the lock/unlock logic.
     *
     * @param pkt 客户端发来的数据包 / the packet from the client
     * @param ctx 负载上下文，提供玩家、入队工作等 / payload context providing player, enqueue-work, etc.
     */
    public static void handle(RadarLockPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            // 安全校验：仅距离检查
            // Security: distance check only

            // 仅服务端玩家可处理 —— 客户端不应收到此包
            // Only server-side players may process —— clients should never receive this packet
            if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            if (!(sp.level() instanceof net.minecraft.server.level.ServerLevel sl)) return;

            // 距离校验：防止玩家超远距离操控雷达（16384.0 = 128^2，约 8 个区块的平方距离）
            // Distance check: prevents players from manipulating radars at extreme range
            // (16384.0 = 128^2, roughly 8 chunks of squared distance)
            if (!io.github.y15173334444.create_schematic_compute.network.SablePacketHelper.isWithinReachableRange(sp, pkt.pos, 16384.0)) return;

            // 注：此处不做编辑会话成员校验。锁定/解锁是「使用」操作（与右键雷达方块
            // 本体的 useWithoutItem 一致），不是「编辑」操作——要求编辑会话成员会把
            // 未打开编辑 UI 的玩家的 blip 锁定全部拒绝（客户端乐观更新被服务器同步覆盖，
            // 边框闪一下就消失）。距离校验已足够防滥用。
            // Note: no edit-session membership check here. Lock/unlock is a USE operation
            // (consistent with right-clicking the radar block itself via useWithoutItem),
            // not an EDIT operation — requiring edit-session membership would reject all
            // blip locks from players who haven't opened the edit UI (client optimistic
            // update gets overwritten by the server sync, the highlight flashes and vanishes).
            // The distance check is sufficient anti-abuse.

            var level = sp.level();
            var be = level.getBlockEntity(pkt.pos);
            if (be instanceof RadarBlockEntity radar) {
                // 统计图中 TARGET_OUT 类型节点的数量 —— 这些代表雷达的可用锁定槽位
                // Count TARGET_OUT nodes in the graph —— these represent the radar's available lock slots
                int nodeCount = 0;
                for (var n : radar.graph.nodes)
                    if (n.type == io.github.y15173334444.create_schematic_compute.graph.NodeType.TARGET_OUT) nodeCount++;

                // 计算最大锁定数：精确模式（scanMode == 1）仅允许锁定 1 个目标，
                // 其他模式允许锁定最多 nodeCount 个目标（至少 1 个）
                // Calculate max locks: precise mode (scanMode == 1) allows only 1 lock,
                // other modes allow up to nodeCount locks (minimum 1)
                int maxLocks = radar.scanMode == 1 ? 1 : Math.max(1, nodeCount);

                // 尝试切换锁定状态，若已达上限则失败
                // Attempt to toggle lock; fails silently if the limit has been reached
                boolean ok = radar.toggleLock(pkt.entityId, maxLocks);
                if (ok) {
                    // 标记方块已修改并同步到客户端
                    // Mark block as changed and sync to clients
                    radar.setChanged();
                    level.sendBlockUpdated(pkt.pos, radar.getBlockState(), radar.getBlockState(), 3);
                }
            }
        });
    }
}
