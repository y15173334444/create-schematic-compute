package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Serializable snapshot of all per-node runtime state.
 * Owned by each BlockEntity and passed to {@link GraphEvaluator#evaluate}
 * for PID integrals, delay queues, flipflop states, and pulse timers.
 *
 * <p>All maps use the same integer keys as the pre-existing per-BlockEntity maps:
 * {@code node.id}, {@code -(node.id+1)} for secondary slots, etc.
 *
 * <p>Sub-graph state (nodes inside ENCAPSULATION) is stored in {@link #subStates}
 * keyed by the encapsulation node ID, keeping IDs separate from top-level nodes.
 */
public class RuntimeState {

    // PID integrals, ACCUMULATOR current values, INTEGRATOR values and tick counters
    public final Map<Integer, Float> pidState = new HashMap<>();

    // DELAY node per-tick queues
    public final Map<Integer, ArrayDeque<Float>> delayQueues = new HashMap<>();

    // LATCH, T_FLIPFLOP, GATE, LOOP, FUSE boolean states
    public final Map<Integer, Boolean> flipflopStates = new HashMap<>();

    // PULSE_EXTEND, LOOP, FUSE tick counters
    public final Map<Integer, Integer> pulseTimers = new HashMap<>();

    // ── Sub-graph state (ENCAPSULATION nodes) ────────────────────────
    // Key: encapsulation node ID. Each entry holds the state maps for
    // the timing/state nodes inside that ENCAPSULATION's sub-graph.

    /** Key: encapNodeId → sub-node state map */
    public final Map<Integer, SubState> subStates = new HashMap<>();

    /** Runtime state for one ENCAPSULATION sub-graph. */
    public static class SubState {
        public final Map<Integer, Float> pidState = new HashMap<>();
        public final Map<Integer, ArrayDeque<Float>> delayQueues = new HashMap<>();
        public final Map<Integer, Boolean> flipflopStates = new HashMap<>();
        public final Map<Integer, Integer> pulseTimers = new HashMap<>();
    }

    public RuntimeState() {}

    /** Get or create the SubState for an encapsulation node. */
    public SubState getOrCreateSubState(int encapNodeId) {
        return subStates.computeIfAbsent(encapNodeId, k -> new SubState());
    }

    /** Wipe all state (used on {@code accept()} merge). */
    public void clear() {
        pidState.clear();
        delayQueues.clear();
        flipflopStates.clear();
        pulseTimers.clear();
        subStates.clear();
    }

    // ── NBT serialisation ──────────────────────────────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();

        if (!pidState.isEmpty()) {
            CompoundTag p = new CompoundTag();
            for (var e : pidState.entrySet())
                p.putFloat(String.valueOf(e.getKey()), e.getValue());
            tag.put("pid", p);
        }
        if (!delayQueues.isEmpty()) {
            CompoundTag d = new CompoundTag();
            for (var e : delayQueues.entrySet()) {
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
        // Sub-graph state — one CompoundTag per encapsulation node
        if (!subStates.isEmpty()) {
            CompoundTag sub = new CompoundTag();
            for (var entry : subStates.entrySet()) {
                CompoundTag ss = new CompoundTag();
                SubState s = entry.getValue();
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
                sub.put(String.valueOf(entry.getKey()), ss);
            }
            tag.put("sub", sub);
        }
        return tag;
    }

    public static RuntimeState load(CompoundTag tag) {
        RuntimeState rs = new RuntimeState();

        if (tag.contains("pid")) {
            var p = tag.getCompound("pid");
            for (var k : p.getAllKeys())
                rs.pidState.put(Integer.parseInt(k), p.getFloat(k));
        }
        if (tag.contains("delay")) {
            var d = tag.getCompound("delay");
            for (var k : d.getAllKeys()) {
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
        // Sub-graph state
        if (tag.contains("sub")) {
            var sub = tag.getCompound("sub");
            for (var k : sub.getAllKeys()) {
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
                rs.subStates.put(encapId, s);
            }
        }
        return rs;
    }
}
