package io.github.y15173334444.create_schematic_compute.graph;

import io.github.y15173334444.create_schematic_compute.api.CustomNodeType;
import net.minecraft.world.item.ItemStack;

/**
 * {@link GraphOp} 应用的共享执行器。
 * 服务端的 {@code applyOp} 和客户端的 {@code onRemoteOp} 都委托给此类，
 * 确保变更逻辑只定义在一处。
 * Shared executor for {@link GraphOp} application.
 * Both server-side {@code applyOp} and client-side {@code onRemoteOp}
 * delegate to this class so mutation logic is defined in one place.
 *
 * <p>验证工作（环检测、目标存在性等）由调用方负责 —— 此类只执行原始图变更。
 * Validation (cycle checks, target existence, etc.) is the caller's
 * responsibility — this class performs the raw graph mutation only.</p>
 */
public final class OpExecutor {

    /** 独立 logger：不引用 (root) 包的 @Mod 类——其静态初始化依赖 MC 注册表，
     *  而 LoggerFactory 不依赖注册表，headless/JUnit 下也能安全完成类初始化。日志名为本类 FQCN。
     *  Same-name standalone logger: never references the @Mod class — its static init needs
     *  the Minecraft registries, while LoggerFactory does not, so class init stays safe in
     *  plain JUnit too. The log name is this class's FQCN. */
    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger(OpExecutor.class);

    private OpExecutor() {}

    /** 玩家热栏实际槽数（0..8）。K15 上界：SET_HOTBAR_ITEM 的槽位声明超出即拒绝 ——
     *  原实现按 {@code new ItemStack[slot + 1]} 无界扩容，单个声明 2³¹-1 的 op 即
     *  数十 GB 数组分配。
     *  <p>Real player hotbar size (0..8). K15 cap: a SET_HOTBAR_ITEM slot beyond it is
     *  refused — the old {@code new ItemStack[slot + 1]} expanded unbounded from the declared
     *  index, so one op claiming 2³¹-1 allocated a tens-of-GB array.</p> */
    public static final int HOTBAR_SLOTS = 9;

    /** 单节点全部 TEXT 控件的文本总量帽（批 D，条目 17②）。包层 per-control 帽
     *  （api.CustomNodeType.MAX_TEXT_PARAM_LENGTH）只限单条；32 控件 × 32KB 按节点
     *  聚合无界才是条目记录的真实暴露面——比照批 B 状态 blob「32KB/节点」纪律，
     *  聚合超帽拒收（不写入不 bump；包层预检看不到节点现有文本，聚合裁定只能住
     *  执行器，客户端超帽表现为静默不生效——与 K15 纵深防御同口径）。
     *  <p>Per-node total TEXT cap across every TEXT control (batch D, entry 17②). The
     *  packet layer's per-control cap bounds a single text only; 32 controls × 32KB
     *  aggregated per node unbounded is the exposure the entry records — mirroring the
     *  batch-B state-blob "32KB per node" discipline, an aggregate over the cap is
     *  refused (no write, no bump; the packet pre-check cannot see the node's existing
     *  texts, so the aggregate ruling can only live in the executor — an over-cap client
     *  write silently does not land, the K15 defense-in-depth shape).</p> */
    public static final int MAX_TEXT_TOTAL_PER_NODE = 32768;

    /** IMAGE_SEQUENCE 帧列表扩容上限。K15 上界：帧仅由用户在像素编辑器手动逐帧添加
     *  （PixelEditorKernel/PixelEditorFrameStrip 无硬上限，实际动画为个位数到几十帧），
     *  64 已远超正常用量；原实现的 {@code while (size <= fi) add(blank)} 按声明索引无界
     *  扩容（每帧 w×h int[]），单个 op 可打爆内存/冻住 tick。
     *  <p>IMAGE_SEQUENCE frame-list expansion cap. K15 cap: frames are only added one by one
     *  by the user in the pixel editor (PixelEditorKernel/PixelEditorFrameStrip impose no hard
     *  cap; real animations run single-digit to a few dozen frames), so 64 far exceeds normal
     *  use; the old {@code while (size <= fi) add(blank)} expanded unbounded from the declared
     *  index (one blank w×h int[] per iteration) — one op could blow memory / freeze the tick.</p> */
    public static final int MAX_SEQUENCE_FRAMES = 64;

    /** 频段列表条数上限（K15 同值收编）：此前只在线缆解码处钳（GraphEditOpPacket 的
     *  字面量 64），ADD_NODE 快照恢复路径无钳 —— 改包客户端可夹带任意长的频段表。
     *  恢复侧与解码侧都引用本常量，同源防漂移。
     *  <p>Band-list count cap (K15 same-value consolidation): previously clamped only at
     *  wire decode (the literal 64 in GraphEditOpPacket), never on the ADD_NODE snapshot
     *  restore path — a modified client could smuggle an arbitrarily long band list. Both
     *  the restore side and the decode side reference this constant so the two cannot
     *  drift.</p> */
    public static final int MAX_BANDS = 64;

    /** ADD_NODE 快照 NBT 的最大结构嵌套深度（条目 16 深度预扫描）。恢复快照唯一合法
     *  来源是 {@code GraphNode.save}（字段扁平 + subGraph 嵌套每层 +2），实际深度 ≤15；
     *  64 远超正常用量。32KB 线缆包封内的 {@code {a:{a:…}} 深链若直达 TagParser，
     *  {@code catch (Exception)} 接不住解析递归的 StackOverflowError —— 解析前先做
     *  O(n) 花括号深度预扫描（引号感知），超限跳过恢复留空壳（与其他畸形快照同口径）。
     *  <p>Max structural nesting depth for an ADD_NODE snapshot NBT (entry-16 depth
     *  pre-scan). The only legitimate snapshot source is {@code GraphNode.save} (flat
     *  fields + subGraph nesting at +2 per level, real depth ≤15), so 64 far exceeds
     *  normal use. A 32KB {@code {a:{a:…}} chain fed straight to TagParser can overflow
     *  the parser's recursion — a {@code catch (Exception)} never catches the resulting
     *  StackOverflowError — so an O(n) quote-aware brace-depth pre-scan runs before the
     *  parse; over-limit restores are skipped (bare shell, same policy as other malformed
     *  snapshots).</p> */
    public static final int MAX_SNAPSHOT_DEPTH = 64;

    /**
     * 注册表访问提供者（P2-6）：网络层在应用 op 时注入（服务端
     * {@code GraphEditOpPacket.handleServer} / 客户端 {@code GraphEditOpSyncPacket.handle}），
     * 供撤销恢复 ENCAPSULATION 子图时反序列化子节点物品（与 {@link BlobStore} 同款
     * 注入模式 —— graph 层不直连注册表，headless 测试无需引导）。未接线 = null →
     * 跳过子图恢复（节点保持空壳，与其他 NBT 解析失败同口径）。
     * <p>Registry-access provider (P2-6): injected by the network layer when applying ops
     * (server {@code GraphEditOpPacket.handleServer} / client
     * {@code GraphEditOpSyncPacket.handle}) so the undo restore can deserialize sub-node
     * ItemStacks inside an ENCAPSULATION sub-graph (same injection pattern as
     * {@link BlobStore} — the graph layer never touches registries directly, and headless
     * tests need no bootstrap). Unwired = null → the subGraph restore is skipped (the node
     * stays a bare shell, same policy as other NBT parse failures).</p>
     */
    private static volatile net.minecraft.core.HolderLookup.Provider registryAccess = null;

    /** 注入注册表访问提供者（每次应用 op 前由网络层刷新）。/ Inject the registry-access
     *  provider (refreshed by the network layer before each op application). */
    public static void setRegistryAccess(net.minecraft.core.HolderLookup.Provider provider) {
        registryAccess = provider;
    }

