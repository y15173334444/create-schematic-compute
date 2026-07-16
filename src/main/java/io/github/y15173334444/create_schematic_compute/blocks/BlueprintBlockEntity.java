package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.RuntimeState;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import com.simibubi.create.foundation.blockEntity.IMergeableBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;

public class BlueprintBlockEntity extends BlockEntity implements MenuProvider, IMergeableBE, GraphBlockEntity {
    public NodeGraph graph = new NodeGraph();
    public boolean running = false;
    public final RuntimeState runtimeState = new RuntimeState();
    private java.util.Map<Integer, Boolean> lastSyncedFlipflopStates = null;
    private final java.util.HashMap<Integer, Integer> lastBusHashMap = new java.util.HashMap<>();

    private final RedstoneLinkHelper rs = new RedstoneLinkHelper(this);

    private GraphEvaluator evaluator = null;
    private NodeGraph lastEvaluatedGraph = null;

    public BlueprintBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.BLUEPRINT_BE.get(), pos, s); }
    @Override public boolean isRunning() { return running; }
    @Override public void setRunning(boolean r) { running = r; }
    @Override public boolean graphHasCycles() { return graph.hasCycles(); }
    @Override public void clearPidState() { runtimeState.pidState.clear(); }
    @Override public void syncFlipflopStates(java.util.Map<Integer, Boolean> states) {
        runtimeState.flipflopStates.clear();
        if (states != null) runtimeState.flipflopStates.putAll(states);
    }
    @Override public io.github.y15173334444.create_schematic_compute.graph.NodeGraph getNodeGraph() { return graph; }
    @Override public void syncBusBandsFromServer(String busName, java.util.List<String> bands) {
        BusChannelHelper.syncBandsFromServer(busName, bands, graph);
    }

    @Override public void accept(BlockEntity other) {
        if(other instanceof BlueprintBlockEntity src) {
            unregisterBusChannels(graph);
            this.graph = src.graph; this.running = src.running; runtimeState.clear();
            setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override public void onLoad() { super.onLoad(); rs.onLoad(graph); }
    @Override public void onChunkUnloaded() { cleanupBusChannels(graph); unregisterBusChannels(graph); super.onChunkUnloaded(); rs.onChunkUnloaded(); }
    @Override public void setRemoved() { cleanupBusChannels(graph); unregisterBusChannels(graph); rs.setRemoved(); super.setRemoved(); }

    private void registerBusChannels() {
        if (BusChannelHelper.registerChannels(graph, worldPosition, level))
            needsFullSync = true;
    }

    private void cleanupBusChannels(io.github.y15173334444.create_schematic_compute.graph.NodeGraph g) {
        BusChannelHelper.cleanupClientBands(g, worldPosition, level);
    }

    private void unregisterBusChannels(io.github.y15173334444.create_schematic_compute.graph.NodeGraph g) {
        BusChannelHelper.unregisterChannels(g, worldPosition, level);
    }

    public void tick() {
        if(level==null||level.isClientSide()) return;
        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        var currentState = getBlockState();
        if (!currentState.hasProperty(BlueprintBlock.LIT)) return;
        if(currentState.getValue(BlueprintBlock.LIT)!=shouldBeLit)
            level.setBlock(worldPosition, currentState.setValue(BlueprintBlock.LIT, shouldBeLit), 3);
        rs.checkGraphChanged(graph);
        // 图变化时维护 BUS 频道注册（必须在 running 检查之前，否则其他方块无法读取未启动电脑的 BUS_OUT）
        if (evaluator == null || lastEvaluatedGraph != graph) {
            if (lastEvaluatedGraph != null) {
                BusChannelHelper.syncDeletedBusNames(lastEvaluatedGraph, graph, worldPosition, level);
                unregisterBusChannels(lastEvaluatedGraph);
                runtimeState.clear();
            }
            evaluator = new GraphEvaluator(graph);
            evaluator.restoreSubState(runtimeState);
            lastEvaluatedGraph = graph;
            registerBusChannels();
        }
        if(!running) {
            for (var n : graph.nodes) {
                if (n.type == NodeType.BUS_OUT && n.busInternalMap != null)
                    n.busInternalMap.clear();
            }
            rs.writeOutputs(java.util.Collections.emptyList());
            return;
        }
        rs.refreshInputsActive();
        BusChannelHelper.recoverConflictedChannels(graph, worldPosition, level);
        var in = rs.buildInputs(graph);
        float dt = 0.05f;
        var results = evaluator.evaluate(in, runtimeState.pidState, dt,
                runtimeState.delayQueues, runtimeState.flipflopStates, runtimeState.pulseTimers);

        // DELAY 入队
        for (var n : graph.nodes) {
            if (n.type == NodeType.DELAY) {
                var q = runtimeState.delayQueues.computeIfAbsent(n.id, k -> new ArrayDeque<>());
                int ticks = Math.max(1, (int)(n.params.length > 0 ? n.params[0] : 10));
                q.addLast(evaluator.getNodeInput(n.id, 0));
                while (q.size() > ticks) q.pollFirst();
            }
        }

        rs.writeOutputs(results);
        // BUS 频段变化时发包通知所有客户端
        BusChannelHelper.syncIfBandsChanged(graph, worldPosition, lastBusHashMap, level);
        if (level instanceof net.minecraft.server.level.ServerLevel sl && !runtimeState.flipflopStates.equals(lastSyncedFlipflopStates)) {
            lastSyncedFlipflopStates = new java.util.HashMap<>(runtimeState.flipflopStates);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingChunk(sl,
                new net.minecraft.world.level.ChunkPos(worldPosition),
                new io.github.y15173334444.create_schematic_compute.network.RuntimeStateSyncPacket(worldPosition,
                    lastSyncedFlipflopStates));
        }
        setChanged();
    }

    public void loadGraphFromBytes(byte[] data) {
        if (level == null) return;
        try {
            var t = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(2 * 1024 * 1024));
            if (t != null && t.contains("graph")) {
                graph = NodeGraph.load(t.getCompound("graph"), level.registryAccess());
                rs.onLoad(graph);
            }
            needsFullSync = true; setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Failed to load blueprint graph, resetting", e);
            graph = new NodeGraph(); rs.onLoad(graph);
            setChanged();
        }
    }
    @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.saveAdditional(t,r); t.put("graph", graph.save(r)); t.putBoolean("running", running);
        t.put("runtime", runtimeState.save());
    }
    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t,r);
        if (t.contains("graph")) {
            var oldExpanded = new java.util.HashMap<Integer, Boolean>();
            for (var n : graph.nodes) if (n.expanded) oldExpanded.put(n.id, true);
            graph = NodeGraph.load(t.getCompound("graph"), r);
            for (var n : graph.nodes) if (oldExpanded.containsKey(n.id)) n.expanded = true;
            rs.onLoad(graph);
        }
        if (t.contains("running")) running = t.getBoolean("running");
        if (t.contains("runtime")) {
            RuntimeState loaded = RuntimeState.load(t.getCompound("runtime"));
            runtimeState.pidState.putAll(loaded.pidState);
            runtimeState.delayQueues.putAll(loaded.delayQueues);
            runtimeState.flipflopStates.putAll(loaded.flipflopStates);
            runtimeState.pulseTimers.putAll(loaded.pulseTimers);
            runtimeState.subStates.putAll(loaded.subStates);
            // 重新编译通过 runtimeState.clear() 重置为初始状态，世界重载保留运行状态
        }
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    private boolean needsFullSync = true;
    /** Force a full graph sync to all tracking clients (called when a new editor joins). */
    public void flagFullSync() { needsFullSync = true; setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) {
        if (needsFullSync) { needsFullSync = false; var t=new CompoundTag(); saveAdditional(t,r); return t; }
        var t=new CompoundTag(); t.putBoolean("running", running); return t;
    }
    @Override public Component getDisplayName() { return Component.translatable("container."+SchematicCompute.MOD_ID+".blueprint"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new BlueprintMenu(id,this); }
}
