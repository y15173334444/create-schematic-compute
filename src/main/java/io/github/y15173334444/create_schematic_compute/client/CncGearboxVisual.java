package io.github.y15173334444.create_schematic_compute.client;

import java.util.function.Consumer;

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
import io.github.y15173334444.create_schematic_compute.blocks.CncGearboxBlock;
import io.github.y15173334444.create_schematic_compute.blocks.CncGearboxBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/**
 * 数控齿轮箱 Flywheel 视觉：两端传动轴是**两个独立的旋转体**（前端/后端各一个
 * RotatingInstance），**独立运动**——输入端轴恒随网络速度旋转；输出端轴受离合
 * 控制（ENGAGED 时随网络速度、分离时静止为 0），体现"CNC 独立输出"语义。
 * 角度演化由 Flywheel 引擎按 rotationalSpeed 积分，相位由 RotatingInstance.setup
 * 的官方 rotationOffset 提供——与官方轴完全同步。
 * CNC gearbox Flywheel visual: the two shaft ends are TWO independent rotating
 * bodies (front/rear RotatingInstance) that move INDEPENDENTLY — the input shaft
 * always spins at the network speed; the output shaft is clutch-controlled
 * (network speed while ENGAGED, static 0 while disengaged), reflecting the
 * "CNC outputs on its own" semantics. The engine integrates the angle from
 * rotationalSpeed; the official rotationOffset phase comes from
 * RotatingInstance.setup.
 */
public class CncGearboxVisual extends KineticBlockEntityVisual<CncGearboxBlockEntity> {

    public static final PartialModel FRONT_SHAFT = PartialModel.of(rl("block/cnc_shaft_front"));
    public static final PartialModel REAR_SHAFT = PartialModel.of(rl("block/cnc_shaft_rear"));

    private final RotatingInstance front;
    private final RotatingInstance rear;

    public CncGearboxVisual(VisualizationContext ctx, CncGearboxBlockEntity be, float partialTick) {
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
     * 每端轴的独立转速：输入面（由 INPUT_NEGATIVE 决定）恒为网络速度；
     * 输出面仅在接合（ENGAGED）时随网络速度，分离时静止（0）。
     * Per-end independent shaft speed: the input face (per INPUT_NEGATIVE) always
     * runs at network speed; the output face spins at network speed only while
     * ENGAGED and is static (0) while disengaged.
     */
    private static float shaftSpeed(CncGearboxBlockEntity be, boolean front) {
        boolean inputFront = be.getBlockState().getValue(CncGearboxBlock.INPUT_NEGATIVE);
        boolean engaged = be.getBlockState().getValue(CncGearboxBlock.ENGAGED);
        boolean isInput = front == inputFront;
        return isInput || engaged ? be.getSpeed() : 0f;
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