    /** Blob 字节来源（接口下沉，根包注入 network.BlobRegistry::poll）；未接线 = 恒 null，
     *  SET_SONG 按坏数据拒存原曲。 / blob byte source (interface sinking; the root wires
     *  network.BlobRegistry::poll); unwired yields null forever and SET_SONG rejects the
     *  data, keeping the old song. */
    private static BlobStore blobStore = id -> null;

    /** 注入 blob 字节来源（根包在模组构造时接线）。 / inject the blob byte source (wired
     *  by the root at mod construction). */
    public static void setBlobStore(BlobStore store) {
        blobStore = store != null ? store : id -> null;
    }

    /** 按 X 升序排列控制点对（冒泡排序，数组很小）。 */
    private static void sortByX(float[] cx, float[] cy) {
        for (int i = 0; i < cx.length - 1; i++) {
            for (int j = i + 1; j < cx.length; j++) {
                if (cx[i] > cx[j]) {
                    float tx = cx[i]; cx[i] = cx[j]; cx[j] = tx;
                    float ty = cy[i]; cy[i] = cy[j]; cy[j] = ty;
                }
            }
        }
    }

    /**
     * 将操作应用到给定图上。对于创建或修改单个节点的操作，返回受影响的节点；
     * 否则返回 null。
     * Apply an op to the given graph. Returns the affected node for
     * operations that create or modify a single node, null otherwise.
     *
     * <p>MOVE 直接落在权威坐标上。客户端接收远程操作时使用
     * {@link #apply(NodeGraph, GraphOp, boolean)} 并传入 {@code animateMoves=true}
     * 来平滑大幅度移动。
     * Moves land directly on x/y (authoritative). Use
     * {@link #apply(NodeGraph, GraphOp, boolean)} with {@code animateMoves=true}
     * on the client when applying remote ops, to smooth large moves.</p>
     */
    public static GraphNode apply(NodeGraph graph, GraphOp op) {
        return apply(graph, op, false);
    }

    /**
     * @param animateMoves 仅客户端使用：较大的 MOVE_NODE 偏移会启动渲染循环插值
     *                     而非直接跳变。服务端必须传 {@code false} ——
     *                     服务端没有 tick 来驱动插值，且 remote* 字段为瞬态的，
     *                     动画移动永远不会落地。
     *                     client-only: large MOVE_NODE deltas start a render-loop
     *                     lerp instead of snapping. Must be {@code false} on the
     *                     server — nothing ticks the lerp there and the remote*
     *                     fields are transient, so animated moves would never land.
     */
    public static GraphNode apply(NodeGraph graph, GraphOp op, boolean animateMoves) {
        return switch (op.type()) {
            case ADD_NODE -> {
                // 服务端→客户端 广播给非发起编辑者：服务端分配的 ID 是权威的。
                // S→C broadcast to non-originator editors: server-assigned ID is authoritative.
                // customNodeId 随 op 抵达（公开 API 波 1）——CUSTOM 建点必须在构造期拿到 id
                // （params 尺寸来自 schema）；内置节点该值为 null，与旧重载等价。
                // customNodeId rides the op (public-API wave 1) — a CUSTOM node must receive
                // its id at construction (params sizing comes from the schema); built-ins
                // carry null, identical to the old overload.
                var node = graph.addNode(op.nodeType(), op.customNodeId(), op.x(), op.y());
                node.id = op.targetNodeId();
                // 这里**不要**再加「按本地频段表给新建 BUS_IN 初始化频段」（issue #13）：
                // 新建节点的 signalName 必为空（NodeGraph.addNode 不设名字），
                // 因此 `!node.signalName.isEmpty()` 这类条件永假——该分支曾经存在但不可达。
                // 它声称要修的「BUS_OUT 写入但 BUS_IN 读 0」实际由评估器的「注册表优先 key 查询」
                // 解决（求值不依赖节点自身的频段列表，见 GraphEvaluator）；节点自身那份列表是
                // **引脚结构**，由服务端唯一解析后下发（issue #11）。
                // Do NOT re-add a local-registry seeding for a new BUS_IN here (issue #13): a freshly
                // created node's signalName is always empty (NodeGraph.addNode assigns no name), so a
                // guard like `!node.signalName.isEmpty()` could never pass — the branch used to exist
                // but was unreachable. The "BUS_OUT writes but BUS_IN reads 0" problem it claimed to
                // fix is handled by the evaluator's registry-first key lookup (evaluation does not
                // depend on the node's own band list, see GraphEvaluator); the node's own list is the
                // *pin structure*, resolved once on the server and pushed to every editor (issue #11).
                // Undo of REMOVE_NODE: restore full node data from NBT snapshot stored in stringValue
                // REMOVE_NODE 撤销：从 stringValue 中存储的 NBT 快照恢复完整节点数据
                //
                // 条目 16 内容卫生（2026-10-31 拍板 = 登记册修复方向①）：快照由客户端构造、
                // 协议无签名无对账，K15 预检只覆盖 SET_* 三个 op——恢复路径在此自审三道门，
                // 任一门不过 = 跳过恢复留空壳（与 NBT 解析失败同口径，绝不抛出/断连）：
                //   ① 深度预扫描：{a:{a:…}} 深链若直达 TagParser，下面的 catch (Exception)
                //      接不住解析递归的 StackOverflowError（Error）——解析前先 O(n) 引号感知
                //      扫描，超 {@link #MAX_SNAPSHOT_DEPTH} 即跳过；
                //   ② 类型一致：快照必须含 "type" 且与 op.nodeType 一致（GraphNode.save 必写
                //      "type"，合法客户端天然满足；防 A 类快照灌进 B 类节点）；
                //   ③ 内容钳制：恢复体内部的控制点/帧/频段三条数组在 restoreNodeFromNbt 内
                //      钳到 op 级同值（K15 同源常量）。
                // Entry-16 content hygiene (2026-10-31 ruling = registry fix direction ①):
                // snapshots are client-constructed and the protocol is unsigned/unreconciled,
                // and the K15 pre-check only covers three SET_* ops — the restore path audits
                // three gates here; any failing gate skips the restore and leaves a bare shell
                // (same policy as an NBT parse failure — never throws, never disconnects):
                //   (1) depth pre-scan: a {a:{a:…}} chain fed straight to TagParser can overflow
                //       the parser's recursion, which the catch (Exception) below never catches
                //       (an Error is not an Exception) — an O(n) quote-aware scan runs first and
                //       skips restores beyond {@link #MAX_SNAPSHOT_DEPTH};
                //   (2) type match: the snapshot must carry "type" and match the op's nodeType
                //       (GraphNode.save always writes "type", so legitimate clients pass for
                //       free; blocks feeding an A-type snapshot into a B-type node);
                //   (3) content clamps: the three arrays inside the snapshot (control points /
                //       frames / bands) are clamped to op-level same values inside
                //       restoreNodeFromNbt (K15-sourced constants).
                if (op.stringValue() != null && !op.stringValue().isEmpty() && op.stringValue().charAt(0) == '{') {
                    String snapshot = op.stringValue();
                    if (snapshotNbtDepthExceeds(snapshot, MAX_SNAPSHOT_DEPTH)) {
                        LOGGER.debug(
                            "[OpExecutor] ADD_NODE snapshot restore skipped: nesting depth exceeds {} (node {})",
                            MAX_SNAPSHOT_DEPTH, op.targetNodeId());
                    } else {
                        try {
                            var nbtTag = net.minecraft.nbt.TagParser.parseTag(snapshot);
                            if (nbtTag instanceof net.minecraft.nbt.CompoundTag ct) {
                                // CUSTOM 哨兵下 type 恒为 "custom"，须再核 cid 一致 ——
                                // 防 A 节点的快照灌进 B id 的空壳（批 A 形状，批 D 名单制后仍有效）。
                                // Under the CUSTOM sentinel the type is always "custom", so the
                                // snapshot's cid must match too — blocks feeding node A's snapshot
                                // into a B-id shell (right shape in batch A, still valid after the
                                // batch-D roster).
                                boolean cidOk = node.type != NodeType.CUSTOM
                                    || (ct.contains("cid", net.minecraft.nbt.Tag.TAG_STRING) ? ct.getString("cid") : "").equals(node.customId);
                                if (cidOk
                                    && ct.contains("type", net.minecraft.nbt.Tag.TAG_STRING)
                                    && ct.getString("type").equals(node.type.id)) {
                                    restoreNodeFromNbt(node, ct);
                                } else {
                                    // 缺 "type" 键或与 op.nodeType 不一致 —— 不是合法的
                                    // GraphNode.save 产物，恢复整体跳过（空壳可被后续 op 填充）。
                                    // Missing "type" or mismatched with the op's nodeType — not a
                                    // legitimate GraphNode.save product; skip the whole restore
                                    // (the bare shell can be filled by subsequent ops).
                                    LOGGER.debug(
                                        "[OpExecutor] ADD_NODE snapshot restore skipped: type mismatch (snapshot '{}', op '{}', node {})",
                                        ct.contains("type", net.minecraft.nbt.Tag.TAG_STRING)
                                            ? ct.getString("type") : "<absent>",
                                        node.type.id, op.targetNodeId());
                                }
                            }
                        } catch (Exception ex) {
                            // NBT parse failure — node will be a bare shell; subsequent ops will fill in data
                            // NBT 解析失败 — 节点将是空壳；后续操作会填充数据
                        }
                    }
                }
                graph.rebuildNodeMap();
                yield node;
            }

            case ADD_NODE_REQUEST -> {
                // 仅服务端：使用服务端的 nextNodeId 创建节点（忽略客户端 tempId）。
                // Server only: create node with the server's nextNodeId (ignore client tempId).
                // customNodeId 随 op（见 ADD_NODE case 注）——CUSTOM 建点构造期需要。
                // customNodeId rides the op (see the ADD_NODE case) — needed at construction for CUSTOM.
                var node = graph.addNode(op.nodeType(), op.customNodeId(), op.x(), op.y());
                graph.rebuildNodeMap();
                yield node;  // 调用方发送 ACK 将 node.id 映射到 tempId  // caller sends ACK with node.id → tempId
            }

            case REMOVE_NODE -> {
                graph.removeNode(op.targetNodeId());
                yield null;
            }

            case MOVE_NODE -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null) {
                    float dx = Math.abs(n.x - op.x());
                    float dy = Math.abs(n.y - op.y());
                    if (animateMoves && (dx >= 2 || dy >= 2)) {
                        n.remoteStartX = n.x; n.remoteStartY = n.y;
                        n.remoteTargetX = op.x(); n.remoteTargetY = op.y();
                        n.remoteLerpT = 0f; // 启动平滑动画（客户端渲染循环） // start smooth animation (client render loop)
                    }
                    // 始终立即落地权威坐标 ——
                    // 客户端的插值纯粹是视觉上的（NodeRenderer 在插值期间读取 remote* 字段）。
                    // Always land the authoritative position immediately —
                    // the client lerp is purely visual (NodeRenderer reads remote* while lerping).
                    n.x = op.x(); n.y = op.y();
                    // 纯视觉 op：不 bump 代际。x/y 不进求值器，拖拽期间 20Hz 的 MOVE_NODE
                    // 若 bump 会让服务端每 op 全量重编译（recompileEvaluatorFull → runtimeState.clear()
                    // 时序清零）并让客户端 renderBg 重建全部展开节点的编辑区。
                    // ENCAP I/O 重排由调用方 rebuildInputCache()（其内部 anyIndexChanged 才 bump）兜底。
                    // Visual-only op: do NOT bump the generation. x/y never reach the evaluator;
                    // bumping here would make the server full-recompile (recompileEvaluatorFull →
                    // runtimeState.clear() wipes sequential state) and rebuild every expanded
                    // EditState on the client for every drag op (up to 20Hz). ENCAP I/O reorder is
                    // covered by the caller's rebuildInputCache(), which bumps only when indices change.
                }
                yield n;
            }

