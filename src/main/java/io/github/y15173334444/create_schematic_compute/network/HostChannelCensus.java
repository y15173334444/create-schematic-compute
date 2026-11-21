package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.PinType;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * <h2>宿主通道普查（§6.4 步骤 3 / issue #25）：图 → 读/写通道集的枚举胶水</h2>
 *
 * <p>把一张宿主图（主图 + ENCAP 封装子图<b>递归</b>）枚举成 {@link TopoStats.ChannelCensus}：
 * 读集 = BUS_IN / PRIVATE_IN 的 {@code signalName} 通道 + 各 AUDIO 载体引脚的音频身份键，
 * 写集 = BUS_OUT / PRIVATE_OUT 的 {@code signalName} 通道 + 各 AUDIO 载体引脚的音频身份键
 * + AUDIO_OUT 的专属发布面。这是「结构核心（{@code graph/TopoStats}，纯 JDK）」与「MC 世界」
 * 之间唯一的枚举胶水——判定与执行分离（{@code C2SPacketRateGuard} 先例）：本类只做枚举，
 * 图构建 / SCC / 注册表全在结构核心里 headless 直测。</p>
 *
 * <p><b>通道身份必须复用 {@link SignalBus#key} 单一规范化入口</b>（§6.4 步骤 2 的单一源纪律）：
 * 枚举在宿主维度作用域（{@link SignalBus#runInDimension}）内进行，产出的键即
 * {@code <维度id>\u0001<逻辑名>} 前缀形态——两个维度的同名通道因此得到不同的键，
 * 通道边天然不跨维度；本类绝不复刻前缀格式，将来 epoch 键同样走该入口。</p>
 *
 * <p><b>ENCAP 递归</b>（§3.2「必须递归」）：封装求值走同一个 eval switch
 * （{@code GraphEvaluator} → {@code EncapEval} → 子 {@code GraphEvaluator}），子图里的
 * BUS_OUT / PRIVATE_OUT 一样写全局总线——不递归则 DAG 不完整，观测与将来切边全错；
 * ENCAP 套 ENCAP 自然再递归。防御面：子图嵌套引用环（损坏 NBT 理论形态）经 identity 访问集
 * 截断，枚举只读、不产生行为。</p>
 *
 * <p><b>范围边界（诚实记录）</b>：</p>
 * <ul>
 * <li>Create 红石链接不入集（§3.2 范围表：互操作兜底通道，时序归 Create，不测不改）。</li>
 * <li>空 {@code signalName} 不入集（P2-5 幽灵频道口径：空名不写不读）。</li>
 * <li>结构性普查、不看瞬时旗标：冲突 BUS_OUT（{@code busConflict}）仍算写者——占用冲突
 * 会自愈翻转，结构边按「通道名可能的依赖」保留，观测侧由步骤 4 的 epoch 记录区分实际来源。</li>
 * <li>音频面随声明载体入集（2026-11-20 扩面，§9.1-5 收口）：AUDIO_OUT 专属发布面与
 * BUS/PRIVATE 的 AUDIO 载体引脚各加一条<b>音频身份键</b>（{@link #audioCensusKey}，维度
 * 无关、表键形状与求值器逐位同源）——音频平面的求值序由全局拓扑接管，AUDIO_OUT→BUS_IN
 * 音频对由此同刻收敛、含音频边的环进切边规划、跨维度对同样有序。通道粒度读/写项原样
 * 保留（音频身份边是精化，不是替代——BUS 频段名单漂移由通道边兜底）。epoch 记账
 * （§3.1）仍不纳音频平面：音频走 AudioBands 表自己的发布时刻戳，不占浮点 epoch。</li>
 * <li>频段值键（{@code channel\0band}）不入集：通道身份 = 频道名（{@code signalName}），
 * 频段是同一通道的值载体划分，epoch 模型（§3.1）按通道 bump。</li>
 * </ul>
 *
 * <hr>
 * <h2>Host channel census (§6.4 step 3 / issue #25): the graph → read/write channel-set
 * enumeration glue</h2>
 *
 * <p>Enumerates one host's graph (main graph + ENCAP encapsulated sub-graphs, <b>recursively</b>)
 * into a {@link TopoStats.ChannelCensus}: the read set = the {@code signalName} channels of
 * BUS_IN / PRIVATE_IN plus one audio-identity key per AUDIO-carrier pin; the write set = the
 * {@code signalName} channels of BUS_OUT / PRIVATE_OUT plus one audio-identity key per
 * AUDIO-carrier pin plus AUDIO_OUT's dedicated publish face. This is the single enumeration
 * glue between the structural core
 * ({@code graph/TopoStats}, pure JDK) and the MC world — decision/execution split (the
 * {@code C2SPacketRateGuard} precedent): this class only enumerates; graph building, SCC and
 * the registry live in the structural core and are tested headless.</p>
 *
 * <p><b>Channel identity must reuse the single {@link SignalBus#key} normalization entry</b>
 * (the §6.4 step-2 single-source discipline): the enumeration runs inside the host's dimension
 * scope ({@link SignalBus#runInDimension}), producing {@code <dimensionId>\u0001<logical>}
 * prefixed keys — same-named channels in two dimensions get different keys, so channel edges
 * never cross dimensions; this class never replicates the prefix format, and the future epoch
 * keys ride the same entry.</p>
 *
 * <p><b>ENCAP recursion</b> (§3.2 "must recurse"): encapsulated evaluation rides the same eval
 * switch ({@code GraphEvaluator} → {@code EncapEval} → the sub {@code GraphEvaluator}), so a
 * sub-graph's BUS_OUT / PRIVATE_OUT writes the global bus exactly like the main graph — a
 * non-recursive census makes the DAG incomplete and every downstream observation and edge
 * cut wrong; ENCAP nested in ENCAP recurses naturally. Defensive face: a cyclic sub-graph
 * nesting (a corrupt-NBT theoretical shape) is cut off by an identity-based visited set; the
 * enumeration is read-only and produces no behaviour.</p>
 *
 * <p><b>Scope boundary (honest record)</b>:</p>
 * <ul>
 * <li>Create redstone links stay out (§3.2 scope table: the interop fallback channel; timing
 * belongs to Create — neither measured nor changed).</li>
 * <li>Empty {@code signalName} stays out (the P2-5 ghost-channel rule: an empty name neither
 * reads nor writes).</li>
 * <li>The census is structural and ignores transient flags: a conflicted BUS_OUT
 * ({@code busConflict}) still counts as a writer — the occupancy conflict self-heals and
 * flips; the structural edge keeps "possible dependency on the channel name", and step 4's
 * epoch records tell the actual source apart.</li>
 * <li>The audio plane joins per declared carrier (the 2026-11-20 widening, §9.1-5 closure):
 * AUDIO_OUT's dedicated publish face and the BUS/PRIVATE AUDIO-carrier pins each add one
 * <b>audio-identity key</b> ({@link #audioCensusKey}, dimension-agnostic, table-key shapes
 * bit-identical to the evaluator's) — the audio plane's evaluation order is owned by the
 * global topology from here on: AUDIO_OUT→BUS_IN audio pairs converge same-tick, cycles
 * involving audio edges enter the cut planning, and cross-dimension pairs are ordered too.
 * The channel-granularity read/write items stay verbatim (the audio-identity edges refine,
 * never replace — a BUS band-list drift is covered by the channel edge). The epoch
 * accounting (§3.1) still excludes the audio plane: audio rides AudioBands' own publish
 * stamps, outside the float epoch model.</li>
 * <li>Band value keys ({@code channel\0band}) stay out: the channel identity is the channel
 * name ({@code signalName}); bands are value-carrier partitions of one channel, and the
 * §3.1 epoch model bumps per channel.</li>
 * </ul>
 */
public final class HostChannelCensus {

    private HostChannelCensus() {}

    /**
     * 枚举宿主图的通道普查（主图 + ENCAP 子图递归）。方法自带宿主维度作用域
     * （{@link SignalBus#runInDimension}）——可在宿主 tick 体之外调用（控制台/Post 汇总），
     * 不依赖调用方记得设作用域；已在作用域内时嵌套恢复先前值即回原样。
     * <p>Enumerate the host graph's channel census (main graph + recursive ENCAP sub-graphs).
     * The method carries the host's dimension scope itself
     * ({@link SignalBus#runInDimension}) — callable outside the host's tick body (console /
     * Post summary) without the caller remembering to set a scope; inside an existing scope
     * the nested restore lands back unchanged.</p>
     *
     * @param root        宿主主图 / the host's main graph
     * @param dimensionId 宿主维度 id（键前缀来源；null/空 = 无作用域键空间，headless 测试口径）
     *                    the host's dimension id (the key-prefix source; null/empty = the
     *                    scope-free keyspace, the headless-test shape)
     * @return 普查快照（不可变有序；空图/零通道节点 = {@link TopoStats.ChannelCensus#EMPTY}）
     *         the census snapshot (immutable, ordered; an empty graph / no channel nodes =
     *         {@link TopoStats.ChannelCensus#EMPTY})
     */
    public static TopoStats.ChannelCensus census(NodeGraph root, String dimensionId) {
        var reads = new TreeSet<String>();
        var writes = new TreeSet<String>();
        if (root != null) {
            SignalBus.runInDimension(dimensionId, () -> collect(root, reads, writes, new HashSet<>()));
        }
        return new TopoStats.ChannelCensus(reads, writes);
    }

    /** 递归收集体：通道节点按类型入读/写集（键经 {@link SignalBus#key}），音频面按声明
     *  载体加音频身份键（键形状与求值器的频段表键逐位同源，见 {@link #audioCensusKey} 与
     *  {@link #collectAudioFaces}），ENCAPSULATION 下钻子图（{@code visited} 以图对象身份
     *  截断理论上的嵌套引用环）。
     *  The recursive collector: channel nodes join the read/write sets by type (keys via
     *  {@link SignalBus#key}), audio faces join audio-identity keys per declared carrier
     *  (key shapes bit-identical to the evaluator's band-table keys — see {@link
     *  #audioCensusKey} and {@link #collectAudioFaces}); ENCAPSULATION drills into its
     *  sub-graph ({@code visited} cuts the theoretical nested-reference cycle by graph-object
     *  identity). */
    private static void collect(NodeGraph g, Set<String> reads, Set<String> writes,
                                Set<NodeGraph> visited) {
        if (g == null || !visited.add(g)) return;
        for (var n : g.nodes) {
            if (n.type == NodeType.ENCAPSULATION) {
                collect(n.subGraph, reads, writes, visited);
                continue;
            }
            if (n.signalName == null || n.signalName.isEmpty()) continue;
            switch (n.type) {
                case BUS_IN, PRIVATE_IN -> {
                    reads.add(SignalBus.key(n.signalName));
                    collectAudioFaces(n, reads, false);
                }
                case BUS_OUT, PRIVATE_OUT -> {
                    writes.add(SignalBus.key(n.signalName));
                    collectAudioFaces(n, writes, true);
                }
                // AUDIO_OUT 专属发布面：表键 = bandKey(名, 名)（AudioEval AUDIO_OUT 同款）。
                // 不加通道粒度写项——它不写浮点载体，通道粒度写会给同频道浮点读者造伪依赖。
                // AUDIO_OUT's dedicated publish face: table key = bandKey(name, name) (the
                // AudioEval AUDIO_OUT shape). No channel-granularity write item — it writes no
                // float carrier, and one would forge a phantom dependency onto float readers
                // of the same channel.
                case AUDIO_OUT ->
                    writes.add(audioCensusKey(AudioBands.bandKey(n.signalName, n.signalName)));
                default -> { }
            }
        }
    }

    /** 音频频段引脚的普查面（键形状与求值器逐位同源）：PRIVATE 单引脚表键 = 原样逻辑名
     *  （BusEval PRIVATE_IN/OUT 同款），BUS 逐频段表键 = {@code 频道\0频段}。BUS_IN 的键名
     *  取节点自身频段列表（求值时优先注册表权威名单——名单漂移由通道粒度边兜住宿主级次序，
     *  音频身份边按「声明名单 = 可能的依赖」口径保留，与冲突 BUS_OUT 仍算写者同款哲学）。
     *  The census face of audio-band pins (key shapes bit-identical to the evaluator's):
     *  PRIVATE's single-pin table key = the raw logical name (the BusEval PRIVATE_IN/OUT
     *  shape), BUS's per-band table key = {@code channel\0band}. BUS_IN's band names come
     *  from the node's own list (evaluation prefers the registry's authoritative list — a
     *  list drift is covered host-order-wise by the channel-granularity edge; the
     *  audio-identity edge keeps the declared list as a possible dependency, the same
     *  philosophy as a conflicted BUS_OUT still counting as a writer). */
    private static void collectAudioFaces(GraphNode n, Set<String> keys, boolean writeSide) {
        if (n.type == NodeType.PRIVATE_IN || n.type == NodeType.PRIVATE_OUT) {
            boolean audio = writeSide ? n.inputType(0) == PinType.AUDIO
                                      : n.outputType(0) == PinType.AUDIO;
            if (audio) keys.add(audioCensusKey(n.signalName));
            return;
        }
        for (int bi = 0; bi < n.bandCount(); bi++) {
            boolean audio = writeSide ? n.inputType(bi) == PinType.AUDIO
                                      : n.outputType(bi) == PinType.AUDIO;
            if (audio)
                keys.add(audioCensusKey(AudioBands.bandKey(n.signalName, n.signalBands.get(bi))));
        }
    }

    /** 音频身份键（维度无关）：{@code audio\u0002<频段表键>}。AudioBands 是全局共享表
     *  （跨维度共享是其声明的设计性质），其依赖边因此不落维度前缀——发布/读取两侧经本
     *  单一入口构造，配对即真实表依赖；跨维度音频对由此同样进拓扑序。表键经
     *  {@link AudioBands#bandKey} 单一源产出（生产形态；端口的无接线桩键形仅测试用）。
     *  {@code \u0002} 分隔不出现在任何维度 id（资源位置）或编辑器可输入的频道名里，身份键
     *  与 {@code SignalBus.key} 的通道键结构性不撞。
     *  The audio identity key (dimension-agnostic): {@code audio\u0002<band table key>}.
     *  AudioBands is a globally shared table (cross-dimension sharing is its declared
     *  design nature), so its dependency edges carry no dimension prefix — publisher and
     *  reader construct through this single entry, a pairing IS the real table dependency,
     *  and cross-dimension audio pairs join the topological order by the same door. The
     *  table key rides the single {@link AudioBands#bandKey} source (the production shape;
     *  the unwired port stub's key shape is test-only). The {@code \u0002} separator appears
     *  in no dimension id (resource locations) and no editor-typed channel name, so identity
     *  keys structurally never collide with {@code SignalBus.key} channel keys. */
    public static String audioCensusKey(String bandTableKey) { return "audio\u0002" + bandTableKey; }
}
