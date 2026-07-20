package io.github.y15173334444.create_schematic_compute.graph;

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

/** 完整的节点图：节点 + 连接。 / The complete node graph: nodes + connections. */
public class NodeGraph {
    public final List<GraphNode> nodes = new ArrayList<>();
    public final List<NodeConnection> connections = new ArrayList<>();
    public int nextNodeId = 1;
    public int nextLayerIndex = 1;
    public int nextSortB = 1;

    // 共享视角书签（存入 NBT，多人协作同步）
    // Shared view bookmarks (stored in NBT, synced via multiplayer collaboration)
    public final List<Bookmark> bookmarks = new ArrayList<>();

    /** 视角书签：名称 + 摄像机状态。 / View bookmark: name + camera state. */
    public record Bookmark(String name, float camX, float camY, float zoom) {}

    // 缓存：O(1) 节点查找  /  Cache: O(1) node lookup
    private Map<Integer, GraphNode> nodeMap = new HashMap<>();
    // 缓存：O(1) 输入查询 key = (toId << 16) | toPin  /  Cache: O(1) input query
    private Map<Long, NodeConnection> inputCache = new HashMap<>();
    // 缓存：拓扑排序版本号，连接变化时递增  /  Cache: topological order version, incremented on connection changes
    private int topoVersion = 0;
    private List<Integer> topoOrder = null;

    /** 暴露 nodeMap 供 UI 重映射使用（GraphEditor 中的 ACK 处理器）。
     *  Expose nodeMap for UI remapping (ACK-handler in GraphEditor). */
    public Map<Integer, GraphNode> nodeMap() { return nodeMap; }

    /** 全局图版本号 — 任何影响渲染的变更（结构/参数/位置）时递增。
     *  Phase 2 脏标记框架用此值判断是否需要重新渲染。
     *  Global graph generation — incremented on any change that affects rendering
     *  (structure/params/position). Phase 2 dirty-flag framework uses this to
     *  determine whether re-render is needed. */
    public int graphGeneration = 0;
    public void bumpGeneration() { graphGeneration++; }

    public GraphNode addNode(NodeType type, float x, float y) {
        GraphNode node = new GraphNode(nextNodeId++, type, x, y);
        node.layerIndex = nextLayerIndex++;
        node.sortB = nextSortB++;
        nodes.add(node);
        nodeMap.put(node.id, node);
        invalidateTopo();
        bumpGeneration();
        return node;
    }

    /** Adopt an externally-constructed node. Does NOT touch {@code nextNodeId}. */
    public void adoptNode(GraphNode node) {
        nodes.add(node);
        nodeMap.put(node.id, node);
        invalidateTopo();
        bumpGeneration();
    }

    public void removeNode(int id) {
        nodes.removeIf(n -> n.id == id);
        connections.removeIf(c -> c.fromId == id || c.toId == id);
        nodeMap.remove(id);
        invalidateTopo();
        bumpGeneration();
    }

    public GraphNode findNode(int id) {
        return nodeMap.get(id);  // O(1) 查找  /  O(1) lookup
    }

    /** 重建节点查找映射（在外部修改节点列表后必须调用）。
     *  Rebuild the node lookup map (required after external node list mutation). */
    public void rebuildNodeMap() {
        nodeMap.clear();
        for (var n : nodes) nodeMap.put(n.id, n);
    }

    public boolean addConnection(int fromId, int fromPin, int toId, int toPin) {
        if (inputCache.containsKey(key(toId, toPin))) return false;
        if (fromId == toId) return false;
        connections.add(new NodeConnection(fromId, fromPin, toId, toPin));
        invalidateTopo();
        bumpGeneration();
        return true;
    }

    public void removeConnection(int fromId, int fromPin, int toId, int toPin) {
        connections.removeIf(c -> c.fromId == fromId && c.fromPin == fromPin
                && c.toId == toId && c.toPin == toPin);
        invalidateTopo();
        bumpGeneration();
    }

    /** 获取拓扑排序（缓存，仅在连接变化时重算）。
     *  Get topological order (cached, recomputed only on connection changes). */
    public List<Integer> getTopoOrder() {
        if (topoOrder != null) return topoOrder;
        return computeTopoOrder();
    }

    /** 返回拓扑版本号，供外部判断图是否变化。
     *  Return the topological version number for external callers to detect graph changes. */
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

    /** 重建输入缓存（公开方法，供 ACK 的 ID 重映射使用）。
     *  Rebuild the input cache (public for ACK-based ID remapping). */
    public void rebuildInputCache() {
        inputCache.clear();
        for (NodeConnection c : connections) {
            inputCache.put(key(c.toId, c.toPin), c);
        }
    }

    private static long key(int nodeId, int pinIdx) {
        return ((long) nodeId << 16) | (pinIdx & 0xFFFF);
    }

    /** O(1) 查找输入连接 / O(1) lookup input connection */
    public float getInputValue(int nodeId, int pinIdx, Map<Integer, float[]> outputs) {
        NodeConnection c = inputCache.get(key(nodeId, pinIdx));
        if (c != null) {
            float[] out = outputs.get(c.fromId);
            if (out != null && c.fromPin < out.length) return out[c.fromPin];
        }
        return 0;
    }

