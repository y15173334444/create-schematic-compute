package io.github.y15173334444.create_schematic_compute.graph;

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
        CompoundTag migrate(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries);
    }

    /**
     * Ordered migration steps. {@code STEPS[0]} migrates v0→v1, {@code STEPS[1]} migrates v1→v2, etc.
     * Add new steps here when bumping DATA_VERSION.
     */
    private static final Migrator[] STEPS = {
            GraphMigration::migrateV0toV1,
            GraphMigration::migrateV1toV2,
            GraphMigration::migrateV2toV3,
            GraphMigration::migrateV3toV4,
    };

    /**
     * Bring {@code rawTag} up to the current {@link NbtVersions#DATA_VERSION}.
     * Returns a migrated copy (or the original if already current).
     */
    public static CompoundTag migrate(CompoundTag rawTag, net.minecraft.core.HolderLookup.Provider registries) {
        int ver = NbtVersions.getVersion(rawTag);
        // Already current — no migration needed
        if (ver >= NbtVersions.DATA_VERSION) return rawTag;

        CompoundTag tag = rawTag;
        while (ver < NbtVersions.DATA_VERSION) {
            int stepIdx = ver;
            if (stepIdx < STEPS.length) {
                tag = STEPS[stepIdx].migrate(tag, registries);
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

    private static CompoundTag migrateV0toV1(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
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
                n.put("subGraph", migrateV0toV1(n.getCompound("subGraph"), registries));
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

    private static CompoundTag migrateV1toV2(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
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
                n.put("subGraph", migrateV1toV2(n.getCompound("subGraph"), registries));
            }
        }

        // 3. Stamp current version
        out.putInt(NbtVersions.VERSION_KEY, 2);
        return out;
    }

    // ── V2 → V3 ───────────────────────────────────────────────────────────
    // Changes in v3:
    //   1. Add "zb" (sortB) field to all nodes with sequential values
    //   2. Add "nextSortB" field to graph root
    //   3. Recursive migration for ENCAPSULATION sub-graphs

    private static CompoundTag migrateV2toV3(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag out = tag.copy();

        ListTag nodes = out.getList("nodes", Tag.TAG_COMPOUND);
        int idx = 0;
        for (int i = 0; i < nodes.size(); i++) {
            CompoundTag n = nodes.getCompound(i);

            // 1. Assign sequential sortB values (higher = newer = larger B)
            if (!n.contains("zb")) {
                n.putInt("zb", idx++);
            }

            // 2. Recursively migrate subGraph (ENCAPSULATION nodes)
            if (n.contains("subGraph")) {
                n.put("subGraph", migrateV2toV3(n.getCompound("subGraph"), registries));
            }
        }

        // 3. Set nextSortB on root graph
        if (!out.contains("nextSortB")) {
            out.putInt("nextSortB", idx);
        }

        // 4. Stamp current version
        out.putInt(NbtVersions.VERSION_KEY, 3);
        return out;
    }

    // ── V3 → V4 ───────────────────────────────────────────────────────────
    // Changes in v4 (v1.2.4):
    //   1. Connections gain stable pinId fields (fPinId, tPinId) derived from
    //      node type + pin index → pin name mapping.
    //   2. Recursive migration for ENCAPSULATION sub-graphs.
    //
    //   PinId derivation rules (mirrors GraphNode.inputPinId/outputPinId):
    //     FORMULA input:  variable name from formula parse (e.g. "A", "B")
    //     FORMULA output: @output label (from outlbls tag), or "outN"
    //     ENCAP input:    String.valueOf(sub-node id) sorted by Y
    //     ENCAP output:   String.valueOf(sub-node id) sorted by Y
    //     BUS_IN output:  band name from signalBands
    //     BUS_OUT input:  band name from signalBands
    //     generic:        String.valueOf(index)

    private static CompoundTag migrateV3toV4(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag out = tag.copy();

        ListTag nodes = out.getList("nodes", Tag.TAG_COMPOUND);

        // Load all nodes via GraphNode.load so that pinId derivation uses the
        // same code path as the runtime (GraphNode.inputPinId/outputPinId).
        // This is the single source of truth — no independent re-parsing.
        // 通过 GraphNode.load 加载所有节点，使 pinId 推导与运行时使用同一代码路径
        //（GraphNode.inputPinId/outputPinId）。单一真相源——不再独立重新解析。
        var runtimeNodes = new java.util.HashMap<Integer, GraphNode>();
        for (int i = 0; i < nodes.size(); i++) {
            CompoundTag n = nodes.getCompound(i);
            GraphNode node = GraphNode.load(n, registries);
            // FORMULA nodes: ensure the script is parsed so inputPinId/outputPinId work
            if (node.type == NodeType.FORMULA && !node.formula.isEmpty()) {
                node.ensureScriptParsed();
            }
            runtimeNodes.put(node.id, node);
        }

        // Migrate connections — use runtime pinId methods, drop if null
        ListTag conns = out.getList("conns", Tag.TAG_COMPOUND);
        var migratedConns = new java.util.ArrayList<CompoundTag>();
        for (int i = 0; i < conns.size(); i++) {
            CompoundTag c = conns.getCompound(i);
            if (c.contains("fPinId") && c.contains("tPinId")) {
                migratedConns.add(c);
                continue;
            }

            int fromId = c.getInt("from");
            int fromPin = c.getInt("fPin");
            int toId = c.getInt("to");
            int toPin = c.getInt("tPin");

            GraphNode fromNode = runtimeNodes.get(fromId);
            GraphNode toNode = runtimeNodes.get(toId);

            boolean drop = false;
            if (fromNode != null) {
                String pid = fromNode.outputPinId(fromPin);
                if (pid == null) drop = true;
                else c.putString("fPinId", pid);
            }
            if (toNode != null) {
                String pid = toNode.inputPinId(toPin);
                if (pid == null) drop = true;
                else c.putString("tPinId", pid);
            }
            if (!drop) migratedConns.add(c);
        }
        out.put("conns", listToTag(migratedConns));

        // Recursively migrate sub-graphs
        for (int i = 0; i < nodes.size(); i++) {
            CompoundTag n = nodes.getCompound(i);
            if (n.contains("subGraph")) {
                n.put("subGraph", migrateV3toV4(n.getCompound("subGraph"), registries));
            }
        }

        out.putInt(NbtVersions.VERSION_KEY, 4);
        return out;
    }

    private static ListTag listToTag(java.util.List<CompoundTag> items) {
        ListTag t = new ListTag();
        for (CompoundTag c : items) t.add(c);
        return t;
    }
}