            case ADD_CONN -> {
                // pinId 编码存在（哪怕只有一端）→ 整条走 pinId 路径：缺的一端从当前节点补全，
                // 补不齐或解析不到/输入脚已占用则静默跳过（不整数回落，避免引脚漂移接错脚）。
                // 无 pinId 编码 → 整数引脚旧路径（同样有占用检查）。
                // Any pinId encoding (even one-sided) → pinId path for the whole wire: fill
                // the missing end from the live node; skip silently when incomplete,
                // unresolvable, or the input pin is occupied (no integer fallback — drifted
                // indices would land on the wrong pin). No encoding → legacy integer path
                // (same occupancy check).
                var pids = GraphOp.parseConnPinIds(op.stringValue());
                if (pids != null) {
                    String fPid = pids[0];
                    String tPid = pids[1];
                    if (fPid == null) {
                        var fn = graph.findNode(op.fromId());
                        if (fn != null) fPid = fn.outputPinId(op.fromPin());
                    }
                    if (tPid == null) {
                        var tn = graph.findNode(op.toId());
                        if (tn != null) tPid = tn.inputPinId(op.toPin());
                    }
                    if (fPid != null && tPid != null) {
                        graph.addConnectionWithPinIds(op.fromId(), fPid, op.toId(), tPid);
                    }
                    // 缺一端 / 解析失败 → 跳过，不覆盖 / incomplete or unresolvable → skip, never clobber
                } else {
                    graph.addConnection(op.fromId(), op.fromPin(), op.toId(), op.toPin());
                }
                yield null;
            }

            case REMOVE_CONN -> {
                // 同 ADD_CONN：有 pinId 编码就整条走 pinId（缺端从节点补全），否则整数路径。
                // Same as ADD_CONN: any pinId encoding routes the whole wire through pinIds
                // (missing end filled from the live node); otherwise the integer path.
                var pids = GraphOp.parseConnPinIds(op.stringValue());
                if (pids != null) {
                    String fPid = pids[0];
                    String tPid = pids[1];
                    if (fPid == null) {
                        var fn = graph.findNode(op.fromId());
                        if (fn != null) fPid = fn.outputPinId(op.fromPin());
                    }
                    if (tPid == null) {
                        var tn = graph.findNode(op.toId());
                        if (tn != null) tPid = tn.inputPinId(op.toPin());
                    }
                    if (fPid != null && tPid != null) {
                        graph.removeConnectionByPinIds(op.fromId(), fPid, op.toId(), tPid);
                    }
                } else {
                    graph.removeConnection(op.fromId(), op.fromPin(), op.toId(), op.toPin());
                }
                yield null;
            }

