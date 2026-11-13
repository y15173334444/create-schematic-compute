package io.github.y15173334444.create_schematic_compute.graph;

import java.util.List;

import io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator.InputSource;
import java.util.Map;

/**
 * 总线/常量域处理器（决策文档 §四）：VALUES / OUTPUT —— CONST、REDSTONE_IN/OUT、
 * PRIVATE_IN/OUT、BUS_IN/BUS_OUT（BUS_IN 音频分支随行）。case 体从 {@link GraphEvaluator}
 * 的 eval() switch 逐字搬迁（§三-2：方法体不改写，只改接收者/归属）；本类是静态函数包，
 * 不建继承体系（§三-3）。
 * <p>Bus / constant-domain handler (decision doc §四): VALUES / OUTPUT — CONST,
 * REDSTONE_IN/OUT, PRIVATE_IN/OUT, BUS_IN/BUS_OUT (BUS_IN's audio branch rides along).
 * Case bodies are relocated verbatim from {@link GraphEvaluator}'s eval() switch
 * (§三-2: bodies are never rewritten, only their owner changes); this class is a
 * static function pack, no inheritance hierarchy (§三-3).</p>
 *
 * <p><b>唯一字面偏差：</b>REDSTONE_IN 的 {@code s.freqKey} / {@code s.signal} 改为访问器
 * 形态 {@code s.freqKey()} / {@code s.signal()} —— {@link GraphEvaluator.InputSource}
 * 是 record，组件字段在嵌套类外恒为 private（Java 语义），访问器是跨类读的唯一形态；
 * 语义逐位一致（record 访问器即组件字段的读取）。
 * <p><b>The one literal deviation:</b> REDSTONE_IN's {@code s.freqKey} / {@code s.signal}
 * use accessor form — {@link GraphEvaluator.InputSource} is a record whose components
 * are unconditionally private outside the nesting class (Java semantics); the accessor
 * is the only cross-class read. Bit-identical semantics.</p>
 *
 * <p><b>口径 A 采集钩子（§3.1/§3.3，issue #26）：</b>PRIVATE_OUT / PRIVATE_IN / BUS_OUT /
 * BUS_IN 的浮点载体路径分支尾以门控尾随形式挂 {@link TopoStats#noteChannelWrite} /
 * {@link TopoStats#noteChannelRead}（写入 bump epoch + 槽位值，读取落
 * {@code (key, epoch, value)} 记录）—— 记录点在求值器分支（§3.3 关键实现选择），不在
 * 12 个 BE 的 tick 体。门控 {@code csc.topoCollect}（{@link TopoStats#COLLECT_PROPERTY}）
 * 关闭时每个钩子只有一次布尔读、无任何分配（无行为变更：求值顺序 / 剪线 / 收敛一概不动，
 * 键规范化经 {@link SignalBusPort#channelKey} 单一入口）。开启时 O(1) 记账；音频 / 向量
 * 载体不记账（音频由 AudioBands 的 exactly-once 游标门控、向量走 last-write-wins 状态
 * 语义，均不在 §3.1 的浮点 epoch 模型内）。</p>
 *
 * <p><b>Criterion-A collection hooks (§3.1/§3.3, issue #26):</b> the float-carrier paths of
 * PRIVATE_OUT / PRIVATE_IN / BUS_OUT / BUS_IN carry gated trailing
 * {@link TopoStats#noteChannelWrite} / {@link TopoStats#noteChannelRead} calls at their branch
 * tails (writes bump the epoch + slot value; reads drop a {@code (key, epoch, value)} record) —
 * the record points sit in the evaluator branches (§3.3's key implementation choice), never in
 * the twelve BE tick bodies. Gated by {@code csc.topoCollect} ({@link TopoStats#COLLECT_PROPERTY}):
 * disabled, each hook costs one boolean read and zero allocation (no behaviour change —
 * evaluation order, pruning and convergence untouched; key normalization rides the single
 * {@link SignalBusPort#channelKey} entry). Enabled, O(1) accounting. Audio / vector carriers are
 * not accounted (audio is gated by AudioBands' exactly-once cursor, vectors ride last-write-wins
 * state semantics — both outside §3.1's float epoch model).</p>
 */
