package com.example.create_schematic_compute.graph;

import net.minecraft.world.item.ItemStack;
import java.util.*;

public class GraphEvaluator {
    private final NodeGraph graph;
    private final Map<Integer, float[]> outputs = new HashMap<>();

    public GraphEvaluator(NodeGraph graph) { this.graph = graph; }

    public List<OutputResult> evaluate(List<InputSource> inputs, Map<Integer, Float> pidState, float dt) {
        outputs.clear();
        // 使用缓存的拓扑排序
        List<Integer> sorted = graph.getTopoOrder();
        for (int id : sorted) {
            GraphNode n = graph.findNode(id);
            if (n != null) eval(n, inputs, pidState, dt);
        }
        List<OutputResult> res = new ArrayList<>();
        for (GraphNode n : graph.nodes) {
            if (n.type == NodeType.REDSTONE_OUT) {
                float v = graph.getInputValue(n.id, 0, outputs);
                ItemStack f = effectiveFreq(n.itemParams);
                if (!f.isEmpty()) res.add(new OutputResult(f, Math.round(v)));
            }
        }
        return res;
    }

    /** 获取有效的频率物品：优先槽位#1，回退到槽位#2 */
    private static ItemStack effectiveFreq(ItemStack[] itemParams) {
        if (itemParams == null) return ItemStack.EMPTY;
        if (itemParams.length > 0 && !itemParams[0].isEmpty()) return itemParams[0];
        if (itemParams.length > 1 && !itemParams[1].isEmpty()) return itemParams[1];
        return ItemStack.EMPTY;
    }

    /** 读取任意节点某引脚的计算结果（供外部查询） */
    public float getNodeOutput(int nodeId, int pinIndex) {
        float[] out = outputs.get(nodeId);
        return out != null && pinIndex < out.length ? out[pinIndex] : 0;
    }

    /** 读取某节点某输入引脚的值（用于延时节点入队） */
    public float getNodeInput(int nodeId, int pinIndex) {
        return graph.getInputValue(nodeId, pinIndex, outputs);
    }

    /** 带扩展状态的评估（时序节点用） */
    public List<OutputResult> evaluate(List<InputSource> inputs, Map<Integer, Float> pidState, float dt,
                                        Map<Integer, java.util.ArrayDeque<Float>> delayQueues,
                                        Map<Integer, Boolean> flipflopStates,
                                        Map<Integer, Integer> pulseTimers) {
        outputs.clear();
        List<Integer> sorted = graph.getTopoOrder();
        for (int id : sorted) {
            GraphNode n = graph.findNode(id);
            if (n != null) evalExt(n, inputs, pidState, dt, delayQueues, flipflopStates, pulseTimers);
        }
        List<OutputResult> res = new ArrayList<>();
        for (GraphNode n : graph.nodes) {
            if (n.type == NodeType.REDSTONE_OUT) {
                float v = graph.getInputValue(n.id, 0, outputs);
                ItemStack f = effectiveFreq(n.itemParams);
                if (!f.isEmpty()) res.add(new OutputResult(f, Math.round(v)));
            }
        }
        return res;
    }

