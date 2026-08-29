package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayDeque;

public class BlueprintBlockEntity extends SyncedGraphBlockEntity {
    public BlueprintBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.BLUEPRINT_BE.get(), pos, s); }

    // accept() 已上提至 SyncedGraphBlockEntity（阶段 1）——Blueprint 无类型特定字段，
    // 合并只需基类那份。/ accept() moved up to SyncedGraphBlockEntity (phase 1) —
    // Blueprint has no type-specific fields, so the base implementation is enough.

    public void tick() {
        if(level==null||level.isClientSide()) return;
        ensureBusRegistered();
        boolean shouldBeLit = isRunning() && !graph().nodes.isEmpty();
        var currentState = getBlockState();
        if (!currentState.hasProperty(BlueprintBlock.LIT)) return;
        if(currentState.getValue(BlueprintBlock.LIT)!=shouldBeLit)
            level.setBlock(worldPosition, currentState.setValue(BlueprintBlock.LIT, shouldBeLit), 3);
        rs().checkGraphChanged(graph());
        if (graphChanged()) recompileEvaluatorFull();
        if(!isRunning()) {
            onStopRunning();
            return;
        }
        rs().refreshInputsActive();
        if (BusChannelHelper.recoverConflictedChannels(graph(), worldPosition, level)) {
            requestFullSync();
            if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        var in = rs().buildInputs(graph());
        float dt = 0.05f;
        var results = evaluator().evaluate(in, runtimeState().pidState, dt,
                runtimeState().delayQueues, runtimeState().flipflopStates, runtimeState().pulseTimers);
        rs().writeOutputs(results);
        broadcastEvalSnapshot(); // 广播 EvalSnapshot 给客户端（供 DEBUG_PROBE 采样）
        BusChannelHelper.syncIfBandsChanged(graph(), worldPosition, lastBusHashMap(), level);
        broadcastFlipflopDiff();
        setChanged();
    }

    // loadGraphFromBytes 覆写已于阶段 3 删除 —— 统一走基类/引擎的
    // loadGraphFromBytes → loadEditorTag（含 BUS 注销与子图/触发器状态清理，
    // 此前本覆写缺这两步：编辑保存后泄漏 SignalBus 注册、封装内时序跨载残留）。
    // The loadGraphFromBytes override was removed in phase 3 — the base/engine
    // loadGraphFromBytes -> loadEditorTag path applies for all BEs (including BUS
    // unregistration and the sub-graph/flipflop clear this override used to miss:
    // a SignalBus leak and stale encapsulation state across editor saves).

    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        // Blueprint preserves expanded node state across reloads
        var oldExpanded = new java.util.HashMap<Integer, Boolean>();
        for (var n : graph().nodes) if (n.expanded) oldExpanded.put(n.id, true);
        super.loadAdditional(t, r);
        for (var n : graph().nodes) if (oldExpanded.containsKey(n.id)) n.expanded = true;
        // 运行时状态全量恢复已上移到 SyncedGraphBlockEntity.loadAdditional（阶段 0）——
        // 原先此处只补 delay/ff/pulse/subStates，漏掉 debugTime 与 nodeEdge。
        // Full runtime-state restore moved up to SyncedGraphBlockEntity.loadAdditional
        // (phase 0) — this site used to patch in only delay/ff/pulse/subStates, missing
        // debugTime and nodeEdge.
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
}
