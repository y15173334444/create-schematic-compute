package io.github.y15173334444.cscexample;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 示例提供方块：右键把信号按 5 步进（0→5→…→15→0），BE 持值。无 tick 逻辑（示例保持
 * 最小）；模型复用原版 iron_block 占位（见 assets 下 blockstate）。
 * The sample provider block: right-click steps the signal by 5 (0→5→…→15→0); the BE
 * holds the value. No tick logic (the sample stays minimal); the model reuses the
 * vanilla iron_block placeholder (see the blockstate under assets).
 */
public class SignalProviderBlock extends BaseEntityBlock {

    // 1.21.1 的 BaseEntityBlock 需要 codec（BE 方块的序列化身份）。
    // 1.21.1's BaseEntityBlock requires a codec (the BE block's serialisation identity).
    public static final com.mojang.serialization.MapCodec<SignalProviderBlock> CODEC =
        simpleCodec(SignalProviderBlock::new);

    public SignalProviderBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SignalProviderBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof SignalProviderBlockEntity be) {
            int next = (be.getSignal() + 5) % 20; // 0,5,10,15 循环 / cycles 0,5,10,15
            be.setSignal(next);
            player.displayClientMessage(
                Component.literal("signal = " + next), true);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        // 示例无 tick 逻辑：返回 null（BaseEntityBlock 契约允许）。
        // No tick logic in the sample: null (the BaseEntityBlock contract allows it).
        return null;
    }
}
