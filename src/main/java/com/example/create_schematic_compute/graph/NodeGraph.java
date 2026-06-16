package com.example.create_schematic_compute.graph;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/** The complete node graph: nodes + connections. */
public class NodeGraph {
    public final List<GraphNode> nodes = new ArrayList<>();
    public final List<NodeConnection> connections = new ArrayList<>();
    public int nextNodeId = 1;

    // 缓存：O(1) 节点查找
    private Map<Integer, GraphNode> nodeMap = new HashMap<>();
    // 缓存：O(1) 输入查询 key = (toId << 16) | toPin
    private Map<Long, NodeConnection> inputCache = new HashMap<>();
    // 缓存：拓扑排序版本号，连接变化时递增
    private int topoVersion = 0;
    private List<Integer> topoOrder = null;

    public GraphNode addNode(NodeType type, float x, float y) {
        GraphNode node = new GraphNode(nextNodeId++, type, x, y);
        nodes.add(node);
        nodeMap.put(node.id, node);
        invalidateTopo();
        return node;
    }

    /** Adopt an externally-constructed node. Does NOT touch {@code nextNodeId}. */
    public void adoptNode(GraphNode node) {
        nodes.add(node);
        nodeMap.put(node.id, node);
    }

    public void removeNode(int id) {
        nodes.removeIf(n -> n.id == id);
        connections.removeIf(c -> c.fromId == id || c.toId == id);
        nodeMap.remove(id);
        invalidateTopo();
    }

    public GraphNode findNode(int id) {
        return nodeMap.get(id);  // O(1) 查找
    }

    public boolean addConnection(int fromId, int fromPin, int toId, int toPin) {
        if (inputCache.containsKey(key(toId, toPin))) return false;
        if (fromId == toId) return false;
        connections.add(new NodeConnection(fromId, fromPin, toId, toPin));
        invalidateTopo();
        return true;
    }

    public void removeConnection(int fromId, int fromPin, int toId, int toPin) {
        connections.removeIf(c -> c.fromId == fromId && c.fromPin == fromPin
                && c.toId == toId && c.toPin == toPin);
        invalidateTopo();
    }

    /** 获取拓扑排序（缓存，仅在连接变化时重算） */
    public List<Integer> getTopoOrder() {
        if (topoOrder != null) return topoOrder;
        return computeTopoOrder();
    }

    /** 返回拓扑版本号，供外部判断图是否变化 */
    public int topoVersion() { return topoVersion; }

    private void invalidateTopo() {
        topoOrder = null;
        topoVersion++;
        rebuildInputCache();
    }

    private List<Integer> computeTopoOrder() {
        if (nodes.isEmpty()) { topoOrder = Collections.emptyList(); return topoOrder; }
        Map<Integer, Integer> inDegree = new HashMap<>();
        Map<Integer, List<Integer>> adj = new HashMap<>();
        for (GraphNode n : nodes) { inDegree.put(n.id, 0); adj.put(n.id, new ArrayList<>()); }
        for (NodeConnection c : connections) {
            adj.get(c.fromId).add(c.toId);
            inDegree.merge(c.toId, 1, Integer::sum);
        }
        Queue<Integer> q = new ArrayDeque<>();
        for (var e : inDegree.entrySet()) if (e.getValue() == 0) q.add(e.getKey());
        List<Integer> sorted = new ArrayList<>(nodes.size());
        while (!q.isEmpty()) {
            int id = q.poll();
            sorted.add(id);
            for (int nb : adj.get(id)) {
                int d = inDegree.merge(nb, -1, Integer::sum);
                if (d == 0) q.add(nb);
            }
        }
        topoOrder = sorted;
        return topoOrder;
    }

    /** 重建输入缓存 */
    private void rebuildInputCache() {
        inputCache.clear();
        for (NodeConnection c : connections) {
            inputCache.put(key(c.toId, c.toPin), c);
        }
    }

    private static long key(int nodeId, int pinIdx) {
        return ((long) nodeId << 16) | (pinIdx & 0xFFFF);
    }

    /** O(1) 查找输入连接 */
    public float getInputValue(int nodeId, int pinIdx, Map<Integer, float[]> outputs) {
        NodeConnection c = inputCache.get(key(nodeId, pinIdx));
        if (c != null) {
            float[] out = outputs.get(c.fromId);
            if (out != null && c.fromPin < out.length) return out[c.fromPin];
        }
        return 0;
    }

    /** O(1) 检查指定输入引脚是否有连线 */
    public boolean hasInputConnection(int nodeId, int pinIdx) {
        return inputCache.containsKey(key(nodeId, pinIdx));
    }

    public boolean hasCycles() {
        return getTopoOrder().size() < nodes.size();
    }

    /** Deep-copy this entire graph with new IDs. Recursively copies sub-graphs inside encapsulation nodes. */
    public NodeGraph copy() {
        NodeGraph g = new NodeGraph();
        java.util.Map<Integer, Integer> idMap = new java.util.HashMap<>();
        for (GraphNode n : nodes) {
            GraphNode dup = n.shallowCopyWithNewId(g.nextNodeId++);
            idMap.put(n.id, dup.id);
            g.nodes.add(dup);
            g.nodeMap.put(dup.id, dup);
        }
        for (NodeConnection c : connections) {
            if (idMap.containsKey(c.fromId) && idMap.containsKey(c.toId))
                g.connections.add(new NodeConnection(idMap.get(c.fromId), c.fromPin, idMap.get(c.toId), c.toPin));
        }
        g.rebuildInputCache();
        return g;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(NbtVersions.VERSION_KEY, NbtVersions.DATA_VERSION);
        tag.putInt("nextId", nextNodeId);
        ListTag nl = new ListTag();
        for (GraphNode n : nodes) nl.add(n.save(registries));
        tag.put("nodes", nl);
        ListTag cl = new ListTag();
        for (NodeConnection c : connections) cl.add(c.save());
        tag.put("conns", cl);
        return tag;
    }

    /** Load graph from NBT, transparently migrating old formats. */
    public static NodeGraph load(CompoundTag rawTag, HolderLookup.Provider registries) {
        CompoundTag tag = GraphMigration.migrate(rawTag);
        return loadCurrent(tag, registries);
    }

    /** Load a tag that is already at the current DATA_VERSION. */
    private static NodeGraph loadCurrent(CompoundTag tag, HolderLookup.Provider registries) {
        NodeGraph g = new NodeGraph();
        g.nextNodeId = tag.getInt("nextId");
        ListTag nl = tag.getList("nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < nl.size(); i++) {
            GraphNode node = GraphNode.load(nl.getCompound(i), registries);
            g.nodes.add(node);
            g.nodeMap.put(node.id, node);
        }
        ListTag cl = tag.getList("conns", Tag.TAG_COMPOUND);
        for (int i = 0; i < cl.size(); i++) {
            NodeConnection c = NodeConnection.load(cl.getCompound(i));
            // 忽略引用不存在的节点的连接
            if (g.nodeMap.containsKey(c.fromId) && g.nodeMap.containsKey(c.toId))
                g.connections.add(c);
        }
        g.rebuildInputCache();
        return g;
    }
}
