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

    private float boolBtnX, boolBtnY, boolBtnW, boolBtnH;
    private float freqSlotX, freqSlotY, hotbarY;

    public EditPanel(net.minecraft.client.gui.screens.Screen screen) {
        this.screen = screen;
    }

    public void open(GraphNode node) {
        selectedNode = node;
        editFields.clear();
        editParamKeys = node.type.paramNames.clone();
        for(int i=0; i<node.params.length; i++) {
            if (node.type == NodeType.BOOL) continue;
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
        if (node.type == NodeType.PRIVATE_IN || node.type == NodeType.PRIVATE_OUT) {
            var sb = new EditBox(Minecraft.getInstance().font, 0, 0, 120, 16, Component.literal(""));
            sb.setMaxLength(32);
            sb.setValue(node.signalName);
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

    /** 内联渲染 — 在节点展开区域内绘制编辑控件 */
    public void renderInline(GuiGraphics g, float ex, float ey, float ew, float eh, float zoom, int mx, int my) {
        if (selectedNode == null) return;
        int px = (int)ex, py = (int)ey, pw = (int)ew, ph = (int)eh;
        int rowH = Math.max(12, (int)(18 * zoom));
        int row = 0;
        for (int i = 0; i < editFields.size(); i++) {
            var b = editFields.get(i);
            String label = i < editParamKeys.length ? editParamKeys[i] + ":" :
                (selectedNode.type==NodeType.PRIVATE_IN||selectedNode.type==NodeType.PRIVATE_OUT ? "channel:" : "");
            g.drawString(Minecraft.getInstance().font, label, px + 4, py + 4 + row * rowH, 0xFF888888, false);
            int lw = Minecraft.getInstance().font.width(label) + 6;
            b.setX(px + 4 + lw);
            b.setY(py + 4 + row * rowH);
            b.setWidth(pw - lw - 8);
            b.render(g, mx, my, 0);
            row++;
        }
        boolBtnX = boolBtnY = boolBtnW = boolBtnH = 0;
        if (selectedNode.type == NodeType.BOOL && selectedNode.params.length > 0) {
            boolean inverted = selectedNode.params[0] > 0.5f;
            int bx = px + 4, by = py + 4 + row * rowH;
            int bw = pw - 8, bh = Math.max(12, (int)(16 * zoom));
            g.fill(bx, by, bx + bw, by + bh, inverted ? 0xFF3A5A2A : 0xFF3A3428);
            g.renderOutline(bx, by, bw, bh, 0xFF8B7533);
            g.renderOutline(bx+1, by+1, bw-2, bh-2, 0xFF1A1814);
            g.drawString(Minecraft.getInstance().font, inverted ? "§a✔ Inverted" : "§7Not Inverted", bx+4, by+2, 0xFFFFFFFF, false);
            boolBtnX = bx; boolBtnY = by; boolBtnW = bw; boolBtnH = bh;
            row++;
        }
        if (selectedNode.type == NodeType.REDSTONE_IN || selectedNode.type == NodeType.REDSTONE_OUT)
            renderInlineFreqSlots(g, px, py + 4 + row * rowH, pw, zoom, mx, my);
    }

    private void renderInlineFreqSlots(GuiGraphics g, int px, int py, int pw, float zoom, int mx, int my) {
        int slot = (int)(20 * zoom), gap = (int)(4 * zoom), step = slot + gap;
        freqSlotX = px + 4; freqSlotY = py;
        for (int i = 0; i < 2; i++) {
            int bx = (int)freqSlotX + i * step, by = (int)freqSlotY;
            g.fill(bx, by, bx + slot, by + slot, 0xFF1A1814);
            g.renderOutline(bx, by, slot, slot, i == freqSlotSelected ? 0xFFFFAA44 : 0xFF8B7533);
            if (selectedNode.itemParams != null && i < selectedNode.itemParams.length && !selectedNode.itemParams[i].isEmpty())
                g.renderItem(selectedNode.itemParams[i], bx + 2, by + 2);
            else
                g.drawString(Minecraft.getInstance().font, i == 0 ? "§8#1" : "§8#2", bx + 5, by + 5, 0xFF888888, false);
        }
        hotbarY = py + slot + gap;
        g.drawString(Minecraft.getInstance().font, "§6§lHotbar:", px + 4, (int)hotbarY, 0xFFFFFFFF, false);
        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            int hSlot = (int)(18 * zoom), hGap = (int)(2 * zoom);
            for (int i = 0; i < 9; i++) {
                int hx = px + 8 + i * (hSlot + hGap);
                g.fill(hx, (int)hotbarY + 12, hx + hSlot, (int)hotbarY + 12 + hSlot, 0xFF1A1814);
                g.renderOutline(hx, (int)hotbarY + 12, hSlot, hSlot, 0xFF5A4D3A);
                var st = mc.player.getInventory().items.get(i);
                if (!st.isEmpty()) g.renderItem(st, hx + 1, (int)hotbarY + 13);
            }
        }
    }


    /** 点击检测（内联版）— 接收编辑区屏幕坐标 */
    public boolean mouseClickedInline(double mx, double my, int btn, float ex, float ey, float ew, float eh) {
        if (btn != 0 || selectedNode == null) return false;
        // 点击不在编辑区内则不消费
        if (mx < ex || mx > ex + ew || my < ey || my > ey + eh) return false;

        // BOOL 按钮
        if (boolBtnH > 0 && mx >= boolBtnX && mx <= boolBtnX + boolBtnW && my >= boolBtnY && my <= boolBtnY + boolBtnH) {
            selectedNode.params[0] = selectedNode.params[0] > 0.5f ? 0 : 1;
            return true;
        }
        // 编辑框
        for (var b : editFields) {
            b.mouseClicked(mx, my, 0);
            b.setFocused(b.isMouseOver(mx, my));
        }
        return true;
    }
    /** 旧版接口保留（不再使用，由 GraphEditor 直接调用 inline 版） */
    public boolean mouseClicked(double mx, double my, int btn) { return false; }

    public boolean handleFreqSlotClick(double mx, double my) {
        if (selectedNode == null || selectedNode.type != NodeType.REDSTONE_IN && selectedNode.type != NodeType.REDSTONE_OUT) return false;
        for (int i = 0; i < 2; i++) {
            int bx = (int)freqSlotX + i * 24;
            if (mx >= bx && mx <= bx + 20 && my >= freqSlotY && my <= freqSlotY + 20) { freqSlotSelected = i; return true; }
        }
        return false;
    }

    public boolean handleHotbarClick(double mx, double my) {
        if (selectedNode == null || selectedNode.type != NodeType.REDSTONE_IN && selectedNode.type != NodeType.REDSTONE_OUT) return false;
        if (my >= hotbarY + 12 && my <= hotbarY + 30) {
            var mc = Minecraft.getInstance();
            if (mc.player == null) return false;
            int slot = (int)((mx - ((int)freqSlotX)) / 20);
            if (slot >= 0 && slot < 9) {
                var st = mc.player.getInventory().items.get(slot).copy();
                if (!st.isEmpty() && selectedNode.itemParams != null && freqSlotSelected < selectedNode.itemParams.length) {
                    selectedNode.itemParams[freqSlotSelected] = st;
                    selectedNode.itemParams[freqSlotSelected].setCount(1);
                } else if (selectedNode.itemParams != null && freqSlotSelected < selectedNode.itemParams.length) {
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
