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
 * K23 / P1-8 / P2-12 / P2-13 / P2-18 / P3-N13 的宿主 tick 编排结构锁。
 * 依据 docs/tech-debt-review-2026-10-11.md §六 的 15 步偏差矩阵，把本批补线钉进源码结构
 * （源码扫描式结构测试，与 {@link DedicatedServerClientRefTest} 同模式——BE tick 本体依赖
 * 世界对象，无法 headless 直测，而编排顺序正是本批的行为变更点）。
 * <p>
 * K23 收敛后的锚点形态：15 步共同编排住 {@code GraphHost.tickBody} 骨架，各宿主 tick()
 * 只委托骨架、宿主偏差化为 TickHooks 声明/钩子。断言相应重锚到「骨架含各步且顺序合矩阵
 * + 各宿主委托骨架 + 各宿主偏差声明与矩阵一致」，每条既有断言语义不削弱：
 * <ul>
 *   <li>⑮ flushPendingFullSync 在骨架中先于 ⑤ 停机路径（P1-8），11 个非雷达宿主委托骨架。</li>
 *   <li>⑦ recoverConflictedChannels 在骨架中位于 refreshInputs 之后、buildInputs 之前
 *       （Blueprint 样板），六补线宿主委托骨架。</li>
 *   <li>⑬ broadcastFlipflopDiff 在骨架中位于 evaluate 之后；CncGearbox / PT /
 *       AmplifierComputer / Speaker / Blueprint / ProgramComputer 声明在位。</li>
 *   <li>P2-18：SpeedProxy 声明 L 档 + AFTER_GRAPH_CHECK 停机位（checkGraphChanged 先于
 *       停机早退），求值输入恒来自 buildInputs（骨架无 List.of() 空表）。</li>
 *   <li>Speaker ⑥⑩：骨架的 refreshInputs / writeOutputs 为无条件步，Speaker 委托骨架。</li>
 *   <li>P3-N13：Blueprint / ProgramComputer 的 loadAdditional 不再加载即发包（原锚保留）。</li>
 *   <li>SpeedProxy 反射退役：全文件不再有 Class.forName / java.lang.reflect（原锚保留）。</li>
 * </ul>
 * Structural lock over the host tick orchestration, re-anchored after the K23 convergence:
 * the shared 15-step body lives in {@code GraphHost.tickBody}, each host tick() delegates,
 * and host deviations are TickHooks declarations. Every prior assertion keeps its meaning
 * (steps in the skeleton in matrix order + per-host delegation + per-host declarations).
 */
class HostTickOrchestrationTest {

    private static final Path BLOCKS_DIR = Path.of("src", "main", "java",
        "io", "github", "y15173334444", "create_schematic_compute", "blocks");

    /** 除雷达外的 11 宿主（雷达的 tick 细节归 W2B 雷达批次）。 */
    private static final List<String> ALL_NON_RADAR_HOSTS = List.of(
        "BlueprintBlockEntity", "ProgramComputerBlockEntity", "AmplifierComputerBlockEntity",
        "SensorBlockEntity", "ControlSeatBlockEntity", "MonitorBlockEntity",
        "KineticGaugeBlockEntity", "CncGearboxBlockEntity", "ProgrammableTransmissionBlockEntity",
        "SpeedProxyBlockEntity", "SpeakerBlockEntity");

    /** 全部 12 宿主（委托骨架的口径）。 */
    private static final List<String> ALL_HOSTS = List.of(
        "BlueprintBlockEntity", "ProgramComputerBlockEntity", "AmplifierComputerBlockEntity",
        "SensorBlockEntity", "ControlSeatBlockEntity", "MonitorBlockEntity",
        "KineticGaugeBlockEntity", "CncGearboxBlockEntity", "ProgrammableTransmissionBlockEntity",
        "RadarBlockEntity", "SpeedProxyBlockEntity", "SpeakerBlockEntity");

    /** 骨架方法体（GraphHost.tickBody）——顺序断言的唯一锚点。 */
    private static List<String> skeleton() throws IOException {
        return methodBody("GraphHost.java", "public void tickBody(");
    }

    // ══════════ 骨架步序锁（15 步矩阵顺序）══════════

