package io.github.y15173334444.create_schematic_compute.blocks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：{@code IMergeableBE.accept()} 的类型判定必须放行**跨变体合并**。
 * Regression test: the type check in {@code IMergeableBE.accept()} must admit
 * cross-variant merges.
 *
 * <p>背景：accept 从 7 个子类上提到 {@link SyncedGraphBlockEntity} 时，首版把各子类原先的
 * {@code other instanceof XxxBlockEntity} 改成了 {@code getClass() != getClass()}，
 * 理由是"各 BE 均无子类" —— 这个理由是错的。compat/ 下有四个 Sable 兼容子类继承对应基类
 * 且都不覆写 accept，旧行为靠 instanceof 双向放行，新行为把基类 ↔ Sable 变体的合并
 * 静默跳过了。整合包中途加装或移除 Sable 时两种 BE 会在同一世界共存。
 * Background: when accept was hoisted to {@link SyncedGraphBlockEntity}, the per-subclass
 * {@code other instanceof XxxBlockEntity} was replaced by {@code getClass() != getClass()}
 * on the reasoning that "no BE is ever subclassed" — which is false. Four Sable compat
 * subclasses under compat/ extend the matching base class and none overrides accept, so
 * the old instanceof let base ↔ variant merge both ways while the new check silently
 * skipped it. Installing or removing Sable mid-game leaves both kinds alive in one world.
 *
 * <p>本测试分两层：语义层用本地类层次锁住判定（防止改回类相等），事实层用源码扫描确认
 * 四个 Sable 变体仍是对应基类的子类且不覆写 accept（防止有人给它们加上覆写后，
 * 基类判定再被"简化"掉）。两层都用静态分析，不实例化 BE —— 加载真实 BE 类会触发
 * Minecraft registry 初始化。
 * Two layers: the semantic layer pins the predicate with a local class hierarchy (so class
 * equality fails it), and the factual layer scans the sources to confirm the four Sable
 * variants still extend their base and do not override accept. Both are static analysis —
 * instantiating real BEs would trigger Minecraft registry initialisation.
 */
class MergeCompatibilityTest {

    // ── 语义层：本地类层次模拟"基类 ↔ 变体" ──────────────────────────────
    //     Semantic layer: a local hierarchy standing in for base ↔ variant

    /** 模拟原生基类 / stands in for a native base BE */
    private static class BaseBE { }
    /** 模拟 Sable 兼容变体 / stands in for its Sable variant */
    private static class VariantBE extends BaseBE { }
    /** 模拟无关的另一种 BE / stands in for an unrelated BE type */
    private static class UnrelatedBE { }

    @Test
    @DisplayName("Identical types merge")
    void identicalTypesMerge() {
        assertTrue(SyncedGraphBlockEntity.isMergeCompatible(BaseBE.class, BaseBE.class));
    }

    @Test
    @DisplayName("Base ↔ variant merges in BOTH directions (the regression)")
    void baseAndVariantMergeBothWays() {
        // 变体被合并进基类 / variant absorbed into the base
        assertTrue(SyncedGraphBlockEntity.isMergeCompatible(BaseBE.class, VariantBE.class),
            "a variant must be mergeable into its base class");
        // 基类被合并进变体 —— 反向同样必须放行，否则旧存档/新增 Sable 场景丢图
        // base absorbed into the variant — the reverse must pass too, or older
        // blocks lose their graph when Sable is installed later.
        assertTrue(SyncedGraphBlockEntity.isMergeCompatible(VariantBE.class, BaseBE.class),
            "a base class must be mergeable into its variant");
    }

    @Test
    @DisplayName("Unrelated BE types never merge")
    void unrelatedTypesDoNotMerge() {
        assertFalse(SyncedGraphBlockEntity.isMergeCompatible(BaseBE.class, UnrelatedBE.class));
        assertFalse(SyncedGraphBlockEntity.isMergeCompatible(UnrelatedBE.class, BaseBE.class));
    }

    @Test
    @DisplayName("Negative guard: class equality would reject variants (proves the test bites)")
    void classEqualityWouldRejectVariants() {
        // 证明上面的断言不是恒真：若判定改用类相等（曾踩过的坑），变体一律被拒。
        // Shows the assertions above are not vacuous: with a class-equality check —
        // the trap already fallen into once — every variant is rejected.
        Class<?> base = BaseBE.class;
        Class<?> variant = VariantBE.class;
        assertFalse(base == variant,
            "guards the regression above — with class equality the variant merge is dropped, "
                + "so isMergeCompatible must keep using isAssignableFrom in both directions");
    }

    // ── 事实层：四个 Sable 变体必须仍是基类的子类且不覆写 accept ──────────
    //     Factual layer: the four Sable variants must stay subclasses without accept

    /** compat/ 源码目录（测试工作目录 = 项目根）。 / compat/ sources (cwd = project root). */
    private static final Path COMPAT_DIR = Path.of("src", "main", "java",
        "io", "github", "y15173334444", "create_schematic_compute", "compat");

    /** 变体类名 → 期望的基类名 / variant class name → expected base class name */
    private static final Map<String, String> EXPECTED_VARIANTS = new LinkedHashMap<>();
    static {
        EXPECTED_VARIANTS.put("ControlSeatBlockEntitySable", "ControlSeatBlockEntity");
        EXPECTED_VARIANTS.put("MonitorBlockEntitySable", "MonitorBlockEntity");
        EXPECTED_VARIANTS.put("RadarBlockEntitySable", "RadarBlockEntity");
        EXPECTED_VARIANTS.put("SensorBlockEntitySable", "SensorBlockEntity");
    }

    @Test
    @DisplayName("Sable variants extend their base class and never override accept()")
    void sableVariantsExtendTheirBaseAndDoNotOverrideAccept() throws IOException {
        assertTrue(Files.isDirectory(COMPAT_DIR), "compat 目录不存在 / missing: " + COMPAT_DIR);
        for (var entry : EXPECTED_VARIANTS.entrySet()) {
            String variant = entry.getKey();
            String base = entry.getValue();
            Path file = COMPAT_DIR.resolve(variant + ".java");
            assertTrue(Files.exists(file), "变体源文件缺失 / missing variant source: " + file);
            String src = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(src.contains("class " + variant + " extends " + base),
                variant + " 必须继承 " + base + "，否则跨变体合并的前提不复存在 / must extend "
                    + base + ", or cross-variant merging is moot");
            assertFalse(src.contains("void accept("),
                variant + " 不得覆写 accept() —— 它依赖基类的跨变体判定放行；"
                    + "一旦覆写，基类判定被绕开，合并语义需要重新审查 / must not override "
                    + "accept(): it relies on the base class's cross-variant check");
        }
    }
}
