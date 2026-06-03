package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.graph.GraphEvaluator;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
import com.simibubi.create.Create;
import com.simibubi.create.content.redstone.link.IRedstoneLinkable;
import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler;
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
import java.util.*;

public class ProgramComputerBlockEntity extends BlockEntity implements MenuProvider {
    private static final Map<Integer, Float> EMPTY_PID = new HashMap<>();
    public NodeGraph graph = new NodeGraph();
    public boolean running = false;
    private GraphEvaluator evaluator = null;
    private NodeGraph lastEvaluatedGraph = null;
    private final List<FreqLink> freqLinks = new ArrayList<>();
    private final Map<Integer, Integer> lastInputs = new HashMap<>();
    private final Map<Integer, Integer> lastOutputs = new HashMap<>();

    // 时序节点状态
    private final Map<Integer, ArrayDeque<Float>> delayQueues = new HashMap<>();
    private final Map<Integer, Boolean> flipflopStates = new HashMap<>();
    private final Map<Integer, Integer> pulseTimers = new HashMap<>();

    private record FreqLink(ItemStack stack, IRedstoneLinkable linkable) {}
    private static int hash(ItemStack s) { return s.isEmpty() ? 0 : s.getItem().hashCode(); }

    public ProgramComputerBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.PROGRAM_BE.get(), pos, s); }

    @Override public void onLoad() { super.onLoad(); registerLinks(); }
    @Override public void onChunkUnloaded() { super.onChunkUnloaded(); unregisterLinks(); }
    @Override public void setRemoved() { unregisterLinks(); super.setRemoved(); }

    private void registerLinks() {
        if(level==null||level.isClientSide()) return;
        unregisterLinks();
        var EMPTY = RedstoneLinkNetworkHandler.Frequency.EMPTY;
        for(var n : graph.nodes) {
            if(n.type==NodeType.REDSTONE_IN||n.type==NodeType.REDSTONE_OUT) {
                var h1 = n.itemParams!=null&&n.itemParams.length>0&&!n.itemParams[0].isEmpty();
                var h2 = n.itemParams!=null&&n.itemParams.length>1&&!n.itemParams[1].isEmpty();
                var f1 = h1 ? RedstoneLinkNetworkHandler.Frequency.of(n.itemParams[0]) : EMPTY;
                var f2 = h2 ? RedstoneLinkNetworkHandler.Frequency.of(n.itemParams[1]) : EMPTY;
                var isIn = n.type==NodeType.REDSTONE_IN;
                var h = hash(h1 ? n.itemParams[0] : (h2 ? n.itemParams[1] : ItemStack.EMPTY));
                var stacks = new ArrayList<ItemStack>();
                if(h1) stacks.add(n.itemParams[0]);
                if(h2) stacks.add(n.itemParams[1]);
                var primary = stacks.isEmpty() ? ItemStack.EMPTY : stacks.get(0);
                addLink(isIn, h, f1, f2, primary);
            }
        }
        for(var fl : freqLinks) {
            var net = Create.REDSTONE_LINK_NETWORK_HANDLER.getNetworkOf(level, fl.linkable);
            if(net!=null) for(var l : net) if(l!=fl.linkable&&l.isAlive()) {
                int sig = l.getTransmittedStrength();
                if(sig>0) { lastInputs.put(hash(fl.stack), sig); break; }
            }
        }
    }

    private void addLink(boolean isIn, int h, RedstoneLinkNetworkHandler.Frequency f1, RedstoneLinkNetworkHandler.Frequency f2, ItemStack stack) {
        var link = new IRedstoneLinkable() {
            public int getTransmittedStrength() { return isIn?0:lastOutputs.getOrDefault(h,0); }
            public void setReceivedStrength(int s) { if(isIn) lastInputs.put(h,s); }
            public boolean isListening() { return isIn; }
            public boolean isAlive() { return true; }
            public Couple<RedstoneLinkNetworkHandler.Frequency> getNetworkKey() { return Couple.create(f1,f2); }
            public BlockPos getLocation() { return worldPosition; }
        };
        Create.REDSTONE_LINK_NETWORK_HANDLER.addToNetwork(level, link);
        freqLinks.add(new FreqLink(stack, link));
    }

    private void unregisterLinks() {
        for(var fl : freqLinks) Create.REDSTONE_LINK_NETWORK_HANDLER.removeFromNetwork(level, fl.linkable);
        freqLinks.clear();
    }

    public void tick() {
        if(level==null||level.isClientSide()) return;
        var state = getBlockState();
        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        if(state.getValue(ProgramComputerBlock.LIT)!=shouldBeLit)
            level.setBlock(worldPosition, state.setValue(ProgramComputerBlock.LIT, shouldBeLit), 3);
        if(!running) return;

        if(evaluator==null||lastEvaluatedGraph!=graph) {
            evaluator = new GraphEvaluator(graph);
            lastEvaluatedGraph = graph;
        }

        // 构建输入源：REDSTONE_IN 从机械动力红石网络读取
        var in = new ArrayList<GraphEvaluator.InputSource>();
        for(var n : graph.nodes)
            if(n.type==NodeType.REDSTONE_IN)
                in.add(new GraphEvaluator.InputSource(getFreq(n.itemParams), lastInputs.getOrDefault(hash(getFreq(n.itemParams)),0)));

        // 评估
        evaluator.evaluate(in, EMPTY_PID, 0.05f, delayQueues, flipflopStates, pulseTimers);

        // DELAY 入队
        for(var n : graph.nodes) {
            if(n.type==NodeType.DELAY) {
                var q = delayQueues.computeIfAbsent(n.id, k -> new ArrayDeque<>());
                int ticks = Math.max(1, (int)(n.params.length>0?n.params[0]:10));
                q.addLast(evaluator.getNodeInput(n.id, 0));
                while(q.size()>ticks) q.pollFirst();
            }
        }

        // REDSTONE_OUT 写入机械动力红石网络
        lastOutputs.clear();
        for(var n : graph.nodes) {
            if(n.type==NodeType.REDSTONE_OUT) {
                float v = evaluator.getNodeInput(n.id, 0);
                int h = hash(getFreq(n.itemParams));
                lastOutputs.put(h, Math.round(v));
                for(var fl : freqLinks) if(hash(fl.stack)==h)
                    Create.REDSTONE_LINK_NETWORK_HANDLER.updateNetworkOf(level, fl.linkable);
            }
        }
        setChanged();
    }

    private static ItemStack getFreq(ItemStack[] p) { if(p.length>0&&!p[0].isEmpty())return p[0]; if(p.length>1&&!p[1].isEmpty())return p[1]; return ItemStack.EMPTY; }

    public void loadGraphFromBytes(byte[] data) {
        try {
            var t = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
            if(t!=null&&t.contains("graph")){ graph=NodeGraph.load(t.getCompound("graph"),level.registryAccess()); registerLinks(); }
            setChanged();
        } catch(Exception e) { SchematicCompute.LOGGER.error("Failed to load program", e); graph=new NodeGraph(); setChanged(); }
    }

    @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) { super.saveAdditional(t,r); t.put("graph",graph.save(r)); t.putBoolean("running",running); }
    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) { super.loadAdditional(t,r); if(t.contains("graph")){ graph=NodeGraph.load(t.getCompound("graph"),r); registerLinks(); } if(t.contains("running"))running=t.getBoolean("running"); }
    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) { var t=new CompoundTag(); saveAdditional(t,r); return t; }
    @Override public Component getDisplayName() { return Component.translatable("container."+SchematicCompute.MOD_ID+".program"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new ProgramComputerMenu(id, this); }
}
