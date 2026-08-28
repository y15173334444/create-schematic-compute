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
}
