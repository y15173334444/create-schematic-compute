package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.graph.TopoStats.ChannelCensus;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.ChannelEdge;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.CensusProvider;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.HostGraph;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.HostId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨 BE 观测设施结构核心的锚（§6.4 步骤 3 / issue #25）：全局 host 注册表、BE 级依赖图
 * （通道边 + host 自环）、Tarjan SCC。全部纯 JDK 合成数据直测（结构核心零 MC 依赖——
 * {@code C2SPacketRateGuard} 的「判定与执行分离」同款：枚举胶水在 network 侧另测）。
 * 这里锁死：
 * <ul>
 *   <li>注册表生命周期：登记/摘除幂等；同身份键重登替换供给器（区块卸载 → 重载不产生
 *       幽灵条目也不丢条目——步骤 0 实测过重载翻转，注册表对重载必须稳健）；摘除从未
 *       登记的键安全；注册表<b>跨维度平铺</b>——两维度宿主同表共存，不按维度分桶（§3.4）。</li>
 *   <li>DAG 构造：跨 host 链（A 写 → B 读 → B 写 → C 读）、同 host 自环（读自己写的通道
 *       ——主图与 ENCAP 子图同通道的 §3.2 推论）、一写多读扇出、同 host 对多通道多边、
 *       通道标签随边保留；边全序确定（重建全同）。</li>
 *   <li>宿主范围口径（§3.2）的数据结构支撑：空普查（停机宿主既不读也不写）不进节点集——
 *       分母纪律在构图处一次性落实；经注册表路径（活供给器改报空普查）同样生效。</li>
 *   <li>SCC 判环：无环链 → 零真环分量、全图分区全是平凡单例；两节点互写 → 一个 {A,B}
 *       环分量；自环单例 → 大小 1 的真环分量（绝不能被平凡单例吞掉）；环 + 尾巴 → 尾巴
 *       不进环分量；两个不相交环 → 两个分量；输出全序确定。</li>
 *   <li>判定/执行分离的接缝：注册表存供给器、构图时才普查——重登新供给器后 DAG 即时
 *       反映（零缓存失效面）。</li>
 * </ul>
 *
 * <p>Anchors for the observation facility's structural core (§6.4 step 3 / issue #25): the
 * global host registry, the BE-level dependency graph (channel edges + host self-loops) and
 * Tarjan SCC. Everything is tested headless on pure-JDK synthetic data (the core carries no
 * MC dependency — the same decision/execution split as {@code C2SPacketRateGuard}; the
 * enumeration glue is tested separately on the network side). Pinned here:</p>
 * <ul>
 *   <li>Registry lifecycle: idempotent add/remove; re-registering under the same identity key
 *       replaces the provider (chunk unload → reload produces neither a ghost entry nor a lost
 *       one — step 0 measured reload flipping, the registry must be reload-robust); removing a
 *       never-registered key is safe; the registry is <b>flat across dimensions</b> — hosts of
 *       two dimensions coexist in one table, never bucketed per dimension (§3.4).</li>
 *   <li>DAG construction: a cross-host chain (A writes → B reads → B writes → C reads), a
 *       same-host self-loop (reading a channel the host writes — the §3.2 corollary of the
 *       main graph and an ENCAP sub-graph sharing a channel), one-writer-many-readers fan-out,
 *       multiple channel edges between one host pair, channel labels riding edges; the edge
 *       order is total (a rebuild replays identically).</li>
 *   <li>Data-structure support for the §3.2 host-scope criterion: an empty census (a stopped
 *       host neither reads nor writes) never enters the node set — the denominator discipline
 *       applied once at graph construction; the registry path (a live provider switching to an
 *       empty census) behaves the same.</li>
 *   <li>SCC cycle judgement: an acyclic chain → zero cyclic components, the full partition is
 *       all trivial singletons; two hosts writing to each other → one {A,B} cyclic component;
 *       a self-loop singleton → a genuine size-1 cyclic component (never swallowed as trivial);
 *       a cycle with a tail → the tail stays out of the cycle component; two disjoint cycles →
 *       two components; the output order is total.</li>
 *   <li>The decision/execution seam: the registry stores providers and censuses only at graph
 *       build — after re-registering a fresh provider the DAG reflects it immediately (zero
 *       invalidation surface).</li>
 * </ul>
 */
