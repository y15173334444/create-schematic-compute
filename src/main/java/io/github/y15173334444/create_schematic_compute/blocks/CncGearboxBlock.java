package io.github.y15173334444.create_schematic_compute.blocks;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
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
public class CncGearboxBlock extends RotatedPillarKineticBlock implements IWrenchable, IBE<CncGearboxBlockEntity> {

    /** 输入面位于轴负方向端（否则为正方向端）。Input face on the axis-negative end. */
    public static final BooleanProperty INPUT_NEGATIVE = BooleanProperty.create("input_negative");
    /** 离合接合：true 时输出面传速（轴面恒在，见 {@link #hasShaftTowards}）。
     *  Clutch engaged: the output face transmits speed (shaft face always present). */
    public static final BooleanProperty ENGAGED = BooleanProperty.create("engaged");
    /** 运行状态灯（材质切换）：IDLE=cnc0 / RUN=cnc1 / COMMAND=cnc2。
     *  Run-state lamp (texture switch): IDLE=cnc0 / RUN=cnc1 / COMMAND=cnc2. */
    public static final EnumProperty<RunState> RUN_STATE = EnumProperty.create("run_state", RunState.class);

    /** 视觉运行档位 / visual run state. */
    public enum RunState implements net.minecraft.util.StringRepresentable {
        /** 初始/分离（贴图 cnc0） idle / disengaged (texture cnc0). */
        IDLE("idle"),
        /** 接合运行、无指令（贴图 cnc1） engaged running, no command (texture cnc1). */
        RUN("run"),
        /** 接合且指令执行中（贴图 cnc2） engaged with a command executing (texture cnc2). */
        COMMAND("command");

        private final String serializedName;

        RunState(String serializedName) {
            this.serializedName = serializedName;
        }

        @Override
        public String getSerializedName() {
            return serializedName;
        }
    }

