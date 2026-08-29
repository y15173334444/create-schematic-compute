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

public class ControlSeatScreen extends AbstractGraphScreen {

    /** 控制座椅允许的节点类型 */
    private static boolean isAllowedNode(NodeType nt) {
        return nt == NodeType.KEYBOARD
            || nt == NodeType.MOUSE_JOYSTICK
            || nt == NodeType.VIEW_ANGLE
            || nt == NodeType.MOUSE_BUTTON
            || nt == NodeType.GAMEPAD_JOYSTICK
            || nt == NodeType.GAMEPAD_BUTTON
            || nt == NodeType.GAMEPAD_TRIGGER
            || nt == NodeType.WORLD_VIEW
            || nt == NodeType.ATTITUDE
            || nt == NodeType.ACCELERATION
            || nt == NodeType.VELOCITY
            || nt == NodeType.POSITION
            || nt == NodeType.BUS_OUT
            || nt == NodeType.POSE_CONVERT
            || nt == NodeType.SPLIT
            || nt == NodeType.REDSTONE_OUT
            || nt == NodeType.PRIVATE_OUT
            || nt == NodeType.COMMENT
            || nt == NodeType.DEBUG_SIGNAL_GEN
            || nt == NodeType.DEBUG_PROBE;
    }

    public ControlSeatScreen(BlockPos pos) {
        super(Component.translatable("container." + SchematicCompute.MOD_ID + ".control_seat"), pos);
        setNodeFilter(ControlSeatScreen::isAllowedNode);
    }

    @Override protected ControlSeatBlockEntity getBE() {
        if (minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(blockPos) instanceof ControlSeatBlockEntity be) return be;
        }
        return null;
    }
    @Override protected boolean isBlockEntityValid() {
        return minecraft != null && minecraft.level != null
            && minecraft.level.getBlockEntity(blockPos) instanceof ControlSeatBlockEntity;
    }
    @Override public NodeGraph getGraph() { ControlSeatBlockEntity be = getBE(); return be != null ? be.getNodeGraph() : new NodeGraph(); }
    @Override public boolean isRunning() { ControlSeatBlockEntity be = getBE(); return be != null && be.isRunning(); }
    @Override public Map<Integer, Boolean> getFlipflopStates() { ControlSeatBlockEntity be = getBE(); return be != null ? be.getFlipflopStates() : null; }
    @Override public EvalSnapshot getCachedEvalSnapshot() {
        ControlSeatBlockEntity be = getBE();
        return be != null ? be.getCachedEvalSnapshot() : null;
    }

    @Override
    public void saveGraph() {
        try {
            ControlSeatBlockEntity be = getBE();
            if(be==null||be.getLevel()==null) return;
            var tag = new CompoundTag(); tag.put("graph", getGraph().save(be.getLevel().registryAccess()));
            var baos = new ByteArrayOutputStream(); NbtIo.writeCompressed(tag, baos);
            PacketDistributor.sendToServer(new BlueprintSavePacket(be.getBlockPos(), baos.toByteArray()));
            editor.saveFeedbackUntil = System.currentTimeMillis() + 1500;
        } catch(Exception e) { SchematicCompute.LOGGER.error("Save", e); }
    }

    @Override
    public void toggleRunning(boolean start) {
        ControlSeatBlockEntity be = getBE();
        if(be != null) { be.setRunning(start); PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); }
    }
}
