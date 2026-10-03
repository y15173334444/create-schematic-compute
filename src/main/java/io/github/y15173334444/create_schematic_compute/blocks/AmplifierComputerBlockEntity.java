package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.MusicTransport;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * 功放电脑 BE：图宿主薄壳（ProgramComputer 同款 tick）+ MUSIC 传输状态注入与持久化。
 * <p>传输状态（playing/head/nextFire）住 BE 类型段 NBT（plan §3.7），每 tick 注入求值器。</p>
 * <p>Amplifier computer BE: a graph-host shell (ProgramComputer-style tick) plus MUSIC
 * transport injection + persistence (type-specific NBT, plan §3.7).</p>
 */
public class AmplifierComputerBlockEntity extends SyncedGraphBlockEntity {

    /** MUSIC 节点传输状态（nodeId → 传输），经类型段 NBT 持久化。 */
    private final Map<Integer, MusicTransport> audioTransports = new HashMap<>();

    public AmplifierComputerBlockEntity(BlockPos pos, BlockState s) {
        super(SchematicCompute.AMPLIFIER_COMPUTER_BE.get(), pos, s);
    }

    public void tick() {
        if (level == null || level.isClientSide()) return;
        ensureBusRegistered();
        var state = getBlockState();
        if (state.hasProperty(AmplifierComputerBlock.LIT)) {
            boolean shouldBeLit = isRunning() && !graph().nodes.isEmpty();
            if (state.getValue(AmplifierComputerBlock.LIT) != shouldBeLit)
                level.setBlock(worldPosition, state.setValue(AmplifierComputerBlock.LIT, shouldBeLit), 3);
        }
        rs().checkGraphChanged(graph());
        if (graphChanged()) recompileEvaluatorFull();
        if (!isRunning()) { onStopRunning(); return; }
        rs().refreshInputsActive();
        if (BusChannelHelper.recoverConflictedChannels(graph(), worldPosition, level)) {
            requestFullSync();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
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
        var in = rs().buildInputs(graph());
        float dt = 0.05f;
        var results = evaluator().evaluate(in, runtimeState().pidState, dt,
                runtimeState().delayQueues, runtimeState().flipflopStates, runtimeState().pulseTimers);
        rs().writeOutputs(results);
        broadcastEvalSnapshot();
        BusChannelHelper.syncIfBandsChanged(graph(), worldPosition, lastBusHashMap(), level);
        broadcastFlipflopDiff();
        setChanged();
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
