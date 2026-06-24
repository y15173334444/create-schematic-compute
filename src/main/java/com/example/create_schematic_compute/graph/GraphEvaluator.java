package com.example.create_schematic_compute.graph;

import com.example.create_schematic_compute.ModUtils;
import com.example.create_schematic_compute.network.ChannelEntry;
import com.example.create_schematic_compute.network.SignalBus;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GraphEvaluator {
    private final NodeGraph graph;
    private final Map<Integer, float[]> outputs = new HashMap<>();
    private net.minecraft.core.BlockPos radarPos = null;
    public void setRadarPos(net.minecraft.core.BlockPos pos) { this.radarPos = pos; }
    // FORMULA script parse cache — formula string → ScriptParseResult (v1.2+ multi-line script)
    private static final int MAX_SCRIPT_CACHE = 1024;
    private final Map<String, FormulaParser.ScriptParseResult> scriptCache =
        new java.util.LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, FormulaParser.ScriptParseResult> eldest) {
                return size() > MAX_SCRIPT_CACHE;
            }
        };

    // Cache sub-graph evaluators and runtime state for ENCAPSULATION nodes
    // Key: encapsulation node ID (node that owns the subGraph)
    private final Map<Integer, GraphEvaluator> subEvaluators = new HashMap<>();
    private final Map<Integer, Map<Integer, java.util.ArrayDeque<Float>>> subDelayQueues = new HashMap<>();
    private final Map<Integer, Map<Integer, Boolean>> subFlipflopStates = new HashMap<>();
    private final Map<Integer, Map<Integer, Integer>> subPulseTimers = new HashMap<>();

    /** External RuntimeState reference — set before first evaluate() to restore sub-graph state. */
    private RuntimeState runtimeState;

    public GraphEvaluator(NodeGraph graph) { this.graph = graph; }

    /** Restore sub-graph state from a previously-saved RuntimeState.
     *  Must be called once after construction and before the first {@link #evaluate}. */
    public void restoreSubState(RuntimeState rs) {
        this.runtimeState = rs;
        for (var entry : rs.subStates.entrySet()) {
            int encapId = entry.getKey();
            RuntimeState.SubState ss = entry.getValue();
            if (!ss.delayQueues.isEmpty()) {
                subDelayQueues.put(encapId, new HashMap<>(ss.delayQueues));
            }
            if (!ss.flipflopStates.isEmpty()) {
                subFlipflopStates.put(encapId, new HashMap<>(ss.flipflopStates));
            }
            if (!ss.pulseTimers.isEmpty()) {
                subPulseTimers.put(encapId, new HashMap<>(ss.pulseTimers));
            }
        }
    }

    /** 控制座椅输入状态 */
    public record SeatInputState(long keyBits, float mouseX, float mouseY, float yaw, float pitch,
        float worldYaw, float worldPitch,
        int mouseButtons, float gpadLX, float gpadLY, float gpadRX, float gpadRY, float gpadLT, float gpadRT, long gpadButtons,
        float blockYaw, float attitudeYaw, float attitudePitch, float attitudeRoll, float forwardYaw, float forwardPitch,
        float accelX, float accelY, float accelZ,
        float velX, float velY, float velZ,
        float worldX, float worldY, float worldZ) {
        public SeatInputState(long keyBits, float mouseX, float mouseY, float yaw, float pitch) {
            this(keyBits, mouseX, mouseY, yaw, pitch, yaw, pitch, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public List<OutputResult> evaluate(List<InputSource> inputs, Map<Integer, Float> pidState, float dt) {
        return evaluate(inputs, pidState, dt, new SeatInputState(0, 0, 0, 0, 0),
            new HashMap<>(), new HashMap<>(), new HashMap<>());
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
                boolean cur = flipflopStates.getOrDefault(node.id,
                    node.params.length > 0 && node.params[0] > 0.5f);
                if (s > 0.5f) cur = true;
                else if (r > 0.5f) cur = false;
                flipflopStates.put(node.id, cur);
                if (node.params.length > 1) node.params[1] = cur ? 1 : 0;
                o[0] = cur ? 1 : 0;
            }
            case GATE -> {
                boolean cur = flipflopStates.getOrDefault(node.id,
                    node.params.length > 0 && node.params[0] > 0.5f);
                float val = graph.getInputValue(node.id, 0, outputs);
                float open = graph.getInputValue(node.id, 1, outputs);
                float close = graph.getInputValue(node.id, 2, outputs);
                float toggle = graph.getInputValue(node.id, 3, outputs);
                // Rising-edge detection for toggle
                boolean prevTog = flipflopStates.getOrDefault(-(node.id+1), toggle > 0.5f);
                boolean togEdge = toggle > 0.5f && !prevTog;
                flipflopStates.put(-(node.id+1), toggle > 0.5f);
                // Open has priority, then Close, then Toggle edge
                if (open > 0.5f) cur = true;
                else if (close > 0.5f) cur = false;
                else if (togEdge) cur = !cur;
                flipflopStates.put(node.id, cur);
                if (node.params.length > 1) node.params[1] = cur ? 1 : 0;
                o[0] = cur ? val : 0;
            }
            case T_FLIPFLOP -> {
                float in = graph.getInputValue(node.id, 0, outputs);
                boolean cur = flipflopStates.getOrDefault(node.id,
                    node.params.length > 0 && node.params[0] > 0.5f);
                boolean prev = flipflopStates.getOrDefault(-(node.id+1), in > 0.5f);
                if (in > 0.5f && !prev) cur = !cur;
                flipflopStates.put(node.id, cur);
                flipflopStates.put(-(node.id+1), in > 0.5f);
                if (node.params.length > 1) node.params[1] = cur ? 1 : 0;
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

        // 通用参数引脚：连线值临时覆盖 node.params（所有节点 handler 无缝兼容）
        float[] origParams = node.params;
        float[] effParams = origParams;
        int extraBase = node.type.inputs;
        int extraCnt = node.type.editableParamCount();
        if (extraCnt > 0) {
            boolean hasOverride = false;
            for (int pi = 0; pi < extraCnt; pi++) {
                if (graph.hasInputConnection(node.id, extraBase + pi)) {
                    if (!hasOverride) { effParams = origParams.clone(); hasOverride = true; }
                    if (pi < effParams.length) effParams[pi] = graph.getInputValue(node.id, extraBase + pi, outputs);
                }
            }
            if (hasOverride) node.params = effParams;
        }

        switch (node.type) {
            case KEYBOARD -> {
                int keyIndex = node.params.length > 0 ? (int)node.params[0] : 0;
                keyIndex = Math.max(0, Math.min(57, keyIndex)); // 58 keys: A-Z, 0-9, Space, Shift, Ctrl, etc.
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
            case GAMEPAD_TRIGGER -> { o[0] = seat.gpadLT(); o[1] = seat.gpadRT(); }
            case WORLD_VIEW -> { o[0] = seat.worldYaw(); o[1] = seat.worldPitch(); }
            case ATTITUDE -> { o[0] = seat.attitudePitch(); o[1] = seat.attitudeRoll(); }
            case FORWARD -> { o[0] = seat.forwardYaw(); o[1] = seat.forwardPitch(); }
            case ACCELERATION -> { o[0] = seat.accelX(); o[1] = seat.accelY(); o[2] = seat.accelZ(); }
            case VELOCITY -> { o[0] = seat.velX() * 2f; o[1] = seat.velY() * 2f; o[2] = seat.velZ() * 2f; }
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
            case ADD -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = Float.isFinite(a) && Float.isFinite(b) ? a + b : 0; }
            case SUB -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = Float.isFinite(a) && Float.isFinite(b) ? a - b : 0; }
            case MUL -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = Float.isFinite(a) && Float.isFinite(b) ? a * b : 0; }
            case DIV -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = (b != 0 && Float.isFinite(a) && Float.isFinite(b)) ? a / b : 0; }
            case MOD -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = (b != 0 && Float.isFinite(a) && Float.isFinite(b)) ? a % b : 0; }
            case POW -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); float r = (float) Math.pow(Math.abs(a), b); o[0] = Float.isFinite(r) ? r : 0; }
            case ROOT -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = (b != 0 && a >= 0) ? (float) Math.pow(a, 1.0 / b) : 0; }
            case ABS -> o[0] = Math.abs(graph.getInputValue(node.id, 0, outputs));
            case ROUND -> {
                float val = graph.getInputValue(node.id, 0, outputs);
                int decimals = node.params.length > 0 ? Math.max(0, (int)node.params[0]) : 2;
                double pow10 = Math.pow(10, decimals);
                o[0] = (float)(Math.round(val * pow10) / pow10);
            }
            case SIN -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) ? (float) Math.sin(Math.toRadians(v)) : 0;
            }
            case COS -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) ? (float) Math.cos(Math.toRadians(v)) : 0;
            }
            case TAN -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) ? (float) Math.tan(Math.toRadians(v)) : 0;
            }
            case ASIN -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = (Float.isFinite(v) && v >= -1 && v <= 1) ? (float) Math.toDegrees(Math.asin(v)) : 0;
            }
            case ACOS -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = (Float.isFinite(v) && v >= -1 && v <= 1) ? (float) Math.toDegrees(Math.acos(v)) : 0;
            }
            case ATAN2 -> {
                float y = graph.getInputValue(node.id, 0, outputs);
                float x = graph.getInputValue(node.id, 1, outputs);
                o[0] = (Float.isFinite(y) && Float.isFinite(x)) ? (float) Math.toDegrees(Math.atan2(y, x)) : 0;
            }
            case SINH -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) ? (float) Math.sinh(v) : 0;
            }
            case COSH -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) ? (float) Math.cosh(v) : 0;
            }
            case SQRT -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) && v >= 0 ? (float) Math.sqrt(v) : 0;
            }
            case LN -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) && v > 0 ? (float) Math.log(v) : 0;
            }
            case LOG -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) && v > 0 ? (float) Math.log10(v) : 0;
            }
            case EXP -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = Float.isFinite(v) ? (float) Math.exp(v) : 0;
            }
            case SEC -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = (Float.isFinite(v) && Math.abs(Math.cos(Math.toRadians(v))) > 1e-12f)
                    ? (float)(1.0 / Math.cos(Math.toRadians(v))) : 0;
            }
            case CSC -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = (Float.isFinite(v) && Math.abs(Math.sin(Math.toRadians(v))) > 1e-12f)
                    ? (float)(1.0 / Math.sin(Math.toRadians(v))) : 0;
            }
            case COT -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                o[0] = (Float.isFinite(v) && Math.abs(Math.tan(Math.toRadians(v))) > 1e-12f)
                    ? (float)(1.0 / Math.tan(Math.toRadians(v))) : 0;
            }
            case ANGLE_UNWRAP -> {
                float cur = graph.getInputValue(node.id, 0, outputs);
                float last = pidState.getOrDefault(node.id, cur);
                float diff = cur - last;
                while (diff > 180f) diff -= 360f;
                while (diff < -180f) diff += 360f;
                o[0] = last + diff;
                pidState.put(node.id, o[0]);
            }
            case DIRECTION -> {
                float ax = node.params.length > 0 ? node.params[0] : 0;
                float ay = node.params.length > 1 ? node.params[1] : 0;
                float az = node.params.length > 2 ? node.params[2] : 0;
                float bx = node.params.length > 3 ? node.params[3] : 0;
                float by = node.params.length > 4 ? node.params[4] : 0;
                float bz = node.params.length > 5 ? node.params[5] : 0;
                double dx = bx - ax, dy = by - ay, dz = bz - az;
                if (dx == 0 && dy == 0 && dz == 0) { o[0] = 0; o[1] = 0; o[2] = 0; break; }
                double h = Math.sqrt(dx * dx + dz * dz);
                double yaw = Math.toDegrees(Math.atan2(dx, -dz));
                yaw = (yaw + 360) % 360;
                double pitch = Math.toDegrees(Math.atan2(-dy, h));
                o[0] = (float) yaw;
                o[1] = (float) pitch;
                o[2] = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            case INTERP -> {
                float a = graph.getInputValue(node.id, 0, outputs);
                float b = graph.getInputValue(node.id, 1, outputs);
                if (!Float.isFinite(a) || !Float.isFinite(b)) { o[0] = 0; o[1] = 0; }
                else if (a >= b) { o[0] = a - b; o[1] = 0; }
                else { o[0] = 0; o[1] = Math.abs(b - a); }
            }
            case CEIL -> o[0] = (float) Math.ceil(graph.getInputValue(node.id, 0, outputs));
            case FLOOR -> o[0] = (float) Math.floor(graph.getInputValue(node.id, 0, outputs));
            case OR -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = (a > 0.5f || b > 0.5f) ? 1 : 0; }
            case BOOL -> {
                float vin = graph.getInputValue(node.id, 0, outputs);
                o[0] = vin > 0 ? 1 : 0;
                if (node.params.length > 0 && node.params[0] > 0.5f) o[0] = 1 - o[0];
            }
            case GT -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a > b ? 1 : 0; }
            case LT -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a < b ? 1 : 0; }
            case GE -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a >= b ? 1 : 0; }
            case LE -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = a <= b ? 1 : 0; }
            case EQ -> { float a = graph.getInputValue(node.id, 0, outputs); float b = graph.getInputValue(node.id, 1, outputs); o[0] = Math.abs(a - b) < 0.01f ? 1 : 0; }
            case PID -> {
                float sp = graph.getInputValue(node.id, 0, outputs);
                float pv = graph.getInputValue(node.id, 1, outputs);
                if (Float.isNaN(sp) || !Float.isFinite(sp)) sp = 0;
                if (Float.isNaN(pv) || !Float.isFinite(pv)) pv = 0;
                float kp = node.params.length > 0 ? node.params[0] : 1.0f;
                float ki = node.params.length > 1 ? node.params[1] : 0.1f;
                float kd = node.params.length > 2 ? node.params[2] : 0.05f;
                float s = node.params.length > 3 ? node.params[3] : 1.0f;
                float ilimit = node.params.length > 4 ? node.params[4] : 3.0f;
                float err = sp - pv;
                int ik = node.id;
                float integral = pidState.getOrDefault(ik, 0f);
                if (Math.abs(err) > 0.001f) integral += err * dt;
                else integral = 0;
                // ilimit 直接限制 I 项输出贡献
                float iContrib = ki * integral;
                if (iContrib > ilimit) iContrib = ilimit;
                if (iContrib < -ilimit) iContrib = -ilimit;
                // 抗饱和：同时限制原始积分
                float iCap = ilimit / Math.max(ki, 0.001f);
                if (integral > iCap) integral = iCap;
                if (integral < -iCap) integral = -iCap;
                pidState.put(ik, integral);
                // D 项：误差变化率
                int prevErrKey = node.id + 300000;
                float prevErr = pidState.getOrDefault(prevErrKey, 0f);
                float derivative = dt > 0 ? (err - prevErr) / dt : 0;
                pidState.put(prevErrKey, err);
                o[0] = (kp * err + iContrib + kd * derivative) * s;
            }
            case PID_POWER -> {
                float sp = graph.getInputValue(node.id, 0, outputs);
                float pv = graph.getInputValue(node.id, 1, outputs);
                float base = graph.getInputValue(node.id, 2, outputs);
                if (Float.isNaN(sp) || !Float.isFinite(sp)) sp = 0;
                if (Float.isNaN(pv) || !Float.isFinite(pv)) pv = 0;
                if (Float.isNaN(base) || !Float.isFinite(base)) base = 0;
                float kp = node.params.length > 0 ? node.params[0] : 1.0f;
                float ki = node.params.length > 1 ? node.params[1] : 0.1f;
                float kd = node.params.length > 2 ? node.params[2] : 0.05f;
                float ilimit = node.params.length > 3 ? node.params[3] : 3.0f;
                float err = sp - pv;
                int ik = node.id;
                float integral = pidState.getOrDefault(ik, 0f);
                if (Math.abs(err) > 0.001f) integral += err * dt;
                else integral = 0;
                // ilimit 直接限制 I 项输出贡献
                float iContrib = ki * integral;
                if (iContrib > ilimit) iContrib = ilimit;
                if (iContrib < -ilimit) iContrib = -ilimit;
                float iCap = ilimit / Math.max(ki, 0.001f);
                if (integral > iCap) integral = iCap;
                if (integral < -iCap) integral = -iCap;
                pidState.put(ik, integral);
                // D 项
                int prevErrKey = node.id + 300000;
                float prevErr = pidState.getOrDefault(prevErrKey, 0f);
                float derivative = dt > 0 ? (err - prevErr) / dt : 0;
                pidState.put(prevErrKey, err);
                o[0] = base + kp * err + iContrib + kd * derivative;
            }
            case CLAMP -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                if (Float.isNaN(v) || !Float.isFinite(v)) v = 0;
                float mn = graph.getInputValueOrDefault(node.id, 1, outputs,
                    node.params.length > 0 ? node.params[0] : 0);
                float mx = graph.getInputValueOrDefault(node.id, 2, outputs,
                    node.params.length > 1 ? node.params[1] : 0);
                o[0] = Math.max(mn, Math.min(mx, v));
            }
            case MAP -> {
                float v = graph.getInputValue(node.id, 0, outputs);
                float imn = graph.getInputValueOrDefault(node.id, 1, outputs,
                    node.params.length > 0 ? node.params[0] : 0);
                float imx = graph.getInputValueOrDefault(node.id, 2, outputs,
                    node.params.length > 1 ? node.params[1] : 0);
                float omn = graph.getInputValueOrDefault(node.id, 3, outputs,
                    node.params.length > 2 ? node.params[2] : 0);
                float omx = graph.getInputValueOrDefault(node.id, 4, outputs,
                    node.params.length > 3 ? node.params[3] : 0);
                if (!Float.isFinite(v) || !Float.isFinite(imn) || !Float.isFinite(imx) || !Float.isFinite(omn) || !Float.isFinite(omx)) { o[0] = 0; }
                else { float r = imx - imn; o[0] = r == 0 ? omn : omn + (v - imn) / r * (omx - omn); }
            }
            case SPEED_CTRL -> {
                float speed = graph.getInputValue(node.id, 0, outputs);
                float dir = graph.getInputValue(node.id, 1, outputs);
                o[0] = dir > 0.5 ? -speed : speed;
            }
            case PRIVATE_IN -> o[0] = SignalBus.get(node.signalName);
            case PRIVATE_OUT -> SignalBus.put(node.signalName, graph.getInputValue(node.id, 0, outputs));
            case BUS_IN -> {
                if (node.signalName.isEmpty()) break; // 空名称不允许通信
                int bc = node.bandCount();
                if (bc > 0) {
                    if (o.length != bc) { o = new float[bc]; node.outputValues = o; }
                    ChannelEntry entry = SignalBus.getChannel(node.signalName);
                    if (entry == null) {
                        for (int bi = 0; bi < bc; bi++) o[bi] = 0;
                    } else {
                        for (int bi = 0; bi < bc; bi++)
                            o[bi] = entry.internalMap.getOrDefault(node.signalBands.get(bi), 0f);
                    }
                } else {
                    if (o.length < 1) { o = new float[1]; node.outputValues = o; }
                    ChannelEntry entry = SignalBus.getChannel(node.signalName);
                    o[0] = (entry != null) ? entry.internalMap.getOrDefault("", 0f) : 0;
                }
            }
            case BUS_OUT -> {
                if (node.signalName.isEmpty()) break;
                int bc = node.bandCount();
                if (bc > 0) {
                    if (node.busInternalMap == null) node.busInternalMap = new java.util.HashMap<>();
                    // 冲突节点不注册频段，防止劫持 BAND_REGISTRY
                    // bandsDirty 跳过稳态下每 tick 的冗余 ArrayList 分配
                    if (!node.busConflict && node.bandsDirty) {
                        SignalBus.registerBands(node.signalName, node.signalBands);
                        node.bandsDirty = false;
                    }
                    // 冲突节点跳过 busInternalMap 写入 — MAP 修改权属于首个注册的 BUS_OUT
                    if (!node.busConflict) {
                        for (int bi = 0; bi < bc; bi++)
                            node.busInternalMap.put(node.signalBands.get(bi),
                                graph.getInputValue(node.id, bi, outputs));
                    }
                }
            }
            case POSITION -> {
                float wx = seat.worldX(), wy = seat.worldY(), wz = seat.worldZ();
                // 应用编辑区偏移（左/右、上/下、前/后），按方块世界朝向旋转
                if (node.params.length >= 3) {
                    float offX = node.params[0], offY = node.params[1], offZ = node.params[2];
                    if (offX != 0 || offY != 0 || offZ != 0) {
                        // 使用 forwardYaw 而非 blockYaw — forwardYaw 包含了 Sable 结构旋转
                        float rad = (float) Math.toRadians(seat.forwardYaw());
                        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
                        // +X = 方块右侧, +Z = 方块前方（世界空间）
                        wx += offX * cos + offZ * sin;
                        wy += offY;
                        wz += offX * sin - offZ * cos;
                    }
                }
                o[0] = wx; o[1] = wy; o[2] = wz;
            }
            case TARGET_OUT -> {
                var t = radarPos != null ? com.example.create_schematic_compute.radar.TargetAssignment.getTarget(radarPos, node.id) : null;
                if (t != null) { o[0] = (float) t.x(); o[1] = (float) t.y(); o[2] = (float) t.z(); o[3] = t.entityId(); o[4] = t.distance(); }
            }
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
            case INTEGRATOR -> {
                float inPlus = graph.getInputValue(node.id, 0, outputs);
                float inMinus = graph.getInputValue(node.id, 1, outputs);
                float inClear = graph.getInputValue(node.id, 2, outputs);
                float step = node.params.length > 0 ? node.params[0] : 1f;
                int interval = node.params.length > 1 ? Math.max(1, (int)node.params[1]) : 1;
                float limit = node.params.length > 2 ? node.params[2] : 1000f;
                int tickKey = node.id + 100000;
                float cur = pidState.getOrDefault(node.id, 0f);
                int tick = pidState.getOrDefault(tickKey, 0f).intValue();
                tick++;
                if (inClear > 0.5f) { cur = 0f; tick = 0; }
                else if (tick >= interval) {
                    tick = 0;
                    if (inPlus > 0.5f && inMinus <= 0.5f) cur += step;
                    else if (inMinus > 0.5f && inPlus <= 0.5f) cur -= step;
                    // both active → hold (no change)
                }
                cur = Math.max(0, Math.min(cur, limit));
                pidState.put(node.id, cur);
                pidState.put(tickKey, (float)tick);
                o[0] = cur;
            }
            case FORMULA -> {
                if (node.formula.isEmpty()) {
                    int nOut = Math.max(1, node.dynamicOutputCount);
                    if (o.length != nOut) { o = new float[nOut]; node.outputValues = o; }
                    for (int i = 0; i < nOut; i++) o[i] = 0;
                    break;
                }
                // Parse script (cached)
                var script = scriptCache.get(node.formula);
                if (script == null) {
                    script = FormulaParser.parseScript(node.formula);
                    scriptCache.put(node.formula, script);
                }
                // Update node state
                int nOut = Math.max(1, script.outputLabels.size());
                node.dynamicInputCount = script.inputVars.size();
                node.dynamicOutputCount = nOut;
                node.outputLabels = script.outputLabels;
                // Size output array dynamically (same pattern as ENCAPSULATION / BUS_IN)
                if (o.length != nOut) { o = new float[nOut]; node.outputValues = o; }
                // Map input pins to variable values
                var vars = new java.util.HashMap<String, Double>();
                for (int vi = 0; vi < script.inputVars.size(); vi++)
                    vars.put(script.inputVars.get(vi), (double)graph.getInputValue(node.id, vi, outputs));
                // Execute assignments in order (each isolated, prevents one bad expr from killing the script)
                for (var assign : script.assignments) {
                    try {
                        vars.put(assign.varName(), FormulaParser.evaluate(assign.rpn(), vars));
                    } catch (Exception e) {
                        vars.put(assign.varName(), 0.0);
                    }
                }
                // Evaluate each output expression
                for (int oi = 0; oi < nOut; oi++) {
                    try {
                        var rpn = script.outputRpns.get(oi);
                        o[oi] = (float)FormulaParser.evaluate(
                            oi < script.outputRpns.size() ? rpn : java.util.List.of(0.0), vars);
                    } catch (Exception e) {
                        o[oi] = 0;
                    }
                }
            }
            // Display nodes — no float output; data read from GraphNode fields by renderer
            case TEXT, DATA, IMAGE, IMAGE_SEQUENCE -> {}
            case ENCAPSULATION -> {
                if (node.subGraph == null) break;
                var outNodes = node.getSubNodes(NodeType.ENCAP_OUTPUT);
                int nOut = outNodes.size();
                // 节点数量超出上限 → 禁用所有功能，输出 0
                if (node.subGraph.nodes.size() > 1024) {
                    o = new float[nOut];
                    node.outputValues = o;
                    break;
                }
                var inpNodes = node.getSubNodes(NodeType.ENCAP_INPUT);
                o = new float[nOut];
                node.outputValues = o;
                // 复用子图 evaluator（子图不变 → evaluator 缓存复用，避免每 tick 新建）
                var subEval = subEvaluators.get(node.id);
                if (subEval == null) {
                    subEval = new GraphEvaluator(node.subGraph);
                    subEvaluators.put(node.id, subEval);
                }
                // Inject outer inputs into ENCAP_INPUT nodes
                for (int i = 0; i < inpNodes.size(); i++) {
                    float val = graph.getInputValue(node.id, i, outputs);
                    subEval.outputs.put(inpNodes.get(i).id, new float[]{val});
                }
                // Evaluate sub-graph — use RuntimeState-backed maps keyed by encap node ID
                int encapId = node.id;
                var subPidState = runtimeState != null
                    ? runtimeState.getOrCreateSubState(encapId).pidState
                    : new java.util.HashMap<Integer, Float>();
                var sdq = subDelayQueues.computeIfAbsent(encapId, k -> new java.util.HashMap<>());
                var sff = subFlipflopStates.computeIfAbsent(encapId, k -> new java.util.HashMap<>());
                var spt = subPulseTimers.computeIfAbsent(encapId, k -> new java.util.HashMap<>());
                var topo = node.subGraph.getTopoOrder();
                for (int nid : topo) {
                    var subNode = node.subGraph.findNode(nid);
                    if (subNode == null || subNode.type == NodeType.ENCAP_INPUT) continue;
                    if (subNode.type == NodeType.ENCAP_OUTPUT) {
                        float val = node.subGraph.getInputValue(nid, 0, subEval.outputs);
                        subEval.outputs.put(nid, new float[]{val});
                    } else {
                        subEval.evalExt(subNode, inputs, subPidState, dt, seat,
                            sdq, sff, spt);
                    }
                }
                // DELAY 入队（子图内）
                for (var n : node.subGraph.nodes) {
                    if (n.type == NodeType.DELAY) {
                        var q = sdq.computeIfAbsent(n.id, k -> new java.util.ArrayDeque<>());
                        int ticks = Math.max(1, (int)(n.params.length>0?n.params[0]:10));
                        q.addLast(subEval.getNodeInput(n.id, 0));
                        while (q.size() > ticks) q.pollFirst();
                    }
                }
                // Collect ENCAP_OUTPUT values
                for (int i = 0; i < outNodes.size(); i++) {
                    o[i] = node.subGraph.getInputValue(outNodes.get(i).id, 0, subEval.outputs);
                }
                // Write sub-graph state back to RuntimeState for NBT persistence
                if (runtimeState != null) {
                    RuntimeState.SubState ss = runtimeState.getOrCreateSubState(encapId);
                    ss.delayQueues.clear(); ss.delayQueues.putAll(sdq);
                    ss.flipflopStates.clear(); ss.flipflopStates.putAll(sff);
                    ss.pulseTimers.clear(); ss.pulseTimers.putAll(spt);
                }
            }
        }
        node.params = origParams; // 恢复参数（可能被连线值临时覆盖）
        outputs.put(node.id, o.clone());
    }

    public record InputSource(long freqKey, int signal) {}
    public record OutputResult(ItemStack freq1, ItemStack freq2, int signal) {}
}
