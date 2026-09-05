package io.github.y15173334444.create_schematic_compute.graph;

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

    private OpExecutor() {}

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
                var node = graph.addNode(op.nodeType(), op.x(), op.y());
                node.id = op.targetNodeId();
                // Newly placed BUS_IN has empty signalBands; if the channel already has a
                // band definition in the global registry (owned by a BUS_OUT, possibly in
                // another block), initialize the bands so this BUS_IN can read them right
                // away. Fixes "BUS_OUT writes but BUS_IN reads 0" when the BUS_IN is placed
                // after the channel definition exists.
                // 新建 BUS_IN 的 signalBands 为空；若频道在全局注册表中已有 band 定义
                // （由 BUS_OUT 拥有，可能在另一个方块），立即初始化 band，使此 BUS_IN
                // 马上能读取。修复频道定义已存在后才放置 BUS_IN 时的 "写入但读 0"。
                if (node.type == NodeType.BUS_IN && !node.signalName.isEmpty()) {
                    var gb = io.github.y15173334444.create_schematic_compute.network.SignalBus.getBands(node.signalName);
                    if (gb != null && !gb.isEmpty())
                        node.signalBands = new java.util.ArrayList<>(gb);
                }
                // Undo of REMOVE_NODE: restore full node data from NBT snapshot stored in stringValue
                // REMOVE_NODE 撤销：从 stringValue 中存储的 NBT 快照恢复完整节点数据
                if (op.stringValue() != null && !op.stringValue().isEmpty() && op.stringValue().charAt(0) == '{') {
                    try {
                        var nbtTag = net.minecraft.nbt.TagParser.parseTag(op.stringValue());
                        if (nbtTag instanceof net.minecraft.nbt.CompoundTag ct) {
                            restoreNodeFromNbt(node, ct);
                        }
                    } catch (Exception ex) {
                        // NBT parse failure — node will be a bare shell; subsequent ops will fill in data
                        // NBT 解析失败 — 节点将是空壳；后续操作会填充数据
                    }
                }
                graph.rebuildNodeMap();
                yield node;
            }

            case ADD_NODE_REQUEST -> {
                // 仅服务端：使用服务端的 nextNodeId 创建节点（忽略客户端 tempId）。
                // Server only: create node with the server's nextNodeId (ignore client tempId).
                var node = graph.addNode(op.nodeType(), op.x(), op.y());
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
                graph.addConnection(op.fromId(), op.fromPin(), op.toId(), op.toPin());
                yield null;
            }

            case REMOVE_CONN -> {
                graph.removeConnection(op.fromId(), op.fromPin(), op.toId(), op.toPin());
                yield null;
            }

            case SET_PARAM -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null && op.paramIndex() >= 0 && op.paramIndex() < n.params.length) {
                    n.params[op.paramIndex()] = op.paramValue();
                    graph.bumpGeneration();
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
                    String oldName = n.signalName;
                    n.displayText = op.stringValue() != null ? op.stringValue() : "";
                    // 同时更新 PRIVATE_IN/OUT/BUS/REDSTONE 节点的 signalName
                    // Also update signalName for PRIVATE_IN/OUT/BUS/REDSTONE nodes
                    if (n.type == NodeType.PRIVATE_IN || n.type == NodeType.PRIVATE_OUT
                        || n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT
                        || n.type == NodeType.REDSTONE_IN || n.type == NodeType.REDSTONE_OUT)
                        n.signalName = n.displayText;
                    // 改名 band 处理（与客户端 commitBusBox 一致）：
                    // - BUS_OUT：保留自身 band 与连线（用户期望改名不丢图）
                    // - BUS_IN：采用新频道的 band 定义（读取方需匹配频道 key 才能读到值）
                    // Rename band handling (consistent with client commitBusBox):
                    // - BUS_OUT: keep its own bands and connections (rename must not lose the graph)
                    // - BUS_IN: adopt the new channel's band definition (reader must match keys)
                    if ((n.type == NodeType.BUS_IN) && !n.signalName.isEmpty() && !n.signalName.equals(oldName)) {
                        boolean found = false;
                        for (var other : graph.nodes) {
                            if (other != n && other.signalName.equals(n.signalName)
                                && other.bandCount() > 0) {
                                n.signalBands = new java.util.ArrayList<>(other.signalBands);
                                found = true; break;
                            }
                        }
                        if (!found) {
                            var gb = io.github.y15173334444.create_schematic_compute.network.SignalBus.getBands(n.signalName);
                            n.signalBands = (gb != null && !gb.isEmpty())
                                ? new java.util.ArrayList<>(gb)
                                : new java.util.ArrayList<>();
                        }
                        n.bandsDirty = true;
                    }
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
                    n.signalBands = new java.util.ArrayList<>(op.bands());
                    n.bandsDirty = true;
                    graph.rebuildInputCache(); // prune connections to removed bands by pinId
                    graph.bumpGeneration();
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
                    n.params[0] = n.params[0] > 0.5f ? 0 : 1;
                    graph.bumpGeneration();
                }
                yield n;
            }

            case SET_DISPLAY_LAYOUT -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null) {
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
                if (n != null && op.hotbarSlot() >= 0) {
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
            case SET_CTRL_POINTS -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.type == NodeType.DEBUG_SIGNAL_GEN) {
                    float[][] parsed = GraphOp.parseCtrlPoints(op.stringValue());
                    if (parsed != null) {
                        // 服务端按 X 排序，保证多人协作时点不会因竞态条件乱序
                        // Server-side X-sort: guarantees point order survives race conditions
                        sortByX(parsed[0], parsed[1]);
                        n.debugCtrlX = parsed[0];
                        n.debugCtrlY = parsed[1];
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
     */
    private static void restoreNodeFromNbt(GraphNode node, net.minecraft.nbt.CompoundTag tag) {
        // params
        int pc = tag.getInt("pcount");
        for (int i = 0; i < pc && i < node.params.length; i++)
            node.params[i] = tag.getFloat("p" + i);
        // signal name
        if (tag.contains("sig")) node.signalName = tag.getString("sig");
        // signal bands
        if (tag.contains("bands")) {
            var bandsTag = tag.getList("bands", net.minecraft.nbt.Tag.TAG_STRING);
            node.signalBands = new java.util.ArrayList<>();
            for (int i = 0; i < bandsTag.size(); i++)
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
        // image sequence frames
        if (tag.contains("iframes")) {
            var framesTag = tag.getList("iframes", net.minecraft.nbt.Tag.TAG_INT_ARRAY);
            node.imageSequenceFrames = new java.util.ArrayList<>();
            for (int i = 0; i < framesTag.size(); i++)
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
        // DEBUG_SIGNAL_GEN control points
        if (tag.contains("dcx") && tag.contains("dcy")) {
            int[] dcx = tag.getIntArray("dcx");
            int[] dcy = tag.getIntArray("dcy");
            node.debugCtrlX = new float[dcx.length];
            node.debugCtrlY = new float[dcy.length];
            for (int i = 0; i < dcx.length; i++) {
                node.debugCtrlX[i] = Float.intBitsToFloat(dcx[i]);
                node.debugCtrlY[i] = Float.intBitsToFloat(dcy[i]);
            }
        }
    }
}
