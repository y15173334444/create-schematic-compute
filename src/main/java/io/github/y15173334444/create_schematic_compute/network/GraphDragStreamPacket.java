package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;

import java.util.List;
import java.util.UUID;

/**
 * <h2>拖动临场流（C→S）· Drag presence stream (C2S)</h2>
 *
 * <p>拖拽进行中的位置/尺寸临场更新（docs/packet-flood-rate-limit-plan.md §三）。此前拖拽被
 * 翻译成逐节点、逐 50ms 窗口的独立编辑 op 包（多选拖动 = 20N 包/s），把 EDIT_OP 令牌桶
 * （burst 1100 / refill 8/s，按「松手一次性突发」标定）持续拖空，桶空后的拒绝流约 1.1–1.3s
 * 即触发限流断开。本包把拖动流量塌缩到一条流：客户端节流 100ms、每包携带**全部**被拖条目
 * （一包多节点——塌缩点本身），服务端**只中继不落图**，编辑者侧以帧影覆盖渲染。</p>
 *
 * <p><b>只中继不落图</b>：本包不应用、不 markDirty、不分配版本、不进 opLog、不触发全量同步
 * ——拖动未松手 = 未提交，服务端图在松手前保持原值；持久化由松手时既有 finish* 路径的编辑
 * op 负责（「松手即提交」）。这与 presence 同一信任层级：条目几何是编辑内容不是身份，身份
 * 字段服务端权威回填（P1-2 同款纪律）。</p>
 *
 * <hr>
 * <h2>Drag presence stream (C2S)</h2>
 *
 * <p>Transient position/size updates while a drag is live (plan doc §3). Drags used to be
 * translated into per-node, per-50ms-window individual edit ops (multi-select = 20N packets/s),
 * continuously draining the EDIT_OP token bucket (burst 1100 / refill 8/s, calibrated for a
 * one-shot release burst); the sustained rejection stream disconnected the sender ~1.1–1.3 s
 * later. This packet collapses drag traffic into one stream: client-throttled at 100 ms, every
 * packet carrying <b>all</b> dragged entries (many nodes per packet — the collapse itself), a
 * relay-only server, and a frame-shadow overlay on remote editors.</p>
 *
 * <p><b>Relay-only</b>: nothing here applies to the authoritative graph — no apply, no
 * markDirty, no version allocation, no opLog, no full sync. An unfinished drag is not a
 * commit; the server graph keeps its pre-drag values until the release ops land via the
 * existing finish* paths ("commit on release"). Same trust tier as presence: entry geometry
 * is edited content, not identity, and the actor field is backfilled server-side
 * (the P1-2 discipline).</p>
 *
 * @param pos        position of the schematic edit-session block / 原理图编辑会话方块的位置
 * @param player     sender UUID — claimed value C2S, server-authoritative backfill S2C /
 *                   发送者 UUID——C2S 方向仅是声明值，S2C 由服务端权威回填
 * @param ownerNodeId  editing scope: -1 = main graph, >0 = encapsulation node id /
 *                     编辑作用域：-1=主图，>0=封装节点 id
 * @param entries    the dragged nodes' latest transient state / 被拖节点的最新临场状态
 */
