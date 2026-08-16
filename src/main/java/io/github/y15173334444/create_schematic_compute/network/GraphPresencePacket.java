package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.EditSessionRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import io.netty.buffer.ByteBuf;

import java.util.UUID;

/**
 * <h1>Graph Presence Packet</h1>
 *
 * <p>Sent between clients and the server to synchronize a player's cursor position,
 * selected node, wire-dragging state, and multi-select set within a shared
 * schematic graph editor block. Each player's presence data is relayed to every
 * other editor of the same schematic so that cursors and selections can be
 * rendered in real-time on each client.</p>
 *
 * <p>This packet is <b>server-authoritative</b> for identity: the sending
 * player's UUID is always taken from {@code ServerPlayer.getUUID()} on the
 * server side, never from the packet payload, preventing spoofing.</p>
 *
 * <hr>
 * <h1>图形临场数据包</h1>
 *
 * <p>在客户端与服务器之间同步某一玩家的鼠标位置、选中节点、连线拖拽状态以及多选集合，
 * 供共享原理图编辑器方块使用。每位玩家的临场数据会中继给同一原理图的所有其他编辑者，
 * 使各个客户端能够实时渲染彼此的光标和选区。</p>
 *
 * <p>本数据包在<b>身份认证上以服务器为准</b>：发送者的 UUID 始终取自服务端的
 * {@code ServerPlayer.getUUID()}，而非来自数据包载荷，以此防止身份伪造。</p>
 *
 * @param pos              position of the schematic edit-session block / 原理图编辑会话方块的位置
 * @param player           UUID of the editing player (validated server-side) / 编辑玩家的 UUID（在服务端校验）
 * @param playerName       display name shown on other clients / 显示在其他客户端上的玩家名称
 * @param ownerNodeId      the player's owning/rooted node id, or -1 if none / 玩家当前持有/根节点的 id，没有时为 -1
 * @param cursorX          cursor X in graph-space coordinates / 图形坐标系下的光标 X
 * @param cursorY          cursor Y in graph-space coordinates / 图形坐标系下的光标 Y
 * @param selectedNodeId   singly-selected node id, or -1 if none / 单选节点 id，没有时为 -1
 * @param editingNodeId    node id currently being edited (e.g. inline rename), or -1 / 正在被编辑（如内联重命名）的节点 id，没有时为 -1
 * @param wireFromNode     node id from which a wire is being dragged, or -1 if not dragging / 正在拖拽连线的起始节点 id，未拖拽时为 -1
 * @param wireFromPin      pin index on the source node from which the wire originates / 起始节点上连线引出的引脚序号
 * @param wireEndX         current X of the floating wire end (mouse position) / 连线浮动端当前的 X（跟随鼠标）
 * @param wireEndY         current Y of the floating wire end (mouse position) / 连线浮动端当前的 Y（跟随鼠标）
 * @param selectedNodeIds  all selected node IDs for multi-select lock / 所有选中节点 ID 用于多选锁定
 * @param mode              editing mode: 0 = node graph editor, 1 = monitor display layout editor / 编辑模式：0=节点图编辑器，1=显示器布局编辑器
 * @param displayDraggedNodeId  node currently being dragged in the display layout editor, or -1 / 显示布局编辑器中正在拖拽的节点 id，-1 为无
 */
