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
        // 第 ⑮ 步（P1-8 补线）：BUS_IN 改名等路径置位的挂起全量同步在此按 grace 窗口冲刷；
        // 必须放在所有早退（LIT 缺属性 / 停机）之前，停机宿主也要能把图推给新加入的编辑者。
        // Step ⑮ (P1-8 wiring): flush the pending full sync (e.g. a BUS_IN rename) within the
        // grace window; it must sit before every early return (missing LIT / stopped) so a
        // stopped host still ships its graph to a newly joined editor.
        flushPendingFullSync();
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

    // 整图保存已随 issue #17 退役；世界加载走 loadHostNBT，保存请求不再替换图。
    // Whole-graph save retired with issue #17; world load goes through loadHostNBT.
    // Save requests no longer replace the graph.

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
        // 加载即发包已删（P3-N13 口径统一）：旧写法在 loadAdditional 里无条件
        // sendBlockUpdated，是前全量同步机制时代的替代品——客户端加载区块时本就会随区块包
        // 收到 getUpdateTag 全量数据，这里只是每次加载多序列化并多发一整份整图包。运行中
        // 的全量同步统一走 needsFullSync + tick 里的 flushPendingFullSync（上方第 ⑮ 步）。
        // Load-time push removed (P3-N13 unified): the old unconditional sendBlockUpdated in
        // loadAdditional was a substitute from the pre-full-sync-machinery era — clients
        // already receive getUpdateTag data with the chunk packet on load, so this only
        // serialized and shipped a second whole-graph packet per load. Runtime full syncs
        // travel through needsFullSync + the tick's flushPendingFullSync (step ⑮ above).
    }
}
