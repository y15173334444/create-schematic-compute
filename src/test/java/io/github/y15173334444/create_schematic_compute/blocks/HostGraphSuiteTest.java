package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.api.HostAdmission;
import io.github.y15173334444.create_schematic_compute.api.HostCategory;
import io.github.y15173334444.create_schematic_compute.api.HostGraphBehavior;
import io.github.y15173334444.create_schematic_compute.api.HostGraphHandle;
import io.github.y15173334444.create_schematic_compute.api.HostGraphType;
import io.github.y15173334444.create_schematic_compute.api.HostGraphs;
import io.github.y15173334444.create_schematic_compute.graph.BlockNodeAllowances;
import io.github.y15173334444.create_schematic_compute.graph.CustomNodeRoster;
import io.github.y15173334444.create_schematic_compute.graph.NodeType;
import java.util.Set;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 宿主套件（波 2）的注册纪律锚：bind 校验矩阵（null / 键形态 / 保留命名空间 / 重复
 * 键）、bind 即键声明（登记表可观测）、适配器登记经 resolver 可达（BE 本体不实现图
 * 契约仍可解析）、准入偏好转内部名单（类目放行 + 类内排除 + 未知排除 id fail-fast）。
 * Headless 取型：BlockEntity 以 null 类型/状态构造（bind 只持引用，不触注册表引导）。
 * Registration-discipline anchors for the host suite (wave 2): the bind validation
 * matrix (null / key shape / reserved namespace / duplicate key), bind-means-declare
 * (the key registry is observable), adapter reachability through the resolver (the BE
 * itself does not implement the graph contract yet still resolves), and the admission
 * conversion into the internal allowance (category admission + in-category exclusions +
 * fail-fast on unknown exclusion ids). Headless shape: BlockEntity constructed with a
 * null type/state (bind holds the reference only, no registry bootstrap).
 */
class HostGraphSuiteTest {

    /** 无头僵尸 BE（Unsafe 免构造实例，TextRulerTest 同款 idiom）：bind 只持引用，
     *  受测路径（校验/声明/resolver/准入换算）不触宿主回调，故可绕开 BlockEntity
     *  构造器的注册表引导依赖（无头测试禁区：直接构造起不来）。
     *  A headless zombie BE (Unsafe no-constructor instance, the TextRulerTest idiom):
     *  bind holds the reference only, and the tested paths (validation/declare/resolver/
     *  admission conversion) never touch the host callbacks, so the constructor's
     *  registry-bootstrap dependency is bypassed (the headless-test no-go list: direct
     *  construction will not start). */
    /** 具体子类壳（allocateInstance 只吃具体类；构造器被 Unsafe 绕开，字段全 null）。
     *  A concrete shell (allocateInstance only takes concrete classes; the constructor is
     *  bypassed by Unsafe, all fields null). */
    private static final class ZombieBe extends BlockEntity {
        // 仅满足编译（super 链需要）；实例经 Unsafe 免构造创建，此构造器永不执行。
        // Compile-satisfaction only (the super chain needs it); instances are created via
        // Unsafe without construction, so this constructor never runs.
        ZombieBe() {
            super(null, BlockPos.ZERO, null);
        }
    }