public record GraphPresencePacket(
    BlockPos pos, UUID player, String playerName,
    int ownerNodeId, float cursorX, float cursorY,
    int selectedNodeId, int editingNodeId,
    int wireFromNode, int wireFromPin, float wireEndX, float wireEndY,
    int[] selectedNodeIds,  // all selected node IDs for multi-select lock / 所有选中节点 ID 用于多选锁定
    byte mode,              // 0=node graph editor, 1=display layout editor
    int displayDraggedNodeId // node dragged in the display layout editor, -1 = none
) implements CustomPacketPayload {

    /**
     * Custom-payload type identifier registered on the NeoForge channel.
     * <p>NeoForge 通道上注册的自定义载荷类型标识符。</p>
     */
    public static final Type<GraphPresencePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_presence"));

    /**
     * Self-contained {@link StreamCodec} that serializes/deserializes every
     * component field of the presence record to/from a network buffer.
     *
     * <p>Field order in {@link #decode} MUST mirror {@link #encode} exactly;
     * any mismatch will corrupt the byte stream and cause desync.</p>
     *
     * <p>自包含的 {@link StreamCodec}，负责将临场记录的所有字段序列化/反序列化到网络缓冲区。
     * {@link #decode} 中的字段顺序<strong>必须</strong>与 {@link #encode} 完全一致，
     * 否则字节流会错位，导致客户端-服务器不同步。</p>
     */
    public static final StreamCodec<ByteBuf, GraphPresencePacket> CODEC =
        new StreamCodec<>() {
            @Override public GraphPresencePacket decode(ByteBuf buf) {
                // Wrap raw ByteBuf in a FriendlyByteBuf for Minecraft-style helpers
                // 将原始 ByteBuf 包装为 FriendlyByteBuf 以使用 Minecraft 风格的便捷方法
                var b = new FriendlyByteBuf(buf);
                BlockPos pos = b.readBlockPos();
                // UUID is split into two longs (most-sig + least-sig) on the wire
                // UUID 在网络上按两个 long（最高位 + 最低位）拆分传输
                UUID player = new UUID(b.readLong(), b.readLong());
                String name = b.readUtf();
                int owner = b.readVarInt();
                float cx = b.readFloat();
                float cy = b.readFloat();
                int sel = b.readVarInt();
                int edit = b.readVarInt();
                int wfn = b.readVarInt();
                int wfp = b.readVarInt();
                float wex = b.readFloat();
                float wey = b.readFloat();
                // Multi-select array: length-prefixed with a VarInt, followed by that many entries
                // 多选数组：先用 VarInt 写入长度，再依次写入对应数量的条目
                int count = b.readVarInt();
                int[] selIds = new int[count];
                for (int i = 0; i < count; i++) selIds[i] = b.readVarInt();
                // 编辑模式 + 显示布局拖拽节点（追加在末尾，保持与旧字段的顺序兼容）
                // Editing mode + display-drag node (appended at the end for order compatibility)
                byte mode = b.readByte();
                int dragId = b.readVarInt();
                return new GraphPresencePacket(pos, player, name, owner, cx, cy, sel, edit, wfn, wfp, wex, wey, selIds, mode, dragId);
            }
            @Override public void encode(ByteBuf buf, GraphPresencePacket p) {
                var b = new FriendlyByteBuf(buf);
                b.writeBlockPos(p.pos);
                b.writeLong(p.player.getMostSignificantBits());
                b.writeLong(p.player.getLeastSignificantBits());
                b.writeUtf(p.playerName);
                b.writeVarInt(p.ownerNodeId);
                b.writeFloat(p.cursorX);
                b.writeFloat(p.cursorY);
                b.writeVarInt(p.selectedNodeId);
                b.writeVarInt(p.editingNodeId);
                b.writeVarInt(p.wireFromNode);
                b.writeVarInt(p.wireFromPin);
                b.writeFloat(p.wireEndX);
                b.writeFloat(p.wireEndY);
                // Guard against null array — write zero-length instead so the decoder never gets NPE
                // 防御空数组——写入零长度以避免解码端 NPE
                int[] ids = p.selectedNodeIds != null ? p.selectedNodeIds : new int[0];
                b.writeVarInt(ids.length);
                for (int id : ids) b.writeVarInt(id);
                b.writeByte(p.mode);
                b.writeVarInt(p.displayDraggedNodeId);
            }
        };

    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * <h3>Server-side handler / 服务端处理</h3>
     *
     * <p>Relays the sender's presence update to every other player who is
     * currently editing the same schematic. The sender itself is excluded
     * (its own state is driven locally). The player UUID is taken from the
     * authenticated server-player entity, <em>not</em> from the packet, to
     * prevent forged presence.</p>
     *
     * <p>将发送者的临场更新中继给当前正在编辑同一原理图的所有其他玩家。发送者自身
     * 会被排除（其自己的状态由本地驱动）。玩家 UUID 取自经过身份认证的服务端玩家对象，
     * <em>而非</em>数据包内字段，以防伪造临场信息。</p>
     *
     * @param pkt the incoming packet from a client / 来自客户端的入站数据包
     * @param ctx the NeoForge payload context (provides player and threading) /
     *            NeoForge 载荷上下文（提供 player 和线程调度）
     */
    public static void handleServer(GraphPresencePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            // Wrap the presence data into a relay packet for each recipient
            // 将临场数据包装为中继数据包，发往每位接收者
            var sync = new GraphPresenceSyncPacket(pkt);
            // Editors of the schematic block on this dimension
            // 当前维度下该原理图方块的所有编辑者
            var editors = EditSessionRegistry.getEditors(sp.serverLevel(), pkt.pos);
            // Use authenticated player UUID, ignore client-supplied UUID
            // 使用经过认证的玩家 UUID，忽略客户端上报的 UUID
            var senderUUID = sp.getUUID();
            for (var editorId : editors) {
                if (editorId.equals(senderUUID)) continue;
                var ep = sp.getServer().getPlayerList().getPlayer(editorId);
                if (ep != null) PacketDistributor.sendToPlayer(ep, sync);
            }
        });
    }

    /**
     * <h3>Client-side handler / 客户端处理</h3>
     *
     * <p>Applies the received presence data to the open graph editor that
     * matches the schematic position in the packet. If no editor is open, or
     * its position does not match, the packet is silently ignored — this can
     * happen when a presence update arrives just after the UI is closed.</p>
     *
     * <p>将收到的临场数据应用到与数据包中原理图位置匹配的已打开图形编辑器。若没有
     * 打开编辑器，或其位置不匹配，则静默忽略该数据包——这种情况可能在 UI 刚关闭后
     * 仍有临场更新到达时发生。</p>
     *
     * @param pkt the relayed presence packet from the server / 来自服务器中继的临场数据包
     * @param ctx the NeoForge payload context / NeoForge 载荷上下文
     */
    public static void handleClient(GraphPresencePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var host = io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.getActiveHost();
            if (host != null && host.getBlockPos().equals(pkt.pos)) {
                host.getEditor().storeRemotePresence(pkt);
            }
        });
    }
}
