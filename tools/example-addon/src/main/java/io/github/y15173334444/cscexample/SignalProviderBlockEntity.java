package io.github.y15173334444.cscexample;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 提供方方的 BE：持一个 0..15 信号值，NBT 持久化。求值回调经
 * {@code ctx.level().getBlockEntity(pos)} 拿到本类实例读值（见 ExampleNodes）。
 * The provider block's BE: holds one 0..15 signal value, NBT-persisted. The evaluation
 * callback reaches this class via ctx.level().getBlockEntity(pos) (see ExampleNodes).
 */
public class SignalProviderBlockEntity extends BlockEntity {

    private int signal = 5;

    public SignalProviderBlockEntity(BlockPos pos, BlockState state) {
        super(CscExampleMod.SIGNAL_PROVIDER_BE.get(), pos, state);
    }

    public int getSignal() {
        return signal;
    }

    public void setSignal(int value) {
        signal = Math.floorMod(value, 16);
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("signal", signal);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        signal = Math.floorMod(tag.getInt("signal"), 16);
    }
}
