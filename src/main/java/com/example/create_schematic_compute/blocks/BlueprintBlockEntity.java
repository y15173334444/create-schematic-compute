package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.ModUtils;
import com.example.create_schematic_compute.SchematicCompute;

import com.example.create_schematic_compute.graph.GraphEvaluator;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
import com.simibubi.create.foundation.blockEntity.IMergeableBE;
import net.createmod.catnip.data.Couple;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BlueprintBlockEntity extends BlockEntity implements MenuProvider, IMergeableBE {
    public NodeGraph graph = new NodeGraph();
    public boolean running = false;
    public final Map<Integer, Float> pidState = new HashMap<>();
    private final List<FreqLink> freqLinks = new ArrayList<>();
    private final Map<Long, Integer> lastInputs = new HashMap<>();
    private final Map<Long, Integer> lastOutputs = new HashMap<>();

    // 重用评估器，减少每 tick 的 GC 压力
    private GraphEvaluator evaluator = null;
    private NodeGraph lastEvaluatedGraph = null;

    private record FreqLink(long freqKey, IRedstoneLinkable linkable) {}

    public BlueprintBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.BLUEPRINT_BE.get(), pos, s); }
    /** Create 蓝图系统通过 IMergeableBE 接口恢复方块实体数据 */
    @Override public void accept(net.minecraft.world.level.block.entity.BlockEntity other) {
        if(other instanceof BlueprintBlockEntity src) {
            this.graph = src.graph;
            this.running = src.running;
            setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
    public void toggleRunning() { running=!running; setChanged(); if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3); }

    @Override public void onLoad() { super.onLoad(); registerLinks(); }
    @Override public void onChunkUnloaded() { super.onChunkUnloaded(); unregisterLinks(); }
    @Override public void setRemoved() { unregisterLinks(); super.setRemoved(); }

    private void registerLinks() {
        if(level==null||level.isClientSide()) return;
        unregisterLinks();
        var EMPTY = RedstoneLinkNetworkHandler.Frequency.EMPTY;
        for(var n : graph.nodes) {
            if(n.type==NodeType.REDSTONE_IN||n.type==NodeType.REDSTONE_OUT) {
                var item1 = n.itemParams!=null&&n.itemParams.length>0 ? n.itemParams[0] : ItemStack.EMPTY;
                var item2 = n.itemParams!=null&&n.itemParams.length>1 ? n.itemParams[1] : ItemStack.EMPTY;
                var f1 = !item1.isEmpty() ? RedstoneLinkNetworkHandler.Frequency.of(item1) : EMPTY;
                var f2 = !item2.isEmpty() ? RedstoneLinkNetworkHandler.Frequency.of(item2) : EMPTY;
                var freqKey = ModUtils.freqKey(item1, item2);
                var isIn = n.type==NodeType.REDSTONE_IN;
                addLink(isIn, freqKey, f1, f2);
            }
        }
        for(var fl : freqLinks) {
            var net = Create.REDSTONE_LINK_NETWORK_HANDLER.getNetworkOf(level, fl.linkable);
            if(net!=null) for(var l : net) if(l!=fl.linkable&&l.isAlive()) {
                int sig = l.getTransmittedStrength();
                if(sig>0) { lastInputs.put(fl.freqKey, sig); break; }
            }
        }
    }

    private void addLink(boolean isIn, long freqKey, RedstoneLinkNetworkHandler.Frequency f1, RedstoneLinkNetworkHandler.Frequency f2) {
        var link = new IRedstoneLinkable() {
            public int getTransmittedStrength() { return isIn?0:lastOutputs.getOrDefault(freqKey,0); }
            public void setReceivedStrength(int s) { if(isIn) lastInputs.put(freqKey,s); }
            public boolean isListening() { return isIn; }
            public boolean isAlive() { return true; }
            public Couple<RedstoneLinkNetworkHandler.Frequency> getNetworkKey() { return Couple.create(f1,f2); }
            public BlockPos getLocation() { return worldPosition; }
        };
        Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, link);
        freqLinks.add(new FreqLink(freqKey, link));
    }

    private void unregisterLinks() {
        for(var fl : freqLinks) Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, fl.linkable);
        freqLinks.clear();
    }

    public void tick() {
        if(level==null||level.isClientSide()) return;
        // 无论 running 与否都更新 LIT 状态（停机时熄灭）
        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        var currentState = getBlockState();
        if(currentState.getValue(BlueprintBlock.LIT)!=shouldBeLit)
            level.setBlock(worldPosition, currentState.setValue(BlueprintBlock.LIT, shouldBeLit), 3);
        if(!running) return;
        // 重用 GraphEvaluator，图对象变化时重建（同时清零 PID 积分）
        if (evaluator == null || lastEvaluatedGraph != graph) {
            evaluator = new GraphEvaluator(graph);
            lastEvaluatedGraph = graph;
            pidState.clear();
        }
        var in = new ArrayList<GraphEvaluator.InputSource>();
        for(var n : graph.nodes) {
            if(n.type==NodeType.REDSTONE_IN) {
                long fk = ModUtils.freqKey(n.itemParams);
                in.add(new GraphEvaluator.InputSource(fk, lastInputs.getOrDefault(fk, 0)));
            }
        }
        float dt = 0.05f;
        var results = evaluator.evaluate(in, pidState, dt);
        lastOutputs.clear();
        for(var r : results) {
            long freqKey = ModUtils.freqKey(r.freq1(), r.freq2());
            lastOutputs.put(freqKey, r.signal());
            for(var fl : freqLinks) if(fl.freqKey() == freqKey) Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(level, fl.linkable);
        }
        setChanged();
    }

    public void loadGraphFromBytes(byte[] data) {
        if (level == null) return;
        try {
            var t = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(2 * 1024 * 1024));
            if (t != null && t.contains("graph")) {
                graph = NodeGraph.load(t.getCompound("graph"), level.registryAccess());
                registerLinks();
            }
            setChanged();
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Failed to load blueprint graph, resetting", e);
            graph = new NodeGraph();
            registerLinks();
            setChanged();
        }
    }
    @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.saveAdditional(t,r);
        t.put("graph", graph.save(r));
        t.putBoolean("running", running);
    }
    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t,r);
        if (t.contains("graph")) {
            graph = NodeGraph.load(t.getCompound("graph"), r);
            registerLinks();
        }
        if (t.contains("running")) running = t.getBoolean("running");
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) { var t=new CompoundTag(); saveAdditional(t,r); return t; }
    @Override public Component getDisplayName() { return Component.translatable("container."+SchematicCompute.MOD_ID+".blueprint"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new BlueprintMenu(id,this); }
}
