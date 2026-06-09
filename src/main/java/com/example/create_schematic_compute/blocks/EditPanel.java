package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.graph.GraphNode;
import com.example.create_schematic_compute.graph.NodeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;

/**
 * 编辑控件工具类 — 提供编辑区高度计算和内联渲染（静态方法）
 */
public class EditPanel {

    /** 计算编辑区高度 */
    public static int calcRenderHeight(GraphNode n, float zoom) {
        if (n == null) return 0;
        int h = 6;
        if (n.type.paramNames.length > 0 && n.type != NodeType.BOOL) h += n.params.length * 18;
        if (n.type == NodeType.BOOL && n.params.length > 0) h += 16;
        if (n.type == NodeType.REDSTONE_IN || n.type == NodeType.REDSTONE_OUT) h += 32;
        if (n.type == NodeType.PRIVATE_IN || n.type == NodeType.PRIVATE_OUT) h += 22;
        if (n.type == NodeType.FORMULA) h += 22;
        return h;
    }

    /** 在局部坐标中渲染编辑控件（由 drawNode 在 pose 内调用，自动随缩放） */
    public static void renderAt(GuiGraphics g, int px, int py, int pw, GraphNode node,
                                 com.example.create_schematic_compute.blocks.GraphEditor.EditState st,
                                 float zoom, int mx, int my) {
        if (node == null || st == null) return;
        int row = 0;
        for (int i = 0; i < st.fields.size(); i++) {
            var b = st.fields.get(i);
            String label = i < st.paramKeys.length ? st.paramKeys[i] + ":" :
                (node.type == NodeType.PRIVATE_IN || node.type == NodeType.PRIVATE_OUT ? "channel:" : "");
            g.drawString(Minecraft.getInstance().font, label, px + 4, py + 4 + row * 18, 0xFF888888, false);
            int lw = Minecraft.getInstance().font.width(label) + 6;
            b.setX(px + 4 + lw);
            b.setY(py + 4 + row * 18);
            b.setWidth(pw - lw - 8);
            b.render(g, mx, my, 0);
            row++;
        }
        if (node.type == NodeType.BOOL && node.params.length > 0) {
            boolean inverted = node.params[0] > 0.5f;
            int bx = px + 4, by = py + 4 + row * 18;
            int bw = pw - 8, bh = 16;
            g.fill(bx, by, bx + bw, by + bh, inverted ? 0xFF3A5A2A : 0xFF3A3428);
            g.renderOutline(bx, by, bw, bh, NodeRenderer.CSB);
            g.renderOutline(bx+1, by+1, bw-2, bh-2, 0xFF1A1814);
            g.drawString(Minecraft.getInstance().font, inverted ? "§a✔ Inverted" : "§7Not Inverted", bx+4, by+2, 0xFFFFFFFF, false);
            row++;
        }
        if (node.type == NodeType.REDSTONE_IN || node.type == NodeType.REDSTONE_OUT) {
            st.freqSlotX = px + 4; st.freqSlotY = py + 8 + row * 18;
            for (int i = 0; i < 2; i++) {
                int bx = (int)st.freqSlotX + i * 24;
                g.fill(bx, (int)st.freqSlotY, bx + 20, (int)st.freqSlotY + 20, 0xFF1A1814);
                g.renderOutline(bx, (int)st.freqSlotY, 20, 20, i == st.freqSlotSelected ? 0xFFFFAA44 : NodeRenderer.CSB);
                if (node.itemParams != null && i < node.itemParams.length && !node.itemParams[i].isEmpty())
                    g.renderItem(node.itemParams[i], bx + 2, (int)st.freqSlotY + 2);
            }
        }
    }
}
