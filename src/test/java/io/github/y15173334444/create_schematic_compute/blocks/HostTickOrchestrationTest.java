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
 * 宿主 tick 编排结构锁（K23 收敛后的重锚形态）。
 * 15 步共同编排住 {@code GraphHost.tickBody}（源码扫描式结构测试，与
 * {@link DedicatedServerClientRefTest} 同模式——BE tick 本体依赖世界对象，无法 headless
 * 直测，而编排顺序正是行为变更点），各宿主 tick() 只剩前段 + 委托，偏差化为
 * {@code GraphHost.TickHooks} 声明。
 * <p>断言口径：
 * <ul>
 *   <li>骨架按矩阵顺序跑 15 步；① ensureBusRegistered 先于一切早退（92d0dbf：图停止也
 *       自愈 + 收敛）；⑮ 先于 ⑤ 停机路径（P1-8）；⑦ 在 ⑥ 与 ⑧ 之间；⑬ 在 ⑨ 之后。</li>
 *   <li>⑫ syncIfBandsChanged 在骨架中为无条件步（275ec03 的 12/12 直呼形态收编，
 *       wantsBandSync 开关已退役），位置在 ⑪ 与 ⑬ 之间。</li>
 *   <li>⑦ 冲突自愈的遗留第二挂点 = 六宿主声明（Blueprint/ProgramComputer/
 *       AmplifierComputer/Sensor/ControlSeat/Radar）；主挂点在 ① 内，其余六宿主不得
 *       再挂（同一 tick 双跑自愈，宿主编排批迁移账）。</li>
 *   <li>P2-13：⑬ 差分广播在位宿主 = 全状态档八宿主（CncGearbox / PT / Amp / Speaker /
 *       BP / PC + Sensor / ControlSeat——末两位随档位合一 2026-10-31 补挂）。</li>
 *   <li>P2-18：SpeedProxy 声明 L 档 + AFTER_GRAPH_CHECK 停机位；骨架求值输入恒来自
 *       buildInputs（无 List.of() 空表）。</li>
 *   <li>Speaker ⑥⑩：骨架的 refreshInputs / writeOutputs 为无条件步，Speaker 委托骨架。</li>
 *   <li>②④⑤⑥⑨ 偏差声明逐宿主与矩阵一致（LIT 属性/缺席口径、F/L 档、停机位/形态、
 *       A/H 档、三档求值）。</li>
 *   <li>P3-N13：Blueprint / ProgramComputer 的 loadAdditional 不再加载即发包。</li>
 *   <li>SpeedProxy 反射退役：全文件不再有 Class.forName / java.lang.reflect。</li>
 * </ul>
 * Structural lock over the host tick orchestration, re-anchored after the K23 convergence
 * (replayed on main): the shared 15-step body lives in {@code GraphHost.tickBody}, every
 * host tick() delegates, and host deviations are TickHooks declarations. Every prior
 * assertion keeps its meaning — steps in the skeleton in matrix order + per-host
 * delegation + per-host declarations.
 */
class HostTickOrchestrationTest {

    private static final Path BLOCKS_DIR = Path.of("src", "main", "java",
        "io", "github", "y15173334444", "create_schematic_compute", "blocks");

    /** 全部 12 宿主（委托骨架的口径；雷达已随收敛一并委托）。 */
    private static final List<String> ALL_HOSTS = List.of(
        "BlueprintBlockEntity", "ProgramComputerBlockEntity", "AmplifierComputerBlockEntity",
        "SensorBlockEntity", "ControlSeatBlockEntity", "MonitorBlockEntity",
        "KineticGaugeBlockEntity", "CncGearboxBlockEntity", "ProgrammableTransmissionBlockEntity",
        "RadarBlockEntity", "SpeedProxyBlockEntity", "SpeakerBlockEntity");

    /** 声明 ⑦ 遗留自愈挂点的六宿主（其余六宿主的主挂点在骨架 ① 内，不得双跑）。 */
    private static final List<String> STEP7_RECOVER_HOSTS = List.of(
        "BlueprintBlockEntity", "ProgramComputerBlockEntity", "AmplifierComputerBlockEntity",
        "SensorBlockEntity", "ControlSeatBlockEntity", "RadarBlockEntity");

    /** 骨架方法体（GraphHost.tickBody）——顺序断言的唯一锚点。 */
    private static List<String> skeleton() throws IOException {
        return methodBody("GraphHost.java", "public void tickBody(");
    }

