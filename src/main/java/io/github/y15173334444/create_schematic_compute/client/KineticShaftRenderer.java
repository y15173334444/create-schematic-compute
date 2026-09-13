package io.github.y15173334444.create_schematic_compute.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;

/**
 * 传动轴渲染工具（与官方轴相位一致）。动力传感器用它画出穿过自身的旋转轴。
 * Shared rotating-shaft drawing helper (official phase). The kinetic gauge uses it to draw the
 * shaft passing through it.
 *
 * <p>角度用**官方的 {@link AnimationTickHolder} 渲染时钟** + 官方相位公式
 * （{@code renderTime * speed * 3/10 + rotationOffset}，模 360），相位与相邻的官方轴在任意时刻
 * 完全一致；{@code rotationOffset} 复刻官方 {@code KineticBlockEntityVisual.rotationOffset}
 * 的棋盘格 22.5°/0°。模型源方向为 +Z（同官方 {@code SHAFT_HALF} 的语义），按轴对齐后再自转。</p>
 * <p>Angle uses the official render clock and phase formula so the phase matches neighbouring
 * official shafts exactly; the model's source direction is +Z (official {@code SHAFT_HALF}
 * semantics), aligned to the axis and then spun.</p>
 *
 * <p><b>注意</b>：{@code TransmissionRenderer} / {@code CncGearboxRenderer} 里目前各有一份等价
 * 的私有副本（那两处已在实机验证过，本次不动它们）；后续可一并迁到这里，避免相位公式三处漂移。</p>
 * <p><b>Note</b>: the transmission and CNC-gearbox renderers still carry their own equivalent private
 * copies (verified in game, deliberately untouched here); they can be migrated later so the phase
 * formula lives in one place.</p>
 */
public final class KineticShaftRenderer {

    private KineticShaftRenderer() {}

    /** 官方角度公式（含相位）/ official angle formula including the phase offset. */
    public static float angleFor(Level level, BlockPos pos, Direction.Axis axis, float speed) {
        float time = AnimationTickHolder.getRenderTime(level);
        return ((time * speed * 3f / 10f + rotationOffset(axis, pos)) % 360f) / 180f * (float) Math.PI;
    }

    /** 官方相位复刻（KineticBlockEntityVisual.rotationOffset）：棋盘格 22.5°/0°。 */
    public static float rotationOffset(Direction.Axis axis, BlockPos pos) {
        return shouldOffset(axis, pos) ? 22.5f : 0f;
    }

    private static boolean shouldOffset(Direction.Axis axis, BlockPos pos) {
        int x = (axis == Direction.Axis.X) ? 0 : pos.getX();
        int y = (axis == Direction.Axis.Y) ? 0 : pos.getY();
        int z = (axis == Direction.Axis.Z) ? 0 : pos.getZ();
        return ((x + y + z) % 2) == 0;
    }

    /**
     * 画一个沿 {@code axis} 自转的轴模型。/ Draw one shaft model spinning around {@code axis}.
     *
     * @param angle 已含相位的自转角（弧度）/ spin angle in radians, phase included
     */
    public static void renderShaft(PoseStack ms, MultiBufferSource buffer, BlockState state,
                                   BakedModel model, Direction.Axis axis, float angle, int light) {
        ms.pushPose();
        ms.translate(0.5, 0.5, 0.5);
        switch (axis) {
            case X -> {
                // 模型沿 Z 源方向（同官方 SHAFT_HALF/SplitShaft 的 rotateToFace(SOUTH, dir) 语义）：
                // 先对齐到 X（Z→X 的 rotateTo），再绕 X 自转。mulPose 后调用的先作用于顶点。
                // Model points along +Z (official semantics): align +Z -> +X, then spin about X.
                ms.mulPose(new Quaternionf().rotateX(angle));
                ms.mulPose(new Quaternionf().rotateTo(0, 0, 1, 1, 0, 0));
            }
            case Y -> {
                ms.mulPose(new Quaternionf().rotateY(angle));
                ms.mulPose(new Quaternionf().rotateTo(0, 0, 1, 0, 1, 0));
            }
            case Z -> ms.mulPose(new Quaternionf().rotateZ(angle));
        }
        ms.translate(-0.5, -0.5, -0.5);

        VertexConsumer vc = buffer.getBuffer(RenderType.cutoutMipped());
        Minecraft.getInstance().getBlockRenderer().getModelRenderer()
            .renderModel(ms.last(), vc, state, model, 1f, 1f, 1f, light,
                OverlayTexture.NO_OVERLAY, net.neoforged.neoforge.client.model.data.ModelData.EMPTY,
                RenderType.cutoutMipped());
        ms.popPose();
    }
}
