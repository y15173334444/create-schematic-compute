package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.RuntimeState;
import io.github.y15173334444.create_schematic_compute.network.BusChannelHelper;
import io.github.y15173334444.create_schematic_compute.network.RuntimeStateSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import javax.annotation.Nullable;
import java.util.ArrayDeque;

public class ProgramComputerBlockEntity extends SyncedGraphBlockEntity {
    private java.util.Map<Integer, Boolean> lastSyncedFlipflopStates = null;

    public ProgramComputerBlockEntity(BlockPos pos, BlockState s) { super(SchematicCompute.PROGRAM_BE.get(), pos, s); }

    @Override public void accept(BlockEntity other) {
        if(other instanceof ProgramComputerBlockEntity src) {
            unregisterBusChannels(graph);
            this.graph = src.graph; this.running = src.running; runtimeState.clear();
            setChanged();
            if(level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void tick() {
        if(level==null||level.isClientSide()) return;
        ensureBusRegistered();
        var state = getBlockState();
        if (!state.hasProperty(ProgramComputerBlock.LIT)) return;
        boolean shouldBeLit = running && !graph.nodes.isEmpty();
        if(state.getValue(ProgramComputerBlock.LIT)!=shouldBeLit)
            level.setBlock(worldPosition, state.setValue(ProgramComputerBlock.LIT, shouldBeLit), 3);
        rs.checkGraphChanged(graph);
        if(graphChanged()) recompileEvaluatorFull();
        if(!running) { onStopRunning(); return; }
        rs.refreshInputsActive();
        BusChannelHelper.recoverConflictedChannels(graph, worldPosition, level);
        var in = rs.buildInputs(graph);
        float dt = 0.05f;
        var results = evaluator.evaluate(in, runtimeState.pidState, dt,
                runtimeState.delayQueues, runtimeState.flipflopStates, runtimeState.pulseTimers);
        for (var n : graph.nodes) {
            if (n.type == NodeType.DELAY) {
                var q = runtimeState.delayQueues.computeIfAbsent(n.id, k -> new ArrayDeque<>());
                int ticks = Math.max(1, (int)(n.params.length > 0 ? n.params[0] : 10));
                q.addLast(evaluator.getNodeInput(n.id, 0));
                while (q.size() > ticks) q.pollFirst();
            }
        }
        rs.writeOutputs(results);
        broadcastEvalSnapshot(); // 广播 EvalSnapshot 给客户端（供 DEBUG_PROBE 采样）
        BusChannelHelper.syncIfBandsChanged(graph, worldPosition, lastBusHashMap, level);
        if (level instanceof ServerLevel sl && !runtimeState.flipflopStates.equals(lastSyncedFlipflopStates)) {
            lastSyncedFlipflopStates = new java.util.HashMap<>(runtimeState.flipflopStates);
            PacketDistributor.sendToPlayersTrackingChunk(sl, new ChunkPos(worldPosition),
                new RuntimeStateSyncPacket(worldPosition, lastSyncedFlipflopStates));
        }
        setChanged();
    }

    @Override protected void loadAdditional(CompoundTag t, HolderLookup.Provider r) {
        super.loadAdditional(t, r);
        if (t.contains("runtime")) {
            RuntimeState loaded = RuntimeState.load(t.getCompound("runtime"));
            runtimeState.delayQueues.putAll(loaded.delayQueues);
            runtimeState.flipflopStates.putAll(loaded.flipflopStates);
            runtimeState.pulseTimers.putAll(loaded.pulseTimers);
            runtimeState.subStates.putAll(loaded.subStates);
        }
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override public Component getDisplayName() { return Component.translatable("container."+SchematicCompute.MOD_ID+".program_computer"); }
    @Nullable @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new ProgramComputerMenu(id, this); }
}
