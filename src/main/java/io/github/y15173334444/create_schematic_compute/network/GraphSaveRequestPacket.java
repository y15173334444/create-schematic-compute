package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.network.EditSessionRegistry;
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
 * 现改为：服务端在**自己的权威图**上执行保存语义（环检测 + 落盘），并把结果全量同步
 * 给编辑者。（旧「编译复位触发器当前态」语义已随 1.3.0 退役——服务端权威下时序状态
 * 跨编译与编辑持久，见 {@code TimingEval} 的缺席播种契约注记。）
 * <p>
 * Save/compile request (client → server). Carries only the position — <b>no</b>
 * whole-graph NBT. The historical {@code BlueprintSavePacket} path replaced the
 * server graph with the client's snapshot; a stale client copy then rolled the
 * server back and silently wiped concurrent edits (issue #17). Local edits already
 * go through targeted ops, so the full upload was pure risk. The server now runs
 * the save semantics on <b>its own authoritative graph</b> (cycle check + persist)
 * and full-syncs the result to editors. (The old "compile resets flip-flop state"
 * semantics is retired as of 1.3.0 — under server authority sequential state
 * persists across compiles and edits; see the absent-state seed note in
 * {@code TimingEval}.)
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

            // （旧「编译复位触发器」调用点已随 1.3.0 退役：保存 = 环检测 + 落盘 + 全量同步，
            // 时序状态完全持久——撤销/重做语义随之保真。）
            // (The old compile-reset call site is retired as of 1.3.0: save = cycle check +
            // persist + full sync, sequential state fully persistent — undo/redo stays
            // faithful as a result.)

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