    /** O(1) 查找输入值，无连线时返回默认值（一次查找，避免 hasInputConnection + getInputValue 两次查找）。
     *  O(1) lookup input value; returns default when unconnected (single lookup avoids
     *  the double lookup of hasInputConnection + getInputValue). */
    public float getInputValueOrDefault(int nodeId, int pinIdx, Map<Integer, float[]> outputs, float defaultVal) {
        NodeConnection c = inputCache.get(key(nodeId, pinIdx));
        if (c != null) {
            float[] out = outputs.get(c.fromId);
            if (out != null && c.fromPin < out.length) return out[c.fromPin];
        }
        return defaultVal;
    }

    /** O(1) 检查指定输入引脚是否有连线 / O(1) check whether the specified input pin has a connection */
    public boolean hasInputConnection(int nodeId, int pinIdx) {
        return inputCache.containsKey(key(nodeId, pinIdx));
    }

    public boolean hasCycles() {
        return getTopoOrder().size() < nodes.size();
    }

    /** 检查新增 from→to 连接是否会构成环（只读，不修改图）。
     *  从 toId 沿现有连接方向 BFS，若能到达 fromId，则 from→to 会闭环。
     *  Check whether adding a from→to connection would form a cycle (read-only, does not
     *  modify the graph). BFS from toId along existing connections; if fromId is reachable,
     *  then from→to would close a cycle. */
    public boolean wouldCreateCycle(int fromId, int toId) {
        if (fromId == toId) return true;
        Map<Integer, List<Integer>> adj = new HashMap<>();
        for (NodeConnection c : connections)
            adj.computeIfAbsent(c.fromId, k -> new ArrayList<>()).add(c.toId);
        Queue<Integer> q = new ArrayDeque<>();
        java.util.Set<Integer> visited = new java.util.HashSet<>();
        q.add(toId);
        visited.add(toId);
        while (!q.isEmpty()) {
            int cur = q.poll();
            if (cur == fromId) return true;
            for (int nb : adj.getOrDefault(cur, Collections.emptyList()))
                if (visited.add(nb)) q.add(nb);
        }
        return false;
    }

    /** 深拷贝整个图，分配新 ID。递归复制封装节点内的子图。
     *  Deep-copy this entire graph with new IDs. Recursively copies sub-graphs inside encapsulation nodes. */
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
        tag.putInt("nextSortB", nextSortB);
        ListTag nl = new ListTag();
        for (GraphNode n : nodes) nl.add(n.save(registries));
        tag.put("nodes", nl);
        ListTag cl = new ListTag();
        for (NodeConnection c : connections) cl.add(c.save());
        tag.put("conns", cl);
        // 序列化共享视角书签 / serialise shared view bookmarks
        if (!bookmarks.isEmpty()) {
            ListTag bl = new ListTag();
            for (Bookmark b : bookmarks) {
                CompoundTag bt = new CompoundTag();
                bt.putString("name", b.name());
                bt.putFloat("camX", b.camX());
                bt.putFloat("camY", b.camY());
                bt.putFloat("zoom", b.zoom());
                bl.add(bt);
            }
            tag.put("bookmarks", bl);
        }
        return tag;
    }

    /** 从 NBT 加载图，透明地迁移旧格式。
     *  Load graph from NBT, transparently migrating old formats. */
    public static NodeGraph load(CompoundTag rawTag, HolderLookup.Provider registries) {
        CompoundTag tag = GraphMigration.migrate(rawTag);
        return loadCurrent(tag, registries);
    }

    /** 加载已经是当前 DATA_VERSION 的 NBT 标签。
     *  Load a tag that is already at the current DATA_VERSION. */
    private static NodeGraph loadCurrent(CompoundTag tag, HolderLookup.Provider registries) {
        NodeGraph g = new NodeGraph();
        g.nextNodeId = tag.getInt("nextId");
        g.nextSortB = tag.contains("nextSortB") ? tag.getInt("nextSortB") : 1;
        ListTag nl = tag.getList("nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < nl.size(); i++) {
            GraphNode node = GraphNode.load(nl.getCompound(i), registries);
            g.nodes.add(node);
            g.nodeMap.put(node.id, node);
        }
        ListTag cl = tag.getList("conns", Tag.TAG_COMPOUND);
        for (int i = 0; i < cl.size(); i++) {
            NodeConnection c = NodeConnection.load(cl.getCompound(i));
            // 忽略引用不存在的节点的连接  /  Skip connections referencing nonexistent nodes
            if (g.nodeMap.containsKey(c.fromId) && g.nodeMap.containsKey(c.toId))
                g.connections.add(c);
        }
        g.rebuildInputCache();
        // 反序列化共享视角书签 / deserialise shared view bookmarks
        if (tag.contains("bookmarks")) {
            ListTag bl = tag.getList("bookmarks", Tag.TAG_COMPOUND);
            for (int i = 0; i < bl.size(); i++) {
                CompoundTag bt = bl.getCompound(i);
                g.bookmarks.add(new Bookmark(
                    bt.getString("name"),
                    bt.getFloat("camX"),
                    bt.getFloat("camY"),
                    bt.getFloat("zoom")));
            }
        }
        return g;
    }
}
