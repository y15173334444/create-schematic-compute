package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;

/**
 * PERF-7 测量场景生成器 — 渲染性能方案 §3.5 测试协议的配套工具
 * （docs/graph-editor-render-performance-plan.md）。
 * <p>
 * F10 循环 256 → 512 → 1024 → 0（清空）个节点。节点直接落在**当前打开的客户端图**上
 * （主图或所在子图）；服务端权威图不受任何影响——重开编辑器（重新拉取权威图）即自愈。
 * 仅用于测量会话（虚空世界 / 固定相机 / 关 VSync），不写存档、不发操作包。
 * <p>
 * Perf-scene generator for PERF-7 — the companion tool of the render-performance plan's
 * §3.5 test protocol. F10 cycles 256 → 512 → 1024 → 0 (clear) nodes. Nodes land directly
 * on the **currently open client-side graph** (main or the sub-graph being edited); the
 * server's authoritative graph is untouched — reopening the editor (which re-pulls the
 * authoritative graph) self-heals. Measurement sessions only (void world / fixed camera /
 * VSync off); no saves, no op packets.
 */
final class GraphPerfScene {

    private GraphPerfScene() {}

    /** 循环档位（0 = 清空，作 0 节点基线档）/ cycle sizes (0 = clear, the 0-node baseline). */
    private static final int[] SIZES = {256, 512, 1024, 0};
    private static int sizeIdx = -1;

    /** F10 入口：切下一档并重建场景。 / F10 entry: advance one size and rebuild the scene. */
    static void cycle(GraphEditor editor) {
        sizeIdx = (sizeIdx + 1) % SIZES.length;
        rebuild(editor, SIZES[sizeIdx]);
    }

    /**
     * 重建场景：count 个节点网格排布（ADD / PID 交替——PID 带参数面板，作中重量代表），
     * 链式连线（前一节点输出 0 → 当前输入 0；{@code addConnection} 的占用/自环/载荷域
     * 校验按构造天然满足）。相机对准场景中心；缩放不动——测试协议里 zoom 是实验者
     * 控制的变量。
     * Rebuild: {@code count} nodes on a grid (ADD / PID alternating — PID carries a param
     * panel and stands for the mid-weight representative), chained wires (previous output 0
     * → current input 0; {@code addConnection}'s occupancy / self-loop / payload-domain
     * checks hold by construction). The camera re-centers on the scene; zoom is left alone
     * — the protocol treats zoom as the experimenter's variable.
     */
    private static void rebuild(GraphEditor editor, int count) {
        var graph = editor.getGraph();
        // 经 removeNode 逐个移除而非裸 clear：removeNode 同步清连线与 nodeMap 并失效拓扑
        // 缓存，不留陈旧引用；1024² 的 removeIf 扫描在 F10 一次性按键上是毫秒级。
        // Remove via removeNode instead of a raw clear: removeNode also prunes connections
        // and the node map and invalidates the topo cache — no stale references survive.
        // The O(n²) removeIf sweep is milliseconds on a one-off F10 press.
        for (var n : new java.util.ArrayList<>(graph.nodes)) graph.removeNode(n.id);
        graph.nextNodeId = 1;
        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(count)));
        GraphNode prev = null;
        for (int i = 0; i < count; i++) {
            var node = graph.addNode((i % 2 == 0) ? NodeType.ADD : NodeType.PID,
                (i % cols) * 200f, (i / cols) * 140f);
            if (prev != null) graph.addConnection(prev.id, 0, node.id, 0);
            prev = node;
        }
        // 相机对准场景中心：c2sX = width/2 + (cx + camX)*zoom —— camX = **负的**世界中心
        // 才让场景中心落在屏幕中央（pan 向右 camX 增、内容右移，跟手约定同源）。
        // Recenter the camera on the scene centre: c2sX = width/2 + (cx + camX)*zoom —
        // camX must be the **negated** world centre (same convention as panning: camX grows
        // rightward and content follows the hand).
        editor.camX = -cols * 200f / 2f;
        editor.camY = -(int) Math.ceil(count / (double) cols) * 140f / 2f;
    }
}
