package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MUSIC 播放编辑区：输入激活（level=1 高电平持续播放/拉低即停，level=0 上升沿开始播放）
 * × 起播（fromHead=1 从头，0 继续）。缺槽默认 = 沿 + 继续（旧行为）。V7→V8 迁移为旧节点
 * 补 level/fromHead 槽（默认 0，不覆盖用户已设值）。
 * MUSIC playback edit panel: input activation (level=1 high keeps playing / low stops,
 * level=0 rising-edge starts) × start policy (fromHead=1 from the head, 0 continue).
 * Missing slots default to edge + continue — the legacy behaviour. V7→V8 migration fills
 * the level/fromHead slots (default 0) on legacy nodes without overwriting user-set values.
 */
class MusicPlayModesTest {

    /** tempo=1000 (10 t/s) → dt=0.1s ⇒ 1 NBS tick of head per playing advance. */
    private static final float DT = 0.1f;

    private static NbsSong song(int... ticks) {
        NbsSong s = new NbsSong();
        s.tempo = 1000;
        for (int t : ticks) s.putNote(t, 0, 2, 45, 100, 100, 0);
        return s;
    }

    /** 持续跑的求值器：MUSIC + play/stop 触点；每拍改电平再 evaluate（状态跨拍留驻）。
     *  A long-lived rig: MUSIC + play/stop contacts; one evaluate per tick with the
     *  levels mutated in between (edge/transport state persists across ticks). */
    private static final class Rig {
        final GraphEvaluator ev;
        final GraphNode music;
        final GraphNode play, stop;
        final Map<Integer, Float> pidState = new HashMap<>();

        Rig(float level, float fromHead) {
            NodeGraph g = new NodeGraph();
            play = g.addNode(NodeType.CONST, 0, 0);
            stop = g.addNode(NodeType.CONST, 0, 50);
            music = g.addNode(NodeType.MUSIC, 100, 0);
            music.song = song(0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
                10, 11, 12, 13, 14, 15, 16, 17, 18, 19);
            music.params[0] = 0f; // loop off
            music.params[1] = level;
            music.params[2] = fromHead;
            assertTrue(g.addConnection(play.id, 0, music.id, 0));
            assertTrue(g.addConnection(stop.id, 0, music.id, 1));
            ev = new GraphEvaluator(g);
            ev.restoreSubState(new RuntimeState());
            ev.setAudioTransports(new HashMap<>());
        }

        void tick(float playLevel, float stopLevel) {
            play.params[0] = playLevel;
            stop.params[0] = stopLevel;
            ev.evaluate(new ArrayList<GraphEvaluator.InputSource>(), pidState, DT);
        }

        boolean playing() { return ev.getNodeOutput(music.id, 1) > 0.5f; }
        int head() { return (int) ev.getNodeOutput(music.id, 2); }
    }

    @Test
    @DisplayName("level=1: high keeps playing, falling stops and keeps the head, high again resumes")
    void levelGateStopsOnFallingAndResumes() {
        Rig r = new Rig(1f, 0f);
        r.tick(1f, 0f);
        assertTrue(r.playing());
        int headAtStart = r.head();
        r.tick(1f, 0f);
        r.tick(1f, 0f);
        assertTrue(r.playing(), "sustained high keeps playback going");
        assertTrue(r.head() > headAtStart, "the head advances while high");
        int headAtStop = r.head();
        r.tick(0f, 0f);
        assertFalse(r.playing(), "falling stops playback in level mode");
        assertEquals(headAtStop, r.head(), "the head survives the stop");
        r.tick(1f, 0f);
        assertTrue(r.playing(), "a fresh high resumes playback");
        assertTrue(r.head() > headAtStart, "continue policy keeps the old head (no rewind)");
    }

    @Test
    @DisplayName("level=1, fromHead=1: every fresh high rewinds to the head")
    void levelGateFromHeadRewindsEveryActivation() {
        Rig r = new Rig(1f, 1f);
        r.tick(1f, 0f);
        r.tick(1f, 0f);
        r.tick(1f, 0f);
        assertTrue(r.head() > 1, "played a few ticks first");
        r.tick(0f, 0f);
        r.tick(1f, 0f);
        assertTrue(r.playing());
        assertTrue(r.head() <= 1, "the fresh activation rewinds to the head");
    }

