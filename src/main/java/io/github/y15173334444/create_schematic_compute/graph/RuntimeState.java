package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 所有逐节点运行时状态的可序列化快照。
 * 由每个 BlockEntity 持有，并传给 {@link GraphEvaluator#evaluate} 以支持
 * PID 积分、延时队列、触发器状态和脉冲计时器。
 * Serializable snapshot of all per-node runtime state.
 * Owned by each BlockEntity and passed to {@link GraphEvaluator#evaluate}
 * for PID integrals, delay queues, flipflop states, and pulse timers.
 *
 * <p>所有映射使用与原有逐 BlockEntity 映射相同的整数键：
 * {@code node.id}、{@code -(node.id+1)} 用于辅助槽位等。
 * All maps use the same integer keys as the pre-existing per-BlockEntity maps:
 * {@code node.id}, {@code -(node.id+1)} for secondary slots, etc.
 *
 * <p>子图状态（ENCAPSULATION 内的节点）存储在 {@link #subStates} 中，
 * 以封装节点 ID 为键，将 ID 与顶层节点隔离开来。
 * Sub-graph state (nodes inside ENCAPSULATION) is stored in {@link #subStates}
 * keyed by the encapsulation node ID, keeping IDs separate from top-level nodes.
 *
 * <p>所有浮点值统一使用 {@code Float}（32 位），因为 Minecraft NBT 仅原生支持
 * {@code FloatTag}，且 Create Schematic Compute 的图运算精度在 32 位范围内足够。
 * All floating-point values use {@code Float} (32-bit) because Minecraft NBT only
 * natively supports {@code FloatTag}, and graph computation precision is adequate
 * within 32-bit range.
 */
public class RuntimeState {

    private static final Logger LOGGER = LoggerFactory.getLogger(RuntimeState.class);

    // PID 积分、ACCUMULATOR 当前值、INTEGRATOR 值和 tick 计数器
    // PID integrals, ACCUMULATOR current values, INTEGRATOR values and tick counters
    // （两个构造器分别赋值：空态构造 / subView 共享视图构造）
    // (assigned by each constructor: the empty ctor / the subView shared-view ctor)
    public final Map<Integer, Float> pidState;

    // DELAY 节点逐 tick 队列
    // DELAY node per-tick queues
    public final Map<Integer, ArrayDeque<Float>> delayQueues = new HashMap<>();

    // LATCH、T_FLIPFLOP、GATE、LOOP、FUSE 布尔状态
    // LATCH, T_FLIPFLOP, GATE, LOOP, FUSE boolean states
    public final Map<Integer, Boolean> flipflopStates = new HashMap<>();

    // PULSE_EXTEND、LOOP、FUSE tick 计数器
    // PULSE_EXTEND, LOOP, FUSE tick counters
    public final Map<Integer, Integer> pulseTimers = new HashMap<>();

    // DEBUG_SIGNAL_GEN 相位状态（nodeId → 归一化时间 0~1）
    // DEBUG_SIGNAL_GEN phase state (nodeId → normalized time 0~1)
    public final Map<Integer, Float> debugTime = new HashMap<>();

    // ── 子图状态（ENCAPSULATION 节点）────────────────────────
    // 键：封装节点 ID。每个条目持有该 ENCAPSULATION 子图中时序/状态节点的状态映射。
    // ── Sub-graph state (ENCAPSULATION nodes) ────────────────────────
    // Key: encapsulation node ID. Each entry holds the state maps for
    // the timing/state nodes inside that ENCAPSULATION's sub-graph.

    /** 键：encapNodeId → 子节点状态映射 / Key: encapNodeId → sub-node state map */
    public final Map<Integer, SubState> subStates = new HashMap<>();

    /**
     * 一个 ENCAPSULATION 子图的运行时状态。
     * 每个子图拥有独立的状态集合，避免子图内节点 ID 与顶层节点 ID 冲突。
     * Runtime state for one ENCAPSULATION sub-graph.
     * Each sub-graph owns an independent state set, preventing ID collisions
     * between sub-graph internal nodes and top-level nodes.
     */
    public static class SubState {
        // PID/ACCUMULATOR/INTEGRATOR 累积值 / PID/ACCUMULATOR/INTEGRATOR accumulated values
        public final Map<Integer, Float> pidState = new HashMap<>();

        // DELAY 节点逐 tick 队列 / DELAY node per-tick queues
        public final Map<Integer, ArrayDeque<Float>> delayQueues = new HashMap<>();

        // LATCH、T_FLIPFLOP、GATE、LOOP、FUSE 布尔状态 / LATCH, T_FLIPFLOP, GATE, LOOP, FUSE boolean states
        public final Map<Integer, Boolean> flipflopStates = new HashMap<>();

        // PULSE_EXTEND、LOOP、FUSE tick 计数器 / PULSE_EXTEND, LOOP, FUSE tick counters
        public final Map<Integer, Integer> pulseTimers = new HashMap<>();

        // DEBUG_SIGNAL_GEN 相位状态（归一化时间 0~1） / DEBUG_SIGNAL_GEN phase state (normalized time 0~1)
        public final Map<Integer, Float> debugTime = new HashMap<>();

        // MOVE/ROTATE/WAIT/MUSIC 触点的上一 tick 电平（键=子图节点 id；MUSIC 停止沿用
        // id+100000 辅助槽位）。与顶层 {@link #nodeEdge} 同语义、同持久化口径（v2 起）——
        // 缺此槽时子求值器每 tick 把常高触发误判为新上升沿，封装内指令被反复重新入队
        // （v2"指令永不停止"事故的子图翻版，P1-4）。
        // Previous-tick trigger level for MOVE/ROTATE/WAIT/MUSIC (key = sub-graph node id;
        // MUSIC's stop edge uses the id+100000 auxiliary slot). Same semantics and the same
        // persistence contract as the top-level {@link #nodeEdge} (since v2) — without this
        // slot the sub-evaluator misjudges a held-high trigger as a fresh rising edge every
        // tick and re-enqueues encapsulated commands forever (the sub-graph replay of the
        // v2 "command never stops" incident, P1-4).
        public final Map<Integer, Boolean> nodeEdge = new HashMap<>();
    }

    /**
     * 构造一个空的 RuntimeState，所有映射初始化为空 HashMap。
     * Construct an empty RuntimeState; all maps are initialized as empty HashMaps.
     */
    public RuntimeState() {
        this.pidState = new HashMap<>();
        this.nodeEdge = new java.util.HashMap<>();
    }

    /**
     * 共享视图构造器：pidState/nodeEdge 直接引用传入映射（不复制）。
     * Shared-view constructor: pidState/nodeEdge reference the given maps directly (no copy).
     */
    private RuntimeState(Map<Integer, Float> pidState, java.util.Map<Integer, Boolean> nodeEdge) {
        this.pidState = pidState;
        this.nodeEdge = nodeEdge;
    }

    /**
     * 为封装子求值器构造共享视图（P1-4）：{@code pidState}/{@code nodeEdge} 与给定
     * {@link SubState} 是<b>同一映射</b>——子求值器的 MOVE/MUSIC 边沿记忆与 MUSIC seek
     * 槽位直接落在权威 SubState 表上，求值即持久化（save() 读的就是 SubState），无需
     * 求值后写回。其余映射（delayQueues/flipflopStates/pulseTimers/debugTime）不共享：
     * 它们分别由封装分支的局部表 + 写回路径与 saveDebugTimes/创建期拷贝管理，视图内
     * 保持空表。子图嵌套封装时，视图的 subStates 为内层提供独立命名空间（内存态，
     * NBT 持久化仍只覆盖第一层）。
     * Build a shared view for the encapsulated sub-evaluator (P1-4): {@code pidState}/
     * {@code nodeEdge} are the <b>same maps</b> as the given {@link SubState}'s — the
     * sub-evaluator's MOVE/MUSIC edge memory and the MUSIC seek slot land directly on the
     * authoritative SubState tables, so evaluating IS persisting (save() reads the
     * SubState) and no write-back is needed. The remaining maps are not shared: they are
     * managed by the encapsulation branch's local tables + write-back, and by
     * saveDebugTimes / the creation-time copy; they stay empty in the view. For nested
     * encapsulation the view's subStates give the inner level its own namespace
     * (in-memory only; NBT persistence still covers the first level).
     */
    public static RuntimeState subView(SubState ss) {
        return new RuntimeState(ss.pidState, ss.nodeEdge);
    }

    /**
     * 获取或创建封装节点的 SubState。
     * 使用 {@link Map#computeIfAbsent} 实现惰性初始化，仅在 ENCAPSULATION 节点
     * 首次参与图计算时才分配内部状态映射。
     * Get or create the SubState for an encapsulation node.
     * Uses {@link Map#computeIfAbsent} for lazy initialization — internal state maps
     * are only allocated when the ENCAPSULATION node first participates in graph evaluation.
     *
     * @param encapNodeId 封装节点的 ID / the ID of the encapsulation node
     * @return 该封装节点的 SubState（新建或已有） / the SubState for the encapsulation node (new or existing)
     */
    public SubState getOrCreateSubState(int encapNodeId) {
        return subStates.computeIfAbsent(encapNodeId, k -> new SubState());
    }

    /**
     * 手动切换触发器当前态（编辑区「当前状态」按钮，GATE / T_FLIPFLOP）：把新值写进
     * 主图或封装子图的 {@link #flipflopStates}。子图条目随写随建——编辑区可以在图从未
     * 运行过（评估器还没建 SubState）时就手动设态，缺条目时评估器会回退初始态、把切换吞掉。
     * Manual toggle of a trigger's current state (the edit-panel "Current state" button on
     * GATE / T_FLIPFLOP): writes the new value into the main or sub-graph
     * {@link #flipflopStates}. Sub-graph entries are created on demand — the edit panel can
     * set state before the graph ever ran (no evaluator-built SubState yet), and a missing
     * entry would make the evaluator fall back to the initial state, swallowing the toggle.
     *
     * @param ownerNodeId 主图传 -1，封装子图传封装节点 ID / -1 for the main graph,
     *                    the encapsulation node ID for a sub-graph
     * @param nodeId      触发器节点 ID（所在作用域内）/ trigger node ID within that scope
     * @param cur         新的当前态 / the new current state
     */
    public void putFlipflop(int ownerNodeId, int nodeId, boolean cur) {
        Map<Integer, Boolean> ff = ownerNodeId >= 0
            ? getOrCreateSubState(ownerNodeId).flipflopStates
            : flipflopStates;
        ff.put(nodeId, cur);
    }

    /**
     * 清除所有状态，用于新一轮图计算开始时接收合并后的新状态。
     * 在 {@link GraphEvaluator#evaluate} 返回后，BlockEntity 调用
     * {@code accept()} 方法将计算结果写入此对象之前，先清空旧数据。
     * Wipe all state before accepting fresh results from a new graph evaluation cycle.
     * After {@link GraphEvaluator#evaluate} returns, the BlockEntity clears old data
     * before its {@code accept()} method writes the computed results into this object.
     */
    public void clear() {
        pidState.clear();
        delayQueues.clear();
        flipflopStates.clear();
        pulseTimers.clear();
        debugTime.clear();
        subStates.clear();
        nodeEdge.clear();
    }

    /** MOVE/ROTATE/WAIT 触点的上一 tick 电平（键=节点 id）。上升沿检测用。
     *  持久化（v2 起）：触发电平记忆若在存档重载/离合翻转导致的实体重建后丢失，
     *  "常高"信号会被误判为新的上升沿 → 指令栈被反复重新入队（"输入指令后一直
     *  在转"事故根因）。持久化后，重载时保持原电平，常高信号只在真正拉低再拉高
     *  时触发。
     *  Previous-tick trigger level for MOVE/ROTATE/WAIT (key = node id) for rising-
     *  edge detection. Persisted (since v2): losing the level memory across saves or
     *  block-entity recreations (clutch flips) misjudges a held-high signal as a NEW
     *  rising edge and re-enqueues commands forever (the "command never stops" root
     *  cause). Persisted, a held-high signal only fires after a real low→high
     *  transition. */
    public final java.util.Map<Integer, Boolean> nodeEdge;

    /** 从存活节点 ID 构建完整键集（含辅助槽位：flipflop 的 -(id+1)、ACCUMULATOR/INTEGRATOR 的
     *  id+100000/id+200000、MUSIC 上次 seek 值的 id+300000）。
     *  Build the full key set from alive node IDs (incl. auxiliary slots: flipflop's
     *  -(id+1), ACCUMULATOR/INTEGRATOR's id+100000/id+200000, MUSIC last-seek value's
     *  id+300000). */
    public static java.util.Set<Integer> aliveStateKeys(java.util.Set<Integer> aliveIds) {
        java.util.Set<Integer> keys = new java.util.HashSet<>(aliveIds);
        for (int id : aliveIds) {
            keys.add(-(id + 1));
            keys.add(id + 100000);
            keys.add(id + 200000);
            keys.add(id + 300000);
        }
        return keys;
    }

    /**
     * 剪除已不再存活节点的运行时状态（含辅助槽位），保留其余节点的时序/积分状态。
     * 供重编译时使用——编辑（连线/加节点/改公式/改参数/注释）不再清空时序与积分，
     * 同时避免被删节点的状态泄漏进 NBT。
     * Prune runtime state of nodes no longer alive (including auxiliary slots), keeping
     * surviving nodes' sequential/integral state. Used by recompiles — edits (wiring,
     * adding nodes, formula/param/comment changes) no longer wipe timing & integrals,
     * while state of removed nodes is dropped so it never leaks into NBT.
     *
     * @param aliveIds 当前图中存活的节点 ID 集合 / the node IDs still present in the graph
     */
    public void pruneToAliveIds(java.util.Set<Integer> aliveIds) {
        java.util.Set<Integer> keys = aliveStateKeys(aliveIds);
        pidState.keySet().removeIf(k -> !keys.contains(k));
        delayQueues.keySet().removeIf(k -> !keys.contains(k));
        flipflopStates.keySet().removeIf(k -> !keys.contains(k));
        pulseTimers.keySet().removeIf(k -> !keys.contains(k));
        debugTime.keySet().removeIf(k -> !keys.contains(k));
        subStates.keySet().removeIf(k -> !aliveIds.contains(k));
        nodeEdge.keySet().removeIf(k -> !keys.contains(k));
    }

    /**
     * 把另一份状态的全部七类映射并入本状态（存档恢复的唯一入口）。
     * Merge all seven map categories from another state into this one — the single
     * entry point for save restore.
     *
     * <p>两线（继承线 {@code SyncedGraphBlockEntity} 与组合线 {@code GraphHost}）都必须
     * 经此方法恢复，不能再各自挑几项 putAll —— 阶段 0 之前正是这种"各挑几项"造成
     * Blueprint/ProgramComputer/Radar 与其余 BE 的恢复项各不相同。存盘侧
     * {@link #save()} 写的是全量，恢复侧漏项会让时序与触发电平静默归零。
     * Both lines (inheritance-based {@code SyncedGraphBlockEntity} and composition-based
     * {@code GraphHost}) must restore through this method instead of hand-picking a few
     * putAll calls — that per-BE cherry-picking is exactly what left Blueprint /
     * ProgramComputer / Radar restoring a different subset from every other BE before
     * phase 0. {@link #save()} persists the full payload, so a partial restore silently
     * zeroes timing and trigger-level state.
     *
     * <p>合并语义（不清空本状态）：已删除节点的陈旧条目无需在此剪除，重编译末尾的
     * {@link #pruneToAliveIds} 会按当前图清理。
     * Merging semantics (this state is not cleared): stale entries for deleted nodes need
     * no pruning here — {@link #pruneToAliveIds} at the tail of the next recompile cleans
     * them against the current graph.
     *
     * @param src 由 {@link #load} 从存档 NBT 重建的状态 / the state rebuilt by {@link #load} from save NBT
     */
    public void putAllFrom(RuntimeState src) {
        pidState.putAll(src.pidState);
        delayQueues.putAll(src.delayQueues);
        flipflopStates.putAll(src.flipflopStates);
        pulseTimers.putAll(src.pulseTimers);
        debugTime.putAll(src.debugTime);
        nodeEdge.putAll(src.nodeEdge);
        subStates.putAll(src.subStates);
    }

    // ── NBT 序列化 ──────────────────────────────────────────────────────
    // ── NBT serialisation ──────────────────────────────────────────────────

    /**
     * 将当前运行时状态序列化为一个 NBT {@link CompoundTag}。
     * 每个映射类别使用独立的子 CompoundTag（如 "pid"、"delay"、"ff"、"pt"、"dt"），
     * 键均为节点 ID 的字符串形式（因为 NBT CompoundTag 的键必须是 String）。
     * 子图状态嵌套在 "sub" 键下，每个封装节点 ID 对应其自身的状态 CompoundTag。
     * 空的映射会被跳过，以减小磁盘占用。
     * Serialise the current runtime state into an NBT {@link CompoundTag}.
     * Each map category uses a dedicated sub-CompoundTag (e.g. "pid", "delay", "ff",
     * "pt", "dt"), with keys as the string form of node IDs (because NBT CompoundTag
     * keys must be Strings). Sub-graph state is nested under the "sub" key, with
     * each encapsulation node ID mapping to its own state CompoundTag.
     * Empty maps are skipped to reduce on-disk footprint.
     *
     * @return 包含完整运行时状态的 CompoundTag / a CompoundTag containing the full runtime state
     */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();

        // PID 积分值 — 使用 String.valueOf(id) 作为 CompoundTag 键
        // PID integral values — use String.valueOf(id) as CompoundTag key
        if (!pidState.isEmpty()) {
            CompoundTag p = new CompoundTag();
            for (var e : pidState.entrySet())
                p.putFloat(String.valueOf(e.getKey()), e.getValue());
            tag.put("pid", p);
        }
        // 触发电平记忆（持久化语义见字段注释） / trigger level memory (see field doc)
        if (!nodeEdge.isEmpty()) {
            CompoundTag ne = new CompoundTag();
            for (var e : nodeEdge.entrySet())
                ne.putBoolean(String.valueOf(e.getKey()), e.getValue());
            tag.put("nodeEdge", ne);
        }
        if (!delayQueues.isEmpty()) {
            CompoundTag d = new CompoundTag();
            for (var e : delayQueues.entrySet()) {
                // 延时队列序列化为 FloatTag 列表以保持 FIFO 顺序
                // Delay queue serialised as FloatTag list to preserve FIFO order
                ListTag list = new ListTag();
                for (float f : e.getValue()) list.add(FloatTag.valueOf(f));
                d.put(String.valueOf(e.getKey()), list);
            }
            tag.put("delay", d);
        }
        if (!flipflopStates.isEmpty()) {
            CompoundTag f = new CompoundTag();
            for (var e : flipflopStates.entrySet())
                f.putBoolean(String.valueOf(e.getKey()), e.getValue());
            tag.put("ff", f);
        }
        if (!pulseTimers.isEmpty()) {
            CompoundTag pt = new CompoundTag();
            for (var e : pulseTimers.entrySet())
                pt.putInt(String.valueOf(e.getKey()), e.getValue());
            tag.put("pt", pt);
        }
        // DEBUG_SIGNAL_GEN 相位状态 / DEBUG_SIGNAL_GEN phase state
        if (!debugTime.isEmpty()) {
            CompoundTag dt = new CompoundTag();
            for (var e : debugTime.entrySet())
                dt.putFloat(String.valueOf(e.getKey()), e.getValue());
            tag.put("dt", dt);
        }
        // 子图状态 — 每个封装节点一个 CompoundTag
        // Sub-graph state — one CompoundTag per encapsulation node
        if (!subStates.isEmpty()) {
            CompoundTag sub = new CompoundTag();
            for (var entry : subStates.entrySet()) {
                CompoundTag ss = new CompoundTag();
                SubState s = entry.getValue();
                // 重复顶层结构的序列化模式，保持格式一致性
                // Repeat the top-level serialisation pattern for format consistency
                if (!s.pidState.isEmpty()) {
                    CompoundTag sp = new CompoundTag();
                    for (var e : s.pidState.entrySet()) sp.putFloat(String.valueOf(e.getKey()), e.getValue());
                    ss.put("pid", sp);
                }
                if (!s.delayQueues.isEmpty()) {
                    CompoundTag sd = new CompoundTag();
                    for (var e : s.delayQueues.entrySet()) {
                        ListTag list = new ListTag();
                        for (float f : e.getValue()) list.add(FloatTag.valueOf(f));
                        sd.put(String.valueOf(e.getKey()), list);
                    }
                    ss.put("delay", sd);
                }
                if (!s.flipflopStates.isEmpty()) {
                    CompoundTag sf = new CompoundTag();
                    for (var e : s.flipflopStates.entrySet()) sf.putBoolean(String.valueOf(e.getKey()), e.getValue());
                    ss.put("ff", sf);
                }
                if (!s.pulseTimers.isEmpty()) {
                    CompoundTag spt = new CompoundTag();
                    for (var e : s.pulseTimers.entrySet()) spt.putInt(String.valueOf(e.getKey()), e.getValue());
                    ss.put("pt", spt);
                }
                if (!s.debugTime.isEmpty()) {
                    CompoundTag sdt = new CompoundTag();
                    for (var e : s.debugTime.entrySet()) sdt.putFloat(String.valueOf(e.getKey()), e.getValue());
                    ss.put("dt", sdt);
                }
                // 子图触发电平记忆（与顶层 "nodeEdge" 键名一致，格式对称）
                // Sub-graph trigger level memory (same "nodeEdge" key name as the top level, symmetric format)
                if (!s.nodeEdge.isEmpty()) {
                    CompoundTag sne = new CompoundTag();
                    for (var e : s.nodeEdge.entrySet()) sne.putBoolean(String.valueOf(e.getKey()), e.getValue());
                    ss.put("nodeEdge", sne);
                }
                // 键 = 封装节点 ID 的字符串形式，与其他映射的键格式一致
                // Key = string form of encapsulation node ID, consistent with other map key formats
                sub.put(String.valueOf(entry.getKey()), ss);
            }
            tag.put("sub", sub);
        }
        return tag;
    }

    /**
     * F17: 坏档键解析兜底。NBT 的 CompoundTag 键是字符串（save 侧写
     * {@code String.valueOf(id)}），load 侧 {@code Integer.parseInt(k)} 无守卫——坏档里被
     * 改坏的字符串键（外部工具写档、手工编辑、版本混杂）会抛 NumberFormatException，
     * 直接炸掉整个区块加载。对照 {@link GraphNode#load} / {@code NbsSong.read} 的
     * 「不炸节点」坏档口径：逐键 try/catch，坏键跳过 + WARN，好键照常恢复。
     * 仅 load 侧；save 侧永远写合法键，save/load 对称性不受影响。
     * F17: corrupted-key parse backstop. NBT CompoundTag keys are strings (save writes
     * {@code String.valueOf(id)}); the load side used to call {@code Integer.parseInt(k)}
     * unguarded — a corrupted string key (external tooling, hand edits, mixed versions)
     * threw NumberFormatException straight through chunk loading. Aligned with
     * {@link GraphNode#load} / {@code NbsSong.read}'s never-throw corrupt-save contract:
     * per-key try/catch, skip the bad key with a WARN, restore good keys as usual.
     * Load-side only; save always writes valid keys, so save/load symmetry is unaffected.
     *
     * @param k 状态子 CompoundTag 的键（节点 ID 的字符串形式）/ a state sub-compound key (string form of a node ID)
     * @return 解析出的键；null 表示键损坏，调用方跳过该条目 / the parsed key; null means corrupt — the caller skips the entry
     */
    private static Integer parseNodeKey(String k) {
        try {
            return Integer.parseInt(k);
        } catch (NumberFormatException e) {
            LOGGER.warn("RuntimeState.load: skipping corrupt state key '{}' (not a node id)", k);
            return null;
        }
    }

    /**
     * 从 NBT {@link CompoundTag} 反序列化，重建一个完整的 RuntimeState。
     * 这是一个静态工厂方法，总是创建一个新的 {@link RuntimeState} 对象。
     * 各映射类别按其在 {@link #save()} 中写入的键名反向解析；
     * 缺失的键（即序列化时被跳过的空映射）会保持为初始的空 HashMap。
     * Deserialise an NBT {@link CompoundTag} back into a full RuntimeState.
     * This is a static factory method that always creates a new {@link RuntimeState}
     * object. Each map category is parsed in reverse by the same key names written
     * in {@link #save()}; missing keys (empty maps skipped during serialisation)
     * remain as their initial empty HashMap.
     *
     * @param tag 先前由 {@link #save()} 生成的 NBT CompoundTag /
     *            the NBT CompoundTag previously produced by {@link #save()}
     * @return 反序列化重建的 RuntimeState / a RuntimeState reconstructed from deserialisation
     */
    public static RuntimeState load(CompoundTag tag) {
        RuntimeState rs = new RuntimeState();

        if (tag.contains("pid")) {
            var p = tag.getCompound("pid");
            for (var k : p.getAllKeys()) {
                // 键从 String 解析回 Integer（节点 ID）；F17 坏键跳过 / parse key from String back to
                // Integer (node ID); F17 skips corrupt keys
                Integer id = parseNodeKey(k);
                if (id == null) continue;
                rs.pidState.put(id, p.getFloat(k));
            }
        }
        if (tag.contains("nodeEdge")) {
            var ne = tag.getCompound("nodeEdge");
            for (var k : ne.getAllKeys()) {
                Integer id = parseNodeKey(k);
                if (id == null) continue; // F17 坏键跳过 / F17 skip corrupt key
                rs.nodeEdge.put(id, ne.getBoolean(k));
            }
        }
        if (tag.contains("delay")) {
            var d = tag.getCompound("delay");
            for (var k : d.getAllKeys()) {
                Integer id = parseNodeKey(k);
                if (id == null) continue; // F17 坏键跳过 / F17 skip corrupt key
                // 反序列化 FloatTag 列表，按顺序重建 ArrayDeque（FIFO）以保持延时顺序
                // Deserialise FloatTag list; rebuild ArrayDeque in order (FIFO) to preserve delay sequence
                var list = d.getList(k, Tag.TAG_FLOAT);
                var q = new ArrayDeque<Float>(list.size());
                for (int i = 0; i < list.size(); i++)
                    q.addLast(list.getFloat(i));
                rs.delayQueues.put(id, q);
            }
        }
        if (tag.contains("ff")) {
            var f = tag.getCompound("ff");
            for (var k : f.getAllKeys()) {
                Integer id = parseNodeKey(k);
                if (id == null) continue; // F17 坏键跳过 / F17 skip corrupt key
                rs.flipflopStates.put(id, f.getBoolean(k));
            }
        }
        if (tag.contains("pt")) {
            var pt = tag.getCompound("pt");
            for (var k : pt.getAllKeys()) {
                Integer id = parseNodeKey(k);
                if (id == null) continue; // F17 坏键跳过 / F17 skip corrupt key
                rs.pulseTimers.put(id, pt.getInt(k));
            }
        }
        // DEBUG_SIGNAL_GEN 相位状态 / DEBUG_SIGNAL_GEN phase state
        if (tag.contains("dt")) {
            var dt = tag.getCompound("dt");
            for (var k : dt.getAllKeys()) {
                Integer id = parseNodeKey(k);
                if (id == null) continue; // F17 坏键跳过 / F17 skip corrupt key
                rs.debugTime.put(id, dt.getFloat(k));
            }
        }
        // 子图状态  /  Sub-graph state
        if (tag.contains("sub")) {
            var sub = tag.getCompound("sub");
            for (var k : sub.getAllKeys()) {
                // 封装节点 ID 必须从 String 键解析回 int；F17 坏键跳过整个子图条目
                // Encapsulation node ID must be parsed from String key back to int; F17 skips the
                // whole sub-graph entry on a corrupt key
                Integer encapId = parseNodeKey(k);
                if (encapId == null) continue;
                var ss = sub.getCompound(k);
                SubState s = new SubState();
                if (ss.contains("pid")) {
                    var sp = ss.getCompound("pid");
                    for (var sk : sp.getAllKeys()) {
                        Integer id = parseNodeKey(sk);
                        if (id == null) continue;
                        s.pidState.put(id, sp.getFloat(sk));
                    }
                }
                if (ss.contains("delay")) {
                    var sd = ss.getCompound("delay");
                    for (var sk : sd.getAllKeys()) {
                        Integer id = parseNodeKey(sk);
                        if (id == null) continue;
                        var list = sd.getList(sk, Tag.TAG_FLOAT);
                        var q = new ArrayDeque<Float>(list.size());
                        for (int i = 0; i < list.size(); i++) q.addLast(list.getFloat(i));
                        s.delayQueues.put(id, q);
                    }
                }
                if (ss.contains("ff")) {
                    var sf = ss.getCompound("ff");
                    for (var sk : sf.getAllKeys()) {
                        Integer id = parseNodeKey(sk);
                        if (id == null) continue;
                        s.flipflopStates.put(id, sf.getBoolean(sk));
                    }
                }
                if (ss.contains("pt")) {
                    var spt = ss.getCompound("pt");
                    for (var sk : spt.getAllKeys()) {
                        Integer id = parseNodeKey(sk);
                        if (id == null) continue;
                        s.pulseTimers.put(id, spt.getInt(sk));
                    }
                }
                if (ss.contains("dt")) {
                    var sdt = ss.getCompound("dt");
                    for (var sk : sdt.getAllKeys()) {
                        Integer id = parseNodeKey(sk);
                        if (id == null) continue;
                        s.debugTime.put(id, sdt.getFloat(sk));
                    }
                }
                if (ss.contains("nodeEdge")) {
                    var sne = ss.getCompound("nodeEdge");
                    for (var sk : sne.getAllKeys()) {
                        Integer id = parseNodeKey(sk);
                        if (id == null) continue;
                        s.nodeEdge.put(id, sne.getBoolean(sk));
                    }
                }
                rs.subStates.put(encapId, s);
            }
        }
        return rs;
    }
}
