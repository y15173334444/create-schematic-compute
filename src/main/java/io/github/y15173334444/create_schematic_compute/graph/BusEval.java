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
        Map<Long, AudioRef> audioRefs = ctx.audioRefs;
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
                // 私有频段音频分支（R1-2 完整落地）：输出接了音频线（下游 AUDIO 域引脚）→ 按
                // consumerKey 读 AudioBands（exactly-once 游标 + 新鲜度门控，与 BUS_IN 同款；
                // 空名/缺席/陈旧均为空音源）；否则走浮点 SIGNALS。
                // Private-band audio branch: output wired to an audio pin reads AudioBands
                // (same cursor/freshness discipline as BUS_IN); otherwise float SIGNALS.
                if (graph.hasAudioSinkConnection(node.id)) {
                    audioRefs.put(EvalContext.audioKey(node.id, 0),
                        audioBands
                            .get(ctx.consumerKey(node), node.signalName, audioTickStamp));
                    break;
                }
                o[0] = signalBus.get(node.signalName);
            }
            case PRIVATE_OUT -> {
                // 私有频段音频分支（R1-2）：输入来自音频线 → 发布 AudioRef 到 AudioBands
                //（名字 = signalName，owner/冲突纪律与 AUDIO_OUT 同款，空名不发布）；否则浮点。
                // Private-band audio branch: audio input publishes the AudioRef to AudioBands
                // (owner/conflict discipline as AUDIO_OUT); otherwise float.
                // 频道定义（引脚类型变换的属性源）：输入对端是音频 → 频道承载音频，随版本戳
                // 同步给订阅方着色；属性跟频道走（拓扑判定、稳态零开销），冲突节点不算定义。
                // Channel definition (the property behind pin typing): an audio pin peer makes
                // the channel audio-carrying, version-synced to subscribers; the property rides
                // the channel (topology-derived), and a conflicted node defines nothing.
                if (!node.busConflict && !node.signalName.isEmpty()) {
                    signalBus.setPrivateAudio(node.signalName, graph.isBandPinAudio(node.id, 0, false));
                }
                AudioRef aref = graph.getAudioInputRef(node.id, 0, audioRefs);
                if (aref != null) {
                    node.audioConflict = !node.signalName.isEmpty()
                        && !audioBands
                            .publish(node.signalName, aref, audioTickStamp, ctx.consumerKey(node));
                    break;
                }
                // 浮点写入（占用检测，BUS 同款）：冲突节点不写——写入权属于首个注册者；
                // 空名不写（BUS_OUT 同款早退口径，P2-5）——否则未命名节点互写 SIGNALS[""]
                // 幽灵频道，释放路径 clearSignal("") 清不掉。
                // Float write (BUS-parity occupancy): a conflicted node writes nothing — write
                // rights belong to the first registrant; an empty name writes nothing either
                // (BUS_OUT's same early-out, P2-5) — otherwise unnamed nodes cross-write the
                // SIGNALS[""] ghost channel that release paths can never clear.
                if (!node.busConflict && !node.signalName.isEmpty())
                    signalBus.put(node.signalName, graph.getInputValue(node.id, 0, outputs));
            }
            case BUS_IN -> {
                // 按频段分流（属性跟频段走）：某频段的输出接了音频线 → 该频段读自己的
                // AudioBands 频段键（频道\0频段，游标 exactly-once + 新鲜度门控同前）；
                // 其余频段走浮点 internalMap——同一频道可混载音频与浮点频段，互不妨碍。
                // Per-band lanes: a band whose output enters an audio pin reads its own
                // AudioBands band key (channel\0band, cursor + freshness as before); the rest
                // read the float internalMap — one channel can mix audio and float bands.
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
                        // 音频频段：该频段的输出接了音频线 → 读该频段的音频键。音频面独立于
                        // 浮点频道表（CHANNELS 由宿主 tick 引用计数注册，纯求值环境可能缺席）。
                        // Audio band: this band's output is wired to an audio pin. The audio
                        // plane is independent of the float CHANNELS table (ref-counted by host
                        // ticks, absent in pure-eval contexts).
                        if (graph.isAudioWired(node.id, bi)) {
                            audioRefs.put(EvalContext.audioKey(node.id, bi),
                                audioBands
                                    .get(ctx.consumerKey(node),
                                        audioBands
                                            .bandKey(node.signalName, key), audioTickStamp));
                            continue;
                        }
                        o[bi] = chanMap != null ? chanMap.getOrDefault(key, 0f) : 0f;
                    }
                } else {
                    if (o.length < 1) { o = new float[1]; node.outputValues = o; ctx.o = o; }
                    var chanMap = signalBus.channelMap(node.signalName);
                    o[0] = (chanMap != null) ? chanMap.getOrDefault("", 0f) : 0;
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
                                    String pid = c.toPinId != null ? c.toPinId : String.valueOf(c.toPin);
                                    pinValues.put(pid, val);
                                }
                            }
                        }
                        // 音频频段引脚：接音频线 → 发到 AudioBands（单引脚多声道，跨方块，带发布时刻）。
                        // owner = 宿主坐标+节点 id（R1-2 冲突纪律，与 AUDIO_OUT 同款）；
                        // 混载频道任一音频频段冲突即置旗标，无音频的频段不影响旗标。
                        node.audioConflict = false;
                        // 频段音频标志（按引脚对端域的拓扑判定，随频段定义同步给订阅方着色；
                        // isBandPinAudio 走拓扑版本缓存，稳态零开销）。冲突节点不算定义。
                        // Per-band audio flags (topology-based via pin peers, synced with the
                        // band definition for subscriber tinting); conflicting nodes define nothing.
                        var audioBandSet = new java.util.HashSet<String>();
                        for (int bi = 0; bi < bc; bi++)
                            if (graph.isBandPinAudio(node.id, bi, false))
                                audioBandSet.add(node.signalBands.get(bi));
                        signalBus.setAudioBands(node.signalName, audioBandSet);
                        for (int bi = 0; bi < bc; bi++) {
                            String bandName = node.signalBands.get(bi);
                            AudioRef aref = graph.getAudioInputRef(node.id, bi, audioRefs);
                            if (aref != null) {
                                // 频段键 = 频道\0频段：每个频段独立占用/冲突/游标，互不覆盖。
                                // Per-band key = channel\0band: independent ownership, conflict
                                // and cursor per band — no cross-band overwrites.
                                if (!audioBands
                                    .publish(audioBands
                                            .bandKey(node.signalName, bandName),
                                        aref, audioTickStamp, ctx.consumerKey(node)))
                                    node.audioConflict = true;
                            } else {
                                node.busInternalMap.put(bandName, pinValues.getOrDefault(bandName, 0f));
                            }
                        }
                    }
                }
            }
        }
        ctx.o = o;
    }
}
