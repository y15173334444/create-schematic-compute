package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * <h2>宿主通道普查（§6.4 步骤 3 / issue #25）：图 → 读/写通道集的枚举胶水</h2>
 *
 * <p>把一张宿主图（主图 + ENCAP 封装子图<b>递归</b>）枚举成 {@link TopoStats.ChannelCensus}：
 * 读集 = BUS_IN / PRIVATE_IN 的 {@code signalName} 通道，写集 = BUS_OUT / PRIVATE_OUT 的
 * {@code signalName} 通道。这是「结构核心（{@code graph/TopoStats}，纯 JDK）」与「MC 世界」
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
 * <li>AUDIO_OUT / CHANNEL（音频频段发布面）不入集：§3.2 范围表只纳 BUS / PRIVATE 通道；
 * 音频平面的新鲜度由 AudioBands 的 exactly-once 游标门控，不在 §3.1 的 epoch 模型里。
 * 经 BUS 通道承载的音频频段（BUS_OUT 的 AUDIO 频段）仍按<b>通道粒度</b>入集——BUS_OUT
 * 本就是写者。若观测显示音频环需要单独追，扩大本枚举是一行的事。</li>
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
 * BUS_IN / PRIVATE_IN; the write set = the {@code signalName} channels of BUS_OUT /
 * PRIVATE_OUT. This is the single enumeration glue between the structural core
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
 * <li>AUDIO_OUT / CHANNEL (the audio-band publish face) stay out: the §3.2 scope table admits
 * only BUS / PRIVATE channels; the audio plane's freshness is gated by AudioBands'
 * exactly-once cursor, outside §3.1's epoch model. Audio bands carried over a BUS channel
 * (a BUS_OUT's AUDIO bands) still enter at <b>channel granularity</b> — a BUS_OUT is a
 * writer regardless of band payload types. If observation shows audio rings need chasing,
 * widening this enumeration is a one-line change.</li>
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

    /** 递归收集体：通道节点按类型入读/写集（键经 {@link SignalBus#key}），ENCAPSULATION
     *  下钻子图（{@code visited} 以图对象身份截断理论上的嵌套引用环）。
     *  The recursive collector: channel nodes join the read/write sets by type (keys via
     *  {@link SignalBus#key}); ENCAPSULATION drills into its sub-graph ({@code visited} cuts
     *  the theoretical nested-reference cycle by graph-object identity). */
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
                case BUS_IN, PRIVATE_IN -> reads.add(SignalBus.key(n.signalName));
                case BUS_OUT, PRIVATE_OUT -> writes.add(SignalBus.key(n.signalName));
                default -> { }
            }
        }
    }
}
