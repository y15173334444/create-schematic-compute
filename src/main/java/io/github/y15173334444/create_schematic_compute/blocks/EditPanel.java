package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import java.util.Map;

/**
 * 编辑控件工具类 — 提供编辑区高度计算和内联渲染（静态方法）
 */
public class EditPanel {

    /** 手柄按键名 (i18n keys) */
    private static final String[] GPAD_BTN_KEYS = {"a","b","x","y","lb","rb","back","start","guide","l3","r3","up","down","left","right"};
    public static String gamepadButtonLabel(int idx) {
        if (idx >= 0 && idx < GPAD_BTN_KEYS.length)
            return I18n.get("gamepad.create_schematic_compute." + GPAD_BTN_KEYS[idx]);
        return "Btn" + idx;
    }

    /** 计算编辑区高度 */
    public static int calcRenderHeight(GraphNode n, float zoom) {
        return calcRenderHeight(n, zoom, null);
    }
    /** 计算编辑区高度（可传入 EditState 以根据连线状态动态调整） */
    public static int calcRenderHeight(GraphNode n, float zoom, io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.EditState st) {
        if (n == null) return 0;
        int h = 6;
        if (n.type.paramNames.length > 0 && n.type != NodeType.BOOL && n.type != NodeType.GATE && n.type != NodeType.T_FLIPFLOP
            && n.type != NodeType.LATCH && n.type != NodeType.IMAGE && n.type != NodeType.IMAGE_SEQUENCE) {
            if (n.type == NodeType.KEYBOARD || n.type == NodeType.GAMEPAD_BUTTON) {
                h += 24;
            } else if (n.type == NodeType.ACCUMULATOR || n.type == NodeType.INTEGRATOR) {
                h += (st != null ? st.fields.size() : n.params.length) * 18;
            } else h += n.params.length * 18;
        }
        if (n.type == NodeType.BOOL && n.params.length > 0) h += 16;
        if ((n.type == NodeType.GATE || n.type == NodeType.T_FLIPFLOP || n.type == NodeType.LATCH) && n.params.length > 1) h += 32; // 初始按钮 + 当前只读
        if (n.type == NodeType.REDSTONE_IN || n.type == NodeType.REDSTONE_OUT) h += 32;
        if (n.type == NodeType.PRIVATE_IN || n.type == NodeType.PRIVATE_OUT) h += 22;
        if (n.type == NodeType.BUS_IN || n.type == NodeType.BUS_OUT) {
            int bands = n.signalBands != null ? n.signalBands.size() : 0;
            h += 22 + bands * 18 + 20;
        }
        if (n.type == NodeType.COMMENT) {
            h += Math.round(n.commentHeight) - 12;
        }
        if (n.type == NodeType.FORMULA) {
            // Height based on visual lines (word-wrap aware)
            if (st != null && !st.fields.isEmpty()
                && st.fields.get(0) instanceof io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox mle) {
                h += 22 + mle.getContentHeight() + 12;
            } else {
                int lineCount = n.formula.isEmpty() ? 1 : Math.max(1, n.formula.split("\n", -1).length);
                h += 22 + Math.max(1, Math.min(lineCount, 32)) * 12 + 12;
            }
        }
        if (n.type == NodeType.TEXT) h += 22;
        if (n.type == NodeType.IMAGE || n.type == NodeType.IMAGE_SEQUENCE) h += 54 + 32; // 3 text fields + 2 toggles
        if (n.type == NodeType.TEXT || n.type == NodeType.DATA) h += 22;
        if (n.type == NodeType.ENCAP_INPUT || n.type == NodeType.ENCAP_OUTPUT) h += 22;
        return h;
    }

