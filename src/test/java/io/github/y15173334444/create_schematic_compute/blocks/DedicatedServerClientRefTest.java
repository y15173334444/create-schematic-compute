package io.github.y15173334444.create_schematic_compute.blocks;

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
 * 回归测试：common 代码不得出现"裸客户端引用"（专用服务器崩溃防护）。
 * <p>
 * 背景（fix 0f033a2）：useWithoutItem 等方法直接调用
 * {@code net.minecraft.client.Minecraft.getInstance().setScreen(new XxxScreen(p))}，
 * 该 client 类的 {@code new} 指令位于 common 代码中 → 专用服务器（dedicated server）
 * 的 RuntimeDistCleaner 类加载校验失败 → 服务器崩溃。
 * 修复：把 screen opener 提取为 {@code @OnlyIn(Dist.CLIENT) private static void openScreen(...)}，
 * 由 dist cleaner 在专用服务端剥离方法体，common 代码不再出现客户端类的 new。
 * <p>
 * 断言：blocks/ 下每个含 {@code setScreen(new} 的调用，其所属方法签名必须带
 * {@code @OnlyIn} 注解（紧邻上一行）。
 */
class DedicatedServerClientRefTest {

    /** blocks/ 包源码目录（gradle test 工作目录 = 项目根）。 */
    private static final Path BLOCKS_DIR = Path.of("src", "main", "java",
        "io", "github", "y15173334444", "create_schematic_compute", "blocks");

    @Test
    @DisplayName("Common blocks: every setScreen(new …) call must live in an @OnlyIn(CLIENT) method")
    void noBareClientScreenRefsInCommonBlocks() throws IOException {
        assertTrue(Files.isDirectory(BLOCKS_DIR), "blocks 目录不存在: " + BLOCKS_DIR);
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(BLOCKS_DIR)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (!lines.get(i).contains("setScreen(new")) continue;
                    if (!isGuardedByOnlyIn(lines, i)) {
                        violations.add(f.getFileName() + ":" + (i + 1));
                    }
                }
            }
        }
        assertTrue(violations.isEmpty(),
            "发现无 @OnlyIn 保护的裸客户端 setScreen 引用（专用服务器会崩溃）: " + violations);
    }

    /** 从调用行向上找所属方法签名，签名紧邻上一行须为 @OnlyIn 注解。 */
    private static boolean isGuardedByOnlyIn(List<String> lines, int callLine) {
        for (int j = callLine - 1; j >= 0; j--) {
            String t = lines.get(j).trim();
            // 方法签名：包含 '(' 且以 '{' 结尾（含返回类型与参数）
            if (t.endsWith("{") && t.contains("(")) {
                // @OnlyIn（全限定名 @net.neoforged.api.distmarker.OnlyIn）独占一行，紧邻方法签名上一行
                return j >= 1 && lines.get(j - 1).contains("distmarker.OnlyIn");
            }
            // 越过方法体边界（前一个闭合 '}'）仍未找到签名 → 该调用不在方法内（异常），视为未保护
            if (t.equals("}")) return false;
        }
        return false;
    }
}
