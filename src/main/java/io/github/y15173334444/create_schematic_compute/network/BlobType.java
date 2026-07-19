package io.github.y15173334444.create_schematic_compute.network;

/** Blob data type identifiers for {@link BlobDataPacket}. */
public enum BlobType {
    IMAGE_PIXELS(0),      // int[] pixel data
    ITEMSTACK_NBT(1),     // ItemStack NBT
    RAW_BYTES(2);         // extension point

    public final int id;

    BlobType(int id) { this.id = id; }

    public static BlobType fromId(int id) {
        for (var t : values()) if (t.id == id) return t;
        return RAW_BYTES;
    }
}
