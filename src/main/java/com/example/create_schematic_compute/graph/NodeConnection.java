package com.example.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;

/** A connection from an output pin to an input pin. */
public class NodeConnection {
    public int fromId;    // source node
    public int fromPin;   // source output pin index
    public int toId;      // target node
    public int toPin;     // target input pin index

    public NodeConnection(int fromId, int fromPin, int toId, int toPin) {
        this.fromId = fromId;
        this.fromPin = fromPin;
        this.toId = toId;
        this.toPin = toPin;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("from", fromId);
        t.putInt("fPin", fromPin);
        t.putInt("to", toId);
        t.putInt("tPin", toPin);
        return t;
    }

    public static NodeConnection load(CompoundTag t) {
        return new NodeConnection(t.getInt("from"), t.getInt("fPin"),
                t.getInt("to"), t.getInt("tPin"));
    }
}
