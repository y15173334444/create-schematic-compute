package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class BlueprintBlock extends BaseEntityBlock {
    public static final MapCodec<BlueprintBlock> CODEC = simpleCodec(BlueprintBlock::new);
    public static final BooleanProperty LIT = BooleanProperty.create("lit");
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    protected static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 15, 15);

    public BlueprintBlock(Properties p) {
        super(p);
        registerDefaultState(stateDefinition.any().setValue(LIT, false).setValue(FACING, Direction.NORTH));
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        super.createBlockStateDefinition(b); b.add(LIT, FACING);
    }
    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new BlueprintBlockEntity(pos, state); }
    @Nullable @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l, BlockState s, BlockEntityType<T> t) {
        if(l.isClientSide()) return null; return createTickerHelper(t, SchematicCompute.BLUEPRINT_BE.get(), (lv,p,st,be)->be.tick());
    }
    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }
    @Override
    protected InteractionResult useWithoutItem(BlockState s, Level l, BlockPos p, Player pl, BlockHitResult h) {
        if(!l.isClientSide()&&pl instanceof ServerPlayer sp)
            if(l.getBlockEntity(p) instanceof BlueprintBlockEntity be) sp.openMenu(be, buf->buf.writeBlockPos(p));
        return InteractionResult.SUCCESS;
    }
}
