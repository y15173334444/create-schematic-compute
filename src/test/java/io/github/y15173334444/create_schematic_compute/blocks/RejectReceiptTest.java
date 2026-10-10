package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.OpExecutor;
import io.github.y15173334444.create_schematic_compute.graph.OpType;
import io.github.y15173334444.create_schematic_compute.network.GraphEditAckPacket;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REJECT 回执契约回归（评审 Standards-1 / Spec-2b）：回执必须携带**被拒 op 的类型**与
 * tempId，客户端按类型回滚本地乐观态 —— 此前一律按 ADD_CONN 处理（对非连线被拒 op 既
 * 识别不了类型、也不清挂起），且 ADD_NODE_REQUEST 被拒后挂起队列不清、后续编辑挂到关屏。
 * <p>
 * REJECT receipt contract (review Standards-1 / Spec-2b): a receipt must carry the refused
 * op's type and tempId so the client can roll back per type — everything used to be handled
 * as ADD_CONN (non-wire rejections were unidentifiable and left the pending queue intact),
 * and a rejected ADD_NODE_REQUEST left its edits hanging until the editor closed.
 */
class RejectReceiptTest {

    @BeforeAll
    static void bootstrapZombieMinecraft() throws Exception {
        // REJECT 归还计数要读 Minecraft.getInstance().level —— 僵尸实例 level=null 走守卫分支
        // The counter release reads Minecraft.getInstance().level — a zombie instance (level
        // = null) takes the guard's skip branch
        var mcClass = net.minecraft.client.Minecraft.class;
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var instField = mcClass.getDeclaredField("instance");
        instField.setAccessible(true);
        instField.set(null, unsafe.allocateInstance(mcClass));
    }

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

    @Test
    @DisplayName("回执携带被拒 op 的类型（paramIndex）与 tempId")
    void receiptCarriesRefusedTypeAndTempId() {
        var uid = java.util.UUID.randomUUID();
        var addOp = GraphOp.addNodeRequest(BlockPos.ZERO, -1, 3, NodeType.CONST, 0f, 0f, uid);
        var rej = GraphOp.reject(addOp, uid);
        assertEquals(OpType.ADD_NODE_REQUEST.ordinal(), rej.paramIndex(),
            "被拒类型必须经 paramIndex 回显");
        assertEquals(3, rej.tempId(), "ADD_NODE_REQUEST 的临时 id 必须回显");

        var connOp = GraphOp.addConn(BlockPos.ZERO, -1, 1, 0, 2, 0, uid);
        var rejConn = GraphOp.reject(connOp, uid);
        assertEquals(OpType.ADD_CONN.ordinal(), rejConn.paramIndex());
        assertEquals(1, rejConn.fromId());
        assertEquals(2, rejConn.toId());
    }

    @Test
    @DisplayName("被拒的加点清挂起队列：后续编辑直发、挂起 op 不复活")
    void rejectedAddNodeRequestClearsPendingQueue() {
        var host = new StubHost(new NodeGraph());
        var ed = new GraphEditor(host, null);
        var node = host.graph.addNode(NodeType.CONST, 0, 0); // tempId = 1
        var uid = host.getPlayerUUID();
        var addOp = GraphOp.addNodeRequest(host.getBlockPos(), -1, node.id,
            NodeType.CONST, 0f, 0f, uid);

        ed.sendOp(addOp); // 登记 tempId / registers the tempId
        var edit = GraphOp.setParam(host.getBlockPos(), -1, node.id, 0, 42f, uid);
        ed.sendOp(edit);  // 引用未 ACK 临时 id → 挂起 / deferred
        assertEquals(1, host.sent.size(), "挂起守卫应扣下引用临时 id 的编辑");

        ed.onRemoteOp(GraphOp.reject(addOp, uid)); // 服务端拒了加点 / the server refused the add

        var edit2 = GraphOp.setParam(host.getBlockPos(), -1, node.id, 0, 43f, uid);
        ed.sendOp(edit2); // 拒后不再挂起 / no longer deferred after the rejection
        assertEquals(2, host.sent.size(), "被拒后的新编辑必须直发");
        assertFalse(host.sent.contains(edit), "被拒节点的挂起 op 不得补发");

        // 迟到 ACK 不得复活挂起 op（等同修复前的静默丢失）
        // A late ACK must not resurrect the dropped op (that would be the old silent loss)
        ed.remapNodeId(new GraphEditAckPacket(BlockPos.ZERO, node.id, 7, 2L));
        assertEquals(2, host.sent.size(), "迟到 ACK 不得补发已随拒绝丢弃的挂起 op");
    }

    @Test
    @DisplayName("被拒的连线按类型回滚本地连线（旧回执无类型时回落连线语义）")
    void rejectedAddConnRollsBackLocalWire() {
        var host = new StubHost(new NodeGraph());
        var ed = new GraphEditor(host, null);
        var a = host.graph.addNode(NodeType.CONST, 0, 0);
        var b = host.graph.addNode(NodeType.ADD, 50, 0);
        host.graph.addConnection(a.id, 0, b.id, 0);
        assertFalse(host.graph.connections.isEmpty(), "夹具前提：本地已乐观连线");

        var connOp = GraphOp.addConn(host.getBlockPos(), -1, a.id, 0, b.id, 0, host.getPlayerUUID());
        ed.onRemoteOp(GraphOp.reject(connOp, host.getPlayerUUID()));

        assertTrue(host.graph.connections.isEmpty(), "被拒连线须从本地回滚");
    }
}
