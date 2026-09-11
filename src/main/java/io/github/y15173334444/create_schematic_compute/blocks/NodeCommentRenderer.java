package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 注释节点渲染器（A=1 层）：便签样式背景、拖拽头栏、编辑按钮、缩放手柄与 markdown 简化正文，
 * 自 {@link NodeRenderer} 拆分（docs/gui-decomposition-plan.md 步骤 5 第二刀）。
 * The comment-node renderer (layer A=1): sticky-note background, drag header, edit button,
 * resize handle and the markdown-simplified body, split out of {@link NodeRenderer}
 * (roadmap step 5, second cut).
 *
 * <p><b>行为零变更</b>：实现逐字搬迁，坐标映射与屏幕尺寸经构造注入，主题色仍取自
 * {@link NodeRenderer} 的包级访问器（单一来源）。编辑态文本与展开集合原为渲染器的公共镜像
 * 字段，仅渲染路径内部使用，现随实现搬入本类。
 * <b>Behaviour-preserving</b>: bodies moved verbatim; the coordinate mappers and screen size
 * are constructor-injected and theme colours still come from {@link NodeRenderer}'s
 * package-level accessors (single source). The edit-state text and expanded-set mirrors were
 * renderer fields used only inside the render path — they moved with the implementation.</p>
 */
final class NodeCommentRenderer {

    private final NodeRenderer.CoordMapper c2sX, c2sY;
    private final net.minecraft.client.gui.screens.Screen screen;

    // 编辑区高度（像素，本地坐标空间）/ edit-area mirrors (moved from NodeRenderer's render path)
    private java.util.Set<Integer> expandedNodeIds = java.util.Collections.emptySet();
    private java.util.Map<Integer, io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.EditState> nodeEditStatesById = java.util.Collections.emptyMap();

    NodeCommentRenderer(NodeRenderer.CoordMapper c2sX, NodeRenderer.CoordMapper c2sY,
                        net.minecraft.client.gui.screens.Screen screen) {
        this.c2sX = c2sX; this.c2sY = c2sY;
        this.screen = screen;
    }

    /** A=1: Render complete COMMENT nodes (background, border, text, handles) behind connections.
     *  Comment nodes act as container mats — everything renders at A=1, sorted by B. */
    void renderCommentNodes(GuiGraphics g, List<GraphNode> nodes, Set<GraphNode> selectedNodes,
                                    GraphNode primaryNode, java.util.Set<Integer> editNodeIds,
                                    java.util.Map<Integer, io.github.y15173334444.create_schematic_compute.blocks.GraphEditor.EditState> editStates,
                                    float camX, float camY, float zoom, int mx, int my,
                                    Map<Integer, Boolean> flipflopStates,
                                    Map<Integer, String> lockedNodes) {
        expandedNodeIds = editNodeIds != null ? editNodeIds : java.util.Collections.emptySet();
        nodeEditStatesById = editStates != null ? editStates : java.util.Collections.emptyMap();
        int w = screen.width, h = screen.height;
        float margin = 50;
        for (var n : nodes) {
            if (n.type != NodeType.COMMENT) continue;
            float sx = c2sX.apply(n.x), sy = c2sY.apply(n.y);
            float sw = n.commentWidth * zoom;
            float sh = n.commentHeight * zoom;
            if (sx + sw < -margin || sx > w + margin || sy + sh < -margin || sy > h + margin)
                continue;
            drawCommentNode(g, n, NodeRenderer.isSelectedById(selectedNodes, n), NodeRenderer.isPrimaryById(primaryNode, n),
                expandedNodeIds.contains(n.id), camX, camY, zoom, mx, my, false,
                lockedNodes != null ? lockedNodes.get(n.id) : null);
        }
    }

