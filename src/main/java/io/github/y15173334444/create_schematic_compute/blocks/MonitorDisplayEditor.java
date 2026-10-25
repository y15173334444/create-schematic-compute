package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.client.GeometryConstants;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.github.y15173334444.create_schematic_compute.client.GeometryConstants.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.y15173334444.create_schematic_compute.client.EditorTheme;

/**
 * 全息显示器的「显示编辑」GUI / The holographic monitor display-layout editor.
 *
 * <p>自 {@link MonitorScreen} 拆出（docs/gui-decomposition-plan.md 步骤 2）：显示布局模式下的
 * 显示区渲染、图层面板、设置面板、拖拽与键鼠路由、协作存在包上报全部集中在本类；
 * {@code MonitorScreen} 只保留节点图模式，并在显示模式激活时把事件转发到本类。</p>
 * <p>Split out of {@link MonitorScreen} (roadmap step 2): everything the display-layout mode
 * draws or handles now lives here; {@code MonitorScreen} keeps the node-graph mode and
 * delegates to this class while display mode is active.</p>
 *
 * <p><b>行为零变更</b>：本次拆分是纯搬迁 + 机械引用重写——原来经由内部类隐式访问的屏幕状态
 * （{@code width}/{@code height}/{@code blockPos}/{@code editor}）改为经 {@link Host} 接缝获取。
 * 显示模式的工具栏条（&lt; Graph / Settings / S,R）仍由 {@code renderDisplayArea} 内部绘制，
 * 与原实现逐字一致；切换按钮依旧只在节点图模式绘制。</p>
 * <p><b>Behaviour-preserving</b>: a pure move with mechanical rewrites of the implicit
 * outer-class accesses into the {@link Host} seam. The display-mode toolbar strip (&lt; Graph /
 * Settings / S,R) is still drawn inside {@code renderDisplayArea}, verbatim from the original;
 * the display toggle button remains graph-mode-only, as before.</p>
 */
public final class MonitorDisplayEditor {
    /** 独立 logger：不引用 (root) 包的 @Mod 类——其静态初始化依赖 MC 注册表，
     *  而 LoggerFactory 不依赖注册表，headless/JUnit 下也能安全完成类初始化。日志名为本类 FQCN。
     *  Same-name standalone logger: never references the @Mod class — its static init needs
     *  the Minecraft registries, while LoggerFactory does not, so class init stays safe in
     *  plain JUnit too. The log name is this class's FQCN. */
    private static final Logger LOGGER = LoggerFactory.getLogger(MonitorDisplayEditor.class);

    /** 宿主：屏幕侧提供的最小接口 / Host: the minimal screen-side surface. */
    public interface Host {
        int screenWidth();
        int screenHeight();
        MonitorBlockEntity be();
        GraphEditor editor();
        BlockPos blockPos();
        void sendOp(GraphOp op);
        UUID playerUUID();
        /** mouseDragged 未被显示模式消费时的屏幕级兜底 / screen-level mouse fallthrough. */
        boolean screenMouseDragged(double mx, double my, int btn, double dx, double dy);
        /** 打开独立像素编辑器（v1.2.6+）/ open the standalone pixel editor. */
        void openPixelEditor(GraphNode node);
    }

    private final Host host;
    /** 图层面板协作对象（拓扑 P7 三分；构造器内装配——依赖已赋值的 host）。 */
    private final MonitorLayerPanel layers;
    /** 设置面板协作对象（拓扑 P7 三分；构造器内装配——依赖已赋值的 host）。 */
    private final MonitorSettingsPanel settings;
    /** 显示区画布协作对象（拓扑 P7 三分；构造器内装配——依赖已赋值的 host）。 */
    private final MonitorDisplayCanvas canvas;

    MonitorLayerPanel layers() { return layers; }
    MonitorDisplayCanvas canvas() { return canvas; }

    /** true = 屏幕处于显示布局模式 / the screen is in display-layout mode. */
    private boolean active = false;
    /** 打开像素编辑器时置位：本次 onClose 跳过离开协作会话 / set while transferring to the pixel editor. */
    private boolean transferToPixelEditor = false;

