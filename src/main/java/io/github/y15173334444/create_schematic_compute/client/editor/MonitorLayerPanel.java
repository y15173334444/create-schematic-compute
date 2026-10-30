package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.y15173334444.create_schematic_compute.client.GeometryConstants.*;
import static io.github.y15173334444.create_schematic_compute.client.editor.MonitorDisplayEditor.ff1;
import io.github.y15173334444.create_schematic_compute.client.EditorTheme;

/**
 * 全息显示器「显示编辑」GUI 的图层面板（拓扑审查 §三 P7 三分，自 MonitorDisplayEditor 逐字迁出）：
 * 图层列表渲染（缩略图/幽灵行/放置指示线/滚动条）、行命中与拖拽排序（PRESSED→DRAGGING 状态机、
 * 自动滚动、SET_LAYER_INDEX 定向同步）与滚动条拖拽。共享状态（selectedDisplayNode）与共享原语
 * （sendOp/getEvalOutputs/renderPixels）留在 MonitorDisplayEditor，经 ed 引用取用——本类不建
 * 继承体系，是编辑器的协作对象。
 * <p>The layer panel of the holographic monitor display-layout editor (topology-review §三
 * P7 three-way split, relocated verbatim from MonitorDisplayEditor): the layer list render
 * (thumbnails / ghost row / drop indicator / scrollbar), row hit-testing and drag-to-reorder
 * (the PRESSED→DRAGGING state machine, auto-scroll, targeted SET_LAYER_INDEX sync) and the
 * scrollbar drag. Shared state (selectedDisplayNode) and shared primitives (sendOp /
 * getEvalOutputs / renderPixels) stay on MonitorDisplayEditor, reached through the ed
 * reference — a collaborator object, no inheritance hierarchy.</p>
 */
final class MonitorLayerPanel {

    private final MonitorDisplayEditor ed;
    private final MonitorDisplayEditor.Host host;

    MonitorLayerPanel(MonitorDisplayEditor ed) {
        this.ed = ed;
        this.host = ed.host();
    }

    /** 统一 op 出口（经编辑器的 temp-id 漏斗）。/ unified op outlet via the editor funnel. */
    private void sendOp(GraphOp op) { ed.sendOp(op); }
    private Map<Integer, float[]> getEvalOutputs() { return ed.getEvalOutputs(); }
    private void renderPixels(GuiGraphics g, int[] pixels, int x, int y, int cellSize, int gridW, int gridH) {
        ed.renderPixels(g, pixels, x, y, cellSize, gridW, gridH);
    }

    // ── Layer panel state ──
    private int layerScroll = 0;

    // ── Layer drag-and-drop state ──
    private enum LayerDragState { IDLE, PRESSED, DRAGGING }
    private LayerDragState layerDragState = LayerDragState.IDLE;
    private GraphNode layerDragNode = null;        // the node being dragged
    private int layerDragOrigIndex = -1;           // original position in full sorted list
    private int layerDropIndex = -1;               // where the drop indicator draws
    private double layerDragStartMy = 0;           // mouse Y when click started
    private long layerDragPressTime = 0;           // system time when click started
    private long lastAutoScrollTime = 0;           // throttle timer for auto-scroll
    private boolean layerScrollbarDragging = false;
    private double layerScrollDragStartY = 0;
    private int layerScrollDragStartOff = 0;

    // ── 路由小助手（原编辑器路由方法里的图层段，语义逐字）/ router helpers ──

    /** mouseMoved/mouseDragged 共用的拖拽推进段：PRESSED 超阈值/超时升格 DRAGGING；
     *  DRAGGING 且持有节点时更新落点 + 自动滚动并返回 true（消费）。 */
    boolean dragMove(double my) {
        if (layerDragState == LayerDragState.PRESSED) {
            if (Math.abs(my - layerDragStartMy) > LAYER_DRAG_THRESHOLD
                || System.currentTimeMillis() - layerDragPressTime > 200) {
                layerDragState = LayerDragState.DRAGGING;
            }
        }
        if (layerDragState == LayerDragState.DRAGGING && layerDragNode != null) {
            updateLayerDropIndex(my);
            handleLayerAutoScroll(my);
            return true;
        }
        return false;
    }

