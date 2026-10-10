package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.OpExecutor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户症状闭环回归：节点编辑区输入数据 → 关屏提交 → 服务端应用 → **重新打开图编辑器**
 * （= 从服务端权威图全量同步）。任何编辑区输入若没把改动送上服务端，重开后就会
 * 「变成原来的参数」。
 * <p>
 * User-symptom closed loop: type into the node edit panel → commit on close → server
 * applies → <b>reopen the graph editor</b> (= full sync from the server-authoritative
 * graph). Any edit-panel input that fails to upload its change reverts to the original
 * value on reopen — exactly the reported symptom.
 * <p>
 * 回路各段全部走真实代码：{@link NodeEditStateFactory#create} 建控件与提交动作、
 * {@code enterActions}（关屏提交路径 {@code commitPendingEditsForClose} 逐个执行的就是
 * 这些动作）、{@link OpExecutor#apply}（EditSessionRegistry 应用 op 的同一条路），
 * 最后 NodeGraph.save/load 模拟重开时的全量同步。
 * <p>
 * Every stage is real code: {@link NodeEditStateFactory#create} builds the widgets and
 * commit actions, {@code enterActions} (what {@code commitPendingEditsForClose} runs on
 * screen close), {@link OpExecutor#apply} (the same path EditSessionRegistry applies ops
 * through), and finally NodeGraph.save/load plays the reopen full sync.
 */
class EditPanelUploadRoundTripTest {

    // ── 无头 GUI 地基（Minecraft 僵尸实例 + stub FontSet）──────────────────
    //     Headless GUI foundation (zombie Minecraft instance + stub FontSet)

    @BeforeAll
    static void bootstrapHeadlessGui() throws Exception {
        var mcClass = net.minecraft.client.Minecraft.class;
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var mc = unsafe.allocateInstance(mcClass);
        var font = new net.minecraft.client.gui.Font(rl -> stubFontSet(), false);
        // font 是 final 字段，Field.set 拒绝 —— Unsafe.putObject 直写
        unsafe.putObject(mc, unsafe.objectFieldOffset(mcClass.getField("font")), font);
        var instField = mcClass.getDeclaredField("instance");
        instField.setAccessible(true);
        instField.set(null, mc);
    }

    /** 无头 FontSet 桩：字形度量一律 6px，bake 永不调用。 / Headless FontSet stub. */
    private static net.minecraft.client.gui.font.FontSet stubFontSet() {
        return new net.minecraft.client.gui.font.FontSet(null,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("csc_test", "stub")) {
            @Override public com.mojang.blaze3d.font.GlyphInfo getGlyphInfo(int codepoint, boolean bold) {
                return new com.mojang.blaze3d.font.GlyphInfo() {
                    @Override public float getAdvance() { return 6f; }
                    @Override public net.minecraft.client.gui.font.glyphs.BakedGlyph bake(
                        java.util.function.Function<com.mojang.blaze3d.font.SheetGlyphInfo,
                            net.minecraft.client.gui.font.glyphs.BakedGlyph> baker) { return null; }
                };
            }
        };
    }

    // ── 测试夹具 / fixture ────────────────────────────────────────────────

    /** 捕获 sendOp 的 Host 桩。 / Host stub capturing every sendOp. */
    private static final class StubHost implements GraphEditor.Host {
        final NodeGraph graph;
        final List<GraphOp> sent = new ArrayList<>();
        StubHost(NodeGraph graph) { this.graph = graph; }
        @Override public NodeGraph getGraph() { return graph; }
        @Override public void saveGraph() {}
        @Override public void toggleRunning(boolean start) {}
        @Override public boolean isRunning() { return false; }
        @Override public net.minecraft.client.gui.screens.Screen asScreen() { return null; }
        @Override public void sendOp(GraphOp op) { sent.add(op); }
    }

    /**
     * 模拟服务端权威图：与客户端编辑前一致（重开时的全量同步起点）。
     * Simulate the server-authoritative graph: identical to the client before editing
     * (the state a reopen full sync would deliver).
     */
    private static NodeGraph serverCopyOf(NodeGraph clientGraph) {
        return NodeGraph.load(clientGraph.save(null), null);
    }

    /** 把捕获的 op 送上服务端图（EditSessionRegistry.applyOp 的执行段同一条路）。
     *  Deliver captured ops to the server graph (same execution path as applyOp). */
    private static void deliverToServer(List<GraphOp> ops, NodeGraph serverGraph) {
        for (GraphOp op : ops) OpExecutor.apply(serverGraph, op);
    }

    /** 重开图编辑器：服务端图 save→load 全量同步到客户端副本。 / Reopen: full sync via NBT. */
    private static NodeGraph reopenFrom(NodeGraph serverGraph) {
        return NodeGraph.load(serverGraph.save(null), null);
    }

    /** 关屏提交：逐个执行 enterActions（commitPendingEditsForClose 的同一条路）。
     *  Commit on close: run every enterAction (same as commitPendingEditsForClose). */
    private static void commitOnClose(GraphEditor ed) {
        for (var e : new java.util.ArrayList<>(ed.enterActions.entrySet())) {
            e.getValue().run();
        }
    }

    // ── 用例 / cases ─────────────────────────────────────────────────────

    @Test
    @DisplayName("参照组：通用参数框逐键 SET_PARAM 必须到达服务端（重开后保留）")
    void genericParamBoxReachesServer() {
        var host = new StubHost(new NodeGraph());
        var ed = new GraphEditor(host, null);
        var pid = host.graph.addNode(NodeType.PID, 0, 0);
        float original = pid.params[0];
        // 服务端副本必须在编辑前快照（客户端会乐观写本地 node.params）
        // The server copy must be snapshotted BEFORE editing (the client writes node.params optimistically)
        var server = serverCopyOf(host.graph);

        var st = NodeEditStateFactory.create(ed, pid);
        var box = st.fields.get(0); // kp
        box.setValue("2.5");        // 用户敲键 → responder
        commitOnClose(ed);          // 关屏

        deliverToServer(host.sent, server);
        var reopened = reopenFrom(server);
        var node = reopened.findNode(pid.id);
        assertNotNull(node);
        assertEquals(2.5f, node.params[0], 1e-4f,
            "参照组失败 = 反馈回路本身坏了，先修回路再谈别的");
        assertNotEquals(original, node.params[0]);
    }

    @Test
    @DisplayName("IMAGE 节点编辑区 moveX/moveY/rotScl 输入必须上传（重开后保留）")
    void imageParamsReachServerAfterReopen() {
        var host = new StubHost(new NodeGraph());
        var ed = new GraphEditor(host, null);
        var img = host.graph.addNode(NodeType.IMAGE, 0, 0);
        var server = serverCopyOf(host.graph); // 编辑前的服务端权威图 / pre-edit server state

        var st = NodeEditStateFactory.create(ed, img);
        assertEquals(3, st.fields.size(), "IMAGE 编辑区应有 moveX/moveY/rotScl 三个输入框");
        st.fields.get(0).setValue("0.5");  // 用户输入 moveX
        st.fields.get(1).setValue("0.25"); // 用户输入 moveY
        st.fields.get(2).setValue("2.0");  // 用户输入 rotScl
        commitOnClose(ed);                 // 关屏提交（commitPendingEditsForClose 同路）

        // 用户症状断言：编辑 → 重开 → 参数不得回到原值
        // User-symptom assertion: edit → reopen → params must NOT revert
        deliverToServer(host.sent, server);
        var reopened = reopenFrom(server);
        var node = reopened.findNode(img.id);
        assertNotNull(node);
        assertEquals(0.5f, node.params[0], 1e-4f, "moveX 重开后被回滚（上传失败）");
        assertEquals(0.25f, node.params[1], 1e-4f, "moveY 重开后被回滚（上传失败）");
        assertEquals(2.0f, node.params[2], 1e-4f, "rotScl 重开后被回滚（上传失败）");
    }

    @Test
    @DisplayName("新节点 ACK 未回时输入的参数必须在 ID 重映射后仍到达服务端")
    void preAckEditsSurviveIdRemap() {
        // 客户端本地分配 tempId=1；服务端 nextNodeId 已漂移（多人/删建历史）到 7，
        // ADD_NODE_REQUEST 会分到 rid=7。ACK 之前输入的 SET_PARAM 带着 tempId=1 出门。
        // Client allocates tempId=1; the server's nextNodeId has drifted to 7, so
        // ADD_NODE_REQUEST lands id=7. Edits typed before the ACK carry tempId=1.
        var clientGraph = new NodeGraph();
        var host = new StubHost(clientGraph);
        var ed = new GraphEditor(host, null);
        var node = clientGraph.addNode(NodeType.CONST, 0, 0);
        assertEquals(1, node.id, "夹具前提：客户端首节点拿到 tempId=1");

        var server = new NodeGraph();
        server.nextNodeId = 7; // 服务端计数器漂移

        // 菜单加点的真实出口（ADD_NODE_REQUEST 经 sendOp 出门，登记 tempId）
        // The real menu-add outlet: ADD_NODE_REQUEST leaves via sendOp (registers the tempId)
        var addOp = GraphOp.addNodeRequest(host.getBlockPos(), -1, node.id,
            NodeType.CONST, 0f, 0f, java.util.UUID.randomUUID());
        ed.sendOp(addOp);
        host.sent.clear(); // addOp 已单独在服务端应用 / the add itself is applied separately

        // 用户在 ACK 回来之前就展开并输入数值
        // The user expands and types before the ACK comes back
        var st = NodeEditStateFactory.create(ed, node);
        st.fields.get(0).setValue("42");
        commitOnClose(ed);

        // 服务端处理 ADD_NODE_REQUEST（分到 7）；ACK 回到客户端 → 重映射 + 补发挂起 op
        // The server processes ADD_NODE_REQUEST (allocates 7); the ACK returns → the client
        // remaps and flushes the deferred ops (GraphEditor.remapNodeId = the real ACK path)
        OpExecutor.apply(server, addOp);
        int assignedId = server.nodes.get(0).id;
        assertNotEquals(node.id, assignedId, "夹具前提：服务端分到的 ID 与 tempId 不同");
        int tempId = node.id;
        ed.remapNodeId(new io.github.y15173334444.create_schematic_compute.network.GraphEditAckPacket(
            net.minecraft.core.BlockPos.ZERO, tempId, assignedId, 2L));

        deliverToServer(host.sent, server);

        // 用户症状断言：重开后编辑的值必须在服务端图上
        // User-symptom assertion: the edited value must be on the server graph after reopen
        var reopened = reopenFrom(server);
        var serverNode = reopened.findNode(assignedId);
        assertNotNull(serverNode);
        assertEquals(42f, serverNode.params[0], 1e-4f,
            "ACK 前输入的参数在重开后被回滚（temp id 被服务端静默丢弃）");
    }

    @Test
    @DisplayName("GAMEPAD_BUTTON 绑定必须上传（SET_KEY_BINDING，重开后保留）")
    void gamepadBindingReachesServer() {
        var host = new StubHost(new NodeGraph());
        var ed = new GraphEditor(host, null);
        var node = host.graph.addNode(NodeType.GAMEPAD_BUTTON, 0, 0);
        var server = serverCopyOf(host.graph);

        ed.commitKeyBinding(node, 5); // 手柄按键捕获的真实提交路径 / the real gamepad capture commit

        deliverToServer(host.sent, server);
        var reopened = reopenFrom(server);
        var serverNode = reopened.findNode(node.id);
        assertNotNull(serverNode);
        assertEquals(5f, serverNode.params[0], 1e-4f,
            "手柄绑定在重开后被回滚（绑定上传失败）");
    }

    @Test
    @DisplayName("穷举全部 NodeType：每个带通用参数框的节点都要上传（PID 等，重开后保留）")
    void everyGenericParamBoxNodeUploads() {
        // 通用参数框 = NodeEditStateFactory 主循环按参数下标绑出的输入框（fieldParamIndices
        // >= 0 且非 BUS 频段框）。穷举 NodeType 驱动：新节点类型自动纳入覆盖，不再靠手工清单。
        // Generic param boxes = the boxes the factory's main loop binds to param indices
        // (fieldParamIndices >= 0, outside the BUS band boxes). Driven for every NodeType:
        // new node types are covered automatically, no hand-maintained list.
        //
        // REDSTONE_IN/OUT 例外：GraphNode 构造器为频率槽触碰 ItemStack.EMPTY（<clinit> 需
        // 注册表引导，纯 JUnit 跑不动）；两者 paramNames 为空、没有通用参数框，跳过无覆盖损失。
        // REDSTONE_IN/OUT exception: the GraphNode ctor touches ItemStack.EMPTY for the
        // frequency slots (registry bootstrap unavailable in plain JUnit); both have empty
        // paramNames and no generic param boxes, so skipping loses no coverage.
        int covered = 0;
        for (NodeType t : NodeType.values()) {
            if (t == NodeType.REDSTONE_IN || t == NodeType.REDSTONE_OUT) continue;
            var host = new StubHost(new NodeGraph());
            var ed = new GraphEditor(host, null);
            var node = host.graph.addNode(t, 0, 0);
            var st = NodeEditStateFactory.create(ed, node);
            if (st.busNode != null) continue; // BUS 频段框走另一条提交链路（防抖 + BusBandUploadPacket）
            var boxes = new java.util.ArrayList<int[]>(); // {fieldIdx, paramIdx}
            for (int i = 0; i < st.fieldParamIndices.size(); i++) {
                int pi = st.fieldParamIndices.get(i);
                if (pi >= 0) boxes.add(new int[]{i, pi});
            }
            if (boxes.isEmpty()) continue;
            covered++;
            var server = serverCopyOf(host.graph); // 编辑前的服务端权威图 / pre-edit server state
            for (int[] b : boxes) {
                float typed = 3.5f * (b[1] + 1) + 0.375f; // 与任何默认值错开 / distinct from any default
                assertTrue(Float.compare(node.params[b[1]], typed) != 0,
                    t + " 夹具前提：输入值须不同于默认值");
                st.fields.get(b[0]).setValue(String.valueOf(typed));
            }
            commitOnClose(ed);

            deliverToServer(host.sent, server);
            var reopened = reopenFrom(server);
            var serverNode = reopened.findNode(node.id);
            assertNotNull(serverNode, t + " 节点重开后丢失");
            for (int[] b : boxes)
                assertEquals(3.5f * (b[1] + 1) + 0.375f, serverNode.params[b[1]], 0f,
                    t + " 参数 " + b[1] + " 重开后被回滚（上传失败）");
        }
        // 防"扫到 0 个类型"的假绿：当前已知带通用参数框的类型有 20 种（CONST/PID/PID_POWER/
        // CLAMP/MAP/DELAY/PULSE_EXTEND/LOOP/FUSE/ROUND/DIRECTION/POSITION/ACCUMULATOR/
        // INTEGRATOR/MOVE/ROTATE/WAIT/CLUTCH/DEBUG_PROBE/AMP）。
        // Guard against a vacuous green: 20 known types carry generic param boxes today.
        assertTrue(covered >= 20, "至少 20 种节点带通用参数框，本次实扫 " + covered + " 种");
    }

    @Test
    @DisplayName("PID 小数值参数（ki=0.0001）不得在失焦归位时被回滚")
    void tinyPidParamSurvivesBlurAndReopen() {
        // 丢值机制：0.0001 不满足「变化量 > 0.0001」阈值 → lastSentParam 不更新 →
        // 失焦归位用旧值覆盖服务端，输入框显示也被 ff3 抹成 0.0。PID 的 ki/kd 就是典型小参数。
        // Loss mechanism: 0.0001 fails the "> 0.0001 changed" epsilon → lastSentParam never
        // updates → the blur normalization overwrites the server with the stale value and ff3
        // erases the display to 0.0. PID ki/kd are the typical tiny params.
        var host = new StubHost(new NodeGraph());
        var ed = new GraphEditor(host, null);
        var pid = host.graph.addNode(NodeType.PID, 0, 0);
        var server = serverCopyOf(host.graph);

        var st = NodeEditStateFactory.create(ed, pid);
        // 逐字输入（真实键入路径）：中间的 "0" 先把已发送账本更新为 0，
        // 最后一步 "0.0001" 相对账本的增量恰为 0.0001 —— 正是被阈值吞掉的形态。
        // Progressive typing (the real keystroke path): the intermediate "0" moves the
        // last-sent ledger to 0, and the final "0.0001" is exactly a 0.0001 delta from it —
        // the shape the epsilon swallows.
        for (String step : new String[]{"0", "0.", "0.0", "0.00", "0.000", "0.0001"})
            st.fields.get(1).setValue(step); // ki
        commitOnClose(ed);

        deliverToServer(host.sent, server);
        var reopened = reopenFrom(server);
        var serverNode = reopened.findNode(pid.id);
        assertNotNull(serverNode);
        assertEquals(0.0001f, serverNode.params[1], 0f,
            "ki=0.0001 在提交/重开后被回滚（小数值参数上传失败）");
        // 显示保真：重开后输入框必须显示 0.0001，不得是 0.0
        // Display fidelity: the reopened box must show 0.0001, not 0.0
        var st2 = NodeEditStateFactory.create(ed, serverNode);
        assertEquals("0.0001", st2.fields.get(1).getValue(),
            "重开后输入框显示被 ff3 三位小数抹掉（看着像丢了）");
    }

    @Test
    @DisplayName("DEBUG_SIGNAL_GEN 小数值 speed 不得丢（编辑区输入框）")
    void tinyDebugSignalGenSpeedSurvives() {
        var host = new StubHost(new NodeGraph());
        var ed = new GraphEditor(host, null);
        var node = host.graph.addNode(NodeType.DEBUG_SIGNAL_GEN, 0, 0); // 默认 SET_MANUAL + OUT_FREQ
        var server = serverCopyOf(host.graph);

        var st = NodeEditStateFactory.create(ed, node);
        assertFalse(st.fields.isEmpty(), "手动+频率模式应有 speed/amplitude 输入框");
        for (String step : new String[]{"0", "0.", "0.0", "0.00", "0.000", "0.0001"})
            st.fields.get(0).setValue(step); // speed (params[2])，逐字输入
        commitOnClose(ed);

        deliverToServer(host.sent, server);
        var reopened = reopenFrom(server);
        var serverNode = reopened.findNode(node.id);
        assertNotNull(serverNode);
        assertEquals(0.0001f, serverNode.params[2], 0f,
            "speed=0.0001 未上传（阈值把小数值变化整个吞掉）");
    }
}
