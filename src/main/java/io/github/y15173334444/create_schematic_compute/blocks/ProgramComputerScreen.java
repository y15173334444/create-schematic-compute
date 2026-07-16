package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.network.BlueprintSavePacket;
import io.github.y15173334444.create_schematic_compute.network.BlueprintTogglePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import java.io.ByteArrayOutputStream;

public class ProgramComputerScreen extends AbstractContainerScreen<ProgramComputerMenu> implements GraphEditor.Host {
    private final ProgramComputerBlockEntity blockEntity;
    private final GraphEditor editor;

    public ProgramComputerScreen(ProgramComputerMenu m, Inventory inv, Component t) {
        super(m, inv, t);
        this.blockEntity = m.blockEntity;
        this.imageWidth = 9999;
        this.editor = new GraphEditor(this, this);
        editor.setNodeFilter(nt -> nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.CONST
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.REDSTONE_IN
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.REDSTONE_OUT
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.PRIVATE_IN
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.PRIVATE_OUT
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_IN
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.BUS_OUT
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.DELAY
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.LATCH
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.T_FLIPFLOP
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.PULSE_EXTEND
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.LOOP
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.FUSE
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.BOOL
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.ACCUMULATOR
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.INTEGRATOR
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.GATE
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.SIN
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.COS
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.TAN
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.ASIN
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.ACOS
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.ATAN2
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.SINH
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.COSH
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.SQRT
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.LN
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.LOG
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.EXP
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.SEC
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.CSC
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.COT
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.ANGLE_UNWRAP
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.DIRECTION
            || nt == io.github.y15173334444.create_schematic_compute.graph.NodeType.COMMENT);
    }

    private ProgramComputerBlockEntity getBE() {
        if (blockEntity != null) return blockEntity;
        if (menu.blockPos != null && minecraft != null && minecraft.level != null) {
            if (minecraft.level.getBlockEntity(menu.blockPos) instanceof ProgramComputerBlockEntity be) return be;
        }
        return null;
    }
    @Override public NodeGraph getGraph() { ProgramComputerBlockEntity be = getBE(); return be != null ? be.graph : new NodeGraph(); }
    @Override public boolean isRunning() { ProgramComputerBlockEntity be = getBE(); return be != null && be.running; }
    @Override public java.util.Map<Integer, Boolean> getFlipflopStates() { ProgramComputerBlockEntity be = getBE(); return be != null ? be.runtimeState.flipflopStates : null; }
    @Override public net.minecraft.client.gui.screens.Screen asScreen() { return this; }

    @Override
    public void saveGraph() {
        try {
            ProgramComputerBlockEntity be = getBE();
            if(be==null||be.getLevel()==null) return;
            var tag = new CompoundTag(); tag.put("graph", getGraph().save(be.getLevel().registryAccess()));
            var baos = new ByteArrayOutputStream(); NbtIo.writeCompressed(tag, baos);
            PacketDistributor.sendToServer(new BlueprintSavePacket(be.getBlockPos(), baos.toByteArray()));
            editor.saveFeedbackUntil = System.currentTimeMillis() + 1500;
        } catch(Exception e) { SchematicCompute.LOGGER.error("Save", e); }
    }

    @Override
    public void toggleRunning(boolean start) {
        ProgramComputerBlockEntity be = getBE();
        if(be != null) { be.running = start; PacketDistributor.sendToServer(new BlueprintTogglePacket(be.getBlockPos(), start)); }
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
        if (key >= 32 && key <= 96) return true; // printable keys → charTyped
        return super.keyPressed(key, sc, mod);
    }
    @Override public boolean keyReleased(int key, int sc, int mod) { return editor.keyReleased(key, sc, mod) || super.keyReleased(key, sc, mod); }
    @Override public boolean charTyped(char ch, int mod) { return editor.charTyped(ch, mod) || super.charTyped(ch, mod); }

    // ── Multiplayer collaboration ──
    @Override public net.minecraft.core.BlockPos getBlockPos() { return menu.blockPos; }
    @Override public java.util.UUID getPlayerUUID() { return minecraft.player != null ? minecraft.player.getUUID() : java.util.UUID.randomUUID(); }
    @Override public GraphEditor getEditor() { return editor; }
    @Override public String getPlayerName() { return minecraft.player != null ? minecraft.player.getName().getString() : ""; }
    @Override public void sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp op) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new io.github.y15173334444.create_schematic_compute.network.GraphEditOpPacket(op));
    }
    @Override public void onRemoteOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp op) { editor.onRemoteOp(op); }
    @Override protected void init() {
        super.init();
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new io.github.y15173334444.create_schematic_compute.network.GraphJoinPacket(menu.blockPos));
    }
    @Override public void removed() {
        super.removed();
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new io.github.y15173334444.create_schematic_compute.network.GraphLeavePacket(menu.blockPos));
    }

}
