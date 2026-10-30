package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import io.github.y15173334444.create_schematic_compute.client.editor.GraphEditor;

/**
 * CHANNEL 布局切换的连线语义（v1.2.6.2「连线回退」修复）。
 * 布局预设变更 = 输出引脚集合整体替换（mix ↔ l/r/…）：旧声道 pinId 上的连线按设计剪除；
 * 服务端 SET_PARAM 路径负责剪（本测试第一段锚定该契约），而编辑器点击分支只手写
 * params、op 回显被跳过/未变守卫拦下——本地必须补同款两步（ensureChannelBands +
 * rebuildInputCache），否则旧线成幽灵（服务端已剪、本地还画着，权威同步一到整批消失，
 * 即实测的「连线回退」）。第二段锚定本地镜像契约；第三段锚定镜像后新线在服务端落地。
 * GraphEditor 侧的镜像调用由 {@link #editorChannelClickMirrorsServerRebuild()} 源码扫描锁钉住。
 * <p>
 * CHANNEL layout-change rewiring semantics (the v1.2.6.2 "wires revert" fix). A layout
 * preset change replaces the output pin set wholesale (mix ↔ l/r/…): wires on old channel
 * pinIds are pruned by design. The server's SET_PARAM path does the pruning (anchored in
 * section one); the editor's click branch writes params by hand and its op echo is skipped
 * or unchanged-guarded, so the editor must mirror the two local steps
 * (ensureChannelBands + rebuildInputCache) - otherwise old wires linger as ghosts that the
 * next authoritative sync wipes (the reported "wires revert"). Section two anchors the
 * local mirror; section three anchors a post-mirror wire surviving the server echo. The
 * GraphEditor mirror calls are pinned by the source scan in
 * {@link #editorChannelClickMirrorsServerRebuild()}.
 */
class ChannelLayoutRewireTest {

    private static final java.util.UUID UID = UUID.randomUUID();

    /** STEREO 拆分 + l→音频输出 的最小图。 / Minimal graph: STEREO split with l → audio out. */
    private static NodeGraph stereoGraph() {
        var g = new NodeGraph();
        var ch = g.addNode(NodeType.CHANNEL, 0, 0);
        var sink = g.addNode(NodeType.AUDIO_OUT, 4, 0);
        ch.params[0] = ChannelLayout.STEREO;
        ch.ensureChannelBands();
        assertTrue(g.addConnection(ch.id, 0, sink.id, 0), "l → audio_out must connect");
        assertEquals("l", g.connections.get(0).fromPinId, "the wire binds by channel pinId");
        return g;
    }

    @Test
    @DisplayName("服务端 SET_PARAM 契约：布局变化剪掉旧声道 pinId 上的连线")
    void serverApplyPrunesStaleChannelWires() {
        var g = stereoGraph();
        int chId = g.nodes.stream().filter(n -> n.type == NodeType.CHANNEL).findFirst().orElseThrow().id;
        var op = GraphOp.setParam(net.minecraft.core.BlockPos.ZERO, -1, chId, 0, ChannelLayout.MIX, UID);
        OpExecutor.apply(g, op, false);
        assertTrue(g.connections.isEmpty(),
            "the wire on pinId 'l' must be pruned server-side once the layout leaves STEREO");
        var ch = g.findNode(chId);
        assertEquals(java.util.List.of("mix"), ch.signalBands, "bands follow the new layout");
    }

    @Test
    @DisplayName("本地镜像契约：params 手写 + ensureChannelBands + rebuildInputCache 立即剪旧线")
    void localMirrorPrunesImmediately() {
        var g = stereoGraph();
        var ch = g.nodes.stream().filter(n -> n.type == NodeType.CHANNEL).findFirst().orElseThrow();
        // 编辑器点击分支的本地序列（op 回显跳过发起者/被未变守卫拦下，这两步必须本地补）
        // The editor click branch's local sequence (the echo skips the originator or is
        // unchanged-guarded; these two steps must be mirrored locally)
        ch.params[0] = ChannelLayout.MIX;
        ch.ensureChannelBands();
        g.rebuildInputCache();
        assertTrue(g.connections.isEmpty(),
            "the ghost wire on 'l' must vanish at click time, not at the next authoritative sync");
    }

