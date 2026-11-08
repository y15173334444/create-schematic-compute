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

    /** 自定义节点入口（公开 API 波 1）：customId 在构造期给出 —— params 尺寸来自
     *  schema。内置节点走旧重载（customId 恒 null）。
     *  <p>Custom-node entry (public-API wave 1): customId reaches the constructor — the
     *  params size comes from the schema. Built-ins keep the old overload (customId null).</p> */
    public GraphNode addNode(NodeType type, String customId, float x, float y) {
        GraphNode node = new GraphNode(nextNodeId++, type, customId, x, y);
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
        autoDeclareBusSlot(fromNode, fromPin, toNode, toPin);
        if (fromNode != null && toNode != null && !pinDomainsMatch(fromNode, fromPin, toNode, toPin)) return false;
        String fPid = fromNode != null ? fromNode.outputPinId(fromPin) : null;
        String tPid = toNode != null ? toNode.inputPinId(toPin) : null;
        connections.add(new NodeConnection(fromId, fPid, fromPin, toId, tPid, toPin));
        invalidateTopo();
        bumpGeneration();
        return true;
    }

    /** 连接即声明（全切换模型不变量，一处写清、全路径生效）：落线时目标是 BUS_OUT 频段脚、
     *  对端有显式类型且与现声明不同 → 槽位声明切成对端类型（空槽补声明、显式声明随连接
     *  改写）。手势、撤销重接、远端 ADD_CONN、粘贴都走 {@link PinType#autoDeclareFrom}
     *  同一判定，「已连的频段脚声明 = 线的类型」在每条路径成立（手势层的 SET_PIN_TYPE
     *  快路径提前得到同一结果并携带同步编排）。
     *  Declare-at-connect (the full-switch model invariant, written once for every path):
     *  when a wire lands on a BUS_OUT band pin and the peer carries an explicit type differing
     *  from the slot's declaration, the slot switches to the peer's type (empty slots fill,
     *  explicit declarations rewrite). Gestures, undo rewires, remote ADD_CONNs and pastes
     *  all decide through {@link PinType#autoDeclareFrom}, so "a wired band pin's declaration
     *  equals the wire's type" holds everywhere (the gesture-level SET_PIN_TYPE fast path
     *  reaches the same result earlier and carries the sync orchestration). */
    private static void autoDeclareBusSlot(GraphNode fromNode, int fromPin, GraphNode toNode, int toPin) {
        if (fromNode == null || toNode == null || toNode.type != NodeType.BUS_OUT
            || toNode.bandTypes == null || toPin < 0 || toPin >= toNode.bandTypes.size()
            || toPin >= (toNode.signalBands != null ? toNode.signalBands.size() : 0)) return;
        PinType declared = PinType.autoDeclareFrom(toNode.bandType(toPin), fromNode.outputType(fromPin));
        if (declared != null) toNode.bandTypes.set(toPin, declared);
    }

    /** 上游断开自动回灰（一律回灰：无线即空）：被摘线的目标是 BUS_OUT 频段脚时该槽声明清回
     *  EMPTY——「上游未连接/断开 ⟺ 灰」的不变量，同样一处写清、全路径生效（手势删线 /
     *  撤销 / 远端 REMOVE_CONN 同规则）。下游旧线随后由频段收敛传播 + 类型换段重绑的
     *  「对端不匹配剪除」契约剪除（类型合法性优先于保线）。
     *  Upstream disconnect reverts to grey (always: no wire = empty): when the removed wire's
     *  target is a BUS_OUT band pin, its slot clears to EMPTY — the "upstream unconnected/
     *  disconnected ⟺ grey" invariant, written once for every path (gesture deletes, undo,
     *  remote REMOVE_CONNs). Downstream wires are then pruned by the type-segment rebind's
     *  "peer mismatch prunes" contract once the change rides the band convergence (type
     *  legality wins over keeping wires). */
    private void disconnectBusSlot(int toId, int toPin) {
        GraphNode to = nodeMap.get(toId);
        if (to != null && to.type == NodeType.BUS_OUT && to.bandTypes != null
            && toPin >= 0 && toPin < to.bandTypes.size()
            && toPin < (to.signalBands != null ? to.signalBands.size() : 0)) {
            to.bandTypes.set(toPin, PinType.EMPTY);
        }
    }

    /** 引脚载荷类型兼容：声明类型严格相等（AUDIO↔AUDIO、FLOAT↔FLOAT、VEC3↔VEC3），且
     *  **任一端未声明（空）即拒**——「下游可连 ⟺ 上游有连线/声明」的连接门：灰频段脚拒接
     *  新线（空↔空暗桥同样拒），上游点亮（接线自动声明 / 手动芯片声明）后才可连接。
     *  BUS/PRIVATE 频段引脚也按**声明类型**参与严格匹配（任意域豁免已随传输收编退役）——
     *  音频走总线/私有频段 = 把频段声明为 AUDIO 后再接线。
     *  Pin payload-type compatibility: declared types must match strictly AND neither end may
     *  be undeclared (empty) — the downstream gate "a downstream connect needs the upstream
     *  wired/declared": grey band pins refuse new wires (empty↔empty dark bridges included)
     *  until the upstream lights up (auto-declare at connect / a manual chip declaration).
     *  BUS/PRIVATE band pins match by their declared type too (the any-domain exemption
     *  retired with the transport consolidation). */
    private static boolean pinDomainsMatch(GraphNode fromNode, int fromPin, GraphNode toNode, int toPin) {
        PinType f = fromNode.outputType(fromPin), t = toNode.inputType(toPin);
        return f == t && f != PinType.EMPTY;
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
        // 录取判定先于声明：被拒的连接（占用/自环）不得改写声明——「落线即声明」的因果
        // 只跟真正落下的线走。/ Admission before declaration: a rejected connect (occupied
        // input / self-loop) must not rewrite the declaration — the declare-at-connect
        // cause only follows wires that actually land.
        if (inputCache.containsKey(key(toId, toPin))) return false;
        if (fromId == toId) return false;
        autoDeclareBusSlot(fromNode, fromPin, toNode, toPin);
        if (!pinDomainsMatch(fromNode, fromPin, toNode, toPin)) return false;
        // pinId 归一：声明可能刚切换过类型段，以节点当前真相源生成的新形态存储（给定
        //  pinId 只用于解析索引）。/ pinIds normalise after a possible declare — stored in
        //  the fresh form from the node's current truth (the given pinIds only resolve pins).
        connections.add(new NodeConnection(fromId, fromNode.outputPinId(fromPin), fromPin,
            toId, toNode.inputPinId(toPin), toPin));
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
                removed = connections.removeIf(c -> {
                    if (c.fromId != fromId || c.toId != toId) return false;
                    boolean fMatch = ff != null ? ff.equals(c.fromPinId) : c.fromPin == fromPin;
                    boolean tMatch = tt != null ? tt.equals(c.toPinId) : c.toPin == toPin;
                    return fMatch && tMatch;
                });
            }
        }
        // 回灰只跟真实摘除走：迟到/重复的 REMOVE_CONN（没摘掉任何线）不得清掉新线的声明。
        // The grey-out only follows an actual removal: a late/duplicate REMOVE_CONN (that
        // removed nothing) must not clear the declaration of a newer wire.
        if (removed) disconnectBusSlot(toId, toPin);
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
            GraphNode to = nodeMap.get(toId);
            disconnectBusSlot(toId, to != null ? to.inputPinIndex(toPinId) : -1);
            invalidateTopo();
            bumpGeneration();
        }
        return removed;
    }

    /** 获取拓扑排序（缓存，仅在连接变化时重算）。
     *  Get topological order (cached, recomputed only on connection changes). */    public List<Integer> getTopoOrder() {
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
    private int transferPinVersion = -1;
    private final Set<Long> transferWiredPins = new HashSet<>();

    /** 该 (节点, 引脚) 是否连着总线/私有传输节点（编辑器引脚着色用）。
     *  缓存按拓扑版本失效——加/删连线/节点都会 bump 版本号，O(E) 重建摊销到首次查询。
     *  Whether this (node, pin) is wired to a BUS/PRIVATE transfer node (editor pin tinting).
     *  The cache invalidates with the topology version — every add/remove bumps it, and the
     *  O(E) rebuild amortises into the first query after a change. */
    public boolean isTransferWired(int nodeId, int pin, boolean output) {
        if (topoVersion != transferPinVersion) {
            transferWiredPins.clear();
            for (NodeConnection c : connections) {
                GraphNode fn = nodeMap.get(c.fromId);
                GraphNode tn = nodeMap.get(c.toId);
                if (fn == null || tn == null) continue;
                if (TRANSFER_TYPES.contains(fn.type) || TRANSFER_TYPES.contains(tn.type)) {
                    transferWiredPins.add(transferPinKey(c.fromId, true, c.fromPin));
                    transferWiredPins.add(transferPinKey(c.toId, false, c.toPin));
                }
            }
            transferPinVersion = topoVersion;
        }
        return transferWiredPins.contains(transferPinKey(nodeId, output, pin));
    }

    /** 该 (频段节点, 引脚) 的**声明载荷类型**是否 AUDIO（频段引脚的音频着色用；v1.3.0 起
     *  看声明、不再看连线拓扑；非频段节点恒 false——方法名本义）/ Whether a band pin's
     *  declared payload type is AUDIO (band-pin audio tinting; declared, not
     *  topology-derived, since v1.3.0; always false for non-band nodes — the name's
     *  literal scope). */
    public boolean isBandPinAudio(int nodeId, int pin, boolean output) {
        GraphNode n = nodeMap.get(nodeId);
        if (n == null || !(n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT
                || n.type == NodeType.PRIVATE_IN || n.type == NodeType.PRIVATE_OUT)) return false;
        return output ? n.outputType(pin) == PinType.AUDIO : n.inputType(pin) == PinType.AUDIO;
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
        // 未知类型码剪线（解析不到即剪的声明侧）：bandTypes 槽位为 null（载入时类型码
        // 解析不到——新版本码/损坏存档）时，该槽位的触点连线剪除——类型合法性优先于
        // 保线。触点判定用**解析后**的引脚索引（pinId 解析环刚把缓存索引刷新过）：
        // 未知码形态的 pinId（如 "quat:kick"）在解析环里本就解析不到、按既有纪律剪除；
        // 恰与降级 FLOAT 形态同名的 pinId（"float:kick"）解析回该槽、由本判定剪除；索引
        // 绑定旧式连线按缓存索引剪除。随后槽位自愈 EMPTY（未连接⟺灰，重存即固化）。
        // Unknown type codes on the declaration side ("unresolvable → prune"): a null
        // bandTypes slot (its code failed to parse at load — a newer version's code or a
        // corrupt save) has its wires cut — type legality wins over keeping wires. Touch is
        // decided on the **resolved** pin indices (the pinId loop above just refreshed them):
        // unknown-code pinIds (like "quat:kick") already fail resolution and die by the
        // existing discipline; pinIds that coincide with the degraded FLOAT shape
        // ("float:kick") resolve back to the slot and die here; legacy index-bound wires die
        // by cached index. The slot then self-heals to EMPTY (the unwired-⟹-grey rule; a
        // resave cements it).
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
            if (touchesUnknownBandTypeSlot(c)) { stale.add(c); continue; }
            inputCache.put(key(c.toId, c.toPin), c);
        }
        if (!stale.isEmpty()) {
            connections.removeAll(stale);
            anyIndexChanged = true;
        }
        // 自愈（F15 同款 NBT 自愈，重存即固化）：null 槽位的声明已不可知，清成 EMPTY——
        // 「未连接 ⟺ 灰」取代旧的 FLOAT 缺省（没有连线就没有声明），后续保存写回 "empty"，
        // 不把无法解析的码带进新档。放在触点判定**之后**——先剪线后自愈，标记一次性。
        // Self-heal (F15's NBT self-heal; a resave cements it): a null slot's declaration is
        // unknowable, so it clears to EMPTY — the unwired-⟹-grey rule supersedes the old
        // FLOAT default (no declaration survives without a wire); a later save writes
        // "empty", never carrying the unparseable code into the new save. Runs **after** the
        // touch checks — prune first, heal second; the marker is one-shot here.
        for (GraphNode n : nodes) {
            if (n.bandTypes == null) continue;
            for (int i = 0; i < n.bandTypes.size(); i++)
                if (n.bandTypes.get(i) == null) n.bandTypes.set(i, PinType.EMPTY);
        }
        if (anyIndexChanged) bumpGeneration();
    }

    /** 连线是否触在声明类型码解析不到（bandTypes 槽位 null）的频段/私有引脚上。
     *  触点判定用解析后的引脚索引；槽位数以频段引脚数为界（BUS 逐频段、PRIVATE 单槽）——
     *  bandTypes 多出的条目（损坏档）不对应引脚，绝不能按同号索引误伤参数引脚的连线。
     *  Whether the connection touches a band/private pin whose declared type code failed to
     *  parse (a null bandTypes slot). Touch is decided on the resolved pin index; the slot
     *  count is bounded by the band-pin count (per-band for BUS, single for PRIVATE) — extra
     *  bandTypes entries (corrupt saves) map to no pin and must never hit a param pin's
     *  same-numbered wire. */
    private boolean touchesUnknownBandTypeSlot(NodeConnection c) {
        GraphNode to = nodeMap.get(c.toId);
        if (to != null && (to.type == NodeType.BUS_OUT || to.type == NodeType.PRIVATE_OUT)
                && to.bandTypes != null && c.toPin >= 0 && c.toPin < to.bandTypes.size()
                && c.toPin < (to.type == NodeType.PRIVATE_OUT ? 1
                    : (to.signalBands != null ? to.signalBands.size() : 0))
                && to.bandTypes.get(c.toPin) == null) return true;
        GraphNode from = nodeMap.get(c.fromId);
        return from != null && (from.type == NodeType.BUS_IN || from.type == NodeType.PRIVATE_IN)
            && from.bandTypes != null && c.fromPin >= 0 && c.fromPin < from.bandTypes.size()
            && c.fromPin < (from.type == NodeType.PRIVATE_IN ? 1
                : (from.signalBands != null ? from.signalBands.size() : 0))
            && from.bandTypes.get(c.fromPin) == null;
    }

    /** 频段列表对齐的**唯一规则**（改名不丢线）：新表与旧表**同长度**、且每个变化的槽位都是
     *  「双射换名」（旧名从新表消失、新名未在旧表出现）时，判定为槽位改名——旧名 pinId 上的
     *  连线原地改绑到新名（索引不动），一条线都不剪；本方法同时写回 {@code signalBands} 与
     *  {@code bandsDirty}。其余情况（增删频段、改名与重排混在一次提交的歧义）维持既有语义：
     *  按名字差集剪掉真正消失频段上的连线（BUS_IN 输入侧的索引兜底由调用方负责）。
     *  <p>在名字对齐之上叠加**类型换段重绑**（v1.3.0）：槽位声明类型变化时，连线上旧类型码的
     *  pinId 原地换段改绑；对端声明类型与新类型不匹配的连线剪除——类型合法性优先于保线。</p>
     *  <p>四条对齐路径共用本方法——SET_BANDS op、BusBandUploadPacket、convergeBusInBands、
     *  syncBandsFromServer——改名因此在任一路径、任意图（含跨方块订阅方经收敛传播）都保线。
     *  判定只需新旧两份列表的状态，不需要专门的 rename 事件在包与方块之间搬运——
     *  「自愈式不变量优于一次性事件推送」。</p>
     *  The single band-list alignment rule (renames keep their wires), with v1.3.0
     *  <b>type-segment rebinding</b> layered on top: a slot whose declared type changes
     *  rebinds its connections' pinIds to the new type code in place; connections whose
     *  peer's declared type mismatches are pruned — type legality wins over keeping wires.
     *  @param n 目标节点（BUS_OUT / BUS_IN 等带 signalBands 的节点）
     *            the node whose signalBands list is being aligned
     *  @param newTypes 新的逐槽声明类型（null = 类型按槽位继承旧值，新增频段缺省 EMPTY——
     *            未连接⟺灰：无型信息的新槽就是未声明）
     *            per-slot declared types (null = carry over by slot, new bands default
     *            EMPTY — unwired means grey: a new slot without type info is undeclared) */
    public void reconcileBands(GraphNode n, List<String> newBands) {
        reconcileBands(n, newBands, null);
    }

    /** 带声明类型的频段对齐（见 {@link #reconcileBands(GraphNode, List)} 的对齐规则）。
     *  Band alignment with declared types (see {@link #reconcileBands(GraphNode, List)}). */
    public void reconcileBands(GraphNode n, List<String> newBands, List<PinType> newTypes) {
        var want = new java.util.ArrayList<String>(newBands != null ? newBands : java.util.Collections.<String>emptyList());
        var old = n.signalBands != null ? n.signalBands : java.util.Collections.<String>emptyList();
        var oldT = new java.util.ArrayList<PinType>(old.size());
        for (int i = 0; i < old.size(); i++) oldT.add(n.bandType(i));
        var wantT = new java.util.ArrayList<PinType>(want.size());
        for (int i = 0; i < want.size(); i++)
            wantT.add(newTypes != null && i < newTypes.size() && newTypes.get(i) != null
                ? newTypes.get(i) : (i < oldT.size() ? oldT.get(i) : PinType.EMPTY));
        var stale = new java.util.ArrayList<NodeConnection>();
        if (old.size() == want.size()) {
            // 双射换名判定：旧名∉新表 且 新名∉旧表，且同一旧名不得映射到两个新名。
            // Bijective-rename test: old name gone from the new list, new name unseen in the
            // old list, and no old name renamed two different ways.
            var renames = new java.util.LinkedHashMap<String, String>();
            var newSet = new java.util.HashSet<>(want);
            var oldSet = new java.util.HashSet<>(old);
            boolean pureRename = true;
            for (int i = 0; i < old.size() && pureRename; i++) {
                String o = old.get(i), w = want.get(i);
                if (o.equals(w)) continue;
                if (newSet.contains(o) || oldSet.contains(w) || renames.put(o, w) != null) pureRename = false;
            }
            if (pureRename) {
                for (int i = 0; i < old.size(); i++) {
                    String o = old.get(i), w = want.get(i);
                    PinType ot = oldT.get(i), nt = wantT.get(i);
                    if (o.equals(w) && ot == nt) continue;
                    retargetPinId(n, PinId.format(ot, o), PinId.format(nt, w), ot != nt, stale);
                }
                n.signalBands = want;
                n.bandTypes = wantT;
                n.bandsDirty = true;
                connections.removeAll(stale);
                return;
            }
        }
        // 混合提交：同名改类型仍按名重绑（跨槽位查找旧类型），真正消失的频段按参段剪线。
        // Mixed commit: same-name retypes still rebind (old type found by name), genuinely
        // removed bands prune by param.
        var oldIdx = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < old.size(); i++) oldIdx.putIfAbsent(old.get(i), i);
        for (int i = 0; i < want.size(); i++) {
            Integer oi = oldIdx.get(want.get(i));
            if (oi != null && oldT.get(oi) != wantT.get(i))
                retargetPinId(n, PinId.format(oldT.get(oi), want.get(i)),
                    PinId.format(wantT.get(i), want.get(i)), true, stale);
        }
        // 既有语义：按名字差集剪掉真正被删除频段上的连线。
        // Existing semantics: prune connections on genuinely-removed band names.
        var removed = new java.util.ArrayList<>(old);
        removed.removeAll(want);
        n.signalBands = want;
        n.bandTypes = wantT;
        n.bandsDirty = true;
        for (String removedBand : removed) {
            connections.removeIf(c ->
                (c.fromId == n.id && removedBand.equals(PinId.paramOf(c.fromPinId))) ||
                (c.toId == n.id && removedBand.equals(PinId.paramOf(c.toPinId))));
        }
        connections.removeAll(stale);
    }

    /** PRIVATE_* 频道声明类型变更（单引脚的类型换段重绑：对端声明类型不匹配的连线剪除）。
     *  Private-channel declared-type change (type-segment rebind of the single pin;
     *  connections whose peer's declared type mismatches are pruned). */
    public void setPrivatePinType(GraphNode n, PinType newType) {
        if (n.type != NodeType.PRIVATE_IN && n.type != NodeType.PRIVATE_OUT) return;
        PinType oldT = n.bandType(0);
        if (oldT == newType) return;
        var stale = new java.util.ArrayList<NodeConnection>();
        String oldPid = PinId.format(oldT, "0");
        String newPid = PinId.format(newType, "0");
        retargetPinId(n, oldPid, newPid, true, stale);
        n.bandTypes = new java.util.ArrayList<>(java.util.List.of(newType));
        n.bandsDirty = true;
        connections.removeAll(stale);
    }

    /** 把挂在 {@code oldPid} 上的连线改绑到 {@code newPid}；{@code peerCheck} 时对端声明类型
     *  须等于新类型，不匹配的列入 {@code stale} 待剪。null pinId 的遗留连线不参与（旧语义）。
     *  Rebind connections bound to {@code oldPid} onto {@code newPid}; with
     *  {@code peerCheck} the peer's declared type must equal the new type, else the
     *  connection goes into {@code stale} for pruning. Legacy null-pinId wires never
     *  participate (existing semantics). */
    private void retargetPinId(GraphNode n, String oldPid, String newPid, boolean peerCheck, List<NodeConnection> stale) {
        for (NodeConnection c : connections) {
            if (c.fromId == n.id && oldPid.equals(c.fromPinId)) {
                if (peerCheck && !peerAccepts(c, true, PinId.typeOf(newPid))) stale.add(c);
                else c.fromPinId = newPid;
            }
            if (c.toId == n.id && oldPid.equals(c.toPinId)) {
                if (peerCheck && !peerAccepts(c, false, PinId.typeOf(newPid))) stale.add(c);
                else c.toPinId = newPid;
            }
        }
    }

    /** 连线对端的声明类型是否为 {@code t}（outputSide=true 查 toNode 的输入脚，否则查
     *  fromNode 的输出脚）；对端节点缺席视为不匹配。 / Whether the connection's peer pin
     *  declares type {@code t} (outputSide=true checks toNode's input pin, else fromNode's
     *  output pin); a missing peer node counts as mismatch. */
    private boolean peerAccepts(NodeConnection c, boolean outputSide, PinType t) {
        if (t == null) return false;
        if (outputSide) {
            GraphNode to = nodeMap.get(c.toId);
            return to != null && to.inputType(c.toPin) == t;
        }
        GraphNode from = nodeMap.get(c.fromId);
        return from != null && from.outputType(c.fromPin) == t;
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

    /** O(1) 取音频输入引脚的上游音源引用（{@link AudioRef}）；无连线/非音频载荷返回 null。
     *  Audio pins carry a typed {@link AudioRef} payload — resolved via the connection to
     *  the upstream node's payload entry. */
    public AudioRef getInputAudio(int nodeId, int pinIdx, Map<Long, PinPayload> payloadRefs) {
        NodeConnection c = inputCache.get(key(nodeId, pinIdx));
        PinPayload p = c != null ? payloadRefs.get(key(c.fromId, c.fromPin)) : null;
        return p instanceof AudioRef a ? a : null;
    }

    /** O(1) 取向量输入引脚的上游向量载荷（{@link Vec3f}）；无连线/非向量载荷返回 null。
     *  Vector pins carry a typed {@link Vec3f} payload — resolved via the connection to
     *  the upstream node's payload entry. */
    public Vec3f getInputVec3(int nodeId, int pinIdx, Map<Long, PinPayload> payloadRefs) {
        NodeConnection c = inputCache.get(key(nodeId, pinIdx));
        PinPayload p = c != null ? payloadRefs.get(key(c.fromId, c.fromPin)) : null;
        return p instanceof Vec3f v ? v : null;
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
        // 存档自愈（未连接 ⟺ 灰）：BUS_OUT 频段脚没有入线的槽位，声明一律清回 EMPTY——
        // 旧档存量声明（FLOAT 缺省时代 / 断线粘性时代残留）不再带进新会话。「声明」
        // 激活器仍在会话内有效（芯片先行声明可放行下游连接），但不再跨存档存活。
        // 排在 rebuildInputCache 之后：未知码空标的剪线/自愈先行，本收敛殿后兜底。
        // Save self-heal (unwired ⟺ grey): a BUS_OUT band slot with no incoming wire clears
        // its declaration to EMPTY — legacy declarations (the FLOAT-default / sticky-era
        // leftovers) no longer carry into new sessions. The "declaration" activator still
        // works within a session (a chip declaration opens downstream connects) but does
        // not survive a save round-trip. Runs after rebuildInputCache so the unknown-code
        // marker's prune/heal goes first and this convergence backstops the rest.
        for (GraphNode n : g.nodes) {
            if (n.type != NodeType.BUS_OUT || n.bandTypes == null || n.signalBands == null) continue;
            for (int i = 0; i < n.bandTypes.size() && i < n.signalBands.size(); i++) {
                if (n.bandTypes.get(i) == PinType.EMPTY) continue;
                boolean wired = false;
                for (NodeConnection c : g.connections) {
                    if (c.toId == n.id && c.toPin == i) { wired = true; break; }
                }
                if (!wired) n.bandTypes.set(i, PinType.EMPTY);
            }
        }
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
