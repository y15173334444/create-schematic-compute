package io.github.y15173334444.create_schematic_compute.blocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K23 / P1-8 / ⑫ / P2-13 / P2-18 / P3-N13 的宿主 tick 编排结构锁。
 * 依据 docs/tech-debt-review-2026-10-11.md §六 的 15 步偏差矩阵，把本批补线钉进源码结构
 * （源码扫描式结构测试，与 {@link DedicatedServerClientRefTest} 同模式——BE tick 本体依赖
 * 世界对象，无法 headless 直测，而编排顺序正是本批的行为变更点）。
 * <p>
 * 断言口径（除雷达外的 11 宿主；雷达归 W2B 批次）：
 * <ul>
 *   <li>⑮ flushPendingFullSync：11/11 在位，且位于 onStopRunning 早退之前（停机宿主也要能
 *       把图推给新编辑者——P1-8）。</li>
 *   <li>⑫ syncIfBandsChanged：12/12 在位（band 编辑不再只经编辑路径或重载到达客户端）。</li>
 *   <li>⑦ recoverConflictedChannels：继承线宿主的既有调用防回退（main 的冲突自愈已由
 *       GraphHost.ensureBusRegistered 统一挂点覆盖，本批不在补线宿主重复挂）。</li>
 *   <li>⑬ broadcastFlipflopDiff：CncGearbox / ProgrammableTransmission（6 参档求值）在位，
 *       位置在 evaluate 之后。</li>
 *   <li>P2-18：SpeedProxy 的 ③⑥⑧⑩⑭ 全部在位；不再有 List.of() 空输入。</li>
 *   <li>Speaker ⑥⑩：refreshInputs / writeOutputs 在位。</li>
 *   <li>P3-N13：Blueprint / ProgramComputer 的 loadAdditional 不再加载即发包。</li>
 *   <li>SpeedProxy 反射退役：全文件不再有 Class.forName / java.lang.reflect。</li>
 * </ul>
 * Structural lock over the host tick orchestration (K23 matrix follow-ups of this batch),
 * using the same source-scan pattern as {@link DedicatedServerClientRefTest}: BE tick bodies
 * need a live world and cannot run headless, yet the ordering IS the behaviour change here.
 * <p>Scope: the 11 non-radar hosts (Radar belongs to the W2B batch).</p>
 */
class HostTickOrchestrationTest {

    private static final Path BLOCKS_DIR = Path.of("src", "main", "java",
        "io", "github", "y15173334444", "create_schematic_compute", "blocks");

    /** 除雷达外的 11 宿主（雷达的 tick 归 W2B 雷达专项，不在此锁）。 */
    private static final List<String> ALL_NON_RADAR_HOSTS = List.of(
        "BlueprintBlockEntity", "ProgramComputerBlockEntity", "AmplifierComputerBlockEntity",
        "SensorBlockEntity", "ControlSeatBlockEntity", "MonitorBlockEntity",
        "KineticGaugeBlockEntity", "CncGearboxBlockEntity", "ProgrammableTransmissionBlockEntity",
        "SpeedProxyBlockEntity", "SpeakerBlockEntity");

    // ══════════ ⑮ flushPendingFullSync（P1-8：8 缺 → 0 缺）══════════

    @Test
    @DisplayName("P1-8: every non-radar host flushes the pending full sync in tick, before the stop path")
    void everyHostFlushesPendingFullSyncBeforeStop() throws IOException {
        for (String host : ALL_NON_RADAR_HOSTS) {
            List<String> tick = methodBody(host + ".java", "public void tick()");
            int flush = indexOf(tick, "flushPendingFullSync()", host, "step 15 missing");
            int stop = indexOf(tick, "onStopRunning()", host, "stop path missing");
            assertTrue(flush < stop,
                host + ": flushPendingFullSync must run before the onStopRunning early return "
                    + "so a stopped host still ships its graph to a newly joined editor");
        }
    }

    // ══════════ ⑫ syncIfBandsChanged（12/12 宿主在位）══════════

    @Test
    @DisplayName("step 12: every host runs syncIfBandsChanged in tick (band edits reach clients without the edit path)")
    void everyHostSyncsBandsOnTick() throws IOException {
        for (String host : ALL_NON_RADAR_HOSTS) {
            List<String> tick = methodBody(host + ".java", "public void tick()");
            indexOf(tick, "syncIfBandsChanged", host, "step 12 missing");
        }
        // 雷达不在 ALL_NON_RADAR_HOSTS，但第 ⑫ 步同样在位。
        // The radar is not in ALL_NON_RADAR_HOSTS, but its step 12 is wired the same way.
        List<String> radar = methodBody("RadarBlockEntity.java", "public void tick()");
        indexOf(radar, "syncIfBandsChanged", "RadarBlockEntity", "step 12 missing");
    }