    @Test
    @DisplayName("K23: the skeleton runs the canonical 15 steps in matrix order")
    void skeletonFollowsTheCanonicalMatrixOrder() throws IOException {
        List<String> s = skeleton();
        int bus = indexOf(s, "ensureBusRegistered()", "GraphHost", "step 1 missing");
        int flush = indexOf(s, "flushPendingFullSync()", "GraphHost", "step 15 missing");
        int lit = indexOf(s, "litProperty()", "GraphHost", "step 2 missing");
        int check = indexOf(s, "checkGraphChanged", "GraphHost", "step 3 missing");
        int recompile = indexOf(s, "recompileEvaluator", "GraphHost", "step 4 missing");
        int refresh = indexOf(s, "refreshInputs", "GraphHost", "step 6 missing");
        int recover = indexOf(s, "recoverConflictedChannels", "GraphHost", "step 7 missing");
        int build = indexOf(s, "buildInputs", "GraphHost", "step 8 missing");
        int evaluate = indexOf(s, ".evaluate(", "GraphHost", "step 9 missing");
        int write = indexOf(s, "writeOutputs", "GraphHost", "step 10 missing");
        int snapshot = indexOf(s, "broadcastEvalSnapshot()", "GraphHost", "step 11 missing");
        int bands = indexOf(s, "syncIfBandsChanged", "GraphHost", "step 12 missing");
        int flipflop = indexOf(s, "broadcastFlipflopDiff", "GraphHost", "step 13 missing");
        int dirty = indexOf(s, "markDirty()", "GraphHost", "step 14 missing");

        assertTrue(bus < flush, "① ensureBusRegistered must precede ⑮ flushPendingFullSync");
        assertTrue(flush < lit, "⑮ must precede ② LIT (P1-8: flush before every early return)");
        assertTrue(lit < check, "② LIT must precede ③ checkGraphChanged");
        assertTrue(check < recompile, "③ must precede ④ recompile");
        assertTrue(recompile < refresh, "④ must precede ⑥ refreshInputs");
        assertTrue(refresh < recover, "⑥ must precede ⑦ recover (P2-12 position)");
        assertTrue(recover < build, "⑦ must precede ⑧ buildInputs (P2-12 position)");
        assertTrue(build < evaluate, "⑧ must precede ⑨ evaluate");
        assertTrue(evaluate < write, "⑨ must precede ⑩ writeOutputs");
        assertTrue(write < snapshot, "⑩ must precede ⑪ broadcastEvalSnapshot");
        assertTrue(snapshot < bands, "⑪ must precede ⑫ syncIfBandsChanged");
        assertTrue(bands < flipflop, "⑫ must precede ⑬ broadcastFlipflopDiff");
        assertTrue(evaluate < flipflop, "⑬ must broadcast after ⑨ evaluate (P2-13 position)");
        assertTrue(flipflop < dirty, "⑬ must precede ⑭ setChanged/markDirty");
    }

    // ══════════ ⑮ flushPendingFullSync（P1-8：8 缺 → 0 缺）══════════

    @Test
    @DisplayName("P1-8: the skeleton flushes the pending full sync before the stop path, and the eleven non-radar hosts delegate")
    void everyHostFlushesPendingFullSyncBeforeStop() throws IOException {
        List<String> s = skeleton();
        int flush = indexOf(s, "flushPendingFullSync()", "GraphHost", "step 15 missing");
        int stop = indexOf(s, "stopBody", "GraphHost", "stop path missing");
        assertTrue(flush < stop,
            "GraphHost: flushPendingFullSync must run before the stop path "
                + "so a stopped host still ships its graph to a newly joined editor");
        for (String host : ALL_NON_RADAR_HOSTS)
            delegatesToSkeleton(host);
    }

    // ══════════ ⑦ recoverConflictedChannels（P2-12：6 缺 → 0 缺）══════════

    @Test
    @DisplayName("P2-12: the skeleton runs recoverConflictedChannels between refreshInputs and buildInputs, and the six healed hosts delegate")
    void healedHostsRunRecoverBetweenRefreshAndBuild() throws IOException {
        List<String> s = skeleton();
        int recover = indexOf(s, "recoverConflictedChannels", "GraphHost", "step 7 missing");
        int refresh = indexOf(s, "refreshInputs", "GraphHost", "refreshInputs missing");
        int build = indexOf(s, "buildInputs", "GraphHost", "buildInputs missing");
        assertTrue(recover > refresh, "GraphHost: recover must run after refreshInputs");
        assertTrue(recover < build, "GraphHost: recover must run before buildInputs");
        for (String host : List.of("MonitorBlockEntity", "KineticGaugeBlockEntity",
                "SpeedProxyBlockEntity", "SpeakerBlockEntity", "CncGearboxBlockEntity",
                "ProgrammableTransmissionBlockEntity"))
            delegatesToSkeleton(host);
    }

