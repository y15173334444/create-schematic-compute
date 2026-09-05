package io.github.y15173334444.create_schematic_compute.blocks;

import io.github.y15173334444.create_schematic_compute.graph.EvalSnapshot;
import io.github.y15173334444.create_schematic_compute.graph.NodeGraph;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * 图宿主方块实体的唯一契约 —— 数据面（图/运行/快照/同步）与宿主绑定面
 * （生命周期回调 + 编辑回弹保护）的单一接口。
 * The single contract for graph-hosting block entities — both the data surface
 * (graph/runtime/snapshot/sync) and the host-binding surface (lifecycle callbacks +
 * editor bounce-back protection) in one interface.
 *
 * <p>阶段 2 契约收敛：原 {@code GraphHostOwner}（宿主绑定面）已并入本接口。实现者
 * 全部是方块实体（继承线 {@link SyncedGraphBlockEntity} 的 7 个子类 + 组合线
 * ProgrammableTransmission / CncGearbox），绑定方法由 BlockEntity 直接满足或
 * 一行转发；{@link GraphHost} 引擎以本接口为构造参数 —— 两线共用同一引擎、
 * 同一契约。SablePacketHelper 等外部消费者**只依赖本接口**，不再
 * instanceof 具体类型。</p>
 * <p>Phase-2 contract convergence: the former {@code GraphHostOwner} (host-binding
 * surface) has been merged into this interface. All implementors are block entities
 * (the 7 subclasses of the inheritance line plus the composition-line
 * ProgrammableTransmission / CncGearbox); binding methods are satisfied directly by
 * BlockEntity or by one-line forwards. The {@link GraphHost} engine takes this
 * interface as its constructor parameter — two lines, one engine, one contract.
 * External consumers such as SablePacketHelper depend on this interface only and no
 * longer instanceof concrete types.</p>
 */
public interface GraphBlockEntity {

    // ── 数据面 / data surface ────────────────────────────────────────────

    /** 暴露节点图供 packet handler 使用 / Expose the node graph for packet handlers. */
    default NodeGraph getNodeGraph() { return null; }

    /** 本图玩家可见名称（编辑器顶栏可编辑，便携终端按此查找；空 = 回退到方块类型名）。
     *  名称挂在 NodeGraph 上，随图走既有同步链路，故此只读视图即足够。
     *  Player-visible name of this graph (editable in the editor top bar, used by the
     *  portable terminal for lookup; empty = fall back to the block type name). The
     *  name lives on the NodeGraph and rides the existing sync pipeline, so a
     *  read-only view is all the contract needs. */
    default String getCustomName() {
        var g = getNodeGraph();
        return g != null ? g.customName : "";
    }

    void loadGraphFromBytes(byte[] data);

    default boolean isRunning() { return false; }

    default void setRunning(boolean running) {}

    default boolean graphHasCycles() { return false; }

    default void clearPidState() {}

    /** 服务端→客户端：同步 flipflopStates 用于 UI 实时显示 / Sync flipflop states for live UI. */
    default void syncFlipflopStates(Map<Integer, Boolean> states) {}

    /** 服务端→客户端：同步子图 flipflopStates 用于 UI 实时显示 / Sync sub-graph flipflop states. */
    default void syncSubFlipflopStates(Map<Integer, Map<Integer, Boolean>> subStates) {}

    /** 服务端→客户端：同步 BUS band 列表到本地图节点 / Sync BUS band lists to local graph nodes. */
    default void syncBusBandsFromServer(String busName, java.util.List<String> bands) {}

    /** 本地未 ACK 编辑 op 计数（客户端回弹保护）。/ Pending un-ACKed local edit ops (client bounce guard). */
    default int getPendingLocalOps() { return 0; }

    default void setPendingLocalOps(int value) {}

    /** 最近一次服务端求值快照（客户端渲染读取）。/ Latest server eval snapshot (read by client rendering). */
    default EvalSnapshot getCachedEvalSnapshot() { return EvalSnapshot.EMPTY; }

    default void setCachedEvalSnapshot(EvalSnapshot snapshot) {}

    /** 强制下次 getUpdateTag 携带完整图（编辑会话加入时调用）。/ Force full-graph sync on next update tag. */
    default void flagFullSync() {}

    /** 合并版全量同步请求（高频显示 op 用，按 tick 冲刷）。/ Coalesced full-sync request, flushed on tick. */
    default void requestFullSync() {}

    /** 客户端是否已从服务端收到图数据。/ True once the client received the graph from the server. */
    default boolean isGraphReady() { return true; }

    /** 读取指定封装节点的子图 flipflop 状态（GraphEditor 渲染徽标用）。
     *  Sub-graph flipflop states of an encapsulation node (for GraphEditor badges). */
    default Map<Integer, Boolean> peekSubStateFlipflops(int encapNodeId) { return java.util.Collections.emptyMap(); }

