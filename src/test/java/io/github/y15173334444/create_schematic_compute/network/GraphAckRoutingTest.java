package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.blocks.GraphEditor;
import io.github.y15173334444.create_schematic_compute.client.GraphAckRouter;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GraphEditAckPacket 解耦回归（E2）：网络层不再 import {@code blocks/GraphEditor}——
 * 包 handler 只查静态 {@link GraphEditAckPacket.EditAckSink}，唯一的客户端实现
 * {@code client/GraphAckRouter} 在 ClientSetup 注册。锁定两条语义：
 * <ul>
 *   <li>无人注册（专用服务器 / 客户端初始化窗口 / 注销后）→ 静默丢弃，与旧的
 *       「编辑器已关则丢」语义一致；</li>
 *   <li>注册后 → ACK 载荷原样送达，同坐标打开的编辑器屏收到、坐标不匹配丢弃。</li>
 * </ul>
 * 测法沿用 {@code blocks.RejectReceiptTest} / {@code NbsEditorOutletFunnelTest}：
 * 僵尸 Minecraft 实例（level=null / screen=null 走守卫分支），dispatch 同步直调，
 * 不伪造 IPayloadContext。
 *
 * <p>GraphEditAckPacket decoupling regression (E2): the network layer no longer imports
 * {@code blocks/GraphEditor} — the packet handler only consults the static
 * {@link GraphEditAckPacket.EditAckSink}, whose single client implementation
 * ({@code client/GraphAckRouter}) registers in ClientSetup. Pinned semantics: no sink
 * registered (dedicated server / client-init window / after unregister) → silent drop,
 * matching the old "editor closed" behavior; registered → the ack payload reaches the
 * open host screen at the same position, mismatched positions are dropped. Method
 * follows {@code blocks.RejectReceiptTest} / {@code NbsEditorOutletFunnelTest}: zombie
 * Minecraft instance (level=null / screen=null takes the guard branches) with
 * synchronous dispatch — no IPayloadContext stub.</p>
 */
class GraphAckRoutingTest {

    @BeforeAll
    static void bootstrapZombieMinecraft() throws Exception {
        // 路由器的 BE 计数归还与屏路由都读 Minecraft.getInstance() —— 僵尸实例
        // （level=null、screen=null）让两处走守卫分支
        // The router's BE-counter release and screen routing read Minecraft.getInstance()
        // — the zombie instance (level=null, screen=null) takes the guard branches.
        var mcClass = net.minecraft.client.Minecraft.class;
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
        var instField = mcClass.getDeclaredField("instance");
        instField.setAccessible(true);
        instField.set(null, unsafe.allocateInstance(mcClass));
    }

    @AfterEach
    void resetSinkAndScreen() {
        GraphEditAckPacket.setClientSink(null);
        net.minecraft.client.Minecraft.getInstance().screen = null;
    }

    /** 同坐标打开的编辑器屏桩（NbsEditorOutletFunnelTest.StubOriginScreen 同款语义）。
     *  Open-host-screen stub (same semantics as NbsEditorOutletFunnelTest.StubOriginScreen). */
    private static final class StubHostScreen extends net.minecraft.client.gui.screens.Screen
            implements GraphEditor.Host {
        final BlockPos pos;
        final List<GraphEditAckPacket> acks = new ArrayList<>();
        StubHostScreen(BlockPos pos) { super(Component.literal("host")); this.pos = pos; }
        @Override public NodeGraph getGraph() { return new NodeGraph(); }
        @Override public void saveGraph() {}
        @Override public void toggleRunning(boolean start) {}
        @Override public boolean isRunning() { return false; }
        @Override public net.minecraft.client.gui.screens.Screen asScreen() { return this; }
        @Override public BlockPos getBlockPos() { return pos; }
        @Override public void handleAck(GraphEditAckPacket ack) { acks.add(ack); }
    }

    @Test
    @DisplayName("无人注册汇点：dispatch 静默丢弃（旧「编辑器已关则丢」语义）")
    void noSinkSilentlyDrops() {
        GraphEditAckPacket.setClientSink(null);
        assertDoesNotThrow(() ->
            GraphEditAckPacket.dispatch(new GraphEditAckPacket(BlockPos.ZERO, 0, 0, 1L)),
            "unregistered sink must be a silent drop, never a crash");
    }

    @Test
    @DisplayName("注册后 dispatch 原样传递 ACK 载荷（pos/tempId/assignedId/editVersion）")
    void registeredSinkReceivesExactPayload() {
        var got = new ArrayList<GraphEditAckPacket>();
        var pkt = new GraphEditAckPacket(new BlockPos(1, 2, 3), 5, 7, 9L);
        GraphEditAckPacket.setClientSink(got::add);

        GraphEditAckPacket.dispatch(pkt);

        assertEquals(List.of(pkt), got, "the sink must receive the ack record verbatim");
    }

    @Test
    @DisplayName("注销汇点后立即恢复静默丢弃")
    void sinkCanBeUnregisteredMidFlight() {
        var got = new ArrayList<GraphEditAckPacket>();
        GraphEditAckPacket.setClientSink(got::add);
        GraphEditAckPacket.dispatch(new GraphEditAckPacket(BlockPos.ZERO, 0, 0, 1L));
        assertEquals(1, got.size());

        GraphEditAckPacket.setClientSink(null);
        GraphEditAckPacket.dispatch(new GraphEditAckPacket(BlockPos.ZERO, 0, 0, 2L));
        assertEquals(1, got.size(), "an unregistered sink must drop silently again");
    }

    @Test
    @DisplayName("路由器：同坐标打开的编辑器屏收到 ACK（含 tempId→assignedId 载荷）")
    void routerRoutesToOpenHostAtSamePos() {
        var pos = new BlockPos(4, 5, 6);
        var screen = new StubHostScreen(pos);
        net.minecraft.client.Minecraft.getInstance().screen = screen;
        GraphEditAckPacket.setClientSink(new GraphAckRouter());

        var pkt = new GraphEditAckPacket(pos, 11, 12, 13L);
        GraphEditAckPacket.dispatch(pkt);

        assertEquals(List.of(pkt), screen.acks,
            "the ack must reach the open host at the same position, payload intact");
    }

    @Test
    @DisplayName("路由器：坐标不匹配的 ACK 有意丢弃（旧语义）")
    void routerDropsMismatchedPos() {
        var screen = new StubHostScreen(new BlockPos(4, 5, 6));
        net.minecraft.client.Minecraft.getInstance().screen = screen;
        GraphEditAckPacket.setClientSink(new GraphAckRouter());

        GraphEditAckPacket.dispatch(new GraphEditAckPacket(new BlockPos(9, 9, 9), 0, 0, 1L));

        assertTrue(screen.acks.isEmpty(),
            "a mismatched-position ack must be dropped (the next editor open does a full sync)");
    }

    @Test
    @DisplayName("路由器：无 Level、无打开屏时不炸（僵尸实例守卫分支）")
    void routerSurvivesNoLevelAndNoScreen() {
        // 僵尸实例 level=null、screen=null：BE 计数归还与屏路由都应走守卫分支
        // Zombie instance: level=null, screen=null — both the counter release and the
        // screen routing must take their guard branches.
        GraphEditAckPacket.setClientSink(new GraphAckRouter());
        assertDoesNotThrow(() ->
            GraphEditAckPacket.dispatch(new GraphEditAckPacket(BlockPos.ZERO, 0, 0, 1L)));
    }
}
