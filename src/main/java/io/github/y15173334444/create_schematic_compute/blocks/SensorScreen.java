package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.BlockNodeAllowances;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.network.BlueprintTogglePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.Map;

public class SensorScreen extends AbstractGraphScreen {
    public SensorScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".sensor"), pos);
        // input_motion + input_pose + output + debug（相对旧名单净增 POSE_CONVERT / SPLIT）
        // input_motion + input_pose + output + debug (gains POSE_CONVERT / SPLIT vs the old list)
        setNodeAllowance(BlockNodeAllowances.SENSOR);
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
    @Override public void toggleRunning(boolean start) { SensorBlockEntity be = getBE(); if(be != null) { be.setRunning(start); PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); } }
}
