package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * Client→Server: player opened the edit UI for a graph.
 * <p>
 * 客户端→服务端：玩家打开了某个图（Graph）的编辑界面。
 *
 * @param pos the block position of the graph block entity to join / 要加入的图方块实体的坐标
 */
public record GraphJoinPacket(BlockPos pos) implements CustomPacketPayload {

    /**
     * Custom packet payload type identifier for this packet, registered under the mod's namespace.
     * <p>
     * 该数据包的自定义载荷类型标识符，注册在模组的命名空间下。
     */
    public static final Type<GraphJoinPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_join"));

    /**
     * Stream codec for serializing/deserializing this packet to and from the network buffer.
     * <p>
     * 流编解码器，用于在此数据包与网络缓冲区之间进行序列化和反序列化。
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, GraphJoinPacket> CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, GraphJoinPacket::pos,
            GraphJoinPacket::new
        );

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * Max join distance (squared) — ~80 blocks range.
     * <p>
     * 最大加入距离（平方值）——约80格范围。使用平方值避免开根号运算，提升性能。
     */
    private static final double MAX_JOIN_DIST_SQ = 128.0 * 128.0;

    /**
     * Handle the graph join request on the server side.
     * Validates range and block entity type before registering the player into the edit session.
     * <p>
     * 在服务端处理图加入请求。先将玩家注册到编辑会话中，在此之前会依次验证距离和图方块实体类型。
     *
     * @param pkt the incoming packet received from the client / 从客户端接收到的数据包
     * @param ctx the network payload context providing server-side player access / 网络载荷上下文，提供服务端玩家访问能力
     */
    public static void handle(GraphJoinPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            // 1. Range check before touching chunk/block entity (Sable-aware)
            //    距离检查——在触碰区块/方块实体之前执行，避免触发不必要的区块加载（兼容Sable反作弊）
            if (!io.github.y15173334444.create_schematic_compute.network.SablePacketHelper.isWithinReachableRange(sp, pkt.pos, MAX_JOIN_DIST_SQ)) return;
            // 2. Verify target is one of the 7 graph block entities BEFORE joining session
            //    在加入编辑会话之前，验证目标是7种图方块实体之一，防止非法方块被注入编辑状态
            var be = sp.level().getBlockEntity(pkt.pos);
            if (!(be instanceof GraphBlockEntity)) return;
            // 3. Safe to join — register player into the edit session and trigger full sync
            //    安全检查通过，将玩家注册到编辑会话中，并触发对应方块实体的全量同步
            EditSessionRegistry.join(sp.serverLevel(), pkt.pos, sp.getUUID());
            if (be instanceof BlueprintBlockEntity bbe) bbe.flagFullSync();
            else if (be instanceof MonitorBlockEntity mbe) mbe.flagFullSync();
            else if (be instanceof RadarBlockEntity rbe) rbe.flagFullSync();
            else if (be instanceof SensorBlockEntity sbe) sbe.flagFullSync();
            else if (be instanceof ControlSeatBlockEntity cbe) cbe.flagFullSync();
            else if (be instanceof SpeedProxyBlockEntity spbe) spbe.flagFullSync();
            else if (be instanceof ProgramComputerBlockEntity pbe) pbe.flagFullSync();
            // Send the initial update packet to the joining player so their UI reflects current state
            // 向加入的玩家发送初始更新数据包，使其UI反映当前方块状态
            var update = be.getUpdatePacket();
            if (update != null) sp.connection.send(update);
        });
    }
}
