package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.network.EditSessionRegistry;
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
import io.github.y15173334444.create_schematic_compute.client.editor.GraphEditor;

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
 * @param editingGraphName  true while the sender is editing the top-bar block-name box — the graph-name soft lock / 发送者正在编辑顶栏方块名框时为 true —— 图名软锁
 */
public record GraphPresencePacket(
    BlockPos pos, UUID player, String playerName,
    int ownerNodeId, float cursorX, float cursorY,
    int selectedNodeId, int editingNodeId,
    int wireFromNode, int wireFromPin, float wireEndX, float wireEndY,
    int[] selectedNodeIds,  // all selected node IDs for multi-select lock / 所有选中节点 ID 用于多选锁定
    byte mode,              // 0=node graph editor, 1=display layout editor
    int displayDraggedNodeId, // node dragged in the display layout editor, -1 = none
    boolean editingGraphName // graph-name soft lock flag / 图名软锁标志
) implements CustomPacketPayload {

    /**
     * Custom-payload type identifier registered on the NeoForge channel.
     * <p>NeoForge 通道上注册的自定义载荷类型标识符。</p>
     */
    public static final Type<GraphPresencePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_presence"));

    /**
     * 多选集合的解码上限（协议错误阈值，非业务截断）。选择列表的合法规模是"图内节点数"
     * （全选是最坏情况，正常图为数百节点量级）；4 096 已远超任何合理图规模。
     * 超限 = 畸形/恶意包，抛 DecoderException 断开该连接，绝不按声明长度分配数组
     * （P0-1：先校验后分配，varint 可声明至 2³¹-1，直接 new 会单包打爆服务端内存）。
     * <p>Decode cap for the multi-select array (a protocol-error threshold, not a business
     * truncation). The legitimate size is "node count of the graph" (select-all is the worst
     * case; realistic graphs are hundreds of nodes). 4 096 far exceeds any sane graph.
     * Over-limit = malformed/malicious packet → DecoderException drops the connection; the
     * array is never allocated at the declared length (P0-1: validate before allocate — a
     * varint can claim up to 2³¹-1 and a bare {@code new int[count]} lets one packet OOM the
     * server's network thread).</p>
     */
    public static final int MAX_SELECTED_NODE_IDS = 4096;

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
                // P0-1 安全钳制：先校验后分配。负数或超限的 count 视为协议错误
                //（DecoderException → NeoForge 断开该连接），绝不按声明值分配数组。
                // P0-1 safety clamp: validate before allocate. A negative or oversized count is a
                // protocol error (DecoderException → NeoForge drops the connection); the array is
                // never allocated at the declared length.
                int count = b.readVarInt();
                if (count < 0 || count > MAX_SELECTED_NODE_IDS)
                    throw new io.netty.handler.codec.DecoderException(
                        "GraphPresencePacket: selectedNodeIds count out of bounds: " + count
                            + " (limit " + MAX_SELECTED_NODE_IDS + ")");
                int[] selIds = new int[count];
                for (int i = 0; i < count; i++) selIds[i] = b.readVarInt();
                // 编辑模式 + 显示布局拖拽节点（追加在末尾，保持与旧字段的顺序兼容）
                // Editing mode + display-drag node (appended at the end for order compatibility)
                byte mode = b.readByte();
                int dragId = b.readVarInt();
                // 图名软锁标志（同样追加在末尾 / appended at the end as well）
                boolean nameEdit = b.readBoolean();
                return new GraphPresencePacket(pos, player, name, owner, cx, cy, sel, edit, wfn, wfp, wex, wey, selIds, mode, dragId, nameEdit);
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
                b.writeBoolean(p.editingGraphName);
            }
        };

    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * <h3>Sub-editor presence / 子编辑器临场保活包</h3>
     *
     * <p>子编辑器屏幕（NBS 钢琴卷帘 / 像素编辑器）转移自图编辑器后，GraphEditor 的每帧
     * 保活不再运行，占用软锁随之冻结——其他玩家重开图后锁表为空、看不到占用框（封装路径
     * 同屏切换所以正常）。子编辑器 tick 以同间隔（120ms）发送本包维持锁：{@code editingNodeId
     * = selectedNodeId = 正在编辑的节点}（锁语义 = 选中且编辑中）、{@code mode=0}、连线字段
     * 全部置空、光标停在节点的图空间中心（B 端占用者光标自然停在该节点上）。关闭子编辑器回
     * 来源屏后，新屏首条常规 presence（editingNodeId=-1）自然清锁。</p>
     *
     * <p>The sub-editor screens (NBS piano roll / pixel editor) transfer away from the graph
     * editor, so GraphEditor's per-frame keep-alive stops running and the occupancy soft lock
     * freezes — other players reopening the graph start with an empty lock table and never see
     * the lock frame (the encapsulation path stays on one screen, which is why it works). The
     * sub-editors send this packet from their tick at the same interval (120 ms) to keep the
     * lock alive: {@code editingNodeId = selectedNodeId = the node being edited} (lock
     * semantics = selected AND being edited), {@code mode=0}, wire fields zeroed, cursor parked
     * at the node's graph-space centre. When the sub-editor closes and the source screen
     * returns, its first regular presence (editingNodeId=-1) clears the lock naturally.</p>
     */
    public static GraphPresencePacket subEditor(BlockPos pos, UUID player, String playerName,
                                                int ownerNodeId, int editingNodeId,
                                                float cursorX, float cursorY) {
        return new GraphPresencePacket(pos, player, playerName,
            ownerNodeId, cursorX, cursorY,
            editingNodeId, editingNodeId,
            -1, -1, 0f, 0f,
            null, (byte) 0, -1, false);
    }

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
            // P1-2：中继包的身份字段由服务端从经过认证的玩家对象派生，**不转发**客户端自带的
            // UUID/名字（客户端可任意伪造 —— 包内 player/playerName 仅是编辑内容，不是身份）。
            // 载荷中的 player 字段因此只对 C2S 方向有意义，S2C 一律服务端回填。
            // P1-2: the relayed packet's identity fields are derived server-side from the
            // authenticated player object; the client-supplied UUID/name are never forwarded
            // (they are freely forgeable — packet fields are edited *content*, not identity).
            // The player field therefore only means anything C2S; S2C is always server-filled.
            var authoritative = new GraphPresencePacket(
                pkt.pos(), sp.getUUID(), sp.getGameProfile().getName(),
                pkt.ownerNodeId(), pkt.cursorX(), pkt.cursorY(),
                pkt.selectedNodeId(), pkt.editingNodeId(),
                pkt.wireFromNode(), pkt.wireFromPin(), pkt.wireEndX(), pkt.wireEndY(),
                pkt.selectedNodeIds(), pkt.mode(), pkt.displayDraggedNodeId(), pkt.editingGraphName());
            // Wrap the server-derived presence into a relay packet for each recipient
            // 将服务端派生的临场数据包装为中继数据包，发往每位接收者
            var sync = new GraphPresenceSyncPacket(authoritative);
            // Editors of the schematic block on this dimension
            // 当前维度下该原理图方块的所有编辑者
            var editors = EditSessionRegistry.getEditors(sp.serverLevel(), pkt.pos);
            // Skip-self 用真实 UUID（与中继包回填的一致）
            // Skip-self uses the real UUID (consistent with the relay's server-derived one)
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
            var host = io.github.y15173334444.create_schematic_compute.client.editor.GraphEditor.getActiveHost();
            // getEditor() 可能为 null：独立像素编辑器（PixelEditorScreen）也实现 Host，
            // 但无节点图编辑器，不处理 presence —— 跳过即可。
            // getEditor() can be null: the standalone pixel editor (PixelEditorScreen) also
            // implements Host but has no node-graph editor — skip presence updates for it.
            if (host != null && host.getBlockPos().equals(pkt.pos) && host.getEditor() != null) {
                host.getEditor().storeRemotePresence(pkt);
            }
        });
    }
}
