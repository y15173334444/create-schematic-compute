package io.github.y15173334444.create_schematic_compute.graph;

/**
 * Shared executor for {@link GraphOp} application.
 * Both server-side {@code applyOp} and client-side {@code onRemoteOp}
 * delegate to this class so mutation logic is defined in one place.
 *
 * <p>Validation (cycle checks, target existence, etc.) is the caller's
 * responsibility — this class performs the raw graph mutation only.</p>
 */
public final class OpExecutor {

    private OpExecutor() {}

    /**
     * Apply an op to the given graph. Returns the affected node for
     * operations that create or modify a single node, null otherwise.
     */
    public static GraphNode apply(NodeGraph graph, GraphOp op) {
        return switch (op.type()) {
            case ADD_NODE -> {
                var node = graph.addNode(op.nodeType(), op.x(), op.y());
                node.id = op.targetNodeId();
                graph.rebuildNodeMap(); // remap after ID override
                yield node;
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
                    if (dx < 2 && dy < 2) {
                        n.x = op.x(); n.y = op.y(); // local or tiny move
                    } else {
                        n.remoteStartX = n.x; n.remoteStartY = n.y;
                        n.remoteTargetX = op.x(); n.remoteTargetY = op.y();
                        n.remoteLerpT = 0f; // start smooth animation
                    }
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
                    // Also update signalName for PRIVATE_IN/OUT/BUS nodes
                    if (n.type == NodeType.PRIVATE_IN || n.type == NodeType.PRIVATE_OUT
                        || n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT)
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

            case SET_BANDS -> {
                var n = graph.findNode(op.targetNodeId());
                if (n != null) {
                    if (op.bands() != null) {
                        n.signalBands = new java.util.ArrayList<>(op.bands());
                    } else {
                        n.signalBands = null;
                    }
                    n.bandsDirty = true;
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
                if (n != null && n.params.length > 3 + op.imageFrameIndex()) {
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
                if (n != null && n.itemParams != null && op.hotbarSlot() < n.itemParams.length) {
                    n.itemParams[op.hotbarSlot()] = op.itemStack();
                    graph.bumpGeneration();
                }
                yield n;
            }
            // These are handled at the session / UI layer, not the graph layer:
            case ADD_NODE_REQUEST, ENCAP_IMPORT, REJECT -> null;

            default -> null;
        };
    }
}