    /** 在局部坐标中渲染编辑控件（由 drawNode 在 pose 内调用，自动随缩放） */
    public static void renderAt(GuiGraphics g, int px, int py, int pw, GraphNode node,
                                 io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.EditState st,
                                 float zoom, int mx, int my, Map<Integer, Boolean> flipflopStates) {
        if (node == null || st == null) return;
        var font = Minecraft.getInstance().font;
        int row = 0;

        // KEYBOARD / GAMEPAD_BUTTON 节点：绑定UI
        if (node.type == NodeType.KEYBOARD || node.type == NodeType.GAMEPAD_BUTTON) {
            int idx = node.params.length > 0 ? (int)node.params[0] : 0;
            String label = node.type == NodeType.KEYBOARD ? keyIndexToLabel(idx) : gamepadButtonLabel(idx);
            int bx = px + 4, by = py + 4, bw = pw - 8, bh = 18;
            String hint = st.listeningForKey
                ? "§e" + I18n.get(node.type == NodeType.GAMEPAD_BUTTON ? "gui.create_schematic_compute.edit.press_gamepad" : "gui.create_schematic_compute.edit.press_key")
                : "§7" + java.text.MessageFormat.format(I18n.get("gui.create_schematic_compute.edit.click_bind"), label);
            boolean hovering = mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;
            g.fill(bx, by, bx + bw, by + bh, st.listeningForKey ? 0xFF5A3A2A : (hovering ? 0xFF3A4A3A : 0xFF1A1814));
            g.renderOutline(bx, by, bw, bh, st.listeningForKey ? 0xFFFF8844 : NodeRenderer.CSB());
            g.drawString(Minecraft.getInstance().font, hint, bx + 6, by + 3,
                st.listeningForKey ? 0xFFFFEE88 : 0xFFCCCCCC, false);
            row++;
        } else if (node.type == NodeType.BUS_IN || node.type == NodeType.BUS_OUT) {
        // ── 总线编辑区 ──
        // 总线名行
        String busLabel = I18n.get("gui.create_schematic_compute.edit.bus_name") + ":";
        int busLabelW = Minecraft.getInstance().font.width(busLabel);
        g.drawString(Minecraft.getInstance().font, busLabel, px + 4, py + 4, 0xFF888888, false);
        if (!st.fields.isEmpty()) {
            var busBox = st.fields.get(0);
            int bx = px + 8 + busLabelW, by = py + 4, bw = Math.max(40, pw - busLabelW - 18);
            busBox.setX(bx); busBox.setY(by); busBox.setWidth(bw);
            manualEditBox(g, busBox, bx, by, bw, 16);
        }
        row++;
        // 频段行
        int pinR = NodeRenderer.PR;
        boolean isBusIn = node.type == NodeType.BUS_IN;
        int bandCount = st.fields.size() - 1;
        if (bandCount > 0) st.bandPinY = new float[bandCount];
        for (int bi = 1; bi < st.fields.size(); bi++) {
            // 引脚圆点（BUS_IN 输出在右，BUS_OUT 输入在左）
            int pinIdx = st.fieldParamIndices.size() > bi ? st.fieldParamIndices.get(bi) : (bi - 1);
            boolean pinConnected;
            int pinX;
            if (isBusIn) {
                pinX = px + pw - 12;
                pinConnected = st.graph != null && hasOutputConnection(st.graph, node.id, pinIdx);
            } else {
                pinX = px + 6 + pinR;
                pinConnected = st.graph != null && st.graph.hasInputConnection(node.id, pinIdx);
            }
            int pinY = py + 4 + row * 18 + 8;
            if (st.bandPinY != null && bi - 1 < st.bandPinY.length) st.bandPinY[bi - 1] = pinY;
            g.fill(pinX - pinR - 1, pinY - pinR - 1, pinX + pinR + 1, pinY + pinR + 1, NodeRenderer.CPIB());
            g.fill(pinX - pinR, pinY - pinR, pinX + pinR, pinY + pinR, pinConnected ? 0xFF666644 : isBusIn ? NodeRenderer.CPO() : NodeRenderer.CPI());
            // 频段名（BUS_IN 只读文本，BUS_OUT EditBox 可编辑）
            if (isBusIn) {
                String bandName = st.fields.get(bi).getValue();
                int tx = px + pw - 12 - Minecraft.getInstance().font.width(bandName) - 6;
                g.drawString(Minecraft.getInstance().font, bandName, tx, py + 4 + row * 18 + 2, 0xFFCCCCCC, false);
            } else {
                var bandBox = st.fields.get(bi);
                int bx = px + 22, by = py + 4 + row * 18, bw = 80;
                bandBox.setX(bx); bandBox.setY(by); bandBox.setWidth(bw);
                manualEditBox(g, bandBox, bx, by, bw, 16);
            }
            row++;
        }

        // +/- 按钮（仅 BUS_OUT 可管理频段，BUS_IN 被动同步）
        if (!isBusIn) {
            int btnY = py + 4 + row * 18;
            int addBW = 20, rmBW = 20;
            g.fill(px + 4, btnY, px + 4 + addBW, btnY + 14, 0xFF2A4A2A);
            g.renderOutline(px + 4, btnY, addBW, 14, NodeRenderer.CSB());
            g.drawString(Minecraft.getInstance().font, "+", px + 11, btnY + 2, 0xFF88FF88, false);
            g.fill(px + 4 + addBW + 4, btnY, px + 4 + addBW + 4 + rmBW, btnY + 14, 0xFF4A2A2A);
            g.renderOutline(px + 4 + addBW + 4, btnY, rmBW, 14, NodeRenderer.CSB());
            g.drawString(Minecraft.getInstance().font, "-", px + 4 + addBW + 11, btnY + 2, 0xFFFF8888, false);
            st.bandAddBtnX = px + 4; st.bandAddBtnY = btnY;
            st.bandAddBtnW = addBW; st.bandAddBtnH = 14;
            st.bandRemoveBtnX = px + 4 + addBW + 4; st.bandRemoveBtnY = btnY;
            st.bandRemoveBtnW = rmBW; st.bandRemoveBtnH = 14;
        } else {
            st.bandAddBtnW = 0; // 隐藏 +/-
        }
        } else if (node.type == NodeType.COMMENT) {
            if (!st.fields.isEmpty() && st.fields.get(0) instanceof io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox mle) {
                mle.setX(px + 6); mle.setY(py + 4);
                mle.setWidth(Math.max(40, pw - 16)); // 6px left + 10px scrollbar right
                mle.setHeight(Math.max(18, Math.round(node.commentHeight) - 12));
                mle.render(g, mx, my, 0);
            }
        } else if (node.type == NodeType.FORMULA) {
        // ── FORMULA 多行脚本编辑区（MultiLineEditBox） ──
        // 摘要信息行
        String summary = java.text.MessageFormat.format(
            I18n.get("gui.create_schematic_compute.formula_summary"),
            node.dynamicInputCount, node.dynamicOutputCount);
        g.drawString(Minecraft.getInstance().font, "§7" + summary, px + 4, py + 4, 0xFF888888, false);
        row++; // row 0 = summary
        // MultiLineEditBox — single field with word-wrap support
        if (!st.fields.isEmpty() && st.fields.get(0) instanceof io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox mle) {
            int editBoxY = py + 4 + row * 18;
            int contentH = mle.getContentHeight();
            mle.setX(px + 28);
            mle.setY(editBoxY);
            mle.setWidth(pw - 36);
            mle.setHeight(Math.max(contentH, 18));
            mle.render(g, mx, my, 0);
            // Render line prefixes aligned with MLE visual lines
            String hintComment = I18n.get("gui.create_schematic_compute.formula_comment_hint");
            String hintOutput = I18n.get("gui.create_schematic_compute.formula_output_hint");
            String hintAssign = I18n.get("gui.create_schematic_compute.formula_assign_hint");
            String hintExpr   = I18n.get("gui.create_schematic_compute.formula_expr_hint");
            String[] logLines = mle.getValue().split("\n", -1);
            if (logLines.length == 0) logLines = new String[]{""};
            int vi = 0;
            for (int li = 0; li < logLines.length; li++) {
                String lineText = logLines[li].trim();
                String prefix;
                if (lineText.startsWith("--")) prefix = "§8--";
                else if (lineText.startsWith("@output")) prefix = "§b@";
                else if (lineText.contains("=") && lineText.indexOf('=') > 0) prefix = "§e=";
                else if (!lineText.isEmpty()) prefix = "§7>";
                else prefix = "§7·";
                int visualCount = mle.visualLinesForLogicalLine(li);
                for (int v = 0; v < visualCount; v++) {
                    int lineY = editBoxY + 3 + vi * 12;
                    if (lineY + 12 > editBoxY + mle.getHeight()) break;
                    if (v == 0) {
                        boolean hovering = mx >= px + 4 && mx <= px + 26 && my >= lineY && my <= lineY + 12;
                        if (hovering) {
                            String tip = switch (prefix) {
                                case "§8--" -> hintComment;
                                case "§b@" -> hintOutput;
                                case "§e=" -> hintAssign;
                                default -> hintExpr;
                            };
                            g.drawString(font, "§7" + tip, px + 28, lineY - 9, 0xFFAAAAAA, false);
                        }
                        String lineLabel = prefix + (li + 1) + ":";
                        g.drawString(font, lineLabel, px + 4, lineY, 0xFFAAAAAA, false);
                    }
                    vi++;
                }
            }
        }
        } else {
        int r = NodeRenderer.PR;
        for (int i = 0; i < st.fields.size(); i++) {
            var b = st.fields.get(i);
            boolean hasParamPin = i < st.fieldParamIndices.size();
            boolean pinConnected = false;
            // 渲染参数输入引脚（始终可见，连线时变暗）
            if (hasParamPin) {
                int paramIdx = st.fieldParamIndices.get(i);
                int pinIdx = node.type.inputs + paramIdx;
                pinConnected = st.graph != null && st.graph.hasInputConnection(node.id, pinIdx);
                int pinX = px + 6 + r, pinY = py + 4 + row * 18 + 8;
                g.fill(pinX - r - 1, pinY - r - 1, pinX + r + 1, pinY + r + 1, NodeRenderer.CPIB());
                g.fill(pinX - r, pinY - r, pinX + r, pinY + r, pinConnected ? 0xFF666644 : NodeRenderer.CPI());
            }

            String label = i < st.paramKeys.length ? I18n.get("param.create_schematic_compute." + st.paramKeys[i]) + ":" :
                (node.type == NodeType.PRIVATE_IN || node.type == NodeType.PRIVATE_OUT ? I18n.get("gui.create_schematic_compute.edit.channel") : "");
            int labelX = px + (hasParamPin ? 18 : 4);
            g.drawString(Minecraft.getInstance().font, label, labelX, py + 4 + row * 18, 0xFF888888, false);
            // 已连线时隐藏输入框，但保留标签和引脚
            if (!pinConnected) {
                int lw = Minecraft.getInstance().font.width(label) + 6;
                int ex = px + (hasParamPin ? 16 : 4) + lw;
                int ey = py + 4 + row * 18;
                int ew = pw - lw - (hasParamPin ? 22 : 8);
                b.setX(ex); b.setY(ey); b.setWidth(ew);
                manualEditBox(g, b, ex, ey, ew, 16);
            }
            row++;
        }
        } // else 块结束
        // Color swatch for TEXT/DATA nodes (replaces old hex EditBox)
        if ((node.type == NodeType.TEXT || node.type == NodeType.DATA) && st.colorButton != null) {
            String label = node.type == NodeType.TEXT
                ? I18n.get("param.create_schematic_compute.color") + ":"
                : I18n.get("param.create_schematic_compute.color") + ":";
            int labelX = px + 4;
            g.drawString(Minecraft.getInstance().font, label, labelX, py + 4 + row * 18, 0xFF888888, false);
            int lw = Minecraft.getInstance().font.width(label) + 6;
            int swatchX = px + 4 + lw;
            int swatchY = py + 4 + row * 18;
            int color = node.textColor != 0 ? node.textColor
                : (node.type == NodeType.DATA ? 0xFF88FF88 : 0xFFCCCCCC);
            g.fill(swatchX, swatchY, swatchX + 16, swatchY + 16, color);
            boolean hover = mx >= swatchX && mx <= swatchX + 16 && my >= swatchY && my <= swatchY + 16;
            g.renderOutline(swatchX, swatchY, 16, 16, hover ? 0xFFFFAA44 : 0xFF888888);
            // Store position for mouse click detection (via EditState.colorButton)
            st.colorButton.setPosition(swatchX, swatchY);
            row++;
        }
        if (node.type == NodeType.BOOL && node.params.length > 0) {
            boolean inverted = node.params[0] > 0.5f;
            int bx = px + 4, by = py + 4 + row * 18;
            int bw = pw - 8, bh = 16;
            g.fill(bx, by, bx + bw, by + bh, inverted ? 0xFF3A5A2A : 0xFF3A3428);
            g.renderOutline(bx, by, bw, bh, NodeRenderer.CSB());
            g.renderOutline(bx+1, by+1, bw-2, bh-2, 0xFF1A1814);
            g.drawString(Minecraft.getInstance().font, inverted ? "§a✔ " + I18n.get("gui.create_schematic_compute.edit.inverted") : "§7" + I18n.get("gui.create_schematic_compute.edit.not_inverted"), bx+4, by+2, 0xFFFFFFFF, false);
            row++;
        }
        if (node.type == NodeType.GATE && node.params.length > 0) {
            boolean defOpen = node.params[0] > 0.5f;
            int bx = px + 4, by = py + 4 + row * 18;
            int bw = pw - 8, bh = 16;
            g.fill(bx, by, bx + bw, by + bh, defOpen ? 0xFF3A5A2A : 0xFF3A3428);
            g.renderOutline(bx, by, bw, bh, NodeRenderer.CSB());
            g.renderOutline(bx+1, by+1, bw-2, bh-2, 0xFF1A1814);
            g.drawString(Minecraft.getInstance().font, defOpen ? "§a✔ " + I18n.get("gui.create_schematic_compute.edit.default_open") : "§7" + I18n.get("gui.create_schematic_compute.edit.default_closed"), bx+4, by+2, 0xFFFFFFFF, false);
            row++;
        }
        if (node.type == NodeType.GATE && node.params.length > 1) {
            boolean curOpen = flipflopStates != null && flipflopStates.containsKey(node.id)
                ? flipflopStates.get(node.id) : node.params[1] > 0.5f;
            g.drawString(Minecraft.getInstance().font, "§7" + I18n.get(curOpen ? "gui.create_schematic_compute.edit.gate_cur_open" : "gui.create_schematic_compute.edit.gate_cur_closed"), px + 8, py + 4 + row * 18 + 3, 0xFFAAAAAA, false);
            row++;
        }
        if (node.type == NodeType.T_FLIPFLOP && node.params.length > 0) {
            boolean defOn = node.params[0] > 0.5f;
            int bx = px + 4, by = py + 4 + row * 18;
            int bw = pw - 8, bh = 16;
            g.fill(bx, by, bx + bw, by + bh, defOn ? 0xFF3A5A2A : 0xFF3A3428);
            g.renderOutline(bx, by, bw, bh, NodeRenderer.CSB());
            g.renderOutline(bx+1, by+1, bw-2, bh-2, 0xFF1A1814);
            g.drawString(Minecraft.getInstance().font, defOn ? "§a✔ " + I18n.get("gui.create_schematic_compute.edit.flipflop_default_on") : "§7" + I18n.get("gui.create_schematic_compute.edit.flipflop_default_off"), bx+4, by+2, 0xFFFFFFFF, false);
            row++;
        }
        if (node.type == NodeType.T_FLIPFLOP && node.params.length > 1) {
            boolean curOn = flipflopStates != null && flipflopStates.containsKey(node.id)
                ? flipflopStates.get(node.id) : node.params[1] > 0.5f;
            g.drawString(Minecraft.getInstance().font, "§7" + I18n.get(curOn ? "gui.create_schematic_compute.edit.flipflop_cur_on" : "gui.create_schematic_compute.edit.flipflop_cur_off"), px + 8, py + 4 + row * 18 + 3, 0xFFAAAAAA, false);
            row++;
        }
        if (node.type == NodeType.LATCH && node.params.length > 0) {
            boolean defSet = node.params[0] > 0.5f;
            int bx = px + 4, by = py + 4 + row * 18;
            int bw = pw - 8, bh = 16;
            g.fill(bx, by, bx + bw, by + bh, defSet ? 0xFF3A5A2A : 0xFF3A3428);
            g.renderOutline(bx, by, bw, bh, NodeRenderer.CSB());
            g.renderOutline(bx+1, by+1, bw-2, bh-2, 0xFF1A1814);
            g.drawString(Minecraft.getInstance().font, defSet ? "§a✔ " + I18n.get("gui.create_schematic_compute.edit.latch_default_set") : "§7" + I18n.get("gui.create_schematic_compute.edit.latch_default_reset"), bx+4, by+2, 0xFFFFFFFF, false);
            row++;
        }
        if (node.type == NodeType.LATCH && node.params.length > 1) {
            boolean curSet = flipflopStates != null && flipflopStates.containsKey(node.id)
                ? flipflopStates.get(node.id) : node.params[1] > 0.5f;
            g.drawString(Minecraft.getInstance().font, "§7" + I18n.get(curSet ? "gui.create_schematic_compute.edit.latch_cur_set" : "gui.create_schematic_compute.edit.latch_cur_reset"), px + 8, py + 4 + row * 18 + 3, 0xFFAAAAAA, false);
            row++;
        }
        if ((node.type == NodeType.IMAGE || node.type == NodeType.IMAGE_SEQUENCE) && node.params.length > 3) {
            for (int ti = 0; ti < 2; ti++) {
                boolean on = node.params[3 + ti] > 0.5f;
                String key = ti == 0 ? "gui.create_schematic_compute.edit.invert_x" : "gui.create_schematic_compute.edit.invert_y";
                int bx = px + 4, by = py + 4 + row * 18;
                int bw = pw - 8, bh = 14;
                g.fill(bx, by, bx + bw, by + bh, on ? 0xFF3A5A2A : 0xFF3A3428);
                g.renderOutline(bx, by, bw, bh, NodeRenderer.CSB());
                g.renderOutline(bx+1, by+1, bw-2, bh-2, 0xFF1A1814);
                g.drawString(Minecraft.getInstance().font, (on ? "§a✔ " : "§7") + I18n.get(key), bx+4, by+1, 0xFFFFFFFF, false);
                row++;
            }
        }
        if (node.type == NodeType.REDSTONE_IN || node.type == NodeType.REDSTONE_OUT) {
            st.freqSlotX = px + 4; st.freqSlotY = py + 8 + row * 18;
            for (int i = 0; i < 2; i++) {
                int bx = (int)st.freqSlotX + i * 24;
                g.fill(bx, (int)st.freqSlotY, bx + 20, (int)st.freqSlotY + 20, 0xFF1A1814);
                g.renderOutline(bx, (int)st.freqSlotY, 20, 20, i == st.freqSlotSelected ? 0xFFFFAA44 : NodeRenderer.CSB());
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
            case K_SPACE -> I18n.get("key.create_schematic_compute.space");
            case K_LSHIFT -> I18n.get("key.create_schematic_compute.lshift");
            case K_RSHIFT -> I18n.get("key.create_schematic_compute.rshift");
            case K_LCTRL -> I18n.get("key.create_schematic_compute.lctrl");
            case K_RCTRL -> I18n.get("key.create_schematic_compute.rctrl");
            case K_LALT -> I18n.get("key.create_schematic_compute.lalt");
            case K_RALT -> I18n.get("key.create_schematic_compute.ralt");
            case K_ENTER -> I18n.get("key.create_schematic_compute.enter");
            case K_TAB -> I18n.get("key.create_schematic_compute.tab");
            case K_BACKSPACE -> I18n.get("key.create_schematic_compute.backspace");
            case K_CAPS -> I18n.get("key.create_schematic_compute.caps");
            case K_MINUS -> "-"; case K_EQUALS -> "=";
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
            case org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT -> K_LSHIFT;
            case org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT -> K_RSHIFT;
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

    /** 检查指定输出引脚是否有连线（BUS_IN 编辑区输出引脚用） */
    private static boolean hasOutputConnection(io.github.y15173334444.create_schematic_compute.graph.NodeGraph g, int nodeId, int pinIdx) {
        for (var c : g.connections)
            if (c.fromId == nodeId && c.fromPin == pinIdx) return true;
        return false;
    }

    /** Manual EditBox rendering — draws bg, text, and cursor inline without
     *  relying on {@link EditBox#render} which separates fills and text into
     *  different vertex buffers (causing text to bleed through later draw calls). */
    private static void manualEditBox(GuiGraphics g, EditBox box, int x, int y, int w, int h) {
        boolean focused = box.isFocused();
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, focused ? 0xFFFFFFFF : 0xFFA0A0A0);
        g.fill(x, y, x + w, y + h, 0xFF000000);
        String val = box.getValue();
        g.drawString(Minecraft.getInstance().font, val, x + 4, y + (h - 8) / 2 + 1, 0xFFFFFFFF, false);
        if (focused && (System.currentTimeMillis() / 500L % 2L == 0L)) {
            int cp = Math.min(box.getCursorPosition(), val.length());
            int cx = x + 4 + Minecraft.getInstance().font.width(val.substring(0, cp));
            g.fill(cx, y + 2, cx + 1, y + h - 4, 0xFFFFFFFF);
        }
    }
}
