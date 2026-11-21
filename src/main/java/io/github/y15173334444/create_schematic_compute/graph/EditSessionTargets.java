package io.github.y15173334444.create_schematic_compute.graph;

import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 编辑会话落点的宿主识别窄口（宿主套件波 2）：把「BlockEntity → EditSessionTarget」
 * 的识别收在 graph 公共域 —— 本模组自有宿主直接就是 {@link EditSessionTarget}，宿主
 * 套件 bind 的第三方宿主经本类查适配器登记（适配器实现链覆盖该窄口）。住 graph 域的
 * 理由与 {@link EditSessionTarget} 同源：network 的 EditSessionRegistry 按
 * 反向包边纪律不 import blocks.*，套件识别面必须与窄口同处共享域。
 * The host-resolution narrow port for edit-session targets (host suite, wave 2): the
 * "BlockEntity → EditSessionTarget" resolution lives in the graph shared domain — this
 * mod's own hosts are EditSessionTargets outright, while third-party hosts bound through
 * the host suite resolve via the adapter registry (whose implementation chain covers this
 * port). The reason mirrors EditSessionTarget's own: the network's EditSessionRegistry
 * must not import blocks.* under the reverse package-edge discipline, so the suite
 * resolution face shares the narrow port's domain.
 *
 * <p>登记表弱键（BE 拆除即随 GC 释放；适配器拆除路径另行显式摘除以保及时性）。
 * bind 在服务端与客户端两侧的 BE 构造期各发生一次，登记表两侧各自成立。</p>
 * <p>The registry is weakly keyed (a broken BE rides GC; the adapter's removal path also
 * unregisters explicitly for promptness). bind happens once per side at BE construction,
 * so the registry stands on both sides.</p>
 */
public final class EditSessionTargets {

    private EditSessionTargets() {}

    private static final Map<BlockEntity, EditSessionTarget> SUITE_TARGETS =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** 宿主识别：自有宿主直通，套件宿主查登记表，否则 null（fail-closed）。
     *  Host resolution: own hosts pass through, suite hosts consult the registry,
     *  anything else is null (fail-closed). */
    @Nullable
    public static EditSessionTarget resolve(@Nullable BlockEntity be) {
        if (be == null) return null;
        if (be instanceof EditSessionTarget t) return t;
        return SUITE_TARGETS.get(be);
    }

    /** bind 时的适配器登记（api 门面委托边专用）。 / Adapter registration at bind
     *  (the api facade's delegate edge only). */
    /** public 仅为 api 门面→适配器的委托边服务（波 1 CustomNodeRoster 同款护栏）。
     *  Public only to serve the api-facade→adapter delegate edge (the wave-1 guardrail family). */
    public static void registerAdapter(BlockEntity owner, EditSessionTarget adapter) {
        SUITE_TARGETS.put(owner, adapter);
    }

    /** 拆除时的显式摘除（柄的 onHostRemoved 路径）。 / Explicit unregistration on removal
     *  (the handle's onHostRemoved path). */
    public static void unregisterAdapter(BlockEntity owner) {
        SUITE_TARGETS.remove(owner);
    }

    /** 清空登记表（仅测试隔离用）。 / clear the registry (test isolation only). */
    public static void clearForTests() {
        SUITE_TARGETS.clear();
    }
}