    /** Render a COMMENT node with sticky-note styling, edit button, resize handle, and markdown text. */
    private void drawCommentNode(GuiGraphics g, GraphNode n, boolean selected, boolean isPrimary,
                                  boolean editing, float camX, float camY, float zoom, int mx, int my,
                                  boolean skipBackground, String lockedBy) {
        float sx = c2sX.apply(n.x), sy = c2sY.apply(n.y);
        float sw = n.commentWidth * zoom;
        float sh = n.commentHeight * zoom;
        int isx = (int) sx, isy = (int) sy, isw = (int) (sx + sw), ish = (int) (sy + sh);
        int headerH = Math.max(6, (int)(12 * zoom)); // drag header bar

        // Background — skip when already rendered at A=1
        if (!skipBackground) g.fill(isx, isy, isw, ish, n.commentBgColor);
        // Header drag bar (allows drag-only-by-header, like regular nodes)
        int argb = n.commentBgColor;
        int headerBg = ((Math.min((argb >> 16) & 0xFF, 220) << 16)
                      | (Math.min((argb >> 8) & 0xFF, 220) << 8)
                      | Math.min(argb & 0xFF, 220)
                      | (argb & 0xFF000000));
        g.fill(isx, isy, isw, (int)(isy + headerH), headerBg);
        // Border: brighter when editing
        int borderColor;
        if (editing) borderColor = 0xFFC0A060;                // warm tan = active editing
        else if (isPrimary) borderColor = NodeRenderer.ACC();               // gold = primary selection
        else if (selected) borderColor = NodeRenderer.ACC();                // light gold = selected
        else borderColor = n.commentBorderColor;                // custom = normal
        g.renderOutline(isx, isy, (int) sw, (int) sh, borderColor);
        // Remote lock/selection indicator — golden border when another player has this comment selected
        // 远程锁定/选中指示器 — 其他玩家选中此注释时显示金色边框
        if (lockedBy != null && !lockedBy.isEmpty()) {
            int lockCol = NodeRenderer.ACC();
            g.renderOutline(isx - 2, isy - 2, (int) sw + 4, (int) sh + 4, lockCol);
            g.renderOutline(isx - 3, isy - 3, (int) sw + 6, (int) sh + 6, lockCol);
            int lw = Minecraft.getInstance().font.width(lockedBy);
            int lx = isx + ((int) sw - lw) / 2;
            int ly = isy - (int)(16 * zoom) - 2;
            g.fill(lx - 3, ly - 2, lx + lw + 3, ly + (int)(12 * zoom), 0xCC2A2822);
            var lockPose = g.pose();
            lockPose.pushPose();
            lockPose.translate(lx, ly, 0);
            lockPose.scale(zoom, zoom, 1);
            drawStr(g, "§e" + lockedBy, 0, 0, 0xFFFFAA44);
            lockPose.popPose();
        }
        // Editing banner — rendered above the node
        if (editing) {
            int bannerH = (int)(16 * zoom);
            String editHint = I18n.get("gui.create_schematic_compute.comment.edit_hint");
            int textW = Minecraft.getInstance().font.width(editHint);
            float bannerW = (textW + 16) * zoom;
            float bx = sx + sw/2 - bannerW/2;
            float by = sy - bannerH - 2 * zoom;
            g.fill((int)bx, (int)by, (int)(bx + bannerW), (int)(by + bannerH), 0xCC2A2822);
            g.renderOutline((int)bx, (int)by, (int)bannerW, bannerH, 0xFFC0A060);
            var bannerPose = g.pose();
            bannerPose.pushPose();
            bannerPose.translate(bx + bannerW/2, by + bannerH/2, 0);
            bannerPose.scale(zoom, zoom, 1);
            drawStr(g, "§e" + editHint, -textW/2, -4, 0xFFFFDD77);
            bannerPose.popPose();
        }
        // Inner subtle border
        if ((int) sw > 6 && (int) sh > 6) {
            int ib = n.commentBorderColor;
            int innerB = (Math.min((ib >> 16) & 0xFF, 248) << 16)
                       | (Math.min((ib >> 8) & 0xFF, 248) << 8)
                       | Math.min(ib & 0xFF, 248)
                       | (ib & 0xFF000000);
            g.renderOutline(isx + 2, isy + 2, (int) sw - 4, (int) sh - 4, innerB);
        }

        // Edit button — top-right 14×14 px, highlighted when editing
        int btnBg = editing ? 0x88C0A060 : 0x66000000;
        int btnOutline = editing ? 0xFFC0A060 : 0xFF888888;
        float btnSize = 14 * zoom;
        int btnX = (int) (sx + 2 * zoom);
        int btnY = (int) (sy + 2 * zoom);
        int btnS = (int) btnSize;
        // Button background
        g.fill(btnX, btnY, btnX + btnS, btnY + btnS, btnBg);
        g.renderOutline(btnX, btnY, btnS, btnS, btnOutline);
        // Gear icon
        var btPose = g.pose();
        btPose.pushPose();
        btPose.translate(btnX + btnS/2f, btnY + btnS/2f, 0);
        btPose.scale(zoom, zoom, 1);
        drawStr(g, "§7⚙", -4, -4, 0xFFCCCCCC);
        btPose.popPose();

        // Resize handle — bottom-right corner, darker contrasting L-shape with grip lines
        int handleW = (int) (18 * zoom);
        int hx2 = (int) (sx + sw - handleW);
        int hy2 = (int) (sy + sh - handleW);
        // Darker contrasting background corner
        int handleBg = 0x88000000;
        g.fill(hx2, hy2, (int) (sx + sw), (int) (sy + sh), handleBg);
        // L-shaped corner lines — thick, visible dark gray
        int handleColor = 0xFF777777;
        int lineW = Math.max(1, (int) (3 * zoom));
        g.fill(hx2, (int) (sy + sh - lineW), (int) (sx + sw), (int) (sy + sh), handleColor);
        g.fill((int) (sx + sw - lineW), hy2, (int) (sx + sw), (int) (sy + sh), handleColor);
        // Diagonal grip stripes — 3 short diagonal lines (classic resize grip pattern)
        int gripColor = 0xFFAAAAAA;
        for (int i = 0; i < 3; i++) {
            int gx = (int) (sx + sw - (5 * zoom) - i * (5 * zoom));
            int gy = (int) (sy + sh - (2 * zoom));
            g.fill(gx, gy, gx + Math.max(1, (int)(3 * zoom)), gy + Math.max(1, (int)(2 * zoom)), gripColor);
        }

        // Render text body (markdown-simplified), below the header bar
        var mc = Minecraft.getInstance();
        float textX = sx + 6 * zoom;
        float textY = sy + headerH + 4 * zoom;
        float maxTextW = sw - 26 * zoom;
        int availW = Math.max(1, (int) (maxTextW / zoom));
        int lineH = 12;
        float visibleH = (sh - headerH - 8 * zoom) / zoom;
        int maxVis = Math.max(1, (int)(visibleH / lineH));
        String renderText = editing ? (nodeEditStatesById.containsKey(n.id)
            ? getEditStateText(n) : n.displayText) : n.displayText;

        // Compute scroll info — use edit state text when editing, displayText otherwise
        String scrollText = n.displayText;
        if (editing) {
            var st = nodeEditStatesById.get(n.id);
            if (st != null && !st.fields.isEmpty()
                && st.fields.get(0) instanceof io.github.y15173334444.create_schematic_compute.client.MultiLineEditBox mle) {
                scrollText = mle.getValue();
            }
        }
        int totalWraps = 0;
        if (!scrollText.isEmpty()) {
            totalWraps = countWrappedLinesLocal(scrollText, availW);
        }
        int scrollMax = Math.max(0, totalWraps - maxVis);
        int scrollOff = n.commentScrollOff;
        if (scrollOff < 0) scrollOff = 0;
        if (scrollOff > scrollMax) { scrollOff = scrollMax; n.commentScrollOff = scrollOff; }

        // Scrollbar — only visible when text overflows
        if (scrollMax > 0) {
            int sbX = (int)(sx + sw - 10 * zoom);
            int sbY = (int)(sy + headerH + 4 * zoom);
            int sbH = (int)(sh - headerH - 8 * zoom);
            int sbW = Math.max(2, (int)(6 * zoom));
            g.fill(sbX, sbY, sbX + sbW, sbY + sbH, 0xFF3A3832);
            float thumbH = Math.max(12 * zoom, (float) maxVis / totalWraps * sbH);
            float thumbY = sbY + (float) scrollOff / scrollMax * (sbH - thumbH);
            g.fill(sbX + 1, (int)thumbY, sbX + sbW - 1, (int)(thumbY + thumbH), n.commentBorderColor);
        }

        if (editing) {
            // Render the MLE on the sticky-note surface via EditPanel, with scroll offset
            var editSt = nodeEditStatesById.get(n.id);
            if (editSt != null) {
                int scX2 = (int) sx, scY2 = (int) (sy + headerH);
                int scW2 = (int) sw, scH2 = (int) (sh - headerH);
                g.enableScissor(scX2, scY2, scX2 + scW2, scY2 + scH2);
                int commentEditY = (int)(headerH / zoom) + 4 - scrollOff * lineH;
                var pose = g.pose();
                pose.pushPose();
                pose.translate(sx, sy, 0);
                pose.scale(zoom, zoom, 1);
                io.github.y15173334444.create_schematic_compute.blocks.EditPanel.renderAt(
                    g, 0, commentEditY, Math.round(n.commentWidth), n, editSt, zoom, mx, my, null);
                pose.popPose();
                g.disableScissor();
            }
        } else if (!n.displayText.isEmpty()) {
            // Build all wrapped segments into a flat list
            var segments = new java.util.ArrayList<String>();
            for (String line : n.displayText.split("\n", -1)) {
                String remaining = line;
                if (remaining.isEmpty()) { segments.add(""); continue; }
                while (!remaining.isEmpty()) {
                    if (mc.font.width(plainText(remaining)) <= availW) {
                        segments.add(remaining);
                        break;
                    }
                    String chunk = mc.font.plainSubstrByWidth(remaining, availW);
                    if (chunk.isEmpty()) chunk = remaining.substring(0, 1);
                    segments.add(chunk);
                    remaining = remaining.substring(chunk.length());
                }
            }

            // Render visible segments with scissor clipping
            int scX = (int) sx, scY = (int) (sy + headerH);
            int scW = (int) sw, scH = (int) (sh - headerH);
            g.enableScissor(scX, scY, scX + scW, scY + scH);
            float ly = textY;
            int endIdx = Math.min(segments.size(), scrollOff + maxVis);
            for (int i = scrollOff; i < endIdx; i++) {
                if (ly > sy + sh - 10 * zoom) break;
                var txtPose = g.pose();
                txtPose.pushPose();
                txtPose.translate(textX, ly, 0);
                txtPose.scale(zoom, zoom, 1);
                renderMarkdownLine(g, segments.get(i), 0, 0, availW, n.commentTextColor);
                txtPose.popPose();
                ly += 12 * zoom;
            }
            g.disableScissor();
        }
        g.flush(); // per-node flush for buffer ordering (see drawNode)
    }

