package io.github.y15173334444.create_schematic_compute.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：network/ 包不得对原始 BE 直接转型 {@code (GraphBlockEntity)}。
 * <p>
 * 背景（2026-11-21 宿主套件实机冒烟）：{@link GraphJoinPacket#handle} 用
 * {@code GraphBlockEntities.resolve(be)} 只做判空、随后却转型<b>原始 BE</b>——
 * 自有宿主的 BE 本身就是 GraphBlockEntity，恰好活着；套件宿主的 BE 本体不是
 * GraphBlockEntity（适配器才是），右键开图即 {@code ClassCastException}（电推实证，
 * join 两次即炸两次）。修复 = 转型对象改用 resolver 的返回值。
 * <p>
 * 断言：network/ 包（含子包）任何源码行不得出现 {@code (GraphBlockEntity)} 强转——
 * 图宿主的解析单源是 {@code GraphBlockEntities.resolve}，包层消费一律走其返回值。
 * 未来若真有必须转型的场景，先扩 resolver 的语义而不是在包层开洞。
 */
class NetworkGraphBlockEntityCastGuardTest {

    /** network/ 包源码目录（gradle test 工作目录 = 项目根）。 */
    private static final Path NETWORK_DIR = Path.of("src", "main", "java",
        "io", "github", "y15173334444", "create_schematic_compute", "network");

    @Test
    @DisplayName("Network: every GraphBlockEntity access must go through GraphBlockEntities.resolve")
    void noRawGraphBlockEntityCastsInNetwork() throws IOException {
        assertTrue(Files.isDirectory(NETWORK_DIR), "network 目录不存在: " + NETWORK_DIR);
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(NETWORK_DIR)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String t = lines.get(i).trim();
                    if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) continue;
                    if (lines.get(i).contains("(GraphBlockEntity)")) {
                        violations.add(f.getFileName() + ":" + (i + 1));
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "network/ 出现对原始 BE 的 (GraphBlockEntity) 强转——套件宿主会 CCE，"
                + "一律改走 GraphBlockEntities.resolve 的返回值: " + violations);
    }
}
