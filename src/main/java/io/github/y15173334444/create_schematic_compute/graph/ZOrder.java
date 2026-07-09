package io.github.y15173334444.create_schematic_compute.graph;

/**
 * Three-level occlusion key: {@code A} = main layer, {@code B} = dynamic priority within a layer,
 * {@code C} = intra-node element order.
 *
 * <p>Comparison: {@code a} descending → {@code b} descending → {@code c} descending.
 * Higher values render later (on top).</p>
 */
public record ZOrder(int a, int b, int c) implements Comparable<ZOrder> {

    @Override
    public int compareTo(ZOrder o) {
        int cmp = Integer.compare(o.a, this.a); // descending
        if (cmp != 0) return cmp;
        cmp = Integer.compare(o.b, this.b);
        if (cmp != 0) return cmp;
        return Integer.compare(o.c, this.c);
    }
}