    // ══════════ ⑦ recoverConflictedChannels（继承线既有调用防回退；main 的冲突自愈
    // 已由 GraphHost.ensureBusRegistered 每 tick 统一挂点——图停止也跑——覆盖全部宿主，
    // 本批的六宿主补线不再迁移，避免同一 tick 双跑自愈）══════════

    @Test
    @DisplayName("the inheritance-line hosts keep their recover call")
    void inheritanceLineKeepsRecover() throws IOException {
        for (String host : List.of("BlueprintBlockEntity", "ProgramComputerBlockEntity",
                "AmplifierComputerBlockEntity", "SensorBlockEntity", "ControlSeatBlockEntity")) {
            List<String> tick = methodBody(host + ".java", "public void tick()");
            indexOf(tick, "recoverConflictedChannels", host, "step 7 regression");
        }
    }

    // ══════════ ⑬ broadcastFlipflopDiff（P2-13：6 参档缺 → 补齐）══════════

    @Test
    @DisplayName("P2-13: CncGearbox and Transmission broadcast the flipflop diff after evaluate")
    void fullStateHostsBroadcastFlipflopDiff() throws IOException {
        for (String host : List.of("CncGearboxBlockEntity", "ProgrammableTransmissionBlockEntity")) {
            List<String> tick = methodBody(host + ".java", "public void tick()");
            int evaluate = indexOf(tick, ".evaluate(", host, "evaluate missing");
            int diff = indexOf(tick, "broadcastFlipflopDiff()", host, "step 13 missing");
            assertTrue(diff > evaluate, host + ": flipflop diff must broadcast after the evaluation");
        }
    }

    @Test
    @DisplayName("P2-13 guard: AmplifierComputer/Speaker/Blueprint/ProgramComputer keep their diff call")
    void otherFullStateHostsKeepFlipflopDiff() throws IOException {
        for (String host : List.of("AmplifierComputerBlockEntity", "SpeakerBlockEntity",
                "BlueprintBlockEntity", "ProgramComputerBlockEntity")) {
            List<String> tick = methodBody(host + ".java", "public void tick()");
            indexOf(tick, "broadcastFlipflopDiff()", host, "step 13 regression");
        }
    }

    // ══════════ P2-18：SpeedProxy 缺步补齐 ══════════

    @Test
    @DisplayName("P2-18: SpeedProxy runs the full L-variant orchestration (3/6/8/10/14)")
    void speedProxyRunsFullLightOrchestration() throws IOException {
        List<String> tick = methodBody("SpeedProxyBlockEntity.java", "public void tick()");
        indexOf(tick, "checkGraphChanged", "SpeedProxy", "step 3 missing (redstone link self-heal)");
        indexOf(tick, "refreshInputs", "SpeedProxy", "step 6 missing");
        indexOf(tick, "buildInputs", "SpeedProxy", "step 8 missing");
        indexOf(tick, "writeOutputs", "SpeedProxy", "step 10 missing");
        indexOf(tick, "setChanged()", "SpeedProxy", "step 14 missing (running-state persistence)");
        assertFalse(tick.stream().anyMatch(l -> l.contains("List.of()")),
            "SpeedProxy must feed the evaluator from the graph's redstone inputs, not an empty list");
    }

    @Test
    @DisplayName("P2-18: SpeedProxy re-registers redstone links on graph change (checkGraphChanged before the stop path)")
    void speedProxyChecksGraphChangedBeforeStop() throws IOException {
        List<String> tick = methodBody("SpeedProxyBlockEntity.java", "public void tick()");
        int check = indexOf(tick, "checkGraphChanged", "SpeedProxy", "step 3 missing");
        int stop = indexOf(tick, "onStopRunning()", "SpeedProxy", "stop path missing");
        assertTrue(check < stop,
            "checkGraphChanged must precede the stop early return so link registration "
                + "tracks graph generation even while the host is stopped (Monitor order)");
    }

    // ══════════ Speaker ⑥⑩（P3 宿主面：红石面残缺）══════════

    @Test
    @DisplayName("Speaker refreshes redstone inputs and writes outputs")
    void speakerRefreshesInputsAndWritesOutputs() throws IOException {
        List<String> tick = methodBody("SpeakerBlockEntity.java", "public void tick()");
        indexOf(tick, "refreshInputs", "Speaker", "step 6 missing");
        indexOf(tick, "writeOutputs", "Speaker", "step 10 missing");
    }

    // ══════════ P3-N13：加载即发包口径统一 ══════════

