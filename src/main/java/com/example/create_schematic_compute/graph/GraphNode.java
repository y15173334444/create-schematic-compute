package com.example.create_schematic_compute.graph;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;

/** A single node instance in the graph. */
public class GraphNode {
    public int id;
    public NodeType type;
    public float x, y;
    public float[] params;           // numeric parameters (const value, kp, ki, etc.)
    public ItemStack[] itemParams;   // item parameters (frequency stacks)
    public String signalName = "";   // channel name for PRIVATE_IN/OUT nodes
    public String formula = "";      // formula expression for FORMULA node
    public int dynamicInputCount = 0; // dynamic inputs for FORMULA node
    // Display node fields (Monitor block)
    public String displayText = "";               // TEXT node content
    public int textColor = 0;                      // ARGB text color (0 = use type default)
    public int[] imagePixels;                      // IMAGE node: 16×16 ARGB pixels (lazy)
    public java.util.List<int[]> imageSequenceFrames; // IMAGE_SEQUENCE frames (lazy)
    public float layoutX = 0.5f, layoutY = 0.5f;  // normalized [0,1] position in display area
    public float displayScale = 1.0f;              // size multiplier
    public float displayRotation = 0f;             // rotation (degrees)
    /** 每单位信号移动量（归一化坐标），默认每1信号移动1%宽度 */
    public float moveScale = 0.01f;
    /** Encapsulation node's nested sub-graph (null for other types) */
    public NodeGraph subGraph;

    /** NBT 持久化的展开/折叠状态 */
    public boolean expanded = false;

    /** 有效输入引脚数（FORMULA/ENCAPSULATION 动态决定，通用参数引脚自动追加） */
    public int inputs() {
        if (type == NodeType.FORMULA) return Math.max(1, Math.min(dynamicInputCount, 26));
        if (type == NodeType.ENCAPSULATION && subGraph != null) return countSubNodes(NodeType.ENCAP_INPUT);
        return type.inputs + type.editableParamCount();
    }
    /** 节点主体上的功能输入引脚数（不含编辑区参数引脚） */
    public int functionalInputs() {
        return inputs() - type.editableParamCount();
    }
    public int outputs() {
        if (type == NodeType.ENCAPSULATION && subGraph != null) return countSubNodes(NodeType.ENCAP_OUTPUT);
        return type.outputs;
    }
    private int countSubNodes(NodeType t) {
        int n = 0;
        for (var node : subGraph.nodes) if (node.type == t) n++;
        return n;
    }
    /** Get internal ENCAP_INPUT/OUTPUT nodes sorted by ID */
    public java.util.List<GraphNode> getSubNodes(NodeType t) {
        var list = new java.util.ArrayList<GraphNode>();
        if (subGraph != null) for (var n : subGraph.nodes) if (n.type == t) list.add(n);
        list.sort(java.util.Comparator.comparingInt(n -> n.id));
        return list;
    }
    /** 输入引脚标签（FORMULA 用变量名，ENCAPSULATION 用内部节点名） */
    public String inputLabel(int i) {
        if (type == NodeType.FORMULA && !formula.isEmpty()) {
            var vars = FormulaParser.extractVariables(formula);
            if (i < vars.size()) return vars.get(i);
        }
        if (type == NodeType.ENCAPSULATION) {
            var ins = getSubNodes(NodeType.ENCAP_INPUT);
            if (i < ins.size()) {
                String name = ins.get(i).displayText;
                return name.isEmpty() ? "in" + (i + 1) : name;
            }
        }
        // 参数输入引脚：显示参数名（如 "step", "kp" 等）
        int extraBase = type.inputs;
        int paramIdx = i - extraBase;
        if (paramIdx >= 0 && paramIdx < type.paramNames.length) return type.paramNames[paramIdx];
        return type.inputLabel(i);
    }
    /** 输出引脚标签（ENCAPSULATION 用内部节点名） */
    public String outputLabel(int i) {
        if (type == NodeType.ENCAPSULATION) {
            var outs = getSubNodes(NodeType.ENCAP_OUTPUT);
            if (i < outs.size()) {
                String name = outs.get(i).displayText;
                return name.isEmpty() ? "out" + (i + 1) : name;
            }
        }
        return type.outputLabel(i);
    }

    // Runtime computed values (filled by evaluator)
    public float[] outputValues;

