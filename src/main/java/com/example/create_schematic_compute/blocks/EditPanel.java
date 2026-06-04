package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.graph.GraphNode;
import com.example.create_schematic_compute.graph.NodeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 编辑面板 — 节点参数编辑和频率槽位选择
 */
public class EditPanel {
    private final net.minecraft.client.gui.screens.Screen screen;
    private GraphNode selectedNode;
    private List<EditBox> editFields = new ArrayList<>();
    private String[] editParamKeys;
    public int freqSlotSelected = 0;

    public EditPanel(net.minecraft.client.gui.screens.Screen screen) {
        this.screen = screen;
    }

    public void open(GraphNode node) {
        selectedNode = node;
        editFields.clear();
        editParamKeys = node.type.paramNames.clone();
        for(int i=0; i<node.params.length; i++) {
            int idx = i;
            var b = new EditBox(Minecraft.getInstance().font, 0, 0, 60, 16, Component.literal(""));
            b.setMaxLength(12);
            b.setValue(String.format("%.3f", node.params[i]));
            float oldVal = node.params[idx];
            b.setResponder(s -> {
                try { node.params[idx] = Float.parseFloat(s.trim()); }
                catch(Exception ex) { node.params[idx] = oldVal; }
            });
            editFields.add(b);
        }
        if((node.type==NodeType.REDSTONE_IN||node.type==NodeType.REDSTONE_OUT) && node.itemParams.length<2)
            node.itemParams = new ItemStack[]{ItemStack.EMPTY, ItemStack.EMPTY};
        // 信号名编辑框
        if (node.type == NodeType.PRIVATE_IN || node.type == NodeType.PRIVATE_OUT) {
            var sb = new EditBox(Minecraft.getInstance().font, 0, 0, 120, 16, Component.literal(""));
            sb.setMaxLength(32);
            sb.setValue(node.signalName);
            int idx = editFields.size();
            sb.setResponder(s -> node.signalName = s);
            editFields.add(sb);
        }
    }

    public void close() {
        selectedNode = null;
        editFields.clear();
    }

    public boolean isOpen() { return selectedNode != null; }

    public GraphNode getNode() { return selectedNode; }

    /** 计算面板高度（含频率槽位区域） */
    private int panelHeight() {
        int h = 28 + editFields.size() * 18 + 10;
        if (selectedNode != null && (selectedNode.type == NodeType.REDSTONE_IN || selectedNode.type == NodeType.REDSTONE_OUT))
            h += 68; // 频率槽位 + 快捷栏
        return h;
    }

    public void render(GuiGraphics g, int width, int height, int mouseX, int mouseY) {
        if(selectedNode == null) return;
        int pw=Math.min(260, width-8), px=width-pw-4, py=35, ph=panelHeight();
        g.fill(px,py,px+pw,py+ph,0xFF2A2A30);
        g.renderOutline(px,py,pw,ph,0xFF666666);
        g.drawString(Minecraft.getInstance().font, "§l"+I18n.get(selectedNode.type.getTitle()), px+4, py+4, 0xFFFFFFFF, false);
        for(int i=0; i<editFields.size(); i++) {
            var b = editFields.get(i);
            b.setX(px+50);
            b.setY(py+18+i*18);
            b.setWidth(pw - 65);
            b.render(g, mouseX, mouseY, 0);
            String label = i < editParamKeys.length ? editParamKeys[i]+":" :
                (selectedNode.type==NodeType.PRIVATE_IN||selectedNode.type==NodeType.PRIVATE_OUT ? "channel:" : "");
            g.drawString(Minecraft.getInstance().font, label, px+10, py+18+i*18-10, 0xFF888888, false);
        }
        // 频率槽位
        if(selectedNode.type == NodeType.REDSTONE_IN || selectedNode.type == NodeType.REDSTONE_OUT)
            renderFreqSlots(g, px, py, mouseX, mouseY);
    }