    @Test
    @DisplayName("P2-12 guard: the inheritance-line hosts still reach recover through the skeleton")
    void inheritanceLineKeepsRecover() throws IOException {
        indexOf(skeleton(), "recoverConflictedChannels", "GraphHost", "step 7 regression");
        for (String host : List.of("BlueprintBlockEntity", "ProgramComputerBlockEntity",
                "AmplifierComputerBlockEntity", "SensorBlockEntity", "ControlSeatBlockEntity"))
            delegatesToSkeleton(host);
    }

    // ══════════ ⑬ broadcastFlipflopDiff（P2-13：6 参档缺 → 补齐）══════════

    @Test
    @DisplayName("P2-13: the flipflop diff broadcasts after evaluate, and CncGearbox/Transmission enable it")
    void fullStateHostsBroadcastFlipflopDiff() throws IOException {
        List<String> s = skeleton();
        int evaluate = indexOf(s, ".evaluate(", "GraphHost", "evaluate missing");
        int diff = indexOf(s, "broadcastFlipflopDiff", "GraphHost", "step 13 missing");
        assertTrue(diff > evaluate, "GraphHost: flipflop diff must broadcast after the evaluation");
        for (String host : List.of("CncGearboxBlockEntity", "ProgrammableTransmissionBlockEntity")) {
            assertTrue(fileContains(host, "wantsFlipflopDiff"),
                host + ": step 13 must be enabled (wantsFlipflopDiff)");
            delegatesToSkeleton(host);
        }
    }

    @Test
    @DisplayName("P2-13 guard: AmplifierComputer/Speaker/Blueprint/ProgramComputer keep their diff call")
    void otherFullStateHostsKeepFlipflopDiff() throws IOException {
        for (String host : List.of("AmplifierComputerBlockEntity", "SpeakerBlockEntity",
                "BlueprintBlockEntity", "ProgramComputerBlockEntity")) {
            assertTrue(fileContains(host, "wantsFlipflopDiff"),
                host + ": step 13 regression (wantsFlipflopDiff must stay enabled)");
            delegatesToSkeleton(host);
        }
    }

    // ══════════ P2-18：SpeedProxy 缺步补齐 ══════════

    @Test
    @DisplayName("P2-18: SpeedProxy runs the full L-variant orchestration via the skeleton (3/6/8/10/14)")
    void speedProxyRunsFullLightOrchestration() throws IOException {
        List<String> s = skeleton();
        // 五步住骨架且无条件在位（SpeedProxy 经委托全部走到）。
        // The five steps live in the skeleton unconditionally; delegation runs them all.
        indexOf(s, "checkGraphChanged", "GraphHost", "step 3 missing (redstone link self-heal)");
        indexOf(s, "refreshInputs", "GraphHost", "step 6 missing");
        indexOf(s, "buildInputs", "GraphHost", "step 8 missing");
        indexOf(s, "writeOutputs", "GraphHost", "step 10 missing");
        indexOf(s, "markDirty()", "GraphHost", "step 14 missing (running-state persistence)");
        assertFalse(s.stream().anyMatch(l -> l.contains("List.of()")),
            "the skeleton must feed the evaluator from the graph's redstone inputs, not an empty list");
        assertTrue(fileContains("SpeedProxyBlockEntity", "lightRecompile"),
            "SpeedProxy: L-variant recompile declaration missing");
        assertTrue(fileContains("SpeedProxyBlockEntity", "EvalVariant.BASIC"),
            "SpeedProxy: 3-arg evaluate declaration missing");
        delegatesToSkeleton("SpeedProxyBlockEntity");
    }