    public GraphNode(int id, NodeType type, float x, float y) {
        this.id = id;
        this.type = type;
        this.x = x;
        this.y = y;
        // ENCAP_INPUT/OUTPUT 的 name 存在 displayText 中，无需 params
        this.params = (type == NodeType.ENCAP_INPUT || type == NodeType.ENCAP_OUTPUT)
            ? new float[0] : new float[type.paramNames.length];
        this.itemParams = new ItemStack[0];
        // Set defaults for param-based nodes
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
            this.params[2] = 3.0f;   // ilimit
        }
        if (type == NodeType.T_FLIPFLOP) this.params[0] = 0f; // default off
        if (type == NodeType.ACCUMULATOR) this.params[0] = 1f; // step=1
        if (type == NodeType.INTEGRATOR) {
            if (this.params.length > 0 && this.params[0] == 0f) this.params[0] = 1f;   // step
            if (this.params.length > 1 && this.params[1] == 0f) this.params[1] = 1f;   // interval
            if (this.params.length > 2 && this.params[2] == 0f) this.params[2] = 1000f; // limit
        }
        if (type == NodeType.ROUND) this.params[0] = 2f;      // 2 decimal places
        if (type == NodeType.DELAY) this.params[0] = 10f;     // 10 ticks
        if (type == NodeType.PULSE_EXTEND) this.params[0] = 10f;
        if (type == NodeType.LOOP) { this.params[0] = 5f; this.params[1] = 10f; }
        if (type == NodeType.FUSE) this.params[0] = 40f; // cooldown=40 ticks
        if (type == NodeType.BOOL) this.params[0] = 0f;  // inverted=0 (默认不反转)
        if (type == NodeType.GATE) { this.params = new float[2]; this.params[0] = 0f; this.params[1] = 0f; } // default closed, current state closed
        if (type == NodeType.KEYBOARD) this.params[0] = 0f; // 默认 A
        if (type == NodeType.GAMEPAD_BUTTON) this.params[0] = 0f; // 默认 A
        // IMAGE/IMAGE_SEQUENCE: lazy-allocate pixel array + set param defaults
        if (type == NodeType.IMAGE || type == NodeType.IMAGE_SEQUENCE) {
            this.imagePixels = new int[256];
            java.util.Arrays.fill(this.imagePixels, 0x00000000);
            if (this.params.length > 0 && this.params[0] == 0f) this.params[0] = 0.01f; // moveScaleX
            if (this.params.length > 1 && this.params[1] == 0f) this.params[1] = 0.01f; // moveScaleY
            if (this.params.length > 2 && this.params[2] == 0f) this.params[2] = 1f;    // rotationScale
            // invertX, invertY defaults stay 0
        }
        // ENCAPSULATION: outputValues resized dynamically during eval based on subGraph
        this.outputValues = new float[type == NodeType.ENCAPSULATION ? 0 : type.outputs];
    }

    /** Deep-copy all fields except id (which is assigned from {@code newId}). Recursively copies {@code subGraph}. */
    public GraphNode shallowCopyWithNewId(int newId) {
        GraphNode n = new GraphNode(newId, type, x, y);
        System.arraycopy(params, 0, n.params, 0, Math.min(params.length, n.params.length));
        if (itemParams != null) n.itemParams = itemParams.clone();
        n.signalName = signalName;
        n.formula = formula;
        n.dynamicInputCount = dynamicInputCount;
        n.displayText = displayText;
        n.textColor = textColor;
        if (imagePixels != null) n.imagePixels = imagePixels.clone();
        if (imageSequenceFrames != null) {
            n.imageSequenceFrames = new java.util.ArrayList<>();
            for (int[] f : imageSequenceFrames) n.imageSequenceFrames.add(f.clone());
        }
        n.layoutX = layoutX; n.layoutY = layoutY;
        n.displayScale = displayScale; n.displayRotation = displayRotation;
        n.moveScale = moveScale;
        if (subGraph != null) n.subGraph = subGraph.copy();
        n.expanded = false; // 副本默认折叠
        return n;
    }

    // Save to NBT
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
        if (!formula.isEmpty()) tag.putString("formula", formula);
        if (dynamicInputCount > 0) tag.putInt("din", dynamicInputCount);
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
        if (expanded) tag.putBoolean("expanded", true);
        if (subGraph != null) tag.put("subGraph", subGraph.save(registries));
        return tag;
    }

    public static GraphNode load(CompoundTag tag, HolderLookup.Provider registries) {
        NodeType type = NodeType.BY_ID.get(tag.getString("type"));
        if (type == null) type = NodeType.CONST; // fallback for corrupted data
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
        if (tag.contains("sig")) node.signalName = tag.getString("sig");
        if (tag.contains("formula")) node.formula = tag.getString("formula");
        if (tag.contains("din")) node.dynamicInputCount = tag.getInt("din");
        if (tag.contains("dtext")) node.displayText = tag.getString("dtext");
        if (tag.contains("tcol")) node.textColor = tag.getInt("tcol");
        if (tag.contains("ipx")) node.imagePixels = tag.getIntArray("ipx");
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
        node.expanded = tag.getBoolean("expanded"); // 旧存档无此 key 返回 false
        if (tag.contains("subGraph")) node.subGraph = NodeGraph.load(tag.getCompound("subGraph"), registries);
        return node;
    }
}
