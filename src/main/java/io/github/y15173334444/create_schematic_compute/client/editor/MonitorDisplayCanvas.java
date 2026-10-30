package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

import static io.github.y15173334444.create_schematic_compute.client.GeometryConstants.*;
import static io.github.y15173334444.create_schematic_compute.client.editor.MonitorDisplayEditor.*;
import io.github.y15173334444.create_schematic_compute.client.EditorTheme;

/**
 * 全息显示器「显示编辑」GUI 的显示区画布（拓扑审查 §三 P7 三分，自 MonitorDisplayEditor 逐字迁出）：
 * 显示区整帧渲染（网格/元素/选中高亮/合并顶栏 + S/R 内联编辑/悬停提示）、显示区点击
 * （图层入口 → 顶栏 → S/R → 元素命中/孤儿重映射/软锁）与拖拽更新（节流流式布局 op）。
 * 几何（DisplayArea/getContentArea）与元素收集（collectDisplayElements）留守编辑器
 * （presence 叠加层共用），经委托取用、保持方法体逐字；共享状态（selectedDisplayNode /
 * draggedDisplayNode / editingS/R）仍在编辑器，接收者改为 ed.*。不建继承体系。
 * <p>The display-area canvas of the holographic monitor display-layout editor (topology
 * §三 P7 three-way split, relocated verbatim from MonitorDisplayEditor): the whole-frame
 * render (grid / elements / selection highlight / merged top bar with the S/R inline
 * editors / hover hints), the display-area click (layer entry → top bar → S/R → element
 * hit with orphan remap and soft locks) and the drag update (throttle-streamed layout
 * ops). The geometry (DisplayArea / getContentArea) and the element collection stay on
 * the editor (shared with the presence overlay) and are reached through delegates so the
 * method bodies stay verbatim; shared state (selectedDisplayNode / draggedDisplayNode /
 * editingS/R) also stays on the editor with ed.* receivers. No inheritance hierarchy.</p>
 */
final class MonitorDisplayCanvas {

    private final MonitorDisplayEditor ed;
    private final MonitorDisplayEditor.Host host;

    MonitorDisplayCanvas(MonitorDisplayEditor ed) {
        this.ed = ed;
        this.host = ed.host();
    }

    // ── 编辑器共享原语的委托（保持迁入方法体逐字）/ delegates to the editor's shared primitives ──

