package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.network.GraphDragStreamPacket;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * <h2>拖动临场流跟踪器（docs/packet-flood-rate-limit-plan.md §三，批 1）</h2>
 *
 * <p><b>发送侧</b>：四条拖拽路径把「被拖节点集合的当前几何」交给 {@link #submit}；本类按
 * {@link #STREAM_INTERVAL_MS}（100ms）节流出站，条目数超过
 * {@link GraphDragStreamPacket#MAX_ENTRIES_PER_PACKET}（512）时旋转续传——1024 节点全选拖动
 * 两窗覆盖全部节点，窗口间 pending 始终以最新值整体替换（last-write-wins）。</p>
 *
 * <p><b>接收侧</b>：远端拖拽覆盖层，按节点 id 存最新条目，{@link #OVERLAY_TIMEOUT_MS}
 * （500ms）静默即失效。应用方式 = <b>渲染帧影</b>：{@link #beginFrame} 把新鲜覆盖经
 * smoothstep 平滑（presence 光标同款手感）写进节点字段，{@link #endFrame} 在 {@code finally}
 * 中还原图权威值——渲染器几何代码零改动（JIT-1 的涂盖/遮挡口径不受扰动），帧外输入路径
 * （命中测试发生在帧间）看到的永远是图真值；临场流中断/拖动者掉线时覆盖自然失效、无任何
 * 残留状态需要清扫。本类不产生 op、不碰 pendingLocalOps、不触 markDirty——服务端只中继
 * 不落图，「松手即提交」的客户端对应面。</p>
 *
 * <hr>
 * <h2>Drag presence stream tracker (plan doc §3, batch 1)</h2>
 *
 * <p><b>Send side</b>: the four drag paths hand the dragged set's current geometry to
 * {@link #submit}; this class paces it at {@link #STREAM_INTERVAL_MS} (100 ms) and, above
 * {@link GraphDragStreamPacket#MAX_ENTRIES_PER_PACKET} (512) entries, rotates the continuation
 * across windows — a 1024-node select-all drag covers every node in two windows, with pending
 * wholesale-replaced by the latest values between windows (last-write-wins).</p>
 *
 * <p><b>Receive side</b>: the remote drag overlay stores the latest entry per node id and goes
 * stale after {@link #OVERLAY_TIMEOUT_MS} (500 ms) of silence. Application = a <b>render frame
 * shadow</b>: {@link #beginFrame} writes fresh overlays into node fields through a smoothstep
 * (the presence-cursor feel), and {@link #endFrame} restores graph-authoritative values in a
 * {@code finally} — the renderer's geometry code stays untouched (the JIT-1 occlusion/cover
 * calipers are undisturbed), input paths between frames always see graph truth, and a stalled
 * stream or a vanished dragger just expires the overlay with no residue to sweep. This class
 * emits no ops, never touches pendingLocalOps, never marks dirty — the client face of
 * "relay-only server, commit on release".</p>
 */
final class GraphDragStreamTracker {

    /** 出站节流间隔（回填 10/s ≥ 此速率 → 服务端桶恒满零拒绝）。 / Outbound pacing interval
     *  (refill 10/s ≥ this rate keeps the server bucket full — zero rejections). */
    static final long STREAM_INTERVAL_MS = 100;

    /** 覆盖层静默失效窗口：容忍 ~4 个丢窗；过期即回落图权威值。 / Overlay silence window:
     *  tolerates ~4 lost windows; expiry falls back to graph truth. */
    static final long OVERLAY_TIMEOUT_MS = 500;

    /** smoothstep 每帧推进量——与远端 op 的 {@code advanceRemoteMoveLerp} 同节奏（0.12/帧，
     *  8-9 帧 ≈ 140ms@60fps 收敛）。滑行时长必须超过 100ms 发包间隔：否则每个窗口内先滑完
     *  再驻停，视觉上就是「一格一格」（批 1.1 用户实测报告，0.25 时驻停 ~34ms/窗）。
     *  Smoothstep advance per frame — the same cadence as the remote-op {@code
     *  advanceRemoteMoveLerp} (0.12/frame, 8-9 frames ≈ 140 ms @60 fps). The glide must
     *  outlast the 100 ms stream interval; a faster step finishes the glide and then holds
     *  still inside each window, which reads as stepping (batch 1.1, user-reported). */
    static final float SMOOTHSTEP_STEP = 0.12f;

    private final LongSupplier clockMs;
    /** 出站包构造接缝：生产端从编辑器取 pos/actor/作用域；测试注入固定工厂（headless 不必起
     *  编辑器）。 / Outbound packet-factory seam: production derives pos/actor/scope from the
     *  editor; tests inject a fixed factory (no editor needed headless). */
    private final java.util.function.Function<List<GraphDragStreamPacket.Entry>, GraphDragStreamPacket> packetFactory;
    private final Consumer<GraphDragStreamPacket> outbound;

    // ── 发送侧状态 / send-side state ──
    private List<GraphDragStreamPacket.Entry> pending = List.of();
    private long lastSendMs = 0;
    private int rotateOffset = 0;

    // ── 接收侧状态 / receive-side state ──
    /** nodeId → 最新临场条目（含作用域与时间戳）。 / nodeId → latest overlay entry (scope + timestamp). */
    private final Map<Integer, Live> overlays = new HashMap<>();
    /** nodeId → {x, y, w, h} 本帧改动前的图权威值（帧末还原，帧间即清）。 /
     *  nodeId → {x, y, w, h} graph truth before this frame's shadow (restored at frame end). */
    private final Map<Integer, float[]> stash = new HashMap<>();
    /** nodeId → {curX, fromX, tgtX, curY, fromY, tgtY, t} 位置平滑状态。 /
     *  nodeId → {curX, fromX, tgtX, curY, fromY, tgtY, t} position smoothing state. */
    private final Map<Integer, float[]> posShadow = new HashMap<>();
    /** nodeId → {curW, fromW, tgtW, curH, fromH, tgtH, t} 尺寸平滑状态。 /
     *  nodeId → {curW, fromW, tgtW, curH, fromH, tgtH, t} size smoothing state. */
    private final Map<Integer, float[]> sizeShadow = new HashMap<>();

    /** 一条在效覆盖：作用域 + 条目 + 落地时刻。 / One live overlay: scope + entry + arrival time. */
    private record Live(int owner, GraphDragStreamPacket.Entry e, long ts) { }

    /** 生产构造：真实时钟 + 直发服务端（包字段取自编辑器）。 / Production: real clock, sent
     *  straight to the server (packet fields derived from the editor). */
    GraphDragStreamTracker(GraphEditor ed) {
        this(System::currentTimeMillis,
            slice -> new GraphDragStreamPacket(ed.host.getBlockPos(), ed.host.getPlayerUUID(), ed.ownerNodeId(), slice),
            pkt -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(pkt));
    }

    /** 注入构造（headless 测试）：假钟 + 固定包工厂 + 出站捕获。 / Injectable (headless tests):
     *  fake clock + fixed packet factory + captured outbound. */
    GraphDragStreamTracker(LongSupplier clockMs,
            java.util.function.Function<List<GraphDragStreamPacket.Entry>, GraphDragStreamPacket> packetFactory,
            Consumer<GraphDragStreamPacket> outbound) {
        this.clockMs = clockMs;
        this.packetFactory = packetFactory;
        this.outbound = outbound;
    }

    // ───────────────────────── 发送侧 / send side ─────────────────────────

    /**
     * 拖拽路径提交本窗最新几何（≤20Hz 由调用方的 50ms 墙钟闸保证）；出站按 100ms 再节流，
     * 超过单包上限时旋转续传。空列表 = 拖拽已结束，清空 pending 即可。
     * <p>A drag path submits this window's latest geometry (≤20 Hz, guaranteed by the caller's
     * 50 ms wall-clock gate); outbound is paced at 100 ms on top, with rotating continuation
     * above the per-packet cap. An empty list means the drag ended — just drop pending.</p>
     */
    void submit(List<GraphDragStreamPacket.Entry> latest) {
        if (latest == null || latest.isEmpty()) {
            pending = List.of();
            return;
        }
        pending = latest;
        tryFlush();
    }

    private void tryFlush() {
        long now = clockMs.getAsLong();
        if (pending.isEmpty()) return;
        if (now - lastSendMs < STREAM_INTERVAL_MS) return;
        lastSendMs = now;
        int n = pending.size();
        int from = Math.floorMod(rotateOffset, n);
        int len = Math.min(GraphDragStreamPacket.MAX_ENTRIES_PER_PACKET, n);
        var slice = new java.util.ArrayList<GraphDragStreamPacket.Entry>(len);
        for (int i = 0; i < len; i++) {
            int idx = from + i;
            slice.add(pending.get(idx < n ? idx : idx - n));
        }
        outbound.accept(packetFactory.apply(slice));
        rotateOffset = from + len < n ? from + len : from + len - n;
    }

    // ───────────────────────── 接收侧 / receive side ─────────────────────────

    /** 存一包远端临场条目（pos/actor 已由处理器过滤，只取作用域 + 条目）。
     *  Store one relayed packet (pos/actor already filtered by the handler — scope + entries only). */
    void storeRemoteDrag(GraphDragStreamPacket pkt) {
        if (pkt.entries() == null) return;
        long now = clockMs.getAsLong();
        int owner = pkt.ownerNodeId();
        for (var e : pkt.entries()) {
            overlays.put(e.nodeId(), new Live(owner, e, now));
        }
    }

    /** 权威 op 接管（批 1.1）：仍新鲜的覆盖层条目把目标更新为 op 携带的权威终值并续期——
     *  影子随之平滑滑向终值，过期时真值已收敛，无跳变。松手尾巴（最后一次流冲刷与松手之间
     *  ≤100ms 的位移）只存在于松手 op 里，没有这条接管就会冻结在流末位、过期时跳变。
     *  仅 MOVE_NODE / SET_COMMENT_SIZE 携带相关几何（宽高走 op.x/op.y，同 OpExecutor 口径）；
     *  REJECT 分支在 GraphRemoteApplier 已提前返回，被拒 op 到不了这里。
     *  <p>Authoritative adoption (batch 1.1): a still-fresh overlay entry adopts the op's
     *  authoritative final as its target and is refreshed — the shadow glides into the final
     *  and expires into it with no jump. The release tail (≤100 ms of travel between the last
     *  stream flush and the release) exists only in the release ops; without adoption the
     *  shadow freezes at the last stream position and jumps on expiry. Only MOVE_NODE /
     *  SET_COMMENT_SIZE carry relevant geometry (w/h ride op.x/op.y, matching OpExecutor);
     *  rejected ops never reach here (the REJECT branch returns early in GraphRemoteApplier).</p> */
    void adoptAuthoritative(io.github.y15173334444.create_schematic_compute.graph.GraphOp op) {
        int owner = op.ownerNodeId();
        int id = op.targetNodeId();
        var live = overlays.get(id);
        if (live == null || live.owner() != owner) return;   // 无覆盖或跨作用域 → 不管 / no overlay or scope mismatch
        long now = clockMs.getAsLong();
        if (op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.MOVE_NODE) {
            overlays.put(id, new Live(owner, GraphDragStreamPacket.Entry.pos(id, op.x(), op.y()), now));
        } else if (op.type() == io.github.y15173334444.create_schematic_compute.graph.OpType.SET_COMMENT_SIZE) {
            overlays.put(id, new Live(owner, GraphDragStreamPacket.Entry.commentSize(id, op.x(), op.y()), now));
        }
    }

    /** 清空全部覆盖状态（编辑器关闭时与 presence 一起走）。 / Clear everything (editor close,
     *  alongside presence). */
    void clear() {
        overlays.clear();
        stash.clear();
        posShadow.clear();
        sizeShadow.clear();
        pending = List.of();
    }

    /**
     * 渲染帧首：过期清理后，把同作用域的新鲜覆盖经平滑写进节点字段。必须在渲染几何段之前
     * 调用，且与 {@link #endFrame} 成对（endFrame 置于 finally）。
     * <p>Frame start: after stale cleanup, writes same-scope fresh overlays into node fields
     * through smoothing. Call before the render-geometry segment, paired with {@link #endFrame}
     * (placed in a finally).</p>
     */
    void beginFrame(NodeGraph graph, int owner) {
        cleanupStale(clockMs.getAsLong());
        for (var live : overlays.values()) {
            if (live.owner() != owner) continue;   // 不同作用域不画（presence 同款）/ skip other scopes
            var e = live.e();
            GraphNode n = graph.findNode(e.nodeId());
            if (n == null) continue;               // 节点已被删/未同步 → 跳过 / gone or not yet synced → skip
            // 幕布原值：每帧从图真值重新取（endFrame 已还原，故这里总是权威值）。
            // Frame originals: re-taken from graph truth every frame (endFrame has restored,
            // so this is always the authoritative value).
            float[] st = stash.computeIfAbsent(e.nodeId(),
                k -> new float[]{n.x, n.y, n.commentWidth, n.commentHeight});
            if (e.kind() == GraphDragStreamPacket.KIND_COMMENT_SIZE) {
                float[] sh = sizeShadow.computeIfAbsent(e.nodeId(),
                    k -> new float[]{st[2], st[2], e.w(), st[3], st[3], e.h(), 0f});
                advance(sh, e.w(), e.h());
                n.commentWidth = sh[0];
                n.commentHeight = sh[3];
            } else {
                float[] sh = posShadow.computeIfAbsent(e.nodeId(),
                    k -> new float[]{st[0], st[0], e.x(), st[1], st[1], e.y(), 0f});
                advance(sh, e.x(), e.y());
                n.x = sh[0];
                n.y = sh[3];
            }
        }
    }

    /** smoothstep 一步：目标变了就从当前显示位起新段（平滑接续），否则继续逼近。
     *  One smoothstep step: a changed target restarts the segment from the current display
     *  position (seamless continuation), otherwise keeps approaching. */
    private static void advance(float[] sh, float tgtA, float tgtB) {
        if (sh[2] != tgtA || sh[5] != tgtB) {
            sh[1] = sh[0]; sh[2] = tgtA;
            sh[4] = sh[3]; sh[5] = tgtB;
            sh[6] = 0f;
        }
        if (sh[6] < 1f) {
            sh[6] = Math.min(1f, sh[6] + SMOOTHSTEP_STEP);
            float s = sh[6] * sh[6] * (3f - 2f * sh[6]);
            sh[0] = sh[1] + (sh[2] - sh[1]) * s;
            sh[3] = sh[4] + (sh[5] - sh[4]) * s;
        }
    }

    /**
     * 渲染帧末：把节点字段还原本帧改动前的图权威值。必须在 finally 中调用——异常路径也要
     * 归还幕布，绝不让临场值滞留到帧外。
     * <p>Frame end: restores node fields to the graph truth captured before this frame's
     * shadow. Must run in a finally — even the exception path returns the curtain; transient
     * values must never survive past the frame.</p>
     */
    void endFrame(NodeGraph graph) {
        for (var en : stash.entrySet()) {
            GraphNode n = graph.findNode(en.getKey());
            if (n == null) continue;
            float[] st = en.getValue();
            n.x = st[0];
            n.y = st[1];
            n.commentWidth = st[2];
            n.commentHeight = st[3];
        }
        stash.clear();
    }

    /** 丢弃静默超时的覆盖（及其平滑状态）。 / Drops overlays (and their smoothing state)
     *  silent past the timeout. */
    private void cleanupStale(long now) {
        var it = overlays.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            if (now - en.getValue().ts() > OVERLAY_TIMEOUT_MS) {
                posShadow.remove(en.getKey());
                sizeShadow.remove(en.getKey());
                it.remove();
            }
        }
    }

    // ── 测试观测面 / test observation ──
    int overlayCount() { return overlays.size(); }
    boolean hasStash() { return !stash.isEmpty(); }
}