@DisplayName("TopoStats：全局注册表 + BE 级依赖图 + SCC / TopoStats: global registry + BE-level DAG + SCC")
class TopoStatsTest {

    private static final HostId A = new HostId("csc_test:dim_a", 1, 2, 3);
    private static final HostId B = new HostId("csc_test:dim_a", 10, 2, 3);
    private static final HostId C = new HostId("csc_test:dim_a", 20, 2, 3);
    private static final HostId B_DIM2 = new HostId("csc_test:dim_b", 10, 2, 3);

    @BeforeEach @AfterEach void resetRegistry() { TopoStats.clearRegistry(); }

    /** 合成供给器：固定普查。 / A synthetic provider: a fixed census. */
    private static CensusProvider of(ChannelCensus census) { return () -> census; }

    private static ChannelCensus census(String... channels) {
        // 单通道便捷形态：读 = 写 = 给定通道（自环宿主）。/ The one-channel convenience
        // shape: reads = writes = the given channels (a self-looping host).
        return new ChannelCensus(Set.of(channels), Set.of(channels));
    }

    private static ChannelCensus writeRead(Set<String> writes, Set<String> reads) {
        return new ChannelCensus(reads, writes);
    }

    // ══════════ 1. 注册表生命周期（增删幂等 + 重载稳健 + 跨维度平铺） ══════════

    @Test
    @DisplayName("登记/摘除幂等：重复登记替换、重复摘除安全、摘除未登记键返回 false")
    void registryAddRemoveIsIdempotent() {
        TopoStats.hostLoaded(A, of(ChannelCensus.EMPTY));
        assertEquals(1, TopoStats.hostCount(), "首次登记");
        TopoStats.hostLoaded(A, of(census("ch1")));
        assertEquals(1, TopoStats.hostCount(), "同键重登不产生第二条目（重载稳健性）");

        assertTrue(TopoStats.hostGone(A), "摘除已登记键");
        assertFalse(TopoStats.hostGone(A), "重复摘除安全 no-op");
        assertFalse(TopoStats.hostGone(B), "摘除从未登记的键安全（幽灵防线）");
        assertEquals(0, TopoStats.hostCount());
    }

    @Test
    @DisplayName("跨维度平铺：两维度宿主同坐标同表共存，摘除互不影响（不按维度分桶）")
    void registryIsFlatAcrossDimensions() {
        TopoStats.hostLoaded(A, of(census("ch1")));
        TopoStats.hostLoaded(B_DIM2, of(census("ch1")));   // 同坐标、不同维度 → 不同身份键
        assertEquals(2, TopoStats.hostCount(), "注册表跨维度列宿主（§3.4），不是每维度一份");

        assertTrue(TopoStats.hostGone(B_DIM2));
        assertEquals(1, TopoStats.hostCount(), "A 维度条目不受 B 维度摘除影响");
        assertTrue(TopoStats.hostsSnapshot().containsKey(A), "A 维度条目仍在");
    }

    @Test
    @DisplayName("快照防御性：快照后摘除不撕裂已取快照；构图先快照后普查")
    void snapshotIsDefensive() {
        TopoStats.hostLoaded(A, of(census("ch1")));
        var snapshot = TopoStats.hostsSnapshot();
        TopoStats.hostGone(A);
        assertEquals(1, snapshot.size(), "已取快照不受后续摘除影响");
        assertTrue(TopoStats.hostsSnapshot().isEmpty(), "真实注册表已摘除");
    }

    // ══════════ 2. 注册表 → DAG 全链路（活供给器 + 范围口径） ══════════

