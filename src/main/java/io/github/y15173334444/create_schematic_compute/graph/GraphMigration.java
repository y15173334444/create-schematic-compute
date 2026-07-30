package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Version-to-version NBT migration chain.
 * Each step transforms a graph tag from version N to N+1 by mutating NBT directly,
 * independent of the Java object model.
 *
 * 版本间 NBT 迁移链。
 * 每一步将一个图标签从版本 N 转换为版本 N+1，通过直接修改 NBT 实现，
 * 与 Java 对象模型解耦。
 */
public final class GraphMigration {

    /**
     * A single migration step that transforms a graph tag from version {@code fromVer}
     * to {@code fromVer + 1}.
     *
     * 单步迁移操作，将图标签从版本 {@code fromVer} 转换到 {@code fromVer + 1}。
     */
    @FunctionalInterface
    public interface Migrator {
        /**
         * Transform tag from version {@code fromVer} to {@code fromVer + 1}.
         *
         * 将标签从版本 {@code fromVer} 转换为 {@code fromVer + 1}。
         *
         * @param tag        the NBT tag to migrate / 待迁移的 NBT 标签
         * @param registries Minecraft holder lookup provider / Minecraft Holder 查找提供器
         * @return the migrated tag at the next version / 迁移到下一版本后的标签
         */
        CompoundTag migrate(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries);
    }

    /**
     * Ordered migration steps. {@code STEPS[0]} migrates v0→v1, {@code STEPS[1]} migrates v1→v2, etc.
     * Add new steps here when bumping {@link NbtVersions#DATA_VERSION}.
     *
     * 按顺序排列的迁移步骤。{@code STEPS[0]} 将 v0 迁移到 v1，{@code STEPS[1]} 将 v1 迁移到 v2，以此类推。
     * 当提升 {@link NbtVersions#DATA_VERSION} 时，在此处添加新的迁移步骤。
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
     *
     * 将 {@code rawTag} 升级到当前的 {@link NbtVersions#DATA_VERSION}。
     * 返回迁移后的副本（如果已经是最新版本则返回原始标签）。
     *
     * @param rawTag     the graph NBT tag to migrate / 待迁移的图 NBT 标签
     * @param registries Minecraft holder lookup provider / Minecraft Holder 查找提供器
     * @return the fully migrated tag / 完成所有迁移后的标签
     */
    public static CompoundTag migrate(CompoundTag rawTag, net.minecraft.core.HolderLookup.Provider registries) {
        int ver = NbtVersions.getVersion(rawTag);
        // Already current — no migration needed / 已经是最新版本，无需迁移
        if (ver >= NbtVersions.DATA_VERSION) return rawTag;

        CompoundTag tag = rawTag;
        // Step through each migration sequentially until reaching DATA_VERSION
        // 顺序执行每个迁移步骤，直到达到 DATA_VERSION
        while (ver < NbtVersions.DATA_VERSION) {
            int stepIdx = ver;
            if (stepIdx < STEPS.length) {
                tag = STEPS[stepIdx].migrate(tag, registries);
            } else {
                // No more registered steps — stop to avoid infinite loop
                // 没有更多已注册的迁移步骤——终止以避免无限循环
                break;
            }
            ver++;
        }
        return tag;
    }

    // ── V0 → V1 ───────────────────────────────────────────────────────────
    // Changes in v1 / v1 中的变更:
    //   1. NodeType serialised as stable string id instead of enum ordinal
    //      NodeType 序列化为稳定的字符串 id，替代枚举序号
    //   2. Legacy "ms" (moveScale) folded into per-axis params[0]/params[1]
    //      将旧的 "ms"（moveScale）折叠为各轴独立的 params[0]/params[1]
    //   3. "data_version": 1 added / 添加 "data_version": 1
    //   4. Recursive migration for ENCAPSULATION sub-graphs
    //      对 ENCAPSULATION 子图进行递归迁移