    @Test
    @DisplayName("P3-N13: Blueprint/ProgramComputer no longer push a packet from loadAdditional")
    void loadAdditionalDoesNotShipPackets() throws IOException {
        for (String host : List.of("BlueprintBlockEntity", "ProgramComputerBlockEntity")) {
            List<String> load = methodBody(host + ".java", "loadAdditional(");
            assertFalse(load.stream().anyMatch(l -> l.contains("sendBlockUpdated")),
                host + ": loadAdditional must not fire a whole-graph packet on load — clients "
                    + "already receive getUpdateTag data with the chunk packet; runtime full "
                    + "syncs go through needsFullSync + flushPendingFullSync (P3-N13)");
        }
    }

    // ══════════ SpeedProxy 反射退役（P3 依赖面）══════════

    @Test
    @DisplayName("SpeedProxy talks to Create's public API directly — no reflection remains")
    void speedProxyUsesDirectCreateApi() throws IOException {
        List<String> lines = Files.readAllLines(BLOCKS_DIR.resolve("SpeedProxyBlockEntity.java"),
            StandardCharsets.UTF_8);
        // 扫描口径：只看代码行——注释/javadoc 里对历史的提及不算引用。
        // Scan code lines only — historical mentions inside comments/javadoc are not references.
        List<String> code = lines.stream().filter(l -> {
            String t = l.trim();
            return !t.isEmpty() && !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*");
        }).toList();
        assertFalse(code.stream().anyMatch(l -> l.contains("Class.forName")),
            "reflection retirement: no Class.forName left");
        assertFalse(code.stream().anyMatch(l -> l.contains("java.lang.reflect")),
            "reflection retirement: no java.lang.reflect left");
        assertTrue(code.stream().anyMatch(l ->
                l.contains("import com.simibubi.create.content.kinetics.speedController.SpeedControllerBlockEntity")),
            "direct connect: SpeedControllerBlockEntity must be imported");
    }

    @Test
    @DisplayName("P1-8 note: flushPendingFullSync bridge is deliberately not @Deprecated")
    void flushBridgeIsNotDeprecated() throws IOException {
        List<String> lines = Files.readAllLines(
            BLOCKS_DIR.resolve("SyncedGraphBlockEntity.java"), StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).contains("protected void flushPendingFullSync()")) continue;
            String prev = i > 0 ? lines.get(i - 1).trim() : "";
            assertFalse(prev.startsWith("@Deprecated"),
                "the flush bridge is the only callable path for inheritance-line hosts and a "
                    + "per-tick orchestration step — it must not be @Deprecated (see its javadoc)");
            return;
        }
        fail("flushPendingFullSync bridge not found in SyncedGraphBlockEntity");
    }

    // ── 扫描助手 / scan helpers ──

    /** 取 blocks/ 下指定文件中第一个匹配签名的方法体（大括号配平），逐行去除注释与字符串后返回
     *  ——顺序断言只看真实代码行，双语注释里提到的标识符不得干扰下标比较。 */
    private static List<String> methodBody(String fileName, String signature) throws IOException {
        assertTrue(Files.isDirectory(BLOCKS_DIR), "blocks dir missing: " + BLOCKS_DIR);
        try (Stream<Path> files = Files.list(BLOCKS_DIR)) {
            Path file = files.filter(p -> p.getFileName().toString().equals(fileName)).findFirst()
                .orElseThrow(() -> new AssertionError("file not found: " + fileName));
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).contains(signature)) continue;
                int depth = 0;
                boolean opened = false;
                for (int j = i; j < lines.size(); j++) {
                    for (char c : stripStringsAndComments(lines.get(j)).toCharArray()) {
                        if (c == '{') { depth++; opened = true; }
                        else if (c == '}') depth--;
                    }
                    if (opened && depth == 0)
                        return lines.subList(i, j + 1).stream()
                            .map(HostTickOrchestrationTest::stripStringsAndComments)
                            .toList();
                }
            }
        }
        throw new AssertionError("method not found in " + fileName + ": " + signature);
    }

    /** 去除行内字符串字面量与注释，避免大括号计数被内容干扰。 */
    private static String stripStringsAndComments(String line) {
        String noComment = line;
        int comment = noComment.indexOf("//");
        if (comment >= 0) noComment = noComment.substring(0, comment);
        return noComment.replaceAll("\"[^\"]*\"", "\"\"");
    }

    /** 断言主体中存在包含 needle 的行并返回其下标；缺失即带 host 语境失败。 */
    private static int indexOf(List<String> body, String needle, String host, String what) {
        for (int i = 0; i < body.size(); i++)
            if (body.get(i).contains(needle)) return i;
        fail(host + ": " + what + " — expected a line containing \"" + needle + "\"");
        return -1; // unreachable
    }
}
