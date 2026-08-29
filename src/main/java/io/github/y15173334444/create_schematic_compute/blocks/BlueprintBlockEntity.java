package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import io.github.y15173334444.create_schematic_compute.network.RuntimeStateSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayDeque;

public class BlueprintBlockEntity extends SyncedGraphBlockEntity {
    private java.util.Map<Integer, Boolean> lastSyncedFlipflopStates = null;
    /** 已同步的子图 flipflop 基线（encapNodeId → sub-node flipflop），用于 diff 判断避免每 tick 广播。
     *  Last-synced sub-graph flipflop baseline (encapNodeId → sub-node flipflop), diffed to avoid per-tick broadcasts. */
    private java.util.Map<Integer, java.util.Map<Integer, Boolean>> lastSyncedSubFlipflopStates = null;

    public BlueprintBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.BLUEPRINT_BE.get(), pos, s); }

    // accept() 已上提至 SyncedGraphBlockEntity（阶段 1）——Blueprint 无类型特定字段，
    // 合并只需基类那份。/ accept() moved up to SyncedGraphBlockEntity (phase 1) —
    // Blueprint has no type-specific fields, so the base implementation is enough.

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

    @Override public void loadGraphFromBytes(byte[] data) {
        if (level == null) return;
        try {
            var t = net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(data), net.minecraft.nbt.NbtAccounter.create(2 * 1024 * 1024));
            if (t != null && t.contains("graph")) {
                unregisterBusChannels(graph); // unregister old BUS channels before replacing graph
                // Do NOT call cleanupBusChannels — it broadcasts empty band syncs to clients,
                // permanently deleting BUS connections. Next tick's recompile restores correct bands.
                graph = io.github.y15173334444.create_schematic_compute.graph.NodeGraph.load(t.getCompound("graph"), level.registryAccess());
                // Force generation bump so graphChanged() triggers recompile + BUS re-registration
                // (see SyncedGraphBlockEntity.loadGraphFromBytes for the full rationale).
                // 强制 bump 代数，确保下一 tick 重编译并重新注册 BUS 频道。
                graph.bumpGeneration();
                // 重置 lastGraphGeneration 为 -1：bump 到 1 可能与上次重编译留下的
                // lastGraphGeneration=1 冲突，graphChanged() 为 false → 重编译（及 BUS
                // 重注册）被跳过 → BUS_IN 读 0（回归审计：反复编译+运行失效）。
                // Reset lastGraphGeneration to -1: bumping to 1 can collide with the
                // prior compile's lastGraphGeneration=1, making graphChanged() false —
                // recompile (and BUS re-registration) is skipped -> BUS_IN reads 0.
                lastGraphGeneration = -1;
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
        // 运行时状态全量恢复已上移到 SyncedGraphBlockEntity.loadAdditional（阶段 0）——
        // 原先此处只补 delay/ff/pulse/subStates，漏掉 debugTime 与 nodeEdge。
        // Full runtime-state restore moved up to SyncedGraphBlockEntity.loadAdditional
        // (phase 0) — this site used to patch in only delay/ff/pulse/subStates, missing
        // debugTime and nodeEdge.
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
}
