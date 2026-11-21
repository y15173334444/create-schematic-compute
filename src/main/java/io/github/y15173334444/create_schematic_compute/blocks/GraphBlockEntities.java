package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.EditSessionTargets;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;

/**
 * 宿主识别统一入口（波 2）：把散在各 packet/client 消费面的
 * 「{@code instanceof GraphBlockEntity}」收敛为一个 resolver —— 本模组自有宿主直通，
 * 宿主套件 bind 的第三方宿主查适配器登记表；都无则 null（**fail-closed 保持**：
 * 未 bind 的第三方 BE 一律照旧被拒）。
 * The unified host-resolution entry (wave 2): converges the scattered
 * "{@code instanceof GraphBlockEntity}" gates across packet/client consumers into one
 * resolver — this mod's own hosts pass straight through, third-party hosts bound through
 * the host suite resolve via the adapter registry, everything else is null (**fail-closed
 * preserved**: an unbound third-party BE is refused exactly as before).
 *
 * <p>登记表弱键（BE 拆除即随 GC 释放；柄的 {@code onHostRemoved} 另行显式摘除以保
 * 及时性）。resolve 在服务端与客户端两侧都成立——bind 在两侧的 BE 构造期各发生一次。</p>
 * <p>The registry is weakly keyed (a broken BE rides GC; the handle's {@code
 * onHostRemoved} also unregisters explicitly for promptness). resolve works on both the
 * server and client sides — bind happens once per side at BE construction.</p>
 */
public final class GraphBlockEntities {

    private GraphBlockEntities() {}

    /** 宿主识别：自有宿主直通，套件宿主经 graph 域窄口查登记表，否则 null
     *  （fail-closed；登记表本体住 EditSessionTargets——network 纪律要求套件识别面
     *  与 EditSessionTarget 窄口同域）。
     *  Host resolution: own hosts pass through, suite hosts consult the graph-domain
     *  registry, anything else is null (fail-closed; the registry itself lives in
     *  EditSessionTargets — the network discipline requires the suite face to share the
     *  EditSessionTarget narrow port's domain). */
    @Nullable
    public static GraphBlockEntity resolve(@Nullable BlockEntity be) {
        if (be instanceof GraphBlockEntity gbe) return gbe;
        return EditSessionTargets.resolve(be) instanceof GraphBlockEntity gbe ? gbe : null;
    }

    /** 清空登记表（仅测试隔离用）。 / clear the registry (test isolation only). */
    public static void clearForTests() {
        EditSessionTargets.clearForTests();
    }
}