    /**
     * Migrate a graph tag from version 0 to version 1.
     *
     * 将图标签从版本 0 迁移到版本 1。
     *
     * @param tag        the v0 graph tag / v0 版本的图标签
     * @param registries Minecraft holder lookup provider / Minecraft Holder 查找提供器
     * @return the migrated v1 tag / 迁移后的 v1 标签
     */
    private static CompoundTag migrateV0toV1(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag out = tag.copy();

        ListTag nodes = out.getList("nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < nodes.size(); i++) {
            CompoundTag n = nodes.getCompound(i);

            // 1. Convert ordinal → string id / 将枚举序号转换为字符串 id
            int ordinal = n.getInt("type");
            NodeType type = NodeType.byOrdinalSafe(ordinal);
            if (type != null) {
                n.putString("type", type.id);
            }
            // If ordinal is invalid, leave the int as-is; loadCurrent will fall back to CONST
            // 如果序号无效，保持原样；loadCurrent 会回退到 CONST

            // 2. Migrate legacy shared moveScale → per-axis params
            //    将旧的共享 moveScale 迁移为各轴独立的参数
            if (n.contains("ms")) {
                float ms = n.getFloat("ms");
                boolean isImage = type == NodeType.IMAGE || type == NodeType.IMAGE_SEQUENCE;
                if (isImage) {
                    // Only overwrite if the per-axis value is still at default (0)
                    // 仅在各轴值仍为默认值（0）时才覆盖
                    if (n.getFloat("p0") == 0f) n.putFloat("p0", ms);
                    if (n.getFloat("p1") == 0f) n.putFloat("p1", ms);
                }
                n.remove("ms");
            }

            // 3. Recursively migrate subGraph (ENCAPSULATION nodes)
            //    递归迁移子图（ENCAPSULATION 节点）
            if (n.contains("subGraph")) {
                n.put("subGraph", migrateV0toV1(n.getCompound("subGraph"), registries));
            }
        }

        // 4. Stamp current version / 写入当前版本号
        out.putInt(NbtVersions.VERSION_KEY, 1);
        return out;
    }

    // ── V1 → V2 ───────────────────────────────────────────────────────────
    // Changes in v2 (v1.1.5) / v2 中的变更（v1.1.5）:
    //   1. LATCH node: old saves had params.length=0 (no "default" param),
    //      new saves have params[2] = {default, currentState}.
    //      Expand empty params to [0f, 0f] for old LATCH nodes.
    //      LATCH 节点：旧存档中 params.length=0（无 "default" 参数），
    //      新存档中 params[2] = {default, currentState}。
    //      将旧 LATCH 节点的空参数扩展为 [0f, 0f]。
    //   2. Recursive migration for ENCAPSULATION sub-graphs.
    //      对 ENCAPSULATION 子图进行递归迁移。

    /**
     * Migrate a graph tag from version 1 to version 2.
     *
     * 将图标签从版本 1 迁移到版本 2。
     *
     * @param tag        the v1 graph tag / v1 版本的图标签
     * @param registries Minecraft holder lookup provider / Minecraft Holder 查找提供器
     * @return the migrated v2 tag / 迁移后的 v2 标签
     */
    private static CompoundTag migrateV1toV2(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag out = tag.copy();

        ListTag nodes = out.getList("nodes", Tag.TAG_COMPOUND);
        for (int i = 0; i < nodes.size(); i++) {
            CompoundTag n = nodes.getCompound(i);

            // 1. Expand LATCH params from old format (0 params) to new format (2 params)
            //    将 LATCH 参数从旧格式（0 个参数）扩展为新格式（2 个参数）
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
            //    递归迁移子图（ENCAPSULATION 节点）
            if (n.contains("subGraph")) {
                n.put("subGraph", migrateV1toV2(n.getCompound("subGraph"), registries));
            }
        }

        // 3. Stamp current version / 写入当前版本号
        out.putInt(NbtVersions.VERSION_KEY, 2);
        return out;
    }

    // ── V2 → V3 ───────────────────────────────────────────────────────────
    // Changes in v3 / v3 中的变更:
    //   1. Add "zb" (sortB) field to all nodes with sequential values
    //      为所有节点添加 "zb"（sortB）字段，赋以顺序值
    //   2. Add "nextSortB" field to graph root
    //      在图根节点添加 "nextSortB" 字段
    //   3. Recursive migration for ENCAPSULATION sub-graphs
    //      对 ENCAPSULATION 子图进行递归迁移

    /**
     * Migrate a graph tag from version 2 to version 3.
     *
     * 将图标签从版本 2 迁移到版本 3。
     *
     * @param tag        the v2 graph tag / v2 版本的图标签
     * @param registries Minecraft holder lookup provider / Minecraft Holder 查找提供器
     * @return the migrated v3 tag / 迁移后的 v3 标签
     */
    private static CompoundTag migrateV2toV3(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag out = tag.copy();

        ListTag nodes = out.getList("nodes", Tag.TAG_COMPOUND);
        int idx = 0;
        for (int i = 0; i < nodes.size(); i++) {
            CompoundTag n = nodes.getCompound(i);

            // 1. Assign sequential sortB values (higher = newer = larger B)
            //    分配顺序 sortB 值（越高 = 越新 = 越大 B）
            if (!n.contains("zb")) {
                n.putInt("zb", idx++);
            }

            // 2. Recursively migrate subGraph (ENCAPSULATION nodes)
            //    递归迁移子图（ENCAPSULATION 节点）
            if (n.contains("subGraph")) {
                n.put("subGraph", migrateV2toV3(n.getCompound("subGraph"), registries));
            }
        }

        // 3. Set nextSortB on root graph so new nodes created after migration start
        //    beyond the highest assigned value
        //    在图根节点设置 nextSortB，使迁移后创建的新节点从已分配最大值之后开始
        if (!out.contains("nextSortB")) {
            out.putInt("nextSortB", idx);
        }

        // 4. Stamp current version / 写入当前版本号
        out.putInt(NbtVersions.VERSION_KEY, 3);
        return out;
    }

