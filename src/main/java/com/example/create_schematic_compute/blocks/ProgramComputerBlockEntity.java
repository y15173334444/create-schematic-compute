package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.graph.GraphEvaluator;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
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
import java.util.HashMap;
import java.util.Map;

public class ProgramComputerBlockEntity extends BlockEntity implements MenuProvider, IMergeableBE, GraphBlockEntity {
    public NodeGraph graph = new NodeGraph();
    public boolean running = false;
    private GraphEvaluator evaluator = null;
    private NodeGraph lastEvaluatedGraph = null;

    private final RedstoneLinkHelper rs = new RedstoneLinkHelper(this);

    // 时序节点状态
    private final Map<Integer, Float> pidState = new HashMap<>();
    private final Map<Integer, ArrayDeque<Float>> delayQueues = new HashMap<>();
    private final Map<Integer, Boolean> flipflopStates = new HashMap<>();
    private final Map<Integer, Integer> pulseTimers = new HashMap<>();

    public ProgramComputerBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.PROGRAM_BE.get(), pos, s); }
    @Override public boolean isRunning() { return running; }
    @Override public void setRunning(boolean r) { running = r; setChanged(); if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }
    @Override public boolean graphHasCycles() { return graph.hasCycles(); }
    @Override public void clearPidState() {} // no PID state

    @Override public void accept(BlockEntity other) {
        if(other instanceof ProgramComputerBlockEntity src) { this.graph = src.graph; this.running = src.running; setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }
    }

    @Override public void onLoad() { super.onLoad(); rs.onLoad(graph); }
    @Override public void onChunkUnloaded() { super.onChunkUnloaded(); rs.onChunkUnloaded(); }
    @Override public void setRemoved() { rs.setRemoved(); super.setRemoved(); }

    public void tick() {
        if(level==null||level.isClientSide()) return;
        var state = getBlockState();
        if (!state.hasProperty(ProgramComputerBlock.LIT)) return;
        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        if(state.getValue(ProgramComputerBlock.LIT)!=shouldBeLit)
            level.setBlock(worldPosition, state.setValue(ProgramComputerBlock.LIT, shouldBeLit), 3);
        rs.checkGraphChanged(graph);
        if(!running) return;

        if(evaluator==null||lastEvaluatedGraph!=graph) { evaluator = new GraphEvaluator(graph); lastEvaluatedGraph = graph; }

        rs.refreshInputsActive();
        var in = rs.buildInputs(graph);
        var results = evaluator.evaluate(in, pidState, 0.05f, delayQueues, flipflopStates, pulseTimers);

        // DELAY 入队
        for(var n : graph.nodes) {
            if(n.type==NodeType.DELAY) {
                var q = delayQueues.computeIfAbsent(n.id, k -> new ArrayDeque<>());
                int ticks = Math.max(1, (int)(n.params.length>0?n.params[0]:10));
                q.addLast(evaluator.getNodeInput(n.id, 0));
                while(q.size()>ticks) q.pollFirst();
            }
        }
        rs.writeOutputs(results);
        setChanged();
    }

    public void loadGraphFromBytes(byte[] data) {
        if (level == null) return;
        try {
            var t = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(2 * 1024 * 1024));
            if(t!=null&&t.contains("graph")){ graph=NodeGraph.load(t.getCompound("graph"),level.registryAccess()); rs.onLoad(graph); setChanged(); if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }
            setChanged();
        } catch(Exception e) { SchematicCompute.LOGGER.error("Failed to load program", e); graph=new NodeGraph(); setChanged(); }
    }

    @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.saveAdditional(t,r); t.put("graph", graph.save(r)); t.putBoolean("running", running);
    }
    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t,r);
        if (t.contains("graph")) { graph = NodeGraph.load(t.getCompound("graph"), r); rs.onLoad(graph); }
        if (t.contains("running")) running = t.getBoolean("running");
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) { var t=new CompoundTag(); saveAdditional(t,r); return t; }
    @Override public Component getDisplayName() { return Component.translatable("container."+SchematicCompute.MOD_ID+".program"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new ProgramComputerMenu(id, this); }
}
