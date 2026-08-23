package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.RuntimeState;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import io.github.y15173334444.create_schematic_compute.network.RuntimeStateSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayDeque;

public class ProgramComputerBlockEntity extends SyncedGraphBlockEntity {
    private java.util.Map<Integer, Boolean> lastSyncedFlipflopStates = null;
    /** 已同步的子图 flipflop 基线（encapNodeId → sub-node flipflop），用于 diff 判断避免每 tick 广播。
     *  Last-synced sub-graph flipflop baseline (encapNodeId → sub-node flipflop), diffed to avoid per-tick broadcasts. */
    private java.util.Map<Integer, java.util.Map<Integer, Boolean>> lastSyncedSubFlipflopStates = null;

    public ProgramComputerBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.PROGRAM_BE.get(), pos, s); }

    @Override public void accept(BlockEntity other) {
        if(other instanceof ProgramComputerBlockEntity src) {
            unregisterBusChannels(graph);
            this.graph = src.graph; this.running = src.running; runtimeState.clear();
            setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void tick() {
        if(level==null||level.isClientSide()) return;
        ensureBusRegistered();
        var state = getBlockState();
        if (!state.hasProperty(ProgramComputerBlock.LIT)) return;
        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        if(state.getValue(ProgramComputerBlock.LIT)!=shouldBeLit)
            level.setBlock(worldPosition, state.setValue(ProgramComputerBlock.LIT, shouldBeLit), 3);
        rs.checkGraphChanged(graph);
        if(graphChanged()) recompileEvaluatorFull();
        if(!running) { onStopRunning(); return; }
        rs.refreshInputsActive();
        if (BusChannelHelper.recoverConflictedChannels(graph, worldPosition, level)) {
            needsFullSync = true; setChanged();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        var in = rs.buildInputs(graph);
        float dt = 0.05f;
        var results = evaluator.evaluate(in, runtimeState.pidState, dt,
                runtimeState.delayQueues, runtimeState.flipflopStates, runtimeState.pulseTimers);
        rs.writeOutputs(results);
        broadcastEvalSnapshot(); // 广播 EvalSnapshot 给客户端（供 DEBUG_PROBE 采样）
        BusChannelHelper.syncIfBandsChanged(graph, worldPosition, lastBusHashMap, level);
        if (level instanceof ServerLevel sl) {
            var currentFf = runtimeState.flipflopStates;
            boolean changed = !currentFf.equals(lastSyncedFlipflopStates);
            // 子图 flipflop 也做 diff（有基线），而不是「存在即变更」，避免每 tick 广播
            // Diff sub-graph flipflop against its own baseline instead of treating
            // presence as change — prevents per-tick RuntimeStateSyncPacket spam.
            java.util.Map<Integer, java.util.Map<Integer, Boolean>> subFf = java.util.Collections.emptyMap();
            if (!runtimeState.subStates.isEmpty()) {
                var curSub = new java.util.HashMap<Integer, java.util.Map<Integer, Boolean>>();
                for (var se : runtimeState.subStates.entrySet()) {
                    if (!se.getValue().flipflopStates.isEmpty())
                        curSub.put(se.getKey(), new java.util.HashMap<>(se.getValue().flipflopStates));
                }
                if (!curSub.equals(lastSyncedSubFlipflopStates)) {
                    changed = true;
                    subFf = curSub;
                }
            } else if (lastSyncedSubFlipflopStates != null && !lastSyncedSubFlipflopStates.isEmpty()) {
                // 子图全部消失（封装被删除等）也视为变更，通知客户端清空
                // All sub-states disappeared (e.g. encap removed) — also a change.
                changed = true;
            }
            if (changed) {
                lastSyncedFlipflopStates = new java.util.HashMap<>(currentFf);
                lastSyncedSubFlipflopStates = subFf.isEmpty() ? null : subFf;
                PacketDistributor.sendToPlayersTrackingChunk(sl, new ChunkPos(worldPosition),
                    new RuntimeStateSyncPacket(worldPosition, lastSyncedFlipflopStates, subFf));
            }
        }
        setChanged();
    }

    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t, r);
        if (t.contains("runtime")) {
            RuntimeState loaded = RuntimeState.load(t.getCompound("runtime"));
            runtimeState.delayQueues.putAll(loaded.delayQueues);
            runtimeState.flipflopStates.putAll(loaded.flipflopStates);
            runtimeState.pulseTimers.putAll(loaded.pulseTimers);
            runtimeState.subStates.putAll(loaded.subStates);
        }
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
}
