package io.github.y15173334444.create_schematic_compute.blocks;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 动力仪表方块：Create 官方表（应力表/转速表）同款的 3 轴多状态放置 ——
 * {@code facing}（显示面朝向：**放置恒为水平**——贴墙=点击面、贴地/贴顶=正对玩家；
 * up/down 竖置态由扳手滚转环可达）+
 * {@code axis_along_first}
 * （旋转轴的正交分解，见 {@link DirectionalAxisKineticBlock#getRotationAxis}）。
 * Kinetic gauge block: Create-gauge-style 3-axis multi-state placement —
 * {@code facing} (display direction; placement is always horizontal — wall = clicked
 * face, floor/ceiling = toward the player; the up/down vertical states are reached via
 * the wrench roll rings) +
 * {@code axis_along_first} (rotation-axis decomposition, see
 * {@link DirectionalAxisKineticBlock#getRotationAxis}).
 *
 * <p><b>放置朝向（对官方表的有意偏离）/ placement direction (intentional deviation)</b>：
 * 贴墙放置 = 官方语义（显示面 = 点击面）；**贴地/贴顶改为"显示面水平正对玩家"**
 * （{@link KineticGaugeStates#facingForPlacement}）。原因：官方表把 `facing` 取成点击面，
 * 贴地/贴顶时点击面是竖直的，只剩 `axis_along_first` 一个自由度 —— 2 个状态换不出 4 个
 * 偏航角，屏幕只能朝西或朝北（2026-09-13 实测报告）。改后贴地/贴顶都是四向可选；
 * 代价是贴顶安装时斜板朝上翘进天花板。回归测试：{@code KineticGaugePlacementTest}。</p>
 * <p>On a wall the official semantics are kept (display = clicked face); floor/ceiling
 * placement instead points the display horizontally at the player, because a vertical clicked
 * face leaves only {@code axis_along_first} and two states cannot encode four yaws. The cost is
 * that a ceiling-mounted gauge tilts its panel up into the ceiling.</p>
 *
 * <p>放在有轴的面上时自动对齐轴（与 Create {@code GaugeBlock#getStateForPlacement}
 * 逐行同构）；自身沿旋转轴贯通传轴（基类 {@code hasShaftTowards}）。扳手三语义
 * （详见 {@link #getRotatedBlockState}，环表在 {@link KineticGaugeStates}）：
 * <b>轴端面</b> → 同轴四态按角点序滚转 90°（轴不动，对官方的有意偏离——官方会翻轴断连）；
 * <b>点上/下</b> → 沿当前倾侧偏航 90°（屏不翻面，另一处有意偏离）；
 * <b>其余面</b> → 与 Create {@link IWrenchable#getRotatedBlockState} 默认逐字相同。
 * 潜行拆除同样走 {@link IWrenchable} 默认。两个轴端面放行物品，其余面右键开图编辑器。</p>
 * <p>Shaft-bearing faces auto-align at placement (line-for-line Create
 * {@code GaugeBlock#getStateForPlacement}); rotation passes through along the shaft
 * (base {@code hasShaftTowards}). Three wrench semantics (see {@link #getRotatedBlockState},
 * rings in {@link KineticGaugeStates}): a <b>shaft-end</b> click rolls through the four
 * same-shaft states in corner order (shaft fixed — a deliberate deviation, official
 * pivots the shaft); a <b>Y-face</b> click yaws 90° staying on the current tilt (second
 * deviation); <b>every other face</b> is verbatim Create's
 * {@link IWrenchable#getRotatedBlockState} default. Sneak-dismantle also keeps
 * the {@link IWrenchable} default. The two axis-end faces pass item clicks through.</p>
 */
public class KineticGaugeBlock extends DirectionalAxisKineticBlock implements IBE<KineticGaugeBlockEntity> {

    public KineticGaugeBlock(Properties properties) {
        super(properties);
    }

    // ── 放置（Create GaugeBlock 同款轴感知）/ placement (Create GaugeBlock-style) ──

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        Direction face = context.getClickedFace();
        BlockPos placedOnPos = context.getClickedPos().relative(face.getOpposite());
        BlockState placedOnState = level.getBlockState(placedOnPos);

        // 贴着带轴的面放 → 屏幕垂直于轴、轴自动对齐（与 Create GaugeBlock 一致）。
        // Placing against a shaft-bearing face -> display ⊥ shaft, axis auto-aligned
        // (identical to Create's GaugeBlock).
        if (placedOnState.getBlock() instanceof IRotate rotate
                && rotate.hasShaftTowards(level, placedOnPos, placedOnState, face)) {
            BlockState toPlace = defaultBlockState();
            Direction horizontalFacing = context.getHorizontalDirection();
            Direction nearestLooking = context.getNearestLookingDirection();
            boolean lookPositive = nearestLooking.getAxisDirection() == AxisDirection.POSITIVE;
            if (face.getAxis() == Axis.X) {
                toPlace = toPlace.setValue(FACING, lookPositive ? Direction.NORTH : Direction.SOUTH)
                        .setValue(AXIS_ALONG_FIRST_COORDINATE, true);
            } else if (face.getAxis() == Axis.Y) {
                toPlace = toPlace.setValue(FACING, horizontalFacing.getOpposite())
                        .setValue(AXIS_ALONG_FIRST_COORDINATE, horizontalFacing.getAxis() == Axis.X);
            } else {
                toPlace = toPlace.setValue(FACING, lookPositive ? Direction.WEST : Direction.EAST)
                        .setValue(AXIS_ALONG_FIRST_COORDINATE, false);
            }
            return toPlace;
        }

        // 贴地/贴顶：**结构轴优先**（变速箱 RotatedPillarKineticBlock#getPreferredAxis）——
        // 邻居轴一致则对齐；无邻居轴时用 **nearest-looking**（Sable 会 mixin
        // orderedByNearest 到子世界局部系，getXRot 世界俯仰角在旋转结构上会错，
        // 2026-09 实机确认）。nearest 轴为 Y → 竖置，否则横置。
        // Floor/ceiling: **structure axis first** (transmission's getPreferredAxis);
        // when free-standing use **nearest-looking** — Sable mixins orderedByNearest
        // into sublevel-local space, while getXRot() is world pitch and wrong on
        // rotated physical structures. Y nearest axis → vertical, else horizontal.
        if (face.getAxis() == Axis.Y) {
            Direction facing = KineticGaugeStates.facingForPlacement(
                    face, context.getNearestLookingDirection(), context.getHorizontalDirection());
            Axis preferred = RotatedPillarKineticBlock.getPreferredAxis(context);
            boolean alongFirst;
            if (preferred != null) {
                alongFirst = alongFirstFor(facing.getAxis(), preferred);
            } else {
                boolean steep = context.getNearestLookingDirection().getAxis().isVertical();
                alongFirst = steep
                        ? KineticGaugeStates.alongFirstForVerticalShaft(facing)
                        : facing.getAxis() == Axis.Z;
            }
            return defaultBlockState()
                    .setValue(FACING, facing)
                    .setValue(AXIS_ALONG_FIRST_COORDINATE, alongFirst);
        }

        // 贴墙空放：屏幕朝向点击面，轴按基类默认逻辑选择。
        // Wall free placement: display faces the clicked face; axis per the base default.
        return super.getStateForPlacement(context);
    }

    /** 由（显示面轴、旋转轴）反解 axis_along_first —— 与基类 getRotationAxis 互逆。 */
    private static boolean alongFirstFor(Axis facingAxis, Axis shaft) {
        return switch (facingAxis) {
            case X -> shaft == Axis.Y;
            case Y -> shaft == Axis.X;
            case Z -> shaft == Axis.X;
        };
    }

    @Override
    protected Direction getFacingForPlacement(BlockPlaceContext context) {
        // 放置朝向的单一真相源：贴墙=点击面；贴地/贴顶=水平正对玩家
        // （竖置与否由下方 along_first 的陡视门控决定，facing 恒水平）。
        // Single source of truth for the placement direction — see KineticGaugeStates.
        return KineticGaugeStates.facingForPlacement(context.getClickedFace(),
                context.getNearestLookingDirection(), context.getHorizontalDirection());
    }

    // 放置恒为水平 facing，基类的竖直分支（及 getAxisAlignmentForPlacement）自放置路径不可达；
    // 仍不覆盖它 —— 竖置态只经扳手滚转环出现，不参与放置选轴。
    // Placement always yields a horizontal facing, so the base class's vertical branch (and
    // getAxisAlignmentForPlacement) is unreachable from placement; left un-overridden — the
    // vertical states only appear via the wrench roll rings.

    // ── 扳手旋转 / wrench rotation ──

    /**
     * 三语义：① <b>轴端面</b>（点击轴 ∥ 旋转轴）→ 同轴四态按角点序滚转 90°，轴不动
     * （环表见 {@link KineticGaugeStates#nextInShaftRoll}）；② <b>点上/下</b> → 沿当前
     * 倾侧偏航 90°，屏幕保持朝上/朝下不翻面（{@link KineticGaugeStates#nextInYaw}）；
     * ③ <b>其余面</b> → Create {@link IWrenchable#getRotatedBlockState} 默认逐字内联
     * （接口 default 无法 super 调用；本态下仅 facing 同轴面可达，走 cycle along_first）。
     * ①② 是对官方的有意偏离（官方轴端面会翻轴断连、Y 面会跳倾侧）。
     * Three semantics: ① shaft-end → 90° corner-order roll, shaft fixed; ② Y-face → yaw
     * staying on the current tilt; ③ everything else verbatim-inlines Create's default
     * (a default method cannot be super-called; for our states only the facing-axis faces
     * reach it, cycling along_first). ①② are deliberate deviations.
     */
    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        Direction facing = originalState.getValue(FACING);
        boolean alongFirst = originalState.getValue(AXIS_ALONG_FIRST_COORDINATE);
        Axis shaft = getRotationAxis(originalState);

        // 轴端面 → 同轴四态按角点序滚转（右上→右下→左下→左上，轴不动）。
        if (targetedFace.getAxis() == shaft) {
            KineticGaugeStates.WrenchTarget next =
                    KineticGaugeStates.nextInShaftRoll(facing, alongFirst, shaft);
            if (next != null) {
                return originalState.setValue(FACING, next.facing())
                        .setValue(AXIS_ALONG_FIRST_COORDINATE, next.alongFirst());
            }
            // 不在环上（不应发生：三环按轴划分 12 态）→ Create 默认一步兜底。
            // Off-ring (cannot happen: the three rings partition the 12 states by shaft)
            // → one official getClockWise step as insurance.
            return originalState.setValue(FACING, facing.getClockWise(shaft))
                    .setValue(AXIS_ALONG_FIRST_COORDINATE, alongFirst);
        }
        // 点上/下 → 沿当前倾侧偏航 90°（两环同为 W→N→E→S 视觉方向）；倒置屏不会翻回朝上。
        if (targetedFace.getAxis() == Axis.Y) {
            KineticGaugeStates.WrenchTarget next = KineticGaugeStates.nextInYaw(facing, alongFirst);
            return originalState.setValue(FACING, next.facing())
                    .setValue(AXIS_ALONG_FIRST_COORDINATE, next.alongFirst());
        }
        // 其余 = Create IWrenchable 默认（间接接口 super 不可调，逐行内联）。
        Axis click = targetedFace.getAxis();
        if (facing.getAxis() == click)
            return originalState.cycle(AXIS_ALONG_FIRST_COORDINATE);
        BlockState newState = originalState;
        do {
            newState = newState.setValue(FACING,
                    newState.getValue(FACING).getClockWise(click));
            if (click == Axis.Y)
                newState = newState.cycle(AXIS_ALONG_FIRST_COORDINATE);
        } while (newState.getValue(FACING).getAxis() == click);
        return newState;
    }

    // ── 交互 / interaction ──

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, net.minecraft.world.InteractionHand hand,
                                              BlockHitResult hitResult) {
        // 扳手永远让路（官方旋转/拆除优先于 UI）/ the wrench always yields (official
        // rotate/dismantle preempts the UI).
        if (stack.getItem() instanceof com.simibubi.create.content.equipment.wrench.WrenchItem)
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        // 轴端面放行（贴面接轴/齿轮等传动连接）/ axis-end faces pass through
        // (butt shafts/cogs against them to connect the drive).
        if (hitResult.getDirection().getAxis() == getRotationAxis(state))
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide()) {
            if (level.getBlockEntity(pos) instanceof KineticGaugeBlockEntity)
                openScreen(pos);
            return ItemInteractionResult.SUCCESS;
        }
        return ItemInteractionResult.CONSUME;
    }

    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    private static void openScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new KineticGaugeScreen(pos));
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType pathComputationType) {
        return false;
    }

    // ── IBE / block entity ──

    @Override
    public Class<KineticGaugeBlockEntity> getBlockEntityClass() {
        return KineticGaugeBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends KineticGaugeBlockEntity> getBlockEntityType() {
        return SchematicCompute.KINETIC_GAUGE_BE.get();
    }
}
