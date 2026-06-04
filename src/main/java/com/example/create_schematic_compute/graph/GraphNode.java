package com.example.create_schematic_compute.graph;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/** A single node instance in the graph. */
public class GraphNode {
    public int id;
    public NodeType type;
    public float x, y;
    public float[] params;           // numeric parameters (const value, kp, ki, etc.)
    public ItemStack[] itemParams;   // item parameters (frequency stacks)
    public String signalName = "";   // channel name for PRIVATE_IN/OUT nodes

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
        }
        if (type == NodeType.PID_POWER) {
            this.params[0] = 2.0f;   // kp
            this.params[1] = 0.05f;  // ki
            this.params[2] = 0.02f;  // kd
        }
        if (type == NodeType.DELAY) this.params[0] = 10f;     // 10 ticks
        if (type == NodeType.PULSE_EXTEND) this.params[0] = 10f;
        if (type == NodeType.LOOP) { this.params[0] = 5f; this.params[1] = 10f; }
        if (type == NodeType.FUSE) this.params[0] = 40f; // cooldown=40 ticks
        if (type == NodeType.BOOL) this.params[0] = 0f;  // inverted=0 (默认不反转)
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
        return node;
    }
}
