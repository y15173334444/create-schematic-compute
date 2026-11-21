package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.CutEdgePlanner;
import io.github.y15173334444.create_schematic_compute.graph.HostEvalOrder;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.ChannelCensus;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.ChannelEdge;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats.HostId;
import io.github.y15173334444.create_schematic_compute.graph.TopoStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 音频面进全局拓扑序的锚（§9.1-5 平台级收口，2026-11-20 扩面）：音频身份边
 * （{@link HostChannelCensus#audioCensusKey}）进 BE 级依赖图之后——
 * <ul>
 *   <li>AUDIO_OUT→BUS_IN 音频对按拓扑序收敛（发布方在前 = 同刻收敛）；</li>
 *   <li>跨维度音频对凭维度无关身份同样成边、同样有序（AudioBands 全局表口径）；</li>
 *   <li>含音频边的混合环进切边规划（每真环分量恰切一条、全员入序）；</li>
 *   <li>音频自环照自环边优先被切。</li>
 * </ul>
 * 合成普查直测纯核心（{@code TopoStats.buildHostGraph(Map)} / {@link HostEvalOrder} /
 * {@link CutEdgePlanner} 全 headless）。
 *
 * <p>Anchors for the audio plane joining the global topological order (the §9.1-5
 * platform-level closure, the 2026-11-20 widening): with audio-identity edges
 * ({@link HostChannelCensus#audioCensusKey}) inside the BE-level dependency graph —</p>
 * <ul>
 *   <li>AUDIO_OUT→BUS_IN audio pairs converge topologically (publisher first = same-tick
 *       convergence);</li>
 *   <li>cross-dimension audio pairs form and honor edges through the dimension-agnostic
 *       identity (the AudioBands global-table criterion);</li>
 *   <li>mixed cycles containing an audio edge enter the cut planning (exactly one cut per
 *       genuine cyclic component, every member enters the order);</li>
 *   <li>an audio self-loop is cut under the self-loop-first rule.</li>
 * </ul>
 * Synthetic censuses drive the pure core directly (fully headless).</p>
 */
@DisplayName("音频面拓扑序：身份边成图、发布方在前、跨维度成边、混合环切边")
class AudioCensusOrderTest {

    private static final String DIM_A = "csc_test:dim_a";
    private static final String DIM_B = "csc_test:dim_b";

    private static HostId host(String dim, int x) { return new HostId(dim, x, 64, 100); }

    private static TopoStats.HostGraph twoHosts(HostId a, Set<String> aReads, Set<String> aWrites,
                                                HostId b, Set<String> bReads, Set<String> bWrites) {
        var m = new TreeMap<HostId, ChannelCensus>();
        m.put(a, new ChannelCensus(new TreeSet<>(aReads), new TreeSet<>(aWrites)));
        m.put(b, new ChannelCensus(new TreeSet<>(bReads), new TreeSet<>(bWrites)));
        return TopoStats.buildHostGraph(m);
    }

    /** AUDIO_OUT 发布面表键（AudioEval 同款 bandKey(名, 名)）。 */
    private static String musicBand() {
        return HostChannelCensus.audioCensusKey(AudioBands.bandKey("music", "music"));
    }

    @Test
    @DisplayName("音频身份边成图且发布方在前：AUDIO_OUT 宿主先于 BUS_IN 音频读宿主（同刻收敛序）")
    void audioIdentityEdgeOrdersPublisherBeforeReader() {
        String band = musicBand();
        var pub = host(DIM_A, 1);
        var reader = host(DIM_A, 10);
        var g = twoHosts(pub, Set.of(), Set.of(band), reader, Set.of(band), Set.of());

        assertEquals(1, g.edges().size(), "音频身份配对 = 恰一条依赖边");
        ChannelEdge e = g.edges().get(0);
        assertEquals(band, e.channel(), "边标签 = 音频身份键");
        assertEquals(pub, e.writer());
        assertEquals(reader, e.reader());
        assertEquals(List.of(pub, reader), HostEvalOrder.evaluationOrder(g, Set.of()),
            "发布方先于读者——同刻收敛（§5.2 方案 A；纯音频链由此从稳定 1 刻变 0 刻）");
    }

    @Test
    @DisplayName("跨维度音频对凭维度无关身份成边：写者在 dim_a、读者在 dim_b 仍有序")
    void crossDimensionAudioPairsAreOrderedThroughTheAgnosticIdentity() {
        String band = musicBand();
        var pub = host(DIM_A, 1);
        var reader = host(DIM_B, 10);
        var g = twoHosts(pub, Set.of(), Set.of(band), reader, Set.of(band), Set.of());

        assertEquals(1, g.edges().size(), "身份键无维度前缀——跨维度照样成边（通道边做不到的形态）");
        assertEquals(pub, g.edges().get(0).writer());
        assertEquals(reader, g.edges().get(0).reader());
        assertEquals(List.of(pub, reader), HostEvalOrder.evaluationOrder(g, Set.of()),
            "跨维度音频对同样进同刻收敛序");
    }

    @Test
    @DisplayName("含音频边的混合环进切边规划：恰切一条、全员入序")
    void mixedCycleWithAnAudioEdgeGetsExactlyOneCut() {
        String band = musicBand();
        String chan = DIM_A + '\u0001' + "ch";   // 通道粒度键（维度前缀形态）
        var pub = host(DIM_A, 1);      // AUDIO_OUT 写 band、读 chan（浮点）
        var reader = host(DIM_A, 10);  // 读 band、写 chan（浮点）——音频边 + 通道边合环
        var g = twoHosts(pub, Set.of(chan), Set.of(band), reader, Set.of(band), Set.of(chan));

        assertEquals(2, g.edges().size(), "环 = 音频身份边 + 通道边（扩面前音频边缺失，环被误判为无环）");
        var plan = CutEdgePlanner.plan(g);
        assertEquals(1, plan.cutEdges().size(), "每真环分量恰切一条（§5.3）");
        // 全序最小兜底：audio\u0002 按字典序先于维度前缀通道键 → 被切的是音频身份边。
        // The total-order-minimum fallback: audio\u0002 sorts before the dimension-prefixed
        // channel key → the audio identity edge is the cut one.
        assertEquals(band, plan.cutEdges().iterator().next().channel(), "被切边 = 边集全序最小");
        var order = HostEvalOrder.evaluationOrder(g, plan.cutEdges());
        assertEquals(2, order.size(), "§5.6 铁律：环分量成员全部入序");
        // 幸存约束 = 通道边（读者写 ch、发布方读 ch）→ 读者在前、发布方在后；
        // 音频腿转为跨 tick 读（AudioBands 表自带「上一发布」语义 + 新鲜窗，读者侧零新机制）。
        // The surviving constraint = the channel edge (reader writes ch, publisher reads ch)
        // → reader first, publisher second; the audio leg turns cross-tick (the AudioBands
        // table carries its own last-publish semantics + freshness window — zero new
        // machinery on the read side).
        assertEquals(List.of(reader, pub), order, "次序由幸存约束决定");
    }

    @Test
    @DisplayName("音频自环（同宿主发读同频段）成自环边，切边规划按自环边优先截断")
    void audioSelfLoopFormsAndIsCutFirst() {
        String band = musicBand();
        var h = host(DIM_A, 1);
        var g = twoHosts(h, Set.of(band), Set.of(band), host(DIM_A, 20), Set.of(), Set.of());

        assertTrue(g.edges().stream().anyMatch(e -> e.writer().equals(h) && e.reader().equals(h)),
            "发读同频段 = 自环边在图（步骤 6 ring 实测的同款形态，音频面口径）");
        var plan = CutEdgePlanner.plan(g);
        assertEquals(1, plan.cutEdges().size(), "自环边优先被切（§5.3 候选纪律）");
        ChannelEdge cut = plan.cutEdges().iterator().next();
        assertEquals(h, cut.writer(), "被切的是音频自环边");
        assertEquals(h, cut.reader(), "自环 = 写读者同宿主");
    }
}
