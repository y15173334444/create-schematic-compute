package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;

/** 图中的单个节点实例。 / A single node instance in the graph. */
public class GraphNode {
    public int id;
    public NodeType type;
    public float x, y;
    public float[] params;           // 数值参数（常量值、kp、ki 等）/ numeric parameters (const value, kp, ki, etc.)
    public ItemStack[] itemParams;   // 物品参数（频率堆）/ item parameters (frequency stacks)
    public String signalName = "";   // PRIVATE_IN/OUT 节点的总线名称 / bus name for PRIVATE_IN/OUT nodes
    public java.util.List<String> signalBands = new java.util.ArrayList<>(); // BUS/BUS_OUT 的频段名称 / band names for BUS/BUS_OUT
    /** BUS_OUT 内部 band→value 映射。注册到 SignalBus.CHANNELS 后，此引用与全局表共享，求值写入立即可见。
     *  BUS_OUT internal band→value map. Once registered with SignalBus.CHANNELS,
     *  this reference is shared with the global table — eval writes are immediately visible. */
    public java.util.Map<String, Float> busInternalMap;
    /** 运行时标记：此 BUS_OUT 的频道名被另一个 BUS_OUT 占用（registerChannel 返回 false）。
     *  Runtime flag: this BUS_OUT's channel name is taken by another BUS_OUT
     *  (registerChannel returned false). */
    public boolean busConflict;
    /** 频段列表是否已变更（用于避免每 tick 重复调用 registerBands）。
     *  Whether the band list has changed (avoids redundant registerBands calls per tick). */
    public boolean bandsDirty = true;
    public String formula = "";      // FORMULA 节点的公式表达式 / formula expression for FORMULA node
    public int dynamicInputCount = 0; // FORMULA 节点的动态输入数 / dynamic inputs for FORMULA node
    public int dynamicOutputCount = 1; // FORMULA 节点的动态输出数 (v1.2+) / dynamic outputs for FORMULA node (v1.2+)
    public List<String> outputLabels = null; // FORMULA 节点的 @output 名称（延迟解析）/ @output names for FORMULA node (lazy-parsed)
    public transient FormulaParser.ScriptParseResult cachedScript = null; // 缓存的解析结果 / cached parse result
    // Display node fields (Monitor block)  /  显示节点字段（Monitor 方块）
    public String displayText = "";               // TEXT 节点内容 / TEXT node content
    public int layerIndex = 0;                     // 显示编辑器中的 z 序（越高越靠前）/ z-order in display editor (higher = front)
    public int sortB = 0;                          // 图编辑器中的 B 层 z 序（越高越靠前）/ B-layer z-order in graph editor (higher = front)
    public int textColor = 0;                      // ARGB 文字颜色（0 = 使用类型默认）/ ARGB text color (0 = use type default)
    public int[] imagePixels;                      // IMAGE 节点：16×16 ARGB 像素（延迟分配）/ IMAGE node: 16×16 ARGB pixels (lazy)
    public java.util.List<int[]> imageSequenceFrames; // IMAGE_SEQUENCE 帧（延迟分配）/ IMAGE_SEQUENCE frames (lazy)
    public float layoutX = 0.5f, layoutY = 0.5f;  // 显示区域中的归一化 [0,1] 坐标 / normalized [0,1] position in display area
    public float displayScale = 1.0f;              // 大小倍数 / size multiplier
    public float displayRotation = 0f;             // 旋转角度（度）/ rotation (degrees)
    /** 每单位信号移动量（归一化坐标），默认每1信号移动1%宽度。
     *  Movement per unit signal (normalized coordinates), default 1% width per 1 signal. */
    public float moveScale = 0.01f;
    /** Comment 节点字段 / Comment node fields */
    public float commentWidth = 160;      // 默认宽度（图空间像素）/ default width in graph-space pixels
    public float commentHeight = 100;     // 默认高度（图空间像素）/ default height in graph-space pixels
    public int commentBgColor = 0xFFFFF8E7;    // 背景色，默认米色 / background color, default cream
    public int commentBorderColor = 0xFFE6D8B0; // 边框色，默认浅棕 / border color, default light brown
    public int commentTextColor = 0xFF333333;   // 文字色，默认深灰 / text color, default dark gray
    public transient int commentScrollOff = 0;  // 垂直滚动偏移（UI 状态，不持久化）/ vertical scroll offset (UI state, not persisted)
    // Remote move interpolation (for smooth multiplayer drag)  /  远程移动插值（多人拖动平滑过渡）
    public transient float remoteLerpT = 1f;
    public transient float remoteStartX, remoteStartY, remoteTargetX, remoteTargetY;

