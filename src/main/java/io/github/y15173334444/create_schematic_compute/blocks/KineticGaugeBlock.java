package io.github.y15173334444.create_schematic_compute.blocks;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.DirectionalAxisKineticBlock;
import com.simibubi.create.content.kinetics.base.IRotate;
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
 * {@code facing}（显示面朝向：平视放置为水平、俯视/仰视放置为 up/down，即"竖置"可直接放出）+
 * {@code axis_along_first}
 * （旋转轴的正交分解，见 {@link DirectionalAxisKineticBlock#getRotationAxis}）。
 * Kinetic gauge block: Create-gauge-style 3-axis multi-state placement —
 * {@code facing} (display direction, always horizontal) +
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
 * 逐行同构）；自身沿旋转轴贯通传轴（基类 {@code hasShaftTowards}）。扳手语义对齐
 * 本模组变速箱家族（见下），潜行拆除走 Create 官方 {@link IWrenchable} 默认路径。
 * 两个轴端面放行物品（贴面接轴），其余面右键打开图编辑器。</p>
 * <p>Shaft-bearing faces auto-align the axis at placement (line-for-line Create
 * {@code GaugeBlock#getStateForPlacement}); the block passes rotation through along
 * its rotation axis (base {@code hasShaftTowards}). Wrench semantics align with this
 * mod's kinetic-block family (see below); sneak-dismantle keeps Create's official
 * {@link IWrenchable} default. The two axis-end faces pass item clicks through
 * (butt shafts/cogs against them); any other face opens the graph editor on
 * right-click.</p>
 *
 * <p><b>扳手旋转（对齐变速箱家族）/ Wrench rotation (kinetic-family semantics)</b>：
 * 不用 Create 默认的「点显示面 = 翻 {@code axis_along_first}」——那一翻会把旋转轴
 * 在水平/竖直之间切换，模型跳到 {@code _shaft_y} 变体且与相邻轴断开。两种点击语义
 * （判定集中在 {@link KineticGaugeStates#wrenchAction}）：
 * ① 点<b>轴端面</b>（点击轴 ∥ 旋转轴）**或** <b>屏幕正前方</b>
 * （{@link KineticGaugeStates#isDisplayFace}，45° 倾斜/斜偏的面板会有**两个**面同时正对屏幕）→
 * 屏幕绕<b>轴</b>循环 90°：轴与传动连接都不动，{@code axis_along_first} 按新朝向重算以保证轴不变。
 * **与 Create 一致**：Create 点端面就是绕轴 90° 转（作者 2026-09-13 指正）；
 * ② 点<b>其余两个面</b> → 整表刚性旋转一格：旋转轴绕点击轴转 90°（与变速器的轴循环同语义），
 * 显示面绕同一轴同步转 90°。</p>
 * <p><b>Wrench rotation</b>: NOT Create's default "click the display face = cycle
 * {@code axis_along_first}" — that flips the rotation axis between horizontal and vertical, jumps the
 * model to the {@code _shaft_y} variant and disconnects the gauge from its shaft. Two semantics (all
 * classified in {@link KineticGaugeStates#wrenchAction}): ① the <b>shaft end face</b> or the
 * <b>display side</b> (a 45°-tilted or yawed panel has two qualifying faces) cycles the display 90°
 * around the shaft, keeping the shaft and the drive connection — <b>matching Create</b>, which rotates
 * a block 90° around its end face; ② the remaining two faces rigidly rotate the whole gauge one step
 * (the transmission's axis cycling).</p>
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

        // 空地放置：屏幕朝向点击面（贴哪面朝哪面），轴按基类默认逻辑选择。
        // Free placement: display faces the clicked face; axis per the base default.
        return super.getStateForPlacement(context);
    }

    @Override
    protected Direction getFacingForPlacement(BlockPlaceContext context) {
        // 放置朝向的单一真相源：贴墙=点击面；贴地/贴顶=视线反向（平视→水平朝向玩家，俯视/仰视→
        // 显示面朝上/朝下，"竖置"可直接放出）。
        // Single source of truth for the placement direction — see KineticGaugeStates.
        return KineticGaugeStates.facingForPlacement(context.getClickedFace(),
                context.getNearestLookingDirection());
    }

    // 注意：**故意不覆盖** getAxisAlignmentForPlacement —— 竖直朝向（俯视/仰视放置得到的 up/down）
    // 下基类会走自己的竖直分支并用基类默认规则（horizontalDir 轴 == X）挑轴，本类不再另行覆盖。
    // 该分支自 2026-09-13 起重新可达（此前"恒为水平"的写法让竖置放不出来）。
    // Note: getAxisAlignmentForPlacement is deliberately NOT overridden — for the vertical facings
    // (up/down, obtained by a steep look) the base class uses its own default rule.

    // ── 扳手旋转（变速箱家族语义）/ wrench rotation (kinetic-family semantics) ──

    /**
     * 扳手旋转（三种点击面语义，详见类注释）：
     * <ul>
     *   <li>点**显示面** → 显示面绕**轴**循环 90°（轴不动、保持传动连接）——修掉"点屏幕结果整表转走"的意外；</li>
     *   <li>点**轴承面**（轴穿过的端面）→ 在轴上翻 180°；</li>
     *   <li>点**其它侧面** → 整表刚性旋转一格（变速器同款轴循环）。</li>
     * </ul>
     * Wrench rotation (three click semantics; full rationale in the class javadoc): clicking the
     * **display face** cycles the display 90° around the shaft (shaft untouched, connection kept);
     * clicking a **bearing face** flips it 180°; clicking any other side rigidly rotates the gauge.
     */
    @Override
    public BlockState getRotatedBlockState(BlockState originalState, Direction targetedFace) {
        Direction facing = originalState.getValue(FACING);
        boolean alongFirst = originalState.getValue(AXIS_ALONG_FIRST_COORDINATE);
        Axis shaft = getRotationAxis(originalState);
        Axis click = targetedFace.getAxis();
        if (KineticGaugeStates.wrenchAction(shaft, facing, alongFirst, targetedFace)
                == KineticGaugeStates.WrenchAction.CYCLE_DISPLAY) {
            // 轴端面 **或** 屏幕正前方（可能是两个方块面，见 isDisplayFace）→ 屏幕绕轴循环 90°：
            // 轴与传动连接都不动，只有朝向转一格（axis_along_first 按新朝向重算，保证轴不变）。
            // **与 Create 一致**：Create 点端面就是绕轴 90° 转（作者 2026-09-13 指正）。
            // Shaft end face OR the display side (possibly two block faces — see isDisplayFace) ->
            // cycle the display 90° around the shaft, keeping the shaft and the drive connection.
            // **Matches Create**: Create rotates a block 90° around its end face.
            Direction turned = facing.getClockWise(shaft);
            return originalState.setValue(FACING, turned)
                    .setValue(AXIS_ALONG_FIRST_COORDINATE, alongFirstFor(turned.getAxis(), shaft));
        }
        // 其它侧面 = 刚性旋转：轴绕点击轴 90°，显示面同轴跟转。
        // Any other side face = rigid rotation: the shaft pivots around the clicked axis, the
        // display turns along.
        Direction newFacing = facing.getClockWise(click);
        Axis newShaft = rotateAxisAround(shaft, click);
        return originalState.setValue(FACING, newFacing)
                .setValue(AXIS_ALONG_FIRST_COORDINATE, alongFirstFor(newFacing.getAxis(), newShaft));
    }

    /** 绕 click 轴把 shaft 转 90°：平行则不动，垂直则转到第三条轴。 */
    private static Direction.Axis rotateAxisAround(Direction.Axis shaft, Direction.Axis click) {
        if (shaft == click)
            return shaft;
        for (Direction.Axis a : Direction.Axis.values())
            if (a != shaft && a != click)
                return a;
        throw new IllegalStateException("unreachable");
    }

    /** 由（显示面轴、旋转轴）反解 axis_along_first —— 与基类 {@link #getRotationAxis} 互逆。 */
    private static boolean alongFirstFor(Direction.Axis facingAxis, Direction.Axis shaft) {
        return switch (facingAxis) {
            case X -> shaft == Direction.Axis.Y;   // X -> alongFirst ? Y : Z
            case Y -> shaft == Direction.Axis.X;   // Y -> alongFirst ? X : Z
            case Z -> shaft == Direction.Axis.X;   // Z -> alongFirst ? X : Y
        };
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
