package io.github.y15173334444.create_schematic_compute.blocks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 多人协作临场（presence）跟踪器（自 {@link GraphEditor} 拆分，docs/gui-decomposition-plan.md 步骤 6b）：
 * 远端玩家临场数据（GraphPresencePacket）的存储 / 过期清理（30 秒超时）/ 软锁查询
 * （节点被他人选中或编辑、显示布局组件被他人拖拽、方块名被他人改名）、本地临场数据的节流上报（120ms 间隔，
 * 模式感知——显示布局模式下光标与拖拽组件由 Host 提供；方块名焦点翻转绕过节流立即发送），以及节点图模式的
 * 协作叠加层渲染（远端光标 smoothstep 插值、远端拖拽悬线、右侧在线玩家列表）。
 * The multiplayer presence tracker (split out of {@link GraphEditor}, roadmap step 6b): stores
 * remote presences (GraphPresencePacket), expires stale ones (30 s), answers the soft-lock queries
 * (a node selected/edited by someone else, a display-layout component being dragged by someone
 * else, the block name being renamed by someone else), throttles the local presence upload (120 ms;
 * mode-aware — in display-layout mode the cursor and dragged component come from the Host; a
 * graph-name focus flip bypasses the throttle), and renders the graph-mode collaboration
 * overlay (smoothstep-lerped remote cursors, remote dragging wires, the online player list).
 *
 * <p><b>行为零变更</b>：方法体逐字搬迁。编辑器内部状态（选中集 / 连线拖拽 / 鼠标坐标 /
 * 坐标系转换 / 作用域 / 渲染几何）经传入的 {@code ed} 引用访问（同包，{@code lastMouseX/Y}
 * 放宽到包级）。
 * <b>Behaviour-preserving</b>: bodies moved verbatim. Editor internals (selection / wire drag /
 * mouse coords / coordinate transforms / scope / render geometry) are reached through the
 * passed-in {@code ed} reference (same package; {@code lastMouseX/Y} widened to package level).</p>
 *
 * <p>外部契约不变：包处理器经 {@code GraphEditor.storeRemotePresence}、显示器显示编辑器经
 * {@code editor().sendPresenceIfNeeded / cleanupStalePresences / getRemotePresences /
 * isDisplayNodeLocked}、屏幕关闭经 {@code editor().clearRemotePresences} 访问——
 * GraphEditor 保留全部薄委托，调用方零改动。
 * External contracts unchanged: the packet handler goes through {@code GraphEditor.storeRemotePresence},
 * the monitor display editor through {@code editor().sendPresenceIfNeeded / cleanupStalePresences /
 * getRemotePresences / isDisplayNodeLocked}, and screen teardown through {@code
 * editor().clearRemotePresences} — GraphEditor keeps all of these as thin delegates with zero
 * caller changes.</p>
 */
final class GraphPresenceTracker {

    private final GraphEditor ed;

    private final java.util.Map<java.util.UUID, io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket> remotePresences = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Long> remotePresenceTimestamps = new java.util.HashMap<>();
    // Cursor smoothstep lerp: {startX, startY, targetX, targetY, t}
    private final java.util.Map<java.util.UUID, float[]> cursorLerp = new java.util.HashMap<>();
    private long lastPresenceSendTime = 0;
    private static final long PRESENCE_INTERVAL_MS = 120;
    private static final long PRESENCE_TIMEOUT_MS = 30_000; // 30s timeout for disconnected players
    /** 上次上报的「方块名编辑中」状态（翻转时绕过节流立即发送）。 / Last-sent graph-name-editing state (a flip bypasses the throttle). */
    private boolean lastNameEditingSent = false;

    GraphPresenceTracker(GraphEditor ed) {
        this.ed = ed;
    }

    /** True if any remote player is currently editing the given node (pixel editor etc).
     *  owner = 当前作用域（-1=主图，>0=封装节点 ID）/ current scope (-1=main graph, >0=encap node ID). */
    public boolean isNodeLocked(int nodeId, int owner) {
        for (var p : remotePresences.values()) {
            if (p.ownerNodeId() != owner) continue;
            if (p.editingNodeId() == nodeId) return true;
            if (p.selectedNodeIds() != null) {
                for (int id : p.selectedNodeIds())
                    if (id == nodeId) return true;
            }
        }
        return false;
    }