    // ── DEBUG_SIGNAL_GEN 控制点（持久化，多人协作同步）──
    // Control points for DEBUG_SIGNAL_GEN; persisted, synced via SET_CTRL_POINTS.
    // X 固定排序递增（0~1），Y 为信号值。默认两点：起点 (0,0)、终点 (1,0)。
    public float[] debugCtrlX = new float[]{0f, 1f};
    public float[] debugCtrlY = new float[]{0f, 0f};
    // 自定义公式编译缓存（避免每 tick 重新编译）
    public transient java.util.List<Object> debugFormulaRpn;

    // ── DEBUG_PROBE 历史采样（transient，不序列化）──
    // Ring buffer of recent samples; transient, not serialized.
    public transient float[] probeHistory = new float[100];
    public transient int probeHead = 0;
    public transient int probeCount = 0;
    public transient boolean probeFrozen = false;

    /** 封装节点的嵌套子图（非该类型则为 null）。/ Encapsulation node's nested sub-graph (null for other types). */
    public NodeGraph subGraph;

    /** NBT 持久化的展开/折叠状态。
     *  NBT-persisted expand/collapse state. */
    public boolean expanded = false;

    /** BUS 频段数（仅供评估器和编辑区使用，不在节点体渲染引脚）。
     *  BUS band count (for evaluator and editor use only; pins are not rendered on the node body). */
    public int bandCount() { return signalBands != null ? signalBands.size() : 0; }
    /** 有效输入引脚数（FORMULA/ENCAPSULATION 动态决定，通用参数引脚自动追加）。
     *  Effective input pin count (dynamically determined for FORMULA/ENCAPSULATION;
     *  generic parameter pins are automatically appended). */
    public int inputs() {
        if (type == NodeType.FORMULA) return Math.max(1, Math.min(dynamicInputCount, 26));
        if (type == NodeType.ENCAPSULATION && subGraph != null) return countSubNodes(NodeType.ENCAP_INPUT);
        return type.inputs + type.editableParamCount();
    }
    /** 节点主体上的功能输入引脚数（不含编辑区参数引脚，BUS_OUT 引脚仅在编辑区）。
     *  Number of functional input pins on the node body (excluding editor parameter pins;
     *  BUS_OUT pins are editor-only). */
    public int functionalInputs() { return inputs() - type.editableParamCount(); }
    public int outputs() {
        if (type == NodeType.FORMULA) return Math.max(1, Math.min(dynamicOutputCount, 16));
        if (type == NodeType.ENCAPSULATION && subGraph != null) return countSubNodes(NodeType.ENCAP_OUTPUT);
        return type.outputs;
    }
    private int countSubNodes(NodeType t) {
        int n = 0;
        for (var node : subGraph.nodes) if (node.type == t) n++;
        return n;
    }
    /** 获取内部 ENCAP_INPUT/OUTPUT 节点，按 Y 位置排序（上→下），以 ID 作为决胜条件
     *  以保证确定性排序。这使得外部引脚顺序与内部视觉布局一致。
     *  Get internal ENCAP_INPUT/OUTPUT nodes sorted by Y position (top → bottom),
     *  tie-breaking by ID for deterministic ordering.
     *  This makes the external pin order match the internal visual layout. */
    public java.util.List<GraphNode> getSubNodes(NodeType t) {
        var list = new java.util.ArrayList<GraphNode>();
        if (subGraph != null) for (var n : subGraph.nodes) if (n.type == t) list.add(n);
        list.sort(java.util.Comparator.<GraphNode>comparingDouble(n -> n.y).thenComparingInt(n -> n.id));
        return list;
    }
    /** 输入引脚标签（FORMULA 用变量名，ENCAPSULATION 用内部节点名，BUS_OUT 用频段名）。
     *  Input pin label (variable names for FORMULA, internal node names for ENCAPSULATION,
     *  band names for BUS_OUT). */
    public String inputLabel(int i) {
        if (type == NodeType.BUS_OUT && signalBands != null && i < signalBands.size())
            return signalBands.get(i);
        if (type == NodeType.FORMULA && !formula.isEmpty()) {
            if (cachedScript == null) cachedScript = FormulaParser.parseScript(formula);
            dynamicInputCount = Math.max(1, cachedScript.inputVars.size());
            if (i < cachedScript.inputVars.size()) return cachedScript.inputVars.get(i);
        }
        if (type == NodeType.ENCAPSULATION) {
            var ins = getSubNodes(NodeType.ENCAP_INPUT);
            if (i < ins.size()) {
                String name = ins.get(i).displayText;
                return name.isEmpty() ? "in" + (i + 1) : name;
            }
        }
        // 参数输入引脚：显示参数名（如 "step", "kp" 等）  /  Param input pins: show param names (e.g. "step", "kp", etc.)
        int extraBase = type.inputs;
        int paramIdx = i - extraBase;
        if (paramIdx >= 0 && paramIdx < type.paramNames.length) return type.paramNames[paramIdx];
        return type.inputLabel(i);
    }
    /** 输出引脚标签（FORMULA 用 @output 名，ENCAPSULATION 用内部节点名，BUS_IN 用频段名）。
     *  Output pin label (@output names for FORMULA, internal node names for ENCAPSULATION,
     *  band names for BUS_IN). */
    public String outputLabel(int i) {
        if (type == NodeType.BUS_IN && signalBands != null && i < signalBands.size())
            return signalBands.get(i);
        if (type == NodeType.FORMULA && !formula.isEmpty()) {
            if (cachedScript == null) cachedScript = FormulaParser.parseScript(formula);
            outputLabels = cachedScript.outputLabels;
            dynamicOutputCount = Math.max(1, cachedScript.outputLabels.size());
            if (i < outputLabels.size()) {
                String name = outputLabels.get(i);
                return name.isEmpty() ? type.outputLabel(i) : name;
            }
            return type.outputLabel(i);
        }
        if (type == NodeType.ENCAPSULATION) {
            var outs = getSubNodes(NodeType.ENCAP_OUTPUT);
            if (i < outs.size()) {
                String name = outs.get(i).displayText;
                return name.isEmpty() ? "out" + (i + 1) : name;
            }
        }
        return type.outputLabel(i);
    }

