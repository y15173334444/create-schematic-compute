package io.github.y15173334444.create_schematic_compute.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.y15173334444.create_schematic_compute.SchematicCompute;
import io.github.y15173334444.create_schematic_compute.blocks.CncGearboxBlock;
import io.github.y15173334444.create_schematic_compute.blocks.CncGearboxBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;

/**
 * 数控齿轮箱渲染器：两端传动轴从 baked 箱体模型拆出，按官方
 * {@code KineticBlockEntityRenderer} 的角度公式
 * （{@code angle = renderTime * speed * 3/10 % 360}，renderTime = 游戏时间 + partialTick）
 * 绕旋转轴（HORIZONTAL_AXIS）旋转 —— speed 由服务端动力学网络同步，因此轴与外部
 * 传动轴视觉同步（同速同向，负速反向）。
 * CNC gearbox renderer: the shaft stubs are split out of the baked casing model and
 * rotated around the kinetic axis (HORIZONTAL_AXIS) using the official
 * {@code KineticBlockEntityRenderer} angle formula
 * ({@code angle = renderTime * speed * 3/10 % 360}, renderTime = game time + partialTick).
 * speed is synced from the server-side kinetic network, so the shaft spins in sync
 * with external shafts (same speed/direction; negative speed reverses).
 */
public class CncGearboxRenderer implements BlockEntityRenderer<CncGearboxBlockEntity> {

    public static final ModelResourceLocation SHAFT_MODEL = ModelResourceLocation.standalone(
        ResourceLocation.fromNamespaceAndPath(SchematicCompute.MOD_ID, "block/cnc_shaft"));

    public CncGearboxRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(CncGearboxBlockEntity be, float partialTick, PoseStack ms,
                       MultiBufferSource buffer, int light, int overlay) {
        if (be.getLevel() == null || be.isRemoved())
            return;
        BlockState state = be.getBlockState();
        if (!(state.getBlock() instanceof CncGearboxBlock))
            return;

        Minecraft mc = Minecraft.getInstance();
        BakedModel shaftModel = mc.getModelManager().getModel(SHAFT_MODEL);
        if (shaftModel == mc.getModelManager().getMissingModel())
            return;

        Direction.Axis axis = state.getValue(CncGearboxBlock.HORIZONTAL_AXIS);
        float angle = getAngleForBe(be, partialTick, axis);

        ms.pushPose();
        // 绕方块中心旋转（官方 rotateCentered 等价）
        ms.translate(0.5, 0.5, 0.5);
        if (axis == Direction.Axis.X)
            ms.mulPose(new Quaternionf().rotateX(angle));
        else
            ms.mulPose(new Quaternionf().rotateZ(angle));
        ms.translate(-0.5, -0.5, -0.5);

        VertexConsumer vc = buffer.getBuffer(RenderType.cutoutMipped());
        mc.getBlockRenderer().getModelRenderer()
            .renderModel(ms.last(), vc, state, shaftModel, 1f, 1f, 1f, light,
                OverlayTexture.NO_OVERLAY, net.neoforged.neoforge.client.model.data.ModelData.EMPTY,
                RenderType.cutoutMipped());
        ms.popPose();
    }

    /** 官方角度公式：renderTime(游戏时间+partialTick) * speed * 3/10，模 360 转弧度。
     *  Official angle formula: renderTime(game time + partialTick) * speed * 3/10, mod 360 -> rad. */
    private static float getAngleForBe(CncGearboxBlockEntity be, float partialTick, Direction.Axis axis) {
        float time = be.getLevel().getGameTime() + partialTick;
        return ((time * be.getSpeed() * 3f / 10f) % 360f) / 180f * (float) Math.PI;
    }
}