    // ══════════ 骨架步序锁（15 步矩阵顺序）══════════

    @Test
    @DisplayName("K23: the skeleton runs the canonical 15 steps in matrix order")
    void skeletonFollowsTheCanonicalMatrixOrder() throws IOException {
        List<String> s = skeleton();
        int guard = indexOf(s, "isClientSide()", "GraphHost", "guard missing");
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

        assertTrue(guard < bus, "the guard precedes ① (client ticks never reach the engine)");
        assertTrue(bus < flush, "① ensureBusRegistered must precede ⑮ flushPendingFullSync");
        assertTrue(flush < lit, "⑮ must precede ② LIT (P1-8: flush before every early return)");
        assertTrue(lit < check, "② LIT must precede ③ checkGraphChanged");
        assertTrue(check < recompile, "③ must precede ④ recompile");
        assertTrue(recompile < refresh, "④ must precede ⑥ refreshInputs");
        assertTrue(refresh < recover, "⑥ must precede ⑦ recover (matrix position)");
        assertTrue(recover < build, "⑦ must precede ⑧ buildInputs (matrix position)");
        assertTrue(build < evaluate, "⑧ must precede ⑨ evaluate");
        assertTrue(evaluate < write, "⑨ must precede ⑩ writeOutputs");
        assertTrue(write < snapshot, "⑩ must precede ⑪ broadcastEvalSnapshot");
        assertTrue(snapshot < bands, "⑪ must precede ⑫ syncIfBandsChanged");
        assertTrue(bands < flipflop, "⑫ must precede ⑬ broadcastFlipflopDiff");
        assertTrue(evaluate < flipflop, "⑬ must broadcast after ⑨ evaluate (P2-13 position)");
        assertTrue(flipflop < dirty, "⑬ must precede ⑭ setChanged/markDirty");
    }

    // ══════════ ① ensureBusRegistered 语义钉（92d0dbf，v1.2.6.1 冲突自愈）══════════

    @Test
    @DisplayName("92d0dbf: ensureBusRegistered runs at the tick top, before every early return")
    void busRegistrationPrecedesEveryEarlyReturn() throws IOException {
        List<String> s = skeleton();
        int bus = indexOf(s, "ensureBusRegistered()", "GraphHost", "step 1 missing");
        int stop = indexOf(s, "stopBody", "GraphHost", "stop path missing");
        assertTrue(bus < stop,
            "① must precede the stop path: the conflict heal + band convergence run even "
                + "while the graph is stopped (92d0dbf — a stopped graph must still "
                + "self-heal and republish band definitions within the tick)");
        // ⑮ 在一切早退之前（P1-8）。
        int flush = indexOf(s, "flushPendingFullSync()", "GraphHost", "step 15 missing");
        assertTrue(flush < stop,
            "⑮ must run before the stop path so a stopped host still ships its graph to a "
                + "newly joined editor (P1-8)");
        for (String host : ALL_HOSTS)
            delegatesToSkeleton(host);
    }

    // ══════════ ⑦ recoverConflictedChannels（遗留挂点 = 六宿主声明）══════════

    @Test
    @DisplayName("step 7: the legacy heal hook sits between refreshInputs and buildInputs, declared by exactly six hosts")
    void legacyRecoverHookPositionAndDeclarations() throws IOException {
        List<String> s = skeleton();
        int refresh = indexOf(s, "refreshInputs", "GraphHost", "refreshInputs missing");
        int recover = indexOf(s, "recoverConflictedChannels", "GraphHost", "step 7 missing");
        int build = indexOf(s, "buildInputs", "GraphHost", "buildInputs missing");
        assertTrue(recover > refresh, "GraphHost: the step-7 heal must run after refreshInputs");
        assertTrue(recover < build, "GraphHost: the step-7 heal must run before buildInputs");
        // 声明在位 = 六宿主（继承线五宿主 + 雷达）；主挂点已在 ① 内覆盖全部宿主。
        for (String host : STEP7_RECOVER_HOSTS)
            assertDeclares(host, "wantsStep7Recover");
        // 其余六宿主不得声明（同一 tick 双跑自愈——宿主编排批迁移账）。
        for (String host : ALL_HOSTS) {
            if (STEP7_RECOVER_HOSTS.contains(host)) continue;
            assertFalse(fileContains(host, "wantsStep7Recover"),
                host + ": must NOT declare the step-7 legacy hook — the primary heal "
                    + "already runs inside the skeleton's ① (double heal per tick, see "
                    + "the host-orchestration migration account)");
        }
    }