    private static String getEditStateText(GraphNode n) {
        // Return empty — MLE handles its own text display
        return "";
    }

    private static int countWrappedLinesLocal(String text, int availW) {
        var font = Minecraft.getInstance().font;
        int total = 0;
        for (String line : text.split("\n", -1)) {
            String rem = line;
            if (rem.isEmpty()) { total++; continue; }
            while (!rem.isEmpty()) {
                if (font.width(rem) <= availW) { total++; break; }
                String chunk = font.plainSubstrByWidth(rem, availW);
                if (chunk.isEmpty()) chunk = rem.substring(0, 1);
                total++;
                rem = rem.substring(chunk.length());
            }
        }
        return Math.max(1, total);
    }

    private static String plainText(String line) {
        return line.replaceAll("\\*\\*|\\*|`|#\\s?", "");
    }

    private void renderMarkdownLine(GuiGraphics g, String line, int x, int y, int maxW, int textColor) {
        if (line.startsWith("# ")) {
            drawStr(g, "§l" + line.substring(2), x, y, textColor);
        } else if (line.startsWith("- ")) {
            drawStr(g, "§7•§r " + inlineMarkdown(line.substring(2)), x, y, textColor);
        } else {
            drawStr(g, inlineMarkdown(line), x, y, textColor);
        }
    }

    private static String inlineMarkdown(String text) {
        var sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            if (i + 1 < text.length() && text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                int end = text.indexOf("**", i + 2);
                if (end >= 0) {
                    sb.append("§l").append(text, i + 2, end).append("§r");
                    i = end + 2;
                } else { sb.append(text.charAt(i)); i++; }
            } else if (text.charAt(i) == '*' && (i == 0 || text.charAt(i - 1) != '\\')) {
                int end = text.indexOf('*', i + 1);
                if (end >= 0) {
                    sb.append("§o").append(text, i + 1, end).append("§r");
                    i = end + 1;
                } else { sb.append(text.charAt(i)); i++; }
            } else if (text.charAt(i) == '`') {
                int end = text.indexOf('`', i + 1);
                if (end >= 0) {
                    sb.append("§7§o").append(text, i + 1, end).append("§r");
                    i = end + 1;
                } else { sb.append(text.charAt(i)); i++; }
            } else {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    private static void drawStr(GuiGraphics g, String t, float x, float y, int c) {
        g.drawString(Minecraft.getInstance().font, t, (int)x, (int)y, c, false);
    }
}
