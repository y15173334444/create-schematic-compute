package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayDeque;

public class ProgramComputerBlockEntity extends SyncedGraphBlockEntity {
    public ProgramComputerBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.PROGRAM_BE.get(), pos, s); }

    // accept() 已上提至 SyncedGraphBlockEntity（阶段 1）——ProgramComputer 无类型特定
    // 字段，合并只需基类那份。/ accept() moved up to SyncedGraphBlockEntity (phase 1) —
    // ProgramComputer has no type-specific fields, so the base implementation is enough.

    public void tick() {
        if(level==null||level.isClientSide()) return;
        ensureBusRegistered();
        // 第 ⑮ 步（P1-8 补线）：挂起全量同步按 grace 窗口冲刷；必须在所有早退之前，
        // 停机宿主也要能把图推给新加入的编辑者（Blueprint 同款）。
        // Step ⑮ (P1-8 wiring): flush the pending full sync within the grace window; must
        // sit before every early return so a stopped host still ships its graph to a newly
        // joined editor (same as Blueprint).
        flushPendingFullSync();
        var state = getBlockState();
        if (!state.hasProperty(ProgramComputerBlock.LIT)) return;
        boolean shouldBeLit = isRunning() && !graph().nodes.isEmpty();
        if(state.getValue(ProgramComputerBlock.LIT)!=shouldBeLit)
            level.setBlock(worldPosition, state.setValue(ProgramComputerBlock.LIT, shouldBeLit), 3);
        rs().checkGraphChanged(graph());
        if(graphChanged()) recompileEvaluatorFull();
        if(!isRunning()) { onStopRunning(); return; }
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

    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t, r);
        // 运行时状态全量恢复已上移到 SyncedGraphBlockEntity.loadAdditional（阶段 0）——
        // 原先此处只补 delay/ff/pulse/subStates，漏掉 debugTime 与 nodeEdge。
        // Full runtime-state restore moved up to SyncedGraphBlockEntity.loadAdditional
        // (phase 0) — this site used to patch in only delay/ff/pulse/subStates, missing
        // debugTime and nodeEdge.
        // 加载即发包已删（P3-N13 口径统一，Blueprint 同款）：客户端加载区块时随区块包即收
        // getUpdateTag 全量数据，加载期再发一份整图包是前全量同步机制时代的替代品；运行中
        // 的全量同步统一走 needsFullSync + tick 里的 flushPendingFullSync（上方第 ⑮ 步）。
        // Load-time push removed (P3-N13 unified, same as Blueprint): clients already get
        // the getUpdateTag data with the chunk packet on load — the extra whole-graph packet
        // was a pre-full-sync-machinery substitute; runtime full syncs travel through
        // needsFullSync + the tick's flushPendingFullSync (step ⑮ above).
    }
}
