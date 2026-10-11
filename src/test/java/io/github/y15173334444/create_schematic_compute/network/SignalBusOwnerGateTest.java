package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-9 回归：客户端编辑器对 SignalBus 的裸写口收编进 owner 门控。
 * <p>单人/局域网下客户端编辑器与集成服务端共享同一 JVM 静态表 —— 客户端删一个名叫
 * "alpha" 的 BUS_OUT（或改名一个 BUS_IN）时裸调 {@code clearBus} 会把**另一方块**持有的
 * "alpha" 频道定义、值与音频标志抹掉：对方宿主随后的收敛因对方尚存的 CHANNELS 条目而绕过
 * 「缺席不是定义」守卫，按「空定义」收敛并**永久剪掉**其 BUS_IN 的连线。门控语义与
 * {@link SignalBus#releaseChannel} 的「确证离开 → 退役」统一规则同款：</p>
 * <ul>
 *   <li>{@code releaseChannel} —— 发布方离开：本 owner 才整释；他人持有不动；无主清残留。</li>
 *   <li>{@code clearBusIfUnowned} —— 订阅方（BUS_IN）改名：只有无主（确证已死）才清。</li>
 *   <li>{@code registerBandsIfNotForeign} —— 频段定义的乐观本地写：他人持有的名绝不覆盖。</li>
 * </ul>
 * <p>P1-9 regression: the client editor's raw SignalBus writes now go through owner gates.
 * In single-player/LAN the client editor shares the integrated server's static tables in one
 * JVM — a client deleting a BUS_OUT named "alpha" (or renaming a BUS_IN off it) used to call
 * {@code clearBus} directly, wiping the definition, values and audio flags of "alpha" held by
 * a <b>different block</b>: the peer host's convergence then bypassed the "absence is not a
 * definition" guard (its own CHANNELS entry was still present), converged to an "empty
 * definition" and <b>permanently pruned</b> its BUS_IN wires. The gates mirror
 * {@link SignalBus#releaseChannel}'s unified "provable departure → retire" rule:</p>
 * <ul>
 *   <li>{@code releaseChannel} — a publisher leaves: full release for the real owner only;
 *       a foreign holder is untouched; unowned residue is cleaned.</li>
 *   <li>{@code clearBusIfUnowned} — a subscriber's (BUS_IN) rename: clean only when unowned
 *       (provably dead).</li>
 *   <li>{@code registerBandsIfNotForeign} — the optimistic local band-definition write never
 *       overwrites a foreign-held name.</li>
 * </ul>
 *
 * <p>Headless 直测 SignalBus 门控语义（BusOutRenameChannelTest 同款夹具：真实的首 tick
 * 注册内核 {@link BusChannelHelper#registerGraphChannels} + 真实收敛
 * {@link BusChannelHelper#convergeBusInBands}），无需 MC 引导。</p>
 */
class SignalBusOwnerGateTest {

    /** 删除/改名发起方的坐标（客户端编辑器所在方块）。/ The deleting/renaming editor's block. */
    private static final BlockPos POS = new BlockPos(1, 2, 3);
    /** 另一方块（真正的频道持有方）。/ The peer block (the real channel holder). */
    private static final BlockPos FOREIGN = new BlockPos(9, 9, 9);

    @BeforeEach @AfterEach void resetBus() { SignalBus.clear(); }

    /** 夹具：另一方块的 BUS_OUT "alpha" 走真实首 tick 注册（持频道 + 频段定义 + 已发布值 +
     *  音频标志）。Fixture: a peer block's BUS_OUT "alpha" via the real first-tick registration
     *  core (holding the channel, the band definition, a published value and the audio flags). */
    private static void foreignBlockOwnsAlpha() {
        var pubGraph = new NodeGraph();
        var pub = pubGraph.addNode(NodeType.BUS_OUT, 0, 0);
        pub.signalName = "alpha";
        pub.signalBands = new ArrayList<>(List.of("b0", "b1"));
        pub.busInternalMap = new HashMap<>();
        BusChannelHelper.registerGraphChannels(pubGraph, FOREIGN);
        SignalBus.put("alpha\0b0", 3.5f); // 发布方已写出的频段值 / a published band value
        SignalBus.setAudioBands("alpha", java.util.Set.of("b0"));
    }

    // ══════════ 1. 删除 BUS_OUT 裸写口的门控（releaseChannel） ══════════

    @Test
    @DisplayName("P1-9 关键场景：删 BUS_OUT 撞上他人持有的同名频道 —— 对方定义/值/条目/标志分毫不动")
    void deletingBusOutKeepsForeignOwnersChannel() {
        foreignBlockOwnsAlpha();

        // 客户端删除本方块从未注册成功的同名 BUS_OUT（owner = POS#7）：旧代码此处是裸
        // clearBus —— 对方定义被抹、第三方订阅方被剪线。/ The client deletes its own same-name
        // BUS_OUT (owner POS#7, never registered): the old code raw-cleared here — wiping the
        // peer's definition and pruning third-party subscribers.
        boolean released = SignalBus.releaseChannel("alpha", new ChannelOwner(POS, 7));

        assertFalse(released, "他人持有的频道不得被本端整释");
        assertNotNull(SignalBus.getChannel("alpha"), "对方的频道条目必须保留");
        assertEquals(List.of("b0", "b1"), SignalBus.getBands("alpha"), "对方的频段定义不得被抹");
        assertEquals(3.5f, SignalBus.get("alpha\0b0"), 0f, "对方已发布的频段值不得被清");
        assertEquals(java.util.Set.of("b0"), SignalBus.getAudioBands("alpha"), "对方的音频标志不得被清");
        assertTrue(SignalBus.audioStamp("alpha") > 0, "对方的标志版本号不得被清");
    }

    @Test
    @DisplayName("删 BUS_OUT 撞上他人持有：第三方订阅方的连线不因收敛被剪（端到端）")
    void foreignOwnerSurvivalKeepsSubscriberWires() {
        foreignBlockOwnsAlpha();
        // 第三方块：纯订阅方（图内无发布方），BUS_IN 频段来自权威广播。/ A third block: a pure
        // subscriber (no publisher in-graph), its BUS_IN bands come from the authoritative push.
        var subGraph = new NodeGraph();
        var sub = subGraph.addNode(NodeType.BUS_IN, 0, 0);
        sub.signalName = "alpha";
        sub.signalBands = new ArrayList<>(List.of("b0", "b1"));

        SignalBus.releaseChannel("alpha", new ChannelOwner(POS, 7)); // 客户端删除同名词
        BusChannelHelper.convergeBusInBands(subGraph);               // 对方宿主每 tick 收敛

        assertEquals(List.of("b0", "b1"), sub.signalBands,
            "定义幸存时订阅方不得被收敛成空（旧裸写会把 BAND_REGISTRY 抹成缺席再借对方条目 defeating 缺席守卫 → 空定义 → 剪线）");
    }

    @Test
    @DisplayName("删 BUS_OUT 且本 owner 持有：整释（撤条目 + 清值 + 退役定义）")
    void deletingOwnedBusOutReleasesFully() {
        var graph = new NodeGraph();
        var out = graph.addNode(NodeType.BUS_OUT, 0, 0);
        out.signalName = "alpha";
        out.signalBands = new ArrayList<>(List.of("b0"));
        out.busInternalMap = new HashMap<>();
        BusChannelHelper.registerGraphChannels(graph, POS);
        SignalBus.put("alpha\0b0", 1f);

        assertTrue(SignalBus.releaseChannel("alpha", new ChannelOwner(POS, out.id)),
            "本 owner 的删除必须整释");
        assertNull(SignalBus.getChannel("alpha"), "条目应随整释撤销");
        assertEquals(List.of(), SignalBus.getBands("alpha"), "定义应退役为「已定义为空」（死名收敛清图）");
        assertEquals(0f, SignalBus.get("alpha\0b0"), 0f, "残留值应被清");
    }

    @Test
    @DisplayName("删 BUS_OUT 但名字无主：只清死名残留（值清 + 定义退役），不断不炸")
    void deletingUnownedNameRetiresResidue() {
        SignalBus.put("alpha\0b0", 2f);
        SignalBus.registerBands("alpha", List.of("b0")); // 无条目的孤儿定义 / an orphaned definition

        assertFalse(SignalBus.releaseChannel("alpha", new ChannelOwner(POS, 7)),
            "无条目时无「整释」可言");
        assertNull(SignalBus.getChannel("alpha"));
        assertEquals(List.of(), SignalBus.getBands("alpha"), "死名残留应退役为空（非缺席，供收敛清图）");
        assertEquals(0f, SignalBus.get("alpha\0b0"), 0f, "死名残留值应清");
    }

    // ══════════ 2. BUS_IN 改名裸写口的门控（clearBusIfUnowned） ══════════

    @Test
    @DisplayName("BUS_IN 改名走人：他人持有的名一律不清（定义/值/标志全保）")
    void busInRenameNeverTouchesForeignOwnedName() {
        foreignBlockOwnsAlpha();

        assertFalse(SignalBus.clearBusIfUnowned("alpha"), "存活 owner 在场必须拒绝清理");
        assertEquals(List.of("b0", "b1"), SignalBus.getBands("alpha"), "定义不动");
        assertEquals(3.5f, SignalBus.get("alpha\0b0"), 0f, "值不动");
        assertEquals(java.util.Set.of("b0"), SignalBus.getAudioBands("alpha"), "音频标志不动");
    }

    @Test
    @DisplayName("BUS_IN 改名走人：无主死名才清（值清 + 定义退役为空）")
    void busInRenameCleansOnlyProvablyDeadName() {
        SignalBus.put("alpha\0b0", 2f);
        SignalBus.registerBands("alpha", List.of("b0"));

        assertTrue(SignalBus.clearBusIfUnowned("alpha"), "无条目 = 确证已死，允许清残留");
        assertEquals(List.of(), SignalBus.getBands("alpha"), "退役为「已定义为空」（对照缺席守卫）");
        assertEquals(0f, SignalBus.get("alpha\0b0"), 0f);
    }

    @Test
    @DisplayName("无主死名清理后：指向死名的 BUS_IN 按死名口径收敛清图（issue #11）")
    void deadNameConvergenceStillReachesSubscribers() {
        var subGraph = new NodeGraph();
        var sub = subGraph.addNode(NodeType.BUS_IN, 0, 0);
        sub.signalName = "alpha";
        sub.signalBands = new ArrayList<>(List.of("b0", "b1")); // 死名上的旧图 / stale list on a dead name

        assertTrue(SignalBus.clearBusIfUnowned("alpha"), "夹具前提：名字无主");
        BusChannelHelper.convergeBusInBands(subGraph);

        assertEquals(List.of(), sub.signalBands,
            "死名清理（retireBands 的已定义为空）必须驱动订阅方清旧图 —— 与 releaseChannel 的 Case-C 同口径");
    }

    // ══════════ 3. 频段定义乐观本地写的门控（registerBandsIfNotForeign） ══════════

    @Test
    @DisplayName("他人持有的名：乐观本地写拒绝，权威定义幸存")
    void optimisticBandWriteRefusesForeignOwner() {
        foreignBlockOwnsAlpha();

        assertFalse(SignalBus.registerBandsIfNotForeign("alpha", List.of("x", "y"),
                new ChannelOwner(POS, 7)),
            "他人持有的名不得被本地定义覆盖");
        assertEquals(List.of("b0", "b1"), SignalBus.getBands("alpha"), "权威定义必须幸存");
    }

    @Test
    @DisplayName("无主或本 owner 持有的名：乐观本地写照常放行")
    void optimisticBandWriteAllowsUnownedAndSelfOwned() {
        // 无主：编辑器的乐观本地值（服务端广播稍后收敛） / unowned: the editor's optimistic
        assertTrue(SignalBus.registerBandsIfNotForeign("alpha", List.of("x"),
            new ChannelOwner(POS, 7)));
        assertEquals(List.of("x"), SignalBus.getBands("alpha"));

        // 本 owner 持有：正常更新 / self-owned: normal update
        assertTrue(SignalBus.registerChannel("beta", new HashMap<>(), new ChannelOwner(POS, 7)));
        assertTrue(SignalBus.registerBandsIfNotForeign("beta", List.of("y"),
            new ChannelOwner(POS, 7)), "本 owner 的写入必须放行");
        assertEquals(List.of("y"), SignalBus.getBands("beta"));
    }
}
