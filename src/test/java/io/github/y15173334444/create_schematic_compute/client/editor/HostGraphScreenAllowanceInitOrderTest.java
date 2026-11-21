package io.github.y15173334444.create_schematic_compute.client.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 泛用屏名单接线时序守卫：{@code HostGraphScreen} 的名单（节点分类过滤 + CUSTOM 段
 * rosterHost）必须在 {@code init()} 里接线，<b>不能</b>在构造器里经 {@code beResolved()}
 * 解析——构造期 {@code Screen.minecraft} 尚未注入（{@code init(Minecraft,...)} 才赋值），
 * {@code beResolved()} 在构造器里恒返 null，{@code setNodeAllowance} 被静默跳过：
 * 菜单无自定义节点（CUSTOM 段默认拒绝）且内置分类也不过滤（实机实证 2026-11-21 宿主套件
 * 冒烟：电推泛用屏搜「电」只见继电器 A/B，无「推进比例」）。
 * <p>名单缺失在行为上无从观测（空名单 = 默认拒绝 = 静默），源序守卫是唯一能运行时钉住
 * 它的方式（同 {@code blocks.EditorKeysConfigInitOrderTest} 的口径）。分析前先剥注释行，
 * 注释里的字面 {@code beResolved()} 不参与判定。
 * Generic-screen allowance wiring-order guard: the allowance (the node category filter and
 * the CUSTOM section's rosterHost) must be wired in {@code init()}, never in the
 * constructor via {@code beResolved()} - Screen.minecraft is not injected at construction
 * time (init(Minecraft,...) assigns it), so beResolved() always returns null there and
 * {@code setNodeAllowance} is silently skipped: no custom nodes in the menu (the CUSTOM
 * section default-denies) and no built-in category filtering either (live-verified in the
 * 2026-11-21 host-suite smoke: the electric push's generic screen searched for "电" and
 * only found Relay A/B, no Push Ratio). A missing allowance is unobservable in behavior
 * (an empty roster = default deny = silence) - a source-order guard is the only runtime
 * pin (same policy as EditorKeysConfigInitOrderTest). Comment lines are stripped before
 * analysis - literal mentions of beResolved() inside comments do not participate.
 */
class HostGraphScreenAllowanceInitOrderTest {

    @Test
    @DisplayName("名单接线在 init()、构造器不解析 BE / the allowance wiring lives in init(), not the constructor")
    void allowanceWiredInInitNotConstructor() throws Exception {
        List<String> lines = Files.readAllLines(Path.of(
            "src/main/java/io/github/y15173334444/create_schematic_compute/client/editor/HostGraphScreen.java"));
        // 剥注释行（// 与 /* */ 块行）——注释里的字面方法名不参与判定。
        // Strip comment lines (// and /* */ block lines) - literal method names inside
        // comments do not participate in the verdict.
        var src = lines.stream()
            .filter(l -> { var t = l.trim(); return !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*"); })
            .collect(Collectors.joining("\n"));
        int ctor = src.indexOf("public HostGraphScreen(");
        assertTrue(ctor >= 0, "构造器必须存在 / the constructor must exist");
        int ctorEnd = src.indexOf("@Override", ctor);
        assertTrue(ctorEnd > ctor,
            "构造器后必须有下一个成员（@Override）/ a following member must exist after the constructor");
        String ctorBody = src.substring(ctor, ctorEnd);
        assertTrue(!ctorBody.contains("beResolved()"),
            "构造器不得调用 beResolved()——构造期 Screen.minecraft 未注入，恒 null，"
                + "名单静默缺席（机制与实机实证见 HostGraphScreen 构造器注释）/\n"
                + "the constructor must not call beResolved(): Screen.minecraft is not injected "
                + "at construction time, it is always null there, and the allowance silently "
                + "goes missing (mechanism and live evidence in the HostGraphScreen "
                + "constructor comment)");
        int initDecl = src.indexOf("protected void init()");
        assertTrue(initDecl > ctorEnd,
            "必须有 init() 覆写 / an init() override must exist");
        int firstCall = src.indexOf("setNodeAllowance");
        assertTrue(firstCall >= initDecl,
            "setNodeAllowance 唯一接线点是 init()——构造器内调用会在 minecraft 注入前静默跳过"
                + "（机制与实机实证见 HostGraphScreen 构造器注释）/\n"
                + "the single setNodeAllowance wiring point is init(): a constructor call is "
                + "silently skipped before minecraft is injected (mechanism and live evidence "
                + "in the HostGraphScreen constructor comment)");
    }
}