    /** Store a remote player's presence. Called from packet handler.
     *  Empty playerName = player left → remove immediately. */
    public void storeRemotePresence(io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket pkt) {
        if (pkt.playerName() == null || pkt.playerName().isEmpty()) {
            remotePresences.remove(pkt.player());
            remotePresenceTimestamps.remove(pkt.player());
            cursorLerp.remove(pkt.player());
            return;
        }
        remotePresences.put(pkt.player(), pkt);
        remotePresenceTimestamps.put(pkt.player(), System.currentTimeMillis());
        float tx = ed.c2sX(pkt.cursorX()), ty = ed.c2sY(pkt.cursorY());
        var cl = cursorLerp.get(pkt.player());
        if (cl == null) {
            cursorLerp.put(pkt.player(), new float[]{tx, ty, tx, ty, 1f});
        } else {
            cl[2] = tx; cl[3] = ty; cl[4] = 0f; // target + reset t
            cl[0] = cl[0] + (tx - cl[0]) * 0.3f; // gentle start from current display
        }
    }

    /** Clear all remote presences (called when editor closes). */
    public void clearRemotePresences() {
        remotePresences.clear();
        remotePresenceTimestamps.clear();
        cursorLerp.clear();
    }

    /** 远端临场数据访问器（显示器布局界面的协作叠加层用）。
     *  Accessor for remote presences (used by the monitor screen's display-mode overlay). */
    public java.util.Map<java.util.UUID, io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket> getRemotePresences() {
        return remotePresences;
    }

    /** 封装占用判定：是否有其他玩家（节点编辑模式）正以 {@code encapId} 为所在作用域
     *  —— 删除封装节点前的守卫依据（与节点软锁同为建议性 presence 数据，服务端是纯中继）。
     *  Encapsulation occupancy: is any other player (node-editor mode) rooted inside
     *  {@code encapId} — the guard input for deleting an encapsulation node (same advisory
     *  presence trust level as the node soft lock; the server is a pure relay). */
    static boolean encapOccupied(java.util.Map<java.util.UUID, io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket> presences, int encapId) {
        if (encapId <= 0) return false;
        for (var p : presences.values()) {
            // 只认节点编辑模式（mode 0）；显示布局模式的 ownerNodeId 语义不同。
            // Node-editor mode only (mode 0); ownerNodeId means something else in display mode.
            if (p.mode() == 0 && p.ownerNodeId() == encapId) return true;
        }
        return false;
    }

    /** 实例便捷入口：按当前远端临场表判定。 / Instance convenience over the current table. */
    boolean encapOccupied(int encapId) { return encapOccupied(remotePresences, encapId); }

    /** 显示布局组件的软锁：是否有其他玩家正在显示布局模式拖拽该组件。
     *  Display-layout component soft lock: is another player dragging this component
     *  in the display layout editor right now? */
    public boolean isDisplayNodeLocked(int nodeId) {
        for (var p : remotePresences.values()) {
            if (p.mode() == 1 && p.displayDraggedNodeId() == nodeId) return true;
        }
        return false;
    }

    /** 方块名软锁：是否有其他玩家正在编辑顶栏的方块名框。
     *  Graph-name soft lock: is another player editing the top-bar block-name box right now? */
    public boolean isGraphNameLocked() {
        for (var p : remotePresences.values())
            if (p.editingGraphName()) return true;
        return false;
    }

    /** 正在编辑方块名的那位玩家（无人编辑时为 null）—— 供锁定提示显示。
     *  The player editing the block name (null when nobody is) — for the lock hint. */
    public String graphNameEditingBy() {
        for (var p : remotePresences.values())
            if (p.editingGraphName()) return p.playerName();
        return null;
    }

