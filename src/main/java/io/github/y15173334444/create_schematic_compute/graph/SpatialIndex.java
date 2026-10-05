package io.github.y15173334444.create_schematic_compute.graph;

import java.util.*;

/**
 * Uniform grid-based spatial hash index for fast spatial queries on graph nodes.
 * Used by {@code GraphEditor} to accelerate {@code hitNode()}, {@code findNodeBelow()},
 * {@code moveContainedNodes()}, box-select, and {@code hitConn()}.
 *
 * <p>Rebuilt once per frame in {@code renderBg()} — O(n × avgCellsPerNode).</p>
 */
public class SpatialIndex {

    /** Grid cell size in graph-space pixels. */
    public static final int CELL_SIZE = 256;

    private final Map<Long, List<GraphNode>> cells = new HashMap<>();

    /** Pack two ints into a single long cell key. */
    private static long cellKey(int cx, int cy) {
        return ((long) cx << 32) | (cy & 0xFFFF_FFFFL);
    }

    /** Compute cell coordinate from a graph-space coordinate. */
    private static int cellCoord(float v) {
        return (int) Math.floor(v / CELL_SIZE);
    }

    /**
     * Full rebuild of the index from the current node list.
     * Called once per frame in {@code renderBg()} before any spatial queries.
     */
    public void build(List<GraphNode> nodes) {
        build(nodes, null);
    }

