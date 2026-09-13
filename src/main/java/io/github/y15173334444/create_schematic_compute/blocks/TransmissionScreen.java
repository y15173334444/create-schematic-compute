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

/**
 * 可编程变速器图编辑器。节点白名单与转速代理控制器同款（自身输出节点换成 TX_OUT）：
 * TX_OUT + CONST / REDSTONE_IN / PRIVATE_IN / BUS_IN + COMMENT / DEBUG。
 * Transmission graph editor. The whitelist mirrors the Speed Proxy's set with its own
 * output node (TX_OUT): TX_OUT + CONST / REDSTONE_IN / PRIVATE_IN / BUS_IN + COMMENT / DEBUG.
 */
public class TransmissionScreen extends AbstractGraphScreen {

    public TransmissionScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".programmable_transmission"), pos);
        setNodeFilter(nt ->
            nt == NodeType.STRESS || nt == NodeType.RPM
            || nt == NodeType.TX_OUT
            || nt == NodeType.CONST
            || nt == NodeType.REDSTONE_IN
            || nt == NodeType.PRIVATE_IN
            || nt == NodeType.BUS_IN
            || nt == NodeType.COMMENT
            || nt == NodeType.DEBUG_SIGNAL_GEN
            || nt == NodeType.DEBUG_PROBE);
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
