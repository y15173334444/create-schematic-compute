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

public class SensorBlock extends BaseEntityBlock {
    public static final MapCodec<SensorBlock> CODEC = simpleCodec(SensorBlock::new);
    public static final BooleanProperty LIT = BooleanProperty.create("lit");
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    /** 碰撞体：匹配模型体积（底座 10×3×10 + 后部两根柱子 1×7×1） */
    protected static final VoxelShape SHAPE = Block.box(3,0,3,13,10,13);
    public SensorBlock(Properties p) { super(p); registerDefaultState(stateDefinition.any().setValue(LIT,false).setValue(FACING,Direction.NORTH)); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx) { return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection()); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b) { super.createBlockStateDefinition(b); b.add(LIT,FACING); }
    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return SensorBlockEntity.create(pos, state); }
    @Nullable @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l, BlockState s, BlockEntityType<T> t) {
        if(l.isClientSide()) return null; return createTickerHelper(t, SchematicCompute.SENSOR_BE.get(), (lv,p,st,be)->be.tick());
    }
    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }
    @Override
    protected InteractionResult useWithoutItem(BlockState s, Level l, BlockPos p, Player pl, BlockHitResult h) {
        if(!l.isClientSide()&&pl instanceof ServerPlayer sp)
            if(l.getBlockEntity(p) instanceof SensorBlockEntity be) sp.openMenu(be, buf->buf.writeBlockPos(p));
        return InteractionResult.SUCCESS;
    }
}
