package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/** 完整的节点图：节点 + 连接。 / The complete node graph: nodes + connections. */
public class NodeGraph {
    public final List<GraphNode> nodes = new ArrayList<>();
    public final List<NodeConnection> connections = new ArrayList<>();
    public int nextNodeId = 1;
    public int nextLayerIndex = 1;
    public int nextSortB = 1;

    /** 本图的玩家可见名称（编辑器顶栏可编辑，便携终端按此查找；空 = 回退到方块类型名）。
     *  随图序列化（save/load），走既有 op 同步链路 —— 不需要专门的同步包。
     *  Player-visible name of this graph (editable in the editor top bar, used by the
     *  portable terminal for lookup; empty = fall back to the block type name).
     *  Serialized with the graph (save/load) and synced through the existing op
     *  pipeline — no dedicated sync packet needed. */
    public String customName = "";

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

    /** 存储序中首个指定类型的节点 id；无则 -1。
     *  变速器用它挑「谁在驱动目标」（首个 TX_OUT），并让**代理态判定与目标取值同源**
     *  —— 两条路径各写一遍遍历就会漂移成「盒里写(代理)、实际听滚轮」。
     *  Id of the first node of the given type in storage order, or -1 when absent. The
     *  transmission uses it to pick which node drives the target (first TX_OUT) and to keep the
     *  proxy flag and the target value derived from the SAME decision — two hand-rolled scans
     *  would drift into "the box says proxied, the wheel still wins". */
    public int firstNodeIdOfType(NodeType type) {
        for (var n : nodes) {
            if (n.type == type) return n.id;
        }
        return -1;
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
        // Resolve pinIds from nodes
        GraphNode fromNode = nodeMap.get(fromId);
        GraphNode toNode = nodeMap.get(toId);
        if (fromNode != null && toNode != null && !pinDomainsMatch(fromNode, fromPin, toNode, toPin)) return false;
        String fPid = fromNode != null ? fromNode.outputPinId(fromPin) : null;
        String tPid = toNode != null ? toNode.inputPinId(toPin) : null;
        connections.add(new NodeConnection(fromId, fPid, fromPin, toId, tPid, toPin));
        invalidateTopo();
        bumpGeneration();
        return true;
    }

    /** 引脚域兼容：AUDIO 域引脚只连 AUDIO 域，FLOAT 只连 FLOAT；但 BUS_OUT/BUS_IN/PRIVATE_OUT/
     *  PRIVATE_IN 频段引脚接受任意域（音频可走「总线/私有信号频段」，R1-2；接上后求值器走音频分支）。
     *  Pin-domain compatibility: AUDIO↔AUDIO, FLOAT↔FLOAT; but BUS/PRIVATE band pins
     *  accept either domain so audio can route through bus/private bands (R1-2). */
    private static boolean pinDomainsMatch(GraphNode fromNode, int fromPin, GraphNode toNode, int toPin) {
        if (toNode.type == NodeType.BUS_OUT || fromNode.type == NodeType.BUS_IN
            || toNode.type == NodeType.PRIVATE_OUT || fromNode.type == NodeType.PRIVATE_IN) return true;
        return fromNode.type.outputDomain(fromPin) == toNode.type.inputDomain(toPin);
    }

    /** Add a connection using stable pinIds. The int indices are resolved from the
     *  pinIds and cached on the connection. Returns false if the input is already
     *  connected or would create a self-loop. */
    public boolean addConnectionWithPinIds(int fromId, String fromPinId, int toId, String toPinId) {
        GraphNode fromNode = nodeMap.get(fromId);
        GraphNode toNode = nodeMap.get(toId);
        if (fromNode == null || toNode == null) return false;
        int fromPin = fromNode.outputPinIndex(fromPinId);
        int toPin = toNode.inputPinIndex(toPinId);
        if (fromPin < 0 || toPin < 0) return false;
        if (!pinDomainsMatch(fromNode, fromPin, toNode, toPin)) return false;
        if (inputCache.containsKey(key(toId, toPin))) return false;
        if (fromId == toId) return false;
        connections.add(new NodeConnection(fromId, fromPinId, fromPin, toId, toPinId, toPin));
        invalidateTopo();
        bumpGeneration();
        return true;
    }

    public void removeConnection(int fromId, int fromPin, int toId, int toPin) {
        // Try exact match first (index-based); fall back to pinId match if
        // indices were shifted by band reordering between creation and removal.
        boolean removed = connections.removeIf(c ->
            c.fromId == fromId && c.fromPin == fromPin
            && c.toId == toId && c.toPin == toPin);
        if (!removed) {
            // PinId-based fallback: find by node+pinId
            GraphNode fromNode = nodeMap.get(fromId);
            GraphNode toNode = nodeMap.get(toId);
            String fPid = fromNode != null ? fromNode.outputPinId(fromPin) : null;
            String tPid = toNode != null ? toNode.inputPinId(toPin) : null;
            if (fPid != null || tPid != null) {
                final String ff = fPid, tt = tPid;
                connections.removeIf(c -> {
                    if (c.fromId != fromId || c.toId != toId) return false;
                    boolean fMatch = ff != null ? ff.equals(c.fromPinId) : c.fromPin == fromPin;
                    boolean tMatch = tt != null ? tt.equals(c.toPinId) : c.toPin == toPin;
                    return fMatch && tMatch;
                });
            }
        }
        invalidateTopo();
        bumpGeneration();
    }

