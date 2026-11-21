package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.GraphNode;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import io.github.y15173334444.create_schematic_compute.graph.Vec3f;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * known-open-issues 条目『频道清理残余豁口』① 的行为锚（ChannelOwner 图域）：
 * 主图与封装子图的节点 id 计数器独立、必然撞号——owner 键补上图域（ownerNodeId：主图 -1 /
 * 子图 = 承载 ENCAP 节点 id）后，撞号不再被误判为「同 owner 重注册」（internalMap 接管），
 * 改名走人的释放也不再按撞号误匹配对方条目。附带 28.2 收口锚：宿主停机按 owner 门控撤
 * BUS 频段的向量值（FLOAT 半边既有，VEC3 半边补齐）。
 * Behaviour anchors for register entry "residual channel cleanup gaps" part 1 (the
 * ChannelOwner graph domain): the main graph and encapsulation sub-graphs run independent
 * node-id counters that necessarily collide — with the graph domain on the owner key
 * (ownerNodeId: main graph -1 / a sub-graph = the hosting ENC node's id), a collision no
 * longer poses as a same-owner re-registration (the internalMap takeover) and a
 * rename-departure release no longer false-matches the peer's entry by collision. Plus the
 * 28.2 close: a host stop withdraws BUS band vector values under the owner gate (the FLOAT
 * half existed; the VEC3 half completes the symmetry).
 */
class ChannelOwnerGraphDomainTest {

    private static final BlockPos POS = new BlockPos(1, 2, 3);
    private static final int ENC = 42; // 承载子图的 ENCAP 节点 id（主图域内）/ the hosting ENC node id (main-graph domain)

    @BeforeEach @AfterEach void resetBus() { SignalBus.clear(); }

    /** 主图节点与子图节点的夹具：两者 id 恒撞号（计数器独立）。/ Fixture: a main-graph node
     *  and a sub-graph node deliberately sharing the same id (independent counters). */
    private static GraphNode adoptId(NodeGraph g, int id, String name) {
        var n = new GraphNode(id, NodeType.BUS_OUT, 0, 0);
        n.signalName = name;
        n.busInternalMap = new HashMap<>();
        g.adoptNode(n);
        return n;
    }

    @Test
    @DisplayName("撞号不再劫持：子图同号节点注册同名单独标冲突，主图 internalMap 原样")
    void collisionNoLongerHijacksInternalMap() {
        var main = new NodeGraph();
        var mainOut = adoptId(main, 5, "X");
        BusChannelHelper.registerGraphChannels(main, POS);
        var mainMap = mainOut.busInternalMap;
        var entry = SignalBus.getChannel("X");
        assertNotNull(entry);
        assertEquals(new ChannelOwner(POS, -1, 5), entry.owner, "the main-graph walk keys the -1 domain");

        // 子图同号节点（id 5）改名到同名 "X"：注册必须按图域判为他人 → 标冲突、不接管。
        var sub = new NodeGraph();
        var subOut = adoptId(sub, 5, "X");
        BusChannelHelper.applyBusOutRename(sub, POS, subOut, "", ENC, null);

        entry = SignalBus.getChannel("X");
        assertSame(mainMap, entry.internalMap, "the main graph's live map must not be taken over");
        assertEquals(new ChannelOwner(POS, -1, 5), entry.owner, "ownership stays with the main-graph node");
        assertTrue(subOut.busConflict, "the colliding sub-graph node flags the conflict instead");
    }

    @Test
    @DisplayName("撞号不再误释放：子图同号节点改名走人，主图同号节点的条目与定义原样")
    void collisionReleaseNoLongerFreesThePeer() {
        var main = new NodeGraph();
        var mainOut = adoptId(main, 5, "Y");
        mainOut.signalBands = new ArrayList<>(List.of("b0"));
        BusChannelHelper.registerGraphChannels(main, POS);

        // 子图同号节点曾以图域注册持有 "Y" 的镜像意图（注册被主图占用 → 冲突），现改名走人：
        // 释放键 = (POS, ENC, 5) ≠ 主图 owner (POS, -1, 5) → 主图条目/定义不得被释放。
        var sub = new NodeGraph();
        var subOut = adoptId(sub, 5, "Y");
        BusChannelHelper.applyBusOutRename(sub, POS, subOut, "", ENC, null); // 冲突态（Y 被主图持有）
        assertTrue(subOut.busConflict, "fixture: the sub-graph collision is conflicted");

        subOut.signalName = "W";
        BusChannelHelper.applyBusOutRename(sub, POS, subOut, "Y", ENC, null);

        var entry = SignalBus.getChannel("Y");
        assertNotNull(entry, "the main graph's channel survives the colliding sub-node's departure");
        assertEquals(new ChannelOwner(POS, -1, 5), entry.owner);
        assertEquals(List.of("b0"), SignalBus.getBands("Y"), "the main graph's band definition survives too");
        assertNotNull(SignalBus.getChannel("W"), "the renamed-away node holds its new name");
        assertFalse(SignalBus.getChannel("W").owner.equals(new ChannelOwner(POS, -1, 5)),
            "the new-name owner carries the sub-graph domain");
    }

    @Test
    @DisplayName("主图 walk 语义保持：二参兼容构造 = -1 域，与既有 owner 逐位同判")
    void mainGraphWalksKeepTheMainDomain() {
        var g = new NodeGraph();
        var out = adoptId(g, 9, "abc");
        BusChannelHelper.registerGraphChannels(g, POS);
        var entry = SignalBus.getChannel("abc");
        assertEquals(new ChannelOwner(POS, 9), entry.owner,
            "the 2-arg compatibility constructor is byte-identical to the old owner shape");
        assertEquals(new ChannelOwner(POS, -1, 9), entry.owner);
        assertTrue(entry.owner.equals(new ChannelOwner(POS, 9)), "existing owner-matching call sites keep matching");
    }

    // ── 28.2 收口：停机撤 BUS 频段向量值（owner 门控） ──────────────────────────

    @Test
    @DisplayName("停机撤向量值：owner 持有才撤，冲突者停机不撤对端的值")
    void stopWithdrawsVec3BandValuesOwnerGated() {
        var g = new NodeGraph();
        var out = adoptId(g, 9, "abc");
        out.signalBands = new ArrayList<>(List.of("v0", "f0"));
        out.bandTypes.add(io.github.y15173334444.create_schematic_compute.graph.PinType.VEC3); // 频段 0 声明 VEC3 / band 0 declares VEC3
        out.bandTypes.add(io.github.y15173334444.create_schematic_compute.graph.PinType.FLOAT);
        BusChannelHelper.registerGraphChannels(g, POS);

        String vk = io.github.y15173334444.create_schematic_compute.network.AudioBands.bandKey("abc", "v0");
        SignalBus.putVec3(vk, new Vec3f(1, 2, 3));
        BusChannelHelper.clearBusVec3Values(g, POS);
        assertNull(SignalBus.getVec3(vk), "the owner's stop withdraws its VEC3 band value");

        // 冲突者停机：频道被他人持有 → 不得撤对端的向量值 / a conflicted node's stop never
        // withdraws the peer's vectors
        SignalBus.putVec3(vk, new Vec3f(4, 5, 6));
        var foreign = new NodeGraph();
        var foe = adoptId(foreign, 7, "abc");
        foe.busConflict = true; // 注册被拒的冲突形态（值表里是对端的值）
        BusChannelHelper.clearBusVec3Values(foreign, new BlockPos(9, 9, 9));
        assertEquals(new Vec3f(4, 5, 6), SignalBus.getVec3(vk),
            "a conflicted (foreign-held) node's stop never withdraws the owner's vectors");
    }

    // ── 客户端 / op 侧接线源码锚（编辑器链路无法 headless 实例化） ────────────────
    //   Source anchors for the client / op-side wiring (the editor chain cannot be
    //   instantiated headless).

    @Test
    @DisplayName("源码锚：客户端三写 + 删除释放 + op 迁移全部携带图域")
    void graphDomainRidesEveryClientAndOpSite() throws Exception {
        String busEditor = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/GraphBusEditor.java"));
        assertTrue(count(busEditor, "ed.ownerNodeId()") >= 3,
            "all client mirror writes (rename migration / release / band upload) carry the domain"
            + " (the op-construction site already rode ownerNodeId)");
        String editor = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/GraphEditor.java"));
        assertTrue(editor.contains("host.getBlockPos(), ownerNodeId(), n.id"),
            "the client delete-release keys the owner to the editor's graph domain");
        String registry = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/network/EditSessionRegistry.java"));
        assertTrue(registry.contains("op.ownerNodeId()"),
            "the op-time rename migration threads the op's graph domain");
        String owner = Files.readString(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/network/ChannelOwner.java"));
        assertTrue(owner.contains("int ownerNodeId"), "the owner record carries the graph domain");
        assertTrue(owner.contains("this(pos, -1, nodeId)"),
            "the 2-arg compatibility constructor states the main domain explicitly");
    }

    private static int count(String hay, String needle) {
        int c = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { c++; i += needle.length(); }
        return c;
    }
}
