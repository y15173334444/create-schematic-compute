package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;

/** A connection from an output pin to an input pin.
 *  <p>Since v1.2.4, connections bind to stable {@code pinId} strings rather than
 *  positional integer indices. The {@code fromPin}/{@code toPin} ints are
 *  cached resolved indices (set during {@link NodeGraph#rebuildInputCache()})
 *  and are no longer the source of truth for pin identity.</p> */
public class NodeConnection {
    public int fromId;       // source node
    public int fromPin;      // source output pin index (cached, derived from fromPinId)
    public int toId;         // target node
    public int toPin;        // target input pin index (cached, derived from toPinId)

    /** Stable pin identifiers (v1.2.4+). {@code null} for legacy connections
     *  that haven't been migrated yet — in that case {@code fromPin/toPin}
     *  are the fallback. */
    public String fromPinId, toPinId;

    public NodeConnection(int fromId, int fromPin, int toId, int toPin) {
        this.fromId = fromId;
        this.fromPin = fromPin;
        this.toId = toId;
        this.toPin = toPin;
    }

    public NodeConnection(int fromId, String fromPinId, int fromPin,
                          int toId, String toPinId, int toPin) {
        this.fromId = fromId;
        this.fromPinId = fromPinId;
        this.fromPin = fromPin;
        this.toId = toId;
        this.toPinId = toPinId;
        this.toPin = toPin;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("from", fromId);
        t.putInt("fPin", fromPin);
        t.putInt("to", toId);
        t.putInt("tPin", toPin);
        if (fromPinId != null) t.putString("fPinId", fromPinId);
        if (toPinId != null) t.putString("tPinId", toPinId);
        return t;
    }

    public static NodeConnection load(CompoundTag t) {
        int fromId = t.getInt("from");
        int fromPin = t.getInt("fPin");
        int toId = t.getInt("to");
        int toPin = t.getInt("tPin");
        NodeConnection c = new NodeConnection(fromId, fromPin, toId, toPin);
        if (t.contains("fPinId")) c.fromPinId = t.getString("fPinId");
        if (t.contains("tPinId")) c.toPinId = t.getString("tPinId");
        return c;
    }
}