    // ══════════ ⑫ syncIfBandsChanged（12/12 无条件在位；开关已退役）══════════

    @Test
    @DisplayName("step 12: band sync is unconditional in the skeleton (12/12, the wantsBandSync switch is retired)")
    void bandSyncIsUnconditionalForAllHosts() throws IOException {
        List<String> s = skeleton();
        int snapshot = indexOf(s, "broadcastEvalSnapshot()", "GraphHost", "step 11 missing");
        int bands = indexOf(s, "syncIfBandsChanged", "GraphHost", "step 12 missing");
        int flipflop = indexOf(s, "broadcastFlipflopDiff", "GraphHost", "step 13 missing");
        assertTrue(snapshot < bands && bands < flipflop,
            "step 12 must sit between broadcastEvalSnapshot (11) and broadcastFlipflopDiff (13)");
        // 275ec03 的 12/12 直呼形态收编进骨架：不再有宿主侧声明开关。
        for (String host : ALL_HOSTS) {
            assertFalse(fileContains(host, "wantsBandSync"),
                host + ": the wantsBandSync switch is retired — step 12 rides the skeleton");
            delegatesToSkeleton(host);
        }
    }

    // ══════════ ⑬ broadcastFlipflopDiff（P2-13：全状态档八宿主在位）══════════

    @Test
    @DisplayName("P2-13: the flipflop diff broadcasts after evaluate; the eight full-state hosts enable it")
    void fullStateHostsBroadcastFlipflopDiff() throws IOException {
        List<String> s = skeleton();
        int evaluate = indexOf(s, ".evaluate(", "GraphHost", "evaluate missing");
        int diff = indexOf(s, "broadcastFlipflopDiff", "GraphHost", "step 13 missing");
        assertTrue(diff > evaluate, "GraphHost: flipflop diff must broadcast after the evaluation");
        for (String host : List.of("CncGearboxBlockEntity", "ProgrammableTransmissionBlockEntity",
                "AmplifierComputerBlockEntity", "SpeakerBlockEntity",
                "BlueprintBlockEntity", "ProgramComputerBlockEntity",
                "SensorBlockEntity", "ControlSeatBlockEntity"))
            assertDeclares(host, "wantsFlipflopDiff");
        // BASIC / EMPTY_TABLE 档宿主不得声明（矩阵 ⑬ 列口径原样）。
        for (String host : List.of("MonitorBlockEntity", "KineticGaugeBlockEntity",
                "SpeedProxyBlockEntity", "RadarBlockEntity"))
            assertFalse(fileContains(host, "wantsFlipflopDiff"),
                host + ": must NOT declare the step-13 flipflop diff — only the "
                    + "full-state tier hosts broadcast it (the matrix's ⑬ column)");
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
        assertDeclares("SensorBlockEntity", "EvalVariant.FULL_STATE");
        assertDeclares("ControlSeatBlockEntity", "EvalVariant.FULL_STATE");
        assertDeclares("MonitorBlockEntity", "EvalVariant.BASIC");
        assertDeclares("KineticGaugeBlockEntity", "EvalVariant.BASIC");
        assertDeclares("CncGearboxBlockEntity", "EvalVariant.FULL_STATE");
        assertDeclares("ProgrammableTransmissionBlockEntity", "EvalVariant.FULL_STATE");
        assertDeclares("RadarBlockEntity", "EvalVariant.EMPTY_TABLE");
        assertDeclares("SpeedProxyBlockEntity", "EvalVariant.BASIC");
        assertDeclares("SpeakerBlockEntity", "EvalVariant.FULL_STATE");
        // 档位合一（2026-10-31 拍板）：FULL_STATE case = seat + runtimeState 三表
        // （Sensor/ControlSeat 随此 case 获得七类时序/状态节点的跨 tick 演进）；
        // SEAT_STATELESS 档已删除，源码不得再出现。
        assertTrue(fileContains("GraphHost",
                "case FULL_STATE -> evaluator.evaluate(in, runtimeState.pidState, dt, h.seatInput(),"),
            "GraphHost: the unified full-state case must pass the seat input "
                + "(tier unification 2026-10-31 — Sensor/ControlSeat ride this case)");
        for (String host : ALL_HOSTS)
            assertFalse(fileContains(host, "SEAT_STATELESS"),
                host + ": the SEAT_STATELESS tier is retired (tier unification 2026-10-31)");
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
        // ⑤ 先于 ③（Speaker：停止早退先于图检查）。
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
