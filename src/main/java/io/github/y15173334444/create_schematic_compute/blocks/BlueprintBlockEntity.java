package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.RuntimeState;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import io.github.y15173334444.create_schematic_compute.network.RuntimeStateSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import javax.annotation.Nullable;
import java.util.ArrayDeque;

public class BlueprintBlockEntity extends SyncedGraphBlockEntity {
    private java.util.Map<Integer, Boolean> lastSyncedFlipflopStates = null;

    public BlueprintBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.BLUEPRINT_BE.get(), pos, s); }

    @Override public void accept(BlockEntity other) {
        if(other instanceof BlueprintBlockEntity src) {
            unregisterBusChannels(graph);
            this.graph = src.graph; this.running = src.running; runtimeState.clear();
            setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void tick() {
        if(level==null||level.isClientSide()) return;
        ensureBusRegistered();
        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        var currentState = getBlockState();
        if (!currentState.hasProperty(BlueprintBlock.LIT)) return;
        if(currentState.getValue(BlueprintBlock.LIT)!=shouldBeLit)
            level.setBlock(worldPosition, currentState.setValue(BlueprintBlock.LIT, shouldBeLit), 3);
        rs.checkGraphChanged(graph);
        if (graphChanged()) recompileEvaluatorFull();
        if(!running) {
            onStopRunning();
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
        broadcastEvalSnapshot(); // 广播 EvalSnapshot 给客户端（供 DEBUG_PROBE 采样）
        BusChannelHelper.syncIfBandsChanged(graph, worldPosition, lastBusHashMap, level);
        if (level instanceof ServerLevel sl && !runtimeState.flipflopStates.equals(lastSyncedFlipflopStates)) {
            lastSyncedFlipflopStates = new java.util.HashMap<>(runtimeState.flipflopStates);
            PacketDistributor.sendToPlayersTrackingChunk(sl, new ChunkPos(worldPosition),
                new RuntimeStateSyncPacket(worldPosition, lastSyncedFlipflopStates));
        }
        setChanged();
    }

    @Override public void loadGraphFromBytes(byte[] data) {
        if (level == null) return;
        try {
            var t = net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(data), net.minecraft.nbt.NbtAccounter.create(2 * 1024 * 1024));
            if (t != null && t.contains("graph")) {
                graph = io.github.y15173334444.create_schematic_compute.graph.NodeGraph.load(t.getCompound("graph"), level.registryAccess());
                rs.onLoad(graph);
            }
            needsFullSync = true; setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Failed to load blueprint graph, resetting", e);
            graph = new io.github.y15173334444.create_schematic_compute.graph.NodeGraph(); rs.onLoad(graph);
            setChanged();
        }
    }

    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        // Blueprint preserves expanded node state across reloads
        var oldExpanded = new java.util.HashMap<Integer, Boolean>();
        for (var n : graph.nodes) if (n.expanded) oldExpanded.put(n.id, true);
        super.loadAdditional(t, r);
        for (var n : graph.nodes) if (oldExpanded.containsKey(n.id)) n.expanded = true;
        if (t.contains("runtime")) {
            RuntimeState loaded = RuntimeState.load(t.getCompound("runtime"));
            runtimeState.delayQueues.putAll(loaded.delayQueues);
            runtimeState.flipflopStates.putAll(loaded.flipflopStates);
            runtimeState.pulseTimers.putAll(loaded.pulseTimers);
            runtimeState.subStates.putAll(loaded.subStates);
        }
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override public Component getDisplayName() { return Component.translatable("container."+SchematicCompute.MOD_ID+".blueprint"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new BlueprintMenu(id,this); }
}