    private static BlockEntity be() {
        try {
            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            return (BlockEntity) unsafe.allocateInstance(ZombieBe.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("zombie BlockEntity unavailable", e);
        }
    }

    private static HostGraphType spec(String key) {
        return HostGraphType.of(key, HostAdmission.of(HostCategory.CUSTOM, HostCategory.VALUES),
            HostGraphType.EvalVariant.BASIC);
    }

    @AfterEach
    void isolate() {
        CustomNodeRoster.clearForTests();
        GraphBlockEntities.clearForTests();
    }

    @Test
    @DisplayName("bind 校验矩阵：null/键形态/保留命名空间/重复键")
    void bindValidationMatrix() {
        var behavior = new HostGraphBehavior() {};
        assertThrows(NullPointerException.class, () -> HostGraphs.bind(null, spec("mod_a:h"), behavior),
            "a null owner is refused");
        assertThrows(NullPointerException.class, () -> HostGraphs.bind(be(), null, behavior),
            "a null spec is refused");
        assertThrows(NullPointerException.class, () -> HostGraphs.bind(be(), spec("mod_a:h"), null),
            "a null behavior is refused");
        assertThrows(IllegalArgumentException.class, () -> HostGraphs.bind(be(), spec("nokey"), behavior),
            "keys without a namespace are refused");
        assertThrows(IllegalArgumentException.class, () -> HostGraphs.bind(be(), spec(":lonely"), behavior),
            "empty namespaces are refused");
        assertThrows(IllegalArgumentException.class, () -> HostGraphs.bind(be(), spec("mod_a:"), behavior),
            "empty key names are refused");
        assertThrows(IllegalArgumentException.class, () -> HostGraphs.bind(be(), spec("csc:imposter"), behavior),
            "the csc namespace is reserved");
        assertThrows(IllegalArgumentException.class,
            () -> HostGraphs.bind(be(), spec("create_schematic_compute:imposter"), behavior),
            "the mod-id namespace is reserved");

        BlockEntity owner = be();
        HostGraphHandle handle = HostGraphs.bind(owner, spec("mod_a:electric"), behavior);
        assertNotNull(handle, "a valid bind returns the engine handle");
        assertTrue(CustomNodeRoster.declaredHostKeys().contains("mod_a:electric"),
            "bind means declare — the key is observable");
        HostGraphHandle second = HostGraphs.bind(be(), spec("mod_a:electric"), behavior);
        assertNotNull(second, "a second BE of the same host type binds fine (keys are per-type,"
            + " binds per-instance; the declaration is idempotent)");
        assertThrows(IllegalStateException.class, () -> CustomNodeRoster.declareHostKey(
            BlockNodeAllowances.BLUEPRINT.hostKey()),
            "a CSC allowlist key still cannot be declared by the suite path");
    }

    @Test
    @DisplayName("resolver：BE 本体不实现图契约仍可解析为适配器；clear 摘除")
    void resolverFindsAdapters() {
        BlockEntity owner = be();
        assertFalse(owner instanceof io.github.y15173334444.create_schematic_compute.blocks.GraphBlockEntity,
            "the third-party BE itself implements no graph contract (the premise of the suite)");
        assertNull(GraphBlockEntities.resolve(owner), "an unbound BE resolves to null (fail-closed)");
        HostGraphs.bind(owner, spec("mod_a:socket"), new HostGraphBehavior() {});
        var resolved = GraphBlockEntities.resolve(owner);
        assertNotNull(resolved, "a bound BE resolves to its adapter");
        assertEquals("mod_a:socket", resolved.nodeAllowance().hostKey(),
            "the adapter answers the declared host key");
        GraphBlockEntities.clearForTests();
        assertNull(GraphBlockEntities.resolve(owner), "clearForTests empties the registry");
    }

    @Test
    @DisplayName("准入换算：类目放行、类内排除、未知排除 id fail-fast")
    void admissionConversion() {
        var behavior = new HostGraphBehavior() {};
        // CUSTOM（名单制）+ VALUES，排除 add：CUSTOM 节点随名单放行、ADD 拒、CONST 放行。
        BlockEntity owner = be();
        HostGraphs.bind(owner,
            HostGraphType.of("mod_a:calc",
                HostAdmission.ofExcluding(HostAdmission.of(HostCategory.CUSTOM, HostCategory.VALUES).categories(),
                    "add"),
                HostGraphType.EvalVariant.BASIC),
            behavior);
        var resolved = GraphBlockEntities.resolve(owner);
        var allowance = resolved.nodeAllowance();
        assertTrue(allowance.allows(NodeType.CONST), "VALUES admits CONST");
        assertFalse(allowance.allows(NodeType.ADD), "the excluded add node refuses");
        assertTrue(allowance.allows(NodeType.CUSTOM),
            "CUSTOM rides the category face (the roster subdivides by id)");
        assertEquals("mod_a:calc", allowance.hostKey(), "the roster key is the declared key");
        assertThrows(IllegalArgumentException.class,
            () -> HostGraphs.bind(be(),
                HostGraphType.of("mod_a:typo",
                    new HostAdmission(HostAdmission.of(HostCategory.VALUES).categories(),
                        java.util.Set.of("csc:no_such_node")),
                    HostGraphType.EvalVariant.BASIC),
                behavior),
            "an unknown exclusion id fails fast at bind");
        assertEquals(BlockNodeAllowances.BLUEPRINT.hostKey(),
            BlockNodeAllowances.BLUEPRINT.hostKey(),
            "sanity: the CSC allowlist key stays untouched by suite declarations");
    }
}
