package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

public class ProgramComputerBlockEntity extends SyncedGraphBlockEntity implements GraphHost.TickHooks {
    public ProgramComputerBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.PROGRAM_BE.get(), pos, s); }

    // accept() 已上提至 SyncedGraphBlockEntity（阶段 1）——ProgramComputer 无类型特定
    // 字段，合并只需基类那份。/ accept() moved up to SyncedGraphBlockEntity (phase 1) —
    // ProgramComputer has no type-specific fields, so the base implementation is enough.

    /** tick 体已收敛进 GraphHost.tickBody（K23）；程序计算机的偏差化为声明（②中止口径、
     *  ⑥A 档、⑦ 遗留挂点在位、⑨ 全状态档、⑬ 在位）。/ The tick body converged into
     *  GraphHost.tickBody (K23); every ProgramComputer deviation is a declaration below. */
    public void tick() {
        tickBody(this);
    }

    // ── tick 编排偏差声明（K23 骨架钩子）/ tick-orchestration deviations (K23 hooks) ──

    /** ② LIT 指示属性（缺席即中止口径）。 / Step ② LIT property (abort-on-absent). */
    @Override public BooleanProperty litProperty() { return ProgramComputerBlock.LIT; }

    /** ⑥ A 档：refreshInputsActive。 / Step ⑥ A variant. */
    @Override public boolean activeInputRefresh() { return true; }

    /** ⑦ 冲突自愈遗留第二挂点在位（主挂点已在骨架 ① 内先行）。 / Step ⑦ legacy
     *  second heal hook present (the primary already ran in the skeleton's ①). */
    @Override public boolean wantsStep7Recover() { return true; }

    /** ⑬ 触发器差分在位（矩阵 ⑬ 列 ✓）。 / Step ⑬ present (matrix ✓). */
    @Override public boolean wantsFlipflopDiff() { return true; }

    /** ⑨ 全状态档（6 参）。 / Step ⑨ full-state (6-arg) form. */
    @Override public GraphHost.EvalVariant evalVariant() { return GraphHost.EvalVariant.FULL_STATE; }

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
