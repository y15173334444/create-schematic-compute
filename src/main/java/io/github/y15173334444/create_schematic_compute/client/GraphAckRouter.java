package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity;
import io.github.y15173334444.create_schematic_compute.blocks.GraphEditor;
import io.github.y15173334444.create_schematic_compute.network.GraphEditAckPacket;
import net.minecraft.client.Minecraft;

/**
 * Client-side ACK router (E2) — the single {@link GraphEditAckPacket.EditAckSink}
 * implementation. It carries the two consumption steps the old inline packet handler
 * owned, so the network layer no longer imports the client UI ({@code blocks/GraphEditor});
 * the import direction is reversed to client → network and dedicated servers never
 * classload any GUI reference through the ack path.
 *
 * 客户端 ACK 路由（E2）—— {@link GraphEditAckPacket.EditAckSink} 的唯一客户端实现。
 * 它承接旧内联包 handler 的两步消费逻辑，网络层因此不再 import 客户端 UI
 * （{@code blocks/GraphEditor}）；import 方向反转为 client → network，专用服务器在
 * ACK 路径上不再加载任何 GUI 类引用。
 *
 * <p><b>注册 / Registration</b>：{@code ClientSetup.clientSetup} 的 enqueueWork 里
 * {@code GraphEditAckPacket.setClientSink(new GraphAckRouter())}。专用服务器不注册，
 * {@code dispatch} 静默丢弃（与旧的「编辑器已关则丢」语义一致）。</p>
 */
public final class GraphAckRouter implements GraphEditAckPacket.EditAckSink {

    @Override
    public void onAck(GraphEditAckPacket pkt) {
        var mc = Minecraft.getInstance();
        // 1) 无论编辑器是否仍打开，都按坐标对 BE 的待 ACK 计数递减（ACK 可能晚于关闭到达）。
        //    被拒 op 在 GraphEditor.onRemoteOp 中递减（不会作为 ACK 到达这里）。
        //    Decrement the pending-ACK counter on the target BE regardless of screen state
        //    (the ack may arrive after the editor closed). Rejected ops are decremented in
        //    GraphEditor.onRemoteOp instead (they never arrive here as an ACK).
        if (mc.level != null
            && mc.level.getBlockEntity(pkt.pos()) instanceof GraphBlockEntity gbe) {
            gbe.setPendingLocalOps(Math.max(0, gbe.getPendingLocalOps() - 1));
        }
        // 2) 只有同坐标的编辑器屏仍打开时才应用。走 getActiveHost() 而非裸 instanceof——
        //    它会向内解一层便携终端包装（与 sendOp/getEditor 的漏斗收口同一入口），
        //    终端内编辑的 ACK 不再被误丢。坐标不匹配则有意丢弃——下次打开编辑器会整图全同步。
        //    Apply only when an editor screen is still open at the same position. Routed
        //    through getActiveHost() instead of a bare instanceof — it unwraps the portable
        //    terminal wrapper one level (the same entry as the sendOp/getEditor funnel fix),
        //    so ACKs for editing inside the terminal are no longer mis-dropped. A position
        //    mismatch is intentionally dropped — the next editor open does a full sync.
        var host = GraphEditor.getActiveHost();
        if (host != null && host.getBlockPos() != null && host.getBlockPos().equals(pkt.pos())) {
            host.handleAck(pkt);
        }
    }
}
