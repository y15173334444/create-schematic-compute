package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.MusicTransport;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * 功放电脑 BE：图宿主薄壳（ProgramComputer 同款 tick）+ MUSIC 传输状态注入与持久化。
 * <p>传输状态（playing/head/nextFire）住 BE 类型段 NBT（plan §3.7），每 tick 注入求值器。</p>
 * <p>Amplifier computer BE: a graph-host shell (ProgramComputer-style tick) plus MUSIC
 * transport injection + persistence (type-specific NBT, plan §3.7).</p>
 */
public class AmplifierComputerBlockEntity extends SyncedGraphBlockEntity implements GraphHost.TickHooks {

    /** MUSIC 节点传输状态（nodeId → 传输），经类型段 NBT 持久化。 */
    private final Map<Integer, MusicTransport> audioTransports = new HashMap<>();

    public AmplifierComputerBlockEntity(BlockPos pos, BlockState s) {
        super(SchematicCompute.AMPLIFIER_COMPUTER_BE.get(), pos, s);
    }

    /** tick 体已收敛进 GraphHost.tickBody（K23）：15 步共同编排住骨架，功放电脑的偏差
     *  化为下方声明与钩子（② 跳过不中止、⑥A 档、⑫⑬ 在位、⑨ 全状态档、⑦ 后传输表剪枝
     *  + 注入）。/ The tick body converged into GraphHost.tickBody (K23); every amplifier
     *  deviation is now a declaration or hook below. */
    public void tick() {
        tickBody(this);
    }

    // ── tick 编排偏差声明（K23 骨架钩子）/ tick-orchestration deviations (K23 hooks) ──

    /** ② LIT 指示属性。缺席时**跳过不中止**（功放/雷达口径——原 if 包裹无早退）。
     *  Step ② LIT property. Absent = skip without aborting (the amplifier/radar shape —
     *  the original if-wrapped, no early return). */
    @Override public BooleanProperty litProperty() { return AmplifierComputerBlock.LIT; }

    /** ② 属性缺席不中止本 tick。 / Step ② absence does not abort the tick. */
    @Override public boolean litAbsentAbortsTick() { return false; }

    /** ⑥ A 档：refreshInputsActive。 / Step ⑥ A variant. */
    @Override public boolean activeInputRefresh() { return true; }

    /** ⑫ 频段变更同步在位（矩阵 ⑫ 列 ✓）。 / Step ⑫ present (matrix ✓). */
    @Override public boolean wantsBandSync() { return true; }

    /** ⑬ 触发器差分在位（矩阵 ⑬ 列 ✓）。 / Step ⑬ present (matrix ✓). */
    @Override public boolean wantsFlipflopDiff() { return true; }

    /** ⑨ 全状态档（6 参）。 / Step ⑨ full-state (6-arg) form. */
    @Override public GraphHost.EvalVariant evalVariant() { return GraphHost.EvalVariant.FULL_STATE; }

    /** 缝 4（⑦ 后 ⑧ 前）：传输表按存活 MUSIC 节点剪枝（矩阵「独有缺口」列）+ 音频宿主注入。
     *  Seam 4 (after ⑦, before ⑧): prune the transport table to surviving MUSIC nodes
     *  (the matrix's unique-gap column) + audio-host injection. */
    @Override public void afterRecover() {
        // 传输表只保留仍存活的 MUSIC 节点（删节点后条目随剪，防类型段 NBT 慢性膨胀）
        audioTransports.keySet().removeIf(id -> {
            GraphNode n = graph().findNode(id);
            return n == null || n.type != NodeType.MUSIC;
        });
        // 注入音频宿主状态（传输表 + 方块坐标 + 本 tick 时刻戳），仿雷达位置注入范式；
        // 时刻戳供 AudioBands 新鲜度门控（发布方停机/卸载后陈旧音源自愈失效）。
        evaluator().setAudioTransports(this.audioTransports);
        evaluator().setAudioHostPos(this.worldPosition);
        evaluator().setAudioTickStamp(level.getGameTime());
    }

    // ── 类型段 NBT：传输状态持久化 / type-specific NBT: transport persistence ──

    @Override
    protected void saveTypeSpecific(CompoundTag t, HolderLookup.Provider r) {
        ListTag list = new ListTag();
        for (var e : audioTransports.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putInt("id", e.getKey());
            MusicTransport tr = e.getValue();
            c.putBoolean("playing", tr.playing);
            c.putFloat("head", tr.head);
            c.putInt("nextFire", tr.nextFire);
            list.add(c);
        }
        t.put("audioTransports", list);
    }

    @Override
    protected void loadTypeSpecific(CompoundTag t, HolderLookup.Provider r) {
        audioTransports.clear();
        if (!t.contains("audioTransports", Tag.TAG_LIST)) return;
        ListTag list = t.getList("audioTransports", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            MusicTransport tr = new MusicTransport();
            tr.playing = c.getBoolean("playing");
            tr.head = c.getFloat("head");
            tr.nextFire = c.getInt("nextFire");
            audioTransports.put(c.getInt("id"), tr);
        }
    }

    @Override
    protected void acceptTypeSpecific(SyncedGraphBlockEntity src) {
        if (!(src instanceof AmplifierComputerBlockEntity a)) return;
        this.audioTransports.clear();
        this.audioTransports.putAll(a.audioTransports);
    }
}