public final class BusEval {

    private BusEval() {}

    /** 求值总线/常量域节点（case 体逐字搬迁，见类注释）。方法开头放局部别名块（接收者改写），
     *  让 case 体一个字都不用改；结尾 {@code ctx.o = o} 回写输出数组 —— BUS_IN 会重建
     *  {@code o = new float[...]; node.outputValues = o;}，调用方尾部记账直读 ctx.o 同步。
     *  Evaluate a bus / constant-domain node (case bodies relocated verbatim, see the
     *  class doc). The method opens with local aliases (receiver rewrite) so the case
     *  bodies stay untouched; it ends with a {@code ctx.o = o} write-back — BUS_IN may
     *  rebuild the array, which the caller's tail bookkeeping picks up via ctx.o.
     *
     * @param ctx 求值上下文（每调用/每节点槽已由调用方填好）/ evaluation context
     */
    static void eval(EvalContext ctx) {
        // 方法内局部别名（接收者改写）：case 体逐字保留 / local aliases (receiver rewrite):
        // the case bodies stay verbatim
        GraphNode node = ctx.node;
        float[] o = ctx.o;
        List<GraphEvaluator.InputSource> inputs = ctx.inputs;
        NodeGraph graph = ctx.graph;
        Map<Integer, float[]> outputs = ctx.outputs;
        Map<Long, PinPayload> payloadRefs = ctx.payloadRefs;
        SignalBusPort signalBus = ctx.signalBus;
        AudioBandsPort audioBands = ctx.audioBands;
        long audioTickStamp = ctx.audioTickStamp;
        switch (node.type) {
            case CONST -> o[0] = node.params.length > 0 ? node.params[0] : 0;
            case REDSTONE_IN -> {
                long nodeKey = FreqItems.freqKey(node.itemParams);
                int sig = 0;
                for (InputSource s : inputs) if (s.freqKey() == nodeKey) { sig = s.signal(); break; }
                o[0] = sig;
            }
            case REDSTONE_OUT -> { }
            case PRIVATE_IN -> {
                // 声明类型分派（v1.3.0 起拓扑推断退役）：AUDIO → 读 AudioBands（exactly-once
                // 游标 + 新鲜度门控）；VEC3 → 读向量值表；FLOAT → 读浮点 SIGNALS。
                // Declared-type dispatch (topology inference retired in v1.3.0): AUDIO reads
                // AudioBands (cursor + freshness); VEC3 reads the vector-value table; FLOAT
                // reads the float SIGNALS table.
                PinType pt = node.outputType(0);
                if (pt == PinType.AUDIO) {
                    o[0] = 0;
                    payloadRefs.put(EvalContext.payloadKey(node.id, 0),
                        audioBands.get(ctx.consumerKey(node), node.signalName, audioTickStamp));
                } else if (pt == PinType.VEC3) {
                    o[0] = 0;
                    Vec3f v = signalBus.getVec3(node.signalName);
                    payloadRefs.put(EvalContext.payloadKey(node.id, 0), v != null ? v : Vec3f.ZERO);
                } else {
                    o[0] = signalBus.get(node.signalName);
                    // 口径 A 读取侧钩子（§3.1，issue #26）：记录 (key, epoch, value) —— 门控
                    // 关闭时只有一次布尔读；无宿主上下文不落记录（§3.3：客户端本地求值永不
                    // 设上下文）。键经 SignalBusPort.channelKey 单一入口规范化。
                    // The criterion-A read-side hook (§3.1, issue #26): record (key, epoch,
                    // value) — one boolean read while gated off; no record without a host
                    // context (§3.3: client-local evaluation never sets one). Keys normalize
                    // through the single SignalBusPort.channelKey entry.
                    if (TopoStats.collecting())
                        TopoStats.noteChannelRead(signalBus.channelKey(node.signalName), "", o[0]);
                }
            }
            case PRIVATE_OUT -> {
                // 频道声明类型（引脚类型变换的属性源）：跟节点声明走、随版本戳同步给订阅方；
                // 冲突节点不算定义。载荷按声明类型入对应载体。
                // Channel declared type (the property behind pin typing): follows the node's
                // declaration, version-synced to subscribers; a conflicted node defines
                // nothing. The payload enters its declared carrier.
                if (!node.busConflict && !node.signalName.isEmpty()) {
                    signalBus.setPrivateType(node.signalName, node.inputType(0));
                }
                PinType pt = node.inputType(0);
                if (pt == PinType.AUDIO) {
                    AudioRef aref = graph.getInputAudio(node.id, 0, payloadRefs);
                    node.audioConflict = !node.signalName.isEmpty()
                        && !audioBands
                            .publish(node.signalName, aref != null ? aref : AudioRef.EMPTY,
                                audioTickStamp, ctx.consumerKey(node));
                } else if (pt == PinType.VEC3) {
                    // 冲突节点不写（写入权属于首个注册者）；空名不写（P2-5 幽灵频道口径）。
                    // A conflicted node writes nothing (write rights belong to the first
                    // registrant); an empty name writes nothing (P2-5 ghost-channel rule).
                    if (!node.busConflict && !node.signalName.isEmpty()) {
                        Vec3f v = graph.getInputVec3(node.id, 0, payloadRefs);
                        signalBus.putVec3(node.signalName, v != null ? v : Vec3f.ZERO);
                    }
                } else {
                    if (!node.busConflict && !node.signalName.isEmpty()) {
                        signalBus.put(node.signalName, graph.getInputValue(node.id, 0, outputs));
                        // 口径 A 写入侧钩子（§3.1，issue #26）：bump(key) —— 冲突节点不写
                        // 存储，自然不 bump（记账不查瞬时冲突旗标，与普查的结构性口径一致）。
                        // 值二次读取是纯查表（NodeGraph.getInputValue），行为逐位一致。
                        // The criterion-A write-side hook (§3.1, issue #26): bump(key) — a
                        // conflicted node writes no storage and naturally bumps nothing (the
                        // accounting never checks transient conflict flags, matching the
                        // census's structural criterion). The value re-read is a pure lookup
                        // (NodeGraph.getInputValue) — bit-identical behaviour.
                        if (TopoStats.collecting())
                            TopoStats.noteChannelWrite(signalBus.channelKey(node.signalName), "",
                                graph.getInputValue(node.id, 0, outputs));
                    }
                }
            }
            case BUS_IN -> {
                // 按频段声明类型分流（属性跟频段走）：AUDIO → 读 AudioBands 频段键（频道\0频段，
                // 游标 exactly-once + 新鲜度门控）；VEC3 → 读向量值表；FLOAT → 读浮点
                // internalMap——同一频道可混载，互不妨碍。
                // Per-band declared-type lanes: AUDIO reads its AudioBands band key
                // (channel\0band, cursor + freshness); VEC3 reads the vector-value table;
                // FLOAT reads the float internalMap — one channel can mix freely.
                if (node.signalName.isEmpty()) break; // 空名称不允许通信  /  Empty name disallows communication
                int bc = node.bandCount();
                if (bc > 0) {
                    if (o.length != bc) { o = new float[bc]; node.outputValues = o; ctx.o = o; }
                    // Band definition is owned by the channel's BUS_OUT and stored in the
                    // global BAND_REGISTRY. A BUS_IN in a DIFFERENT block may hold a stale
                    // signalBands list (BusBandUploadPacket only updates the sender's block),
                    // so prefer the registry's authoritative band list for KEY LOOKUP. This
                    // keeps the map key aligned with what BUS_OUT writes, fixing "BUS_OUT
                    // writes but BUS_IN reads 0" across blocks. The output array size still
                    // follows the node's own bandCount (pin count is node-defined); if the
                    // registry is longer we just read the leading bands, if shorter the rest
                    // fall back to the node's own names.
                    // band 定义归频道 BUS_OUT 所有，存于全局 BAND_REGISTRY。其他方块中的
                    // BUS_IN 可能持有过时的 signalBands（BusBandUploadPacket 只更新发送者
                    // 方块），故 KEY 查询优先用注册表的权威 band 列表，使 map key 与 BUS_OUT
                    // 写入一致，修复跨方块 "BUS_OUT 写入但 BUS_IN 读 0"。输出数组大小仍
                    // 遵循节点自身 bandCount（引脚数由节点定义）；注册表更长时只读前几个，
                    // 更短时其余回退到节点自身名称。
                    var registered = signalBus.getBands(node.signalName);
                    var chanMap = signalBus.channelMap(node.signalName);
                    for (int bi = 0; bi < bc; bi++) {
                        String key;
                        if (registered != null && bi < registered.size())
                            key = registered.get(bi);        // authoritative key / 权威 key
                        else
                            key = node.signalBands.get(bi);    // fallback to node list / 回退到节点列表
                        PinType pt = node.outputType(bi);
                        if (pt == PinType.AUDIO) {
                            o[bi] = 0;
                            payloadRefs.put(EvalContext.payloadKey(node.id, bi),
                                audioBands
                                    .get(ctx.consumerKey(node),
                                        audioBands
                                            .bandKey(node.signalName, key), audioTickStamp));
                            continue;
                        }
                        if (pt == PinType.VEC3) {
                            o[bi] = 0;
                            Vec3f v = signalBus.getVec3(audioBands.bandKey(node.signalName, key));
                            payloadRefs.put(EvalContext.payloadKey(node.id, bi), v != null ? v : Vec3f.ZERO);
                            continue;
                        }
                        o[bi] = chanMap != null ? chanMap.getOrDefault(key, 0f) : 0f;
                        // 口径 A 读取侧钩子（§3.1）：频段槽位记录 —— ② 的值比较落在读者实际
                        // 读的那一槽。 / The criterion-A read-side hook (§3.1): the band-slot
                        // record — ②'s value compare lands on the very slot the reader read.
                        if (TopoStats.collecting())
                            TopoStats.noteChannelRead(signalBus.channelKey(node.signalName), key, o[bi]);
                    }
                } else {
                    if (o.length < 1) { o = new float[1]; node.outputValues = o; ctx.o = o; }
                    var chanMap = signalBus.channelMap(node.signalName);
                    o[0] = (chanMap != null) ? chanMap.getOrDefault("", 0f) : 0;
                    // 口径 A 读取侧钩子（§3.1）：无频段 BUS 读 = "" 槽位。
                    // The criterion-A read-side hook (§3.1): a band-less BUS read = the "" slot.
                    if (TopoStats.collecting())
                        TopoStats.noteChannelRead(signalBus.channelKey(node.signalName), "", o[0]);
                }
            }
            case BUS_OUT -> {
                if (node.signalName.isEmpty()) break;
                int bc = node.bandCount();
                if (bc > 0) {
                    if (node.busInternalMap == null) node.busInternalMap = new java.util.HashMap<>();
                    // 冲突节点不注册频段，防止劫持 BAND_REGISTRY
                    // bandsDirty 跳过稳态下每 tick 的冗余 ArrayList 分配
                    // Conflicting nodes skip band registration to prevent BAND_REGISTRY hijacking
                    // bandsDirty avoids redundant ArrayList allocations per tick in steady state
                    if (!node.busConflict && node.bandsDirty) {
                        signalBus.registerBands(node.signalName, node.signalBands);
                        // 频段声明类型 = 定义（跟节点声明走，随频段定义同步给订阅方收敛/着色）；
                        // 取型走 GraphNode.bandTypeMap 单源，与注册点发布同源。
                        // Declared band types = the definition (follows the node, synced with
                        // the band definition for subscriber convergence and tinting); the
                        // map comes from GraphNode.bandTypeMap — same source the registration
                        // points publish from.
                        signalBus.setBandTypes(node.signalName, node.bandTypeMap());
                        node.bandsDirty = false;
                    }
                    // 冲突节点跳过 busInternalMap 写入 — MAP 修改权属于首个注册的 BUS_OUT
                    // Conflicting nodes skip busInternalMap writes — MAP mutation rights belong to
                    // the first registered BUS_OUT
                    if (!node.busConflict) {
                        // v1.2.4: Build pinId→value map from connections, then feed by band name.
                        // This avoids depending on cache position being in sync with signalBands order.
                        // 构建 pinId→值映射，再按频段名注入，避免依赖缓存位置与 signalBands 顺序一致。
                        var pinValues = new java.util.HashMap<String, Float>();
                        for (var c : graph.connections) {
                            if (c.toId == node.id) {
                                float[] out = outputs.get(c.fromId);
                                if (out != null) {
                                    float val = c.fromPin < out.length ? out[c.fromPin] : 0;
                                    // 按参段键控（pinId 带类型段，键=频段名）/ key by param segment
                                    String pid = c.toPinId != null ? PinId.paramOf(c.toPinId) : String.valueOf(c.toPin);
                                    pinValues.put(pid, val);
                                }
                            }
                        }
                        // 按频段声明类型入载体：AUDIO → AudioBands（单引脚多声道，跨方块，
                        // 带发布时刻；owner = 宿主坐标+节点 id，R1-2 冲突纪律，与 AUDIO_OUT
                        // 同款，混载频道任一音频频段冲突即置旗标）；VEC3 → 向量值表；FLOAT →
                        // internalMap。
                        // Declared-type carriers: AUDIO publishes to AudioBands (single-pin
                        // multi-channel, cross-block, timestamped; owner = host pos + node id,
                        // the R1-2 conflict discipline, any conflicted audio band flags the
                        // node); VEC3 writes the vector-value table; FLOAT writes internalMap.
                        node.audioConflict = false;
                        for (int bi = 0; bi < bc; bi++) {
                            String bandName = node.signalBands.get(bi);
                            PinType pt = node.inputType(bi);
                            if (pt == PinType.AUDIO) {
                                AudioRef aref = graph.getInputAudio(node.id, bi, payloadRefs);
                                // 频段键 = 频道\0频段：每个频段独立占用/冲突/游标，互不覆盖。
                                // Per-band key = channel\0band: independent ownership, conflict
                                // and cursor per band — no cross-band overwrites.
                                if (!audioBands
                                    .publish(audioBands
                                            .bandKey(node.signalName, bandName),
                                        aref != null ? aref : AudioRef.EMPTY,
                                        audioTickStamp, ctx.consumerKey(node)))
                                    node.audioConflict = true;
                            } else if (pt == PinType.VEC3) {
                                Vec3f v = graph.getInputVec3(node.id, bi, payloadRefs);
                                signalBus.putVec3(audioBands.bandKey(node.signalName, bandName),
                                    v != null ? v : Vec3f.ZERO);
                            } else {
                                node.busInternalMap.put(bandName, pinValues.getOrDefault(bandName, 0f));
                                // 口径 A 写入侧钩子（§3.1）：频段槽位 bump —— 多频段 FLOAT 写
                                // 逐槽记账（epoch 按调用递增，读者看到的是分支前 / 后两个版本
                                // 之一，粒度不影响 ① 判定）。
                                // The criterion-A write-side hook (§3.1): the band-slot bump —
                                // multi-band FLOAT writes account per slot (the epoch advances
                                // per call; readers see either the pre-branch or post-branch
                                // version, so the granularity never affects the ① verdict).
                                if (TopoStats.collecting())
                                    TopoStats.noteChannelWrite(signalBus.channelKey(node.signalName),
                                        bandName, pinValues.getOrDefault(bandName, 0f));
                            }
                        }
                    }
                }
            }
        }
        ctx.o = o;
    }
}