    @Test
    @DisplayName("注册表全链路：供给器普查 → 跨 host 边 + host 自环进图；重登新供给器 DAG 即时反映")
    void registryToGraphPipelineReflectsLiveProviders() {
        // A 写 ch1；B 读 ch1 且读自己写的 ch2（自环）；C 无通道（空普查）。
        // A writes ch1; B reads ch1 and reads its own ch2 (self-loop); C touches no channel.
        TopoStats.hostLoaded(A, of(writeRead(Set.of("ch1"), Set.of())));
        TopoStats.hostLoaded(B, of(writeRead(Set.of("ch2"), Set.of("ch1", "ch2"))));
        TopoStats.hostLoaded(C, of(ChannelCensus.EMPTY));

        HostGraph g = TopoStats.buildHostGraph();
        assertEquals(Set.of(A, B), g.hosts(), "空普查宿主 C 不进节点集（§3.2 分母纪律）");
        assertEquals(2, g.edges().size(), "边 = A→B(ch1 跨 host) + B→B(ch2 自环)，恰两条");
        assertTrue(g.edges().contains(new ChannelEdge("ch1", A, B)), "跨 host 链边 A→B");
        assertTrue(g.edges().contains(new ChannelEdge("ch2", B, B)), "host 自环边 B→B（§3.2：主图与 ENCAP 子图同通道）");
        assertTrue(g.hasSelfLoop(B), "自环判定");
        assertFalse(g.hasSelfLoop(A));

        // 重登 A 为停机宿主（空普查）→ A 立即出图（零缓存失效面）。
        // Re-register A as a stopped host (empty census) → A leaves the graph at once.
        TopoStats.hostLoaded(A, of(ChannelCensus.EMPTY));
        HostGraph g2 = TopoStats.buildHostGraph();
        assertEquals(Set.of(B), g2.hosts(), "重登空普查后 A 出图");
        assertTrue(g2.edges().stream().noneMatch(e -> e.writer().equals(A) || e.reader().equals(A)),
            "A 的边一并消失");
        assertEquals(List.of(new ChannelEdge("ch2", B, B)), g2.edges(), "只剩 B 的自环边");
    }

    @Test
    @DisplayName("宿主离场 → 摘除 → 重建图无幽灵：离场宿主及其边一并消失，重载重登即回归")
    void hostGoneLeavesNoGhostInRebuiltGraph() {
        TopoStats.hostLoaded(A, of(writeRead(Set.of("ch1"), Set.of())));
        TopoStats.hostLoaded(B, of(writeRead(Set.of(), Set.of("ch1"))));
        assertEquals(1, TopoStats.buildHostGraph().edges().size());

        TopoStats.hostGone(A);   // 区块卸载 / setRemoved 路径
        HostGraph g = TopoStats.buildHostGraph();
        assertTrue(g.edges().isEmpty(), "写者离场 → 悬空读者无边（幽灵防线）");
        assertEquals(Set.of(B), g.hosts(), "读者仍在场（无写者的悬空读 = 无边节点）");

        TopoStats.hostLoaded(A, of(writeRead(Set.of("ch1"), Set.of())));   // 重载重登
        assertEquals(1, TopoStats.buildHostGraph().edges().size(), "重载后边回归（不丢条目）");
    }

    // ══════════ 3. DAG 构造（纯核心：合成普查集） ══════════

    @Test
    @DisplayName("跨 host 链：A→B→C 两跳，边带通道标签、方向为写者→读者")
    void crossHostChainBuildsDirectedLabelledEdges() {
        var g = TopoStats.buildHostGraph(Map.of(
            A, writeRead(Set.of("c1"), Set.of()),
            B, writeRead(Set.of("c2"), Set.of("c1")),
            C, writeRead(Set.of(), Set.of("c2"))));

        assertEquals(Set.of(A, B, C), g.hosts());
        assertEquals(List.of(new ChannelEdge("c1", A, B), new ChannelEdge("c2", B, C)),
            g.edges(), "边全序 = 通道字典序，方向 = 写者→读者（读者依赖写者）");
        assertEquals(Set.of(B), g.downstreamOf(A), "A 的下游是读者 B");
        assertEquals(Set.of(C), g.downstreamOf(B));
        assertEquals(Set.of(), g.downstreamOf(C), "尾节点无下游");
        assertFalse(TopoStats.hasCycle(g), "链无环");
    }

