package com.example.create_schematic_compute.graph;

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

    public RuntimeState() {}

    /** Wipe all state (used on {@code accept()} merge). */
    public void clear() {
        pidState.clear();
        delayQueues.clear();
        flipflopStates.clear();
        pulseTimers.clear();
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
        return rs;
    }
}