    /** 统一 op 出口：经 GraphEditor 的 temp-id 漏斗（host.sendOp 直连传输层会绕过挂起守卫）。
     *  回退分支仅在无编辑器时可达 —— 无编辑器即无编辑会话、也没有临时 id 在途，无挂起可守；
     *  生产宿主（MonitorScreen）始终提供编辑器。
     *  Unified op outlet: through the GraphEditor temp-id funnel (host.sendOp hits the transport
     *  directly and would bypass the pending-id guard). The fallback is reachable only without
     *  an editor — and without an editor there is no editing session and no temp id in flight,
     *  so there is no pending state to guard; the production host (MonitorScreen) always
     *  provides the editor. */
    void sendOp(GraphOp op) {
        var ed = host.editor();
        if (ed != null) ed.sendOp(op);
        else host.sendOp(op);
    }

    /** 面板协作对象取用的宿主接缝 / the host seam, reached by the panel collaborators. */
    Host host() { return host; }

    public MonitorDisplayEditor(Host host) {
        this.host = host;
        this.layers = new MonitorLayerPanel(this);
        this.settings = new MonitorSettingsPanel(this);
        this.canvas = new MonitorDisplayCanvas(this);
    }

    // ── 屏幕侧接缝 / screen-side seam ──

    public boolean active() { return active; }
    public void setActive(boolean v) { active = v; }
    public boolean settingsOpen() { return settings.isOpen(); }
    public void openSettings() { settings.open(); }
    public void closeSettings() { settings.close(); }
    /** 转移标记消费一次即复位 / consume the transfer flag once. */
    public boolean consumeSkipLeave() { boolean t = transferToPixelEditor; transferToPixelEditor = false; return t; }
    public void markPixelEditorTransfer() { transferToPixelEditor = true; }
    public boolean displayDragInProgress() { return draggedDisplayNode != null; }
    public int presenceMode() { return active ? 1 : 0; }
    public float presenceCursorX() { return active ? lastDisplayMouseX : -1f; }
    public float presenceCursorY() { return active ? lastDisplayMouseY : -1f; }
    public int presenceDraggedNodeId() { return draggedDisplayNode != null ? draggedDisplayNode.id : -1; }
    /** 当前选中元素的节点 id（-1 = 无）—— 经 presence 上报，锁跟随选择而非仅拖动。
     *  The selected element's node id (-1 = none) — reported via presence so the lock
     *  follows the selection, not just the drag. */
    public int presenceSelectedNodeId() { return selectedDisplayNode != null ? selectedDisplayNode.id : -1; }

    /** 显示区整帧渲染入口（屏幕 renderGraphCanvas 调用）/ display-area render entry. */
    public void render(GuiGraphics g, int mx, int my) {
        // 显示模式也持续发送存在包（节点图模式的 renderBg 不会在此运行）
        // Keep presence flowing in display mode (the graph-mode renderBg does not run here)
        host.editor().sendPresenceIfNeeded();
        canvas.renderDisplayArea(g, mx, my);
        layers.renderLayerPanel(g, mx, my);
        // 显示模式绘制显示区协作叠加层（队友光标 + 拖拽描边），不再绘制节点图光标
        // Display mode draws the display-area collaboration overlay instead of the graph overlay
        renderDisplayPresence(g);
    }

    /** 设置面板浮层（显示模式与节点图模式都要画）/ settings overlay. */
    public void renderSettingsOverlay(GuiGraphics g, int mx, int my) {
        if (settings.isOpen()) settings.renderSettingsPanel(g, mx, my);
    }

    // ── 输入路由 / input routing ──

    /** 显示模式鼠标点击。返回 true = 已消费。/ display-mode click. */
    public boolean handleClick(double mx, double my, int btn) {
        if (settings.isOpen()) return settings.handleSettingsClick(mx, my, btn);
        return canvas.handleDisplayAreaClick(mx, my, btn);
    }

    public void handleMouseMoved(double mx, double my) {
        if (layers.handleLayerScrollbarDrag(mx, my)) return;
        // 记录最近鼠标屏幕坐标（存在包用）/ track the last mouse position for the presence packet
        lastDisplayMouseX = (float) mx;
        lastDisplayMouseY = (float) my;
        // Layer drag-and-drop — handled here AND in mouseDragged
        // (Minecraft may call either depending on version/patches)
        if (layers.dragMove(my)) return;
        if (draggedDisplayNode != null) canvas.updateDisplayDrag(mx, my);
    }