    @Test
    @DisplayName("一写多读扇出：两个读者两条边；同 host 对多通道两条边、标签各自保留")
    void fanOutAndMultiChannelPairs() {
        var g = TopoStats.buildHostGraph(Map.of(
            A, writeRead(Set.of("c1", "c2"), Set.of()),
            B, writeRead(Set.of(), Set.of("c1", "c2"))));

        assertEquals(List.of(
                new ChannelEdge("c1", A, B),
                new ChannelEdge("c2", A, B)),
            g.edges(), "同 host 对两通道两条边，通道标签各自保留");
        assertEquals(Set.of(B), g.downstreamOf(A), "邻接按 host 去重");

        var fan = TopoStats.buildHostGraph(Map.of(
            A, writeRead(Set.of("c1"), Set.of()),
            B, writeRead(Set.of(), Set.of("c1")),
            C, writeRead(Set.of(), Set.of("c1"))));
        assertEquals(List.of(
                new ChannelEdge("c1", A, B),
                new ChannelEdge("c1", A, C)),
            fan.edges(), "一写多读扇出两条边");
    }

    @Test
    @DisplayName("自环宿主：读自己写的通道 → 自环边进图、hasSelfLoop 判真")
    void selfLoopEdgeEntersTheGraph() {
        var g = TopoStats.buildHostGraph(Map.of(A, census("c1")));
        assertEquals(List.of(new ChannelEdge("c1", A, A)), g.edges(), "自环边 A→A");
        assertTrue(g.hasSelfLoop(A));
        assertTrue(TopoStats.hasCycle(g), "自环是真环（大小 1 的环分量），不是可忽略的平凡单例");
    }

    @Test
    @DisplayName("空普查/纯悬空读：停机宿主不进节点集；无写者的悬空读只留节点不留边")
    void emptyCensusHostsStayOutOfTheNodeSet() {
        var g = TopoStats.buildHostGraph(Map.of(
            A, ChannelCensus.EMPTY,
            B, writeRead(Set.of(), Set.of("c1"))));   // 悬空读：无写者

        assertEquals(Set.of(B), g.hosts(), "空普查宿主 A 不进节点集（§3.2 停机口径）");
        assertTrue(g.edges().isEmpty(), "悬空读无边");
        assertFalse(g.isEmpty(), "有节点即非空图");
    }

    @Test
    @DisplayName("边全序确定：同一图集任意次重建边序全同；同通道多写者如实保留多边")
    void edgeOrderIsTotalAndMultiWriterChannelsKeepAllEdges() {
        var census = new TreeMap<HostId, ChannelCensus>();
        census.put(A, writeRead(Set.of("c1"), Set.of()));
        census.put(B, writeRead(Set.of("c1"), Set.of("c1")));   // B 既写又读 c1（与 A 竞争写者 + 自环）
        census.put(C, writeRead(Set.of(), Set.of("c1")));

        var g1 = TopoStats.buildHostGraph(census);
        var g2 = TopoStats.buildHostGraph(census);
        assertEquals(g1.edges(), g2.edges(), "重建全同（可复现输出）");
        assertEquals(List.of(
                new ChannelEdge("c1", A, B),
                new ChannelEdge("c1", A, C),
                new ChannelEdge("c1", B, B),
                new ChannelEdge("c1", B, C)),
            g1.edges(), "同通道两写者（占用竞争形态）→ 四条边如实保留，按 (通道,写者,读者) 全序");
    }

    // ══════════ 4. SCC 判环 ══════════

