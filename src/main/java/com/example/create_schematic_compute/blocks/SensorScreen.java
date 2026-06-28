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

public class SensorScreen extends AbstractContainerScreen<SensorMenu> implements GraphEditor.Host {
    private final SensorBlockEntity blockEntity;
    private final GraphEditor editor;
    private static boolean isAllowed(NodeType nt) {
        return nt == NodeType.ATTITUDE || nt == NodeType.FORWARD || nt == NodeType.ACCELERATION || nt == NodeType.VELOCITY || nt == NodeType.POSITION || nt == NodeType.BUS_OUT
            || nt == NodeType.REDSTONE_OUT || nt == NodeType.PRIVATE_OUT;
    }
    public SensorScreen(SensorMenu m, Inventory inv, Component t) {
        super(m, inv, t);
        this.blockEntity = m.blockEntity;
        this.imageWidth = 9999;
        this.editor = new GraphEditor(this, this);
        editor.setNodeFilter(SensorScreen::isAllowed);
    }
    private SensorBlockEntity getBE() {
        if (blockEntity != null) return blockEntity;
        if (menu.blockPos != null && minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(menu.blockPos) instanceof SensorBlockEntity be) return be;
        }
        return null;
    }
    @Override public NodeGraph getGraph() { SensorBlockEntity be = getBE(); return be != null ? be.graph : new NodeGraph(); }
    @Override public boolean isRunning() { SensorBlockEntity be = getBE(); return be != null && be.running; }
    @Override public java.util.Map<Integer, Boolean> getFlipflopStates() { SensorBlockEntity be = getBE(); return be != null ? be.runtimeState.flipflopStates : null; }
    @Override public net.minecraft.client.gui.screens.Screen asScreen() { return this; }
    @Override public void saveGraph() {
        try { SensorBlockEntity be = getBE();
            if(be==null||be.getLevel()==null) return;
            var tag = new CompoundTag(); tag.put("graph", getGraph().save(be.getLevel().registryAccess()));
            var baos = new ByteArrayOutputStream(); NbtIo.writeCompressed(tag, baos);
            PacketDistributor.sendToServer(new BlueprintSavePacket(be.getBlockPos(), baos.toByteArray()));
            editor.saveFeedbackUntil = System.currentTimeMillis() + 1500;
        } catch(Exception e) { SchematicCompute.LOGGER.error("Save", e); }
    }
    @Override public void toggleRunning(boolean start) { SensorBlockEntity be = getBE(); if(be != null) { be.running = start; PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); } }
    @Override protected void renderBg(GuiGraphics g, float pt, int mx, int my) { editor.renderBg(g, mx, my); }
    @Override public boolean mouseClicked(double mx, double my, int btn) { return editor.mouseClicked(mx, my, btn) || super.mouseClicked(mx, my, btn); }
    @Override public boolean mouseReleased(double mx, double my, int btn) { editor.mouseReleased(mx, my, btn); return super.mouseReleased(mx, my, btn); }
    @Override public void mouseMoved(double mx, double my) { editor.mouseMoved(mx, my); }
    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return editor.mouseDragged(mx, my, btn, dx, dy) || super.mouseDragged(mx, my, btn, dx, dy); }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) { return editor.mouseScrolled(mx, my, sx, sy); }
    @Override public boolean keyPressed(int key, int sc, int mod) { if(key==256){onClose();return true;} if (editor.keyPressed(key, sc, mod)) return true; if (key >= 32 && key <= 96) return true; return super.keyPressed(key, sc, mod); }
    @Override public boolean keyReleased(int key, int sc, int mod) { return editor.keyReleased(key, sc, mod) || super.keyReleased(key, sc, mod); }
    @Override public boolean charTyped(char ch, int mod) { return editor.charTyped(ch, mod) || super.charTyped(ch, mod); }
}