    /**
     * Full rebuild with expanded-edit-panel awareness.
     * Expanded BUS_IN/OUT nodes get their edit panel height added to the AABB
     * so that {@code queryPoint} returns them for clicks in the expanded area,
     * enabling correct z-ordering via {@code compareHitOrder}.
     */
    public void build(List<GraphNode> nodes, java.util.Set<Integer> expandedIds) {
        cells.clear();
        for (var n : nodes) {
            float w = n.type == NodeType.COMMENT ? n.commentWidth : nwStatic(n);
            float h = n.type == NodeType.COMMENT ? n.commentHeight : nhStatic(n);
            if (expandedIds != null && expandedIds.contains(n.id)) {
                // 与旧 calcRenderHeight(n, 1f) 等价的静态估算（本类即几何真相源）。
                // 动态多出的编辑行可能不在索引内——既有局限，非本入口能解。
                // The static estimate (this class is the geometry source). Extra dynamic
                // edit rows may miss the index - a pre-existing limit this entry cannot fix.
                h += editHeight(n);
            }
            int minCX = cellCoord(n.x);
            int minCY = cellCoord(n.y);
            int maxCX = cellCoord(n.x + w);
            int maxCY = cellCoord(n.y + h);
            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cy = minCY; cy <= maxCY; cy++) {
                    cells.computeIfAbsent(cellKey(cx, cy), k -> new ArrayList<>()).add(n);
                }
            }
        }
    }

    /**
     * Query all nodes whose AABB overlaps the given rectangle (in graph-space).
     * Results are deduplicated.
     */
    public List<GraphNode> queryRect(float x, float y, float w, float h) {
        int minCX = cellCoord(x);
        int minCY = cellCoord(y);
        int maxCX = cellCoord(x + w);
        int maxCY = cellCoord(y + h);
        List<GraphNode> result = new ArrayList<>();
        // For small query ranges, linear scan with an identity set is faster than a HashSet
        Set<GraphNode> seen = null;
        int expectedCells = (maxCX - minCX + 1) * (maxCY - minCY + 1);
        if (expectedCells > 1) seen = new HashSet<>();
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cy = minCY; cy <= maxCY; cy++) {
                List<GraphNode> bucket = cells.get(cellKey(cx, cy));
                if (bucket == null) continue;
                if (seen == null) {
                    // Single cell — no dedup needed
                    result.addAll(bucket);
                } else {
                    for (var n : bucket) {
                        if (seen.add(n)) result.add(n);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Query all nodes that cover the given point (in graph-space).
     */
    public List<GraphNode> queryPoint(float px, float py) {
        List<GraphNode> bucket = cells.get(cellKey(cellCoord(px), cellCoord(py)));
        if (bucket == null) return new ArrayList<>();
        return new ArrayList<>(bucket);
    }

    // — Static helpers for node dimensions (mirrors NodeRenderer.nw/nh) —

    /** 节点几何的**唯一权威**（渲染 NodeRenderer.nw/nh 委托到这里）——新增宽体/图表类节点
     *  只改此处，命中索引与渲染天然同步（曾有第二份手工镜像漏加曲线节点导致触摸宽度不符）。
     *  The single source of truth for node geometry (NodeRenderer.nw/nh delegate here) -
     *  add wide/chart node kinds here only; hit index and rendering stay in sync (a second
     *  hand-mirrored copy once missed the curve nodes and broke touch width). */
    public static float nwStatic(GraphNode n) {
        if (n.type == NodeType.COMMENT) return n.commentWidth;
        if (n.type == NodeType.FORMULA) return 240f; // = NodeRenderer.WIDE_NW
        if (n.type == NodeType.DEBUG_SIGNAL_GEN || n.type == NodeType.DEBUG_PROBE) return 240f;
        if (n.isCurveNode()) return 240f; // AMP/WSHAPE：曲线图表宽体 / curve chart wide body
        return 140f; // = NodeRenderer.NW
    }

    public static float nhStatic(GraphNode n) {
        if (n.type == NodeType.COMMENT) return n.commentHeight;
        float base = 18f + 16f * (n.functionalInputs() + n.outputs()); // = HH + PH * rows
        if (n.type == NodeType.DEBUG_SIGNAL_GEN) return base + 84f; // XY 图区域 / chart band
        if (n.type == NodeType.DEBUG_PROBE) return base + 64f;       // 数值 + 趋势图
        if (n.isCurveNode()) return base + 84f;                      // AMP/WSHAPE 曲线图区域
        return base;
    }

    /** 展开编辑区高度（图空间，无 EditState 的静态估算）：与 nwStatic/nhStatic 同为节点
     *  几何单一真相源，裁剪/命中/遮挡与编辑器渲染共用（原 EditPanel.calcRenderHeight 的
     *  纯几何部分）。/ Expanded edit-panel height (graph space, the static estimate without
     *  an EditState): alongside nwStatic/nhStatic the single node-geometry source, shared
     *  by culling/hit-tests/occlusion and the editor renderer (the pure-geometry part of
     *  the old EditPanel.calcRenderHeight). */
    public static int editHeight(GraphNode n) {
        return editHeight(n, -1, -1);
    }

    /** 展开编辑区高度（图空间）：{@code dynamicFieldCount} ≥ 0 覆盖 ACCUMULATOR/INTEGRATOR
     *  的动态字段行数（有 EditState 时的实测字段数）；{@code formulaContentHeight} ≥ 0 覆盖
     *  FORMULA 脚本框高度（实测内容高）。负值走静态估算分支。
     *  Expanded edit-panel height (graph space): dynamicFieldCount overrides the dynamic
     *  field-row count of ACCUMULATOR/INTEGRATOR (measured from an EditState);
     *  formulaContentHeight overrides the FORMULA script-box height (measured content
     *  height). Negative values take the static-estimate branches. */
    public static int editHeight(GraphNode n, int dynamicFieldCount, int formulaContentHeight) {
        if (n == null) return 0;
        int h = 6;
        if (n.type.paramNames.length > 0 && n.type != NodeType.BOOL && n.type != NodeType.GATE && n.type != NodeType.T_FLIPFLOP
            && n.type != NodeType.LATCH && n.type != NodeType.IMAGE && n.type != NodeType.IMAGE_SEQUENCE
            && n.type != NodeType.DEBUG_SIGNAL_GEN && n.type != NodeType.MOUSE_JOYSTICK
            && n.type != NodeType.TX_OUT && n.type != NodeType.SPEED_CTRL) {
            if (n.type == NodeType.KEYBOARD || n.type == NodeType.GAMEPAD_BUTTON) {
                h += 24;
            } else if (n.type == NodeType.ACCUMULATOR || n.type == NodeType.INTEGRATOR) {
                h += (dynamicFieldCount >= 0 ? dynamicFieldCount : n.params.length) * 18;
            } else h += n.type.editableParamCount() * 18;   // rev 等按钮参数不占 EditBox 行 / button-only params take no EditBox row
        }
        if (n.type == NodeType.BOOL && n.params.length > 0) h += 16;
        // 正/反转按钮（输出指令类）/ forward-reverse toggle (output-command nodes)
        if (n.type == NodeType.MOVE || n.type == NodeType.ROTATE
            || n.type == NodeType.TX_OUT || n.type == NodeType.SPEED_CTRL) h += 18;
        // v1.2.6 音频节点按钮行 + HUD 范围/间隔步进行 / audio-node button rows + HUD stepper rows
        if (n.type == NodeType.MUSIC) h += 18;            // loop 循环开关 / loop toggle
        if (n.type == NodeType.CHANNEL) h += 36;          // 布局预设两行（3+2）/ layout presets, two rows
        if (n.type == NodeType.HUD_PITCH_LADDER) h += 36; // 范围/间隔两行 / range & interval steppers
        if (n.type == NodeType.MOUSE_JOYSTICK && n.params.length > 0) h += 16;
        if ((n.type == NodeType.GATE || n.type == NodeType.T_FLIPFLOP || n.type == NodeType.LATCH) && n.params.length > 1) h += 32; // 初始按钮 + 当前只读
        if (n.type == NodeType.REDSTONE_IN || n.type == NodeType.REDSTONE_OUT) h += 32;
        if (n.type == NodeType.PRIVATE_IN || n.type == NodeType.PRIVATE_OUT) h += 22;
        if (n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT) {
            int bands = n.signalBands != null ? n.signalBands.size() : 0;
            h += 22 + bands * 18 + 20;
        }
        if (n.type == NodeType.COMMENT) {
            h += Math.round(n.commentHeight) - 12;
        }
        if (n.type == NodeType.FORMULA) {
            // 摘要行 + 参数行(warm 等,刀5) + 脚本编辑区高度 / summary + param rows (warm etc., knife 5) + script box height
            // 高度基于视觉行（折行感知）；实测框高由调用方量好传入 / height based on visual
            // lines (word-wrap aware); the measured box height arrives as a parameter
            int paramRows = n.type.editableParamCount();
            if (formulaContentHeight >= 0) {
                h += 22 + paramRows * 18 + formulaContentHeight + 12;
            } else {
                int lineCount = n.formula.isEmpty() ? 1 : Math.max(1, n.formula.split("\n", -1).length);
                h += 22 + paramRows * 18 + Math.max(1, Math.min(lineCount, 32)) * 12 + 12;
            }
        }
        if (n.type == NodeType.TEXT) h += 22;
        if (n.type == NodeType.DEBUG_SIGNAL_GEN) {
            // 模式切换行（始终可见）+ 条件 EditBox / mode toggle rows (always) + conditional EditBoxes
            h += 36; // 2 toggle rows: setMode + outMode
            int setMode = n.params.length > 0 ? (int) n.params[0] : 0;
            int outMode = n.params.length > 1 ? (int) n.params[1] : 0;
            if (setMode == DebugSignals.SET_FORMULA) h += 18; // formula EditBox
            if (setMode == DebugSignals.SET_MANUAL
                && outMode == DebugSignals.OUT_FREQ) h += 18; // speed (manual+frequency only)
            if (setMode == DebugSignals.SET_MANUAL) h += 18; // amplitude (manual only)
        }
        if (n.type == NodeType.IMAGE || n.type == NodeType.IMAGE_SEQUENCE) h += 54 + 36 + 32; // 3 move/rot fields + 2 canvas-size fields + 2 toggles
        if (n.type == NodeType.TEXT || n.type == NodeType.DATA) h += 22;
        if (n.type == NodeType.ENCAP_INPUT || n.type == NodeType.ENCAP_OUTPUT) h += 22;
        return h;
    }
}
