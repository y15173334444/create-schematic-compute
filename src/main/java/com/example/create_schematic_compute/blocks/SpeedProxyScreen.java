package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.graph.NodeGraph;
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

public class SpeedProxyScreen extends AbstractContainerScreen<SpeedProxyMenu> implements GraphEditor.Host {
    private final SpeedProxyBlockEntity blockEntity;
    private final GraphEditor editor;

    public SpeedProxyScreen(SpeedProxyMenu m, Inventory inv, Component t) {
        super(m, inv, t);
        this.blockEntity = m.blockEntity;
        this.imageWidth = 9999;
        this.editor = new GraphEditor(this, this);
        editor.setNodeFilter(nt -> nt == com.example.create_schematic_compute.graph.NodeType.SPEED_CTRL
            || nt == com.example.create_schematic_compute.graph.NodeType.PRIVATE_IN
            || nt == com.example.create_schematic_compute.graph.NodeType.BUS_IN);
    }

    private SpeedProxyBlockEntity getBE() {
        if (blockEntity != null) return blockEntity;
        if (menu.blockPos != null && minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(menu.blockPos) instanceof SpeedProxyBlockEntity be) return be;
        }
        return null;
    }
    @Override public NodeGraph getGraph() { SpeedProxyBlockEntity be = getBE(); return be != null ? be.graph : new NodeGraph(); }
    @Override public boolean isRunning() { SpeedProxyBlockEntity be = getBE(); return be != null && be.running; }
    @Override public java.util.Map<Integer, Boolean> getFlipflopStates() { SpeedProxyBlockEntity be = getBE(); return be != null ? be.runtimeState.flipflopStates : null; }
    @Override public net.minecraft.client.gui.screens.Screen asScreen() { return this; }

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
        if(be != null)
            PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start));
    }

    @Override protected void renderBg(GuiGraphics g, float pt, int mx, int my) { editor.renderBg(g, mx, my); }

    @Override public boolean mouseClicked(double mx, double my, int btn) { return editor.mouseClicked(mx, my, btn) || super.mouseClicked(mx, my, btn); }
    @Override public boolean mouseReleased(double mx, double my, int btn) { editor.mouseReleased(mx, my, btn); return super.mouseReleased(mx, my, btn); }
    @Override public void mouseMoved(double mx, double my) { editor.mouseMoved(mx, my); }
    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return editor.mouseDragged(mx, my, btn, dx, dy) || super.mouseDragged(mx, my, btn, dx, dy); }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) { return editor.mouseScrolled(mx, my, sx, sy); }
    @Override public boolean keyPressed(int key, int sc, int mod) {
        if(key==256){onClose();return true;}
        if (editor.keyPressed(key, sc, mod)) return true;
        if (key >= 32 && key <= 96) return true;
        return super.keyPressed(key, sc, mod);
    }
    @Override public boolean keyReleased(int key, int sc, int mod) { return editor.keyReleased(key, sc, mod) || super.keyReleased(key, sc, mod); }
    @Override public boolean charTyped(char ch, int mod) { return editor.charTyped(ch, mod) || super.charTyped(ch, mod); }
}
