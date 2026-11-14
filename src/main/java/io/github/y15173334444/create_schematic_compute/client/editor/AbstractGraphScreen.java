package io.github.y15173334444.create_schematic_compute.client.editor;

import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NodeAllowance;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.network.GraphEditOpPacket;
import io.github.y15173334444.create_schematic_compute.network.GraphJoinPacket;
import io.github.y15173334444.create_schematic_compute.network.GraphLeavePacket;
import io.github.y15173334444.create_schematic_compute.network.GraphSaveRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * 所有节点图编辑界面的公共基类 / Common base for all node-graph editor screens.
 *
 * <p>持有 BlockPos 与 GraphEditor，管理界面生命周期（加入/离开协作会话、
 * BE 失效自动关闭），并实现 {@link GraphEditor.Host} 的多人协作样板方法。
 * 取代此前 7 个 Screen 各自复制粘贴的 AbstractContainerScreen 样板。</p>
 * <p>Holds BlockPos + GraphEditor, manages the screen lifecycle (join/leave
 * collab session, auto-close when the BE is invalidated), and implements the
 * {@link GraphEditor.Host} boilerplate previously copy-pasted into all 7 screens.</p>
 */
public abstract class AbstractGraphScreen extends Screen implements GraphEditor.Host {

    /** 编辑目标方块坐标 / position of the block being edited */
    protected final BlockPos blockPos;
    /** 节点图编辑器 / node graph editor */
    protected final GraphEditor editor;

    protected AbstractGraphScreen(Component title, BlockPos pos) {
        super(title);
        this.blockPos = pos;
        this.editor = new GraphEditor(this, this);
    }

    /** 子类在构造器中调用，设置节点过滤器（子图等一次性谓词仍走这里）。
     *  Called from subclass constructors; one-off predicates (sub-graph) still use this. */
    protected void setNodeFilter(Predicate<NodeType> filter) {
        editor.setNodeFilter(filter);
    }

    /** 子类在构造器中调用，按分类白名单 + 类内黑名单设置节点准入。
     *  Called from subclass constructors: category allowlist + in-category exclusions. */
    protected void setNodeAllowance(NodeAllowance allowance) {
        editor.setNodeFilter(allowance::allows);
        // D5 名单制（批 D）：宿主键随名单一并下发——菜单 CUSTOM 段与服务端准入门同源。
        // The D5 roster (batch D): the host key rides the allowance — the menu's CUSTOM
        // section and the server gate share one source.
        editor.setCustomRosterHost(allowance.hostKey());
    }

    // ── Screen 生命周期 / lifecycle ──

