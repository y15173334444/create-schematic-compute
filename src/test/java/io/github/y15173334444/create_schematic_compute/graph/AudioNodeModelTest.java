package io.github.y15173334444.create_schematic_compute.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 音频数据模型测试：引脚域分类、分类覆盖、AUDIO_OUT 频道 pinId、域兼容连线、曲目字段。
 * Audio data-model tests: pin-domain classification, category coverage, AUDIO_OUT channel
 * pinIds, domain-compatible connections, and the song field.
 */
class AudioNodeModelTest {

    @Test
    @DisplayName("引脚域分类：MUSIC/AMP 的 audio 引脚为 AUDIO，其余 FLOAT / pin-domain classification")
    void domainClassification() {
        // MUSIC: output 0 = audio (AUDIO), outputs 1..4 = float; inputs all float
        assertEquals(NodeType.PinDomain.AUDIO, NodeType.MUSIC.outputDomain(0));
        for (int i = 1; i < 5; i++) assertEquals(NodeType.PinDomain.FLOAT, NodeType.MUSIC.outputDomain(i));
        for (int i = 0; i < 3; i++) assertEquals(NodeType.PinDomain.FLOAT, NodeType.MUSIC.inputDomain(i));
        // AMP: input 0 = audio (AUDIO), input 1 = gain (FLOAT); output 0 = audio (AUDIO)
        assertEquals(NodeType.PinDomain.AUDIO, NodeType.AMP.inputDomain(0));
        assertEquals(NodeType.PinDomain.FLOAT, NodeType.AMP.inputDomain(1));
        assertEquals(NodeType.PinDomain.AUDIO, NodeType.AMP.outputDomain(0));
        // AUDIO_OUT: all channel input pins are AUDIO
        assertEquals(NodeType.PinDomain.AUDIO, NodeType.AUDIO_OUT.inputDomain(0));
        assertEquals(NodeType.PinDomain.AUDIO, NodeType.AUDIO_OUT.inputDomain(3));
        // Ordinary node stays float
        assertEquals(NodeType.PinDomain.FLOAT, NodeType.CONST.outputDomain(0));
        assertEquals(NodeType.PinDomain.FLOAT, NodeType.ADD.inputDomain(0));
    }

    @Test
    @DisplayName("分类覆盖：每个 NodeType 恰属一类，AUDIO 归 MUSIC/AMP/AUDIO_OUT / category coverage")
    void categoryCoverage() {
        assertEquals(NodeCategory.AUDIO, NodeCategory.of(NodeType.MUSIC));
        assertEquals(NodeCategory.AUDIO, NodeCategory.of(NodeType.AMP));
        assertEquals(NodeCategory.AUDIO, NodeCategory.of(NodeType.AUDIO_OUT));
        // every NodeType is in exactly one category (static init throws otherwise)
        assertEquals(NodeType.values().length, NodeCategory.allTypes().size());
    }

    @Test
    @DisplayName("AUDIO_OUT 单引脚多声道 / AUDIO_OUT is a single multi-channel pin")
    void audioOutSinglePin() {
        GraphNode n = new GraphNode(1, NodeType.AUDIO_OUT, 0, 0);
        assertEquals(1, n.inputs());
        assertEquals("0", n.inputPinId(0));
        assertEquals(0, n.inputPinIndex("0"));
    }

    @Test
    @DisplayName("域兼容连线：AUDIO↔AUDIO 通过，FLOAT→AUDIO 拒绝 / domain-compatible connections")
    void domainCompatibleConnections() {
        NodeGraph g = new NodeGraph();
        GraphNode music = g.addNode(NodeType.MUSIC, 0, 0);
        GraphNode amp = g.addNode(NodeType.AMP, 100, 0);
        GraphNode cst = g.addNode(NodeType.CONST, 0, 100);
        GraphNode out = g.addNode(NodeType.AUDIO_OUT, 200, 0);
        out.signalBands = new ArrayList<>(List.of("舞池"));

        // audio → audio accepted
        assertTrue(g.addConnection(music.id, 0, amp.id, 0), "MUSIC.audio → AMP.audio (AUDIO↔AUDIO)");
        // float → audio rejected
        assertFalse(g.addConnection(cst.id, 0, amp.id, 0), "CONST → AMP.audio (FLOAT→AUDIO) must be rejected");
        // float → gain accepted
        assertTrue(g.addConnection(cst.id, 0, amp.id, 1), "CONST → AMP.gain (FLOAT↔FLOAT)");
        // audio → channel accepted
        assertTrue(g.addConnection(amp.id, 0, out.id, 0), "AMP.audio → AUDIO_OUT.channel (AUDIO↔AUDIO)");
    }

    @Test
    @DisplayName("曲目字段：MUSIC 分配空曲、深拷贝、NBT 往返 / song field: allocate, copy, NBT round trip")
    void songField() {
        GraphNode music = new GraphNode(1, NodeType.MUSIC, 0, 0);
        assertNotNull(music.song, "MUSIC node allocates an empty song");
        assertEquals(0, music.song.noteCount());

        music.song.songName = "Test";
        music.song.putNote(0, 0, 2, 33, 100, 100, 0);
        GraphNode copy = music.shallowCopyWithNewId(2);
        assertNotSame(music.song, copy.song);
        assertEquals("Test", copy.song.songName);
        assertEquals(1, copy.song.noteCount());

        // NBT round trip (MUSIC has no itemParams, so a null registry provider is safe)
        var tag = music.save(null);
        assertTrue(tag.contains("song"));
        GraphNode loaded = GraphNode.load(tag, null);
        assertEquals(NodeType.MUSIC, loaded.type);
        assertNotNull(loaded.song);
        assertEquals("Test", loaded.song.songName);
        assertEquals(1, loaded.song.noteCount());
    }

    @Test
    @DisplayName("MUSIC/AMP 引脚计数与标签 / MUSIC/AMP pin counts and labels")
    void pinCountsAndLabels() {
        GraphNode music = new GraphNode(1, NodeType.MUSIC, 0, 0);
        assertEquals(3, music.inputs());   // play/stop/seek (loop is a pinless button)
        assertEquals(5, music.outputs());  // audio/playing/tick/seconds/done
        // labels are i18n keys: pin.create_schematic_compute.<name>
        assertEquals("pin.create_schematic_compute.play", NodeType.MUSIC.inputLabel(0));
        assertEquals("pin.create_schematic_compute.stop", NodeType.MUSIC.inputLabel(1));
        assertEquals("pin.create_schematic_compute.seek", NodeType.MUSIC.inputLabel(2));
        assertEquals("pin.create_schematic_compute.audio", NodeType.MUSIC.outputLabel(0));
        assertEquals("pin.create_schematic_compute.playing", NodeType.MUSIC.outputLabel(1));
        assertEquals("pin.create_schematic_compute.done", NodeType.MUSIC.outputLabel(4));

        GraphNode amp = new GraphNode(2, NodeType.AMP, 0, 0);
        assertEquals(2, amp.inputs());     // audio + gain (gain param pin)
        assertEquals(1, amp.outputs());
        assertEquals("pin.create_schematic_compute.audio", NodeType.AMP.inputLabel(0));
        assertEquals("pin.create_schematic_compute.gain", NodeType.AMP.inputLabel(1));
    }
}
