package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.GraphEvaluator;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.RuntimeState;
import io.github.y15173334444.create_schematic_compute.network.MonitorRedstoneSyncPacket;
import com.simibubi.create.foundation.blockEntity.IMergeableBE;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
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

public class MonitorBlockEntity extends BlockEntity implements MenuProvider, IMergeableBE, GraphBlockEntity {
    public NodeGraph graph = new NodeGraph();
    @Override public io.github.y15173334444.create_schematic_compute.graph.NodeGraph getNodeGraph() { return graph; }
    public boolean running = false;
    public final RuntimeState runtimeState = new RuntimeState();

    // ── Redstone Link network integration ──
    private final RedstoneLinkHelper rs = new RedstoneLinkHelper(this);
    public void putRedstoneInput(long freqKey, int signal) { rs.putInput(freqKey, signal); }
    public int getRedstoneInput(long freqKey) { return rs.getInput(freqKey); }

    // Display settings (units in blocks)
    public float screenWidth = 1.5f, screenLength = 1.2f;
    public float screenX = 0f, screenY = 2.0f, screenZ = 0f;
    public float screenRoll = 0f, screenPitch = 0f, screenYaw = 0f;

    private GraphEvaluator evaluator = null;
    private NodeGraph lastEvaluatedGraph = null;
    private int lastGraphGeneration = -1;

    public MonitorBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.MONITOR_BE.get(), pos, s); }

    @Override public void accept(BlockEntity other) {
        if(other instanceof MonitorBlockEntity src) {
            this.graph = src.graph; this.running = src.running;
            this.screenWidth = src.screenWidth; this.screenLength = src.screenLength;
            this.screenX = src.screenX; this.screenY = src.screenY; this.screenZ = src.screenZ;
            this.screenRoll = src.screenRoll; this.screenPitch = src.screenPitch; this.screenYaw = src.screenYaw;
            runtimeState.clear();
            setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override public boolean isRunning() { return running; }
    @Override public void setRunning(boolean r) { running = r; setChanged(); }
    @Override public boolean graphHasCycles() { return graph.hasCycles(); }
    @Override public void clearPidState() { runtimeState.pidState.clear(); }

    public void toggleRunning() { running = !running; setChanged(); if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }

    // ── Redstone links ──
    @Override public void onLoad() { super.onLoad(); rs.onLoad(graph); }
    @Override public void onChunkUnloaded() { super.onChunkUnloaded(); rs.onChunkUnloaded(); }
    @Override public void setRemoved() { rs.setRemoved(); super.setRemoved(); }

    public void tick() {
        if(level == null || level.isClientSide()) return;
        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        var currentState = getBlockState();
        if (!currentState.hasProperty(MonitorBlock.LIT)) return;
        if(currentState.getValue(MonitorBlock.LIT) != shouldBeLit)
            level.setBlock(worldPosition, currentState.setValue(MonitorBlock.LIT, shouldBeLit), 3);
        rs.checkGraphChanged(graph);
        if(!running) return;
        if(evaluator == null || lastGraphGeneration != graph.graphGeneration) {
            evaluator = new GraphEvaluator(graph);
            lastEvaluatedGraph = graph;
            lastGraphGeneration = graph.graphGeneration;
            runtimeState.pidState.clear();
        }
        rs.refreshInputs();
        var in = rs.buildInputs(graph);
        float dt = 0.05f;
        var results = evaluator.evaluate(in, runtimeState.pidState, dt);
        rs.writeOutputs(results);
        // Sync redstone inputs to tracking clients for in-world rendering
        if (level instanceof ServerLevel sl) {
            for (var e : rs.lastInputs().entrySet())
                PacketDistributor.sendToPlayersTrackingChunk(sl, new ChunkPos(worldPosition),
                    new MonitorRedstoneSyncPacket(worldPosition, e.getKey(), e.getValue()));
        }
        setChanged();
    }

    public void loadGraphFromBytes(byte[] data) {
        if (level == null) return;
        try {
            var t = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.create(2 * 1024 * 1024));
            if (t != null && t.contains("graph")) { graph = NodeGraph.load(t.getCompound("graph"), level.registryAccess()); }
            if (t != null) loadSettings(t);
            rs.onLoad(graph);
            setChanged();
        } catch (Exception e) {
            SchematicCompute.LOGGER.error("Failed to load monitor graph, resetting", e);
            graph = new NodeGraph();
            rs.onLoad(graph);
            setChanged();
        }
    }

    public void applySettings(float w, float l, float x, float y, float z, float r, float p, float yw) {
        this.screenWidth = Math.max(0.1f, Math.min(10f, w)); this.screenLength = Math.max(0.1f, Math.min(10f, l));
        this.screenX = Math.max(-10f, Math.min(10f, x)); this.screenY = Math.max(-10f, Math.min(10f, y));
        this.screenZ = Math.max(-10f, Math.min(10f, z));
        this.screenRoll = r % 360f; this.screenPitch = p % 360f; this.screenYaw = yw % 360f;
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override protected void saveAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.saveAdditional(t, r); t.put("graph", graph.save(r)); t.putBoolean("running", running);
        t.put("runtime", runtimeState.save());
        saveSettings(t);
        var inputs = new CompoundTag();
        for(var e : rs.lastInputs().entrySet()) inputs.putInt(String.valueOf(e.getKey()), e.getValue());
        t.put("rs_in", inputs);
    }
    private void saveSettings(CompoundTag t) {
        t.putFloat("ss_w", screenWidth); t.putFloat("ss_l", screenLength);
        t.putFloat("ss_x", screenX); t.putFloat("ss_y", screenY); t.putFloat("ss_z", screenZ);
        t.putFloat("ss_r", screenRoll); t.putFloat("ss_p", screenPitch); t.putFloat("ss_yw", screenYaw);
    }
    public void loadSettings(CompoundTag t) {
        if (t.contains("ss_w")) screenWidth = t.getFloat("ss_w"); if (t.contains("ss_l")) screenLength = t.getFloat("ss_l");
        if (t.contains("ss_x")) screenX = t.getFloat("ss_x"); if (t.contains("ss_y")) screenY = t.getFloat("ss_y");
        if (t.contains("ss_z")) screenZ = t.getFloat("ss_z");
        if (t.contains("ss_r")) screenRoll = t.getFloat("ss_r"); if (t.contains("ss_p")) screenPitch = t.getFloat("ss_p");
        if (t.contains("ss_yw")) screenYaw = t.getFloat("ss_yw");
    }
    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t, r);
        if (t.contains("graph")) { graph = NodeGraph.load(t.getCompound("graph"), r); rs.onLoad(graph); }
        if (t.contains("running")) running = t.getBoolean("running");
        if (t.contains("runtime")) {
            RuntimeState loaded = RuntimeState.load(t.getCompound("runtime"));
            runtimeState.pidState.putAll(loaded.pidState);
        }
        loadSettings(t);
        if (t.contains("rs_in")) { var inputs = t.getCompound("rs_in"); for(var k : inputs.getAllKeys()) putRedstoneInput(Long.parseLong(k), inputs.getInt(k)); }
        setChanged();
    }
    @Nullable @Override public Packet<ClientGamePacketListener> getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    public void flagFullSync() { setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3); }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r) {
        // Always send full data — the graph is the authoritative source for in-world
        // rendering and new players need it on first chunk load regardless of whether
        // a previous player already consumed a full sync.
        var t = new CompoundTag(); saveAdditional(t, r); return t;
    }
    @Override public Component getDisplayName() { return Component.translatable("container." + SchematicCompute.MOD_ID + ".monitor"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new MonitorMenu(id, this); }
}
