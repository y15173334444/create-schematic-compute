package com.example.create_schematic_compute.blocks;

import com.example.create_schematic_compute.SchematicCompute;
import com.example.create_schematic_compute.entity.ControlSeatEntity;
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

public class ControlSeatBlock extends BaseEntityBlock {
    public static final MapCodec<ControlSeatBlock> CODEC = simpleCodec(ControlSeatBlock::new);
    public static final BooleanProperty LIT = BooleanProperty.create("lit");
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    // 碰撞体：座椅面(5px)
    protected static final VoxelShape SHAPE = net.minecraft.world.level.block.Block.box(0, 0, 0, 16, 5, 16);

    public ControlSeatBlock(Properties p) {
        super(p);
        registerDefaultState(stateDefinition.any().setValue(LIT, false).setValue(FACING, Direction.NORTH));
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        super.createBlockStateDefinition(b); b.add(LIT, FACING);
    }
    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return ControlSeatBlockEntity.create(pos, state); }
    @Nullable @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l, BlockState s, BlockEntityType<T> t) {
        if(l.isClientSide()) return null; return createTickerHelper(t, SchematicCompute.CONTROL_SEAT_BE.get(), (lv,p,st,be)->be.tick());
    }
    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }

    @Override
    protected InteractionResult useWithoutItem(BlockState s, Level l, BlockPos p, Player pl, BlockHitResult h) {
        if (l.isClientSide()) return InteractionResult.SUCCESS;

        // Shift+右键 → 打开编辑 GUI
        if (pl.isShiftKeyDown()) {
            if (pl instanceof ServerPlayer sp && l.getBlockEntity(p) instanceof ControlSeatBlockEntity be)
                sp.openMenu(be, buf -> buf.writeBlockPos(p));
            return InteractionResult.SUCCESS;
        }

        // 已在本座椅上时不做任何事（~ 下马由数据包处理，保证服务端先处理下马）
        if (pl.isPassenger() && pl.getVehicle() instanceof com.example.create_schematic_compute.entity.ControlSeatEntity)
            return InteractionResult.SUCCESS;

        if (pl.isPassenger()) {
            pl.stopRiding();
        }
        // 如果该位置已有座椅实体，直接坐上去
        var existing = l.getEntities(null, pl.getBoundingBox().inflate(2)).stream()
            .filter(e -> e instanceof ControlSeatEntity)
            .findFirst();
        if (existing.isPresent()) {
            var oldSeat = (com.example.create_schematic_compute.entity.ControlSeatEntity) existing.get();
            pl.startRiding(oldSeat);
            if (l.getBlockEntity(p) instanceof ControlSeatBlockEntity be) be.setSeatEntity(oldSeat);
        } else {
            // 创建新的座椅实体（初始朝向与方块一致）
            var seat = new ControlSeatEntity(SchematicCompute.CONTROL_SEAT_ENTITY.get(), l);
            seat.setPos(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
            float initialYaw = s.getValue(FACING).toYRot();
            seat.setYRot(initialYaw);
            seat.yRotO = initialYaw;
            seat.setYHeadRot(initialYaw);
            l.addFreshEntity(seat);
            // 将实体引用存入 BE（sable 子类需要）
            if (l.getBlockEntity(p) instanceof ControlSeatBlockEntity be) {
                be.setSeatEntity(seat);
            }
            pl.startRiding(seat);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())) {
            // 方块被破坏时移除座椅实体并让乘客安全着陆
            if (!level.isClientSide()) {
                var entities = level.getEntitiesOfClass(ControlSeatEntity.class,
                    new net.minecraft.world.phys.AABB(pos).inflate(1));
                for (var seat : entities) {
                    seat.ejectPassengers();
                    seat.remove(net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                }
            }
            super.onRemove(state, level, pos, newState, moved);
        }
    }
}
