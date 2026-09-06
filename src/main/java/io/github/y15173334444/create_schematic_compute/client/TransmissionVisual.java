package io.github.y15173334444.create_schematic_compute.client;

import java.util.function.Consumer;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.AbstractInstance;
import dev.engine_room.flywheel.lib.instance.FlatLit;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.ProgrammableTransmissionBlock;
import io.github.y15173334444.create_schematic_compute.blocks.ProgrammableTransmissionBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/**
 * 可编程变速器 Flywheel 视觉（CNC 齿轮箱同款轴承效果）：两端传动轴是**两个独立的
 * 旋转体**（正端/负端各一个 RotatingInstance），**独立运动**——输入端轴恒随网络
 * 速度旋转；输出端轴按程序目标转速（{@code appliedTarget}，带符号可反转）旋转，
 * 体现"变速器两端各说各话"的 conveyed 语义。角度演化由 Flywheel 引擎按
 * rotationalSpeed 积分，相位由 RotatingInstance.setup 的官方 rotationOffset 提供
 * ——与官方轴完全同步。
 * Programmable transmission Flywheel visual (CNC-gearbox-style bearing effect):
 * the two shaft ends are TWO independent rotating bodies (positive/negative end
 * RotatingInstance) that move INDEPENDENTLY — the input shaft always spins at the
 * network speed; the output shaft spins at the program's target speed
 * ({@code appliedTarget}, signed, can reverse), reflecting the conveyed
 * "two ends speak independently" semantics. The engine integrates the angle from
 * rotationalSpeed; the official rotationOffset phase comes from
 * RotatingInstance.setup.
 */
public class TransmissionVisual extends KineticBlockEntityVisual<ProgrammableTransmissionBlockEntity> {

    public static final PartialModel FRONT_SHAFT = PartialModel.of(rl("block/transmission_shaft_front"));
    public static final PartialModel REAR_SHAFT = PartialModel.of(rl("block/transmission_shaft_rear"));

    private final RotatingInstance front;
    private final RotatingInstance rear;

    public TransmissionVisual(VisualizationContext ctx, ProgrammableTransmissionBlockEntity be, float partialTick) {
        super(ctx, be, partialTick);
        Direction dir = Direction.get(Direction.AxisDirection.POSITIVE, rotationAxis());
        front = createShaft(FRONT_SHAFT, dir, shaftSpeed(be, true));
        rear = createShaft(REAR_SHAFT, dir, shaftSpeed(be, false));
    }

    private RotatingInstance createShaft(PartialModel model, Direction dir, float speed) {
        RotatingInstance instance = instancerProvider().instancer(AllInstanceTypes.ROTATING, Models.partial(model))
            .createInstance()
            .setup(blockEntity, speed)            // 官方相位（rotationOffset）在此应用
            .setPosition(getVisualPosition())
            .rotateToFace(Direction.SOUTH, dir);  // 模型沿 Z（SOUTH）源方向，对齐实际旋转轴
        instance.setChanged();
        return instance;
    }

    @Override
    public void update(float pt) {
        front.setup(blockEntity, shaftSpeed(blockEntity, true)).setChanged();
        rear.setup(blockEntity, shaftSpeed(blockEntity, false)).setChanged();
    }

    /**
     * 每端轴头的显示转速 —— **镜像贴面邻居的实际转速**：轴面贴着动力邻居时显示
     * 邻居的真实转速（该邻居自己的渲染就是这个值，junction 处严格锁相，无论
     * conveyed 语义把网络收敛到哪）；空置轴头显示本体转速（无动力即静止）。
     * 旧实现显示自身网络速度/appliedTarget 等状态推导值 —— 由于 SpeedController
     * 语义会把整网收敛到目标转速（官方 getDesiredOutputSpeed 复刻：
     * wheelPowersController+targeting → targetSpeed），自身网络速度就等于目标，
     * 两端便都"随输出转"且与贴面邻居脱节。
     * Per-end stub speed — MIRRORS the actual speed of the kinetic neighbour at that
     * shaft face (the neighbour's own renderer shows exactly that value, so the
     * junction is phase-locked no matter where the conveyed semantics converge the
     * network); an empty stub shows the block's own body speed (static when
     * unpowered). The old implementation showed state-derived values (own network
     * speed / appliedTarget): official SC semantics converge the whole network to
     * the target (getDesiredOutputSpeed replica: wheelPowersController+targeting →
     * targetSpeed), so the own speed IS the target and both stubs "followed the
     * output" while drifting from their attached neighbours.
     */
    static float shaftSpeed(ProgrammableTransmissionBlockEntity be, boolean front) {
        if (be.getLevel() == null)
            return 0f;
        Direction.Axis axis = be.getBlockState().getValue(ProgrammableTransmissionBlock.AXIS);
        Direction face = Direction.fromAxisAndDirection(axis,
            front ? Direction.AxisDirection.NEGATIVE : Direction.AxisDirection.POSITIVE);
        BlockPos neighborPos = be.getBlockPos().relative(face);
        // 仅当对侧确实有轴面（双方 hasShaftTowards 皆真、真正耦合）才镜像；
        // 否则显示本体转速。 Mirror only when the opposite side truly carries a
        // shaft face (real coupling); otherwise show the body speed.
        if (be.getLevel().getBlockEntity(neighborPos) instanceof KineticBlockEntity kbe
            && kbe.getBlockState().getBlock() instanceof IRotate ir
            && ir.hasShaftTowards(be.getLevel(), neighborPos, kbe.getBlockState(), face.getOpposite()))
            return kbe.getSpeed();
        return be.getSpeed();
    }

    @Override
    public void updateLight(float partialTick) {
        relight(front, rear);
    }

    @Override
    protected void _delete() {
        front.delete();
        rear.delete();
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        consumer.accept(front);
        consumer.accept(rear);
    }

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, path);
    }
}