    /** 主图 flipflop 状态只读视图（GraphEditor / 屏幕徽标渲染用）。
     *  返回引擎的活映射，调用方只读不写 —— 写路径走 syncFlipflopStates。
     *  Read-only view of the main-graph flipflop states (GraphEditor / screen badge
     *  rendering). The engine's live map is returned; treat it as read-only — the write
     *  path is syncFlipflopStates. */
    default Map<Integer, Boolean> getFlipflopStates() { return java.util.Collections.emptyMap(); }

    // ── 宿主绑定面（原 GraphHostOwner）/ host-binding surface (formerly GraphHostOwner) ──

    /** 引擎回调视图：本 BE 作为 BlockEntity 的自身引用 / This BE as a plain BlockEntity (engine callback view). */
    BlockEntity asBlockEntity();

    @Nullable Level getLevel();

    BlockPos getBlockPos();

    void setChanged();

    /** 触发放块更新使追踪客户端收到新的 getUpdateTag。
     *  Trigger a block update so tracking clients receive a fresh update tag. */
    void sendBlockUpdated();

    /** 写入宿主类型特定 NBT（graph/running/runtime 由 GraphHost 负责）。
     *  Type-specific NBT hook (graph/running/runtime are GraphHost's business). */
    default void writeHostSpecific(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {}

    /** 读取宿主类型特定 NBT。 Type-specific NBT read hook. */
    default void readHostSpecific(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {}

    // ── 客户端编辑回弹保护钩子 ──────────────────────────────────────────
    //     Client-side editor bounce-back hooks
    //
    // 只有真正承载 UI 状态的宿主需要覆写这两个查询（默认 false）。判定本身统一走
    // isGraphReplaceBlocked()，两线共用一份实现。
    // Only hosts that actually carry UI state need to override these (default false).
    // The decision itself lives in isGraphReplaceBlocked(), shared by both lines.

    /** 客户端：像素编辑器当前是否打开（绘画不逐笔发 op，pendingLocalOps 守不住）。
     *  Client: is the pixel editor open? (Painting sends no per-stroke op, so the
     *  pendingLocalOps guard cannot cover it.) */
    default boolean isPixelEditorOpen() { return false; }

    /** 客户端：显示区元素是否正在拖拽（替换会孤儿化 draggedDisplayNode，拖拽不跟手）。
     *  Client: is a display element being dragged? (A replacement orphans
     *  draggedDisplayNode, so the drag stops following the cursor.) */
    default boolean isDisplayDragInProgress() { return false; }

    /**
     * 判定"本次图替换是否应被跳过"——编辑回弹保护的唯一实现。
     * 继承线（{@link SyncedGraphBlockEntity}）与组合线（{@link GraphHost}）共用此处判定：
     * 判定逻辑漂移会让两条线的客户端行为不一致。
     * Decides whether a graph replacement must be skipped — the single implementation
     * of the editor bounce-back protection, shared by the inheritance line and the
     * composition line so the two cannot drift apart.
     *
     * <p>跳过条件（任一成立即跳过）：编辑器已打开且本地仍有未 ACK 的编辑 op；
     * 或像素编辑器打开；或显示区正在拖拽。服务端永远返回 false —— 服务端是权威，
     * 必须接受 NBT。
     * Skipped when any holds: an editor is open with un-ACKed local ops; or the pixel
     * editor is open; or a display drag is in progress. Always false on the server —
     * the server is authoritative and must take the NBT.
     *
     * @param pendingLocalOps 未 ACK 的本地编辑 op 数 / un-ACKed local edit ops
     * @return true 表示必须跳过本地图替换 / true when the local replacement must be skipped
     */
    default boolean isGraphReplaceBlocked(int pendingLocalOps) {
        var lvl = getLevel();
        if (lvl == null || !lvl.isClientSide()) return false;
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (!(mc.screen instanceof GraphEditor.Host host) || !host.getBlockPos().equals(getBlockPos()))
            return false;   // 编辑器未打开 → 总是接受服务端权威图 / no editor → always accept
        // 编辑器打开且有未 ACK 的本地改动 → 保住本地编辑
        // Editor open with un-ACKed local edits → protect them.
        if (pendingLocalOps > 0) return true;
        // 这两项不逐笔发 op，pendingLocalOps 覆盖不到，需要独立的实时查询
        // These two send no per-op ACK, so the pendingLocalOps guard can't cover them.
        return host.isPixelEditorOpen() || host.isDisplayDragInProgress()
            || isPixelEditorOpen() || isDisplayDragInProgress();
    }
}
