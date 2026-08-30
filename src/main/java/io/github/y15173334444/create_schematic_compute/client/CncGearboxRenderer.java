package io.github.y15173334444.create_schematic_compute.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import io.github.y15173334444.create_schematic_compute.blocks.CncGearboxBlock;
import io.github.y15173334444.create_schematic_compute.blocks.CncGearboxBlockEntity;
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
 * 数控齿轮箱渲染器（vanilla fallback）：仅在 Flywheel 不可用时渲染两端传动轴
 * （前端/后端各一，独立旋转体）。角度用**官方的 AnimationTickHolder 渲染时钟**
 * + 官方相位公式（renderTime * speed * 3/10 + rotationOffset，mod 360），与官方
 * 轴在任意时刻的相位完全一致。Flywheel 可用时由 {@link CncGearboxVisual} 接管。
 * CNC gearbox renderer (vanilla fallback): only renders when Flywheel is
 * unavailable — two independent shaft-end bodies. Uses the OFFICIAL
 * AnimationTickHolder render clock and the official phase formula
 * (renderTime * speed * 3/10 + rotationOffset, mod 360), so the phase matches
 * official shafts exactly. When Flywheel is available, {@link CncGearboxVisual}
 * takes over.
 */
public class CncGearboxRenderer implements BlockEntityRenderer<CncGearboxBlockEntity> {

    public CncGearboxRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(CncGearboxBlockEntity be, float partialTick, PoseStack ms,
                       MultiBufferSource buffer, int light, int overlay) {
        if (be.getLevel() == null || be.isRemoved())
            return;
        // Flywheel 接管时跳过 vanilla 路径（官方 renderSafe 同款检查）
        if (VisualizationManager.supportsVisualization(be.getLevel()))
            return;
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof CncGearboxBlock))
            return;

        Minecraft mc = Minecraft.getInstance();
        BakedModel front = CncGearboxVisual.FRONT_SHAFT.get();
        BakedModel rear = CncGearboxVisual.REAR_SHAFT.get();
        BakedModel missing = mc.getModelManager().getMissingModel();
        if (front == missing || rear == missing)
            return;

        Direction.Axis axis = state.getValue(CncGearboxBlock.HORIZONTAL_AXIS);
        float angle = getAngleForBe(be, axis);

        renderShaft(ms, buffer, state, front, axis, angle, light);
        renderShaft(ms, buffer, state, rear, axis, angle, light);
    }

    private static void renderShaft(PoseStack ms, MultiBufferSource buffer, BlockState state,
                                    BakedModel model, Direction.Axis axis, float angle, int light) {
        ms.pushPose();
        ms.translate(0.5, 0.5, 0.5);
        if (axis == Direction.Axis.X)
            ms.mulPose(new Quaternionf().rotateX(angle));
        else
            ms.mulPose(new Quaternionf().rotateZ(angle));
        ms.translate(-0.5, -0.5, -0.5);

        VertexConsumer vc = buffer.getBuffer(RenderType.cutoutMipped());
        Minecraft.getInstance().getBlockRenderer().getModelRenderer()
            .renderModel(ms.last(), vc, state, model, 1f, 1f, 1f, light,
                OverlayTexture.NO_OVERLAY, net.neoforged.neoforge.client.model.data.ModelData.EMPTY,
                RenderType.cutoutMipped());
        ms.popPose();
    }

    /** 官方角度公式（含相位）：AnimationTickHolder 渲染时钟 * speed * 3/10 + rotationOffset，模 360 转弧度。 */
    private static float getAngleForBe(CncGearboxBlockEntity be, Direction.Axis axis) {
        float time = AnimationTickHolder.getRenderTime(be.getLevel());
        float offset = rotationOffset(be.getBlockState(), axis, be.getBlockPos());
        return ((time * be.getSpeed() * 3f / 10f + offset) % 360f) / 180f * (float) Math.PI;
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