    @Test
    @DisplayName("level=0: a falling play level is ignored — playback runs on")
    void edgeModeIgnoresFalling() {
        Rig r = new Rig(0f, 0f);
        r.tick(1f, 0f);
        int headAfterStart = r.head();
        r.tick(0f, 0f);
        assertTrue(r.playing(), "edge mode ignores the falling edge");
        assertTrue(r.head() > headAfterStart);
    }

    @Test
    @DisplayName("level=0, fromHead=1: each rising edge retriggers from the head")
    void edgeModeFromHeadRetriggersOnRising() {
        Rig r = new Rig(0f, 1f);
        r.tick(1f, 0f);
        r.tick(0f, 0f);
        r.tick(0f, 0f);
        assertTrue(r.head() > 1, "played past the head first");
        r.tick(1f, 0f);
        assertTrue(r.playing());
        assertTrue(r.head() <= 1, "the rising edge retriggers from the head");
    }

    @Test
    @DisplayName("missing slots behave as legacy: rising-edge start + continue")
    void missingSlotsDefaultToLegacyBehaviour() {
        Rig r = new Rig(0f, 0f);
        r.music.params = new float[]{0f}; // 旧形态：只有 loop 槽 / legacy shape: loop only
        r.tick(1f, 0f);
        assertTrue(r.playing());
        int headAfterStart = r.head();
        r.tick(0f, 0f);
        assertTrue(r.playing(), "falling must not stop (legacy rising-edge start)");
        assertTrue(r.head() > headAfterStart);
        // 播一段后沿模式下再触发 = 续播 / a retrigger continues (legacy policy)
        r.tick(0f, 0f);
        int headBeforeRetrigger = r.head();
        r.tick(1f, 0f);
        assertTrue(r.playing());
        assertTrue(r.head() >= headBeforeRetrigger, "continue policy does not rewind");
    }

    @Test
    @DisplayName("the stop pin still stops regardless of the activation mode")
    void stopPinStillStops() {
        Rig r = new Rig(1f, 0f);
        r.tick(1f, 0f);
        r.tick(1f, 1f);
        assertFalse(r.playing(), "stop pin wins while the play level is high");
    }

    // ── V7→V8 迁移 / migration ──────────────────────────────────────────

    private static CompoundTag musicNbt(int id, int pcount, Float p0) {
        CompoundTag n = new CompoundTag();
        n.putInt("id", id);
        n.putString("type", "music");
        n.putFloat("x", 0);
        n.putFloat("y", 0);
        n.putInt("pcount", pcount);
        if (p0 != null) n.putFloat("p0", p0);
        return n;
    }

    private static CompoundTag graphOf(CompoundTag... nodes) {
        CompoundTag g = new CompoundTag();
        ListTag list = new ListTag();
        for (CompoundTag n : nodes) list.add(n);
        g.put("nodes", list);
        g.putInt(NbtVersions.VERSION_KEY, 7);
        return g;
    }

    @Test
    @DisplayName("V7→V8 fills the missing level/fromHead slots with 0 (legacy behaviour)")
    void migrateFillsPlaybackSlots() {
        CompoundTag out = GraphMigration.migrate(graphOf(musicNbt(7, 1, 1f)), null);
        assertEquals(NbtVersions.DATA_VERSION, NbtVersions.getVersion(out));
        CompoundTag n = out.getList("nodes", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
        assertEquals(3, n.getInt("pcount"));
        assertEquals(1f, n.getFloat("p0"), 1e-6f, "the user-set loop slot survives");
        assertEquals(0f, n.getFloat("p1"), 1e-6f, "level 0 = rising-edge start (legacy)");
        assertEquals(0f, n.getFloat("p2"), 1e-6f, "fromHead 0 = continue (legacy)");
    }

    @Test
    @DisplayName("V7→V8 never overwrites user-set playback slots")
    void migrateKeepsUserSetSlots() {
        CompoundTag n = musicNbt(8, 2, 1f);
        n.putFloat("p1", 1f); // 用户已选高电平 / user picked level mode
        CompoundTag out = GraphMigration.migrate(graphOf(n), null);
        CompoundTag on = out.getList("nodes", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
        assertEquals(3, on.getInt("pcount"));
        assertEquals(1f, on.getFloat("p1"), 1e-6f, "user-picked level mode survives");
        assertEquals(0f, on.getFloat("p2"), 1e-6f, "the missing fromHead slot is still filled");
    }
}
