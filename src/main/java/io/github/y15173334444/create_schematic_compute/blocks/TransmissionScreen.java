package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.BlockNodeAllowances;
import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.network.BlueprintSavePacket;
import io.github.y15173334444.create_schematic_compute.network.BlueprintTogglePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * 可编程变速器图编辑器。节点白名单 = 值 + 动力读数 + 变速输出 + 调试
 * （见 BlockNodeAllowances.TRANSMISSION）。
 * Transmission graph editor. Allowance = values + kinetic readings + TX_OUT + debug.
 */
public class TransmissionScreen extends AbstractGraphScreen {

    public TransmissionScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".programmable_transmission"), pos);
        setNodeAllowance(BlockNodeAllowances.TRANSMISSION);
    }

    @Override protected ProgrammableTransmissionBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(blockPos) instanceof ProgrammableTransmissionBlockEntity be) return be;
        }
        return null;
    }
    @Override protected boolean isBlockEntityValid() {
        return minecraft != null && minecraft.level != null
            && minecraft.level.getBlockEntity(blockPos) instanceof ProgrammableTransmissionBlockEntity;
    }

    @Override public NodeGraph getGraph() { ProgrammableTransmissionBlockEntity be = getBE(); return be != null ? be.host.graph : new NodeGraph(); }
    @Override public boolean isRunning() { ProgrammableTransmissionBlockEntity be = getBE(); return be != null && be.host.running; }
    @Override public Map<Integer, Boolean> getFlipflopStates() { ProgrammableTransmissionBlockEntity be = getBE(); return be != null ? be.host.runtimeState.flipflopStates : null; }
    @Override public EvalSnapshot getCachedEvalSnapshot() { ProgrammableTransmissionBlockEntity be = getBE(); return be != null ? be.getCachedEvalSnapshot() : null; }

    @Override
    public void saveGraph() {
        try {
            var be = getBE();
            if (be == null || be.getLevel() == null) return;
            var tag = new CompoundTag();
            tag.put("graph", getGraph().save(be.getLevel().registryAccess()));
            var baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, baos);
            PacketDistributor.sendToServer(new BlueprintSavePacket(be.getBlockPos(), baos.toByteArray()));
            editor.saveFeedbackUntil = System.currentTimeMillis() + 1500;
        } catch (Exception e) { SchematicCompute.LOGGER.error("Save", e); }
    }

    @Override
    public void toggleRunning(boolean start) {
        var be = getBE();
        if (be != null) { be.host.running = start; PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); }
    }
}
