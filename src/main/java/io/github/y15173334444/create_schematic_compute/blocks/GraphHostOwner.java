package io.github.y15173334444.create_schematic_compute.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;

/**
 * {@link GraphHost} 对宿主方块实体的回调视图 —— 组合式托管的最小依赖面。
 * Callback view of the host block entity for {@link GraphHost} — the minimal
 * dependency surface for composition-based hosting.
 *
 * <p>挂在 Create 继承线上的方块实体（如 ProgrammableGearbox）实现本接口，
 * 即可把图托管逻辑（GraphHost）组合进来，无需继承 SyncedGraphBlockEntity。</p>
 * <p>Block entities on Create's inheritance line (e.g. ProgrammableGearbox)
 * implement this to compose GraphHost without extending SyncedGraphBlockEntity.</p>
 */
public interface GraphHostOwner {

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
     * 继承线（{@link SyncedGraphBlockEntity}）与组合线（{@link GraphHost}）此前各写一份，
     * 现收敛到此处：判定逻辑漂移会让两条线的客户端行为不一致。
     * Decides whether a graph replacement must be skipped — the single implementation
     * of the editor bounce-back protection. The inheritance line
     * ({@link SyncedGraphBlockEntity}) and the composition line ({@link GraphHost})
     * each carried a copy before; it now lives here so the two lines cannot drift apart.
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
