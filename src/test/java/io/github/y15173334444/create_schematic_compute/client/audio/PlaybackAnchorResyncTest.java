package io.github.y15173334444.create_schematic_compute.client.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 播放锚点重同步（v1.2.6.2「播放有时会有延迟」修复）。
 * <p>病灶链：渲染卡顿 → 按帧泵停摆 → 队列抽干 → 声道欠载停播 → 重挂；而墙钟锚点
 * (anchorFrames, anchorWallNanos) 只在渲染回调刷新，停播期间冻结——重挂后第一批音符的
 * 目标帧 = 旧锚点 + 停播全程的墙钟推进，混音游标要再爬完这段停播时长才够到，
 * <b>停播多久就晚多久</b>（NBS 编辑器渲染重、帧卡顿最易触发）。冷启动同病：类加载到
 * 首播的墙钟全程被算进第一批。修复 = ensureChannel 在停播/冷启动路径上把锚点成对重置到
 * 当前混音游标（停播期的乐理时间被舍弃而不是拖欠）。</p>
 * <p>换算本体需要音频运行时（OpenAL 声道），无头测试起不了——按仓库结构锁先例做两层：
 * ① 反射行为断言（重置把锚点墙钟拉回当前、帧游标对齐混音游标）；② 源码扫描锁
 * （ensureChannel 的停播路径必须在 attaching 检查之前调用重同步、重同步必须持
 * ANCHOR_LOCK 成对写——拆开写会被 targetFrameFor 撕裂）。</p>
 * <p>
 * Playback-anchor resync (the v1.2.6.2 "sometimes delayed" fix). Failure chain: a render
 * hitch starves the per-frame pump, the queue drains, the channel underruns and stops, the
 * next note re-attaches — but the wall anchor (anchorFrames, anchorWallNanos) only refreshes
 * in the render callback, so it stays frozen at the stop moment. The first batch after
 * re-attach then targets (stale anchor + the whole stall's wall advance) and the mixer
 * cursor must climb the stall duration before reaching it: <b>late by exactly the stall</b>.
 * Cold start shares the disease (class-load-to-first-play lands in the first batch). The fix
 * resets the anchor pair to the current mixer cursor on the stopped/cold path, discarding
 * the stall's musical time instead of owing it. The placement itself needs a live audio
 * runtime, so per repo precedent this test layers a reflection behavioural check over
 * source-scan locks (the resync must sit before the attaching check inside ensureChannel and
 * must write the pair under ANCHOR_LOCK).</p>
 */
class PlaybackAnchorResyncTest {

    private static Object staticField(String name) throws Exception {
        Field f = CscAudioEngine.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static void setStaticField(String name, Object v) throws Exception {
        Field f = CscAudioEngine.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }

    @Test
    @DisplayName("重同步把锚点墙钟拉回当前、帧游标对齐混音游标")
    void resyncRefreshesTheAnchorPair() throws Exception {
        // 造一台混音器并推进游标到已知位置（静音渲染推进游标，等同真实消费）
        // Build a mixer and advance its cursor to a known spot (silent render advances it).
        var mixer = new AudioMixer();
        mixer.render(new float[2 * 1000], 1000);
        assertEquals(1000L, mixer.renderedFrames(), "cursor must advance by the rendered frames");

        // 锚点做成陈旧态：帧游标停在别处、墙钟落后 10 秒（停播期语义）
        // Make the anchor stale: frames parked elsewhere, wall clock 10s behind (stall semantics)
        setStaticField("mixer", mixer);
        setStaticField("anchorFrames", 777_777_777L);
        setStaticField("anchorWallNanos", System.nanoTime() - 10_000_000_000L);

        Method resync = CscAudioEngine.class.getDeclaredMethod("resyncPlaybackAnchor");
        resync.setAccessible(true);
        resync.invoke(null);

        assertEquals(1000L, (long) (Long) staticField("anchorFrames"),
            "anchorFrames must re-align to the mixer cursor after the resync");
        long wall = (long) (Long) staticField("anchorWallNanos");
        long age = System.nanoTime() - wall;
        assertTrue(age >= 0 && age < 1_000_000_000L,
            "anchorWallNanos must come back to ~now after the resync (was 10s stale)");
    }

    @Test
    @DisplayName("结构锁：ensureChannel 在停播路径、attaching 检查之前重同步锚点")
    void ensureChannelResyncsBeforeAttachingCheck() throws Exception {
        var p = java.nio.file.Path.of("src", "main", "java", "io", "github", "y15173334444",
            "create_schematic_compute", "client", "audio", "CscAudioEngine.java");
        var lines = java.nio.file.Files.readAllLines(p);
        int ensure = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("private static synchronized void ensureChannel()")) { ensure = i; break; }
        }
        assertTrue(ensure >= 0, "ensureChannel moved - re-anchor this scan");
        int stoppedCheck = -1, resync = -1, attaching = -1;
        for (int i = ensure; i < Math.min(ensure + 60, lines.size()); i++) {
            String l = lines.get(i);
            if (stoppedCheck < 0 && l.contains("!handle.isStopped()")) stoppedCheck = i;
            if (resync < 0 && l.contains("resyncPlaybackAnchor()")) resync = i;
            if (resync >= 0 && l.contains("if (attaching) return;")) { attaching = i; break; }
        }
        assertTrue(stoppedCheck > 0 && resync > stoppedCheck && attaching > resync,
            "ensureChannel must resyncPlaybackAnchor() after the stopped check and before the "
                + "attaching check - cold start and every re-attach must refresh the stale anchor");
    }

    @Test
    @DisplayName("结构锁：重同步持 ANCHOR_LOCK 成对写（拆开写会被 targetFrameFor 撕裂）")
    void resyncWritesThePairUnderAnchorLock() throws Exception {
        var p = java.nio.file.Path.of("src", "main", "java", "io", "github", "y15173334444",
            "create_schematic_compute", "client", "audio", "CscAudioEngine.java");
        var src = java.nio.file.Files.readString(p);
        int method = src.indexOf("static void resyncPlaybackAnchor()");
        assertTrue(method >= 0, "resyncPlaybackAnchor moved - re-anchor this scan");
        var body = src.substring(method, Math.min(method + 400, src.length()));
        int lock = body.indexOf("synchronized (ANCHOR_LOCK)");
        assertTrue(lock >= 0, "the resync must hold ANCHOR_LOCK");
        // 成对写：锁块内既写 anchorFrames 也写 anchorWallNanos
        // Paired writes: both fields assigned inside the locked block.
        var locked = body.substring(lock);
        assertTrue(locked.contains("anchorFrames =") && locked.contains("anchorWallNanos ="),
            "the resync must write anchorFrames and anchorWallNanos as a pair");
    }
}