    public boolean handleMouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (layers.dragMove(my)) return true;
        // Display-area dragging — updated in BOTH mouseDragged and mouseMoved (touchscreens
        // only emit mouseDragged during drags; without this the local render freezes on touch)
        if (draggedDisplayNode != null) { canvas.updateDisplayDrag(mx, my); return true; }
        return host.screenMouseDragged(mx, my, btn, dx, dy);
    }

    public boolean handleMouseReleased(double mx, double my) {
        if (layers.endScrollbarDrag()) return true;
        if (layers.isDragActive()) {
            layers.applyLayerReorder();
            layers.resetLayerDragState();
            draggedDisplayNode = null;
            return true;
        }
        if (layers.isPressed()) layers.resetLayerDragState();
        if (draggedDisplayNode != null) {
            sendOp(GraphOp.setDisplayLayout(
                host.blockPos(), -1, draggedDisplayNode.id,
                draggedDisplayNode.layoutX, draggedDisplayNode.layoutY,
                draggedDisplayNode.displayScale, draggedDisplayNode.displayRotation,
                draggedDisplayNode.moveScale, host.playerUUID()));
        }
        draggedDisplayNode = null;
        return true;
    }

    public boolean handleMouseScrolled(double mx, double sy) {
        if (layers.inPanelX(mx)) layers.scrollBy(sy);
        return true;
    }

    /** 键盘：返回 null 表示显示模式未消费（屏幕继续走自己的路径）。
     *  null = display mode did not consume the key; the screen continues its own path. */
    public Boolean handleKeyPressed(int key, int sc, int mod) {
        var sr = settings.tryKeyPressed(key, sc, mod);
        if (sr != null) return sr;
        // ── 仅设置面板打开（未处于显示布局模式）时，这里只允许"面板自己消化掉"的按键到此为止：
        // 其余按键必须交还屏幕 → 节点图编辑区，否则节点图的输入框会完全收不到按键
        // （设置面板在离开显示模式后并不关闭，所以只有显示器这个设备会持续触发）。
        // 这与原 MonitorScreen 的语义一致：原代码在 displayMode == false 时会继续往下走到
        // editor.keyPressed(...)，而拆分后这里曾无条件返回 true，把按键吞掉了。
        // When only the settings panel is open (not display mode), return null for anything the
        // panel did not consume so the screen can forward it to the node-graph editor. The panel
        // stays open after leaving display mode, which is why only the monitor misbehaved.
        if (!active) return null;
        // ESC cancels an in-progress layer drag
        if (key == 256 && layers.isDragging()) {
            layers.resetLayerDragState();
            return true;
        }
        if (editingS) {
            if (key == 256) { editingS = false; return true; }
            if (key == 257 || key == 335) {
                try { selectedDisplayNode.displayScale = Math.max(0.01f, Float.parseFloat(editSBuf)); }
                catch (Exception e) { LOGGER.debug("Hex input parse", e); }
                sendOp(GraphOp.setDisplayLayout(
                    host.blockPos(), -1, selectedDisplayNode.id,
                    selectedDisplayNode.layoutX, selectedDisplayNode.layoutY,
                    selectedDisplayNode.displayScale, selectedDisplayNode.displayRotation,
                    selectedDisplayNode.moveScale, host.playerUUID()));
                editingS = false; return true;
            }
            if (key == 259 && editSBuf.length() > 0) { editSBuf = editSBuf.substring(0, editSBuf.length() - 1); return true; }
            return true;
        }
        if (editingR) {
            if (key == 256) { editingR = false; return true; }
            if (key == 257 || key == 335) {
                try { selectedDisplayNode.displayRotation = Float.parseFloat(editRBuf) % 360f; }
                catch (Exception e) { LOGGER.debug("Hex input parse", e); }
                sendOp(GraphOp.setDisplayLayout(
                    host.blockPos(), -1, selectedDisplayNode.id,
                    selectedDisplayNode.layoutX, selectedDisplayNode.layoutY,
                    selectedDisplayNode.displayScale, selectedDisplayNode.displayRotation,
                    selectedDisplayNode.moveScale, host.playerUUID()));
                editingR = false; return true;
            }
            if (key == 259 && editRBuf.length() > 0) { editRBuf = editRBuf.substring(0, editRBuf.length() - 1); return true; }
            return true;
        }
        if (key == 256) { setActive(false); return true; } // ESC → back to the graph editor
        return true;
    }

    public boolean handleCharTyped(char ch, int mod) {
        var cr = settings.tryCharTyped(ch, mod);
        if (cr != null) return cr;
        if (editingS && (Character.isDigit(ch) || ch == '.' || ch == '-')) {
            if (editSBuf.length() < 8) editSBuf += ch; return true;
        }
        if (editingR && (Character.isDigit(ch) || ch == '.' || ch == '-')) {
            if (editRBuf.length() < 8) editRBuf += ch; return true;
        }
        return false;
    }

    /** 关界面前补发未落定的拖拽/图层排序（等不到 mouseReleased）。
     *  Flush in-progress display drags / layer reorders on close. */
    public void preClose() {
        if (host.be() == null) return;
        if (layers.isDragActive()) {
            layers.applyLayerReorder();
        }
        if (draggedDisplayNode != null) {
            sendOp(GraphOp.setDisplayLayout(
                host.blockPos(), -1, draggedDisplayNode.id,
                draggedDisplayNode.layoutX, draggedDisplayNode.layoutY,
                draggedDisplayNode.displayScale, draggedDisplayNode.displayRotation,
                draggedDisplayNode.moveScale, host.playerUUID()));
            draggedDisplayNode = null;
        }
    }

    // ════ 以下为自 MonitorScreen 原样搬迁的实现 / verbatim-moved implementation ══════
    // （图层面板一族已按拓扑 P7 三分迁出至 MonitorLayerPanel / the layer-panel family
    //   now lives in MonitorLayerPanel per the P7 three-way split）



    // ── Double-click tracking ──
    private long lastClickTime = 0;
    private int lastClickNodeId = -1;

    // ── Dragging state (display mode) ──
    /** 拖拽中的元素（包级：MonitorDisplayCanvas 读写；路由/接缝也在编辑器侧读写）。 */
    GraphNode draggedDisplayNode = null;
    /** 选中元素（包级：MonitorLayerPanel 行命中写、面板渲染读 / package: written by the
     *  layer panel's row hit, read by its render）。 */
    GraphNode selectedDisplayNode = null;
    // （dragOffX/Y 与拖拽节流状态已随拓扑 P7 迁至 MonitorDisplayCanvas /
    //  dragOffX/Y and the drag throttle moved to MonitorDisplayCanvas）
    // 显示模式下最近一次鼠标屏幕坐标（存在包用）
    // Last mouse screen position in display mode (for the presence packet)
    private float lastDisplayMouseX = -1, lastDisplayMouseY = -1;

    // ── Layer panel state ──（已按拓扑 P7 迁出至 MonitorLayerPanel / moved to MonitorLayerPanel）

    // ── Display mode inline editing ──（共享状态：画布点击写、键盘路由读写 → 包级 / shared:
    // written by the canvas click, read/written by the key router → package level）
    boolean editingS = false, editingR = false;
    String editSBuf = "", editRBuf = "";

    // ── Phase 2: Display area render cache ──（已随拓扑 P7 迁至 MonitorDisplayCanvas /
    //  moved to MonitorDisplayCanvas）

    // ── Settings panel state ──（已按拓扑 P7 迁出至 MonitorSettingsPanel / moved to MonitorSettingsPanel）

    // ── Fast number formatting to avoid String.format allocation in hot paths (Phase 1) ──
    public static String ff0(float v) { return Integer.toString(Math.round(v)); }
    public static String ff1(float v) { return Float.toString((float)Math.round(v * 10) / 10); }
    public static String ff2(float v) { return Float.toString((float)Math.round(v * 100) / 100); }
    public static String ff3(float v) { return Float.toString((float)Math.round(v * 1000) / 1000); }

    /** 每帧推进显示布局的远端插值（与节点模式 renderBg 的 remote* 同款 smoothstep，步进 0.12）。
     *  @return 是否仍有元素在插值中（调用方据此在本帧强制重建元素缓存——插值不 bump 代际，
     *  缓存若不强制重建会把插值吞掉）。
     *  Advances the display-layout remote interpolation per frame (the same smoothstep as
     *  the node mode's remote* pass, 0.12 per frame).
     *  @return true while any element is still interpolating (the caller force-rebuilds the
     *  element cache this frame — a lerp doesn't bump the generation, so without the forced
     *  rebuild the cache would swallow the interpolation). */
    static boolean advanceLayoutLerp(NodeGraph graph) {
        boolean active = false;
        for (var n : graph.nodes) {
            if (n.layoutLerpT < 1f) {
                n.layoutLerpT = Math.min(1f, n.layoutLerpT + 0.12f);
                float t = n.layoutLerpT * n.layoutLerpT * (3f - 2f * n.layoutLerpT);
                n.layoutX = n.layoutStartX + (n.layoutTargetX - n.layoutStartX) * t;
                n.layoutY = n.layoutStartY + (n.layoutTargetY - n.layoutStartY) * t;
                active = true;
            }
        }
        return active;
    }

    private void renderDisplayPresence(GuiGraphics g) {
        host.editor().cleanupStalePresences();
        var presences = host.editor().getRemotePresences();
        if (presences.isEmpty()) return;
        var mc = Minecraft.getInstance();
        var graph = host.be() != null ? host.be().getNodeGraph() : null;
        if (graph == null) return;
        var elements = collectDisplayElements(graph, getEvalOutputs());
        for (var p : presences.values()) {
            if (p.mode() != 1) continue; // 仅显示布局模式的临场数据 / display-layout presences only
            if (p.cursorX() < 0 || p.cursorY() < 0) continue; // 离开哨兵 / left sentinel
            int h = p.player().hashCode();
            int color = 0xFF000000 | (((h >> 16) & 0xFF) << 16) | (((h >> 8) & 0xFF) << 8) | (h & 0xFF);
            float sx = p.cursorX(), sy = p.cursorY();
            g.fill((int)sx - 6, (int)sy - 1, (int)sx + 7, (int)sy, color);
            g.fill((int)sx - 1, (int)sy - 6, (int)sx, (int)sy + 7, color);
            g.drawString(mc.font, p.playerName(), (int)sx + 8, (int)sy - 4, color);
            // 队友正在拖拽**或选中**的元素：彩色描边（软锁视觉）+ 名字标注。
            // 可视化与 isDisplayNodeLocked 的判定保持一致（锁跟随选择）——只画拖拽会让
            // 「选中占锁」在队友屏幕上不可见，看起来像无锁可抢。
            // Element a teammate is dragging OR has selected: colored outline (soft-lock
            // visual) + name tag. The visual must match isDisplayNodeLocked (the lock
            // follows the selection) — drawing only the drag would make a selection-held
            // lock invisible, looking grabbable.
            int lockedId = p.displayDraggedNodeId() >= 0 ? p.displayDraggedNodeId() : p.selectedNodeId();
            if (lockedId >= 0) {
                var elem = findInElements(elements, lockedId);
                if (elem != null) {
                    var da = computeDisplayArea();
                    var ci = getContentArea(da);
                    float guiScale = da.w * FONT_BLOCK_SCALE / Math.max(getContentWorldW(), 0.01f);
                    float s = guiScale * elem.scale;
                    float ew = (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE)
                        ? elem.imgW * IMAGE_CELL_FONT
                        : Minecraft.getInstance().font.width(elem.text.isEmpty() ? " " : elem.text);
                    float eh = (elem.type == NodeType.IMAGE || elem.type == NodeType.IMAGE_SEQUENCE)
                        ? elem.imgH * IMAGE_CELL_FONT : 10;
                    float ex = ci[0] + elem.x * ci[2];
                    float ey = ci[1] + elem.y * ci[3];
                    float[] bb = elemRotAABB(ex, ey, ew * s, eh * s, elem.rotation);
                    int dr = ci[0] + ci[2], db = ci[1] + ci[3];
                    if (bb[2] > dr) ex -= (bb[2] - dr);
                    if (bb[3] > db) ey -= (bb[3] - db);
                    if (bb[0] < ci[0]) ex += (ci[0] - bb[0]);
                    if (bb[1] < ci[1]) ey += (ci[1] - bb[1]);
                    var aabb = elemRotAABB(ex, ey, ew * s, eh * s, elem.rotation);
                    g.renderOutline((int)aabb[0] - 1, (int)aabb[1] - 1,
                        (int)(aabb[2] - aabb[0]) + 2, (int)(aabb[3] - aabb[1]) + 2, color);
                    g.drawString(mc.font, "§o" + p.playerName(), (int)aabb[0], (int)aabb[1] - 10, color);
                }
            }
        }
    }

    // ── Display area rendering ──（渲染/点击/拖拽三大方法已随拓扑 P7 迁至 MonitorDisplayCanvas；
    //  几何与元素收集留守本类，presence 叠加层共用 / the three big methods live in
    //  MonitorDisplayCanvas; the geometry and element collection stay here, shared with
    //  the presence overlay）
    record DisplayArea(int x, int y, int w, int h) {}
    DisplayArea computeDisplayArea() {
        int margin = MONITOR_MARGIN;
        // 模式条并入顶栏后，显示区只让出顶栏本体 + 4px 边距（原工具条占位行已收回）。
        // With the strip merged into the top bar, the display area clears only the bar
        // itself + a 4px margin (the old toolbar row is reclaimed).
        int topOffset = GraphEditor.TOP_BAR_H + 4;
        int availW = host.screenWidth() - 2 * margin;
        int availH = host.screenHeight() - margin - topOffset;
        float aspect = 16f / 9f;
        MonitorBlockEntity mbe = host.be();
        if (mbe != null && mbe.screenLength > 0.001f)
            aspect = mbe.screenWidth / mbe.screenLength;
        int dw, dh;
        if (availW / aspect <= availH) { dw = availW; dh = (int)(availW / aspect); }
        else { dh = availH; dw = (int)(availH * aspect); }
        return new DisplayArea((host.screenWidth() - dw) / 2, (host.screenHeight() - dh) / 2 + topOffset / 2, dw, dh);
    }

    /** Get effective screen dimensions, using preview overrides when settings panel is open */
    float getEffectiveScreenW() { return settings.previewW() >= 0 ? settings.previewW() : (host.be() != null ? host.be().screenWidth : 1.5f); }
    float getEffectiveScreenL() { return settings.previewL() >= 0 ? settings.previewL() : (host.be() != null ? host.be().screenLength : 1.2f); }

    /** Compute content area insets matching the 3D renderer's 0.04-block bezel margin.
     *  Returns {contentX, contentY, contentW, contentH} within the given DisplayArea. */
    int[] getContentArea(DisplayArea da) {
        float sw = getEffectiveScreenW();
        float sl = getEffectiveScreenL();
        float mfX = BEZEL_MARGIN / Math.max(sw, 0.01f);
        float mfY = BEZEL_MARGIN / Math.max(sl, 0.01f);
        int ix = Math.round(da.w * mfX);
        int iy = Math.round(da.h * mfY);
        return new int[]{da.x + ix, da.y + iy, da.w - 2 * ix, da.h - 2 * iy};
    }

    /** Get the world-space content width (screenWidth - 2*margin) for guiScale computation */
    float getContentWorldW() { return Math.max(getEffectiveScreenW() - (2 * BEZEL_MARGIN), 0.01f); }

    /** Read evaluation outputs from the server-authoritative snapshot (synced via ClientboundGraphEvalPacket).
     *  包级：MonitorLayerPanel 缩略图取值 / package: also read by MonitorLayerPanel's thumbnails. */
    Map<Integer, float[]> getEvalOutputs() {
        var be = host.be();
        if (be == null) return java.util.Collections.emptyMap();
        return be.getCachedEvalSnapshot().outputs();
    }

    // renderDisplayArea 已随拓扑 P7 迁至 MonitorDisplayCanvas（体逐字）/ moved verbatim to MonitorDisplayCanvas

    // ── Layer panel ──（渲染/命中/拖拽排序一族已按拓扑 P7 迁出至 MonitorLayerPanel /
    //                   the render / hit / drag-reorder family lives in MonitorLayerPanel）

    /** Returns clicked layer index in full sorted list, or -1 if no hit */
    // （行命中与拖拽排序助手已按拓扑 P7 迁出至 MonitorLayerPanel /
    //   the row-hit and drag-reorder helpers live in MonitorLayerPanel）

    /** 设置面板点击（显示模式由 handleClick 转发；节点图模式下面板打开时由屏幕直接路由——
     *  本公开签名是 MonitorScreen 的既有接缝，冻结为门面）。
     *  Settings-panel click: forwarded by handleClick in display mode, routed directly by
     *  the screen when only the panel is open — this public signature is MonitorScreen's
     *  existing seam, frozen as a facade. */
    public boolean handleSettingsClick(double mx, double my, int btn) {
        return settings.handleSettingsClick(mx, my, btn);
    }

    // ── Settings panel ──（渲染/保存/复选框/点击一族已按拓扑 P7 迁出至 MonitorSettingsPanel /
    //                      the render / save / checkbox / click family lives in MonitorSettingsPanel）

    record DisplayElement(int nodeId, NodeType type, String text, float value, int[] pixels,
        String label, float x, float y, float scale, float rotation, int color, int imgW, int imgH) {}

    static DisplayElement findInElements(java.util.List<DisplayElement> elements, int nodeId) {
        for (var e : elements) if (e.nodeId == nodeId) return e;
        return null;
    }

    java.util.List<DisplayElement> collectDisplayElements(NodeGraph graph, Map<Integer, float[]> outputs) {
        var list = new java.util.ArrayList<DisplayElement>();
        for (var n : graph.nodes) {
            switch (n.type) {
                case TEXT -> {
                    int tc = n.textColor != 0 ? n.textColor : 0xFFCCCCCC;
                    list.add(new DisplayElement(n.id, n.type, n.displayText, 0, null, "", n.layoutX, n.layoutY, n.displayScale, n.displayRotation, tc, 0, 0));
                }
                case DATA -> {
                    float val = graph.getInputValue(n.id, 0, outputs);
                    String lbl = n.params.length > 0 ? ff3(n.params[0]) : "val";
                    int dc = n.textColor != 0 ? n.textColor : 0xFF88FF88;
                    list.add(new DisplayElement(n.id, n.type, "", val, null, lbl, n.layoutX, n.layoutY, n.displayScale, n.displayRotation, dc, 0, 0));
                }
                case IMAGE -> {
                    float ox = graph.getInputValue(n.id, 0, outputs);
                    float oy = graph.getInputValue(n.id, 1, outputs);
                    float rotIn = graph.getInputValue(n.id, 2, outputs);
                    float msX = n.params.length > 0 ? n.params[0] : 0.01f;
                    float msY = n.params.length > 1 ? n.params[1] : 0.01f;
                    float rotScale = n.params.length > 2 ? n.params[2] : 1f;
                    boolean invX = n.params.length > 3 && n.params[3] > 0.5f;
                    boolean invY = n.params.length > 4 && n.params[4] > 0.5f;
                    float dx = ox * (invX ? -msX : msX);
                    float dy = oy * (invY ? -msY : msY);
                    float effRot = n.displayRotation + rotIn * rotScale;
                    float[] cp = clampImageNorm(n, n.layoutX + dx, n.layoutY + dy, effRot);
                    list.add(new DisplayElement(n.id, n.type, "", 0, n.imagePixels, "", cp[0], cp[1], n.displayScale, effRot, 0, n.imageWidth, n.imageHeight));
                }
                case IMAGE_SEQUENCE -> {
                    float ox = graph.getInputValue(n.id, 0, outputs);
                    float oy = graph.getInputValue(n.id, 1, outputs);
                    int frameIdx = Math.round(graph.getInputValue(n.id, 2, outputs));
                    float rotIn = graph.getInputValue(n.id, 3, outputs);
                    float msX = n.params.length > 0 ? n.params[0] : 0.01f;
                    float msY = n.params.length > 1 ? n.params[1] : 0.01f;
                    float rotScale = n.params.length > 2 ? n.params[2] : 1f;
                    boolean invX = n.params.length > 3 && n.params[3] > 0.5f;
                    boolean invY = n.params.length > 4 && n.params[4] > 0.5f;
                    float dx = ox * (invX ? -msX : msX);
                    float dy = oy * (invY ? -msY : msY);
                    float effRot = n.displayRotation + rotIn * rotScale;
                    int[] pixels = null;
                    if (n.imageSequenceFrames != null && !n.imageSequenceFrames.isEmpty()) {
                        frameIdx = Math.max(0, Math.min(frameIdx, n.imageSequenceFrames.size() - 1));
                        pixels = n.imageSequenceFrames.get(frameIdx);
                    }
                    float[] cp = clampImageNorm(n, n.layoutX + dx, n.layoutY + dy, effRot);
                    list.add(new DisplayElement(n.id, n.type, "", 0, pixels, "", cp[0], cp[1], n.displayScale, effRot, 0, n.imageWidth, n.imageHeight));
                }
                case HUD_PITCH_LADDER -> {
                    // 共形俯仰梯：编辑器里用固定相机模拟预览（layout 作符号组偏移，默认居中）
                    // Conformal pitch ladder: fixed-camera mock preview in the editor (layout = group offset)
                    list.add(new DisplayElement(n.id, n.type, "", 0, null, "", n.layoutX, n.layoutY, n.displayScale, 0, 0, 0, 0));
                }
            }
        }
        list.sort((a, b) -> {
            GraphNode na = graph.findNode(a.nodeId()), nb = graph.findNode(b.nodeId());
            int la = na != null ? na.layerIndex : 0;
            int lb = nb != null ? nb.layerIndex : 0;
            return Integer.compare(lb, la); // descending: higher layerIndex = front = rendered last
        });
        return list;
    }

    /** Clamp IMAGE normalized position using rotated-AABB-aware bounds,
     *  matching MonitorBlockEntityRenderer's clamping (lines 124-133).
     *  使用旋转 AABB 感知边界裁剪 IMAGE 归一化位置，
     *  与 MonitorBlockEntityRenderer 的裁剪逻辑一致（第 124-133 行）。 */
    /** Clamp IMAGE normalized position — delegates to shared GeometryConstants.
     *  裁剪 IMAGE 归一化位置 — 委托给共享的 GeometryConstants。 */
    private float[] clampImageNorm(GraphNode n, float rawX, float rawY, float rotation) {
        return GeometryConstants.clampImageNorm(n.displayScale, rawX, rawY, rotation,
            getEffectiveScreenW(), getEffectiveScreenL(), n.imageWidth, n.imageHeight);
    }

    /** Compute rotated AABB — delegates to shared GeometryConstants. */
    static float[] elemRotAABB(float ex, float ey, float w, float h, float rot) {
        return GeometryConstants.elemRotAABB(ex, ey, w, h, rot);
    }

    /** 包级：MonitorLayerPanel 缩略图共用 / package: shared with MonitorLayerPanel's thumbnails. */
    void renderPixels(GuiGraphics g, int[] pixels, int x, int y, int cellSize, int gridW, int gridH) {
        for (int py = 0; py < gridH; py++) {
            for (int px = 0; px < gridW; px++) {
                int idx = py * gridW + px;
                if (idx < pixels.length) {
                    int color = pixels[idx];
                    // Skip fully transparent pixels so background shows through
                    if ((color >>> 24) != 0) {
                        g.fill(x + px * cellSize, y + py * cellSize,
                            x + (px + 1) * cellSize, y + (py + 1) * cellSize,
                            color);
                    }
                }
            }
        }
    }

    /** 显示区拖拽是否进行中（整图同步守卫用）。 */
public boolean isDisplayDragInProgress() { return draggedDisplayNode != null; }

    /** 存在包编辑模式：显示布局模式下为 1。 */
public int getPresenceMode() { return active ? 1 : 0; }
    /** 显示布局模式下的光标屏幕 X；节点图模式返回 -1 走图光标。 */
public float getPresenceCursorX() { return active ? lastDisplayMouseX : -1f; }
    /** 显示布局模式下的光标屏幕 Y；节点图模式返回 -1 走图光标。 */
public float getPresenceCursorY() { return active ? lastDisplayMouseY : -1f; }
    /** 显示布局编辑器中正在拖拽的节点 id。 */
public int getPresenceDraggedNodeId() { return draggedDisplayNode != null ? draggedDisplayNode.id : -1; }

    // handleDisplayAreaClick 已随拓扑 P7 迁至 MonitorDisplayCanvas（体逐字）/ moved verbatim to MonitorDisplayCanvas

    // updateDisplayDrag 已随拓扑 P7 迁至 MonitorDisplayCanvas（体逐字）/ moved verbatim to MonitorDisplayCanvas
}



