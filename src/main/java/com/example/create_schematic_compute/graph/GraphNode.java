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
    /** 有效输入引脚数（FORMULA 用 dynamicInputCount，其他用 type.inputs） */
    public int inputs() { return type == NodeType.FORMULA ? Math.max(1, Math.min(dynamicInputCount, 26)) : type.inputs; }
    public int outputs() { return type.outputs; }
    /** 输入引脚标签（FORMULA 用公式中的变量名，其他用 type 的默认） */
    public String inputLabel(int i) {
        if (type == NodeType.FORMULA && !formula.isEmpty()) {
            var vars = FormulaParser.extractVariables(formula);
            if (i < vars.size()) return vars.get(i);
        }
        return type.inputLabel(i);
    }

    // Runtime computed values (filled by evaluator)
    public float[] outputValues;

    public GraphNode(int id, NodeType type, float x, float y) {
        this.id = id;
        this.type = type;
        this.x = x;
        this.y = y;
        this.params = new float[type.paramNames.length];
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
        if (type == NodeType.DELAY) this.params[0] = 10f;     // 10 ticks
        if (type == NodeType.PULSE_EXTEND) this.params[0] = 10f;
        if (type == NodeType.LOOP) { this.params[0] = 5f; this.params[1] = 10f; }
        if (type == NodeType.FUSE) this.params[0] = 40f; // cooldown=40 ticks
        if (type == NodeType.BOOL) this.params[0] = 0f;  // inverted=0 (默认不反转)
        if (type == NodeType.KEYBOARD) this.params[0] = 0f; // 默认 A
        if (type == NodeType.GAMEPAD_BUTTON) this.params[0] = 0f; // 默认 A
        // IMAGE/IMAGE_SEQUENCE: lazy-allocate pixel array (saves memory for other node types)
        if (type == NodeType.IMAGE || type == NodeType.IMAGE_SEQUENCE) {
            this.imagePixels = new int[256];
            java.util.Arrays.fill(this.imagePixels, 0x00000000);
        }
        this.outputValues = new float[type.outputs];
    }

    // Save to NBT
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putInt("type", type.ordinal());
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
        if (moveScale != 0.01f) tag.putFloat("ms", moveScale);
        return tag;
    }

    public static GraphNode load(CompoundTag tag, HolderLookup.Provider registries) {
        NodeType type = NodeType.values()[tag.getInt("type")];
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
        if (tag.contains("ms")) node.moveScale = tag.getFloat("ms");
        return node;
    }
}
