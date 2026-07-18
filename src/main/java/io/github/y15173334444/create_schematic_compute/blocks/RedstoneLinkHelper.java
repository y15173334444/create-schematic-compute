package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.ModUtils;
import io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Composition-based helper for Redstone Link network integration.
 * Eliminates ~200 lines of duplicated code across 5 block entities.
 *
 * Usage: each BlockEntity creates a RedstoneLinkHelper(this) and delegates
 * registerLinks() / unregisterLinks() / refreshInputs() / buildInputs() / writeOutputs() to it.
 */
public class RedstoneLinkHelper {
    private final BlockEntity owner;
    private final BlockPos worldPosition;
    private final List<FreqLink> freqLinks = new ArrayList<>();
    private final Map<Long, Integer> lastInputs = new HashMap<>();
    private final Map<Long, Integer> lastOutputs = new HashMap<>();
    private NodeGraph lastLinkedGraph;
    private int lastLinkGeneration = -1;

    public record FreqLink(long freqKey, IRedstoneLinkable linkable) {}

    public RedstoneLinkHelper(BlockEntity owner) {
        this.owner = owner;
        this.worldPosition = owner.getBlockPos();
    }

    public Map<Long, Integer> lastInputs() { return lastInputs; }
    public int getInput(long freqKey) { return lastInputs.getOrDefault(freqKey, 0); }
    public void putInput(long freqKey, int signal) { lastInputs.put(freqKey, signal); }

    // ── Lifecycle ──

    public void onLoad(NodeGraph graph) { registerLinksFrom(graph, true); }
    public void onChunkUnloaded() { unregisterLinks(); }
    public void setRemoved() { unregisterLinks(); }

    /** Check if graph changed and re-register if needed. Returns true if re-registered. */
    public boolean checkGraphChanged(NodeGraph graph) {
        if (lastLinkGeneration != graph.graphGeneration) {
            registerLinksFrom(graph, true);
            lastLinkedGraph = graph;
            lastLinkGeneration = graph.graphGeneration;
            return true;
        }
        return false;
    }

    // ── Registration ──

    private void registerLinksFrom(NodeGraph graph, boolean forceResend) {
        Level level = owner.getLevel();
        if (level == null || level.isClientSide()) return;
        unregisterLinks();
        var EMPTY = RedstoneLinkNetworkHandler.Frequency.EMPTY;
        for (var n : graph.nodes) {
            if (n.type == NodeType.REDSTONE_IN || n.type == NodeType.REDSTONE_OUT) {
                var item1 = n.itemParams != null && n.itemParams.length > 0 ? n.itemParams[0] : ItemStack.EMPTY;
                var item2 = n.itemParams != null && n.itemParams.length > 1 ? n.itemParams[1] : ItemStack.EMPTY;
                var f1 = !item1.isEmpty() ? RedstoneLinkNetworkHandler.Frequency.of(item1) : EMPTY;
                var f2 = !item2.isEmpty() ? RedstoneLinkNetworkHandler.Frequency.of(item2) : EMPTY;
                var freqKey = ModUtils.freqKey(item1, item2);
                var isIn = n.type == NodeType.REDSTONE_IN;
                addLink(isIn, freqKey, f1, f2);
            }
        }
        if (forceResend) {
            // 强制网络中所有发射端重发信号（后注册的监听器可能错过之前的推送）
            for (var fl : freqLinks) {
                var net = Create.REDSTONE_LINK_NETWORK_HANDLER.getNetworkOf(level, fl.linkable);
                if (net != null) for (var l : net)
                    if (l != fl.linkable && l.isAlive() && !l.isListening())
                        Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(level, l);
            }
        }
    }

    private void addLink(boolean isIn, long freqKey, RedstoneLinkNetworkHandler.Frequency f1,
                         RedstoneLinkNetworkHandler.Frequency f2) {
        var link = new IRedstoneLinkable() {
            public int getTransmittedStrength() { return isIn ? 0 : lastOutputs.getOrDefault(freqKey, 0); }
            public void setReceivedStrength(int s) { if (isIn) lastInputs.put(freqKey, s); }
            public boolean isListening() { return isIn; }
            public boolean isAlive() { return true; }
            public Couple<RedstoneLinkNetworkHandler.Frequency> getNetworkKey() { return Couple.create(f1, f2); }
            public BlockPos getLocation() { return worldPosition; }
        };
        Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(owner.getLevel(), link);
        freqLinks.add(new FreqLink(freqKey, link));
    }

    private void unregisterLinks() {
        for (var fl : freqLinks)
            Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(owner.getLevel(), fl.linkable);
        freqLinks.clear();
    }

    // ── Per-tick refresh ──

    /** Hybrid refresh: scan for non-zero signals, keep setReceivedStrength values as fallback. */
    public void refreshInputs() {
        Level level = owner.getLevel();
        if (level == null) return;
        for (var fl : freqLinks) {
            var net = Create.REDSTONE_LINK_NETWORK_HANDLER.getNetworkOf(level, fl.linkable);
            if (net == null) { lastInputs.remove(fl.freqKey); continue; }
            int maxSig = 0;
            for (var l : net)
                if (l != fl.linkable && l.isAlive())
                    maxSig = Math.max(maxSig, l.getTransmittedStrength());
            if (maxSig > 0 || !lastInputs.containsKey(fl.freqKey))
                lastInputs.put(fl.freqKey, maxSig);
        }
    }

    /** Active refresh: scan all linkables for max signal per frequency. */
    public void refreshInputsActive() {
        Level level = owner.getLevel();
        if (level == null) return;
        for (var fl : freqLinks) {
            var net = Create.REDSTONE_LINK_NETWORK_HANDLER.getNetworkOf(level, fl.linkable);
            if (net != null) {
                int maxSig = 0;
                for (var l : net)
                    if (l != fl.linkable && l.isAlive())
                        maxSig = Math.max(maxSig, l.getTransmittedStrength());
                lastInputs.put(fl.freqKey, maxSig);
            } else {
                lastInputs.remove(fl.freqKey);
            }
        }
    }

    /** Build InputSource list for REDSTONE_IN nodes in the given graph. */
    public ArrayList<GraphEvaluator.InputSource> buildInputs(NodeGraph graph) {
        var in = new ArrayList<GraphEvaluator.InputSource>();
        for (var n : graph.nodes) {
            if (n.type == NodeType.REDSTONE_IN) {
                long fk = ModUtils.freqKey(n.itemParams);
                in.add(new GraphEvaluator.InputSource(fk, lastInputs.getOrDefault(fk, 0)));
            }
        }
        return in;
    }

    /** Write REDSTONE_OUT results back to the network. Call after evaluation. */
    public void writeOutputs(List<GraphEvaluator.OutputResult> results) {
        Level level = owner.getLevel();
        if (level == null) return;
        lastOutputs.clear();
        for (var r : results) {
            long freqKey = ModUtils.freqKey(r.freq1(), r.freq2());
            lastOutputs.put(freqKey, r.signal());
            for (var fl : freqLinks)
                if (fl.freqKey() == freqKey)
                    Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(level, fl.linkable);
        }
    }
}
