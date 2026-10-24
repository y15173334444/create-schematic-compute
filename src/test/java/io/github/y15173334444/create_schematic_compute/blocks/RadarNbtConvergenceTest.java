package io.github.y15173334444.create_schematic_compute.blocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 雷达 NBT/生命周期收敛的源码契约锚（headless 无法构造方块实体——注册表未引导时
 * {@code SchematicCompute.RADAR_BE.get()} 为 null，故沿用本仓
 * {@code HostTickOrchestrationTest} 的源码断言模式）：
 * <ul>
 *   <li>P2-14：RadarBlockEntity 不得再覆写 saveAdditional/loadAdditional 手写
 *       graph/running/runtime 同名键——整图只由基类 saveHostNBT 序列化一遍，雷达只走
 *       saveTypeSpecific/loadTypeSpecific 类型钩子；</li>
 *   <li>P2-15：CLIENT_RADARS 注册表必须接 onChunkUnloaded 与 LoggingOut 清表；</li>
 *   <li>P2-10(a)：方块拆除路径必须回收编辑会话（EditSessionRegistry.dropBlock）。</li>
 * </ul>
 * 既有旧行为的双写是真实生产事故面（P1-7 每 tick 推送叠加双倍序列化成本），此锚防止回潮。
 *
 * <p>Source-contract anchor for the radar NBT/lifecycle convergence (a block entity
 * cannot be constructed headlessly — {@code SchematicCompute.RADAR_BE.get()} is null
 * without registry bootstrap — so this follows the repo's existing
 * {@code HostTickOrchestrationTest} source-assertion pattern): (P2-14) RadarBlockEntity
 * must not re-override saveAdditional/loadAdditional to hand-write graph/running/runtime
 * keys — the graph is serialized exactly once by the base host NBT and the radar only
 * fills the type hooks; (P2-15) the CLIENT_RADARS registry must be wired to
 * onChunkUnloaded and the LoggingOut wipe; (P2-10a) the block-removal path must reclaim
 * the edit session. The old double-write was a real production-cost surface (P1-7's
 * per-tick pushes paid serialization twice); this anchor prevents a relapse.</p>
 */
class RadarNbtConvergenceTest {

    private static final Path RADAR_SOURCE = Paths.get(
        "src/main/java/io/github/y15173334444/create_schematic_compute/blocks/RadarBlockEntity.java");
    private static final Path CLIENT_SETUP_SOURCE = Paths.get(
        "src/main/java/io/github/y15173334444/create_schematic_compute/client/ClientSetup.java");

    private static List<String> lines(Path p) throws IOException {
        return Files.readAllLines(p, StandardCharsets.UTF_8);
    }

    /** 方法体提取（起止 brace 计数，HostTickOrchestrationTest 同款思路）。/ Method-body extraction by brace counting. */
    private static List<String> methodBody(List<String> source, String signatureStart) {
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i).contains(signatureStart)) {
                int depth = 0;
                boolean opened = false;
                var body = new java.util.ArrayList<String>();
                for (int j = i; j < source.size(); j++) {
                    String l = source.get(j);
                    body.add(l);
                    for (char c : l.toCharArray()) {
                        if (c == '{') { depth++; opened = true; }
                        else if (c == '}') depth--;
                    }
                    if (opened && depth == 0) return body;
                }
            }
        }
        return List.of();
    }

    private static String text(List<String> ls) { return ls.stream().collect(Collectors.joining("\n")); }

    @Test
    @DisplayName("P2-14：雷达不再覆写 saveAdditional/loadAdditional（类型钩子口径）")
    void radarUsesTypeHooksOnly() throws IOException {
        var src = lines(RADAR_SOURCE);
        assertFalse(src.stream().anyMatch(l -> l.contains("protected void saveAdditional")),
            "RadarBlockEntity must not override saveAdditional — the base serializes the whole "
                + "graph once; overriding it re-introduces the double-write (P2-14)");
        assertFalse(src.stream().anyMatch(l -> l.contains("protected void loadAdditional")),
            "RadarBlockEntity must not override loadAdditional — the base handles graph/running/"
                + "runtime with the bounce-back guards");
        assertTrue(src.stream().anyMatch(l -> l.contains("protected void saveTypeSpecific")),
            "the radar type section must live in saveTypeSpecific");
        assertTrue(src.stream().anyMatch(l -> l.contains("protected void loadTypeSpecific")),
            "the radar type section must live in loadTypeSpecific");
    }

    @Test
    @DisplayName("P2-14：雷达源码不再手写 graph/running/runtime 同名键")
    void radarDoesNotHandWriteCommonKeys() throws IOException {
        var src = lines(RADAR_SOURCE);
        assertFalse(src.stream().anyMatch(l -> l.contains("t.put(\"graph\"")),
            "the radar must not re-serialize the graph — single write lives in GraphHost.saveHostNBT");
        assertFalse(src.stream().anyMatch(l -> l.contains("t.putBoolean(\"running\"")),
            "the radar must not hand-write the running key");
        assertFalse(src.stream().anyMatch(l -> l.contains("t.put(\"runtime\"")),
            "the radar must not hand-write the runtime key");
    }

    @Test
    @DisplayName("P2-15：onChunkUnloaded 摘除客户端注册表 + LoggingOut 兜底清表已接线")
    void clientRegistryLifecycleWired() throws IOException {
        var unloaded = methodBody(lines(RADAR_SOURCE), "public void onChunkUnloaded()");
        assertFalse(unloaded.isEmpty(), "RadarBlockEntity must override onChunkUnloaded (P2-15)");
        assertTrue(text(unloaded).contains("CLIENT_RADARS.remove(this)"),
            "chunk unload must drop the radar from the client registry");

        var setup = text(lines(CLIENT_SETUP_SOURCE));
        assertTrue(setup.contains("ClientPlayerNetworkEvent.LoggingOut")
                && setup.contains("clearClientRadars()"),
            "ClientSetup must wipe the radar registry on LoggingOut (P2-15 backstop)");
        assertTrue(setup.contains("setClientSink(new GraphAckRouter())"),
            "ClientSetup must register the client ACK sink (E2)");
    }

    @Test
    @DisplayName("P2-10(a)：方块拆除路径回收编辑会话（基类统一接线，雷达不重复）")
    void setRemovedReclaimsEditSession() throws IOException {
        // 会话回收接线已上移到基类 SyncedGraphBlockEntity.setRemoved（全部宿主同款），
        // 雷达覆写只做客户端表清理，不再重复调用 dropBlock。
        var base = java.nio.file.Files.readString(java.nio.file.Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/blocks/SyncedGraphBlockEntity.java"));
        assertTrue(base.contains("EditSessionRegistry.dropBlock"),
            "the base setRemoved must reclaim the edit session (ghost editors otherwise linger)");
        var removed = methodBody(lines(RADAR_SOURCE), "public void setRemoved()");
        assertFalse(removed.isEmpty());
        assertFalse(text(removed).contains("EditSessionRegistry.dropBlock"),
            "the radar override must not duplicate the base-class reclaim");
    }
}
