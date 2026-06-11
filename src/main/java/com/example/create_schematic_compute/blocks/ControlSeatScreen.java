package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.graph.NodeGraph;
import com.example.create_schematic_compute.graph.NodeType;
import com.example.create_schematic_compute.network.BlueprintSavePacket;
import com.example.create_schematic_compute.network.BlueprintTogglePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import java.io.ByteArrayOutputStream;

public class ControlSeatScreen extends AbstractContainerScreen<ControlSeatMenu> implements GraphEditor.Host {
    private final ControlSeatBlockEntity blockEntity;
    private final GraphEditor editor;

    /** 控制座椅允许的节点类型 */
    private static boolean isAllowedNode(NodeType nt) {
        return nt == NodeType.KEYBOARD
            || nt == NodeType.MOUSE_JOYSTICK
            || nt == NodeType.VIEW_ANGLE
            || nt == NodeType.MOUSE_BUTTON
            || nt == NodeType.GAMEPAD_JOYSTICK
            || nt == NodeType.GAMEPAD_BUTTON
            || nt == NodeType.WORLD_VIEW
            || nt == NodeType.ATTITUDE
            || nt == NodeType.POSE_CONVERT
            || nt == NodeType.SPLIT
            || nt == NodeType.REDSTONE_OUT
            || nt == NodeType.PRIVATE_OUT;
    }

    public ControlSeatScreen(ControlSeatMenu m, Inventory inv, Component t) {
        super(m, inv, t);
        this.blockEntity = m.blockEntity;
        this.imageWidth = 9999;
        this.editor = new GraphEditor(this, this);
        editor.setNodeFilter(ControlSeatScreen::isAllowedNode);
    }

    @Override public NodeGraph getGraph() { return blockEntity != null ? blockEntity.graph : new NodeGraph(); }
    @Override public boolean isRunning() { return blockEntity != null && blockEntity.running; }
    @Override public net.minecraft.client.gui.screens.Screen asScreen() { return this; }

    @Override
    public void saveGraph() {
        try {
            ControlSeatBlockEntity be = blockEntity;
            if(be==null&&menu.blockPos!=null&&minecraft!=null&&minecraft.level!=null)
                if(minecraft.level.getBlockEntity(menu.blockPos) instanceof ControlSeatBlockEntity found) be=found;
            if(be==null||be.getLevel()==null) return;
            var tag = new CompoundTag(); tag.put("graph", getGraph().save(be.getLevel().registryAccess()));
            var baos = new ByteArrayOutputStream(); NbtIo.writeCompressed(tag, baos);
            PacketDistributor.sendToServer(new BlueprintSavePacket(be.getBlockPos(), baos.toByteArray()));
            editor.saveFeedbackUntil = System.currentTimeMillis() + 1500;
        } catch(Exception e) { SchematicCompute.LOGGER.error("Save", e); }
    }

    @Override
    public void toggleRunning(boolean start) {
        if(blockEntity != null)
            PacketDistributor.sendToServer(new BlueprintTogglePacket(blockEntity.getBlockPos(), start));
    }

    @Override protected void renderBg(GuiGraphics g, float pt, int mx, int my) { editor.renderBg(g, mx, my); }
    @Override public boolean mouseClicked(double mx, double my, int btn) { return editor.mouseClicked(mx, my, btn) || super.mouseClicked(mx, my, btn); }
    @Override public boolean mouseReleased(double mx, double my, int btn) { editor.mouseReleased(mx, my, btn); return super.mouseReleased(mx, my, btn); }
    @Override public void mouseMoved(double mx, double my) { editor.mouseMoved(mx, my); }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) { return editor.mouseScrolled(mx, my, sx, sy); }
    @Override public boolean keyPressed(int key, int sc, int mod) {
        if(key==256){onClose();return true;}
        return editor.keyPressed(key, sc, mod) || super.keyPressed(key, sc, mod);
    }
    @Override public boolean keyReleased(int key, int sc, int mod) { return editor.keyReleased(key, sc, mod) || super.keyReleased(key, sc, mod); }
    @Override public boolean charTyped(char ch, int mod) { return editor.charTyped(ch, mod) || super.charTyped(ch, mod); }
}