    @Test
    @DisplayName("P2-18: SpeedProxy's stop check sits after checkGraphChanged (Monitor order)")
    void speedProxyChecksGraphChangedBeforeStop() throws IOException {
        List<String> s = skeleton();
        int check = indexOf(s, "checkGraphChanged", "GraphHost", "step 3 missing");
        int stop = indexOf(s, "StopSlot.AFTER_GRAPH_CHECK", "GraphHost", "Monitor-order stop slot missing");
        assertTrue(check < stop,
            "checkGraphChanged must precede the AFTER_GRAPH_CHECK stop early return so link "
                + "registration tracks graph generation even while the host is stopped (Monitor order)");
        assertTrue(fileContains("SpeedProxyBlockEntity", "StopSlot.AFTER_GRAPH_CHECK"),
            "SpeedProxy: the Monitor-order stop slot must be declared");
    }

    // ══════════ Speaker ⑥⑩（P3 宿主面：红石面残缺）══════════

    @Test
    @DisplayName("Speaker refreshes redstone inputs and writes outputs through the skeleton")
    void speakerRefreshesInputsAndWritesOutputs() throws IOException {
        List<String> s = skeleton();
        indexOf(s, "refreshInputs", "GraphHost", "step 6 missing");
        indexOf(s, "writeOutputs", "GraphHost", "step 10 missing");
        delegatesToSkeleton("SpeakerBlockEntity");
    }

    // ══════════ ②④⑤⑥⑨ 偏差声明与矩阵一致 ══════════

    @Test
    @DisplayName("K23: every host declares its matrix step-9 evaluate variant")
    void evalVariantsMatchTheMatrix() throws IOException {
        assertDeclares("BlueprintBlockEntity", "EvalVariant.FULL_STATE");
        assertDeclares("ProgramComputerBlockEntity", "EvalVariant.FULL_STATE");
        assertDeclares("AmplifierComputerBlockEntity", "EvalVariant.FULL_STATE");
        assertDeclares("SensorBlockEntity", "EvalVariant.SEAT_STATELESS");
        assertDeclares("ControlSeatBlockEntity", "EvalVariant.SEAT_STATELESS");
        assertDeclares("MonitorBlockEntity", "EvalVariant.BASIC");
        assertDeclares("KineticGaugeBlockEntity", "EvalVariant.BASIC");
        assertDeclares("CncGearboxBlockEntity", "EvalVariant.FULL_STATE");
        assertDeclares("ProgrammableTransmissionBlockEntity", "EvalVariant.FULL_STATE");
        assertDeclares("RadarBlockEntity", "EvalVariant.EMPTY_TABLE");
        assertDeclares("SpeedProxyBlockEntity", "EvalVariant.BASIC");
        assertDeclares("SpeakerBlockEntity", "EvalVariant.FULL_STATE");
    }

    @Test
    @DisplayName("K23: the light-recompile trio and the Active-refresh quintet match the matrix")
    void recompileAndRefreshVariantsMatchTheMatrix() throws IOException {
        // ④ L 档 = Monitor / KineticGauge / SpeedProxy；其余为 F 档（默认）。
        for (String host : List.of("MonitorBlockEntity", "KineticGaugeBlockEntity", "SpeedProxyBlockEntity"))
            assertDeclares(host, "lightRecompile");
        // ⑥ A 档 = Blueprint / ProgramComputer / AmplifierComputer / CncGearbox / PT；其余 H 档（默认）。
        for (String host : List.of("BlueprintBlockEntity", "ProgramComputerBlockEntity",
                "AmplifierComputerBlockEntity", "CncGearboxBlockEntity", "ProgrammableTransmissionBlockEntity"))
            assertDeclares(host, "activeInputRefresh");
    }

    @Test
    @DisplayName("K23: the stop-slot / stop-shape declarations match the matrix")
    void stopSlotAndShapeMatchTheMatrix() throws IOException {
        // ⑤ 介于 ③④（Monitor / SpeedProxy 口径）。
        for (String host : List.of("MonitorBlockEntity", "SpeedProxyBlockEntity"))
            assertDeclares(host, "StopSlot.AFTER_GRAPH_CHECK");
        // ⑤ 先于 ③（Speaker 独有缺口：停止早退早于默认图建立）。
        assertDeclares("SpeakerBlockEntity", "StopSlot.BEFORE_GRAPH_CHECK");
        // ⑤ if/else 形态不早退（CncGearbox / PT 共用尾段口径）。
        for (String host : List.of("CncGearboxBlockEntity", "ProgrammableTransmissionBlockEntity"))
            assertDeclares(host, "stopEndsTick");
    }

