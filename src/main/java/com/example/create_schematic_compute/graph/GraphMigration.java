package com.example.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Version-to-version NBT migration chain.
 * Each step transforms a graph tag from version N to N+1 by mutating NBT directly,
 * independent of the Java object model.
 */
public final class GraphMigration {

    @FunctionalInterface
    public interface Migrator {
        /** Transform tag from version {@code fromVer} to {@code fromVer + 1}. */
        CompoundTag migrate(CompoundTag tag);
    }

    /**
     * Ordered migration steps. {@code STEPS[0]} migrates v0→v1, {@code STEPS[1]} migrates v1→v2, etc.
     * Add new steps here when bumping DATA_VERSION.
     */
    private static final Migrator[] STEPS = {
            GraphMigration::migrateV0toV1,
            GraphMigration::migrateV1toV2,
    };

    /**
     * Bring {@code rawTag} up to the current {@link NbtVersions#DATA_VERSION}.
     * Returns a migrated copy (or the original if already current).
     */
    public static CompoundTag migrate(CompoundTag rawTag) {
        int ver = NbtVersions.getVersion(rawTag);
        // Already current — no migration needed
        if (ver >= NbtVersions.DATA_VERSION) return rawTag;

        CompoundTag tag = rawTag;
        while (ver < NbtVersions.DATA_VERSION) {
            int stepIdx = ver;
            if (stepIdx < STEPS.length) {
                tag = STEPS[stepIdx].migrate(tag);
            } else {
                break;
            }
            ver++;
        }
        return tag;
    }

    // ── V0 → V1 ───────────────────────────────────────────────────────────
    // Changes in v1:
    //   1. NodeType serialised as stable string id instead of enum ordinal
    //   2. Legacy "ms" (moveScale) folded into per-axis params[0]/params[1]
    //   3. "data_version": 1 added
    //   4. Recursive migration for ENCAPSULATION sub-graphs

    private static CompoundTag migrateV0toV1(CompoundTag tag) {
        CompoundTag out = tag.copy();

        ListTag nodes = out.getList("nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < nodes.size(); i++) {
            CompoundTag n = nodes.getCompound(i);

            // 1. Convert ordinal → string id
            int ordinal = n.getInt("type");
            NodeType type = NodeType.byOrdinalSafe(ordinal);
            if (type != null) {
                n.putString("type", type.id);
            }
            // If ordinal is invalid, leave the int as-is; loadCurrent will fall back to CONST

            // 2. Migrate legacy shared moveScale → per-axis params
            if (n.contains("ms")) {
                float ms = n.getFloat("ms");
                boolean isImage = type == NodeType.IMAGE || type == NodeType.IMAGE_SEQUENCE;
                if (isImage) {
                    if (n.getFloat("p0") == 0f) n.putFloat("p0", ms);
                    if (n.getFloat("p1") == 0f) n.putFloat("p1", ms);
                }
                n.remove("ms");
            }

            // 3. Recursively migrate subGraph (ENCAPSULATION nodes)
            if (n.contains("subGraph")) {
                n.put("subGraph", migrateV0toV1(n.getCompound("subGraph")));
            }
        }

        // 4. Stamp current version
        out.putInt(NbtVersions.VERSION_KEY, 1);
        return out;
    }

    // ── V1 → V2 ───────────────────────────────────────────────────────────
    // Changes in v2 (v1.1.5):
    //   1. LATCH node: old saves had params.length=0 (no "default" param),
    //      new saves have params[2] = {default, currentState}.
    //      Expand empty params to [0f, 0f] for old LATCH nodes.
    //   2. Recursive migration for ENCAPSULATION sub-graphs.

    private static CompoundTag migrateV1toV2(CompoundTag tag) {
        CompoundTag out = tag.copy();

        ListTag nodes = out.getList("nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < nodes.size(); i++) {
            CompoundTag n = nodes.getCompound(i);

            // 1. Expand LATCH params from old format (0 params) to new format (2 params)
            String typeId = n.getString("type");
            if ("latch".equals(typeId)) {
                int pc = n.getInt("pcount");
                if (pc == 0) {
                    n.putInt("pcount", 2);
                    n.putFloat("p0", 0f);
                    n.putFloat("p1", 0f);
                }
            }

            // 2. Recursively migrate subGraph (ENCAPSULATION nodes)
            if (n.contains("subGraph")) {
                n.put("subGraph", migrateV1toV2(n.getCompound("subGraph")));
            }
        }

        // 3. Stamp current version
        out.putInt(NbtVersions.VERSION_KEY, 2);
        return out;
    }
}
