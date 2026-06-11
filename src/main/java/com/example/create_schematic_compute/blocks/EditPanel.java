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

    /** 手柄按键名 */
    private static final String[] GPAD_BTN_NAMES = {"A","B","X","Y","LB","RB","Back","Start","Guide","L3","R3","↑","↓","←","→"};
    public static String gamepadButtonLabel(int idx) {
        if (idx >= 0 && idx < GPAD_BTN_NAMES.length) return GPAD_BTN_NAMES[idx];
        return "Btn" + idx;
    }

    /** 计算编辑区高度 */
    public static int calcRenderHeight(GraphNode n, float zoom) {
        if (n == null) return 0;
        int h = 6;
        if (n.type.paramNames.length > 0 && n.type != NodeType.BOOL) {
            if (n.type == NodeType.KEYBOARD || n.type == NodeType.GAMEPAD_BUTTON) { h += 24; }
            else h += n.params.length * 18;
        }
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

        // KEYBOARD / GAMEPAD_BUTTON 节点：绑定UI
        if (node.type == NodeType.KEYBOARD || node.type == NodeType.GAMEPAD_BUTTON) {
            int idx = node.params.length > 0 ? (int)node.params[0] : 0;
            String label = node.type == NodeType.KEYBOARD ? keyIndexToLabel(idx) : gamepadButtonLabel(idx);
            int bx = px + 4, by = py + 4, bw = pw - 8, bh = 18;
            String hint = st.listeningForKey
                ? (node.type == NodeType.GAMEPAD_BUTTON ? "§e按下手柄按键..." : "§e按下按键...")
                : "§7[ " + label + " ] §7点击绑定";
            boolean hovering = mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;
            g.fill(bx, by, bx + bw, by + bh, st.listeningForKey ? 0xFF5A3A2A : (hovering ? 0xFF3A4A3A : 0xFF1A1814));
            g.renderOutline(bx, by, bw, bh, st.listeningForKey ? 0xFFFF8844 : NodeRenderer.CSB);
            g.drawString(Minecraft.getInstance().font, hint, bx + 6, by + 3,
                st.listeningForKey ? 0xFFFFEE88 : 0xFFCCCCCC, false);
            row++;
        } else
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

    // 按键索引定义
    public static final int
        K_A=0, K_B=1, K_C=2, K_D=3, K_E=4, K_F=5, K_G=6, K_H=7, K_I=8, K_J=9,
        K_K=10, K_L=11, K_M=12, K_N=13, K_O=14, K_P=15, K_Q=16, K_R=17, K_S=18,
        K_T=19, K_U=20, K_V=21, K_W=22, K_X=23, K_Y=24, K_Z=25,
        K_0=26, K_1=27, K_2=28, K_3=29, K_4=30, K_5=31, K_6=32, K_7=33, K_8=34, K_9=35,
        K_SPACE=36, K_LSHIFT=37, K_RSHIFT=38, K_LCTRL=39, K_RCTRL=40,
        K_LALT=41, K_RALT=42, K_ENTER=43, K_TAB=44, K_BACKSPACE=45,
        K_CAPS=46, K_MINUS=47, K_EQUALS=48, K_LBRACKET=49, K_RBRACKET=50,
        K_SEMICOLON=51, K_QUOTE=52, K_COMMA=53, K_PERIOD=54, K_SLASH=55,
        K_BACKSLASH=56, K_GRAVE=57, K_MAX=58;

    /** 按键索引 → 显示标签 */
    public static String keyIndexToLabel(int idx) {
        if (idx >= 0 && idx < 26) return "" + (char)('A' + idx);
        if (idx >= 26 && idx < 36) return "" + (char)('0' + idx - 26);
        return switch (idx) {
            case K_SPACE -> "Space"; case K_LSHIFT -> "LShift"; case K_RSHIFT -> "RShift";
            case K_LCTRL -> "LCtrl"; case K_RCTRL -> "RCtrl"; case K_LALT -> "LAlt"; case K_RALT -> "RAlt";
            case K_ENTER -> "Enter"; case K_TAB -> "Tab"; case K_BACKSPACE -> "Bksp";
            case K_CAPS -> "Caps"; case K_MINUS -> "-"; case K_EQUALS -> "=";
            case K_LBRACKET -> "["; case K_RBRACKET -> "]"; case K_SEMICOLON -> ";";
            case K_QUOTE -> "'"; case K_COMMA -> ","; case K_PERIOD -> "."; case K_SLASH -> "/";
            case K_BACKSLASH -> "\\"; case K_GRAVE -> "`";
            default -> "?";
        };
    }

    /** GLFW key → 按键索引 (-1=不支持) */
    public static int glfwKeyToIndex(int glfwKey) {
        if (glfwKey >= org.lwjgl.glfw.GLFW.GLFW_KEY_A && glfwKey <= org.lwjgl.glfw.GLFW.GLFW_KEY_Z)
            return glfwKey - org.lwjgl.glfw.GLFW.GLFW_KEY_A;
        if (glfwKey >= org.lwjgl.glfw.GLFW.GLFW_KEY_0 && glfwKey <= org.lwjgl.glfw.GLFW.GLFW_KEY_9)
            return 26 + (glfwKey - org.lwjgl.glfw.GLFW.GLFW_KEY_0);
        return switch (glfwKey) {
            case org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE -> K_SPACE;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL -> K_LCTRL;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL -> K_RCTRL;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT -> K_LALT;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_ALT -> K_RALT;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER -> K_ENTER;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_TAB -> K_TAB;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE -> K_BACKSPACE;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_CAPS_LOCK -> K_CAPS;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS -> K_MINUS;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_EQUAL -> K_EQUALS;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET -> K_LBRACKET;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET -> K_RBRACKET;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_SEMICOLON -> K_SEMICOLON;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_APOSTROPHE -> K_QUOTE;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_COMMA -> K_COMMA;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_PERIOD -> K_PERIOD;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_SLASH -> K_SLASH;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSLASH -> K_BACKSLASH;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_GRAVE_ACCENT -> K_GRAVE;
            default -> -1;
        };
    }

    /** 检测 KEYBOARD / GAMEPAD_BUTTON 绑定按钮点击 */
    public static boolean handleKeyboardClick(GraphNode node, GraphEditor.EditState st, int lmx, int lmy, int pw) {
        if (node.type != NodeType.KEYBOARD && node.type != NodeType.GAMEPAD_BUTTON) return false;
        int bx = 4, by = 4, bw = pw - 8, bh = 18;
        if (lmx >= bx && lmx <= bx + bw && lmy >= by && lmy <= by + bh) {
            st.listeningForKey = !st.listeningForKey;
            return true;
        }
        return false;
    }
}
