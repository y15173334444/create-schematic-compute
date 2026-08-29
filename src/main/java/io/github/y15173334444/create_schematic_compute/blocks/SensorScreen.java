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

public class SensorScreen extends AbstractGraphScreen {
    private static boolean isAllowed(NodeType nt) {
        return nt == NodeType.ATTITUDE || nt == NodeType.FORWARD || nt == NodeType.ACCELERATION || nt == NodeType.VELOCITY || nt == NodeType.POSITION || nt == NodeType.BUS_OUT
            || nt == NodeType.REDSTONE_OUT || nt == NodeType.PRIVATE_OUT
            || nt == NodeType.COMMENT
            || nt == NodeType.DEBUG_SIGNAL_GEN
            || nt == NodeType.DEBUG_PROBE;
    }
    public SensorScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".sensor"), pos);
        setNodeFilter(SensorScreen::isAllowed);
    }
    @Override protected SensorBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(blockPos) instanceof SensorBlockEntity be) return be;
        }
        return null;
    }
    @Override protected boolean isBlockEntityValid() {
        return minecraft != null && minecraft.level != null
            && minecraft.level.getBlockEntity(blockPos) instanceof SensorBlockEntity;
    }
    @Override public NodeGraph getGraph() { SensorBlockEntity be = getBE(); return be != null ? be.getNodeGraph() : new NodeGraph(); }
    @Override public boolean isRunning() { SensorBlockEntity be = getBE(); return be != null && be.isRunning(); }
    @Override public Map<Integer, Boolean> getFlipflopStates() { SensorBlockEntity be = getBE(); return be != null ? be.getFlipflopStates() : null; }
    @Override public EvalSnapshot getCachedEvalSnapshot() {
        SensorBlockEntity be = getBE();
        return be != null ? be.getCachedEvalSnapshot() : null;
    }
    @Override public void saveGraph() {
        try { SensorBlockEntity be = getBE();
            if(be==null||be.getLevel()==null) return;
            var tag = new CompoundTag(); tag.put("graph", getGraph().save(be.getLevel().registryAccess()));
            var baos = new ByteArrayOutputStream(); NbtIo.writeCompressed(tag, baos);
            PacketDistributor.sendToServer(new BlueprintSavePacket(be.getBlockPos(), baos.toByteArray()));
            editor.saveFeedbackUntil = System.currentTimeMillis() + 1500;
        } catch(Exception e) { SchematicCompute.LOGGER.error("Save", e); }
    }
    @Override public void toggleRunning(boolean start) { SensorBlockEntity be = getBE(); if(be != null) { be.setRunning(start); PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); } }
}