    /** Remove stale remote presences that haven't been updated within the timeout window.
     *  Public so the monitor screen's display-mode presence overlay can also clean up. */
    public void cleanupStalePresences() {
        long now = System.currentTimeMillis();
        var it = remotePresenceTimestamps.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (now - e.getValue() > PRESENCE_TIMEOUT_MS) {
                remotePresences.remove(e.getKey());
                cursorLerp.remove(e.getKey());
                it.remove();
            }
        }
    }

    /** Check if a node is selected/edited by another player in the same scope (soft lock).
     *  owner = 当前作用域（-1=主图，>0=封装节点 ID）/ current scope (-1=main graph, >0=encap node ID). */
    boolean isNodeLockedByOther(int nodeId, int owner) {
        for (var rp : remotePresences.values()) {
            if (rp.ownerNodeId() != owner) continue;
            if (rp.selectedNodeId() == nodeId || rp.editingNodeId() == nodeId) return true;
            if (rp.selectedNodeIds() != null) {
                for (int id : rp.selectedNodeIds())
                    if (id == nodeId) return true;
            }
        }
        return false;
    }

    /** Send local presence to server (throttled). Called from mouseMoved and — for the monitor
     *  display layout editor — from MonitorScreen.renderGraphCanvas so presence keeps flowing
     *  in display mode too (the graph-mode renderBg does not run there).
     *  发送本地临场数据到服务端（节流）。由 mouseMoved 调用；显示器布局模式下由
     *  MonitorScreen.renderGraphCanvas 调用，保证显示模式也持续发送。 */
    public void sendPresenceIfNeeded() {
        long now = System.currentTimeMillis();
        // 方块名焦点翻转绕过节流立即发送 —— 键盘编辑不产生 mouseMoved，锁必须及时广播与释放。
        // A graph-name focus flip bypasses the throttle — keyboard editing never fires
        // mouseMoved, so the lock must broadcast and release immediately.
        boolean nameEdit = ed.isGraphNameEditing();
        boolean force = nameEdit != lastNameEditingSent;
        lastNameEditingSent = nameEdit;
        if (!force && now - lastPresenceSendTime < PRESENCE_INTERVAL_MS) return;
        lastPresenceSendTime = now;
        int selId = ed.selectedNode != null ? ed.selectedNode.id : -1;
        int editId = (ed.selectedNode != null && ed.expandedNodeIds.contains(ed.selectedNode.id)) ? ed.selectedNode.id : -1;
        int wfn = ed.draggingWire ? ed.wireFromNode : -1;
        int wfp = ed.draggingWire ? ed.wireFromPin : -1;
        float wex = ed.draggingWire ? ed.wireEndX : 0;
        float wey = ed.draggingWire ? ed.wireEndY : 0;
        // Collect all selected node IDs for multi-select lock display
        int[] selIds = ed.selectedNodes.stream().mapToInt(n -> n.id).toArray();
        // 编辑模式感知：显示布局模式下光标与拖拽节点由 Host 提供
        // Mode-aware presence: in the display layout editor the cursor and dragged node come from the Host
        int mode = ed.host.getPresenceMode();
        float pcx = ed.host.getPresenceCursorX();
        float pcy = ed.host.getPresenceCursorY();
        float cx = pcx >= 0 ? pcx : ed.s2cX(ed.lastMouseX);
        float cy = pcy >= 0 ? pcy : ed.s2cY(ed.lastMouseY);
        int dragId = ed.host.getPresenceDraggedNodeId();
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new io.github.y15173334444.create_schematic_compute.network.GraphPresencePacket(
                ed.host.getBlockPos(), ed.host.getPlayerUUID(), ed.host.getPlayerName(),
                ed.ownerNodeId(), cx, cy,
                selId, editId, wfn, wfp, wex, wey, selIds, (byte)mode, dragId, nameEdit));
    }

    /** Render remote cursors and online player list. Called from renderBg + MonitorScreen.displayMode.
     *  渲染远程光标和在线玩家列表。由 renderBg 和 MonitorScreen.displayMode 调用。
     *  <p>
     *  Draws remote player cursors with smoothstep interpolation, remote dragging wires,
     *  and a player list overlay on the right side. Stale presences (>30s) are cleaned up.
     *  使用 smoothstep 插值绘制远程玩家光标、远程拖拽中的连线，以及右侧的玩家列表叠加层。
     *  过期（>30 秒）的在线状态会被清理。
     *  @param g GuiGraphics 渲染上下文 / rendering context */
    public void renderPresenceOverlay(GuiGraphics g) {
        cleanupStalePresences();
        if (remotePresences.isEmpty()) return;
        var mc = Minecraft.getInstance();
        int sw = ed.host.asScreen().width;
        // Render remote dragging wires (same scope only)
        for (var e : remotePresences.entrySet()) {
            var p = e.getValue();
            if (p.wireFromNode() < 0) continue;
            if (p.ownerNodeId() != ed.ownerNodeId()) continue; // 不同作用域不画 / skip different scopes
            var graph = ed.getGraph();
            var fn = graph.findNode(p.wireFromNode());
            if (fn == null) continue;
            int h = p.player().hashCode();
            int color = 0xFF000000 | (((h >> 16) & 0xFF) << 16) | (((h >> 8) & 0xFF) << 8) | (h & 0xFF) | 0xFF000000;
            float fromX = ed.c2sX(fn.x + io.github.y15173334444.create_schematic_compute.blocks.NodeRenderer.nw(fn));
            float fromY = ed.c2sY(fn.y + NodeRenderer.HH + NodeRenderer.PH * (fn.functionalInputs() + p.wireFromPin()) + NodeRenderer.PH / 2f);
            float toX = ed.c2sX(p.wireEndX()), toY = ed.c2sY(p.wireEndY());
            float dx = Math.abs(toX - fromX) * 0.4f;
            float dist = (float)Math.sqrt((toX-fromX)*(toX-fromX)+(toY-fromY)*(toY-fromY));
            int steps = Math.max(10, (int)(dist * 0.15f));
            float px = fromX, py = fromY;
            for (int i = 1; i <= steps; i++) {
                float t = i / (float)steps, inv = 1 - t;
                float nx = inv*inv*inv*fromX + 3*inv*inv*t*(fromX+dx) + 3*inv*t*t*(toX-dx) + t*t*t*toX;
                float ny = inv*inv*inv*fromY + 3*inv*inv*t*fromY + 3*inv*t*t*toY + t*t*t*toY;
                int sdx = (int)nx - (int)px, sdy = (int)ny - (int)py;
                int segLen = Math.max(Math.abs(sdx), Math.abs(sdy));
                if (segLen == 0) g.fill((int)px, (int)py, (int)px + 1, (int)py + 1, color);
                else {
                    int runStart = (int)px, runY = (int)py;
                    for (int j = 1; j <= segLen; j++) {
                        int cx2 = (int)px + sdx * j / segLen;
                        int cy2 = (int)py + sdy * j / segLen;
                        if (cy2 != runY || j == segLen) {
                            int endX = j == segLen ? (int)nx : (int)px + sdx * (j - 1) / segLen;
                            int x1 = Math.min(runStart, endX), x2 = Math.max(runStart, endX);
                            g.fill(x1, runY, x2 + 1, runY + 1, color);
                            runStart = cx2; runY = cy2;
                        }
                    }
                }
                px = nx; py = ny;
            }
        }
        // Render remote cursors (same scope only; skip display-layout presences — the monitor
        // screen renders those on its own display-area overlay)
        // 渲染远端光标（仅同作用域；跳过显示布局的临场数据——由显示器界面自行渲染）
        for (var e : remotePresences.entrySet()) {
            var p = e.getValue();
            if (p.ownerNodeId() != ed.ownerNodeId()) continue; // 不同作用域不显示光标 / skip different scopes
            if (p.mode() == 1) continue; // 显示布局模式的光标由 MonitorScreen 渲染 / display-mode cursors render on MonitorScreen
            var cl = cursorLerp.get(p.player());
            if (cl == null) { cl = new float[]{0,0,0,0,1f}; cursorLerp.put(p.player(), cl); }
            // Smoothstep cursor lerp (same algorithm as node move)
            if (cl[4] < 1f) {
                cl[4] = Math.min(1f, cl[4] + 0.1f);
                float t2 = cl[4] * cl[4] * (3f - 2f * cl[4]);
                cl[0] = cl[0] + (cl[2] - cl[0]) * t2 * 0.5f + (cl[2] - cl[0]) * 0.15f;
                cl[1] = cl[1] + (cl[3] - cl[1]) * t2 * 0.5f + (cl[3] - cl[1]) * 0.15f;
            }
            float sx = cl[0], sy = cl[1]; // render from lerped position
            if (sx < -20 || sx > sw + 20 || sy < -20 || sy > ed.host.asScreen().height + 20) continue;
            int h = p.player().hashCode();
            int color = 0xFF000000 | (((h >> 16) & 0xFF) << 16) | (((h >> 8) & 0xFF) << 8) | (h & 0xFF);
            g.fill((int)sx - 6, (int)sy - 1, (int)sx + 7, (int)sy, color);
            g.fill((int)sx - 1, (int)sy - 6, (int)sx, (int)sy + 7, color);
            g.drawString(mc.font, p.playerName(), (int)sx + 8, (int)sy - 4, color);
        }
        // Online player list — right side, below toolbar, vertical
        var players = new java.util.ArrayList<String>();
        players.add("● " + ed.host.getPlayerName());
        for (var p : remotePresences.values()) players.add(p.playerName());
        int maxW = 0;
        for (var name : players) maxW = Math.max(maxW, mc.font.width(name));
        int lx = sw - maxW - 14, ly = GraphEditor.TOP_BAR_H + 24;
        g.fill(lx, ly, sw - 6, ly + 2 + players.size() * 12, 0xAA222222);
        for (int i = 0; i < players.size(); i++) {
            int color = i == 0 ? 0xFFFFFF88 : 0xFFCCCCCC;
            g.drawString(mc.font, players.get(i), lx + 4, ly + 2 + i * 12, color);
        }
    }
}