    // 运行时计算值（由 evaluator 填充）/ Runtime computed values (filled by evaluator)
    public float[] outputValues;

    public GraphNode(int id, NodeType type, float x, float y) {
        this.id = id;
        this.type = type;
        this.x = x;
        this.y = y;
        // ENCAP_INPUT/OUTPUT 的 name 存在 displayText 中，无需 params
        // ENCAP_INPUT/OUTPUT name is stored in displayText; no params needed
        this.params = (type == NodeType.ENCAP_INPUT || type == NodeType.ENCAP_OUTPUT)
            ? new float[0] : new float[type.paramNames.length];
        this.itemParams = new ItemStack[0];
        // REDSTONE nodes always have 2 frequency slots / REDSTONE 节点始终有 2 个频率槽
        if (type == NodeType.REDSTONE_IN || type == NodeType.REDSTONE_OUT)
            this.itemParams = new ItemStack[]{ItemStack.EMPTY, ItemStack.EMPTY};
        // 设置基于参数的节点的默认值 / Set defaults for param-based nodes
        if (type == NodeType.CONST) this.params[0] = 1.0f;
        if (type == NodeType.PID) {
            this.params[0] = 1.0f;   // kp
            this.params[1] = 0.1f;   // ki
            this.params[2] = 0.05f;  // kd
            this.params[3] = 1.0f;   // scale
            this.params[4] = 3.0f;   // ilimit
        }
        if (type == NodeType.PID_POWER) {
            this.params[0] = 2.0f;   // kp
            this.params[1] = 0.05f;  // ki
            this.params[2] = 3.0f;   // kd
            this.params[3] = 3.0f;   // ilimit
        }
        if (type == NodeType.T_FLIPFLOP) { this.params = new float[2]; this.params[0] = 0f; this.params[1] = 0f; } // 默认关，当前关 / default off, current off
        if (type == NodeType.LATCH) { this.params = new float[2]; this.params[0] = 0f; this.params[1] = 0f; } // 默认复位，当前复位 / default reset, current reset
        if (type == NodeType.ACCUMULATOR) this.params[0] = 1f; // step=1
        if (type == NodeType.INTEGRATOR) {
            if (this.params.length > 0 && this.params[0] == 0f) this.params[0] = 1f;   // step
            if (this.params.length > 1 && this.params[1] == 0f) this.params[1] = 1f;   // interval
            if (this.params.length > 2 && this.params[2] == 0f) this.params[2] = 1000f; // limit
        }
        if (type == NodeType.ROUND) this.params[0] = 2f;      // 2 位小数  /  2 decimal places
        if (type == NodeType.DELAY) this.params[0] = 10f;     // 10 ticks
        if (type == NodeType.PULSE_EXTEND) this.params[0] = 10f;
        if (type == NodeType.LOOP) { this.params[0] = 5f; this.params[1] = 10f; }
        if (type == NodeType.FUSE) this.params[0] = 40f; // cooldown=40 ticks
        if (type == NodeType.BOOL) this.params[0] = 0f;  // inverted=0 (默认不反转 / default not inverted)
        if (type == NodeType.GATE) { this.params = new float[2]; this.params[0] = 0f; this.params[1] = 0f; } // 默认关，当前关 / default closed, current closed
        if (type == NodeType.KEYBOARD) this.params[0] = 0f; // 默认 A  /  default A
        if (type == NodeType.GAMEPAD_BUTTON) this.params[0] = 0f; // 默认 A  /  default A
        // IMAGE/IMAGE_SEQUENCE：延迟分配像素数组 + 设置参数默认值
        // IMAGE/IMAGE_SEQUENCE: lazy-allocate pixel array + set param defaults
        if (type == NodeType.IMAGE || type == NodeType.IMAGE_SEQUENCE) {
            this.imagePixels = new int[256];
            java.util.Arrays.fill(this.imagePixels, 0x00000000);
            if (this.params.length > 0 && this.params[0] == 0f) this.params[0] = 0.01f; // moveScaleX
            if (this.params.length > 1 && this.params[1] == 0f) this.params[1] = 0.01f; // moveScaleY
            if (this.params.length > 2 && this.params[2] == 0f) this.params[2] = 1f;    // rotationScale
            // invertX, invertY 默认保持 0  /  invertX, invertY defaults stay 0
        }
        // FORMULA：默认 "A+B"，折叠视图显示 2 输入 + 1 输出
        // FORMULA: default to "A+B" so collapsed view shows 2 inputs + 1 output
        if (type == NodeType.FORMULA) {
            this.formula = "A+B";
            this.dynamicInputCount = 2;
            this.dynamicOutputCount = 1;
            this.outputLabels = java.util.List.of("");
        }
        // DEBUG_SIGNAL_GEN：默认手动曲线 + 频率发生
        // DEBUG_SIGNAL_GEN: default manual curve + frequency generate
        if (type == NodeType.DEBUG_SIGNAL_GEN) {
            this.params[0] = 0f;       // setMode = 手动曲线 / manual curve
            this.params[1] = 0f;       // outMode = 频率发生 / frequency generate
            this.params[2] = 1f / 20f; // speed (每 tick 推进 1/20，1 秒循环一次)
            this.params[3] = 1f;       // amplitude
            this.params[4] = 0.5f;     // inputX (指定模式下的 x 值)
        }
        // DEBUG_PROBE：默认窗口 50 tick，自动缩放
        // DEBUG_PROBE: default window 50 ticks, auto-scale
        if (type == NodeType.DEBUG_PROBE) {
            this.params[0] = 50f;     // windowSize
            this.params[1] = 1f;      // autoScale = on
        }
        // ENCAPSULATION：outputValues 在 eval 期间根据 subGraph 动态调整大小
        // ENCAPSULATION: outputValues resized dynamically during eval based on subGraph
        this.outputValues = new float[type == NodeType.ENCAPSULATION ? 0 : type.outputs];
    }

