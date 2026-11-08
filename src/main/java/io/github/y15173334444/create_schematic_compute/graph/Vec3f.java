package io.github.y15173334444.create_schematic_compute.graph;

/** 三分量向量载荷（{@link PinType#VEC3}）：float32 三分量，图内经 {@link PinPayload} 旁路、
 *  跨方块经 SignalBus 向量值表传输。不可变。
 *  <p>Three-component vector payload ({@link PinType#VEC3}): three float32 components,
 *  carried in-graph through the {@link PinPayload} side channel and cross-block through
 *  the SignalBus vector-value table. Immutable.</p>
 */
public record Vec3f(float x, float y, float z) implements PinPayload {

    /** 零向量 / the zero vector. */
    public static final Vec3f ZERO = new Vec3f(0f, 0f, 0f);

    public Vec3f add(Vec3f o) { return new Vec3f(x + o.x, y + o.y, z + o.z); }
    public Vec3f sub(Vec3f o) { return new Vec3f(x - o.x, y - o.y, z - o.z); }
    public Vec3f scale(float k) { return new Vec3f(x * k, y * k, z * k); }
    public float dot(Vec3f o) { return x * o.x + y * o.y + z * o.z; }
    public Vec3f cross(Vec3f o) {
        return new Vec3f(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }
    public float length() { return (float) Math.sqrt(x * x + y * y + z * z); }
    /** 归一化；零向量返回 {@link #ZERO}（不产生 NaN）/ normalize; the zero vector returns
     *  {@link #ZERO} (never produces NaN). */
    public Vec3f normalize() {
        float len = length();
        return len == 0f ? ZERO : scale(1f / len);
    }
}
