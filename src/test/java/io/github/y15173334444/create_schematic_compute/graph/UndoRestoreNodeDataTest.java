package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-6 回归：REMOVE_NODE 撤销（ADD_NODE + NBT 快照）必须恢复 MUSIC 曲目与
 * ENCAPSULATION 子图 —— 快照里明明有数据，此前恢复清单不含这两项，Ctrl+Z 看似成功
 * 而整段封装/整首曲目静默丢失。
 * <p>P2-6 regressions: the REMOVE_NODE undo (ADD_NODE + NBT snapshot) must restore the
 * MUSIC song and the ENCAPSULATION sub-graph — the snapshot always carried the data, but the
 * restore list omitted both, so Ctrl+Z looked successful while the whole encapsulation / whole
 * song was silently lost.</p>
 *
 * <p>快照构造与 GraphEditor.saveNodeNbt / GraphOpHistory 的撤销链同口径：
 * {@code node.save(registries).toString()} 作为 ADD_NODE 的 stringValue。headless 测试
 * 传 null 注册表 —— 夹具节点（MUSIC/ENCAPSULATION/CONST/ADD）的 itemParams 全为空，
 * 序列化不触碰注册表（EMPTY 物品的 saveOptional/parseOptional 均不查 provider）。</p>
 * <p>The snapshot mirrors GraphEditor.saveNodeNbt / the GraphOpHistory undo chain:
 * {@code node.save(registries).toString()} as the ADD_NODE stringValue. The headless test
 * passes a null registry — the fixture nodes (MUSIC/ENCAPSULATION/CONST/ADD) all carry empty
 * itemParams, so serialization never touches the registry (EMPTY items' saveOptional /
 * parseOptional never consult the provider).</p>
 */
class UndoRestoreNodeDataTest {

    private static final BlockPos POS = new BlockPos(2, 3, 4);
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000021");

    /** REMOVE_NODE → 快照 → ADD_NODE 撤销（与 GraphOpHistory.reverseOp 同构）。 */
    private static void undoRemove(NodeGraph targetGraph, NodeGraph sourceGraph, GraphNode removed) {
        String snapshot = removed.save(null).toString();
        var removeOp = new GraphOp(OpType.REMOVE_NODE, POS, -1, removed.id, ACTOR);
        OpExecutor.apply(sourceGraph, removeOp);
        assertNull(sourceGraph.findNode(removed.id), "夹具前提：删除生效");
        // 撤销 op：ADD_NODE 携带快照（GraphOpHistory 传 ItemStack.EMPTY —— headless 传 null）
        var undoOp = new GraphOp(OpType.ADD_NODE, POS, -1, removed.id,
            0, removed.type, removed.x, removed.y, 0, 0, 0, 0, 0, 0f,
            snapshot, 0, 0, 0, 0, null, 0, 0, 0,
            null, 0L, ACTOR, 0, null, null);
        OpExecutor.apply(targetGraph, undoOp);
    }

    @Test
    @DisplayName("删带曲 MUSIC 节点 → undo → 曲目还在")
    void removedMusicNodeUndoRestoresSong() {
        NodeGraph g = new NodeGraph();
        GraphNode music = g.addNode(NodeType.MUSIC, 0, 0);
        music.song.songName = "undo-test";
        music.song.tempo = 1500;
        music.song.putNote(0, 0, 3, 45, 100, 100, 0);
        music.song.putNote(8, 0, 3, 47, 100, 100, 0);
        int expectedNotes = music.song.noteCount();

        NodeGraph restored = new NodeGraph();
        undoRemove(restored, g, music);

        GraphNode back = restored.findNode(music.id);
        assertNotNull(back, "撤销后节点回到图中");
        assertEquals(NodeType.MUSIC, back.type);
        assertNotNull(back.song, "曲目必须随撤销恢复（此前静默丢失）");
        assertEquals("undo-test", back.song.songName);
        assertEquals(1500, back.song.tempo);
        assertEquals(expectedNotes, back.song.noteCount());
        assertEquals(45, back.song.getNote(0, 0).key, "音符内容逐条还原");
        assertEquals(47, back.song.getNote(8, 0).key);
    }

    @Test
    @DisplayName("删封装节点 → undo → 子图（节点+连线+嵌套曲目）还在")
    void removedEncapsulationUndoRestoresSubGraph() {
        NodeGraph g = new NodeGraph();
        GraphNode encap = g.addNode(NodeType.ENCAPSULATION, 0, 0);
        NodeGraph sub = new NodeGraph();
        GraphNode a = sub.addNode(NodeType.CONST, 0, 0);
        a.params[0] = 42f;
        GraphNode music = sub.addNode(NodeType.MUSIC, 10, 0);
        music.song.songName = "nested-song";
        music.song.putNote(2, 0, 0, 60, 100, 100, 0);
        sub.addConnection(a.id, 0, music.id, 0);
        encap.subGraph = sub;

        NodeGraph restored = new NodeGraph();
        undoRemove(restored, g, encap);

        GraphNode back = restored.findNode(encap.id);
        assertNotNull(back, "撤销后封装节点回到图中");
        assertNotNull(back.subGraph, "子图必须随撤销恢复（此前静默丢失）");
        assertEquals(2, back.subGraph.nodes.size(), "子节点全部还原");
        assertEquals(1, back.subGraph.connections.size(), "子图连线还原");
        GraphNode nestedConst = back.subGraph.findNode(a.id);
        assertNotNull(nestedConst);
        assertEquals(42f, nestedConst.params[0], "子节点参数还原");
        GraphNode nestedMusic = back.subGraph.findNode(music.id);
        assertNotNull(nestedMusic);
        assertEquals("nested-song", nestedMusic.song.songName, "嵌套曲目还原");
        assertEquals(1, nestedMusic.song.noteCount());
    }

    @Test
    @DisplayName("撤销恢复不回归既有字段：信号名与层序仍在")
    void existingRestoreFieldsUnregressed() {
        NodeGraph g = new NodeGraph();
        GraphNode bus = g.addNode(NodeType.BUS_OUT, 0, 0);
        bus.signalName = "alpha";
        bus.sortB = 5; // BUS_OUT 的 params 长度为 0，用 sortB（"zb" 键）验证数值恢复

        NodeGraph restored = new NodeGraph();
        undoRemove(restored, g, bus);

        GraphNode back = restored.findNode(bus.id);
        assertNotNull(back);
        assertEquals("alpha", back.signalName, "既有恢复项不得回归");
        assertEquals(5, back.sortB);
    }
}