    /** 深拷贝除 id 外的所有字段（id 由 {@code newId} 指定）。递归复制 {@code subGraph}。
     *  Deep-copy all fields except id (which is assigned from {@code newId}). Recursively copies {@code subGraph}. */
    public GraphNode shallowCopyWithNewId(int newId) {
        GraphNode n = new GraphNode(newId, type, x, y);
        System.arraycopy(params, 0, n.params, 0, Math.min(params.length, n.params.length));
        if (itemParams != null) n.itemParams = itemParams.clone();
        n.signalName = signalName;
        if (signalBands != null) n.signalBands = new java.util.ArrayList<>(signalBands);
        if (busInternalMap != null) n.busInternalMap = new java.util.HashMap<>(busInternalMap);
        n.formula = formula;
        n.dynamicInputCount = dynamicInputCount;
        n.dynamicOutputCount = dynamicOutputCount;
        if (outputLabels != null) n.outputLabels = new java.util.ArrayList<>(outputLabels);
        n.displayText = displayText;
        n.textColor = textColor;
        if (imagePixels != null) n.imagePixels = imagePixels.clone();
        if (debugCtrlX != null) n.debugCtrlX = debugCtrlX.clone();
        if (debugCtrlY != null) n.debugCtrlY = debugCtrlY.clone();
        if (imageSequenceFrames != null) {
            n.imageSequenceFrames = new java.util.ArrayList<>();
            for (int[] f : imageSequenceFrames) n.imageSequenceFrames.add(f.clone());
        }
        n.layoutX = layoutX; n.layoutY = layoutY;
        n.sortB = sortB + 1; // 将副本带到最前面 / bring copy to front
        n.displayScale = displayScale; n.displayRotation = displayRotation;
        n.moveScale = moveScale;
        n.commentWidth = commentWidth;
        n.commentHeight = commentHeight;
        n.commentBgColor = commentBgColor;
        n.commentBorderColor = commentBorderColor;
        n.commentTextColor = commentTextColor;
        if (subGraph != null) n.subGraph = subGraph.copy();
        n.expanded = expanded;
        n.busConflict = busConflict;
        n.bandsDirty = bandsDirty;
        return n;
    }

