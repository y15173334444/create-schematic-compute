package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.PinType;
import io.github.y15173334444.create_schematic_compute.graph.Vec3f;
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
 * 进程内总线**维度作用域**的隔离锚（§6.4 步骤 2 / issue #23）。SignalBus 的静态表是进程级
 * 共享的，修复前两个维度的同名通道互相串值（私有信号同理）——修复 = 键空间按维度前缀隔离，
 * 服务端线程在宿主维度作用域内访问，无作用域线程（headless 测试 / 单人档客户端线程）访问
 * 原样逻辑名。这里锁死：
 * <ul>
 *   <li>维度隔离：A 维度写 ch1、B 维度读恒 0；BUS 频道注册表（同名不同 owner 曾必冲突）、
 *       私有占用表、频段定义与向量值表逐维度各持一份。</li>
 *   <li>作用域恢复：嵌套 runInDimension 后回到外层作用域；外层返回后回到无作用域（键访问
 *       全部经 {@code key()} 单一规范化入口）。</li>
 *   <li>追踪逻辑名：{@link BusTrace} 挂点在 put/get 入口/出口收**逻辑名**——前缀在记录点
 *       之后，步骤 0 的行格式锚（按行 grep）不受维度前缀影响。</li>
 *   <li>停机撤私有值（§9.1-2）：真实注册内核种下私有频道后，
 *       {@link BusChannelHelper#clearPrivateSignalValues} 只撤值（标量 + 向量），占用与定义
 *       保留（停机不打开接管窗口）；冲突节点（名被对端持有）不撤对端的值；无主残留一并清。</li>
 * </ul>
 * 全部 headless 直测 SignalBus 与 {@link BusChannelHelper} 的无 Level 内核
 * （SignalBusOwnerGateTest 同款夹具口径），无需 MC 引导。
 *
 * <p>Dimension-**scope** isolation anchors for the process-wide signal bus (§6.4 step 2 /
 * issue #23). SignalBus's static tables are process-shared; before the fix, same-named channels
 * in two dimensions cross-contaminated (private signals alike) — the fix prefixes the keyspace
 * per dimension: server threads ride the host's dimension scope, unscoped threads (headless
 * tests / the single-player client thread) use the logical name unchanged. Pinned here:</p>
 * <ul>
 *   <li>Dimension isolation: dimension A writes ch1, dimension B reads a constant 0; the BUS
 *       channel registry (same name + different owner used to be an unconditional conflict), the
 *       private occupancy table, band definitions and the vector-value table each hold one copy
 *       per dimension.</li>
 *   <li>Scope restoration: nested {@code runInDimension} returns to the outer scope; the outer
 *       return lands back in the unscoped keyspace (every key access goes through the single
 *       {@code key()} normalization entry).</li>
 *   <li>Trace keeps logical names: the {@link BusTrace} hooks at the put/get entry/exit receive
 *       the **logical name** — the prefix is applied after the trace points, so the step-0
 *       line-format anchors (grepped per line) are unaffected by the dimension prefix.</li>
 *   <li>Stop withdraws private values (§9.1-2): with a private channel planted through the real
 *       registration kernel, {@link BusChannelHelper#clearPrivateSignalValues} withdraws only the
 *       values (scalar + vector) while occupancy and definition stay (a stop never opens a
 *       takeover window); a conflicted node (name held by the peer) never withdraws the peer's
 *       value; unowned residue is cleared too.</li>
 * </ul>
 * All headless, testing SignalBus and {@link BusChannelHelper}'s Level-less kernels directly
 * (the SignalBusOwnerGateTest fixture discipline) — no MC bootstrap needed.
 */
@DisplayName("SignalBus 维度作用域 / SignalBus dimension scope")
class SignalBusDimensionScopeTest {

    private static final String DIM_A = "csc_test:dim_a";
    private static final String DIM_B = "csc_test:dim_b";
    private static final BlockPos POS = new BlockPos(1, 2, 3);
    private static final BlockPos FOREIGN = new BlockPos(9, 9, 9);

    private final List<String> traceLines = new ArrayList<>();

    @BeforeEach @AfterEach void resetBus() { SignalBus.clear(); }

    @AfterEach void restoreTrace() {
        BusTrace.configure(System.getProperty("csc.traceChannels", ""));
        BusTrace.sink = BusTrace.LOG_SINK;
    }

    // ══════════ 1. 维度隔离（issue 验收口径：A 写 ch1、B 读恒 0） ══════════

    @Test
    @DisplayName("维度隔离：A 写 ch1、B 读恒 0；无作用域键空间与维度键空间互不可见")
    void dimensionAWriteIsInvisibleToDimensionB() {
        SignalBus.runInDimension(DIM_A, () -> SignalBus.put("ch1", 7f));

        SignalBus.runInDimension(DIM_B, () ->
            assertEquals(0f, SignalBus.get("ch1"), 0f, "B 维度读 A 维度的通道必须恒 0"));
        SignalBus.runInDimension(DIM_A, () ->
            assertEquals(7f, SignalBus.get("ch1"), 0f, "A 维度自己读得到"));
        assertEquals(0f, SignalBus.get("ch1"), 0f, "无作用域（headless/客户端本地）键空间读不到维度键空间");
    }

    @Test
    @DisplayName("BUS 频道注册表逐维度一份：同名不同 owner 不再跨维度冲突，各自 internalMap")
    void busChannelRegistryHoldsOneCopyPerDimension() {
        var mapA = new HashMap<String, Float>();
        var mapB = new HashMap<String, Float>();
        assertTrue(SignalBus.runInDimension(DIM_A, () -> SignalBus.registerChannel("bus", mapA, new ChannelOwner(POS, 1))),
            "A 维度注册成功");
        assertTrue(SignalBus.runInDimension(DIM_B, () -> SignalBus.registerChannel("bus", mapB, new ChannelOwner(FOREIGN, 2))),
            "B 维度同名不同 owner 必须成功（隔离前进程级同表必冲突串值）");

        SignalBus.runInDimension(DIM_A, () ->
            assertSame(mapA, SignalBus.getChannel("bus").internalMap, "A 维度的 BUS_IN 读 A 的 internalMap"));
        SignalBus.runInDimension(DIM_B, () ->
            assertSame(mapB, SignalBus.getChannel("bus").internalMap, "B 维度的 BUS_IN 读 B 的 internalMap"));

        // A 注销不影响 B（引用计数各自记账）
        // A's unregistration leaves B untouched (ref-counts are per-dimension).
        SignalBus.runInDimension(DIM_A, () -> assertTrue(SignalBus.unregisterChannel("bus", new ChannelOwner(POS, 1))));
        assertNull(SignalBus.runInDimension(DIM_A, () -> SignalBus.getChannel("bus")), "A 维度条目已撤");
        SignalBus.runInDimension(DIM_B, () ->
            assertSame(mapB, SignalBus.getChannel("bus").internalMap, "B 维度条目不受 A 注销影响"));
    }

    @Test
    @DisplayName("频段定义与向量值逐维度一份：clearBus 只清本维度的频段值与定义")
    void bandDefinitionsAndVectorValuesArePerDimension() {
        SignalBus.runInDimension(DIM_A, () -> {
            SignalBus.registerBands("bus", List.of("b0", "b1"));
            SignalBus.setBandTypes("bus", java.util.Map.of("b0", PinType.AUDIO));
            SignalBus.put("bus\0b0", 3.5f);
            SignalBus.putVec3("bus\0b0", new Vec3f(1, 2, 3));
        });
        SignalBus.runInDimension(DIM_B, () -> {
            SignalBus.registerBands("bus", List.of("x"));
            SignalBus.put("bus\0x", 1f);
        });

        SignalBus.runInDimension(DIM_B, () -> {
            assertEquals(List.of("x"), SignalBus.getBands("bus"), "B 的定义不受 A 影响");
            assertEquals(1f, SignalBus.get("bus\0x"), 0f);
        });
        SignalBus.runInDimension(DIM_A, () -> {
            assertEquals(List.of("b0", "b1"), SignalBus.getBands("bus"), "A 的定义不受 B 影响");
            assertEquals(PinType.AUDIO, SignalBus.bandType("bus", "b0"), "声明类型逐维度");
            assertEquals(3.5f, SignalBus.get("bus\0b0"), 0f, "频段值逐维度");
            assertEquals(new Vec3f(1, 2, 3), SignalBus.getVec3("bus\0b0"), "频段向量值逐维度");
        });

        SignalBus.runInDimension(DIM_A, () -> SignalBus.clearBus("bus"));
        SignalBus.runInDimension(DIM_A, () -> {
            assertNull(SignalBus.getBands("bus"), "clearBus 清本维度的定义");
            assertEquals(0f, SignalBus.get("bus\0b0"), 0f, "clearBus 清本维度的频段值");
            assertNull(SignalBus.getVec3("bus\0b0"), "clearBus 清本维度的频段向量值");
        });
        SignalBus.runInDimension(DIM_B, () -> {
            assertEquals(List.of("x"), SignalBus.getBands("bus"), "B 的定义不被 A 的 clearBus 波及");
            assertEquals(1f, SignalBus.get("bus\0x"), 0f, "B 的频段值不被 A 的 clearBus 波及");
        });
    }

    @Test
    @DisplayName("私有占用逐维度一份：同名不同 owner 在两维度各自持有、各自撤")
    void privateOccupancyHoldsOneCopyPerDimension() {
        assertTrue(SignalBus.runInDimension(DIM_A, () -> SignalBus.registerPrivateChannel("sig", new ChannelOwner(POS, 1))),
            "A 维度占用成功");
        assertTrue(SignalBus.runInDimension(DIM_B, () -> SignalBus.registerPrivateChannel("sig", new ChannelOwner(FOREIGN, 2))),
            "B 维度同名不同 owner 必须成功（隔离前进程级同表必冲突）");

        SignalBus.runInDimension(DIM_A, () ->
            assertEquals(new ChannelOwner(POS, 1), SignalBus.getPrivateOwner("sig"), "A 的占用者"));
        SignalBus.runInDimension(DIM_B, () ->
            assertEquals(new ChannelOwner(FOREIGN, 2), SignalBus.getPrivateOwner("sig"), "B 的占用者"));

        SignalBus.runInDimension(DIM_A, () ->
            assertTrue(SignalBus.unregisterPrivateChannel("sig", new ChannelOwner(POS, 1)), "A 按 owner 撤"));
        SignalBus.runInDimension(DIM_B, () ->
            assertEquals(new ChannelOwner(FOREIGN, 2), SignalBus.getPrivateOwner("sig"), "B 的占用不被 A 的注销波及"));
    }

    // ══════════ 2. 作用域恢复（嵌套安全 + 回到无作用域） ══════════

    @Test
    @DisplayName("作用域嵌套恢复：内层返回回外层、外层返回回无作用域")
    void nestedScopesRestoreThePreviousKeyspace() {
        SignalBus.runInDimension(DIM_A, () -> {
            SignalBus.put("nest", 7f);
            SignalBus.runInDimension(DIM_B, () ->
                assertEquals(0f, SignalBus.get("nest"), 0f, "嵌套内层读外层的通道恒 0"));
            assertEquals(7f, SignalBus.get("nest"), 0f, "内层返回后恢复外层作用域");
        });
        assertEquals(0f, SignalBus.get("nest"), 0f, "外层返回后回到无作用域键空间");
    }

    // ══════════ 3. 追踪收逻辑名（步骤 0 行格式锚的前缀豁免） ══════════

    @Test
    @DisplayName("维度作用域下 BusTrace 仍收逻辑名：行形状与无作用域一致")
    void traceKeepsLogicalNamesUnderScope() {
        BusTrace.configure("ch1");
        BusTrace.sink = traceLines::add;

        SignalBus.runInDimension(DIM_A, () -> SignalBus.put("ch1", 1.5f));
        SignalBus.runInDimension(DIM_A, () -> assertEquals(1.5f, SignalBus.get("ch1"), 0f, "同维度往返照常"));

        assertEquals(2, traceLines.size(), () -> "put 与 get 各一行：" + traceLines);
        assertTrue(traceLines.get(0).matches("^t=\\d+ seq=\\d+ PUT ch1=1\\.5$"),
            () -> "PUT 行收逻辑名（前缀在记录点之后）：" + traceLines.get(0));
        assertTrue(traceLines.get(1).matches("^t=\\d+ seq=\\d+ GET ch1->1\\.5$"),
            () -> "GET 行收逻辑名（前缀在记录点之后）：" + traceLines.get(1));
    }

    // ══════════ 4. 停机撤私有值（§9.1-2） ══════════

    /** 夹具：真实首 tick 注册内核种下本方块的私有频道（占用在位）。Fixture: the real
     *  first-tick registration kernel plants this block's private channel (occupancy in place). */
    private static NodeGraph graphWithPrivateOut(String name) {
        var graph = new NodeGraph();
        var out = graph.addNode(NodeType.PRIVATE_OUT, 0, 0);
        out.signalName = name;
        BusChannelHelper.registerGraphChannels(graph, POS);
        return graph;
    }

    @Test
    @DisplayName("停机撤值：标量与向量清、占用与定义保留（停机不打开接管窗口）")
    void stopWithdrawsValuesButKeepsOccupancyAndDefinition() {
        var graph = graphWithPrivateOut("sig");
        SignalBus.put("sig", 5f);
        SignalBus.putVec3("sig", new Vec3f(1, 2, 3));
        SignalBus.setPrivateType("sig", PinType.AUDIO);

        BusChannelHelper.clearPrivateSignalValues(graph, POS);

        assertEquals(0f, SignalBus.get("sig"), 0f, "停机即撤值：外部读者从此读 0（§9.1-2 的一致边界）");
        assertNull(SignalBus.getVec3("sig"), "向量值一并撤");
        assertNotNull(SignalBus.getPrivateOwner("sig"), "占用保留：停机宿主并未离去，不打开接管窗口");
        assertEquals(PinType.AUDIO, SignalBus.privateType("sig"), "声明定义保留：重启后原样续写");
        assertTrue(SignalBus.privateTypeStamp("sig") > 0, "定义版本戳保留");
    }

    @Test
    @DisplayName("停机撤值 owner 门控：冲突节点（名被对端持有）不撤对端的值")
    void stopWithdrawalNeverClearsAPeerHeldValue() {
        // 对端先持名并已发布值 / the peer holds the name and has published a value
        assertTrue(SignalBus.registerPrivateChannel("held", new ChannelOwner(FOREIGN, 7)));
        SignalBus.put("held", 9f);

        // 本方块的同名节点注册被拒 → 冲突节点（从未写值）
        // this block's same-name node is refused → a conflicted node (never wrote values)
        var graph = graphWithPrivateOut("held");
        var node = graph.nodes.get(0);
        assertTrue(node.busConflict, "夹具前提：注册被拒、节点标冲突");

        BusChannelHelper.clearPrivateSignalValues(graph, POS);

        assertEquals(9f, SignalBus.get("held"), 0f, "对端的值绝不被冲突节点代理清除");
        assertEquals(new ChannelOwner(FOREIGN, 7), SignalBus.getPrivateOwner("held"), "对端的占用不动");
    }

    @Test
    @DisplayName("停机撤值清无主残留：无占用者的陈旧值一并撤")
    void stopWithdrawalClearsUnownedResidue() {
        SignalBus.put("orphan", 3f); // 无占用者的残留值 / a value with no occupancy

        BusChannelHelper.clearPrivateSignalValues(graphWithPrivateOut("orphan"), POS);

        assertEquals(0f, SignalBus.get("orphan"), 0f, "无主残留值一并撤（clearPrivateValue 的无主放行口径）");
    }

    @Test
    @DisplayName("停机撤值对维度作用域正确：撤的是本维度键空间（tick 体作用域语义）")
    void stopWithdrawalLandsInTheScopedKeyspace() {
        var graph = graphWithPrivateOut("sig");
        SignalBus.runInDimension(DIM_A, () -> SignalBus.put("sig", 5f));
        SignalBus.runInDimension(DIM_B, () -> SignalBus.put("sig", 6f));

        // stopBody 在 tickBody 的宿主维度作用域内执行 —— 测试以同款作用域复现该语义。
        // stopBody runs inside tickBody's host-dimension scope — the test replays that
        // semantics with the same scope.
        SignalBus.runInDimension(DIM_A, () -> BusChannelHelper.clearPrivateSignalValues(graph, POS));

        SignalBus.runInDimension(DIM_A, () -> assertEquals(0f, SignalBus.get("sig"), 0f, "宿主维度的值被撤"));
        SignalBus.runInDimension(DIM_B, () -> assertEquals(6f, SignalBus.get("sig"), 0f, "B 维度的值分毫不动"));
    }
}
