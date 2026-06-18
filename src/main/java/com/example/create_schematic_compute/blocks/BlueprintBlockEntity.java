package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.graph.GraphEvaluator;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
import com.example.create_schematic_compute.graph.RuntimeState;
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

    private final RedstoneLinkHelper rs = new RedstoneLinkHelper(this);

    private GraphEvaluator evaluator = null;
    private NodeGraph lastEvaluatedGraph = null;

    public BlueprintBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.BLUEPRINT_BE.get(), pos, s); }
    @Override public boolean isRunning() { return running; }
    @Override public void setRunning(boolean r) { running = r; setChanged(); if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }
    @Override public boolean graphHasCycles() { return graph.hasCycles(); }
    @Override public void clearPidState() { runtimeState.pidState.clear(); }
    @Override public void syncFlipflopStates(java.util.Map<Integer, Boolean> states) {
        runtimeState.flipflopStates.clear();
        if (states != null) runtimeState.flipflopStates.putAll(states);
    }

    @Override public void accept(BlockEntity other) {
        if(other instanceof BlueprintBlockEntity src) {
            this.graph = src.graph; this.running = src.running; runtimeState.clear();
            setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override public void onLoad() { super.onLoad(); rs.onLoad(graph); }
    @Override public void onChunkUnloaded() { super.onChunkUnloaded(); rs.onChunkUnloaded(); }
    @Override public void setRemoved() { rs.setRemoved(); super.setRemoved(); }

    public void tick() {
        if(level==null||level.isClientSide()) return;
        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        var currentState = getBlockState();
        if (!currentState.hasProperty(BlueprintBlock.LIT)) return;
        if(currentState.getValue(BlueprintBlock.LIT)!=shouldBeLit)
            level.setBlock(worldPosition, currentState.setValue(BlueprintBlock.LIT, shouldBeLit), 3);
        rs.checkGraphChanged(graph);
        if(!running) return;
        if (evaluator == null || lastEvaluatedGraph != graph) {
            evaluator = new GraphEvaluator(graph);
            lastEvaluatedGraph = graph;
            runtimeState.clear();
        }
        rs.refreshInputsActive();
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
        if (level instanceof net.minecraft.server.level.ServerLevel sl && !runtimeState.flipflopStates.equals(lastSyncedFlipflopStates)) {
            lastSyncedFlipflopStates = new java.util.HashMap<>(runtimeState.flipflopStates);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingChunk(sl,
                new net.minecraft.world.level.ChunkPos(worldPosition),
                new com.example.create_schematic_compute.network.RuntimeStateSyncPacket(worldPosition,
                    lastSyncedFlipflopStates));
        }
        setChanged();
    }

    public void loadGraphFromBytes(byte[] data) {
        if (level == null) return;
        try {
            var t = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(2 * 1024 * 1024));
            if (t != null && t.contains("graph")) { graph = NodeGraph.load(t.getCompound("graph"), level.registryAccess()); rs.onLoad(graph); }
            setChanged();
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
        if (t.contains("graph")) { graph = NodeGraph.load(t.getCompound("graph"), r); rs.onLoad(graph); }
        if (t.contains("running")) running = t.getBoolean("running");
        if (t.contains("runtime")) {
            RuntimeState loaded = RuntimeState.load(t.getCompound("runtime"));
            runtimeState.pidState.putAll(loaded.pidState);
            runtimeState.delayQueues.putAll(loaded.delayQueues);
            runtimeState.flipflopStates.putAll(loaded.flipflopStates);
            runtimeState.pulseTimers.putAll(loaded.pulseTimers);
            // 重新编译通过 runtimeState.clear() 重置为初始状态，世界重载保留运行状态
        }
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) { var t=new CompoundTag(); saveAdditional(t,r); return t; }
    @Override public Component getDisplayName() { return Component.translatable("container."+SchematicCompute.MOD_ID+".blueprint"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new BlueprintMenu(id,this); }
}
