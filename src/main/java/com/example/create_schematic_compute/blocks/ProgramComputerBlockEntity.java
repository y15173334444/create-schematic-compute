package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.graph.GraphEvaluator;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
import com.example.create_schematic_compute.graph.RuntimeState;
import com.example.create_schematic_compute.network.BusChannelHelper;
import com.simibubi.create.foundation.blockEntity.IMergeableBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;

public class ProgramComputerBlockEntity extends BlockEntity implements MenuProvider, IMergeableBE, GraphBlockEntity {
    public NodeGraph graph = new NodeGraph();
    public boolean running = false;
    private GraphEvaluator evaluator = null;
    private NodeGraph lastEvaluatedGraph = null;

    private final RedstoneLinkHelper rs = new RedstoneLinkHelper(this);

    // 时序节点状态
    public final RuntimeState runtimeState = new RuntimeState();
    private java.util.Map<Integer, Boolean> lastSyncedFlipflopStates = null;
    private final java.util.HashMap<Integer, Integer> lastBusHashMap = new java.util.HashMap<>();

    public ProgramComputerBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.PROGRAM_BE.get(), pos, s); }
    @Override public boolean isRunning() { return running; }
    @Override public void setRunning(boolean r) { running = r; }
    @Override public boolean graphHasCycles() { return graph.hasCycles(); }
    @Override public void clearPidState() { runtimeState.pidState.clear(); }
    @Override public void syncFlipflopStates(java.util.Map<Integer, Boolean> states) {
        runtimeState.flipflopStates.clear();
        if (states != null) runtimeState.flipflopStates.putAll(states);
    }
    @Override public void syncBusBandsFromServer(String busName, java.util.List<String> bands) {
        BusChannelHelper.syncBandsFromServer(busName, bands, graph);
    }

    @Override public void accept(BlockEntity other) {
        if(other instanceof ProgramComputerBlockEntity src) {
            unregisterBusChannels(graph);
            this.graph = src.graph; this.running = src.running; runtimeState.clear(); setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override public void onLoad() { super.onLoad(); rs.onLoad(graph); }
    @Override public void onChunkUnloaded() { cleanupBusChannels(graph); unregisterBusChannels(graph); super.onChunkUnloaded(); rs.onChunkUnloaded(); }
    @Override public void setRemoved() { cleanupBusChannels(graph); unregisterBusChannels(graph); rs.setRemoved(); super.setRemoved(); }

    /** 将图中所有 BUS_OUT 频段注册到全局 SignalBus.CHANNELS */
    private void registerBusChannels() {
        if (BusChannelHelper.registerChannels(graph, worldPosition, level))
            needsFullSync = true;
    }

    /** 发送空同步清理客户端 BAND_REGISTRY（方块销毁/卸载时调用） */
    private void cleanupBusChannels(NodeGraph g) {
        BusChannelHelper.cleanupClientBands(g, worldPosition, level);
    }

    private void unregisterBusChannels(NodeGraph g) {
        BusChannelHelper.unregisterChannels(g, worldPosition, level);
    }

    public void tick() {
        if(level==null||level.isClientSide()) return;
        var state = getBlockState();
        if (!state.hasProperty(ProgramComputerBlock.LIT)) return;
        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        if(state.getValue(ProgramComputerBlock.LIT)!=shouldBeLit)
            level.setBlock(worldPosition, state.setValue(ProgramComputerBlock.LIT, shouldBeLit), 3);
        rs.checkGraphChanged(graph);
        // 图变化时维护 BUS 频道注册（必须在 running 检查之前，否则其他方块无法读取未启动电脑的 BUS_OUT）
        if(evaluator==null||lastEvaluatedGraph!=graph) {
            // 收集旧图中的 BUS_OUT 名称（供后续检测被删除的频道）
            if (lastEvaluatedGraph != null) {
                BusChannelHelper.syncDeletedBusNames(lastEvaluatedGraph, graph, worldPosition, level);
                unregisterBusChannels(lastEvaluatedGraph);
                runtimeState.clear(); // 仅 Recompile 时重置状态
            }
            evaluator = new GraphEvaluator(graph);
            evaluator.restoreSubState(runtimeState);
            lastEvaluatedGraph = graph;
            registerBusChannels();
        }
        if(!running) {
            // 停止时清除 MAP + 红石输出
            for (var n : graph.nodes) {
                if (n.type == NodeType.BUS_OUT && n.busInternalMap != null)
                    n.busInternalMap.clear();
            }
            rs.writeOutputs(java.util.Collections.emptyList());
            return;
        }

        rs.refreshInputsActive();
        BusChannelHelper.recoverConflictedChannels(graph, worldPosition, level);
        var in = rs.buildInputs(graph);
        var results = evaluator.evaluate(in, runtimeState.pidState, 0.05f,
                runtimeState.delayQueues, runtimeState.flipflopStates, runtimeState.pulseTimers);

        // DELAY 入队
        for(var n : graph.nodes) {
            if(n.type==NodeType.DELAY) {
                var q = runtimeState.delayQueues.computeIfAbsent(n.id, k -> new ArrayDeque<>());
                int ticks = Math.max(1, (int)(n.params.length>0?n.params[0]:10));
                q.addLast(evaluator.getNodeInput(n.id, 0));
                while(q.size()>ticks) q.pollFirst();
            }
        }
        rs.writeOutputs(results);
        // BUS 频段变化时发包通知所有客户端
        BusChannelHelper.syncIfBandsChanged(graph, worldPosition, lastBusHashMap, level);
        if (level instanceof net.minecraft.server.level.ServerLevel sl && !runtimeState.flipflopStates.equals(lastSyncedFlipflopStates)) {
            lastSyncedFlipflopStates = new java.util.HashMap<>(runtimeState.flipflopStates);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingChunk(sl,
                new net.minecraft.world.level.ChunkPos(worldPosition),
                new com.example.create_schematic_compute.network.RuntimeStateSyncPacket(worldPosition,
                    lastSyncedFlipflopStates));
        }
        setChanged();
    }

    public void loadGraphFromBytes(byte[] data) {
        if (level == null) return;
        try {
            var t = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(2 * 1024 * 1024));
            if(t!=null&&t.contains("graph")){ graph=NodeGraph.load(t.getCompound("graph"),level.registryAccess()); runtimeState.clear(); rs.onLoad(graph);
            needsFullSync=true; setChanged(); if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }
            setChanged();
        } catch(Exception e) { SchematicCompute.LOGGER.error("Failed to load program", e); graph=new NodeGraph(); runtimeState.clear(); setChanged(); }
    }

    @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.saveAdditional(t,r); t.put("graph", graph.save(r)); t.putBoolean("running", running);
        t.put("runtime", runtimeState.save());
    }
    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t,r);
        if (t.contains("graph")) {
            // 保留客户端展开状态（服务器同步不应覆盖 UI 状态）
            var oldExpanded = new java.util.HashMap<Integer, Boolean>();
            for (var n : graph.nodes) if (n.expanded) oldExpanded.put(n.id, true);
            graph = NodeGraph.load(t.getCompound("graph"), r);
            for (var n : graph.nodes) if (oldExpanded.containsKey(n.id)) n.expanded = true;
            rs.onLoad(graph);
        }
        if (t.contains("running")) running = t.getBoolean("running");
        if (t.contains("runtime")) {
            RuntimeState loaded = RuntimeState.load(t.getCompound("runtime"));
            runtimeState.pidState.putAll(loaded.pidState);
            runtimeState.delayQueues.putAll(loaded.delayQueues);
            runtimeState.flipflopStates.putAll(loaded.flipflopStates);
            runtimeState.pulseTimers.putAll(loaded.pulseTimers);
            runtimeState.subStates.putAll(loaded.subStates);
            // 重新编译通过 runtimeState.clear() 重置为初始状态，世界重载保留运行状态
        }
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    private boolean needsFullSync = true;
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) {
        if (needsFullSync) { needsFullSync = false; var t=new CompoundTag(); saveAdditional(t,r); return t; }
        var t=new CompoundTag(); t.putBoolean("running", running); return t;
    }
    @Override public Component getDisplayName() { return Component.translatable("container."+SchematicCompute.MOD_ID+".program"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new ProgramComputerMenu(id, this); }
}
