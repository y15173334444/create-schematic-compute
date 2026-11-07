package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;

/** Central version tracking for all NBT data formats in this mod. */
public final class NbtVersions {
    /** Current data format version. Bump when breaking NBT changes are made.
     *  v9 (1.3.0) = typed pinId（类型参数段）+ bandTypes 声明表. */
    public static final int DATA_VERSION = 9;

    /** NBT key name for the version field. */
    public static final String VERSION_KEY = "data_version";

    private NbtVersions() {}

    /**
     * Read the format version from a tag.
     * Returns 0 if the key is absent (legacy save from before versioning existed).
     */
    public static int getVersion(CompoundTag tag) {
        return tag.contains(VERSION_KEY, net.minecraft.nbt.Tag.TAG_INT)
                ? tag.getInt(VERSION_KEY)
                : 0;
    }
}
