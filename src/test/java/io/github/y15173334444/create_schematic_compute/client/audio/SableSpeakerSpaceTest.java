package io.github.y15173334444.create_schematic_compute.client.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sable 结构内音响发声坐标换算的结构锁（v1.2.6.2）。
 * 换算本体依赖 Sable 运行时（FML ModList / ClientSubLevel），无头测试无法起——
 * 按仓库结构锁先例（HostTickOrchestrationTest / ChannelLayoutRewireTest）做源码扫描，
 * 钉住三个易在重构中丢掉的决策：
 * <ol>
 *   <li>NoteEventPacket.playClient 必须先过 SableSpeakerSpace.toWorldSpace 再播——
 *       丢了它「Sable 结构上的音响没有声音」原样复发（发声点落回绘图网格保留区）。</li>
 *   <li>SableSpeakerSpace 必须用 <b>logicalPose</b>、不得出现 renderPose——renderPose 按
 *       partialTick 原地改写共享 Pose3d，音频网络线程调用会撕裂真正的渲染姿态（视觉毛刺）。</li>
 *   <li>Sable 访问必须 catch Throwable 回落 null（原坐标播）——Sable 版本线外 API 漂移
 *       不能把音频路径整个打崩。</li>
 * </ol>
 * <p>
 * Structural locks over the Sable speaker-space resolution (v1.2.6.2). The resolution needs
 * a live Sable runtime (FML ModList / ClientSubLevel) and cannot run headless, so per the
 * repo's structural-lock precedent (HostTickOrchestrationTest / ChannelLayoutRewireTest)
 * this scans the sources for three decisions a refactor must not lose: playClient resolves
 * through SableSpeakerSpace before playing (drop it and the Sable silence returns);
 * SableSpeakerSpace uses logicalPose and never renderPose (renderPose mutates the shared
 * pose in place - a network-thread call tears the real render pose); and the Sable access
 * catches Throwable into a null fallback (an out-of-range Sable line must not crash the
 * audio path).
 */
class SableSpeakerSpaceTest {

    private static String read(String... parts) throws Exception {
        var p = java.nio.file.Path.of("src", "main", "java", "io", "github", "y15173334444",
            "create_schematic_compute");
        for (String s : parts) p = p.resolve(s);
        return java.nio.file.Files.readString(p);
    }

    @Test
    @DisplayName("playClient 先过 SableSpeakerSpace 再播（丢了即 Sable 结构无声复发）")
    void playClientResolvesThroughSableSpeakerSpace() throws Exception {
        var src = read("network", "NoteEventPacket.java");
        int resolve = src.indexOf("SableSpeakerSpace.toWorldSpace(speakerPos)");
        int loop = src.indexOf("for (NoteEvent e : events) playAt(");
        assertTrue(resolve >= 0, "playClient must consult SableSpeakerSpace.toWorldSpace");
        assertTrue(loop > resolve,
            "the per-note playAt loop must run after the world-space resolution");
        // fallback 形态在位：null → 原坐标 +0.5（非 Sable 世界零变化）
        // The fallback shape is in place: null → the raw +0.5 center (no change off-Sable)
        assertTrue(src.contains("worldSpace != null ? worldSpace.x : speakerPos.getX() + 0.5"),
            "the raw-coordinate fallback must survive refactors");
    }

    @Test
    @DisplayName("SableSpeakerSpace 用 logicalPose、禁 renderPose（网络线程撕裂渲染姿态）")
    void resolutionUsesLogicalPoseNeverRenderPose() throws Exception {
        var src = read("client", "audio", "SableSpeakerSpace.java");
        assertTrue(src.contains("logicalPose().transformPosition"),
            "the transform must ride logicalPose (read-only snapshot semantics)");
        // 扫调用形态而非裸词：类头 javadoc 里的「绝不用 renderPose」说明本身含该词。
        // Scan the call shape, not the bare word - the class javadoc's own warning mentions it.
        assertFalse(src.contains(".renderPose()"),
            "renderPose mutates the shared Pose3d in place per partialTick - a network-thread "
                + "call tears the real render pose; never call it here");
    }

    @Test
    @DisplayName("Sable 访问 catch Throwable 回落 null（版本线外不崩音频路径）")
    void sableAccessFallsBackToNull() throws Exception {
        var src = read("client", "audio", "SableSpeakerSpace.java");
        assertTrue(src.contains("catch (Throwable"), "the Sable access must be fail-closed to null");
        assertTrue(src.contains("ModList.get().isLoaded(\"sable\")"),
            "the availability gate must ride the ModList check (Sable is an optional dependency)");
    }
}
