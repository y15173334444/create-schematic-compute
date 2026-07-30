package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import io.netty.buffer.ByteBuf;

/**
 * S→C 同步数据包：服务端将已应用的图编辑操作广播给其他正在编辑的客户端。
 * <p>
 * S→C sync packet: the server broadcasts an applied graph-edit op to other
 * editors. This separate type is necessary because NeoForge does not allow
 * registering the same {@link CustomPacketPayload.Type} for both
 * {@code playToServer} and {@code playToClient} directions.
 *
 * @param op the graph operation to synchronize / 需要同步的图操作
 */
public record GraphEditOpSyncPacket(GraphOp op) implements CustomPacketPayload {

    /**
     * 数据包类型标识符，用于 NeoForge 网络注册。
     * <p>
     * Packet type identifier used for NeoForge network registration.
     * The {@link ResourceLocation} is derived from the mod id and a fixed
     * path to ensure uniqueness across the network channel.
     */
    public static final Type<GraphEditOpSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_edit_op_sync"));

    /**
     * 流编解码器：复用 {@link GraphEditOpPacket#CODEC} 的逻辑，
     * 将 {@link GraphOp} 封装为同步包进行编解码。
     * <p>
     * Stream codec that reuses {@link GraphEditOpPacket#CODEC} logic by
     * wrapping/unwrapping the inner {@link GraphOp} in a sync-packet
     * envelope. This avoids duplicating the serialization logic.
     */
    public static final StreamCodec<ByteBuf, GraphEditOpSyncPacket> CODEC =
        new StreamCodec<>() {
            @Override public GraphEditOpSyncPacket decode(ByteBuf buf) {
                // 复用 GraphEditOpPacket 的编解码器解析字节流，提取其中的 GraphOp
                // Decode via GraphEditOpPacket's codec, then extract the inner op
                var inner = GraphEditOpPacket.CODEC.decode(buf);
                return new GraphEditOpSyncPacket(inner.op());
            }
            @Override public void encode(ByteBuf buf, GraphEditOpSyncPacket pkt) {
                // 编码时先将 op 包装为 GraphEditOpPacket，再委托给其编解码器写入字节流
                // Encode by wrapping the op in a GraphEditOpPacket and delegating
                // to its own codec so the wire format stays consistent
                GraphEditOpPacket.CODEC.encode(buf, new GraphEditOpPacket(pkt.op()));
            }
        };

    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * 客户端处理入口：接收服务端下发的远程操作，将其应用到本地图数据结构上。
     * <p>
     * Client-side handler: receives a remote op pushed by the server and
     * applies it to the local node-graph data so the world stays in sync.
     *
     * @param pkt the sync packet carrying the remote operation / 携带远程操作的同步数据包
     * @param ctx NeoForge 网络上下文，提供 player 和线程调度 / NeoForge network context providing player access and thread scheduling
     */
    public static void handle(GraphEditOpSyncPacket pkt, IPayloadContext ctx) {
        // 将实际工作调度到主线程执行，避免并发修改图数据结构
        // Enqueue work on the main thread to avoid concurrent modification of graph data
        ctx.enqueueWork(() -> {
            if (ctx.player() == null) return;
            var mc = net.minecraft.client.Minecraft.getInstance();

            // 如果编辑器 UI 处于打开状态，将操作委托给 UI 宿主处理，
            // 因为宿主拥有完整的 UI 状态感知能力（选中、连线预览等）
            // If the editor UI is open, delegate to the host for full UI-aware
            // handling (selection state, wire previews, etc.)
            var host = io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.getActiveHost();
            if (host != null && host.getBlockPos().equals(pkt.op().graphPos())) {
                host.onRemoteOp(pkt.op());
            } else {
                // UI 关闭：直接将操作应用到 BlockEntity 的节点图上，
                // 确保世界中的渲染器（如连线渲染）能立即看到变更
                // UI closed: apply the op directly to the BlockEntity's
                // node-graph so the in-world renderer (e.g. wire rendering)
                // sees the changes immediately
                var level = mc.level;
                if (level != null) {
                    var be = level.getBlockEntity(pkt.op().graphPos());
                    if (be instanceof io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity gbe) {
                        var graph = gbe.getNodeGraph();
                        if (graph == null) return;

                        // 当操作属于某个封装节点内部时，需要先定位到对应的子图
                        // 再进行应用。ownerNodeId 为 -1 表示操作作用于顶层图。
                        // Resolve the sub-graph when the op targets an
                        // encapsulation node's interior. ownerNodeId == -1
                        // means the op acts on the top-level graph.
                        if (pkt.op().ownerNodeId() >= 0) {
                            var encapNode = graph.findNode(pkt.op().ownerNodeId());
                            if (encapNode == null) return; // 封装节点不存在 / encapsulation node does not exist
                            // 子图尚未初始化时进行懒创建，
                            // 保证后续 OpExecutor.apply 不会 NPE
                            // Lazily create the sub-graph if it doesn't exist yet,
                            // so that OpExecutor.apply won't NPE on a null reference
                            if (encapNode.subGraph == null) encapNode.subGraph = new io.github.y15173334444.create_schematic_compute.graph.NodeGraph();
                            graph = encapNode.subGraph;
                        }

                        // 应用操作到最终确定的图上（可能是顶层图或封装子图）。
                        // 最后一个参数为 false 表示此操作来自网络同步而非本地编辑，
                        // 因此不需要再次向网络广播。
                        // Apply the op to the resolved graph (top-level or
                        // encapsulation sub-graph). The last argument is false
                        // because this op came from the network — it must not
                        // be re-broadcast, which would cause an infinite loop.
                        io.github.y15173334444.create_schematic_compute.graph.OpExecutor.apply(graph, pkt.op(), false);
                    }
                }
            }
        });
    }
}