    /** 键盘 ESC 取消拖拽的判定（原 handleKeyPressed 的状态检查）。 */
    boolean isDragging() { return layerDragState == LayerDragState.DRAGGING; }

    /** 松手路径的 PRESSED 复位判定（原 handleMouseReleased 的状态检查）。 */
    boolean isPressed() { return layerDragState == LayerDragState.PRESSED; }

    /** 松手路径的滚动条拖拽结束（原 handleMouseReleased 的首段）。 */
    boolean endScrollbarDrag() {
        if (!layerScrollbarDragging) return false;
        layerScrollbarDragging = false;
        return true;
    }

    /** 松手/关界冲刷的判定（DRAGGING 且持有节点）。 */
    boolean isDragActive() { return layerDragState == LayerDragState.DRAGGING && layerDragNode != null; }

    /** 面板区域滚轮（原 handleMouseScrolled 的图层段）。 */
    void scrollBy(double sy) { layerScroll += (sy > 0) ? -1 : 1; }

    /** 原面板区域宽度命中判定供路由复用 / the panel-x hit test reused by the router. */
    boolean inPanelX(double mx) {
        int px = host.screenWidth() - LAYER_PANEL_W - LAYER_PANEL_PADDING;
        return mx >= px && mx <= px + LAYER_PANEL_W;
    }

