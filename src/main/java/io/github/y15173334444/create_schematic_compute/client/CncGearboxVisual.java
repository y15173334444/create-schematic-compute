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
import io.github.y15173334444.create_schematic_compute.blocks.CncGearboxBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/**
 * 数控齿轮箱 Flywheel 视觉：两端传动轴是**两个独立的旋转体**（前端/后端各一个
 * RotatingInstance），各自按 BE 速度 setup —— 两端速度可不同（为变速器
 * "输入端=网络速度、输出端=绝对定速" 预留）；当前 CNC 两端同速（getSpeed）。
 * 角度演化由 Flywheel 引擎按 rotationalSpeed 累积，相位由
 * RotatingInstance.setup 的官方 rotationOffset 提供 —— 与官方轴完全同步。
 * CNC gearbox Flywheel visual: the two shaft ends are TWO independent rotating
 * bodies (front/rear RotatingInstance), each setup with the BE speed — the two
 * ends may run at different speeds (reserved for the transmission's input=network
 * speed / output=absolute target), while the CNC currently uses the same speed.
 * The engine integrates the angle from rotationalSpeed; the official
 * rotationOffset phase is applied inside RotatingInstance.setup.
 */
public class CncGearboxVisual extends KineticBlockEntityVisual<CncGearboxBlockEntity> {

    public static final PartialModel FRONT_SHAFT = PartialModel.of(rl("block/cnc_shaft_front"));
    public static final PartialModel REAR_SHAFT = PartialModel.of(rl("block/cnc_shaft_rear"));

    private final RotatingInstance front;
    private final RotatingInstance rear;

    public CncGearboxVisual(VisualizationContext ctx, CncGearboxBlockEntity be, float partialTick) {
        super(ctx, be, partialTick);
        Direction dir = Direction.get(Direction.AxisDirection.POSITIVE, rotationAxis());
        float speed = be.getSpeed();
        front = createShaft(FRONT_SHAFT, dir, speed);
        rear = createShaft(REAR_SHAFT, dir, speed);
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
        float speed = blockEntity.getSpeed();
        front.setup(blockEntity, speed).setChanged();
        rear.setup(blockEntity, speed).setChanged();
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
