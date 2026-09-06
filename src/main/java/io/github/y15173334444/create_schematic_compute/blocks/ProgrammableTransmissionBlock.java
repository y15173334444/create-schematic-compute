package io.github.y15173334444.create_schematic_compute.blocks;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 可编程变速器：沿轴（x/y/z 三向，官方 RotatedPillarKineticBlock 放置语义）两端出轴的
 * **从动件**——输入侧被上游驱动（官方传播语义），输出侧经 RotationPropagatorMixin
 * 按**程序目标转速（绝对值）**向外传播（SpeedController 同款 conveyed 语义复刻，见
 * {@link ProgrammableTransmissionBlockEntity#getConveyedSpeed}）。
 * Programmable transmission: a DRIVEN in-line member along the axis (x/y/z, official
 * RotatedPillarKineticBlock placement) — the input side is driven upstream (official
 * propagation); the output side conveys the program's ABSOLUTE target speed via
 * RotationPropagatorMixin (SpeedController-style conveyed semantics, see
 * {@link ProgrammableTransmissionBlockEntity#getConveyedSpeed}).
 *
 * <p>与旧单方块（独立源）的本质区别：本方块是官方应力网络的一等成员——不产生动力、
 * 不桥接应力、不存在收编/扫描/镜像问题；负载应力由真电机承担，无输入动力则无输出。</p>
 * <p>Unlike the old single block (independent source): this block is a first-class
 * citizen of the official stress network — it generates nothing, bridges no stress,
 * and has no adoption/sweep/mirror problems; the load's stress is carried by the
 * real motor, and no input power means no output.</p>
 */
public class ProgrammableTransmissionBlock extends RotatedPillarKineticBlock
        implements IWrenchable, IBE<ProgrammableTransmissionBlockEntity> {

    public ProgrammableTransmissionBlock(Properties properties) {
        super(properties);
    }

    /** 两端均为轴面（从动件直通形态）。 Both ends are shaft faces (in-line driven form). */
    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, net.minecraft.core.Direction face) {
        return face.getAxis() == state.getValue(AXIS);
    }

    @Override
    public net.minecraft.core.Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    @Override
    public Class<ProgrammableTransmissionBlockEntity> getBlockEntityClass() {
        return ProgrammableTransmissionBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends ProgrammableTransmissionBlockEntity> getBlockEntityType() {
        return SchematicCompute.TRANSMISSION_BE.get();
    }

    /** 右键交互（CNC 齿轮箱同款三方分流）：手持扳手或点在轴面（两端出轴面）→ 放行
     *  物品路径（贴面放置轴/齿轮连接传动，不开 UI）；点在侧面 → 打开图编辑器。
     *  Right-click interaction (same three-way pattern as the CNC gearbox): wrench
     *  in hand or click on a shaft face (either axis end) → pass to the item path
     *  (place shafts/cogs against the face to connect the drive — no UI); click on
     *  a side face → open the graph editor. */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(
            net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hitResult) {
        if (stack.getItem() instanceof com.simibubi.create.content.equipment.wrench.WrenchItem)
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (hitResult.getDirection().getAxis() == state.getValue(AXIS))
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide()) {
            if (level.getBlockEntity(pos) instanceof ProgrammableTransmissionBlockEntity)
                openScreen(pos);
            return net.minecraft.world.ItemInteractionResult.SUCCESS;
        }
        return net.minecraft.world.ItemInteractionResult.CONSUME;
    }

    @net.neoforged.api.distmarker.OnlyIn(net.neoforged.api.distmarker.Dist.CLIENT)
    private static void openScreen(BlockPos pos) {
        Minecraft.getInstance().setScreen(new TransmissionScreen(pos));
    }
}
