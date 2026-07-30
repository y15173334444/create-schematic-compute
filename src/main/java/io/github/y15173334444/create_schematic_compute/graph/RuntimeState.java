package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

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

    // PID 积分、ACCUMULATOR 当前值、INTEGRATOR 值和 tick 计数器
    // PID integrals, ACCUMULATOR current values, INTEGRATOR values and tick counters
    public final Map<Integer, Float> pidState = new HashMap<>();

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
    }

    /**
     * 构造一个空的 RuntimeState，所有映射初始化为空 HashMap。
     * Construct an empty RuntimeState; all maps are initialized as empty HashMaps.
     */
    public RuntimeState() {}

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
                // 键 = 封装节点 ID 的字符串形式，与其他映射的键格式一致
                // Key = string form of encapsulation node ID, consistent with other map key formats
                sub.put(String.valueOf(entry.getKey()), ss);
            }
            tag.put("sub", sub);
        }
        return tag;
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
            for (var k : p.getAllKeys())
                // 键从 String 解析回 Integer（节点 ID）
                // Parse key from String back to Integer (node ID)
                rs.pidState.put(Integer.parseInt(k), p.getFloat(k));
        }
        if (tag.contains("delay")) {
            var d = tag.getCompound("delay");
            for (var k : d.getAllKeys()) {
                // 反序列化 FloatTag 列表，按顺序重建 ArrayDeque（FIFO）以保持延时顺序
                // Deserialise FloatTag list; rebuild ArrayDeque in order (FIFO) to preserve delay sequence
                var list = d.getList(k, Tag.TAG_FLOAT);
                var q = new ArrayDeque<Float>(list.size());
                for (int i = 0; i < list.size(); i++)
                    q.addLast(list.getFloat(i));
                rs.delayQueues.put(Integer.parseInt(k), q);
            }
        }
        if (tag.contains("ff")) {
            var f = tag.getCompound("ff");
            for (var k : f.getAllKeys())
                rs.flipflopStates.put(Integer.parseInt(k), f.getBoolean(k));
        }
        if (tag.contains("pt")) {
            var pt = tag.getCompound("pt");
            for (var k : pt.getAllKeys())
                rs.pulseTimers.put(Integer.parseInt(k), pt.getInt(k));
        }
        // DEBUG_SIGNAL_GEN 相位状态 / DEBUG_SIGNAL_GEN phase state
        if (tag.contains("dt")) {
            var dt = tag.getCompound("dt");
            for (var k : dt.getAllKeys())
                rs.debugTime.put(Integer.parseInt(k), dt.getFloat(k));
        }
        // 子图状态  /  Sub-graph state
        if (tag.contains("sub")) {
            var sub = tag.getCompound("sub");
            for (var k : sub.getAllKeys()) {
                // 封装节点 ID 必须从 String 键解析回 int
                // Encapsulation node ID must be parsed from String key back to int
                int encapId = Integer.parseInt(k);
                var ss = sub.getCompound(k);
                SubState s = new SubState();
                if (ss.contains("pid")) {
                    var sp = ss.getCompound("pid");
                    for (var sk : sp.getAllKeys()) s.pidState.put(Integer.parseInt(sk), sp.getFloat(sk));
                }
                if (ss.contains("delay")) {
                    var sd = ss.getCompound("delay");
                    for (var sk : sd.getAllKeys()) {
                        var list = sd.getList(sk, Tag.TAG_FLOAT);
                        var q = new ArrayDeque<Float>(list.size());
                        for (int i = 0; i < list.size(); i++) q.addLast(list.getFloat(i));
                        s.delayQueues.put(Integer.parseInt(sk), q);
                    }
                }
                if (ss.contains("ff")) {
                    var sf = ss.getCompound("ff");
                    for (var sk : sf.getAllKeys()) s.flipflopStates.put(Integer.parseInt(sk), sf.getBoolean(sk));
                }
                if (ss.contains("pt")) {
                    var spt = ss.getCompound("pt");
                    for (var sk : spt.getAllKeys()) s.pulseTimers.put(Integer.parseInt(sk), spt.getInt(sk));
                }
                if (ss.contains("dt")) {
                    var sdt = ss.getCompound("dt");
                    for (var sk : sdt.getAllKeys()) s.debugTime.put(Integer.parseInt(sk), sdt.getFloat(sk));
                }
                rs.subStates.put(encapId, s);
            }
        }
        return rs;
    }
}
