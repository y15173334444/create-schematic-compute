package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.ChannelCensus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 宿主通道普查的锚（§6.4 步骤 3 / issue #25）：图 → 读/写通道集的枚举胶水，DAG 通道身份
 * 的**单一源**面。用真实 {@link NodeGraph} 合成图集 headless 直测（issue 验收口径：ENCAP
 * 嵌套、同通道自环、跨 host 链的图集）。这里锁死：
 * <ul>
 *   <li>通道身份复用 {@link SignalBus#key} 单一入口：普查键与同作用域内 {@code key()} 的
 *       产出逐位相等（单一源纪律——本测试若发现普查另立前缀格式即刻失败）；并锁一处
 *       {@code <维度id>\u0001<逻辑名>} 的字面形态。</li>
 *   <li>维度隔离的边语义：两个维度同名通道 → 两份不同前缀键（通道边天然同维度——跨维度
 *       的是注册表，不是通道边）；无作用域（null）调用拿原样逻辑名。</li>
 *   <li>ENCAP 递归（§3.2「必须递归」）：子图 BUS_OUT/PRIVATE_OUT 计入写集；ENCAP 套
 *       ENCAP 再递归（三层）；同 host 自环形态——主图 BUS_IN 读本 host ENCAP 子图写的
 *       同一通道，读/写集交集非空；嵌套引用环（损坏 NBT 理论形态）被 identity 访问集
 *       截断，枚举必然终止。</li>
 *   <li>范围边界（诚实记录）：空 {@code signalName} 不入集（P2-5 幽灵口径）；频段值键
 *       （{@code channel\0band}）不入集——通道身份 = 频道名；AUDIO_OUT 音频发布面不入集；
 *       非通道节点（CONST/ADD/ENCAP_INPUT/ENCAP_OUTPUT 等）不入集。</li>
 *   <li>作用域自含与只读性：普查自带 {@code runInDimension}（嵌套安全，外层作用域原样
 *       恢复）；普查不写总线任何表。</li>
 * </ul>
 *
 * <p>Anchors for the host channel census (§6.4 step 3 / issue #25): the graph → read/write
 * channel-set enumeration glue, the <b>single-source</b> face of the DAG's channel identity.
 * Synthetic graph sets of real {@link NodeGraph}s, tested headless (the issue's acceptance
 * shape: ENCAP nesting, same-channel self-loops, cross-host chains). Pinned here:</p>
 * <ul>
 *   <li>Channel identity reuses the single {@link SignalBus#key} entry: census keys are
 *       bit-identical to what {@code key()} produces under the same scope (the single-source
 *       discipline — if the census ever grew its own prefix format, this test fails); one
 *       literal {@code <dimensionId>\u0001<logical>} shape is pinned too.</li>
 *   <li>The dimension isolation's edge semantics: same-named channels in two dimensions → two
 *       differently prefixed keys (channel edges are naturally same-dimension — what crosses
 *       dimensions is the registry, never a channel edge); an unscoped (null) call gets the
 *       logical name unchanged.</li>
 *   <li>ENCAP recursion (§3.2 "must recurse"): a sub-graph's BUS_OUT/PRIVATE_OUT joins the
 *       write set; ENCAP in ENCAP recurses again (three levels); the same-host self-loop
 *       shape — a main-graph BUS_IN reading the very channel an ENCAP sub-graph of the same
 *       host writes — leaves the read/write intersection non-empty; a cyclic sub-graph
 *       nesting (corrupt-NBT theoretical shape) is cut by the identity visited set and the
 *       enumeration provably terminates.</li>
 *   <li>Scope boundary (honest record): empty {@code signalName} stays out (the P2-5 ghost
 *       rule); band value keys ({@code channel\0band}) stay out — the channel identity is the
 *       channel name; the AUDIO_OUT audio-publish face stays out; non-channel nodes
 *       (CONST/ADD/ENCAP_INPUT/ENCAP_OUTPUT etc.) stay out.</li>
 *   <li>Scope self-containment and read-only-ness: the census carries its own
 *       {@code runInDimension} (nesting-safe, the outer scope restored verbatim); the census
 *       writes nothing into any bus table.</li>
 * </ul>
 */
@DisplayName("HostChannelCensus：图 → 读/写通道普查 / HostChannelCensus: graph → read/write channel census")
class HostChannelCensusTest {

    private static final String DIM_A = "csc_test:dim_a";
    private static final String DIM_B = "csc_test:dim_b";

    /** 便捷夹具：在图上加通道节点并回填。 / Fixture helper: add a channel node and return it. */
    private static GraphNode channelNode(NodeGraph g, NodeType type, String name) {
        var n = g.addNode(type, 0, 0);
        n.signalName = name;
        return n;
    }

    // ══════════ 1. 通道身份 = key() 单一入口（单一源纪律） ══════════

    @Test
    @DisplayName("普查键与同作用域 key() 逐位相等：BUS/PRIVATE 读写各归其位，字面形态 <维度id>\\u0001<逻辑名>")
    void censusKeysAreBitIdenticalToSignalBusKey() {
        var g = new NodeGraph();
        channelNode(g, NodeType.BUS_OUT, "bus1");
        channelNode(g, NodeType.BUS_IN, "bus1");
        channelNode(g, NodeType.PRIVATE_OUT, "sig1");
        channelNode(g, NodeType.PRIVATE_IN, "sig2");

        ChannelCensus c = HostChannelCensus.census(g, DIM_A);

        // 单一源判定的最强形态：期望值由 key() 在同作用域下现场产出。
        // The strongest single-source form: expectations produced live by key() under the same scope.
        String bus1 = SignalBus.runInDimension(DIM_A, () -> SignalBus.key("bus1"));
        String sig1 = SignalBus.runInDimension(DIM_A, () -> SignalBus.key("sig1"));
        String sig2 = SignalBus.runInDimension(DIM_A, () -> SignalBus.key("sig2"));
        assertEquals(Set.of(bus1, sig2), c.reads(), "读集 = BUS_IN(bus1) + PRIVATE_IN(sig2)");
        assertEquals(Set.of(bus1, sig1), c.writes(), "写集 = BUS_OUT(bus1) + PRIVATE_OUT(sig1)");
        assertEquals(DIM_A + '\u0001' + "bus1", bus1, "字面形态：<维度id>\\u0001<逻辑名>");
    }

    @Test
    @DisplayName("维度隔离的边语义：两维度同名通道两份前缀键（通道边天然同维度）；无作用域调用拿原样逻辑名")
    void sameLogicalNameInTwoDimensionsYieldsTwoDistinctKeys() {
        var g = new NodeGraph();
        channelNode(g, NodeType.BUS_OUT, "shared");

        String inA = HostChannelCensus.census(g, DIM_A).writes().iterator().next();
        String inB = HostChannelCensus.census(g, DIM_B).writes().iterator().next();
        assertNotEquals(inA, inB, "两个维度的同名通道得到不同键 → 通道边天然不跨维度（§3.4）");
        assertEquals(DIM_A + '\u0001' + "shared", inA);
        assertEquals(DIM_B + '\u0001' + "shared", inB);

        var unscoped = HostChannelCensus.census(g, null);
        assertEquals(Set.of("shared"), unscoped.writes(), "无作用域（null）= 原样逻辑名（headless 键空间口径）");
    }

    // ══════════ 2. ENCAP 递归（§3.2「必须递归」） ══════════

    @Test
    @DisplayName("ENCAP 子图写入计入写集；同 host 自环形态：主图 BUS_IN 读本 host 子图写的同一通道")
    void encapSubGraphWritesJoinTheCensusAndFormSelfLoops() {
        var main = new NodeGraph();
        channelNode(main, NodeType.BUS_IN, "x");              // 主图读者（自环的读侧）
        var encap = main.addNode(NodeType.ENCAPSULATION, 10, 10);
        var sub = new NodeGraph();
        channelNode(sub, NodeType.BUS_OUT, "x");              // 子图写者（自环的写侧：主图与 ENCAP 子图同通道）
        encap.subGraph = sub;

        ChannelCensus c = HostChannelCensus.census(main, DIM_A);
        String keyX = SignalBus.runInDimension(DIM_A, () -> SignalBus.key("x"));
        assertEquals(Set.of(keyX), c.reads(), "主图 BUS_IN 的读");
        assertEquals(Set.of(keyX), c.writes(), "ENCAP 子图 BUS_OUT 的写——不递归则此处为空，DAG 缺自环边");

        var merged = new java.util.TreeSet<>(c.reads());
        merged.retainAll(c.writes());
        assertEquals(Set.of(keyX), merged, "读/写集交集非空 = host 自环（§3.2 顺带推论的图集形态）");
    }

    @Test
    @DisplayName("ENCAP 套 ENCAP 再递归：三层嵌套的子图通道全部入集")
    void encapNestedInEncapRecursesThroughAllLevels() {
        var level0 = new NodeGraph();
        channelNode(level0, NodeType.PRIVATE_OUT, "l0");      // 主图私有写
        var enc0 = level0.addNode(NodeType.ENCAPSULATION, 0, 0);
        var level1 = new NodeGraph();
        channelNode(level1, NodeType.BUS_OUT, "l1");          // 一层子图 BUS 写
        enc0.subGraph = level1;
        var enc1 = level1.addNode(NodeType.ENCAPSULATION, 5, 5);
        var level2 = new NodeGraph();
        channelNode(level2, NodeType.PRIVATE_IN, "l2");       // 二层子图私有读
        enc1.subGraph = level2;

        ChannelCensus c = HostChannelCensus.census(level0, DIM_A);
        String l0 = SignalBus.runInDimension(DIM_A, () -> SignalBus.key("l0"));
        String l1 = SignalBus.runInDimension(DIM_A, () -> SignalBus.key("l1"));
        String l2 = SignalBus.runInDimension(DIM_A, () -> SignalBus.key("l2"));
        assertEquals(Set.of(l0, l1), c.writes(), "主图 + 一层子图的写（二层递归到位）");
        assertEquals(Set.of(l2), c.reads(), "二层子图的读（ENCAP 套 ENCAP 再递归）");
    }

    @Test
    @DisplayName("嵌套引用环被截断：损坏 NBT 理论形态下枚举必然终止且不重复计数")
    void cyclicSubGraphNestingTerminates() {
        var g1 = new NodeGraph();
        channelNode(g1, NodeType.BUS_OUT, "x");
        var enc1 = g1.addNode(NodeType.ENCAPSULATION, 0, 0);
        var g2 = new NodeGraph();
        var enc2 = g2.addNode(NodeType.ENCAPSULATION, 0, 0);
        enc1.subGraph = g2;
        enc2.subGraph = g1;   // 引用环（真实编辑器不可达，损坏 NBT 理论形态）/ a reference cycle (unreachable via the real editor, a corrupt-NBT shape)

        // 若防御缺失，此调用将以栈溢出/挂死暴露——测试本体即终止性证明。
        // With the guard missing, this call exposes itself as a stack overflow / hang — the
        // call itself is the termination proof.
        ChannelCensus c = HostChannelCensus.census(g1, DIM_A);
        String keyX = SignalBus.runInDimension(DIM_A, () -> SignalBus.key("x"));
        assertEquals(Set.of(keyX), c.writes(), "环被截断后正常通道照常入集");
    }

    // ══════════ 3. 范围边界（诚实记录） ══════════

    @Test
    @DisplayName("空 signalName 不入集（P2-5 幽灵口径）；非通道节点不入集")
    void emptyNamesAndNonChannelNodesStayOut() {
        var g = new NodeGraph();
        channelNode(g, NodeType.BUS_OUT, "");                 // 空名 BUS_OUT
        channelNode(g, NodeType.PRIVATE_IN, "");              // 空名 PRIVATE_IN
        g.addNode(NodeType.CONST, 10, 10);
        g.addNode(NodeType.ADD, 20, 20);
        g.addNode(NodeType.ENCAP_INPUT, 30, 30);              // 子图端口不是通道
        g.addNode(NodeType.ENCAP_OUTPUT, 40, 40);
        channelNode(g, NodeType.BUS_IN, "real");

        ChannelCensus c = HostChannelCensus.census(g, DIM_A);
        assertEquals(Set.of(SignalBus.runInDimension(DIM_A, () -> SignalBus.key("real"))), c.reads());
        assertTrue(c.writes().isEmpty(), "空名写者不入集");
    }

    @Test
    @DisplayName("通道粒度：频段值键（channel\\0band）不入集；AUDIO_OUT 音频发布面不入集")
    void censusIsChannelGrainedAndAudioPublishStaysOut() {
        var g = new NodeGraph();
        var busOut = channelNode(g, NodeType.BUS_OUT, "bus");
        busOut.signalBands = new java.util.ArrayList<>(java.util.List.of("b0", "b1"));  // 频段载体
        channelNode(g, NodeType.AUDIO_OUT, "music");          // 音频发布面（范围表外）
        channelNode(g, NodeType.BUS_IN, "bus");               // BUS 读者（跨 host 音频频段的通道粒度读）

        ChannelCensus c = HostChannelCensus.census(g, DIM_A);
        String busKey = SignalBus.runInDimension(DIM_A, () -> SignalBus.key("bus"));
        assertEquals(Set.of(busKey), c.writes(), "写集只有频道名——频段是同通道的值载体划分（epoch 按通道 bump）");
        assertTrue(c.writes().stream().noneMatch(k -> k.contains("b0") || k.contains("b1")),
            "channel\\0band 值键绝不入集");
        assertTrue(c.writes().stream().noneMatch(k -> k.contains("music")),
            "AUDIO_OUT 不入集（§3.2 范围表只纳 BUS/PRIVATE；音频新鲜度走 AudioBands 游标门控）");
        assertEquals(Set.of(busKey), c.reads(), "BUS_IN 的通道粒度读在集");
    }

    // ══════════ 4. 作用域自含 + 只读性 ══════════

    @Test
    @DisplayName("普查自带作用域且嵌套安全：外层作用域在普查后原样恢复；普查不写总线任何表")
    void censusCarriesItsOwnScopeAndWritesNothing() {
        SignalBus.runInDimension(DIM_A, () -> SignalBus.put("outer", 7f));
        var g = new NodeGraph();
        channelNode(g, NodeType.BUS_OUT, "inner");

        SignalBus.runInDimension(DIM_A, () -> {
            ChannelCensus c = HostChannelCensus.census(g, DIM_B);   // 内层切到 DIM_B
            assertEquals(Set.of(DIM_B + '\u0001' + "inner"), c.writes(), "普查按自己声明的维度前缀");
            assertEquals(7f, SignalBus.get("outer"), 0f, "普查返回后外层作用域原样恢复（嵌套安全）");
        });
        assertEquals(0f, SignalBus.get("outer"), 0f, "外层 runInDimension 结束后回到无作用域键空间");

        // 只读性：普查之后总线读不到任何普查过的名字（key() 只做规范化，不落表）。
        // Read-only: after the census, nothing the census touched is readable (key() only
        // normalizes, never lands a table row).
        assertEquals(0f, SignalBus.get("inner"), 0f, "普查不写总线");
        assertNull(SignalBus.runInDimension(DIM_B, () -> SignalBus.getPrivateOwner("inner")));
    }

    @Test
    @DisplayName("空图与 null 图：返回空普查，不抛")
    void emptyAndNullGraphsYieldEmptyCensus() {
        assertTrue(HostChannelCensus.census(new NodeGraph(), DIM_A).isEmpty());
        assertEquals(ChannelCensus.EMPTY, HostChannelCensus.census(null, DIM_A), "null 图安全收敛为空普查");
    }
}
