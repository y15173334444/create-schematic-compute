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
                    graph.bumpGeneration();
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
                    n.cachedScript = null;
                    var res = FormulaParser.parseScript(n.formula);
                    n.dynamicInputCount = res.inputVars.size();
                    n.dynamicOutputCount = Math.max(1, res.outputLabels.size());
                    n.outputLabels = res.outputLabels;
                    // 清理指向已移除引脚的连接
                    // Clean up connections to now-removed pins
                    graph.connections.removeIf(c ->
                        (c.toId == n.id && c.toPin >= n.inputs()) ||
                        (c.fromId == n.id && c.fromPin >= n.outputs()));
                    graph.rebuildNodeMap();
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
                    graph.bumpGeneration();
                }
                yield n;
            }

            case SET_COMMENT_COLORS -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.type == NodeType.COMMENT) {
                    n.commentBgColor = op.colorBg();
                    n.commentBorderColor = op.colorBorder();
                    n.commentTextColor = op.colorText();
                    graph.bumpGeneration();
                }
                yield n;
            }

            case SET_COMMENT_SIZE -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null && n.type == NodeType.COMMENT) {
                    if (op.x() > 0) n.commentWidth = op.x();
                    if (op.y() > 0) n.commentHeight = op.y();
                    graph.bumpGeneration();
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
                    graph.bumpGeneration();
                }
                yield n;
            }

            case SET_ZORDER -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null) {
                    n.sortB = op.sortB();
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
                    int[] pixels = op.imageData();
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
                            int[] blank = new int[256];
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
}
