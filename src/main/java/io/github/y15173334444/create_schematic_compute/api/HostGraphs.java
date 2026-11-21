package io.github.y15173334444.create_schematic_compute.api;

import io.github.y15173334444.create_schematic_compute.blocks.HostGraphAdapter;
import io.github.y15173334444.create_schematic_compute.graph.CustomNodeRoster;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 宿主套件注册门面（本模组公开 API 的组成部分，宿主套件波 2）：第三方图宿主的唯一
 * 登记入口。第三方 BE 在构造期调 {@link #bind} 拿 {@link HostGraphHandle}，tick/生命周期/
 * NBT 各一行转发给柄（接线口径见 {@link HostGraphHandle} 类注）；方块打开编辑器用
 * {@link #openEditor}。实现侧（适配器与引擎）住在 blocks/graph 包 —— 本类与波 1
 * {@link CustomNodes} 同款门面模式：api 只见契约，图域类型零暴露。
 * The host-suite registration facade (part of this mod's public API; host suite, wave 2):
 * the single entry point for third-party graph hosts. A third-party BE calls {@link #bind}
 * at construction to get a {@link HostGraphHandle}, forwards tick/lifecycle/NBT to it one
 * line each (the wiring contract is the {@link HostGraphHandle} class doc), and opens the
 * editor from its block via {@link #openEditor}. Implementations (the adapter and engine)
 * live in the blocks/graph packages — the same facade pattern as wave 1's
 * {@link CustomNodes}: the api package sees contracts only, zero graph-domain exposure.
 *
 * <p><b>bind 即键声明</b>：{@code hostKey} 强制 {@code modid:name} 形态（与波 1 节点 id
 * 同约定），命名空间不得用本模组保留段（{@code csc} / {@code create_schematic_compute}）；
 * 声明后该键进入 {@code CustomNodes.admitToHost} 的归属两路放行（声明者收自己命名空间
 * 的节点）。同类型宿主的多个方块实体各 bind 一次、键声明幂等。</p>
 * <p><b>bind means declare</b>: {@code hostKey} must be {@code modid:name} (the wave-1
 * node-id convention) and its namespace must not be one of this mod's reserved segments
 * ({@code csc} / {@code create_schematic_compute}); once declared the key joins
 * {@code CustomNodes.admitToHost}'s two-path ownership (the declarer enrols nodes in its
 * own namespace). Multiple block entities of one host type each bind; key
 * declaration is idempotent.</p>
 */
public final class HostGraphs {

    private HostGraphs() {}

    /** 本模组保留的键命名空间：第三方宿主键不得使用（自家宿主走内部登记缝）。
     *  This mod's reserved key namespaces: third-party host keys must not use them (this
     *  mod's own hosts register through the internal seam). */
    private static final Set<String> RESERVED_NAMESPACES = Set.of("csc", "create_schematic_compute");

    /** 编辑器开启钩子（client-only 初始化注册；专用服务器上恒 null）。/ The editor-opener
     *  hook (registered by client-only init; always null on a dedicated server). */
    @Nullable
    private static volatile Consumer<BlockPos> editorOpener;

    /**
     * 绑定一个第三方图宿主（在 BE 构造期调用一次；服务端与客户端两侧的 BE 构造都要调）。
     * 返回引擎柄，宿主 BE 的 tick/NBT/生命周期各一行转发（接线口径见
     * {@link HostGraphHandle}）。绑定即声明宿主键（键登记表 + 归属放行，见类注）。
     * <p>Bind a third-party graph host (once, at BE construction; call it on both the
     * server- and client-side BE instances). Returns the engine handle; the host BE
     * forwards tick/NBT/lifecycle one line each (see {@link HostGraphHandle}). Binding
     * declares the host key (the key registry + ownership, see the class doc).</p>
     *
     * @param owner    宿主方块实体（引擎经契约回调反驱动的对象）/ the host block entity
     *                 (the object the engine drives back through the contract callbacks)
     * @param spec     声明面（键/准入/偏差）/ the declaration face (key/admission/deviations)
     * @param behavior 缝面（逐 tick 宿主行为）/ the seam face (per-tick host behaviour)
     * @return 引擎柄 / the engine handle
     * @throws NullPointerException     任一参数为 {@code null} / any argument is {@code null}
     * @throws IllegalArgumentException 键非 {@code modid:name} 形态，或命名空间落在保留段
     *                                  / the key is not {@code modid:name}, or its namespace
     *                                  is reserved
     * @throws IllegalStateException    键与 CSC 显式白名单撞键（经声明口） / the key
     *                                  collides with the CSC allowlist (via the
     *                                  declaration point)
     */
    public static HostGraphHandle bind(BlockEntity owner, HostGraphType spec, HostGraphBehavior behavior) {
        java.util.Objects.requireNonNull(owner, "bind owner");
        java.util.Objects.requireNonNull(spec, "bind spec");
        java.util.Objects.requireNonNull(behavior, "bind behavior");
        String key = spec.hostKey();
        int colon = key.indexOf(':');
        if (colon <= 0 || colon == key.length() - 1)
            throw new IllegalArgumentException("host key must be modid:name: " + key);
        String ns = key.substring(0, colon);
        if (RESERVED_NAMESPACES.contains(ns))
            throw new IllegalArgumentException("host key namespace is reserved for this mod's own hosts: " + key);
        // 键声明幂等：键属于宿主类型、bind 属于实例——同类型多个 BE 各 bind 一次，
        // 同键重登是常态（实例专属状态住适配器登记表，按 BE 实例分键）。
        // Key declaration is idempotent: the key belongs to the host type, bind to the
        // instance — multiple BEs of one type each bind, re-declaring the same key is the
        // norm (per-instance state lives in the adapter registry, keyed by BE instance).
        HostGraphAdapter adapter = new HostGraphAdapter(owner, spec, behavior);
        CustomNodeRoster.declareHostKey(key);
        return adapter.handle();
    }

    /**
     * 打开本模组的图编辑器（第三方方块 {@code useWithoutItem} 的客户端分支调用一行）。
     * 目标坐标必须是一个已 bind 的宿主 BE（或本模组自有宿主）；否则静默返回（编辑器
     * 没有可打开的图不是错误）。
     * <p>Open this mod's graph editor (one line from a third-party block's client-side
     * {@code useWithoutItem}). The target must be a bound host BE (or this mod's own
     * host); otherwise this returns silently (no editor to open is not an error).</p>
     *
     * @throws IllegalStateException 在专用服务器/服务端线程调用（编辑器是客户端行为，
     *                               开启实现只在客户端注册）
     *                               / called on a dedicated server or server thread
     *                               (the editor is a client-side behaviour; the opener
     *                               registers client-side only)
     */
    public static void openEditor(BlockPos pos) {
        Consumer<BlockPos> opener = editorOpener;
        if (opener == null)
            throw new IllegalStateException(
                "opening a graph editor is client-side only (the opener registers on the client)");
        opener.accept(pos);
    }

    /**
     * 注册编辑器开启实现（内部缝：本模组客户端初始化调用；第三方不经过此口）。
     * <p>Registers the editor-opener implementation (internal seam: called by this mod's
     * client init; third parties never touch this).</p>
     */
    public static void registerEditorOpener(Consumer<BlockPos> opener) {
        editorOpener = java.util.Objects.requireNonNull(opener, "editor opener");
    }
}