    @Test
    @DisplayName("镜像后画的新线：服务端落地 + 未变守卫回显不动它")
    void wireDrawnAfterMirrorSurvives() {
        var g = stereoGraph();
        var ch = g.nodes.stream().filter(n -> n.type == NodeType.CHANNEL).findFirst().orElseThrow();
        var sink = g.nodes.stream().filter(n -> n.type == NodeType.AUDIO_OUT).findFirst().orElseThrow();
        ch.params[0] = ChannelLayout.MIX;
        ch.ensureChannelBands();
        g.rebuildInputCache();
        assertTrue(g.addConnection(ch.id, 0, sink.id, 0), "mix → audio_out must connect");
        assertEquals("mix", g.connections.get(0).fromPinId);

        // 服务端从零应用同一序列：SET_PARAM（值已同 → 未变守卫跳过，不剪新线）
        // The server applies the same sequence from scratch: SET_PARAM (same value → the
        // unchanged guard skips it, leaving the fresh wire alone)
        var gServer = new NodeGraph();
        var chS = gServer.addNode(NodeType.CHANNEL, 0, 0);
        var sinkS = gServer.addNode(NodeType.AUDIO_OUT, 4, 0);
        chS.params[0] = ChannelLayout.MIX;
        chS.ensureChannelBands();
        assertTrue(gServer.addConnection(chS.id, 0, sinkS.id, 0));
        var echo = GraphOp.setParam(net.minecraft.core.BlockPos.ZERO, -1, chS.id, 0, ChannelLayout.MIX, UID);
        OpExecutor.apply(gServer, echo, false);
        assertEquals(1, gServer.connections.size(),
            "the unchanged guard must not prune the wire drawn after the mirror");
    }

    @Test
    @DisplayName("结构锁：GraphEditor 的 CHANNEL 预设点击分支在 sendOp 后镜像服务端重建")
    void editorChannelClickMirrorsServerRebuild() throws Exception {
        var p = java.nio.file.Path.of("src", "main", "java", "io", "github", "y15173334444",
            "create_schematic_compute", "client", "editor", "GraphEditor.java");
        var lines = java.nio.file.Files.readAllLines(p);
        int branch = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("NodeType.CHANNEL && en.params.length > 0")
                    && lines.get(i).contains("if (")) { branch = i; break; }
        }
        assertTrue(branch >= 0, "the CHANNEL preset click branch moved - re-anchor this scan");
        // 从 CHANNEL 分支内找 sendOp(sOp)（布局 op 的发送点），其后到分支 return 之间必须
        // 先出现 ensureChannelBands() 再出现 rebuildInputCache()——两步就是「旧线幽灵化」
        // 的直接防线，缺一即回退病复发。
        // Find sendOp(sOp) inside the CHANNEL branch (the layout op's send site); between it
        // and the branch's return, ensureChannelBands() must precede rebuildInputCache() -
        // the two steps are the direct defence against ghost wires; miss either and the
        // revert returns.
        int send = -1, ret = -1;
        for (int i = branch; i < Math.min(branch + 40, lines.size()); i++) {
            if (send < 0 && lines.get(i).contains("sendOp(sOp)")) send = i;
            if (send >= 0 && lines.get(i).contains("return true")) { ret = i; break; }
        }
        assertTrue(send >= 0 && ret > send, "click-branch shape changed - re-anchor this scan");
        boolean sawEnsure = false, sawRebuild = false;
        for (int i = send; i < ret; i++) {
            if (lines.get(i).contains("ensureChannelBands()")) sawEnsure = true;
            if (lines.get(i).contains("rebuildInputCache()")) sawRebuild = true;
        }
        assertTrue(sawEnsure && sawRebuild, "the CHANNEL click branch must mirror "
            + "ensureChannelBands + rebuildInputCache locally after sendOp - without it the "
            + "origin keeps ghost wires that the next authoritative sync wipes (the v1.2.6.2 revert)");
    }
}
