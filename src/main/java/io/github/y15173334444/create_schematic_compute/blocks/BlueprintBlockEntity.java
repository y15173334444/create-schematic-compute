package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.NodeAllowance;
import io.github.y15173334444.create_schematic_compute.graph.BlockNodeAllowances;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

public class BlueprintBlockEntity extends SyncedGraphBlockEntity implements GraphHost.TickHooks {
    public BlueprintBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.BLUEPRINT_BE.get(), pos, s); }

    // accept() 已上提至 SyncedGraphBlockEntity（阶段 1）——Blueprint 无类型特定字段，
    // 合并只需基类那份。/ accept() moved up to SyncedGraphBlockEntity (phase 1) —
    // Blueprint has no type-specific fields, so the base implementation is enough.

    /** tick 体已收敛进 GraphHost.tickBody（K23）：15 步共同编排住骨架（①/⑮/②/③/④/⑤/⑥/
     *  ⑧/⑨/⑩/⑪/⑫/⑬/⑭ 全默认口径），蓝图机的偏差化为声明（②中止口径、⑥A 档、⑦ 遗留
     *  挂点在位、⑨ 全状态档、⑬ 在位）。/ The tick body converged into GraphHost.tickBody
     *  (K23); every Blueprint deviation is a declaration below (② abort-on-absent, ⑥ A,
     *  ⑦ legacy hook present, ⑨ full-state, ⑬ present). */
    public void tick() {
        tickBody(this);
    }

    // ── tick 编排偏差声明（K23 骨架钩子）/ tick-orchestration deviations (K23 hooks) ──

    /** ② LIT 指示属性（缺席即中止口径，litAbsentAbortsTick 默认）。
     *  Step ② LIT property (abort-on-absent shape, the litAbsentAbortsTick default). */
    @Override public BooleanProperty litProperty() { return BlueprintBlock.LIT; }

    /** ⑥ A 档：refreshInputsActive（继承线运行档口径）。 / Step ⑥ A variant. */
    @Override public boolean activeInputRefresh() { return true; }

    /** ⑦ 冲突自愈遗留第二挂点在位（主挂点已在骨架 ① 内先行）。 / Step ⑦ legacy
     *  second heal hook present (the primary already ran in the skeleton's ①). */
    @Override public boolean wantsStep7Recover() { return true; }

    /** ⑬ 触发器差分在位（矩阵 ⑬ 列 ✓）。 / Step ⑬ present (matrix ✓). */
    @Override public boolean wantsFlipflopDiff() { return true; }

    /** ⑨ 全状态档（6 参，时序节点真实演进）。 / Step ⑨ full-state (6-arg) form. */
    @Override public GraphHost.EvalVariant evalVariant() { return GraphHost.EvalVariant.FULL_STATE; }

    /** P2-7 服务端准入门：本宿主答自己的名单（与 BlueprintScreen 的客户端过滤同源）。
     *  P2-7 server admission gate: this host answers its own allowlist (same source
     *  as BlueprintScreen's client-side filtering). */
    @Override public NodeAllowance nodeAllowance() { return BlockNodeAllowances.BLUEPRINT; }

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
