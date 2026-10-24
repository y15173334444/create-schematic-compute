package io.github.y15173334444.create_schematic_compute.client;

import io.github.y15173334444.create_schematic_compute.blocks.GraphEditor;
import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.GraphOp;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.OpType;
import io.github.y15173334444.create_schematic_compute.network.GraphEditAckPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NBS 编辑器 op 出口收口回归（P1-10）：{@code getEditor()} 以往写死 {@code return null}、
 * {@code sendOp} 直发 —— "新增 MUSIC 节点后一个 RTT 内打开 NBS 编辑器保存"的 SET_SONG
 * 携带未 ACK 的临时 id，服务端 {@code findNode} 落空静默丢弃，整首曲目丢失。修复 = 像素
 * 编辑器同款出口收口：getEditor 路由回来源屏的 GraphEditor（便携终端包装屏向内解一层），
 * sendOp 走 {@code ed.sendOp} 漏斗（temp-id 挂起 + ACK 重映射补发）。
 * <p>
 * NBS editor op-outlet convergence regression (P1-10): {@code getEditor()} used to be a
 * hardwired {@code return null} and {@code sendOp} went out directly — a SET_SONG saved
 * within one RTT after adding the MUSIC node still carried the un-ACKed temp id, the
 * server's {@code findNode} missed and silently dropped it, losing the whole song. Fix =
 * the pixel editor's outlet convergence: getEditor routes back to the origin screen's
 * GraphEditor (unwrapping one level through the portable-terminal wrapper), and sendOp
 * goes through the {@code ed.sendOp} funnel (temp-id stash + ACK remap + flush).
 * <p>
 * 测法沿用 {@code EditPanelUploadRoundTripTest}：僵尸 Minecraft 实例 + 捕获型 Host 桩，
 * ACK 走真实 {@link GraphEditor#remapNodeId} 路径。
 * Method follows {@code EditPanelUploadRoundTripTest}: zombie Minecraft instance +
 * capturing Host stub; the ACK goes through the real {@link GraphEditor#remapNodeId}.
 */
class NbsEditorOutletFunnelTest {

    // ── 无头 GUI 地基（僵尸 Minecraft 实例 + stub FontSet，EditPanelUploadRoundTripTest 同款）──
    //     Headless GUI foundation (zombie Minecraft + stub FontSet, as in EditPanelUploadRoundTripTest)

    @BeforeAll
    static void bootstrapHeadlessGui() throws Exception {
        var mcClass = net.minecraft.client.Minecraft.class;
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var mc = unsafe.allocateInstance(mcClass);
        var font = new net.minecraft.client.gui.Font(rl -> stubFontSet(), false);
        unsafe.putObject(mc, unsafe.objectFieldOffset(mcClass.getField("font")), font);
        var instField = mcClass.getDeclaredField("instance");
        instField.setAccessible(true);
        instField.set(null, mc);
    }

    private static net.minecraft.client.gui.font.FontSet stubFontSet() {
        return new net.minecraft.client.gui.font.FontSet(null,
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("csc_test", "stub")) {
            @Override public com.mojang.blaze3d.font.GlyphInfo getGlyphInfo(int codepoint, boolean bold) {
                return new com.mojang.blaze3d.font.GlyphInfo() {
                    @Override public float getAdvance() { return 6f; }
                    @Override public net.minecraft.client.gui.font.glyphs.BakedGlyph bake(
                        java.util.function.Function<com.mojang.blaze3d.font.SheetGlyphInfo,
                            net.minecraft.client.gui.font.glyphs.BakedGlyph> baker) { return null; }
                };
            }
        };
    }

    // ── 测试夹具 / fixture ────────────────────────────────────────────────

    /** 捕获 sendOp 的 Host 桩。 / Capturing Host stub. */
    private static final class StubHost implements GraphEditor.Host {
        final NodeGraph graph = new NodeGraph();
        final List<GraphOp> sent = new ArrayList<>();
        @Override public NodeGraph getGraph() { return graph; }
        @Override public void saveGraph() {}
        @Override public void toggleRunning(boolean start) {}
        @Override public boolean isRunning() { return false; }
        @Override public net.minecraft.client.gui.screens.Screen asScreen() { return null; }
        @Override public void sendOp(GraphOp op) { sent.add(op); }
    }

    /** 来源屏桩：getEditor 返回共享的编辑器实例（AbstractGraphScreen 同款语义）。
     *  Origin-screen stub: getEditor returns the shared editor instance (AbstractGraphScreen semantics). */
    private static final class StubOriginScreen extends net.minecraft.client.gui.screens.Screen
            implements GraphEditor.Host {
        final GraphEditor ed;
        StubOriginScreen(GraphEditor ed) { super(net.minecraft.network.chat.Component.literal("origin")); this.ed = ed; }
        @Override public NodeGraph getGraph() { return ed.getGraph(); }
        @Override public void saveGraph() {}
        @Override public void toggleRunning(boolean start) {}
        @Override public boolean isRunning() { return false; }
        @Override public net.minecraft.client.gui.screens.Screen asScreen() { return this; }
        @Override public GraphEditor getEditor() { return ed; }
    }

    /** 便携终端包装桩：Screen + HostWrapper（TerminalWrapper 同款语义）。
     *  Terminal wrapper stub: a Screen implementing HostWrapper (TerminalWrapper semantics). */
    private static final class StubWrapperScreen extends net.minecraft.client.gui.screens.Screen
            implements PortableTerminalScreen.HostWrapper {
        final net.minecraft.client.gui.screens.Screen inner;
        StubWrapperScreen(net.minecraft.client.gui.screens.Screen inner) {
            super(net.minecraft.network.chat.Component.literal("wrapper"));
            this.inner = inner;
        }
        @Override public net.minecraft.client.gui.screens.Screen getInnerScreen() { return inner; }
    }

    /** 来源屏（图编辑器）+ 已登记 temp id 的 MUSIC 节点 + NBS 编辑器。
     *  Origin editor + MUSIC node with a registered temp id + the NBS editor. */
    private record Fixture(StubHost host, GraphEditor ed, GraphNode music, NbsEditorScreen nbs) {}

    private static Fixture fixture() {
        var host = new StubHost();
        var ed = new GraphEditor(host, null);
        // 客户端本地加 MUSIC 节点（tempId=1），菜单加点真实出口登记挂起
        // Client-local MUSIC node (tempId=1); the real menu-add outlet registers the stash
        var music = host.graph.addNode(NodeType.MUSIC, 0, 0);
        var addOp = GraphOp.addNodeRequest(host.getBlockPos(), -1, music.id,
            NodeType.MUSIC, 0f, 0f, UUID.randomUUID());
        ed.sendOp(addOp);
        host.sent.clear();
        // 来源屏 = 同一编辑器实例的宿主（AbstractGraphScreen 语义：getEditor 返回自身编辑器）
        // The origin screen hosts the SAME editor instance (AbstractGraphScreen semantics)
        var nbs = new NbsEditorScreen(host.getBlockPos(), music, new StubOriginScreen(ed), -1);
        return new Fixture(host, ed, music, nbs);
    }

    private static GraphOp setSong(Fixture f, int nodeId) {
        return GraphOp.setSong(f.host().getBlockPos(), -1, nodeId, 42, UUID.randomUUID());
    }

    @Test
    @DisplayName("ACK 未回的窗口内保存：SET_SONG 挂进漏斗，ACK 重映射后补发真实 id")
    void setSongDefersBehindPendingTempIdAndFlushesOnAck() {
        Fixture f = fixture();
        assertSame(f.ed(), f.nbs().getEditor(), "getEditor must route to the origin screen's editor");

        // temp id 未 ACK → SET_SONG 不得直发（修复前：直发 → 服务端静默丢弃 → 曲目丢失）
        // temp id not yet ACKed → SET_SONG must not go out directly (before the fix: direct
        // send → silent server-side drop → lost song)
        f.nbs().sendOp(setSong(f, f.music().id));
        assertTrue(f.host().sent.isEmpty(),
            "a SET_SONG referencing a pending temp id must be stashed by the funnel, never sent raw");

        // ACK 回来（服务端分到 7）→ 重映射补发，SET_SONG 带真实 id 出门
        // ACK arrives (server assigned 7) → remap + flush, SET_SONG leaves with the real id
        f.ed().remapNodeId(new GraphEditAckPacket(f.host().getBlockPos(), f.music().id, 7, 2L));
        assertEquals(1, f.host().sent.size(), "the deferred SET_SONG must flush on ACK");
        assertEquals(OpType.SET_SONG, f.host().sent.get(0).type());
        assertEquals(7, f.host().sent.get(0).targetNodeId(),
            "the flushed SET_SONG must carry the server-assigned id, not the temp id");
    }

    @Test
    @DisplayName("无挂起 temp id：op 经漏斗直通（漏斗不吞普通 op）")
    void opsPassThroughWhenNoTempIdPending() {
        var host = new StubHost();
        var ed = new GraphEditor(host, null);
        var music = host.graph.addNode(NodeType.MUSIC, 0, 0); // 不登记挂起 / no stash entry
        var nbs = new NbsEditorScreen(host.getBlockPos(), music, new StubOriginScreen(ed), -1);
        nbs.sendOp(GraphOp.setSong(host.getBlockPos(), -1, music.id, 42, UUID.randomUUID()));
        assertEquals(1, host.sent.size(), "ops with resolved ids must pass straight through the funnel");
        assertEquals(music.id, host.sent.get(0).targetNodeId());
    }

    @Test
    @DisplayName("便携终端包装屏：getEditor 向内解一层拿到来源编辑器")
    void wrapperScreenUnwrapsToOriginEditor() {
        var host = new StubHost();
        var ed = new GraphEditor(host, null);
        var origin = new StubOriginScreen(ed);
        var music = host.graph.addNode(NodeType.MUSIC, 0, 0);
        var nbs = new NbsEditorScreen(host.getBlockPos(), music, new StubWrapperScreen(origin), -1);
        assertSame(ed, nbs.getEditor(), "the wrapper must unwrap one level to the origin editor");
    }
}
