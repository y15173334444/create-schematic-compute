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

public class BlueprintScreen extends AbstractGraphScreen {

    public BlueprintScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".blueprint"), pos);
        setNodeFilter(nt -> nt != NodeType.SPEED_CTRL
            && nt != NodeType.DELAY
            && nt != NodeType.LATCH
            && nt != NodeType.T_FLIPFLOP
            && nt != NodeType.PULSE_EXTEND
            && nt != NodeType.LOOP
            && nt != NodeType.FUSE
            && nt != NodeType.KEYBOARD
            && nt != NodeType.MOUSE_JOYSTICK
            && nt != NodeType.MOUSE_BUTTON
            && nt != NodeType.GAMEPAD_JOYSTICK
            && nt != NodeType.GAMEPAD_BUTTON
            && nt != NodeType.GAMEPAD_TRIGGER
            && nt != NodeType.VIEW_ANGLE
            && nt != NodeType.WORLD_VIEW
            && nt != NodeType.ATTITUDE
            && nt != NodeType.FORWARD
            && nt != NodeType.ACCELERATION
            && nt != NodeType.VELOCITY
            && nt != NodeType.POSITION
            && nt != NodeType.TARGET_OUT
            && nt != NodeType.TEXT
            && nt != NodeType.DATA
            && nt != NodeType.IMAGE
            && nt != NodeType.IMAGE_SEQUENCE
            && !nt.isMonitorOnly()
            && nt != NodeType.ENCAP_INPUT
            && nt != NodeType.ENCAP_OUTPUT);
    }

    @Override protected BlueprintBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(blockPos) instanceof BlueprintBlockEntity be) return be;
        }
        return null;
    }
    @Override protected boolean isBlockEntityValid() {
        return minecraft != null && minecraft.level != null
            && minecraft.level.getBlockEntity(blockPos) instanceof BlueprintBlockEntity;
    }

    @Override public NodeGraph getGraph() { BlueprintBlockEntity be = getBE(); return be != null ? be.getNodeGraph() : new NodeGraph(); }
    @Override public boolean isRunning() { BlueprintBlockEntity be = getBE(); return be != null && be.isRunning(); }
    @Override public Map<Integer, Boolean> getFlipflopStates() { BlueprintBlockEntity be = getBE(); return be != null ? be.getFlipflopStates() : null; }
    @Override public EvalSnapshot getCachedEvalSnapshot() {
        BlueprintBlockEntity be = getBE();
        return be != null ? be.getCachedEvalSnapshot() : null;
    }

    @Override
    public void saveGraph() {
        try {
            BlueprintBlockEntity be = getBE();
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
        BlueprintBlockEntity be = getBE();
        if(be != null) { be.setRunning(start); PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); }
    }
}