public record GraphDragStreamPacket(
    BlockPos pos, UUID player, int ownerNodeId, List<Entry> entries
) implements CustomPacketPayload {

    /** NeoForge channel type id. / NeoForge 通道类型标识。 */
    public static final Type<GraphDragStreamPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "graph_drag_stream"));

    /** 条目种类：节点位置（x/y 有效）。 / Entry kind: node position (x/y meaningful). */
    public static final byte KIND_NODE_POS = 0;
    /** 条目种类：注释框尺寸（w/h 有效）。 / Entry kind: comment-box size (w/h meaningful). */
    public static final byte KIND_COMMENT_SIZE = 1;

    /** 独立 logger：不引用 (root) 包的 @Mod 类——其静态初始化依赖 MC 注册表，headless/JUnit
     *  下起不来。 / Same-name standalone logger: never references the @Mod class — its static
     *  init needs the Minecraft registries, so class init stays safe in plain JUnit too. */
    private static final Logger LOGGER = LoggerFactory.getLogger(GraphDragStreamPacket.class);

    /**
     * 单包条目上限（对齐 BlobDataPacket 分片尺寸的安全余量：512 条 × ~21B ≈ 11KB）。图容量
     * 上限 1024 节点的全选拖动由发送侧旋转续传分两窗覆盖——临场流 last-write-wins，截断
     * 无损语义。
     * <p>Per-packet entry cap (aligned with the BlobDataPacket chunk-sizing headroom: 512
     * entries × ~21 B ≈ 11 KB). A select-all drag at the 1024-node graph cap is covered in
     * two windows by the sender's rotating continuation — the stream is last-write-wins, so
     * truncation is loss-free by construction.</p>
     */
    public static final int MAX_ENTRIES_PER_PACKET = 512;

    /**
     * 一条拖动临场条目：被拖节点 id + 种类 + 几何。固定六字段形态（不按种类变长编码）——
     * 512 条上限下浪费的 8 字节远不抵双编解码路径的复杂度。
     * <p>One drag entry: dragged node id + kind + geometry. Fixed six-field shape (no
     * per-kind variable encoding) — at the 512-entry cap the 8 wasted bytes cost far less
     * than a second codec path would.</p>
     */
    public record Entry(int nodeId, byte kind, float x, float y, float w, float h) {
        /** 节点位置条目。 / Node-position entry. */
        public static Entry pos(int nodeId, float x, float y) {
            return new Entry(nodeId, KIND_NODE_POS, x, y, 0f, 0f);
        }
        /** 注释尺寸条目。 / Comment-size entry. */
        public static Entry commentSize(int nodeId, float w, float h) {
            return new Entry(nodeId, KIND_COMMENT_SIZE, 0f, 0f, w, h);
        }
    }

    /**
     * P0-1 安全钳制（照 GraphPresencePacket 的 selectedNodeIds 同款纪律）：先校验后分配。
     * varint 可声明至 2³¹-1，直接 new 会让单包打爆网络线程内存；超限 = 畸形/恶意包，
     * 抛 DecoderException 断开该连接。
     * <p>The P0-1 safety clamp (the GraphPresencePacket selectedNodeIds discipline verbatim):
     * validate before allocate. A varint can claim up to 2³¹-1 and a bare allocation lets one
     * packet OOM the network thread; over-limit means a malformed/malicious packet →
     * DecoderException drops the connection.</p>
     */
    public static final StreamCodec<ByteBuf, GraphDragStreamPacket> CODEC =
        new StreamCodec<>() {
            @Override public GraphDragStreamPacket decode(ByteBuf buf) {
                var b = new FriendlyByteBuf(buf);
                BlockPos pos = b.readBlockPos();
                UUID player = new UUID(b.readLong(), b.readLong());
                int owner = b.readVarInt();
                int count = b.readVarInt();
                if (count < 0 || count > MAX_ENTRIES_PER_PACKET)
                    throw new io.netty.handler.codec.DecoderException(
                        "GraphDragStreamPacket: entries count out of bounds: " + count
                            + " (limit " + MAX_ENTRIES_PER_PACKET + ")");
                var es = new java.util.ArrayList<Entry>(count);
                for (int i = 0; i < count; i++) {
                    int nodeId = b.readVarInt();
                    byte kind = b.readByte();
                    float x = b.readFloat(), y = b.readFloat(), w = b.readFloat(), h = b.readFloat();
                    es.add(new Entry(nodeId, kind, x, y, w, h));
                }
                return new GraphDragStreamPacket(pos, player, owner, es);
            }
            @Override public void encode(ByteBuf buf, GraphDragStreamPacket p) {
                var b = new FriendlyByteBuf(buf);
                b.writeBlockPos(p.pos);
                b.writeLong(p.player.getMostSignificantBits());
                b.writeLong(p.player.getLeastSignificantBits());
                b.writeVarInt(p.ownerNodeId);
                // 防御空列表——写入零长度以避免解码端 NPE / guard against null — write zero-length
                List<Entry> es = p.entries != null ? p.entries : List.of();
                b.writeVarInt(es.size());
                for (var e : es) {
                    b.writeVarInt(e.nodeId());
                    b.writeByte(e.kind());
                    b.writeFloat(e.x());
                    b.writeFloat(e.y());
                    b.writeFloat(e.w());
                    b.writeFloat(e.h());
                }
            }
        };

    @Override public @NotNull Type<? extends CustomPacketPayload> type() { return TYPE; }

    /**
     * <h4>Server-side handler / 服务端处理</h4>
     *
     * <p>校验照编辑 op 的门槛裁剪（临场流不改图，准入门/环检测/存在性校验不适用）：
     * 距离校验 + 会话成员校验，失格静默丢弃（DEBUG 一条）——临场数据没有客户端挂起状态，
     * 无回执可还。通过后以服务端权威 actor 重建并逐编辑者转发（跳过发起者，presence 中继
     * 同款纪律）。</p>
     *
     * <p>Validation reuses the edit-op gates trimmed to fit (the stream changes nothing, so
     * admission/cycle/existence checks do not apply): reach + session membership; a failed
     * gate drops silently with one DEBUG line — transient data carries no client pending
     * state, so there is no receipt to return. On pass, the packet is rebuilt with the
     * server-authoritative actor and relayed per editor, skipping the sender (the presence
     * relay discipline verbatim).</p>
     */
    public static void handleServer(GraphDragStreamPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            // 距离门槛与编辑 op 同源（128²）；失格 = 非编辑或走远的客户端，DEBUG 后丢弃。
            // Reach gate shared with the edit ops (128²); a failed gate means a non-editor or
            // a walked-away client — log DEBUG and drop.
            if (!io.github.y15173334444.create_schematic_compute.network.SablePacketHelper.isWithinReachableRange(
                    sp, pkt.pos(), GraphEditOpPacket.MAX_EDIT_DIST_SQ)) {
                LOGGER.debug("[GraphDragStream] dropped (out of range) from {}", sp.getName().getString());
                return;
            }
            // 会话成员门槛：不是该图编辑会话的成员不中继。
            // Session-membership gate: non-members of this edit session are not relayed to.
            var editors = io.github.y15173334444.create_schematic_compute.network.EditSessionRegistry
                .getEditors(sp.serverLevel(), pkt.pos());
            if (!editors.contains(sp.getUUID())) {
                LOGGER.debug("[GraphDragStream] dropped (not a registered editor) from {}", sp.getName().getString());
                return;
            }
            // P1-2：actor 由服务端权威回填，绝不转发客户端自带的 UUID。
            // P1-2: the actor is backfilled server-side; the client-supplied UUID is never forwarded.
            var authoritative = new GraphDragStreamPacket(pkt.pos(), sp.getUUID(), pkt.ownerNodeId(), pkt.entries());
            var sync = new GraphDragStreamSyncPacket(authoritative);
            var senderUUID = sp.getUUID();
            for (var editorId : editors) {
                if (editorId.equals(senderUUID)) continue;
                var ep = sp.getServer().getPlayerList().getPlayer(editorId);
                if (ep != null) PacketDistributor.sendToPlayer(ep, sync);
            }
        });
    }

    /**
     * <h4>Client-side handler / 客户端处理</h4>
     *
     * <p>应用到与包位置匹配的已打开图编辑器的临场覆盖层。无编辑器或位置不匹配则静默忽略
     * （presence 同款边界——UI 刚关闭后的在途包）。</p>
     *
     * <p>Applies to the open graph editor's drag overlay when the position matches; silently
     * ignored otherwise (the presence boundary verbatim — in-flight packets just after the
     * UI closed).</p>
     */
    public static void handleClient(GraphDragStreamPacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            var host = io.github.y15173334444.create_schematic_compute.client.editor.GraphEditor.getActiveHost();
            // getEditor() 可能为 null：独立像素编辑器也实现 Host 但无节点图编辑器（presence 同款跳过）。
            // getEditor() can be null: the standalone pixel editor implements Host without a
            // node-graph editor (skipped exactly like presence).
            if (host != null && host.getBlockPos().equals(pkt.pos()) && host.getEditor() != null) {
                host.getEditor().storeRemoteDragOverlay(pkt);
            }
        });
    }
}