    /** 按稳定 pinId 删除连线（撤销恢复 / REMOVE_CONN 携带 pinId 时用）。两端 pinId 都须非空。
     *  Remove a connection by stable pinIds (undo restore / pinId-carrying REMOVE_CONN).
     *  Both pinIds must be non-null. Returns whether a connection was removed. */
    public boolean removeConnectionByPinIds(int fromId, String fromPinId, int toId, String toPinId) {
        if (fromPinId == null || toPinId == null) return false;
        boolean removed = connections.removeIf(c ->
            c.fromId == fromId && c.toId == toId
            && fromPinId.equals(c.fromPinId) && toPinId.equals(c.toPinId));
        if (removed) {
            invalidateTopo();
            bumpGeneration();
        }
        return removed;
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

    // ── 传输连线引脚缓存（编辑器渲染着色用）/ transfer-wired pin cache (editor tinting) ──
    /** 总线/私有传输节点类型：与这些节点相连的引脚在编辑器中自动变色（长距传输一眼可辨）。
     *  Transfer node types: pins wired to these auto-tint in the editor so long-range
     *  transfer hops read at a glance. */
    private static final EnumSet<NodeType> TRANSFER_TYPES =
        EnumSet.of(NodeType.BUS_IN, NodeType.BUS_OUT, NodeType.PRIVATE_IN, NodeType.PRIVATE_OUT);
    /** 输出侧频段节点（其输出引脚可承载音频）/ output-side band nodes (their output pins can carry audio). */
    private static final EnumSet<NodeType> BAND_SOURCE_TYPES =
        EnumSet.of(NodeType.BUS_IN, NodeType.PRIVATE_IN);
    /** 输入侧频段节点（其输入引脚可承载音频）/ input-side band nodes (their input pins can carry audio). */
    private static final EnumSet<NodeType> BAND_SINK_TYPES =
        EnumSet.of(NodeType.BUS_OUT, NodeType.PRIVATE_OUT);
    private int transferPinVersion = -1;
    private final Set<Long> transferWiredPins = new HashSet<>();
    private final Set<Long> bandAudioPins = new HashSet<>();

    /** 该 (节点, 引脚) 是否连着总线/私有传输节点（编辑器引脚着色用）。
     *  缓存按拓扑版本失效——加/删连线/节点都会 bump 版本号，O(E) 重建摊销到首次查询。
     *  Whether this (node, pin) is wired to a BUS/PRIVATE transfer node (editor pin tinting).
     *  The cache invalidates with the topology version — every add/remove bumps it, and the
     *  O(E) rebuild amortises into the first query after a change. */
    public boolean isTransferWired(int nodeId, int pin, boolean output) {
        if (topoVersion != transferPinVersion) {
            transferWiredPins.clear();
            bandAudioPins.clear();
            for (NodeConnection c : connections) {
                GraphNode fn = nodeMap.get(c.fromId);
                GraphNode tn = nodeMap.get(c.toId);
                if (fn == null || tn == null) continue;
                if (TRANSFER_TYPES.contains(fn.type) || TRANSFER_TYPES.contains(tn.type)) {
                    transferWiredPins.add(transferPinKey(c.fromId, true, c.fromPin));
                    transferWiredPins.add(transferPinKey(c.toId, false, c.toPin));
                }
                // 频段节点自身的引脚承载音频 = 对端引脚是 AUDIO 域（BUS_IN/PRIVATE_IN 的输出接
                // 音频输入、BUS_OUT/PRIVATE_OUT 的输入接音频输出）——引脚随连线变音频色。
                // A band node's own pin carries audio when its peer pin is AUDIO-domain
                // (BUS_IN/PRIVATE_IN outputs into audio inputs, audio outputs into
                // BUS_OUT/PRIVATE_OUT inputs) — the pin tints with its wires.
                if (BAND_SOURCE_TYPES.contains(fn.type)
                        && tn.type.inputDomain(c.toPin) == NodeType.PinDomain.AUDIO)
                    bandAudioPins.add(transferPinKey(c.fromId, true, c.fromPin));
                if (BAND_SINK_TYPES.contains(tn.type)
                        && fn.type.outputDomain(c.fromPin) == NodeType.PinDomain.AUDIO)
                    bandAudioPins.add(transferPinKey(c.toId, false, c.toPin));
            }
            transferPinVersion = topoVersion;
        }
        return transferWiredPins.contains(transferPinKey(nodeId, output, pin));
    }

    /** 该 (频段节点, 引脚) 的连线对端是否音频域（BUS_IN/PRIVATE_IN 输出、BUS_OUT/PRIVATE_OUT
     *  输入的音频着色用）。缓存与 {@link #isTransferWired} 同版本同生命周期。
     *  Whether a band node's pin peers an AUDIO-domain pin (tinting BUS_IN/PRIVATE_IN outputs
     *  and BUS_OUT/PRIVATE_OUT inputs); shares isTransferWired's cache lifecycle. */
    public boolean isBandPinAudio(int nodeId, int pin, boolean output) {
        isTransferWired(nodeId, pin, output); // ensure cache rebuilt for the current topology
        return bandAudioPins.contains(transferPinKey(nodeId, output, pin));
    }

    private static long transferPinKey(int nodeId, boolean output, int pin) {
        return ((long) nodeId << 20) | ((output ? 1L : 0L) << 19) | (pin & 0xFFFFF);
    }

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
     *  Resolves stable pinIds to current integer indices and prunes connections
     *  whose pinIds no longer map to any pin.
     *  Always bumps generation when any index changes to ensure evaluator recompile.
     *  Rebuild the input cache (public for ACK-based ID remapping). */
    public void rebuildInputCache() {
        inputCache.clear();
        var stale = new java.util.ArrayList<NodeConnection>();
        boolean anyIndexChanged = false;
        for (NodeConnection c : connections) {
            // Resolve pinIds to current integer indices
            if (c.toPinId != null) {
                GraphNode toNode = nodeMap.get(c.toId);
                if (toNode != null) {
                    int resolved = toNode.inputPinIndex(c.toPinId);
                    if (resolved < 0) { stale.add(c); continue; }
                    if (c.toPin != resolved) { c.toPin = resolved; anyIndexChanged = true; }
                }
            }
            if (c.fromPinId != null) {
                GraphNode fromNode = nodeMap.get(c.fromId);
                if (fromNode != null) {
                    int resolved = fromNode.outputPinIndex(c.fromPinId);
                    if (resolved < 0) { stale.add(c); continue; }
                    if (c.fromPin != resolved) { c.fromPin = resolved; anyIndexChanged = true; }
                }
            }
            inputCache.put(key(c.toId, c.toPin), c);
        }
        if (!stale.isEmpty()) {
            connections.removeAll(stale);
            anyIndexChanged = true;
        }
        if (anyIndexChanged) bumpGeneration();
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

    /** O(1) 取音频输入引脚的上游音源引用（{@link AudioRef}）；无连线返回 null。
     *  Audio wires carry a typed {@link AudioRef} (not a float) — resolved via the connection
     *  to the upstream node's audio output ref. */
    public AudioRef getAudioInputRef(int nodeId, int pinIdx, Map<Long, AudioRef> audioRefs) {
        NodeConnection c = inputCache.get(key(nodeId, pinIdx));
        return c != null ? audioRefs.get(key(c.fromId, c.fromPin)) : null;
    }

    /** 该节点的输出是否接了音频线（任一出线指向 AUDIO 域输入引脚）。
     *  供 BUS_IN 的音频分支判定——只有挂了音频线的 BUS_IN 才读音频频段，
     *  避免同名浮点频段被音频发布方劫持。
     *  Whether any outgoing wire of this node feeds an AUDIO-domain input pin.
     *  Gates the BUS_IN audio branch: a BUS_IN reads audio bands only when audio-wired. */
    public boolean hasAudioSinkConnection(int fromNodeId) {
        for (NodeConnection c : connections) {
            if (c.fromId != fromNodeId) continue;
            GraphNode to = nodeMap.get(c.toId);
            if (to != null && to.type.inputDomain(c.toPin) == NodeType.PinDomain.AUDIO) return true;
        }
        return false;
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
            if (idMap.containsKey(c.fromId) && idMap.containsKey(c.toId)) {
                NodeConnection dup = new NodeConnection(
                    idMap.get(c.fromId), c.fromPinId, c.fromPin,
                    idMap.get(c.toId), c.toPinId, c.toPin);
                g.connections.add(dup);
            }
        }
        g.rebuildInputCache();
        return g;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(NbtVersions.VERSION_KEY, NbtVersions.DATA_VERSION);
        tag.putInt("nextId", nextNodeId);
        tag.putInt("nextSortB", nextSortB);
        // 可选键：旧档缺省 → 空名（回退到方块类型名），新档多一个键旧代码也直接忽略
        // —— 双向兼容，无需 DATA_VERSION 迁移（同 MonitorBlockEntity.saveSettings 的先例）。
        // Optional key: absent on legacy saves → empty name (falls back to the block
        // type name); older code loading a newer save ignores it — compatible in both
        // directions, no DATA_VERSION migration (same precedent as
        // MonitorBlockEntity.saveSettings).
        if (!customName.isEmpty()) tag.putString("customName", customName);
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
        CompoundTag tag = GraphMigration.migrate(rawTag, registries);
        return loadCurrent(tag, registries);
    }

    /** 加载已经是当前 DATA_VERSION 的 NBT 标签。
     *  Load a tag that is already at the current DATA_VERSION. */
    private static NodeGraph loadCurrent(CompoundTag tag, HolderLookup.Provider registries) {
        NodeGraph g = new NodeGraph();
        g.nextNodeId = tag.getInt("nextId");
        g.nextSortB = tag.contains("nextSortB") ? tag.getInt("nextSortB") : 1;
        g.customName = tag.contains("customName") ? tag.getString("customName") : "";
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
