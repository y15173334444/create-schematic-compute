package io.github.y15173334444.create_schematic_compute.network;

import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 私有频道占用 + 定义测试（BUS 同款占用检测 / 引脚类型变换的机制面）：首个注册者获胜、
 * 同名不同 owner 拒注册、owner 校验释放（值/定义随撤）、竞争者接管、定义版本去重、
 * 同图重名双方亮旗标（只增不减、跨表同名不算冲突）。
 * Private channel occupancy + definition tests (the mechanism behind BUS-parity occupancy
 * detection and pin typing): first-registrant-wins, refusal, owner-checked release taking
 * value+definition, contender takeover, definition version dedup, same-graph duplicate flags.
 */
class PrivateChannelOccupancyTest {

    private static final ChannelOwner A = new ChannelOwner(new BlockPos(1, 2, 3), 10);
    private static final ChannelOwner B = new ChannelOwner(new BlockPos(4, 5, 6), 20);

    @AfterEach void cleanup() { SignalBus.clear(); }

    @Test
    @DisplayName("占用检测：首个注册者获胜，同名不同 owner 拒注册 / first registrant wins, a second owner is refused")
    void firstRegistrantWins() {
        assertTrue(SignalBus.registerPrivateChannel("p", A));
        assertFalse(SignalBus.registerPrivateChannel("p", B), "a second owner is refused");
        assertTrue(SignalBus.registerPrivateChannel("p", A), "the owner may re-register");
        assertEquals(A, SignalBus.getPrivateOwner("p"));
    }

    @Test
    @DisplayName("owner 校验释放：他人放不掉；owner 放掉即撤值撤定义 / release is owner-checked and takes value+definition with it")
    void releaseIsOwnerChecked() {
        SignalBus.put("p", 7f);
        SignalBus.setPrivateAudio("p", true);
        SignalBus.registerPrivateChannel("p", A);
        assertFalse(SignalBus.unregisterPrivateChannel("p", B), "a peer cannot release someone else's channel");
        assertEquals(7f, SignalBus.get("p"));
        assertTrue(SignalBus.isPrivateAudio("p"));
        assertTrue(SignalBus.unregisterPrivateChannel("p", A));
        assertEquals(0f, SignalBus.get("p"), "the channel dies with its publisher");
        assertFalse(SignalBus.isPrivateAudio("p"), "definition goes with the publisher");
        assertNull(SignalBus.getPrivateOwner("p"));
    }

    @Test
    @DisplayName("释放后竞争者接管（自愈同 BUS）/ a contender takes over after the release (BUS-style self-heal)")
    void contenderTakesOver() {
        SignalBus.registerPrivateChannel("p", A);
        assertFalse(SignalBus.registerPrivateChannel("p", B));
        SignalBus.unregisterPrivateChannel("p", A);
        assertTrue(SignalBus.registerPrivateChannel("p", B), "the contender claims the freed name");
    }

    @Test
    @DisplayName("clearSignal 全撤（改名/销毁路径）/ clearSignal drops value, occupancy and definition")
    void clearSignalReleasesEverything() {
        SignalBus.registerPrivateChannel("p", A);
        SignalBus.put("p", 7f);
        SignalBus.setPrivateAudio("p", true);
        SignalBus.clearSignal("p");
        assertNull(SignalBus.getPrivateOwner("p"));
        assertEquals(0f, SignalBus.get("p"));
        assertFalse(SignalBus.isPrivateAudio("p"));
        assertTrue(SignalBus.registerPrivateChannel("p", B), "the freed name is claimable");
    }

    @Test
    @DisplayName("定义版本：变化才自增（推送去重用）/ the definition version bumps only on change")
    void definitionStampBumpsOnChangeOnly() {
        assertEquals(0, SignalBus.privateAudioStamp("p"));
        assertTrue(SignalBus.setPrivateAudio("p", true));
        assertEquals(1, SignalBus.privateAudioStamp("p"));
        assertFalse(SignalBus.setPrivateAudio("p", true), "same value is a no-op");
        assertEquals(1, SignalBus.privateAudioStamp("p"));
        assertTrue(SignalBus.setPrivateAudio("p", false));
        assertEquals(2, SignalBus.privateAudioStamp("p"));
    }

    @Test
    @DisplayName("同图重名双方亮旗标；跨表同名不算冲突 / same-graph duplicates flag both; cross-table names don't collide")
    void sameGraphDuplicateFlagsBoth() {
        NodeGraph g = new NodeGraph();
        var a = g.addNode(NodeType.PRIVATE_OUT, 0, 0);
        a.signalName = "dup";
        var b = g.addNode(NodeType.PRIVATE_OUT, 100, 0);
        b.signalName = "dup";
        BusChannelHelper.mergeLocalBusConflicts(g);
        assertTrue(a.busConflict, "locally provable duplicate raises the flag");
        assertTrue(b.busConflict);

        var busOut = g.addNode(NodeType.BUS_OUT, 200, 0);
        busOut.signalName = "dup";
        BusChannelHelper.mergeLocalBusConflicts(g);
        assertFalse(busOut.busConflict, "the tables are isolated — a cross-table name match is not a conflict");
    }

    @Test
    @DisplayName("旗标只增不减：服务端权威值不被本地合并下调 / raise-only: local merging never lowers the authoritative flag")
    void mergeNeverLowersAuthoritativeFlag() {
        NodeGraph g = new NodeGraph();
        var solo = g.addNode(NodeType.PRIVATE_OUT, 0, 0);
        solo.signalName = "s";
        solo.busConflict = true; // 服务端随图同步来的权威值 / authoritative value from the server
        BusChannelHelper.mergeLocalBusConflicts(g);
        assertTrue(solo.busConflict, "never lower the authoritative value");
    }
}
