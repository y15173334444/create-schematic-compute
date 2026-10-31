package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.api.CustomNodeType;
import io.github.y15173334444.create_schematic_compute.graph.CustomNodeRegistry;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.OpExecutor;
import io.github.y15173334444.create_schematic_compute.graph.OpType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SET_CUSTOM_TEXT 服务端文本帽的行为锚（批 D，登记册条目 17②）：包层 per-control 帽
 * 走 K15 预检（REJECT 语义），执行器 per-node 聚合帽比照批 B 状态 blob「32KB/节点」
 * 纪律（替换记账、fail-closed 不写入不 bump）。headless 可测——TEXT schema 无 ITEM
 * （ItemStack 禁区不触）。
 * Behaviour anchors for the SET_CUSTOM_TEXT server-side text cap (batch D, registry
 * entry 17②): the packet layer's per-control cap rides the K15 pre-check (REJECT
 * semantics), the executor's per-node aggregate cap mirrors the batch-B state-blob
 * "32KB per node" discipline (replace-accounting, fail-closed — no write, no bump).
 * Headless-safe — the TEXT schema carries no ITEM (the ItemStack forbidden zone stays
 * untouched).
 */
class CustomNodeTextCapTest {

    private static final BlockPos POS = BlockPos.ZERO;
    private static final UUID ACTOR = UUID.nameUUIDFromBytes("text-cap".getBytes());
    private static final String MODID = "capmod";

    private static CustomNodeType twoTextSpec() {
        return new CustomNodeType(MODID + ":notes", "N notes",
            List.of(), List.of(),
            List.of(
                new CustomNodeType.CustomParam("a", CustomNodeType.CustomParam.ParamKind.TEXT),
                new CustomNodeType.CustomParam("b", CustomNodeType.CustomParam.ParamKind.TEXT)));
    }

    private static GraphOp textOp(int nodeId, int paramIndex, String text) {
        return new GraphOp(OpType.SET_CUSTOM_TEXT, POS, -1, nodeId,
            0, null, 0f, 0f, 0, 0, 0, 0, paramIndex, 0f, text,
            0, 0, 0, 0, null, 0, 0, 0, null, 0L, ACTOR);
    }

    /** 包层预检：per-control 帽边界（32768 过 / 32769 拒）；常量图钉。
     *  Packet pre-check: the per-control cap boundary (32768 passes / 32769 refuses);
     *  the constants are pinned. */
    @Test
    @DisplayName("包层：per-control 帽 32768 过 / 32769 拒；常量钉 32768")
    void packetPerControlCap() throws Exception {
        assertEquals(32768, CustomNodeType.MAX_TEXT_PARAM_LENGTH);
        assertEquals(32768, OpExecutor.MAX_TEXT_TOTAL_PER_NODE);

        var legal = textOp(1, 0, "x".repeat(32768));
        assertNull(GraphEditOpPacket.opBoundsViolation(legal), "at the cap = legal");
        var over = textOp(1, 0, "x".repeat(32769));
        assertEquals("textParamLength=32769", GraphEditOpPacket.opBoundsViolation(over),
            "one past the cap = refused with REJECT semantics (no silent wire truncation)");
        assertNull(GraphEditOpPacket.opBoundsViolation(textOp(1, 0, null)), "absent text = not this cap's business");
    }

    /** 执行器聚合帽：替换记账（现有总量 − 旧文本 + 新文本），超帽拒收不写入。
     *  The executor aggregate cap: replace-accounting (existing total − old + new);
     *  over the cap the write never lands. */
    @Test
    @DisplayName("执行器：聚合帽 32KB/节点，替换记账，超帽不写入不 bump")
    void executorAggregateCap() {
        CustomNodeRegistry.register(twoTextSpec());
        NodeGraph g = new NodeGraph();
        // findNode 走 nodeMap（addNode 填充）——手工 nodes.add 会让执行器找不到节点
        // （首跑实证）。经 addNode 建点并取实际 id。
        // findNode rides nodeMap (populated by addNode) — a raw nodes.add leaves the
        // executor unable to see the node (first-run lesson). Build via addNode and take
        // the real id.
        GraphNode n = g.addNode(NodeType.CUSTOM, MODID + ":notes", 0f, 0f);
        int nid = n.id;
        int gen0 = g.graphGeneration;

        // ctrl a ← 20000 字符：落（20000 ≤ 32768）
        OpExecutor.apply(g, textOp(nid, 0, "a".repeat(20000)));
        assertEquals(20000, n.customTextParams.get("a").length(), "first write lands");
        assertTrue(g.graphGeneration != gen0, "a landed write bumps the generation");

        // ctrl b ← 15000：20000 + 15000 = 35000 > 32768 → 拒（b 不存在，a 原样）
        OpExecutor.apply(g, textOp(nid, 1, "b".repeat(15000)));
        assertFalse(n.customTextParams.containsKey("b"), "aggregate over the cap never lands");
        assertEquals(20000, n.customTextParams.get("a").length(), "the refused op leaves the other control untouched");

        // ctrl a ← 10000（替换）：20000−20000+10000 = 10000 → 落（单字符 × 10000）
        OpExecutor.apply(g, textOp(nid, 0, "a".repeat(10000)));
        assertEquals(10000, n.customTextParams.get("a").length(), "replace-accounting frees room");

        // ctrl b ← 22000：10000 + 22000 = 32000 ≤ 32768 → 落（恰在帽内）
        OpExecutor.apply(g, textOp(nid, 1, "b".repeat(22000)));
        assertEquals(22000, n.customTextParams.get("b").length(), "exactly at the aggregate cap lands");

        // ctrl a ← 10768：32000−10000+10768 = 32768 → 恰在帽上，落；再 +1 → 拒。
        // ctrl a ← 10768: 32000−10000+10768 = 32768 — exactly at the cap, lands; +1 refuses.
        OpExecutor.apply(g, textOp(nid, 0, "a".repeat(10768)));
        assertEquals(10768, n.customTextParams.get("a").length(), "exactly at the aggregate cap lands");
        OpExecutor.apply(g, textOp(nid, 0, "a".repeat(10769)));
        assertEquals(10768, n.customTextParams.get("a").length(), "one past the aggregate cap is refused");
    }

    /** 包层 REJECT 语义与节流 WARN 的接线源锚（行为在服务端线程，headless 造不出
     *  完整 handler；预检纯函数已直接行为锚）。/ Source anchor for the REJECT wiring
     *  and the throttled WARN (the behaviour runs on the server thread; headless cannot
     *  build the full handler — the pure pre-check function is anchored directly). */
    @Test
    @DisplayName("接线源锚：预检进 opBoundsViolation，节流 WARN 与 REJECT 通路在")
    void wiringThrottledWarnAndReject() throws Exception {
        String src = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/network/GraphEditOpPacket.java"));
        assertTrue(src.contains("case SET_CUSTOM_TEXT -> {"), "the pre-check carries the TEXT cap case");
        assertTrue(src.contains("textParamLength="), "the violation description is machine-greppable");
        assertTrue(src.contains("lastTextCapWarnMs") && src.contains("TEXT_CAP_WARN_INTERVAL_MS"),
            "the throttled WARN state exists (logging-only, no behavioural reset owed)");
        assertTrue(src.contains("GraphOp.reject(pkt.op, sp.getUUID())"),
            "over-cap ops ride the existing REJECT path (client rolls back)");
    }
}