    // ── V3 → V4 ───────────────────────────────────────────────────────────
    // Changes in v4 (v1.2.4) / v4 中的变更（v1.2.4）:
    //   1. Connections gain stable pinId fields (fPinId, tPinId) derived from
    //      node type + pin index → pin name mapping.
    //      连接获得稳定的 pinId 字段（fPinId, tPinId），由"节点类型 + 引脚索引 → 引脚名称"映射派生而来。
    //   2. Recursive migration for ENCAPSULATION sub-graphs.
    //      对 ENCAPSULATION 子图进行递归迁移。
    //
    //   PinId derivation rules (mirrors GraphNode.inputPinId/outputPinId):
    //   PinId 派生规则（与 GraphNode.inputPinId/outputPinId 对应）：
    //     FORMULA input:  variable name from formula parse (e.g. "A", "B")
    //     FORMULA input:  公式解析出的变量名（如 "A"、"B"）
    //     FORMULA output: @output label (from outlbls tag), or "outN"
    //     FORMULA output: @output 标签（来自 outlbls 标签），或 "outN"
    //     ENCAP input:    String.valueOf(sub-node id) sorted by Y
    //     ENCAP input:    String.valueOf(子节点 id)，按 Y 排序
    //     ENCAP output:   String.valueOf(sub-node id) sorted by Y
    //     ENCAP output:   String.valueOf(子节点 id)，按 Y 排序
    //     BUS_IN output:  band name from signalBands
    //     BUS_IN output:  来自 signalBands 的频段名称
    //     BUS_OUT input:  band name from signalBands
    //     BUS_OUT input:  来自 signalBands 的频段名称
    //     generic:        String.valueOf(index)
    //     generic:        String.valueOf(索引)

    /**
     * Migrate a graph tag from version 3 to version 4.
     *
     * 将图标签从版本 3 迁移到版本 4。
     *
     * @param tag        the v3 graph tag / v3 版本的图标签
     * @param registries Minecraft holder lookup provider / Minecraft Holder 查找提供器
     * @return the migrated v4 tag / 迁移后的 v4 标签
     */
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
            // FORMULA 节点：确保脚本已解析，使 inputPinId/outputPinId 正常工作
            if (node.type == NodeType.FORMULA && !node.formula.isEmpty()) {
                node.ensureScriptParsed();
            }
            runtimeNodes.put(node.id, node);
        }

        // Migrate connections — use runtime pinId methods, drop if null
        // 迁移连接——使用运行时的 pinId 方法，如果为 null 则丢弃该连接
        ListTag conns = out.getList("conns", Tag.TAG_COMPOUND);
        var migratedConns = new java.util.ArrayList<CompoundTag>();
        for (int i = 0; i < conns.size(); i++) {
            CompoundTag c = conns.getCompound(i);
            // Already has pinId fields — keep as-is (idempotent re-migration)
            // 已有 pinId 字段——保持原样（幂等重迁移）
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

            // Resolve stable pin names from the runtime node graph; drop connections
            // whose pin indices reference pins that no longer exist
            // 从运行时节点图解析稳定的引脚名称；丢弃那些引脚索引指向已不再存在的引脚的连接
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

        // Recursively migrate sub-graphs (ENCAPSULATION nodes)
        // 递归迁移子图（ENCAPSULATION 节点）
        for (int i = 0; i < nodes.size(); i++) {
            CompoundTag n = nodes.getCompound(i);
            if (n.contains("subGraph")) {
                n.put("subGraph", migrateV3toV4(n.getCompound("subGraph"), registries));
            }
        }

        out.putInt(NbtVersions.VERSION_KEY, 4);
        return out;
    }

    /**
     * Convert a list of {@link CompoundTag} items into an NBT {@link ListTag}.
     *
     * 将 {@link CompoundTag} 列表转换为 NBT 的 {@link ListTag}。
     *
     * @param items the compound tags to pack / 待打包的复合标签列表
     * @return a new {@link ListTag} containing all items / 包含所有项的新 {@link ListTag}
     */
    private static ListTag listToTag(java.util.List<CompoundTag> items) {
        ListTag t = new ListTag();
        for (CompoundTag c : items) t.add(c);
        return t;
    }
}