    @Test
    @DisplayName("无环链：零真环分量，全图分区全是平凡单例（分区覆盖全部节点）")
    void acyclicChainYieldsOnlyTrivialComponents() {
        var g = TopoStats.buildHostGraph(Map.of(
            A, writeRead(Set.of("c1"), Set.of()),
            B, writeRead(Set.of("c2"), Set.of("c1")),
            C, writeRead(Set.of(), Set.of("c2"))));

        var sccs = TopoStats.stronglyConnectedComponents(g);
        assertEquals(3, sccs.size(), "全图分区 = 三平凡单例");
        sccs.forEach(scc -> assertEquals(1, scc.size()));
        assertTrue(TopoStats.cyclicComponents(g).isEmpty());
        assertFalse(TopoStats.hasCycle(g));
        // 分区覆盖校验：三单例并集 = 全部节点。
        var union = new java.util.HashSet<HostId>();
        sccs.forEach(union::addAll);
        assertEquals(g.hosts(), union, "SCC 是全图分区，不重不漏");
    }

    @Test
    @DisplayName("两节点互写：一个 {A,B} 环分量；环外尾巴不进分量")
    void twoHostMutualWriteFormsOneComponentAndTailsStayOut() {
        var g = TopoStats.buildHostGraph(Map.of(
            A, writeRead(Set.of("c1"), Set.of("c2")),       // A 写 c1 读 c2
            B, writeRead(Set.of("c2"), Set.of("c1")),       // B 写 c2 读 c1 → A↔B 环
            C, writeRead(Set.of(), Set.of("c1"))));         // C 只读环内产物（尾巴）

        var cyclic = TopoStats.cyclicComponents(g);
        assertEquals(1, cyclic.size());
        assertEquals(Set.of(A, B), cyclic.get(0), "环分量恰含 A、B，不含尾巴 C");
        assertFalse(g.hasSelfLoop(A), "跨 host 环，非自环");
        assertTrue(TopoStats.hasCycle(g));
    }

    @Test
    @DisplayName("自环单例：大小 1 且带自环 → 真环分量（绝不能被平凡单例吞掉）")
    void selfLoopSingletonIsAGenuineCycleComponent() {
        var g = TopoStats.buildHostGraph(Map.of(
            A, census("c1"),                                // 自环宿主
            B, writeRead(Set.of(), Set.of("c9"))));         // 无关悬空读者

        var cyclic = TopoStats.cyclicComponents(g);
        assertEquals(1, cyclic.size());
        assertEquals(Set.of(A), cyclic.get(0), "大小 1 的真环分量（§3.2 host 自环）");
        assertTrue(TopoStats.hasCycle(g));
    }

    @Test
    @DisplayName("两个不相交环：两个分量、按最小成员排序、输出确定")
    void twoDisjointCyclesYieldTwoOrderedComponents() {
        var g = TopoStats.buildHostGraph(Map.of(
            A, writeRead(Set.of("c1"), Set.of("c2")),
            B, writeRead(Set.of("c2"), Set.of("c1")),        // 环 1：A↔B
            C, writeRead(Set.of("c3"), Set.of("c4")),
            new HostId("csc_test:dim_a", 30, 2, 3), writeRead(Set.of("c4"), Set.of("c3")))); // 环 2：C↔D

        var cyclic = TopoStats.cyclicComponents(g);
        assertEquals(2, cyclic.size(), "两个不相交环");
        assertEquals(List.of(Set.of(A, B), Set.of(C, new HostId("csc_test:dim_a", 30, 2, 3))),
            cyclic, "按最小成员排序的确定输出");
    }

    @Test
    @DisplayName("空图与全空普查：构图得空图，SCC 为空、无环")
    void emptyInputsProduceEmptyGraphWithNoCycles() {
        assertTrue(TopoStats.buildHostGraph(Map.of()).isEmpty());
        assertTrue(TopoStats.buildHostGraph(Map.of(A, ChannelCensus.EMPTY)).isEmpty(),
            "全空普查 → 空图（§3.2 分母）");
        var g = TopoStats.buildHostGraph();
        assertTrue(g.isEmpty(), "空注册表 → 空图（生产入口在空注册表下安全）");
        assertTrue(TopoStats.stronglyConnectedComponents(g).isEmpty());
        assertFalse(TopoStats.hasCycle(g));
    }
}