    private void evalExt(GraphNode node, List<InputSource> inputs, Map<Integer, Float> pidState, float dt,
                         Map<Integer, java.util.ArrayDeque<Float>> delayQueues,
                         Map<Integer, Boolean> flipflopStates,
                         Map<Integer, Integer> pulseTimers) {
        float[] o = node.outputValues;
        switch (node.type) {
            case DELAY -> {
                float in = graph.getInputValue(node.id, 0, outputs);
                var q = delayQueues.get(node.id);
                if (q == null || q.isEmpty()) { o[0] = 0; break; }
                o[0] = q.peekFirst();
            }
            case LATCH -> {
                float s = graph.getInputValue(node.id, 0, outputs);
                float r = graph.getInputValue(node.id, 1, outputs);
                boolean cur = flipflopStates.getOrDefault(node.id, false);
                if (s > 0.5f) cur = true;
                else if (r > 0.5f) cur = false;
                flipflopStates.put(node.id, cur);
                o[0] = cur ? 1 : 0;
            }
            case T_FLIPFLOP -> {
                float in = graph.getInputValue(node.id, 0, outputs);
                boolean cur = flipflopStates.getOrDefault(node.id, false);
                // 检测上升沿
                boolean prev = flipflopStates.getOrDefault(-(node.id+1), false);
                if (in > 0.5f && !prev) cur = !cur;
                flipflopStates.put(node.id, cur);
                flipflopStates.put(-(node.id+1), in > 0.5f);
                o[0] = cur ? 1 : 0;
            }
            case PULSE_EXTEND -> {
                float in = graph.getInputValue(node.id, 0, outputs);
                int timer = pulseTimers.getOrDefault(node.id, 0);
                if (in > 0.5f) timer = Math.max(1, (int)node.params[0]);
                else if (timer > 0) timer--;
                pulseTimers.put(node.id, timer);
                o[0] = timer > 0 ? 1 : 0;
            }
            case LOOP -> {
                float in = graph.getInputValue(node.id, 0, outputs);
                int count = Math.max(1, (int)(node.params.length>0?node.params[0]:5));
                int interval = Math.max(1, (int)(node.params.length>1?node.params[1]:10));
                int remaining = pulseTimers.getOrDefault(node.id, 0);
                int counter = pulseTimers.getOrDefault(-(node.id+1), 0);
                boolean prevTrigger = flipflopStates.getOrDefault(-(node.id+1), false);
                // 上升沿触发启动
                if (in > 0.5f && !prevTrigger && remaining == 0) {
                    remaining = count;
                    counter = interval;
                }
                flipflopStates.put(-(node.id+1), in > 0.5f);
                if (remaining > 0) {
                    counter--;
                    if (counter <= 0) {
                        o[0] = 1;
                        remaining--;
                        if (remaining > 0) counter = interval;
                    } else {
                        o[0] = 0;
                    }
                } else {
                    o[0] = 0;
                }
                pulseTimers.put(node.id, remaining);
                pulseTimers.put(-(node.id+1), counter);
            }
            case FUSE -> {
                float in = graph.getInputValue(node.id, 0, outputs);
                int cd = Math.max(1, (int)(node.params.length>0?node.params[0]:40));
                int timer = pulseTimers.getOrDefault(node.id, 0);
                boolean prev = flipflopStates.getOrDefault(-(node.id+1), false);
                boolean triggered = in > 0.5f && !prev;
                flipflopStates.put(-(node.id+1), in > 0.5f);
                if (triggered && timer == 0) {
                    timer = cd;         // 冷却倒计时开始
                    pulseTimers.put(-(node.id+1), 2); // 脉冲已输出 0 tick
                    o[0] = 1;
                } else if (timer > 0) {
                    int pulseOut = pulseTimers.getOrDefault(-(node.id+1), 0);
                    if (pulseOut < 2) {
                        o[0] = 1;      // 2 tick 脉冲
                        pulseTimers.put(-(node.id+1), pulseOut + 1);
                    } else {
                        o[0] = 0;      // 脉冲结束，在冷却中
                    }
                    timer--;
                    if (timer == 0) pulseTimers.remove(-(node.id+1));
                } else {
                    o[0] = 0;
                }
                pulseTimers.put(node.id, timer);
            }
            default -> { eval(node, inputs, pidState, dt); return; }
        }
        outputs.put(node.id, o.clone());
    }