    /** P7 拆出：原 handleDisplayAreaClick 的「行命中 + 拖拽发起」块。
     *  拆分时此调用点曾被整块遗漏（见 MonitorDisplayEditor 步骤 2 实施记录），保序为先于元素命中。 */
    boolean tryRowPress(double mx, double my) {
        int clickedLayerIdx = handleLayerPanelClick(mx, my);
        if (clickedLayerIdx >= 0) {
            // Initiate potential drag
            layerDragState = LayerDragState.PRESSED;
            layerDragNode = ed.selectedDisplayNode;
            layerDragOrigIndex = clickedLayerIdx;
            layerDropIndex = clickedLayerIdx;
            layerDragStartMy = my;
            layerDragPressTime = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    // ════ 以下自 MonitorDisplayEditor 原样搬迁 / verbatim-moved from MonitorDisplayEditor ════

    /** 图层滚动条拖动中（mouseMoved）。返回 true = 消费本次移动。
     *  Layer scrollbar drag from mouseMoved. */
    boolean handleLayerScrollbarDrag(double mx, double my) {
        if (!layerScrollbarDragging) return false;
        var graph3 = host.be() != null ? host.be().getNodeGraph() : new NodeGraph();
        List<GraphNode> layers3 = getDisplayLayers(graph3);
        if (!layers3.isEmpty()) {
            int rowStartY3 = GraphEditor.TOP_BAR_H + 4 + 12 + 2;
            int maxRows3 = Math.max(1, (host.screenHeight() - rowStartY3 - 4) / LAYER_ROW_H);
            int visRows3 = Math.min(layers3.size(), maxRows3);
            int maxScroll3 = Math.max(0, layers3.size() - maxRows3);
            int sbH3 = visRows3 * LAYER_ROW_H;
            float thumbH3 = Math.max(20, (float) visRows3 / layers3.size() * sbH3);
            float delta = (float) (my - layerScrollDragStartY) / (sbH3 - thumbH3);
            int newOff = layerScrollDragStartOff + Math.round(delta * maxScroll3);
            if (newOff < 0) newOff = 0;
            if (newOff > maxScroll3) newOff = maxScroll3;
            layerScroll = newOff;
        }
        return true;
    }

    /** 图层滚动条拇指按下 → 开始拖动。返回 true = 已消费。
     *  Layer-panel scrollbar thumb press → begin a thumb drag. */
    boolean handleLayerScrollbarPress(double mx, double my) {
        var graph2 = host.be() != null ? host.be().getNodeGraph() : new NodeGraph();
        List<GraphNode> layers2 = getDisplayLayers(graph2);
        if (layers2.isEmpty()) return false;
        int px2 = host.screenWidth() - LAYER_PANEL_W - LAYER_PANEL_PADDING;
        int rowStartY2 = GraphEditor.TOP_BAR_H + 4 + 12 + 2;
        int maxRows2 = Math.max(1, (host.screenHeight() - rowStartY2 - 4) / LAYER_ROW_H);
        int visRows2 = Math.min(layers2.size(), maxRows2);
        int maxScroll2 = Math.max(0, layers2.size() - maxRows2);
        if (maxScroll2 <= 0) return false;
        int sbX2 = px2 + LAYER_PANEL_W - 8;
        int sbY2 = rowStartY2;
        int sbH2 = visRows2 * LAYER_ROW_H;
        float thumbH2 = Math.max(20, (float) visRows2 / layers2.size() * sbH2);
        float thumbY2 = sbY2 + (float) layerScroll / maxScroll2 * (sbH2 - thumbH2);
        if (mx >= sbX2 && mx <= sbX2 + 6 && my >= thumbY2 && my <= thumbY2 + thumbH2) {
            layerScrollbarDragging = true;
            layerScrollDragStartY = my;
            layerScrollDragStartOff = layerScroll;
            return true;
        }
        return false;
    }

    // ── Layer panel ──
    private List<GraphNode> getDisplayLayers(NodeGraph graph) {
        List<GraphNode> layers = new ArrayList<>();
        for (var n : graph.nodes) {
            if (n.type == NodeType.TEXT || n.type == NodeType.DATA
                || n.type == NodeType.IMAGE || n.type == NodeType.IMAGE_SEQUENCE)
                layers.add(n);
        }
        layers.sort((a, b) -> Integer.compare(b.layerIndex, a.layerIndex));
        return layers;
    }

    private void renderLayerThumbnail(GuiGraphics g, GraphNode node, int x, int y, int size) {
        // Dark background
        g.fill(x, y, x + size, y + size, EditorTheme.get().insetBg());

        switch (node.type) {
            case TEXT -> {
                String preview = node.displayText.isEmpty() ? "T"
                    : node.displayText.substring(0, Math.min(3, node.displayText.length()));
                int tc = node.textColor != 0 ? node.textColor : 0xFFCCCCCC;
                int tw = Minecraft.getInstance().font.width(preview);
                g.drawString(Minecraft.getInstance().font, preview,
                    x + (size - tw) / 2, y + (size - 8) / 2, tc, false);
            }
            case DATA -> {
                var graph = host.be() != null ? host.be().getNodeGraph() : new NodeGraph();
                var evalOutputs2 = getEvalOutputs();
                float val = graph.getInputValue(node.id, 0, evalOutputs2);
                String valStr = ff1(val);
                int dc = node.textColor != 0 ? node.textColor : 0xFF88FF88;
                int tw = Minecraft.getInstance().font.width(valStr);
                g.drawString(Minecraft.getInstance().font, valStr,
                    x + (size - tw) / 2, y + (size - 8) / 2, dc, false);
            }
            case IMAGE -> {
                if (node.imagePixels != null) {
                    int cellSz = 1;
                    int offsetX = x + (size - node.imageWidth * cellSz) / 2;
                    int offsetY = y + (size - node.imageHeight * cellSz) / 2;
                    renderPixels(g, node.imagePixels, offsetX, offsetY, cellSz, node.imageWidth, node.imageHeight);
                }
            }
            case IMAGE_SEQUENCE -> {
                var graph = host.be() != null ? host.be().getNodeGraph() : new NodeGraph();
                var evalOutputs3 = getEvalOutputs();
                int frameIdx = Math.round(graph.getInputValue(node.id, 2, evalOutputs3));
                int[] pixels = null;
                if (node.imageSequenceFrames != null && !node.imageSequenceFrames.isEmpty()) {
                    frameIdx = Math.max(0, Math.min(frameIdx, node.imageSequenceFrames.size() - 1));
                    pixels = node.imageSequenceFrames.get(frameIdx);
                }
                if (pixels != null) {
                    int cellSz = 1;
                    int offsetX = x + (size - node.imageWidth * cellSz) / 2;
                    int offsetY = y + (size - node.imageHeight * cellSz) / 2;
                    renderPixels(g, pixels, offsetX, offsetY, cellSz, node.imageWidth, node.imageHeight);
                }
                // "S" badge at top-right of thumbnail
                int badgeX = x + size - 7;
                int badgeY = y + 1;
                g.fill(badgeX, badgeY, badgeX + 6, badgeY + 6, 0xFF3A3A3A);
                g.renderOutline(badgeX, badgeY, 6, 6, EditorTheme.get().sysBorder());
                g.drawString(Minecraft.getInstance().font, "S", badgeX + 1, badgeY, EditorTheme.get().accent(), false);
            }
            case HUD_PITCH_LADDER -> {
                // 缩略图：简化俯仰梯示意（中心地平线 + 上下刻度线）
                // Thumbnail: simplified ladder sketch (center horizon + ticks above/below)
                int cy = y + size / 2;
                for (int deg = 1; deg <= 3; deg++) {
                    int dy = Math.round(size * 0.22f * deg / 3f);
                    g.hLine(x + 2, cy - dy, x + size - 2, 0x55FFFFFF);
                    g.hLine(x + 2, cy + dy, x + size - 2, 0x55FFFFFF);
                }
                g.hLine(x + 2, cy, x + size - 2, 0xFF88E866);
                g.fill(x + size / 2 - 2, cy - 1, x + size / 2 + 2, cy + 1, 0xFF33CC66);
            }
        }
    }

    void renderLayerPanel(GuiGraphics g, int mx, int my) {
        var graph = host.be() != null ? host.be().getNodeGraph() : new NodeGraph();
        List<GraphNode> layers = getDisplayLayers(graph);
        if (layers.isEmpty()) return;

        int px = host.screenWidth() - LAYER_PANEL_W - LAYER_PANEL_PADDING;
        // 让开编辑器顶栏 + 4px 边距（模式条并入顶栏后基础工具栏行已收回）。
        // Clears the editor top bar + a 4px margin (the base-toolbar row is reclaimed now
        // that the strip is merged into the top bar).
        int py = GraphEditor.TOP_BAR_H + 4;
        int titleH = 12;
        int rowStartY = py + titleH + 2;
        // Calculate max visible rows below title
        int availableH = host.screenHeight() - rowStartY - 4;
        int maxRows = Math.max(1, availableH / LAYER_ROW_H);
        int visibleRows = Math.min(layers.size(), maxRows);
        int ph = titleH + 2 + visibleRows * LAYER_ROW_H + 4;
        if (layers.size() > maxRows) ph += 2; // scrollbar foot

        // Panel background
        g.fill(px, py, px + LAYER_PANEL_W, py + ph, 0xCC1A1814);
        g.renderOutline(px, py, LAYER_PANEL_W, ph, 0xFF6A6A4A);

        // Title bar
        g.fill(px + 1, py + 1, px + LAYER_PANEL_W - 1, py + titleH + 1, EditorTheme.get().panelBg());
        String title = "Layers";
        int titleW = Minecraft.getInstance().font.width(title);
        g.drawString(Minecraft.getInstance().font, title,
            px + (LAYER_PANEL_W - titleW) / 2, py + 2, EditorTheme.get().sysBorder(), false);

        int maxScroll = Math.max(0, layers.size() - maxRows);
        if (layerScroll < 0) layerScroll = 0;
        if (layerScroll > maxScroll) layerScroll = maxScroll;

        // Compute drop indicator Y position (in screen space, above the target row)
        int dropIndicatorY = -1;
        if (layerDragState == LayerDragState.DRAGGING && layerDropIndex >= 0) {
            int visibleDropIdx = layerDropIndex - layerScroll;
            if (visibleDropIdx >= 0 && visibleDropIdx <= visibleRows) {
                dropIndicatorY = rowStartY + visibleDropIdx * LAYER_ROW_H;
            }
        }

        // Draw drop indicator line (behind rows)
        if (dropIndicatorY >= rowStartY) {
            g.fill(px + 2, dropIndicatorY - 1, px + LAYER_PANEL_W - 2, dropIndicatorY + 1, EditorTheme.get().accent());
        }

        for (int vi = 0; vi < visibleRows; vi++) {
            int idx = layerScroll + vi;
            if (idx >= layers.size()) break;
            var n = layers.get(idx);
            int ry = rowStartY + vi * LAYER_ROW_H;
            boolean isSel = ed.selectedDisplayNode != null && ed.selectedDisplayNode.id == n.id;
            boolean isDragged = layerDragState == LayerDragState.DRAGGING
                             && layerDragNode != null && layerDragNode.id == n.id;

            if (isDragged) {
                // Ghost — dimmed placeholder at original position
                g.fill(px + 2, ry, px + LAYER_PANEL_W - 2, ry + LAYER_ROW_H, 0x442A2822);
            } else {
                int bgCol = isSel ? 0xFF4A5A2A : (idx % 2 == 0 ? 0xFF2A2822 : 0xFF22201A);
                g.fill(px + 2, ry, px + LAYER_PANEL_W - 2, ry + LAYER_ROW_H, bgCol);
                // Hover highlight (only when not dragging)
                if (layerDragState != LayerDragState.DRAGGING
                    && mx >= px && mx <= px + LAYER_PANEL_W
                    && my >= ry && my <= ry + LAYER_ROW_H) {
                    g.fill(px + 2, ry, px + LAYER_PANEL_W - 2, ry + LAYER_ROW_H, 0x33353428);
                }
            }

            // Thumbnail
            int thumbX = px + LAYER_PANEL_PADDING;
            int thumbY = ry + (LAYER_ROW_H - LAYER_THUMB_SIZE) / 2;
            renderLayerThumbnail(g, n, thumbX, thumbY, LAYER_THUMB_SIZE);

            // Type icon + node name
            String typeIcon = switch (n.type) {
                case TEXT -> "T"; case DATA -> "D"; case IMAGE -> "I"; case IMAGE_SEQUENCE -> "S";
                case HUD_PITCH_LADDER -> "H"; default -> "?";
            };
            int labelX = thumbX + LAYER_THUMB_SIZE + LAYER_THUMB_MARGIN;
            int labelY = ry + 5;
            g.drawString(Minecraft.getInstance().font, typeIcon + " #" + n.id,
                labelX, labelY, isSel ? 0xFFFFFF88 : 0xFFCCCCCC, false);

            // Color swatch for TEXT/DATA
            if ((n.type == NodeType.TEXT || n.type == NodeType.DATA) && n.textColor != 0) {
                int swatchX = px + LAYER_PANEL_W - LAYER_PANEL_PADDING - 12;
                int swatchY = ry + 4;
                g.fill(swatchX, swatchY, swatchX + 10, swatchY + 8, n.textColor);
                g.renderOutline(swatchX, swatchY, 10, 8, 0xFF666666);
            }
        }

        // ── Render dragged ghost row following cursor (on top of everything) ──
        if (layerDragState == LayerDragState.DRAGGING && layerDragNode != null) {
            int ghostY = (int)(my - LAYER_ROW_H / 2.0);
            // Clamp within visible row area
            ghostY = Math.max(rowStartY, Math.min(ghostY, rowStartY + visibleRows * LAYER_ROW_H - LAYER_ROW_H));
            g.fill(px + 2, ghostY, px + LAYER_PANEL_W - 2, ghostY + LAYER_ROW_H, 0xBB3A3A38);
            g.renderOutline(px + 2, ghostY, LAYER_PANEL_W - 4, LAYER_ROW_H, EditorTheme.get().accent());
            int ghostThumbX = px + LAYER_PANEL_PADDING;
            int ghostThumbY = ghostY + (LAYER_ROW_H - LAYER_THUMB_SIZE) / 2;
            renderLayerThumbnail(g, layerDragNode, ghostThumbX, ghostThumbY, LAYER_THUMB_SIZE);
            String ghostIcon = switch (layerDragNode.type) {
                case TEXT -> "T"; case DATA -> "D"; case IMAGE -> "I"; case IMAGE_SEQUENCE -> "S";
                case HUD_PITCH_LADDER -> "H"; default -> "?";
            };
            int ghostLabelX = ghostThumbX + LAYER_THUMB_SIZE + LAYER_THUMB_MARGIN;
            g.drawString(Minecraft.getInstance().font, ghostIcon + " #" + layerDragNode.id,
                ghostLabelX, ghostY + 5, 0xFFFFFFFF, false);
        }

        // ── Scrollbar ──
        if (maxScroll > 0) {
            int sbX = px + LAYER_PANEL_W - 8;
            int sbY = rowStartY;
            int sbH = visibleRows * LAYER_ROW_H;
            g.fill(sbX, sbY, sbX + 6, sbY + sbH, EditorTheme.get().panelBg());
            float thumbH = Math.max(20, (float) visibleRows / layers.size() * sbH);
            float thumbY = sbY + (float) layerScroll / maxScroll * (sbH - thumbH);
            g.fill(sbX + 1, (int) thumbY, sbX + 5, (int) (thumbY + thumbH), EditorTheme.get().sysBorder());
        }
    }

    /** Returns clicked layer index in full sorted list, or -1 if no hit */
    private int handleLayerPanelClick(double mx, double my) {
        int px = host.screenWidth() - LAYER_PANEL_W - LAYER_PANEL_PADDING;
        if (mx < px || mx > px + LAYER_PANEL_W) return -1;

        var graph = host.be() != null ? host.be().getNodeGraph() : new NodeGraph();
        List<GraphNode> layers = getDisplayLayers(graph);
        if (layers.isEmpty()) return -1;

        int titleH = 12;
        int rowStartY = GraphEditor.TOP_BAR_H + 4 + titleH + 2;
        int maxRows = Math.max(1, (host.screenHeight() - rowStartY - 4) / LAYER_ROW_H);
        if (my < rowStartY || my > rowStartY + maxRows * LAYER_ROW_H) return -1;

        int idx = layerScroll + (int)((my - rowStartY) / LAYER_ROW_H);
        if (idx < 0 || idx >= layers.size()) return -1;

        ed.selectedDisplayNode = layers.get(idx);
        return idx;
    }

    // ── Layer drag-and-drop helpers ──

    private void updateLayerDropIndex(double my) {
        var graph = host.be() != null ? host.be().getNodeGraph() : new NodeGraph();
        List<GraphNode> layers = getDisplayLayers(graph);
        if (layers.isEmpty()) return;

        int titleH = 12;
        int rowStartY = GraphEditor.TOP_BAR_H + 4 + titleH + 2;
        int maxRows = Math.max(1, (host.screenHeight() - rowStartY - 4) / LAYER_ROW_H);
        int visibleRows = Math.min(layers.size(), maxRows);

        // Walk through ACTUAL visible rows (not empty virtual slots):
        // if mouse is below a row's center, drop advances to after that row
        int targetIdx = layerScroll;
        for (int vi = 0; vi < visibleRows; vi++) {
            int rowCenterY = rowStartY + vi * LAYER_ROW_H + LAYER_ROW_H / 2;
            if (my > rowCenterY) {
                targetIdx = layerScroll + vi + 1;
            }
        }
        // If mouse is below the last visible row, drop at the very end
        float lastRowBottom = rowStartY + visibleRows * LAYER_ROW_H;
        if (my > lastRowBottom) {
            targetIdx = layerScroll + visibleRows;
        }
        targetIdx = Math.max(0, Math.min(layers.size(), targetIdx));
        if (targetIdx != layerDropIndex) {
            layerDropIndex = targetIdx;
        }
    }

    private void handleLayerAutoScroll(double my) {
        int titleH = 12;
        int rowStartY = GraphEditor.TOP_BAR_H + 4 + titleH + 2;
        int maxRows = Math.max(1, (host.screenHeight() - rowStartY - 4) / LAYER_ROW_H);
        int panelBottom = rowStartY + maxRows * LAYER_ROW_H;
        long now = System.currentTimeMillis();
        if (now - lastAutoScrollTime < LAYER_AUTOSCROLL_TICK) return;

        var graph = host.be() != null ? host.be().getNodeGraph() : new NodeGraph();
        List<GraphNode> layers = getDisplayLayers(graph);
        if (layers.isEmpty()) return;

        int maxScroll = Math.max(0, layers.size() - maxRows);
        if (my < rowStartY + LAYER_AUTOSCROLL_ZONE && layerScroll > 0) {
            layerScroll = Math.max(0, layerScroll - 1);
            lastAutoScrollTime = now;
        } else if (my > panelBottom - LAYER_AUTOSCROLL_ZONE && layerScroll < maxScroll) {
            layerScroll = Math.min(maxScroll, layerScroll + 1);
            lastAutoScrollTime = now;
        }
    }

    void applyLayerReorder() {
        var graph = host.be() != null ? host.be().getNodeGraph() : new NodeGraph();
        if (layerDragNode == null) return;

        List<GraphNode> layers = getDisplayLayers(graph);
        int fromIdx = -1;
        for (int i = 0; i < layers.size(); i++) {
            if (layers.get(i).id == layerDragNode.id) { fromIdx = i; break; }
        }
        if (fromIdx < 0) return;

        int toIdx = layerDropIndex;
        if (toIdx > fromIdx) toIdx--;
        if (fromIdx == toIdx) return; // no movement

        // Remove dragged node then insert at target position
        GraphNode dragged = layers.remove(fromIdx);
        if (toIdx < 0) toIdx = 0;
        if (toIdx > layers.size()) toIdx = layers.size();
        layers.add(toIdx, dragged);

        // Reassign layerIndex: front (top) gets highest value, back gets lowest
        // Use nextLayerIndex as the base to avoid collisions
        int base = graph.nextLayerIndex + 1000;
        for (int i = 0; i < layers.size(); i++) {
            layers.get(i).layerIndex = base + (layers.size() - i);
        }
        // Update nextLayerIndex so future nodes appear in front
        graph.nextLayerIndex = base + layers.size() + 1;

        graph.bumpGeneration();
        // 定向同步图层序（不再全量上传）：每个节点的 layerIndex 各发一个 SET_LAYER_INDEX op，
        // 服务端应用并广播，不会用本客户端整图快照冲掉其他玩家的并发编辑。
        // Targeted layer-order sync (no full upload): one SET_LAYER_INDEX op per node; the
        // server applies and broadcasts them without clobbering other players' concurrent
        // edits with a whole-graph snapshot.
        var uid = host.playerUUID();
        for (var ln : layers) {
            sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setLayerIndex(
                host.blockPos(), -1, ln.id, ln.layerIndex, uid));
        }
    }

    void resetLayerDragState() {
        layerDragState = LayerDragState.IDLE;
        layerDragNode = null;
        layerDragOrigIndex = -1;
        layerDropIndex = -1;
        layerDragStartMy = 0;
        layerDragPressTime = 0;
    }
}
