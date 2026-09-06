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
 * 数控齿轮箱 Flywheel 视觉：两端轴头独立运动——输入面（INPUT_NEGATIVE 决定哪端）
 * **恒随网络转速**；输出面仅在离合接合（ENGAGED）时随网络速度，**分离时静止**
 * （官方 SplitShaftVisual 分离侧转速修饰符归零的同款语义，已按需求恢复）。
 * 离合状态的另一视觉指示是运行灯贴图（cnc0/1/2）。
 * 角度演化由 Flywheel 引擎按 rotationalSpeed 积分，相位由 RotatingInstance.setup
 * 的官方 rotationOffset 提供——与官方轴完全同步。
 * CNC gearbox Flywheel visual: the two shaft stubs move INDEPENDENTLY — the input
 * face (which end is decided by INPUT_NEGATIVE) always spins at the network speed;
 * the output face spins at the network speed only while ENGAGED and is STATIC when
 * disengaged (same semantics as the official SplitShaftVisual zeroing the
 * disengaged side's speed modifier — restored per request). The clutch state is
 * also indicated by the run-state lamp textures (cnc0/1/2). The engine integrates
 * the angle from rotationalSpeed; the official rotationOffset phase comes from
 * RotatingInstance.setup — fully in sync with official shafts.
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
     * 每端轴头转速：输入面（INPUT_NEGATIVE 决定哪端）恒为网络速度；输出面仅在
     * 接合（ENGAGED）时随网络速度，分离时静止（0）。
     * Per-end stub speed: the input face (which end per INPUT_NEGATIVE) always runs
     * at the network speed; the output face spins at the network speed only while
     * ENGAGED and is static (0) while disengaged.
     */
    private static float shaftSpeed(CncGearboxBlockEntity be, boolean front) {
        float networkSpeed = be.getSpeed();
        boolean isInput = front == be.getBlockState().getValue(CncGearboxBlock.INPUT_NEGATIVE);
        if (isInput)
            return networkSpeed;
        return be.getBlockState().getValue(CncGearboxBlock.ENGAGED) ? networkSpeed : 0f;
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