            case SET_PARAM -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null && op.paramIndex() >= 0 && op.paramIndex() < n.params.length) {
                    // 未变守卫：值相同不写、不 bumpGeneration。草稿-only op（清空/半截输入，
                    // stringValue 变、paramValue 不变）每键都会到达这里；bump 会触发服务端
                    // 全量重编译 + runtimeState.clear()，打断 DELAY/触发器/PID。
                    // Unchanged guard: same value skips the write and the generation bump.
                    // Draft-only ops (clear / partial text, stringValue changes while
                    // paramValue does not) arrive every keystroke; a bump forces a full
                    // recompile + runtimeState.clear() and wipes DELAY/flipflop/PID state.
                    if (Float.compare(n.params[op.paramIndex()], op.paramValue()) != 0) {
                        n.params[op.paramIndex()] = op.paramValue();
                        // CHANNEL 布局变化 → 引脚集合整体替换（布局表），失效输出连线交给
                        // rebuildInputCache 的 stale 剪枝收走（与 SET_BANDS 同路）。
                        // CHANNEL layout change → the pin set is replaced wholesale; stale
                        // output wires are pruned by rebuildInputCache (same path as SET_BANDS).
                        if (n.type == NodeType.CHANNEL) {
                            n.ensureChannelBands();
                            graph.rebuildInputCache();
                        }
                        graph.bumpGeneration();
                    }
                }
                yield n;
            }

            case SET_FORMULA -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.type == NodeType.FORMULA) {
                    n.formula = op.stringValue() != null ? op.stringValue() : "";
                    var res = FormulaParser.parseScript(n.formula);
                    n.cachedScript = res; // must set BEFORE rebuildInputCache — inputPinIndex depends on it
                    n.dynamicInputCount = res.inputVars.size();
                    n.dynamicOutputCount = Math.max(1, res.outputLabels.size());
                    n.outputLabels = res.outputLabels;
                    // 清理 pinId 不再存在的连接（v1.2.4: 通过 pinIndex 解析判断，而非 list.contains）
                    // Clean up connections whose pinId no longer resolves to a valid pin
                    // (v1.2.4: uses pinIndex resolution, not list.contains, to handle
                    //  "out0" vs "" for default output labels correctly)
                    graph.connections.removeIf(c -> {
                        if (c.toId == n.id) {
                            if (c.toPinId != null) return n.inputPinIndex(c.toPinId) < 0;
                            else return c.toPin >= n.inputs(); // legacy fallback
                        }
                        if (c.fromId == n.id) {
                            if (c.fromPinId != null) return n.outputPinIndex(c.fromPinId) < 0;
                            else return c.fromPin >= n.outputs(); // legacy fallback
                        }
                        return false;
                    });
                    graph.rebuildNodeMap();
                    graph.rebuildInputCache();
                    graph.bumpGeneration();
                } else if (n != null && n.type == NodeType.DEBUG_SIGNAL_GEN) {
                    // DEBUG_SIGNAL_GEN 自定义公式（单行表达式，用 FormulaParser.compile）
                    n.formula = op.stringValue() != null ? op.stringValue() : "";
                    n.debugFormulaRpn = null; // 失效编译缓存
                    graph.bumpGeneration();
                }
                yield n;
            }

            case SET_COMMENT_TEXT -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.type == NodeType.COMMENT) {
                    n.displayText = op.stringValue() != null ? op.stringValue() : "";
                    // 纯视觉 op（注释被求值器跳过）——不 bump / visual-only, evaluator skips COMMENT
                }
                yield n;
            }

            case SET_COMMENT_COLORS -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.type == NodeType.COMMENT) {
                    n.commentBgColor = op.colorBg();
                    n.commentBorderColor = op.colorBorder();
                    n.commentTextColor = op.colorText();
                    // 纯视觉 op——不 bump / visual-only
                }
                yield n;
            }

            case SET_COMMENT_SIZE -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.type == NodeType.COMMENT) {
                    if (op.x() > 0) n.commentWidth = op.x();
                    if (op.y() > 0) n.commentHeight = op.y();
                    // 纯视觉 op——不 bump / visual-only
                }
                yield n;
            }

            case SET_DISPLAY_TEXT -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null) {
                    n.displayText = op.stringValue() != null ? op.stringValue() : "";
                    // 同时更新 PRIVATE_IN/OUT/BUS/REDSTONE 节点的 signalName
                    // Also update signalName for PRIVATE_IN/OUT/BUS/REDSTONE nodes
                    if (n.type == NodeType.PRIVATE_IN || n.type == NodeType.PRIVATE_OUT
                        || n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT
                        || n.type == NodeType.REDSTONE_IN || n.type == NodeType.REDSTONE_OUT)
                        n.signalName = n.displayText;
                    // 频段列表的解析**刻意不在这里做**（issue #11）：改名后 BUS_IN 的新频段列表由
                    // 服务端**唯一解析**，并作为一条权威 SET_BANDS 下发给全部编辑者（含发起者）。
                    // 客户端与服务端因此都走同一条 SET_BANDS 应用路径，结果不可能分岔。
                    // Band-list resolution deliberately does NOT happen here (issue #11): after a
                    // rename, a BUS_IN's new list is resolved once on the server and delivered to
                    // every editor (including the originator) as an authoritative SET_BANDS. Both
                    // sides then apply it through the same SET_BANDS path, so they cannot diverge.
                    graph.bumpGeneration();
                }
                yield n;
            }

            case SET_TEXT_COLOR -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null) {
                    n.textColor = op.colorText();
                    graph.bumpGeneration();
                }
                yield n;
            }

            case SET_BANDS -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null && op.bands() != null) {
                    // 列表未变时不改写、不剪线、不 bump（issue #11）：改名后服务端会无条件下发一条
                    // 权威 SET_BANDS，此守卫让「本来就持有同一个值」的那些端成为无副作用的空操作。
                    // No-op when the list is unchanged (issue #11): a rename always pushes an
                    // authoritative SET_BANDS, and this guard makes sides that already hold the same
                    // value take no action at all.
                    if (!op.bands().equals(n.signalBands)) {
                        // 频段对齐唯一规则（NodeGraph.reconcileBands）：改名重绑 pinId 不剪线，
                        // 增删按名剪除 —— 与上传/收敛/客户端同步共用同一实现。
                        // The single band alignment rule: renames rebind pinIds (wires kept),
                        // additions/removals prune by name - shared with the upload,
                        // convergence and client-sync paths.
                        graph.reconcileBands(n, op.bands());
                        graph.rebuildInputCache(); // re-resolve pinIds (rename: same index; removal: caches refresh)
                        graph.bumpGeneration();
                    }
                }
                yield n;
            }

            case SET_BLOCK_NAME -> {
                // 图级 op：名称挂在 NodeGraph 上（随图序列化/同步），不属于任何节点。
                // 纯视觉 op —— 名称不参与求值，不 bump（与 SET_ZORDER 同理），避免无谓重编译。
                // Graph-level op: the name lives on the NodeGraph (serialized and synced
                // with it), not on any node. Visual-only — the name never feeds
                // evaluation, so no bump (same reasoning as SET_ZORDER) and hence no
                // pointless recompile.
                graph.customName = op.stringValue() != null ? op.stringValue() : "";
                yield null;
            }

            case SET_ZORDER -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null) {
                    n.sortB = op.sortB();
                    // 纯视觉 op（渲染层序）——不 bump / visual-only stacking, no bump
                }
                yield n;
            }

            case SET_LAYER_INDEX -> {
                // 显示器图层序：layerIndex 存于 sortB 字段；顺带维护 nextLayerIndex，
                // 保证后续新建节点仍排在最前（图层重排不再依赖整图上传同步该游标）。
                // Display layer index (value packed in sortB); also maintain nextLayerIndex
                // so future nodes still land in front without a whole-graph upload syncing it.
                var n = graph.findNode(op.targetNodeId());
                if (n != null) {
                    n.layerIndex = op.sortB();
                    if (op.sortB() >= graph.nextLayerIndex)
                        graph.nextLayerIndex = op.sortB() + 1;
                    graph.bumpGeneration();
                }
                yield n;
            }

            case SET_KEY_BINDING -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.params.length > 0) {
                    n.params[0] = op.keyIndex();
                    graph.bumpGeneration();
                }
                yield n;
            }

            case TOGGLE_BOOL -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.params.length > 0) {
                    // CUSTOM：paramIndex 选目标 BOOL 控件（schema 校验 kind==BOOL，非 BOOL
                    // 序位拒收）；内置类型维持 params[0] 惯例。
                    // CUSTOM: paramIndex selects the target BOOL control (schema-validated
                    // kind==BOOL, non-BOOL slots refused); built-ins keep the params[0] rule.
                    int idx = 0;
                    if (n.type == NodeType.CUSTOM) {
                        idx = customParamSlot(n, op.paramIndex(), CustomNodeType.CustomParam.ParamKind.BOOL);
                        if (idx < 0) yield n;
                    }
                    n.params[idx] = n.params[idx] > 0.5f ? 0 : 1;
                    graph.bumpGeneration();
                }
                yield n;
            }

            case SET_CUSTOM_TEXT -> {
                // 公开 API 波 1 批 C：自定义节点 TEXT 参数写入（paramIndex = schema 序位，
                // stringValue = 文本）。按 schema 校验 kind==TEXT；非 CUSTOM / 非 TEXT /
                // 序位越界一律拒收（fail-closed，不写入不 bump）。
                // Public-API wave 1 batch C: a custom node's TEXT param write (paramIndex =
                // the schema slot, stringValue = the text). Schema-validated kind==TEXT;
                // non-CUSTOM / non-TEXT / out-of-range slots are refused (fail-closed — no
                // write, no bump).
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.type == NodeType.CUSTOM) {
                    int slot = customParamSlot(n, op.paramIndex(), CustomNodeType.CustomParam.ParamKind.TEXT);
                    if (slot >= 0) {
                        String next = op.stringValue() != null ? op.stringValue() : "";
                        // 纵深防御 ①（K15 同款）：per-control 单条帽——包层预检已按同源常量
                        // REJECT，此处再拦（直连执行器的路径绕不过线缆帽时兜底）。
                        // Defense in depth ① (the K15 shape): the per-control cap — the
                        // packet pre-check already REJECTs on the same-source constant; this
                        // re-checks (a backstop for callers reaching the executor directly).
                        String key = CustomNodeRegistry.get(n.customId).params().get(slot).key();
                        if (next.length() > CustomNodeType.MAX_TEXT_PARAM_LENGTH) yield n;
                        // 纵深防御 ②：per-node 聚合帽（MAX_TEXT_TOTAL_PER_NODE，替换记账：
                        // 现有总量 − 旧文本 + 新文本）。超帽拒收：不写入、不 bump。
                        // Defense in depth ②: the per-node aggregate cap
                        // (MAX_TEXT_TOTAL_PER_NODE, replace-accounting: existing total −
                        // old text + new text). Over the cap: refused — no write, no bump.
                        int total = 0;
                        if (n.customTextParams != null)
                            for (var v : n.customTextParams.values()) total += v.length();
                        int oldLen = n.customTextParams != null && n.customTextParams.containsKey(key)
                            ? n.customTextParams.get(key).length() : 0;
                        if (total - oldLen + next.length() > MAX_TEXT_TOTAL_PER_NODE) yield n;
                        if (n.customTextParams == null) n.customTextParams = new java.util.HashMap<>();
                        n.customTextParams.put(key, next);
                        graph.bumpGeneration();
                    }
                }
                yield n;
            }

            case TOGGLE_CUR_STATE -> {
                // 只翻触发器的当前态镜像 params[1]（GATE/T_FLIPFLOP/LATCH 语义；其余类型的
                // params[1] 是普通参数，畸形 op 不得改写）。运行时 flipflopStates 的落地在
                // 服务端宿主 applyCurrentState（此处无运行时句柄）；不 bumpGeneration ——
                // params[1] 不参与求值（求值器每 tick 反向写它），纯显示/持久化镜像。
                // Flip only the trigger current-state mirror params[1] (GATE/T_FLIPFLOP/LATCH
                // semantics; on every other type params[1] is an ordinary parameter — a
                // malformed op must not rewrite it). Landing the runtime flipflopStates is
                // the server host's applyCurrentState (no runtime handle here). No
                // bumpGeneration — params[1] never feeds evaluation (the evaluator writes it
                // back each tick); it is a display/persistence mirror only.
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.params.length > 1
                    && (n.type == NodeType.GATE || n.type == NodeType.T_FLIPFLOP || n.type == NodeType.LATCH)) {
                    n.params[1] = n.params[1] > 0.5f ? 0 : 1;
                }
                yield n;
            }

            case SET_DISPLAY_LAYOUT -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null) {
                    // 远端拖拽平滑（与 MOVE_NODE 同款）：位置变化超过阈值时启动 smoothstep
                    // 插值（客户端每帧推进 layoutX/Y），权威坐标仍然立即落地；缩放/旋转不插值。
                    // Remote drag smoothing (same as MOVE_NODE): a position change past the
                    // threshold starts the smoothstep interpolation (the client advances
                    // layoutX/Y per frame) while the authoritative position still lands
                    // immediately; scale/rotation don't lerp.
                    float dx = Math.abs(n.layoutX - op.x());
                    float dy = Math.abs(n.layoutY - op.y());
                    if (animateMoves && (dx >= 0.005f || dy >= 0.005f)) {
                        n.layoutStartX = n.layoutX; n.layoutStartY = n.layoutY;
                        n.layoutTargetX = op.x(); n.layoutTargetY = op.y();
                        n.layoutLerpT = 0f;
                    }
                    n.layoutX = op.x();
                    n.layoutY = op.y();
                    n.displayScale = op.paramValue();
                    n.displayRotation = op.keyIndex() / 100f;
                    if (op.sortB() != 0) n.moveScale = op.sortB() / 10000f;
                    graph.bumpGeneration();
                }
                yield n;
            }

            case SET_IMAGE_FRAME_TOGGLE -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null && op.imageFrameIndex() >= 0 && n.params.length > 3 + op.imageFrameIndex()) {
                    int pi = 3 + op.imageFrameIndex();
                    n.params[pi] = n.params[pi] > 0.5f ? 0 : 1;
                    graph.bumpGeneration();
                }
                yield n;
            }

            case EXPAND_NODE -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null) n.expanded = true;
                yield n;
            }
            case COLLAPSE_NODE -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null) n.expanded = false;
                yield n;
            }

            case SET_HOTBAR_ITEM -> {
                var n = graph.findNode(op.targetNodeId());
                // K15 槽位上界：热栏实际 9 格（0..8），越界 = 拒绝（不扩容、不写入）；
                // 服务端在 handleServer 的 K15 预检中以 REJECT 回执，此处为纵深防御。
                // K15 slot cap: the hotbar is really 9 slots (0..8); beyond is refused (no
                // expansion, no write). The server answers such ops with a REJECT receipt via
                // the K15 pre-check in handleServer; this remains as defense in depth.
                // CUSTOM：hotbarSlot 承载 schema ITEM 控件序位（构造期已按 schema 预分配
                // itemParams，槽位界 = 实际长度；不扩容）。K15 静态预检在包层先行拦截。
                // CUSTOM: hotbarSlot carries the schema ITEM control slot (itemParams are
                // pre-sized from the schema at construction, so the bound is the actual
                // length; no expansion). The K15 static pre-check intercepts at the packet
                // layer first.
                int slotCap = n != null && n.type == NodeType.CUSTOM
                    ? (n.itemParams != null ? n.itemParams.length : 0) : HOTBAR_SLOTS;
                if (n != null && op.hotbarSlot() >= 0 && op.hotbarSlot() < slotCap) {
                    // Ensure itemParams is large enough; resize if needed (handles legacy nodes and edge cases)
                    // 确保 itemParams 足够大；必要时扩容（处理旧节点和边缘情况）
                    if (n.itemParams == null) n.itemParams = new ItemStack[0];
                    if (op.hotbarSlot() >= n.itemParams.length) {
                        ItemStack[] expanded = new ItemStack[op.hotbarSlot() + 1];
                        System.arraycopy(n.itemParams, 0, expanded, 0, n.itemParams.length);
                        for (int i = n.itemParams.length; i < expanded.length; i++)
                            expanded[i] = ItemStack.EMPTY;
                        n.itemParams = expanded;
                    }
                    n.itemParams[op.hotbarSlot()] = op.itemStack();
                    graph.bumpGeneration();
                }
                yield n;
            }
            case SET_IMAGE_PIXELS -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null && op.imageData() != null && op.imageData().length > 0) {
                    // K15 帧索引上界：paramIndex 承载 IMAGE_SEQUENCE 帧索引（见
                    // GraphOp.setImagePixels），越界（负值原会 IOOBE 崩 apply，超限原会按
                    // 声明索引无界扩容帧列表）一律视为拒绝输入 —— 节点原样不动；服务端在
                    // handleServer 的 K15 预检中以 REJECT 回执，此处为纵深防御。
                    // K15 frame-index cap: paramIndex carries the IMAGE_SEQUENCE frame index
                    // (see GraphOp.setImagePixels). Out of range (negative used to IOOBE-crash
                    // apply; oversized used to expand the frame list unbounded from the declared
                    // index) — treated as refused input, the node is left untouched. The server
                    // answers such ops with a REJECT receipt via the K15 pre-check in
                    // handleServer; this remains as defense in depth.
                    boolean frameIndexLegal = n.type != NodeType.IMAGE_SEQUENCE
                        || (op.paramIndex() >= 0 && op.paramIndex() < MAX_SEQUENCE_FRAMES);
                    if (frameIndexLegal) {
                        // 按节点画布尺寸规整像素数组（旧客户端/竞态可能送来长度不符的帧）
                        // Normalize pixel data to the node's canvas size (stale/racing peers may
                        // send mismatched lengths, which the renderer would otherwise skip).
                        int[] pixels = GraphNode.fitPixelArray(op.imageData(), n.imageWidth * n.imageHeight);
                        n.imagePixels = pixels;
                        if (n.type == NodeType.IMAGE_SEQUENCE) {
                            // Lazily initialize imageSequenceFrames if this is the first
                            // pixel data arriving (e.g. from remote ADD_NODE + flushCopyGroup).
                            // Without this, imageSequenceFrames stays null forever, causing
                            // save to skip frames and display to render nothing.
                            // 延迟初始化 imageSequenceFrames（首次像素数据到达时）。
                            // 否则 imageSequenceFrames 永远为 null，导致保存跳过帧且显示为空。
                            if (n.imageSequenceFrames == null) {
                                n.imageSequenceFrames = new java.util.ArrayList<>();
                            }
                            // Expand frames list to accommodate the incoming frame index
                            // 扩展帧列表以容纳传入的帧索引
                            int fi = op.paramIndex();
                            while (n.imageSequenceFrames.size() <= fi) {
                                int[] blank = new int[n.imageWidth * n.imageHeight];
                                java.util.Arrays.fill(blank, 0x00000000);
                                n.imageSequenceFrames.add(blank);
                            }
                            n.imageSequenceFrames.set(fi, pixels.clone());
                            // Re-link imagePixels to the current frame so painting works
                            // 将 imagePixels 重新链接到当前帧，确保绘制正常
                            if (fi >= 0 && fi < n.imageSequenceFrames.size())
                                n.imagePixels = n.imageSequenceFrames.get(fi);
                        }
                        graph.bumpGeneration();
                    }
                }
                yield n;
            }
            case SET_IMAGE_SIZE -> {
                // paramIndex=w, keyIndex=h（见 GraphOp.setImageSize）
                var n = graph.findNode(op.targetNodeId());
                if (n != null && (n.type == NodeType.IMAGE || n.type == NodeType.IMAGE_SEQUENCE)) {
                    int w = Math.max(1, Math.min(GraphNode.IMAGE_MAX_SIZE, op.paramIndex()));
                    int h = Math.max(1, Math.min(GraphNode.IMAGE_MAX_SIZE, op.keyIndex()));
                    if (w != n.imageWidth || h != n.imageHeight) {
                        GraphNode.resizeImagePixels(n, w, h);
                        graph.bumpGeneration();
                    }
                }
                yield n;
            }
            case REMOVE_IMAGE_FRAME -> {
                // 删除 IMAGE_SEQUENCE 帧；保留至少一帧（最后一帧清空而非删除）。
                // paramIndex=frameIndex（见 GraphOp.removeImageFrame）
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.type == NodeType.IMAGE_SEQUENCE && n.imageSequenceFrames != null
                    && !n.imageSequenceFrames.isEmpty()) {
                    int fi = Math.max(0, Math.min(n.imageSequenceFrames.size() - 1, op.paramIndex()));
                    if (n.imageSequenceFrames.size() > 1) {
                        n.imageSequenceFrames.remove(fi);
                    } else {
                        // 最后一帧：清空而不是删光，保证帧列表永不空
                        // Last frame: clear instead of removing, so the frame list never empties
                        int[] blank = new int[n.imageWidth * n.imageHeight];
                        java.util.Arrays.fill(blank, 0x00000000);
                        n.imageSequenceFrames.set(0, blank);
                    }
                    if (!n.imageSequenceFrames.isEmpty())
                        n.imagePixels = n.imageSequenceFrames.get(0);
                    graph.bumpGeneration();
                }
                yield n;
            }
            case MOVE_IMAGE_FRAME -> {
                // 重排 IMAGE_SEQUENCE 帧：remove(from) 后 insert(to)。
                // paramIndex=from, keyIndex=to（见 GraphOp.moveImageFrame）
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.type == NodeType.IMAGE_SEQUENCE && n.imageSequenceFrames != null) {
                    int from = op.paramIndex(), to = op.keyIndex();
                    int size = n.imageSequenceFrames.size();
                    if (from >= 0 && from < size && to >= 0 && to < size && from != to) {
                        int[] f = n.imageSequenceFrames.remove(from);
                        n.imageSequenceFrames.add(to, f);
                    }
                    graph.bumpGeneration();
                }
                yield n;
            }
            case SET_SONG -> {
                // 曲目写入/替换（plan §3.5 数据面）：字节从 BlobRegistry 按 blobRefId 取走，
                // 解析失败/超限拒存（保留原曲不炸节点，与节点 NBT 解析同口径）。
                // Song write/replace (plan §3.5): bytes are consumed from BlobRegistry by
                // blobRefId; oversized or malformed data is rejected (the old song stays,
                // the node never breaks — same policy as the node-NBT parse).
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.type == NodeType.MUSIC && op.blobRefId() != 0) {
                    byte[] data = blobStore.poll(op.blobRefId());
                    if (data != null && data.length <= NbsSong.MAX_BYTES) {
                        try {
                            n.song = NbsSong.read(data);
                            graph.bumpGeneration();
                        } catch (IllegalArgumentException ignored) {
                            // 坏数据：保留原曲 / malformed: keep the old song
                        }
                    }
                }
                yield n;
            }
            case SET_CTRL_POINTS -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.isCurveNode()) {
                    float[][] parsed = GraphOp.parseCtrlPoints(op.stringValue());
                    if (parsed != null) {
                        // 服务端按 X 排序，保证多人协作时点不会因竞态条件乱序
                        // Server-side X-sort: guarantees point order survives race conditions
                        sortByX(parsed[0], parsed[1]);
                        n.curveX = parsed[0];
                        n.curveY = parsed[1];
                        graph.bumpGeneration();
                    }
                }
                yield n;
            }

            // 这些在会话层 / UI 层处理，不在图层中处理：
            // These are handled at the session / UI layer, not the graph layer:
            case REJECT -> null;

            case ADD_BOOKMARK -> {
                String name = op.stringValue() != null ? op.stringValue() : "";
                graph.bookmarks.add(new NodeGraph.Bookmark(name, op.x(), op.y(), op.paramValue()));
                graph.bumpGeneration();
                yield null;
            }

            case REMOVE_BOOKMARK -> {
                int idx = op.targetNodeId();
                if (idx >= 0 && idx < graph.bookmarks.size()) {
                    graph.bookmarks.remove(idx);
                    graph.bumpGeneration();
                }
                yield null;
            }

            case RENAME_BOOKMARK -> {
                int idx = op.targetNodeId();
                String newName = op.stringValue() != null ? op.stringValue() : "";
                if (idx >= 0 && idx < graph.bookmarks.size() && !newName.isEmpty()) {
                    var old = graph.bookmarks.get(idx);
                    graph.bookmarks.set(idx, new NodeGraph.Bookmark(newName, old.camX(), old.camY(), old.zoom()));
                    graph.bumpGeneration();
                }
                yield null;
            }

            case MOVE_BOOKMARK -> {
                int fromIdx = op.targetNodeId();
                int toIdx = op.paramIndex();
                if (fromIdx >= 0 && fromIdx < graph.bookmarks.size()
                    && toIdx >= 0 && toIdx < graph.bookmarks.size() && fromIdx != toIdx) {
                    var bm = graph.bookmarks.remove(fromIdx);
                    graph.bookmarks.add(toIdx, bm);
                    graph.bumpGeneration();
                }
                yield null;
            }

            default -> null;
        };
    }

    /**
     * Restore node fields from an NBT snapshot (undo of REMOVE_NODE).
     * Does NOT restore itemParams (needs registries) or transient fields.
     * Connections are restored separately by the caller.
     * 从 NBT 快照恢复节点字段（REMOVE_NODE 撤销）。
     * 不恢复 itemParams（需要 registries）或 transient 字段。
     * 连线由调用方单独恢复。
     *
     * <p>P2-6：恢复清单补 {@code song}（MUSIC 曲目，NBS 字节直解，无需注册表）与
     * {@code subGraph}（ENCAPSULATION 嵌套 CompoundTag 原样经 {@link NodeGraph#load} 恢复，
     * 子节点物品需要 {@link #registryAccess}，未接线/解析失败时跳过 —— 快照里明明有数据，
     * 此前 Ctrl+Z 看似成功而整段封装/整首曲目静默丢失）。</p>
     * <p>P2-6: the restore list now covers {@code song} (MUSIC track — NBS bytes parsed
     * directly, no registries needed) and {@code subGraph} (the ENCAPSULATION nested
     * CompoundTag restored as-is via {@link NodeGraph#load}; sub-node ItemStacks need
     * {@link #registryAccess}, and an unwired/failed load is skipped). The snapshot always
     * carried the data — Ctrl+Z used to look successful while the whole encapsulation / whole
     * song was silently lost.</p>
     */
    /**
     * 条目 16 深度预扫描：SNBT 文本的**结构**花括号最大嵌套深度是否超限。
     * 引号感知 —— {@code "} 与 {@code '} 包裹的字符串值内、以及反斜杠转义的字符不计数
     * （注释/公式/信号名文本里的字面花括号是内容不是结构，误计会把合法快照错杀）。
     * 扫描器模拟的是 SNBT 词法本身：合法 SNBT 里它与解析器的结构判定一致，恶意输入
     * 要么合法（判断一致）要么解析失败（本就安全）——误差方向只会是多拒（fail-safe）。
     * <p>Entry-16 depth pre-scan: whether the maximum nesting depth of *structural* braces
     * in the SNBT text exceeds the cap. Quote-aware — braces inside {@code "}/{@code '}
     * strings and backslash-escaped characters do not count (literal braces in comment /
     * formula / signal-name text are content, not structure; counting them would wrongly
     * skip legitimate snapshots). The scanner mirrors SNBT lexing itself: on well-formed
     * SNBT it agrees with the parser's structural judgement, and malicious input is either
     * well-formed (verdict matches) or fails to parse (safe anyway) — the error direction
     * is over-rejection only, which fails safe.</p>
     *
     * @param snapshot the raw SNBT text / 原始 SNBT 文本
     * @param maxDepth depth cap to test against / 深度上限
     * @return true if structural nesting exceeds maxDepth / 结构嵌套超限返回 true
     */
    static boolean snapshotNbtDepthExceeds(String snapshot, int maxDepth) {
        int depth = 0;
        // charMode: 0 = 无字符串，1 = 双引号串，2 = 单引号串
        // charMode: 0 = outside strings, 1 = inside a double-quoted string, 2 = single-quoted
        int charMode = 0;
        for (int i = 0; i < snapshot.length(); i++) {
            char c = snapshot.charAt(i);
            if (charMode != 0) {
                if (c == '\\') { i++; continue; } // 转义字符连带其后一位一并跳过 / skip escaped char
                if ((charMode == 1 && c == '"') || (charMode == 2 && c == '\'')) charMode = 0;
                continue;
            }
            switch (c) {
                case '"' -> charMode = 1;
                case '\'' -> charMode = 2;
                case '{' -> { if (++depth > maxDepth) return true; }
                case '}' -> { if (depth > 0) depth--; }
                default -> { }
            }
        }
        return false;
    }

    /**
     * 自定义节点的参数序位校验：paramIndex 落在 schema 界内且 kind 匹配则返回该序位，
     * 否则 -1（调用方拒收）。schema 未解析 = -1。
     * Validates a custom node's param slot: returns the slot when paramIndex is in range
     * and the kind matches, otherwise -1 (callers refuse). An unresolved schema = -1.
     */
    private static int customParamSlot(GraphNode n, int paramIndex, CustomNodeType.CustomParam.ParamKind kind) {
        var spec = CustomNodeRegistry.get(n.customId);
        if (spec == null || paramIndex < 0 || paramIndex >= spec.params().size()) return -1;
        var p = spec.params().get(paramIndex);
        return p.kind() == kind ? paramIndex : -1;
    }

    private static void restoreNodeFromNbt(GraphNode node, net.minecraft.nbt.CompoundTag tag) {
        // params
        int pc = tag.getInt("pcount");
        for (int i = 0; i < pc && i < node.params.length; i++)
            node.params[i] = tag.getFloat("p" + i);
        // TEXT 参数存储（公开 API 波 1 批 C，审查批 (c)2）：撤销删除后回填——否则 Ctrl+Z
        // 看似成功而 TEXT 控件全部清空（P2-6 同族数据丢失面）。
        // TEXT param storage (wave 1 batch C, review (c)2): restored on undo-delete —
        // otherwise Ctrl+Z looks fine while every TEXT control comes back empty (the
        // P2-6 data-loss family).
        if (node.type == NodeType.CUSTOM && tag.contains("ctp", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            var ctp = tag.getCompound("ctp");
            node.customTextParams = new java.util.HashMap<>();
            for (var k : ctp.getAllKeys()) node.customTextParams.put(k, ctp.getString(k));
        }
        // itemParams 回填（先在病的同刀：restore 清单一直缺 icount/i{i}，REDSTONE 频率
        // 槽撤销后清空）。registryAccess 未接线（headless）= 跳过（空壳语义不变）。
        // itemParams restore (the same sweep over the pre-existing gap: the restore list
        // never read icount/i{i}, so REDSTONE frequency slots came back empty).
        // Unwired registryAccess (headless) = skipped (the bare-shell semantics hold).
        int ic = tag.getInt("icount");
        if (ic > 0 && registryAccess != null && node.itemParams.length == ic) {
            var restored = new ItemStack[ic];
            boolean ok = true;
            for (int i = 0; i < ic; i++) {
                if (!tag.contains("i" + i, net.minecraft.nbt.Tag.TAG_COMPOUND)) { ok = false; break; }
                restored[i] = ItemStack.parseOptional(registryAccess, tag.getCompound("i" + i));
            }
            if (ok) node.itemParams = restored;
        }
        // signal name
        if (tag.contains("sig")) node.signalName = tag.getString("sig");
        // signal bands —— 条数钳到 MAX_BANDS（条目 16：与线缆解码同源同值，防快照夹带任意长频段表）
        // signal bands — count clamped to MAX_BANDS (entry 16: same source and value as wire
        // decode, blocks smuggling an arbitrarily long band list via a snapshot)
        if (tag.contains("bands")) {
            var bandsTag = tag.getList("bands", net.minecraft.nbt.Tag.TAG_STRING);
            node.signalBands = new java.util.ArrayList<>();
            for (int i = 0; i < bandsTag.size() && i < MAX_BANDS; i++)
                node.signalBands.add(bandsTag.getString(i));
        }
        // BUS_OUT internalMap
        if (tag.contains("busData")) {
            var busData = tag.getCompound("busData");
            node.busInternalMap = new java.util.HashMap<>();
            for (String key : busData.getAllKeys())
                node.busInternalMap.put(key, busData.getFloat(key));
        }
        // formula — invalidate cachedScript so ensureScriptParsed re-parses on next use
        // (the formula may have changed via NBT sync / paste / collaboration, not via SET_FORMULA)
        // formula——使 cachedScript 失效，让 ensureScriptParsed 在下次使用时重新解析
        //（formula 可能通过 NBT 同步/粘贴/协作而非 SET_FORMULA 被修改）
        if (tag.contains("formula")) { node.formula = tag.getString("formula"); node.cachedScript = null; }
        // display text
        if (tag.contains("dtext")) node.displayText = tag.getString("dtext");
        // text color
        if (tag.contains("tcol")) node.textColor = tag.getInt("tcol");
        // comment geometry
        if (tag.contains("cw")) node.commentWidth = tag.getFloat("cw");
        if (tag.contains("ch")) node.commentHeight = tag.getFloat("ch");
        if (tag.contains("cbg")) node.commentBgColor = tag.getInt("cbg");
        if (tag.contains("cbr")) node.commentBorderColor = tag.getInt("cbr");
        if (tag.contains("ctx")) node.commentTextColor = tag.getInt("ctx");
        // image pixels
        if (tag.contains("ipx")) node.imagePixels = tag.getIntArray("ipx");
        if (tag.contains("iw")) node.imageWidth = Math.max(1, Math.min(GraphNode.IMAGE_MAX_SIZE, tag.getInt("iw")));
        if (tag.contains("ih")) node.imageHeight = Math.max(1, Math.min(GraphNode.IMAGE_MAX_SIZE, tag.getInt("ih")));
        // image sequence frames —— 帧数钳到 MAX_SEQUENCE_FRAMES（条目 16：K15 同值——帧仅能
        // 在像素编辑器逐帧添加，op 路径帧索引上限 64；单帧长度不受此钳，fixImagePixelsToSize
        // 已按 W×H 重排）。
        // image sequence frames — frame count clamped to MAX_SEQUENCE_FRAMES (entry 16: K15
        // same value — frames are added one by one in the pixel editor and the op path caps
        // the frame index at 64; per-frame length is not clamped here, fixImagePixelsToSize
        // already re-fits each to W×H).
        if (tag.contains("iframes")) {
            var framesTag = tag.getList("iframes", net.minecraft.nbt.Tag.TAG_INT_ARRAY);
            node.imageSequenceFrames = new java.util.ArrayList<>();
            for (int i = 0; i < framesTag.size() && i < MAX_SEQUENCE_FRAMES; i++)
                node.imageSequenceFrames.add(framesTag.getIntArray(i));
        }
        // 迁移保护：像素数组长度与 W×H 不符时重排（与 GraphNode.load 一致）
        // Migration guard: re-fit pixel arrays whose length disagrees with W×H (same as GraphNode.load)
        GraphNode.fixImagePixelsToSize(node);
        // display layout
        node.layoutX = tag.getFloat("lx");
        node.layoutY = tag.getFloat("ly");
        node.displayScale = tag.getFloat("ds");
        node.displayRotation = tag.getFloat("dr");
        if (tag.contains("ms")) node.moveScale = tag.getFloat("ms");
        // layer / z-order
        if (tag.contains("layer")) node.layerIndex = tag.getInt("layer");
        if (tag.contains("zb")) node.sortB = tag.getInt("zb");
        // expanded state
        if (tag.contains("expanded")) node.expanded = tag.getBoolean("expanded");
        // bus conflict
        if (tag.contains("busConflict")) node.busConflict = tag.getBoolean("busConflict");
        // dynamic input/output counts for FORMULA
        if (tag.contains("din")) node.dynamicInputCount = tag.getInt("din");
        if (tag.contains("dout")) node.dynamicOutputCount = tag.getInt("dout");
        if (tag.contains("outlbls")) {
            var lbls = tag.getList("outlbls", net.minecraft.nbt.Tag.TAG_STRING);
            node.outputLabels = new java.util.ArrayList<>();
            for (int i = 0; i < lbls.size(); i++)
                node.outputLabels.add(lbls.getString(i));
        }
        // DEBUG_SIGNAL_GEN control points —— 对数钳到 MAX_CTRL_POINTS（条目 16：K15 同值——
        // op 路径 parseCtrlPoints/K15 预检均钳 64 对，快照路径此前无界）。同时以两数组较短者
        // 为界：原实现循环以 dcx.length 为界直取 dcy[i]，dcy 更短的畸形快照会 AIOOBE 落入
        // 外层 catch（节点空壳）——钳制后按对数取 min，两数组长度不对称不再越界。
        // DEBUG_SIGNAL_GEN control points — pair count clamped to MAX_CTRL_POINTS (entry 16:
        // K15 same value — the op path caps at 64 pairs in both the K15 pre-check and
        // parseCtrlPoints; the snapshot path used to be unbounded). Also bounded by the
        // shorter of the two arrays: the old loop ran to dcx.length while indexing dcy[i],
        // so a malformed snapshot with a shorter dcy threw AIOOBE into the outer catch (bare
        // shell) — clamping to the pair count (min of both) removes the out-of-bounds.
        if (tag.contains("dcx") && tag.contains("dcy")) {
            int[] dcx = tag.getIntArray("dcx");
            int[] dcy = tag.getIntArray("dcy");
            int pairs = Math.min(Math.min(dcx.length, dcy.length), GraphOp.MAX_CTRL_POINTS);
            node.curveX = new float[pairs];
            node.curveY = new float[pairs];
            for (int i = 0; i < pairs; i++) {
                node.curveX[i] = Float.intBitsToFloat(dcx[i]);
                node.curveY[i] = Float.intBitsToFloat(dcy[i]);
            }
        }
        // P2-6：MUSIC 曲目恢复（NBS 字节 → NbsSong.read；无需注册表）。
        // P2-6: MUSIC song restore (NBS bytes → NbsSong.read; no registries needed).
        if (node.type == NodeType.MUSIC && tag.contains("song")) {
            try {
                node.song = NbsSong.read(tag.getByteArray("song"));
            } catch (RuntimeException e) {
                // 坏数据：保留新节点的空曲，不破坏节点（与 GraphNode.load 同口径）
                // Malformed: keep the fresh node's empty song, never break the node (same
                // policy as GraphNode.load)
            }
        }
        // P2-6：ENCAPSULATION 子图恢复（嵌套 CompoundTag 原样走 NodeGraph.load；
        // 子节点物品反序列化需要 registryAccess —— 网络层在应用 op 前注入，未接线或
        // 解析失败时保留空子图，节点不破坏）。
        // P2-6: ENCAPSULATION sub-graph restore (the nested CompoundTag goes through
        // NodeGraph.load as-is; deserializing sub-node ItemStacks needs registryAccess —
        // injected by the network layer before applying ops; an unwired or failed load keeps
        // an empty sub-graph and never breaks the node).
        if (node.type == NodeType.ENCAPSULATION && tag.contains("subGraph")) {
            try {
                node.subGraph = NodeGraph.load(tag.getCompound("subGraph"), registryAccess);
            } catch (RuntimeException e) {
                // 未接线注册表 / 坏数据：保留空子图（节点空壳可被后续 op 填充）
                // Unwired registry / malformed data: keep an empty sub-graph (the bare-shell
                // node can be filled by subsequent ops)
            }
        }
    }
}
