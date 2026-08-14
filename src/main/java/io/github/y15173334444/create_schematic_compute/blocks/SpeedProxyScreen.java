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

public class SpeedProxyScreen extends AbstractGraphScreen {

    public SpeedProxyScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".speed_proxy"), pos);
        setNodeFilter(nt -> nt == NodeType.SPEED_CTRL
            || nt == NodeType.CONST
            || nt == NodeType.REDSTONE_IN
            || nt == NodeType.PRIVATE_IN
            || nt == NodeType.BUS_IN
            || nt == NodeType.COMMENT
            || nt == NodeType.DEBUG_SIGNAL_GEN
            || nt == NodeType.DEBUG_PROBE);
    }

    @Override protected SpeedProxyBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(blockPos) instanceof SpeedProxyBlockEntity be) return be;
        }
        return null;
    }
    @Override protected boolean isBlockEntityValid() {
        return minecraft != null && minecraft.level != null
            && minecraft.level.getBlockEntity(blockPos) instanceof SpeedProxyBlockEntity;
    }

    @Override public NodeGraph getGraph() { SpeedProxyBlockEntity be = getBE(); return be != null ? be.graph : new NodeGraph(); }
    @Override public boolean isRunning() { SpeedProxyBlockEntity be = getBE(); return be != null && be.running; }
    @Override public Map<Integer, Boolean> getFlipflopStates() { SpeedProxyBlockEntity be = getBE(); return be != null ? be.runtimeState.flipflopStates : null; }
    @Override public EvalSnapshot getCachedEvalSnapshot() {
        SpeedProxyBlockEntity be = getBE();
        return be != null ? be.cachedEvalSnapshot : null;
    }

    @Override
    public void saveGraph() {
        try {
            SpeedProxyBlockEntity be = getBE();
            if(be==null||be.getLevel()==null) return;
            var tag = new CompoundTag();
            tag.put("graph", getGraph().save(be.getLevel().registryAccess()));
            var baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, baos);
            PacketDistributor.sendToServer(new BlueprintSavePacket(be.getBlockPos(), baos.toByteArray()));
            editor.saveFeedbackUntil = System.currentTimeMillis() + 1500;
        } catch(Exception e) { SchematicCompute.LOGGER.error("Save", e); }
    }

    @Override
    public void toggleRunning(boolean start) {
        SpeedProxyBlockEntity be = getBE();
        if(be != null) { be.running = start; PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); }
    }
}
