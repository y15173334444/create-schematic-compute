package io.github.y15173334444.create_schematic_compute.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import io.github.y15173334444.create_schematic_compute.blocks.ProgrammableTransmissionBlock;
import io.github.y15173334444.create_schematic_compute.blocks.ProgrammableTransmissionBlockEntity;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;

/**
 * 可编程变速器渲染器（vanilla fallback，CNC 齿轮箱同款）：仅在 Flywheel 不可用时
 * 渲染两端传动轴（正端/负端各一，独立旋转体）。角度用**官方的 AnimationTickHolder
 * 渲染时钟** + 官方相位公式（renderTime * speed * 3/10 + rotationOffset，mod 360），
 * 与官方轴在任意时刻的相位完全一致。Flywheel 可用时由 {@link TransmissionVisual} 接管。
 * Programmable transmission renderer (vanilla fallback, CNC-gearbox-style): only
 * renders when Flywheel is unavailable — two independent shaft-end bodies
 * (positive/negative). Uses the OFFICIAL AnimationTickHolder render clock and the
 * official phase formula (renderTime * speed * 3/10 + rotationOffset, mod 360), so
 * the phase matches official shafts exactly. When Flywheel is available,
 * {@link TransmissionVisual} takes over.
 */
public class TransmissionRenderer implements BlockEntityRenderer<ProgrammableTransmissionBlockEntity> {

    public TransmissionRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(ProgrammableTransmissionBlockEntity be, float partialTick, PoseStack ms,
                       MultiBufferSource buffer, int light, int overlay) {
        if (be.getLevel() == null || be.isRemoved())
            return;
        // Flywheel 接管时跳过 vanilla 路径（官方 renderSafe 同款检查）
        if (VisualizationManager.supportsVisualization(be.getLevel()))
            return;
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof ProgrammableTransmissionBlock))
            return;

        Minecraft mc = Minecraft.getInstance();
        BakedModel front = TransmissionVisual.FRONT_SHAFT.get();
        BakedModel rear = TransmissionVisual.REAR_SHAFT.get();
        BakedModel missing = mc.getModelManager().getMissingModel();
        if (front == missing || rear == missing)
            return;

        Direction.Axis axis = state.getValue(ProgrammableTransmissionBlock.AXIS);
        // 与 Flywheel 视觉共用同一分流函数（输入端=网络速度，输出端仅在驱动时随目标），
        // 保证两条渲染路径的转速语义永不漂移。
        // The Flywheel visual and this fallback share ONE speed-splitting helper, so
        // the two render paths can never drift apart in speed semantics.
        float frontAngle = getAngleForBe(be, axis, TransmissionVisual.shaftSpeed(be, true));
        float rearAngle = getAngleForBe(be, axis, TransmissionVisual.shaftSpeed(be, false));

        renderShaft(ms, buffer, state, front, axis, frontAngle, light);
        renderShaft(ms, buffer, state, rear, axis, rearAngle, light);
    }

    private static void renderShaft(PoseStack ms, MultiBufferSource buffer, BlockState state,
                                    BakedModel model, Direction.Axis axis, float angle, int light) {
        ms.pushPose();
        ms.translate(0.5, 0.5, 0.5);
        switch (axis) {
            case X -> {
                // 模型沿 Z 源方向（同官方 SHAFT_HALF/SplitShaft 的 rotateToFace(SOUTH, dir) 语义）：
                // 先对齐到 X（Z→X 的 rotateTo），再绕 X 自转。mulPose 后调用的先作用于顶点。
                ms.mulPose(new Quaternionf().rotateX(angle));                     // spin：绕 X 自转
                ms.mulPose(new Quaternionf().rotateTo(0, 0, 1, 1, 0, 0));         // align：+Z → +X
            }
            case Y -> {
                ms.mulPose(new Quaternionf().rotateY(angle));                     // spin：绕 Y 自转
                ms.mulPose(new Quaternionf().rotateTo(0, 0, 1, 0, 1, 0));         // align：+Z → +Y
            }
            case Z -> ms.mulPose(new Quaternionf().rotateZ(angle));               // Z：模型已沿轴，直接自转
        }
        ms.translate(-0.5, -0.5, -0.5);

        VertexConsumer vc = buffer.getBuffer(RenderType.cutoutMipped());
        Minecraft.getInstance().getBlockRenderer().getModelRenderer()
            .renderModel(ms.last(), vc, state, model, 1f, 1f, 1f, light,
                OverlayTexture.NO_OVERLAY, net.neoforged.neoforge.client.model.data.ModelData.EMPTY,
                RenderType.cutoutMipped());
        ms.popPose();
    }

    /** 官方角度公式（含相位）：AnimationTickHolder 渲染时钟 * speed * 3/10 + rotationOffset，模 360 转弧度。 */
    private static float getAngleForBe(ProgrammableTransmissionBlockEntity be, Direction.Axis axis, float speed) {
        float time = AnimationTickHolder.getRenderTime(be.getLevel());
        float offset = rotationOffset(be.getBlockState(), axis, be.getBlockPos());
        return ((time * speed * 3f / 10f + offset) % 360f) / 180f * (float) Math.PI;
    }

    /** 官方相位复刻（KineticBlockEntityVisual.rotationOffset）：棋盘格 22.5°/0°。 */
    private static float rotationOffset(BlockState state, Direction.Axis axis, BlockPos pos) {
        return shouldOffset(axis, pos) ? 22.5f : 0f;
    }

    private static boolean shouldOffset(Direction.Axis axis, BlockPos pos) {
        int x = (axis == Direction.Axis.X) ? 0 : pos.getX();
        int y = (axis == Direction.Axis.Y) ? 0 : pos.getY();
        int z = (axis == Direction.Axis.Z) ? 0 : pos.getZ();
        return ((x + y + z) % 2) == 0;
    }
}
