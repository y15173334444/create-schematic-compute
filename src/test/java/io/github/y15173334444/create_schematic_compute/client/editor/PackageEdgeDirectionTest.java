package io.github.y15173334444.create_schematic_compute.client.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反向包边解环（2026-10-31 拍板）的**方向锁**：包归位批（e831c94/5122149）把 blocks 内同包互访
 * 显形为跨包 import 后，blocks ↔ client.editor 成环、EditSessionRegistry 新增 network → blocks
 * 边；本批按 E2 {@code EditAckSink} 手法依赖倒置（Host 能力查询 / 窄口 / markDirty 形参收窄），
 * 以下边归零并由本锁钉住：
 * <ul>
 *   <li>{@code client/editor/**} 不得 import {@code blocks.}（编辑器家族经 Host 缝与窄口消费）；</li>
 *   <li>{@code network/EditSessionRegistry.java} 不得 import {@code blocks.}（经
 *       {@code EditSessionTarget}/{@code CoalescedFullSync} 窄口消费）。</li>
 * </ul>
 * 允许方向（不锁）：blocks → client.editor（宿主屏继承 AbstractGraphScreen / 实现 Host）与
 * blocks → network（BE 实现 network 窄口）。
 * <p>
 * Direction lock for the reverse package-edge cycle break (2026-10-31 ruling): the
 * package-rehoming batch turned in-package access into cross-package imports, closing a
 * blocks ↔ client.editor cycle and adding network → blocks edges; this batch inverts the
 * dependencies (E2 {@code EditAckSink} playbook — host capability queries / narrow ports /
 * markDirty param narrowing), and these edges are now pinned at zero:
 * client/editor/** must not import blocks., and EditSessionRegistry must not import
 * blocks. The allowed directions (blocks → client.editor, blocks → network) stay open.
 */
class PackageEdgeDirectionTest {

    private static final Path SRC = Path.of("src", "main", "java",
        "io", "github", "y15173334444", "create_schematic_compute");

    /** 去注释与字符串后的行内容（源码扫描只看真实代码——注释里提到包名不违规）。 */
    private static String stripCommentsAndStrings(String line) {
        String s = line;
        int bc = s.indexOf("/*");
        if (bc >= 0) s = s.substring(0, bc);
        int lc = s.indexOf("//");
        if (lc >= 0) s = s.substring(0, lc);
        return s.replaceAll("\"[^\"]*\"", "\"\"");
    }

    private static boolean importsBlocks(Path file) throws IOException {
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.map(PackageEdgeDirectionTest::stripCommentsAndStrings)
                .anyMatch(l -> l.contains("import io.github.y15173334444.create_schematic_compute.blocks."));
        }
    }

    @Test
    @DisplayName("client/editor 不得回引 blocks（编辑器家族经 Host 缝与窄口消费）")
    void editorFamilyNeverReachesBackIntoBlocks() throws IOException {
        Path dir = SRC.resolve("client").resolve("editor");
        assertTrue(Files.isDirectory(dir), "client/editor dir missing: " + dir);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertFalse(importsBlocks(f),
                    f.getFileName() + ": client/editor must not import blocks.* — consume "
                        + "through GraphEditor.Host queries and the narrow ports "
                        + "(EditStateView / MonitorBeView), reverse package-edge cycle break");
            }
        }
    }

    @Test
    @DisplayName("EditSessionRegistry 不得回引 blocks（经 EditSessionTarget/CoalescedFullSync 窄口消费）")
    void editSessionRegistryNeverReachesBackIntoBlocks() throws IOException {
        Path f = SRC.resolve("network").resolve("EditSessionRegistry.java");
        assertTrue(Files.exists(f), "EditSessionRegistry.java missing: " + f);
        assertFalse(importsBlocks(f),
            "EditSessionRegistry: network edit-session layer must not import blocks.* — "
                + "consume through EditSessionTarget / CoalescedFullSync (reverse package-edge "
                + "cycle break)");
    }
}
