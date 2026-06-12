package com.example.create_schematic_compute.graph;

import com.example.create_schematic_compute.ModUtils;
import com.example.create_schematic_compute.network.SignalBus;
import net.minecraft.world.item.ItemStack;
import java.util.*;

public class GraphEvaluator {
    private final NodeGraph graph;
    private final Map<Integer, float[]> outputs = new HashMap<>();

    public GraphEvaluator(NodeGraph graph) { this.graph = graph; }

    /** 控制座椅输入状态 */
    public record SeatInputState(long keyBits, float mouseX, float mouseY, float yaw, float pitch,
        float worldYaw, float worldPitch,
        int mouseButtons, float gpadLX, float gpadLY, float gpadRX, float gpadRY, long gpadButtons,
        float blockYaw, float attitudeYaw, float attitudePitch, float attitudeRoll, float forwardYaw, float forwardPitch) {
        public SeatInputState(long keyBits, float mouseX, float mouseY, float yaw, float pitch) {
            this(keyBits, mouseX, mouseY, yaw, pitch, yaw, pitch, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public List<OutputResult> evaluate(List<InputSource> inputs, Map<Integer, Float> pidState, float dt) {
        return evaluate(inputs, pidState, dt, new SeatInputState(0, 0, 0, 0, 0));
    }

    public List<OutputResult> evaluate(List<InputSource> inputs, Map<Integer, Float> pidState, float dt, SeatInputState seat) {
        outputs.clear();
        // 使用缓存的拓扑排序
        List<Integer> sorted = graph.getTopoOrder();
        for (int id : sorted) {
            GraphNode n = graph.findNode(id);
            if (n != null) eval(n, inputs, pidState, dt, seat);
        }
        List<OutputResult> res = new ArrayList<>();
        for (GraphNode n : graph.nodes) {
            if (n.type == NodeType.REDSTONE_OUT) {
                float v = graph.getInputValue(n.id, 0, outputs);
                res.add(new OutputResult(
                    n.itemParams.length > 0 ? n.itemParams[0] : ItemStack.EMPTY,
                    n.itemParams.length > 1 ? n.itemParams[1] : ItemStack.EMPTY,
                    Math.max(0, Math.min(15, Math.round(v)))));

            }
        }
        return res;
    }

    /** 获取当前输出映射表（供显示区域等外部查询输入值） */
    public Map<Integer, float[]> getCurrentOutputs() { return outputs; }

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
        return evaluate(inputs, pidState, dt, new SeatInputState(0, 0, 0, 0, 0), delayQueues, flipflopStates, pulseTimers);
    }

    public List<OutputResult> evaluate(List<InputSource> inputs, Map<Integer, Float> pidState, float dt,
                                        SeatInputState seat,
                                        Map<Integer, java.util.ArrayDeque<Float>> delayQueues,
                                        Map<Integer, Boolean> flipflopStates,
                                        Map<Integer, Integer> pulseTimers) {
        outputs.clear();
        List<Integer> sorted = graph.getTopoOrder();
        for (int id : sorted) {
            GraphNode n = graph.findNode(id);
            if (n != null) evalExt(n, inputs, pidState, dt, seat, delayQueues, flipflopStates, pulseTimers);
        }
        List<OutputResult> res = new ArrayList<>();
        for (GraphNode n : graph.nodes) {
            if (n.type == NodeType.REDSTONE_OUT) {
                float v = graph.getInputValue(n.id, 0, outputs);
                res.add(new OutputResult(
                    n.itemParams.length > 0 ? n.itemParams[0] : ItemStack.EMPTY,
                    n.itemParams.length > 1 ? n.itemParams[1] : ItemStack.EMPTY,
                    Math.max(0, Math.min(15, Math.round(v)))));

            }
        }
        return res;
    }

    private void evalExt(GraphNode node, List<InputSource> inputs, Map<Integer, Float> pidState, float dt,
                         SeatInputState seat,
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
            default -> { eval(node, inputs, pidState, dt, seat); return; }
        }
        outputs.put(node.id, o.clone());
    }

    private void eval(GraphNode node, List<InputSource> inputs, Map<Integer, Float> pidState, float dt, SeatInputState seat) {
        float[] o = node.outputValues;
        switch (node.type) {
            case KEYBOARD -> {
                int keyIndex = node.params.length > 0 ? (int)node.params[0] : 0;
                keyIndex = Math.max(0, Math.min(35, keyIndex));
                o[0] = ((seat.keyBits >> keyIndex) & 1L) != 0 ? 1f : 0f;
            }
            case MOUSE_JOYSTICK -> { o[0] = seat.mouseX(); o[1] = seat.mouseY(); }
            case VIEW_ANGLE -> { o[0] = seat.pitch(); o[1] = seat.yaw(); }
            case MOUSE_BUTTON -> { o[0] = (seat.mouseButtons() & 1) != 0 ? 1 : 0; o[1] = (seat.mouseButtons() & 2) != 0 ? 1 : 0; }
            case GAMEPAD_JOYSTICK -> { o[0] = seat.gpadLX(); o[1] = seat.gpadLY(); o[2] = seat.gpadRX(); o[3] = seat.gpadRY(); }
            case GAMEPAD_BUTTON -> {
                int bi = node.params.length > 0 ? (int)node.params[0] : 0;
                o[0] = bi >= 0 && bi < 64 && ((seat.gpadButtons() >> bi) & 1L) != 0 ? 1 : 0;
            }
            case WORLD_VIEW -> { o[0] = seat.worldYaw(); o[1] = seat.worldPitch(); }
            case ATTITUDE -> { o[0] = seat.attitudePitch(); o[1] = seat.attitudeRoll(); }
            case FORWARD -> { o[0] = seat.forwardYaw(); o[1] = seat.forwardPitch(); }
            case SPLIT -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Math.max(0, v); o[1] = Math.max(0, -v);
            }
            case POSE_CONVERT -> {
                float pa = graph.getInputValue(node.id, 0, outputs);
                float ya = graph.getInputValue(node.id, 1, outputs);
                float roll = graph.getInputValue(node.id, 2, outputs);
                double r = Math.toRadians(roll);
                double sr = Math.sin(r), cr = Math.cos(r);
                o[0] = (float)(sr * pa + cr * ya);
                o[1] = (float)(cr * pa - sr * ya);
            }
            case CONST -> o[0] = node.params.length > 0 ? node.params[0] : 0;
            case REDSTONE_IN -> {
                long nodeKey = ModUtils.freqKey(node.itemParams);
                int sig = 0;
                for (InputSource s : inputs) if (s.freqKey == nodeKey) { sig = s.signal; break; }
                o[0] = sig;
            }
            case REDSTONE_OUT -> { }
            case ADD -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a + b; }
            case SUB -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a - b; }
            case MUL -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a * b; }
            case DIV -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = b != 0 ? a / b : 0; }
            case MOD -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = b != 0 ? a % b : 0; }
            case POW -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = (float) Math.pow(a, b); }
            case ROOT -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = b != 0 ? (float) Math.pow(a, 1.0 / b) : 0; }
            case ABS -> o[0] = Math.abs(graph.getInputValue(node.id, 0, outputs));
            case INTERP -> {
                float a = graph.getInputValue(node.id, 0, outputs);
                float b = graph.getInputValue(node.id, 1, outputs);
                if (a >= b) { o[0] = a - b; o[1] = 0; }
                else { o[0] = 0; o[1] = Math.abs(b - a); }
            }
            case CEIL -> o[0] = (float) Math.ceil(graph.getInputValue(node.id, 0, outputs));
            case FLOOR -> o[0] = (float) Math.floor(graph.getInputValue(node.id, 0, outputs));
            case BOOL -> {
                float vin = graph.getInputValue(node.id, 0, outputs);
                o[0] = vin > 0 ? 1 : 0;
                if (node.params.length > 0 && node.params[0] > 0.5f) o[0] = 1 - o[0];
            }
            case GT -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a > b ? 1 : 0; }
            case LT -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a < b ? 1 : 0; }
            case EQ -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = Math.abs(a - b) < 0.01f ? 1 : 0; }
            case PID -> {
                float sp = Math.max(0, Math.min(15, graph.getInputValue(node.id, 0, outputs)));
                float kp = node.params.length > 0 ? node.params[0] : 1.0f;
                float ki = node.params.length > 1 ? node.params[1] : 0.1f;
                float kd = node.params.length > 2 ? node.params[2] : 0.05f;
                float s = node.params.length > 3 ? node.params[3] : 1.0f;
                float ilimit = node.params.length > 4 ? node.params[4] : 3.0f;
                float err = sp;
                int ik = node.id;
                float integral = pidState.getOrDefault(ik, 0f);
                if (Math.abs(err) > 0.001f) integral += err * dt;
                else integral = 0;
                if (integral > ilimit) integral = ilimit;
                if (integral < -ilimit) integral = -ilimit;
                pidState.put(ik, integral);
                o[0] = Math.max(0, (kp * err + ki * integral) * s);
            }
            case PID_POWER -> {
                float sp = Math.max(0, Math.min(15, graph.getInputValue(node.id, 0, outputs)));
                float base = Math.max(0, Math.min(15, graph.getInputValue(node.id, 1, outputs)));
                float kp = node.params.length > 0 ? node.params[0] : 1.0f;
                float ki = node.params.length > 1 ? node.params[1] : 0.1f;
                float ilimit = node.params.length > 2 ? node.params[2] : 3.0f;
                base = Float.isNaN(base) ? 0 : base;
                float err = sp;
                int ik = node.id;
                float integral = pidState.getOrDefault(ik, 0f);
                if (Math.abs(err) > 0.001f) integral += err * dt;
                else integral = 0;
                if (integral > ilimit) integral = ilimit;
                if (integral < -ilimit) integral = -ilimit;
                pidState.put(ik, integral);
                o[0] = Math.max(0, base + kp * err + ki * integral);
            }
            case CLAMP -> { float v = graph.getInputValue(node.id, 0, outputs); float mn = graph.getInputValue(node.id, 1, outputs); float mx = graph.getInputValue(node.id, 2, outputs); o[0] = Math.max(mn, Math.min(mx, v)); }
            case MAP -> { float v = graph.getInputValue(node.id, 0, outputs); float imn = graph.getInputValue(node.id, 1, outputs); float imx = graph.getInputValue(node.id, 2, outputs); float omn = graph.getInputValue(node.id, 3, outputs); float omx = graph.getInputValue(node.id, 4, outputs); float r = imx - imn; o[0] = r == 0 ? omn : omn + (v - imn) / r * (omx - omn); }
            case SPEED_CTRL -> {
                float speed = graph.getInputValue(node.id, 0, outputs);
                float dir = graph.getInputValue(node.id, 1, outputs);
                o[0] = dir > 0.5 ? -speed : speed;
            }
            case PRIVATE_IN -> o[0] = SignalBus.get(node.signalName);
            case PRIVATE_OUT -> SignalBus.put(node.signalName, graph.getInputValue(node.id, 0, outputs));
            case ACCUMULATOR -> {
                float inPlus = graph.getInputValue(node.id, 0, outputs);
                float inMinus = graph.getInputValue(node.id, 1, outputs);
                float step = node.params.length > 0 ? node.params[0] : 1f;
                int prevPKey = node.id + 100000, prevMKey = node.id + 200000;
                float cur = pidState.getOrDefault(node.id, 0f);
                float prevP = pidState.getOrDefault(prevPKey, 0f);
                float prevM = pidState.getOrDefault(prevMKey, 0f);
                if (inPlus > 0.5f && prevP <= 0.5f) cur += step;   // + 上升沿
                if (inMinus > 0.5f && prevM <= 0.5f) cur -= step;  // - 上升沿
                pidState.put(node.id, cur);
                pidState.put(prevPKey, inPlus);
                pidState.put(prevMKey, inMinus);
                o[0] = cur;
            }
            case FORMULA -> {
                if (node.formula.isEmpty()) { o[0] = 0; break; }
                var vars = new java.util.HashMap<String, Double>();
                var varNames = FormulaParser.extractVariables(node.formula);
                for (int vi = 0; vi < varNames.size(); vi++)
                    vars.put(varNames.get(vi), (double)graph.getInputValue(node.id, vi, outputs));
                o[0] = FormulaParser.eval(node.formula, vars);
            }
            // Display nodes — no float output; data read from GraphNode fields by renderer
            case TEXT, DATA, IMAGE, IMAGE_SEQUENCE -> {}
        }
        outputs.put(node.id, o.clone());
    }

    public record InputSource(long freqKey, int signal) {}
    public record OutputResult(ItemStack freq1, ItemStack freq2, int signal) {}
}