    private void sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp op) { ed.sendOp(op); }
    private java.util.Map<Integer, float[]> getEvalOutputs() { return ed.getEvalOutputs(); }
    private java.util.List<MonitorDisplayEditor.DisplayElement> collectDisplayElements(NodeGraph graph, java.util.Map<Integer, float[]> outputs) { return ed.collectDisplayElements(graph, outputs); }
    private static MonitorDisplayEditor.DisplayElement findInElements(java.util.List<MonitorDisplayEditor.DisplayElement> elements, int nodeId) { return MonitorDisplayEditor.findInElements(elements, nodeId); }
    private static float[] elemRotAABB(float ex, float ey, float w, float h, float rot) { return MonitorDisplayEditor.elemRotAABB(ex, ey, w, h, rot); }
    private float getEffectiveScreenW() { return ed.getEffectiveScreenW(); }
    private float getEffectiveScreenL() { return ed.getEffectiveScreenL(); }
    private MonitorDisplayEditor.DisplayArea computeDisplayArea() { return ed.computeDisplayArea(); }
    private int[] getContentArea(MonitorDisplayEditor.DisplayArea da) { return ed.getContentArea(da); }
    private float getContentWorldW() { return ed.getContentWorldW(); }
    static boolean advanceLayoutLerp(NodeGraph graph) { return MonitorDisplayEditor.advanceLayoutLerp(graph); }

    // ── Dragging state (display mode) ──
    /** 拖拽中的元素（共享：编辑器路由/接缝读写 → 留编辑器；此处经 ed.draggedDisplayNode）。 */
    private float dragOffX, dragOffY;
    // 显示布局拖拽的实时协作：节流流式发送 SET_DISPLAY_LAYOUT（与节点图 MOVE_NODE 同思路）
    // Live collaboration for display-layout drags: throttle-stream SET_DISPLAY_LAYOUT
    private long lastDisplayDragSendTime = 0;
    private static final long DISPLAY_DRAG_SEND_INTERVAL_MS = 100;

    // ── Phase 2: Display area render cache ──
    private int lastDisplayGen = -1;
    private float lastDisplaySW = -1, lastDisplaySL = -1;
    private java.util.List<MonitorDisplayEditor.DisplayElement> cachedDisplayElements = null;
    private MonitorDisplayEditor.DisplayArea cachedDisplayArea = null;

    // ════ 以下自 MonitorDisplayEditor 原样搬迁（接收者 ed.* 改写）/ verbatim-moved ════

    void renderDisplayArea(GuiGraphics g, int mx, int my) {
        var da = computeDisplayArea();
        int w = host.screenWidth(), h = host.screenHeight();

        // Dark background + grid (matching node editor style)
        g.fill(0, 0, w, h, 0xFF1F1E1A);
        // Placement guide grid aligned to the CONTENT (display) area so cells are exact
        // integers and the center is always locatable. 16 divisions = image-native resolution;
        // major lines every 4 cells; bold center cross at division 8/8.
        // 摆放辅助线绑定内容区：16 等分（与图像原生分辨率一致），每 4 格主线，8/8 处中心十字加粗。
        var gi = getContentArea(da);
        final int GDIV = 16;
        for (int gx = 0; gx <= GDIV; gx++) {
            int x = gi[0] + Math.round(gi[2] * (float) gx / GDIV);
            int c = (gx == GDIV / 2) ? 0xFF5A4D3A : (gx % 4 == 0 ? 0xFF3A3A3A : 0xFF2C2A24);
            g.fill(x, gi[1], x + 1, gi[1] + gi[3], c);
        }
        for (int gy = 0; gy <= GDIV; gy++) {
            int y = gi[1] + Math.round(gi[3] * (float) gy / GDIV);
            int c = (gy == GDIV / 2) ? 0xFF5A4D3A : (gy % 4 == 0 ? 0xFF3A3A3A : 0xFF2C2A24);
            g.fill(gi[0], y, gi[0] + gi[2], y + 1, c);
        }
        g.renderOutline(gi[0], gi[1], gi[2], gi[3], 0xFF5A4D3A);

        // Read server-authoritative evaluation outputs (synced via ClientboundGraphEvalPacket)
        var graph = host.be() != null ? host.be().getNodeGraph() : new NodeGraph();
        var evalOutputs = getEvalOutputs();

        // Collect and render display elements (cached when graph is static — Phase 2)
        // When running, output values change each tick so we must rebuild.
        boolean layoutLerpActive = advanceLayoutLerp(graph);
        boolean isRunning = host.be() != null && host.be().isRunning();
        float efsw = getEffectiveScreenW(), efsl = getEffectiveScreenL();
        int curGen = graph.graphGeneration;
        boolean displayChanged = curGen != lastDisplayGen || efsw != lastDisplaySW || efsl != lastDisplaySL
            || isRunning || ed.draggedDisplayNode != null || layoutLerpActive;
        if (displayChanged || cachedDisplayElements == null) {
            lastDisplayGen = curGen; lastDisplaySW = efsw; lastDisplaySL = efsl;
            cachedDisplayElements = collectDisplayElements(graph, evalOutputs);
            cachedDisplayArea = da;
        }
        var elements = cachedDisplayElements;
        var daCached = cachedDisplayArea != null ? cachedDisplayArea : da;
        // Dynamic guiScale: match world proportions
        // World: 1 font-pixel = 0.015 blocks. GUI: da.w() pixels maps to cw = screenWidth-0.08 blocks.
        // So: guiScale = 0.015 * da.w() / cw  (font-px → screen-px matching world scale)
        float cw = getContentWorldW();
        float guiScale = da.w() * FONT_BLOCK_SCALE / Math.max(cw, 0.01f);
        var ci = getContentArea(da);
        int contentX = ci[0], contentY = ci[1], contentW = ci[2], contentH = ci[3];
        var mc = Minecraft.getInstance();
        for (var elem : elements) {
            float s = guiScale * elem.scale();

            // Compute element content size in local (unscaled) coords.
            // In the world: 1 IMAGE pixel = 0.03 blocks = 2 font-pixels (since 1 font-px = 0.015 blocks).
            // In the GUI: each IMAGE pixel = 2 font-pixels (cellSize=2), total 32 font-pixels.
            float elemW = (elem.type() == NodeType.IMAGE || elem.type() == NodeType.IMAGE_SEQUENCE) ? elem.imgW() * IMAGE_CELL_FONT
                : elem.type() == NodeType.HUD_PITCH_LADDER ? 72
                : Minecraft.getInstance().font.width(elem.text().isEmpty() && elem.type() != NodeType.DATA ? " " :
                    elem.type() == NodeType.DATA ? ff1(elem.value()) : elem.text());
            float elemH = (elem.type() == NodeType.IMAGE || elem.type() == NodeType.IMAGE_SEQUENCE) ? elem.imgH() * IMAGE_CELL_FONT
                : elem.type() == NodeType.HUD_PITCH_LADDER ? 48 : 10;
            // Clamp using same rotated-AABB calculation as the yellow selection outline
            float ex = contentX + elem.x() * contentW;
            float ey = contentY + elem.y() * contentH;
            float ew = elemW * s, eh = elemH * s;
            float[] bb = elemRotAABB(ex, ey, ew, eh, elem.rotation());
            // Clamp to content area (matching 3D renderer's cx/cw bounds), not display area
            float dl = contentX, dr = contentX + contentW, dt = contentY, db = contentY + contentH;
            if (bb[2] > dr) ex -= (bb[2] - dr);
            if (bb[3] > db) ey -= (bb[3] - db);
            if (bb[0] < dl) ex += (dl - bb[0]);
            if (bb[1] < dt) ey += (dt - bb[1]);
            // Center-based rotation: translate to screen center → rotate → translate back → scale
            var pose = g.pose();
            pose.pushPose();
            pose.translate(ex + elemW * s / 2, ey + elemH * s / 2, 0);
            pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(elem.rotation()));
            pose.scale(s, s, 1);
            pose.translate(-elemW / 2, -elemH / 2, 0);

            switch (elem.type()) {
                case TEXT -> {
                    String text = elem.text().isEmpty() ? I18n.get("gui.create_schematic_compute.text_placeholder") : elem.text();
                    g.drawString(Minecraft.getInstance().font, text, 0, 0, elem.color(), false);
                }
                case DATA -> {
                    String dataStr = ff1(elem.value());
                    g.drawString(Minecraft.getInstance().font, dataStr, 0, 0, elem.color(), false);
                }
                case IMAGE, IMAGE_SEQUENCE -> {
                    if (elem.pixels() != null) {
                        ed.renderPixels(g, elem.pixels(), 0, 0, 2, elem.imgW(), elem.imgH());
                    }
                }
                case HUD_PITCH_LADDER -> {
                    // 固定相机模拟预览：正对面板中心，刻度按 tan(θ) 分布（示意共形俯仰梯）
                    // Fixed-camera mock preview: ticks spread by tan(θ) (conformal ladder sketch)
                    float halfWpx = elemW * 0.5f, halfHpx = elemH * 0.5f;
                    for (int deg = 5; deg <= 45; deg += 5) {
                        float y = (float)(Math.tan(Math.toRadians(deg)) / Math.tan(Math.toRadians(45))) * halfHpx * 0.85f;
                        g.hLine(0, (int)-y, (int)elemW, 0x55FFFFFF); // 上刻度 / upper ticks
                        g.hLine(0, (int)y, (int)elemW, 0x55FFFFFF);  // 下刻度 / lower ticks
                    }
                    g.hLine(0, (int)(halfHpx * 0.02f), (int)elemW, 0xFF88E866); // 地平线：高亮 / horizon: highlight
                    g.fill((int)(elemW / 2 - 3), (int)(halfHpx * 0.02f - 1), (int)(elemW / 2 + 3), (int)(halfHpx * 0.02f + 1), 0xFF33CC66); // 姿态标记 / marker
                }
            }
            pose.popPose();
        }
        // ── Selection highlight: draw AFTER all elements so it's always on top ──
        if (ed.selectedDisplayNode != null) {
            for (var elem : elements) {
                if (ed.selectedDisplayNode.id != elem.nodeId()) continue;
                float s = guiScale * elem.scale();
                float elemW = (elem.type() == NodeType.IMAGE || elem.type() == NodeType.IMAGE_SEQUENCE) ? elem.imgW() * IMAGE_CELL_FONT
                    : elem.type() == NodeType.HUD_PITCH_LADDER ? 72
                    : Minecraft.getInstance().font.width(elem.text().isEmpty() && elem.type() != NodeType.DATA ? " " :
                        elem.type() == NodeType.DATA ? ff1(elem.value()) : elem.text());
                float elemH = (elem.type() == NodeType.IMAGE || elem.type() == NodeType.IMAGE_SEQUENCE) ? elem.imgH() * IMAGE_CELL_FONT
                    : elem.type() == NodeType.HUD_PITCH_LADDER ? 48 : 10;
                float ex = contentX + elem.x() * contentW;
                float ey = contentY + elem.y() * contentH;
                float ew = elemW * s, eh = elemH * s;
                float[] bb = elemRotAABB(ex, ey, ew, eh, elem.rotation());
                float dl = contentX, dr = contentX + contentW, dt = contentY, db = contentY + contentH;
                if (bb[2] > dr) ex -= (bb[2] - dr);
                if (bb[3] > db) ey -= (bb[3] - db);
                if (bb[0] < dl) ex += (dl - bb[0]);
                if (bb[1] < dt) ey += (dt - bb[1]);
                var pose = g.pose();
                pose.pushPose();
                pose.translate(ex + elemW * s / 2, ey + elemH * s / 2, 0);
                pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(elem.rotation()));
                pose.scale(s, s, 1);
                pose.translate(-elemW / 2, -elemH / 2, 0);
                int hx = -1, hy = -1, hw = (int)elemW + 2, hh = (int)elemH + 2;
                g.renderOutline(hx, hy, hw, hh, EditorTheme.get().accent());
                pose.popPose();
                break;
            }
        }
        // ── 合并顶栏（显示模式；与图编辑器同一根条） ──
        // 同底色/同高度/右侧同款 46 宽按钮槽：左 = 模式标识 +（选中元素时）S/R 内联编辑；
        // 右 = 设置（面板开关）+ 返回（退出显示模式）。旧的 TOP_BAR_H+2 独立工具条并入这里，
        // 其占位行由显示区（computeDisplayArea）与图层面板收回。
        // ── Merged top bar (display mode; same bar as the graph editor's) ──
        // Same background/height/right-hand 46-wide button slots: left = mode indicator +
        // (when an element is selected) the S/R inline editors; right = Settings (panel
        // toggle) + Back (leave display mode). The old TOP_BAR_H+2 strip is merged in here
        // and its reserved row reclaimed by the display area and the layer panel.
        int sw = host.screenWidth();
        int backX = sw - 52, settingsX = backX - 52;
        g.fill(0, 0, sw, GraphEditor.TOP_BAR_H, NodeRenderer.withAlpha(EditorTheme.get().panelBg(), 0xEE));
        g.fill(0, GraphEditor.TOP_BAR_H - 1, sw, GraphEditor.TOP_BAR_H, EditorTheme.get().sysBorder());
        String modeText = "◈ " + I18n.get("gui.create_schematic_compute.monitor.display_mode") + " ◈";
        g.drawString(Minecraft.getInstance().font, modeText, 6, 8, 0xFFFFCC88, false);
        boolean hovBack = mx >= backX && mx <= backX + 46 && my >= 3 && my <= 19;
        g.fill(backX, 3, backX + 46, 19, hovBack ? EditorTheme.get().hover() : EditorTheme.get().panelBg());
        g.renderOutline(backX, 3, 46, 16, EditorTheme.get().sysBorder());
        g.drawString(Minecraft.getInstance().font, I18n.get("gui.create_schematic_compute.monitor.back_graph"), backX + 6, 7, 0xFFFFFFFF, false);
        boolean hovSettings = mx >= settingsX && mx <= settingsX + 46 && my >= 3 && my <= 19;
        g.fill(settingsX, 3, settingsX + 46, 19, ed.settingsOpen() ? 0xFF3A5A2A : (hovSettings ? EditorTheme.get().hover() : EditorTheme.get().panelBg()));
        g.renderOutline(settingsX, 3, 46, 16, EditorTheme.get().sysBorder());
        g.drawString(Minecraft.getInstance().font, I18n.get("gui.create_schematic_compute.monitor.settings"), settingsX + 6, 7, 0xFFFFFFFF, false);

        // Selected element editing (clickable S/R values)
        if (ed.selectedDisplayNode != null) {
            int srx = 6 + Minecraft.getInstance().font.width(modeText) + 12;
            String sTxt = "§6S:";
            if (ed.editingS) sTxt += "§e" + ed.editSBuf + "▌";
            else sTxt += "§e" + ff1(ed.selectedDisplayNode.displayScale);
            g.drawString(Minecraft.getInstance().font, sTxt, srx, 8, EditorTheme.get().accent(), false);
            srx += Minecraft.getInstance().font.width(sTxt) + 12;
            String rTxt = "§6R:";
            if (ed.editingR) rTxt += "§e" + ed.editRBuf + "▌";
            else rTxt += "§e" + ff0(ed.selectedDisplayNode.displayRotation);
            g.drawString(Minecraft.getInstance().font, rTxt, srx, 8, EditorTheme.get().accent(), false);
        }

        // Hover hints (use rotated AABB for accuracy, with bounding-box clamp)
        if (ed.selectedDisplayNode == null) {
            var font2 = Minecraft.getInstance().font;
            for (var elem : elements) {
                float s = guiScale * elem.scale();
                float hitW, hitH;
                if (elem.type() == NodeType.IMAGE || elem.type() == NodeType.IMAGE_SEQUENCE) {
                    hitW = elem.imgW() * IMAGE_CELL_FONT; hitH = elem.imgH() * IMAGE_CELL_FONT;
                } else {
                    String displayStr = elem.type() == NodeType.DATA
                        ? ff1(elem.value())
                        : (elem.text().isEmpty() ? " " : elem.text());
                    hitW = font2.width(displayStr);
                    hitH = 10;
                }
                float ex = contentX + elem.x() * contentW;
                float ey = contentY + elem.y() * contentH;
                float ew = hitW * s, eh = hitH * s;
                float[] bb = elemRotAABB(ex, ey, ew, eh, elem.rotation());
                float dl = contentX, dr = contentX + contentW, dt = contentY, db = contentY + contentH;
                if (bb[2] > dr) ex -= (bb[2] - dr);
                if (bb[3] > db) ey -= (bb[3] - db);
                if (bb[0] < dl) ex += (dl - bb[0]);
                if (bb[1] < dt) ey += (dt - bb[1]);
                var aabb = elemRotAABB(ex, ey, hitW * s, hitH * s, elem.rotation());
                if (mx >= aabb[0] && mx <= aabb[2] && my >= aabb[1] && my <= aabb[3]) {
                    g.renderOutline((int)aabb[0] - 1, (int)aabb[1] - 1,
                        (int)(aabb[2] - aabb[0]) + 2, (int)(aabb[3] - aabb[1]) + 2, 0xFF88AA44);
                    break;
                }
            }
        }
    }

    boolean handleDisplayAreaClick(double mx, double my, int btn) {
        if (btn == 0) {
            // 图层滚动条拇指按下优先（原在 MonitorScreen.mouseClicked 的显示分支里，
            // 且排在「图层面板行命中」之前——搬迁时保序）。
            // Layer-panel scrollbar thumb press comes first (it used to sit in
            // MonitorScreen.mouseClicked's display branch, ahead of the row hit-test).
            if (ed.layers().handleLayerScrollbarPress(mx, my)) return true;
            // 图层面板行命中 + 拖拽发起（同样原在屏幕侧显示分支里、滚动条之后）。拆分时此
            // 调用点被整块遗漏，handleLayerPanelClick 沦为无调用死方法：图层面板行点不了
            // （选中失效）也拖不动（PRESSED 状态永远不会置位）。按原始顺序补回到这里。
            // Layer-panel row hit + drag initiation (also used to sit in the screen's display
            // branch, right after the scrollbar press). The split dropped this call site
            // entirely, leaving handleLayerPanelClick dead: rows could neither be selected
            // (click) nor dragged (PRESSED was never set). Restored here in the original order.
            if (ed.layers().tryRowPress(mx, my)) return true;
            var da = computeDisplayArea();
            // 合并顶栏的按钮命中（几何与 renderDisplayArea 的顶栏分支一致）。
            // Merged-top-bar button hits (geometry matches renderDisplayArea's top bar).
            int backX = host.screenWidth() - 52, settingsX = backX - 52;
            // Back（退出显示模式）/ Back (leave display mode)
            if (mx >= backX && mx <= backX + 46 && my >= 3 && my <= 19)
                { ed.setActive(false); ed.selectedDisplayNode = null; return true; }
            // Settings
            if (mx >= settingsX && mx <= settingsX + 46 && my >= 3 && my <= 19)
                { ed.openSettings(); return true; }

            // 整图同步替换后 selectedDisplayNode 可能指向旧图的孤儿节点——按 id 重映射到当前图。
            // 否则随后的"已选元素优先检查"会把拖拽绑定到孤儿节点：本地实时更新落空（渲染冻结、
            // 松手才同步），而流式 op 仍携带孤儿位置使远端可见移动——"首次拖动正常、之后本地
            // 视觉不更新"的根因。
            // After a full-graph sync replacement selectedDisplayNode can point at an orphaned
            // node from the old graph — remap it by id to the current graph. Otherwise the
            // elevated hit check binds the next drag to the orphan: local live updates go
            // nowhere (frozen render, position lands on release) while the streamed ops carry
            // the orphan's position so remote clients still see movement.
            {
                var liveGraph = host.be() != null ? host.be().getNodeGraph() : null;
                if (ed.selectedDisplayNode != null && liveGraph != null) {
                    var curSel = liveGraph.findNode(ed.selectedDisplayNode.id);
                    if (curSel != ed.selectedDisplayNode) ed.selectedDisplayNode = curSel;
                }
            }

            // S/R editable value clicks（位置与顶栏渲染按同一表达式计算）
            // S/R editable value clicks (positions computed exactly like the top-bar render)
            if (ed.selectedDisplayNode != null) {
                var fw = Minecraft.getInstance().font;
                int sx = 6 + fw.width("◈ " + I18n.get("gui.create_schematic_compute.monitor.display_mode") + " ◈") + 12, sy = 3;
                String sVal = ed.editingS ? ed.editSBuf : ff1(ed.selectedDisplayNode.displayScale);
                int sEnd = sx + fw.width("§6S:§e" + sVal + "▌") + 12;
                if (mx >= sx && mx <= sEnd && my >= sy && my <= sy + 16) {
                    ed.editingS = true; ed.editingR = false; ed.editSBuf = ff1(ed.selectedDisplayNode.displayScale);
                    return true;
                }
                int rx = sEnd;
                String rVal = ed.editingR ? ed.editRBuf : ff0(ed.selectedDisplayNode.displayRotation);
                int rEnd = rx + fw.width("§6R:§e" + rVal + "▌") + 4;
                if (mx >= rx && mx <= rEnd && my >= sy && my <= sy + 16) {
                    ed.editingR = true; ed.editingS = false; ed.editRBuf = ff0(ed.selectedDisplayNode.displayRotation);
                    return true;
                }
            }

            // Check for display element hits (scaled to rendered size)
            var graph = host.be() != null ? host.be().getNodeGraph() : new NodeGraph();
            var evalOutputs4 = getEvalOutputs();
            var elements = collectDisplayElements(graph, evalOutputs4);
            float guiScale2 = da.w() * FONT_BLOCK_SCALE / Math.max(getContentWorldW(), 0.01f);
            var ci = getContentArea(da);
            int contentX = ci[0], contentY = ci[1], contentW = ci[2], contentH = ci[3];

            // Check selected element first (from layer panel) — elevated hit priority
            // 队友正在拖拽的组件跳过（软锁） / skip elements a teammate is dragging (soft lock)
            if (ed.selectedDisplayNode != null) {
                var selNode = ed.selectedDisplayNode;
                if (host.editor().isDisplayNodeLocked(selNode.id)) {
                    ed.selectedDisplayNode = null;
                } else {
                var selElem = findInElements(elements, selNode.id);
                if (selElem != null) {
                    float s2 = guiScale2 * selElem.scale();
                    float hw, hh;
                    var font3 = Minecraft.getInstance().font;
                    if (selElem.type() == NodeType.IMAGE || selElem.type() == NodeType.IMAGE_SEQUENCE) {
                        hw = selElem.imgW() * IMAGE_CELL_FONT; hh = selElem.imgH() * IMAGE_CELL_FONT;
                    } else if (selElem.type() == NodeType.DATA) {
                        String vs = ff1(selElem.value());
                        hw = font3.width(vs.isEmpty() ? "0.0" : vs); hh = 10;
                    } else {
                        hw = font3.width(selElem.text().isEmpty() ? " " : selElem.text()); hh = 10;
                    }
                    float sx = contentX + selElem.x() * contentW;
                    float sy = contentY + selElem.y() * contentH;
                    float[] bb2 = elemRotAABB(sx, sy, hw * s2, hh * s2, selElem.rotation());
                    if (mx >= bb2[0] && mx <= bb2[2] && my >= bb2[1] && my <= bb2[3]) {
                        ed.draggedDisplayNode = selNode;
                        lastDisplayDragSendTime = 0;
                        dragOffX = (float)(mx - sx);
                        dragOffY = (float)(my - sy);
                        return true;
                    }
                }
                }
            }
            for (int i = elements.size() - 1; i >= 0; i--) {
                var elem = elements.get(i);
                if (host.editor().isDisplayNodeLocked(elem.nodeId())) continue; // 软锁：队友拖拽中 / soft lock
                float s = guiScale2 * elem.scale();
                float hitW, hitH;
                var font2 = Minecraft.getInstance().font;
                if (elem.type() == NodeType.IMAGE || elem.type() == NodeType.IMAGE_SEQUENCE) {
                    hitW = elem.imgW() * IMAGE_CELL_FONT; hitH = elem.imgH() * IMAGE_CELL_FONT;
                } else if (elem.type() == NodeType.DATA) {
                    String valStr = ff1(elem.value());
                    hitW = font2.width(valStr.isEmpty() ? "0.0" : valStr);
                    hitH = 10;
                } else {
                    hitW = font2.width(elem.text().isEmpty() ? " " : elem.text());
                    hitH = 10;
                }
                // Clamp so full element stays in display area, then do hit test
                float ex = contentX + elem.x() * contentW;
                float ey = contentY + elem.y() * contentH;
                float ew = hitW * s, eh = hitH * s;
                float[] bb = elemRotAABB(ex, ey, ew, eh, elem.rotation());
                float dl = contentX, dr = contentX + contentW, dt = contentY, db = contentY + contentH;
                if (bb[2] > dr) ex -= (bb[2] - dr);
                if (bb[3] > db) ey -= (bb[3] - db);
                if (bb[0] < dl) ex += (dl - bb[0]);
                if (bb[1] < dt) ey += (dt - bb[1]);
                // Rotated AABB hit test (center-based)
                var aabb = elemRotAABB(ex, ey, hitW * s, hitH * s, elem.rotation());
                if (mx >= aabb[0] && mx <= aabb[2] && my >= aabb[1] && my <= aabb[3]) {
                    GraphNode hitNode = graph.findNode(elem.nodeId());
                    if (hitNode != null) {
                        ed.selectedDisplayNode = hitNode;
                        ed.draggedDisplayNode = hitNode;
                        lastDisplayDragSendTime = 0;
                        dragOffX = (float)(mx - ex);
                        dragOffY = (float)(my - ey);
                        return true;
                    }
                }
            }
            // No element hit — only drag the selected node if the press point is actually
            // inside its (clamped) AABB, never grab a stale selection. This fixes the
            // "select image 1, then drag image 2 → image 1 moves / image 2 doesn't follow" bug.
            // 未命中任何元素——仅当按下点落在已选节点（裁剪后的）AABB 内时才开拖，
            // 不再抓取陈旧选择（先点图1再拖图2 → 动的却是图1 的 bug）。
            if (ed.selectedDisplayNode != null && !host.editor().isDisplayNodeLocked(ed.selectedDisplayNode.id)) {
                var selNode = ed.selectedDisplayNode;
                var selElem = findInElements(elements, selNode.id);
                if (selElem != null) {
                    float s2 = guiScale2 * selElem.scale();
                    float hw2, hh2;
                    var font3 = Minecraft.getInstance().font;
                    if (selElem.type() == NodeType.IMAGE || selElem.type() == NodeType.IMAGE_SEQUENCE) {
                        hw2 = selElem.imgW() * IMAGE_CELL_FONT; hh2 = selElem.imgH() * IMAGE_CELL_FONT;
                    } else if (selElem.type() == NodeType.DATA) {
                        String vs = ff1(selElem.value());
                        hw2 = font3.width(vs.isEmpty() ? "0.0" : vs); hh2 = 10;
                    } else {
                        hw2 = font3.width(selElem.text().isEmpty() ? " " : selElem.text()); hh2 = 10;
                    }
                    float ex = contentX + selElem.x() * contentW;
                    float ey = contentY + selElem.y() * contentH;
                    // Apply the same full-AABB clamp as the draw path so the guard region
                    // matches the element's rendered position at the borders.
                    // 与绘制路径一致的全 AABB 裁剪，保证守卫区域与边框处实际渲染位置吻合。
                    float[] bb = elemRotAABB(ex, ey, hw2 * s2, hh2 * s2, selElem.rotation());
                    if (bb[2] > contentX + contentW) ex -= (bb[2] - (contentX + contentW));
                    if (bb[3] > contentY + contentH) ey -= (bb[3] - (contentY + contentH));
                    if (bb[0] < contentX) ex += (contentX - bb[0]);
                    if (bb[1] < contentY) ey += (contentY - bb[1]);
                    var aabb = elemRotAABB(ex, ey, hw2 * s2, hh2 * s2, selElem.rotation());
                    if (mx >= aabb[0] && mx <= aabb[2] && my >= aabb[1] && my <= aabb[3]) {
                        ed.draggedDisplayNode = selNode;
                        lastDisplayDragSendTime = 0;
                        dragOffX = (float)(mx - ex);
                        dragOffY = (float)(my - ey);
                        return true;
                    }
                }
            }
            ed.selectedDisplayNode = null;
        }
        return false;
    }

    /** 更新显示区拖拽位置 + 节流流式发送布局 op。
     *  必须在 mouseMoved 与 mouseDragged 中都调用：触屏设备拖动期间只产生
     *  mouseDragged 事件（无 mouseMoved），只挂在 mouseMoved 会导致触屏拖拽
     *  本地渲染冻结、松手才同步（鼠标则正常）。
     *  Update the display drag position + throttle-stream the layout op. MUST run from
     *  both mouseMoved and mouseDragged: touchscreens only emit mouseDragged during a
     *  drag (no mouseMoved), so updating only in mouseMoved froze the local render on
     *  touch until release (mouse worked fine). */
    void updateDisplayDrag(double mx, double my) {
        var da = computeDisplayArea();
        float gsD = da.w() * FONT_BLOCK_SCALE / Math.max(getContentWorldW(), 0.01f);
        float sD = gsD * ed.draggedDisplayNode.displayScale;
        float eW, eH;
        if (ed.draggedDisplayNode.type == NodeType.IMAGE || ed.draggedDisplayNode.type == NodeType.IMAGE_SEQUENCE) {
            eW = ed.draggedDisplayNode.imageWidth * IMAGE_CELL_FONT; eH = ed.draggedDisplayNode.imageHeight * IMAGE_CELL_FONT;
        } else {
            String ts = ed.draggedDisplayNode.type == NodeType.DATA
                ? ff1(0f)
                : (ed.draggedDisplayNode.displayText.isEmpty() ? " " : ed.draggedDisplayNode.displayText);
            eW = Minecraft.getInstance().font.width(ts); eH = 10;
        }
        var ciD = getContentArea(da);
        int cXD = ciD[0], cYD = ciD[1], cWD = ciD[2], cHD = ciD[3];
        float rawX = (float)(mx - cXD - dragOffX) / cWD;
        float rawY = (float)(my - cYD - dragOffY) / cHD;
        float exD = cXD + Math.max(0, Math.min(1, rawX)) * cWD;
        float eyD = cYD + Math.max(0, Math.min(1, rawY)) * cHD;
        float[] bbD = elemRotAABB(exD, eyD, eW * sD, eH * sD, ed.draggedDisplayNode.displayRotation);
        int drD = cXD + cWD, dbD = cYD + cHD;
        if (bbD[2] > drD) exD -= (bbD[2] - drD);
        if (bbD[3] > dbD) eyD -= (bbD[3] - dbD);
        if (bbD[0] < cXD) exD += (cXD - bbD[0]);
        if (bbD[1] < cYD) eyD += (cYD - bbD[1]);
        ed.draggedDisplayNode.layoutX = Math.max(0, Math.min(1, (exD - cXD) / cWD));
        ed.draggedDisplayNode.layoutY = Math.max(0, Math.min(1, (eyD - cYD) / cHD));
        // 实时协作：按节流流式发送布局 op，远端客户端实时看到拖拽（松手时另有最终 op）。
        // 顺带使拖拽期间 pendingLocalOps > 0，与整图同步守卫双重保护本地图不被替换。
        // Live collaboration: throttle-stream the layout op so remote clients see the
        // drag in real time (release sends the final op). Also keeps pendingLocalOps > 0
        // during the drag as a second line of defense for the full-sync guard.
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastDisplayDragSendTime >= DISPLAY_DRAG_SEND_INTERVAL_MS) {
            lastDisplayDragSendTime = nowMs;
            sendOp(io.github.y15173334444.create_schematic_compute.graph.GraphOp.setDisplayLayout(
                host.blockPos(), -1, ed.draggedDisplayNode.id,
                ed.draggedDisplayNode.layoutX, ed.draggedDisplayNode.layoutY,
                ed.draggedDisplayNode.displayScale, ed.draggedDisplayNode.displayRotation,
                ed.draggedDisplayNode.moveScale,
                host.playerUUID()));
        }
    }
}