    private void eval(GraphNode node, List<InputSource> inputs, Map<Integer, Float> pidState, float dt) {
        float[] o = node.outputValues;
        switch (node.type) {
            case CONST -> o[0] = node.params.length > 0 ? node.params[0] : 0;
            case REDSTONE_IN -> {
                ItemStack f = effectiveFreq(node.itemParams);
                int sig = 0;
                for (InputSource s : inputs) if (s.freqEquals(f)) { sig = s.signal; break; }
                o[0] = sig;
            }
            case REDSTONE_OUT -> { }
            case ADD -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a + b; }
            case SUB -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a - b; }
            case MUL -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a * b; }
            case DIV -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = b != 0 ? a / b : 0; }
            case MOD -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = b != 0 ? a % b : 0; }
            case CEIL -> o[0] = (float) Math.ceil(graph.getInputValue(node.id, 0, outputs));
            case FLOOR -> o[0] = (float) Math.floor(graph.getInputValue(node.id, 0, outputs));
            case GT -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a > b ? 1 : 0; }
            case LT -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a < b ? 1 : 0; }
            case EQ -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = Math.abs(a - b) < 0.01f ? 1 : 0; }
            case PID -> {
                float sp = Math.max(0, Math.min(15, graph.getInputValue(node.id, 0, outputs)));
                float pv = Math.max(0, Math.min(15, graph.getInputValue(node.id, 1, outputs)));
                float kp = node.params.length > 0 ? node.params[0] : 1.0f;
                float ki = node.params.length > 1 ? node.params[1] : 0.1f;
                float kd = node.params.length > 2 ? node.params[2] : 0.05f;
                float s = node.params.length > 3 ? node.params[3] : 1.0f;
                float err = sp - pv;
                int ik = node.id, dk = -(node.id + 1);
                float integral = pidState.getOrDefault(ik, 0f);
                float prevPv = pidState.getOrDefault(dk, pv);
                if (Math.abs(err) > 0.001f) integral += err * dt;
                else integral = 0;
                if (integral > 50) integral = 50; if (integral < -50) integral = -50;
                pidState.put(ik, integral);
                float dPv = pv - prevPv;
                float deriv = (dt > 0.001f) ? -dPv / dt : 0;
                pidState.put(dk, pv);
                o[0] = Math.max(0, Math.min(16, (kp * err + ki * integral + kd * deriv) * s));
            }
            case PID_POWER -> {
                float sp = Math.max(0, Math.min(15, graph.getInputValue(node.id, 0, outputs)));
                float pv = Math.max(0, Math.min(15, graph.getInputValue(node.id, 1, outputs)));
                float base = Math.max(0, Math.min(15, graph.getInputValue(node.id, 2, outputs)));  // 初始动力值
                float kp = node.params.length > 0 ? node.params[0] : 1.0f;
                float ki = node.params.length > 1 ? node.params[1] : 0.1f;
                float kd = node.params.length > 2 ? node.params[2] : 0.05f;
                base = Float.isNaN(base) ? 0 : base;
                float err = sp - pv;
                int ik = node.id, dk = -(node.id + 1);
                float integral = pidState.getOrDefault(ik, 0f);
                float prevPv = pidState.getOrDefault(dk, pv);
                if (Math.abs(err) > 0.001f) integral += err * dt;
                else integral = 0;
                if (integral > 50) integral = 50; if (integral < -50) integral = -50;
                pidState.put(ik, integral);
                float dPv = pv - prevPv;
                float deriv = (dt > 0.001f) ? -dPv / dt : 0;
                float pidOut = kp * err + ki * integral + kd * deriv;
                // 输出 = 初始动力 + PID 调整，上限不超过 16，下限不低于 0
                float maxUp = 16 - base;
                float maxDown = base;
                o[0] = Math.max(0, Math.min(16, base + pidOut));
            }
            case CLAMP -> { float v = graph.getInputValue(node.id, 0, outputs); float mn = graph.getInputValue(node.id, 1, outputs); float mx = graph.getInputValue(node.id, 2, outputs); o[0] = Math.max(mn, Math.min(mx, v)); }
            case MAP -> { float v = graph.getInputValue(node.id, 0, outputs); float imn = graph.getInputValue(node.id, 1, outputs); float imx = graph.getInputValue(node.id, 2, outputs); float omn = graph.getInputValue(node.id, 3, outputs); float omx = graph.getInputValue(node.id, 4, outputs); float r = imx - imn; o[0] = r == 0 ? omn : omn + (v - imn) / r * (omx - omn); }
            case SPEED_CTRL -> { o[0] = graph.getInputValue(node.id, 0, outputs); }
            case PRIVATE_IN -> o[0] = com.example.create_schematic_compute.network.SignalBus.get(node.signalName);
            case PRIVATE_OUT -> com.example.create_schematic_compute.network.SignalBus.put(node.signalName, graph.getInputValue(node.id, 0, outputs));
        }
        outputs.put(node.id, o.clone());
    }

    public record InputSource(ItemStack freq, int signal) { public boolean freqEquals(ItemStack o) { return ItemStack.isSameItem(freq, o); } }
    public record OutputResult(ItemStack freq, int signal) {}
}