    // 保存到 NBT / Save to NBT
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putString("type", type.id);
        tag.putFloat("x", x);
        tag.putFloat("y", y);
        tag.putInt("pcount", params.length);
        for (int i = 0; i < params.length; i++)
            tag.putFloat("p" + i, params[i]);
        tag.putInt("icount", itemParams.length);
        for (int i = 0; i < itemParams.length; i++)
            tag.put("i" + i, itemParams[i].saveOptional(registries));
        if (!signalName.isEmpty()) tag.putString("sig", signalName);
        if (signalBands != null && !signalBands.isEmpty()) {
            ListTag bandsTag = new ListTag();
            for (String b : signalBands) bandsTag.add(net.minecraft.nbt.StringTag.valueOf(b));
            tag.put("bands", bandsTag);
        }
        // BUS_OUT internalMap — 仅保存本地数据，不保存全局状态
        // BUS_OUT internalMap — only save local data, not global state
        if (type == NodeType.BUS_OUT && busInternalMap != null && !busInternalMap.isEmpty()) {
            CompoundTag busData = new CompoundTag();
            for (var e : busInternalMap.entrySet())
                busData.putFloat(e.getKey(), e.getValue());
            tag.put("busData", busData);
        }
        if (!formula.isEmpty()) tag.putString("formula", formula);
        // 控制点持久化（DEBUG_SIGNAL_GEN 手动曲线模式）
        // Control point persistence (DEBUG_SIGNAL_GEN manual curve mode)
        if (type == NodeType.DEBUG_SIGNAL_GEN && debugCtrlX != null && debugCtrlY != null) {
            int[] dcx = new int[debugCtrlX.length];
            int[] dcy = new int[debugCtrlY.length];
            for (int i = 0; i < debugCtrlX.length; i++) dcx[i] = Float.floatToRawIntBits(debugCtrlX[i]);
            for (int i = 0; i < debugCtrlY.length; i++) dcy[i] = Float.floatToRawIntBits(debugCtrlY[i]);
            tag.putIntArray("dcx", dcx);
            tag.putIntArray("dcy", dcy);
        }
        if (dynamicInputCount > 0) tag.putInt("din", dynamicInputCount);
        if (type == NodeType.FORMULA && dynamicOutputCount > 1) tag.putInt("dout", dynamicOutputCount);
        if (type == NodeType.FORMULA && outputLabels != null && !outputLabels.isEmpty()) {
            ListTag lbls = new ListTag();
            for (String l : outputLabels) lbls.add(net.minecraft.nbt.StringTag.valueOf(l));
            tag.put("outlbls", lbls);
        }
        if (type == NodeType.COMMENT) {
            tag.putFloat("cw", commentWidth);
            tag.putFloat("ch", commentHeight);
            if (commentBgColor != 0xFFFFF8E7) tag.putInt("cbg", commentBgColor);
            if (commentBorderColor != 0xFFE6D8B0) tag.putInt("cbr", commentBorderColor);
            if (commentTextColor != 0xFF333333) tag.putInt("ctx", commentTextColor);
        }
        if (!displayText.isEmpty()) tag.putString("dtext", displayText);
        if (textColor != 0) tag.putInt("tcol", textColor);
        if (type == NodeType.IMAGE || type == NodeType.IMAGE_SEQUENCE) {
            if (imagePixels != null) tag.putIntArray("ipx", imagePixels);
        }
        if (type == NodeType.IMAGE_SEQUENCE && imageSequenceFrames != null && !imageSequenceFrames.isEmpty()) {
            ListTag framesTag = new ListTag();
            for (int[] frame : imageSequenceFrames) {
                framesTag.add(new net.minecraft.nbt.IntArrayTag(frame));
            }
            tag.put("iframes", framesTag);
        }
        tag.putFloat("lx", layoutX);
        tag.putFloat("ly", layoutY);
        tag.putFloat("ds", displayScale);
        tag.putFloat("dr", displayRotation);
        if (moveScale != 0.01f) tag.putFloat("ms", moveScale);
        if (layerIndex != 0) tag.putInt("layer", layerIndex);
        if (sortB != 0) tag.putInt("zb", sortB);
        if (expanded) tag.putBoolean("expanded", true);
        if (busConflict) tag.putBoolean("busConflict", true);
        if (subGraph != null) tag.put("subGraph", subGraph.save(registries));
        return tag;
    }

    public static GraphNode load(CompoundTag tag, HolderLookup.Provider registries) {
        NodeType type = NodeType.BY_ID.get(tag.getString("type"));
        if (type == null) type = NodeType.CONST; // 损坏数据的回退  /  fallback for corrupted data
        GraphNode node = new GraphNode(tag.getInt("id"), type, tag.getFloat("x"), tag.getFloat("y"));
        int pc = tag.getInt("pcount");
        for (int i = 0; i < pc && i < node.params.length; i++)
            node.params[i] = tag.getFloat("p" + i);
        int ic = tag.getInt("icount");
        node.itemParams = new ItemStack[ic];
        for (int i = 0; i < ic; i++) {
            if (tag.contains("i" + i, Tag.TAG_COMPOUND))
                node.itemParams[i] = ItemStack.parseOptional(registries, tag.getCompound("i" + i));
            else
                node.itemParams[i] = ItemStack.EMPTY;
        }
        // Legacy save compatibility: REDSTONE nodes always need at least 2 frequency slots
        // 旧存档兼容：REDSTONE 节点始终需要至少 2 个频率槽
        if ((node.type == NodeType.REDSTONE_IN || node.type == NodeType.REDSTONE_OUT) && node.itemParams.length < 2) {
            ItemStack[] expanded = new ItemStack[]{ItemStack.EMPTY, ItemStack.EMPTY};
            System.arraycopy(node.itemParams, 0, expanded, 0, node.itemParams.length);
            node.itemParams = expanded;
        }
        if (tag.contains("sig")) node.signalName = tag.getString("sig");
        if (tag.contains("bands")) {
            ListTag bandsTag = tag.getList("bands", Tag.TAG_STRING);
            node.signalBands = new java.util.ArrayList<>();
            for (int i = 0; i < bandsTag.size(); i++)
                node.signalBands.add(bandsTag.getString(i));
        }
        if (node.type == NodeType.BUS_OUT && tag.contains("busData")) {
            CompoundTag busData = tag.getCompound("busData");
            node.busInternalMap = new java.util.HashMap<>();
            for (String key : busData.getAllKeys())
                node.busInternalMap.put(key, busData.getFloat(key));
        }
        if (tag.contains("formula")) node.formula = tag.getString("formula");
        if (tag.contains("din")) node.dynamicInputCount = tag.getInt("din");
        if (tag.contains("dout")) node.dynamicOutputCount = tag.getInt("dout");
        if (tag.contains("outlbls")) {
            ListTag lbls = tag.getList("outlbls", Tag.TAG_STRING);
            node.outputLabels = new java.util.ArrayList<>();
            for (int i = 0; i < lbls.size(); i++)
                node.outputLabels.add(lbls.getString(i));
        }
        if (tag.contains("cw")) node.commentWidth = tag.getFloat("cw");
        if (tag.contains("ch")) node.commentHeight = tag.getFloat("ch");
        if (tag.contains("cc")) node.commentBgColor = tag.getInt("cc"); // 旧版键 / legacy key
        if (tag.contains("cbg")) node.commentBgColor = tag.getInt("cbg");
        if (tag.contains("cbr")) node.commentBorderColor = tag.getInt("cbr");
        if (tag.contains("ctx")) node.commentTextColor = tag.getInt("ctx");
        if (tag.contains("dtext")) node.displayText = tag.getString("dtext");
        if (tag.contains("tcol")) node.textColor = tag.getInt("tcol");
        if (tag.contains("ipx")) node.imagePixels = tag.getIntArray("ipx");
        // 控制点加载（DEBUG_SIGNAL_GEN 手动曲线模式）
        // Control point loading (DEBUG_SIGNAL_GEN manual curve mode)
        if (node.type == NodeType.DEBUG_SIGNAL_GEN) {
            if (tag.contains("dcx")) {
                int[] dcx = tag.getIntArray("dcx");
                node.debugCtrlX = new float[dcx.length];
                for (int i = 0; i < dcx.length; i++) node.debugCtrlX[i] = Float.intBitsToFloat(dcx[i]);
            }
            if (tag.contains("dcy")) {
                int[] dcy = tag.getIntArray("dcy");
                node.debugCtrlY = new float[dcy.length];
                for (int i = 0; i < dcy.length; i++) node.debugCtrlY[i] = Float.intBitsToFloat(dcy[i]);
            }
        }
        if (tag.contains("iframes")) {
            var framesTag = tag.getList("iframes", Tag.TAG_INT_ARRAY);
            node.imageSequenceFrames = new java.util.ArrayList<>();
            for (int i = 0; i < framesTag.size(); i++) {
                node.imageSequenceFrames.add(framesTag.getIntArray(i));
            }
        }
        if (tag.contains("lx")) node.layoutX = tag.getFloat("lx");
        if (tag.contains("ly")) node.layoutY = tag.getFloat("ly");
        if (tag.contains("ds")) node.displayScale = tag.getFloat("ds");
        if (tag.contains("dr")) node.displayRotation = tag.getFloat("dr");
        if (tag.contains("ms")) node.moveScale = tag.getFloat("ms");
        if (tag.contains("layer")) node.layerIndex = tag.getInt("layer");
        if (tag.contains("zb")) node.sortB = tag.getInt("zb");
        node.expanded = tag.getBoolean("expanded");
        node.busConflict = tag.getBoolean("busConflict");
        if (tag.contains("subGraph")) node.subGraph = NodeGraph.load(tag.getCompound("subGraph"), registries);
        return node;
    }
}