    @Test
    @DisplayName("K23: the LIT step declarations match the matrix (8 property hosts, 2 non-aborting)")
    void litStepMatchesTheMatrix() throws IOException {
        assertDeclares("BlueprintBlockEntity", "BlueprintBlock.LIT");
        assertDeclares("ProgramComputerBlockEntity", "ProgramComputerBlock.LIT");
        assertDeclares("AmplifierComputerBlockEntity", "AmplifierComputerBlock.LIT");
        assertDeclares("SensorBlockEntity", "SensorBlock.LIT");
        assertDeclares("ControlSeatBlockEntity", "ControlSeatBlock.LIT");
        assertDeclares("MonitorBlockEntity", "MonitorBlock.LIT");
        assertDeclares("RadarBlockEntity", "RadarBlock.LIT");
        assertDeclares("SpeedProxyBlockEntity", "SpeedProxyBlock.LIT");
        // 「缺席即跳过不中止」口径 = 功放电脑 / 雷达。
        assertDeclares("AmplifierComputerBlockEntity", "litAbsentAbortsTick");
        assertDeclares("RadarBlockEntity", "litAbsentAbortsTick");
    }

    @Test
    @DisplayName("K23 batch 1: step-12 band sync presence matches the matrix (6 present, 6 gaps kept)")
    void bandSyncPresenceMatchesTheMatrix() throws IOException {
        // ⑫ 在位 = 蓝图机/程序机/功放电脑/传感器/控制座椅/雷达。
        for (String host : List.of("BlueprintBlockEntity", "ProgramComputerBlockEntity",
                "AmplifierComputerBlockEntity", "SensorBlockEntity", "ControlSeatBlockEntity",
                "RadarBlockEntity"))
            assertDeclares(host, "wantsBandSync");
        // ⑫ 缺失保持缺失（批 2 的行为补步）= 显示器/动力仪表/数控齿轮箱/可变速器/转速代理/音响。
        for (String host : List.of("MonitorBlockEntity", "KineticGaugeBlockEntity",
                "CncGearboxBlockEntity", "ProgrammableTransmissionBlockEntity",
                "SpeedProxyBlockEntity", "SpeakerBlockEntity"))
            assertFalse(fileContains(host, "wantsBandSync"),
                host + ": matrix step 12 gap must be preserved in batch 1");
        // 骨架中的 ⑫ 位置：⑪ 与 ⑬ 之间。
        List<String> s = skeleton();
        int snapshot = indexOf(s, "broadcastEvalSnapshot()", "GraphHost", "step 11 missing");
        int bands = indexOf(s, "syncIfBandsChanged", "GraphHost", "step 12 missing");
        int flipflop = indexOf(s, "broadcastFlipflopDiff", "GraphHost", "step 13 missing");
        assertTrue(snapshot < bands && bands < flipflop,
            "step 12 must sit between broadcastEvalSnapshot (11) and broadcastFlipflopDiff (13)");
    }

    @Test
    @DisplayName("K23: all twelve hosts delegate their tick to the skeleton")
    void allTwelveHostsDelegateToTheSkeleton() throws IOException {
        for (String host : ALL_HOSTS)
            delegatesToSkeleton(host);
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

    /** 断言宿主 tick() 委托骨架（收敛后各宿主 tick 只剩前段 + tickBody 委托）。
     *  Assert the host's tick() delegates to the skeleton. */
    private static void delegatesToSkeleton(String host) throws IOException {
        List<String> tick = methodBody(host + ".java", "public void tick()");
        assertTrue(tick.stream().anyMatch(l -> l.contains("tickBody(")),
            host + ": tick() must delegate to the GraphHost tickBody skeleton");
    }

    /** 断言宿主文件含偏差声明（逐宿主的矩阵列声明）。
     *  Assert the host file declares the given deviation. */
    private static void assertDeclares(String host, String needle) throws IOException {
        assertTrue(fileContains(host, needle), host + ": missing declaration \"" + needle + "\"");
    }

    /** 宿主文件是否含 needle（扫描全文件代码行）。 */
    private static boolean fileContains(String host, String needle) throws IOException {
        List<String> lines = Files.readAllLines(BLOCKS_DIR.resolve(host + ".java"), StandardCharsets.UTF_8);
        return lines.stream()
            .map(HostTickOrchestrationTest::stripStringsAndComments)
            .anyMatch(l -> l.contains(needle));
    }

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
