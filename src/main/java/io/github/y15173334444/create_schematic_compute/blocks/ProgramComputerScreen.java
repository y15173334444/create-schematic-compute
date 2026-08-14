package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.network.BlueprintSavePacket;
import io.github.y15173334444.create_schematic_compute.network.BlueprintTogglePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.io.ByteArrayOutputStream;
import java.util.Map;

public class ProgramComputerScreen extends AbstractGraphScreen {

    public ProgramComputerScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".program_computer"), pos);
        setNodeFilter(nt -> nt == NodeType.CONST
            || nt == NodeType.REDSTONE_IN
            || nt == NodeType.REDSTONE_OUT
            || nt == NodeType.PRIVATE_IN
            || nt == NodeType.PRIVATE_OUT
            || nt == NodeType.BUS_IN
            || nt == NodeType.BUS_OUT
            || nt == NodeType.DELAY
            || nt == NodeType.LATCH
            || nt == NodeType.T_FLIPFLOP
            || nt == NodeType.PULSE_EXTEND
            || nt == NodeType.LOOP
            || nt == NodeType.FUSE
            || nt == NodeType.BOOL
            || nt == NodeType.ACCUMULATOR
            || nt == NodeType.INTEGRATOR
            || nt == NodeType.GATE
            || nt == NodeType.SIN
            || nt == NodeType.COS
            || nt == NodeType.TAN
            || nt == NodeType.ASIN
            || nt == NodeType.ACOS
            || nt == NodeType.ATAN2
            || nt == NodeType.SINH
            || nt == NodeType.COSH
            || nt == NodeType.SQRT
            || nt == NodeType.LN
            || nt == NodeType.LOG
            || nt == NodeType.EXP
            || nt == NodeType.SEC
            || nt == NodeType.CSC
            || nt == NodeType.COT
            || nt == NodeType.ANGLE_UNWRAP
            || nt == NodeType.DIRECTION
            || nt == NodeType.COMMENT
            || nt == NodeType.DEBUG_SIGNAL_GEN
            || nt == NodeType.DEBUG_PROBE
            || nt == NodeType.RELAY_A
            || nt == NodeType.RELAY_B);
    }

    @Override protected ProgramComputerBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(blockPos) instanceof ProgramComputerBlockEntity be) return be;
        }
        return null;
    }
    @Override protected boolean isBlockEntityValid() {
        return minecraft != null && minecraft.level != null
            && minecraft.level.getBlockEntity(blockPos) instanceof ProgramComputerBlockEntity;
    }
    @Override public NodeGraph getGraph() { ProgramComputerBlockEntity be = getBE(); return be != null ? be.graph : new NodeGraph(); }
    @Override public boolean isRunning() { ProgramComputerBlockEntity be = getBE(); return be != null && be.running; }
    @Override public Map<Integer, Boolean> getFlipflopStates() { ProgramComputerBlockEntity be = getBE(); return be != null ? be.runtimeState.flipflopStates : null; }
    @Override public EvalSnapshot getCachedEvalSnapshot() {
        ProgramComputerBlockEntity be = getBE();
        return be != null ? be.cachedEvalSnapshot : null;
    }

    @Override
    public void saveGraph() {
        try {
            ProgramComputerBlockEntity be = getBE();
            if(be==null||be.getLevel()==null) return;
            var tag = new CompoundTag(); tag.put("graph", getGraph().save(be.getLevel().registryAccess()));
            var baos = new ByteArrayOutputStream(); NbtIo.writeCompressed(tag, baos);
            PacketDistributor.sendToServer(new BlueprintSavePacket(be.getBlockPos(), baos.toByteArray()));
            editor.saveFeedbackUntil = System.currentTimeMillis() + 1500;
        } catch(Exception e) { SchematicCompute.LOGGER.error("Save", e); }
    }

    @Override
    public void toggleRunning(boolean start) {
        ProgramComputerBlockEntity be = getBE();
        if(be != null) { be.running = start; PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); }
    }
}