    /** 编辑器界面不暂停单机游戏：v1.2.4.1 的容器界面（AbstractContainerScreen）默认不暂停，
     *  v1.2.5 迁移到纯 Screen 后继承了「打开即暂停」的默认行为——单机里打开编辑器会冻结
     *  整个世界（方块图全部停 tick）。这里恢复容器界面语义。
     *  Editor screens must not pause the singleplayer game: v1.2.4.1's container screens
     *  (AbstractContainerScreen) don't pause by default; the v1.2.5 migration to plain Screen
     *  inherited the pause-on-open default — freezing the whole world (block graphs stop
     *  ticking) while an editor is open in singleplayer. Restore the container semantics. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        super.init();
        // 加入多人协作编辑会话 / join collaborative editing session
        PacketDistributor.sendToServer(new GraphJoinPacket(blockPos));
    }

    @Override
    public void tick() {
        // BE 失效（方块被破坏 / 被卸载）时自动关闭，与迁移前 containerTick 行为一致
        // auto-close when the BE is gone (block broken / chunk unloaded) — same as pre-migration containerTick
        if (!isBlockEntityValid()) {
            onClose();
            return;
        }
        editor.clientTick();
    }

    @Override
    public void onClose() {
        preClose();                               // 子类钩子：关界面前把 EditBox 输入写回 BE 等 / subclass hook: write EditBox input back to the BE etc.
        var be = getBE();
        if (be != null) be.setPendingLocalOps(0);   // 复位本地编辑 op 守卫（对应 5892caa 的 pendingLocalOps 修复）/ reset pending-op guard
        editor.onClose();                         // 保存临时视角书签 / save temporary view bookmark
        editor.clearRemotePresences();
        // 离开多人协作编辑会话 / leave collaborative editing session
        // （像素编辑器转移时跳过：屏幕被独立 PixelEditorScreen 替换，但会话必须保持——
        //  像素编辑器屏不 join/leave，关闭后重建本屏再正常 join，幂等）
        // (skipped during pixel-editor transfer: the screen is replaced by the standalone
        //  PixelEditorScreen but the session must stay alive — the pixel-editor screen never
        //  joins/leaves; the rebuilt screen re-joins idempotently afterwards)
        if (!skipLeaveOnClose())
            PacketDistributor.sendToServer(new GraphLeavePacket(blockPos));
        super.onClose();
    }

    /** 关闭时是否跳过离开协作会话（像素编辑器转移用）。默认 false。
     *  Whether to skip leaving the edit session on close (pixel-editor transfer). Default false. */
    protected boolean skipLeaveOnClose() { return false; }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // PERF-7 帧采样：帧间隔在 render 入口测才是真实帧率（方案 §3.2）；开关关闭时
        // 只有两次长整型赋值。/ PERF-7 frame sampling: the delta is measured at render()
        // entry — that is the real frame rate (plan §3.2); while off, the cost is two longs.
        long perfNow = System.nanoTime();
        if (perfLastFrameNs != 0)
            perfFrameNs[perfFrameIdx++ % perfFrameNs.length] = perfNow - perfLastFrameNs;
        perfLastFrameNs = perfNow;
        this.renderBackground(g, mx, my, pt);     // 背景层 / background layer
        long perfT0 = perfStatsOn ? System.nanoTime() : 0L;
        renderGraphCanvas(g, mx, my, pt);         // 节点画布 / graph canvas
        long perfCanvasNs = perfStatsOn ? System.nanoTime() - perfT0 : 0L;
        for (var r : this.renderables)            // EditBox 等 widget 在画布之上（复刻 AbstractContainerScreen 契约）/ widgets above the canvas
            r.render(g, mx, my, pt);
        if (perfStatsOn) drawPerfStats(g, perfCanvasNs); // 采样 HUD：widget 之上、控制台之下（控制台仍是最上层）/ HUD above widgets, below the console (the console stays topmost)
        // 控制台叠加层：最新/最上层 → 画在全部 widget 之后（规格 §4.1 render 钩子；未打开时零开销）。
        // Console overlay: the newest/topmost layer → drawn after every widget (spec §4.1's
        // render hook; zero cost while closed).
        editor.renderConsoleOverlay(g, mx, my);
        // tooltip 由 GraphEditor/NodeRenderer 自行绘制（AbstractContainerScreen.renderTooltip 仅画槽位提示，无槽位界面下为空操作）
        // tooltips are drawn by GraphEditor/NodeRenderer themselves; AbstractContainerScreen.renderTooltip only draws slot tips (no-op without slots)
    }

    /**
     * 节点画布渲染钩子。默认委托给 {@link GraphEditor#renderBg}；
     * 子类可覆盖以叠加自定义绘制（如 RadarScreen 的工具栏、MonitorScreen 的显示模式画布）。
     * Canvas rendering hook. Defaults to {@link GraphEditor#renderBg};
     * subclasses may override to layer custom drawing on top (radar toolbar, monitor display mode).
     */
    protected void renderGraphCanvas(GuiGraphics g, int mx, int my, float pt) {
        editor.renderBg(g, mx, my);
    }

    // ── PERF-7 渲染采样叠加层（docs/graph-editor-render-performance-plan.md §3.2）──
    //    只读测量 + 自绘 HUD：不改渲染路径、不在此做优化决策。F9 切换、F10 生成测量
    //    场景（GraphPerfScene）。仅开关位 static（跨界面重建存活）；采样缓冲每屏自持。
    // ── PERF-7 render sampling overlay (plan doc §3.2) ──
    //    Read-only measurement + self-drawn HUD: the render path is untouched and no
    //    optimization decision is made here. F9 toggles; F10 builds a measurement scene
    //    (GraphPerfScene). Only the toggle is static (survives screen rebuilds); the
    //    sampling buffers are per-screen.
    /** F9 调试开关 / F9 debug toggle. */
    private static boolean perfStatsOn = false;
    private long perfLastFrameNs;
    private final long[] perfFrameNs = new long[240];
    private int perfFrameIdx;

    /** 叠加层：FPS / 帧时间 avg·p95·max（240 帧环）/ canvas 耗时 / 节点·连线数 / 缩放。
     *  落点 TOP_BAR_H+24：让开顶栏（0–22）与顶置工具栏（24–42）。
     *  Overlay: FPS / frame avg·p95·max (240-frame ring) / canvas cost / node·wire counts /
     *  zoom. Placed at TOP_BAR_H+24: clears the top bar (0–22) and the top toolbar (24–42). */
    private void drawPerfStats(GuiGraphics g, long canvasNs) {
        int n = Math.min(perfFrameIdx, perfFrameNs.length);
        if (n == 0) return;
        long[] s = new long[n];
        long sum = 0, max = 0;
        for (int i = 0; i < n; i++) {
            long v = perfFrameNs[i];
            s[i] = v; sum += v; if (v > max) max = v;
        }
        java.util.Arrays.sort(s);
        // 就近秩法 p95（环形数组乱序无碍——统计量与顺序无关）。/ Nearest-rank p95 (ring
        // order is irrelevant — the statistic is order-independent).
        long p95 = s[(int) Math.min(n - 1, (long) Math.ceil(n * 0.95) - 1)];
        double avgMs = sum / (double) n / 1e6;
        var font = Minecraft.getInstance().font;
        var graph = editor.getGraph();
        String l1 = String.format(java.util.Locale.ROOT,
            "[CSC Perf] FPS %.1f | frame avg %.2f p95 %.2f max %.2f ms",
            avgMs > 0 ? 1000.0 / avgMs : 0.0, avgMs, p95 / 1e6, max / 1e6);
        String l2 = String.format(java.util.Locale.ROOT,
            "canvas %.2f ms | nodes %d | wires %d | zoom %.2f",
            canvasNs / 1e6, graph.nodes.size(), graph.connections.size(), editor.zoom);
        int bw = Math.max(font.width(l1), font.width(l2)) + 8;
        int by = GraphEditor.TOP_BAR_H + 24;
        g.fill(0, by, bw, by + 22, 0x90000000);
        g.drawString(font, l1, 4, by + 3, 0xFFFFFF00, false);
        g.drawString(font, l2, 4, by + 13, 0xFFFFFF00, false);
    }

    // ── 输入事件委托（所有子类共享）/ input delegation shared by all subclasses ──

    @Override public boolean mouseClicked(double mx, double my, int btn) { return editor.mouseClicked(mx, my, btn) || super.mouseClicked(mx, my, btn); }
    @Override public boolean mouseReleased(double mx, double my, int btn) { editor.mouseReleased(mx, my, btn); return super.mouseReleased(mx, my, btn); }
    @Override public void mouseMoved(double mx, double my) { editor.mouseMoved(mx, my); }
    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return editor.mouseDragged(mx, my, btn, dx, dy) || super.mouseDragged(mx, my, btn, dx, dy); }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) { return editor.mouseScrolled(mx, my, sx, sy); }
    @Override public boolean keyPressed(int key, int sc, int mod) {
        // PERF-7 调试键拦在 editor.keyPressed 之前（方案 §3.2：编辑器守卫链先吞键，调试键
        // 不进 EditorKeys 序列引擎）。控制台打开时让位——模态叠加层优先吞键（规格 §4.2）。
        // PERF-7 debug keys intercepted BEFORE editor.keyPressed (plan §3.2: the editor guard
        // chain consumes keys first; debug keys must not enter the EditorKeys sequence
        // engine). While the console is open it wins — the modal overlay eats keys first
        // (spec §4.2).
        if (!editor.console.isOpen()) {
            if (key == 298) { perfStatsOn = !perfStatsOn; return true; }   // GLFW_KEY_F9
            if (key == 299) { GraphPerfScene.cycle(editor); return true; } // GLFW_KEY_F10 (300=F11 会被原版全屏抢先)
        }
        if (editor.keyPressed(key, sc, mod)) return true;
        if (key == 256) { onClose(); return true; } // ESC
        if (key >= 32 && key <= 96) return true;     // 可打印字符交给 charTyped / printable keys go to charTyped
        return super.keyPressed(key, sc, mod);
    }
    @Override public boolean keyReleased(int key, int sc, int mod) { return editor.keyReleased(key, sc, mod) || super.keyReleased(key, sc, mod); }
    @Override public boolean charTyped(char ch, int mod) { return editor.charTyped(ch, mod) || super.charTyped(ch, mod); }

    // ── GraphEditor.Host 多人协作 / multiplayer collaboration ──

    @Override public BlockPos getBlockPos() { return blockPos; }
    @Override public java.util.Map<Integer, Boolean> peekSubStateFlipflops(int encapNodeId) {
        // 反向包边解环 2026-10-31：编辑器经 Host 查询取子图触发器徽标状态，本类答自
        // getBE() 窄视图（blocks.GraphBlockEntity 实现同名方法）。 / Reverse package-edge
        // cycle break: the editor queries the host; we answer via the getBE() narrow view.
        var be = getBE();
        return be != null ? be.peekSubStateFlipflops(encapNodeId) : null;
    }
    @Override public UUID getPlayerUUID() {
        return Minecraft.getInstance().player != null
            ? Minecraft.getInstance().player.getUUID() : UUID.randomUUID();
    }
    @Override public GraphEditor getEditor() { return editor; }
    @Override public String getPlayerName() {
        return Minecraft.getInstance().player != null
            ? Minecraft.getInstance().player.getName().getString() : "";
    }
    @Override public Screen asScreen() { return this; }
    @Override public void sendOp(GraphOp op) {
        var be = getBE();
        if (be != null) be.setPendingLocalOps(be.getPendingLocalOps() + 1);     // 本地编辑 op 计数（回弹保护）/ count local edit op
        PacketDistributor.sendToServer(new GraphEditOpPacket(op));
    }
    @Override public void onRemoteOp(GraphOp op) { editor.onRemoteOp(op); }

    /**
     * 保存/编译：只向服务端发**保存请求**，由服务端在自己的权威图上落盘（环检测 +
     * 全量同步；时序状态完全持久——旧「编译复位触发器」语义已随 1.3.0 退役）。
     * 不再上传客户端整图快照 —— 那会在客户端副本过期时把服务端回退（issue #17）。
     * Save/compile: send a save <b>request</b> only; the server persists on its own
     * authoritative graph (cycle check + full sync; sequential state is fully persistent —
     * the old "compile resets flip-flops" semantics retired in 1.3.0). No client
     * full-graph upload — that rolled the server back whenever the client copy was
     * stale (issue #17).
     */
    @Override
    public void saveGraph() {
        var be = getBE();
        if (be == null) return;
        PacketDistributor.sendToServer(new GraphSaveRequestPacket(be.getBlockPos()));
        editor.saveFeedbackUntil = System.currentTimeMillis() + 1500;
    }

    // ── 子类覆盖点 / subclass hooks ──

    /**
     * BE 失效检查（方块被破坏 / 卸载时为 false），供 {@link #tick()} 自动关界面。
     * Validity check used by {@link #tick()} to auto-close; false once the block is gone.
     */
    protected abstract boolean isBlockEntityValid();

    /**
     * 返回当前客户端 BlockEntity 的编辑窄视图，供 sendOp / onClose 访问 pendingLocalOps 守卫。
     * 子类返回各自具体类型（协变返回即可；宿主 BE 均实现 {@link EditStateView}）。托管线
     * 为 {@code blocks.GraphBlockEntity}（含组合式 Kinetic 系方块实体 ProgrammableTransmission /
     * CncGearbox 等），经窄视图消费（反向包边解环 2026-10-31，E2 EditAckSink 手法）。
     * Current client-side BlockEntity edit view for the pendingLocalOps guard; subclasses
     * return their concrete type (covariant — every host BE implements {@link EditStateView}).
     * The hosting line is {@code blocks.GraphBlockEntity} (composition-based kinetic BEs
     * included), consumed through the narrow view (reverse package-edge cycle break,
     * 2026-10-31, the E2 EditAckSink playbook).
     */
    protected abstract EditStateView getBE();

    /**
     * 关界面前钩子：子类可在此把 EditBox 输入写回 BE（如 RadarScreen 的 applyInputs + hideInputs）。
     * 默认空实现。 / Pre-close hook; default no-op.
     */
    protected void preClose() {}
}
