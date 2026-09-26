package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.EditSessionRegistry;
import io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 保存/编译请求（客户端 → 服务端）。只携带坐标，**不携带整图 NBT**。
 * <p>
 * 历史路径 {@code BlueprintSavePacket} 会把客户端整图快照整体替换服务端图——
 * 客户端副本一旦过期（漏收 op、旧全量同步），保存就会把服务端回退，静默冲掉
 * 并发编辑（issue #17）。局部编辑早已走定向 op，整图上传只剩风险没有收益。
 * <p>
 * 现改为：服务端在**自己的权威图**上执行编译语义（触发器当前态回归初始态 +
 * 环检测 + 落盘），并把结果全量同步给编辑者。
 * <p>
 * Save/compile request (client → server). Carries only the position — <b>no</b>
 * whole-graph NBT. The historical {@code BlueprintSavePacket} path replaced the
 * server graph with the client's snapshot; a stale client copy then rolled the
 * server back and silently wiped concurrent edits (issue #17). Local edits already
 * go through targeted ops, so the full upload was pure risk. The server now runs
 * the compile semantics on <b>its own authoritative graph</b> (flip-flop current
 * state reset + cycle check + persist) and full-syncs the result to editors.
 */
public record GraphSaveRequestPacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<GraphSaveRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_save_request"));

    public static final StreamCodec<ByteBuf, GraphSaveRequestPacket> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, GraphSaveRequestPacket::pos,
        GraphSaveRequestPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
            if (!(sp.level() instanceof net.minecraft.server.level.ServerLevel sl)) return;
            if (!SablePacketHelper.isWithinReachableRange(sp, pos, 16384.0)) return;
            if (!EditSessionRegistry.getEditors(sl, pos).contains(sp.getUUID())) return;
            if (!(sp.level().getBlockEntity(pos) instanceof GraphBlockEntity gbe)) return;

            // 编译语义：触发器当前态回归初始态（原先在客户端本地改完再靠整图上传捎带）
            // Compile semantics: flip-flop current state resets to initial (used to be a
            // client-local mutation hitchhiking on the full-graph upload).
            gbe.applyCompileReset();

            if (gbe.graphHasCycles() && gbe.isRunning()) {
                gbe.setRunning(false);
                sp.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal("§e⚠ Cycle detected! Graph stopped. Remove the loop and try again."));
            }
            // 落盘 + 全量同步给编辑者（markDirty + flagFullSync，不替换图）
            // Persist + full-sync to editors (markDirty + flagFullSync, no graph replace).
            gbe.markDirtyAndSyncGraph();
        });
    }
}
