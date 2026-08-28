package io.github.y15173334444.create_schematic_compute.blocks;

import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 数控齿轮箱（运动块）：串在轴线上的**从动件 + 离合器**——输入面始终直通上游，
 * 输出面仅在接合（ENGAGED）时对外传轴；转速完全由上游变速器/网络决定，本方块
 * **不改变任何转速**，只做"接合/分离 + 行程记账（配额完成）+ 完成脉冲"。
 * CNC gearbox (motion block): a DRIVEN in-line member + clutch — the input face
 * always passes the upstream through; the output face carries a shaft only while
 * ENGAGED. Speed is entirely the upstream transmission's/network's business — this
 * block changes NO speed; it only engages/disengages, books travel (quota
 * completion) and fires done pulses.
 *
 * <p><b>离合语义</b>：分离 = 官方失源路径（下游失去本方块这个源 → 归零停转）；
 * 接合 = 官方合并路径（下游并入本网络，按网络转速运转）。空闲（无指令且无
 * CLUTCH 意图）自动分离。</p>
 * <p><b>Clutch semantics</b>: disengage = the official missing-source path (the
 * downstream loses this block as its source → stops); engage = the official merge
 * path (the downstream joins our network at network speed). Idle (no command, no
 * CLUTCH intent) auto-disengages.</p>
 */
public class CncGearboxBlock extends HorizontalAxisKineticBlock implements IBE<CncGearboxBlockEntity> {

    /** 输入面位于轴负方向端（否则为正方向端）。Input face on the axis-negative end. */
    public static final BooleanProperty INPUT_NEGATIVE = BooleanProperty.create("input_negative");
    /** 离合接合：true 时输出面带轴面。Clutch engaged: the output face carries a shaft. */
    public static final BooleanProperty ENGAGED = BooleanProperty.create("engaged");

    public CncGearboxBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(INPUT_NEGATIVE, ENGAGED);
        super.createBlockStateDefinition(builder);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        // 放置自动感知：哪端邻接传动方块，哪端设为输入面（默认负端）。
        // Placement auto-sense: whichever axis-end touches a kinetic neighbour becomes
        // the input face (defaults to the negative end).
        BlockPos pos = context.getClickedPos();
        Direction.Axis axis = state.getValue(HORIZONTAL_AXIS);
        Direction neg = axis == Direction.Axis.X ? Direction.WEST : Direction.NORTH;
        Direction pos2 = neg.getOpposite();
        Level level = context.getLevel();
        boolean negHas = level.getBlockEntity(pos.relative(neg)) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity;
        boolean posHas = level.getBlockEntity(pos.relative(pos2)) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity;
        if (posHas && !negHas)
            state = state.setValue(INPUT_NEGATIVE, false);
        else
            state = state.setValue(INPUT_NEGATIVE, true);   // 默认负端 / default negative
        return state.setValue(ENGAGED, false);   // 空闲断开 / idle disengaged
    }

    /** 输入面恒有轴面；输出面仅接合时有。 Input face always carries a shaft; output only when engaged. */
    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        if (face == inputFace(state, pos))
            return true;
        return state.getValue(ENGAGED) && face == outputFace(state, pos);
    }

    /** 输出面方向（输入面的对面）。 Output face = opposite of the input face. */
    public static Direction outputFace(BlockState state, BlockPos pos) {
        return inputFace(state, pos).getOpposite();
    }

    /** 输入面方向（由方块状态推导）。 Input face direction derived from state. */
    public static Direction inputFace(BlockState state, BlockPos pos) {
        Direction.Axis axis = state.getValue(HORIZONTAL_AXIS);
        boolean neg = state.getValue(INPUT_NEGATIVE);
        return axis == Direction.Axis.X ? (neg ? Direction.WEST : Direction.EAST)
                              : (neg ? Direction.NORTH : Direction.SOUTH);
    }

    /** 扳手右键：翻转输入端（先切除旧输出侧的离合关系）。翻面改变输出面朝向——
     *  分离状态下的翻面无需运动学处理；接合状态下先分离再翻。
     *  Wrench-right-click: flip the input end (severing the old output side's clutch
     *  first — a flip with the clutch engaged would leave the old chain stale). */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!level.isClientSide) {
            if (level.getBlockEntity(pos) instanceof CncGearboxBlockEntity gearbox)
                gearbox.disengageForFlip();
            BlockState flipped = state.cycle(INPUT_NEGATIVE);
            level.setBlock(pos, flipped, 3);
            Player player = context.getPlayer();
            if (player != null)
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "input face: " + inputFace(flipped, pos)), true);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public Class<CncGearboxBlockEntity> getBlockEntityClass() {
        return CncGearboxBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CncGearboxBlockEntity> getBlockEntityType() {
        return SchematicCompute.CNC_GEARBOX_BE.get();
    }

    /** 右键打开图编辑器（同款客户端直开屏样板）。 Right-click opens the graph editor. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            if (level.getBlockEntity(pos) instanceof CncGearboxBlockEntity)
                openScreen(pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }

    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    private static void openScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new CncGearboxScreen(pos));
    }
}
