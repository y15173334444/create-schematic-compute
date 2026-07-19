package io.github.y15173334444.create_schematic_compute.graph;

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
                if (n != null && n.itemParams != null && op.hotbarSlot() >= 0 && op.hotbarSlot() < n.itemParams.length) {
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
                    if (n.type == NodeType.IMAGE_SEQUENCE && n.imageSequenceFrames != null
                        && op.paramIndex() >= 0 && op.paramIndex() < n.imageSequenceFrames.size())
                        n.imageSequenceFrames.set(op.paramIndex(), pixels.clone());
                    graph.bumpGeneration();
                }
                yield n;
            }
            // 这些在会话层 / UI 层处理，不在图层中处理：
            // These are handled at the session / UI layer, not the graph layer:
            case REJECT -> null;

            default -> null;
        };
    }
}