    private void renderFreqSlots(GuiGraphics g, int px, int py, int mx, int my) {
        int sx = px+80, sy = py+4;
        for(int i=0; i<2; i++) {
            int bx = sx+i*24, by = sy;
            g.fill(bx, by, bx+20, by+20, 0xFF1A1A1A);
            g.renderOutline(bx, by, 20, 20, i == freqSlotSelected ? 0xFFFFAA44 : 0xFF666666);
            if(selectedNode.itemParams != null && i < selectedNode.itemParams.length && !selectedNode.itemParams[i].isEmpty())
                g.renderItem(selectedNode.itemParams[i], bx+2, by+2);
            else
                g.drawString(Minecraft.getInstance().font, i==0 ? "#1" : "#2", bx+5, by+5, 0xFF888888, false);
        }
        // 快捷栏
        int hy = sy+24;
        g.drawString(Minecraft.getInstance().font, "§lHotbar:", px+4, hy, 0xFFFFFFFF, false);
        var mc = Minecraft.getInstance();
        if(mc.player != null) {
            for(int i=0; i<9; i++) {
                int hx = px+4 + i*20;
                g.fill(hx, hy+12, hx+18, hy+30, 0xFF1A1A1A);
                g.renderOutline(hx, hy+12, 18, 18, 0xFF555555);
                var st = mc.player.getInventory().items.get(i);
                if(!st.isEmpty()) g.renderItem(st, hx+1, hy+13);
            }
        }
    }

    public boolean mouseClicked(double mx, double my, int btn) {
        if(btn!=0 || selectedNode==null) return false;
        // 点击编辑面板外部关闭
        int pw = Math.min(260, screen.width-8), px = screen.width-pw-4;
        int ph = panelHeight();
        if(mx<px || mx>px+pw || my<35 || my>35+ph) {
            close();
            return true;
        }
        // 编辑框
        for(var b : editFields) {
            b.mouseClicked(mx, my, btn);
            b.setFocused(b.isMouseOver(mx, my));
        }
        return true;
    }

    public boolean handleFreqSlotClick(double mx, double my) {
        if(selectedNode==null) return false;
        if(selectedNode.type != NodeType.REDSTONE_IN && selectedNode.type != NodeType.REDSTONE_OUT) return false;
        // 面板在右上方：pw=min(260, w-8), px=w-pw-4, py=35
        int pw = Math.min(260, screen.width-8), px = screen.width-pw-4, py = 35;
        int sx = px+80, sy = py+4;
        for(int i=0; i<2; i++) {
            int bx = sx + i*24;
            if(mx>=bx && mx<=bx+20 && my>=sy && my<=sy+20) { freqSlotSelected = i; return true; }
        }
        return false;
    }

    public boolean handleHotbarClick(double mx, double my) {
        if(selectedNode==null) return false;
        if(selectedNode.type != NodeType.REDSTONE_IN && selectedNode.type != NodeType.REDSTONE_OUT) return false;
        int pw = Math.min(260, screen.width-8), px = screen.width-pw-4, py = 35;
        int hy = py + 4 + 24 + 12;
        if(my>=hy && my<=hy+18) {
            var mc = Minecraft.getInstance();
            if(mc.player == null) return false;
            int slot = (int)((mx - (px + 4)) / 20);
            if(slot >= 0 && slot < 9) {
                var st = mc.player.getInventory().items.get(slot).copy();
                if(!st.isEmpty() && selectedNode.itemParams != null && freqSlotSelected < selectedNode.itemParams.length) {
                    selectedNode.itemParams[freqSlotSelected] = st;
                    selectedNode.itemParams[freqSlotSelected].setCount(1);
                } else if(selectedNode.itemParams != null && freqSlotSelected < selectedNode.itemParams.length) {
                    selectedNode.itemParams[freqSlotSelected] = ItemStack.EMPTY;
                }
                return true;
            }
        }
        return false;
    }

    public boolean keyPressed(int key, int sc, int mod) {
        for(var b : editFields) {
            if(b.isFocused()) {
                if(key==257 || key==335) { b.setFocused(false); return true; }
                return b.keyPressed(key, sc, mod);
            }
        }
        return false;
    }

    public boolean charTyped(char ch, int mod) {
        for(var b : editFields) if(b.isFocused()) return b.charTyped(ch, mod);
        return false;
    }
}
