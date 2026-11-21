package io.github.y15173334444.create_schematic_compute.client.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 泛用屏（宿主套件波 2）的接线锚（GUI 不可 headless，一律源码锚——
 * {@code CustomNodeEditorFaceTest} 同款手法）：泛用屏经 resolver 读宿主视图、名单与
 * 宿主键同源、运行切换走 BlueprintTogglePacket；开启器静默边界；client-only 钩子注册；
 * 便携终端套件兜底路由的判定形态（先专后泛）。
 * Wiring anchors for the generic screen (host suite, wave 2; GUI cannot run headless so
 * these are source anchors — the {@code CustomNodeEditorFaceTest} playbook): the screen
 * reads the host view through the resolver, the allowance shares the host-key source,
 * the running toggle rides BlueprintTogglePacket; the opener's silent boundary; the
 * client-only hook registration; and the terminal's suite default-route discriminator
 * shape (specific before generic).
 */
class HostGraphScreenWiringTest {

    private static final Path SRC = Path.of("src/main/java/io/github/y15173334444/create_schematic_compute");

    private static String source(String... parts) throws IOException {
        return Files.readString(SRC.resolve(Path.of("", parts)), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("泛用屏：resolver 读视图、名单同源、toggle 走权威包")
    void screenWiring() throws IOException {
        String s = source("client", "editor", "HostGraphScreen.java");
        assertTrue(s.contains("EditSessionTargets.resolve(minecraft.level.getBlockEntity(blockPos))")
                && s.contains("instanceof HostGraphView v ? v : null"),
            "the screen reads the host view through the graph-domain resolver and the narrow port"
                + " (no concrete host types, no blocks imports)");
        assertTrue(s.contains("setNodeAllowance(be.nodeAllowance())"),
            "the allowance and host key share the source (the adapter's converted admission)");
        assertTrue(s.contains("be.setRunning(start)"), "the optimistic local write precedes the packet");
        assertTrue(s.contains("new BlueprintTogglePacket(be.getBlockPos(), start)"),
            "the running toggle rides the authoritative toggle packet");
        assertTrue(s.contains("be.getNodeGraph()") && s.contains("be.getCachedEvalSnapshot()"),
            "graph and snapshot reads go through the resolved view");
    }

    @Test
    @DisplayName("开启器：resolve 命中才 setScreen，未命中静默返回")
    void openerBoundary() throws IOException {
        String s = source("client", "editor", "HostGraphScreens.java");
        assertTrue(s.contains("var be = EditSessionTargets.resolve(mc.level.getBlockEntity(pos));"),
            "the opener resolves through the graph-domain registry (narrow-port consumer)");
        int resolveAt = s.indexOf("var be = EditSessionTargets.resolve");
        int silentAt = s.indexOf("if (!(be instanceof HostGraphView)) return;");
        int setScreenAt = s.indexOf("mc.setScreen(new HostGraphScreen(pos));");
        assertTrue(resolveAt >= 0 && silentAt > resolveAt && setScreenAt > silentAt,
            "resolve → narrow-port guard (silent return) → setScreen, in that order");
    }

    @Test
    @DisplayName("client-only 钩子：ClientSetup 注册 HostGraphScreens::open")
    void hookRegistration() throws IOException {
        String s = source("client", "ClientSetup.java");
        assertTrue(s.contains("HostGraphs.registerEditorOpener(")
                && s.contains("HostGraphScreens::open"),
            "the client-only opener registers into the api facade");
        String api = source("api", "HostGraphs.java");
        assertFalse(api.contains("package io.github.y15173334444.create_schematic_compute.client"),
            "the api facade itself imports no client classes (the hook keeps the edge clean)");
    }

    @Test
    @DisplayName("终端兜底路由：BE 本体非图契约但解析命中 → 泛用屏")
    void terminalDefaultRoute() throws IOException {
        String s = source("client", "PortableTerminalScreen.java");
        assertTrue(s.contains("!(raw instanceof GraphBlockEntity)"),
            "the discriminator is a raw BE that is not itself a graph contract");
        assertTrue(s.contains("GraphBlockEntities.resolve(raw) != null"),
            "…that still resolves to a suite adapter");
        assertTrue(s.contains("new HostGraphScreen(editingPos)"),
            "the fallback opens the generic screen (the silent-nothing family is sealed)");
        int keyNull = s.indexOf("if (key != null)");
        int fallback = s.indexOf("!(raw instanceof GraphBlockEntity)");
        assertTrue(keyNull >= 0 && fallback > keyNull,
            "specific routes stay ahead of the suite fallback (specific before generic)");
    }
}
