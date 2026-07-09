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
                h += io.github.y15173334444.create_schematic_compute.blocks.EditPanel
                    .calcRenderHeight(n, 1.0f);
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

    static float nwStatic(GraphNode n) {
        if (n.type == NodeType.COMMENT) return n.commentWidth;
        if (n.type == NodeType.FORMULA) return 240f; // WIDE_NW
        return 140f; // NW
    }

    static float nhStatic(GraphNode n) {
        if (n.type == NodeType.COMMENT) return n.commentHeight;
        return 18f + 16f * (n.functionalInputs() + n.outputs()); // HH + PH * rows
    }
}