    public CncGearboxBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(INPUT_NEGATIVE, ENGAGED, RUN_STATE);
        super.createBlockStateDefinition(builder);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        // 放置自动感知：哪端邻接传动方块，哪端设为输入面（默认负端）。两侧都有动力
        // 邻居（放进运转中的链条缺口）时，取转速更强的邻居一侧作为输入面。
        // Placement auto-sense: whichever axis-end touches a kinetic neighbour becomes
        // the input face (defaults to the negative end). With kinetic neighbours on
        // BOTH sides (placed into a running chain gap) the stronger-spinning side wins.
        BlockPos pos = context.getClickedPos();
        Direction.Axis axis = state.getValue(AXIS);
        Direction neg = inputNegativeDir(axis);
        Direction pos2 = neg.getOpposite();
        Level level = context.getLevel();
        boolean negHas = level.getBlockEntity(pos.relative(neg)) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity;
        boolean posHas = level.getBlockEntity(pos.relative(pos2)) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity;
        if (posHas && !negHas)
            state = state.setValue(INPUT_NEGATIVE, false);
        else if (negHas && posHas)
            state = state.setValue(INPUT_NEGATIVE,
                    Math.abs(neighbourSpeed(level, pos.relative(neg))) >= Math.abs(neighbourSpeed(level, pos.relative(pos2))));
        else
            state = state.setValue(INPUT_NEGATIVE, true);   // 默认负端 / default negative
        return state.setValue(ENGAGED, false).setValue(RUN_STATE, RunState.IDLE);   // 空闲断开 / idle disengaged
    }

    /** 轴负方向（X=西 / Z=北 / Y=下）。The axis-negative direction (X=west / Z=north / Y=down). */
    public static Direction inputNegativeDir(Direction.Axis axis) {
        return switch (axis) {
            case X -> Direction.WEST;
            case Z -> Direction.NORTH;
            case Y -> Direction.DOWN;
        };
    }

    /** 邻居实时转速（感知用）。Neighbour's live speed (for auto-sense). */
    private static float neighbourSpeed(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kbe
                ? kbe.getSpeed() : 0f;
    }

    /**
     * 两端轴面恒在（官方 SplitShaft / AbstractEncasedShaftBlock 同款，只看轴向）。
     * 分离不再靠「抽掉输出轴面」——那会连带关掉放置吸附与邻居耦合，贴上去的传动轴
     * 接不上。离合隔离改由 {@link CncGearboxBlockEntity#getRotationSpeedModifier}
     * （输出面分离时 0）+ 既有的 detach/attachKinetics 负责。
     * Both axis ends always carry a shaft face (official SplitShaft /
     * AbstractEncasedShaftBlock — axis-only). Isolation no longer removes the output
     * face (that also killed placement snap and neighbour coupling, so shafts placed
     * against the block would not attach); the clutch is enforced by
     * {@link CncGearboxBlockEntity#getRotationSpeedModifier} (0 on the output face
     * while disengaged) plus the existing detach/attachKinetics.
     */
    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == state.getValue(AXIS);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    /** 输出面方向（输入面的对面）。 Output face = opposite of the input face. */
    public static Direction outputFace(BlockState state, BlockPos pos) {
        return inputFace(state, pos).getOpposite();
    }

    /** 输入面方向（由方块状态推导）。 Input face direction derived from state. */
    public static Direction inputFace(BlockState state, BlockPos pos) {
        Direction.Axis axis = state.getValue(AXIS);
        boolean neg = state.getValue(INPUT_NEGATIVE);
        return switch (axis) {
            case X -> neg ? Direction.WEST : Direction.EAST;
            case Z -> neg ? Direction.NORTH : Direction.SOUTH;
            case Y -> neg ? Direction.DOWN : Direction.UP;
        };
    }

    // 扳手不再翻输入端：输入/输出面由放置感知 + autoSenseInputFace 自动识别（官方
    // 从动件同款）。端面/侧面扳手都走 IWrenchable 默认旋转（换轴重建动力网）。
    // Wrench no longer flips the input end — the input/output faces are auto-sensed
    // (placement + autoSenseInputFace), like a vanilla driven member. Wrenching any
    // face is the official IWrenchable rotate (axis swap + kinetic rebuild).

    @Override
    public Class<CncGearboxBlockEntity> getBlockEntityClass() {
        return CncGearboxBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CncGearboxBlockEntity> getBlockEntityType() {
        return SchematicCompute.CNC_GEARBOX_BE.get();
    }

    /** 右键交互（Create ElevatorContactBlock 同款三方分流）：
     *  手持扳手 → 放行物品路径（IWrenchable 官方旋转）；
     *  点在轴面（两端出轴面）→ 放行物品路径（贴面放置轴/齿轮连接传动，不开 UI）；
     *  点在侧面 → 打开图编辑器。
     *  Right-click interaction (Create's ElevatorContactBlock pattern, three-way):
     *  wrench in hand → pass to the item path (official IWrenchable rotate);
     *  click on a shaft face (either axis end) → pass to the item path
     *  (place shafts/cogs against the face to connect the drive — no UI); click on
     *  a side face → open the graph editor. */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(
            net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hitResult) {
        // 扳手永远让路：物品路径的 onWrenched 需要先于方块 UI。
        // The wrench always yields: the item path's onWrenched must preempt the UI.
        if (stack.getItem() instanceof com.simibubi.create.content.equipment.wrench.WrenchItem)
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        // 轴面点击放行（传动连接面）——点击方向沿旋转轴即两端出轴面。
        // Shaft-face clicks pass through (drive connection faces) — the click
        // direction lies along the rotation axis.
        if (hitResult.getDirection().getAxis() == state.getValue(AXIS))
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide()) {
            if (level.getBlockEntity(pos) instanceof CncGearboxBlockEntity)
                openScreen(pos);
            return net.minecraft.world.ItemInteractionResult.SUCCESS;
        }
        return net.minecraft.world.ItemInteractionResult.CONSUME;
    }

    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    private static void openScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new CncGearboxScreen(pos));
    }
}
